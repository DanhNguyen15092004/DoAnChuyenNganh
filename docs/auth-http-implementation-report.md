# Báo cáo CSRF/login/session/logout/phân quyền — 28/10/2026

## File thay đổi

Repository có sẵn thay đổi chưa commit của lượt trước. Danh sách sau là phần bổ sung/sửa ở lượt này:

| File (từ root repo) | Nội dung |
| --- | --- |
| `src/main/java/com/example/doanchuyennganh/account/auth/AuthController.java` | CSRF, login JSON, me; DTO, explicit strategy/save context |
| `src/main/java/com/example/doanchuyennganh/account/auth/RoleProbeController.java` | Ba GET probe local/test, không xuất hiện ở prod |
| `src/main/java/com/example/doanchuyennganh/config/SecurityConfiguration.java` | Repository/strategy dùng cho login, logout DSL, role rules/default deny, cookie deletion |
| `src/main/java/com/example/doanchuyennganh/common/exception/GlobalExceptionHandler.java` | AuthenticationException → cùng 401 ProblemDetail cho credential sai/disabled |
| `src/main/resources/application.yml` | Cookie HttpOnly/path/SameSite, tracking cookie, timeout cấu hình được, Secure mặc định |
| `src/main/resources/application-local.yml` | Secure=false cho HTTP local |
| `src/main/resources/application-test.yml` | Secure=false cho HTTP test |
| `src/main/resources/application-prod.yml` | Secure=true cho production |
| `src/test/java/com/example/doanchuyennganh/account/AuthSessionIntegrationTest.java` | 6 test HTTP thật + 1 MockMvc, dùng MySQL và login thật |
| `src/test/java/com/example/doanchuyennganh/account/AuthProductionConfigurationTest.java` | Binding cấu hình prod, timeout tùy chỉnh, probe profile guard |
| `src/test/java/com/example/doanchuyennganh/account/AccountIntegrationTest.java` | POST login với `{}`/CSRF chuyển kỳ vọng 401 → 400 theo contract endpoint vừa được triển khai |
| `README.md` | Trạng thái auth và liên kết ví dụ đầy đủ |
| `docs/auth-contract.md` | Cập nhật contract đã triển khai |
| `docs/auth-session-flow.md` | Cơ chế session/CSRF, ví dụ đầy đủ, cấu hình cookie và frontend cần bổ sung |
| `docs/auth-http-implementation-report.md` | Báo cáo này |

Không sửa các test nền tảng trong lượt này. Thay đổi assertion ở test tài khoản là thay đổi contract:
POST login hiện public nhưng cần CSRF, nên body `{}` hợp lệ qua filter rồi thất bại validation với 400.
Không tắt CSRF/filter hoặc mở rộng ngoại lệ public để né test.
V1/V2/V3 và seeder giữ nguyên checksum SHA-256 so với đầu lượt; không tạo migration mới.
Không đổi dependency/Java 21/Spring Boot 3.5.16/Spring Security 6.5.11, không dùng JWT.

## Cách lưu SecurityContext và áp dụng session strategy

AuthenticationManager hiện có xác thực DB/BCrypt. Credential bị xóa trước khi lưu session.
Controller gọi composite `loginSessionAuthenticationStrategy` đúng một lần:

1. `ChangeSessionIdAuthenticationStrategy` đổi ID của session trước login.
2. `CsrfAuthenticationStrategy` xóa token trước login, dùng cùng HttpSessionCsrfTokenRepository/XOR handler với filter.

Sau đó controller tạo SecurityContext mới, đặt authentication và lưu qua bean SecurityContextRepository.
Filter chain dùng chính repository đó (`HttpSessionSecurityContextRepository`) với explicit save;
request `/me` tiếp theo đọc principal từ session, không nhận userId của client.
Không chỉ đặt authentication vào SecurityContextHolder.

`requireExplicitAuthenticationStrategy=true` loại SessionManagementFilter khỏi luồng này để không gọi strategy lần hai.
Giữ policy mặc định IF_REQUIRED, không gọi setter IF_REQUIRED vốn xung đột chế độ explicit ở Security 6.5.
MockMvc kiểm tra strategy đúng một lần, không có SessionManagementFilter và cả authentication/principal đều không giữ credential.
Spy chỉ đếm lời gọi strategy; không stub authentication hoặc dùng mock user.

Logout sử dụng LogoutFilter/DSL và các handlers của Security: chỉ POST, cần CSRF, xóa CSRF/context,
invalidate session phía server, xóa JSESSIONID path `/`, success handler trả 204. Không có controller logout.
Cookie HttpOnly=true, SameSite=Lax, path `/`; Secure=false ở local/test, true ở prod;
timeout `SESSION_TIMEOUT` mặc định 30m, không có remember-me, form login hoặc HTTP Basic.

## Kết quả Maven verify, tổng test/failures/errors/skipped

Thực thi Git Bash/Maven Wrapper bằng JDK 21, ngoài sandbox để có quyền Docker/Maven cache/HTTP localhost:

| Kiểm tra | Tests | Failures | Errors | Skipped | Kết quả |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline trước sửa: `./mvnw --batch-mode --no-transfer-progress verify` | 27 | 0 | 0 | 0 | BUILD SUCCESS, 2m57s |
| Mốc HTTP: `./mvnw --batch-mode --no-transfer-progress -Dtest=AuthSessionIntegrationTest#realHttpCsrfLoginMeMilestoneAndCookieAttributes test` | 1 | 0 | 0 | 0 | BUILD SUCCESS, 1m11s |
| Verify cuối: `./mvnw --batch-mode --no-transfer-progress verify` | **37** | **0** | **0** | **0** | **BUILD SUCCESS, 3m59s** |

37 test gồm 9 nền tảng, 14 tài khoản, 7 session/HTTP, 3 cấu hình prod, 1 email normalizer,
2 điều kiện seeder, 1 startup seeder local. Không skip khi thiếu Docker.
Các lỗi helper test và xung đột cấu hình session ở những lần chạy trước đã sửa; bảng ghi kết quả chạy cuối thực tế.
`git diff --check` đạt; ví dụ PowerShell trong tài liệu được parse kiểm tra cú pháp thành công.

## Kết quả HTTP thực tế

Spring Boot RANDOM_PORT chạy Tomcat thật, MySQL 8.0 Testcontainers; Java HttpClient gọi TCP localhost,
đọc Set-Cookie và gửi Cookie thật ở request sau. Sáu test HTTP đều đạt, không dựa vào MockHttpSession.

| Hành vi | Kết quả thực tế |
| --- | --- |
| Anonymous GET csrf | 200, token được tạo, cookie session được cấp, Cache-Control=no-store |
| POST login cả ba role bằng CSRF/cookie tương ứng | 200, DTO đúng id/email/displayName/role, không có hash/password |
| Login → cookie mới → GET me | 200, đúng tài khoản vừa login, Cache-Control=no-store |
| Session fixation | ID sau login khác ID trước login; gửi cookie trước login tới me nhận 401 |
| Sai password/email không tồn tại/disabled | Cùng 401, cùng ProblemDetail và “Email hoặc mật khẩu không hợp lệ.” |
| JSON sai/null/thiếu field/email không hợp lệ/password trắng | 400 ProblemDetail khi có CSRF hợp lệ |
| Thiếu/sai CSRF khi login/logout | 403 ProblemDetail |
| Token trước login dùng với cookie sau login | 403; session đã login vẫn dùng được |
| CSRF mới sau login → logout | 204, không body/redirect |
| Logout thiếu/sai CSRF | 403; request me tiếp theo vẫn 200 |
| GET logout | 403 với session đã login; me tiếp theo vẫn 200 |
| Anonymous logout với CSRF hợp lệ | 204 |
| Cookie HTTP thật | JSESSIONID có HttpOnly, Path=/, SameSite=Lax, không có Secure ở test HTTP |
| Logout cookie deletion | Set-Cookie JSESSIONID Max-Age=0, Path=/ |
| API chưa cho phép rõ ràng | Session đã login nhận 403 |

Các lỗi kiểm tra đều không redirect/HTML. Production binding xác nhận Secure=true và timeout mặc định 30m;
test override timeout 45m cũng đạt. Profile guard xác nhận không có probe ở prod kể cả kèm local/test.
Không in password, token hoặc session ID vào báo cáo/log test; so sánh ID/token bằng boolean.

## Kết quả ma trận role

Mỗi hàng authenticated dùng session tạo qua login HTTP thật; không có role hierarchy.

| Session | student/probe | teacher/probe | admin/probe | auth/me |
| --- | --- | --- | --- | --- |
| Chưa login | 401 | 401 | 401 | 401 |
| STUDENT | 200 | 403 | 403 | 200 |
| TEACHER | 403 | 200 | 403 | 200 |
| ADMIN | 403 | 200 | 200 | 200 |

Giữ chính xác ba GET health/probes và các method/path lỗi mẫu hiện có; toàn bộ 9 test nền tảng đạt.

## Bằng chứng cookie cũ bị từ chối sau logout

Test `realHttpLogoutRevokesCopiedCookieAndRejectedLogoutPreservesSession`:

1. Login HTTP thật bằng ADMIN, lưu bản sao cookie đã authenticated trong biến.
2. GET CSRF mới, xác nhận các lần logout thiếu/sai CSRF và GET không làm mất phiên.
3. POST logout với token hợp lệ → 204 và cookie deletion.
4. Tạo HTTP client/session mới, chủ động gửi lại **bản sao cookie trước logout** tới `/me` → **401**.
5. Gửi cookie đó tới admin/probe → 401; token cũ kèm cookie đó POST logout → 403.

Client replay không dựa vào cookie jar đã được xóa. Vì vậy đây là bằng chứng thu hồi session phía server,
không chỉ kiểm tra cookie bị xóa hoặc mock session bị invalidate.

## Phần chưa xác minh

- Chưa chạy trên HTTPS/reverse proxy production hoặc frontend/browser thực tế.
- Chưa biết frontend origin; không cấu hình CORS. Cần xác nhận origin/credentials và mô hình same-site khi tích hợp.
- Ví dụ PowerShell mới kiểm tra cú pháp; chưa chạy script đó với DB local/prod của người dùng.
- Chưa chạy CI GitHub remote; không push/deploy. Không áp dụng thay đổi vào DB local/prod hay reset dữ liệu.

Không có blocker Docker/dependency hiện tại. Session trong bộ nhớ backend; nhiều instance/restart nằm ngoài phạm vi.
Hướng dẫn chạy đầy đủ ở `docs/auth-session-flow.md`.
