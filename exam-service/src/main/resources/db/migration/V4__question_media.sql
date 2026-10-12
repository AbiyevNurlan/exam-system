-- =====================================================
-- V4: question and option media (images, audio, video)
-- Additive only: four new tables, nothing existing is changed.
-- The files live in S3-compatible object storage; these tables hold only metadata.
-- owner_id holds exam_auth.users.id (UUID); no foreign key, the users table is in another database.
-- =====================================================

-- One row per uploaded file.
--   storage_key : object key in the bucket; a random UUID, never the user's file name
--   status      : PENDING (upload URL issued, content not checked yet), ACTIVE (content verified),
--                 DELETED (soft delete; the object is removed from the bucket)
--   alt_text    : shown to participants as the text alternative
CREATE TABLE media_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    alt_text VARCHAR(300),
    duration_seconds INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP,
    CONSTRAINT uq_media_assets_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_media_assets_kind CHECK (kind IN ('IMAGE', 'AUDIO', 'VIDEO')),
    CONSTRAINT ck_media_assets_status CHECK (status IN ('PENDING', 'ACTIVE', 'DELETED')),
    CONSTRAINT ck_media_assets_size CHECK (size_bytes > 0)
);

-- "How many uploads has this user pending" (upload limit) and the owner's own assets
CREATE INDEX idx_media_assets_owner_status ON media_assets(owner_id, status);
-- The cleanup job looks for old PENDING rows only
CREATE INDEX idx_media_assets_pending_created ON media_assets(created_at) WHERE status = 'PENDING';

-- Media shown with a question. max_plays (AUDIO/VIDEO only) limits how often a participant may
-- fetch the media within one session; NULL = unlimited.
-- Questions are replaced on every exam update, so the rows go with their question (CASCADE) and
-- are written again in the same transaction.
CREATE TABLE question_media (
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_assets(id),
    position SMALLINT NOT NULL DEFAULT 0,
    max_plays SMALLINT,
    PRIMARY KEY (question_id, media_id),
    CONSTRAINT ck_question_media_max_plays CHECK (max_plays IS NULL OR max_plays > 0)
);

CREATE INDEX idx_question_media_media_id ON question_media(media_id);

-- An image shown with an answer option
CREATE TABLE option_media (
    option_id BIGINT NOT NULL REFERENCES question_options(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_assets(id),
    PRIMARY KEY (option_id, media_id)
);

CREATE INDEX idx_option_media_media_id ON option_media(media_id);

-- How often a session has fetched a play-limited media. Keyed by the public session id, so the
-- count survives an exam update that replaces the question rows.
CREATE TABLE media_play_counts (
    session_id VARCHAR(100) NOT NULL REFERENCES exam_sessions(session_id),
    media_id UUID NOT NULL REFERENCES media_assets(id),
    plays INT NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, media_id)
);
