-- The profile picture of an account (every role). The file lives in the private media bucket under a random key;
-- this row keeps only the key of the confirmed picture and of at most one upload that is not confirmed yet. A new
-- upload replaces the unconfirmed one; a confirmed upload replaces the picture (the old file is deleted).
CREATE TABLE user_avatars (
    user_id              UUID PRIMARY KEY,
    storage_key          VARCHAR(80),
    content_type         VARCHAR(50),
    size_bytes           BIGINT CHECK (size_bytes IS NULL OR size_bytes > 0),
    pending_key          VARCHAR(80),
    pending_content_type VARCHAR(50),
    pending_size_bytes   BIGINT CHECK (pending_size_bytes IS NULL OR pending_size_bytes > 0),
    pending_created_at   TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);
