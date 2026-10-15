-- B10: a 7-digit participant code (1000000-9999999) for every account, for display and search only;
-- never for any permission or security decision. The UUID stays the key.
--
-- Codes come from a sequence n = 0, 1, 2, ... through a bijection of [0, 9 000 000):
--     code = 1 000 000 + (7 654 321 * n + 3 141 592) mod 9 000 000
-- 7 654 321 shares no factor with 9 000 000 = 2^6 * 3^2 * 5^6 (it is odd, its digit sum 28 is not a
-- multiple of 3, it does not end in 0 or 5), so the multiplication is invertible modulo 9 000 000: every
-- n gives a different code and every code is reached once. No collision check, no retry. Consecutive
-- accounts get codes 7 654 321 apart (mod 9 000 000), so the codes do not look sequential.
-- The sequence never cycles: after 9 000 000 codes nextval fails ("reached maximum value") and no
-- code is ever given twice. Applied by Flyway. NEVER edit this file once applied anywhere.

CREATE SEQUENCE participant_code_seq AS BIGINT MINVALUE 0 MAXVALUE 8999999 START WITH 0 NO CYCLE;

CREATE FUNCTION next_participant_code() RETURNS INTEGER
    LANGUAGE sql VOLATILE
AS $$
    SELECT (1000000 + ((7654321::BIGINT * nextval('participant_code_seq') + 3141592) % 9000000))::INTEGER
$$;

ALTER TABLE users ADD COLUMN participant_code INTEGER;

-- Every existing account, whatever its role, oldest first
WITH ordered AS (
    SELECT id, next_participant_code() AS code
    FROM (SELECT id FROM users ORDER BY created_at NULLS FIRST, id) AS existing
)
UPDATE users SET participant_code = ordered.code FROM ordered WHERE users.id = ordered.id;

-- New accounts get theirs from the default (the entity never writes the column)
ALTER TABLE users ALTER COLUMN participant_code SET DEFAULT next_participant_code();
ALTER TABLE users ALTER COLUMN participant_code SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_participant_code UNIQUE (participant_code);
ALTER TABLE users ADD CONSTRAINT ck_users_participant_code CHECK (participant_code BETWEEN 1000000 AND 9999999);
