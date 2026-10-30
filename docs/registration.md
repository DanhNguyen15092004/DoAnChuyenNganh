# Đăng ký sinh viên

Được bổ sung theo yêu cầu sau lượt Docker. Dùng React/Vite JavaScript, Spring Security
và dependency hiện có; không đổi schema/migration hoặc cơ chế login/session/logout.

## API

| Method | URL | Request | Response | Role | CSRF | Pagination |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | `{email,password,displayName}` | 201 `{id,email,displayName,role:"STUDENT"}` | Public | Bắt buộc | Không |

Lấy token và giữ cookie qua GET `/api/v1/auth/csrf`, gửi headerName/token nhận được.
Email trim/lowercase Locale.ROOT, tối đa 254, định dạng email hợp lệ. Họ tên trim,
không trắng, tối đa 100 ký tự. Password không trim/normalize: 8–72 ký tự và tối đa
72 byte UTF-8 theo giới hạn BCrypt. Không truncate password dài.

Tài khoản tạo ra enabled=true, role luôn STUDENT do server quyết định. Field thừa
như role/enabled bị từ chối 400. Password/hash không có trong response hay toString.
Response Cache-Control no-store. Không tự đăng nhập: /me vẫn 401 cho đến khi login.
Không có đăng ký TEACHER/ADMIN, email verification, đổi/quên password hoặc quản lý tài khoản.

- 400: dữ liệu/JSON không hợp lệ, password vượt giới hạn BCrypt.
- 403: thiếu/sai CSRF, giữ convention ProblemDetail hiện có.
- 409: email đã tồn tại sau chuẩn hóa hoặc va chạm unique khi hai request đồng thời.
  Không ghi đè password, displayName hay role của tài khoản cũ.
- 500: lỗi không xác định theo handler chung, không trả stack trace.

Transaction tạo user kết thúc trước khi controller xử lý collision. MySQL unique email
vẫn là lớp bảo vệ race cuối cùng. Không xóa/reset dữ liệu để đăng ký lại.

## UI và local

Mở http://localhost:8088 → **Đăng ký sinh viên**, hoặc `/#/register`.
Nhập tên/email/password/xác nhận password. Nút khóa khi gửi; lỗi giữ form;
chỉ thông báo thành công sau 201 rồi quay về đăng nhập. Frontend không lưu password/token
vào localStorage, không mock authentication, không gửi role từ form.

File môi trường backend ở thư mục chứa docker-compose.yml: `.env` (file bị Git ignore).
Để hiện file bị ignore trong VS Code có thể mở trực tiếp đường dẫn hoặc dùng
`code .env` từ root repository. Không commit file này.
Ba biến SEED_*_PASSWORD và APP_SEED_ENABLED chỉ ảnh hưởng seeder local;
seeder không thay password/role tài khoản có sẵn. Đăng ký không thay đổi seeder.

Sau khi sửa source, chạy `docker compose up -d --build --wait backend frontend`.
Backend khởi động lại làm mất session cũ; đăng nhập lại sau khi cập nhật.

## Kiểm chứng

RegistrationIntegrationTest dùng MySQL Testcontainers + Tomcat/HTTP/cookie thật,
kiểm tra create → login → me → logout, chuẩn hóa, BCrypt/password whitespace,
role STUDENT, duplicate không overwrite, concurrent registration 201/409,
CSRF 403, validation/role injection 400, password UTF-8 và DTO redaction.
Không sửa test cũ hay tắt filter để làm test pass.
Kết quả lệnh và phần chưa kiểm chứng: [registration-implementation-report.md](registration-implementation-report.md).
