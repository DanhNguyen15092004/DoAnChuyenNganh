package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.example.doanchuyennganh.account.UserRepository;
import com.example.doanchuyennganh.account.security.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.example.doanchuyennganh.exam.AttemptContracts.*;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Testcontainers
@Import(ExamAttemptIntegrationTest.TimeConfiguration.class)
class AttemptGradingIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    static final Instant NOW = ExamAttemptIntegrationTest.NOW;
    static final String PASSWORD = "Grading-Fixture-Only";
    @Autowired ExamService exams;
    @Autowired ExamAttemptService service;
    @Autowired ManagedAttemptService managers;
    @Autowired ExamAttemptRepository attempts;
    @Autowired AttemptAnswerRepository answers;
    @Autowired AttemptTimeoutService timeouts;
    @Autowired UserRepository users;
    @Autowired DatabaseUserDetailsService details;
    @Autowired PasswordEncoder encoder;
    @Autowired ExamAttemptIntegrationTest.AdjustableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean AttemptGrader grader;
    @LocalServerPort int port;
    @BeforeEach void fixtures() {
        clock.set(NOW);
        for (String name : List.of("student", "other", "third", "teacher", "otherteacher", "admin")) {
            Role role = name.contains("teacher") ? Role.TEACHER : name.equals("admin") ? Role.ADMIN : Role.STUDENT;
            if (users.findByEmail(email(name)).isEmpty()) users.saveAndFlush(new User(email(name), encoder.encode(PASSWORD), name, role, true));
        }
    }
    String email(String name) { return name + ".grading@example.test"; }
    AccountPrincipal actor(String name) { return details.loadUserByUsername(email(name)); }
    record Paper(TeacherExamResponse exam, AttemptResponse attempt) { }
    TeacherExamResponse published(int count, String owner) {
        var teacher = actor(owner);
        var exam = exams.create(teacher, new MetadataRequest("GRADING-TEST-" + UUID.randomUUID(), null, 1, NOW, NOW.plusSeconds(3600)));
        var questions = new ArrayList<QuestionRequest>();
        for (int i = 1; i <= count; i++) questions.add(new QuestionRequest("Question " + i, i,
                List.of(new OptionRequest("A", 1, true), new OptionRequest("B", 2, false))));
        exams.replaceQuestions(teacher, exam.id(), new QuestionsRequest(questions));
        return exams.publish(teacher, exam.id());
    }
    Paper paper(int count) { return paper(count, "teacher"); }
    Paper paper(int count, String owner) {
        var exam = published(count, owner); var student = actor("student");
        exams.enroll(actor(owner), exam.id(), new EnrollmentRequest(student.getId()));
        exams.acceptConsent(student, exam.id(), new ConsentRequest(true, exam.consentVersion()));
        return new Paper(exam, service.start(student, exam.id()).body());
    }
    void select(Paper p, int index, boolean correct) {
        var q = p.attempt.questions().get(index);
        service.saveAnswer(actor("student"), p.attempt.attemptId(), q.id(),
                new SaveAnswerRequest(q.options().get(correct ? 0 : 1).id(), 0L)).unwrap();
    }
    String base(Paper p) { return "/api/v1/student/attempts/" + p.attempt.attemptId(); }
    String managed(Paper p) { return "/api/v1/teacher/exams/" + p.exam.id() + "/attempts"; }
    @Test void gradesAllCorrectAllWrongBlankMixedAndRoundingUsingStoredAnswers() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var p = paper(3);
            if (scenario == 0 || scenario == 1) for (int i = 0; i < 3; i++) select(p, i, scenario == 0);
            if (scenario == 2) {
                select(p, 0, true); var q = p.attempt.questions().getFirst();
                service.saveAnswer(actor("student"), p.attempt.attemptId(), q.id(), new SaveAnswerRequest(null, 1L)).unwrap();
            }
            if (scenario == 3) { select(p, 0, true); select(p, 1, true); select(p, 2, false); }
            var r = service.submit(actor("student"), p.attempt.attemptId());
            assertThat(r.totalQuestions()).isEqualTo(3);
            assertThat(r.answeredCount()).isEqualTo(scenario == 2 ? 0 : 3);
            assertThat(r.correctCount()).isEqualTo(scenario == 0 ? 3 : scenario == 3 ? 2 : 0);
            assertThat(r.score()).isEqualByComparingTo(scenario == 0 ? "10.00" : scenario == 3 ? "6.67" : "0.00");
            assertThat(r.score().scale()).isEqualTo(2);
        }
        var p = paper(6); select(p, 0, true);
        var r = service.submit(actor("student"), p.attempt.attemptId());
        assertThat(r.score()).isEqualByComparingTo("1.67"); assertThat(r.unansweredCount()).isEqualTo(5);
    }
    @Test void httpSubmitBeforeDeadlineIsIdempotentBlocksEditsAndPaperWithoutLeakingKeys() throws Exception {
        var p = paper(3); select(p, 0, true); select(p, 1, false); var client = login("student");
        var notReady = client.get(base(p) + "/result"); problem(notReady, 409);
        assertThat(json(notReady).get("code").asText()).isEqualTo("RESULT_NOT_READY");
        problem(client.post(base(p) + "/submit", Map.of()), 400);
        problem(client.post(base(p) + "/submit", Map.of("score", 10, "answers", List.of())), 400);
        var response = client.post(base(p) + "/submit", null); expect(response, 200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var original = json(response); resultShape(original);
        assertThat(original.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(original.get("score").decimalValue()).isEqualByComparingTo("3.33");
        assertThat(original.get("unansweredCount").asInt()).isEqualTo(1);
        assertThat(Instant.parse(original.get("effectiveEndedAt").asText())).isEqualTo(NOW);
        clock.set(NOW.plusSeconds(10));
        var read = json(client.get(base(p)));
        assertThat(read.get("availability").asText()).isEqualTo("ACTIVE"); // Time availability is independent of final status.
        assertThat(read.get("status").asText()).isEqualTo("SUBMITTED"); assertThat(read.get("questions").isEmpty()).isTrue();
        var q = p.attempt.questions().getFirst();
        var lateWrite = client.send("PUT", base(p) + "/answers/" + q.id(), Map.of("selectedOptionId", q.options().getLast().id(), "expectedRevision", 1));
        problem(lateWrite, 409); assertThat(json(lateWrite).get("code").asText()).isEqualTo("ATTEMPT_FINISHED");
        expect(client.post(base(p) + "/timeout", null), 200);
        for (Instant time : List.of(NOW.plusSeconds(20), p.attempt.deadlineAt().plusSeconds(10))) {
            clock.set(time);
            assertThat(json(client.post(base(p) + "/submit", null))).isEqualTo(original);
            assertThat(json(client.get(base(p) + "/result"))).isEqualTo(original);
        }
        verify(grader, times(1)).grade(eq(p.attempt.attemptId()), eq(p.exam.id()));
    }
    @Test void submitExactlyAtAndAfterDeadlineIsTimedOut() {
        for (int seconds : List.of(0, 2)) {
            clock.set(NOW); var p = paper(1); select(p, 0, true); clock.set(p.attempt.deadlineAt().plusSeconds(seconds));
            var r = service.submit(actor("student"), p.attempt.attemptId());
            assertThat(r.status()).isEqualTo(AttemptStatus.TIMED_OUT); assertThat(r.effectiveEndedAt()).isEqualTo(p.attempt.deadlineAt());
            assertThat(r.score()).isEqualByComparingTo("10.00");
            assertThat(attempts.findById(r.attemptId()).orElseThrow().finalizedAt).isEqualTo(clock.instant());
        }
    }
    @Test void timeoutLazyResultAndSchedulerShareGradingAndDoNotRegrade() {
        for (int mode = 0; mode < 3; mode++) {
            clock.set(NOW); var p = paper(3); select(p, 0, true); select(p, 1, false); clock.set(p.attempt.deadlineAt());
            if (mode == 0) service.timeout(actor("student"), p.attempt.attemptId()).unwrap();
            if (mode == 1) service.result(actor("student"), p.attempt.attemptId()).unwrap();
            if (mode == 2) timeouts.finalizeDue(p.attempt.attemptId());
            var r = service.result(actor("student"), p.attempt.attemptId()).unwrap();
            assertThat(r.status()).isEqualTo(AttemptStatus.TIMED_OUT); assertThat(r.score()).isEqualByComparingTo("3.33");
            clock.set(clock.instant().plusSeconds(10)); timeouts.finalizeDue(r.attemptId());
            assertThat(service.submit(actor("student"), r.attemptId())).isEqualTo(r);
            verify(grader, times(1)).grade(eq(r.attemptId()), eq(p.exam.id()));
        }
    }
    @Test void gradingFailureRollsBackFinalStateTimestampsAndPartialResultForSubmitAndTimeout() {
        var p = paper(1); select(p, 0, true);
        doThrow(new IllegalStateException("Injected grading failure")).when(grader).grade(eq(p.attempt.attemptId()), anyLong());
        assertThatThrownBy(() -> service.submit(actor("student"), p.attempt.attemptId())).isInstanceOf(IllegalStateException.class);
        assertUnfinalized(p);
        clock.set(p.attempt.deadlineAt());
        assertThatThrownBy(() -> timeouts.finalizeDue(p.attempt.attemptId())).isInstanceOf(IllegalStateException.class);
        assertUnfinalized(p);
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).hasSize(1);
        reset(grader);
        assertThat(service.submit(actor("student"), p.attempt.attemptId()).score()).isEqualByComparingTo("10.00");
    }
    void assertUnfinalized(Paper p) {
        var a = attempts.findById(p.attempt.attemptId()).orElseThrow();
        assertThat(a.status).isEqualTo(AttemptStatus.IN_PROGRESS); assertThat(a.finalizedAt).isNull(); assertThat(a.effectiveEndedAt).isNull();
        assertThat(a.gradedAt).isNull(); assertThat(a.score).isNull(); assertThat(a.totalQuestions).isNull();
        assertThat(a.answeredCount).isNull(); assertThat(a.correctCount).isNull();
    }
    @Test void saveAndSubmitRaceUsesSeparateConnectionsAndGradesExactlyTheAcceptedAnswer() throws Exception {
        var p = paper(1); var student = actor("student"); var q = p.attempt.questions().getFirst();
        var values = race(List.of(
                () -> { var saved = service.saveAnswer(student, p.attempt.attemptId(), q.id(), new SaveAnswerRequest(q.options().getFirst().id(), 0L)); return saved.code() == null ? "ACCEPTED" : saved.code(); },
                () -> service.submit(student, p.attempt.attemptId()).status().name()));
        assertThat(values).contains("SUBMITTED");
        boolean accepted = values.contains("ACCEPTED");
        assertThat(values).contains(accepted ? "ACCEPTED" : "ATTEMPT_FINISHED");
        var result = service.result(student, p.attempt.attemptId()).unwrap();
        assertThat(result.correctCount()).isEqualTo(accepted ? 1 : 0);
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(result.attemptId())).hasSize(accepted ? 1 : 0);
        verify(grader, times(1)).grade(eq(result.attemptId()), anyLong());
        System.out.println("SAVE_SUBMIT_RACE: connections=2 acceptedAnswerIncluded=true finalResultStable=true");
    }
    @Test void submitTimeoutAndSchedulerRaceFinalizeAndGradeExactlyOnce() throws Exception {
        var p = paper(3); select(p, 0, true); var student = actor("student"); clock.set(p.attempt.deadlineAt());
        var values = race(List.of(() -> service.submit(student, p.attempt.attemptId()).status().name(),
                () -> service.timeout(student, p.attempt.attemptId()).unwrap().status().name(),
                () -> { timeouts.finalizeDue(p.attempt.attemptId()); return "SCHEDULED"; }));
        assertThat(values).containsExactlyInAnyOrder("TIMED_OUT", "TIMED_OUT", "SCHEDULED");
        var result = service.result(student, p.attempt.attemptId()).unwrap();
        assertThat(result.score()).isEqualByComparingTo("3.33"); clock.set(clock.instant().plusSeconds(60));
        assertThat(service.submit(student, result.attemptId())).isEqualTo(result);
        verify(grader, times(1)).grade(eq(result.attemptId()), anyLong());
        System.out.println("FINALIZE_RACE: connections=3 submitTimeoutScheduler=true gradeCalls=1 sameResultAndTimestamps=true");
    }
    @Test void schedulerBackfillsHistoricalTimedOutWithoutChangingEndTimesAndIsIdempotent() {
        var p = paper(3); select(p, 0, true); var deadline = p.attempt.deadlineAt();
        jdbc.update("UPDATE exam_attempts SET status='TIMED_OUT', finalized_at=TIMESTAMPADD(SECOND,2,deadline_at), effective_ended_at=deadline_at WHERE id=?",
                p.attempt.attemptId());
        var before = managers.list(actor("teacher"), p.exam.id(), 0, 20).content().getFirst();
        assertThat(before.score()).isNull(); assertThat(before.resultReady()).isFalse();
        clock.set(deadline.plusSeconds(100));
        var scheduler = new AttemptTimeoutScheduler(attempts, timeouts, clock, new AttemptTimeoutConfiguration.Policy(1, 50, 0));
        scheduler.sweep(); scheduler.sweep();
        var r = service.result(actor("student"), p.attempt.attemptId()).unwrap();
        assertThat(r.score()).isEqualByComparingTo("3.33");
        assertThat(r.effectiveEndedAt()).isEqualTo(deadline);
        assertThat(attempts.findById(r.attemptId()).orElseThrow().finalizedAt).isEqualTo(deadline.plusSeconds(2));
        verify(grader, times(1)).grade(eq(r.attemptId()), anyLong());
    }
    @Test void realHttpOwnershipRolesCsrfManagerScopeAndExactResultShape() throws Exception {
        var p = paper(1); service.submit(actor("student"), p.attempt.attemptId());
        Client own = login("student"), other = login("other"), teacher = login("teacher"), outsider = login("otherteacher"), admin = login("admin");
        problem(other.get(base(p) + "/result"), 404); problem(other.post(base(p) + "/submit", null), 404);
        problem(teacher.get(base(p) + "/result"), 403); problem(teacher.post(base(p) + "/submit", null), 403);
        problem(own.get(managed(p)), 403); problem(outsider.get(managed(p)), 404);
        problem(outsider.get(managed(p) + "/" + p.attempt.attemptId() + "/result"), 404);
        for (Client client : List.of(teacher, admin)) {
            var response = client.get(managed(p) + "/" + p.attempt.attemptId() + "/result"); expect(response, 200);
            var body = json(response); assertThat(fields(body)).containsExactlyInAnyOrder("student", "result");
            assertThat(fields(body.get("student"))).containsExactlyInAnyOrder("id", "email", "displayName"); resultShape(body.get("result"));
        }
        var otherExam = published(1, "otherteacher");
        problem(admin.get("/api/v1/teacher/exams/" + otherExam.id() + "/attempts/" + p.attempt.attemptId() + "/result"), 404);
        var empty = json(outsider.get("/api/v1/teacher/exams/" + otherExam.id() + "/attempts")); assertThat(empty.get("totalElements").asInt()).isZero();
        problem(teacher.get(managed(p) + "?size=101"), 400); problem(teacher.get(managed(p) + "?page=-1"), 400);
        var anonymous = new Client(); problem(anonymous.get(base(p) + "/result"), 401); problem(anonymous.get(managed(p)), 401);
        own.token = null; problem(own.post(base(p) + "/submit", null), 403);
        own.token = "wrong"; problem(own.post(base(p) + "/submit", null), 403);
    }
    @Test void managerPaginationKeepsUnstartedEnrollmentsOutAndUsesNullForUnfinishedScore() throws Exception {
        var p = paper(1); service.submit(actor("student"), p.attempt.attemptId());
        for (String name : List.of("other", "third")) exams.enroll(actor("teacher"), p.exam.id(), new EnrollmentRequest(actor(name).getId()));
        exams.acceptConsent(actor("other"), p.exam.id(), new ConsentRequest(true, p.exam.consentVersion()));
        var active = service.start(actor("other"), p.exam.id()).body(); var client = login("teacher");
        var page0 = json(client.get(managed(p) + "?page=0&size=1"));
        assertThat(page0.get("totalElements").asInt()).isEqualTo(2); assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        var row = page0.get("content").get(0); assertThat(row.get("attemptId").asLong()).isEqualTo(active.attemptId());
        assertThat(row.get("score").isNull()).isTrue(); assertThat(row.get("resultReady").asBoolean()).isFalse();
        var page1 = json(client.get(managed(p) + "?page=1&size=1")); assertThat(page1.get("content").get(0).get("score").decimalValue()).isEqualByComparingTo("0.00");
        var pending = client.get(managed(p) + "/" + active.attemptId() + "/result"); problem(pending, 409);
        assertThat(json(pending).get("code").asText()).isEqualTo("RESULT_NOT_READY");
    }
    private List<String> race(List<Callable<String>> tasks) throws Exception {
        var ready = new CountDownLatch(tasks.size()); var go = new CountDownLatch(1);
        var connections = new ConcurrentLinkedQueue<Long>(); var pool = Executors.newFixedThreadPool(tasks.size());
        try {
            var futures = new ArrayList<Future<String>>();
            for (var task : tasks) futures.add(pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); ready.countDown();
                try { if (!go.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Race barrier timeout"); return task.call(); }
                catch (Exception e) { throw new IllegalStateException(e); }
            })));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            var values = new ArrayList<String>(); for (var f : futures) values.add(f.get(25, TimeUnit.SECONDS));
            assertThat(new HashSet<>(connections)).hasSize(tasks.size()); return values;
        } finally { go.countDown(); pool.shutdownNow(); }
    }
    Set<String> fields(JsonNode node) { var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    void resultShape(JsonNode node) { assertThat(fields(node)).containsExactlyInAnyOrder("attemptId", "examId", "title", "status", "startedAt", "deadlineAt", "effectiveEndedAt", "totalQuestions", "answeredCount", "unansweredCount", "correctCount", "score", "gradedAt"); }
    Client login(String name) throws Exception {
        var c = new Client(); c.csrf(); expect(c.post("/api/v1/auth/login", Map.of("email", email(name), "password", PASSWORD)), 200); c.csrf(); return c;
    }
    JsonNode json(HttpResponse<String> r) throws Exception { return mapper.readTree(r.body()); }
    void expect(HttpResponse<String> r, int status) { assertThat(r.statusCode()).isEqualTo(status); }
    void problem(HttpResponse<String> r, int status) throws Exception { expect(r, status); assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json"); assertThat(json(r).get("status").asInt()).isEqualTo(status); }
    final class Client {
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String cookie, token;
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Object body) throws Exception { return send("POST", path, body); }
        void csrf() throws Exception { var r = get("/api/v1/auth/csrf"); expect(r, 200); token = json(r).get("token").asText(); }
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
            if (cookie != null) request.header("Cookie", cookie);
            if (token != null && !method.equals("GET")) request.header("X-CSRF-TOKEN", token);
            if (body != null) request.header("Content-Type", "application/json");
            var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) if (header.startsWith("JSESSIONID=")) cookie = header.substring(0, header.indexOf(';'));
            return response;
        }
    }
}
