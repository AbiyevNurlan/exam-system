-- =====================================================
-- V8: manual grading of open-ended answers
-- Additive only: new columns with defaults, a backfill that only sets the two new flags, and
-- two indexes. No score, no answer and no session is removed or re-scored here.
-- graded_by holds exam_auth.users.id (UUID); no foreign key, the users table is in another database.
-- =====================================================

-- Who gave the points of an answer, when, and what they wrote to the participant.
--   grade_version : counts the gradings of the answer; a grader sends the version they saw, so two
--                   people grading the same answer cannot overwrite each other unnoticed
-- needs_manual_review exists since V1 and is now actually used: true = waiting for a grader.
ALTER TABLE user_answers ADD COLUMN graded_by UUID;
ALTER TABLE user_answers ADD COLUMN graded_at TIMESTAMP;
ALTER TABLE user_answers ADD COLUMN feedback TEXT;
ALTER TABLE user_answers ADD COLUMN grade_version INT NOT NULL DEFAULT 0;
ALTER TABLE user_answers ADD CONSTRAINT ck_user_answers_feedback_length
    CHECK (feedback IS NULL OR char_length(feedback) <= 2000);

-- NOT_REQUIRED : nothing in the session needs a grader (also every session in progress)
-- PENDING      : completed, at least one answer waits for a grader; the score is not final
-- COMPLETE     : every answer that needed a grader has been graded
ALTER TABLE exam_sessions ADD COLUMN grading_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED';
ALTER TABLE exam_sessions ADD CONSTRAINT ck_exam_sessions_grading_status
    CHECK (grading_status IN ('NOT_REQUIRED', 'PENDING', 'COMPLETE'));

-- Backfill. Until now an open-ended answer was compared with the expected text and got all or
-- nothing. Those answers of completed sessions are marked for a grader; their points, the session
-- scores and everything else stay exactly as they are until someone grades them.
UPDATE user_answers a
SET needs_manual_review = true
FROM exam_sessions s, questions q
WHERE s.id = a.session_id
  AND q.id = a.question_id
  AND s.status = 'COMPLETED'
  AND q.question_type = 'OPEN_ENDED'
  AND a.text_answer IS NOT NULL AND btrim(a.text_answer) <> ''
  AND a.graded_at IS NULL
  AND a.needs_manual_review = false;

UPDATE exam_sessions s
SET grading_status = 'PENDING'
WHERE s.status = 'COMPLETED'
  AND EXISTS (SELECT 1 FROM user_answers a WHERE a.session_id = s.id AND a.needs_manual_review = true);

-- Results of one exam filtered by session status. (exam_id, user_id) is already the prefix of
-- idx_sessions_exam_user_status and idx_sessions_exam_user_created, and user_answers(session_id)
-- the prefix of uq_user_answers_session_question, so neither gets a second index.
CREATE INDEX idx_sessions_exam_status ON exam_sessions(exam_id, status);
-- The grading queue of one exam, oldest submission first
CREATE INDEX idx_sessions_exam_grading ON exam_sessions(exam_id, grading_status, submitted_at);
