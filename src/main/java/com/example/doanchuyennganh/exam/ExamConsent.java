package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "exam_consents")
public class ExamConsent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "enrollment_id", nullable = false) Long enrollmentId;
    @Column(name = "consent_version", nullable = false, length = 100) String consentVersion;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "accepted_at", nullable = false) Instant acceptedAt;
}
