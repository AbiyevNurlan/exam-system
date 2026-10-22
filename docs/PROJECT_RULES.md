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

Build the jars with `.\mvnw.cmd clean package` (with `clean`): an IDE that compiles into
`target/classes` without Lombok leaves classes there that make a service fail at start.

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
- **`ReadCache` is the only Redis cache of exam-service** (monitoring 3 s, analytics 60 s, grading
  overview 10 s; the analytics answers carry `generatedAt`). Nothing is invalidated: a value is at most its TTL old. The access
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
  exam that does not exist. B13: also an exam the caller can see but may not manage (a PUBLIC exam
  of another owner) is that 404 on every management endpoint (`ExamAccessPolicy.findManageableExam`
  and `findManageableSession`, the ownership in the SQL condition); `EXAM_NOT_OWNER` is gone. 403 is
  left for a role that may not use an endpoint at all.
- **Losing access blocks new starts only.** A session that is already IN_PROGRESS belongs to its
  user and can be continued, submitted and read after the user left the group.
- **An exam's audience is the union of its groups** (`exam_audience`, up to 50): somebody in two of
  them counts once, and joining a group later makes the exam visible and startable at once, with
  no change to the exam. `GET /api/exams/{id}/audience/preview?groupIds=…` says how many
  participants that would be before anything is stored (without `groupIds`: the stored audience).
- **Groups and memberships live in exam-service** (`exam_db`) and reference users by UUID. Every
  change writes `group_audit_log` in the same transaction. A membership says where it came from
  (`user_group_members.source`): ADMIN (one at a time), CSV (import or pending invite), BULK (a
  filter or a page selection), LINK (the group's join link).
- **Who manages a group is decided in one place: `GroupAccessPolicy`.** ADMIN every group, INSTRUCTOR
  only the groups they own (`user_groups.owner_id`, the creator; V12): the group, its members,
  invites, CSV import, additions by ids, join link and join requests (`/api/admin/groups/**`,
  `/api/groups/{id}/**`). Another owner's group is a 404 `GROUP_NOT_FOUND`, never a 403. Lists filter
  in SQL (`GroupAccessPolicy.ownerFilter`). Adding by filter is ADMIN only: a filter reaches every
  participant of the platform. An exam audience may use, for an INSTRUCTOR, the own groups and
  those already stored on the exam; the audience preview lists other groups as unknown.
- **Teachers are isolated from each other (B13).** What a teacher owns is decided by
  `OwnershipPolicy.ownerFilter` (ADMIN: no filter, INSTRUCTOR: the own id, anybody else: the nil UUID
  nobody owns) and always applied in SQL: exams, sessions, results, grading, monitoring, analytics,
  history, groups, members, invites, join links, media, the participant list and card, the audit
  trail, `/api/me/stats`. Another teacher's row is the same 404 and code as an id that does not exist,
  and nothing is written. Group names are unique per owner (exam V18), so a name tells nothing about
  somebody else's groups. A teacher adding a member by email always gets a pending invite (the same
  answer for a known and an unknown address); by participant code an exact match with a masked name,
  at most 10 a minute (`MEMBER_CODE_RATE_LIMITED`). `TeacherIsolationMatrixTest` is the matrix of two
  teachers on a real database.
- **A PUBLIC audience is for ADMIN only** (403 `PUBLIC_EXAM_ADMIN_ONLY`): a teacher's new exam is GROUP,
  a teacher's copy of a PUBLIC exam becomes GROUP.
- **Every endpoint has an access class** in `src/test/resources/endpoint-access.txt` of its service
  (ADMIN_ONLY, OWNER_SCOPED, OWN_DATA, STUDENT_ONLY, PUBLIC, INTERNAL). `EndpointRegistryTest` reads the
  endpoints through `RequestMappingHandlerMapping` and asks the real security chain with `@PreAuthorize`
  who gets through: a new endpoint needs its line, and its path rule and annotation must match the class.
- **Teacher accounts are made by ADMIN only** (`/api/admin/instructors`, auth-service; registration
  always makes a STUDENT). The temporary password (16 characters) is shown once, in the create or
  reset answer, never stored in clear or audited; it ends after 7 days (401
  `TEMPORARY_PASSWORD_EXPIRED`) and a token of it (`pwc` claim) works only for
  `POST /auth/change-password` and `POST /auth/logout` (403 `PASSWORD_CHANGE_REQUIRED`). Deactivation,
  a password reset and a password change end the access tokens at once
  (`auth:tokens-valid-after:<user>` in Redis); when Redis cannot be written the change is refused (503
  `TOKEN_REVOCATION_UNAVAILABLE`), never done half. Deactivating a teacher with ACTIVE or SCHEDULED
  exams needs `confirm=true`.
- **Teacher limits** (`instructor_limits`, exam V19; null = the default `exam.instructor-limits.*`: 50
  groups, 2 000 members, 200 exams, 1 GiB of media) are checked before anything is written (409
  `TEACHER_LIMIT_REACHED` with `{limit, max, used}`); lowering a limit removes nothing. ADMIN has none.
- **No service ever holds the whole user table.** The admin's participant list
  (`GET /api/admin/participants`, `ParticipantService`) is filtered, sorted and paged in
  auth-service's database (`POST /internal/users/participants`), a group filter is handed over as
  the set of member ids, and the group names of the page come from one statement in exam-service.
  Day filters are calendar days of `EXAM_DAY_ZONE` turned into UTC moments. Participant means a
  user with the role STUDENT; the email is not masked here, because the list is for admins only.
  B13: a teacher gets the same list limited to the members of the own groups (the ids handed over as
  `includeIds`), without the lock state, the admin filters (`blocked`, `noGroup`, profile) and other
  owners' group names; within 300 ms for 20 000 participants (`TeacherScopePerformanceTest`,
  `TeacherParticipantsPerformanceTest`).
- **Names sort in the Azerbaijani alphabet, on the server.** A paged list can only be sorted where
  it is paged: in SQL by `btrim(name) COLLATE az_ci` (auth V6, exam V15; never `lower()`, which turns
  I into i), in Java by `AzerbaijaniOrder`. Every ORDER BY of a page ends with a unique key
  (`participant_code`, an id), and a sort key comes from a whitelist, never from the request text.
- **Mass membership is explicit and idempotent** (`GroupBulkService`): a dry run reports
  `{matched, alreadyMembers, willAdd, cap}` and writes nothing; a real run by filter must carry the
  `expectedCount` the dry run reported (409 `FILTER_COUNT_CHANGED` otherwise) and refuses more than
  20 000 additions (422 `TOO_MANY_MATCHES`). The memberships are written in batches of 1000 inside
  one transaction with one audit row, with `ON CONFLICT DO NOTHING`, so repeating a request adds
  nobody twice.
- **A join link is the group's secret** (`GroupJoinService`): one link per group, a 10 character
  code from an alphabet without 0, O, 1 and I (50 bits of `SecureRandom`). Creating it again
  replaces the code and resets the uses. Anything wrong with a code — unknown, switched off,
  expired, used up, group deleted — is the same answer, 404 `JOIN_CODE_INVALID`, and the link
  itself only ever gives away the group's name. Joining is for a STUDENT with a **verified** email
  (checked against auth-service, not from the token), is idempotent, and takes turns on the locked
  link row, so `max_uses` is never exceeded. `requires_approval` turns a join into a request an
  admin approves. The code is never written to the audit log. A participant sees the own requests
  and their decision (`GET /api/join/requests/mine`; who decided is not shown). After a rejection
  the same user may ask that group again only after `GROUP_JOIN_REJECT_COOLDOWN_HOURS` (default 24,
  0 = at once): 409 `JOIN_REQUEST_COOLDOWN` with `canRequestAgainAt`, and the use is not counted.
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
- **Admin lists carry their figures in the same statement as the page.** The group list
  (`GroupOverviewRepository`) cuts the page first and adds members, invites, open join requests,
  exams and the join link state per row with lateral subqueries on their indexes; the grading
  overview (`GradingOverviewRepository`) is one aggregate statement. Never a call or a query per row,
  and never a separate 404 to learn that something does not exist (`joinLink.exists=false`).
- **Result exports follow the result list** (`ExamResultsService.export`): the same access rule,
  parameters and order, one statement read as a stream, names per block of 500. UTF-8 with BOM,
  comma separated, Azerbaijani headers, times in UTC; a cell starting with `=`, `+`, `-`, `@` (or a
  tab or carriage return) gets a leading apostrophe. More rows than `EXAM_RESULTS_EXPORT_MAX_ROWS`
  (default 50 000) is 422 `RESULTS_EXPORT_TOO_LARGE` before anything is sent, and every export
  writes `RESULTS_EXPORTED` to `group_audit_log` (who, exam, rows).
- **The audit list (`GET /api/admin/audit`, ADMIN) reads both audit tables**: `group_audit_log` here
  and auth-service's `admin_audit_log` through `POST /internal/audit/admin`, merged by time. A new
  audit table must join this list. Details are shown as written, so nothing secret may ever be
  written to them (join codes, OTPs, passwords, full emails); `AuditService.clean` masks
  `code=`/`otp=`/`token=`/`password=`/`secret=` pairs as a last line of defence.
- **A new top-level API path needs a gateway route** in both `api-gateway` application files
  (`application.yml` and `application-production.yml`); otherwise the gateway answers 401 or 404.
  Both files keep the same route ids, order and paths: `scripts\check-gateway-routes.ps1` exits 1
  on a difference, and `scripts\check-gateway-production.ps1` sends one admin request per frontend
  path through a gateway with the production profile (run it after any gateway change).
- **Rate limits are per route and client address** (`RateLimiterConfig`: Redis key
  `request_rate_limiter.{<route id>:<ip>}`). A path that needs its own capacity gets its own route,
  before the general one. Capacities that a site may have to change come from environment variables
  (`GATEWAY_AUTH_REGISTER_*`, `GATEWAY_AUTH_LOGIN_*`, `GATEWAY_OTP_REPLENISH`/`GATEWAY_OTP_BURST`);
  the defaults of the code routes (`auth-otp`: 1 and 50, 5 tokens a request) stay strict and are
  only loosened through `.env`.
- **The client address is the remote address**; `X-Forwarded-For` is believed only from the proxies
  in `GATEWAY_TRUSTED_PROXIES` (`ClientIpResolver`, read from the right). Production must set it to
  nginx, otherwise everybody behind nginx shares one bucket.
- **CORS origins come from `CORS_ALLOWED_ORIGINS`** (never `*` in production). A response header the
  frontend reads must be in `exposedHeaders` of both files.
- **Gateway time limits are longer than the services' own timeouts**, so a service's own error (503
  `USER_DIRECTORY_UNAVAILABLE`) reaches the client; the gateway fallback answers 503 with `Retry-After`.
- **Test data follows the naming rules** (accounts `@x.test`, exams and groups `T-...`):
  `scripts\cleanup-test-data.ps1` finds it by them. Run its dry run first; `-Execute` asks for the
  confirmation word typed by hand and takes a backup itself.
- **Full email addresses are never logged** and never written to audit details; use `LogMaskUtils`.
- **Errors**: expected client errors are 4xx with a code and a generic message; responses never
  contain SQL, stack traces or other internals. 4xx are logged once at WARN, 5xx at ERROR.
- **Secrets** come from the environment (`.env`, git-ignored). Never commit or print them.
  `DB_PASSWORD` is the live password of the database role: change it only with
  `scripts\init-public-env.ps1` (generates and applies it with `ALTER ROLE`), never in `.env` alone.
  Inside the Postgres container 127.0.0.1 is trusted without a password, so a password check is
  done from another container over the network.
- **Temporary public mode** (`docker-compose.public.yml`, [PUBLIC_TEMP.md](PUBLIC_TEMP.md)) is
  started and stopped only with `scripts\go-public.ps1` (never `down`). It publishes nothing on the
  network (edge 127.0.0.1:8088, gateway 127.0.0.1:8080, Postgres 127.0.0.1:5432 only). A new
  public path needs a `location` in `deploy/public/nginx.conf`; management paths (`/internal`,
  `/actuator`, ...) stay blocked there. Its mail comes from `PUBLIC_MAIL_*`, never `MAIL_*` (that
  would take the development stack off Mailpit). A new outside source in the frontend (a script,
  font, image host) needs the CSP of `deploy/public/security-headers.conf`.

## Working agreements

- One topic per branch, small commits, no pushing without being asked.
- `AUDIT_REPORT.md` and the nested `exam-system/exam-system/` folder are not to be modified.
- Verify changes against the running stack (health on 8080-8084, the real HTTP flow), not only by
  compiling.

## Project map rule

[PROJECT_MAP.md](PROJECT_MAP.md) is the living map of the backend (architecture, databases, endpoints, roles, the
exam model, security, scripts, tests, branches, risks, what the frontend does not use yet), in Azerbaijani:

> Hər tapşırığın sonunda, commit-dən əvvəl docs\PROJECT_MAP.md yenilənməlidir: əlaqəli bölmə düzəldilməli, status
> cədvəli yenilənməli və Dəyişikliklər jurnalına yeni sətir əlavə olunmalıdır. Sənəd yenilənməyibsə tapşırıq bitmiş
> sayılmır.

- Every task, before its commit: fix the section of what changed (a new endpoint: sections 3 and 13; a migration:
  section 2; a script: section 8; ...), update the status table (section 14) and add a line at the top of the change
  log (section 17: date and time in Baku, branch, what changed, which sections).
- Check with `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-project-map.ps1` (against `main`;
  `-Base <branch>` for another base, `-Staged` for the next commit only). Exit 1 means code changed and the map did
  not: the task is not finished.
- Counts in the map (endpoints, migrations, tests) are counted with the commands of its section 16, never estimated.
  What was not checked is written as "yoxlanılmadı" with the reason.
