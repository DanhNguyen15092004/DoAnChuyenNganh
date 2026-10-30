# Kiểm chứng Docker — 29/10/2026

## Thay đổi

- Root `Dockerfile`, `.dockerignore`: build Maven Wrapper/Java 21, runtime JRE 21,
  user app không phải root; build context loại environment/Git/artifact.
- `frontend/Dockerfile`, `.dockerignore`, `nginx.conf`: npm ci theo lockfile hiện có,
  React/Vite build, Nginx static + proxy /api cùng origin; không bake .env vào image.
- `docker-compose.yml`: thêm backend/frontend, healthcheck và depends_on healthy.
  Giữ MySQL 8.0, named volume hiện có, kết nối nội bộ mysql:3306.
- `frontend/vite.config.js`: chỉ yêu cầu BACKEND_TARGET khi serve; static build không
  cần địa chỉ backend. Docker dùng Nginx thay dev proxy.
- `.env.example`: thêm BACKEND_PORT/FRONTEND_PORT; không sửa .env thực tế.
- README root/frontend, docs/docker.md, báo cáo này; ghi chú cập nhật báo cáo frontend cũ.

Không sửa Java/Spring Boot, pom, auth, CSRF, cookie, migration/schema hoặc test backend.
Không thay đổi password/role của tài khoản trên database hiện có.

## Kết quả thực tế

| Kiểm tra | Kết quả |
| --- | --- |
| docker compose config --quiet | PASS |
| npm --prefix frontend run build | PASS, Vite 7.3.7 |
| npm --prefix frontend run lint | PASS, không warning |
| npm --prefix frontend test | PASS 3/3, không skip |
| docker compose build backend frontend | PASS cả hai image |
| Compose project kiểm thử riêng up --build --wait | MySQL/backend/frontend đều healthy |
| Backend /actuator/health/readiness | 200, UP |
| Flyway trên DB kiểm thử | Log xác nhận V1–V4 áp dụng thành công, version v4 |
| Frontend index và JS build qua Nginx | 200/200 |
| GET /api/v1/auth/me anonymous qua Nginx | 401 |
| CSRF → login STUDENT qua Nginx | 200, role STUDENT, ID 1 từ response thật |
| CSRF mới → GET /me với session | 200, cùng ID tài khoản đã đăng nhập |
| POST logout qua Nginx | 204 |
| /me sau logout và phát lại cookie cũ | Cả hai 401 |
| nginx -t trên container frontend | PASS |
| User backend image | UID 100, không phải root |
| Backend /app/.env; frontend .env/.env.local | Không tồn tại trong runtime image |
| git diff --check | PASS, chỉ cảnh báo chuyển LF/CRLF của Git |

Kiểm tra user/context đầu tiên trong helper PowerShell bị lỗi quoting khi truyền sh -c;
helper kết thúc exit 1 ở bước phụ này sau khi toàn bộ HTTP/session checks đã đạt.
Đã chạy lại trực tiếp bằng docker run --entrypoint id/test trên cùng image, không dùng
shell quoting: UID 100 và ba kiểm tra loại environment đều đạt. Không đổi ứng dụng để né lỗi.

Dockerfile backend đóng gói với -DskipTests vì Testcontainers cần chạy ở host/CI,
không có Docker daemon trong build. Không chạy lại Maven verify trong lượt Docker này;
không coi image build là bằng chứng suite backend đã chạy. Runtime Flyway và Hibernate
ddl-auto=validate đã hoàn tất khi app khởi động với MySQL thật.

## Cách ly và dữ liệu kiểm thử

Project `doan-docker-check-20261029`, database `docker_verification`, volume
`doan-docker-check-20261029_mysql_data`; các cổng kiểm thử 18088/18080/33306.
Credential sinh ngẫu nhiên chỉ dùng trong process kiểm thử, không ghi source/log/report.
Seeder local tạo ba tài khoản student@example.test, teacher@example.test, admin@example.test
trong **database kiểm thử riêng**. Không tạo exam, enrollment hoặc consent.

Đã dừng cả ba container kiểm thử, giữ nguyên volume/dữ liệu. Không xóa/reset volume.
Không dùng lại credential fixture làm tài khoản demo của người dùng.
Container MySQL ban đầu `doanchuyennganh-mysql-1` vẫn chạy tại 3306; không thay đổi nó.

## Giới hạn

Đây là HTTP integration qua container thật, không phải kiểm chứng thao tác UI trình duyệt.
Chưa xác minh toàn bộ luồng kỳ thi trong browser, HTTPS production, TLS ingress,
distributed session hoặc CI remote. Không push/deploy lên server.

Chạy stack chính theo [docker.md](docker.md):

```powershell
docker compose up -d --build --wait
```

Mặc định frontend http://localhost:8088, health backend http://localhost:8080/actuator/health.
Cần giải phóng cổng backend host hoặc đặt BACKEND_PORT nếu Maven đang chạy.
