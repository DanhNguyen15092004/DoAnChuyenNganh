package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.math.BigDecimal;

/** Immutable timing; ownership and exam come only from the enrollment FK. */
@Entity @Table(name = "exam_attempts")
public class ExamAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "enrollment_id", nullable = false, unique = true, updatable = false) Long enrollmentId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "started_at", nullable = false, updatable = false) Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "deadline_at", nullable = false, updatable = false) Instant deadlineAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20)
    AttemptStatus status = AttemptStatus.IN_PROGRESS;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "finalized_at") Instant finalizedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "effective_ended_at") Instant effectiveEndedAt;
    @Column(name = "total_questions") Integer totalQuestions;
    @Column(name = "answered_count") Integer answeredCount;
    @Column(name = "correct_count") Integer correctCount;
    @Column(precision = 4, scale = 2) BigDecimal score;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "graded_at") Instant gradedAt;
}
