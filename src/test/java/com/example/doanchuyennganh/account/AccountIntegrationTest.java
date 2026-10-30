package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.example.doanchuyennganh.account.security.DatabaseUserDetailsService;
import com.example.doanchuyennganh.account.seed.LocalAccountSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AccountIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired DatabaseUserDetailsService details;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void migrationAndEntityValidateAgainstMysqlWithUtcTimestamps() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        Instant before = Instant.now().minusSeconds(1);
        var saved = users.saveAndFlush(new User(uniqueEmail(), encoder.encode("fixture-password"), "Fixture", Role.STUDENT, true));
        var loaded = users.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        var storedUtc = jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM app_users WHERE id = ?",
                String.class, saved.getId());
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                .withZone(java.time.ZoneOffset.UTC);
        assertThat(storedUtc).isEqualTo(formatter.format(loaded.getCreatedAt()));
    }

    @Test
    void emailIsNormalizedOnSaveAndRepositoryLookup() {
        String email = uniqueEmail();
        var saved = users.saveAndFlush(new User("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ",
                encoder.encode("fixture-password"), "Fixture", Role.STUDENT, true));
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(users.findByEmail(" " + email.toUpperCase(java.util.Locale.ROOT) + " "))
                .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
    }

    @Test
    void uniqueEmailIsEnforcedByDatabase() {
        String email = uniqueEmail();
        users.saveAndFlush(new User(email, encoder.encode("fixture-password"), "First", Role.STUDENT, true));
        assertThatThrownBy(() -> users.saveAndFlush(new User(" " + email.toUpperCase(java.util.Locale.ROOT) + " ",
                encoder.encode("other-fixture"), "Duplicate", Role.TEACHER, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInvalidRoleAndDefaultsEnabledToTrue() {
        for (String invalidRole : new String[]{"OWNER", "student", "STUDENT "}) {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO app_users (email,password_hash,display_name,role,created_at,updated_at)
                    VALUES (?, 'fixture-not-a-password', 'Fixture', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, uniqueEmail(), invalidRole))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().isInstanceOf(java.sql.SQLException.class)
                    .satisfies(cause -> assertThat(((java.sql.SQLException) cause).getErrorCode()).isEqualTo(3819));
        }
        String email = uniqueEmail();
        jdbc.update("""
                INSERT INTO app_users (email,password_hash,display_name,role,created_at,updated_at)
                VALUES (?, 'fixture-not-a-password', 'Fixture', 'STUDENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, email);
        assertThat(users.findByEmail(email).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void bcryptPreservesPasswordWhitespaceAndCase() {
        String raw = "  Fixture-Case-Sensitive  ";
        String hash = encoder.encode(raw);
        assertThat(encoder).isInstanceOf(org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.class);
        assertThat(encoder.matches(raw, hash)).isTrue();
        assertThat(encoder.matches(raw.trim(), hash)).isFalse();
        assertThat(encoder.matches(raw.toLowerCase(java.util.Locale.ROOT), hash)).isFalse();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void detailsLoadExactlyOneAuthorityAndEnabledStateFromDatabase() {
        for (Role role : Role.values()) {
            String email = uniqueEmail();
            users.saveAndFlush(new User(email, encoder.encode("fixture-password"), "Fixture", role, false));
            var principal = details.loadUserByUsername("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
            assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_" + role.name());
            assertThat(principal.isEnabled()).isFalse();
            assertThat(principal.getRole()).isEqualTo(role);
            assertThat(principal.getEmail()).isEqualTo(email);
        }
    }

    @Test
    void realProviderAuthenticatesMysqlAccountAndRejectsBadMissingOrDisabledCredentials() {
        String email = uniqueEmail();
        String raw = "  Case-Sensitive-Fixture  ";
        var user = users.saveAndFlush(new User(email, encoder.encode(raw), "Fixture", Role.TEACHER, true));
        var authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", raw));
        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getPrincipal()).isInstanceOf(AccountPrincipal.class);
        var principal = (AccountPrincipal) authenticated.getPrincipal();
        assertThat(principal.getId()).isEqualTo(user.getId());
        assertThat(principal.getPassword()).isNull();
        assertThat(authenticated.getCredentials()).isNull();
        assertThat(authenticated.getAuthorities()).extracting("authority").containsExactly("ROLE_TEACHER");
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, raw.trim())))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(uniqueEmail(), raw)))
                .isInstanceOf(BadCredentialsException.class);
        String disabledEmail = uniqueEmail();
        users.saveAndFlush(new User(disabledEmail, encoder.encode(raw), "Disabled", Role.ADMIN, false));
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(disabledEmail, raw)))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void principalIsDetachedAndDoesNotExposePasswordInToStringOrJson() throws Exception {
        String raw = "fixture-secret";
        var saved = users.saveAndFlush(new User(uniqueEmail(), encoder.encode(raw), "Fixture", Role.ADMIN, true));
        var principal = details.loadUserByUsername(saved.getEmail());
        assertThat(AccountPrincipal.class.getDeclaredFields()).noneMatch(field -> field.getType().equals(User.class));
        var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        assertThat(principal.toString()).doesNotContain(raw, saved.getPasswordHash());
        assertThat(saved.toString()).doesNotContain(raw, saved.getPasswordHash());
        assertThat(mapper.writeValueAsString(principal)).doesNotContain(raw, saved.getPasswordHash(), "password");
        assertThat(mapper.writeValueAsString(saved)).doesNotContain(raw, saved.getPasswordHash(), "password");
    }

    @Test
    void seedIsNotInstalledForTestProfile() {
        assertThat(context.getBeansOfType(LocalAccountSeeder.class)).isEmpty();
    }

    @Test
    void repeatedSeedPreservesExistingPasswordRoleAndTimestamps() {
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var first = seedEnvironment(" first-fixture ");
            new LocalAccountSeeder(users, encoder, first).run(new DefaultApplicationArguments());
            var student = users.findByEmail("student@example.test").orElseThrow();
            assertThat(encoder.matches(" first-fixture ", student.getPasswordHash())).isTrue();
            var before = jdbc.queryForList("SELECT * FROM app_users WHERE email IN (?, ?, ?) ORDER BY email",
                    "student@example.test", "teacher@example.test", "admin@example.test");
            new LocalAccountSeeder(users, encoder, seedEnvironment("different-fixture"))
                    .run(new DefaultApplicationArguments());
            assertThat(jdbc.queryForList("SELECT * FROM app_users WHERE email IN (?, ?, ?) ORDER BY email",
                    "student@example.test", "teacher@example.test", "admin@example.test")).isEqualTo(before);
            assertThat(before).hasSize(3);
            for (Role role : Role.values()) {
                var account = users.findByEmail(role.name().toLowerCase(java.util.Locale.ROOT) + "@example.test").orElseThrow();
                assertThat(account.getRole()).isEqualTo(role);
                assertThat(account.isEnabled()).isTrue();
                assertThat(encoder.matches(" first-fixture ", account.getPasswordHash())).isTrue();
            }
        });
    }

    @Test
    void seedFailsClearlyForMissingPasswordWithoutInsertingAccounts() {
        long before = users.count();
        var environment = seedEnvironment("fixture");
        environment.setProperty("SEED_TEACHER_PASSWORD", "  ");
        assertThatThrownBy(() -> new LocalAccountSeeder(users, encoder, environment).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SEED_TEACHER_PASSWORD");
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void seedRejectsConflictingRoleWithoutChangingExistingAccount() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            var user = users.saveAndFlush(new User("admin@example.test", encoder.encode("existing-fixture"),
                    "Existing", Role.STUDENT, false));
            assertThatThrownBy(() -> new LocalAccountSeeder(users, encoder, seedEnvironment("replacement-fixture"))
                    .run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("role conflict", "admin@example.test", "ADMIN", "STUDENT");
            var existing = users.findById(user.getId()).orElseThrow();
            assertThat(existing.getRole()).isEqualTo(Role.STUDENT);
            assertThat(existing.isEnabled()).isFalse();
            assertThat(encoder.matches("existing-fixture", existing.getPasswordHash())).isTrue();
            assertThat(users.findByEmail("student@example.test")).isEmpty();
        });
    }

    @Test
    void missingOrInvalidCsrfIs403ProblemDetailWithFiltersEnabled() throws Exception {
        mvc.perform(post("/api/examples/errors/validation").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(post("/api/examples/errors/validation").with(csrf().useInvalidToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedApiRequiresAuthenticationAndNoDefaultLoginUiOrLogoutEndpointExists() throws Exception {
        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/admin/example", "/login", "/logout", "/actuator"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(header().doesNotExist("Location"));
        }
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content("{}"))
                // Login is now implemented: an invalid public request reaches validation.
                .andExpect(status().isBadRequest());
    }

    private static String uniqueEmail() { return UUID.randomUUID() + "@example.test"; }

    private static MockEnvironment seedEnvironment(String password) {
        return new MockEnvironment().withProperty("SEED_STUDENT_PASSWORD", password)
                .withProperty("SEED_TEACHER_PASSWORD", password).withProperty("SEED_ADMIN_PASSWORD", password);
    }
}
