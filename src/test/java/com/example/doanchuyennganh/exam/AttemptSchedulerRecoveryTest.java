package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.DoAnChuyenNganhApplication;
import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.example.doanchuyennganh.account.UserRepository;
import com.example.doanchuyennganh.account.security.DatabaseUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.doanchuyennganh.exam.ExamContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class AttemptSchedulerRecoveryTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    static final Instant NOW = Instant.parse("2031-02-01T00:00:00Z");
    static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    ConfigurableApplicationContext boot(boolean enabled, int initialDelay) {
        return new SpringApplicationBuilder(DoAnChuyenNganhApplication.class, TimeConfig.class)
                .web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--app.attempt.timeout.enabled=" + enabled, "--app.attempt.timeout.batch-size=1",
                        "--app.attempt.timeout.scan-delay-ms=50", "--app.attempt.timeout.initial-delay-ms=" + initialDelay);
    }
    @Test void boundedBatchesAndRealSchedulerRecoverDurableAttemptsAcrossApplicationRestartsWithoutBrowser() {
        TIME.set(NOW);
        var ids = new ArrayList<Long>(); Long futureId; Long firstQuestion; Long firstOption;
        try (var context = boot(false, 300000)) {
            var users = context.getBean(UserRepository.class);
            users.saveAndFlush(new User("teacher.scheduler@example.test", "unused-fixture-hash", "Teacher", Role.TEACHER, true));
            users.saveAndFlush(new User("student.scheduler@example.test", "unused-fixture-hash", "Student", Role.STUDENT, true));
            var details = context.getBean(DatabaseUserDetailsService.class);
            var teacher = details.loadUserByUsername("teacher.scheduler@example.test");
            var student = details.loadUserByUsername("student.scheduler@example.test");
            var exams = context.getBean(ExamService.class); var service = context.getBean(ExamAttemptService.class);
            AttemptContracts.AttemptResponse first = null;
            for (int i = 0; i < 5; i++) {
                var exam = exams.create(teacher, new MetadataRequest("SCHEDULER-TEST-" + UUID.randomUUID(), null,
                        i == 4 ? 2 : 1, NOW, NOW.plusSeconds(3600)));
                exams.replaceQuestions(teacher, exam.id(), new QuestionsRequest(List.of(new QuestionRequest("Question", 1,
                        List.of(new OptionRequest("A", 1, true), new OptionRequest("B", 2, false))))));
                exam = exams.publish(teacher, exam.id()); exams.enroll(teacher, exam.id(), new EnrollmentRequest(student.getId()));
                exams.acceptConsent(student, exam.id(), new ConsentRequest(true, exam.consentVersion()));
                var attempt = service.start(student, exam.id()).body(); ids.add(attempt.attemptId());
                if (i == 0) first = attempt;
            }
            futureId = ids.removeLast(); firstQuestion = first.questions().getFirst().id(); firstOption = first.questions().getFirst().options().getFirst().id();
            service.saveAnswer(student, first.attemptId(), firstQuestion, new AttemptContracts.SaveAnswerRequest(firstOption, 0L)).unwrap();
            context.getBean(AttemptTimeoutService.class).finalizeDue(first.attemptId());
            assertThat(context.getBean(ExamAttemptRepository.class).findById(first.attemptId()).orElseThrow().status).isEqualTo(AttemptStatus.IN_PROGRESS);
        } // Whole application stops. The database/container is deliberately retained.
        TIME.set(NOW.plusSeconds(65));
        try (var context = boot(true, 300000)) {
            var jdbc = context.getBean(JdbcTemplate.class); var scheduler = context.getBean(AttemptTimeoutScheduler.class);
            assertThat(count(jdbc, "TIMED_OUT")).isZero();
            scheduler.sweep(); assertThat(count(jdbc, "TIMED_OUT")).isEqualTo(1);
            scheduler.sweep(); assertThat(count(jdbc, "TIMED_OUT")).isEqualTo(2);
        }
        try (var context = boot(true, 0)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            // Only real @Scheduled invocations now; no GET/save/timeout API drives finalization.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(jdbc, "TIMED_OUT")).isEqualTo(4));
            var attempts = context.getBean(ExamAttemptRepository.class);
            for (Long id : ids) {
                var a = attempts.findById(id).orElseThrow();
                assertThat(a.deadlineAt).isEqualTo(NOW.plusSeconds(60));
                assertThat(a.finalizedAt).isEqualTo(TIME.get()); assertThat(a.effectiveEndedAt).isEqualTo(a.deadlineAt);
                assertThat(a.gradedAt).isEqualTo(TIME.get()); assertThat(a.totalQuestions).isEqualTo(1);
                assertThat(a.score).isEqualByComparingTo(id.equals(ids.getFirst()) ? "10.00" : "0.00");
            }
            assertThat(attempts.findById(futureId).orElseThrow().status).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(context.getBean(AttemptAnswerRepository.class).findByAttemptIdAndQuestionId(ids.getFirst(), firstQuestion))
                    .hasValueSatisfying(a -> { assertThat(a.selectedOptionId).isEqualTo(firstOption); assertThat(a.revision).isEqualTo(1); });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempts", Integer.class)).isEqualTo(5);
            System.out.println("SCHEDULER_RECOVERY: appContexts=3 batchSize=1 manualBatches=2 scheduledRemaining=2 timedOut=4 futureUnchanged=1 persistedAnswerRetained=true browserRequests=0");
        }
    }
    long count(JdbcTemplate jdbc, String status) { return jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE status=?", Long.class, status); }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock recoveryClock() {
            return new Clock() {
                public Instant instant() { return TIME.get(); }
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            };
        }
    }
}
