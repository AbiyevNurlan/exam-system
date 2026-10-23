-- =====================================================
-- V11 (B13a): the codes of the guest sign-in and the profile step of a new guest
-- Additive only: two new tables. email_otp of accounts is not changed (its rows belong to users, and a guest asks
-- for a code before any row of the guest exists).
--
-- guest_email_otp      : like email_otp (V2), by email: only the HMAC of the code, the wrong attempts, consumed_at;
--                        the send limits (cooldown, per hour) are counted from created_at
-- guest_profile_tokens : the one-time proof "this email was verified just now" with which a new guest gives the
--                        name and phone (15 minutes); only the SHA-256 of the token; guest_id once the guest exists
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE guest_email_otp (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT ck_guest_email_otp_email_lower CHECK (email = lower(email))
);

CREATE INDEX idx_guest_email_otp_email_purpose_created ON guest_email_otp (email, purpose, created_at);

CREATE TABLE guest_profile_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    guest_id UUID REFERENCES guest_participants(id) ON DELETE CASCADE,
    used_at TIMESTAMP,
    CONSTRAINT ck_guest_profile_tokens_email_lower CHECK (email = lower(email))
);

CREATE INDEX idx_guest_profile_tokens_expires ON guest_profile_tokens (expires_at);
