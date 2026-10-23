-- =====================================================
-- V10 (B13a): refresh tokens of guests
-- Additive only: one new table. refresh_tokens of accounts is not changed (its user_id references users; a guest has
-- no row there).
--
-- Only the SHA-256 of a token is stored. The tokens of one sign-in form a family that shares family_expires_at: the
-- sign-in ends 7 days after the code was entered, however often the token is rotated. Rotation revokes the used
-- token; a revoked token presented again ends the whole family (it was copied).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE guest_refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id UUID NOT NULL REFERENCES guest_participants(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    family_id UUID NOT NULL,
    family_expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uq_guest_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_guest_refresh_tokens_guest ON guest_refresh_tokens (guest_id);
CREATE INDEX idx_guest_refresh_tokens_family ON guest_refresh_tokens (family_id);
CREATE INDEX idx_guest_refresh_tokens_expires ON guest_refresh_tokens (family_expires_at);
