package com.example.doanchuyennganh.account;

import com.example.doanchuyennganh.account.auth.RoleProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthProductionConfigurationTest {
    @Test
    void productionCookieAndSessionSettingsAreBoundFromTheRealProfile() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(ServerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var session = context.getBean(ServerProperties.class).getServlet().getSession();
                    assertThat(session.getTimeout()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(session.getCookie().getName()).isEqualTo("JSESSIONID");
                    assertThat(session.getCookie().getPath()).isEqualTo("/");
                    assertThat(session.getCookie().getHttpOnly()).isTrue();
                    assertThat(session.getCookie().getSecure()).isTrue();
                    assertThat(session.getCookie().getSameSite().attributeValue()).isEqualTo("Lax");
                });
    }

    @Test
    void idleTimeoutIsConfigurable() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod", "SESSION_TIMEOUT=45m")
                .withUserConfiguration(ServerConfiguration.class)
                .run(context -> assertThat(context.getBean(ServerProperties.class).getServlet().getSession().getTimeout())
                        .isEqualTo(Duration.ofMinutes(45)));
    }

    @Test
    void probesOnlyExistInLocalOrTestAndNeverInProduction() {
        for (String profile : new String[]{"local", "test", "prod", "local,prod", "test,prod"}) {
            new ApplicationContextRunner().withUserConfiguration(RoleProbeController.class)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        if (profile.contains("prod")) { assertThat(context).doesNotHaveBean(RoleProbeController.class); }
                        else { assertThat(context).hasSingleBean(RoleProbeController.class); }
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerConfiguration { }
}
