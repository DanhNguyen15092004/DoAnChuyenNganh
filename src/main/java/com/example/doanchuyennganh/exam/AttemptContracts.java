package com.example.doanchuyennganh.exam;

import java.time.Instant;
import java.util.List;

/** Separate from manager DTOs: answer keys never enter these response types. */
public final class AttemptContracts {
    private AttemptContracts() { }
    public enum Availability { ACTIVE, EXPIRED }
    public record Option(Long id, String content, int position) { }
    public record Question(Long id, String content, int position, List<Option> options) { }
    public record AttemptResponse(Long attemptId, Long examId, String title, Instant startedAt,
            Instant deadlineAt, Instant serverTime, Availability availability, List<Question> questions) { }
}
