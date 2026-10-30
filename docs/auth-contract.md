# Tài khoản và contract xác thực

## Trạng thái của lượt triển khai hiện tại

Đã thêm model/repository `app_users`, tải tài khoản MySQL qua `UserDetailsService`,
principal tách khỏi JPA, BCrypt và seeder local. Java 21 và Spring Boot 3.5.16 được giữ nguyên;
Spring Security do BOM quản lý (dependency tree hiện resolve 6.5.11).
Đã triển khai CSRF, login JSON, `/me`, logout qua Security DSL và phân quyền theo role.
Luồng chạy và ví dụ đầy đủ: [auth-session-flow.md](auth-session-flow.md).
Đã bổ sung đăng ký tài khoản STUDENT theo yêu cầu kiểm thử. Contract:
[registration.md](registration.md). Chưa có đổi/quên mật khẩu, JWT, OAuth hoặc quản lý tài khoản.

V2 tạo bảng tài khoản, V3 siết CHECK bằng so sánh binary để từ chối chữ thường/khoảng trắng
bất kể collation DB. V1 và V2 đã chạy trong kiểm chứng được giữ nguyên; không sửa checksum migration.
Hibernate tiếp tục `ddl-auto=validate`.
Email được `trim()` rồi lowercase bằng `Locale.ROOT` ở entity và lookup repository.
Không chuẩn hóa mật khẩu. Role lưu `EnumType.STRING` với JDBC VARCHAR để khớp schema MySQL.
`created_at` và `updated_at` là DATETIME(6), ứng dụng ghi Instant theo UTC qua JDBC timezone UTC.
SQL bên ngoài ứng dụng phải tự cung cấp timestamp UTC.
Entity không được trả trực tiếp qua API; DTO chỉ chứa các trường trong contract bên dưới.
Principal chứa snapshot của tài khoản; credential được xóa sau xác thực bởi ProviderManager.

## Security hiện tại

- Security filter bảo vệ toàn bộ `/api/**`, các Actuator endpoint thực sự được expose,
  và các đường dẫn `/login`, `/logout` mặc định.
- Public GET chính xác `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
- Local/test chỉ public GET hai endpoint lỗi mẫu `not-found`, `internal` và POST `validation`.
  POST vẫn phải có CSRF token. Không mở cả namespace API hoặc Actuator.
- Public thêm GET `/api/v1/auth/csrf`, POST `/api/v1/auth/login`, POST `/api/v1/auth/logout`
  và POST `/api/v1/auth/register`; mọi POST vẫn bắt buộc CSRF. `/me` và các nhóm API dùng đúng ma trận role bên dưới.
- Các API chưa được cho phép rõ ràng mặc định bị từ chối. Các đường dẫn không được MVC ánh xạ bên ngoài API
  tiếp tục trả 404 như skeleton; chỉ Actuator `health` được expose trong cấu hình hiện tại.
  API mới phải nằm dưới `/api/**` hoặc được bổ sung phạm vi bảo vệ rõ ràng.
- Form login và HTTP Basic bị vô hiệu hóa. Logout chỉ xử lý POST `/api/v1/auth/logout` bằng DSL,
  không có controller logout hoặc remember-me. CSRF và security filters vẫn hoạt động.
- CSRF dùng `HttpSessionCsrfTokenRepository`; phiên chỉ tạo khi cần (`IF_REQUIRED`),
  login gọi composite strategy đúng một lần: đổi session id rồi xóa CSRF cũ.
  Controller lưu SecurityContext qua cùng repository với filter chain (explicit save).
- Lỗi filter và controller trả ProblemDetail JSON, không redirect. Authorization theo role ở bảng dưới
  đã được cấu hình; probe chỉ có trong local/test và không có trong prod.

Hai POST test nền tảng chỉ được bổ sung `.with(csrf())` để gửi request hợp lệ qua filter;
giữ nguyên body, status và các kỳ vọng ProblemDetail. Có test riêng thiếu/sai CSRF nhận 403.
Test xác thực dùng BCrypt + AuthenticationManager thật + tài khoản MySQL, không dùng mock user.
AuthSessionIntegrationTest bổ sung server Tomcat thật, HTTP client thật và cookie để kiểm chứng session/logout.

## Tài khoản mẫu local

Chỉ chạy khi đồng thời có profile `local`, không có `test/prod`, và `app.seed.enabled=true`.
Mặc định tắt; trong local cấu hình `APP_SEED_ENABLED=true` để bật. Ba biến mật khẩu bắt buộc:

| Email | Role | Biến môi trường | Tên hiển thị |
| --- | --- | --- | --- |
| student@example.test | STUDENT | `SEED_STUDENT_PASSWORD` | Sinh viên mẫu |
| teacher@example.test | TEACHER | `SEED_TEACHER_PASSWORD` | Giảng viên mẫu |
| admin@example.test | ADMIN | `SEED_ADMIN_PASSWORD` | Quản trị viên mẫu |

Điền các placeholder rỗng trong `.env` của máy local hoặc cấp qua environment; không commit file đó.
Không đưa secret vào command line, log hoặc tài liệu. Seeder không trim/lowercase mật khẩu.
Khi bật seed, thiếu/rỗng/trắng bất kỳ mật khẩu nào đều làm startup thất bại với tên biến cấu hình còn thiếu.
Khi email đã tồn tại và cùng role, seeder giữ nguyên password hash, role, display name, enabled và timestamps.
Role xung đột làm startup thất bại với email, role mong đợi và role đang lưu; không sửa dữ liệu.
Seeder chạy trong transaction; không chứa dữ liệu mẫu trong migration.
Tests tự tạo fixture; test/prod không tự seed kể cả bật cờ hoặc đồng thời có profile local.

## Contract HTTP đã triển khai

Dùng session cookie `JSESSIONID`, session trong bộ nhớ backend. Không dùng JWT.

| Method | Endpoint | Hành vi |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | Public, cấp CSRF token |
| POST | `/api/v1/auth/login` | JSON email/password, cần CSRF, thành công 200 |
| GET | `/api/v1/auth/me` | Cần session đã đăng nhập, thành công 200 |
| POST | `/api/v1/auth/logout` | Cần CSRF, invalidate session phía server, trả 204 |

Login request:

```json
{"email":"student@example.test","password":"<password>"}
```

Login và `/me` response (DTO, không trả entity hoặc hash):

```json
{"id":1,"email":"student@example.test","displayName":"Sinh viên mẫu","role":"STUDENT"}
```

CSRF response:

```json
{"headerName":"X-CSRF-TOKEN","token":"<token>"}
```

Client lấy token trước login và lấy lại sau login/logout. Không tắt CSRF cho login/logout.
Authentication phải lưu bằng cơ chế Spring Security vào session, có chống session fixation.
Sau logout, gửi cookie session cũ tới `/me` phải nhận 401. Backend restart làm mất session trong bộ nhớ.

| Nhóm API | STUDENT | TEACHER | ADMIN |
| --- | --- | --- | --- |
| `/api/v1/student/**` | Có | Không | Không |
| `/api/v1/teacher/**` | Không | Có | Có |
| `/api/v1/admin/**` | Không | Không | Có |
| `/api/v1/auth/me` | Có | Có | Có |

Authority tương ứng `ROLE_STUDENT`, `ROLE_TEACHER`, `ROLE_ADMIN`; mỗi tài khoản đúng một role,
không có role hierarchy ngầm. Backend thực thi ma trận này trong filter chain.

Lỗi dùng ProblemDetail theo convention của repository, không trả HTML/redirect:

- Request không hợp lệ: 400.
- Chưa đăng nhập: 401.
- Email không tồn tại, sai password hoặc disabled: cùng 401, cùng thông báo
  **“Email hoặc mật khẩu không hợp lệ.”**. Controller advice ánh xạ các exception provider về cùng phản hồi này.
- Sai quyền: 403.
- Thiếu/sai CSRF: 403.

## Kiểm chứng

```bash
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress dependency:tree -Dincludes=org.springframework.security
```

Docker phải hoạt động; không skip Testcontainers để báo thành công. Test bao gồm migration V2/schema validation,
unique email, CHECK role, timezone UTC, chuẩn hóa email, BCrypt, authority/enabled, xác thực provider thật,
seeder idempotent/xung đột/thiếu cấu hình, điều kiện profile, filter 401/CSRF 403, và toàn bộ test nền tảng.
`LocalAccountSeederStartupTest` cố ý boot profile local với credential fixture và MySQL tạm
để kiểm chứng ApplicationRunner/transaction proxy thật. Test này dùng config name riêng để không đọc `.env`.
Các test profile `test` không tự chạy seeder; các fixture tài khoản khác được tạo rõ ràng trong từng test.

Tài liệu Spring Security tham chiếu: [CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html),
[phạm vi SecurityFilterChain](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html).
