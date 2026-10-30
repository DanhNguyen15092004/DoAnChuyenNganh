package com.example.doanchuyennganh.exam;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import java.time.Instant;
import java.util.List;

public final class ExamContracts {
    private ExamContracts() { }
    private static void unknown() { throw new IllegalArgumentException("Unsupported request field"); }

    public record MetadataRequest(@NotBlank @Size(max=200) String title, @Size(max=5000) String description,
            @NotNull @Min(1) @Max(300) Integer durationMinutes, @NotNull Instant opensAt, @NotNull Instant closesAt) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record QuestionsRequest(@NotNull @Size(max=100) List<@NotNull @Valid QuestionRequest> questions) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record QuestionRequest(@NotBlank @Size(max=5000) String content, @NotNull @Min(1) Integer position,
            @NotNull @Size(min=2,max=6) List<@NotNull @Valid OptionRequest> options) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record OptionRequest(@NotBlank @Size(max=2000) String content, @NotNull @Min(1) Integer position,
            @NotNull Boolean correct) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record EnrollmentRequest(@NotNull @Positive Long studentId) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record ConsentRequest(@NotNull @AssertTrue Boolean accepted, @NotBlank @Size(max=100) String consentVersion) {
        @JsonAnySetter public void reject(String field, Object value) { unknown(); }
    }
    public record OptionResponse(Long id, String content, int position, boolean correct) { }
    public record QuestionResponse(Long id, String content, int position, List<OptionResponse> options) { }
    public record TeacherExamResponse(Long id, Long ownerId, String title, String description, int durationMinutes,
            Instant opensAt, Instant closesAt, ExamStatus status, Instant publishedAt, String consentVersion,
            String consentText, Long version, Instant createdAt, Instant updatedAt, List<QuestionResponse> questions) { }
    public record ExamSummary(Long id, Long ownerId, String title, String description, int durationMinutes,
            Instant opensAt, Instant closesAt, ExamStatus status, Instant publishedAt, Instant createdAt, Long version) { }
    public record StudentResponse(Long id, String email, String displayName) { }
    public record EnrollmentResponse(Long id, Long examId, StudentResponse student, Long enrolledBy,
            Instant enrolledAt, boolean consentAccepted, Instant acceptedAt) { }
    public record ConsentResponse(Long id, Long enrollmentId, String consentVersion, Instant acceptedAt) { }
    public enum Availability { UPCOMING, OPEN, CLOSED }
    public record StudentExamResponse(Long id, String title, String description, int durationMinutes, Instant opensAt,
            Instant closesAt, long questionCount, Instant serverTime, Availability availability,
            String consentVersion, String consentText, boolean consentAccepted) { }
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T,R> PageResponse<R> from(Page<T> page, java.util.function.Function<T,R> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
    public record Creation<T>(boolean created, T body) { }
}
