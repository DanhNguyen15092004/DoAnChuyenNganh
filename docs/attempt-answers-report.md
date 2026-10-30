# Báo cáo autosave, timeout và điều hướng câu hỏi — 30/10/2026

## Baseline trước thay đổi

Đã đọc attachment yêu cầu, migration V1–V5, Clock/lock/ownership hiện có,
attempt DTO/controller/service, exam/enrollment/consent, auth/session/CSRF,
error handlers, frontend API client/route guard, test và tài liệu liên quan.
Không tìm thấy AGENTS.md trong repo hoặc các thư mục cha đã kiểm tra.
Git working tree ban đầu sạch, commit nền `a5d300e`.

Baseline **chạy mới**, không lấy con số từ báo cáo cũ:

- Backend `./mvnw ... verify`: **74 test, failures=0, errors=0, skipped=0**, BUILD SUCCESS,
  5 phút 40 giây, log `target/answers-baseline.log`.
- Frontend lint PASS, test **6/6**, build PASS.
- V5 trước đó chỉ tạo/đọc đề, chưa có answer persistence/lifecycle scheduler.

Giữ Java 21, Spring Boot 3.5.16 và dependency management hiện tại, không thêm dependency.
POM, package.json/lockfile, security/auth/account, Docker/CI và V1–V5 không đổi qua
đối chiếu SHA-256 source tracked trước/sau. Không đọc/in `.env`, đổi mật khẩu/role,
reset DB, push, deploy hoặc rebuild stack Docker của người dùng.

## File thay đổi

Java ghi ngắn dưới `src/main/java/com/example/doanchuyennganh/exam/`.

| Nhóm | File |
| --- | --- |
| Migration mới | `src/main/resources/db/migration/V6__attempt_answers_and_timeout.sql` |
| Model/repository mới | `AttemptAnswer.java`, `AttemptAnswerRepository.java`, `AttemptStatus.java` |
| Lifecycle mới | `AttemptTimeoutConfiguration.java`, `AttemptTimeoutService.java`, `AttemptTimeoutScheduler.java` |
| Lỗi mới | `AttemptConflictException.java` |
| Mở rộng attempt hiện có | `ExamAttempt.java`, `ExamAttemptRepository.java`, `AttemptContracts.java`, `ExamAttemptService.java`, `StudentAttemptController.java`, `ExamExceptionHandler.java` |
| Profile test | `src/main/resources/application-test.yml` tắt scheduler tự chạy; test scheduler bật lại tường minh |
| Test backend | `src/test/java/com/example/doanchuyennganh/exam/ExamAttemptIntegrationTest.java`, `AttemptSchedulerRecoveryTest.java` |
| State/test frontend mới | `frontend/src/answerSession.js`, `frontend/tests/answerSession.test.js` |
| Giao diện | `frontend/src/AttemptPages.jsx`, `frontend/src/main.jsx`, `frontend/src/style.css` |
| Tài liệu | `docs/attempt-answers.md`, báo cáo này, README root/frontend, `docs/attempt-module.md`, `docs/frontend-contract.md` |

Hai assertion test cũ thay đổi vì **contract được mở rộng thật sự**: tập cột
exam_attempts thêm lifecycle, tập field response thêm lifecycle/answers. Vẫn assert chính xác
toàn bộ field, giữ các assertion deadline/ownership/CSRF/không lộ đáp án và tất cả test cũ.
Không tắt filters hoặc sửa kỳ vọng status/nghiệp vụ cũ để làm pass.

## Hành vi đã triển khai

Contract đầy đủ, bảng endpoint/CSRF/role/pagination, mã lỗi, schema và demo:
[attempt-answers.md](attempt-answers.md).

- PUT `/api/v1/student/attempts/{attemptId}/answers/{questionId}` lưu chọn/đổi/null;
  unique mỗi câu/lượt, giữ revision khi clear. GET attempt trả answers riêng của mình.
- Parent attempt lock trước Clock/deadline/revision; expectedRevision cũ không overwrite.
  Coded 409: ANSWER_REVISION_CONFLICT / ATTEMPT_TIMED_OUT / TIMEOUT_NOT_DUE.
- Persist IN_PROGRESS/TIMED_OUT, finalizedAt lúc xử lý và effectiveEndedAt=deadline.
  Late save chốt bền vững trước khi controller trả 409. Sau hạn không có nội dung đề.
- Scheduler query ID theo index, batch hữu hạn, mỗi attempt transaction qua bean proxy khác;
  xử lý lại row cũ sau restart, không phụ thuộc browser/session. Timeout endpoint hỗ trợ UI.
- React/Vite JS: radio/clear, một PUT mỗi câu, chỉ giữ ý định cuối khi thay đổi nhanh;
  không dùng localStorage. GET trước retry không rõ kết quả; conflict cần chọn lại.
- Ô số đúng thứ tự đề, click cuộn/focus, đánh dấu đang xem, legend chữ/ký hiệu, cùng state
  radio và thống kê “Đã chọn: X/N · Chưa trả lời: Y · Chưa lưu: Z”. Pending clear cũng
  chưa lưu, dù selected đã giảm ngay. Mobile thu gọn nhưng vẫn thấy thống kê.
- Local countdown=0 khóa/ẩn đề và yêu cầu xác nhận timeout; chỉ server response mới
  xác nhận đã kết thúc, offline có thông báo chưa xác nhận. Chưa nộp sớm/chấm điểm.

## Lệnh và kết quả thực tế

Dùng JDK 21 được cài trong `C:/Users/ngdan/AppData/Local/codex-jdks/` vì Java mặc định
shell là Java 8; Maven dùng cache sẵn và quyền truy cập Docker. Lệnh nội dung:

```bash
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository verify
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=C:/Users/ngdan/.m2/repository -Dtest=ExamAttemptIntegrationTest,AttemptSchedulerRecoveryTest test
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

- Focused backend: **27 test PASS**, failures=0/errors=0/skipped=0, 2 phút 37 giây.
- Full verify đầu: **86 test PASS**, failures=0/errors=0/skipped=0, 4 phút 56 giây.
- Verify cuối sau CHECK NOT NULL bổ sung: **86 test PASS**, failures=0/errors=0/skipped=0,
  BUILD SUCCESS, 7 phút 45 giây; log `target/answers-final-verify.log` và surefire reports.
- Frontend cuối: lint PASS, **17 test PASS**, không skip, build PASS (Vite 7.3.7).
  Không có typecheck vì dùng JS. `git diff --check` PASS.

Lần focused đầu thất bại ở **mã test mới**: IntNode/LongNode so sánh cùng số và quyền
SELECT performance_schema của test user. Đã so sánh giá trị số/null đúng nghĩa và dùng
kết nối root riêng của **container test** để quan sát lock wait; không nới quyền ứng dụng.
Compile sandbox ban đầu không đọc được JAR cache; chạy với quyền phù hợp đã compile đạt.
Không skip test để tránh các lỗi này. Javaagent/Hikari shutdown warning giống baseline
không làm test fail; không thay dependency hoặc cấu hình để che warning.

## Bằng chứng HTTP, DB, đồng thời và scheduler

HTTP test dùng Tomcat RANDOM_PORT + MySQL 8.0.46 Testcontainers + login thật/cookie/CSRF,
không mock user làm bằng chứng đăng nhập.

| Nhóm | Assertion đạt trong test |
| --- | --- |
| Persist / restore | PUT chọn→đổi→null, GET sau mỗi lần khớp lựa chọn/revision; clear giữ row revision=3; no-store |
| Scope/validation | Sai owner 404; teacher/admin 403; anonymous 401 với CSRF; câu khác exam/option khác câu 400; unknown field/DTO/timeout body 400 |
| CSRF | PUT và timeout thiếu/sai CSRF 403; filters thật hoạt động |
| Revision | Cũ 409 có code; clear giữ revision; request cũ không overwrite |
| Đúng deadline | Từ IN_PROGRESS chưa finalize, save đúng deadline 409; query DB chứng minh finalization đã commit dù response lỗi; đáp án đã lưu không đổi |
| Lock wait | Hai transaction/connection; root observer nhìn thấy performance_schema.data_lock_waits; sau đó mới advance Clock qua deadline và nhả lock; save bị chặn |
| Cùng revision | Hai worker đồng thời trên hai connection; đúng 1 accepted, 1 revision conflict; một row revision=1 |
| Save/timeout | Hai transaction đồng thời ở deadline; late save bị chặn, timeout chốt, giữ nguyên answer/revision |
| Idempotence | Timeout sớm 409; lặp sau khi Clock tăng và timeout đồng thời giữ timestamps; scheduler gọi lại cũng không sửa |
| Schema | Flyway V6, JPA validate, FK/UNIQUE/CHECK revision/status/lifecycle; index có đủ ba cột |
| Không lộ key | Assert chính xác các field paper/option/answer DTO; expired không paper; metadata exam cũ không thay đổi |
| Scheduler/restart | DB giữ qua 3 application context/HTTP server thật; batch=1, hai lần sweep chỉ chốt từng row; restart bật @Scheduled, tự chốt hai row còn lại không request browser; lượt tương lai và answer đã lưu nguyên vẹn |

Các marker chỉ in **sau assertion**, log `target/answers-focused.log` / verify:

```text
ANSWER_RACE: connections=2 accepted=1 revisionConflict=1 revision=1
WAITING_SAVE: observedInnoDbLockWait=true connections=2 clockCheckedAfterLock=true lateSaveRejected=true
SAVE_TIMEOUT_RACE: connections=2 lateSaveRejected=true acceptedAnswerUnchanged=true
SCHEDULER_RECOVERY: appContexts=3 batchSize=1 manualBatches=2 scheduledRemaining=2 timedOut=4 futureUnchanged=1 persistedAnswerRetained=true browserRequests=0
```

Clock điều chỉnh có kiểm soát, CountDownLatch/barrier, transaction/connection riêng;
Awaitility chỉ quan sát điều kiện DB, không dùng sleep dài làm giả race.
Frontend 11 test mới là **unit test state với transport điều khiển được**: chọn nhanh,
queued clear/revision, response cũ, ambiguous commit, GET trước retry, conflict/reselection
kể cả GET lỗi, restore/ignore revision cũ, hai câu độc lập, khóa khi hết giờ, retry sau hạn
không giả trạng thái đang lưu, counts và dispose.
Các test này không được coi là bằng chứng browser flow thật.

## Browser, dữ liệu và giới hạn

**Chưa kiểm chứng flow browser của phần mới.** Browser connector trả
`The browser access host is unavailable`. Fallback Computer Use đọc được Chrome, nhưng
khi mở tab test thì tự dừng với lỗi không xác định được URL đủ chắc chắn để thực thi policy.
Đã dừng input UI, không dùng công cụ khác để vượt chặn. Vì vậy chưa kiểm chứng trực quan
click radio/clear/ô số/focus, thống kê responsive, reload UI, đổi nhanh, offline/hai tab,
timeout UI hoặc mở lại tab sau khi hết giờ. HTTP/DB thật nêu trên là bằng chứng riêng.

Đã khởi động backend source và Vite riêng để chuẩn bị browser, không deploy image:
hai lần harness DB Testcontainers độc lập, mỗi lần có teacher/student fixture
`*.browser@example.test`, exam `BROWSER-AUTOSAVE-20261030`, examId=1/attemptId=1/studentId=1
trong **DB fixture đó**, hai câu/enrollment/consent. Chưa có thao tác trả lời từ browser.
Đã dừng harness/dev server khi công cụ browser bị chặn; Testcontainers tự dọn DB fixture.
Không dùng các ID này cho DB local của bạn. Fixture verify có prefix ATTEMPT-TEST-/SCHEDULER-TEST-
và tài khoản *.attempt@example.test / *.scheduler@example.test, chỉ trong DB test.
Không tạo/sửa/xóa dữ liệu ở DB người dùng.

Không load test throughput, thử lỗi hạ tầng scheduler kéo dài hoặc xác minh mọi interleaving.
Một row lỗi liên tục có thể chiếm slot batch ở các lượt quét sau; warning cần theo dõi.
CI remote, HTTPS production và session/routing nhiều instance chưa xác minh.
Build PASS không có nghĩa luồng browser hoặc production đã chạy đạt.

## Cách chạy bản mới

Giữ MySQL hiện có chạy theo README; từ thư mục chứa pom.xml:

```powershell
.\mvnw.cmd spring-boot:run
cd frontend
npm ci
# Lần đầu copy .env.example thành .env.local và đặt BACKEND_TARGET theo backend thực tế.
npm run dev
```

Mở http://127.0.0.1:5173. Backend source mới sẽ áp dụng V6 vào DB bạn cấu hình;
không reset. Nếu dùng Docker image cũ, image đó chưa có source mới cho tới khi **bạn**
chủ động rebuild theo hướng dẫn Docker trong repo. Demo đầy đủ/1 phút tại
[attempt-answers.md](attempt-answers.md). Không push/deploy trong nhiệm vụ này.
