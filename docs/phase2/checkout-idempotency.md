# Idempotency-Key cho checkout (`POST /api/orders`): triển khai

Tài liệu này ghi lại phần đã code theo mục 2.4 của kế hoạch Pha 2 và mục "Hợp đồng idempotency checkout" trong `checkout-design.md`. Điểm khác so với bản thiết kế được ghi rõ ở cuối.

## Hợp đồng HTTP

| Tình huống | Kết quả |
| --- | --- |
| Không gửi header | Hành vi cũ: checkout bình thường, gửi lại sau khi giỏ đã dọn trả `400 EMPTY_CART` |
| Header sai định dạng (ngoài `[A-Za-z0-9._-]{1,64}`, kể cả chuỗi rỗng) | `400 INVALID_IDEMPOTENCY_KEY`, không chạm DB |
| Key mới, checkout thành công | `201` + `Location` + `OrderDto`; key được lưu trong cùng transaction với đơn |
| Cùng khách + cùng key + cùng payload | `201` + `Location` + đúng body lần đầu; không đọc giỏ, không trừ kho, không tạo đơn |
| Cùng khách + cùng key + payload khác | `409 IDEMPOTENCY_KEY_REUSED`; đơn, kho, giỏ không đổi |
| Nhiều request cùng key đến đồng thời | Tuần tự hóa bằng khóa hàng `users`; một request tạo đơn, các request sau replay cùng body |
| Checkout lỗi (EMPTY_CART, INSUFFICIENT_STOCK, lỗi hệ thống...) | Rollback toàn bộ, key không được lưu; có thể retry cùng key |
| Trùng UNIQUE ngoài dự kiến | `409 CONCURRENT_UPDATE` (không phải 500); transaction đã rollback, retry sẽ replay |
| Cùng key, khách khác | Hai phạm vi độc lập (`UNIQUE(user_id, idempotency_key)`) |
| Đơn bị hủy sau checkout | Replay vẫn trả snapshot lúc checkout (`PENDING`); `GET /api/orders/{id}` trả trạng thái hiện tại |

"Cùng payload" được so bằng SHA-256 của request sau khi đã trim và áp quy tắc `orderType` (`OrderService.applyOrderTypeRules`). Các trường được mã hóa theo thứ tự cố định, có tiền tố độ dài và ký hiệu null riêng, nên thứ tự property JSON hay khoảng trắng đầu/cuối không làm đổi hash, còn đổi `orderType`, `paymentMethod`, `tableNumber`, thông tin giao hàng hoặc `note` thì có.

## Luồng trong một transaction

```
OrderController (@RequestHeader Idempotency-Key)
 └─ OrderService.create(customerId, request, key)
     1. normalizeKey(key)                   → 400 nếu sai, trước khi chạm DB
     2. applyOrderTypeRules + fingerprint   → request_hash
     3. CartService.lockForCheckout         → SELECT ... FROM users WHERE id = ? FOR UPDATE
     4. CheckoutIdempotencyService.findReplay
          có bản ghi, hash khớp   → trả OrderDto đã lưu (kết thúc)
          có bản ghi, hash khác   → 409 IDEMPOTENCY_KEY_REUSED
     5. đọc giỏ → trừ kho theo foodId tăng dần → lưu Order/OrderItem → dọn giỏ   (luồng đã có)
     6. CheckoutIdempotencyService.remember → INSERT checkout_idempotency (order_id, response_json)
     commit: đơn, kho, giỏ và key cùng commit hoặc cùng rollback
```

Bước 4 là đọc thường, không dùng `FOR UPDATE`, và đây là chủ ý: khóa `users` ở bước 3 đã tuần tự hóa các request của cùng khách. Vì đây là lần đọc nhất quán đầu tiên của transaction, MySQL tạo snapshot *sau* khi có khóa, nên request đến sau luôn thấy key mà request trước đã commit. Nếu dùng `FOR UPDATE` trên một key chưa tồn tại, InnoDB sẽ lấy gap lock; hai khách khác nhau có thể cùng giữ một gap rồi deadlock khi cùng INSERT. `UNIQUE(user_id, idempotency_key)` vẫn là hàng rào cuối cùng ở tầng DB.

## Thay đổi mã nguồn

| File | Nội dung |
| --- | --- |
| `db/schema.sql`, `kaggle/dataset/schema.sql` | Bảng `checkout_idempotency` |
| `db/migration/phase2-checkout-idempotency.sql` | Migration cho DB đã tạo từ Pha 1 (chạy lại được nhiều lần) |
| `entity/CheckoutIdempotency.java`, `repository/CheckoutIdempotencyRepository.java` | Native `SELECT`/`INSERT` (đúng luật ArchUnit: persistence nằm ở repository) |
| `service/CheckoutIdempotencyService.java` | Kiểm tra key, fingerprint, replay/lưu `OrderDto` (JSON) |
| `service/OrderService.java` | Thêm `create(customerId, request, key)`; `create(customerId, request)` cũ gọi sang với `key = null` |
| `service/CartService.java` | `lockForCheckout` (`Propagation.MANDATORY`) dùng chung khóa hàng `users` |
| `controller/OrderController.java` | `@RequestHeader(name = "Idempotency-Key", required = false)` |
| `common/GlobalErrors.java` | Trùng `uq_checkout_idempotency_user_key` → `409 CONCURRENT_UPDATE` |
| `static/openapi.yaml` | Header, các lỗi mới và hành vi replay |

Bảng dùng `ON DELETE CASCADE` theo `users` và `orders`, nên các script dọn fixture đang xóa `orders`/`users` (`scripts/test-endpoints.py`, `OrderIntegrationTest`) vẫn chạy được. `response_json` là `MEDIUMTEXT`, không dùng kiểu `JSON`, vì cột JSON của MySQL lưu số thập phân dưới dạng double và làm mất scale của tiền (`65000.00`).

## Áp dụng vào DB đang có

`ddl-auto: none` và `CREATE TABLE IF NOT EXISTS` chỉ tạo bảng khi khởi tạo volume mới. Với DB Docker/Kaggle đã có dữ liệu Pha 1:

```bash
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < src/main/resources/db/migration/phase2-checkout-idempotency.sql
```

## Kiểm thử

- `OrderServiceTest`: key sai định dạng, fingerprint, thứ tự khóa → tra key → dọn giỏ → lưu key, replay không chạm giỏ/kho, payload khác bị từ chối, không key giữ luồng cũ.
- `OrderWebTest`: header được truyền vào service, mã lỗi 400/409, trùng UNIQUE ra 409 thay vì 500.
- `OrderIntegrationTest` (Testcontainers MySQL), tương ứng ma trận kiểm thử mục 5:
  - Ca 2: `sequentialRetryWithSameKeyAndPayloadCreatesOneOrder`, `concurrentRequestsWithSameKeyCreateOneOrderAndAllGetIt` (8 luồng)
  - Ca 3: `reusingKeyWithDifferentPayloadIsRejectedAndChangesNothing`
  - Ca 4: `retryAfterLostResponseReturnsCommittedOrderEvenIfCartChanged`
  - Ca 7 (có key): `failureAfterStoringKeyRollsBackOrderStockCartAndKey`, `failedCheckoutDoesNotStoreKeySoTheSameKeyCanSucceedLater`
  - Bổ sung: `replayReturnsOriginalResponseAfterCancellation`, `sameKeyForDifferentCustomersIsIndependent`, `invalidKeyIsRejectedWithoutWrites`
- `scripts/test-endpoints.py`: nhóm "Idempotency" chạy trên API thật.

## Khác với `checkout-design.md`

- **Header là tùy chọn, không bắt buộc.** Mục 2.4 của kế hoạch cho phép chọn tùy chọn để giữ tương thích với bộ test Pha 1, workload k6 và lượt A của benchmark A/B. Nếu nhóm chốt bắt buộc, chỉ cần đổi `normalizeKey` để từ chối `null` và đổi `required: true` trong OpenAPI.
- **Không lưu `cart_snapshot`.** Body `OrderDto` đã lưu đã chứa danh sách món và số lượng của lần checkout đầu, đủ cho việc replay.
