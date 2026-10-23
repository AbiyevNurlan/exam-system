# Regression checks

Live checks of earlier work packages (B2-B13) against the **development** stack: gateway on
`http://127.0.0.1:8080`, Mailpit on `http://127.0.0.1:8025` (codes are read from there), Postgres
through `docker exec`. They do not work against the public mode (no Mailpit) and must never be run
against a stack that serves real users: they create test data.

| Script | What | Creates |
|---|---|---|
| `verify-b2.ps1` | exam life cycle, attempt limits, reschedule, management list | `T-b2-*` exams, `be-b2-*@x.test` |
| `verify-b3.ps1` | grading, results, review | `T-b3-*` exams, `be-b3-*@x.test` |
| `verify-b4.ps1` | monitoring, analytics (stops and starts auth-service once) | `T-b4-*` exams, `be-b4-*@x.test` |
| `verify-b5-join.ps1` | join links and requests | `T-b5*` groups, `@x.test` accounts |
| `verify-b6-buckets.ps1` | rate limit buckets per route (301 registrations) | `t-b6-*@x.test` |
| `verify-b6-parallel.ps1` | parallel admin actions (on 6 participants it registers) | `T-b6-*` groups, `t-b6-*@x.test` |
| `verify-b7.ps1` | admin API gaps, CSV export, audit list | `T-b7-*`, `be-b7-*@x.test` |
| `verify-b10.ps1` | participant code (new and existing accounts, unique, `/auth/me`, search by code), `GET /api/me/stats` against attempts with known scores, `PATCH /api/exams/{id}/meta` on an exam with attempts, E.164 profile phone | `T-b10-*` exams, `be-b10-*@x.test` |
| `verify-b9.ps1` | an exam with attempts keeps questions, answer key, points and duration (409 EXAM_HAS_SESSIONS), reschedule of finished exams with attempts, `GET /auth/me`, `PUT /auth/profile`, `POST /auth/change-password` (token revocation, rate limit) | `T-b9-*` exams, `be-b9-*@x.test` |
| `verify-b11.ps1` | participant profile (`GET/PUT /api/me/profile`: null for a new account, trim, HTML removed, 400 with the field, audit), participant card (ADMIN all, INSTRUCTOR only a member of an own group else 404, STUDENT 403, no secret field, paged attempts), ADMIN `PATCH .../profile` (keep, clear, audit with actor and target codes), category and institution filters, the immutable participant code and `check-participant-codes.ps1`. Refuses to run while the public mode is up | `T-b11-*` groups, `be-b11-*@x.test` |
| `verify-b11b.ps1` | participant list order: `sortBy` (code, firstName, lastName, email, registeredAt) and `dir`, Azerbaijani alphabet ignoring case and spaces, both directions, pages without a repeat or a gap, the whitelist (400), sorting with the category, institution and code filters, the older `sort`/`order`, INSTRUCTOR 403. Refuses to run while the public mode is up; needs no mail | `be-b11b-*@x.test` |
| `verify-b12a.ps1` | exam blocks (create with blocks and the older flat questions, validation, totalMinutes, EXAM_DURATION_EXCEEDS_WINDOW), update in place (question and option ids kept, a question moved, EXAM_HAS_SESSIONS kept), the history (answer key and points as sensitive rows of one save, META_CHANGED, PUBLISHED, DELETE_ATTEMPTED, CLONED_FROM; UPDATE and DELETE of a row refused by the database), version 1 with its SHA-256, readiness and the publish gate (EXAM_NOT_READY, FEATURE_NOT_AVAILABLE), clone, block results hidden from the participant and shown to the administrator, who may read the history. Refuses to run while the public mode is up | `T-b12a-*` exams, `be-b12a-*@x.test` |
| `verify-b8.ps1` | group ownership of an INSTRUCTOR (another owner's group is 404 like a missing one), admin paths 403 for an INSTRUCTOR, `GET /api/join/requests/mine`, rejection cooldown | `T-b8-*` groups and exams, `be-b8-*@x.test` |
| `verify-b13.ps1` | teacher accounts by ADMIN (`/api/admin/instructors`, reset-password: temporary password shown once, change forced first, 7 days, tokens ended on deactivation and reset, audit without passwords), two teachers isolated (another teacher's exam, group and results are the 404 of a missing id, own rows only, PUBLIC for ADMIN only), the own audit trail, the start flags of `/api/me/stats`, teacher limits. Refuses to run while the public mode is up | `T-b13-*` exams and groups, `be-b13-*@x.test` |
| `verify-b13a.ps1` | guest participants of paid open exams (needs PAID_ACCESS_ENABLED=true for auth-service; with false only the 404 FEATURE_DISABLED of every guest endpoint): a new guest (code, PROFILE_REQUIRED, profile, 7-digit code, E.164 phone), the same profile token again, /api/guest/me and its PATCH (the email never changes), the guest token refused on the accounts' endpoints and account tokens on /api/guest/**, an account's email (the same answer, then 409 EMAIL_BELONGS_TO_ACCOUNT), wrong codes and the cooldown, refresh rotation, the admin's search, card, sort whitelist, block and unblock, the same guest back after 61 s, codes only as HMAC, the audit, no shared code. Refuses to run while the public mode is up | `be-b13a-*@x.test` guests and one account |

Every script starts with `Wait-Gateway` (`_common.ps1`, up to 120 s for `/actuator/health` to answer 200 UP), so a stack that is still starting does not show up as `admin login answered 0`. `New-ActiveExam` in the same file makes an exam ACTIVE with an open window (`POST /api/exams` refuses a start in the past).

Run one with `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\regression\verify-b2.ps1`.
Each prints `PASS`/`FAIL` lines and a total (`NOTE` lines report known behaviour and are not counted). Known failures that are not regressions are listed in
docs/BACKLOG.md. Everything they create follows the test data rules and is removed by
`scripts\cleanup-test-data.ps1`; roles they give to test accounts are given back at the end.

Not here: `verify-b5.ps1` (adds 20 000 synthetic participants per run) and `verify-b4-2.ps1`
(checked sessions of one day): one-off checks, not repeatable.
