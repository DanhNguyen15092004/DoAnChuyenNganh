# Module kỳ thi tối thiểu

Tài liệu này mô tả module exam ban đầu. Phần attempt được bổ sung riêng tại
[attempt-module.md](attempt-module.md); API metadata sinh viên bên dưới vẫn không trả nội dung đề.

## Phạm vi

TEACHER tạo draft → thay toàn bộ câu hỏi trắc nghiệm → publish → ghi danh STUDENT enabled →
STUDENT xem metadata và chấp nhận consent. ADMIN quản lý mọi exam qua nhóm URL giáo viên;
khi tự tạo exam, ADMIN vẫn là owner của exam đó.

Chưa có attempt/lượt làm bài, đáp án của sinh viên, nộp bài, chấm điểm, AI/camera, upload,
WebSocket, question bank, random đề, nhiều đáp án đúng, self-enrollment, unpublish hoặc sửa đề đã publish.
Mỗi câu có trọng số như nhau; chưa có trường điểm. Consent không tạo attempt và không thu IP/user-agent.
Nội dung consent mẫu không được coi là đáp ứng mọi nghĩa vụ pháp lý; ứng dụng chưa thu dữ liệu giám sát.

Không đổi Java 21/Spring Boot 3.5.16/dependency. Giữ nguyên auth/session/CSRF,
seeder và migration V1/V2/V3. Tất cả API tiếp tục dùng cookie + CSRF hiện có và ProblemDetail.

## Schema và trạng thái

Migration mới: `V4__create_exam_module.sql`; Hibernate vẫn `ddl-auto=validate`.

| Bảng | Dữ liệu và ràng buộc |
| --- | --- |
| `exams` | id; owner_id FK app_users; title VARCHAR(200) NOT NULL; description VARCHAR(5000) nullable; duration_minutes 1–300; opens_at/closes_at DATETIME(6); status VARCHAR(20) CHECK DRAFT/PUBLISHED; published_at nullable chỉ ở DRAFT; consent_version VARCHAR(100), consent_text VARCHAR(5000); version BIGINT; created_at/updated_at UTC |
| `exam_questions` | id; exam_id FK; content VARCHAR(5000) NOT NULL; position > 0, unique(exam_id, position); created_at/updated_at UTC |
| `exam_question_options` | id; question_id FK; content VARCHAR(2000) NOT NULL; position > 0, unique(question_id, position); is_correct BOOLEAN NOT NULL, CHECK 0/1 |
| `exam_enrollments` | id; exam_id/student_id/enrolled_by FKs; enrolled_at UTC; unique(exam_id, student_id) |
| `exam_consents` | id; enrollment_id FK unique; consent_version; accepted_at UTC |

Entity lưu các ID FK dạng scalar; migration thực thi FK tại MySQL. Không trả entity qua API.
Timestamp dùng Instant/JDBC UTC, độ chính xác microsecond (DATETIME(6)); input được chuẩn hóa về microsecond.
Title/content/consent không được rỗng; các giới hạn độ dài được kiểm tra trước khi ghi.
CHECK tại DB bảo vệ range/status/window/position, unique và FK là lớp bảo vệ cuối.

`opensAt < closesAt`; khoảng mở thi phải dài ít nhất durationMinutes.
Thời gian tạo draft không bắt buộc ở tương lai, nhưng publish phải có `closesAt > serverNow`.
Publish cần 1–100 câu; mỗi câu 2–6 option, đúng một option correct, position dương và không trùng trong phạm vi cha.
Rule tổng hợp được kiểm tra toàn bộ tại service, cả khi thay câu hỏi và trước publish.

DRAFT → PUBLISHED là một chiều. Publish ghi server publishedAt và đóng băng metadata/câu hỏi/đáp án/consent.
Gọi publish lặp trả 200 kể cả sau giờ đóng, không đổi publishedAt. PUT metadata/questions sau publish trả 409.
Enrollment/consent chỉ được ghi khi exam published và `serverNow < closesAt`; được phép trước opensAt.
Lặp enrollment/consent chỉ idempotent khi các điều kiện này còn hợp lệ; sau giờ đóng trả 409.

Consent do bean cấu hình `app.exam.consent.version` và `app.exam.consent.text` quản lý.
Mặc định version `monitoring-v1` và nội dung mẫu thông báo module chỉ ghi nhận sự đồng ý.
Ứng dụng chọn mẫu lúc tạo draft và chốt lại mẫu hiện hành lúc publish; request không được tự đặt consent.
Muốn đổi mẫu cho exam tương lai có thể cấu hình Spring environment `APP_EXAM_CONSENT_VERSION`/
`APP_EXAM_CONSENT_TEXT` rồi khởi động lại. Không tác động bản consent đã chốt của exam published.

Availability chỉ suy ra từ Clock server:

- `now < opensAt`: UPCOMING.
- `opensAt <= now < closesAt`: OPEN.
- `now >= closesAt`: CLOSED.

Module attempt sau này sẽ chốt deadline bằng `min(startedAt + duration, closesAt)`;
consent hiện tại không phải là thời điểm bắt đầu làm bài.

## Quyền và lỗi

| API | TEACHER | ADMIN | STUDENT |
| --- | --- | --- | --- |
| teacher/exams | Chỉ exam mình sở hữu | Mọi exam | 403 |
| student/exams | 403 | 403 | Chỉ PUBLISHED và đã ghi danh chính mình |

Role ở filter chain được giữ nguyên. Service kiểm tra role, owner và enrollment độc lập với URL.
Owner luôn lấy từ principal, không có chuyển owner hoặc tạo thay. Query đọc/list của TEACHER được scope theo owner;
mutation lấy parent lock rồi kiểm tra owner. Sinh viên không đọc được DTO đề giáo viên bằng endpoint sinh viên.

| Tình huống | Status |
| --- | --- |
| Chưa login | 401 |
| Sai role/CSRF thiếu hoặc sai | 403 |
| Đúng role nhưng exam không tồn tại/ngoài owner hoặc enrollment; exam chưa published ở API sinh viên | 404 |
| Body/field/range/position/số option/đáp án không hợp lệ; tài khoản ghi danh không phải STUDENT enabled | 400 |
| Đã publish khi PUT; publish rỗng/đã đóng; ghi danh/consent đã đóng; consent version lệch; xung đột cập nhật | 409 |

Request DTO chỉ nhận các field được liệt kê. Field thừa như ownerId/status/publishedAt/version,
question/option id, studentId/acceptedAt trong consent bị từ chối 400, không bị dùng để sửa trường server quản lý.
Lỗi có Content-Type application/problem+json theo convention hiện có, không trả stack trace hoặc entity.

## API giáo viên

Prefix `/api/v1/teacher/exams`. Collection chấp nhận cả có và không có slash cuối.

| Method/path | Request | Success |
| --- | --- | --- |
| POST collection | Metadata bên dưới | 201 + Location `/api/v1/teacher/exams/{id}`, TeacherExamResponse |
| GET collection?page=0&size=20 | Không body | 200 PageResponse<ExamSummary> |
| GET /{id} | Không body | 200 TeacherExamResponse, có đáp án đúng |
| PUT /{id} | Metadata bên dưới | 200 TeacherExamResponse; chỉ DRAFT |
| PUT /{id}/questions | QuestionsRequest bên dưới | 200 TeacherExamResponse; thay toàn bộ trong transaction; chỉ DRAFT |
| POST /{id}/publish | Không body | 200 TeacherExamResponse, idempotent |
| POST /{id}/enrollments | `{"studentId":2}` | 201 lần đầu / 200 đã có, EnrollmentResponse |
| GET /{id}/enrollments?page=0&size=20 | Không body | 200 PageResponse<EnrollmentResponse> |

MetadataRequest cho POST/PUT (chỉ 5 field):

```json
{
  "title":"Kỳ thi mẫu",
  "description":"Nội dung giới thiệu",
  "durationMinutes":60,
  "opensAt":"2030-01-02T08:00:00Z",
  "closesAt":"2030-01-02T10:00:00Z"
}
```

QuestionsRequest:

```json
{
  "questions":[
    {
      "content":"2 + 2 = ?",
      "position":1,
      "options":[
        {"content":"4","position":1,"correct":true},
        {"content":"5","position":2,"correct":false}
      ]
    }
  ]
}
```

`{"questions":[]}` xóa câu hỏi của draft. Không có ID trong request; server cấp ID mới khi thay thành công.
Validate toàn bộ trước khi xóa dữ liệu draft cũ; lỗi validation hoặc lỗi transaction giữ nguyên đề cũ.

TeacherExamResponse đầy đủ (ID/thời gian dưới đây chỉ minh họa):

```json
{
  "id":10,"ownerId":1,"title":"Kỳ thi mẫu","description":"Nội dung giới thiệu",
  "durationMinutes":60,"opensAt":"2030-01-02T08:00:00Z","closesAt":"2030-01-02T10:00:00Z",
  "status":"PUBLISHED","publishedAt":"2030-01-01T12:00:00Z",
  "consentVersion":"monitoring-v1","consentText":"Nội dung mẫu do backend chốt",
  "version":1,"createdAt":"2030-01-01T11:00:00Z","updatedAt":"2030-01-01T12:00:00Z",
  "questions":[{"id":20,"content":"2 + 2 = ?","position":1,"options":[
    {"id":30,"content":"4","position":1,"correct":true},
    {"id":31,"content":"5","position":2,"correct":false}
  ]}]
}
```

Draft có status DRAFT, publishedAt null; mới tạo có questions rỗng và version 0.
ExamSummary trong danh sách chỉ có: id, ownerId, title, description, durationMinutes,
opensAt, closesAt, status, publishedAt, createdAt, version (không có questions).
Phân trang rõ ràng, mặc định size=20, page=0, size trong 1–100; size quá giới hạn trả 400.
Exam sort cố định `createdAt DESC, id DESC`; enrollment sort `enrolledAt DESC, id DESC`.
Không nhận sort tùy ý từ client.

```json
{
  "content":[{
    "id":10,"ownerId":1,"title":"Kỳ thi mẫu","description":"Nội dung giới thiệu",
    "durationMinutes":60,"opensAt":"2030-01-02T08:00:00Z","closesAt":"2030-01-02T10:00:00Z",
    "status":"PUBLISHED","publishedAt":"2030-01-01T12:00:00Z","createdAt":"2030-01-01T11:00:00Z","version":1
  }],
  "page":0,"size":20,"totalElements":1,"totalPages":1
}
```

EnrollmentResponse (trong danh sách cũng dùng DTO này, không có hash/password/principal):

```json
{
  "id":40,"examId":10,"student":{"id":2,"email":"student@example.test","displayName":"Sinh viên mẫu"},
  "enrolledBy":1,"enrolledAt":"2030-01-01T12:10:00Z","consentAccepted":false,"acceptedAt":null
}
```

## API sinh viên

Prefix `/api/v1/student/exams`.

| Method/path | Request | Success |
| --- | --- | --- |
| GET collection?page=0&size=20 | Không body | 200 PageResponse<StudentExamResponse> |
| GET /{id} | Không body | 200 StudentExamResponse |
| POST /{id}/consent | `{"accepted":true,"consentVersion":"monitoring-v1"}` | 201 lần đầu / 200 cùng bản ghi |

StudentExamResponse đầy đủ; danh sách cũng chỉ chứa DTO này:

```json
{
  "id":10,"title":"Kỳ thi mẫu","description":"Nội dung giới thiệu","durationMinutes":60,
  "opensAt":"2030-01-02T08:00:00Z","closesAt":"2030-01-02T10:00:00Z","questionCount":1,
  "serverTime":"2030-01-01T12:15:00Z","availability":"UPCOMING",
  "consentVersion":"monitoring-v1","consentText":"Nội dung mẫu do backend chốt","consentAccepted":false
}
```

Không có question content/id, options hoặc correct; không gọi service/DTO trả đề giáo viên để dựng response sinh viên.
accepted phải có và bằng true; consentVersion phải khớp chính xác phiên bản đã hiển thị. Không nhận studentId/acceptedAt.
ConsentResponse:

```json
{"id":50,"enrollmentId":40,"consentVersion":"monitoring-v1","acceptedAt":"2030-01-01T12:15:00Z"}
```

acceptedAt lấy từ Clock server sau khi khóa exam và kiểm tra thời gian; retry cùng version giữ nguyên id/acceptedAt.
Không có hủy enrollment/consent trong phạm vi hiện tại.

## Transaction và đồng thời

Mọi mutation hiện hữu trên một exam đều bắt đầu bằng `SELECT ... FOR UPDATE` trên hàng exam:
PUT metadata, thay questions, publish, enroll và acceptConsent. Sau khi lấy lock mới kiểm tra owner/enrollment,
status và thời gian; lấy một operation timestamp cho kiểm tra deadline và dữ liệu ghi của operation đó.
Tạo draft mới không cần lock trước vì chưa có hàng chung.

Đây là giao thức serialize mutation cho MVP. `@Version` của JPA bảo vệ các update entity exam thông thường,
nhưng không dựa vào version để bảo vệ câu hỏi: parent row lock bao phủ cả replace và publish,
kể cả khi Clock cố định khiến updatedAt không đổi. Không có thêm optimistic retry/lock thứ hai cho child.
FK/unique là lớp bảo vệ cuối. Không bắt DataIntegrityViolationException rồi tiếp tục transaction rollback-only;
lỗi được dịch thành ProblemDetail 409 sau khi transaction đã rollback.

Không có snapshot table: exam PUBLISHED bất biến chính là đề cố định cho MVP.
Khóa chung khiến mutation các sinh viên của cùng exam được xử lý lần lượt; đây là lựa chọn đơn giản cho quy mô MVP.
Read-only transaction dùng query scope quyền; danh sách tối đa 100, một số metadata/count/consent lookup được đọc theo từng item.

## Demo PowerShell bằng cookie + CSRF hiện có

Chạy DB/app theo README, có TEACHER và STUDENT enabled (ví dụ seed local đã bật đúng cấu hình).
Biết studentId từ tài khoản/roster hiện có; chưa thêm API tìm kiếm/quản lý tài khoản ở module này.
Không bật transcript/debug HTTP. Password/token/cookie chỉ ở biến, không in ra console hoặc commit.

```powershell
$base = 'http://localhost:8080'
function Connect-ExamDemo([string]$email) {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $session
    $secure = Read-Host "Password cho $email" -AsSecureString
    $password = (New-Object System.Net.NetworkCredential('', $secure)).Password
    try {
        $body = @{email=$email; password=$password} | ConvertTo-Json -Compress
        $null = Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -WebSession $session `
            -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
            -Headers @{ $csrf.headerName=$csrf.token }
        $csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $session
        return [pscustomobject]@{ Session=$session; Headers=@{ $csrf.headerName=$csrf.token } }
    } finally { $password=$null; $body=$null; $secure=$null }
}
function Invoke-ExamDemo($client,[string]$method,[string]$path,$body=$null) {
    $requestParameters = @{Uri="$base$path"; Method=$method; WebSession=$client.Session; Headers=$client.Headers}
    if ($null -ne $body) {
        $requestParameters.ContentType='application/json; charset=utf-8'
        $requestParameters.Body=[Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress))
    }
    Invoke-RestMethod @requestParameters
}
$teacher = Connect-ExamDemo 'teacher@example.test'
$studentId = [long](Read-Host 'ID của STUDENT enabled cần ghi danh')
$opens = [DateTime]::UtcNow.AddHours(1)
$exam = Invoke-ExamDemo $teacher POST '/api/v1/teacher/exams' @{
    title='Demo kỳ thi'; description='Demo tối thiểu'; durationMinutes=30
    opensAt=$opens.ToString('o'); closesAt=$opens.AddHours(1).ToString('o')
}
$teacherPath = "/api/v1/teacher/exams/$($exam.id)"
$exam = Invoke-ExamDemo $teacher PUT "$teacherPath/questions" @{
    questions=@(@{content='2 + 2 = ?'; position=1; options=@(
        @{content='4';position=1;correct=$true}, @{content='5';position=2;correct=$false}
    )})
}
$exam = Invoke-ExamDemo $teacher POST "$teacherPath/publish"
$enrollment = Invoke-ExamDemo $teacher POST "$teacherPath/enrollments" @{studentId=$studentId}
$student = Connect-ExamDemo 'student@example.test'
$studentPath = "/api/v1/student/exams/$($exam.id)"
$view = Invoke-ExamDemo $student GET $studentPath
$view  # Chỉ metadata + questionCount + consent; không có nội dung đề
$consent = Invoke-ExamDemo $student POST "$studentPath/consent" @{
    accepted=$true; consentVersion=$view.consentVersion
}
$consent | Select-Object id,enrollmentId,consentVersion,acceptedAt
```

Tài khoản student dùng để login phải tương ứng studentId đã ghi danh. Muốn logout/kiểm tra cookie cũ,
xem [auth-session-flow.md](auth-session-flow.md). Không tự tắt CSRF cho demo.

## Kiểm chứng

```bash
./mvnw --batch-mode --no-transfer-progress -Dtest=ExamSchemaIntegrationTest test
./mvnw --batch-mode --no-transfer-progress -Dtest=ExamModuleIntegrationTest test
./mvnw --batch-mode --no-transfer-progress verify
git diff --check
git status --short
```

Test module dùng Tomcat RANDOM_PORT, HTTP client giữ cookie/CSRF và MySQL Testcontainers, không mock user.
Clock inject được kiểm tra boundary mà không sleep.
Race tests khởi động hai transaction/connection riêng, lấy CONNECTION_ID(), chờ latch rồi cùng chạy mutation;
assert hai connection khác nhau, không thay bằng gọi tuần tự. Kết quả chi tiết trong [exam-implementation-report.md](exam-implementation-report.md).
Demo script chỉ là hướng dẫn, không tự chạy trên dữ liệu local/prod của người dùng.
