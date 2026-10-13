-- =====================================================
-- V10: joining a group with a link, and bulk membership
-- Two new tables and indexes. The only change to an existing table: the list of allowed values of
-- user_group_members.source grows by BULK and LINK (no row is changed; every existing row is
-- ADMIN or CSV and stays valid).
-- User references hold exam_auth.users.id (UUID); no foreign key, the users table is in another database.
-- =====================================================

-- How a membership came to be:
--   ADMIN : added by an administrator, one at a time        CSV  : CSV import, directly or by a pending invite
--   BULK  : added by an administrator from a filter or a page selection
--   LINK  : the user joined with the group's link (directly, or after an administrator approved the request)
ALTER TABLE user_group_members DROP CONSTRAINT ck_group_members_source;
ALTER TABLE user_group_members ADD CONSTRAINT ck_group_members_source
    CHECK (source IN ('ADMIN', 'CSV', 'BULK', 'LINK'));

-- One link per group. Creating it again replaces the code, so the old code stops working.
--   code              : 10 characters from an alphabet without 0, O, 1 and I (50 bits), random
--   requires_approval : a join becomes a request an administrator has to approve
--   uses              : joins and requests made through the current code; max_uses NULL = unlimited
CREATE TABLE group_join_links (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES user_groups(id),
    code VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP,
    max_uses INT,
    uses INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_group_join_links_group UNIQUE (group_id),
    CONSTRAINT uq_group_join_links_code UNIQUE (code),
    CONSTRAINT ck_group_join_links_max_uses CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT ck_group_join_links_uses CHECK (uses >= 0)
);

-- A user who asked to join a group whose link needs approval
CREATE TABLE group_join_requests (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES user_groups(id),
    user_id UUID NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by UUID,
    decided_at TIMESTAMP,
    CONSTRAINT ck_group_join_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- At most one open request per user and group; a decided one does not block asking again
CREATE UNIQUE INDEX uq_group_join_requests_pending ON group_join_requests(group_id, user_id) WHERE status = 'PENDING';
-- The requests of a group by status, oldest first
CREATE INDEX idx_group_join_requests_group ON group_join_requests(group_id, status, created_at);

-- "The members of these groups" starts from the group: the unique (group_id, user_id) already serves
-- it. "The groups of these users" (participant list) is served by idx_group_members_user_id (V3).
