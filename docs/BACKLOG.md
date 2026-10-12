# Backlog

Open work found during the audit and the fix rounds. Tick an item in the commit that fixes it.
Rules for all changes: [PROJECT_RULES.md](PROJECT_RULES.md).

## Exam

- [x] Email verification: registration now needs a 6-digit email code (OTP); no tokens before verification; `GroupInviteClient.applyForVerifiedUser` runs after verification and on login
- [ ] **Account pre-hijacking:** anyone can register someone else's address with a password of their own; if the real owner later enters the emailed code, the account is verified with the stranger's password. Fix options: set the password at verification, or delete unverified accounts on a new registration for the same address
- [ ] Real email provider for production and its sending limits (today only the local Mailpit is wired; production needs `MAIL_*`)
- [ ] SPF, DKIM and DMARC for the sender domain, otherwise codes land in spam
- [ ] CAPTCHA and real per-client IP limits against OTP abuse: the gateway limit keys on the remote address, which is the proxy or Docker bridge for every client until forwarded headers are trusted
- [ ] OTP via SMS (later)
- [ ] Accounts created before OTP and still unverified (30 on the development database, all test accounts) cannot log in until they verify by `resend-otp` or an admin uses `POST /auth/admin/users/{id}/verify-email`
- [ ] `POST /auth/register` still answers 409 for a known email, so it tells whether an address is registered (resend and verify do not)
- [ ] Unverified accounts and old `email_otp` rows are never cleaned up
- [ ] Verification emails are sent once, detached, without retry or outbox; a failed send is only logged and the user must ask for a new code
- [ ] `email_verification_tokens` (V1) is unused, and the gateway still permits `/auth/verify-email`, which does not exist
- [ ] An account verified by an admin gets its pending group invites only at the next login
- [ ] No "change email" flow; a typo in the address at registration cannot be corrected by the user [x] Submit response ignores `showResultsImmediately` (the score is returned even when the exam withholds results)
- [x] Start returns an IN_PROGRESS session that is already past its end time
- [x] `/available` counts only COMPLETED sessions for remaining attempts, while start counts all sessions
- [x] Results list has no status filter
- [x] Soft-deleted exams are still returned by `GET /api/exams/{id}`
- [x] ADMIN cannot publish or archive another user's exam
- [x] Unused code: `activeSessionExists`, `countCompletedAttempts`, `existsByExamIdAndUserIdAndStatus`, `findTimedOutSessions`, `hasTimedOut`
- [x] No upper bounds in request validation (points, text lengths, number of questions and options)
- [x] Database constraint errors surface as 500 instead of 4xx
- [x] Inactive `@Transactional` on `ExamService.deleteExam` and `updateExamStatus`
- [ ] Manual grading for open-ended questions (today: exact match only, `needs_manual_review` is always false)
- [ ] "Show correct answers after the exam" setting (does not exist; results never include the key)
- [x] Who may take which exam: any authenticated user can start any ACTIVE exam (now: access type PUBLIC or GROUP, user groups, exam audience, one `ExamAccessPolicy`)
- [x] `/available` is not paginated and filters in memory (now: SQL filter, `page`/`size`, max 50)
- [ ] A new exam is PUBLIC until its audience is set (`PUT /api/exams/{id}/audience`); create/update requests cannot carry the audience
- [ ] Instructors cannot list groups (group endpoints are ADMIN only), so they need the group ids from an admin
- [ ] No `GET /api/exams/{id}/audience`: the group ids are returned only by the PUT
- [ ] Deleting a group leaves its GROUP exams accessible to nobody but owner and ADMIN, without a warning
- [ ] No endpoint to list or cancel pending group invites, and none to read `group_audit_log`
- [ ] Group members are returned as user ids only; names and emails need a second lookup in auth-service
- [ ] `POST /api/exams/{id}/start` requires `examId` in the body although it is in the path (`{}` is a 400)
- [ ] Create/update refuse an exam that overlaps another published exam of the same owner, so one instructor cannot prepare parallel exams once one is published
- [ ] Publishing does not check for scheduling conflicts
- [ ] ADMIN cannot edit or delete another user's exam
- [ ] Exam and session-question caches are in memory per instance (wrong with more than one exam-service instance; questions are re-shuffled after a restart)
- [ ] `exam.settings.auto-submit-buffer-seconds` is configured but unused
- [x] `TIMED_OUT` and `ABANDONED` session statuses are never set (removed)
- [ ] Index for the results list ordering (`exam_id, submitted_at DESC`)
- [ ] `FraudDetectionService` is not wired into the exam flow
- [ ] `/available` runs two session queries per listed exam (N+1, at most 50 exams per page)

## Auth

- [x] Inactive `@Transactional` annotations in auth-service (login, forgotPassword, resetPassword)
- [ ] Access tokens issued before the UUID change are rejected (users must log in again); no token revocation list
- [x] Email verification: registration now needs a 6-digit email code (OTP); no tokens before verification; `GroupInviteClient.applyForVerifiedUser` runs after verification and on login
- [ ] **Account pre-hijacking:** anyone can register someone else's address with a password of their own; if the real owner later enters the emailed code, the account is verified with the stranger's password. Fix options: set the password at verification, or delete unverified accounts on a new registration for the same address
- [ ] Real email provider for production and its sending limits (today only the local Mailpit is wired; production needs `MAIL_*`)
- [ ] SPF, DKIM and DMARC for the sender domain, otherwise codes land in spam
- [ ] CAPTCHA and real per-client IP limits against OTP abuse: the gateway limit keys on the remote address, which is the proxy or Docker bridge for every client until forwarded headers are trusted
- [ ] OTP via SMS (later)
- [ ] Accounts created before OTP and still unverified (30 on the development database, all test accounts) cannot log in until they verify by `resend-otp` or an admin uses `POST /auth/admin/users/{id}/verify-email`
- [ ] `POST /auth/register` still answers 409 for a known email, so it tells whether an address is registered (resend and verify do not)
- [ ] Unverified accounts and old `email_otp` rows are never cleaned up
- [ ] Verification emails are sent once, detached, without retry or outbox; a failed send is only logged and the user must ask for a new code
- [ ] `email_verification_tokens` (V1) is unused, and the gateway still permits `/auth/verify-email`, which does not exist
- [ ] An account verified by an admin gets its pending group invites only at the next login
- [ ] No "change email" flow; a typo in the address at registration cannot be corrected by the user
- [ ] Pending group invites are applied at login, not at token refresh
- [ ] `INTERNAL_SERVICE_SECRET` is one shared static secret without rotation; in the development compose the service ports (8081, 8082) with `/internal/**` are published on the host
- [ ] No admin endpoint to change a user's role (INSTRUCTOR is assigned by SQL today)
- [ ] `POST /auth/logout` needs an access token at the gateway but not when auth-service is called directly
- [ ] `forgotPassword` deletes old reset tokens and creates the new one without a transaction; no email is sent yet (TODO in code; `EmailSender` now exists)
- [x] Failed logins are logged at ERROR

## Platform

- [x] Expected 4xx errors are logged at ERROR, and twice (handler and service)
- [ ] Security filter chain answers 401/403 with an empty body, unlike the JSON error shape elsewhere
- [ ] Two codes for 401 (`AUTHENTICATION_ERROR` and `UNAUTHENTICATED`)
- [ ] `X-User-Role` / `X-User-Email` headers are set by the gateway but unused downstream
- [ ] Gateway rate limiting puts all anonymous traffic in one bucket (`anonymous`)
- [ ] No automated tests in any module
- [ ] No foreign key from exam tables to users (different database); deleting a user leaves exams and sessions
- [ ] monitoring-service has no tables and no Flyway setup
- [ ] Services answer 500/503 for a short time after a Postgres restart, until the pool drops dead connections
- [ ] Gateway logs at DEBUG in the development profile
- [x] Gateway DEBUG log printed every request header, including bearer tokens and `X-User-Email` (that logger is now at INFO)
- [ ] Request bodies are limited to the default 256 KB (413 `PAYLOAD_TOO_LARGE`); a 200-question exam with long texts can exceed it
- [ ] Scheduler jobs log a stack trace on every tick while the database is unreachable
- [ ] Every login of a verified user makes one call from auth-service to exam-service (2 s timeout, failures only logged)
- [ ] README does not mention `INTERNAL_SERVICE_SECRET` (documented in `.env.example` only)

## Ops

- [ ] Backups are local only, unencrypted and not scheduled; production needs managed Postgres or off-server copies with WAL archiving (hosting decision)
- [ ] `docker-compose.performance.yml` is invalid (`container_name` together with `deploy.replicas`)
- [ ] Development compose uses fixed `postgres`/`postgres` credentials and publishes port 5432
- [ ] Concurrent Flyway migration by several instances (production starts 2 auth and 3 exam instances) is untested
- [ ] README schema diagram is outdated (TEACHER role, `selected_option_id`, `started_at`)
- [ ] `restore-test.ps1` compares row counts only, not content
- [ ] No CI pipeline

## Frontend

- [ ] User ids are UUID strings (`user.id`, `createdBy`, `userId`), not numbers
- [ ] Status codes changed: completed session 409, max attempts 409 `MAX_ATTEMPTS_REACHED`, expired session 410
- [ ] Use `GET /api/exams/session/{sessionId}/result`; handle `resultsAvailable=false` (score withheld until the exam ends)
- [ ] Send `selectedOptionIds` (array); `selectedOptionId` is legacy
- [ ] Start is idempotent: it returns the existing active session instead of an error
- [ ] Results carry `unansweredQuestions`; the percentage is over all questions of the exam
- [ ] `POST .../submit` now returns the same shape as the result endpoint (`status`, `submittedAt`, `resultsAvailable`, score only when available); `startTime`/`completedAt` are gone
- [ ] New error codes: 413 `PAYLOAD_TOO_LARGE`, 400 `DATA_CONSTRAINT_VIOLATION` / `DATA_VALUE_INVALID`, 409 `DATA_CONFLICT`; request limits (points 0.01-1000, 200 questions, 10 options, text lengths)
- [ ] Results list accepts `?status=IN_PROGRESS|COMPLETED`; `/available` keeps an exam listed while a session can be resumed even with `remainingAttempts=0`
- [ ] Registration changed: `POST /auth/register` returns 201 without tokens; a new verify screen posts `{email, code}` to `/auth/verify-otp` (200 with the usual tokens, 400 `OTP_INVALID` for every failure) and offers `/auth/resend-otp` (always 200; 60 s between codes, 5 per hour)
- [ ] Login can answer 403 `EMAIL_NOT_VERIFIED`: send the user to the verify screen; `/auth/verify-otp` and `/auth/resend-otp` can answer 429 with an empty body
- [ ] `GET /api/exams/available` now returns a page (`data.content`, `page`, `size`, `totalElements`; `?page=&size=`, max 50) instead of a bare array
- [ ] An exam outside the user's groups answers 404 `EXAM_NOT_FOUND` everywhere (detail, start, results); exams carry `accessType` for owner and ADMIN
- [ ] Admin UI for groups: `/api/admin/groups` (CRUD, `?name=`), `/{id}/members` (add by `userId` or `email`, statuses `ADDED|ALREADY_MEMBER|INVITED|ALREADY_INVITED`), `/{id}/members/import` (multipart part `file`, `?dryRun=true` first)
- [ ] Exam audience: `PUT /api/exams/{id}/audience` with `{accessType, groupIds}`

## Later

Deliberately out of scope so far; not started.

- [ ] Organizations (several companies on one installation)
- [ ] Programs (a set of exams a participant goes through)
- [ ] Mentorship applications
- [ ] Payments
- [ ] Per-company branding
- [ ] Enrollments with per-user extra time
- [ ] Join codes for groups or exams
