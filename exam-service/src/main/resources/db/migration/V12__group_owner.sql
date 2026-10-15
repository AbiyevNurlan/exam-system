-- =====================================================
-- V12: the owner of a group
-- Additive only: one new column, filled for every existing row, and an index. Nothing is dropped
-- or renamed; the previous build does not read the column and keeps working.
--
-- owner_id: who manages the group. An INSTRUCTOR sees and changes only the groups they own; an
-- ADMIN every group (GroupAccessPolicy). It starts as the creator; created_by stays what it is.
-- created_by is NOT NULL since V3, so every existing group gets an owner. There is no fallback to
-- "an ADMIN": roles live in exam_auth, not in this database. Should a row ever lack a creator,
-- SET NOT NULL below fails and the migration stops instead of guessing an owner.
-- =====================================================

ALTER TABLE user_groups ADD COLUMN owner_id UUID;

UPDATE user_groups SET owner_id = created_by WHERE owner_id IS NULL;

ALTER TABLE user_groups ALTER COLUMN owner_id SET NOT NULL;

-- "The groups of this instructor" (list, audience check), active groups only, by name
CREATE INDEX idx_user_groups_owner ON user_groups (owner_id, lower(name)) WHERE active = true;
