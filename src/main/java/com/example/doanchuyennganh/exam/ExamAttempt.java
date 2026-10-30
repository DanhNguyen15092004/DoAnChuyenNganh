package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

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
}
