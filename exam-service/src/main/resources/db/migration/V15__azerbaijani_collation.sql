-- =====================================================
-- V15 (B11b): Azerbaijani alphabetical order for group names
-- Additive only: one collation and two partial indexes, no data is changed.
--
-- The same collation as auth-service V6 (see there): the database collation is en_US.utf8 from libc on Alpine
-- (musl), which compares by code point, so a group named "Ədəbiyyat" or "Şagirdlər" was listed after "Zoologiya".
-- az_ci is ICU "az" at strength 2: capital and small letters equal, ı/i, e/ə, s/ş different letters;
-- nondeterministic, so every ORDER BY on it ends with the id. Used only for sorting; searches keep
-- lower(name) LIKE (uq_user_groups_name, idx_user_groups_owner).
--
-- The group lists (all groups, and the groups of one owner) read these indexes in name order instead of sorting.
-- After an ICU upgrade of the image: REINDEX these two and ALTER COLLATION az_ci REFRESH VERSION.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE COLLATION IF NOT EXISTS az_ci (provider = icu, locale = 'az-u-ks-level2', deterministic = false);

CREATE INDEX idx_user_groups_name_az ON user_groups ((btrim(name) COLLATE az_ci), id) WHERE active = true;

CREATE INDEX idx_user_groups_owner_name_az ON user_groups (owner_id, (btrim(name) COLLATE az_ci), id) WHERE active = true;
