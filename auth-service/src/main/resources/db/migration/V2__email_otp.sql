-- =====================================================
-- V2: one-time codes sent by email (registration: email verification)
-- Additive only. Existing users and their email_verified flag are not changed.
-- =====================================================

-- One row per code that was sent. Rows are kept after use: the send limits (cooldown, sends per hour)
-- are counted from created_at.
--   code_hash   : HMAC-SHA256 of the code (hex); the code itself is never stored
--   attempts    : wrong codes entered against this row
--   consumed_at : set when the code was used, replaced by a newer code, or burned by too many wrong
--                 attempts; a row with consumed_at can never be verified again
CREATE TABLE email_otp (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_email_otp_user_purpose_created ON email_otp(user_id, purpose, created_at);