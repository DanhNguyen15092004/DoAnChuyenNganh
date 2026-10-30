package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.seed.LocalAccountSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Deliberately boots local against a disposable DB to exercise the actual ApplicationRunner. */
@SpringBootTest(properties = {
        // Isolate this fixture from the developer's .env and local configuration.
        "spring.config.name=seed-fixture",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.jpa.open-in-view=false",
        "app.seed.enabled=true",
        "SEED_STUDENT_PASSWORD=local-seed-fixture-only",
        "SEED_TEACHER_PASSWORD=local-seed-fixture-only",
        "SEED_ADMIN_PASSWORD=local-seed-fixture-only"
})
@ActiveProfiles("local")
@Testcontainers
class LocalAccountSeederStartupTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired LocalAccountSeeder seeder;

    @Test
    void applicationRunnerSeedsOnLocalStartupAndTransactionalRerunDoesNotOverwrite() {
        assertThat(AopUtils.isAopProxy(seeder)).isTrue();
        assertThat(users.count()).isEqualTo(3);
        Map<String, String> originalHashes = users.findAll().stream()
                .collect(Collectors.toMap(User::getEmail, User::getPasswordHash));
        for (Role role : Role.values()) {
            var account = users.findByEmail(role.name().toLowerCase(java.util.Locale.ROOT) + "@example.test").orElseThrow();
            assertThat(account.getRole()).isEqualTo(role);
            assertThat(account.isEnabled()).isTrue();
            assertThat(encoder.matches("local-seed-fixture-only", account.getPasswordHash())).isTrue();
        }
        seeder.run(new DefaultApplicationArguments());
        assertThat(users.count()).isEqualTo(3);
        for (User account : users.findAll()) {
            assertThat(account.getPasswordHash().equals(originalHashes.get(account.getEmail()))).isTrue();
        }
    }
}
