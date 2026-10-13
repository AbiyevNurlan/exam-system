-- =====================================================
-- V7: daily attempt limit of an exam
-- Additive only. Existing exams keep working unchanged: NULL = no daily limit.
-- The total limit stays in max_attempts; this one caps how many sessions one user may open
-- for the exam within one calendar day (the day's zone is the service setting EXAM_DAY_ZONE).
-- =====================================================

ALTER TABLE exams ADD COLUMN max_attempts_per_day SMALLINT;
ALTER TABLE exams ADD CONSTRAINT ck_exams_max_attempts_per_day
    CHECK (max_attempts_per_day IS NULL OR max_attempts_per_day BETWEEN 1 AND 100);

-- "How many sessions has this user opened for this exam today" is asked on every start
CREATE INDEX idx_sessions_exam_user_created ON exam_sessions(exam_id, user_id, created_at);
