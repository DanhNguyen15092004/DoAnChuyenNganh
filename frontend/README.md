# Frontend kiểm thử kỳ thi

React + Vite, JavaScript/JSX. Có bắt đầu/tiếp tục lượt và đọc đề theo deadline server;
chưa có lưu đáp án/nộp bài/điểm. Demo: [attempt-module.md](../docs/attempt-module.md).
Trang đăng nhập có nút **Đăng ký sinh viên**. Đăng ký tạo STUDENT, không cho chọn role;
thành công quay lại đăng nhập. Chi tiết: [registration.md](../docs/registration.md).
Contract đã đối chiếu source: [frontend-contract.md](../docs/frontend-contract.md).
Trạng thái kiểm chứng và blocker: [frontend-implementation-report.md](../docs/frontend-implementation-report.md).

## Chạy local

Muốn chạy toàn bộ bằng Docker, không cần Node trên host: tại root repository chạy
`docker compose up -d --build --wait`, mở **http://localhost:8088**.
Xem [hướng dẫn Docker](../docs/docker.md). Frontend container dùng Nginx proxy, không cần `.env.local`.

Cần Node >=22.12 và npm. Mở terminal tại thư mục repository có `pom.xml`.
Chạy MySQL và backend theo [README backend](../README.md):

```powershell
# Chỉ copy lần đầu; không ghi đè .env đang có.
Copy-Item .env.example .env
# Điền cấu hình local của bạn, không commit .env.
docker compose up -d mysql
.\mvnw.cmd spring-boot:run
```

Backend dùng Java 21; port mặc định trong application.yml là 8080, có thể đổi bằng
`SERVER_PORT`. Giữ backend chạy và mở terminal thứ hai:

```powershell
cd frontend
Copy-Item .env.example .env.local
# Chỉnh BACKEND_TARGET trong .env.local theo địa chỉ backend thực sự.
npm ci
npm run dev
```

Mở **http://127.0.0.1:5173**. Dùng nhất quán host này trong suốt demo.
Đã có package-lock.json; dùng `npm ci` để cài đúng dependency được khóa.

`BACKEND_TARGET` chỉ dùng trong Vite dev server, không có prefix VITE_, không chứa secret.
Trình duyệt gọi đường dẫn tương đối `/api`; Vite chuyển request/cookie tới backend.
Proxy không sửa CORS, CSRF hoặc thuộc tính cookie. Không dùng vite preview thay dev proxy.
Hash route ví dụ `/#/teacher/exams` tránh yêu cầu rewrite route trên server.

## Tài khoản local và studentId

Seeder backend hiện có chỉ chạy khi profile local hoạt động (không test/prod)
và `APP_SEED_ENABLED=true`. Bạn tự đặt ba giá trị `SEED_STUDENT_PASSWORD`,
`SEED_TEACHER_PASSWORD`, `SEED_ADMIN_PASSWORD` trong environment hoặc `.env` backend.
Không điền mật khẩu trong frontend, tài liệu hoặc command history.
Seeder không đổi password/role tài khoản đã tồn tại; dùng mật khẩu thực tế đang có.

- `student@example.test`: STUDENT.
- `teacher@example.test`: TEACHER.
- `admin@example.test`: ADMIN.

Sinh viên đăng nhập trước, đọc **ID** trong thông tin tài khoản ở đầu trang
(dữ liệu thật từ `/api/v1/auth/me`), rồi đăng xuất. Giáo viên dùng đúng ID đó để ghi danh.
Backend chưa có API tìm tài khoản, UI không đoán ID theo thứ tự seed.

## Kịch bản demo cần thực hiện

1. Sinh viên đăng nhập, ghi lại ID thật, đăng xuất.
2. Giáo viên đăng nhập, tạo draft có tiêu đề prefix `UI-DEMO-`, khoảng mở đủ thời lượng,
   giờ đóng ở tương lai. Input giờ theo múi giờ trình duyệt, gửi Instant UTC.
3. Thêm ít nhất hai câu hỏi, mỗi câu 2–6 lựa chọn và chọn một radio đáp án đúng.
   Thử thêm/xóa/đổi thứ tự; lưu toàn bộ câu hỏi. Metadata và câu hỏi lưu riêng.
4. Reload trình duyệt để kiểm tra nội dung đã persist; kiểm tra phiên được khôi phục qua /me.
5. Xuất bản, xác nhận cảnh báo bất biến; kiểm tra metadata/câu hỏi chỉ đọc.
6. Ghi danh studentId vừa lấy, kiểm tra danh sách và trạng thái chưa đồng ý; đăng xuất.
7. Sinh viên đăng nhập, thấy kỳ thi đã ghi danh, mở chi tiết. Không có nội dung/đáp án đề.
8. Đọc consent, tick checkbox chưa chọn sẵn rồi xác nhận. Reload và kiểm tra trạng thái vẫn còn.
   Sau reload backend sinh viên không cung cấp acceptedAt, nên UI chỉ hiện trạng thái đã đồng ý.
9. Đăng xuất, mở lại route bảo vệ: phải về màn hình đăng nhập.

Kiểm tra thêm: password sai; session hết hạn/restart backend rồi gọi API; form còn nội dung
khi request lỗi; STUDENT mở route giáo viên bị chặn; TEACHER chỉ thấy exam của mình;
Network tab thực sự tới backend và response sinh viên không có questions/options/correct.
Không xóa/reset dữ liệu để dọn demo. Ghi lại ID/tiêu đề các exam đã tạo.

## Kiểm tra frontend

```powershell
npm test
npm run lint
npm run build
```

Không có bước typecheck vì frontend dùng JavaScript. Test thuần kiểm tra chuyển đổi giờ,
validation và dựng payload; không thay thế kiểm thử trình duyệt/backend thật.
Không tự retry request ghi dữ liệu. Nếu mất response, tải lại kiểm tra trước khi gửi lại.
403 không có code phân biệt CSRF/quyền, UI thông báo chung và yêu cầu làm mới token;
409 consent tải lại chi tiết, bỏ tick và yêu cầu đọc lại. Các 409 khác có nút tải lại.

Production HTTPS, reverse proxy, cookie/session qua production và toàn bộ browser flow
chưa được xác minh. Không coi bản build frontend là production đã sẵn sàng.
