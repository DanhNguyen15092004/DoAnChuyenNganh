package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.example.doanchuyennganh.common.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import static com.example.doanchuyennganh.exam.AttemptContracts.*;
import static com.example.doanchuyennganh.exam.ExamContracts.Creation;

@Service
@Transactional(readOnly = true)
public class ExamAttemptService {
    private final ExamRepository exams;
    private final ExamEnrollmentRepository enrollments;
    private final ExamConsentRepository consents;
    private final ExamAttemptRepository attempts;
    private final ExamQuestionRepository questions;
    private final QuestionOptionRepository options;
    private final Clock clock;

    public ExamAttemptService(ExamRepository exams, ExamEnrollmentRepository enrollments,
            ExamConsentRepository consents, ExamAttemptRepository attempts,
            ExamQuestionRepository questions, QuestionOptionRepository options, Clock clock) {
        this.exams = exams; this.enrollments = enrollments; this.consents = consents;
        this.attempts = attempts; this.questions = questions; this.options = options; this.clock = clock;
    }

    @Transactional
    public Creation<AttemptResponse> start(AccountPrincipal actor, Long examId) {
        requireStudent(actor);
        // Same first lock as publish/enroll/consent/questions. No read snapshot before it.
        var exam = exams.lockById(examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, examId);
        var existing = attempts.findByEnrollmentId(enrollment.id);
        Instant time = now();
        // Existing attempts keep their deadline even after the window/consent conditions change.
        if (existing.isPresent()) return new Creation<>(false, response(existing.get(), exam, time));
        if (consents.findByEnrollmentId(enrollment.id).filter(c -> c.consentVersion.equals(exam.consentVersion)).isEmpty()) {
            throw new ExamConflictException("Bạn cần đồng ý đúng phiên bản nội dung giám sát trước khi bắt đầu.");
        }
        if (time.isBefore(exam.opensAt) || !time.isBefore(exam.closesAt)) {
            throw new ExamConflictException("Kỳ thi chưa mở hoặc đã đóng, không thể tạo lượt thi mới.");
        }
        var attempt = new ExamAttempt();
        attempt.enrollmentId = enrollment.id; attempt.startedAt = time; attempt.createdAt = time;
        Instant fullDuration = time.plus(exam.durationMinutes, ChronoUnit.MINUTES);
        attempt.deadlineAt = fullDuration.isBefore(exam.closesAt) ? fullDuration : exam.closesAt;
        attempts.saveAndFlush(attempt);
        return new Creation<>(true, response(attempt, exam, time));
    }

    public AttemptResponse current(AccountPrincipal actor, Long examId) {
        requireStudent(actor);
        var exam = exams.findById(examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, examId);
        var attempt = attempts.findByEnrollmentId(enrollment.id).orElseThrow(this::notFound);
        return response(attempt, exam, now());
    }

    public AttemptResponse get(AccountPrincipal actor, Long attemptId) {
        requireStudent(actor);
        var attempt = attempts.findById(attemptId).orElseThrow(this::notFound);
        var enrollment = enrollments.findById(attempt.enrollmentId)
                .filter(n -> n.studentId.equals(actor.getId())).orElseThrow(this::notFound);
        var exam = exams.findById(enrollment.examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        return response(attempt, exam, now());
    }

    private ExamEnrollment ownEnrollment(AccountPrincipal actor, Long examId) {
        return enrollments.findByExamIdAndStudentId(examId, actor.getId()).orElseThrow(this::notFound);
    }
    private void requireStudent(AccountPrincipal actor) {
        if (actor == null || !actor.isEnabled() || actor.getRole() != Role.STUDENT) {
            throw new AccessDeniedException("STUDENT role is required.");
        }
    }
    private AttemptResponse response(ExamAttempt attempt, Exam exam, Instant time) {
        boolean active = time.isBefore(attempt.deadlineAt);
        return new AttemptResponse(attempt.id, exam.id, exam.title, attempt.startedAt, attempt.deadlineAt,
                time, active ? Availability.ACTIVE : Availability.EXPIRED,
                active ? paper(exam.id) : List.of());
    }
    private List<Question> paper(Long examId) {
        var content = questions.findByExamIdOrderByPositionAsc(examId);
        if (content.isEmpty()) return List.of();
        var choices = options.findByQuestionIdInOrderByPositionAsc(content.stream().map(q -> q.id).toList())
                .stream().collect(Collectors.groupingBy(o -> o.questionId));
        return content.stream().map(q -> new Question(q.id, q.content, q.position,
                choices.getOrDefault(q.id, List.of()).stream()
                        .map(o -> new Option(o.id, o.content, o.position)).toList())).toList();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private ResourceNotFoundException notFound() { return new ResourceNotFoundException("Attempt not found."); }
}
