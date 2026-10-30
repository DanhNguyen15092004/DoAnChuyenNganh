package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Mutated only by the transactional exam service; never serialized as an API response. */
@Entity
@Table(name = "exams")
public class Exam {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "owner_id", nullable = false) Long ownerId;
    @Column(nullable = false, length = 200) String title;
    @Column(length = 5000) String description;
    @Column(name = "duration_minutes", nullable = false) int durationMinutes;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "opens_at", nullable = false) Instant opensAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "closes_at", nullable = false) Instant closesAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20) ExamStatus status;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "published_at") Instant publishedAt;
    @Column(name = "consent_version", nullable = false, length = 100) String consentVersion;
    @Column(name = "consent_text", nullable = false, length = 5000) String consentText;
    @Version @Column(nullable = false) Long version;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
