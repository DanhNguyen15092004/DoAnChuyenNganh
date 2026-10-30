package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.*;
import com.example.doanchuyennganh.account.security.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static com.example.doanchuyennganh.exam.ExamContracts.*;
import static com.example.doanchuyennganh.exam.AttemptContracts.AttemptResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Testcontainers
@Import(ExamAttemptIntegrationTest.TimeConfiguration.class)
class ExamAttemptIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    static final String PASSWORD = "Attempt-Fixture-Only";
    @LocalServerPort int port;
    @Autowired ExamService exams;
    @Autowired ExamAttemptService service;
    @Autowired ExamAttemptRepository attempts;
    @Autowired ExamConsentRepository consents;
    @Autowired UserRepository users;
    @Autowired DatabaseUserDetailsService details;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired AdjustableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void fixtures() {
        clock.set(NOW);
        for (String name : List.of("student", "other", "teacher", "admin")) {
            Role role = name.equals("teacher") ? Role.TEACHER : name.equals("admin") ? Role.ADMIN : Role.STUDENT;
            if (users.findByEmail(email(name)).isEmpty()) {
                users.saveAndFlush(new User(email(name), encoder.encode(PASSWORD), name, role, true));
            }
        }
    }
    AccountPrincipal actor(String name) { return details.loadUserByUsername(email(name)); }
    String email(String name) { return name + ".attempt@example.test"; }
    String startPath(Long examId) { return "/api/v1/student/exams/" + examId + "/attempt"; }
    String getPath(Long attemptId) { return "/api/v1/student/attempts/" + attemptId; }
    TeacherExamResponse published(Instant opens, Instant closes) {
        var teacher = actor("teacher");
        var exam = exams.create(teacher, new MetadataRequest("ATTEMPT-TEST-" + UUID.randomUUID(), "Fixture", 10, opens, closes));
        var choices = List.of(new OptionRequest("Option B", 2, true), new OptionRequest("Option A", 1, false));
        exams.replaceQuestions(teacher, exam.id(), new QuestionsRequest(List.of(
                new QuestionRequest("Second question", 2, choices), new QuestionRequest("First question", 1, choices))));
        return exams.publish(teacher, exam.id());
    }
    record Ready(TeacherExamResponse exam, Long enrollmentId) { }
    Ready ready(Instant opens, Instant closes, boolean consent) {
        var exam = published(opens, closes);
        var enrollment = exams.enroll(actor("teacher"), exam.id(), new EnrollmentRequest(actor("student").getId())).body();
        if (consent) exams.acceptConsent(actor("student"), exam.id(), new ConsentRequest(true, exam.consentVersion()));
        return new Ready(exam, enrollment.id());
    }
    Ready ready() { return ready(NOW, NOW.plusSeconds(3600), true); }

    @Test void realHttpStartReloadAndRepeatKeepIdentityAndNeverExposeAnswers() throws Exception {
        var fixture = ready(); var student = login("student");
        problem(student.get(startPath(fixture.exam.id())), 404);
        var first = student.post(startPath(fixture.exam.id()), null); expect(first, 201);
        var body = json(first); assertShape(body, true);
        long id = body.get("attemptId").asLong();
        assertThat(first.headers().firstValue("Location")).hasValue(getPath(id));
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(Instant.parse(body.get("startedAt").asText())).isEqualTo(NOW);
        assertThat(Instant.parse(body.get("deadlineAt").asText())).isEqualTo(NOW.plusSeconds(600));
        assertThat(body.get("examId").asLong()).isEqualTo(fixture.exam.id());
        clock.set(NOW.plusSeconds(30));
        for (var repeat : List.of(student.get(getPath(id)), student.get(startPath(fixture.exam.id())), student.post(startPath(fixture.exam.id()), null))) {
            expect(repeat, 200); assertSameTiming(body, json(repeat)); assertShape(json(repeat), true);
            assertThat(repeat.headers().firstValue("Location")).isEmpty();
            assertThat(repeat.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(Instant.parse(json(repeat).get("serverTime").asText())).isEqualTo(NOW.plusSeconds(30));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE enrollment_id=?", Integer.class, fixture.enrollmentId)).isEqualTo(1);
        var metadata = json(student.get("/api/v1/student/exams/" + fixture.exam.id()));
        assertThat(metadata.has("questions") || metadata.has("options") || metadata.has("correct")).isFalse();
        assertThat(metadata.size()).isEqualTo(12);
    }

    @Test void missingExamDraftAndUnenrolledAre404() throws Exception {
        var student = login("student"); var exam = published(NOW, NOW.plusSeconds(3600));
        for (Long id : List.of(exam.id(), Long.MAX_VALUE)) {
            problem(student.post(startPath(id), null), 404); problem(student.get(startPath(id)), 404);
        }
        var draft = exams.create(actor("teacher"), new MetadataRequest("Draft", null, 10, NOW, NOW.plusSeconds(3600)));
        problem(student.post(startPath(draft.id()), null), 404);
        problem(student.get(getPath(Long.MAX_VALUE)), 404);
    }

    @Test void anotherEnrolledStudentCannotReadSomeoneElsesAttempt() throws Exception {
        var fixture = ready(); var owner = service.start(actor("student"), fixture.exam.id()).body();
        exams.enroll(actor("teacher"), fixture.exam.id(), new EnrollmentRequest(actor("other").getId()));
        var other = login("other");
        problem(other.get(getPath(owner.attemptId())), 404);
        problem(other.get(startPath(fixture.exam.id())), 404);
    }

    @Test void missingOrStaleConsentIs409WithoutCreatingAttempt() throws Exception {
        var fixture = ready(NOW, NOW.plusSeconds(3600), false); var student = login("student");
        problem(student.post(startPath(fixture.exam.id()), null), 409);
        var consent = new ExamConsent(); consent.enrollmentId = fixture.enrollmentId;
        consent.consentVersion = "stale-version"; consent.acceptedAt = NOW; consents.saveAndFlush(consent);
        problem(student.post(startPath(fixture.exam.id()), null), 409);
        assertThat(attempts.findByEnrollmentId(fixture.enrollmentId)).isEmpty();
    }

    @Test void beforeOpenIs409AndExactlyOpenIsAllowed() throws Exception {
        Instant opens = NOW.plusSeconds(3600);
        var fixture = ready(opens, opens.plusSeconds(3600), true); var student = login("student");
        clock.set(opens.minusNanos(1000)); problem(student.post(startPath(fixture.exam.id()), null), 409);
        clock.set(opens); var response = student.post(startPath(fixture.exam.id()), null); expect(response, 201);
        assertThat(Instant.parse(json(response).get("startedAt").asText())).isEqualTo(opens);
    }

    @Test void exactlyClosedCannotCreateNewAttempt() throws Exception {
        var fixture = ready(); clock.set(fixture.exam.closesAt());
        problem(login("student").post(startPath(fixture.exam.id()), null), 409);
        assertThat(attempts.findByEnrollmentId(fixture.enrollmentId)).isEmpty();
    }

    @Test void deadlineIsClippedToCloseWhenStartingLate() throws Exception {
        var fixture = ready(); Instant start = fixture.exam.closesAt().minusSeconds(120); clock.set(start);
        var response = service.start(actor("student"), fixture.exam.id()).body();
        assertThat(response.startedAt()).isEqualTo(start);
        assertThat(response.deadlineAt()).isEqualTo(fixture.exam.closesAt());
    }

    @Test void startOneMicrosecondBeforeCloseIsStillValidAndExpiresExactlyAtClose() throws Exception {
        var fixture = ready(); clock.set(fixture.exam.closesAt().minusNanos(1000));
        var result = service.start(actor("student"), fixture.exam.id()).body();
        assertThat(result.availability()).isEqualTo(AttemptContracts.Availability.ACTIVE);
        assertThat(result.deadlineAt()).isEqualTo(fixture.exam.closesAt());
        clock.set(fixture.exam.closesAt());
        assertThat(service.get(actor("student"), result.attemptId()).questions()).isEmpty();
    }

    @Test void exactDeadlineIsExpiredAndAllReadPathsReturnNoPaper() throws Exception {
        var fixture = ready(); var student = login("student");
        var original = json(student.post(startPath(fixture.exam.id()), null));
        Instant deadline = Instant.parse(original.get("deadlineAt").asText());
        String path = getPath(original.get("attemptId").asLong());
        clock.set(deadline.minusNanos(1000)); assertShape(json(student.get(path)), true);
        clock.set(deadline);
        for (var response : List.of(student.get(path), student.get(startPath(fixture.exam.id())), student.post(startPath(fixture.exam.id()), null))) {
            expect(response, 200); assertShape(json(response), false); assertSameTiming(original, json(response));
        }
    }

    @Test void repeatAfterExamClosesReturnsOldAttemptWithoutExtendingTime() throws Exception {
        var fixture = ready(); var student = login("student");
        var original = json(student.post(startPath(fixture.exam.id()), null));
        clock.set(fixture.exam.closesAt().plusSeconds(30));
        var repeat = student.post(startPath(fixture.exam.id()), null); expect(repeat, 200);
        assertSameTiming(original, json(repeat)); assertShape(json(repeat), false);
        assertSameTiming(original, json(student.get(startPath(fixture.exam.id()))));
        assertSameTiming(original, json(student.get(getPath(original.get("attemptId").asLong()))));
    }

    @Test void filtersRequireStudentSessionAndCsrfOnStart() throws Exception {
        var fixture = ready(); String path = startPath(fixture.exam.id()); var anonymous = new Client();
        problem(anonymous.get(path), 401); problem(anonymous.get(getPath(1L)), 401);
        anonymous.csrf(); problem(anonymous.post(path, null), 401);
        for (String name : List.of("teacher", "admin")) {
            var manager = login(name); problem(manager.get(path), 403);
            problem(manager.post(path, null), 403); problem(manager.get(getPath(1L)), 403);
        }
        var student = login("student"); student.token = null; problem(student.post(path, null), 403);
        student.token = "invalid-csrf"; problem(student.post(path, null), 403);
        assertThat(attempts.findByEnrollmentId(fixture.enrollmentId)).isEmpty();
    }

    @Test void bodyCannotSupplyIdentityTimingOrEvenAnEmptyObject() throws Exception {
        var fixture = ready(); var student = login("student");
        for (Object body : List.of(Map.of(), Map.of("studentId", 1), Map.of("startedAt", NOW.toString()),
                Map.of("deadlineAt", NOW.plusSeconds(9000).toString()), Map.of("durationMinutes", 300), "untrusted")) {
            problem(student.post(startPath(fixture.exam.id()), body), 400);
        }
        assertThat(attempts.findByEnrollmentId(fixture.enrollmentId)).isEmpty();
    }

    @Test void serviceAlsoEnforcesStudentRoleWithoutDependingOnUrlFilters() {
        var fixture = ready();
        for (String name : List.of("teacher", "admin")) {
            assertThatThrownBy(() -> service.start(actor(name), fixture.exam.id())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.current(actor(name), fixture.exam.id())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.get(actor(name), 1L)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void concurrentStartsUseSeparateTransactionsAndConnectionsButOneAttempt() throws Exception {
        var fixture = ready(); var student = actor("student");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var connections = new ConcurrentLinkedQueue<Long>(); var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Creation<AttemptResponse>> worker = () -> new TransactionTemplate(transactions).execute(status -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); ready.countDown();
                try { if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Race barrier timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return service.start(student, fixture.exam.id());
            });
            var a = pool.submit(worker); var b = pool.submit(worker);
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(new HashSet<>(connections)).hasSize(2);
            assertThat(results.stream().filter(Creation::created).count()).isEqualTo(1);
            assertThat(results.get(0).body()).isEqualTo(results.get(1).body());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE enrollment_id=?", Integer.class, fixture.enrollmentId)).isEqualTo(1);
            System.out.println("ATTEMPT_RACE: separateConnections=2 created=1 existing=1 sameIdAndDeadline=true rows=1");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void v5SchemaMatchesEntityAndEnforcesUniqueFkNotNullAndDeadline() {
        var fixture = ready(); var result = service.start(actor("student"), fixture.exam.id()).body();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_attempts'", String.class))
                .containsExactlyInAnyOrder("id", "enrollment_id", "started_at", "deadline_at", "created_at");
        rejected("INSERT INTO exam_attempts(enrollment_id,started_at,deadline_at,created_at) SELECT enrollment_id,started_at,deadline_at,created_at FROM exam_attempts WHERE id=?", result.attemptId(), 1062);
        rejected("UPDATE exam_attempts SET enrollment_id=-1 WHERE id=?", result.attemptId(), 1452);
        rejected("UPDATE exam_attempts SET started_at=NULL WHERE id=?", result.attemptId(), 1048);
        rejected("UPDATE exam_attempts SET deadline_at=started_at WHERE id=?", result.attemptId(), 3819);
    }

    private void rejected(String sql, Long id, int code) {
        assertThatThrownBy(() -> jdbc.update(sql, id)).isInstanceOf(DataAccessException.class).rootCause()
                .isInstanceOf(java.sql.SQLException.class)
                .satisfies(cause -> assertThat(((java.sql.SQLException) cause).getErrorCode()).isEqualTo(code));
    }
    private void assertSameTiming(JsonNode first, JsonNode second) {
        for (String field : List.of("attemptId", "examId", "startedAt", "deadlineAt")) assertThat(second.get(field)).isEqualTo(first.get(field));
    }
    private Set<String> fields(JsonNode node) { var fields = new HashSet<String>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private void assertShape(JsonNode body, boolean active) {
        assertThat(fields(body)).containsExactlyInAnyOrder("attemptId", "examId", "title", "startedAt", "deadlineAt", "serverTime", "availability", "questions");
        assertThat(body.get("availability").asText()).isEqualTo(active ? "ACTIVE" : "EXPIRED");
        assertThat(body.get("questions").size()).isEqualTo(active ? 2 : 0);
        int position = 0;
        for (JsonNode question : body.get("questions")) {
            assertThat(fields(question)).containsExactlyInAnyOrder("id", "content", "position", "options");
            assertThat(question.get("position").asInt()).isGreaterThan(position); position = question.get("position").asInt();
            int optionPosition = 0;
            for (JsonNode option : question.get("options")) {
                assertThat(fields(option)).containsExactlyInAnyOrder("id", "content", "position");
                assertThat(option.get("position").asInt()).isGreaterThan(optionPosition); optionPosition = option.get("position").asInt();
            }
        }
    }
    Client login(String name) throws Exception {
        var client = new Client(); client.csrf();
        expect(client.post("/api/v1/auth/login", Map.of("email", email(name), "password", PASSWORD)), 200);
        client.csrf(); return client;
    }
    JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    void expect(HttpResponse<String> response, int status) { assertThat(response.statusCode()).isEqualTo(status); }
    void problem(HttpResponse<String> response, int status) throws Exception {
        expect(response, status); assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(response).get("status").asInt()).isEqualTo(status);
    }
    final class Client {
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String cookie, token;
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Object body) throws Exception { return send("POST", path, body); }
        void csrf() throws Exception { var response = get("/api/v1/auth/csrf"); expect(response, 200); token = json(response).get("token").asText(); }
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
            if (cookie != null) builder.header("Cookie", cookie);
            if (token != null && !method.equals("GET")) builder.header("X-CSRF-TOKEN", token);
            if (body != null) builder.header("Content-Type", "application/json");
            var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) if (header.startsWith("JSESSIONID=")) cookie = header.substring(0, header.indexOf(';'));
            return response;
        }
    }
    static class AdjustableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        void set(Instant value) { time.set(value); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return time.get(); }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary AdjustableClock attemptTestClock() { return new AdjustableClock(); }
    }
}
