package com.example.doanchuyennganh.exam;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {
    List<QuestionOption> findByQuestionIdInOrderByPositionAsc(Collection<Long> questionIds);
    @Modifying
    @Query("delete from QuestionOption o where o.questionId in (select q.id from ExamQuestion q where q.examId = :exam)")
    void deleteForExam(@Param("exam") Long examId);
}
