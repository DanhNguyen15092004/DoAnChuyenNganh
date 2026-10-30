package com.example.doanchuyennganh.exam;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Collectors;

/** Reads stored answers only. Correct option identifiers never leave this component. */
@Component
public class AttemptGrader {
    private final ExamQuestionRepository questions;
    private final QuestionOptionRepository options;
    private final AttemptAnswerRepository answers;
    public AttemptGrader(ExamQuestionRepository questions, QuestionOptionRepository options, AttemptAnswerRepository answers) {
        this.questions = questions; this.options = options; this.answers = answers;
    }
    public record Grade(int total, int answered, int correct, BigDecimal score) { }
    public Grade grade(Long attemptId, Long examId) {
        var paper = questions.findByExamIdOrderByPositionAsc(examId);
        if (paper.isEmpty()) throw new IllegalStateException("Published exam has no questions.");
        var questionIds = paper.stream().map(q -> q.id).collect(Collectors.toSet());
        var choices = options.findByQuestionIdInOrderByPositionAsc(questionIds);
        var byId = choices.stream().collect(Collectors.toMap(o -> o.id, o -> o));
        for (Long questionId : questionIds) {
            if (choices.stream().filter(o -> o.questionId.equals(questionId) && o.correct).count() != 1) {
                throw new IllegalStateException("Invalid published question configuration.");
            }
        }
        int answered = 0, correct = 0;
        for (var answer : answers.findByAttemptIdOrderByQuestionIdAsc(attemptId)) {
            if (!questionIds.contains(answer.questionId)) throw new IllegalStateException("Invalid stored answer scope.");
            if (answer.selectedOptionId == null) continue;
            var option = byId.get(answer.selectedOptionId);
            if (option == null || !option.questionId.equals(answer.questionId)) throw new IllegalStateException("Invalid stored answer scope.");
            answered++;
            if (option.correct) correct++;
        }
        var score = BigDecimal.valueOf(correct).multiply(BigDecimal.TEN)
                .divide(BigDecimal.valueOf(paper.size()), 2, RoundingMode.HALF_UP);
        return new Grade(paper.size(), answered, correct, score);
    }
}
