-- B13: limits per teacher, set by an administrator. A teacher without a row, and a null column, has the system
-- default (exam.instructor-limits.* in application.yml). ADMIN has no limits. A limit that is reached refuses the
-- next group, member, exam or upload with 409 TEACHER_LIMIT_REACHED; nothing existing is removed when an
-- administrator lowers a limit below what a teacher has already.
CREATE TABLE instructor_limits (
    instructor_id   UUID PRIMARY KEY,
    max_groups      INTEGER CHECK (max_groups IS NULL OR max_groups > 0),
    max_members     INTEGER CHECK (max_members IS NULL OR max_members > 0),
    max_exams       INTEGER CHECK (max_exams IS NULL OR max_exams > 0),
    max_media_bytes BIGINT CHECK (max_media_bytes IS NULL OR max_media_bytes > 0),
    updated_by      UUID NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);
