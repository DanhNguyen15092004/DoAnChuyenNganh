package com.example.doanchuyennganh.exam;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AttemptTimeoutConfiguration.Policy.class)
public class AttemptTimeoutConfiguration {
    @Validated @ConfigurationProperties("app.attempt.timeout")
    public record Policy(@DefaultValue("100") @Min(1) @Max(1000) int batchSize,
            @DefaultValue("5000") @Min(10) long scanDelayMs,
            @DefaultValue("5000") @Min(0) long initialDelayMs) { }
}
