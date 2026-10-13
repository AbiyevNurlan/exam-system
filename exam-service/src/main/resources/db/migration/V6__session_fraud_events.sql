-- ==============================================
-- V6: fraud events reported by the participant's browser
-- ==============================================
-- One row per event (tab switch, leaving full screen, copy attempt, ...) of a running session.
-- Only recorded: nothing is decided from these rows automatically. The application stores at most
-- exam.fraud.max-events-per-session rows per session.
--   session_id  : exam_sessions.id; the events go with their session
--   occurred_at : when the browser saw it (UTC); created_at: when the server stored it (UTC)

CREATE TABLE session_fraud_events (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    details VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_session_fraud_events_type CHECK (type IN
        ('TAB_SWITCH', 'WINDOW_BLUR', 'FULLSCREEN_EXIT', 'COPY_ATTEMPT', 'PASTE_ATTEMPT', 'DEVTOOLS', 'OTHER'))
);

-- The per-session count before every insert, and reading a session's events in order
CREATE INDEX idx_session_fraud_events_session ON session_fraud_events(session_id, occurred_at);