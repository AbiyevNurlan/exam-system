-- Applied by docker-compose.performance.yml as 05-perf-indexes.sql (after 03-exam.sql)
\connect exam_db

-- Performance Optimization: Database Indexes
-- Designed for 100,000 concurrent users
-- Query optimization for high-load scenarios

-- ====================
-- EXAMS TABLE INDEXES
-- ====================

-- Index for finding user's exams (dashboard)
CREATE INDEX IF NOT EXISTS idx_exams_created_by ON exams(created_by) 
WHERE active = true;

-- Index for finding active exams by status and time window
CREATE INDEX IF NOT EXISTS idx_exams_status_time ON exams(status, start_time, end_time) 
WHERE active = true;

-- Index for scheduling conflict checks
CREATE INDEX IF NOT EXISTS idx_exams_time_range ON exams(created_by, start_time, end_time) 
WHERE active = true AND status IN ('SCHEDULED', 'ACTIVE');

-- Composite index for available exams query
CREATE INDEX IF NOT EXISTS idx_exams_available ON exams(status, start_time, active);

-- ====================
-- QUESTIONS TABLE INDEXES
-- ====================

-- Index for fetching all questions of an exam
CREATE INDEX IF NOT EXISTS idx_questions_exam ON questions(exam_id, order_index) 
WHERE active = true;

-- Index for counting exam questions
CREATE INDEX IF NOT EXISTS idx_questions_exam_count ON questions(exam_id) 
WHERE active = true;

-- ====================
-- QUESTION OPTIONS INDEXES
-- ====================

-- Index for fetching options by question
CREATE INDEX IF NOT EXISTS idx_options_question ON question_options(question_id, order_index);

-- Index for finding correct answers (grading optimization)
CREATE INDEX IF NOT EXISTS idx_options_correct ON question_options(question_id, is_correct) 
WHERE is_correct = true;

-- Composite index for batch option queries
CREATE INDEX IF NOT EXISTS idx_options_batch ON question_options(question_id) 
INCLUDE (option_text, is_correct, order_index);

-- ====================
-- EXAM SESSIONS INDEXES
-- ====================

-- Index for finding user's sessions
CREATE INDEX IF NOT EXISTS idx_sessions_user ON exam_sessions(user_id, exam_id, created_at DESC);

-- Index for finding active sessions (monitoring)
CREATE INDEX IF NOT EXISTS idx_sessions_active ON exam_sessions(status, start_time) 
WHERE status = 'IN_PROGRESS';

-- Index for expired session cleanup
CREATE INDEX IF NOT EXISTS idx_sessions_expired ON exam_sessions(status, end_time) 
WHERE status = 'IN_PROGRESS';

-- Unique index for session ID lookups (ultra-fast)
CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_session_id ON exam_sessions(session_id);

-- Index for counting user attempts
CREATE INDEX IF NOT EXISTS idx_sessions_attempts ON exam_sessions(exam_id, user_id, status);

-- ====================
-- USER ANSWERS INDEXES
-- ====================

-- Index for finding all answers in a session
CREATE INDEX IF NOT EXISTS idx_answers_session ON user_answers(session_id, question_id);

-- Index for calculating session score
CREATE INDEX IF NOT EXISTS idx_answers_score ON user_answers(session_id) 
INCLUDE (is_correct, points_earned);

-- Composite index for answer lookup
CREATE INDEX IF NOT EXISTS idx_answers_session_question ON user_answers(session_id, question_id);

-- ====================
-- PARTITIONING STRATEGY (Optional for > 1M records)
-- ====================

-- Partition exam_sessions by month (for archival)
-- CREATE TABLE exam_sessions_2026_01 PARTITION OF exam_sessions
-- FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- ====================
-- STATISTICS UPDATE
-- ====================

-- Ensure query planner has fresh statistics
ANALYZE exams;
ANALYZE questions;
ANALYZE question_options;
ANALYZE exam_sessions;
ANALYZE user_answers;

-- ====================
-- CONNECTION POOLING SETTINGS
-- ====================

-- Optimize for high connection count
-- Add to postgresql.conf:
-- max_connections = 300
-- shared_buffers = 2GB
-- effective_cache_size = 6GB
-- work_mem = 16MB
-- maintenance_work_mem = 512MB
-- checkpoint_completion_target = 0.9
-- wal_buffers = 16MB
-- default_statistics_target = 100
-- random_page_cost = 1.1
-- effective_io_concurrency = 200
