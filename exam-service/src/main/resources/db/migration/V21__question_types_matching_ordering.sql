-- New question types ORDERING, MATCHING and SHORT_ANSWER (question_type is a free VARCHAR, no constraint to change).
-- ORDERING: the options in their order_index are the right order. MATCHING: option_text is the left side of a pair,
-- match_text the right side that belongs to it. SHORT_ANSWER: the options are the accepted answers, like FILL_BLANK.
-- An answer to ORDERING stores the option ids in the participant's order, an answer to MATCHING the right side
-- chosen for each left side (option ids in the left sides' order, 0 = none), both in user_answers.selected_option_ids.
ALTER TABLE question_options ADD COLUMN match_text TEXT;
