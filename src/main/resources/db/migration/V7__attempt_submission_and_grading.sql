ALTER TABLE exam_attempts
    DROP CHECK ck_attempts_status,
    DROP CHECK ck_attempts_finalization,
    ADD COLUMN total_questions INT NULL,
    ADD COLUMN answered_count INT NULL,
    ADD COLUMN correct_count INT NULL,
    ADD COLUMN score DECIMAL(4,2) NULL,
    ADD COLUMN graded_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_attempts_status CHECK (CAST(status AS BINARY) IN ('IN_PROGRESS', 'SUBMITTED', 'TIMED_OUT')),
    ADD CONSTRAINT ck_attempts_finalization CHECK (
        (status = 'IN_PROGRESS' AND finalized_at IS NULL AND effective_ended_at IS NULL) OR
        (status = 'TIMED_OUT' AND finalized_at IS NOT NULL AND finalized_at >= deadline_at
            AND effective_ended_at IS NOT NULL AND effective_ended_at = deadline_at) OR
        (status = 'SUBMITTED' AND finalized_at IS NOT NULL AND finalized_at >= started_at AND finalized_at < deadline_at
            AND effective_ended_at IS NOT NULL AND effective_ended_at = finalized_at)),
    -- All-null permits historical V6 finalizations. The service/job backfills them.
    ADD CONSTRAINT ck_attempts_grade CHECK (
        (total_questions IS NULL AND answered_count IS NULL AND correct_count IS NULL AND score IS NULL AND graded_at IS NULL) OR
        (status IN ('SUBMITTED', 'TIMED_OUT') AND total_questions IS NOT NULL AND total_questions > 0
            AND answered_count IS NOT NULL AND answered_count BETWEEN 0 AND total_questions
            AND correct_count IS NOT NULL AND correct_count BETWEEN 0 AND answered_count
            AND score IS NOT NULL AND score BETWEEN 0 AND 10 AND graded_at IS NOT NULL)),
    ADD INDEX ix_attempts_ungraded (status, graded_at, id);
