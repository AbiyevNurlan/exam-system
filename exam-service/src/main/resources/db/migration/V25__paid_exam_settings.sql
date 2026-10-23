-- =====================================================
-- V25 (B13b): the settings of a paid open exam
-- Additive only: new columns with defaults; every existing exam becomes access_mode AUDIENCE, which is exactly what
-- it is today (its audience, PUBLIC or GROUP, decides who may take it). Nothing else is changed.
--
-- access_mode           AUDIENCE (today's rule) or PAID_OPEN (guests who paid; registered participants do not see it)
-- price_minor           the price in whole minor units (qəpik), never a fraction; > 0 for PAID_OPEN
-- currency              AZN
-- seat_limit            NULL = no limit; otherwise a pending (until it expires) or paid order holds a seat
-- seats_taken           the seats held now, changed only by a conditional UPDATE (never beyond seat_limit)
-- sales_open_at/close_at the sales window; it ends before the exam starts (checked when saved)
-- refund_deadline_hours a refund may be asked for until this many hours before the start
-- attempts_per_purchase one payment gives this many attempts (1)
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

ALTER TABLE exams ADD COLUMN access_mode VARCHAR(16) NOT NULL DEFAULT 'AUDIENCE';
ALTER TABLE exams ADD COLUMN price_minor INTEGER;
ALTER TABLE exams ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'AZN';
ALTER TABLE exams ADD COLUMN seat_limit INTEGER;
ALTER TABLE exams ADD COLUMN seats_taken INTEGER NOT NULL DEFAULT 0;
ALTER TABLE exams ADD COLUMN sales_open_at TIMESTAMP;
ALTER TABLE exams ADD COLUMN sales_close_at TIMESTAMP;
ALTER TABLE exams ADD COLUMN refund_deadline_hours INTEGER NOT NULL DEFAULT 24;
ALTER TABLE exams ADD COLUMN attempts_per_purchase INTEGER NOT NULL DEFAULT 1;

ALTER TABLE exams ADD CONSTRAINT ck_exams_access_mode CHECK (access_mode IN ('AUDIENCE', 'PAID_OPEN'));
ALTER TABLE exams ADD CONSTRAINT ck_exams_price_minor CHECK (price_minor IS NULL OR price_minor > 0);
ALTER TABLE exams ADD CONSTRAINT ck_exams_currency CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE exams ADD CONSTRAINT ck_exams_seat_limit CHECK (seat_limit IS NULL OR seat_limit > 0);
ALTER TABLE exams ADD CONSTRAINT ck_exams_seats_taken CHECK (seats_taken >= 0 AND (seat_limit IS NULL OR seats_taken <= seat_limit));
ALTER TABLE exams ADD CONSTRAINT ck_exams_refund_deadline CHECK (refund_deadline_hours >= 0);
ALTER TABLE exams ADD CONSTRAINT ck_exams_attempts_per_purchase CHECK (attempts_per_purchase BETWEEN 1 AND 10);
ALTER TABLE exams ADD CONSTRAINT ck_exams_paid_open CHECK (access_mode <> 'PAID_OPEN'
    OR (price_minor IS NOT NULL AND sales_open_at IS NOT NULL AND sales_close_at IS NOT NULL AND sales_open_at < sales_close_at));

-- The public catalog: paid open exams by start
CREATE INDEX idx_exams_paid_open ON exams (start_time, id) WHERE access_mode = 'PAID_OPEN';
