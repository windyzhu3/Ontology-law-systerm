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
