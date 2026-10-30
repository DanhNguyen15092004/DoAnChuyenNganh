package com.example.doanchuyennganh.exam;

import jakarta.persistence.*;

@Entity @Table(name = "exam_question_options")
public class QuestionOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "question_id", nullable = false) Long questionId;
    @Column(nullable = false, length = 2000) String content;
    @Column(nullable = false) int position;
    @Column(name = "is_correct", nullable = false) boolean correct;
}
