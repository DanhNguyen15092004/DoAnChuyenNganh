# Báo cáo phần nền tài khoản — 27/10/2026

## Baseline trước thay đổi

Đã đọc pom, các profile, migration, exception handler, controller lỗi mẫu, CI và test hiện có.
Working tree ban đầu sạch. Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11;
package `com.example.doanchuyennganh`; JPA `ddl-auto=validate`; Flyway chỉ có V1 dạng comment;
lỗi theo ProblemDetail. Chưa có Security hoặc bảng tài khoản.

Baseline `./mvnw --batch-mode --no-transfer-progress verify`: **BUILD SUCCESS**,
9 test, 0 failure, 0 error, 0 skipped (1 phút 18 giây).

## Dependency và phiên bản được resolve

Chỉ thêm `spring-boot-starter-security` và `spring-security-test` (test scope), không ghi version riêng.
Giữ nguyên Java 21, Spring Boot 3.5.16 và BOM hiện tại.

`./mvnw --batch-mode --no-transfer-progress dependency:tree -Dincludes=org.springframework.security`
đã **BUILD SUCCESS**, resolve `spring-security-config`, `web`, `core`, `crypto`, `test` ở **6.5.11**.

## File đã thay đổi

Đường dẫn dưới đây tính từ root repository:

| File | Thay đổi |
| --- | --- |
| `pom.xml` | Hai dependency Security, không pin version |
| `.env.example` | Cờ seed mặc định false và ba placeholder mật khẩu rỗng |
| `README.md` | Hướng dẫn và liên kết contract; yêu cầu CSRF cho POST lỗi mẫu |
| `src/main/resources/application.yml` | JDBC timezone UTC; giữ validate |
| `src/main/resources/application-local.yml` | `app.seed.enabled` lấy từ `APP_SEED_ENABLED`, mặc định false |
| `src/main/resources/db/migration/V2__create_app_users.sql` | Schema tài khoản, unique email, CHECK role |
| `src/main/resources/db/migration/V3__enforce_exact_user_roles.sql` | CHECK so sánh binary, loại case variant/khoảng trắng |
| `src/main/java/com/example/doanchuyennganh/account/User.java` | Entity, enum string/VARCHAR, UTC lifecycle timestamps; hash bị loại khỏi JSON |
| `src/main/java/com/example/doanchuyennganh/account/Role.java` | STUDENT, TEACHER, ADMIN |
| `src/main/java/com/example/doanchuyennganh/account/EmailNormalizer.java` | trim + lowercase Locale.ROOT |
| `src/main/java/com/example/doanchuyennganh/account/UserRepository.java` | Lookup chuẩn hóa email |
| `src/main/java/com/example/doanchuyennganh/account/security/AccountPrincipal.java` | Snapshot UserDetails, một authority, enabled, xóa credential; không giữ entity |
| `src/main/java/com/example/doanchuyennganh/account/security/DatabaseUserDetailsService.java` | Tải MySQL trong read-only transaction |
| `src/main/java/com/example/doanchuyennganh/account/seed/LocalAccountSeeder.java` | ApplicationRunner opt-in local, transaction, validate và seed idempotent |
| `src/main/java/com/example/doanchuyennganh/config/SecurityConfiguration.java` | BCrypt/DaoAuthenticationProvider/AuthenticationManager, filter, session CSRF, lỗi JSON |
| `src/test/java/com/example/doanchuyennganh/ApplicationIntegrationTest.java` | Chỉ thêm import và hai `.with(csrf())`; giữ nguyên body và toàn bộ kỳ vọng cũ |
| `src/test/java/com/example/doanchuyennganh/account/AccountIntegrationTest.java` | 14 test tích hợp MySQL/provider/filter/seeder |
| `src/test/java/com/example/doanchuyennganh/account/EmailNormalizerTest.java` | Locale.ROOT với locale máy giả định Turkish |
| `src/test/java/com/example/doanchuyennganh/account/LocalAccountSeederConditionTest.java` | Điều kiện profile/cờ, chặn test/prod kể cả kèm local |
| `src/test/java/com/example/doanchuyennganh/account/LocalAccountSeederStartupTest.java` | Local startup runner thật trên DB tạm; kiểm tra transactional proxy và chạy lại |
| `docs/auth-contract.md` | Contract đầy đủ cho các lượt HTTP auth tiếp theo, hướng dẫn seed và phạm vi filter |
| `docs/auth-implementation-report.md` | Báo cáo này |

## Migration

V1 không đổi. V2 tạo `app_users` đúng các cột/ràng buộc yêu cầu, không chứa dữ liệu mẫu.
V3 thay CHECK bằng so sánh binary để chỉ chấp nhận chính xác ba chuỗi enum;
không sửa V2 sau khi đã chạy kiểm chứng. MySQL từ chối `OWNER`, `student`, `STUDENT ` với mã 3819.
Hibernate validate thành công trên schema do Flyway tạo. Không dùng create/update, không reset DB.
Migration mới chỉ được chạy trên MySQL Testcontainers; không áp dụng vào DB local/prod trong nhiệm vụ này.

## Hành vi đã triển khai

- Chuẩn hóa email tại entity và lookup; không trim/lowercase mật khẩu.
- BCrypt, tài khoản MySQL qua UserDetailsService, principal tách entity;
  đúng một authority `ROLE_<role>`; enabled=false bị DaoAuthenticationProvider từ chối.
- ProviderManager xác thực credential thật và xóa credential sau thành công.
  Không có mock user được dùng làm bằng chứng xác thực.
- Public đúng ba GET health/probes và các method/path lỗi mẫu ở local/test.
  API còn lại được bảo vệ; chỉ health được expose qua Actuator. Không có form login/HTTP Basic/logout mặc định.
- CSRF dùng HttpSessionCsrfTokenRepository, không disable/ignore CSRF; MockMvc filters vẫn bật.
  Thiếu/sai token trả 403; API chưa xác thực trả ProblemDetail 401, không redirect.
- Seeder chỉ đăng ký khi local và opt-in, không có test/prod. Thiếu mật khẩu báo tên biến cấu hình;
  role xung đột báo email/role; không log mật khẩu/hash, không ghi đè tài khoản đã có.
- Ba email mẫu và ba biến mật khẩu được ghi trong contract và `.env.example`.

## Lệnh kiểm tra và kết quả thực tế

Chạy bằng Git Bash với JAVA_HOME là JDK 21, trên Docker Desktop Linux containers:

| Lệnh/kiểm tra | Kết quả |
| --- | --- |
| `./mvnw --batch-mode --no-transfer-progress verify` trước thay đổi | BUILD SUCCESS, 9/9 |
| `./mvnw --batch-mode --no-transfer-progress dependency:tree -Dincludes=org.springframework.security` | BUILD SUCCESS, Security 6.5.11 |
| `./mvnw --batch-mode --no-transfer-progress verify` cuối | **BUILD SUCCESS, 27 test, 0 failure/error/skipped**, 2 phút 13 giây |
| `git diff --check` | Không có lỗi whitespace |
| Diff V1 và test nền tảng | V1 không đổi; test chỉ thêm CSRF request token, không sửa kỳ vọng |

27 test gồm: 9 nền tảng, 14 account tích hợp, 1 chuẩn hóa email, 2 điều kiện seeder,
1 startup local thực tế. Bao phủ migration/schema, timestamps UTC, email unique, CHECK role,
BCrypt đúng/sai/case/whitespace, authority/enabled, credential provider, seeder idempotent/xung đột/
thiếu cấu hình, profile guard, 401 JSON và CSRF 403.

Lần verify đầu của phần mới có một assertion sai lớp exception cho CHECK:
MySQL đã từ chối dữ liệu đúng với mã 3819, nhưng JDBC dịch thành UncategorizedSQLException.
Đã sửa test mới để kiểm tra nguyên nhân SQL/mã lỗi; không sửa kỳ vọng test nền tảng để né lỗi.

## Phần chưa xác minh

- Chưa triển khai hoặc kiểm chứng endpoint `/csrf`, `/login`, `/me`, `/logout`.
- Chưa kiểm chứng lưu/khôi phục authentication vào JSESSIONID, session fixation qua login HTTP,
  rotation CSRF sau login/logout, invalidate session hay cookie cũ sau logout.
- Chưa triển khai ma trận phân quyền các API STUDENT/TEACHER/ADMIN; hiện chỉ map authority.
- Chưa chạy GitHub Actions từ remote, chưa thử DB local/prod thực tế.

## Giới hạn hoặc blocker

Sandbox ban đầu chặn Docker named pipe/config Docker (`permission denied`).
Các lần Maven/Testcontainers được phép chạy ngoài sandbox đã thành công; không còn blocker kiểm chứng hiện tại.
Không skip test, không thay security filters, không tắt CSRF, không xóa dữ liệu hoặc reset database.
Không sửa `.env`, không commit secret, không push hoặc deploy.

Dừng tại phần nền tài khoản theo yêu cầu. Contract của lượt tiếp theo ở `docs/auth-contract.md`.
