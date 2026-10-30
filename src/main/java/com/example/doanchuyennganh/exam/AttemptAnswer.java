package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "exam_attempt_answers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"attempt_id", "question_id"}))
public class AttemptAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "attempt_id", nullable = false, updatable = false) Long attemptId;
    @Column(name = "question_id", nullable = false, updatable = false) Long questionId;
    @Column(name = "selected_option_id") Long selectedOptionId;
    // Explicit client revision, serialized by the parent attempt lock (not JPA @Version).
    @Column(nullable = false) long revision;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
