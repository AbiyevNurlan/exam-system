-- =====================================================
-- V9: indexes for live monitoring and analytics
-- Indexes only: no column, no row and no constraint is changed.
-- =====================================================

-- "The events of this session after id N" (the monitoring screen polls for new ones only).
-- idx_session_fraud_events_session (session_id, occurred_at) cannot return them in id order.
CREATE INDEX idx_session_fraud_events_session_id ON session_fraud_events(session_id, id);

-- Sessions started within the last N days, over all exams (platform analytics).
-- Sessions in progress are already served by idx_sessions_in_progress_end (end_time, partial),
-- so (status, end_time) gets no second index.
CREATE INDEX idx_sessions_start_time ON exam_sessions(start_time);
