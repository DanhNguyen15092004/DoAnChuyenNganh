package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.seed.LocalAccountSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalAccountSeederConditionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalAccountSeeder.class)
            // This mock is used only to inspect conditional bean registration, never authentication.
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new);

    @Test
    void localAndExplicitOptInAreBothRequired() {
        runner.withPropertyValues("spring.profiles.active=local", "app.seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LocalAccountSeeder.class));
        runner.withPropertyValues("spring.profiles.active=local", "app.seed.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LocalAccountSeeder.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean(LocalAccountSeeder.class));
        runner.withPropertyValues("app.seed.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LocalAccountSeeder.class));
    }

    @Test
    void productionAndTestNeverInstallSeederEvenWithOptInAndLocalAlsoActive() {
        for (String profiles : new String[]{"prod", "test", "local,prod", "local,test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles, "app.seed.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(LocalAccountSeeder.class));
        }
    }
}
