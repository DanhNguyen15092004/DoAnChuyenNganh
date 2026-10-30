package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {
    List<ExamQuestion> findByExamIdOrderByPositionAsc(Long examId);
    long countByExamId(Long examId);
    @Modifying @Query("delete from ExamQuestion q where q.examId = :exam")
    void deleteForExam(@Param("exam") Long examId);
}
