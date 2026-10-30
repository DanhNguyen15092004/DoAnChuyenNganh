package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.example.doanchuyennganh.account.UserRepository;
import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.example.doanchuyennganh.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import static com.example.doanchuyennganh.exam.AttemptContracts.*;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@Service @Transactional(readOnly = true)
public class ManagedAttemptService {
    private final ExamRepository exams;
    private final ExamAttemptRepository attempts;
    private final ExamEnrollmentRepository enrollments;
    private final UserRepository users;
    private final AttemptFinalizationService finalizer;
    private final Clock clock;
    public ManagedAttemptService(ExamRepository exams, ExamAttemptRepository attempts, ExamEnrollmentRepository enrollments,
            UserRepository users, AttemptFinalizationService finalizer, Clock clock) {
        this.exams = exams; this.attempts = attempts; this.enrollments = enrollments;
        this.users = users; this.finalizer = finalizer; this.clock = clock;
    }
    public PageResponse<ManagedAttemptResponse> list(AccountPrincipal actor, Long examId, int page, int size) {
        managed(actor, examId);
        if (page < 0 || size < 1 || size > 100) throw new InvalidExamRequestException("page must be non-negative and size must be between 1 and 100.");
        var found = attempts.findForExam(examId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt", "id")));
        var enrollmentMap = enrollments.findAllById(found.getContent().stream().map(a -> a.enrollmentId).toList()).stream()
                .collect(Collectors.toMap(e -> e.id, e -> e));
        var accounts = users.findAllById(enrollmentMap.values().stream().map(e -> e.studentId).toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return PageResponse.from(found, a -> new ManagedAttemptResponse(a.id,
                student(accounts.get(enrollmentMap.get(a.enrollmentId).studentId)), a.status,
                a.startedAt, a.deadlineAt, a.effectiveEndedAt, a.score, a.gradedAt, a.gradedAt != null));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome<ManagedResultResponse> result(AccountPrincipal actor, Long examId, Long attemptId) {
        var exam = managed(actor, examId); // Read only: never take exam lock after an attempt lock.
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        var enrollment = enrollments.findById(attempt.enrollmentId).filter(e -> e.examId.equals(examId)).orElseThrow(this::notFound);
        var time = clock.instant().truncatedTo(ChronoUnit.MICROS);
        finalizer.finishLocked(attempt, time, false);
        if (attempt.gradedAt == null) return Outcome.conflict("RESULT_NOT_READY", "Lượt thi chưa kết thúc, chưa có kết quả.", time);
        return Outcome.ok(new ManagedResultResponse(student(users.findById(enrollment.studentId).orElseThrow(this::notFound)), AttemptContracts.result(attempt, exam)));
    }
    private Exam managed(AccountPrincipal actor, Long examId) {
        if (actor == null || !actor.isEnabled() || (actor.getRole() != Role.ADMIN && actor.getRole() != Role.TEACHER)) {
            throw new AccessDeniedException("Exam management requires TEACHER or ADMIN.");
        }
        return (actor.getRole() == Role.ADMIN ? exams.findById(examId) : exams.findByIdAndOwnerId(examId, actor.getId())).orElseThrow(this::notFound);
    }
    private StudentResponse student(User user) { return new StudentResponse(user.getId(), user.getEmail(), user.getDisplayName()); }
    private ResourceNotFoundException notFound() { return new ResourceNotFoundException("Attempt not found."); }
}
