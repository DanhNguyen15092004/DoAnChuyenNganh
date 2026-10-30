package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.auth.RegistrationController.RegistrationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RegistrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    private static final String PASSWORD = "  Registration-test1234  ";
    private static final String AUTH = "/api/v1/auth";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    @Test
    void registrationPersistsNormalizedStudentAndRealLoginPreservesPasswordWhitespace() throws Exception {
        var browser = browser();
        String email = email();
        var response = browser.post(AUTH + "/register", body("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD, "  Sinh viên mới  "), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode account = mapper.readTree(response.body());
        assertThat(account.size()).isEqualTo(4);
        assertThat(account.get("email").asText()).isEqualTo(email);
        assertThat(account.get("role").asText()).isEqualTo("STUDENT");
        assertThat(account.get("displayName").asText()).isEqualTo("Sinh viên mới");
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.STUDENT);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(encoder.matches(PASSWORD.trim(), user.getPasswordHash())).isFalse();
        // Registration creates an account, never an authenticated session.
        assertThat(browser.get(AUTH + "/me").statusCode()).isEqualTo(401);
        var login = browser.post(AUTH + "/login", mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)), true);
        assertThat(login.statusCode()).isEqualTo(200);
        browser.csrf();
        assertThat(mapper.readTree(browser.get(AUTH + "/me").body()).get("id").asLong()).isEqualTo(user.getId());
        assertThat(browser.get("/api/v1/teacher/exams").statusCode()).isEqualTo(403);
        assertThat(browser.post(AUTH + "/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(browser.get(AUTH + "/me").statusCode()).isEqualTo(401);
    }

    @Test
    void duplicateNormalizedEmailDoesNotOverwritePasswordNameOrRole() throws Exception {
        String email = email();
        users.saveAndFlush(new User(email, encoder.encode(PASSWORD), "Original teacher", Role.TEACHER, true));
        var response = browser().post(AUTH + "/register", body(email.toUpperCase(java.util.Locale.ROOT), "other1234", "Replacement"), true);
        problem(response, 409);
        var original = users.findByEmail(email).orElseThrow();
        assertThat(original.getDisplayName()).isEqualTo("Original teacher");
        assertThat(original.getRole()).isEqualTo(Role.TEACHER);
        assertThat(encoder.matches(PASSWORD, original.getPasswordHash())).isTrue();
    }

    @Test
    void missingAndWrongCsrfAreForbiddenWithoutInsertingAccounts() throws Exception {
        String email = email();
        var browser = browser();
        problem(browser.post(AUTH + "/register", body(email, PASSWORD, "Student"), false), 403);
        browser.token = "incorrect-token";
        problem(browser.post(AUTH + "/register", body(email, PASSWORD, "Student"), true), 403);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void rejectsInvalidFieldsAndAnyAttemptToChooseRoleOrEnabled() throws Exception {
        String email = email();
        var browser = browser();
        for (String body : List.of("{}", "{", body("invalid", PASSWORD, "Name"), body(email, "short", "Name"),
                body(email, PASSWORD, "  "), body(email, PASSWORD, "x".repeat(101)),
                mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "displayName", "Name", "role", "ADMIN")),
                mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "displayName", "Name", "enabled", false)))) {
            problem(browser.post(AUTH + "/register", body, true), 400);
        }
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void bcryptLimitIsMeasuredInUtf8BytesAndNeverTruncates() throws Exception {
        var browser = browser();
        String rejected = email();
        problem(browser.post(AUTH + "/register", body(rejected, "đ".repeat(37), "Name"), true), 400);
        assertThat(users.findByEmail(rejected)).isEmpty();
        String accepted = email();
        String password = "đ".repeat(36);
        assertThat(browser.post(AUTH + "/register", body(accepted, password, "Name"), true).statusCode()).isEqualTo(201);
        assertThat(encoder.matches(password, users.findByEmail(accepted).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void concurrentRegistrationCreatesOneAccountAndReportsConflict() throws Exception {
        String email = email();
        var first = browser();
        var second = browser();
        String input = body(email, PASSWORD, "Concurrent student");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.post(AUTH + "/register", input, true).statusCode(); });
            var b = pool.submit(() -> { start.await(); return second.post(AUTH + "/register", input, true).statusCode(); });
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(users.findAll().stream().filter(user -> user.getEmail().equals(email)).count()).isEqualTo(1);
    }

    @Test
    void credentialDtoDoesNotRevealPasswordInToString() {
        assertThat(new RegistrationRequest("student@example.test", PASSWORD, "Name").toString().contains(PASSWORD)).isFalse();
    }

    private String email() { return "register-" + UUID.randomUUID() + "@example.test"; }
    private String body(String email, String password, String name) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", password, "displayName", name));
    }
    private void problem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).get("status").asInt()).isEqualTo(status);
    }
    private Browser browser() throws Exception { var browser = new Browser(); browser.csrf(); return browser; }
    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        private String token;
        private String header;
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, false); }
        HttpResponse<String> post(String path, String body, boolean csrf) throws Exception { return send("POST", path, body, csrf); }
        void csrf() throws Exception {
            var response = get(AUTH + "/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            var json = mapper.readTree(response.body()); header = json.get("headerName").asText(); token = json.get("token").asText();
        }
        private HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
            if (csrf) request.header(header, token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
