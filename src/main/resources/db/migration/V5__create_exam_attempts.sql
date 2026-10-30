CREATE TABLE exam_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enrollment_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_attempts_enrollment FOREIGN KEY (enrollment_id) REFERENCES exam_enrollments(id),
    CONSTRAINT uk_attempts_enrollment UNIQUE (enrollment_id),
    CONSTRAINT ck_attempts_deadline CHECK (deadline_at > started_at)
);
