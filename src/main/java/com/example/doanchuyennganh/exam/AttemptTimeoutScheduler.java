package com.example.doanchuyennganh.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
@ConditionalOnProperty(name = "app.attempt.timeout.enabled", havingValue = "true", matchIfMissing = true)
public class AttemptTimeoutScheduler {
    private static final Logger log = LoggerFactory.getLogger(AttemptTimeoutScheduler.class);
    private final ExamAttemptRepository attempts;
    private final AttemptTimeoutService timeouts;
    private final Clock clock;
    private final AttemptTimeoutConfiguration.Policy policy;
    public AttemptTimeoutScheduler(ExamAttemptRepository attempts, AttemptTimeoutService timeouts, Clock clock,
            AttemptTimeoutConfiguration.Policy policy) {
        this.attempts = attempts; this.timeouts = timeouts; this.clock = clock; this.policy = policy;
    }
    @Scheduled(fixedDelayString = "${app.attempt.timeout.scan-delay-ms:5000}",
            initialDelayString = "${app.attempt.timeout.initial-delay-ms:5000}")
    public void sweep() {
        var ids = attempts.dueIds(AttemptStatus.IN_PROGRESS, clock.instant(), PageRequest.of(0, policy.batchSize()));
        process(ids);
        process(attempts.ungradedFinalIds(java.util.List.of(AttemptStatus.TIMED_OUT, AttemptStatus.SUBMITTED), PageRequest.of(0, policy.batchSize())));
    }
    private void process(java.util.List<Long> ids) {
        for (Long id : ids) {
            try { timeouts.finalizeDue(id); } // Separate proxied transaction for every attempt.
            catch (RuntimeException failure) { log.warn("Timeout processing failed for attempt {} ({})", id, failure.getClass().getSimpleName()); }
        }
    }
}
