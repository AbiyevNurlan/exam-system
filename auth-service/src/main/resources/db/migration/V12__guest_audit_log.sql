-- =====================================================
-- V12 (B13a): the audit of guest participants
-- Additive only: one new table. admin_audit_log is not changed and gets no guest rows: its readers expect the id of
-- an account as actor and target.
--
-- One row per event: GUEST_OTP_REQUESTED, GUEST_OTP_VERIFIED, GUEST_CREATED, GUEST_LOGIN, GUEST_UPDATED,
-- GUEST_BLOCKED, GUEST_UNBLOCKED. Who did it (actor_kind ANONYMOUS before the sign-in, GUEST, ADMIN; the id and the
-- 7-digit code) and to which guest (target id and code); the email only masked (a***@domain); details never hold a
-- code, a token or a field value, only what happened (the changed field names, the reason of a block).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE guest_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action VARCHAR(40) NOT NULL,
    actor_kind VARCHAR(10) NOT NULL,
    actor_id UUID,
    actor_code INTEGER,
    target_guest_id UUID,
    target_code INTEGER,
    email_masked VARCHAR(255),
    details VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT ck_guest_audit_log_actor_kind CHECK (actor_kind IN ('ANONYMOUS', 'GUEST', 'ADMIN'))
);

CREATE INDEX idx_guest_audit_log_target ON guest_audit_log (target_guest_id, created_at DESC);
CREATE INDEX idx_guest_audit_log_created ON guest_audit_log (created_at DESC);
