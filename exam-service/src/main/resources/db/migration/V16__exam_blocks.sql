-- =====================================================
-- V16 (B12a): an exam is made of blocks
-- Additive only: new columns with defaults, one new table, a backfill that gives every existing exam
-- exactly one block ("Əsas blok") holding all its questions, and two triggers. No session, answer,
-- score or result is changed; every question keeps its id, its order and its exam.
--
-- Settings on the exam (all with the current behaviour as default):
--   timing_mode                     EXAM_TIMER (one timer, duration_minutes; as before) or BLOCK_TIMERS
--   allow_return_to_previous_blocks false
--   allow_skip_break                true
--   show_block_breakdown            true: participants may see a result per block (exam_blocks.show_result_to_participant)
-- The participant side (block timers, breaks, navigation rules) is B12b; until then publishing an
-- exam that would need it is refused (FEATURE_NOT_AVAILABLE).
--
-- free_navigation = true for the existing blocks: participants answer any question of the exam in any
-- order while their session runs (AnswerSubmissionService.processAnswer accepts every question of the
-- exam; GET /session/{id}/questions serves any batch), which is free navigation.
-- shuffle_questions of the block copies the exam's setting; the session still shuffles by the exam's
-- setting until B12b.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

ALTER TABLE exams ADD COLUMN timing_mode VARCHAR(16) NOT NULL DEFAULT 'EXAM_TIMER';
ALTER TABLE exams ADD CONSTRAINT ck_exams_timing_mode CHECK (timing_mode IN ('EXAM_TIMER', 'BLOCK_TIMERS'));
ALTER TABLE exams ADD COLUMN allow_return_to_previous_blocks BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE exams ADD COLUMN allow_skip_break BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE exams ADD COLUMN show_block_breakdown BOOLEAN NOT NULL DEFAULT true;

-- position: 1..20, consecutive per exam. The unique key is checked at commit (DEFERRABLE), so the
-- blocks of an exam can swap places inside one transaction.
-- time_limit_minutes: required by the application in BLOCK_TIMERS mode; NULL = no own limit.
-- break_after_minutes: pause before the next block (the last block's is always 0).
CREATE TABLE exam_blocks (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    instructions TEXT,
    time_limit_minutes SMALLINT,
    break_after_minutes SMALLINT NOT NULL DEFAULT 0,
    free_navigation BOOLEAN NOT NULL DEFAULT true,
    shuffle_questions BOOLEAN NOT NULL DEFAULT false,
    show_result_to_participant BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_exam_blocks_id_exam UNIQUE (id, exam_id),
    CONSTRAINT uq_exam_blocks_exam_position UNIQUE (exam_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_exam_blocks_position CHECK (position BETWEEN 1 AND 20),
    CONSTRAINT ck_exam_blocks_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_exam_blocks_instructions CHECK (instructions IS NULL OR char_length(instructions) <= 5000),
    CONSTRAINT ck_exam_blocks_time_limit CHECK (time_limit_minutes IS NULL OR time_limit_minutes BETWEEN 1 AND 480),
    CONSTRAINT ck_exam_blocks_break CHECK (break_after_minutes BETWEEN 0 AND 120)
);

-- Every existing exam (also soft-deleted ones) gets its one block
INSERT INTO exam_blocks (exam_id, position, title, free_navigation, shuffle_questions)
SELECT id, 1, 'Əsas blok', true, COALESCE(shuffle_questions, false) FROM exams;

-- Every question goes into the block of its exam; the block must belong to the same exam
ALTER TABLE questions ADD COLUMN block_id BIGINT;
UPDATE questions q SET block_id = b.id FROM exam_blocks b WHERE b.exam_id = q.exam_id;
ALTER TABLE questions ALTER COLUMN block_id SET NOT NULL;
ALTER TABLE questions ADD CONSTRAINT fk_questions_block FOREIGN KEY (block_id, exam_id) REFERENCES exam_blocks (id, exam_id);
CREATE INDEX idx_questions_block_order ON questions (block_id, order_index);

-- A new exam always starts with one block, whoever inserts it (the application then names and
-- configures it, or adds more), so "every exam has at least one block" holds for every writer.
CREATE FUNCTION exam_main_block() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO exam_blocks (exam_id, position, title, shuffle_questions)
    VALUES (NEW.id, 1, 'Əsas blok', COALESCE(NEW.shuffle_questions, false));
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_exams_main_block AFTER INSERT ON exams
    FOR EACH ROW EXECUTE FUNCTION exam_main_block();

-- A question inserted without a block (an older client, a script, a test) goes into the first block
-- of its exam; a block given explicitly is kept
CREATE FUNCTION question_default_block() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.block_id IS NULL THEN
        SELECT b.id INTO NEW.block_id FROM exam_blocks b WHERE b.exam_id = NEW.exam_id ORDER BY b.position LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_questions_default_block BEFORE INSERT ON questions
    FOR EACH ROW EXECUTE FUNCTION question_default_block();
