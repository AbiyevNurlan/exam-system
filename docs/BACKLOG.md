# Backlog

Open work found during the audit and the fix rounds. Tick an item in the commit that fixes it.
Rules for all changes: [PROJECT_RULES.md](PROJECT_RULES.md).

## Exam

- [x] Email verification: registration now needs a 6-digit email code (OTP); no tokens before verification; `GroupInviteClient.applyForVerifiedUser` runs after verification and on login
- [x] **Account pre-hijacking:** anyone can register someone else's address with a password of their own; if the real owner later enters the emailed code, the account is verified with the stranger's password. Fix options: set the password at verification, or delete unverified accounts on a new registration for the same address (now: registering an unverified address again replaces its password and name and invalidates the older codes)
- [x] Real email provider (SMTP) for production and its sending limits (today only the local Mailpit is wired; production needs `MAIL_*`) (now: `MAIL_*` switch development and production to any SMTP provider, startup check, HTML+text mail; README "Real email". Choosing the provider and its account is an operations step)
- [ ] SPF, DKIM and DMARC on the production sender domain, otherwise codes land in spam
- [ ] Bounce and complaint handling: nothing reads the provider's bounces or spam complaints; codes keep being sent to dead or complaining addresses, which costs sender reputation
- [ ] CAPTCHA against OTP abuse (real per-client IP limits are done: the gateway believes X-Forwarded-For from `GATEWAY_TRUSTED_PROXIES` only, which production must set to nginx)
- [ ] OTP via SMS (later)
- [ ] Accounts created before OTP and still unverified (30 on the development database, all test accounts) cannot log in until they verify by `resend-otp` or an admin uses `POST /auth/admin/users/{id}/verify-email`
- [ ] `POST /auth/register` still answers 409 for a known email, so it tells whether an address is registered (resend and verify do not)
- [x] Unverified accounts and old `email_otp` rows are never cleaned up (now: daily `AccountCleanupJob`, `auth.cleanup.*`; accounts are deactivated, never deleted)
- [ ] Outbox for guaranteed delivery: emails are sent detached with 3 attempts in memory (1 s, 3 s) and counted (`mail_sent_total`, `mail_failed_total`); a restart or a longer mail outage still loses the email and the user must ask for a new code
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
- [x] A new exam is PUBLIC until its audience is set (`PUT /api/exams/{id}/audience`); create/update requests cannot carry the audience (now: `accessType` and `groupIds` on create and update; default stays PUBLIC)
- [x] Instructors cannot list groups (group endpoints are ADMIN only), so they need the group ids from an admin (now: `GET /api/groups`, id and name only)
- [x] No `GET /api/exams/{id}/audience`: the group ids are returned only by the PUT (now: `GET /api/exams/{id}` and the manage list carry `accessType`, `audienceGroupCount` and `groupIds` for ADMIN and the owner)
- [ ] Deleting a group leaves its GROUP exams accessible to nobody but owner and ADMIN, without a warning
- [x] No endpoint to list or cancel pending group invites, and none to read `group_audit_log` (now: `GET /api/admin/groups/{id}/invites` and `DELETE .../invites/{inviteId}`; reading the audit log is still open, see below)
- [x] Group members are returned as user ids only; names and emails need a second lookup in auth-service (now: first name, last name and masked email, one lookup per page)
- [x] `POST /api/exams/{id}/start` requires `examId` in the body although it is in the path (`{}` is a 400) (now: the body is optional)
- [x] Create/update refuse an exam that overlaps another published exam of the same owner, so one instructor cannot prepare parallel exams once one is published (the overlap check is removed)
- [ ] Publishing does not check for scheduling conflicts
- [ ] ADMIN cannot edit or delete another user's exam
- [ ] Exam and session-question caches are in memory per instance (wrong with more than one exam-service instance; questions are re-shuffled after a restart)
- [ ] `exam.settings.auto-submit-buffer-seconds` is configured but unused
- [x] `TIMED_OUT` and `ABANDONED` session statuses are never set (removed)
- [ ] Index for the results list ordering (`exam_id, submitted_at DESC`)
- [x] `FraudDetectionService` is not wired into the exam flow (removed: fraud events are stored by `FraudEventService` and scored by `FraudRiskPolicy`)
- [x] No endpoint to read `group_audit_log` (now: `GET /api/admin/audit`, together with auth-service's `admin_audit_log`)
- [x] Exam responses carry `accessType` but not the audience group ids (those come only from `PUT /api/exams/{id}/audience`)
- [ ] The audit list merges two databases in exam-service: each is read up to the end of the requested page, so it is paged only 10 000 rows deep; `admin_audit_log` has no details column, and its `q` matches the action only
- [ ] The group list counts `examCount` over exams of every status (drafts and archived included, deleted ones not); there is no filter by status
- [ ] The member search of a group (`GET /api/admin/groups/{id}/members?q=`) goes through the participant list of auth-service, so members who are not STUDENT (staff added by id) are not found by it; emails stay masked
- [ ] The CSV export names its rows with one auth-service lookup per 500 rows (100 lookups for 50 000 rows) and has no progress or resume; an error after the first bytes cuts the file (the client sees a broken download, not a JSON error)
- [ ] The member list shows no name or email when auth-service cannot be reached, or for an inactive account (the list itself still answers)
- [x] Question and option media (IMAGE, AUDIO, VIDEO): direct upload with presigned URLs, magic-byte confirmation, media on questions (up to 3, optional `maxPlays`) and options (one image), session-bound download URLs with server-side play limits
- [ ] A confirmed file can be replaced by its uploader through the same upload URL until that URL expires (10 minutes): the signature fixes size and content type, but the new content is not checked again. Fix: copy the object to a final key at confirmation
- [ ] `media_assets.duration_seconds` is never filled (no probing of audio/video); the magic-byte check proves the container type, not that the file plays
- [ ] An object whose removal fails stays in the bucket unreferenced (logged only), and DELETED asset rows are never purged
- [ ] `GET /api/exams/my` signs a preview URL for every media of every listed exam
- [ ] Every media URL handed out counts as a play, also when the player never starts; a participant who reloads the page uses up a play
- [ ] Media stays reachable through an issued URL until it expires (5 minutes), also after the session was submitted
- [ ] `/available` runs two session queries per listed exam (N+1, at most 50 exams per page)

## Auth

- [x] Inactive `@Transactional` annotations in auth-service (login, forgotPassword, resetPassword)
- [ ] Access tokens issued before the UUID change are rejected (users must log in again); no token revocation list
- [x] Email verification: registration now needs a 6-digit email code (OTP); no tokens before verification; `GroupInviteClient.applyForVerifiedUser` runs after verification and on login
- [x] **Account pre-hijacking:** anyone can register someone else's address with a password of their own; if the real owner later enters the emailed code, the account is verified with the stranger's password. Fix options: set the password at verification, or delete unverified accounts on a new registration for the same address (now: registering an unverified address again replaces its password and name and invalidates the older codes)
- [x] Real email provider (SMTP) for production and its sending limits (today only the local Mailpit is wired; production needs `MAIL_*`) (now: `MAIL_*` switch development and production to any SMTP provider, startup check, HTML+text mail; README "Real email". Choosing the provider and its account is an operations step)
- [ ] SPF, DKIM and DMARC on the production sender domain, otherwise codes land in spam
- [ ] Bounce and complaint handling: nothing reads the provider's bounces or spam complaints; codes keep being sent to dead or complaining addresses, which costs sender reputation
- [ ] CAPTCHA against OTP abuse (real per-client IP limits are done: the gateway believes X-Forwarded-For from `GATEWAY_TRUSTED_PROXIES` only, which production must set to nginx)
- [ ] OTP via SMS (later)
- [ ] Accounts created before OTP and still unverified (30 on the development database, all test accounts) cannot log in until they verify by `resend-otp` or an admin uses `POST /auth/admin/users/{id}/verify-email`
- [ ] `POST /auth/register` still answers 409 for a known email, so it tells whether an address is registered (resend and verify do not)
- [x] Unverified accounts and old `email_otp` rows are never cleaned up (now: daily `AccountCleanupJob`, `auth.cleanup.*`; accounts are deactivated, never deleted)
- [ ] Outbox for guaranteed delivery: emails are sent detached with 3 attempts in memory (1 s, 3 s) and counted (`mail_sent_total`, `mail_failed_total`); a restart or a longer mail outage still loses the email and the user must ask for a new code
- [ ] `email_verification_tokens` (V1) is unused, and the gateway still permits `/auth/verify-email`, which does not exist
- [ ] An account verified by an admin gets its pending group invites only at the next login
- [ ] No "change email" flow; a typo in the address at registration cannot be corrected by the user
- [ ] Pending group invites are applied at login, not at token refresh
- [ ] `INTERNAL_SERVICE_SECRET` is one shared static secret without rotation; in the development compose the service ports (8081, 8082) with `/internal/**` are published on the host
- [ ] No admin endpoint to change a user's role (INSTRUCTOR is assigned by SQL today)
- [ ] `POST /auth/logout` needs an access token at the gateway but not when auth-service is called directly
- [ ] Pre-hijacking, remaining gap: a stranger who registers the address again after the owner did, and before the owner verifies, replaces the password once more; the owner then verifies an account with the stranger's password. Closing it needs the password to be set (or confirmed) at verification
- [ ] A second registration inside the 60 s cooldown invalidates the first code and sends no new one; the user has to wait and use `resend-otp`
- [ ] Registering an unverified address again also reactivates an account that the cleanup job (or anyone else) had deactivated
- [ ] `password_reset_tokens` (V1) is unused now, like `email_verification_tokens`; `CodeGenerator.generatePasswordResetToken` is unused as well
- [ ] Password reset is only for verified accounts; an unverified user registers again instead. A successful reset also lifts a lockout
- [x] `forgotPassword` deletes old reset tokens and creates the new one without a transaction; no email is sent yet (TODO in code; `EmailSender` now exists) (now: `forgot-password` emails a 6-digit code, `reset-password` takes `{email, code, newPassword}`; the token flow is removed)
- [x] Failed logins are logged at ERROR
- [x] B11: an extendable participant profile (auth V4 `participant_profiles`, categories as the lookup `participant_categories`), `GET/PUT /api/me/profile`, the admin's participant card `GET /api/admin/participants/{id}` (INSTRUCTOR: members of own groups only), `PATCH /api/admin/participants/{id}/profile` (ADMIN, audited before/after), category and institution filters, participant codes fixed by the database (auth V5) and `scripts\check-participant-codes.ps1`
- [ ] B11: the frontend has no profile form and no participant card yet (the API is there)
- [ ] B11: `PATCH .../profile` cannot clear `birthDate` (null keeps a field); a full `PUT /api/me/profile` by the participant can
- [ ] B11: an INSTRUCTOR's card shows no events (the audit rows of other groups and of accounts are not filtered by owner); an ADMIN sees the newest 20 of both logs
- [ ] B11: required fields per category are configuration of auth-service (`participant-profile.required-fields`), not editable in the admin screens; adding a category is a migration (one INSERT into `participant_categories`)
- [ ] B11: `scripts\regression\verify-b11.ps1` has not run yet: it needs the development stack, and the public mode was up while B11 was written (V4, V5 and V14 were checked on throwaway copies of the live databases only)
- [x] B11b: the participant list sorted on the server in the Azerbaijani alphabet: `sortBy` (code, firstName, lastName, email, registeredAt; a whitelist, else 400) and `dir` (asc default, desc); names by `btrim(name) COLLATE az_ci` (auth V6: ICU "az" at strength 2, nondeterministic: case ignored, ı/i, e/ə, s/ş different letters), every order ends with `participant_code` (stable pages), a missing registration time last; one partial index per order (auth V7). Group lists (exam V15), the member search, results and monitoring by name follow the same alphabet (`AzerbaijaniOrder` in Java)
- [ ] B11b: auth V6/V7 and exam V15 are not applied to the live databases yet (checked on throwaway copies of the backup of 2026-10-17: no row changed, indexes valid); they run with the next start of auth-service and exam-service. `verify-b11b.ps1` has not run yet (the public mode was up)
- [ ] B11b: az_ci records the ICU version it was created with; after an image upgrade that brings another ICU, PostgreSQL warns ("version mismatch", already shown today for the built-in az-x-icu of the live database): REINDEX the az_ci indexes (auth V7, exam V15) and `ALTER COLLATION az_ci REFRESH VERSION` in both databases
- [ ] B11b: the frontend does not send `sortBy`/`dir` yet; the older `sort`/`order` still work (sort=name is now the Azerbaijani order by last name too)
- [ ] The live auth database has one account whose names are stored double-encoded (UTF-8 read as Latin-1: "MÉ™mmÉ™dova-ÅžÃ¼kÃ¼rova"); it sorts by those characters. Data, not code; fix by hand if it is a real participant
- [x] B12a: an exam is made of blocks (exam V16: `exam_blocks`, one "Əsas blok" for every existing exam, `questions.block_id` NOT NULL; timing mode, block time limits and breaks, free navigation, return to earlier blocks, block result visibility); updates change questions, options and blocks in place (ids kept, a question can move to another block, removal only without sessions: B9 EXAM_HAS_SESSIONS kept); every change of an exam is a row of `exam_revisions` in the transaction of the change (exam V17, append-only by triggers, kept after a delete; summary_key + params for the frontend, sensitive flag, optional changeReason); every publication stores a snapshot with its SHA-256 (`exam_published_snapshots`); history and versions endpoints, clone, readiness and the publish gate (EXAM_NOT_READY); block results hidden from participants on the server
- [ ] B12a: exam V16/V17 are not applied to the live database yet (checked on a throwaway copy of the backup of 2026-10-19: 59 exams -> 59 blocks, 391 questions each in the block of its exam, sessions, answers, questions and options byte for byte); they run with the next start of exam-service. `verify-b12a.ps1` has not run yet (the public mode was up)
- [ ] B12a: the participant side of blocks (block timers, breaks, fixed navigation, returning to earlier blocks) is B12b; until then an exam that needs it cannot be published (409 FEATURE_NOT_AVAILABLE, `EXAM_BLOCK_RULES_ENABLED=false`)
- [ ] B12a: no frontend yet for blocks, the history, the published versions, cloning and the readiness check; `REQUIRE_CHANGE_REASON_AFTER_PUBLISH` is off by default (changeReason optional everywhere)

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
- [x] README does not mention `INTERNAL_SERVICE_SECRET` (documented in `.env.example` only)

## Ops

- [ ] Backups are local only, unencrypted and not scheduled; production needs managed Postgres or off-server copies with WAL archiving (hosting decision)
- [ ] `docker-compose.performance.yml` is invalid (`container_name` together with `deploy.replicas`)
- [ ] Development compose uses fixed `postgres`/`postgres` credentials and publishes port 5432
- [ ] Concurrent Flyway migration by several instances (production starts 2 auth and 3 exam instances) is untested
- [ ] README schema diagram is outdated (TEACHER role, `selected_option_id`, `started_at`)
- [ ] `restore-test.ps1` compares row counts only, not content
- [ ] exam-service uses the storage root credentials in development; it needs a dedicated access key limited to the media bucket
- [ ] `docker-compose.production.yml` has no object storage and no `MEDIA_S3_*` settings: media endpoints answer 503 there
- [ ] The development storage (SeaweedFS) is a single node without replication
- [ ] `restore-test.ps1` compares the media object count and the backup checksums, not the restored content
- [ ] Media backups are full copies on every run (no incremental copy); large video libraries will make `backup.ps1` slow and big
- [ ] No CI pipeline
- [ ] The backend has no automated tests at all (no `src/test` in any module); `feat/participant-api` was verified only against the running stack
- [x] Dates of auth-service were written without `Z` (now: `UtcDateTimeModule` is registered there too)
- [ ] `docker-compose.production.yml` does not pass `MEDIA_ORPHAN_RETENTION_DAYS` (and has no media storage at all)
- [ ] Fraud events are written only: there is no endpoint for an admin to read `session_fraud_events`, and the frontend still posts the old body (`eventType`, `count`, `timestamp`), which now answers 400 instead of 404
- [x] The in-memory `FraudDetectionService` is not connected to anything (removed)
- [ ] Roles can only be changed in the database: there is no admin endpoint for assigning ADMIN or INSTRUCTOR
- [ ] Groups have no owner, so every group action is ADMIN only; an INSTRUCTOR cannot build the audience of their own exam and has to ask an admin
- [ ] A join link cannot be deleted, only switched off or replaced; `group_join_links` keeps one row per group forever
- [ ] A rejected join request does not stop the participant from asking again at once; there is no cool-down and no notification of the decision
- [ ] Joining with a link does not tell the participant which exams the group gives access to, and nothing notifies them when a request is approved
- [ ] The participant list and the bulk addition hand sets of user ids between the services (a group filter of 20 000 members is a 760 KB request body); the internal endpoints accept up to 16 MB, which is the real ceiling of these screens
- [ ] `noGroup=true` and a group filter read every membership of the database into the request; fine at tens of thousands, not at millions
- [ ] The bulk cap (20 000) is a constant in `ParticipantDtos`, not a setting
- [ ] `feat/groups-bulk` was verified only against the running stack (no automated tests)
- [ ] Test data of `feat/groups-bulk`: 20 000 synthetic participants `t-b5-%-6ace5e2a@x.test` (password hash `not-a-hash`, so none of them can log in), groups `T-b5-*`, accounts `be-b5-*` and `be-b5j-*@x.test`
- [x] An ADMIN could attach the own media to another user's exam, and that owner could not save the exam again (now: media the exam already carries is accepted from every editor)
- [ ] Exams deleted before `feat/exam-lifecycle` are still in the table as `active = false` (delete was a soft delete then); their media stays attached and is therefore never cleaned up
- [ ] `feat/exam-lifecycle` was verified only against the running stack (no automated tests): daily limit, reschedule, delete, management list
- [ ] The management list searches the title with `ILIKE '%text%'`, which cannot use an index; with many thousands of exams it needs a trigram index (`pg_trgm`)
- [ ] The management list shows `createdBy` as a user id only; the creator's name needs a lookup in auth-service
- [ ] Archiving, publishing and deleting by the owner: only delete and reschedule always write an audit row; publish and archive write one only when an ADMIN acts on someone else's exam
- [x] `POST /api/exams/{id}/publish` set ACTIVE also for an exam whose end time had passed (now 400 `EXAM_WINDOW_ENDED`)
- [ ] `POST /api/exams/{id}/publish` sets ACTIVE also when the start time is still ahead (the exam cannot be started before it, but it is no longer editable); making it SCHEDULED would delay the opening by up to a minute because the scheduler activates once a minute. Checked again 2026-10-14: a start before `startTime` is 400 `EXAM_NOT_ACTIVE` and opens no session; `/available` lists the exam with `canStart=false`
- [ ] The V8 backfill marked 8 old open-ended answers (8 sessions of test exams 1, 2, 37, 38, 43, 68, 70, 88) for manual grading: their participants see no score until someone grades them
- [ ] Load-test data from `feat/results-grading` is still in the development database: exam `T-b3-load` with 5000 sessions (`session_id LIKE 'T-b3-load-%'`, spread over the existing test users, so their "my sessions" lists are long) and 4166 fraud events (`details = 'T-b3-load'`)
- [ ] Results sorted by name are sorted in the service (names live in auth-service): all matching rows are read and named in batches of 500; refused above 10 000 rows
- [ ] The internal user search has `text_pattern_ops` indexes for first and last name (auth V1), but none for the full name (`first || ' ' || last`) and none for the email prefix other than the unique `lower(email)` index; check its plan before the user table grows large
- [x] `FraudDetectionService` (in-memory counters, never called) should be deleted (done): fraud events are stored by `FraudEventService` and scored by `FraudRiskPolicy`
- [ ] `ExamCacheService` is an in-memory map although its name and the old comments suggest Redis: with several exam-service instances the cached exam and the questions of a session differ per instance
- [x] The gateway's rate limiter keeps one Redis bucket per client IP for all routes (now: one bucket per route and client address, and `/auth/register` and `/auth/login` have routes and capacities of their own: 300 registrations from one address in 67 s without a 429), although the routes have different capacities (`/auth/verify-otp` 50 with 5 tokens per request, `/auth/**` 200): a drained verification budget also refuses `/auth/register` and `/auth/login` for that IP
- [ ] `/internal/**` answers 404 without the shared secret (by design, like an unknown path), not 401/403
- [ ] Monitoring is polled (3 second cache); there is no push over the websocket service, and no action (stop a session, warn a participant)
- [ ] Analytics are up to 60 seconds old and are not invalidated by a grading or a new session (`generatedAt` says how old)
- [ ] With auth-service down, lists with names and the platform overview wait 2 seconds for it on every uncached call
- [ ] `feat/monitoring-analytics` was verified only against the running stack (no automated tests)
- [ ] Test data of `feat/monitoring-analytics`: exams `T-b4-*`, accounts `be-b4-*@x.test` (ten `be-b4-p01..p10-6ace3b5f@x.test` come from a run that stopped at the registration limit and have no session)
- [ ] Results and grading show the participant's full email to ADMIN and the owning INSTRUCTOR, while group member lists mask it: decide one rule
- [ ] Users that are inactive in auth-service have no name or email in result lists (the internal lookup returns active users only)
- [ ] `feat/results-grading` was verified only against the running stack (no automated tests): grading, results list, summary, review
- [ ] Grading can be changed forever, also after the participant has seen the score; there is no "final" state and no notification to the participant
- [ ] `exam_sessions.total_points` holds the maximum score and `score` the earned points (the names suggest otherwise)
- [ ] Status `CANCELLED` exists but no endpoint sets it; a cancelled exam cannot be rescheduled
- [ ] The per-IP rate limit bucket of exam-service is created from the first path that IP calls (start: 10, session: 200, other: 1000 per window) and then used for every path of that IP
- [x] A service started without a required setting failed only at runtime: `scripts\check-env.ps1` checks `.env` against the compose file and `scripts\up.ps1` runs it before `docker-compose up -d`. A missing `OTP_HMAC_SECRET` is generated with `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)` and written to `.env` as `OTP_HMAC_SECRET=<value>` (never committed)
- [x] Application services did not come back after a Docker restart (`restart: unless-stopped` was set for Postgres, Mailpit and the media storage only)
- [ ] An old copy of the project in another folder shares the compose project name `exam-system`, the container names and the volume names: `docker-compose up` there recreates this stack's containers with the old settings (2026-10-13: auth-service and the gateway failed to start). Remove or rename that copy, or give it another project name
- [ ] `scripts\check-env.ps1` reads `.env` only; variables set in the shell override `.env` for docker-compose and are not checked
- [ ] `exam-grafana` and `exam-prometheus` containers from an earlier compose file are left as stopped orphans
- [ ] The VS Code Java extension compiles into `target/classes` without Lombok; `.\mvnw.cmd package` without `clean` can pack those classes and the service then fails at start with "Unresolved compilation problems" (2026-10-14). Always build with `clean package`
- [x] `api-gateway/src/main/resources/application-production.yml` does not route `/api/admin/participants/**`, `/api/join/**` and `/api/analytics/**` (the development file does); `/api/admin/audit/**` and `/api/grading/**` were added to both (now: the same routes in both files, checked by `scripts\check-gateway-routes.ps1`; `scripts\check-gateway-production.ps1` sends one request per frontend path through a production-profile gateway)
- [ ] The gateway circuit breaker of exam-service opens for 10 s when calls fail during a restart (the per-IP bucket is now per route: a browser on the same host no longer drains the auth routes of a script, but the exam route of that address is still shared): live checks must wait for five good calls and repeat a 429
- [x] The production profile of the gateway routed nothing: its `lb://` routes had no load balancer and no instances, every request ended in the circuit breaker fallback (now: `spring-cloud-starter-loadbalancer` with the instances of `gateway.services` = `AUTH_SERVICE_URL`, `EXAM_SERVICE_URL`, ...; 2026-10-15)
- [x] The gateway fallback answered 200 with an error text, and 405 to every POST (now: every method, 503 with `Retry-After` and the code `SERVICE_UNAVAILABLE`; the field `error` is an object now, as in the other errors)
- [x] CORS: production allowed every origin with credentials, development every origin; `X-Total-Count` and `Content-Disposition` were not exposed (now: `CORS_ALLOWED_ORIGINS`, required in production, localhost by default in development; `X-Total-Count`, `Content-Disposition`, `Retry-After` exposed)
- [ ] `/auth/verify-otp` and `/auth/resend-otp` stay at 10 requests at once and then one every 5 seconds per address: a hall of 1 000 people who verify their codes through one address needs more than an hour. Raise it per site with care, or move the code check to a per-email limit only (it already has one: 5 attempts, 60 s cooldown)
- [ ] The load balancer of the production gateway takes turns over the configured instances without health checks: a stopped instance gets every n-th request until the circuit breaker opens
- [ ] `docker-compose.production.yml` now requires `CORS_ALLOWED_ORIGINS` and `GATEWAY_TRUSTED_PROXIES`; the address of nginx in the compose network is not fixed, so the trusted range is the network's subnet (set it, or give the network a fixed subnet)
- [ ] `docker-compose.production.yml` is not a complete stack: Postgres and Redis published on all interfaces, `latest` images for Prometheus and Grafana, nginx has no route for presigned media (`/exam-media/`), its own database volume (`exam-system-prod_postgres_data`). (Done 2026-10-15: media storage with its own volume, `REDIS_PASSWORD` required, health checks on every service, its own project name `exam-system-prod` so it can never take over the development containers.) The public mode (`docker-compose.public.yml`) is the stack that was actually run with the production profile (2026-10-15); a server setup should start from it
- [ ] The development stack (`docker-compose.yml`) publishes Postgres (5432), Redis (6379, no password), the gateway and every service (8080-8084) on all interfaces: they answer from the LAN (checked 2026-10-15). The public mode closes them; development should bind them to 127.0.0.1 as well
- [x] exam-service could not use a Redis with a password: `PerformanceConfig` built its own connection factory without `spring.data.redis.password`, so every Redis call failed with NOAUTH and the service never turned healthy behind a protected Redis (now: `REDIS_PASSWORD` is used when set; found when the production profile first ran as a whole stack, 2026-10-15)
- [x] SeaweedFS listened only on its first network address; a second network (edge) got "connection refused" (now `-ip.bind=0.0.0.0` in the public mode; nothing is published on the host)
- [ ] The production profile of exam-service sets `spring.cache.type: redis` while the base file excludes the Redis auto-configuration; it does no harm today (no `@Cacheable`), but it is dead configuration
- [x] The gateway's OTP route had fixed capacities (now `GATEWAY_OTP_REPLENISH` / `GATEWAY_OTP_BURST`, defaults unchanged 1 / 50)
- [ ] Public mode (quick tunnel): the address changes on every start, no SLA, one home computer; for real exams a server with a fixed domain is needed (docs/PUBLIC_TEMP.md, "Serverə keçid")
- [x] `docker-compose.performance.yml` was not valid (`container_name` together with `replicas`) and could not have run anyway: Dockerfiles in service folders that do not exist, profile `prod` that does not exist, wrong ports for monitoring and websocket, no `OTP_HMAC_SECRET`, nginx pointing at the production gateways, and the same project and container names as the development stack (now: one instance per service, project `exam-system-perf`, containers `exam-perf-*`, ports on 127.0.0.1 only; 2026-10-15)
- [x] Containers `exam-grafana` and `exam-prometheus` (stopped since 2026-01-26, created from an old copy of the project in `Desktop\Yukselis crusor\...`, not part of any compose file here) removed with `docker rm` on 2026-10-15. Their volumes `exam-system_grafana_data` and `exam-system_prometheus_data` were kept; they hold only old metrics and dashboards, deleting them is an owner's decision
- [ ] exam-service has no rate limit of its own; only the gateway limits (per route and address). Not done in B8 because it is more than a short change: behind the gateway every request reaches exam-service from the gateway's address, so a limit per address would put all users in one bucket (a mass join on `/api/join/{code}` would be refused); it needs a key per JWT user (and the gateway's X-Forwarded-For only from the gateway, the logic of `ClientIpResolver` a second time), and with three exam-service instances in production an in-memory limit counts per instance (Redis would be shared, but adds a Redis call to the participant path, which `ReadCache` avoids on purpose). Direct calls are closed by the network instead: the public mode and docker-compose.production.yml publish no exam-service port; only the development stack does (0.0.0.0:8082, see the item on development ports)
- [ ] Public mode: video uploads above 100 MB fail at Cloudflare (the media policy allows 200 MB)
- [ ] `exam-frontend`: `/yukselis-logo.svg` (favicon in index.html) does not exist (no `public/` folder), every page load gets a 404 for it
- [ ] `scripts\check-gateway-routes.ps1` runs only by hand (no CI)
- [ ] `scripts\cleanup-test-data.ps1` exists and was tried on a restored copy only; it has not been run on the development database (dry run there: 40 577 users, 139 exams, 62 groups, 5 204 sessions to delete; 2026-10-15). Older test data that does not follow the rules (exams `R*`, `M-*`, accounts `@example.com`) is not found by it
- [ ] Test data of `fix/gateway-hardening`: 301 accounts `t-b6-6acfabc6-*@x.test` (300 registered in the rate limit check, unverified), `t-b6-adm2-*@x.test` (ADMIN for one check, STUDENT again), groups `T-b6-*-par*` with 301 members each
- [ ] `verify-b5.ps1` adds 20 000 synthetic participants on every run (the development database has 40 000 since 2026-10-14), and `verify-b4-2.ps1` checks sessions of 2026-10-13 that are no longer running: both are one-off scripts, not repeatable regression tests
- [x] `verify-b2.ps1`, `verify-b3.ps1` and `verify-b4.ps1` make one `be-*@x.test` account ADMIN by SQL on every run and never give the role back; `cleanup-test-data.ps1` keeps every ADMIN, so these accounts survive the cleanup (12 of them by 2026-10-15, set back to STUDENT by hand with their refresh tokens revoked). (now: scripts\regression\, a `finally` block gives the three test accounts STUDENT back whatever happens)
- [x] `verify-b4.ps1` "types, order and maximum points" fails on every run although the API is right: `[decimal]$_.maxPoints` keeps the scale of the JSON value (`2.00`), the expected text has `2` (API and database both give `0:MULTIPLE_CHOICE:2.00 ... 4:OPEN_ENDED:4.00`; checked 2026-10-15). A script error; the comparison needs a fixed format such as `'{0:0.##}'`
- [x] `verify-b6-buckets.ps1` "verify-otp itself still 429" depends on timing and on the state of the bucket: the OTP route refills 1 token per second and a request takes 5, so one request passes again every 5 seconds; refused (429) requests take no tokens, so the 20-odd refused calls of step 1 plus register and login (about 1.8 s) are often 5 seconds, and the next verify-otp answers 400 (wrong code) instead of 429. Step 1 is not deterministic either: verify-otp calls of other scripts from the same address (b5-join, b7) leave the bucket part-empty (2026-10-15: `400x8 429x22`; earlier `400x2 429x28`). The gateway does what its settings say; the check needs the refill taken into account (or a lower GATEWAY_OTP_REPLENISH for the test)
- [x] Regression scripts, fixed on fix/regression-scripts-2 (all script errors, none an application error; not run yet because the public mode was up, so no before/after run): every `verify-*.ps1` waits for the gateway (`_common.ps1` Wait-Gateway); verify-b9 and verify-b10 created exams with a start in the past, which POST /api/exams refuses on purpose (@Future), now `New-ActiveExam` (create ahead, publish, reschedule); verify-b9 expected the phone as typed, the API stores E.164; verify-b7 expected the CSV header to end with `Risk seviyyesi`, B10 added `Istirakci kodu` last; verify-b4 maximum points as numbers; verify-b6-buckets waits for a full bucket and tolerates the refill
- [ ] Group names are unique over all active groups (`uq_user_groups_name`), so an INSTRUCTOR who picks the name of another owner's group gets 409 `GROUP_NAME_EXISTS` and learns that the name exists (a small leak: the name only, no id, members or owner; everything else of another owner's group answers 404 like a missing group). Closing it needs names unique per owner (a new migration and the ADMIN view of same-named groups); checked by `verify-b8.ps1` as a NOTE (2026-10-15)
- [ ] `feat/admin-api-gaps` was verified only against the running stack (no automated tests)
- [ ] Test data of `feat/admin-api-gaps`: groups and exams `T-b7-*`, accounts `be-b7-*@x.test` (two of them INSTRUCTOR by SQL, one with the first name `=SUM(1+1)` for the CSV injection check), one deleted exam `T-b7-*-e4` per run

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
- [ ] Password reset: `POST /auth/forgot-password` `{email}` (always the same 200), then `POST /auth/reset-password` `{email, code, newPassword}` (200, or 400 `OTP_INVALID` for every failure); all sessions end, the user logs in again
- [ ] Registering an address that is still unverified answers 201 like a new one (409 only for a verified address)
- [ ] Exam create/update accept `accessType` and `groupIds` (GROUP without groups is 400 `AUDIENCE_GROUPS_REQUIRED`); instructors pick groups from `GET /api/groups` (`id`, `name`, `?name=&page=&size=`)
- [ ] Group members carry `firstName`, `lastName` and a masked `email`; pending invites: `GET /api/admin/groups/{id}/invites`, cancel with `DELETE /api/admin/groups/{id}/invites/{inviteId}` (204)
- [ ] `POST /api/exams/{id}/start` needs no body; 409 `EXAM_SCHEDULING_CONFLICT` no longer exists
- [ ] Media upload (ADMIN, INSTRUCTOR): `POST /api/media/upload-url` `{kind, contentType, sizeBytes, altText}` -> `{mediaId, uploadUrl, method, headers, expiresIn}`; PUT the file to `uploadUrl` with exactly `headers` (direct to the storage, no Authorization header); then `POST /api/media/{id}/confirm`. Errors: 400 `MEDIA_TYPE_NOT_ALLOWED` / `MEDIA_TOO_LARGE` / `MEDIA_INVALID`, 409 `MEDIA_NOT_UPLOADED` / `MEDIA_PENDING_LIMIT`, 503 `MEDIA_STORAGE_UNAVAILABLE`
- [ ] `GET /api/media/{id}/preview-url` -> `{url, kind, expiresIn}`; `DELETE /api/media/{id}` (409 `MEDIA_IN_USE` while a published exam uses it)
- [ ] Exam create/update: question `media: [{mediaId, position, maxPlays}]` (max 3), option `mediaIds: [id]` (max 1, image); 400 `MEDIA_NOT_ATTACHABLE`. Responses carry `media: [{id, kind, altText, position, maxPlays, previewUrl}]` on questions and options; `previewUrl` only for owner and ADMIN and valid 5 minutes
- [ ] Taking an exam: questions and options list their media without URLs; fetch `GET /api/exams/session/{sessionId}/media/{mediaId}` -> `{url, kind, expiresIn, remainingPlays}` when the media is shown or played (409 `MEDIA_PLAYS_EXHAUSTED`, 409 `SESSION_COMPLETED`, 410 `SESSION_EXPIRED`, 404 otherwise). Ask for an audio/video URL only when the user presses play: every request counts
- [ ] The alt text of option images is visible to participants: the editor must warn that it may not contain the answer
- [ ] Exam create/update/response carry `maxAttemptsPerDay` (1..100, absent or null = no daily limit; on update omitted = unchanged, explicit null = removed); `maxAttempts` may be up to 100
- [ ] Start can answer 409 `DAILY_ATTEMPTS_REACHED` with `error.details.nextAvailableAt` (UTC, `Z`); `MAX_ATTEMPTS_REACHED` stays for the total limit
- [ ] `/available` rows carry `remainingAttemptsToday` (absent = no daily limit) and `nextAttemptAt` (only when today is used up); `remainingAttempts` is now what can be started right now (total and daily limit), so it can be 0 while attempts are left for another day
- [ ] `POST /api/exams/{id}/reschedule` `{startTime?, endTime, maxAttempts?, maxAttemptsPerDay?}` -> the exam without questions; 400 `EXAM_CANNOT_RESCHEDULE` for a draft, 400 `EXAM_INVALID_TIME_RANGE`, 400 `VALIDATION_ERROR` for an end time in the past
- [ ] `DELETE /api/exams/{id}` removes for good; an exam with sessions answers 409 `EXAM_CANNOT_DELETE` (was 400): offer "archive" instead
- [ ] `GET /api/exams/manage?search=&status=&page=&size=` (ADMIN all, INSTRUCTOR own; archived only with `status=ARCHIVED`; search needs 2 characters): rows with `sessionCount`, `participantCount`, `totalPoints`, without questions
- [ ] auth-service dates (`createdAt`, `lockedUntil`, `timestamp`) now end with `Z`
- [ ] Submit, result and `my-sessions` carry `gradingStatus` (`NOT_REQUIRED`, `PENDING`, `COMPLETE`); with `PENDING` there is no score and no `resultsAvailableAt`: show "waiting for grading"
- [ ] Grading UI: `GET /api/exams/{examId}/grading?status=PENDING|COMPLETE|ALL&q=&page=&size=`, `GET /api/exams/session/{sessionId}/grading`, `PUT /api/exams/session/{sessionId}/grades` with `[{answerId, points, feedback?, expectedVersion}]` (send back `gradeVersion`; 400 `GRADE_INVALID`, 409 `GRADE_CONFLICT` with `error.details.currentVersion`, 409 `SESSION_NOT_COMPLETED`)
- [ ] Results: `GET /api/exams/{id}/results` rows now also carry `participantName`, `email`, `attemptNumber`, `startedAt`, `durationSeconds`, `maxScore`, `gradingStatus`, `violationCount`, `riskLevel`; new parameters `q`, `passed`, `gradingStatus`, `hasViolations`, `bestOnly`, `sort` (`score|name|violations|submittedAt`), `order`. `bestOnly` defaults to true as soon as one of the new parameters is sent; a call with only `page`, `size`, `status` lists every session as before
- [ ] `GET /api/exams/{id}/results/summary` and `GET /api/exams/session/{sessionId}/review` (answer key, grading, media with `previewUrl`, fraud events)
- [ ] `POST /api/exams/{id}/publish` can answer 400 `EXAM_WINDOW_ENDED`: offer reschedule (or editing, for a draft)
- [ ] Live monitoring (ADMIN, INSTRUCTOR for own exams): `GET /api/exams/monitoring/active`, `GET /api/exams/{examId}/monitoring?q=&riskLevel=&sort=risk|violations|startedAt|name|progress&order=&page=&size=` (header figures + `sessions` page), `GET /api/exams/session/{sessionId}/violations?sinceId=&limit=` (poll with the last id seen). Poll no faster than every 3 seconds: answers are cached that long
- [ ] Exam analytics: `GET /api/exams/{examId}/analytics/questions` (rates 0..1, `difficulty`, `discriminationIndex`, `options`, `topAnswers`, grading figures) and `/analytics/overview` (percentages 0..100, `scoreDistribution`, `sessionsPerDay`); show `generatedAt`
- [ ] Platform analytics (ADMIN): `GET /api/analytics/overview?days=`; handle `usersAvailable=false` (`users` is null)
- [ ] Participant administration (ADMIN): `GET /api/admin/participants?q=&groupId=&noGroup=&verified=&blocked=&registeredFrom=&registeredTo=&category=&institution=&sortBy=&dir=&page=&size=` (sortBy: code, firstName, lastName, email, registeredAt; dir: asc or desc; the older sort/order still work; rows carry the first three groups and `groupCount`) and `/api/admin/participants/summary`. `registeredFrom`/`registeredTo` are days (yyyy-MM-dd) in the exam day zone, both inclusive
- [ ] Mass membership (ADMIN): `POST /api/groups/{id}/members/bulk-by-filter` `{filter, dryRun, expectedCount}` and `/bulk-by-ids` `{userIds (max 1000), dryRun}`. Always run the dry run first and send its `willAdd` back as `expectedCount`; handle 409 `FILTER_COUNT_CHANGED` (show the new figures) and 422 `TOO_MANY_MATCHES` (cap 20 000)
- [ ] Join link (ADMIN): `GET/POST/PATCH /api/groups/{id}/join-link` (POST replaces the code), `GET /api/groups/{id}/join-requests?status=`, `POST .../join-requests/approve|reject` `{requestIds (max 500)}` and `POST .../join-requests/{requestId}/approve|reject`
- [ ] Joining (participant): the invite URL carries the code; `GET /api/join/{code}` works without a token (group name only) and `POST /api/join/{code}` needs one. Keep the code across registration and verification and post it afterwards. Answers `{status: MEMBER|PENDING, groupName, changed}`; 404 `JOIN_CODE_INVALID` for every unusable code, 403 `EMAIL_NOT_VERIFIED`, 403 `JOIN_PARTICIPANTS_ONLY`
- [ ] `GET /api/exams/{id}/audience/preview?groupIds=a,b,c` before saving an audience: members per group, `distinctEligible`, `unknownGroupIds`
- [ ] For a new exam (no id yet): `POST /api/exams/audience/preview` `{groupIds}` (max 50) gives the same answer; `examId`/`accessType` are null there
- [ ] Group list rows (and `GET /api/admin/groups/{id}`) carry `memberCount`, `pendingInviteCount`, `pendingJoinRequests`, `examCount` and `joinLink {exists, enabled, usable, requiresApproval, uses, maxUses}`: no call per row and no 404 for a group without a link
- [ ] `GET /api/admin/groups/{id}/exams?page=&size=` (id, title, status, startTime, endTime, accessType); `GET /api/admin/groups/{id}/members?q=` (2..100 characters, sorted by name, source and addedAt per row)
- [ ] `GET /api/exams/manage` rows and `GET /api/exams/{id}` (ADMIN, owner) carry `audienceGroupCount` and `groupIds`
- [ ] Results: `riskLevel=LOW|MEDIUM|HIGH|CRITICAL`, `minPercent`, `maxPercent` (0..100), `sort=risk`; rows carry `submitKind` (`MANUAL`, `AUTO_EXPIRED`, null while running). `GET /api/exams/{id}/results/export` with the same parameters downloads a CSV (UTF-8 with BOM, comma, `X-Total-Count`); 422 `RESULTS_EXPORT_TOO_LARGE` above 50 000 rows
- [ ] Grading overview: `GET /api/grading/pending-exams?page=&size=` and `GET /api/grading/pending-count` (`{sessions, answers}`, also for INSTRUCTOR, cached 10 s) replace the platform overview for the menu badge
- [ ] Groups for INSTRUCTOR: the group pages (`/api/admin/groups/**`, join link, requests, additions by ids, CSV import) now answer an INSTRUCTOR for the own groups (another owner's group is 404); the admin menu shows them to ADMIN only so far. Rows carry `ownerId`. Adding by filter stays ADMIN only
- [ ] Participant cabinet: `GET /api/join/requests/mine` (groupName, status PENDING/APPROVED/REJECTED, createdAt, decidedAt, canRequestAgainAt); joining can answer 409 `JOIN_REQUEST_COOLDOWN` with `error.details.canRequestAgainAt`
- [ ] Audit list (ADMIN): `GET /api/admin/audit?action=&actorId=&from=&to=&q=&source=EXAM_SERVICE|AUTH_SERVICE&page=&size=` (from/to ISO UTC; 10 000 rows deep)

## Later

Deliberately out of scope so far; not started.

- [ ] Group CSV import by participant code (B10 left it out on purpose): the email stays the key of the import; a `code` column (7 digits, exact match, an unknown code reported per row like an unknown email) would need its own lookup in auth-service and the same audit as the email import

- [ ] Organizations (several companies on one installation)
- [ ] Programs (a set of exams a participant goes through)
- [ ] Mentorship applications
- [ ] Payments
- [ ] Per-company branding
- [ ] Enrollments with per-user extra time
- [ ] Join codes for groups or exams
- [ ] Video transcoding and adaptive streaming (HLS)
- [ ] Thumbnails and poster images for media
- [ ] Virus scanning of uploads
- [ ] CDN in front of the media storage
- [ ] Audio and file answers by participants
- [ ] Media library: list, search and reuse of uploaded media across exams
- [ ] S3 lifecycle rules (automatic expiry of unconfirmed uploads and old versions)
- [ ] Managed S3-compatible storage in production
- [ ] Password set (or confirmed) at OTP verification, which closes the remaining pre-hijack gap
- [ ] `POST /api/exams/{id}/start` answers 415 for an empty body that is labelled `application/x-www-form-urlencoded`
- [ ] Plan for removing test data from the development databases and the media bucket (test accounts `@x.test`, `T-...`/`M-...` groups and exams, test media)

## Gələcək (future: Kubernetes, not started)

Plan only, nothing of it is code yet. Target: the services on 4-5 servers with Kubernetes (k3s); the
data outside the cluster.

- [ ] **Order.** First **D** (servers, a domain, TLS, backups kept off the server) and **T2** (load test of
  the target: thousands of starts at once, 100 000 accounts); K8s only after both, with their figures
- [ ] **Data outside the cluster.** PostgreSQL, Redis and the media storage (SeaweedFS, or any S3) run on
  their own data servers (or managed PostgreSQL), not in Kubernetes: no database volume ever depends on a
  node or on a cluster rebuild; backups and restore tests (`scripts\backup.ps1`, `restore-test.ps1`)
  carry over to those servers
- [ ] **K8s-1: manifests.** Deployments for api-gateway, auth-service, exam-service and the frontend (nginx);
  readiness and liveness probes on `/actuator/health`; resource requests and limits; HPA for exam-service
  and the gateway (CPU and request rate); PodDisruptionBudget (at least one instance of every service while
  a node is drained); secrets from a secret store, never in a manifest; ingress with TLS; a staging namespace
  with its own database that runs `scripts\regression` before every release
- [ ] **K8s-2: what to do when things go wrong.** A written runbook: a node is lost, a rollout fails
  (rollback), the database server is lost (restore from the off-server backup, how long it takes), Redis is
  lost (sessions and rate limits start empty, what users see), the media storage is lost, a certificate
  runs out; who is called, in which order
- [ ] **Open before K8s-1:** the scheduled jobs of exam-service (activation, completion, auto-submit) run on
  every instance and rely on conditional updates (AUDIT_GAP §6.7): measure under T2 whether a shared lock is
  needed; websocket-service and monitoring-service are stubs and need not be deployed
