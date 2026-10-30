package com.example.doanchuyennganh.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
@Table(name = "app_users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() { }

    public User(String email, String passwordHash, String displayName, Role role, boolean enabled) {
        this.email = EmailNormalizer.normalize(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "Password hash is required");
        this.displayName = Objects.requireNonNull(displayName, "Display name is required");
        this.role = Objects.requireNonNull(role, "Role is required");
        this.enabled = enabled;
    }

    @PrePersist
    private void onCreate() {
        email = EmailNormalizer.normalize(email);
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    private void onUpdate() {
        email = EmailNormalizer.normalize(email);
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
