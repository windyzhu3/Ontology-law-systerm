# Task 1 report: R1 business closure contract

## Scope and comparison

- Base: `9fda014700cd0f929366e0faf06f8ccd84c56eef`.
- OpenAPI operations: 13 before, 15 after; security split 11 public Bearer / 4 mutualTLS.
- OpenAPI document SHA-256: `cb5b364df43c65bf3ea7d7443789b8b7687642231f86e5f7d93a554d6c1135d6` before; `b5b3375aab66f7c07d2a58e98b5c320789d6c99c7406efd797a92fc68022589d` after.
- Baseline frozen at `MVP-2026-09-05.3`; `.2` evidence remains historical. No production Handler, migration, physical contract, event schema/version, Task registry code, or business status was changed.

## RED

Command: `python -m unittest scripts.baseline.tests.test_r1_business_closure_contract -v`

Result against the old contract: exit 1; 14 subtests ran, 13 expected failures (missing ADR-0008 decisions, SERVICE row, both internal operations, capacity profile and `.3` baseline). The public-error mutation already failed closed under the existing OpenAPI fixture.

## GREEN and regression

- `python -m unittest scripts.baseline.tests.test_r1_business_closure_contract scripts.baseline.tests.test_r1_command_contract -v` — PASS, 26 tests.
- `python -m unittest discover -s scripts/baseline/tests -q` — PASS (exit 0).
- `python scripts/verify_topology.py` — PASS.
- `mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest test -q` with locked JDK 25.0.4.1+1 — PASS, 17 tests; generator emits its pre-existing mutualTLS unsupported-type diagnostics but exits 0 and tests pass.
- `npm run openapi:generate` — PASS; generated TypeScript updated.
- `npm run openapi:check` — PASS.
- `npm run typecheck` — PASS.
- `git diff --check` — PASS.
- Locked raw baseline helper — `baseline consistency: PASS; R2 readiness: BLOCKED (5 non-fatal blockers)`.

## Concerns

- The full `tests.test_topology` mutation suite was interrupted after several temporary-repository cases failed during concurrent Git metadata restoration; the authoritative repository-level `scripts/verify_topology.py` subsequently passed. Root should rerun that mutation suite on the stable committed checkout.
- Five R2 blockers are intentionally retained: R1-OPENAPI remains MERGED rather than IMPLEMENTED, and R1-BACKEND/R1-SPA/R1-E2E-GOLDEN/R1-E2E-FAILURES remain absent. This task must not advance them.

## Fix round 1 (review of `9c60cac`)

The earlier report's baseline-suite PASS statement was inaccurate for the committed `9c60cac`: root's immutable `task-1-locked-python.log` ran 245 tests and found one failure, `test_runtime_baseline_version_accepts_only_the_approved_successor`, because its fixture still accepted `.2`. This round changes that fixture to accept only `.3` and explicitly reject `.2`.

RED evidence: five new closure mutations failed before repair: obsolete HUMAN-only capture prose escaped; both internal rows were absent from the HTTP Operations table; the two-operation mTLS phrase remained active; and exact candidate/consume required arrays could be weakened because the validator used unrelated global substrings. Root's pinned log supplied the separate stale-version RED.

GREEN evidence:

- Pinned Python image `python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`, read-only `/work`, no GIT overrides: the three requested modules ran 220 tests, exit 0 (`task-1-fix1-locked-python.log`).
- Focused closure + command tests: 32 tests, PASS.
- Locked raw baseline: `baseline consistency: PASS; R2 readiness: BLOCKED (5 non-fatal blockers)`.
- `OpenApiContractTest`: 17 tests, PASS. It now checks both new operations' exact status/component/schema/header sets rather than exempting them, plus exact due page/candidate/query constraints, consume DTO, lease-owner 64 bound, fencing range, and InternalProblem code/status/retry shape.
- OpenAPI generation/check and `git diff --check`: PASS.

Public compatibility comparison against `9fda014`: all 143 pre-existing schema objects are byte-structurally equal after YAML parsing (`changedPublicSchemas=[]`); all 11 public operations retain exact security and `x-error-codes` arrays (`changedPublicSecurityOrErrorSets=[]`). Only approved capture description and documentation/audit/cache semantics changed outside those sets.

The active command prose now has exact dual HUMAN/SERVICE capture semantics. The HTTP Operations, transport, DTO, success, security, authentication and error tables contain both new operations and are enforced through exact/scoped parsers. The approved spec and active contract also clarify the 52+2 authority ruling: organization_unit is an authorization/Audit anchor for chain/scope/Grant checks, not an ObjectAccessGrant business subject; exact existing-Lead DENY and final scope revalidation remain required.
