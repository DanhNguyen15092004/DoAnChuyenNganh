package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {
    Optional<ExamAttempt> findByEnrollmentId(Long enrollmentId);
}
