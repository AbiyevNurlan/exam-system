-- =====================================================
-- V9 (B13a): guest participants of paid open exams
-- Additive only: one new table. No existing table, sequence or function is changed.
--
-- A guest has no password and no row in users: the email is proved with a one-time code every time, and on the
-- first visit the guest gives a name, a surname and a phone number. The participant code comes from the same
-- sequence and the same bijection as the codes of users (participant_code_seq, next_participant_code(), V3), through
-- the same two trigger functions as users (assign_participant_code(), forbid_participant_code_change(), V5): every
-- INSERT takes the next code whatever the statement gives, an UPDATE cannot change it. One sequence for both tables
-- and a bijection behind it, so a code of a guest is never the code of an account and the other way round.
--
-- email is stored in lower case (CHECK), the phone in E.164 (+994 and 9 digits). A blocked guest has a reason.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE guest_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    email_verified_at TIMESTAMP NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(13) NOT NULL,
    participant_code INTEGER NOT NULL,
    consent_at TIMESTAMP NOT NULL,
    consent_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    last_login_at TIMESTAMP,
    blocked BOOLEAN NOT NULL DEFAULT false,
    blocked_reason VARCHAR(500),
    CONSTRAINT uq_guest_participants_email UNIQUE (email),
    CONSTRAINT uq_guest_participants_participant_code UNIQUE (participant_code),
    CONSTRAINT ck_guest_participants_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_guest_participants_participant_code CHECK (participant_code BETWEEN 1000000 AND 9999999),
    CONSTRAINT ck_guest_participants_phone CHECK (phone ~ '^\+994[1-9][0-9]{8}$'),
    CONSTRAINT ck_guest_participants_names CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0),
    CONSTRAINT ck_guest_participants_blocked_reason CHECK (NOT blocked OR length(btrim(coalesce(blocked_reason, ''))) > 0)
);

CREATE TRIGGER trg_guest_participants_participant_code_assign
    BEFORE INSERT ON guest_participants
    FOR EACH ROW EXECUTE FUNCTION assign_participant_code();

CREATE TRIGGER trg_guest_participants_participant_code_immutable
    BEFORE UPDATE OF participant_code ON guest_participants
    FOR EACH ROW EXECUTE FUNCTION forbid_participant_code_change();

-- The admin's list: newest first, and names in Azerbaijani order (az_ci, V6), each ending with the code
CREATE INDEX idx_guest_participants_created ON guest_participants (created_at DESC, participant_code);
CREATE INDEX idx_guest_participants_last_name ON guest_participants ((btrim(last_name) COLLATE az_ci), (btrim(first_name) COLLATE az_ci), participant_code);
CREATE INDEX idx_guest_participants_first_name ON guest_participants ((btrim(first_name) COLLATE az_ci), (btrim(last_name) COLLATE az_ci), participant_code);
