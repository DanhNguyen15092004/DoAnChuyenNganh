# Chạy toàn bộ dự án bằng Docker

## Kiến trúc

Browser → `http://localhost:8088` → Nginx phục vụ React build.
Request `/api/...` → Nginx → `backend:8080` → `mysql:3306`.
Giữ nguyên đường dẫn, header CSRF và cookie HttpOnly JSESSIONID; không thêm CORS hay tắt CSRF.
Browser không gọi tên service Docker. Không cần Vite dev server hoặc BACKEND_TARGET cho bản build Docker.

- Dockerfile root: JDK 21 + Maven Wrapper build JAR; JRE 21 chạy app bằng user app.
- frontend/Dockerfile: Node 22 + npm ci + Vite build; Nginx phục vụ static và proxy API.
- Build context chỉ cho phép source cần thiết; .env, .git, node_modules và target không vào image.
- MySQL giữ nguyên named volume `mysql_data`. Backend chờ MySQL healthy, frontend chờ backend readiness.
- Các cổng chỉ bind localhost. Không publish app lên Internet trong lượt này.

## Khởi chạy

Cần Docker Engine/Docker Desktop Linux containers và Docker Compose v2 hỗ trợ `--wait`.
Không cần Java/Node/Maven trên host. Tại thư mục chứa pom.xml/docker-compose.yml:

```powershell
# Chỉ chạy dòng copy khi chưa có .env.
Copy-Item .env.example .env
docker compose up -d --build --wait
docker compose ps
```

Đặt credential local của bạn trong .env trước khi khởi tạo MySQL; không commit file này.
Giữ credential đang dùng nếu volume đã có dữ liệu: MYSQL_* chỉ khởi tạo lúc volume rỗng.
Không đổi password trong file rồi xóa volume để xử lý lỗi kết nối.

| Địa chỉ mặc định | Mục đích |
| --- | --- |
| http://localhost:8088 | Frontend và API qua proxy cùng origin |
| http://localhost:8080/actuator/health | Health backend |
| http://localhost:8080/actuator/health/readiness | Readiness app + DB |
| 127.0.0.1:3306 | MySQL từ host |

Dùng nhất quán một hostname khi kiểm thử login/session. Đăng nhập lại sau khi đổi từ frontend
Vite sang frontend Docker; không coi trạng thái UI/cookie là bằng chứng session còn hiệu lực.

## Cổng, seed và phiên đăng nhập

`.env` có thể đặt:

```dotenv
BACKEND_PORT=8080
FRONTEND_PORT=8088
DB_PORT=3306
APP_SEED_ENABLED=false
```

Nếu Maven backend đang chạy ở 8080, dừng tiến trình đó hoặc đặt BACKEND_PORT=8081.
Nginx vẫn dùng backend:8080 trong network Docker. Cổng MySQL nội bộ luôn là 3306,
không phụ thuộc DB_PORT trên host. SERVER_PORT trên host không đổi cổng backend container.
Nếu chỉ muốn MySQL để chạy Maven/Vite trên host: `docker compose up -d mysql`.

Compose dùng profile **local** cho stack HTTP. Muốn seed: tự đặt
SEED_STUDENT_PASSWORD, SEED_TEACHER_PASSWORD, SEED_ADMIN_PASSWORD và APP_SEED_ENABLED=true
trong .env, sau đó tạo lại backend. Mật khẩu được truyền lúc chạy, không bake vào image.
Seeder hiện có không ghi đè password/role của tài khoản đã tồn tại. Không có tài khoản tự đăng ký.
Xem [auth-contract.md](auth-contract.md) và [frontend/README.md](../frontend/README.md).

Session lưu trong bộ nhớ backend: restart/recreate backend sẽ đăng xuất các session hiện tại.
Flyway chạy khi app khởi động, Hibernate validate schema; không có ddl-auto=create/update.
Container không có cơ chế reset hoặc tự xóa dữ liệu.

## Cập nhật, theo dõi, dừng

```powershell
docker compose up -d --build --wait
docker compose ps
docker compose logs --tail=100 backend frontend
docker compose stop
# Hoặc gỡ container/network, vẫn giữ named volume:
docker compose down
```

Không dùng down -v nếu cần giữ dữ liệu. Không log/in .env, cookie, token hoặc password khi chẩn đoán.
Frontend readiness chỉ kiểm tra Nginx; trạng thái DB hiện tại kiểm tra ở backend readiness.

## Build và kiểm tra

```powershell
docker compose config --quiet
docker compose build backend frontend
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
# Trên host Java 21 + Docker hoặc CI để chạy cả Testcontainers:
.\mvnw.cmd verify
```

Dockerfile backend dùng -DskipTests để đóng gói image vì Testcontainers không chạy trong
Docker build. Build image thành công không đồng nghĩa suite backend đã chạy. Kết quả thực tế
của lượt này được ghi tại [docker-verification.md](docker-verification.md).

## Giới hạn deployment

Đây là bộ chạy Docker HTTP local hoàn chỉnh, chưa phải cấu hình production đã xác minh.
Để chạy Internet cần TLS ingress/reverse proxy, profile prod (Secure cookie giữ nguyên),
credential/DB TLS phù hợp, backup, logging và cấu hình forwarded headers theo hạ tầng thực tế.
Không chỉ đổi profile prod rồi truy cập bằng HTTP: cookie Secure sẽ không hỗ trợ luồng đó.
Không tắt Secure/CSRF để né HTTPS. Chưa kiểm thử HTTPS, nhiều instance hoặc distributed session.
Không có module attempt/làm bài/nộp bài/điểm. Không push hoặc deploy remote trong nhiệm vụ này.
