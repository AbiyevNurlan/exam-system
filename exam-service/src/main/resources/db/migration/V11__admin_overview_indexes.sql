-- Indexes for the admin overviews added after B5: the grading overview over all exams and the
-- audit list. Only indexes; no table, column or row changes.

-- Grading overview (GET /api/grading/pending-exams and /pending-count): the completed sessions that
-- wait for a grader, over every exam. Without it the overview reads all sessions; the partial index
-- holds only the waiting ones and gives them grouped by exam with the oldest submission first.
CREATE INDEX idx_sessions_pending_grading ON exam_sessions (exam_id, submitted_at)
    WHERE status = 'COMPLETED' AND grading_status = 'PENDING';

-- Audit list (GET /api/admin/audit): newest first, also narrowed to one actor or one action.
-- idx_group_audit_group_created (V3) only serves the rows of one group.
CREATE INDEX idx_group_audit_created ON group_audit_log (created_at DESC, id DESC);
CREATE INDEX idx_group_audit_actor_created ON group_audit_log (actor_id, created_at DESC, id DESC);
CREATE INDEX idx_group_audit_action_created ON group_audit_log (action, created_at DESC, id DESC);
