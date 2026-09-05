# Task 0 report — C0 cross-layer contract readiness

## Status

C0 implemented only. No C/D production persistence or command runtime was added.

## RED evidence

Command after installing the exact lockfile dependencies:

`npm run typecheck`

Expected failure: `TS2339: Property '/internal/v1/tasks/commands/reopen-due-routing-review-tasks' does not exist on type 'paths'.` This proved the generated client lacked the dedicated routing recovery contract. The first pre-install attempt failed earlier because `tsc` was unavailable; `npm ci` resolved that environment issue.

During GREEN, the new real-fetch round-trip test also exposed two test-harness issues in sequence (`Invalid URL`, then captured native fetch). They were corrected with a per-call absolute `baseUrl` and per-call fetch implementation; the final test exercises the exported real `apiClient`, inspects its serialized Request body, and parses the response.

## Implemented contract

- Added `reopenDueRoutingReviewTasks` at `POST /internal/v1/tasks/commands/reopen-due-routing-review-tasks`, `ReopenDueRoutingReviewTaskV1`, and its individually mounted example.
- Preserved the existing 12 operation contracts and froze 13 operations / 14 examples.
- Central `Revision` is `0..9007199254740991`; generated TypeScript remains `number`. Java tests accept max and reject max+1; frontend fetch proves max is serialized and parsed unchanged.
- Contact and routing recovery are separate named commands with static Task/WaitReceipt profile mappings. Reopen scope includes `commandType`.
- Frozen ordering: after authentication/authorization and canonical scope, existing same-key same-scope/payload Receipt replay and scope/payload conflict take precedence. New keys enter a lock-protected pre-insert eligibility gate (`LEAD→TASK→COMMAND advisory lock`); before-due, wrong-type/profile and stale-selector failures are `VALIDATION_FAILED` with literal zero durable writes. Only eligible new commands insert a slot and CAS.
- Revision increment overflow at max is a pre-write `INTERNAL_ERROR` with no durable slot/Receipt. Non-safe revision canonicalization is forbidden; legacy out-of-range database values cannot be emitted.
- ADR-0005 records unchanged 52+2/V001-V850/database-bigint physical contracts and the absence of deployed R1 business-client compatibility burden.
- Delivery ledger records PR #9 OpenAPI artifacts as MERGED without claiming backend, SPA business behavior, or E2E implemented.

## Files

Canonical OpenAPI/example and generated TypeScript; Java and frontend contract tests; ADR-0004 amendment and ADR-0005; current baseline; R1 HTTP/Task/Workbench contracts; frozen R1 plan and controller-created foundation plan; baseline verifier/tests; delivery ledger; README.

## GREEN verification

- `npm run openapi:check` — PASS.
- `npm run typecheck` — PASS.
- `npm run test` — PASS, 1 file / 7 tests.
- `npm run build` — PASS, Vite production build.
- `.\mvnw.cmd -f backend/pom.xml test -Dtest=OpenApiContractTest` — PASS, 16 tests (generator continues its pre-existing `mutualTLS` diagnostic output).
- `.\mvnw.cmd -f backend/pom.xml package` — PASS; compilation/package and repository tests completed.
- Focused baseline tests for R1 E2E, HTTP operation registry, and mTLS registry — PASS, 3 tests.
- `python scripts/baseline/verify_baseline.py` — contract checks clear; non-zero only for this sparse/grafted worktree: 34 tracked design PNGs absent and ancestor evidence cannot be established for an old DB ledger row.
- `python -m unittest discover -s tests -v` — 24/30 PASS; exactly the documented Windows topology exceptions remain: five slash-format assertions and one symlink privilege error. The package run itself passed before these standalone topology results.
- `git diff --check` — PASS.

## Concerns / follow-up constraints for C/D

- C/D must implement the replay/conflict precedence and lock-protected pre-insert eligibility gate exactly; it must not convert new-key early/wrong/stale recovery into terminal rejected receipts.
- C/D must guard all API/canonical scope/Draft revisions and every revision increment before writes.
- Full baseline success requires a non-sparse checkout with intact ancestry. Root reported fresh merged-main Ubuntu CI is passing and the runtime harness refusal is caused by the current sparse checkout; this C0 task did not weaken those guards.
- OpenAPI Generator 7.25.0 logs its existing unsupported-`mutualTLS` diagnostics while still exiting successfully; the executable contract tests enforce the actual security definition and operation binding.
