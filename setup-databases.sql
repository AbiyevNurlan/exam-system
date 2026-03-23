-- Create Databases for Exam System
-- Run this with: psql -U postgres -f setup-databases.sql

-- Create databases
CREATE DATABASE exam_auth;
CREATE DATABASE exam_db;
CREATE DATABASE exam_monitoring;

-- Connect to exam_auth and create schema
\c exam_auth

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100),
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);

-- Connect to exam_db and create schema
\c exam_db

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

-- Indexes for performance
CREATE INDEX idx_questions_exam_id ON questions(exam_id);
CREATE INDEX idx_options_question_id ON question_options(question_id);
CREATE INDEX idx_sessions_exam_user ON exam_sessions(exam_id, user_id);
CREATE INDEX idx_sessions_status ON exam_sessions(status);
CREATE INDEX idx_answers_session ON user_answers(session_id);
CREATE INDEX idx_answers_question ON user_answers(question_id);

-- Connect to exam_monitoring
\c exam_monitoring

CREATE TABLE IF NOT EXISTS system_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_name VARCHAR(100) NOT NULL,
    metric_value DECIMAL(20,2),
    service_name VARCHAR(50),
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_metrics_name_time ON system_metrics(metric_name, recorded_at);
CREATE INDEX idx_metrics_service ON system_metrics(service_name);
