ALTER TABLE exam_attempts
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    ADD COLUMN finalized_at DATETIME(6) NULL,
    ADD COLUMN effective_ended_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_attempts_status CHECK (CAST(status AS BINARY) IN ('IN_PROGRESS', 'TIMED_OUT')),
    ADD CONSTRAINT ck_attempts_finalization CHECK (
        (status = 'IN_PROGRESS' AND finalized_at IS NULL AND effective_ended_at IS NULL) OR
        (status = 'TIMED_OUT' AND finalized_at IS NOT NULL AND finalized_at >= deadline_at AND effective_ended_at IS NOT NULL AND effective_ended_at = deadline_at)),
    ADD INDEX ix_attempts_due (status, deadline_at, id);

CREATE TABLE exam_attempt_answers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option_id BIGINT NULL,
    revision BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_answers_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempts(id),
    CONSTRAINT fk_answers_question FOREIGN KEY (question_id) REFERENCES exam_questions(id),
    CONSTRAINT fk_answers_option FOREIGN KEY (selected_option_id) REFERENCES exam_question_options(id),
    CONSTRAINT uk_answers_question UNIQUE (attempt_id, question_id),
    CONSTRAINT ck_answers_revision CHECK (revision > 0)
);
