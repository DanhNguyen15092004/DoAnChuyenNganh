# Báo cáo module kỳ thi tối thiểu — 28/10/2026

## File thay đổi

Repo đã có thay đổi chưa commit của các lượt auth trước. Lượt này chỉ thêm package exam,
migration V4, hai test exam, hai tài liệu exam và một liên kết trong README.
Không sửa các test auth/skeleton hoặc mở rộng public/security để làm test pass.

| Vị trí | File |
| --- | --- |
| `src/main/resources/db/migration/` | `V4__create_exam_module.sql` |
| `src/main/java/com/example/doanchuyennganh/exam/` — model | `Exam.java`, `ExamStatus.java`, `ExamQuestion.java`, `QuestionOption.java`, `ExamEnrollment.java`, `ExamConsent.java` |
| Cùng package — repository | `ExamRepository.java`, `ExamQuestionRepository.java`, `QuestionOptionRepository.java`, `ExamEnrollmentRepository.java`, `ExamConsentRepository.java` |
| Cùng package — API/service | `ExamContracts.java`, `ExamService.java`, `TeacherExamController.java`, `StudentExamController.java` |
| Cùng package — cấu hình/lỗi | `ExamConfiguration.java`, `ExamExceptionHandler.java`, `InvalidExamRequestException.java`, `ExamConflictException.java` |
| `src/test/java/com/example/doanchuyennganh/exam/` | `ExamSchemaIntegrationTest.java`, `ExamModuleIntegrationTest.java` |
| Tài liệu | `docs/exam-module.md`, `docs/exam-implementation-report.md`, liên kết module trong `README.md` |

Đối chiếu SHA-256 trước/sau xác nhận pom, SecurityConfiguration, seeder và V1/V2/V3 không đổi.
Không nâng dependency, không thay auth/session/CSRF, không sửa `.env` hoặc dữ liệu local/prod.

## Migration mới

Repository trước lượt này có V1, V2, V3; migration tiếp theo là **V4**.
Tạo `exams`, `exam_questions`, `exam_question_options`, `exam_enrollments`, `exam_consents`.
FK tới app_users và các bảng cha; unique vị trí theo cha, unique exam/student và unique enrollment/consent;
CHECK thời gian/duration/status/publication/position/content; NOT NULL theo model.
`ddl-auto=validate` giữ nguyên và đã validate thành công trên MySQL 8.0.

Test schema kiểm tra lỗi MySQL đúng loại: CHECK **3819**, FK **1452**, unique **1062**, NOT NULL **1048**;
không chỉ coi bất kỳ SQLException nào là thành công. Không có dữ liệu mẫu trong V4.
V4 mới chỉ được áp dụng trên Testcontainers trong kiểm chứng, chưa chạy trên DB local/prod của người dùng.

## API đã triển khai

| Method | Endpoint | Thành công |
| --- | --- | --- |
| POST | `/api/v1/teacher/exams` | 201 + Location; owner từ principal, DRAFT |
| GET | `/api/v1/teacher/exams` | 200 danh sách có phân trang theo scope |
| GET | `/api/v1/teacher/exams/{id}` | 200 đầy đủ câu hỏi/lựa chọn/đáp án đúng cho người quản lý |
| PUT | `/api/v1/teacher/exams/{id}` | 200 cập nhật metadata của draft |
| PUT | `/api/v1/teacher/exams/{id}/questions` | 200 thay toàn bộ câu hỏi draft sau khi validate toàn bộ |
| POST | `/api/v1/teacher/exams/{id}/publish` | 200, idempotent giữ publishedAt |
| POST | `/api/v1/teacher/exams/{id}/enrollments` | 201 lần đầu / 200 cùng enrollment |
| GET | `/api/v1/teacher/exams/{id}/enrollments` | 200 phân trang, thông tin sinh viên cần thiết và consent |
| GET | `/api/v1/student/exams` | 200 chỉ published + đã ghi danh chính mình |
| GET | `/api/v1/student/exams/{id}` | 200 metadata + questionCount/time/consent, không có nội dung đề |
| POST | `/api/v1/student/exams/{id}/consent` | 201 lần đầu / 200 cùng record/version/acceptedAt |

Trang mặc định 0, size 20 (1–100); sort exam `createdAt DESC, id DESC`.
DTO rõ ràng, không trả JPA entity. Field server quản lý/ID câu hỏi-option/studentId hoặc acceptedAt
trong consent đều không được nhận ngoài contract; request field thừa bị từ chối 400.
Ví dụ request/response đầy đủ và demo PowerShell: `docs/exam-module.md`.

## Quyền sở hữu và bất biến sau publish

- Filter chain hiện có kiểm tra role. Service vẫn kiểm tra role độc lập; TEACHER chỉ quản lý exam của mình;
  ADMIN được quản lý mọi exam nhưng khi tạo vẫn lấy chính ADMIN làm owner.
- Đúng role nhưng tài nguyên ngoài scope/không tồn tại trả 404; STUDENT gọi API giáo viên trả 403;
  chưa login GET API được bảo vệ trả 401. POST/PUT thiếu hoặc sai CSRF trả 403.
- Published không sửa metadata/questions/options/answers/consent; publish lặp không sửa publishedAt.
- Consent version/text do backend quản lý và chốt khi publish. acceptedAt và identity từ server/principal;
  không ghi IP/user-agent, không tạo attempt. Consent mẫu không mặc định đáp ứng mọi nghĩa vụ pháp lý.
- Student response có đúng 12 trường được whitelist; không chứa câu hỏi, lựa chọn hoặc đáp án đúng.
- Enrollment chỉ chấp nhận STUDENT enabled, exam published/chưa đóng; consent cùng điều kiện thời gian,
  accepted=true bắt buộc, version lệch 409. Lặp hợp lệ không ghi đè record/timestamp.

## Tổng test/failures/errors/skipped

Các lệnh Maven dùng Wrapper qua Git Bash, JAVA_HOME JDK 21, Docker/MySQL/HTTP được cấp quyền ngoài sandbox:

| Bước | Kiểm tra | Tests | Failures | Errors | Skipped | Kết quả |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 0 | Baseline verify trước sửa | 37 | 0 | 0 | 0 | BUILD SUCCESS, 3m16s |
| 1 | ExamSchemaIntegrationTest | 1 | 0 | 0 | 0 | BUILD SUCCESS |
| 2–3 | Service/quyền/API giáo viên | 7 | 0 | 0 | 0 | BUILD SUCCESS |
| 4 | Thêm sinh viên/consent/Clock/HTTP flow | 10 | 0 | 0 | 0 | BUILD SUCCESS |
| 5 | Thêm ba race thật | 13 | 0 | 0 | 0 | BUILD SUCCESS |
| 6–7 | `./mvnw --batch-mode --no-transfer-progress verify` cuối | **52** | **0** | **0** | **0** | **BUILD SUCCESS, 4m58s** |

Verify cuối gồm **37 test cũ + 14 test hành vi exam + 1 test schema**.
Test schema chứa nhiều assertion FK/unique/CHECK/NOT NULL. Test hành vi chứa các ca validation,
scope, idempotence, immutability, ba race và full HTTP flow; không viết một test mirror cho từng getter/field.
Lỗi compile import Role ở test mới trong lần chạy đầu đã sửa; không sửa test cũ để né lỗi.
`git diff --check` đạt. `git status --short` đã kiểm tra; thay đổi cũ được giữ lại.
Ví dụ PowerShell trong tài liệu đã parse cú pháp thành công, chưa thực thi trên dữ liệu local/prod.

## Kết quả luồng HTTP thật

`ExamModuleIntegrationTest.realHttpTeacherToStudentConsentFlow` chạy Tomcat RANDOM_PORT,
Java HttpClient thật giữ Set-Cookie/CSRF và MySQL Testcontainers. Tài khoản fixture lưu MySQL/BCrypt,
login qua endpoint auth thật; không dùng mock user hoặc tắt filter.

| Bước | HTTP thực tế |
| --- | --- |
| Teacher GET CSRF → POST login → GET CSRF mới | 200, session giữ cookie |
| POST tạo draft | 201, Location đúng, owner từ principal |
| PUT questions | 200, lưu câu/option đúng |
| POST publish | 200 PUBLISHED |
| POST enrollment student | 201 |
| Student login/CSRF → GET exam | 200, chỉ whitelist 12 trường metadata/time/consent |
| Student POST consent | 201, version từ exam, acceptedAt server |
| Student GET exam sau consent | consentAccepted=true |

Test HTTP bổ sung kiểm tra 404 của giáo viên khác, ADMIN quản lý được, STUDENT không gọi API giáo viên,
request owner/status/ID trái phép 400, thiếu/sai CSRF 403, validation không phá đề cũ,
PUT sau publish 409, enrollment/consent lặp 200 và không đổi record.
Clock fixed không sleep kiểm tra UPCOMING trước opensAt, OPEN đúng opensAt và sát closesAt,
CLOSED đúng closesAt; consent sau/đúng giờ đóng trả 409.

## Kết quả test đồng thời

Chiến lược: mọi mutation của một exam khóa hàng exam PESSIMISTIC_WRITE trước,
sau đó kiểm tra lại scope/status/time trong cùng transaction. @Version có trên exam nhưng khóa parent
là giao thức bảo vệ cả thay questions; không thêm cơ chế retry/optimistic lock thứ hai cho child.
Không catch DataIntegrityViolationException rồi tiếp tục transaction rollback-only.

Mỗi race dùng hai thread và TransactionTemplate riêng, lấy CONNECTION_ID() từ mỗi transaction,
chờ CountDownLatch trước khi cùng chạy; assert **hai connection ID khác nhau**.
Không điều khiển thời gian bằng sleep và không thay bằng hai lời gọi tuần tự.

| Race | Assertion đã đạt |
| --- | --- |
| Publish vs thay questions | Publish thành công; nếu replace đi trước thì publish trả đúng đề mới, nếu publish đi trước replace nhận 409 và đề cũ không đổi; kết quả cuối PUBLISHED bất biến |
| Enrollment vs enrollment cùng student | Đúng một created=true, một created=false, cùng enrollment response, SQL COUNT=1 |
| Consent vs consent cùng enrollment | Đúng một created=true, một created=false, cùng consent response, SQL COUNT=1; gọi lại sau khi tăng Clock 30 giây vẫn giữ acceptedAt ban đầu |

Các race chạy lại trong verify cuối đều đạt. Không tuyên bố đã load test hay kiểm chứng mọi lịch lập lịch có thể có;
đây là kiểm tra đồng thời có connection/transaction thật cho các invariant được yêu cầu.

## Phần chưa xác minh và blocker

- Chưa chạy demo PowerShell trên DB local/prod hoặc áp dụng V4 vào các DB đó; dữ liệu hiện có không bị reset/sửa.
- Chưa chạy CI GitHub remote, HTTPS/reverse proxy hoặc frontend/browser thực tế trong lượt này.
- Không có blocker môi trường hiện tại sau khi Maven/Testcontainers được phép truy cập Docker ngoài sandbox.
- MVP serialize mutation theo exam, nên throughput của cùng exam có giới hạn; chưa làm load test.
- Không làm attempt/chấm điểm/giám sát/AI/frontend, không push hoặc deploy.

Module dừng đúng phạm vi kỳ thi tối thiểu, chưa có lượt làm bài.
