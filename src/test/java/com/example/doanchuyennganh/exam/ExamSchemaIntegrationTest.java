package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Testcontainers
class ExamSchemaIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired ExamRepository exams;
    @Autowired ExamQuestionRepository questions;
    @Autowired QuestionOptionRepository options;
    @Autowired ExamEnrollmentRepository enrollments;
    @Autowired ExamConsentRepository consents;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test void flywayAndJpaValidateAllFiveTablesAndConstraints() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=1", Integer.class)).isEqualTo(1);
        Long owner = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "fixture", "Teacher", Role.TEACHER, true)).getId();
        var e = new Exam(); e.ownerId=owner; e.title="Schema"; e.durationMinutes=1;
        e.opensAt=Instant.parse("2030-01-01T00:00:00Z"); e.closesAt=e.opensAt.plusSeconds(3600);
        e.status=ExamStatus.DRAFT; e.consentVersion="v1"; e.consentText="Fixture consent";
        e.createdAt=e.opensAt; e.updatedAt=e.opensAt; exams.saveAndFlush(e);
        var q = new ExamQuestion(); q.examId=e.id; q.content="Q"; q.position=1; q.createdAt=e.createdAt; q.updatedAt=e.createdAt;
        questions.saveAndFlush(q);
        var o = new QuestionOption(); o.questionId=q.id; o.content="A"; o.position=1; o.correct=true; options.saveAndFlush(o);
        var n = new ExamEnrollment(); n.examId=e.id; n.studentId=owner; n.enrolledBy=owner; n.enrolledAt=e.createdAt; enrollments.saveAndFlush(n);
        var c = new ExamConsent(); c.enrollmentId=n.id; c.consentVersion="v1"; c.acceptedAt=e.createdAt; consents.saveAndFlush(c);
        rejected("UPDATE exams SET duration_minutes=0 WHERE id=?", e.id,3819);
        rejected("UPDATE exams SET closes_at=opens_at WHERE id=?", e.id,3819);
        rejected("UPDATE exams SET duration_minutes=301 WHERE id=?", e.id,3819);
        rejected("UPDATE exams SET status='published' WHERE id=?", e.id,3819);
        rejected("UPDATE exams SET owner_id=-1 WHERE id=?", e.id,1452);
        rejected("UPDATE exams SET title=NULL WHERE id=?", e.id,1048);
        rejected("UPDATE exam_questions SET position=0 WHERE id=?", q.id,3819);
        rejected("UPDATE exam_questions SET exam_id=-1 WHERE id=?", q.id,1452);
        rejected("INSERT INTO exam_questions(exam_id,content,position,created_at,updated_at) SELECT exam_id,content,position,created_at,updated_at FROM exam_questions WHERE id=?", q.id,1062);
        rejected("INSERT INTO exam_question_options(question_id,content,position,is_correct) SELECT question_id,content,position,is_correct FROM exam_question_options WHERE id=?", o.id,1062);
        rejected("UPDATE exam_question_options SET is_correct=2 WHERE id=?", o.id,3819);
        rejected("UPDATE exam_question_options SET question_id=-1 WHERE id=?", o.id,1452);
        rejected("INSERT INTO exam_enrollments(exam_id,student_id,enrolled_by,enrolled_at) SELECT exam_id,student_id,enrolled_by,enrolled_at FROM exam_enrollments WHERE id=?", n.id,1062);
        rejected("UPDATE exam_enrollments SET student_id=-1 WHERE id=?", n.id,1452);
        rejected("UPDATE exam_enrollments SET enrolled_by=-1 WHERE id=?", n.id,1452);
        rejected("INSERT INTO exam_consents(enrollment_id,consent_version,accepted_at) SELECT enrollment_id,consent_version,accepted_at FROM exam_consents WHERE id=?", c.id,1062);
        rejected("UPDATE exam_consents SET enrollment_id=-1 WHERE id=?", c.id,1452);
    }
    private void rejected(String sql, Long id, int code) {
        assertThatThrownBy(() -> jdbc.update(sql,id)).isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .satisfies(cause -> assertThat(((java.sql.SQLException)cause).getErrorCode()).isEqualTo(code));
    }
}
