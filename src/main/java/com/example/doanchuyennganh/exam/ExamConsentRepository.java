package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ExamConsentRepository extends JpaRepository<ExamConsent, Long> {
    Optional<ExamConsent> findByEnrollmentId(Long enrollmentId);
}
