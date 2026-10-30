# Lượt thi: bắt đầu và tiếp tục

Phạm vi ngày 30/10/2026: tạo một lượt cho mỗi enrollment, chốt deadline, đọc đề khi
ACTIVE, tiếp tục sau reload. Chưa có lưu đáp án/nộp bài/chấm điểm, scheduler finalize,
thi lại/gia hạn, camera/AI hoặc WebSocket. EXPIRED chỉ là hết thời gian truy cập đề.

## Contract API

Giữ session JSESSIONID và HttpSessionCsrfTokenRepository hiện có. Mọi response thành công
của attempt có Cache-Control: no-store. Không có phân trang trong ba endpoint này.

| Method | URL | Request | Response | Role | CSRF |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/student/exams/{examId}/attempt` | Body rỗng hoàn toàn | 201 tạo mới + Location `/api/v1/student/attempts/{attemptId}`; 200 lượt cũ, không Location | STUDENT, enrollment chính mình | Bắt buộc |
| GET | `/api/v1/student/attempts/{attemptId}` | — | 200 AttemptResponse, chỉ chủ sở hữu | STUDENT | Không |
| GET | `/api/v1/student/exams/{examId}/attempt` | — | 200 AttemptResponse; chưa có 404 | STUDENT, enrollment chính mình | Không |

Không nhận JSON `{}` hoặc bất kỳ body nào, kể cả studentId/startedAt/deadlineAt/duration.
Client gọi POST chỉ khi người dùng xác nhận; kiểm tra tồn tại bằng GET.

```json
{
  "attemptId": 10,
  "examId": 20,
  "title": "Kỳ thi mẫu",
  "startedAt": "2030-01-01T08:00:00Z",
  "deadlineAt": "2030-01-01T08:30:00Z",
  "serverTime": "2030-01-01T08:00:10Z",
  "availability": "ACTIVE",
  "questions": [
    {
      "id": 30, "content": "Câu hỏi", "position": 1,
      "options": [
        {"id": 40, "content": "Lựa chọn A", "position": 1},
        {"id": 41, "content": "Lựa chọn B", "position": 2}
      ]
    }
  ]
}
```

ID/thời gian chỉ minh họa. `AttemptContracts` là DTO riêng; không chuyển tiếp DTO giáo viên,
entity hoặc principal. Question chỉ id/content/position/options, option chỉ id/content/position;
không có correct/isCorrect/correctOptionId, đáp án mẫu hoặc lời giải.
Khi `serverTime >= deadlineAt`, availability=EXPIRED và questions=[]; không query nội dung đề
để dựng response expired. Exam metadata API sinh viên cũ vẫn không có questions/options.

## Quy tắc và lỗi

Khi chưa có attempt: exam PUBLISHED, enrollment của actor, consent đúng phiên bản,
`opensAt <= serverNow < closesAt`. Chốt startedAt=serverNow, deadlineAt bằng giá trị nhỏ hơn
giữa startedAt+durationMinutes và closesAt. Dùng Clock inject được, chuẩn hóa microsecond.

Sau khi có attempt, cả POST lặp và GET trả ID/startedAt/deadlineAt đã lưu, không cộng giờ,
kể cả exam đã đóng hoặc deadline đã qua. Không kiểm tra lại cửa sổ/consent để tạo lại lượt cũ.
Mọi đường đọc vẫn xác thực role/enrollment/ownership. ACTIVE khi now<deadlineAt,
EXPIRED tại đúng deadlineAt; không lưu enum trạng thái hoặc điểm trên DB.

| Trường hợp | ProblemDetail status |
| --- | --- |
| Chưa đăng nhập | 401 (POST cần CSRF hợp lệ để đi qua filter trước) |
| TEACHER/ADMIN gọi namespace student | 403 |
| Thiếu/sai CSRF trên POST | 403 |
| Exam không tồn tại/chưa published, không ghi danh, attempt không có/không sở hữu | 404 |
| Chưa consent/consent cũ, chưa mở/đã đóng khi chưa có attempt | 409 |
| POST có body hoặc tham số path sai kiểu | 400 |
| Xung đột DB/lock | 409 theo handler exam hiện có |

Không thêm mã lỗi riêng. Frontend dùng status và detail, không suy đoán code qua chuỗi.
401/403 vẫn theo quy tắc ưu tiên CSRF của filter Spring Security hiện có.

## Schema và locking

Migration mới **V5__create_exam_attempts.sql**, giữ nguyên V1–V4.
`exam_attempts`: id BIGINT auto increment PK, enrollment_id BIGINT NOT NULL FK UNIQUE,
started_at/deadline_at/created_at DATETIME(6) NOT NULL UTC, CHECK deadline_at>started_at.
Không lặp exam_id/student_id: lấy qua enrollment. Các cột thời gian và enrollment đánh dấu
JPA updatable=false. Hibernate tiếp tục ddl-auto=validate, Flyway tự áp dụng V5 lúc khởi động.

Không cần snapshot vì đề PUBLISHED đã bất biến. Service không sửa quy tắc publish.
POST transaction lấy PESSIMISTIC_WRITE trên **exam trước** mọi consistent read,
giống publish/questions/enroll/consent. Sau lock mới đọc enrollment/attempt và kiểm tra
điều kiện/time. Hai request cùng exam serialize; transaction thứ hai thấy record đã commit.
Unique enrollment_id là lớp bảo vệ cuối. Không synchronized, không catch constraint rồi
tiếp tục transaction rollback-only. GET chỉ đọc, không tạo hoặc sửa attempt.
Đánh đổi: các mutation cùng exam bị serialize; chưa load test throughput.

## Frontend và đồng hồ

React + Vite JavaScript/JSX, dùng chung API client credentials include và CSRF bộ nhớ.
Trang chi tiết gọi GET current attempt; 404 chỉ được coi là chưa có sau khi chi tiết exam
đã tải được. Nếu lookup lỗi khác, UI yêu cầu kiểm tra lại, không POST để dò tồn tại.
Lượt cũ hiện Tiếp tục/Xem trạng thái. Bắt đầu chỉ bật khi API exam báo OPEN và đã consent;
backend luôn kiểm tra lại. Xác nhận nhắc thời gian vẫn chạy khi đóng trang/mất mạng.

Route `/#/student/attempts/{attemptId}` reload chỉ GET sau /me, không POST, không localStorage.
Đề là văn bản/các lựa chọn chỉ đọc, không có radio trả lời hoặc nút lưu/nộp giả.
Hiển thị chính xác “Chức năng lưu đáp án đang được phát triển”.

Đồng hồ ước tính `deadlineAt - serverTime - elapsed(performance.now())`, không giảm biến
đếm, không dùng Date.now của máy làm quyết định nghiệp vụ. Lấy GET mới khi focus,
visibility trở lại, mỗi 30 giây khi visible và khi đồng hồ local về 0. Lỗi, đang xác minh
hoặc về 0 thì tạm ẩn đề; chỉ server xác nhận EXPIRED. Request cũ sau reload bị bỏ qua.
Không tự POST lại khi mất response; người dùng có nút GET kiểm tra lại trước khi thử tiếp.

## Demo thủ công

Chạy backend/frontend mới theo README, dùng tài khoản local của bạn. Không đoán studentId.
Ở lượt này không tự cập nhật Docker đang chạy hoặc áp dụng V5 vào database của người dùng.

1. STUDENT login, lấy ID thật ở thông tin tài khoản rồi logout.
2. TEACHER login, tạo exam có prefix `ATTEMPT-DEMO-`, thời gian đã mở/chưa đóng,
   khoảng mở đủ thời lượng (ví dụ duration 10 phút, mở trước hiện tại 5 phút, đóng sau 30 phút).
3. Thêm hai câu/mỗi câu một đáp án đúng, lưu, publish, ghi danh studentId; logout.
4. STUDENT login → chi tiết → đọc và consent. Ban đầu GET current attempt trả 404.
5. Bắt đầu thi → xác nhận. Network phải thấy POST body rỗng trả 201/Location;
   trang đề có attemptId, startedAt/deadlineAt, câu hỏi/lựa chọn không có đáp án đúng.
6. Reload: /me và GET attempt, không POST; so sánh cùng ID/deadline. Về chi tiết → Tiếp tục
   phải về lượt đó. Gọi POST chủ động lần nữa chỉ trả 200 với lượt cũ.
7. Quay lại tab sau khi ngủ: GET đồng bộ server. Tới deadline, đề bị ẩn, GET trả EXPIRED,
   questions=[]; UI không tuyên bố đã nộp/chấm.
8. STUDENT khác hoặc teacher/admin không được đọc lượt đó. Không xóa/reset dữ liệu demo;
   ghi lại các ID đã tạo. Dữ liệu Testcontainers độc lập với local DB.

Các lệnh kiểm tra và kết quả: [attempt-implementation-report.md](attempt-implementation-report.md).
