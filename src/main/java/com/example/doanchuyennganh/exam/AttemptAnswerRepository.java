package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, Long> {
    Optional<AttemptAnswer> findByAttemptIdAndQuestionId(Long attemptId, Long questionId);
    List<AttemptAnswer> findByAttemptIdOrderByQuestionIdAsc(Long attemptId);
}
