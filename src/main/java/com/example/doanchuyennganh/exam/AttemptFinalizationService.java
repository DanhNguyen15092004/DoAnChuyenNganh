package com.example.doanchuyennganh.exam;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class AttemptFinalizationService {
    private final AttemptGrader grader;
    private final ExamEnrollmentRepository enrollments;
    public AttemptFinalizationService(AttemptGrader grader, ExamEnrollmentRepository enrollments) {
        this.grader = grader; this.enrollments = enrollments;
    }

    /** Caller owns the managed attempt's write lock and reads Clock AFTER that lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishLocked(ExamAttempt attempt, Instant time, boolean manualSubmit) {
        if (attempt.gradedAt != null) {
            if (attempt.status == AttemptStatus.IN_PROGRESS) throw new IllegalStateException("In-progress attempt cannot have a result.");
            return;
        }
        if (attempt.status == AttemptStatus.IN_PROGRESS) {
            if (!manualSubmit && time.isBefore(attempt.deadlineAt)) return;
            attempt.status = time.isBefore(attempt.deadlineAt) ? AttemptStatus.SUBMITTED : AttemptStatus.TIMED_OUT;
            attempt.finalizedAt = time;
            attempt.effectiveEndedAt = attempt.status == AttemptStatus.SUBMITTED ? time : attempt.deadlineAt;
        }
        // Historical finalizations keep their original status and both ending timestamps.
        var enrollment = enrollments.findById(attempt.enrollmentId).orElseThrow();
        var grade = grader.grade(attempt.id, enrollment.examId);
        attempt.totalQuestions = grade.total(); attempt.answeredCount = grade.answered();
        attempt.correctCount = grade.correct(); attempt.score = grade.score(); attempt.gradedAt = time;
    }
}
