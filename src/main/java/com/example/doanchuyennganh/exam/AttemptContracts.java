package com.example.doanchuyennganh.exam;

import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Separate from manager DTOs: answer keys never enter these response types. */
public final class AttemptContracts {
    private AttemptContracts() { }
    public enum Availability { ACTIVE, EXPIRED }
    public record Option(Long id, String content, int position) { }
    public record Question(Long id, String content, int position, List<Option> options) { }
    public record AttemptResponse(Long attemptId, Long examId, String title, Instant startedAt,
            Instant deadlineAt, Instant serverTime, Availability availability, List<Question> questions,
            AttemptStatus status, Instant finalizedAt, Instant effectiveEndedAt, List<Answer> answers) { }
    public record Answer(Long questionId, Long selectedOptionId, long revision, Instant savedAt) { }
    public record SaveAnswerRequest(@Positive Long selectedOptionId, @NotNull @PositiveOrZero Long expectedRevision) {
        @JsonAnySetter public void reject(String field, Object value) { throw new IllegalArgumentException("Unsupported answer field"); }
    }
    public record SaveAnswerResponse(Long questionId, Long selectedOptionId, long revision, Instant savedAt, Instant serverTime) { }
    public record TimeoutResponse(Long attemptId, AttemptStatus status, Instant deadlineAt,
            Instant finalizedAt, Instant effectiveEndedAt, Instant serverTime) { }
    public record ResultResponse(Long attemptId, Long examId, String title, AttemptStatus status,
            Instant startedAt, Instant deadlineAt, Instant effectiveEndedAt, int totalQuestions,
            int answeredCount, int unansweredCount, int correctCount, BigDecimal score, Instant gradedAt) { }
    public record ManagedAttemptResponse(Long attemptId, ExamContracts.StudentResponse student, AttemptStatus status,
            Instant startedAt, Instant deadlineAt, Instant effectiveEndedAt, BigDecimal score, Instant gradedAt, boolean resultReady) { }
    public record ManagedResultResponse(ExamContracts.StudentResponse student, ResultResponse result) { }
    static ResultResponse result(ExamAttempt a, Exam exam) {
        if (a.gradedAt == null) throw new IllegalStateException("Result is not ready.");
        return new ResultResponse(a.id, exam.id, exam.title, a.status, a.startedAt, a.deadlineAt, a.effectiveEndedAt,
                a.totalQuestions, a.answeredCount, a.totalQuestions - a.answeredCount, a.correctCount, a.score, a.gradedAt);
    }
    /** Controllers unwrap only AFTER the service transaction commits, including late-save finalization. */
    public record Outcome<T>(T value, String code, String detail, Instant serverTime) {
        static <T> Outcome<T> ok(T value) { return new Outcome<>(value, null, null, null); }
        static <T> Outcome<T> conflict(String code, String detail, Instant time) { return new Outcome<>(null, code, detail, time); }
        public T unwrap() {
            if (code != null) throw new AttemptConflictException(code, detail, serverTime);
            return value;
        }
    }
}
