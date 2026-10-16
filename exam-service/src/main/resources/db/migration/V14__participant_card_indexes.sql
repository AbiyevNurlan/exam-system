-- =====================================================
-- V14 (B11): the participant card
-- Additive only: one index. The card shows the newest events where the user acted (idx_group_audit_actor_created,
-- V11) or was the target (this index). Partial: most rows have no target user.
-- The attempts of a user come from idx_sessions_user_start (V5), the groups from idx_group_members_user_id (V3).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE INDEX idx_group_audit_target_created ON group_audit_log (target_user_id, created_at DESC, id DESC)
    WHERE target_user_id IS NOT NULL;
