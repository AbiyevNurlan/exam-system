# Project rules

Rules for everyone (and every AI assistant) changing this repository. Open work is tracked in
[BACKLOG.md](BACKLOG.md).

## Absolute rules: data must never be lost

Never run any of these against a database that holds data:

- `docker-compose down -v`, `docker volume rm`, `docker system prune`
- `DROP DATABASE` on `exam_auth`, `exam_db` or `exam_monitoring`
- an edit, rename or delete of a Flyway migration that has already been applied

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
- **`@Transactional` does not work at runtime in these services.** When several writes must be
  atomic, wrap them with `TransactionalOperator`. Do not add `@Transactional`; it only misleads.
- **User ids are UUIDs** (`users.id` in `exam_auth`): JWT subject, `UserPrincipal.id`,
  `exams.created_by`, `exam_sessions.user_id`. Never derive a numeric id from them. Exam, question
  and session row ids are `Long`.
- **Pagination**: page is 0-based, default size 25, maximum size 50 (larger values are clamped).
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
- **Full email addresses are never logged** and never written to audit details; use `LogMaskUtils`.
- **Errors**: expected client errors are 4xx with a code and a generic message; responses never
  contain SQL, stack traces or other internals. 4xx are logged once at WARN, 5xx at ERROR.
- **Secrets** come from the environment (`.env`, git-ignored). Never commit or print them.

## Working agreements

- One topic per branch, small commits, no pushing without being asked.
- `AUDIT_REPORT.md` and the nested `exam-system/exam-system/` folder are not to be modified.
- Verify changes against the running stack (health on 8080-8084, the real HTTP flow), not only by
  compiling.
