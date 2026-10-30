package com.example.doanchuyennganh.exam;

import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ExamConfiguration.ConsentPolicy.class)
public class ExamConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock examClock() { return Clock.systemUTC(); }

    @Validated @ConfigurationProperties("app.exam.consent")
    public record ConsentPolicy(@DefaultValue("monitoring-v1") @NotBlank @Size(max=100) String version,
            @DefaultValue("Tôi đồng ý với nội dung giám sát được thông báo cho kỳ thi này. Module hiện tại chỉ ghi nhận sự đồng ý, chưa thu thập dữ liệu giám sát.")
            @NotBlank @Size(max=5000) String text) { }
}
