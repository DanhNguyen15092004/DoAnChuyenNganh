# Báo cáo đăng ký và tài khoản local — 29/10/2026

## Kết quả

Đã bổ sung đăng ký STUDENT theo yêu cầu mới và cập nhật Docker local đang chạy.
Ba tài khoản mẫu đã login HTTP thật thành công bằng mật khẩu local do người dùng yêu cầu.
Không thay đổi seeder để ghi đè tài khoản cũ, không đổi role, reset/xóa database,
sửa migration hay nâng Java/Spring/dependency. Không push/deploy remote.

## File thay đổi trong lượt này

- `.env` local bị Git ignore: chỉ cập nhật APP_SEED_ENABLED và ba SEED_*_PASSWORD;
  giữ cấu hình khác, không in nội dung, không đưa credential vào source/tài liệu commit.
- `account/auth/RegistrationController.java`: DTO có validation/redaction, POST register,
  response DTO 201/no-store, handler va chạm unique 409.
- `account/auth/RegistrationService.java`: chuẩn hóa qua DTO, BCrypt, giới hạn 72 byte,
  role STUDENT cố định, transaction và kiểm tra email trùng.
- `config/SecurityConfiguration.java`: chỉ thêm public POST chính xác /api/v1/auth/register;
  CSRF và các luật truy cập còn lại được giữ nguyên.
- `RegistrationIntegrationTest.java`: 7 test mới, MySQL Testcontainers và HTTP thật.
- `frontend/src/main.jsx`: form đăng ký/tên/email/password/confirm, link từ login,
  validation, loading/error, thành công quay về login; JavaScript/JSX.
- README root/frontend, docs/auth-contract.md, docs/frontend-contract.md,
  docs/registration.md và báo cáo này.

Repo có nhiều thay đổi chưa commit từ những lượt trước; danh sách này chỉ mô tả lượt đăng ký.
Không sửa kỳ vọng test cũ hoặc tắt security filter để làm test pass.

## API và hành vi

Contract đầy đủ: [registration.md](registration.md).
POST `/api/v1/auth/register` public nhưng bắt buộc session cookie/CSRF.
Nhận email,password,displayName; trả 201 id,email,displayName,role=STUDENT.
Không nhận role/enabled hay field thừa; không tự login sau khi tạo tài khoản.
Email trim/lowercase Locale.ROOT, name trim; password giữ nguyên, ít nhất 8 ký tự,
tối đa 72 ký tự và 72 byte UTF-8, hash bằng PasswordEncoder BCrypt hiện có.
400 validation, 403 CSRF, 409 duplicate; tất cả theo ProblemDetail.

## Lệnh và kiểm chứng thực tế

| Lệnh/kiểm tra | Kết quả |
| --- | --- |
| Maven verify trong sandbox lần đầu | Blocked: không tạo được cache C:/.m2/repository |
| ./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository verify (Java 21, quyền Docker/cache) | BUILD SUCCESS; 59 test, failures=0, errors=0, skipped=0; 6 phút 1 giây |
| 7 RegistrationIntegrationTest | PASS; register/login/me/logout thật, CSRF, validation, role injection, unique/concurrent, UTF-8/BCrypt, không overwrite tài khoản cũ |
| npm --prefix frontend run lint | PASS |
| npm --prefix frontend test | PASS 3/3 |
| npm --prefix frontend run build | PASS |
| docker compose build backend frontend | PASS |
| docker compose up -d --wait backend frontend | Cả MySQL/backend/frontend healthy; seed runtime true |
| HTTP qua localhost:8088: 3 seed accounts login, /me và logout | PASS STUDENT/TEACHER/ADMIN; ID thật 1/2/3 từ response |
| HTTP register thêm role ADMIN | 400, không tự cấp quyền |
| HTTP register hợp lệ, chuẩn hóa email | 201, role STUDENT |
| /me ngay sau register | 401, không tự login |
| Register lại email | 409 |
| Login tài khoản mới, /me, gọi teacher API, logout, /me | 200/200/403/204/401 |
| git diff --check | PASS, chỉ cảnh báo LF/CRLF của Git |

Script HTTP lần đầu bị ExecutionPolicy của sandbox chặn; đã chạy cùng script với
quyền phù hợp, không thay đổi ExecutionPolicy của máy, và tất cả kiểm tra đạt.
Build Docker vẫn dùng -DskipTests như thiết kế trước; bằng chứng test là Maven verify
độc lập bên trên, không lấy Docker build thay kết quả test.

## Dữ liệu thực tế

Seeder tạo ba tài khoản mẫu trong DB local hiện có: student@example.test (ID 1),
teacher@example.test (ID 2), admin@example.test (ID 3). Các ID lấy từ response thực tế,
không suy đoán theo thứ tự seed. Không thay đổi password/role tài khoản cũ bằng SQL.

HTTP smoke test tạo thêm STUDENT `registration-check-20261029-9605d99a@example.test`,
ID 4, tên `Registration Docker check`; giữ lại dữ liệu để truy vết. Không tạo exam,
enrollment hoặc consent trong lượt này. Testcontainers dùng DB riêng và fixture riêng.
Không log/lưu cookie, CSRF token hoặc password vào report/source.

## Chưa xác minh

Browser host báo unavailable; thử native Chrome nhận `Computer Use app approval timed out`.
Chưa xác minh thao tác UI signup bằng trình duyệt; đã build/lint UI và kiểm tra HTTP
qua chính Nginx/backend/MySQL Docker thật, không mock authentication.
Chưa kiểm tra HTTPS/production, email verification hoặc abuse/rate-limit; không bổ sung
các module đó trong lượt này. Không có đổi/quên password hoặc đăng ký TEACHER/ADMIN.

Mở http://localhost:8088, refresh nếu tab còn bundle cũ; chọn **Đăng ký sinh viên**,
hoặc đi thẳng http://localhost:8088/#/register. Docker hiện đã được cập nhật và chạy.
