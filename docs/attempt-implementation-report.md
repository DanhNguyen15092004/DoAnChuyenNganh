# Báo cáo attempt — 30/10/2026

## Baseline và phạm vi

Đã đọc source backend/frontend, migration V1–V4, auth/session/registration, principal,
exam/enrollment/consent, error handlers và tài liệu hiện có trước khi sửa. Không tìm thấy
AGENTS.md trong repository hoặc các thư mục cha đã kiểm tra. Source xác nhận registration
đã có; attempt chưa có. Repo có nhiều thay đổi chưa commit từ các lượt trước, được giữ lại.

Baseline thực chạy trước khi sửa source:

- Maven verify với Java 21: **59 test, failures=0, errors=0, skipped=0**, BUILD SUCCESS,
  thời gian 4 phút 57 giây; log local `target/attempt-baseline.log`.
- Frontend lint/build PASS, test PASS 3/3.

Lượt này chỉ bắt đầu/đọc/tiếp tục lượt, deadline server và đề không có đáp án đúng.
Không thêm lưu đáp án, nộp/chấm, scheduler finalize, retake/gia hạn, camera/AI/WebSocket.
Contract, schema/locking và demo: [attempt-module.md](attempt-module.md).

## File và migration

| Loại | File |
| --- | --- |
| Migration mới | `src/main/resources/db/migration/V5__create_exam_attempts.sql` |
| Model/repository | `exam/ExamAttempt.java`, `exam/ExamAttemptRepository.java` |
| DTO/service/controller | `exam/AttemptContracts.java`, `exam/ExamAttemptService.java`, `exam/StudentAttemptController.java` |
| Test mới | `src/test/java/com/example/doanchuyennganh/exam/ExamAttemptIntegrationTest.java` |
| Frontend mới | `frontend/src/AttemptPages.jsx`, `frontend/src/attemptTime.js`, `frontend/tests/attemptTime.test.js` |
| Frontend sửa | `frontend/src/main.jsx`, `frontend/src/style.css` |
| Tài liệu mới | `docs/attempt-module.md`, báo cáo này |
| Tài liệu cập nhật | README root/frontend, `docs/exam-module.md`, `docs/frontend-contract.md` |

Java trong bảng nằm dưới `src/main/java/com/example/doanchuyennganh/` khi không ghi đủ path.
V5 có 5 cột id/enrollment_id/started_at/deadline_at/created_at, FK/UNIQUE enrollment,
NOT NULL và CHECK deadline>start; không lặp exam_id/student_id hay tạo snapshot table.
UTC DATETIME(6), Hibernate vẫn validate. V5 mới được áp dụng trên Testcontainers,
không tự áp dụng vào database local/prod của người dùng.

Snapshot SHA-256 trước/sau xác nhận trong các source có sẵn chỉ main.jsx/CSS thay đổi;
toàn bộ Java/config/test cũ, V1–V4, auth/security giữ nguyên. POM, package/lockfile,
Dockerfile/compose và CI cũng không đổi. Không đọc/in `.env`, thêm dependency, nâng version,
nới role/CORS/CSRF, hoặc sửa test cũ để làm pass.

## API thực tế

| Method | Endpoint | Thành công |
| --- | --- | --- |
| POST | `/api/v1/student/exams/{examId}/attempt` | Body rỗng; 201 + Location GET attempt lần đầu, 200 lượt cũ |
| GET | `/api/v1/student/attempts/{attemptId}` | 200 chỉ chủ sở hữu |
| GET | `/api/v1/student/exams/{examId}/attempt` | 200 lượt của mình; 404 chưa có |

POST giữ auth + CSRF, namespace student dùng filter hiện có và service kiểm tra role độc lập.
Kiểm tra enrollment/consent/window sau khóa exam; existing attempt được trả trước điều kiện
tạo mới. Deadline min(startedAt+duration, closesAt), không sửa thời gian khi lặp/reload.
Response DTO riêng chỉ có các field trong contract. EXPIRED trả questions=[];
metadata exam sinh viên cũ vẫn đúng 12 field, không lộ đề.

## Kiểm chứng backend và HTTP

Lệnh verify cuối thực chạy bằng Java 21 và Maven cache có quyền truy cập Docker:

```bash
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository verify
```

**BUILD SUCCESS: 74 test, failures=0, errors=0, skipped=0, 4 phút 54 giây.**
Log local `target/attempt-verify.log`; surefire reports trong target.
Gồm nguyên 59 test cũ + 15 test attempt mới. Focused run
`-Dtest=ExamAttemptIntegrationTest test` cũng PASS 15/15 trước verify cuối.

| Test attempt | Bằng chứng đạt |
| --- | --- |
| Start → GET by ID/current → POST lặp | Tomcat RANDOM_PORT, HTTP client/cookie login thật, 201/Location rồi 200; cùng ID/start/deadline, DB count=1, no-store |
| Ownership/existence | Exam thiếu/draft, không ghi danh, attempt không có, STUDENT khác (kể cả đã ghi danh cùng exam) đều 404 |
| Consent | Thiếu hoặc record phiên bản cũ trả 409, không insert attempt |
| Opens/closes | Trước opensAt 1 microsecond bị chặn; đúng opensAt được; đúng closesAt không tạo mới |
| Deadline | Đủ thời lượng: NOW+10 phút; bắt đầu muộn: closesAt; 1 microsecond trước close vẫn hợp lệ |
| Expiration | Đúng deadline EXPIRED, questions=[] trên cả GET/GET current/POST lặp; trước deadline 1 microsecond còn ACTIVE |
| Lặp sau khi exam đóng | 200 cùng lượt cũ, không cộng giờ, GET vẫn đọc metadata expired |
| Auth/CSRF | Anonymous 401 với CSRF hợp lệ; teacher/admin 403; thiếu/sai CSRF 403; service tự kiểm tra role |
| Client tampering | JSON rỗng, studentId, startedAt, deadlineAt, duration và body khác đều 400, không insert |
| Response | Assert chính xác tập field top-level/question/option; không correct/isCorrect/correctOptionId/lời giải; API metadata cũ không thêm câu hỏi |
| Schema | Flyway v5 success; JPA validate; SQL lỗi unique=1062, FK=1452, NOT NULL=1048, CHECK=3819 |

Test thời gian dùng Clock inject/điều chỉnh cố định, không sleep.
Đây là HTTP/database thật trong Testcontainers, không mock user hoặc AuthenticationManager.

## Đồng thời

Hai worker mở TransactionTemplate riêng, lấy SELECT CONNECTION_ID() riêng, chờ
CountDownLatch trước cùng gọi start. Assert hai ID connection khác nhau; không thay
race bằng hai lời gọi tuần tự. Không catch constraint rồi chạy tiếp transaction lỗi.
Kết quả trong focused và full verify:

```text
ATTEMPT_RACE: separateConnections=2 created=1 existing=1 sameIdAndDeadline=true rows=1
```

Thông báo chỉ in sau các assertion: một created=true, một created=false, response bằng nhau,
và SQL COUNT(*) theo enrollment bằng 1. Khóa exam trước enrollment/attempt phù hợp thứ tự
publish/enroll/consent/questions hiện có. Chưa load test hoặc kiểm chứng mọi lịch lập lịch có thể có.

## Frontend

`npm --prefix frontend run lint`, `test`, `run build`: **PASS**, 6 test thuần, không skip.
Ba test mới kiểm tra budget server/elapsed monotonic, nhảy thời gian/tab ngủ và reanchor,
định dạng countdown. Không dùng test thuần để tuyên bố browser flow đã chạy.

Chi tiết exam GET lượt có sẵn trước, Bắt đầu chỉ sau xác nhận của người dùng; khóa khi gửi.
Lượt cũ có Tiếp tục/Xem trạng thái. Route chứa attemptId, reload chỉ GET, không POST lại.
Đề là văn bản chỉ đọc; không lựa chọn trả lời/nút lưu/nộp giả. Đồng hồ từ deadlineAt/serverTime
và performance.now; GET lại khi focus/visible, mỗi 30 giây visible và khi local về 0.
Không lưu đề/token vào localStorage; api.js/session/CSRF hiện có không đổi.

Lần kiểm tra đầu gặp import Role trùng tên trong test mới và lint dependency hook chưa đủ;
đã sửa import rõ ràng và destructure state/hook dependency, rồi chạy lại đạt. Không tắt lint,
sửa kỳ vọng test cũ hoặc đổi security. Javaagent/Hikari warning khi Testcontainers kết thúc
context không làm test fail; không thay cấu hình để che warning.

## Browser và dữ liệu

Chưa kiểm chứng browser flow login → mở exam → start → xem đề → reload → tiếp tục.
Browser API trả `The browser access host is unavailable`. Sau khi đọc skill computer-use,
fallback Windows cũng bị công cụ tự dừng do không xác định được URL trình duyệt đủ chắc
chắn để thực thi policy. Đã dừng thao tác UI, không dùng công cụ khác để vượt chặn.
Do đó chưa xác minh trực quan modal/loading, tab sleep/focus, route reload hoặc Network tab.

Chỉ tạo fixture trong DB Testcontainers (tài khoản `*.attempt@example.test`, kỳ thi prefix
`ATTEMPT-TEST-` và enrollment/consent/attempt liên quan). Không tạo demo trong DB người dùng,
không sửa tài khoản/đề hiện có, reset hoặc xóa dữ liệu local. Testcontainers tự quản lý lifecycle
DB test độc lập như các test cũ. Không tạo fixture Docker lưu lâu dài cho browser.

Theo yêu cầu không deploy, không rebuild/restart stack Docker đang chạy hoặc push remote.
UI/backend đang chạy từ image cũ chưa có phần attempt này. Muốn demo bản mới, người dùng
cần khởi động source mới hoặc chủ động rebuild/recreate theo README; Flyway sẽ áp dụng V5
khi backend mới khởi động. Xem demo cụ thể ở [attempt-module.md](attempt-module.md).
Chưa kiểm chứng CI remote/HTTPS production hoặc lifecycle nộp/chấm (ngoài phạm vi).
