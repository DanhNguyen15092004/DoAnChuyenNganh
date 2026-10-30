package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.example.doanchuyennganh.common.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
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
    private final AttemptAnswerRepository answers;
    private final AttemptFinalizationService finalizer;

    public ExamAttemptService(ExamRepository exams, ExamEnrollmentRepository enrollments,
            ExamConsentRepository consents, ExamAttemptRepository attempts,
            ExamQuestionRepository questions, QuestionOptionRepository options, Clock clock, AttemptAnswerRepository answers,
            AttemptFinalizationService finalizer) {
        this.exams = exams; this.enrollments = enrollments; this.consents = consents;
        this.attempts = attempts; this.questions = questions; this.options = options; this.clock = clock;
        this.answers = answers;
        this.finalizer = finalizer;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Creation<AttemptResponse> start(AccountPrincipal actor, Long examId) {
        requireStudent(actor);
        // Same first lock as publish/enroll/consent/questions. No read snapshot before it.
        var exam = exams.lockById(examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, examId);
        var existing = attempts.lockByEnrollmentId(enrollment.id);
        // Existing attempts keep their deadline even after the window/consent conditions change.
        if (existing.isPresent()) {
            return new Creation<>(false, response(existing.get(), exam, now()));
        }
        Instant time = now();
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttemptResponse current(AccountPrincipal actor, Long examId) {
        requireStudent(actor);
        var exam = exams.findById(examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, examId);
        var attempt = attempts.lockByEnrollmentId(enrollment.id).orElseThrow(this::notFound);
        return response(attempt, exam, now());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttemptResponse get(AccountPrincipal actor, Long attemptId) {
        requireStudent(actor);
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        var enrollment = enrollments.findById(attempt.enrollmentId)
                .filter(n -> n.studentId.equals(actor.getId())).orElseThrow(this::notFound);
        var exam = exams.findById(enrollment.examId).filter(e -> e.status == ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        return response(attempt, exam, now());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome<SaveAnswerResponse> saveAnswer(AccountPrincipal actor, Long attemptId, Long questionId, SaveAnswerRequest request) {
        requireStudent(actor);
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, attempt);
        Instant time = now(); // Deliberately AFTER lock, not request arrival.
        finalizer.finishLocked(attempt, time, false);
        if (attempt.status != AttemptStatus.IN_PROGRESS || !time.isBefore(attempt.deadlineAt)) {
            return Outcome.conflict(attempt.status == AttemptStatus.SUBMITTED ? "ATTEMPT_FINISHED" : "ATTEMPT_TIMED_OUT",
                    "Lượt thi đã kết thúc; không thể lưu đáp án.", time);
        }
        var question = questions.findById(questionId).filter(q -> q.examId.equals(enrollment.examId))
                .orElseThrow(() -> new InvalidExamRequestException("Câu hỏi không thuộc đề thi này."));
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new InvalidExamRequestException("expectedRevision phải là số nguyên không âm.");
        }
        if (request.selectedOptionId() != null && options.findById(request.selectedOptionId())
                .filter(o -> o.questionId.equals(question.id)).isEmpty()) {
            throw new InvalidExamRequestException("Lựa chọn không thuộc câu hỏi này.");
        }
        var answer = answers.findByAttemptIdAndQuestionId(attemptId, questionId).orElse(null);
        long revision = answer == null ? 0 : answer.revision;
        if (revision != request.expectedRevision()) {
            return Outcome.conflict("ANSWER_REVISION_CONFLICT", "Đáp án đã thay đổi. Hãy đồng bộ và chủ động chọn lại.", time);
        }
        if (answer == null) { answer = new AttemptAnswer(); answer.attemptId = attemptId; answer.questionId = questionId; }
        answer.selectedOptionId = request.selectedOptionId(); answer.revision = revision + 1; answer.updatedAt = time;
        answers.saveAndFlush(answer);
        return Outcome.ok(new SaveAnswerResponse(questionId, answer.selectedOptionId, answer.revision, time, time));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome<TimeoutResponse> timeout(AccountPrincipal actor, Long attemptId) {
        requireStudent(actor);
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        ownEnrollment(actor, attempt);
        Instant time = now();
        finalizer.finishLocked(attempt, time, false);
        if (attempt.status == AttemptStatus.IN_PROGRESS) {
            return Outcome.conflict("TIMEOUT_NOT_DUE", "Chưa đến hạn kết thúc lượt thi.", time);
        }
        return Outcome.ok(new TimeoutResponse(attempt.id, attempt.status, attempt.deadlineAt,
                attempt.finalizedAt, attempt.effectiveEndedAt, time));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultResponse submit(AccountPrincipal actor, Long attemptId) {
        requireStudent(actor);
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, attempt);
        Instant time = now();
        finalizer.finishLocked(attempt, time, true);
        return AttemptContracts.result(attempt, exams.findById(enrollment.examId).orElseThrow(this::notFound));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome<ResultResponse> result(AccountPrincipal actor, Long attemptId) {
        requireStudent(actor);
        var attempt = attempts.lockById(attemptId).orElseThrow(this::notFound);
        var enrollment = ownEnrollment(actor, attempt);
        Instant time = now();
        finalizer.finishLocked(attempt, time, false);
        if (attempt.gradedAt == null) return Outcome.conflict("RESULT_NOT_READY", "Lượt thi chưa kết thúc, chưa có kết quả.", time);
        return Outcome.ok(AttemptContracts.result(attempt, exams.findById(enrollment.examId).orElseThrow(this::notFound)));
    }

    private ExamEnrollment ownEnrollment(AccountPrincipal actor, ExamAttempt attempt) {
        return enrollments.findById(attempt.enrollmentId).filter(n -> n.studentId.equals(actor.getId())).orElseThrow(this::notFound);
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
        finalizer.finishLocked(attempt, time, false);
        boolean withinTime = time.isBefore(attempt.deadlineAt);
        boolean active = attempt.status == AttemptStatus.IN_PROGRESS && withinTime;
        return new AttemptResponse(attempt.id, exam.id, exam.title, attempt.startedAt, attempt.deadlineAt,
                time, withinTime ? Availability.ACTIVE : Availability.EXPIRED,
                active ? paper(exam.id) : List.of(), attempt.status, attempt.finalizedAt, attempt.effectiveEndedAt,
                answers.findByAttemptIdOrderByQuestionIdAsc(attempt.id).stream()
                        .map(a -> new Answer(a.questionId, a.selectedOptionId, a.revision, a.updatedAt)).toList());
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
