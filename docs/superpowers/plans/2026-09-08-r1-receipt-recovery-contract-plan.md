# R1 Receipt Recovery Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate the approved minimal Receipt recovery authorization contract with executable static consistency and mutation checks, without claiming runtime recovery implemented.

**Architecture:** One independently reviewed contract unit adds ADR-0013 and exact named successors to existing contracts. A focused Python validator joins the real baseline CLI; mutation tests prove drift is rejected. Original Task8 resumes only after this unit passes review and committed verification.

**Tech Stack:** Existing Markdown contracts, OpenAPI YAML 3.1, Python unittest/PyYAML 6.0.3, locked baseline Docker runtime, existing Maven Java contract tests; no new dependencies.

**Spec:** [approved complete design](../specs/2026-09-08-r1-command-receipt-recovery-design.md). Read all sections; their exact recovery structures, authorization boundaries, error table and legacy rules bind this task.

## Global Constraints

- 一个 SPA、一份 OpenAPI、一个 Jar；`ols.runtime-role` / `OLS_RUNTIME_ROLE` 的 api/worker 互斥规则不变。
- 保持 16 operation、11 public Bearer / 5 internal mTLS、全部已有请求/响应 DTO 及错误码集合。
- 保持 13 Schema、52 应用表加 2 技术表、`52-plus-2-v1.2`、V001–V860、manifest、字段合同、数据库 GRANT、jOOQ 生成范围及 14 个 EventType。
- Exact successor: `MVP-2026-09-08.1`, `R1-HTTP-V1.3`, `R1-COMMAND-POLICY-EVENT-V1.2`; OpenAPI remains `1.2.0`. Historical activation values remain historical, never bulk replace dates.
- No backend production changes, migrations, generated artifacts, frontend, deployment, credentials, push or merge in this contract unit. Preserve the four existing Task8 auth WIP files and do not stage them.
- Only static FROZEN delivery rows may be added; no R1/backend/SPA/E2E/capacity/release promotion. A contract pass is not an HTTP or PostgreSQL runtime pass.

## Task 1: Activate and verify the bounded receipt recovery successor

**Files:**

- Create: `docs/adr/ADR-0013-r1-command-receipt-recovery.md` — accepted authority, machine-readable closed registry and the exact approved protocol.
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md` — new active baseline, named supersession and authority pointer; preserve historical evidence.
- Modify: `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`, `R1-COMMAND-POLICY-EVENT-CONTRACT.md`, `R1-WORKBENCH-PRESENTATION-CONTRACT.md` — versioned GET/write/read/legacy semantics.
- Modify: `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md` — exact API Audit Owner metadata lookup and no-disclosure-failure audit exceptions, with V830 semantic supersession only; never edit the migration or generated copy.
- Modify: `contracts/openapi/ontology-law-api.yaml` — GET receipt description/cache semantics only; retain parsed DTOs, errors, security, parameters, response shapes and operation inventory.
- Modify: `docs/progress/MVP-DELIVERY-LEDGER.md` — dated static successor context and FROZEN rows; retain old evidence and non-static states.
- Modify: `docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md`, `docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md` — active successor pointer and Task8 ownership, not rewritten historical implementation evidence.
- Create: `scripts/baseline/r1_receipt_recovery_contract.py`, `scripts/baseline/tests/test_r1_receipt_recovery_contract.py` — focused semantic validator and mutation tests.
- Modify: `scripts/baseline/verify_baseline.py` and existing `scripts/baseline/r1_*_contract.py` / corresponding tests only where exact active-version/pointer integration requires it; preserve prior registry activation IDs.
- Test: `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java` and existing Architecture/RuntimeRole tests unchanged unless an exact static successor assertion requires adjustment.

**Interfaces:**

- Consumes: approved spec §§2–8, existing `verify_repository(root)` and existing validators' `validate(root: Path) -> list[str]` conventions.
- Produces: `scripts.baseline.r1_receipt_recovery_contract.validate(root: Path) -> list[str]`, returning empty only for the exact active protocol, and structural findings in the real baseline CLI. Import supports both module and direct CLI execution like existing validators.
- Produces for original Task8: authoritative ADR registry matching spec §§3–7, not an inferred generic Audit query API or historical ALLOW shortcut.

- [x] **Step 1: Write the meaningful RED activation test first.** Use a missing-validator diagnostic like the existing readiness tests, not an import error. Start with:

```python
from pathlib import Path
import importlib
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[3]

def validate(root: Path) -> list[str]:
    name = 'scripts.baseline.r1_receipt_recovery_contract'
    if importlib.util.find_spec(name) is None:
        return ['R1 receipt recovery validator missing']
    return importlib.import_module(name).validate(root)

class R1ReceiptRecoveryContractTest(unittest.TestCase):
    def test_approved_recovery_contract_is_active(self):
        self.assertEqual([], validate(ROOT))
```

Run `python -m unittest scripts.baseline.tests.test_r1_receipt_recovery_contract -v` in the locked Python environment and retain actual exit code/failure. Expected assertion failure on missing recovery validator or inactive contract; unrelated environment failure is not RED.

- [x] **Step 2: Implement the approved contract, not a new design.** ADR prose and closed `Profile | Key | Value` registries must cover every spec clause: V2 nine-public command metadata shape (including original Draft selector and qualified Evidence pair), exact original Actor/on-behalf and scopeDigest binding, constrained internal lookup, current Owner authorization distinct from rejected attempted input, fixed capture organization anchor, full current authorization-set digest, receipt row hash, audit-before-disclosure, all error/delta rows, no-store and legacy original-request replay. Current Audit V1 remains for internal recovery. State the exact V830/runtime semantic exception and prohibit generalization. Version pointers must resolve to this ADR; no permissive accept-any-version paths.

- [x] **Step 3: Add semantic registry and integration mutation tests.** Follow existing `copy_valid_repository`/temporary-copy pattern with valid-control assertions before each mutation, non-vacuous changed-value assertion, and restored-control acceptance. Derive an exact allowed registry independently in validator; reject duplicate/unknown/missing rows and fenced/commented decoys. Keep parsed OpenAPI comparison structural rather than formatting-sensitive. Required mutation families:

  1. Remove/unknown/duplicate metadata profile or binding field; omit Draft original selector, Evidence exact pair, scopeDigest or Actor/on-behalf constraint; permit raw payload/credential/PII or unrestricted lookup.
  2. Replace current authorization with historical ALLOW, generic source Grant, changed capture organization, or omit current natural-key Lead DENY/Evidence four Subjects; rerun rejected attempted candidate eligibility.
  3. Disclose before Audit commit, cache/304, inaccurate receipt hash columns or incomplete authorization digest binding; wrong success/refusal/ack-loss delta.
  4. Legacy text parsing/backfill, internal recovery through public GET, POST same-key replay adding Audit, new business outcome or automatic new key.
  5. Stale/unknown active version, missing ADR/runtime/HTTP/Workbench pointers, extra operation/security/input/DTO/error, changed physical/grant invariants or illicit delivery-state promotion.

  Example fixture mutation pattern (implement `copy_valid_repository` with the validator's exact input files, and use actual ADR registry row literals):

```python
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    self.copy_valid_repository(root)
    adr = root / 'docs/adr/ADR-0013-r1-command-receipt-recovery.md'
    original = adr.read_text(encoding='utf-8')
    altered = original.replace('R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1',
                               'UNRESTRICTED_AUDIT_LOOKUP')
    self.assertNotEqual(original, altered)
    adr.write_text(altered, encoding='utf-8')
    self.assertTrue(validate(root))
    adr.write_text(original, encoding='utf-8')
    self.assertEqual([], validate(root))
```

- [x] **Step 4: Verify the finite unit.** First focused new unittest; then full baseline discovery `python -m unittest discover -s scripts/baseline/tests -v`, existing topology verifier tests, and real `python scripts/baseline/verify_baseline.py` against the worktree. Use locked `python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970` and requirements file; mount worktree read-only. For real CLI only, mount git common dir read-only and set GIT_DIR/GIT_COMMON_DIR/GIT_WORK_TREE to Linux mount paths. Do not export those variables into unittest suites creating temporary repositories.

  Run existing `./mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest,ArchitectureTest,RuntimeRoleTest test` with pinned local JDK25. Verify parsed OpenAPI against base: only receipt GET descriptive/cache semantics may differ; no schema/error/security shape differences. Verify `git diff --name-only BASE -- database/schema-contract-52-plus-2/contract database/schema-contract-52-plus-2/generated contracts/events backend/src/main` is empty for this unit, including no production WIP edits. Record actual full output/exit/counts, expected downstream R2 BLOCKED gates, and inherited warnings without relabeling them runtime acceptance.

- [x] **Step 5: Scoped commit and handoff.** `git diff --check`; stage only this unit's named docs/validators/tests, inspect cached names, then `git commit -m "feat(contracts): activate bounded R1 receipt recovery protocol"`. Never `git add .`. Write full report with RED/GREEN, test commands/counts/exits, changed files, clause coverage, frozen-surface checks, self-review and concerns. Root performs fresh independent spec AND quality review, resolves findings through the same implementer, then committed verification. Only after that gate may root resume the original Task8 implementer with the accepted contract and unchanged original Task8 acceptance scope.

Acceptance: implementation `b6e33ce`, independent spec and quality approved, root committed verification passed; [progress and evidence](../../progress/2026-09-08-r1-receipt-recovery-contract-progress.md). Original Task8 runtime remains separate and incomplete.

## Root preflight and execution choice

The user already selected subagent implementation plus independent review; reuse that choice without another approval prompt. One contract task is intentionally atomic: its ADR, successors and verifier cannot be accepted independently. Root self-review maps spec §§1–2 to constraints, §§3–7 to steps 2–3, §8 to steps 4–5, and §9 to actual evidence rather than new runtime claims. Runtime implementation remains original Task8, not a hidden second task in this contract plan.
