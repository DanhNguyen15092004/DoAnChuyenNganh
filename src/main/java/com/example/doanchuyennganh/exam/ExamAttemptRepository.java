package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {
    Optional<ExamAttempt> findByEnrollmentId(Long enrollmentId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExamAttempt a where a.id = :id")
    Optional<ExamAttempt> lockById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExamAttempt a where a.enrollmentId = :enrollmentId")
    Optional<ExamAttempt> lockByEnrollmentId(Long enrollmentId);

    @Query("select a.id from ExamAttempt a where a.status = :status and a.deadlineAt <= :time order by a.deadlineAt, a.id")
    List<Long> dueIds(AttemptStatus status, Instant time, Pageable page);
    @Query("select a.id from ExamAttempt a where a.status in :statuses and a.gradedAt is null order by a.id")
    List<Long> ungradedFinalIds(List<AttemptStatus> statuses, Pageable page);
    @Query("select a from ExamAttempt a where a.enrollmentId in (select e.id from ExamEnrollment e where e.examId = :examId)")
    Page<ExamAttempt> findForExam(Long examId, Pageable page);
}
