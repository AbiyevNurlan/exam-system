-- "Diapazon" (LINEAR_SCALE), as a linear scale of Google Forms: the participant picks one whole number from
-- scale_min (0 or 1) to scale_max (2..10); scale_min_label and scale_max_label are the optional words under the
-- ends. The right number is the question's only option (option_text = the number, is_correct = true), like the
-- accepted answers of FILL_BLANK, so it stays in the answer key; the answer is stored in user_answers.text_answer.
-- Additive only: every existing question keeps NULL in all four columns.
ALTER TABLE questions
    ADD COLUMN scale_min       SMALLINT,
    ADD COLUMN scale_max       SMALLINT,
    ADD COLUMN scale_min_label VARCHAR(100),
    ADD COLUMN scale_max_label VARCHAR(100);

ALTER TABLE questions
    ADD CONSTRAINT chk_questions_scale_range CHECK (
        (scale_min IS NULL AND scale_max IS NULL)
        OR (scale_min IN (0, 1) AND scale_max BETWEEN 2 AND 10)
    ),
    ADD CONSTRAINT chk_questions_scale_only_linear CHECK (
        question_type = 'LINEAR_SCALE'
        OR (scale_min IS NULL AND scale_max IS NULL AND scale_min_label IS NULL AND scale_max_label IS NULL)
    );
