# Task 9.6c source delivery and review report

Status: source implementation and synthetic/temporary adapter verification complete;
independent review and actual local Worker deployment gate remain controller work.
Source baseline provided to implementer: `04bd695f7a8f656a5ed8fb96c5168e44a91bab8d`.
The controller committed operations/docs concurrently; those commits are preserved.
No push. Delivery commit is recorded in the implementer's final handoff (this report
is part of that same commit).

## Scope and implementation

Owned paths: `deploy/local-login/local_worker.py`, its new tests, runner command
dispatch/lifecycle, local release process boundary, affected release tests, local
README, and this report. No product Java, migration, allowlist, UI, dependency,
source/assignment configuration, or main project documentation was changed.
No private `local-login-runtime` file was read. No existing DB/IdP/process was
mutated, no service or repository build was run, and no existing key material was
used in implementer tests. Live release/role/certificate observations supplied by
the controller are context, not implementer proof.

The original SERVICE is selected only by `service-fixture.json`. The original
founder/ROOT are selected from the original bootstrap audit keyed by the original
manifest command ID, not the first administrator or arbitrary caller input.
`worker-grant` snapshots exact facts, runs the existing original-bootstrap verifier,
rechecks the snapshot, saves all three exact grant rows/IDs/times and grantor basis
before writing, and compares the full snapshot under a single locked migration
Owner transaction. The original audit, slot, receipt and original four founder
grants are part of the comparison. Only three SERVICE authority_grant rows are
inserted; no HTTP/command/audit receipt is fabricated. Exact full retry is delta 0;
partial, extra, changed or unrecorded rows fail. Lost responses retain the original
inventory for reconciliation. No grant deletion, SERVICE recreation or business
fact rollback exists.

Grantor ruling received from controller: fixed original bootstrap founder
appointment, after exact original verification; this is approved local
infrastructure setup, not an ADM HTTP action or an expansion of founder online
SYSTEM grant ability. Required codes remain exactly R1_PROJECTION_CONSUME,
CONTACT_TASK_RECOVER, ROUTING_REVIEW_TASK_RECOVER with original ROOT scope.

Certificate adaptation uses a fresh protected copy and `keytool -changealias` from
`local-service` to `local_service`. A short local JDK source-launcher adapter
verifies unchanged original/copy private key and DER, certificate validity/client
EKU and original API client/server trust. This adapter is local runner material,
not a modification to production Java or a new application service. Original
keystore, certificate and truststores are never rewritten. Inspection/health
refuses missing copied material rather than creating it. Only explicit prepare
can make an absent copy; invalid/expired/conflicting existing copies fail.

Worker properties are independent: explicit ols.runtime-role=worker,
MVP-2026-09-08.3, LOCAL_LOGIN_WORKER, https://localhost:19445, verify-full TLS,
law_worker_login/only law_app_worker, original worker-db secret, current immutable
Jar/schema/release/manifest and original SERVICE binding. No API properties, OIDC
directory/introspection/offline secrets or Owner credential is passed to Worker.
Its environment excludes Spring/OLS/JVM option overrides. The hidden, non-web
process has a 384 MiB heap and exact PID/executable/arguments/creation/launch-time
registration. Old logs/configs are retained before replacement. An uncertain
existing process or pending launch blocks another start.

Release stop/switch/recovery/rollback now accounts for the registered third
consumer, preserving existing exact ownership and bounded handle-based stopping.
API/SPA startup/resume refreshes and restarts an already registered Worker using
the selected package. A stopped Worker retains grants and registration.

Health is a point-in-time check: exact live ownership; latest ISOLATED then READY
from the full WorkerRuntimeHealth logger and this PID after actual OS creation;
current Worker-login DB capability/gate; certificate-bound HTTPS read readiness;
zero owned TCP listeners/UDP endpoints. The existing production health source
requires database + projection + contact recovery + routing recovery together.
It does not emit separate per-loop READY events; returned requiredLoops=3 refers
to that existing aggregate contract. Later UNAVAILABLE, missing recovery grants,
stale READY, unknown logger/PID or current gate failure cannot yield READY.

## TDD evidence

All commands below ran from
`C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business`.
Python executable: `D:/soft/python3/python.exe`.

| Command / selection | Observed RED | Covering GREEN |
| --- | --- | --- |
| `-m unittest discover -s deploy/local-login/tests -p test_local_worker.py` initial 7 tests | exit 1, 7 failures: local Worker assembly missing | exit 0, 7 tests OK |
| Same suite after certificate/transaction/command tests | exit 1, 3 failures among 10: certificate adapter, WorkerBoundary, worker_command missing | exit 0, 10 tests OK |
| `-m unittest discover -s deploy/local-login/tests -p test_local_release.py -k registered_worker` | exit 1, Worker command missing | exit 0, 1 test OK |
| Worker suite `-k operator` | exit 1, 2 failures: LocalWorker orchestration missing | covering Worker suite exit 0, 15 tests OK |
| Worker suite `-k prepare`, `-k start_records`, `-k health_requires` | each exit 1, LocalWorker orchestration missing | covering Worker suite exit 0, 15 tests OK |
| Worker suite `-k refusal_scan` | exit 1: own inspection process incorrectly counted (first characterized as RuntimeError, then explicit failing assertion) | covering Worker suite exit 0, 17 tests OK; real newly created hidden child refusal tested |
| Worker suite `-k runner_dispatches` | exit 1, Worker command dispatch missing | covering Worker suite exit 0, 17 tests OK |
| Worker suite `-k restart_preserves`, `-k missing_registered` | each exit 1: old evidence missing / read-only validation missing | covering Worker suite exit 0, 20 tests OK |
| Worker suite `-k before_exact_process` | exit 1, RuntimeError not raised for READY before OS process creation | exit 0, full 22 Worker tests OK (8.660 s) |

Additional positive/negative coverage: fixed minimum grant shape and immutable
grantor/scope, changed original facts, HUMAN target rejection, partial/extra grants,
uncertain committed-response reconciliation, bad release/manifest/config/API
origin, failed DB response or wrong current gate, only Worker-secret mount,
missing permissions, hidden startup/env/registry, preserved previous evidence,
explicit release config refresh, malformed/stale/latest-failed logger states,
unregistered process refusal, listeners and mTLS refusal.

The certificate tests generated their own temporary RSA keypair, certificate and
truststores with the pinned JDK. Real keytool alias conversion and Java
certificate/key/trust verification passed. Wrong alias, wrong certificate, wrong
trust and an independently generated expired certificate were rejected; original
test-keystore bytes remained unchanged. No real API mTLS request was made.

Affected regression observations before final combined run:

- `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_release.py`: exit 0, 27 tests OK (48.712 s).
- `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_login.py`: exit 0, 7 tests OK (0.306 s).
- `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe --test deploy/local-login/tests/server.test.mjs`: exit 0, 4 passed / 0 failed / 0 skipped. Direct admin navigation, SPA fallback/internal route exclusion and host digest boundaries remain covered.
- `git diff --check`: exit 0.

Final combined regression:

- `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p 'test_*.py'`: exit 0, **56 tests OK**, 55.457 seconds (22 Worker + 27 release/lifecycle + 7 runner).
- Repeated Node command above after final source edits: exit 0, **4 passed / 0 failed / 0 skipped**, 105.8686 ms.
- Final `git diff --check`: exit 0. No repository build was invoked.

## Self-review, limitations and deployment gate

Self-review found and addressed: refusal scan counting its own PowerShell process;
old Worker logs being truncated without a retained copy; health potentially
recreating a missing copy; and startup timestamps allowing READY before the exact
OS process creation. Each corrective behavior received a failing test followed by
GREEN. Worker readiness uses the existing production logger, not a product health
endpoint or PID-only substitute.

**M1 remains deferred and is carried into lifecycle judgment.** With a surviving
apps-start.pending or worker-start.pending marker, the existing final stopped()
check raises even after successful exact cleanup. New regression
`test_m1_pending_worker_launch_retains_marker_after_three_owned_children_are_stopped`
ran actual Windows CIM/handle-based termination against three new hidden test
children: all exited; the marker remained and final status failed for explicit
reconciliation. Thus this delivery does not claim unconditional successful stop
after an interrupted launch. M2 was reported resolved by the controller and is
not reopened here.

The external SQL and current-DB branches were verified against synthetic boundary
fixtures/emitted commands, not executed against a new or existing PostgreSQL
server. Actual SQL transaction success, unchanged original bootstrap/key hashes,
delta 3 then exact delta 0, real current worker-role gate, real mTLS read readiness,
aggregate three-loop READY, exact zero new listeners, and actual stop/resume with
unchanged qualifications remain required controller deployment evidence after
independent spec/quality review. Failure must preserve all operation inventory;
never adopt unexplained rows or rerun service-fixture. No production defect or
substitute scheduler was implemented. READY remains insufficient for W09/seven-card
real-business acceptance. The MANUAL-source/routing-gap concern is separate
controller work and no AUTO source was added here.

## Fix round 1/5 — I1 named tenant exclusion protocol

Reviewed the complete `task-9.6c-review-01.md` and the unchanged production
`JooqR1BusinessFence.java` and `JooqAuthorizationService.java` lock implementations.
I1 was confirmed: the initial infrastructure transaction had table locks but did
not participate in the existing business/Identity advisory-lock protocol.

The only implementation change is in `WorkerBoundary.apply_grants`: after the
existing exact canonical original-tenant/plan validation, start an explicit
READ COMMITTED transaction, set the existing 5-second lock and 30-second statement
timeouts, acquire the existing exclusive transaction advisory locks in business
then identity order, and only then acquire the existing table locks and perform
the exact original-fact comparison/write. Keys use UTF-8 namespace plus canonical
tenant UUID, SHA-256, first eight bytes, signed big-endian int64, matching the two
production implementations. `PERFORM` in a local SQL DO block preserves the
existing fixed acknowledgment output. Both delta 3 and exact delta 0 retries join
the same exclusion protocol. No namespace, command UUID/envelope, HTTP operation,
receipt, product code or additional data writer was introduced.

The emitted-SQL boundary test uses independently computed .NET SHA256/BitConverter
vectors for synthetic tenant `00000000-0000-0000-0000-000000000001`:
business key `3054790668159973240`, identity key `-6113651264468117507`. It checks
both exact exclusive keys, their order, explicit isolation, timeout ordering,
table locks before comparison and comparison before INSERT. Its delta 0 branch
also requires no INSERT. A second test rejects noncanonical, changed and malformed
tenant values before any external SQL call. Existing grant-plan, fixed-shape and
lost-commit-response retry tests remain in the covering suite.

Commands ran from `C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business`:

- RED: `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_worker.py -k grant_transaction_joins_exact` — exit 1, one test with two failing subtests (delta 3 and delta 0); actual advisory-lock list `[]` instead of the two exact keys. An earlier run of the same test also failed both subtests at the missing explicit READ COMMITTED assertion; the assertions were reordered to expose I1 directly before changing implementation.
- GREEN: `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_worker.py -k grant` — exit 0, 9 tests OK, 0.123 seconds.
- Covering GREEN: `D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_worker.py` — exit 0, 24 tests OK, 8.672 seconds.
- `git diff --check` — exit 0.

Self-review: canonical tenant validation still precedes the SQL boundary; both
locks are exclusive and transaction-scoped; signed conversion handles the
negative identity vector; bounded waiting is configured before acquiring either
lock; original comparison, fixed three-row insert, exact retry behavior and
uncertain-response handling are unchanged. Only local_worker.py, its tests and
this report changed in this fix round. No runtime/private-file/DB/service/build
access or push occurred. M1 stays deferred and no launch marker is auto-cleared.
Actual grant/start remains held for the controller's scoped re-review and the
previously documented live deployment gate; these tests do not claim real SQL
contention or deployment proof.
