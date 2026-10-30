package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "exam_questions")
public class ExamQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "exam_id", nullable = false) Long examId;
    @Column(nullable = false, length = 5000) String content;
    @Column(nullable = false) int position;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false) Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
