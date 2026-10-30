CREATE TABLE exams (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(5000),
    duration_minutes INT NOT NULL,
    opens_at DATETIME(6) NOT NULL,
    closes_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    published_at DATETIME(6),
    consent_version VARCHAR(100) NOT NULL,
    consent_text VARCHAR(5000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_exams_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT ck_exams_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_exams_duration CHECK (duration_minutes BETWEEN 1 AND 300),
    CONSTRAINT ck_exams_window CHECK (opens_at < closes_at AND TIMESTAMPDIFF(SECOND, opens_at, closes_at) >= duration_minutes * 60),
    CONSTRAINT ck_exams_status CHECK (CAST(status AS BINARY) IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_exams_publication CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)),
    CONSTRAINT ck_exams_consent CHECK (CHAR_LENGTH(TRIM(consent_version)) > 0 AND CHAR_LENGTH(TRIM(consent_text)) > 0),
    INDEX ix_exams_owner_created (owner_id, created_at, id)
);

CREATE TABLE exam_questions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    content VARCHAR(5000) NOT NULL,
    position INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_questions_exam FOREIGN KEY (exam_id) REFERENCES exams(id),
    CONSTRAINT uk_questions_position UNIQUE (exam_id, position),
    CONSTRAINT ck_questions_position CHECK (position > 0),
    CONSTRAINT ck_questions_content CHECK (CHAR_LENGTH(TRIM(content)) > 0)
);

CREATE TABLE exam_question_options (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    position INT NOT NULL,
    is_correct BOOLEAN NOT NULL,
    CONSTRAINT fk_options_question FOREIGN KEY (question_id) REFERENCES exam_questions(id),
    CONSTRAINT uk_options_position UNIQUE (question_id, position),
    CONSTRAINT ck_options_position CHECK (position > 0),
    CONSTRAINT ck_options_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT ck_options_correct CHECK (is_correct IN (0, 1))
);

CREATE TABLE exam_enrollments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    enrolled_by BIGINT NOT NULL,
    enrolled_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_enrollments_exam FOREIGN KEY (exam_id) REFERENCES exams(id),
    CONSTRAINT fk_enrollments_student FOREIGN KEY (student_id) REFERENCES app_users(id),
    CONSTRAINT fk_enrollments_actor FOREIGN KEY (enrolled_by) REFERENCES app_users(id),
    CONSTRAINT uk_enrollments_student UNIQUE (exam_id, student_id),
    INDEX ix_enrollments_student (student_id, exam_id)
);

CREATE TABLE exam_consents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enrollment_id BIGINT NOT NULL,
    consent_version VARCHAR(100) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_consents_enrollment FOREIGN KEY (enrollment_id) REFERENCES exam_enrollments(id),
    CONSTRAINT uk_consents_enrollment UNIQUE (enrollment_id),
    CONSTRAINT ck_consents_version CHECK (CHAR_LENGTH(TRIM(consent_version)) > 0)
);
