-- exam_db database schema
-- Run this while connected to exam_db database

CREATE TABLE IF NOT EXISTS exams (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    duration_minutes INTEGER NOT NULL,
    total_marks DECIMAL(10,2) NOT NULL,
    passing_marks DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS questions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    question_type VARCHAR(20) NOT NULL,
    marks DECIMAL(10,2) NOT NULL,
    display_order INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS question_options (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    option_text TEXT NOT NULL,
    is_correct BOOLEAN DEFAULT false,
    display_order INTEGER
);

CREATE TABLE IF NOT EXISTS exam_sessions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id),
    user_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP,
    score DECIMAL(10,2),
    total_marks DECIMAL(10,2),
    time_taken_seconds INTEGER
);

CREATE TABLE IF NOT EXISTS user_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES questions(id),
    selected_option_ids TEXT,
    text_answer TEXT,
    is_correct BOOLEAN,
    marks_obtained DECIMAL(10,2),
    answered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for 100K+ users performance
CREATE INDEX idx_questions_exam_id ON questions(exam_id);
CREATE INDEX idx_options_question_id ON question_options(question_id);
CREATE INDEX idx_sessions_exam_user ON exam_sessions(exam_id, user_id);
CREATE INDEX idx_sessions_status ON exam_sessions(status);
CREATE INDEX idx_answers_session ON user_answers(session_id);
CREATE INDEX idx_answers_question ON user_answers(question_id);
