# Backlog

Open work found during the audit and the fix rounds. Tick an item in the commit that fixes it.
Rules for all changes: [PROJECT_RULES.md](PROJECT_RULES.md).

## Exam

- [x] Submit response ignores `showResultsImmediately` (the score is returned even when the exam withholds results)
- [x] Start returns an IN_PROGRESS session that is already past its end time
- [ ] `/available` counts only COMPLETED sessions for remaining attempts, while start counts all sessions
- [ ] Results list has no status filter
- [ ] Soft-deleted exams are still returned by `GET /api/exams/{id}`
- [ ] ADMIN cannot publish or archive another user's exam
- [ ] Unused code: `activeSessionExists`, `countCompletedAttempts`, `existsByExamIdAndUserIdAndStatus`, `findTimedOutSessions`, `hasTimedOut`
- [ ] No upper bounds in request validation (points, text lengths, number of questions and options)
- [ ] Database constraint errors surface as 500 instead of 4xx
- [ ] Inactive `@Transactional` on `ExamService.deleteExam` and `updateExamStatus`
- [ ] Manual grading for open-ended questions (today: exact match only, `needs_manual_review` is always false)
- [ ] "Show correct answers after the exam" setting (does not exist; results never include the key)
- [ ] Who may take which exam: any authenticated user can start any ACTIVE exam (no enrolment or assignment)
- [ ] Publishing does not check for scheduling conflicts
- [ ] ADMIN cannot edit or delete another user's exam
- [ ] Exam and session-question caches are in memory per instance (wrong with more than one exam-service instance; questions are re-shuffled after a restart)
- [ ] `exam.settings.auto-submit-buffer-seconds` is configured but unused
- [ ] `TIMED_OUT` and `ABANDONED` session statuses are never set
- [ ] Index for the results list ordering (`exam_id, submitted_at DESC`)
- [ ] `FraudDetectionService` is not wired into the exam flow

## Auth

- [ ] Inactive `@Transactional` annotations in auth-service (login, forgotPassword, resetPassword)
- [ ] Access tokens issued before the UUID change are rejected (users must log in again); no token revocation list
- [ ] Email verification: table exists, no flow implements it
- [ ] No admin endpoint to change a user's role (INSTRUCTOR is assigned by SQL today)
- [ ] Failed logins are logged at ERROR

## Platform

- [ ] Expected 4xx errors are logged at ERROR, and twice (handler and service)
- [ ] Security filter chain answers 401/403 with an empty body, unlike the JSON error shape elsewhere
- [ ] Two codes for 401 (`AUTHENTICATION_ERROR` and `UNAUTHENTICATED`)
- [ ] `X-User-Role` / `X-User-Email` headers are set by the gateway but unused downstream
- [ ] Gateway rate limiting puts all anonymous traffic in one bucket (`anonymous`)
- [ ] No automated tests in any module
- [ ] No foreign key from exam tables to users (different database); deleting a user leaves exams and sessions
- [ ] monitoring-service has no tables and no Flyway setup
- [ ] Services answer 500/503 for a short time after a Postgres restart, until the pool drops dead connections
- [ ] Gateway logs at DEBUG

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
