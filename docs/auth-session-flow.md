# Xác thực bằng session cookie

API dùng `JSESSIONID`, session lưu trong bộ nhớ backend. Restart backend làm mất session;
triển khai nhiều instance cần thiết kế session/routing riêng sau này. Không có JWT hoặc remember-me.
Không thay migration V1/V2/V3, model dữ liệu, seeder hoặc dependency ở lượt này.

## Cách lưu authentication

Login được CSRF filter kiểm tra trước khi controller validate JSON/email/password.
Email được chuẩn hóa; password giữ nguyên. AuthenticationManager/DaoAuthenticationProvider hiện có
xác thực MySQL, sau đó credential bị xóa cả ở authentication và principal.

Controller gọi `loginSessionAuthenticationStrategy` **một lần**, gồm
`ChangeSessionIdAuthenticationStrategy` và `CsrfAuthenticationStrategy` dùng repository/handler CSRF chung.
Sau đó tạo context mới, đặt vào SecurityContextHolder và gọi `SecurityContextRepository.saveContext`.
Filter chain dùng chính bean `HttpSessionSecurityContextRepository` đó với `requireExplicitSave=true`;
request tiếp theo đọc authentication từ session, không dựa vào biến trong controller.

Security 6.5 dùng `requireExplicitAuthenticationStrategy=true`; không có SessionManagementFilter
để gọi strategy lần thứ hai. Policy mặc định IF_REQUIRED được giữ; không gọi setter IF_REQUIRED vì
ở phiên bản này setter đó buộc chế độ SessionManagementFilter và xung đột explicit strategy.

Logout dùng LogoutFilter/DSL, matcher chỉ POST `/api/v1/auth/logout`:
CSRF filter phải cho qua trước, rồi handlers xóa CSRF/context, invalidate session phía server,
xóa JSESSIONID path `/`, trả 204. GET cùng URL bị từ chối và không thay đổi session.
Cookie được xóa ở client và authentication cũ trong server cũng bị thu hồi.

## Cookie, timeout và cache

| Cấu hình | Giá trị |
| --- | --- |
| Tên/path | JSESSIONID, `/` |
| HttpOnly | true |
| SameSite | Lax (mô hình cùng site) |
| Secure | true mặc định/prod; false ở local/test HTTP |
| Idle timeout | `SESSION_TIMEOUT`, mặc định `30m` |
| Tracking | cookie; không dùng session id trong URL |
| Cache | CSRF, login và `/me`: `Cache-Control: no-store` |

Không log password, hash, CSRF token hoặc session ID. Các DTO chứa credential/token có toString che nội dung;
response login/me chỉ có id/email/displayName/role. Lỗi controller/filter đều là ProblemDetail.
Sai password, email không tồn tại và disabled cùng 401 với “Email hoặc mật khẩu không hợp lệ.”.
Validation/JSON sai trả 400; thiếu/sai CSRF trả 403. Không redirect hoặc HTML cho API.

Chưa biết frontend origin nên **không cấu hình CORS**. Khi tích hợp frontend cần xác nhận scheme/host/port,
cấu hình allowlist origin cụ thể và credentials nếu cần, cùng `credentials: include` phía client.
Không mở `*`. Nếu thay đổi mô hình same-site cần đánh giá lại SameSite/Secure.

## Ví dụ PowerShell chạy đầy đủ trên local

Chạy MySQL/app theo README, bật seed local với ba mật khẩu riêng do bạn đặt trong environment hoặc `.env`.
Không ghi mật khẩu vào script, lịch sử command, git hoặc output. Ví dụ dưới dùng tài khoản student đã tồn tại.
Cookie/token chỉ giữ trong biến, không in ra console; không bật verbose/debug HTTP hoặc transcript.

```powershell
$base = 'http://localhost:8080'
$browser = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$email = 'student@example.test'
$securePassword = Read-Host 'Password' -AsSecureString
$password = (New-Object System.Net.NetworkCredential('', $securePassword)).Password

try {
    # 1. CSRF public tạo session và token thực sự.
    $csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $browser

    # 2. Login; WebSession tự giữ cookie có session ID mới.
    $body = @{ email = $email; password = $password } | ConvertTo-Json -Compress
    $account = Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -WebSession $browser `
        -ContentType 'application/json' -Body $body -Headers @{ $csrf.headerName = $csrf.token }
    $account | Select-Object id,email,displayName,role

    # Lưu bản sao cookie đã đăng nhập để thử lại sau logout, không hiển thị giá trị.
    $oldSessionValue = $browser.Cookies.GetCookies([Uri]$base)['JSESSIONID'].Value

    # 3. Token trước login đã bị xóa; lấy token mới.
    $csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $browser

    # 4. /me lấy principal từ session.
    Invoke-RestMethod "$base/api/v1/auth/me" -WebSession $browser

    # 5. Probe chỉ tồn tại ở local/test; STUDENT được truy cập nhóm student.
    Invoke-RestMethod "$base/api/v1/student/probe" -WebSession $browser

    # 6. Logout bằng token mới; không có body, trả 204.
    $logout = Invoke-WebRequest "$base/api/v1/auth/logout" -UseBasicParsing -Method Post `
        -WebSession $browser -Headers @{ $csrf.headerName = $csrf.token }
    $logout.StatusCode  # 204

    # 7. Chủ động gửi lại cookie cũ, không dựa vào việc client đã xóa cookie.
    $replay = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $cookie = New-Object System.Net.Cookie('JSESSIONID', $oldSessionValue, '/')
    $replay.Cookies.Add([Uri]$base, $cookie)
    try {
        $null = Invoke-WebRequest "$base/api/v1/auth/me" -UseBasicParsing -WebSession $replay
        throw 'Cookie cũ vẫn truy cập được /me'
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
        'Cookie cũ bị từ chối: 401'
    }

    # Muốn login lại: lấy CSRF mới với session mới trước khi gửi POST.
} finally {
    $password = $null; $body = $null; $csrf = $null; $oldSessionValue = $null
    $cookie = $null; $browser = $null; $replay = $null; $securePassword = $null
}
```

Ma trận áp dụng ở backend (không có role hierarchy):

| Session | student/probe | teacher/probe | admin/probe | auth/me |
| --- | --- | --- | --- | --- |
| Chưa đăng nhập | 401 | 401 | 401 | 401 |
| STUDENT | 200 | 403 | 403 | 200 |
| TEACHER | 403 | 200 | 403 | 200 |
| ADMIN | 403 | 200 | 200 | 200 |

Toàn bộ `/api/v1/{student,teacher,admin}/**` theo ma trận trên; các API chưa được cho phép rõ ràng
mặc định từ chối. Giữ chính xác ngoại lệ health và method/path lỗi mẫu.
Probe không có trong prod, kể cả đồng thời bật local/test.

## Kiểm tra tự động

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

`AuthSessionIntegrationTest` boot Tomcat thật ở cổng ngẫu nhiên + MySQL Testcontainers.
HTTP client giữ Set-Cookie, gửi cookie thật ở request sau, kiểm tra fixation/CSRF rotation,
toàn bộ ma trận và replay cookie đã thu hồi. Không in token/cookie/password vào log.
MockMvc bổ sung kiểm tra credential đã xóa trong context và strategy đúng một lần; không dùng mock user.
`AuthProductionConfigurationTest` bind cookie Secure/timeout từ profile prod và kiểm tra không có probe.
Chưa kiểm chứng hạ tầng HTTPS/reverse proxy hoặc frontend thực tế.

Tham chiếu chính thức: [session management](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)
và [CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).
