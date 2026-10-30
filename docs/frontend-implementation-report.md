# Báo cáo frontend — 29/10/2026

> Cập nhật sau lượt Docker: dependency/lockfile hiện đã có; lint, build và 3 test
> đã chạy đạt. Đã kiểm tra HTTP login/me/logout thật qua Nginx trong stack Docker
> riêng. Phần dưới lưu kết quả lượt frontend ban đầu; kết quả mới xem
> [docker-verification.md](docker-verification.md). Chưa xác minh browser flow kỳ thi.

## Trạng thái

Đã viết frontend React + Vite bằng **JavaScript/JSX**. TypeScript được thêm ban đầu
ngoài yêu cầu và đã được gỡ sau phản hồi của người dùng: không còn .ts/.tsx,
tsconfig, dependency TypeScript hoặc script typecheck.

**Chưa hoàn tất kiểm chứng và chưa xác nhận flow chạy được.** Dependency chưa cài được,
chưa có lockfile, lint/build chưa chạy đạt, công cụ browser không khởi tạo được.
Không dùng test thuần hoặc HTTP âm tính để thay bằng chứng browser flow thành công.

## File của lượt này

- `frontend/package.json`, `.gitignore`, `.env.example`, `index.html`, `vite.config.js`,
  `eslint.config.js` và `README.md`.
- `frontend/src/main.jsx`: session restore, login/logout, route theo role, danh sách,
  metadata, soạn câu hỏi, publish, enrollment, student detail/consent.
- `frontend/src/api.js`: client cookie/CSRF dùng chung, ProblemDetail, 204,
  không tự retry mutation, xử lý 401/403.
- `frontend/src/forms.js`: chuyển đổi thời gian, validation, dựng payload position.
- `frontend/src/style.css`: giao diện tiếng Việt responsive, label/focus/keyboard.
- `frontend/tests/forms.test.js`: ba test thuần.
- `docs/frontend-contract.md` và tài liệu báo cáo này.

Repo có nhiều thay đổi auth/exam chưa commit từ trước lượt này. Đối chiếu SHA-256
snapshot đầu/cuối xác nhận **0 file src backend thay đổi**, **0 file backend/config
được theo dõi trong snapshot thay đổi** (gồm pom, compose, application profiles,
.env.example backend). Không đọc nội dung .env, đổi tài khoản, schema hoặc security.

## Endpoint đã tích hợp trong code

Contract đầy đủ Method/URL/Request/Response/Role/CSRF/Pagination nằm tại
[frontend-contract.md](frontend-contract.md), được viết trước UI.

| Method | URL |
| --- | --- |
| GET | `/api/v1/auth/csrf` |
| POST | `/api/v1/auth/login` |
| GET | `/api/v1/auth/me` |
| POST | `/api/v1/auth/logout` |
| GET, POST | `/api/v1/teacher/exams` |
| GET, PUT | `/api/v1/teacher/exams/{id}` |
| PUT | `/api/v1/teacher/exams/{id}/questions` |
| POST | `/api/v1/teacher/exams/{id}/publish` |
| GET, POST | `/api/v1/teacher/exams/{id}/enrollments` |
| GET | `/api/v1/student/exams` |
| GET | `/api/v1/student/exams/{id}` |
| POST | `/api/v1/student/exams/{id}/consent` |

“Tích hợp” ở bảng này nghĩa là code đã gọi đúng contract source, không có nghĩa
mọi endpoint đã được xác minh qua trình duyệt. Không thêm API tìm user/attempt.

## Hành vi đã viết

CSRF chỉ giữ bộ nhớ; cookie do browser quản lý, credentials include, không localStorage,
JWT/Bearer hoặc đọc JSESSIONID. Login lấy token, đăng nhập, lấy token mới rồi /me;
reload khôi phục qua /me. Logout chờ 204 trước khi xóa user, rồi lấy token mới.
401 bảo vệ chuyển về login; 403 thông báo chung và yêu cầu token mới vì backend
không có code phân biệt quyền/CSRF; không tự retry request ghi.

Metadata/questions lưu tách biệt, giữ phần chưa lưu khi lưu phần kia. Có dirty indicator,
beforeunload và xác nhận rời route, nút publish bị khóa khi chưa lưu. Publish có cảnh báo
và chỉ chuyển read-only sau response thật. Editor giới hạn 100 câu, 2–6 option, radio
đúng một đáp án; position được đánh lại 1..n, không gửi ID response ngược về backend.

Giờ local chuyển qua Date/toISOString, không nối Z; hiển thị múi giờ browser. Timestamp
microsecond server giữ nguyên khi field thời gian không sửa. Metadata kiểm tra window/duration.
Student chỉ gọi endpoint sinh viên, render consent như text, không render câu hỏi/đáp án.
Mọi 409 consent đều bỏ tick và tải lại (không đoán loại conflict qua message).
Không hiện acceptedAt sau browser reload vì DTO đọc sinh viên không có field này.

## Lệnh và kết quả thực tế

Node không có trên PATH; dùng Node 24.22.1 có sẵn trong runtime Codex và npm 11.6.2
được tải vào target (thư mục ignored). Không thay đổi cấu hình Node của hệ thống.

| Kiểm tra | Kết quả |
| --- | --- |
| `npm install --no-audit --no-fund` | Không hoàn tất do truy cập mạng trong sandbox; đã dừng tiến trình |
| Yêu cầu chạy npm install có quyền mạng | Bị từ chối; không tìm cách tải qua kênh khác |
| `npm install --offline --no-audit --no-fund --fetch-retries=0` | Fail ENOTCACHED ở @eslint/js; cache không đủ |
| `npm run lint` | Fail: eslint chưa được cài / not recognized |
| `npm run build` | Fail: vite chưa được cài / not recognized |
| `npm test` | PASS 3/3, không skip |
| `TZ=America/New_York node --test tests/forms.test.js` (env PowerShell) | PASS 3/3 |
| `node --check src/api.js`, `node --check src/forms.js` | PASS; không kiểm tra JSX/build thay thế |
| `git diff --check` | PASS; chỉ cảnh báo LF/CRLF có sẵn |
| Snapshot hash backend đầu/cuối | Không đổi |

HTTP thật tới backend đang chạy tại localhost:8080, không đọc .env/credential:

- GET /actuator/health: status UP.
- GET /api/v1/auth/csrf: 200, có headerName/token (không in token).
- GET /api/v1/auth/me khi anonymous: 401.
- POST login email không tồn tại + password sinh ngẫu nhiên, có CSRF: 401.
- POST login thiếu CSRF trong session mới riêng: 403, ProblemDetail about:blank.
- GET teacher/exams khi anonymous: 401.

Không chạy lại Maven verify trong lượt frontend này; không lấy kết quả 52 test của
lượt backend trước làm kết quả kiểm tra mới.

## Browser, dữ liệu và phần còn thiếu

`node_repl` và `cua_repl` đều không khởi tạo được runtime:
`failed to start Node runtime: The system cannot find the path specified. (os error 3)`.
Không khởi chạy được browser kiểm thử từ các công cụ này. Chưa chạy Vite server do thiếu dependency.

**Chưa xác minh toàn bộ 13 bước browser** trong yêu cầu: login thật student/teacher,
ID thật, draft hai câu, persist sau reload, publish read-only, enrollment, student visibility,
consent persist, logout và route bảo vệ. Cũng chưa xác minh bằng browser: password sai,
restore/expiry, form giữ nội dung khi lỗi, chặn role, ownership và payload sinh viên không lộ đề.
Các mục role/ownership/payload chỉ mới đối chiếu implementation và test backend hiện có.

Đã kiểm tra Docker read-only, MySQL local đang chạy. Ý định dùng fixture MySQL/backend
riêng để kiểm thử không được tiến hành sau khi gặp blocker frontend/browser.
**Không tạo container/DB fixture, tài khoản, exam, enrollment hoặc consent nào.**
Không đổi password/role, không reset/xóa dữ liệu, không push/deploy.

Chưa có package-lock.json vì npm install không hoàn tất. Cần cấp khả năng tải dependency,
chạy npm install để tạo lockfile, chạy lại lint/build/test và sửa lỗi thực tế nếu có,
sau đó mới kiểm thử browser/backend đầy đủ. Chưa xác minh HTTPS/production.

## Khởi chạy sau khi gỡ blocker

Xem [frontend/README.md](../frontend/README.md): backend Java 21 đang chạy;
trong frontend copy .env.example sang .env.local, đặt BACKEND_TARGET theo port backend,
chạy npm install rồi npm run dev, mở http://127.0.0.1:5173.
