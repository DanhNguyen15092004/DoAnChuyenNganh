package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.DoAnChuyenNganhApplication;
import com.example.doanchuyennganh.account.security.DatabaseUserDetailsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class AttemptV6UpgradeTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");
    static final Instant NOW = BASE.plusSeconds(3600);
    @Test void actualV6DatabaseWithHistoricalAnswerAndTimeoutUpgradesAndBackfillsWithoutChangingData() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("6").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("INSERT INTO app_users(id,email,password_hash,display_name,role,enabled,created_at,updated_at) VALUES (1,'teacher.v6@example.test','unused','Teacher','TEACHER',1,?,?),(2,'student.v6@example.test','unused','Student','STUDENT',1,?,?)", ts(0),ts(0),ts(0),ts(0));
        jdbc.update("INSERT INTO exams(id,owner_id,title,duration_minutes,opens_at,closes_at,status,published_at,consent_version,consent_text,created_at,updated_at) VALUES (1,1,'V6-UPGRADE-FIXTURE',1,?,?,'PUBLISHED',?,'v1','Fixture consent',?,?)",ts(0),ts(3600),ts(0),ts(0),ts(0));
        jdbc.update("INSERT INTO exam_questions(id,exam_id,content,position,created_at,updated_at) VALUES (1,1,'Q1',1,?,?),(2,1,'Q2',2,?,?)",ts(0),ts(0),ts(0),ts(0));
        jdbc.update("INSERT INTO exam_question_options(id,question_id,content,position,is_correct) VALUES (1,1,'A',1,1),(2,1,'B',2,0),(3,2,'C',1,1),(4,2,'D',2,0)");
        jdbc.update("INSERT INTO exam_enrollments(id,exam_id,student_id,enrolled_by,enrolled_at) VALUES (1,1,2,1,?)",ts(0));
        jdbc.update("INSERT INTO exam_consents(enrollment_id,consent_version,accepted_at) VALUES (1,'v1',?)",ts(0));
        jdbc.update("INSERT INTO exam_attempts(id,enrollment_id,started_at,deadline_at,created_at,status,finalized_at,effective_ended_at) VALUES (1,1,?,?,?,'TIMED_OUT',?,?)",ts(0),ts(60),ts(0),ts(80),ts(60));
        jdbc.update("INSERT INTO exam_attempt_answers(attempt_id,question_id,selected_option_id,revision,updated_at) VALUES (1,1,1,4,?),(1,2,NULL,2,?)",ts(20),ts(30));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_attempts' AND COLUMN_NAME='graded_at'", Integer.class)).isZero();
        var oldAnswers = jdbc.queryForList("SELECT * FROM exam_attempt_answers ORDER BY id");
        try (var app = new SpringApplicationBuilder(DoAnChuyenNganhApplication.class, TimeConfig.class).profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(), "--app.attempt.timeout.enabled=true",
                        "--app.attempt.timeout.scan-delay-ms=50", "--app.attempt.timeout.initial-delay-ms=0")) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT graded_at FROM exam_attempts WHERE id=1", java.sql.Timestamp.class)).isNotNull());
            var attempt = app.getBean(ExamAttemptRepository.class).findById(1L).orElseThrow();
            assertThat(attempt.status).isEqualTo(AttemptStatus.TIMED_OUT); assertThat(attempt.startedAt).isEqualTo(BASE);
            assertThat(attempt.deadlineAt).isEqualTo(BASE.plusSeconds(60)); assertThat(attempt.effectiveEndedAt).isEqualTo(BASE.plusSeconds(60));
            assertThat(attempt.finalizedAt).isEqualTo(BASE.plusSeconds(80)); assertThat(attempt.gradedAt).isEqualTo(NOW);
            assertThat(attempt.score).isEqualByComparingTo("5.00"); assertThat(attempt.totalQuestions).isEqualTo(2);
            assertThat(attempt.answeredCount).isEqualTo(1); assertThat(attempt.correctCount).isEqualTo(1);
            var principal = app.getBean(DatabaseUserDetailsService.class).loadUserByUsername("student.v6@example.test");
            var service = app.getBean(ExamAttemptService.class); var original = service.result(principal, 1L).unwrap();
            app.getBean(AttemptTimeoutService.class).finalizeDue(1L);
            assertThat(service.submit(principal, 1L)).isEqualTo(original);
            assertThat(jdbc.queryForList("SELECT * FROM exam_attempt_answers ORDER BY id")).isEqualTo(oldAnswers);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempts", Integer.class)).isEqualTo(1);
            System.out.println("V6_UPGRADE: existingTimedOut=1 backfilled=1 oldEndTimesAndAnswerRevisionsUnchanged=true score=5.00 noBrowserRequests=true");
        }
    }
    static LocalDateTime ts(int seconds) { return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC); }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock upgradeClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
