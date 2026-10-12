-- =====================================================
-- V3: user groups, exam audience and exam access type
-- Additive only. Existing exams keep working unchanged: access_type defaults to PUBLIC.
-- User references (created_by, user_id, added_by, actor_id, target_user_id) hold exam_auth.users.id (UUID);
-- there is no foreign key because the users table lives in another database.
-- =====================================================

CREATE TABLE user_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Names are unique ignoring case among the groups that exist; a deleted (inactive) group frees its name
CREATE UNIQUE INDEX uq_user_groups_name ON user_groups (lower(name)) WHERE active = true;

CREATE TABLE user_group_members (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES user_groups(id),
    user_id UUID NOT NULL,
    source VARCHAR(20) NOT NULL,
    added_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_group_members_group_user UNIQUE (group_id, user_id),
    CONSTRAINT ck_group_members_source CHECK (source IN ('ADMIN', 'CSV'))
);

-- "Which groups is this user in" is asked on every exam access check
CREATE INDEX idx_group_members_user_id ON user_group_members(user_id);

-- Emails from a CSV import that have no verified account yet. Turned into a membership when that
-- email logs in with a verified address (auth-service calls exam-service).
CREATE TABLE pending_group_invites (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES user_groups(id),
    email_normalized VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pending_invites_group_email UNIQUE (group_id, email_normalized)
);

CREATE INDEX idx_pending_invites_email ON pending_group_invites(email_normalized);

-- The groups whose members may take a GROUP exam
CREATE TABLE exam_audience (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES user_groups(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_exam_audience_exam_group UNIQUE (exam_id, group_id)
);

CREATE INDEX idx_exam_audience_group_id ON exam_audience(group_id);

-- PUBLIC: every authenticated user. GROUP: only members of the exam's audience groups.
ALTER TABLE exams ADD COLUMN access_type VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE exams ADD CONSTRAINT ck_exams_access_type CHECK (access_type IN ('PUBLIC', 'GROUP'));

-- Who changed which group, membership or exam audience. No foreign keys: the log outlives what it describes.
CREATE TABLE group_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,
    group_id BIGINT,
    target_user_id UUID,
    details TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_group_audit_group_created ON group_audit_log(group_id, created_at);
