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
    @Autowired AttemptAnswerRepository answers;
    @Autowired AttemptTimeoutService timeouts;

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
                .containsExactlyInAnyOrder("id", "enrollment_id", "started_at", "deadline_at", "created_at", "status", "finalized_at", "effective_ended_at", "total_questions", "answered_count", "correct_count", "score", "graded_at");
        rejected("INSERT INTO exam_attempts(enrollment_id,started_at,deadline_at,created_at) SELECT enrollment_id,started_at,deadline_at,created_at FROM exam_attempts WHERE id=?", result.attemptId(), 1062);
        rejected("UPDATE exam_attempts SET enrollment_id=-1 WHERE id=?", result.attemptId(), 1452);
        rejected("UPDATE exam_attempts SET started_at=NULL WHERE id=?", result.attemptId(), 1048);
        rejected("UPDATE exam_attempts SET deadline_at=started_at WHERE id=?", result.attemptId(), 3819);
    }

    record Paper(AttemptResponse attempt, Long question, Long option, Long otherOption) { }
    Paper paper() {
        var a = service.start(actor("student"), ready().exam.id()).body();
        var q = a.questions().getFirst();
        return new Paper(a, q.id(), q.options().getFirst().id(), q.options().getLast().id());
    }
    AttemptContracts.Outcome<AttemptContracts.SaveAnswerResponse> save(Paper p, Long option, long revision) {
        return service.saveAnswer(actor("student"), p.attempt.attemptId(), p.question,
                new AttemptContracts.SaveAnswerRequest(option, revision));
    }
    String answerPath(Paper p) { return getPath(p.attempt.attemptId()) + "/answers/" + p.question; }
    @Test void httpSelectChangeClearAndReloadPersistRevisionWithoutLeakingKeys() throws Exception {
        var p = paper(); var student = login("student");
        for (int i = 0; i < 3; i++) {
            Long choice = i == 0 ? p.option : i == 1 ? p.otherOption : null;
            var request = new HashMap<String, Object>(); request.put("selectedOptionId", choice); request.put("expectedRevision", i);
            var response = student.send("PUT", answerPath(p), request); expect(response, 200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var saved = json(response);
            assertThat(fields(saved)).containsExactlyInAnyOrder("questionId", "selectedOptionId", "revision", "savedAt", "serverTime");
            assertThat(saved.get("revision").asLong()).isEqualTo(i + 1);
            if (choice == null) assertThat(saved.get("selectedOptionId").isNull()).isTrue();
            else assertThat(saved.get("selectedOptionId").asLong()).isEqualTo(choice);
            var restored = json(student.get(getPath(p.attempt.attemptId()))).get("answers");
            assertThat(restored.size()).isEqualTo(1);
            assertThat(fields(restored.get(0))).containsExactlyInAnyOrder("questionId", "selectedOptionId", "revision", "savedAt");
            assertThat(restored.get(0).get("revision").asLong()).isEqualTo(i + 1);
            if (choice == null) assertThat(restored.get(0).get("selectedOptionId").isNull()).isTrue();
            else assertThat(restored.get(0).get("selectedOptionId").asLong()).isEqualTo(choice);
        }
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).singleElement()
                .satisfies(a -> { assertThat(a.selectedOptionId).isNull(); assertThat(a.revision).isEqualTo(3); });
    }
    @Test void answerAndTimeoutEnforceOwnershipRolesAndCsrfAndRejectUntrustedBodies() throws Exception {
        var p = paper(); String timeoutPath = getPath(p.attempt.attemptId()) + "/timeout";
        var request = Map.of("selectedOptionId", p.option, "expectedRevision", 0);
        var student = login("student");
        student.token = null; problem(student.send("PUT", answerPath(p), request), 403); problem(student.post(timeoutPath, null), 403);
        student.token = "wrong"; problem(student.send("PUT", answerPath(p), request), 403); problem(student.post(timeoutPath, null), 403);
        student.csrf();
        for (Object body : List.of(Map.of(), Map.of("expectedRevision", -1), Map.of("expectedRevision", 0, "deadlineAt", NOW.toString()),
                Map.of("expectedRevision", 0, "selectedOptionId", -1))) problem(student.send("PUT", answerPath(p), body), 400);
        problem(student.post(timeoutPath, Map.of()), 400);
        for (String name : List.of("other", "teacher", "admin")) {
            var client = login(name); int status = name.equals("other") ? 404 : 403;
            problem(client.send("PUT", answerPath(p), request), status); problem(client.post(timeoutPath, null), status);
            assertThatThrownBy(() -> service.saveAnswer(actor(name), p.attempt.attemptId(), p.question,
                    new AttemptContracts.SaveAnswerRequest(p.option, 0L)))
                    .isInstanceOf(name.equals("other") ? com.example.doanchuyennganh.common.exception.ResourceNotFoundException.class : AccessDeniedException.class);
        }
        var anonymous = new Client(); anonymous.csrf(); problem(anonymous.send("PUT", answerPath(p), request), 401);
        problem(anonymous.post(timeoutPath, null), 401);
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).isEmpty();
    }
    @Test void rejectsQuestionFromAnotherExamAndOptionFromAnotherQuestion() throws Exception {
        var p = paper(); var other = paper(); var student = login("student");
        problem(student.send("PUT", answerPath(p), Map.of("selectedOptionId", other.option, "expectedRevision", 0)), 400);
        var sameExamOtherQuestion = p.attempt.questions().getLast();
        problem(student.send("PUT", answerPath(p), Map.of("selectedOptionId", sameExamOtherQuestion.options().getFirst().id(), "expectedRevision", 0)), 400);
        problem(student.send("PUT", getPath(p.attempt.attemptId()) + "/answers/" + other.question, Map.of("expectedRevision", 0)), 400);
        problem(student.send("PUT", getPath(p.attempt.attemptId()) + "/answers/" + Long.MAX_VALUE, Map.of("expectedRevision", 0)), 400);
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).isEmpty();
    }
    @Test void staleRevisionReturnsCoded409AndCannotOverwriteIncludingAfterClear() throws Exception {
        var p = paper(); assertThat(save(p, p.option, 0).unwrap().revision()).isEqualTo(1);
        var response = login("student").send("PUT", answerPath(p), Map.of("selectedOptionId", p.otherOption, "expectedRevision", 0));
        problem(response, 409); assertThat(json(response).get("code").asText()).isEqualTo("ANSWER_REVISION_CONFLICT");
        assertThat(save(p, null, 1).unwrap().revision()).isEqualTo(2);
        assertThat(save(p, p.otherOption, 1).code()).isEqualTo("ANSWER_REVISION_CONFLICT");
        assertThat(service.get(actor("student"), p.attempt.attemptId()).answers()).singleElement()
                .satisfies(a -> { assertThat(a.selectedOptionId()).isNull(); assertThat(a.revision()).isEqualTo(2); });
    }
    @Test void lateHttpSaveCommitsFinalizationEvenThoughResponseIs409() throws Exception {
        var p = paper(); save(p, p.option, 0).unwrap();
        assertThat(attempts.findById(p.attempt.attemptId()).orElseThrow().status).isEqualTo(AttemptStatus.IN_PROGRESS);
        clock.set(p.attempt.deadlineAt());
        var response = login("student").send("PUT", answerPath(p), Map.of("selectedOptionId", p.otherOption, "expectedRevision", 1));
        problem(response, 409); assertThat(json(response).get("code").asText()).isEqualTo("ATTEMPT_TIMED_OUT");
        var persisted = attempts.findById(p.attempt.attemptId()).orElseThrow();
        assertThat(persisted.status).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(persisted.finalizedAt).isEqualTo(p.attempt.deadlineAt());
        assertThat(persisted.effectiveEndedAt).isEqualTo(p.attempt.deadlineAt());
        var finalResponse = service.get(actor("student"), persisted.id);
        assertThat(finalResponse.questions()).isEmpty(); assertThat(finalResponse.availability()).isEqualTo(AttemptContracts.Availability.EXPIRED);
        assertThat(finalResponse.answers()).singleElement().satisfies(a -> {
            assertThat(a.selectedOptionId()).isEqualTo(p.option); assertThat(a.revision()).isEqualTo(1);
        });
    }
    @Test void timeoutEarlyIsCoded409AndRepeatsKeepProcessingAndEffectiveTimes() throws Exception {
        var p = paper(); var student = login("student"); String path = getPath(p.attempt.attemptId()) + "/timeout";
        var early = student.post(path, null); problem(early, 409);
        assertThat(json(early).get("code").asText()).isEqualTo("TIMEOUT_NOT_DUE");
        assertThat(attempts.findById(p.attempt.attemptId()).orElseThrow().finalizedAt).isNull();
        clock.set(p.attempt.deadlineAt().plusSeconds(25)); var first = student.post(path, null); expect(first, 200);
        assertThat(Instant.parse(json(first).get("effectiveEndedAt").asText())).isEqualTo(p.attempt.deadlineAt());
        assertThat(Instant.parse(json(first).get("finalizedAt").asText())).isEqualTo(clock.instant());
        clock.set(clock.instant().plusSeconds(30)); var second = student.post(path, null); expect(second, 200);
        for (String key : List.of("status", "deadlineAt", "effectiveEndedAt", "finalizedAt")) assertThat(json(second).get(key)).isEqualTo(json(first).get(key));
    }
    @Test void concurrentSameRevisionAcceptsExactlyOneUsingTwoDatabaseConnections() throws Exception {
        var p = paper(); var student = actor("student");
        var results = race(() -> service.saveAnswer(student, p.attempt.attemptId(), p.question, new AttemptContracts.SaveAnswerRequest(p.option, 0L)),
                () -> service.saveAnswer(student, p.attempt.attemptId(), p.question, new AttemptContracts.SaveAnswerRequest(p.otherOption, 0L)));
        assertThat(results.stream().filter(r -> r.code() == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> "ANSWER_REVISION_CONFLICT".equals(r.code())).count()).isEqualTo(1);
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).singleElement().satisfies(a -> assertThat(a.revision).isEqualTo(1));
        System.out.println("ANSWER_RACE: connections=2 accepted=1 revisionConflict=1 revision=1");
    }
    @Test void saveAndTimeoutRaceAtDeadlineCannotChangeAcceptedAnswers() throws Exception {
        var p = paper(); var student = actor("student"); save(p, p.option, 0).unwrap(); clock.set(p.attempt.deadlineAt());
        var results = race(() -> service.saveAnswer(student, p.attempt.attemptId(), p.question,
                new AttemptContracts.SaveAnswerRequest(p.otherOption, 1L)).code(),
                () -> service.timeout(student, p.attempt.attemptId()).unwrap().status().name());
        assertThat(results).containsExactlyInAnyOrder("ATTEMPT_TIMED_OUT", "TIMED_OUT");
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).singleElement()
                .satisfies(a -> { assertThat(a.selectedOptionId).isEqualTo(p.option); assertThat(a.revision).isEqualTo(1); });
        assertThat(attempts.findById(p.attempt.attemptId()).orElseThrow().finalizedAt).isEqualTo(p.attempt.deadlineAt());
        System.out.println("SAVE_TIMEOUT_RACE: connections=2 lateSaveRejected=true acceptedAnswerUnchanged=true");
    }
    @Test void concurrentTimeoutsAreIdempotentAndKeepAnswers() throws Exception {
        var p = paper(); var student = actor("student"); save(p, p.option, 0).unwrap(); clock.set(p.attempt.deadlineAt().plusSeconds(5));
        var results = race(() -> service.timeout(student, p.attempt.attemptId()).unwrap(), () -> service.timeout(student, p.attempt.attemptId()).unwrap());
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        clock.set(clock.instant().plusSeconds(60)); timeouts.finalizeDue(p.attempt.attemptId());
        assertThat(attempts.findById(p.attempt.attemptId()).orElseThrow().finalizedAt).isEqualTo(results.getFirst().finalizedAt());
        assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).hasSize(1);
    }
    @Test void requestWaitingOnRealLockRechecksClockAfterItAcquiresLock() throws Exception {
        var p = paper(); var student = actor("student");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var starting = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2); var connectionIds = new ConcurrentLinkedQueue<Long>();
        var waitingConnection = new AtomicReference<Long>();
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                connectionIds.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                attempts.lockById(p.attempt.attemptId()).orElseThrow(); locked.countDown(); await(release); return true;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var saver = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); connectionIds.add(connection);
                waitingConnection.set(connection); starting.countDown();
                return service.saveAnswer(student, p.attempt.attemptId(), p.question, new AttemptContracts.SaveAnswerRequest(p.option, 0L));
            }));
            assertThat(starting.await(10, TimeUnit.SECONDS)).isTrue();
            // Observe an actual InnoDB lock wait, not just a worker-start signal.
            try (var observer = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                 var query = observer.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON w.REQUESTING_THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_ID=?")) {
                query.setLong(1, waitingConnection.get());
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    try (var result = query.executeQuery()) { result.next(); assertThat(result.getInt(1)).isGreaterThan(0); }
                });
            }
            clock.set(p.attempt.deadlineAt()); release.countDown();
            assertThat(holder.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(saver.get(15, TimeUnit.SECONDS).code()).isEqualTo("ATTEMPT_TIMED_OUT");
            assertThat(new HashSet<>(connectionIds)).hasSize(2);
            assertThat(answers.findByAttemptIdOrderByQuestionIdAsc(p.attempt.attemptId())).isEmpty();
            System.out.println("WAITING_SAVE: observedInnoDbLockWait=true connections=2 clockCheckedAfterLock=true lateSaveRejected=true");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void v6EnforcesAnswerUniqueForeignKeysRevisionAndLifecycleConstraints() {
        var p = paper(); save(p, p.option, 0).unwrap();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6' AND success=1", Integer.class)).isEqualTo(1);
        Long id = answers.findByAttemptIdAndQuestionId(p.attempt.attemptId(), p.question).orElseThrow().id;
        rejected("INSERT INTO exam_attempt_answers(attempt_id,question_id,selected_option_id,revision,updated_at) SELECT attempt_id,question_id,selected_option_id,revision,updated_at FROM exam_attempt_answers WHERE id=?", id, 1062);
        rejected("UPDATE exam_attempt_answers SET selected_option_id=-1 WHERE id=?", id, 1452);
        rejected("UPDATE exam_attempt_answers SET question_id=-1 WHERE id=?", id, 1452);
        rejected("UPDATE exam_attempt_answers SET attempt_id=-1 WHERE id=?", id, 1452);
        rejected("UPDATE exam_attempt_answers SET revision=0 WHERE id=?", id, 3819);
        rejected("UPDATE exam_attempts SET status='TIMED_OUT' WHERE id=?", p.attempt.attemptId(), 3819);
        rejected("UPDATE exam_attempts SET status='TIMED_OUT', finalized_at=deadline_at, effective_ended_at=NULL WHERE id=?", p.attempt.attemptId(), 3819);
        rejected("UPDATE exam_attempts SET status='invalid' WHERE id=?", p.attempt.attemptId(), 3819);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_attempts' AND INDEX_NAME='ix_attempts_due'", Integer.class)).isEqualTo(3);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        var connections = new ConcurrentLinkedQueue<Long>(); var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<Callable<T>, Callable<T>> worker = action -> () -> new TransactionTemplate(transactions).execute(status -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); ready.countDown(); await(go);
                try { return action.call(); } catch (Exception e) { throw new IllegalStateException(e); }
            });
            var a = pool.submit(worker.apply(first)); var b = pool.submit(worker.apply(second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(new HashSet<>(connections)).hasSize(2); return results;
        } finally { go.countDown(); pool.shutdownNow(); }
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
        assertThat(fields(body)).containsExactlyInAnyOrder("attemptId", "examId", "title", "startedAt", "deadlineAt", "serverTime", "availability", "questions", "status", "finalizedAt", "effectiveEndedAt", "answers");
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
