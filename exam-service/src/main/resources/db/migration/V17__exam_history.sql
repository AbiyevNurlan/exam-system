-- =====================================================
-- V17 (B12a): the history of every exam, and the snapshot of every publication
-- Additive only: three new tables and the triggers that keep two of them append-only. Nothing existing
-- is changed. group_audit_log keeps its one line per platform event (GET /api/admin/audit); the full
-- record of what happened to one exam, field by field, lives here.
--
-- exam_revisions: one row per changed thing (the exam's settings, a block, a question, the audience,
--   a status change...). All rows of one save share save_id. revision_no counts the rows of one exam
--   (1, 2, 3 ...), handed out by exam_revision_counters in the transaction of the change.
--   No foreign key to exams: the history stays when the exam is deleted (the delete itself is a row).
--   exam_owner_id: who owned the exam at the time (an INSTRUCTOR may read the history of an own exam,
--   also after it was deleted). actor_code/actor_name as auth-service knew them at that moment.
--   changes: {"field": [before, after], ...}; long texts of edits are cut, the content of a deleted
--   question is kept whole. summary_key + summary_params: the frontend builds the sentence in az/ru/en.
--   reason: changeReason of the request (optional; required for sensitive changes of a published exam
--   only when REQUIRE_CHANGE_REASON_AFTER_PUBLISH is on). ip and user_agent shortened.
-- exam_published_snapshots: the whole exam (settings, blocks, questions, options, answer key, media
--   ids) at each publication, version 1, 2, ... per exam, with the SHA-256 of its canonical JSON.
-- Both are never updated or deleted, also not when the exam is deleted (triggers below).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE exam_revision_counters (
    exam_id BIGINT PRIMARY KEY,
    last_revision INT NOT NULL
);

CREATE TABLE exam_revisions (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    save_id UUID NOT NULL,
    exam_owner_id UUID,
    actor_user_id UUID NOT NULL,
    actor_code INT,
    actor_name VARCHAR(200),
    actor_role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    action VARCHAR(40) NOT NULL,
    entity_type VARCHAR(20) NOT NULL,
    entity_id BIGINT,
    changes JSONB NOT NULL DEFAULT '{}'::jsonb,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    reason TEXT,
    summary_key VARCHAR(60) NOT NULL,
    summary_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip VARCHAR(45),
    user_agent VARCHAR(200),
    request_id VARCHAR(64),
    CONSTRAINT uq_exam_revisions_exam_revision UNIQUE (exam_id, revision_no),
    CONSTRAINT ck_exam_revisions_action CHECK (action IN (
        'EXAM_CREATED', 'EXAM_UPDATED', 'META_CHANGED', 'QUESTION_ADDED', 'QUESTION_UPDATED', 'QUESTION_DELETED',
        'QUESTION_MOVED', 'QUESTION_REORDERED', 'OPTION_CHANGED', 'CORRECT_ANSWER_CHANGED', 'POINTS_CHANGED',
        'BLOCK_ADDED', 'BLOCK_UPDATED', 'BLOCK_DELETED', 'BLOCK_REORDERED', 'SETTINGS_CHANGED', 'AUDIENCE_CHANGED',
        'RESCHEDULED', 'PUBLISHED', 'ARCHIVED', 'CANCELLED', 'DELETE_ATTEMPTED', 'CLONED_FROM')),
    CONSTRAINT ck_exam_revisions_entity CHECK (entity_type IN ('EXAM', 'SETTINGS', 'AUDIENCE', 'BLOCK', 'QUESTION', 'OPTION')),
    CONSTRAINT ck_exam_revisions_reason CHECK (reason IS NULL OR char_length(reason) BETWEEN 3 AND 500),
    -- A guard against runaway rows only: edits are cut in the application, a deleted exam is kept whole
    CONSTRAINT ck_exam_revisions_changes_size CHECK (octet_length(changes::text) <= 16777216)
);

-- The history of one exam in order (the unique key above), by time, by person, and the newest edit of each question
CREATE INDEX idx_exam_revisions_exam_created ON exam_revisions (exam_id, created_at);
CREATE INDEX idx_exam_revisions_actor ON exam_revisions (actor_user_id, created_at);
CREATE INDEX idx_exam_revisions_entity ON exam_revisions (exam_id, entity_type, entity_id, revision_no);

CREATE TABLE exam_published_snapshots (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    snapshot JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    actor_user_id UUID NOT NULL,
    actor_code INT,
    actor_name VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT uq_exam_published_snapshots_version UNIQUE (exam_id, version_no),
    CONSTRAINT ck_exam_published_snapshots_sha CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- Append-only: an UPDATE, DELETE or TRUNCATE of the history is refused, whoever tries
CREATE FUNCTION forbid_history_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_exam_revisions_append_only BEFORE UPDATE OR DELETE ON exam_revisions
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();
CREATE TRIGGER trg_exam_revisions_no_truncate BEFORE TRUNCATE ON exam_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_history_change();
CREATE TRIGGER trg_exam_published_snapshots_append_only BEFORE UPDATE OR DELETE ON exam_published_snapshots
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();
CREATE TRIGGER trg_exam_published_snapshots_no_truncate BEFORE TRUNCATE ON exam_published_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_history_change();
