-- B13: teacher accounts made by an administrator, with a temporary password.
--
-- must_change_password: true for an account whose password was set by an administrator (a new teacher, a reset):
--   its tokens only allow changing the password and signing out (403 PASSWORD_CHANGE_REQUIRED elsewhere) until the
--   owner sets an own password. Every existing account keeps false (the default, written without a table rewrite).
-- temp_password_expires_at: until when such a temporary password works (7 days); null for an own password.
-- last_login_at: the last successful sign-in, for the administrator's list of teachers; null until the next one.
--
-- Nothing existing is changed or deleted.

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN temp_password_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;

-- The administrator's list of teachers (100 or so), newest first
CREATE INDEX idx_users_instructors ON users (created_at DESC, id) WHERE role = 'INSTRUCTOR';
