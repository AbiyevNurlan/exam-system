-- B13: teachers are isolated from each other, so the name of a group is unique only among the groups of
-- one owner. The old index made the name unique over all owners: two teachers could not both have "11A",
-- and the 409 GROUP_NAME_EXISTS told a teacher that somebody else had a group of that name.
--
-- owner_id is set for every group since V12 (from created_by, which is NOT NULL), so no group needs an owner
-- here. The old unique index holds for every pair of groups, so it holds within one owner too: creating the
-- new one cannot fail on existing rows. Nothing is deleted or changed; only indexes are replaced.

-- Unique per owner, case ignored; also serves the lists of one owner by name (it replaces idx_user_groups_owner,
-- which had exactly these columns without being unique)
CREATE UNIQUE INDEX uq_user_groups_owner_name ON user_groups (owner_id, lower(name)) WHERE active = true;

DROP INDEX IF EXISTS uq_user_groups_name;
DROP INDEX IF EXISTS idx_user_groups_owner;
