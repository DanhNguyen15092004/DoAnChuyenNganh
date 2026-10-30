package com.example.doanchuyennganh.account.seed;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.example.doanchuyennganh.account.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Profile("local & !test & !prod")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class LocalAccountSeeder implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Environment environment;

    public LocalAccountSeeder(UserRepository users, PasswordEncoder encoder, Environment environment) {
        this.users = users;
        this.encoder = encoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        var accounts = List.of(
                new SampleAccount("student@example.test", "Sinh viên mẫu", Role.STUDENT, "SEED_STUDENT_PASSWORD"),
                new SampleAccount("teacher@example.test", "Giảng viên mẫu", Role.TEACHER, "SEED_TEACHER_PASSWORD"),
                new SampleAccount("admin@example.test", "Quản trị viên mẫu", Role.ADMIN, "SEED_ADMIN_PASSWORD"));

        // Validate everything before inserting; diagnostics contain variable names, never values.
        for (var account : accounts) {
            String password = environment.getProperty(account.passwordVariable());
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Local account seed requires non-blank " + account.passwordVariable());
            }
            users.findByEmail(account.email()).ifPresent(existing -> {
                if (existing.getRole() != account.role()) {
                    throw new IllegalStateException("Local account seed role conflict for " + account.email()
                            + ": expected " + account.role() + ", found " + existing.getRole());
                }
            });
        }
        for (var account : accounts) {
            if (users.findByEmail(account.email()).isEmpty()) {
                String hash = encoder.encode(environment.getRequiredProperty(account.passwordVariable()));
                users.save(new User(account.email(), hash, account.displayName(), account.role(), true));
            }
        }
    }

    private record SampleAccount(String email, String displayName, Role role, String passwordVariable) { }
}
