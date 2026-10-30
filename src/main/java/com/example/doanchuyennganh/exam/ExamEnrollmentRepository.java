package com.example.doanchuyennganh.exam;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ExamEnrollmentRepository extends JpaRepository<ExamEnrollment, Long> {
    Optional<ExamEnrollment> findByExamIdAndStudentId(Long examId, Long studentId);
    Page<ExamEnrollment> findByExamId(Long examId, Pageable page);
}
