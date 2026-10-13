-- ==============================================
-- V5: index for "my sessions"
-- ==============================================
-- GET /api/exams/my-sessions lists one user's sessions newest first and pages through them.
-- idx_sessions_user_id (V1) finds the rows but cannot return them in that order.

CREATE INDEX idx_sessions_user_start ON exam_sessions(user_id, start_time DESC, id DESC);