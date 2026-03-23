-- =====================================================
-- DATABASE SCHEMA - PRODUCTION OPTIMIZED
-- Indexes for 100K concurrent users
-- =====================================================

-- ============== AUTH DATABASE ==============

-- Users table with optimized indexes
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(20),
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    email_verified BOOLEAN DEFAULT FALSE,
    account_locked BOOLEAN DEFAULT FALSE,
    failed_login_attempts INTEGER DEFAULT 0,
    last_login_at TIMESTAMP,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

-- Performance indexes
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_role ON users(role);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_active ON users(active) WHERE active = true;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_created_at ON users(created_at DESC);

-- Refresh tokens table
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(500) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens(expires_at) WHERE revoked = false;

-- Password reset tokens table
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(500) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reset_tokens_token ON password_reset_tokens(token);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reset_tokens_user ON password_reset_tokens(user_id);

-- ============== EXAM DATABASE ==============

-- Exams table
CREATE TABLE IF NOT EXISTS exams (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    created_by BIGINT NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    total_questions INTEGER DEFAULT 0,
    passing_score DECIMAL(5,2) DEFAULT 60.00,
    max_attempts INTEGER DEFAULT 1,
    shuffle_questions BOOLEAN DEFAULT FALSE,
    shuffle_options BOOLEAN DEFAULT FALSE,
    show_results BOOLEAN DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_exams_created_by ON exams(created_by);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_exams_status ON exams(status);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_exams_start_time ON exams(start_time);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_exams_active ON exams(status, start_time, end_time) WHERE deleted = false;

-- Questions table
CREATE TABLE IF NOT EXISTS questions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    question_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE_CHOICE',
    points DECIMAL(5,2) DEFAULT 1.00,
    order_index INTEGER NOT NULL DEFAULT 0,
    explanation TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_questions_exam_id ON questions(exam_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_questions_order ON questions(exam_id, order_index);

-- Question options table
CREATE TABLE IF NOT EXISTS question_options (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    option_text TEXT NOT NULL,
    is_correct BOOLEAN DEFAULT FALSE,
    order_index INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_options_question_id ON question_options(question_id);

-- Exam sessions table (for tracking attempts)
CREATE TABLE IF NOT EXISTS exam_sessions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id),
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP,
    time_remaining_seconds INTEGER,
    score DECIMAL(5,2),
    total_correct INTEGER DEFAULT 0,
    total_questions INTEGER DEFAULT 0,
    passed BOOLEAN,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_exam_user ON exam_sessions(exam_id, user_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_user_status ON exam_sessions(user_id, status);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_active ON exam_sessions(status) WHERE status = 'IN_PROGRESS';

-- User answers table
CREATE TABLE IF NOT EXISTS user_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL,
    selected_option_ids BIGINT[],
    text_answer TEXT,
    is_correct BOOLEAN,
    points_earned DECIMAL(5,2) DEFAULT 0,
    answered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_answers_session ON user_answers(session_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_answers_question ON user_answers(question_id);

-- ============== PARTITIONING FOR HIGH VOLUME ==============

-- Partition exam_sessions by month (for high volume)
-- CREATE TABLE exam_sessions_y2026m01 PARTITION OF exam_sessions
--     FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- ============== VACUUM AND ANALYZE ==============
-- Run these periodically:
-- VACUUM ANALYZE users;
-- VACUUM ANALYZE exams;
-- VACUUM ANALYZE questions;
-- VACUUM ANALYZE exam_sessions;
