-- =====================================================
-- V13: a participant's own join requests
-- One index, nothing else. Serves "my requests" (newest first) and the check whether the user's
-- last request to a group was rejected recently (GroupJoinService, rejection cooldown).
-- =====================================================

CREATE INDEX idx_group_join_requests_user ON group_join_requests (user_id, created_at DESC);
