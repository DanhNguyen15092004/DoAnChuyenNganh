package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AuthSessionIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    private static final String PASSWORD = "  Http-Fixture-Password  ";
    private static final String AUTH = "/api/v1/auth";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired FilterChainProxy filters;
    @LocalServerPort int port;
    @MockitoSpyBean(name = "loginSessionAuthenticationStrategy")
    SessionAuthenticationStrategy strategy;

    @BeforeEach
    void fixtures() {
        for (Role role : Role.values()) {
            if (users.findByEmail(email(role)).isEmpty()) {
                users.saveAndFlush(new User(email(role), encoder.encode(PASSWORD), "HTTP " + role, role, true));
            }
        }
        if (users.findByEmail("disabled.http@example.test").isEmpty()) {
            users.saveAndFlush(new User("disabled.http@example.test", encoder.encode(PASSWORD), "Disabled", Role.STUDENT, false));
        }
    }

    @Test
    void mockMvcLoginPersistsErasedContextAndInvokesStrategyExactlyOnce() throws Exception {
        clearInvocations(strategy);
        var csrfResult = mvc.perform(get(AUTH + "/csrf")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String before = session.getId();
        String token = mapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post(AUTH + "/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(loginBody(email(Role.STUDENT), PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(before.equals(session.getId())).isFalse();
        var saved = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved.getAuthentication().getCredentials()).isNull();
        assertThat(((AccountPrincipal) saved.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get(AUTH + "/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email(Role.STUDENT)))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(strategy, times(1)).onAuthentication(any(), any(), any());
        assertThat(filters.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream()).toList())
                .noneMatch(SessionManagementFilter.class::isInstance);
    }

    @Test
    void realHttpCsrfLoginMeMilestoneAndCookieAttributes() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String before = browser.cookie;
        String beforeToken = browser.token;
        HttpResponse<String> login = browser.post(AUTH + "/login", loginBody("  STUDENT.HTTP@EXAMPLE.TEST  ", PASSWORD), true);
        httpStatus(login, 200);
        assertAccount(login, Role.STUDENT);
        assertThat(before.equals(browser.cookie)).isFalse();
        assertCookieAttributes(browser.lastSessionCookie);
        var me = browser.get(AUTH + "/me");
        httpStatus(me, 200);
        assertAccount(me, Role.STUDENT);
        assertThat(me.headers().firstValue("Cache-Control")).hasValue("no-store");
        var oldAnonymous = new Browser();
        oldAnonymous.cookie = before;
        httpStatus(oldAnonymous.get(AUTH + "/me"), 401);
        // The old token must not work with the new authenticated session.
        browser.token = beforeToken;
        httpStatus(browser.post(AUTH + "/logout", "", true), 403);
        httpStatus(browser.get(AUTH + "/me"), 200);
        browser.csrf();
        httpStatus(browser.post(AUTH + "/logout", "", true), 204);
    }

    @Test
    void realHttpFullRoleMatrixUsesOnlySessionsFromLogin() throws Exception {
        for (Role role : Role.values()) {
            var browser = loggedIn(role);
            httpStatus(browser.get(AUTH + "/me"), 200);
            for (Role group : Role.values()) {
                boolean allowed = group == Role.STUDENT ? role == Role.STUDENT
                        : group == Role.TEACHER ? role == Role.TEACHER || role == Role.ADMIN : role == Role.ADMIN;
                var response = browser.get(probe(group));
                httpStatus(response, allowed ? 200 : 403);
                if (!allowed) { problem(response, 403); }
            }
            problem(browser.get("/api/v1/unlisted"), 403);
            browser.csrf();
            httpStatus(browser.post(AUTH + "/logout", "", true), 204);
        }
        for (Role group : Role.values()) { problem(new Browser().get(probe(group)), 401); }
    }

    @Test
    void realHttpWrongPasswordMissingEmailAndDisabledReturnIdentical401() throws Exception {
        var browser = new Browser();
        browser.csrf();
        JsonNode expected = null;
        for (var pair : new String[][]{{email(Role.STUDENT), "wrong"}, {"missing.http@example.test", PASSWORD},
                {"disabled.http@example.test", PASSWORD}, {email(Role.STUDENT), PASSWORD.trim()}}) {
            var response = browser.post(AUTH + "/login", loginBody(pair[0], pair[1]), true);
            problem(response, 401);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.get("detail").asText()).isEqualTo("Email hoặc mật khẩu không hợp lệ.");
            if (expected == null) { expected = body; } else { assertThat(body.equals(expected)).isTrue(); }
            problem(browser.get(AUTH + "/me"), 401);
        }
    }

    @Test
    void realHttpInvalidRequestsAre400AndMissingOrWrongCsrfIs403() throws Exception {
        var browser = new Browser();
        browser.csrf();
        for (String body : new String[]{"{", "{}", "null", loginBody("invalid-email", PASSWORD),
                loginBody(email(Role.STUDENT), " ")}) {
            problem(browser.post(AUTH + "/login", body, true), 400);
        }
        problem(browser.post(AUTH + "/login", loginBody(email(Role.STUDENT), PASSWORD), false), 403);
        browser.token = "invalid-fixture-token";
        problem(browser.post(AUTH + "/login", loginBody(email(Role.STUDENT), PASSWORD), true), 403);
        problem(browser.get(AUTH + "/me"), 401);
    }

    @Test
    void realHttpLogoutRevokesCopiedCookieAndRejectedLogoutPreservesSession() throws Exception {
        var browser = loggedIn(Role.ADMIN);
        String copiedCookie = browser.cookie;
        browser.csrf();
        String validToken = browser.token;
        problem(browser.post(AUTH + "/logout", "", false), 403);
        httpStatus(browser.get(AUTH + "/me"), 200);
        browser.token = "invalid-fixture-token";
        problem(browser.post(AUTH + "/logout", "", true), 403);
        httpStatus(browser.get(AUTH + "/me"), 200);
        problem(browser.get(AUTH + "/logout"), 403);
        httpStatus(browser.get(AUTH + "/me"), 200);
        browser.token = validToken;
        var response = browser.post(AUTH + "/logout", "", true);
        httpStatus(response, 204);
        assertThat(response.body()).isEmpty();
        String deletedCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(deletedCookie.contains("Max-Age=0")).isTrue();
        assertThat(deletedCookie.contains("Path=/")).isTrue();
        // Bypass client cookie deletion: explicitly replay the saved authenticated cookie.
        var replay = new Browser();
        replay.cookie = copiedCookie;
        problem(replay.get(AUTH + "/me"), 401);
        problem(replay.get(probe(Role.ADMIN)), 401);
        // Its former CSRF token cannot authorize another POST either.
        replay.token = validToken;
        problem(replay.post(AUTH + "/logout", "", true), 403);
    }

    @Test
    void realHttpAnonymousLogoutWithValidCsrfIs204() throws Exception {
        var browser = new Browser();
        browser.csrf();
        httpStatus(browser.post(AUTH + "/logout", "", true), 204);
    }

    private Browser loggedIn(Role role) throws Exception {
        var browser = new Browser();
        browser.csrf();
        var response = browser.post(AUTH + "/login", loginBody(email(role), PASSWORD), true);
        httpStatus(response, 200);
        assertAccount(response, role);
        return browser;
    }

    private void assertAccount(HttpResponse<String> response, Role role) throws Exception {
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.get("id").asLong()).isEqualTo(users.findByEmail(email(role)).orElseThrow().getId());
        assertThat(body.get("email").asText()).isEqualTo(email(role));
        assertThat(body.get("role").asText()).isEqualTo(role.name());
        assertThat(body.get("displayName").asText()).isEqualTo("HTTP " + role);
    }

    private void problem(HttpResponse<String> response, int expected) throws Exception {
        httpStatus(response, expected);
        assertThat(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json")).isTrue();
        assertThat(mapper.readTree(response.body()).get("status").asInt()).isEqualTo(expected);
    }

    private static void httpStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    private static void assertCookieAttributes(String header) {
        String lower = header.toLowerCase(java.util.Locale.ROOT);
        assertThat(lower.contains("httponly")).isTrue();
        assertThat(lower.contains("path=/")).isTrue();
        assertThat(lower.contains("samesite=lax")).isTrue();
        assertThat(lower.contains("secure")).isFalse();
    }

    private String loginBody(String email, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private static String email(Role role) { return role.name().toLowerCase(java.util.Locale.ROOT) + ".http@example.test"; }
    private static String probe(Role role) { return "/api/v1/" + role.name().toLowerCase(java.util.Locale.ROOT) + "/probe"; }

    /** Explicit cookie jar permits replay of revoked IDs without ever printing them. */
    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        private String cookie;
        private String token;
        private String lastSessionCookie;

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, false); }
        HttpResponse<String> post(String path, String body, boolean csrf) throws Exception { return send("POST", path, body, csrf); }

        void csrf() throws Exception {
            var response = get(AUTH + "/csrf");
            httpStatus(response, 200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var json = mapper.readTree(response.body());
            assertThat(json.get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
            token = json.get("token").asText();
            assertThat(token != null && !token.isBlank()).isTrue();
        }

        private HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            if (cookie != null) { builder.header("Cookie", cookie); }
            if (csrf) { builder.header("X-CSRF-TOKEN", token); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                if (header.startsWith("JSESSIONID=")) {
                    lastSessionCookie = header;
                    cookie = header.contains("Max-Age=0") ? null : header.substring(0, header.indexOf(';'));
                }
            }
            return response;
        }
    }
}
