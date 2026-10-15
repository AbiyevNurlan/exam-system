# Regression checks

Live checks of earlier work packages (B2-B7) against the **development** stack: gateway on
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
| `verify-b8.ps1` | group ownership of an INSTRUCTOR (another owner's group is 404 like a missing one), admin paths 403 for an INSTRUCTOR, `GET /api/join/requests/mine`, rejection cooldown | `T-b8-*` groups and exams, `be-b8-*@x.test` |

Run one with `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\regression\verify-b2.ps1`.
Each prints `PASS`/`FAIL` lines and a total (`NOTE` lines report known behaviour and are not counted). Known failures that are not regressions are listed in
docs/BACKLOG.md. Everything they create follows the test data rules and is removed by
`scripts\cleanup-test-data.ps1`; roles they give to test accounts are given back at the end.

Not here: `verify-b5.ps1` (adds 20 000 synthetic participants per run) and `verify-b4-2.ps1`
(checked sessions of one day): one-off checks, not repeatable.
