# ByteMe

## Phân tầng theo nhiệm vụ (Package-by-Layer)

Mã nguồn được tổ chức theo nhiệm vụ của từng tầng kỹ thuật; các quy tắc phân tầng được `src/test/java/org/example/ArchitectureTest.java` (ArchUnit) kiểm tra tự động.
| Tầng | Package | Trách nhiệm | Được phép phụ thuộc |
| --- | --- | --- | --- |
| Bảo mật | `config.SecurityConfiguration`, `platform.security` | Xác thực JWT, phân quyền endpoint theo vai trò, giới hạn đăng nhập | Spring Security |
| API / Web | `controller`, `dto` | Tiếp nhận và điều hướng HTTP/JSON request, `@Valid` kiểm tra dữ liệu đầu vào, bóc tách `CurrentUser` | Service, DTO, entity enum, `shared.api`, Spring Web |
| Nghiệp vụ | `service` | Quy tắc nghiệp vụ, ranh giới transaction (`@Transactional`), ném lỗi nghiệp vụ (`BusinessException` ở Food/Cart/Order; `AuthFailure`, `DuplicateAccountException`, `AccountPersistenceException` ở Auth) | Repository, Entity, DTO, các Service khác. **Không** import Spring Web/HTTP, Servlet, Spring Data, JDBC, JPA, Hibernate |
| Truy cập dữ liệu | `repository`, `entity` | Spring Data JPA (CRUD, custom query, `CartRepository.saveChanges`), ánh xạ thực thể CSDL | JPA/Hibernate, MySQL |
| Dùng chung | `common`, `shared.api` | `BusinessException`, `CurrentUser`, `ApiError`, `GlobalErrors`; `AuthenticatedUser`, `Role` | — |

Lỗi nghiệp vụ: service của Food/Cart/Order ném `BusinessException(type, code, message)`; `GlobalErrors` đổi `INVALID/FORBIDDEN/NOT_FOUND/CONFLICT` thành 400/403/404/409 với body `{status, code, message, path, fieldErrors}`. Auth giữ handler riêng `AuthErrors` cho `AuthFailure` và `DuplicateAccountException`.

## Sơ đồ kiến trúc

```mermaid
flowchart TB
    Client["Ứng dụng khách / API Tester / k6"]
    Docs["/openapi.yaml + Swagger UI"]
    Client -->|"HTTP / JSON"| Security
    Docs -.-> Client

    Security["Spring Security filter chain<br/>JWT RS256 + phân quyền theo vai trò<br/>LoginIpFilter cho /api/auth/token"]

    subgraph App["Ứng dụng Spring Boot — Kiến trúc phân tầng (Package-by-Layer)"]
        direction TB
        subgraph Controllers["Tầng API / Web: org.example.controller"]
            AuthAPI["AuthController"]
            FoodAPI["FoodController"]
            CartAPI["CartController"]
            OrderAPI["OrderController"]
        end

        subgraph Services["Tầng Nghiệp vụ: org.example.service"]
            AuthCore["RegisterService / LoginService / AuthValidation"]
            FoodCore["FoodService"]
            CartCore["CartService"]
            OrderCore["OrderService"]
        end

        subgraph Repositories["Tầng Dữ liệu: org.example.repository"]
            AuthRepo["AccountRepository / MySqlAccountRepository / UserRepository"]
            FoodRepo["FoodRepository / CategoryRepository"]
            CartRepo["CartRepository"]
            OrderRepo["OrderRepository"]
        end

        subgraph Entities["Tầng Thực thể & DTO: org.example.entity / org.example.dto"]
            EntityModel["User, Food, Category, Cart, CartItem, Order, OrderItem, OrderStatus..."]
            DtoModel["FoodDto, CartDto, OrderDto, LoginRequest, RegisterRequest..."]
        end

        Platform["platform: BCryptPasswordHasher, LocalRsaTokenIssuer, LoginRateLimiter"]
        Common["common: BusinessException, GlobalErrors, CurrentUser"]

        Controllers --> Services
        Services --> Repositories
        Services --> DtoModel
        Repositories --> EntityModel
        AuthCore -.->|"PasswordHasher / TokenIssuer"| Platform
        CartCore -->|"getAvailableFood, findByIds"| FoodCore
        OrderCore -->|"getItemsForCheckout, clearCart"| CartCore
        OrderCore -->|"reserveStock, releaseStock"| FoodCore
    end

    Security --> Controllers

    DB[("MySQL 8.4: users, categories, foods, carts, cart_items, orders, order_items")]
    Repositories --> DB
```

Mũi tên giữa các service là lời gọi Java trực tiếp. Các method được gọi đều là `@Transactional` với propagation mặc định (`REQUIRED`), nên khi `OrderService` gọi `CartService`/`FoodService` thì tất cả tham gia **cùng một transaction**: commit cùng nhau hoặc rollback cùng nhau.

Quy tắc ranh giới phân tầng:

- `CartItem` chỉ lưu `foodId`; Cart lấy thông tin món để hiển thị qua `FoodService.findByIds` và kiểm tra món còn bán qua `FoodService.getAvailableFood`.
- `OrderItem` chỉ lưu `foodId` cùng snapshot `foodName`/`unitPrice`; Order không phụ thuộc entity `Food`.
- Controller chỉ gọi qua Service; không bao giờ gọi trực tiếp Repository. Service không phụ thuộc Controller.

## Sequence UC13 — Tạo đơn hàng

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Khách hàng
    participant Sec as Security filter (JWT)
    participant API as OrderController
    participant Order as OrderService
    participant Cart as CartService
    participant Food as FoodService
    participant Repo as OrderRepository
    participant DB as MySQL

    Customer->>Sec: POST /api/orders + Bearer JWT
    Sec->>Sec: Kiểm tra chữ ký, hạn, issuer/audience và role CUSTOMER
    alt Token thiếu/sai/hết hạn
        Sec-->>Customer: 401 UNAUTHENTICATED
    else Không phải CUSTOMER
        Sec-->>Customer: 403 FORBIDDEN
    else Hợp lệ
        Sec->>API: AuthenticatedUser(userId, role)
        API->>API: @Valid: orderType, paymentMethod bắt buộc, độ dài trường (sai: 400 VALIDATION_ERROR)
        API->>API: CurrentUser.id(user) — subject phải là ID số (nếu không: 403)
        API->>Order: create(customerId, request) — @Transactional bắt đầu
        Order->>Order: quy tắc DINE_IN/DELIVERY: số bàn/thông tin giao hàng, phương thức thanh toán
        alt Dữ liệu không hợp lệ
            Order-->>Customer: 400 VALIDATION_ERROR (chưa có truy vấn DB nào)
        else Hợp lệ
            Order->>Cart: getItemsForCheckout(customerId)
            Cart->>DB: Khóa hàng users theo customerId, rồi đọc giỏ
            Cart-->>Order: foodId → quantity theo thứ tự dòng giỏ (rỗng nếu chưa có giỏ)
            alt Chưa có giỏ hoặc giỏ rỗng
                Order-->>Customer: 400 EMPTY_CART (rollback)
            else Có món
                loop Mỗi món theo foodId tăng dần
                    Order->>Food: reserveStock(foodId, quantity)
                    Food->>DB: UPDATE foods WHERE id = ? AND ACTIVE AND stock đủ
                    alt Không có món / ngừng bán / thiếu kho
                        Food-->>Customer: 404 FOOD_NOT_FOUND / 409 FOOD_UNAVAILABLE / 409 INSUFFICIENT_STOCK (rollback toàn bộ)
                    else Đủ điều kiện
                        Food->>DB: Đọc snapshot tên và giá của hàng đã cập nhật
                        Food-->>Order: tên + giá hiện tại (snapshot)
                    end
                end
                Order->>Repo: save(Order PENDING + OrderItem snapshot)
                Repo->>DB: INSERT orders, order_items
                Order->>Cart: clearCart(customerId)
                Cart->>DB: DELETE cart_items khi flush
                Order->>DB: COMMIT
                Order-->>API: OrderDto (items, totalAmount)
                API-->>Customer: 201 Created + Location /api/orders/{id}
            end
        end
    end
```

Giá và tổng tiền do server tính từ dữ liệu món đọc trong transaction; client không gửi danh sách món, giá hay `userId`. Tổng tiền không lưu trên `orders` mà tính từ `order_items` (`OrderDto.totalAmount` = tổng `unitPrice × quantity`). Mọi lỗi trong transaction, kể cả lỗi hệ thống (500), đều rollback trước khi trả về.

Giỏ được tạo ở lần thêm món đầu tiên (`addToCart`). `GET /api/cart` khi chưa có giỏ trả `200` với `id: null`, `items: []` và không ghi gì vào database. Sửa/xóa dòng giỏ khi chưa có giỏ trả `404 CART_ITEM_NOT_FOUND`.

Checkout và các thao tác ghi giỏ dùng chung khóa hàng `users`, kể cả lần đầu tạo giỏ. Admin lấy khóa ghi hàng `foods` trước khi đọc để sửa hoặc ngừng bán món. Khi admin truyền `stockQuantity`, giá trị đó là mức tồn kho tuyệt đối tại thời điểm cập nhật. MySQL có thể vẫn báo tranh chấp khóa và ứng dụng trả `409 CONCURRENT_UPDATE`. Xem [khảo sát trước cải tiến](phase2/checkout-before.md) để đối chiếu hành vi Pha 1.

## Sequence hủy đơn (UC15, UC17)

```mermaid
sequenceDiagram
    autonumber
    actor User as Khách / Nhân viên
    participant API as OrderController
    participant Order as OrderService
    participant Food as FoodService
    participant DB as MySQL

    User->>API: DELETE /api/orders/{id} (CUSTOMER) hoặc PUT status CANCELLED (EMPLOYEE)
    API->>Order: cancel(customerId, id) / updateStatus(employeeId, id, CANCELLED)
    Order->>DB: SELECT orders WHERE id = ? FOR UPDATE
    Order->>Order: Kiểm tra tồn tại (404), chủ đơn với khách (403), bước chuyển hợp lệ (409)
    loop Mỗi dòng đơn theo foodId tăng dần
        Order->>Food: releaseStock(foodId, quantity)
        Food->>DB: UPDATE foods SET stock = stock + quantity
    end
    Order->>DB: COMMIT (UPDATE foods, UPDATE orders status = CANCELLED, cancelled_at, updated_at)
    Order-->>User: 200 + đơn sau khi hủy (giữ đơn và chi tiết làm lịch sử)
```

`cancel`, `confirm` và `updateStatus` dùng chung khóa hàng Order. Sau khi kiểm quyền, yêu cầu hủy đơn đã `CANCELLED` trả `200` với đơn đã lưu và giữ nguyên kho lẫn timestamp. Hoàn kho áp dụng cả cho món đã ngừng bán.

## Trạng thái theo hình thức phục vụ

```mermaid
flowchart LR
    P["PENDING"] -->|"POST /confirm"| C["CONFIRMED"] --> R["PROCESSING"]
    R -->|"DINE_IN"| Done["COMPLETED"]
    R -->|"DELIVERY"| S["SHIPPING"] --> Done
    P -->|"Khách hoặc nhân viên hủy"| X["CANCELLED"]
    C -->|"Nhân viên hủy"| X
    R -->|"Nhân viên hủy"| X
```

| Trạng thái hiện tại | Nhân viên được chuyển sang | Khách được chuyển sang |
| --- | --- | --- |
| PENDING | CONFIRMED, CANCELLED | CANCELLED |
| CONFIRMED | PROCESSING, CANCELLED | — |
| PROCESSING | CANCELLED; DINE_IN → COMPLETED; DELIVERY → SHIPPING | — |
| SHIPPING | COMPLETED | — |
| COMPLETED, CANCELLED | — | — |

Xác nhận lưu `confirmed_by`/`confirmed_at`; hủy lưu `cancelled_at`, giữ thông tin xác nhận nếu có; hoàn thành lưu `completed_at`. Thời gian do `OrderService` gán theo UTC (độ chính xác micro giây); thứ tự các mốc do service bảo đảm, schema không có CHECK. CASH chỉ cho DINE_IN, COD chỉ cho DELIVERY, BANK_TRANSFER cho cả hai. `tableNumber` là nhãn bàn lưu trong đơn; giai đoạn này chưa có đặt bàn hay quản lý bàn.
