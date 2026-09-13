# Task 10.5 implementation report

## Status and scope

`SOURCE_COMPLETE_LIVE_NOT_RUN`. Implemented the approved isolated R1 golden consumer at base `8b78f50fa7b0bff39f0f04312a7fc2d8c332082c`. The owned source adds a default-offline Playwright entry, verified current-run projection, exact 18-write journal/gate, real browser adapter, golden orchestrator, read-only database closure, focused tests, and the permitted README handoff. It changes no product, endpoint, UI, DDL, role, grant catalog, dependency, service configuration, IdP account, or system trust.

The corrected capture contract is applied: the SPA has no capture page. The sourceOwner's authenticated HUMAN browser session issues exactly one armed `/api/v1/leads` synthetic ingress with `sourceAccountCode=R1_AUTO`, following the existing controlled fixture. All 15 management operations and sales Draft/save/reload/primary submit actions use the real pages. Source neither imports/removes a CA nor disables TLS. Root owns the separately approved exact CurrentUser trust prerequisite, independent source review, single live run, and exact cleanup verification.

## TDD evidence

### Initial RED: Python boundary and closure contracts

Command:

```powershell
D:/soft/python3/python.exe -X utf8 -m unittest tests.test_r1_acceptance
```

Exit code `1`: 6 tests ran and all 6 failed with the expected explicit `r1_acceptance implementation is missing` assertions.

After the initial implementation this command passed 6 tests. A subsequent focused closure-query test first failed alone with `completion_query implementation is missing`; it then passed after the fixed read-only query was added.

### Initial RED: TypeScript harness

Command:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/@playwright/test/cli.js test tests/r1-isolated-harness.spec.ts --config e2e --workers=1 --reporter=line
```

Exit code `1`: 7 tests ran and all 7 failed on the intentionally absent isolated config/fixtures/write policy. The first authoring attempt had an import-loader error and was not accepted as the behavioral RED.

The subsequently added golden-orchestrator test was run alone before its implementation and failed `1 failed` because `R1GoldenOrchestrator` was missing. It passed alone after the real orchestrator was added. One intermediate test-double method mismatch was corrected in test code before accepting GREEN.

### Final GREEN: Python

Command:

```powershell
D:/soft/python3/python.exe -X utf8 -m unittest tests.test_r1_acceptance
```

Exit code `0`:

```text
.......
----------------------------------------------------------------------
Ran 7 tests in 0.110s

OK
```

### Final GREEN: offline Playwright harness

Command:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/@playwright/test/cli.js test --config e2e/r1-isolated.config.ts --project offline-r1-isolated
```

Exit code `0`: `8 passed (2.3s)` using one worker. The only additional output was Node's existing `NO_COLOR`/`FORCE_COLOR` warning.

### Final GREEN: touched TypeScript graph

Command:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/typescript/bin/tsc --noEmit --target ES2022 --module ESNext --moduleResolution Bundler --esModuleInterop --skipLibCheck --strict --types node,@playwright/test e2e/fixtures/r1-isolated-environment.ts e2e/fixtures/r1-isolated-setup.ts e2e/fixtures/r1-isolated-browser.ts e2e/r1-isolated.config.ts e2e/tests/r1-golden-path.spec.ts e2e/tests/r1-isolated-harness.spec.ts apps/workbench/src/features/workcard/contract.ts apps/workbench/src/generated/api/schema.d.ts
```

Exit code `0`, silent.

### Discovery boundary

Default `--list` exited `0` and listed only 8 `offline-r1-isolated` harness tests. Exact `--project approved-r1-isolated --list` exited `0` and listed only `R1-CONTACT-CONNECTED-GOLDEN`. Neither command entered a test body or accessed a run.

## Implemented boundary

- The Python projection reuses the current `APPLICATION_INFRASTRUCTURE_READY` verifier, rechecks all three saved process identities, source/artifact/configuration digests, main bootstrap binding, and four protected credential references before the browser can launch.
- The fixed management sequence is exactly 3 principals, 2 child organizations, 3 appointments, and 7 ROOT-scoped DIRECT grants. It never binds `revokedAppointment`, creates an IdP user, grants sales an authority, creates a delegation, or recreates bootstrap data.
- The browser adapter uses strict TLS and real OIDC/UI interaction. Post-management verification binds each created row, requires EMPTY_ROOT to have no appointment, and requires sales to be the sole active CONTACT_OPERATOR before dynamic user login.
- Each mutation is matched against the one armed method/path/canonical body/Actor. Its observed original idempotency key is durably PENDING before forwarding. An unknown result retains PENDING and poisons the gate; no later write or generic retry occurs.
- Durable completed stages skip already confirmed management commands only when the operator explicitly selects the same continuation operation ID. Default retries remain zero and an existing journal without that explicit identity fails closed.
- The one sourceOwner `R1_AUTO` ingress is followed by sales CONTACT_LEAD discovery, real Draft save, full page reload and value verification, and one real CONNECTED_VALID primary submit.
- Original receipt recovery must equal the journaled command/receipt/outcome/result Fact exactly.
- Completion uses `BEGIN READ ONLY; SET LOCAL ROLE law_app_query` and `audit.audit_entry_classified_v`. It requires one ContactResult, Opportunity, DONE Task, CONFIRMED Draft, Receipt, and Audit, plus exactly two expected Events and two matching Outbox rows. The query excludes candidate payload, legal-need content/digests, result summary, event payload, audit change summary, token, and credential columns.
- The exclusive report whitelists only run, operation, environment digest, management count, golden command/result Fact, and fixed aggregate counts. Approved-run failures collapse to `R1_GOLDEN_CLOSED_FAILURE`.

## Files

- `e2e/runtime/r1_acceptance.py`
- `e2e/fixtures/r1-isolated-environment.ts`
- `e2e/fixtures/r1-isolated-setup.ts`
- `e2e/fixtures/r1-isolated-browser.ts`
- `e2e/r1-isolated.config.ts`
- `e2e/tests/r1-golden-path.spec.ts`
- `e2e/tests/r1-isolated-harness.spec.ts`
- `tests/test_r1_acceptance.py`
- `e2e/README.md` (append only)
- `.superpowers/sdd/2026-08-28-r1-lead-contact-vertical-slice-plan/task-10.5-report.md`

## Live boundary and concerns

- No real/private run, protected runtime, service, database, IdP, browser login, fixed port, or old suite was accessed by this implementer. Offline transport/process/database boundaries are doubles; the actual orchestrator, journal, validators, and closed projection run in the focused tests.
- Therefore this report is not a golden PASS. The live result remains unavailable in this source unit until Root completes independent review, the approved exact temporary CurrentUser CA trust, the single controlled run, database/audit closure, and exact trust cleanup verification.
- The 600-second live test contains no retry. Per-screen waits and the verified-environment/closure bridges are fixed and bounded; an uncertain successful write remains PENDING rather than being resent.
- Early RED runs created untracked `test-results/` error-context files containing only offline synthetic failure diagnostics. They are excluded from the owned commit. Pre-existing JVM crash/replay logs and concurrent Root documentation edits are untouched and excluded.

## Review fix round 1

Base commit: `c67a67009675565d44f53c2db29c46c98718004d`.

The two Important findings in `task-10.5-review-01.md` were fixed without changing the 18-write surface, browser flow, product, application assembler, permissions, DDL, service state, or live environment:

- `load_acceptance_environment` now enters the existing validated `_load_application(..., (APPLICATION_INFRASTRUCTURE_READY,))` boundary before invoking `verify_applications`. A persisted `STARTED_UNVERIFIED` state therefore fails before the verifier's state-promotion branch can run. The focused test uses the real `_load_application` implementation and proves both zero verifier calls and byte-identical original `state.json`.
- The exact original submit receipt `resultFact.digest` now crosses the orchestrator, browser adapter, TypeScript/Python bridge, CLI, query, and completion validator. The `law_app_query` transaction exposes URL-safe base64 `task_occurrence.completion_fact_hash` and `command_receipt.result_fact_hash`, filters both by the expected digest, and requires both returned values to equal the original HTTP digest. Mismatch coverage independently changes the expected HTTP digest, Task hash, and Receipt hash.

### Review-fix RED

Python command:

```powershell
D:/soft/python3/python.exe -X utf8 -m unittest tests.test_r1_acceptance.R1AcceptanceTest.test_non_ready_saved_state_uses_real_loader_before_verifier_without_mutation tests.test_r1_acceptance.R1AcceptanceTest.test_golden_completion_rejects_http_task_or_receipt_digest_mismatch
```

Exit code `1`:

```text
FF
FAIL: test_non_ready_saved_state_uses_real_loader_before_verifier_without_mutation
AssertionError: "applications not consumable" does not match "forbidden non-ready verifier call"

FAIL: test_golden_completion_rejects_http_task_or_receipt_digest_mismatch
AssertionError: expected digest closure implementation is missing

Ran 2 tests in 0.074s
FAILED (failures=2)
```

TypeScript command:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/@playwright/test/cli.js test --config e2e/r1-isolated.config.ts --project offline-r1-isolated -g "golden orchestrator"
```

Exit code `1`: the single orchestrator test expected the exact 43-character receipt digest but received the resources object at the old second argument, proving that the original digest was not forwarded to closure.

### Review-fix focused GREEN

The same two Python tests exited `0` with `Ran 2 tests ... OK`. The same targeted Playwright command exited `0` with `1 passed (894ms)`. The three readiness tests, including the existing READY projection/order coverage, separately exited `0` with `Ran 3 tests ... OK`.

### Review-fix final GREEN

Python command, with `FORCE_COLOR` removed and `NO_COLOR=1` only for the test process:

```powershell
D:/soft/python3/python.exe -X utf8 -m unittest tests.test_r1_acceptance
```

Exit code `0`:

```text
.........
----------------------------------------------------------------------
Ran 9 tests in 0.114s

OK
```

Playwright command, with `NO_COLOR` removed and `FORCE_COLOR=0` only for the test process:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/@playwright/test/cli.js test --config e2e/r1-isolated.config.ts --project offline-r1-isolated
```

Exit code `0`: `8 passed (2.2s)` using one worker, with no warning output.

Touched TypeScript graph command:

```powershell
C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe node_modules/typescript/bin/tsc --noEmit --target ES2022 --module ESNext --moduleResolution Bundler --esModuleInterop --skipLibCheck --strict --types node,@playwright/test e2e/fixtures/r1-isolated-environment.ts e2e/fixtures/r1-isolated-setup.ts e2e/fixtures/r1-isolated-browser.ts e2e/r1-isolated.config.ts e2e/tests/r1-golden-path.spec.ts e2e/tests/r1-isolated-harness.spec.ts apps/workbench/src/features/workcard/contract.ts apps/workbench/src/generated/api/schema.d.ts
```

Exit code `0`, silent.

### Review-fix live boundary

No application verifier, real/private run, protected runtime, database, service, browser, IdP, trust store, fixed listener, or old suite was accessed in this fix. The strict-TLS golden run remains Root-owned after scoped re-review; this offline GREEN does not claim runtime PASS. The pre-existing untracked crash/replay logs, offline `test-results/`, and concurrent Root progress-document edit remain untouched and excluded.
