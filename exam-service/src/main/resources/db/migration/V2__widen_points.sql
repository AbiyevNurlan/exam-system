-- =====================================================
-- V2: question points up to 1000.00
-- DECIMAL(5,2) topped out at 999.99 while request validation allows 0.01 - 1000.
-- Widening a numeric column keeps every existing value and is compatible with the previous build.
-- =====================================================

ALTER TABLE questions ALTER COLUMN points TYPE DECIMAL(7,2);
ALTER TABLE user_answers ALTER COLUMN points_earned TYPE DECIMAL(7,2);
