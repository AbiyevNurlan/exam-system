-- =====================================================
-- V7 (B11b): one index for each order of the participant list
-- Additive only: five partial indexes on users (participants only, role = 'STUDENT'), no data is changed.
-- Each one has exactly the expressions of ParticipantQueryRepository.orderBy, ending with participant_code
-- (unique), so a page is read in index order and stops after LIMIT + OFFSET rows instead of sorting every
-- participant. Names and email are NOT NULL, so their indexes serve both directions (read backwards for
-- desc). The registration time may be NULL and comes last in both directions, so it has one index per
-- direction. participant_code alone uses uq_users_participant_code (V3).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE INDEX idx_users_students_last_first ON users
    ((btrim(last_name) COLLATE az_ci), (btrim(first_name) COLLATE az_ci), participant_code)
    WHERE role = 'STUDENT';

CREATE INDEX idx_users_students_first_last ON users
    ((btrim(first_name) COLLATE az_ci), (btrim(last_name) COLLATE az_ci), participant_code)
    WHERE role = 'STUDENT';

CREATE INDEX idx_users_students_email ON users ((lower(email) COLLATE "C"), participant_code)
    WHERE role = 'STUDENT';

CREATE INDEX idx_users_students_registered_desc ON users (created_at DESC NULLS LAST, participant_code DESC)
    WHERE role = 'STUDENT';

CREATE INDEX idx_users_students_registered_asc ON users (created_at ASC NULLS LAST, participant_code ASC)
    WHERE role = 'STUDENT';
