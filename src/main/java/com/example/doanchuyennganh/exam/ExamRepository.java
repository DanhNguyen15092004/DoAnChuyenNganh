package com.example.doanchuyennganh.exam;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exam e where e.id = :id")
    Optional<Exam> lockById(@Param("id") Long id);
    Optional<Exam> findByIdAndOwnerId(Long id, Long ownerId);
    Page<Exam> findByOwnerId(Long ownerId, Pageable page);
    @Query("select e from Exam e where e.status = com.example.doanchuyennganh.exam.ExamStatus.PUBLISHED and e.id in (select n.examId from ExamEnrollment n where n.studentId = :student)")
    Page<Exam> findEnrolled(@Param("student") Long studentId, Pageable page);
}
