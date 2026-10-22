-- =====================================================
-- V23 (B12b): where a session is among the blocks of its exam
-- Additive only: one new table. No session, answer, score or result is changed.
--
-- One row per block a session has entered, written by SessionBlockService under a lock on the session row:
--   started_at   when the block began (the session start, the end of the previous block, or the end of the break)
--   deadline     when its time runs out: with block timers its own limit, otherwise the end of the session
--   finished_at  when it was left: early by the participant, or at the deadline
--   break_until  the end of the pause after it (block timers only, never after the last block)
--   cursor       a block without free navigation: the furthest question reached (0-based, in the session's order);
--                questions before it are closed
-- The times are those of the timeline, also when the participant was away: a block whose time ran out while
-- nobody looked is finished at its deadline, the next one starts when the break would have ended.
-- Exams with one block, the exam timer and free navigation never get rows (nothing changes for them).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE TABLE session_block_progress (
    session_id BIGINT NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL,
    block_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    deadline TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    break_until TIMESTAMP,
    cursor SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, position),
    CONSTRAINT ck_session_block_position CHECK (position BETWEEN 1 AND 20),
    CONSTRAINT ck_session_block_deadline CHECK (deadline >= started_at),
    CONSTRAINT ck_session_block_break CHECK (break_until IS NULL OR (finished_at IS NOT NULL AND break_until >= finished_at)),
    CONSTRAINT ck_session_block_cursor CHECK (cursor >= 0)
);
