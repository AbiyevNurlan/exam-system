# Project rules

Rules for everyone (and every AI assistant) changing this repository. Open work is tracked in
[BACKLOG.md](BACKLOG.md).

## Absolute rules: data must never be lost

Never run any of these against a database that holds data:

- `docker-compose down -v`, `docker volume rm`, `docker system prune`
- `DROP DATABASE` on `exam_auth`, `exam_db` or `exam_monitoring`
- an edit, rename or delete of a Flyway migration that has already been applied

Uploaded media is data like the database: never remove the media volume (`exam-system_media_data`), never
delete or empty the `exam-media` bucket by hand. `backup.ps1` and `restore-test.ps1` cover it.

Schema changes are made only by adding a new migration (`V2__description.sql`, `V3__...`) under
`<service>/src/main/resources/db/migration/`. Migrations must be additive and backward compatible:
new columns are nullable or have a default, nothing that existing rows or the previous build depend
on is dropped or renamed in the same release.

Before the first Docker rebuild of a task run `scripts\backup.ps1`; at the end run it again and then
`scripts\restore-test.ps1` (every table must PASS). Rebuild with `docker-compose up -d --build`.
Details: README, section "Database safety".

## Stack

- Java 21, Spring Boot 3.2 on WebFlux (reactive), Spring Security with JWT
- Spring Data R2DBC on PostgreSQL 16; Flyway (over JDBC) owns the schema
- Redis, Spring Cloud Gateway, Maven multi-module build (`.\mvnw.cmd`), Docker Compose
- Modules: `common`, `api-gateway` (8080), `auth-service` (8081), `exam-service` (8082),
  `monitoring-service` (8083), `websocket-service` (8084)

## Conventions that are easy to get wrong

- **Roles** are exactly `ADMIN`, `INSTRUCTOR`, `STUDENT`. There is no TEACHER role.
- **Registration always creates a STUDENT.** Other roles are assigned by an admin or the bootstrap
  admin from the environment, never by the registering client.
- **Registration is complete only after the email code (OTP) was verified.** `register` creates an
  unverified STUDENT and returns no tokens; tokens are issued by `verify-otp`, and `login` of an
  unverified account is 403 `EMAIL_NOT_VERIFIED` (only after the password was accepted). Nothing
  but `verify-otp` and the admin `verify-email` endpoint sets `email_verified`.
- **One-time codes** come from `SecureRandom`, are stored only as HMAC-SHA256 (`OTP_HMAC_SECRET`),
  are single use, and are never logged, returned or put into an error message. Their limits (TTL,
  wrong attempts, resend cooldown, sends per hour; `auth.otp.*`) are enforced in SQL by `OtpService`.
- **Endpoints that take an email without a session answer the same for every outcome**
  (`resend-otp`: always the same 200; `verify-otp`: always the same 400).
- **Registering an email whose account is still unverified takes that account over**: password and
  name are replaced, every older code is invalidated and the answer is the same 201 as a new
  registration. Only a verified email is a 409. Failed logins never count against or lock an
  unverified account (wrong password 401, right password 403 `EMAIL_NOT_VERIFIED`).
- **Password reset is by emailed code** (`forgot-password`, then `reset-password` with
  `{email, code, newPassword}`; OTP purpose `PASSWORD_RESET`, the same `OtpService` limits), for
  verified accounts only. Success ends every session (all refresh tokens revoked) and clears the
  lockout. `forgot-password` is always the same 200, `reset-password` always the same 400.
- **Email leaves the system only through `EmailSender`** (plain SMTP, no provider API), never on a
  reactive thread, and a failed send never fails the request that caused it.
- **`@Transactional` does not work at runtime in these services.** When several writes must be
  atomic, wrap them with `TransactionalOperator`. Do not add `@Transactional`; it only misleads.
- **User ids are UUIDs** (`users.id` in `exam_auth`): JWT subject, `UserPrincipal.id`,
  `exams.created_by`, `exam_sessions.user_id`. Never derive a numeric id from them. Exam, question
  and session row ids are `Long`.
- **Pagination**: page is 0-based, default size 25 (`my-sessions`, `manage`: 20), maximum size 50 (larger values are clamped).
- **Time: UTC in the database, `Z` in the API.** Columns are `TIMESTAMP` without zone and hold UTC;
  every container runs with `TZ=UTC` and every JVM with `-Duser.timezone=UTC` (set in all compose
  files). exam-service and auth-service write every date-time as ISO-8601 UTC with milliseconds and `Z`
  (`2026-10-13T07:59:31.099Z`) and read a value with an offset (`Z`, `+04:00`) as that instant; a
  value without an offset is taken as UTC (`UtcDateTimeModule`, registered by each service's
  `UtcJsonConfig`). Keep the fields `LocalDateTime`; never format a date by hand and never convert
  with the machine's zone.
- **Attempt limits are decided by the statement that opens the session**
  (`ExamAttemptRepository.openSessionIfAttemptsRemain`, under a per user-and-exam advisory lock):
  the total (`max_attempts`, 1..100) and the daily one (`max_attempts_per_day`, 1..100, NULL = none).
  Every session counts, whatever its status; resuming a running session does not. A day is a
  calendar day in `EXAM_DAY_ZONE` (default `Asia/Baku`, `ExamDayClock`) and is the only place where
  a zone other than UTC is used. 409 `MAX_ATTEMPTS_REACHED` when the total is used up, otherwise
  409 `DAILY_ATTEMPTS_REACHED` with `error.details.nextAvailableAt`. Never check a limit in Java
  before the insert.
- **Exam life cycle.** DRAFT -> (publish) ACTIVE -> COMPLETED by the scheduler; ARCHIVED hides an
  exam from participants and from the default management list and keeps every attempt and result.
  `POST /api/exams/{id}/reschedule` opens a SCHEDULED, ACTIVE, COMPLETED or ARCHIVED exam again with
  a new window (SCHEDULED when the start is ahead, otherwise ACTIVE), touches only the exam row and
  always writes `group_audit_log` (`EXAM_RESCHEDULED`). The scheduler changes a status only with a
  conditional update (`activateIfDue`, `completeIfEnded`), so it never overrules a reschedule.
- **Delete is for exams nobody started.** `DELETE /api/exams/{id}` removes the exam for good with
  its questions, options, media links and audience (one statement, `ON DELETE CASCADE`) and writes
  an audit row; with a single session it answers 409 `EXAM_CANNOT_DELETE` and the exam is archived
  instead. Media files are never deleted from here: unused ones go with the orphan cleanup.
- **What may be attached when an exam is saved**: the editor's own media, for ADMIN everybody's, and
  whatever the exam already carries, whoever uploaded it (`ExamMediaService.validate`).
- **A participant can always read back what they stored**: `GET /api/exams/session/{id}/answers` and
  `savedAnswers` in the start response return the answers as given (own session only), never
  correctness, points or the key. `GET /api/exams/my-sessions` lists only the caller's sessions.
- **Whether a participant may see a score is decided by one method**
  (`AnswerSubmissionService.resultsWithheld`), used by submit, the result endpoint and
  `my-sessions`. Do not repeat that condition anywhere else. A session whose `grading_status` is
  PENDING never shows a score (and no `resultsAvailableAt`); all three carry `gradingStatus`.
- **Open-ended answers are graded by a person.** At submit an OPEN_ENDED answer with text gets zero
  points, no verdict and `needs_manual_review = true`, and the session becomes PENDING
  (`exam_sessions.grading_status`: NOT_REQUIRED, PENDING, COMPLETE); FILL_BLANK stays automatic.
  Grading (`GradingService`, `PUT /api/exams/session/{id}/grades`) is all-or-nothing per request,
  only for a COMPLETED session, guarded by the answer's `grade_version` (409 `GRADE_CONFLICT`,
  never a silent overwrite), and writes one `group_audit_log` row per answer (`ANSWER_GRADED` /
  `ANSWER_REGRADED`, old and new points). The session's `score`, `percentage` and `passed` are
  recalculated by one SQL statement (`GradingRepository.recalculateSession`); `total_points` (the
  maximum) is never changed after completion. Never write a score of a session from Java.
- **Results for the managers of an exam** (`ExamResultsService`, ADMIN and the owning INSTRUCTOR,
  `ExamAccessPolicy.findManageableExam`): `GET /api/exams/{id}/results`, `/results/summary`,
  `/api/exams/session/{id}/review` and the grading endpoints. A list page is a fixed number of
  statements however many sessions the exam has; names and emails come from auth-service with one
  lookup per page (`ParticipantDirectory`), and a text search asks auth-service first
  (`POST /internal/users/search`). The review carries the answer key and never goes to a participant.
- **The risk level of a session's fraud events comes from one class** (`FraudRiskPolicy`: weights
  per event type, boundaries `EXAM_FRAUD_RISK_MEDIUM/HIGH/CRITICAL`, default 5/15/30). It is
  informational: nothing is decided from it.
- **Monitoring and analytics are read only** (`MonitoringService`, `ExamAnalyticsService`,
  `PlatformAnalyticsService`): they never stop a session, warn a participant or write a row. A
  "running" session is IN_PROGRESS with its own end time still ahead. Every screen is a fixed
  number of statements however many sessions there are; names come from the `ParticipantDirectory`
  (one lookup per page, at most 2 seconds, missing names are left out). Score figures are over each
  participant's best completed attempt; sessions waiting for a grader are counted separately and
  never enter an average. Monitoring answers never carry an answer or the key.
- **`ReadCache` is the only Redis cache of exam-service** (monitoring 3 s, analytics 60 s; the
  answers carry `generatedAt`). Nothing is invalidated: a value is at most its TTL old. The access
  check runs before the cache and is never cached; the key spells out the exam, whose view it is
  and every parameter (never a hash of them). Redis is optional: no answer within 250 ms or any
  error means reading from the database. Never call it on a participant's path (start, answer,
  submit). `ExamCacheService` is a different thing: an in-memory map of exams and session questions.
- **Platform analytics get user figures from auth-service as numbers only**
  (`POST /internal/users/stats`); when it cannot be reached the answer says `usersAvailable=false`
  and is not cached.
- **Fraud events are only recorded.** `POST /api/exams/session/{id}/fraud-event` stores what the
  browser reports (own running session, at most 300 rows per session, 60 per minute); no score,
  status or penalty ever follows from them automatically, and participants cannot read them.
- **ADMIN manages every exam, INSTRUCTOR only the own ones** (`ExamAccessPolicy.canManage`): edit,
  publish, archive, reschedule, delete, and the list `GET /api/exams/manage` (one statement per
  page, never a query per row). The other limits stay for everyone (`EXAM_CANNOT_MODIFY`, no delete
  of an exam with sessions). An ADMIN acting on someone else's exam writes `group_audit_log`
  (`EXAM_*_BY_ADMIN`, target = the exam's creator) in the same transaction.
- **The answer key never reaches students.** `isCorrect`, explanations and the expected answers of
  text questions go only to ADMIN and the INSTRUCTOR who owns the exam. Results never contain
  per-question correctness.
- **Someone else's session is a 404**, the same as an unknown one, on every session endpoint.
- **Exam access is decided in one place: `ExamAccessPolicy`.** ADMIN and the INSTRUCTOR who owns the
  exam always; a PUBLIC exam every authenticated user; a GROUP exam only members of at least one of
  its active audience groups. Every endpoint that touches an exam asks the policy; lists filter in
  SQL with the policy's condition (`ExamRepository.ACCESS_CONDITION`), never in memory.
- **An exam the caller may not access is a 404 (`EXAM_NOT_FOUND`), never a 403**, exactly like an
  exam that does not exist. 403 is only for an exam the caller can see but may not manage.
- **Losing access blocks new starts only.** A session that is already IN_PROGRESS belongs to its
  user and can be continued, submitted and read after the user left the group.
- **Groups and memberships live in exam-service** (`exam_db`) and reference users by UUID. Every
  change writes `group_audit_log` in the same transaction.
- **Service-to-service endpoints are under `/internal/**`**, are not routed by the gateway and
  require the `X-Internal-Secret` header (`INTERNAL_SERVICE_SECRET`, at least 32 characters; without
  it they answer 404). Users are resolved only through auth-service, never by reading its database.
- **Group access through an email needs a verified email.** An unverified account never becomes a
  member by email; it stays a pending invite until `GroupInviteClient.applyForVerifiedUser` runs.
- **Media files never pass through a service.** Uploads and downloads go directly between the
  browser and the object storage with presigned URLs; the services handle metadata only, and the
  256 KB request body limit stays. All storage access is in `MediaStorage`.
- **An upload is usable only after `confirm`**, which trusts nothing the client said: the stored
  object must have the declared size and its first bytes must be the declared type (magic bytes,
  `MediaTypePolicy`). File names, extensions and declared content types are never trusted. A
  mismatch deletes the object and the asset; it must never leave an ACTIVE asset.
- **The media bucket is private and media leaves it only through signed URLs.** No public policy,
  no proxying. Storage keys are random UUIDs and never appear in a response; presigned URLs and
  storage keys are never logged and never cached.
- **Participants get media URLs only through their session**
  (`GET /api/exams/session/{sessionId}/media/{mediaId}`): own session, IN_PROGRESS and not expired,
  media attached to that session's exam; everything else is a 404 or the usual session error. Exam
  and question responses for participants carry media metadata and never a URL; `previewUrl` is
  only for ADMIN and the INSTRUCTOR who owns the exam.
- **Play limits are enforced on the server**, per session, in one atomic SQL statement
  (`MediaLinkRepository.registerPlay`); every URL handed out counts as a play. Never count in the client.
- **Alt text of media is shown to participants.** On an option image it must not reveal the answer.
- **Unused media is removed, used media never.** Confirmed files attached to no question and no
  option of any exam (drafts included) are deleted after `MEDIA_ORPHAN_RETENTION_DAYS` (default 14;
  0 or less = never). The removal locks the asset row and re-checks the links in the same
  transaction, and attaching (`MediaLinkRepository.attachTo*`) takes the conflicting row lock and
  requires `ACTIVE`: keep both sides, they are what makes it race-free.
- **Full email addresses are never logged** and never written to audit details; use `LogMaskUtils`.
- **Errors**: expected client errors are 4xx with a code and a generic message; responses never
  contain SQL, stack traces or other internals. 4xx are logged once at WARN, 5xx at ERROR.
- **Secrets** come from the environment (`.env`, git-ignored). Never commit or print them.

## Working agreements

- One topic per branch, small commits, no pushing without being asked.
- `AUDIT_REPORT.md` and the nested `exam-system/exam-system/` folder are not to be modified.
- Verify changes against the running stack (health on 8080-8084, the real HTTP flow), not only by
  compiling.
