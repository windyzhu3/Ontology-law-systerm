# Task 1 Report: R1 semantic contract and baseline gate

## Status

Implemented Task 1 only. The authoritative Markdown contract freezes the four non-completion command policies, seven Draft authority mappings, fourteen R1 event descriptors, nineteen exact success-branch event sets, CONNECTED_VALID 2/2 cardinality, current-state projection behavior and delayed R2 consumer obligations. MVP semantic baseline is now `MVP-2026-09-05.2`. No Java, OpenAPI, physical schema contract, generated database artifact, migration, production Handler, R2 Task or management capability changed.

## TDD evidence

### RED

Command:

```text
python -m unittest scripts.baseline.tests.test_r1_command_contract -v
```

Observed before contract/schema/validator implementation: exit 1, `Ran 10 tests`, `FAILED (failures=10)`. Every failure named the missing canonical fixture `docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md`; this was the expected absence of the new contract, not a syntax/import error.

The first implementation iteration remained RED with 2 failures: the parser did not permit explanatory prose between a heading and its table, and the recovery test/table initially omitted the required TaskType/WaitProfile columns. Both were corrected to the approved contract before the focused GREEN run.

### Focused GREEN

Command:

```text
python -m unittest scripts.baseline.tests.test_r1_command_contract -v
```

Final output: exit 0, `Ran 10 tests in 0.355s`, `OK`. The suite copies the actual canonical Markdown and JSON Schema into a writable temporary fixture, asserts every replacement matches exactly once, and rejects missing capture policy, recovery authority-code swap, missing OpportunityOpened, wrong Opportunity source selector/queue, duplicate event, duplicate branch, permissive/nonempty payload and CONNECTED_VALID 1/1 counts.

Discovery check:

```text
python -c "import unittest; s=unittest.defaultTestLoader.loadTestsFromName('scripts.baseline.tests.test_verify_baseline'); print(s.countTestCases()); print(any('R1CommandContractTest' in str(t) for group in s for t in group))"
```

Output: `185` and `True`. CI's existing `test_verify_baseline` target therefore executes all 10 new tests rather than leaving the new module undiscovered.

## Full verification

Python 3.12 Linux command (pinned image, repository mounted read-only):

```text
docker run --rm --mount "type=bind,source=C:/Users/Jacob/.cache/codex-worktrees/ontology-law-c0,target=/repo,readonly" -w /repo python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970 python -m unittest scripts.baseline.tests.test_verify_baseline -q
```

The first post-change full run exposed one stale test expectation: two subtests still accepted `.1` and rejected `.2`; `Ran 185 tests in 140.220s`, `FAILED (failures=2)`. After updating that exact approved-successor expectation, the next run passed 185/185. Following final self-review corrections (contact branches name the actual `RECORD_CONTACT_RESULT` command, LeadCapturedV1 selects the transaction-final Lead revision, and new authoritative docs participate in relative-link validation), the final fresh run exited 0: `Ran 185 tests in 142.273s`, `OK`.

Schema generation command:

```text
cd database/schema-contract-52-plus-2
python generate.py --check
```

Final output: exit 0, `schema contract is deterministic and generated files are current`.

Schema tests command:

```text
cd database/schema-contract-52-plus-2
python -m unittest discover -s tests -q
```

Final output: exit 0, `Ran 57 tests in 4.132s`, `OK`.

Diff hygiene command:

```text
git diff --check
```

Final output: exit 0 with no findings.

## Files

- Created `docs/adr/ADR-0007-r1-command-policy-event-closure.md`.
- Created `docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md`.
- Created `contracts/events/r1-domain-notification-v1.schema.json`.
- Created `scripts/baseline/r1_command_contract.py` and `scripts/baseline/tests/test_r1_command_contract.py`.
- Updated `scripts/baseline/verify_baseline.py` and `scripts/baseline/tests/test_verify_baseline.py`; CI target discovery is 185 tests.
- Updated `.github/workflows/baseline-consistency.yml` so event-Schema-only changes trigger the gate.
- Updated `docs/baseline/CURRENT-MVP-BASELINE.md`, `docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md`, `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`, `docs/adr/ADR-0006-command-runtime-authorization-boundary.md`, the approved closure spec, `README.md` and `docs/progress/MVP-DELIVERY-LEDGER.md`.

## Self-review and limitations

- The validator parses exact controlled tables, rejects duplicate/missing/unknown rows, cross-checks referenced event sets and computed cardinalities, and requires the exact JSON Schema 2020-12 empty-object shape. The Markdown remains the single mutable registry; no second JSON event registry was introduced.
- ADR-0006 remains ACCEPTED history; ADR-0007 supersedes only its four previously missing policy/event descriptors. Original receipt, failure, lock, savepoint and commit-confirmation-loss semantics remain active.
- `R1-BACKEND`, `R1-SPA`, `R1-E2E-GOLDEN` and `R1-E2E-FAILURES` were not created or advanced. The ledger records the new contract as `FROZEN`, not implemented.
- Windows cannot execute the symlink test without privilege (`WinError 1314`); no test was skipped or weakened. The pinned Linux suite is the full baseline evidence.
- Direct `python scripts/baseline/verify_baseline.py` in this sparse worktree reports the known environmental findings: historical MERGED evidence cannot be resolved from the sparse history, and the omitted visual bundles contain 0/27 sales PNGs and 0/7 identity PNGs. These are not represented as a local repository-verifier PASS; the complete checkout remains the merge gate.
- The authorized parent plan-only commit `661ae9393f5e1fddbe0dd011a51cfdd9efb36810` precedes this task commit and is not staged as part of Task 1.
