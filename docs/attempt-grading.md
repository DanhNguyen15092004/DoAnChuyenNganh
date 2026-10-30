# Nộp bài và chấm điểm attempt

Phần mở rộng V7 của [autosave/timeout V6](attempt-answers.md). Java 21, Spring Boot,
dependency management, session JSESSIONID/CSRF và React + Vite JavaScript giữ nguyên.
Không có công bố đáp án/lời giải, chấm lại/phúc khảo/sửa điểm, thi lại/gia hạn,
tự luận/trọng số, camera/AI hoặc export.

## Schema và kết quả bền vững

Migration mới `V7__attempt_submission_and_grading.sql`, không sửa V1–V6.
Lưu kết quả **trực tiếp trên exam_attempts**, vì mỗi lượt chỉ có một kết quả và không
có lịch sử chấm lại trong phạm vi. PK attempt tự bảo đảm không thể có hai bộ kết quả;
không cần bảng/ID/FK kết quả riêng. Các cột mới:

| Cột | Kiểu | Ý nghĩa |
| --- | --- | --- |
| total_questions | INT nullable | Tổng câu trong đề published |
| answered_count | INT nullable | Chỉ đáp án đã lưu có selected_option_id khác null |
| correct_count | INT nullable | Số lựa chọn đã lưu khớp option đúng của câu |
| score | DECIMAL(4,2) nullable | Điểm /10 |
| graded_at | DATETIME(6) nullable UTC | Giờ server chấm |

CHECK bảo vệ kết quả all-null hoặc đầy đủ, 0<=correct<=answered<=total, total>0,
score trong [0,10], không có điểm khi IN_PROGRESS. All-null dành cho lượt V6 chưa
được backfill, không mang nghĩa điểm 0. Index `(status,graded_at,id)` hỗ trợ job.
Giữ ddl-auto=validate. V7 thay CHECK trạng thái/timestamp để nhận SUBMITTED;
không đổi timestamp hoặc dữ liệu cũ trong migration và không ghi điểm bằng SQL migration.

Mỗi câu cùng trọng số: đúng 1, sai/bỏ trống 0.
`BigDecimal.valueOf(correctCount).multiply(BigDecimal.TEN).divide(totalQuestions, 2, HALF_UP)`.
Không dùng double để tính hoặc lưu. Ví dụ 2/3 → 6.67; 1/6 → 1.67; bỏ trống toàn bộ → 0.00.
Không log hoặc trả correct option identifiers. Exam published đã bất biến, không cần snapshot mới.

## Trạng thái, deadline và transaction

| Hiện tại / thao tác | Điều kiện sau khi có lock | Kết quả |
| --- | --- | --- |
| IN_PROGRESS / submit | now < deadlineAt | SUBMITTED, chấm ngay |
| IN_PROGRESS / submit, timeout, đọc hoặc scheduler | now >= deadlineAt | TIMED_OUT, chấm ngay |
| IN_PROGRESS / timeout | now < deadlineAt | 409 TIMEOUT_NOT_DUE |
| IN_PROGRESS / GET result | now < deadlineAt | 409 RESULT_NOT_READY |
| Đã kết thúc, đã chấm | Mọi lần gọi lặp | Trả dữ liệu cũ, không chấm lại/đổi timestamp |
| Đã kết thúc, chưa chấm (V6) | Job hoặc request đọc/submit/timeout | Chấm một lần, giữ nguyên trạng thái và timestamp kết thúc |

SUBMITTED: effectiveEndedAt=finalizedAt=giờ server quyết định nộp trước hạn.
TIMED_OUT: effectiveEndedAt=deadlineAt, finalizedAt=giờ thực sự xử lý.
gradedAt của lượt mới bằng giờ xử lý, lượt backfill là giờ chấm sau này.

`AttemptFinalizationService.finishLocked` là **một** engine cho tất cả các đường.
Nó yêu cầu transaction MANDATORY và caller giữ write lock trên entity attempt.
Caller lấy lock trước rồi đọc Clock; quyền role/ownership luôn kiểm tra trước mutation.
Scheduler gọi `AttemptTimeoutService.finalizeDue` qua bean/proxy khác, mỗi lượt một
transaction, sau đó gọi chung engine. Không có self-invocation mất transaction.

Save answer cùng khóa parent attempt với finalize. Nếu save commit trước, nó được
tính điểm; nếu finalize trước, save bị từ chối. expectedRevision vẫn bảo vệ cập nhật cũ.
Submit không nhận answers/score/studentId/timestamp, chỉ đọc dữ liệu đã lưu trong DB.
Đọc bộ câu hỏi và chấm, chuyển trạng thái, ghi timestamps/score commit nguyên khối.
Lỗi grader/DB làm rollback toàn bộ; không catch lỗi rồi trả thành công.
Late-save vẫn trả Outcome rồi controller unwrap thành 409 **sau** transaction commit,
giữ được finalization và điểm timeout vừa ghi.

Availability trong GET attempt là **theo thời gian**: ACTIVE nếu now<deadlineAt,
EXPIRED nếu ngược lại. Quyền làm bài còn cần status=IN_PROGRESS. Vì vậy bài SUBMITTED
trước hạn có thể availability=ACTIVE nhưng questions=[] và save trả 409 ATTEMPT_FINISHED.
Sau hạn không trả đề. GET attempt giữ shape V6; điểm lấy qua endpoint result riêng.
Answers DTO cũ chỉ chứa lựa chọn riêng của sinh viên, không chứa đáp án đúng.

## Backfill và dữ liệu đang có

Kiểm tra chỉ đọc Docker local ngày 30/10/2026 xác nhận Flyway đang ở V6,
có **2 attempt TIMED_OUT** và 1 answer row, chưa có cột kết quả. Nhiệm vụ này không
áp dụng V7/chấm hai bài đó trên DB của người dùng, vì không deploy/restart stack.
Khi người dùng chạy backend mới, Flyway áp dụng V7 và scheduler bắt đầu backfill.

Mỗi sweep có hai lane hữu hạn: tối đa batchSize lượt IN_PROGRESS quá hạn, rồi tối đa
batchSize lượt SUBMITTED/TIMED_OUT có gradedAt=NULL. Dùng cấu hình timeout hiện có
`app.attempt.timeout.enabled` (mặc định true), `batch-size` (100, 1–1000),
`scan-delay-ms` (5000), `initial-delay-ms` (5000). Tối đa 2*batchSize ID mỗi sweep.
Chạy lại/restart tiếp tục từ DB; grade đã có không bị ghi đè. Lỗi một lượt được catch
ngoài transaction, không rollback batch còn lại. Row lỗi lặp có thể chiếm slot batch;
cần theo dõi warning. Không có timer riêng cho từng lượt hoặc phụ thuộc session/browser.

GET result cũng kích hoạt cùng engine khi đã hết giờ hoặc thiếu kết quả cũ, không
tạo bộ chấm thứ hai. Trước khi job chạy, danh sách giáo viên có score=null,
gradedAt=null, resultReady=false; không lấy 0 thay cho chưa chấm. Nếu chấm gặp lỗi thì
request trả lỗi an toàn và transaction rollback, không trả điểm giả.

## API thực tế

Session cookie hiện có, no-store trên tất cả response thành công bên dưới.
Submit cần token CSRF theo headerName từ `/api/v1/auth/csrf`. Không có JWT.

| Method | URL | Request | Response | Role/scope | CSRF | Pagination |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/student/attempts/{attemptId}/submit` | Body rỗng hoàn toàn | 200 ResultResponse, lặp trả cũ | STUDENT, chính mình | Có | Không |
| GET | `/api/v1/student/attempts/{attemptId}/result` | — | 200 ResultResponse / 409 RESULT_NOT_READY | STUDENT, chính mình | Không | Không |
| GET | `/api/v1/teacher/exams/{examId}/attempts` | page=0,size=20 mặc định | PageResponse<ManagedAttemptResponse> | TEACHER owner / ADMIN mọi exam | Không | page>=0,size 1–100; startedAt DESC,id DESC |
| GET | `/api/v1/teacher/exams/{examId}/attempts/{attemptId}/result` | — | 200 `{student,result}` / 409 RESULT_NOT_READY | TEACHER owner / ADMIN; attempt phải thuộc exam URL | Không | Không |

`POST submit` có `{}` hoặc bất kỳ body nào đều 400. Đến/sau deadline trả TIMED_OUT,
không giả là nộp trước giờ. Submit gọi lặp sau khi đã SUBMITTED vẫn trả SUBMITTED cũ.
Các endpoint start/get/save/timeout V6 tiếp tục tồn tại, cùng engine khi finalize.

ResultResponse chỉ có:

```json
{
  "attemptId": 10,
  "examId": 20,
  "title": "Kỳ thi demo",
  "status": "SUBMITTED",
  "startedAt": "2030-01-01T00:00:00Z",
  "deadlineAt": "2030-01-01T00:01:00Z",
  "effectiveEndedAt": "2030-01-01T00:00:30Z",
  "totalQuestions": 3,
  "answeredCount": 2,
  "unansweredCount": 1,
  "correctCount": 1,
  "score": 3.33,
  "gradedAt": "2030-01-01T00:00:30Z"
}
```

ID/timestamp minh họa. Không có question/option/correct key/lời giải trong response kết quả.
`student` của giáo viên chỉ `{id,email,displayName}`. List row chỉ:
`{attemptId,student,status,startedAt,deadlineAt,effectiveEndedAt,score,gradedAt,resultReady}`.
PageResponse giữ convention `{content,page,size,totalElements,totalPages}`.
Enrollment chưa bắt đầu không tạo attempt và không xuất hiện trong danh sách này.

ProblemDetail: 400 body/pagination sai; 401 chưa đăng nhập; 403 role/CSRF;
404 tài nguyên thiếu/ngoài ownership/attempt khác exam; 409 RESULT_NOT_READY.
Save bài SUBMITTED dùng 409 ATTEMPT_FINISHED, timeout vẫn ATTEMPT_TIMED_OUT;
ANSWER_REVISION_CONFLICT và TIMEOUT_NOT_DUE giữ nguyên. Coded 409 có serverTime.
Lỗi CSRF/sai quyền vẫn không có code riêng; frontend không đoán theo chuỗi message.

## Frontend

- Nút Nộp bài tạm khóa lựa chọn, chờ một flight/queue mỗi câu hết, GET đồng bộ mới.
  Có lỗi/conflict/chưa xác nhận thì dừng, nêu số câu cần xử lý; không tự retry write.
- Xác nhận hiển thị số câu **đã lưu** và bỏ trống, cảnh báo không sửa sau nộp.
  Hủy xác nhận mở lại thao tác nếu còn giờ. Chỉ POST body rỗng khi xác nhận.
- Nếu hết giờ lúc chờ/xác nhận, ưu tiên timeout. Response server quyết định cuối cùng;
  chỉ có một route kết quả. Submit mất response thì GET trạng thái, không giả thất bại
  hoặc tự POST lại. GET cũng thất bại thì báo chưa xác nhận, người dùng kiểm tra lại.
- Route sinh viên `/#/student/attempts/{attemptId}/result`, reload tải từ server.
  Vào attempt đã kết thúc chuyển đến kết quả. Hiển thị /10, số đúng/đã trả lời/bỏ trống,
  timestamps và SUBMITTED/TIMED_OUT, không đáp án đúng.
- Giáo viên từ chi tiết exam → Xem danh sách bài thi, route
  `/#/teacher/exams/{examId}/attempts`; chi tiết
  `/#/teacher/exams/{examId}/attempts/{attemptId}/result`.
  Có pagination/loading/empty/error, score null hiện “Chưa có điểm”. ADMIN dùng cùng UI.
- Bảng điều hướng/state autosave V6 giữ nguyên nguồn state; đang lưu dùng notice trung
  tính, lỗi/conflict mới dùng đỏ. Không đưa token/đề/đáp án vào localStorage.

## Chạy và demo

MySQL chạy theo README, JDK 21. Chạy backend source mới tại thư mục chứa pom.xml:

```powershell
.\mvnw.cmd spring-boot:run
```

Terminal khác:

```powershell
cd frontend
npm ci
# Nếu chưa có: Copy-Item .env.example .env.local
# Đặt BACKEND_TARGET trong .env.local đúng port backend đang chạy (SERVER_PORT, mặc định 8080).
npm run dev
```

Mở http://127.0.0.1:5173. Seeder local/cách lấy studentId theo README hiện có;
không đổi password/role tài khoản có sẵn. Docker image cũ cần người dùng chủ động
rebuild theo docs/docker.md mới có phần này; agent không tự deploy.

1. Giáo viên tạo exam prefix `GRADING-DEMO-`, duration 1 phút, cửa sổ mở đủ dài và đã mở;
   thêm 3 câu, lưu/publish, ghi danh ID sinh viên thật. Không đoán ID seed.
2. Sinh viên consent → bắt đầu → chọn 2 câu, để trống 1. Đợi xác nhận đã lưu, bấm Nộp bài:
   hộp xác nhận phải báo đã lưu 2/3, bỏ trống 1. Hủy một lần để kiểm tra mở lại thao tác.
3. Nộp lại/xác nhận, Network thấy POST body rỗng 200 SUBMITTED; trang kết quả có điểm,
   không đề/đáp án đúng. Reload giữ nguyên điểm và gradedAt, không nộp lại.
4. Giáo viên mở danh sách bài thi và kết quả đúng exam; giáo viên khác không được đọc,
   ADMIN được đọc. Enrollment chưa bắt đầu không có attempt; đang làm chưa có điểm.
5. Tạo lượt mới ở exam khác, chọn/lưu rồi đóng tab trước hạn. Sau deadline và chu kỳ
   scheduler, mở lại: TIMED_OUT và có điểm. PUT sửa bị chặn, submit lặp giữ nguyên kết quả.
6. Thử offline khi đang lưu: không được nộp âm thầm. Online đồng bộ/giải quyết câu lỗi rồi
   chủ động nộp nếu còn giờ. Offline sau submit cần GET xác minh trước khi thử lại.

Ghi lại examId/attemptId của demo, không xóa/reset để dọn dữ liệu. Kết quả thực chạy và
giới hạn browser: [attempt-grading-report.md](attempt-grading-report.md).
