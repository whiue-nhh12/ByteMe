# Thiết kế checkout an toàn và retry cho Pha 2

## Trạng thái quyết định

Phần idempotency đã được triển khai, xem `checkout-idempotency.md`. Đây là đề xuất thiết kế cụ thể để nhóm duyệt trước khi T03 trở đi sửa code. Các hợp đồng HTTP mới (Idempotency-Key bắt buộc, replay hủy là 200) cần được TV1/TV3 xác nhận và cập nhật OpenAPI trước khi tích hợp. Tài liệu giữ kiến trúc phân tầng hiện tại, một backend instance và MySQL InnoDB; chưa đề xuất thêm dịch vụ ngoài phạm vi.

## Mục tiêu và ngoài phạm vi

- Không chấp nhận tổng lượng checkout vượt tồn kho hợp lệ.
- Một giỏ không bị tiêu thụ hai lần hoặc xóa mất thay đổi đang chạy đồng thời.
- Cùng user/key và cùng payload tạo tối đa một đơn; retry sau commit trả lại response đã lưu.
- Cùng key nhưng payload khác bị từ chối trước khi thay đổi kho.
- Hủy đơn đồng thời chỉ hoàn kho một lần; các chuyển trạng thái khác vẫn tuân theo máy trạng thái hiện tại.
- Lỗi bất kỳ trước commit rollback stock, Order, OrderItem, giỏ và idempotency record cùng nhau.

Không thêm Redis, message queue, reservation có thời hạn, distributed transaction hoặc backend instance thứ hai. Không dùng cache thực đơn làm nguồn quyết định tồn kho.

## Kiến trúc đề xuất và lý do

Giữ Controller → Service → Repository → MySQL InnoDB. Service điều phối nghiệp vụ và ranh giới `@Transactional`; Repository nắm các thao tác khóa, câu SQL cập nhật tồn kho và truy vấn kết quả hiện tại. Cách này tuân thủ `ArchitectureTest`, dùng MySQL làm điểm đồng bộ chung cho mọi đường ghi và phù hợp cấu hình một backend instance của thí nghiệm.

Transaction hiện tại gom các thao tác nhưng `@Transactional` tự nó không khóa các lần đọc/ghi cạnh tranh. Cập nhật tồn kho bằng biểu thức có điều kiện ở DB bảo đảm điều kiện đủ kho được đánh giá tại thời điểm ghi. Khóa hàng user tuần tự hóa thao tác trên một giỏ; khóa hàng Order tuần tự hóa transition/hủy. Các lựa chọn phải đo lại vì khóa có thể làm tăng latency hoặc giảm throughput.

## Quy tắc khóa và thứ tự

### Giỏ hàng và checkout

Lấy khóa ghi trên hàng `users` của customer trước khi đọc Cart hoặc idempotency record. Hàng user đã tồn tại kể cả khi khách chưa có Cart, nên đây là mutex DB ổn định. `OrderService.create` và mọi thao tác ghi giỏ (`addToCart`, `updateCartItem`, `removeCartItem`, `clearCart`) phải tuân thủ cùng một khóa.

Khóa được giữ trong transaction hiện tại đến commit/rollback; không dùng `REQUIRES_NEW`. Nếu một service được gọi từ transaction checkout, propagation `REQUIRED` phải tham gia transaction ngoài. Service khóa chỉ gọi interface Repository; không import Spring Data/JPA/JDBC.

Sau khóa user, reserve các hàng `foods` theo `foodId` tăng dần. Các đường chỉnh sửa giỏ không giữ food lock qua transaction. Các đường chỉnh sửa món/admin khóa food trước khi đọc entity cần sửa.

### Hủy và transition Order

Đọc hàng Order bằng locking read trước khi kiểm tra quyền và trạng thái; giữ khóa đến commit. Áp dụng cho `cancel`, `confirm` và `updateStatus`, vì chỉ khóa cancel vẫn để confirm/update dựa trên trạng thái cũ ghi đè kết quả hủy.

Sau khi khóa Order, nếu chuyển sang CANCELLED thì reserve/restore các hàng Food theo `foodId` tăng dần. Mỗi transaction chỉ chuyển trạng thái sau khi đã xác nhận quy tắc hợp lệ; thay đổi trạng thái và hoàn kho cùng commit hoặc cùng rollback.

### Giảm deadlock

- Checkout: `users` → `foods` tăng dần → ghi `orders`/`order_items` → xóa cart_items, tất cả trong một transaction.
- Sửa giỏ: `users` → Cart/cart_items → đọc Food cần xác nhận món, giữ transaction ngắn.
- Admin cập nhật món: `foods` trước khi load entity và sửa; không khóa `users` hoặc `orders`.
- Hủy/transition: `orders` → `foods` tăng dần.

Không có đường nghiệp vụ nào được giữ Food lock rồi mới lấy User/Order lock. FK và kế hoạch flush của Hibernate vẫn có thể phát sinh khóa bổ sung; cần quan sát deadlock thực tế trên MySQL và cập nhật thứ tự nếu log cho thấy chu trình khác.

## Cập nhật tồn kho

### Trừ kho

Repository thực hiện một câu lệnh có điều kiện tương đương:

```sql
UPDATE foods
SET stock_quantity = stock_quantity - :quantity
WHERE id = :foodId
  AND status = 'ACTIVE'
  AND stock_quantity >= :quantity;
```

Validate `quantity > 0`. Chỉ `affected rows = 1` mới là reserve thành công. `0` nghĩa là điều kiện không còn đúng; nếu cần phân biệt không tồn tại/ngừng bán/hết hàng, đọc trạng thái hiện tại và ánh xạ theo mã lỗi đã thống nhất. Không lấy entity tồn kho đã đọc trước để quyết định kết quả.

### Snapshot tên và giá

OrderItem phải lưu `foodName`/`unitPrice` tại lần checkout thành công. Lấy snapshot nhất quán trong transaction sau khi kiểm tra/cập nhật stock, bằng projection/locking read phù hợp; không để entity stale trong persistence context trả dữ liệu cũ. Nếu entity Food đã được load trước bulk update, refresh đúng entity trong Repository sau khi đã flush thay đổi liên quan. Không clear toàn bộ persistence context vì có thể bỏ thay đổi Order/Cart chưa flush.

### Hoàn kho

```sql
UPDATE foods
SET stock_quantity = stock_quantity + :quantity
WHERE id = :foodId;
```

Hoàn kho cho món `INACTIVE` vẫn hợp lệ. `affected rows != 1`, quantity không dương hoặc vượt giới hạn kiểu INT phải làm transaction thất bại; không âm thầm bỏ qua.

## Hủy và vòng đời trạng thái

| Trạng thái hiện tại | Khách | Nhân viên |
| --- | --- | --- |
| PENDING | CANCELLED | CONFIRMED hoặc CANCELLED |
| CONFIRMED | Không cho hủy | PROCESSING hoặc CANCELLED |
| PROCESSING | Không cho hủy | CANCELLED; DINE_IN → COMPLETED; DELIVERY → SHIPPING |
| SHIPPING | Không cho hủy | COMPLETED |
| COMPLETED | Không cho hủy | Không chuyển |
| CANCELLED | Yêu cầu hủy lặp trả đơn đã hủy sau kiểm tra chủ đơn | Yêu cầu CANCELLED lặp trả đơn đã hủy |

Đối với lần chuyển đầu sang CANCELLED: khóa Order, kiểm tra quyền/trạng thái, đặt `status`, `cancelledAt`, `updatedAt`, hoàn từng OrderItem một lần rồi commit. Nếu hoàn kho lỗi, rollback cả cập nhật Order. Request lặp sau đó không gọi `releaseStock`.

Customer vẫn chỉ được xem/hủy đơn của mình. Phải kiểm tra quyền trước khi trả kết quả replay cho khách để không tiết lộ đơn của người khác. Role endpoint tiếp tục được bảo vệ bởi Spring Security. Không thay đổi quy tắc shipping/completion khác.

## Hợp đồng idempotency checkout

### Request và phạm vi key

`POST /api/orders` yêu cầu header `Idempotency-Key`. Key dài 1–64 ký tự ASCII thuộc `[A-Za-z0-9._-]`. Phạm vi duy nhất là `(user_id, idempotency_key)`; user lấy từ JWT, không nhận trong body. Client tạo key mới khi bắt đầu ý định đặt hàng mới và dùng lại key đó cho tất cả retry của cùng ý định.

### Chuẩn hóa body và fingerprint

Chạy cùng một hàm chuẩn hóa hiện dùng cho quy tắc DINE_IN/DELIVERY trước khi hash và tạo Order. Canonical encoding theo thứ tự trường cố định, thể hiện rõ null/giá trị, rồi SHA-256. Các trường gồm `orderType`, `tableNumber`, `recipientName`, `shippingPhone`, `shippingAddress`, `paymentMethod`, `note` sau khi trim theo quy tắc hiện tại. Không hash raw JSON, `toString()` hoặc thứ tự property JSON.

Fingerprint chỉ định danh payload checkout; không đọc giỏ để xác định retry. Snapshot giỏ được lưu riêng cùng key thành công. Nếu khách sửa giỏ nhưng gửi lại cùng key và body thì server replay đơn đầu, không mua giỏ mới. Khi muốn checkout giỏ mới, client phải tạo key mới. API hiện tại không mang cart version, nên không thể nhận biết một ý định mới chỉ từ việc giỏ thay đổi.

### Lưu bền vững và transaction

Đề xuất bảng `checkout_idempotency` với ít nhất `user_id`, `idempotency_key`, `request_hash`, `cart_snapshot`, `order_id`, `response_json`, `created_at`; ràng buộc `UNIQUE(user_id, idempotency_key)` và FK cần thiết. Key dùng collation phân biệt hoa thường; hash cố định 64 ký tự. Không lưu bản ghi PROCESSING độc lập.

Trong cùng transaction DB: khóa user → tìm key bằng current/locking read → nếu chưa có, đọc snapshot giỏ → reserve stock → tạo Order/OrderItem → clear cart → serialize response và insert idempotency record → commit. Bản ghi key không được commit tách khỏi đơn. Nếu bất kỳ bước nào lỗi, toàn bộ cùng rollback và key chưa thành công có thể thử lại.

Vì lookup đầu tiên dùng locking/current read, retry không dựa trên consistent snapshot cũ của MySQL REPEATABLE READ. Khóa user làm request cùng user tuần tự hóa; `UNIQUE` vẫn là hàng rào cuối ở DB. Nếu gặp duplicate key ngoài dự kiến, rollback transaction hiện tại; không catch exception rồi tiếp tục query trên transaction rollback-only.

### Hành vi HTTP đề xuất

| Tình huống | Kết quả |
| --- | --- |
| Key thiếu/sai định dạng | `400 INVALID_IDEMPOTENCY_KEY`, trước khi ghi DB |
| Key mới, checkout thành công | `201`, cùng `Location` và body OrderDto; lưu response trong transaction |
| Cùng user/key, cùng fingerprint | Replay `201`, `Location` và body đã lưu; không đọc giỏ, trừ kho hay xóa giỏ lần nữa |
| Cùng user/key, fingerprint khác | `409 IDEMPOTENCY_KEY_REUSED`; không thay đổi Order/kho/giỏ |
| Key giống nhau, user khác | Hai phạm vi độc lập; quyền vẫn căn cứ JWT |
| Checkout thất bại trước commit | Rollback key; lỗi nghiệp vụ hiện tại được trả; client có thể retry cùng key |
| Lock timeout/deadlock | Rollback toàn transaction, `409 CONCURRENT_UPDATE`; client có thể retry cùng key |
| Server commit nhưng client timeout | Retry cùng key trả response thành công đã lưu |
| Đơn được hủy sau checkout | Replay key vẫn trả snapshot response checkout ban đầu; GET Order trả trạng thái hiện tại |

Chỉ lưu key của checkout thành công. Không lưu vĩnh viễn lỗi validation, EMPTY_CART hoặc INSUFFICIENT_STOCK trong pha này; do đó cùng key có thể thử lại sau khi lỗi đã được xử lý. Không xóa key thành công trong pha 2 nếu chưa có chính sách retention và bảo đảm client không retry key cũ.

## Migration và tương thích

`application.yml` đặt Hibernate `ddl-auto: none`; thay `schema.sql` không nâng cấp database đã tồn tại. Commit triển khai phải có migration SQL cho bảng mới và hướng dẫn áp dụng vào DB cũ, đồng thời cập nhật schema dùng cho môi trường mới/Testcontainers/Kaggle. Xác minh constraint và migration mà không xóa Docker volume hay dữ liệu người dùng.

Trước tích hợp, cập nhật `src/main/resources/static/openapi.yaml`, `OrderController`, API tests và client scripts để mô tả header, lỗi và replay. Test đang kỳ vọng hủy lặp trả 409 phải được điều chỉnh theo hợp đồng mới nếu nhóm duyệt replay hủy là 200.

## Ma trận nghiệm thu thiết kế

| Ca | Kỳ vọng |
| --- | --- |
| Nhiều khách tranh stock 1 | Tối đa một đơn chứa đơn vị đó; stock không âm; oversell = 0 |
| Giỏ nhiều món có một món thiếu | Không đơn dở dang; mọi stock ban đầu và giỏ được khôi phục |
| Cùng user/key/body gửi đồng thời | Tối đa một đơn; mọi retry nhận cùng response checkout |
| Cùng key/body khác | 409; stock và số đơn không đổi thêm |
| Timeout client sau commit | Retry trả order cũ dù giỏ đã rỗng |
| Hủy cùng Order đồng thời/lặp | Một transition CANCELLED và một lần hoàn từng dòng |
| Hủy đua confirm/updateStatus | Kết quả phản ánh một thứ tự transition hợp lệ; không lost update |
| Checkout đua sửa giỏ/admin | Không mất cập nhật giỏ; stock không bị ghi đè từ entity cũ |
| Gây lỗi giữa transaction | Stock, Order, OrderItem, Cart và key rollback đồng bộ |

## Trình tự triển khai

1. Chốt tài liệu và hợp đồng với TV1/TV3; khóa SHA BEFORE và kết quả baseline thực tế.
2. Tuần tự hóa theo user cho checkout và mọi thao tác ghi giỏ.
3. Chuyển trừ/hoàn kho sang cập nhật có điều kiện tại DB; lấy snapshot tên/giá nhất quán.
4. Khóa mọi cập nhật Food/admin và Order/state transition theo thứ tự đã nêu.
5. Thêm schema/migration và repository idempotency; chuẩn hóa key/fingerprint.
6. Ghép nhánh replay và ghi response vào transaction checkout; cập nhật API/OpenAPI.
7. Chạy ma trận MySQL tích hợp trên cùng fixture BEFORE/AFTER, rồi mới benchmark Kaggle.

## Thông tin cần nhóm xác nhận trước T03

- Chấp thuận bắt buộc Idempotency-Key ngay trong Pha 2 và grammar/độ dài nêu trên.
- Chấp thuận snapshot giỏ gắn với key thành công; giỏ sửa sau đó cần key mới.
- Chấp thuận replay hủy đơn trả `200` trạng thái CANCELLED sau kiểm tra quyền.
- Chốt timeout khóa, lỗi công khai khi lock timeout, retention key và cách cập nhật DB Kaggle.
- Chốt người sở hữu các đoạn `FoodRepository`/`FoodService` dùng chung với TV1/TV3.
- Chốt SHA BEFORE, môi trường đo và người chạy baseline correctness.
