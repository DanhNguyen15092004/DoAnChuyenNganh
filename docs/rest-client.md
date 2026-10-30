# Test API bằng REST Client trong VS Code

File [api-tests.http](../api-tests.http) gồm 46 request có mã HTTP và kết quả mong đợi ghi trên từng bước. Đây là bộ test thủ công: đối chiếu response với chú thích; file không tự chạy assertion.

## Chuẩn bị

1. Cài extension **REST Client** của **Huachao Mao**, ID `humao.rest-client`.
2. Chạy MySQL và backend theo [README](../README.md). Mặc định API ở `http://localhost:8080`; có thể sửa `@baseUrl` đầu file `.http`.
3. Cần tài khoản TEACHER và STUDENT đang enabled. Với seed local, sao chép `.env.example` thành `.env` nếu chưa có; đặt `APP_SEED_ENABLED=true` và điền đủ `SEED_TEACHER_PASSWORD`, `SEED_STUDENT_PASSWORD`, `SEED_ADMIN_PASSWORD`, rồi khởi động lại backend. Nếu tài khoản đã tồn tại, seeder giữ nguyên mật khẩu cũ.
4. File `.env` phải cùng thư mục với `api-tests.http`. REST Client đọc hai biến `SEED_TEACHER_PASSWORD` và `SEED_STUDENT_PASSWORD` từ file này; điền đúng mật khẩu hiện tại của tài khoản. Sửa `@teacherEmail`/`@studentEmail` nếu dùng email khác. Không cần nhập studentId.
5. Trong VS Code Settings, giữ **Rest-client: Remember Cookies For Subsequent Requests** bật (mặc định bật), tương ứng `"rest-client.rememberCookiesForSubsequentRequests": true`.

Mật khẩu được chèn vào JSON dưới dạng văn bản. Nếu mật khẩu có dấu `"` hoặc `\`, dùng bản JSON đã escape trong biến môi trường riêng cho REST Client và đổi tên biến `$dotenv` tương ứng trong file `.http`; không sửa mật khẩu seed sang dạng escape. `.env` đã được Git ignore.

## Chạy

Mở `api-tests.http`, bấm **Send Request** trên từng request theo thứ tự **01 → 46**, chờ response rồi chạy bước kế tiếp. Có thể đặt con trỏ trong request và nhấn `Ctrl+Alt+R`. Không chọn toàn bộ file để gửi và không chạy xen kẽ tài khoản từ file khác trên cùng host.

| Bước | Nội dung |
| --- | --- |
| 01–07 | Health, login sinh viên, lấy ID qua `/me`, logout và kiểm tra 401 |
| 08–19 | Login giáo viên, lấy CSRF mới, tạo/sửa draft và câu hỏi, kiểm tra validation không làm mất đề |
| 20–30 | Publish, tính bất biến, ghi danh và gọi lặp, phân trang, CSRF, quyền giáo viên |
| 31–43 | Login lại sinh viên, xem kỳ thi, consent sai/đúng/lặp, quyền và CSRF |
| 44–46 | Logout và kiểm tra session không còn truy cập được API bảo vệ |

REST Client tự gửi cookie hiện tại. Token trước login chỉ dùng cho login; sau login lấy token mới. Các biến `examId`, `studentId`, CSRF và consentVersion được lấy từ response của request có `# @name`; REST Client không tự chạy request phụ thuộc.

Ca lỗi cố ý trả **400/401/403/404/409** là kết quả mong đợi, không phải lý do dừng luồng. Response lỗi phải có `Content-Type: application/problem+json`. Nếu bước thành công trả lỗi ngoài dự kiến, xử lý lỗi đó trước khi tiếp tục.

Mỗi lần chạy lại toàn bộ sẽ tạo một kỳ thi mới và ghi danh/consent thật trong DB local. Thời gian mở/đóng được tính tự động là hiện tại +1 giờ/+3 giờ khi tạo; hoàn thành luồng trong 3 giờ. Consent được phép trước giờ mở thi. Nếu quá giờ đóng, chạy lại từ đầu để tạo kỳ thi mới.

## Xử lý nhanh

- **Connection refused**: kiểm tra backend đã chạy và `@baseUrl` đúng cổng.
- **401 khi login**: kiểm tra email/mật khẩu, trạng thái enabled và seed; đổi `.env` không tự đổi mật khẩu tài khoản đã tồn tại.
- **403 ở request cần thành công**: kiểm tra đúng role, cookie đang bật và đã chạy GET CSRF sau login. Backend khởi động lại làm mất session; chạy lại từ đầu.
- **Biến `{{...}}` không được thay thế**: chạy request có `# @name` được tham chiếu trước. File `.http` và `.env` phải cùng thư mục.
- **Session cũ gây nhầm tài khoản**: Command Palette → **Rest Client: Clear Cookies**, sau đó chạy lại từ bước 01.

Cú pháp file, biến response, `$dotenv`, ngày tương đối và cơ chế lưu cookie được đối chiếu với [tài liệu REST Client chính thức](https://github.com/Huachao/vscode-restclient#readme).
