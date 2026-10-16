-- =====================================================
-- V5 (B11): a participant code never changes and is never given twice
-- Additive in effect: no data is changed. Two triggers on users and the column default is replaced
-- by one of them, so nextval is called once per new account (the default and a trigger together would
-- use two codes for every account).
--
-- On INSERT the code always comes from participant_code_seq (next_participant_code(), V3), whatever the
-- statement gives: a code of a deleted account cannot be written into a new one, and the sequence never
-- cycles, so a code is never given twice. NOT NULL, UNIQUE and the 7-digit check of V3 stay.
-- On UPDATE a different code is refused; writing the same value (an entity saved whole) passes.
-- After the last code the INSERT fails as before ("reached maximum value of sequence
-- participant_code_seq", AuthService.participantCodesExhausted -> 503 PARTICIPANT_CODES_EXHAUSTED).
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE FUNCTION assign_participant_code() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.participant_code := next_participant_code();
    RETURN NEW;
END;
$$;

CREATE FUNCTION forbid_participant_code_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.participant_code IS DISTINCT FROM OLD.participant_code THEN
        RAISE EXCEPTION 'participant_code of user % cannot be changed', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE users ALTER COLUMN participant_code DROP DEFAULT;

CREATE TRIGGER trg_users_participant_code_assign
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION assign_participant_code();

CREATE TRIGGER trg_users_participant_code_immutable
    BEFORE UPDATE OF participant_code ON users
    FOR EACH ROW EXECUTE FUNCTION forbid_participant_code_change();
