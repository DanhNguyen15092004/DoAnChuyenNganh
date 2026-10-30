# Lưu đáp án và tự kết thúc khi hết giờ

> Tài liệu giai đoạn V6. V7 bổ sung SUBMITTED, nộp bài và chấm điểm, refactor engine
> finalize dùng chung: [attempt-grading.md](attempt-grading.md). Các mô tả “chưa chấm”
> dưới đây chỉ áp dụng giai đoạn V6; GET attempt hiện phân biệt availability theo giờ
> với trạng thái SUBMITTED/TIMED_OUT.

Đây là phần mở rộng contract V5 trong `attempt-module.md`. Không có nộp sớm,
chấm điểm, đáp án đúng, gia hạn, camera/AI hoặc WebSocket.

## API đã đối chiếu source

Tất cả endpoint dùng session STUDENT, chỉ chủ sở hữu attempt, không pagination.
Response thành công có `Cache-Control: no-store`. PUT/POST cần CSRF theo headerName
từ `/api/v1/auth/csrf`, dùng chung client cookie hiện có.

| Method | URL | Request | Response thành công | Role | CSRF | Pagination |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/student/exams/{examId}/attempt` | Body rỗng | 201 lượt mới + Location; 200 lượt cũ | STUDENT, chính mình | Có | Không |
| GET | `/api/v1/student/exams/{examId}/attempt` | — | 200 AttemptResponse, chưa có 404 | STUDENT, chính mình | Không | Không |
| GET | `/api/v1/student/attempts/{attemptId}` | — | 200 AttemptResponse | STUDENT, chính mình | Không | Không |
| PUT | `/api/v1/student/attempts/{attemptId}/answers/{questionId}` | `{ "selectedOptionId": 123, "expectedRevision": 0 }`, option có thể null | 200 `{questionId,selectedOptionId,revision,savedAt,serverTime}` | STUDENT, chính mình | Có | Không |
| POST | `/api/v1/student/attempts/{attemptId}/timeout` | Body rỗng, không nhận đáp án | 200 `{attemptId,status,deadlineAt,finalizedAt,effectiveEndedAt,serverTime}` | STUDENT, chính mình | Có | Không |

AttemptResponse giữ các field cũ và thêm `status`, `finalizedAt`, `effectiveEndedAt`,
`answers:[{questionId,selectedOptionId,revision,savedAt}]`. Khi IN_PROGRESS và còn giờ:
availability=ACTIVE, trả đề cùng đáp án riêng. Tại deadline: EXPIRED, questions=[];
không trả correct/isCorrect/correctOptionId hoặc lời giải. Answers chỉ là lựa chọn của
sinh viên. `/student/exams/{id}` metadata vẫn không có nội dung đề.

| Lỗi | Status / code thuộc ProblemDetail |
| --- | --- |
| Chưa đăng nhập | 401 theo auth hiện có (CSRF filter kiểm tra trước đối với write) |
| Sai role / CSRF | 403 hiện có, không có code riêng để frontend đoán phân biệt |
| Không có attempt / không sở hữu | 404 |
| Question không thuộc exam / option không thuộc question / DTO không hợp lệ / timeout có body | 400 |
| Revision không còn đúng | 409 `ANSWER_REVISION_CONFLICT` |
| Đã hết giờ hoặc đã TIMED_OUT | 409 `ATTEMPT_TIMED_OUT` |
| Gọi timeout khi chưa tới hạn | 409 `TIMEOUT_NOT_DUE` |
| Xung đột hạ tầng DB/lock | 409 handler exam hiện có, có thể không có code |

Ba lỗi domain mới có `code`, `detail`, `serverTime`; không phân loại bằng chuỗi detail.
Revision logic ban đầu 0, mỗi write được chấp nhận tăng 1, kể cả chọn lại cùng option.
Clear là selectedOptionId=null, giữ row và revision. Omit selectedOptionId cũng được
Jackson đọc thành null; client luôn gửi field tường minh. Không nhận thêm timestamp,
identity hoặc field không nằm trong request DTO.

## Migration và transaction

V6 mới thêm `exam_attempt_answers`: id PK, attempt_id/question_id/selected_option_id FK,
UNIQUE(attempt_id,question_id), revision>0, updated_at UTC DATETIME(6). Option nullable.
Service kiểm tra quan hệ exam → question → option. Không đưa nội dung/correct vào bảng đáp án.

V6 thêm exam_attempts.status VARCHAR(20) với CHECK chính xác IN_PROGRESS/TIMED_OUT,
finalized_at và effective_ended_at. CHECK ràng buộc timestamp khi chốt;
index `(status,deadline_at,id)` phục vụ tìm lượt quá hạn. Các row V5 được bổ sung
IN_PROGRESS; scheduler sẽ xử lý row quá hạn sau khi khởi động, không sửa deadline cũ.
Giữ nguyên V1–V5, ddl-auto=validate. Không cần data reset hoặc xóa đáp án khi clear.

Save/GET/timeout lock attempt PESSIMISTIC_WRITE, **đọc Clock sau lock**, so deadline
không có grace period, kiểm tra revision và ghi trong cùng transaction. Start vẫn lock
exam trước như cũ, sau đó lock attempt nếu có; không có luồng lấy attempt rồi lock exam.
Các request ngoài transaction dùng READ_COMMITTED để không đọc lại answer snapshot cũ
sau khi phải chờ lock. Mọi write vào answer đi qua khóa parent attempt.

Khi save quá hạn, service trả Outcome lỗi sau khi đánh dấu TIMED_OUT; proxy commit rồi
controller mới unwrap thành 409. Không throw làm rollback trạng thái vừa finalize.
finalizedAt là giờ xử lý, effectiveEndedAt luôn bằng deadlineAt. Lặp/đồng thời giữ nguyên
các timestamp đã chốt; đáp án hợp lệ trước hạn không đổi. GET có thể finalize, nên không
còn là transaction read-only như mô tả V5 ban đầu. Availability vẫn EXPIRED tại deadline,
kể cả trước khi scheduler kịp persist.

## Scheduler và restart

`AttemptTimeoutScheduler.sweep()` lấy page đầu hữu hạn các ID IN_PROGRESS đã tới hạn,
sắp theo deadline,id, rồi gọi **bean khác** `AttemptTimeoutService.finalizeDue` qua proxy.
Mỗi attempt một transaction/lock/đọc giờ; lỗi một attempt được catch riêng, không rollback
các attempt còn lại trong batch. Không tải toàn bảng hoặc tạo timer riêng mỗi lượt.
Lần quét tiếp chọn các row còn IN_PROGRESS; sau restart dữ liệu DB là nguồn tiếp tục.
Nếu một row liên tục lỗi, nó vẫn được thử ở lần quét sau; cần theo dõi warning vận hành.

| Property | Mặc định | Giới hạn |
| --- | --- | --- |
| `app.attempt.timeout.enabled` | true | false chỉ tắt scheduled scanning, save vẫn kiểm tra deadline |
| `app.attempt.timeout.batch-size` | 100 | 1–1000 |
| `app.attempt.timeout.scan-delay-ms` | 5000 | >=10 |
| `app.attempt.timeout.initial-delay-ms` | 5000 | >=0 |

Có thể đặt bằng environment `APP_ATTEMPT_TIMEOUT_BATCH_SIZE`,
`APP_ATTEMPT_TIMEOUT_SCAN_DELAY_MS`, v.v. Profile test tắt scheduler tự chạy để Clock
của các test cũ được xác định; test scheduler bật lại tường minh. Endpoint timeout chỉ
để UI phản hồi sớm; không phụ thuộc trình duyệt, session còn sống hoặc việc gửi batch cuối.
Session backend vẫn trong bộ nhớ; restart mất đăng nhập nhưng không mất attempt/answers.

## Frontend và điều hướng

React + Vite **JavaScript**. `AnswerSession` là state dùng chung cho radio, bỏ chọn,
ô số và thống kê. `desired` là lựa chọn UI, `confirmed/revision` là xác nhận server.
Một PUT đang chạy mỗi câu; đổi nhanh giữ ý định mới nhất, response cũ chỉ cập nhật
confirmed và revision rồi gửi ý định cuối nếu còn giờ. Không kéo radio về giá trị cũ.

Sau network/response lỗi, giữ lựa chọn trong bộ nhớ, đánh dấu chưa xác nhận và GET.
Chỉ nút người dùng mới retry, luôn GET trước PUT. Conflict không tự ghi đè: người dùng
chọn “Dùng dữ liệu máy chủ để chọn lại”, đọc dữ liệu hiện hành rồi chủ động chọn lại.
Không tự retry mutation, lưu localStorage, log cookie/token/password hoặc đọc JSESSIONID.

Các ô số đúng thứ tự đề, bấm để cuộn/focus tiêu đề câu, có `aria-current` và “Đang xem”.
Ký hiệu ○/↻/✓/!/⚠, chú thích và nhãn hỗ trợ đọc màn hình; không chỉ màu.
`Đã chọn` đếm desired khác null; `Chưa trả lời=N-X`; `Chưa lưu` gồm đang lưu/lỗi/conflict
và pending clear. Bỏ chọn cập nhật ngay, tới khi server xác nhận mới hết chưa lưu.
Desktop nằm bên cạnh, mobile details thu gọn mặc định. Reload mới dựng state từ GET.
Route/beforeunload hiện có cảnh báo khi unsaved>0, không gửi beacon để giả bảo đảm lưu.

Đồng hồ vẫn từ deadlineAt/serverTime và performance.now. Về 0 khóa và ẩn đề ngay,
POST timeout bằng CSRF; TIMEOUT_NOT_DUE dẫn tới GET reanchor. Mất mạng hiện
“Đã hết giờ. Chưa xác nhận được trạng thái với server.” Kết nối lại/focus/poll GET để
xác minh. Chỉ response TIMED_OUT mới báo server đã kết thúc; không hiển thị điểm.

## Chạy và demo

Khởi động backend/MySQL theo README; backend mới tự áp dụng V6. Không reset DB.
Frontend:

```powershell
cd frontend
npm ci
# Lần đầu: Copy-Item .env.example .env.local
# Đặt BACKEND_TARGET trong .env.local theo port backend thực tế (SERVER_PORT, mặc định 8080).
npm run dev
```

Mở http://127.0.0.1:5173. Dev proxy /api cùng origin, credentials/session/CSRF giữ nguyên.
Seeder local và cách lấy studentId theo README hiện có; không đổi mật khẩu/role account cũ.
Để demo nhanh, giáo viên tạo exam prefix `AUTOSAVE-DEMO-`, duration=1 phút,
mở trước hiện tại vài phút, đóng sau ít nhất vài phút. Thêm >=2 câu, mỗi câu một đáp án
đúng, lưu/publish, ghi danh studentId thật. Sinh viên consent rồi bắt đầu:

1. Chọn câu 1 → kiểm tra ↻ rồi ✓; ô số/stat phải đổi ngay. Bỏ chọn → selected giảm,
   unsaved tăng rồi về 0 khi server xác nhận. Bấm số câu 2 và kiểm tra focus/cuộn.
2. Chọn lại, reload sau ✓: cùng attempt/deadline, lựa chọn khôi phục. Thử đổi nhanh.
3. DevTools offline lúc chọn: còn lựa chọn, có chưa lưu. Online → nút đồng bộ/thử lại.
4. Hai tab cùng attempt, sửa cùng câu ở revision cũ: 409 conflict, không tự ghi đè.
5. Tới deadline: khóa UI, POST timeout/GET; status TIMED_OUT, questions=[];
   PUT sau hạn 409. Không nộp sớm/chấm điểm.
6. Tạo lượt khác, đóng tab trước hạn. Sau deadline + chu kỳ quét, backend chốt dù
   không có trình duyệt. Login lại nếu cần, mở lượt cũ: đã kết thúc, không sửa được.

Không xóa dữ liệu demo; ghi lại examId/attemptId/tiêu đề đã tạo. Testcontainers dùng DB
độc lập. Bằng chứng thực chạy và phần chưa xác minh: [attempt-answers-report.md](attempt-answers-report.md).
Chưa xác minh HTTPS production hoặc deployment nhiều instance.
