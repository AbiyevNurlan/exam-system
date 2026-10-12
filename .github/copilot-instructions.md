# Instructions for AI assistants

Read [docs/PROJECT_RULES.md](../docs/PROJECT_RULES.md) before changing anything; open work is in
[docs/BACKLOG.md](../docs/BACKLOG.md). The short version:

- **Never lose data.** No `docker-compose down -v`, `docker volume rm`, `docker system prune`,
  `DROP DATABASE`, and never edit an applied Flyway migration. Schema changes are new, additive
  `V<n>__description.sql` files only. Back up with `scripts\backup.ps1` before rebuilding.
- Spring Boot 3.2 WebFlux + R2DBC on PostgreSQL, Java 21, build with `.\mvnw.cmd`.
- Roles are `ADMIN`, `INSTRUCTOR`, `STUDENT`; registration always creates a `STUDENT`.
- `@Transactional` does not work at runtime here: use `TransactionalOperator` for multi-write atomicity.
- User ids are UUIDs everywhere; exam, question and session row ids are `Long`.
- Pagination is 0-based, default 25, maximum 50.
- The answer key (`isCorrect`, explanations, expected text answers) never goes to students.
- Client errors are 4xx with a generic message, logged once at WARN; no SQL or stack traces in responses.
- Do not touch `AUDIT_REPORT.md` or `exam-system/exam-system/`. Do not push unless asked.
