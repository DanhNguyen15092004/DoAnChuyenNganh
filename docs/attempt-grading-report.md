# Báo cáo submit và grading — 30/10/2026

## Baseline và khảo sát

Đã đọc attachment, source hiện tại (auth/security/error handling, exam, attempt,
Clock/lock/deadline/revision, scheduler/timeout, API client/route/UI), migration V1–V6,
các test và tài liệu liên quan. Không tìm thấy AGENTS.md trong repo hoặc các thư mục
cha đã kiểm tra. Không coi báo cáo/prompt trước là bằng chứng implementation.
Working tree có thay đổi chưa commit từ lượt autosave V6; đã snapshot và giữ lại.

Baseline chạy mới trước sửa source:

- `./mvnw ... verify`: **86 test, failures=0/errors=0/skipped=0**, BUILD SUCCESS,
  **6 phút 49 giây**, log `target/grading-baseline.log`.
- Frontend: lint PASS, **17/17 test PASS**, build PASS.
- Kiểm tra **chỉ đọc** MySQL Docker hiện có: Flyway V6, 2 TIMED_OUT, 1 answer row,
  chưa có cột điểm. Không đọc/in `.env`, secret, nội dung đáp án hoặc hash.

Giữ Java 21/Spring Boot 3.5.16/dependency management. Không thêm dependency, TypeScript,
thay auth/session/CSRF/CORS, sửa migration V1–V6, reset DB, push, deploy hoặc recreate stack.
Các test dùng database Testcontainers độc lập. V7 chưa được agent áp dụng vào DB local đó.

## File/migration

Java dưới `src/main/java/com/example/doanchuyennganh/exam/` nếu không ghi đủ path.

| Nhóm | File |
| --- | --- |
| Migration mới | `src/main/resources/db/migration/V7__attempt_submission_and_grading.sql` |
| Lõi mới | `AttemptGrader.java`, `AttemptFinalizationService.java` |
| API giáo viên mới | `ManagedAttemptService.java`, `TeacherAttemptController.java` |
| Mở rộng/refactor | `AttemptStatus.java`, `ExamAttempt.java`, `ExamAttemptRepository.java`, `AttemptContracts.java`, `ExamAttemptService.java`, `StudentAttemptController.java`, `AttemptTimeoutService.java`, `AttemptTimeoutScheduler.java` |
| Test mới | `src/test/java/com/example/doanchuyennganh/exam/AttemptGradingIntegrationTest.java`, `AttemptV6UpgradeTest.java` |
| Test mở rộng | `ExamAttemptIntegrationTest.java`: tập cột V7; `AttemptSchedulerRecoveryTest.java`: thêm assertion điểm sau restart |
| Frontend mới | `frontend/src/submission.js`, `frontend/src/ResultPages.jsx`, `frontend/tests/submission.test.js` |
| Frontend mở rộng | `frontend/src/answerSession.js`, `frontend/src/AttemptPages.jsx`, `frontend/src/main.jsx`, `frontend/src/style.css` |
| Tài liệu | `docs/attempt-grading.md`, báo cáo này, README root/frontend, `docs/attempt-answers.md`, `docs/frontend-contract.md` |

Không bỏ assertion cũ hoặc tắt security filter. Assertion schema được mở rộng đúng 5 cột
kết quả; vẫn so chính xác tập cột và giữ mọi kiểm tra constraint/nghiệp vụ V5/V6.
Test scheduler thêm điểm để chứng minh restart không chỉ đổi status mà còn chấm.

## State, công thức, đồng thời và dữ liệu cũ

Lưu một bộ kết quả ngay trên attempt, không thêm bảng riêng: totalQuestions,
answeredCount, correctCount, score DECIMAL(4,2), gradedAt; PK tự bảo đảm tối đa một bộ.
Điểm BigDecimal = correctCount*10/totalQuestions, HALF_UP scale=2; không double.
Chỉ đếm selectedOptionId khác null đã lưu hợp lệ, clear giữ revision nhưng không được tính answered.

Submit khi Clock sau lock còn trước deadline → SUBMITTED; đúng/sau deadline → TIMED_OUT.
SUBMITTED effectiveEndedAt=finalizedAt, TIMED_OUT effectiveEndedAt=deadlineAt.
Mọi đường manual/timeout/GET/scheduler dùng cùng engine MANDATORY trong transaction
đã khóa attempt; chuyển trạng thái và ghi kết quả atomic. Grader lỗi rollback cả hai.
Gọi lặp sau khi gradedAt đã có trả cũ, không regrade/đổi timestamps. Save dùng cùng lock:
accepted trước finalize được tính, sau finalize 409. expectedRevision giữ nguyên.

Job giữ cấu hình timeout hiện có, quét hai batch hữu hạn riêng (due open, final chưa chấm).
Backfill đọc answer đã lưu, không đổi timestamp kết thúc cũ. Một lỗi không rollback
các lượt khác. GET result cũng gọi cùng engine khi cần, không trả điểm 0 để thay missing.
List teacher hiển thị score=null/resultReady=false trước backfill; enrollment chưa bắt đầu
không trở thành attempt. Không công bố câu hỏi/option/correct key trong result/list DTO.

Contract đầy đủ, mã lỗi, migration, transaction, cấu hình job và demo:
[attempt-grading.md](attempt-grading.md).

## Lệnh và kết quả thực tế

Dùng JDK 21 tại `C:/Users/ngdan/AppData/Local/codex-jdks/` thay Java 8 mặc định shell;
DEBUG=false và Maven cache có quyền đọc. Nội dung lệnh:

```bash
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository verify
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository -Dtest=AttemptGradingIntegrationTest,AttemptV6UpgradeTest,AttemptSchedulerRecoveryTest test
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

- Focused cuối: **12/12 PASS**, failures=0/errors=0/skipped=0, **3 phút 40 giây**,
  log `target/grading-focused-final.log`.
- Full verify cuối: **97 test, failures=0/errors=0/skipped=0**, BUILD SUCCESS,
  **10 phút 06 giây**, log `target/grading-verify.log`, surefire reports trong target.
- Frontend cuối: lint PASS, **26/26 test PASS**, build PASS; JavaScript nên không có typecheck.
- `git diff --check` PASS.

Hai lần kiểm tra trước gặp lỗi đã được sửa:

1. Regression 27 test: 1 fail do tập cột assertion vẫn V6 khi migration đã V7. Mở rộng đúng
   5 cột mới, không bỏ/giảm assertion. Các hành vi timeout/ownership/CSRF cũ vẫn giữ.
2. Focused 36 test: 1 error trong fixture historical timestamp do JDBC Timestamp dùng
   múi giờ hệ điều hành. Đổi fixture SQL sang deadline_at/TIMESTAMPADD và UTC LocalDateTime
   tường minh. Không đổi timezone ứng dụng hoặc constraint để làm test pass.

Không skip test. Javaagent/Hikari/connection warning trong lúc các test context/container
khác vòng đời không dùng làm bằng chứng fail/pass; xem tổng surefire và BUILD SUCCESS thực tế.
Không thay dependency hoặc cấu hình để che warning.

## Bằng chứng backend/HTTP/MySQL

`AttemptGradingIntegrationTest`: Tomcat RANDOM_PORT, MySQL Testcontainers, login thật
qua CSRF + session cookie; các đường thành công không mock Authentication/User.
Chỉ dùng Mockito spy gọi method thật để đếm số lần grade và inject lỗi ở test rollback.

| Trường hợp | Bằng chứng assertion |
| --- | --- |
| Công thức | Toàn đúng 10.00, toàn sai 0.00, cleared/blank 0.00, 2/3=6.67, 1/6=1.67; BigDecimal scale=2 |
| Submit HTTP | Trước hạn SUBMITTED, số answered/unanswered đúng, body rỗng; JSON `{}` hoặc answers/score 400; no-store |
| Deadline | Đúng hạn/sau hạn TIMED_OUT, effectiveEndedAt=deadline, finalizedAt=Clock sau lock |
| Idempotence | Submit/GET result lặp, kể cả sau deadline, giữ toàn bộ result; grader gọi đúng một lần |
| Khóa sau nộp | GET attempt SUBMITTED trước hạn vẫn ACTIVE theo giờ nhưng questions=[]; PUT 409 ATTEMPT_FINISHED |
| Timeout chung | Endpoint timeout, lazy GET result, scheduler wrapper đều chấm cùng công thức, gọi lặp không regrade |
| Rollback | Inject grader exception sau khi bắt đầu finalize; DB giữ IN_PROGRESS, timestamps/điểm đều null, answer nguyên vẹn; cả submit và timeout |
| Save/submit race | Hai TransactionTemplate/connection thực, barrier đồng thời; hoặc save được tính, hoặc bị từ chối sau finalize; kết quả khớp DB |
| Triple race | Ba connection đồng thời submit/timeout/scheduler ở deadline, status TIMED_OUT, grader đúng một lần, result/timestamps ổn định |
| Ownership/role | Student khác 404; teacher/admin không đọc namespace student 403; teacher ngoài owner 404; admin đọc result ngoài ownership được |
| Exam URL | attemptId khác examId trên URL bị 404 kể cả ADMIN |
| CSRF/auth | Submit thiếu/sai CSRF 403, anonymous 401 với filter thật; result/list GET anonymous 401 |
| Pagination | Page size 1, totalElements đúng chỉ 2 attempt dù có enrollment chưa bắt đầu; row đang làm score=null; size/page sai 400; exam chưa có attempt trả empty page |
| Không lộ key | Assert tập field chính xác ResultResponse, student và managed result; DTO không có questions/options/correct key/hash |
| Legacy backfill | Fixture TIMED_OUT chưa có điểm được job chấm, giữ finalizedAt/effectiveEndedAt; repeated job/GET/submit không chấm lại |

`AttemptV6UpgradeTest` tạo schema bằng **Flyway target=6 thật**, insert một lượt TIMED_OUT
có answer revision=4 và cleared revision=2, chưa có cột điểm. Khởi động ứng dụng mới:
Flyway V7 + JPA validate, @Scheduled tự backfill không request browser. Assert 5.00,
giữ startedAt/deadline/finalized/effective times, answer rows/revisions và số attempt=1;
gọi result/submit/timeout lặp vẫn cùng kết quả. Test scheduler restart ba context cũ
cũng thêm assertion điểm, deadline và answers vẫn nguyên.

Marker sau assertion trong log:

```text
SAVE_SUBMIT_RACE: connections=2 acceptedAnswerIncluded=true finalResultStable=true
FINALIZE_RACE: connections=3 submitTimeoutScheduler=true gradeCalls=1 sameResultAndTimestamps=true
V6_UPGRADE: existingTimedOut=1 backfilled=1 oldEndTimesAndAnswerRevisionsUnchanged=true score=5.00 noBrowserRequests=true
```

Clock cố định/điều chỉnh, race có barrier và connection riêng, không giả đồng thời bằng
gọi tuần tự. Awaitility chỉ đợi điều kiện DB/scheduler, không sleep dài theo deadline.

## Frontend và browser

9 unit test mới kiểm tra AnswerSession/submission orchestration bằng transport điều khiển:
chờ cả flight và queue, khóa choice, xác nhận counts đã lưu/bỏ trống, hủy mở lại sửa,
lưu lỗi/conflict chặn submit có số câu, ambiguous save phải giải quyết, hết giờ lúc chờ
hoặc trong xác nhận ưu tiên timeout, submit mất response GET final không resend,
GET thất bại/IN_PROGRESS không giả thành công, bài đã final không nộp thêm, unmount ngăn nộp.
17 test cũ vẫn chạy. Không dùng unit test giả transport làm bằng chứng browser thành công.

**Chưa kiểm chứng browser flow mới.** Đã chọn/đọc cửa sổ Chrome để chuẩn bị tab test;
khi mở tab, Computer Use báo **người dùng nhấn Escape để dừng**. Đã dừng mọi input UI
và không gọi tiếp công cụ Computer Use trong lượt. Vì vậy chưa xác minh trực quan:
chọn→lưu→nộp→điểm→reload, modal/blank counts, offline UI, timeout→điểm, teacher UI,
responsive/keyboard và Network tab. HTTP/database thật ở trên là bằng chứng riêng.

Không tạo browser harness/demo trên DB người dùng ở lượt này. Chỉ có fixture trong DB
Testcontainers: `GRADING-TEST-*`, `*.grading@example.test`, `V6-UPGRADE-FIXTURE`,
`*.v6@example.test` cùng fixture scheduler/legacy cũ. Testcontainers tự quản lý vòng đời;
không xóa/reset dữ liệu local. Hai bài TIMED_OUT trong DB Docker của người dùng vẫn
chưa được agent migrate/chấm; backend mới sẽ tự backfill khi người dùng khởi động.

CI remote, production HTTPS, throughput, mọi interleaving và lỗi hạ tầng job kéo dài
chưa xác minh. Session vẫn lưu trong bộ nhớ backend; restart mất đăng nhập nhưng DB
attempt/results còn. Chưa có đáp án đúng/lời giải hoặc tính năng ngoài phạm vi.

## Chạy và demo

Từ thư mục chứa pom.xml, JDK 21 và MySQL đã chạy:

```powershell
.\mvnw.cmd spring-boot:run
```

Terminal khác:

```powershell
cd frontend
npm ci
# Cấu hình .env.local theo .env.example, BACKEND_TARGET đúng port backend thực tế.
npm run dev
```

Mở http://127.0.0.1:5173. Backend mới tự áp dụng V7 và job chấm các bài cũ khi được bật.
Nếu dùng Docker image cũ, cần người dùng chủ động rebuild theo tài liệu Docker hiện có;
agent không push/deploy/restart stack trong nhiệm vụ này. Demo 1 phút và bảng API tại
[attempt-grading.md](attempt-grading.md).
