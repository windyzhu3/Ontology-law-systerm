# R1 Projection Readiness Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate and mechanically validate the user-approved readiness protocol, then resume original business closure Task7 without another design decision.

**Architecture:** One new read-only internal mTLS operation transports an API-owned current organization-coverage decision to the execution-only Worker for one immediately following claim call. A named temporal supersession acknowledges the check-to-claim race; event consumption still performs full current authorization.

**Tech Stack:** Existing pinned Python/PyYAML, Java25/Maven, OpenAPI Java/TS generation; no dependency updates.

**Spec:** `docs/superpowers/specs/2026-09-07-r1-projection-readiness-design.md`, approved in chat2026-09-07. Parent continuation: `docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md`, original Tasks7–10.

## Global Constraints

- One responsive SPA, one OpenAPI, one modular monolith Jar; `APP_ROLE=api|worker` is exclusive.
- 11 public + 5 internal = 16 operations; existing public and internal request/response DTO shapes remain unchanged.
- Worker retains execution-only module/DB capability.
- Keep 13 schemas, 52 application + 2 technical tables, physical capability `52-plus-2-v1.2`, all V001–V860 bytes, manifest, field contract, database grants and generated jOOQ scope unchanged.
- No new business command/authority/slot, proof persistence, Provider, AI, admin mode or R2+; no push/merge/deploy or delivery-status promotion.

## Task 1: Activate the approved readiness contract and reject drift

**Files:**
- Create `docs/adr/ADR-0012-r1-projection-readiness-protocol.md`.
- Create `scripts/baseline/r1_projection_readiness_contract.py` and `scripts/baseline/tests/test_r1_projection_readiness_contract.py`.
- Modify `docs/baseline/CURRENT-MVP-BASELINE.md`, `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`, `contracts/openapi/ontology-law-api.yaml` and only affected static inventory rows in other active R1 contracts.
- Modify `scripts/baseline/verify_baseline.py`, `scripts/baseline/r1_business_closure_contract.py`, `scripts/baseline/r1_contact_evidence_contract.py`, `scripts/baseline/r1_command_contract.py` and their affected tests/valid repository fixtures as required by the named successor. The command validator changes only its active semantic-baseline expectation; command/event policy semantics stay frozen.
- Modify `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`; mechanically regenerate existing tracked OpenAPI TS outputs with the existing script, no handwritten generated edits.
- Add only a named supersession pointer to the original closure plan/spec, preserving historical implementation steps. Add the new baseline successor to `docs/progress/MVP-DELIVERY-LEDGER.md` without fabricating hosted evidence or rewriting historical rows. Root owns the later user progress receipt.
- Include the approved new design/plan documents in this contract unit's explicit local commit.

**Interfaces:** Python validator exports `validate(root: Path) -> list[str]` and is invoked by the existing baseline verifier. OpenAPI produces `checkR1ProjectionReadiness` GET `/internal/v1/projections/r1/readiness`, no body/parameters, mTLS only, 204/no body/no-store; exact existing error codes in spec§2. Existing Java generated delegates gain the method through generation, not a production controller in this unit. ADR exposes BaselineId `MVP-2026-09-07.1`, OpenApiVersion `1.2.0`, OperationCount16/PublicBearerOperations11/MutualTlsOperations5, ReadinessProfile `R1_PROJECTION_READINESS_V1`, and precise bounded semantics from spec§3–4. Historical ADR0008 values stay historical; exact active successor takes precedence, never permissive either-version acceptance.

- [ ] **Step1: fresh baseline, then meaningful RED.** Run the existing closure/contact Python suites and OpenApiContractTest/ArchitectureTest before edits. Create mutation tests using the existing temporary copied repository pattern; fail because the readiness validator/protocol is absent, not due to import/setup failures. For example:

```python
def test_missing_readiness_operation_is_rejected(self):
    root = self.copy_valid_repository()
    api = root / "contracts/openapi/ontology-law-api.yaml"
    text = api.read_text(encoding="utf-8")
    self.assertIn("operationId: checkR1ProjectionReadiness", text)
    api.write_text(text.replace("operationId: checkR1ProjectionReadiness", "operationId: wrongReadiness"), encoding="utf-8")
    self.assertTrue(validate(root))
```

The RED before activation also includes a positive expected16/mTLS5/no-store readiness operation assertion against the current parsed OpenAPI (currently missing). Fixture helpers copy real active artifacts into tempfile directories; invalid transformations must be caught by the validator output, not by grepping validator source.

- [ ] **Step2: activate complete exact contract.** Implement parser-based scoped checks of the actual new operation, empty input/no-content response, mTLS/error/cache policy and exact operation/security inventory. Add ADR/active baseline/HTTP table and prose for scope-universe, final-evaluation decision point, one success/one claim, no reuse, generation guard, no claim/attempt on preflight failure, post-evaluation race and mandatory consume reauthorization. Keep source/Owner-derived coverage separate from event-specific DENY. Connect the validator to the real CLI; update valid successor fixtures rather than weakening older-version gates.
- [ ] **Step3: complete mutation coverage and Java contract tests.** Detect removed/renamed/duplicate readiness operations, publicBearer substitution or extra security alternative, caller Tenant/body/query, response body/304/ETag/cacheability, added error code, wrong16/11/5 counts, missing/incorrect active successor, missing bounded-consistency policy fields and altered Worker/table invariants. Exercise positive and negative parsed fixtures. Java test verifies generated operation and no public/legacy DTO changes. Existing handwritten test inventory helpers may be extended, production runtime classes may not be added here.
- [ ] **Step4: focused GREEN, full contract regression and generation.** Use pinned Python image `python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970` with PyYAML6.0.3; run `python -m unittest discover -s scripts/baseline/tests -v` and `python -m unittest discover -s tests -v`. Run `./mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest,ArchitectureTest verify`; existing OpenAPI generation and TS typecheck commands from package.json must pass without dependency upgrades. Run actual `python scripts/baseline/verify_baseline.py` with correct linked Git metadata mounts; consistencyPASS is required, existing R2 readiness blockers remain non-fatal/unpromoted. Verify frozen `database/`, event contracts, public DTO/security and no application implementation drift. Preserve raw logs, actual exit codes and exact executed counts; warnings/failures must not be rewritten away.
- [ ] **Step5: self-review and local commit.** Explicitly stage only intended contract/generation/test/plan files; commit `fix: define bounded R1 projection readiness protocol`. Write full report with RED/GREEN commands, raw output paths, files and final Task7/8 interfaces to this plan's ignored `task-1-report.md`. Root dispatches a different reviewer for spec and quality of full BASE..HEAD, performs committed checks, records acceptance, then resumes original Task7 with this amendment. No implementer subagents/reviewers and no automatic push/merge.

## Root preflight and continuation checklist

- [ ] Task1 self: spec§1–4 matches exact inventory, DTOs, errors, temporal policy and frozen files; test positive/negative artifacts, not implementation claims.
- [ ] Contract→Task7: coverage universe and final-decision semantics are concrete; Owner reads preserve DAG, client gate consumes only current mTLS outcome, no persistent proof.
- [ ] Contract→Task8: generated method + frozen DTOs map to production controller/security/role assembly; sixteen operations not fifteen.
- [ ] Contract→Task9/10: no public feature additions; only downstream inventory and readiness race evidence change, capacity remains separately deferred.
- [ ] Independent review/fixes and committed root verification; return to original plan ledger and regenerate original Task7 brief/context with named approved amendment before dispatch.
