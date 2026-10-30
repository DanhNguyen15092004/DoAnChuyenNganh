# DoAnChuyenNganh

Test API trực tiếp trong VS Code: [bộ request REST Client](api-tests.http) và [hướng dẫn chạy](docs/rest-client.md).

Skeleton Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11, MySQL 8.0, JPA và Flyway.
Đã bổ sung xác thực email/password, Spring Security + BCrypt, session cookie và phân quyền theo role.
Contract và hướng dẫn seed local: [docs/auth-contract.md](docs/auth-contract.md).
Đăng ký tài khoản sinh viên qua UI/API: [docs/registration.md](docs/registration.md).
Ví dụ CSRF → login → me → API theo role → logout → thử cookie cũ: [docs/auth-session-flow.md](docs/auth-session-flow.md).
Module kỳ thi (draft/câu hỏi/publish/ghi danh/consent): [docs/exam-module.md](docs/exam-module.md).
Lượt thi bắt đầu/tiếp tục, deadline server và xem đề không chứa đáp án:
[docs/attempt-module.md](docs/attempt-module.md). Chưa có lưu đáp án/nộp/chấm điểm.

## Chạy toàn bộ bằng Docker

Docker Compose hiện chạy **MySQL + backend Java 21 + frontend React/Vite qua Nginx**.
Chạy tại thư mục có `docker-compose.yml`:

```powershell
# Chỉ copy nếu chưa có .env; không ghi đè cấu hình hiện có.
Copy-Item .env.example .env
docker compose up -d --build --wait
docker compose ps
```

Mở **http://localhost:8088**. Backend health: **http://localhost:8080/actuator/health**.
Không cần cài Java/Node/Maven trên host để chạy stack Docker.
Nếu đang chạy backend bằng Maven ở cổng 8080, dừng tiến trình đó hoặc đặt
`BACKEND_PORT=8081` trong `.env`. `FRONTEND_PORT` đổi cổng frontend; `DB_PORT` đổi cổng
MySQL trên host. Container backend luôn kết nối `mysql:3306`, Nginx luôn proxy tới `backend:8080`.

Đây là cấu hình HTTP **local**, dùng profile local và giữ nguyên chính sách cookie/CSRF.
Không có TLS và chưa được xác minh production. Hướng dẫn seed, cập nhật image, dữ liệu
và yêu cầu production: [docs/docker.md](docs/docker.md).

## Chạy local bằng Maven/Vite

Cần JDK 21 (`JAVA_HOME` trỏ tới JDK), Docker Engine/Docker Desktop đang chạy Linux containers,
Git; cổng 3306 và 8080 còn trống. Không cần cài Maven riêng.

```bash
git clone https://github.com/DanhNguyen15092004/DoAnChuyenNganh.git
cd DoAnChuyenNganh
cp .env.example .env
docker compose up -d mysql
# Chờ MySQL healthy: docker compose ps
./mvnw spring-boot:run
```

Có thể dùng `docker compose up -d --wait mysql` để chờ healthcheck ngay trong lệnh.
Trên PowerShell dùng `Copy-Item .env.example .env` và `.\mvnw.cmd spring-boot:run`.
Trên Git Bash/Linux/macOS dùng `./mvnw` như trên.

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Cả ba trả HTTP 200 với `"status":"UP"` khi app/DB sẵn sàng.
Health tổng theo mặc định Actuator trả
`{"status":"UP","groups":["liveness","readiness"]}`; hai URL probe trả `{"status":"UP"}`.
Health tổng và readiness kiểm tra kết nối DB; liveness chỉ kiểm tra trạng thái app.
Chỉ expose endpoint Actuator `health`; không công khai chi tiết DB, env, heapdump hay configprops.

## Môi trường

| Profile | Cách chạy và cấu hình |
| --- | --- |
| `local` (mặc định) | Đọc `.env` ở thư mục chạy app; có mặc định DB cho local giống `.env.example`. |
| `test` | Test kích hoạt profile này; Testcontainers cấp URL/user/password động, không đọc `.env`. |
| `prod` | Đặt `SPRING_PROFILES_ACTIVE=prod`; bắt buộc cấp biến DB, không đọc `.env` và không có mặc định credential. |

Biến DB: `DB_HOST`, `DB_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`.
`MYSQL_ROOT_PASSWORD` chỉ dùng cho container MySQL. Tùy chọn: `SERVER_PORT` (8080),
`DB_POOL_SIZE` cho prod (10). Biến môi trường thực có ưu tiên cao hơn file `.env`.
Spring Boot cũng hỗ trợ `SPRING_DATASOURCE_URL` để cấp JDBC URL đầy đủ (ví dụ cấu hình TLS của DB production).
Trong production, cấu hình TLS phù hợp với máy chủ DB; chỉ local tắt SSL và cho phép public key retrieval.

`.env` đã được ignore. Dùng giá trị thuần không bọc dấu nháy trong `.env` vì local đọc file
theo cú pháp Java properties; không dùng nội suy `${...}` hay escape đặc biệt của shell.
Nếu đổi mật khẩu/database sau khi volume đã được tạo, phải cập nhật DB tương ứng;
các biến `MYSQL_*` của image chỉ khởi tạo dữ liệu khi volume rỗng.

```bash
docker compose down  # Dừng MySQL, giữ dữ liệu trong named volume
```

## Cấu trúc và migration

```text
src/main/java/com/example/doanchuyennganh/
  DoAnChuyenNganhApplication.java
  config/                 # Cấu hình ứng dụng khi phát sinh nhu cầu
  account/                # User, Role, repository, principal/service và seeder local
  common/exception/       # RestControllerAdvice, exception dùng chung
  common/demo/            # Endpoint kiểm chứng lỗi, chỉ local/test
src/main/resources/
  application.yml
  application-{local,test,prod}.yml
  db/migration/V1__init.sql
  db/migration/V2__create_app_users.sql
  db/migration/V3__enforce_exact_user_roles.sql
src/test/java/com/example/doanchuyennganh/
  ApplicationIntegrationTest.java
```

Flyway tự chạy khi khởi động, lưu lịch sử trong `flyway_schema_history`.
V1 là baseline chỉ có comment, không tạo bảng nghiệp vụ. Thêm thay đổi bằng migration mới
`V2__description.sql` trở đi; không sửa migration đã áp dụng.
Hibernate dùng `ddl-auto=validate`, không tự tạo/cập nhật schema.

## Kiểm chứng lỗi mẫu

Chỉ profile `local` và `test` có các endpoint ví dụ sau:

```bash
curl -i -X POST http://localhost:8080/api/examples/errors/validation \
  -H 'Content-Type: application/json' -d '{"name":""}'
curl -i http://localhost:8080/api/examples/errors/not-found
curl -i http://localhost:8080/api/examples/errors/internal
```

POST validation hiện cần CSRF token hợp lệ; ví dụ curl trên chưa gửi token sẽ nhận 403 từ filter.
Lấy token và giữ cookie qua GET `/api/v1/auth/csrf`; gửi token trong header `X-CSRF-TOKEN` cho POST.
Khi có CSRF hợp lệ, ba ví dụ lần lượt trả 400, 404, 500 với `Content-Type: application/problem+json`, theo ProblemDetail
(RFC 7807, được RFC 9457 thay thế). Ví dụ lỗi 500:

```json
{
  "type": "about:blank",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "An unexpected error occurred.",
  "instance": "/api/examples/errors/internal"
}
```

Lỗi validation có thêm `errors` chứa tên field và thông báo, không trả giá trị bị từ chối.
Lỗi bất ngờ được ghi log phía server; response không chứa stack trace hay thông báo exception nội bộ.
Đường dẫn không tồn tại cũng trả 404 ProblemDetail; JSON sai cú pháp trả 400.

## Test và CI

```bash
./mvnw verify
# PowerShell: .\mvnw.cmd verify
```

Test JUnit 5 chạy ứng dụng thật trong Spring context với MySQL 8.0 Testcontainers,
kiểm tra migration, health/probes, giới hạn Actuator, và các phản hồi lỗi.
Docker bắt buộc hoạt động; test không bị skip khi thiếu Docker. Không cần chạy Compose
hoặc tạo `.env` để test; container dùng port ngẫu nhiên và được tự thu dọn.

GitHub Actions chạy trên `push` và `pull_request`, Java 21, Maven cache và
`./mvnw --batch-mode --no-transfer-progress verify` trên runner Ubuntu có Docker.

## Giả định và phạm vi

- Tên dự án suy ra từ repo; package gốc là `com.example.doanchuyennganh`.
- Dùng bản Spring Boot 3.x ổn định mới nhất có trên Maven Central: 3.5.16,
  đối chiếu ngày 23/10/2026 với [thông báo chính thức](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).
- Compose chỉ chạy MySQL. Dockerfile/app service là tùy chọn và chưa thêm; app chạy bằng Maven Wrapper.
- V2 bổ sung bảng `app_users`, V3 siết CHECK role chính xác bằng so sánh binary; V1 giữ nguyên.
- Bổ sung starter Security và `spring-security-test` theo phạm vi auth; dependency version do Spring Boot quản lý.
