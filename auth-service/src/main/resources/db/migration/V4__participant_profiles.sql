-- =====================================================
-- V4 (B11): an extendable profile per participant
-- Additive only: two new tables, one nullable column and indexes. Nothing is dropped or renamed;
-- the previous build does not read any of it and keeps working.
--
-- Why in auth-service: the profile belongs to the account (one row per user, removed with the
-- user), and the participant list is filtered in this database (ParticipantQueryRepository), so a
-- filter by category or institution is a join here instead of a list of ids across services.
--
-- participant_categories: the kinds of participant. A lookup table instead of an enum or a CHECK
-- list, so a new kind is one INSERT (a new migration) and no change of the column type; inactive
-- kinds stay valid for the profiles that have them but are not offered any more.
--
-- participant_profiles: at most one row per user (user_id UNIQUE). An account without a row has no
-- profile (profile = null); that is the state of every account before B11 and not an error.
-- Which fields a category needs is configuration (participant-profile.required-fields), not schema:
-- every column but category is nullable.
--
-- admin_audit_log.details: what changed ("field: before -> after") for profile changes; NULL for
-- the rows written before, which never had details.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE participant_categories (
    code VARCHAR(40) PRIMARY KEY,
    sort_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_participant_categories_code CHECK (code ~ '^[A-Z][A-Z_]{1,39}$')
);

INSERT INTO participant_categories (code, sort_order) VALUES
    ('STUDENT', 10),
    ('UNIVERSITY_STUDENT', 20),
    ('SCHOOL_STUDENT', 30),
    ('EMPLOYEE', 40),
    ('INTERN', 50),
    ('OTHER', 90);

CREATE TABLE participant_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(40) NOT NULL REFERENCES participant_categories(code),
    institution VARCHAR(200),
    faculty_or_department VARCHAR(200),
    course_or_grade VARCHAR(50),
    workplace VARCHAR(200),
    position VARCHAR(100),
    city VARCHAR(100),
    birth_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Who wrote the last change: the participant or an administrator (no FK: an admin may be removed later)
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_participant_profiles_user UNIQUE (user_id),
    CONSTRAINT ck_participant_profiles_birth_date CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01')
);

-- Participant list filters: by category, and by the start of the institution (ignoring case)
CREATE INDEX idx_participant_profiles_category ON participant_profiles (category);
CREATE INDEX idx_participant_profiles_institution_lower ON participant_profiles (lower(institution) text_pattern_ops);

ALTER TABLE admin_audit_log ADD COLUMN details TEXT;

-- The participant card: the newest account events where the user acted (target_user_id has its index since V1)
CREATE INDEX idx_admin_audit_log_actor_created ON admin_audit_log (admin_user_id, created_at DESC);
