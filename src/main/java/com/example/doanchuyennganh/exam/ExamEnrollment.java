package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "exam_enrollments")
public class ExamEnrollment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "exam_id", nullable = false) Long examId;
    @Column(name = "student_id", nullable = false) Long studentId;
    @Column(name = "enrolled_by", nullable = false) Long enrolledBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "enrolled_at", nullable = false) Instant enrolledAt;
}
