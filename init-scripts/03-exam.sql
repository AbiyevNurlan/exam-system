-- =====================================================
-- exam_db schema (exam-service)
-- Runs after 01-create-databases.sh has created the databases.
-- Mirrors: Exam, Question, QuestionOption, ExamSession, UserAnswer.
-- Indexes are plain CREATE INDEX (CONCURRENTLY is not allowed in init scripts).
-- =====================================================

\connect exam_db

CREATE TABLE IF NOT EXISTS categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    parent_id BIGINT REFERENCES categories(id),
    icon_url VARCHAR(500),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exams (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    total_questions INTEGER DEFAULT 0,
    passing_score DECIMAL(5,2) DEFAULT 60.00,
    shuffle_questions BOOLEAN DEFAULT TRUE,
    shuffle_options BOOLEAN DEFAULT TRUE,
    show_results_immediately BOOLEAN DEFAULT FALSE,
    max_attempts INTEGER DEFAULT 1,
    category_id BIGINT REFERENCES categories(id),
    created_by BIGINT NOT NULL,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS questions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    question_type VARCHAR(50) NOT NULL,
    points DECIMAL(5,2) NOT NULL DEFAULT 1.0,
    order_index INTEGER NOT NULL,
    allow_multiple_answers BOOLEAN DEFAULT FALSE,
    is_required BOOLEAN DEFAULT TRUE,
    explanation TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS question_options (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    option_text TEXT NOT NULL,
    is_correct BOOLEAN DEFAULT FALSE,
    order_index INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exam_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    exam_id BIGINT NOT NULL REFERENCES exams(id),
    user_id BIGINT NOT NULL,
    start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    score DECIMAL(10,2),
    total_points DECIMAL(10,2),
    percentage DECIMAL(5,2),
    passed BOOLEAN,
    submitted_at TIMESTAMP,
    ip_address VARCHAR(50),
    browser_info TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

-- selected_option_id: written by the current UserAnswer entity (single choice)
-- selected_option_ids: reserved for multi-select
CREATE TABLE IF NOT EXISTS user_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES questions(id),
    selected_option_id BIGINT,
    selected_option_ids BIGINT[],
    text_answer TEXT,
    is_correct BOOLEAN,
    points_earned DECIMAL(5,2) DEFAULT 0,
    answered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- AnswerSubmissionService.processAnswer updates the existing row per (session, question)
    CONSTRAINT uq_user_answers_session_question UNIQUE (session_id, question_id)
);

-- exams (scheduler and listing queries filter on status/time and active = true)
CREATE INDEX IF NOT EXISTS idx_exams_created_by ON exams(created_by) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_exams_status_start ON exams(status, start_time) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_exams_status_end ON exams(status, end_time) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_exams_category_id ON exams(category_id);

-- questions / options
CREATE INDEX IF NOT EXISTS idx_questions_exam_order ON questions(exam_id, order_index);
CREATE INDEX IF NOT EXISTS idx_options_question_order ON question_options(question_id, order_index);
CREATE INDEX IF NOT EXISTS idx_options_correct ON question_options(question_id) WHERE is_correct = true;

-- exam_sessions (the UNIQUE constraint on session_id already provides the lookup index)
CREATE INDEX IF NOT EXISTS idx_sessions_exam_user_status ON exam_sessions(exam_id, user_id, status);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON exam_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_in_progress_start ON exam_sessions(start_time) WHERE status = 'IN_PROGRESS';
CREATE INDEX IF NOT EXISTS idx_sessions_in_progress_end ON exam_sessions(end_time) WHERE status = 'IN_PROGRESS';

-- user_answers (UNIQUE (session_id, question_id) already serves session_id lookups)
CREATE INDEX IF NOT EXISTS idx_answers_question_id ON user_answers(question_id);
