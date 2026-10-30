# Contract frontend đã đối chiếu implementation

Đối chiếu ngày 29/10/2026 với AuthController, ExamContracts, TeacherExamController,
StudentExamController, ExamService, SecurityConfiguration và hai exception handler.
Đây là đối chiếu source; kết quả HTTP/browser được ghi riêng trong báo cáo kiểm chứng.

Prefix A = `/api/v1/auth`, T = `/api/v1/teacher/exams`, S = `/api/v1/student/exams`.
M = TEACHER (chỉ exam sở hữu) hoặc ADMIN (mọi exam). U = tài khoản đã đăng nhập.

| Method | URL | Request | Response | Role | CSRF | Pagination |
| --- | --- | --- | --- | --- | --- | --- |
| GET | A/csrf | — | 200 {headerName,token} | Public | Không | — |
| POST | A/login | {email,password} | 200 Account | Public | Có | — |
| POST | A/register | {email,password,displayName} | 201 Account (STUDENT), chưa login | Public | Có | — |
| GET | A/me | — | 200 Account | U | Không | — |
| POST | A/logout | — | 204, không body | Public | Có | — |
| GET | T | — | 200 Page<Summary> | M | Không | page,size |
| POST | T | Metadata | 201 TeacherExam + Location | M | Có | — |
| GET | T/{id} | — | 200 TeacherExam | M | Không | — |
| PUT | T/{id} | Metadata | 200 TeacherExam | M | Có | — |
| PUT | T/{id}/questions | {questions:[QuestionInput]} | 200 TeacherExam | M | Có | — |
| POST | T/{id}/publish | — | 200 TeacherExam | M | Có | — |
| POST | T/{id}/enrollments | {studentId} | 201 mới / 200 có sẵn Enrollment | M | Có | — |
| GET | T/{id}/enrollments | — | 200 Page<Enrollment> | M | Không | page,size |
| GET | S | — | 200 Page<StudentExam> | STUDENT | Không | page,size |
| GET | S/{id} | — | 200 StudentExam | STUDENT | Không | — |
| POST | S/{id}/consent | {accepted:true,consentVersion} | 201 mới / 200 có sẵn Consent | STUDENT | Có | — |
| POST | S/{id}/attempt | Body rỗng | 201 + Location tạo / 200 lượt cũ AttemptResponse | STUDENT | Có | — |
| GET | S/{id}/attempt | — | 200 AttemptResponse / 404 chưa có | STUDENT | Không | — |
| GET | /api/v1/student/attempts/{attemptId} | — | 200 AttemptResponse chính chủ | STUDENT | Không | — |

Contract attempt bổ sung 30/10/2026: [attempt-module.md](attempt-module.md).
AttemptResponse có attemptId,examId,title,startedAt,deadlineAt,serverTime,availability,
questions. ACTIVE: question id/content/position/options, option id/content/position;
EXPIRED: questions=[]. Không có correct/isCorrect/correctOptionId/lời giải.

- Account: id,email,displayName,role (STUDENT/TEACHER/ADMIN).
- Metadata: title (không trắng, tối đa 200), description (nullable, 5000), durationMinutes
  (integer 1–300), opensAt, closesAt (Instant). Khoảng mở phải dương và đủ thời lượng.
- QuestionInput: content (không trắng, 5000), position (integer >0), options (2–6).
  OptionInput: content (không trắng, 2000), position (>0), correct (boolean).
  Tối đa 100 câu; vị trí duy nhất theo cha, chính xác một đáp án đúng/câu. Lưu mảng rỗng
  được phép ở draft, nhưng publish cần ít nhất một câu. Request không nhận ID câu/option.
- Summary: id,ownerId,title,description,durationMinutes,opensAt,closesAt,status,
  publishedAt,createdAt,version. status chỉ DRAFT/PUBLISHED.
- TeacherExam: Summary + consentVersion,consentText,updatedAt,questions.
  QuestionResponse/OptionResponse thêm id vào input; không được gửi các ID này khi lưu.
- StudentExam: id,title,description,durationMinutes,opensAt,closesAt,questionCount,
  serverTime,availability (UPCOMING/OPEN/CLOSED),consentVersion,consentText,consentAccepted.
  Không có câu hỏi/lựa chọn/đáp án và **không có acceptedAt**.
- Enrollment: id,examId,student:{id,email,displayName},enrolledBy,enrolledAt,
  consentAccepted,acceptedAt (nullable). Consent: id,enrollmentId,consentVersion,acceptedAt.
- Page: content,page,size,totalElements,totalPages. Mặc định page=0,size=20;
  size 1–100, page >=0. Sort exam createdAt DESC,id DESC; enrollment enrolledAt DESC,id DESC.
- Publish đóng băng metadata/questions. Enrollment/consent chỉ khi PUBLISHED và chưa đóng,
  được phép trước giờ mở. Không có API tìm user; lấy studentId thật từ /me.

## Session, lỗi và khác biệt cần lưu ý

CSRF dùng HttpSessionCsrfTokenRepository; headerName nhận từ response. Token phải lấy lại
sau login/logout. Cookie HttpOnly JSESSIONID, SameSite=Lax, local HTTP Secure=false;
prod Secure=true. Backend port đọc SERVER_PORT, mặc định 8080 trong application.yml.
Dev dùng proxy cùng origin, không cần sửa CORS hoặc security backend.

ProblemDetail: validation 400 có thể có errors:[{field,message}], nhưng lỗi domain/JSON
không nhất thiết có. 401 auth sai dùng thông báo chung. 403 CSRF và sai quyền không có
code/type riêng (about:blank), nên không thể phân loại chính xác ở frontend.
404 gồm ngoài scope; 409 gồm version consent, immutable, hết giờ và concurrency.
Không suy đoán loại conflict từ chuỗi detail: mọi 409 consent đều tải lại và bỏ tick.

Tài liệu exam khớp source trong các contract trên. docs/auth-session-flow.md đề cập cấu hình
CORS khi biết origin; dev frontend này dùng Vite proxy nên không thêm CORS. Thời điểm consent
chỉ có sau POST hoặc ở danh sách giáo viên; sau reload trang sinh viên chỉ hiển thị trạng thái.
