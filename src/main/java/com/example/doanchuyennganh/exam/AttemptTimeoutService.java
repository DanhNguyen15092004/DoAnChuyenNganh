package com.example.doanchuyennganh.exam;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
public class AttemptTimeoutService {
    private final ExamAttemptRepository attempts;
    private final Clock clock;
    private final AttemptFinalizationService finalizer;
    public AttemptTimeoutService(ExamAttemptRepository attempts, Clock clock, AttemptFinalizationService finalizer) {
        this.attempts = attempts; this.clock = clock; this.finalizer = finalizer;
    }

    @Transactional
    public void finalizeDue(Long id) {
        attempts.lockById(id).ifPresent(attempt ->
                finalizer.finishLocked(attempt, clock.instant().truncatedTo(ChronoUnit.MICROS), false));
    }
}
