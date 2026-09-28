# T09 manual signature schema evidence (local only)

Date: 2026-09-23. Scope: named V990 persistence contract and v13 compatibility, not whole signing workflow acceptance or deployment.

## Implemented contract

- V990 / 52-plus-2-r2-v13 appends one migration; V001-V980 are preserved and the named historical projection proves the independently pinned V980 manifest.
- Nine immutable supporting tables preserve explicit approved-template firm identity, arrangements, drafts, submissions, verification, archives, revision returns, workflow chains and the durable handoff. Existing signature_plan and contract_signature remain the signing facts.
- Existing frozen contract_participation rows are not rewritten. Firm identity is an exact existing ORGANIZATION Party/profile sealed with a newly approved template in the same transaction; older templates cannot be retrofitted. Plans explicitly reference either contract participation or template signer binding, and every configured firm is required.
- Accurate current readiness, source and body evidence are rechecked for passing actions. A nonpassing REVISION_REQUIRED return may close an invalid basis but still requires the exact current revision/readiness identity. Old arrangements and signatures cannot satisfy a new arrangement.
- Single roots and predecessor uniqueness enforce append-chain CAS semantics. Arrangement collections and passing verification/signature pairs seal in one transaction. Archive rechecks every required signature and authority material, and the handoff seals with that archive. No execution/payment/case fact or unhandleable successor task is created.

## Verification observed

- Python schema suite: 107 tests passed after the template binding addition.
- Named V13 and V12 successor projection tests: 4 passed. Exact historical migration and field-document digests remain required.
- Generated SQL parser: 34 PostgreSQL migrations and 72 PL/pgSQL functions passed; generate.py --check passed.
- Actual PostgreSQL run in output/sign-boundary-migration7.log: R2ManualSignatureSchemaIT 4 tests passed; R2SignatureMigrationIT 1 test passed. Failsafe reports confirm zero failures/errors in those two classes. The four direct schema probes cover per-arrangement plan reuse without participant mutation, collection completeness/sealing including omitted firm, rejected late template signer binding, and archive rejection before required signatures.
- RuntimeSchemaVersionTest 1 test and R1WorkerDeploymentTest 7 tests passed after observed v13 compatibility RED failures. V12 stays allowed, V14 remains rejected, expected digests remain validated, and worker switches stay opt-in.
- Fresh development admission check passed again after the final template metadata addition, with seven existing nonfatal readiness blockers. R1 PAUSED and R2 NOT_GRANTED remain unchanged.

## Limits and remaining integrated checks

The combined run7 was NOT all green: nine Owner readiness-discovery tests failed in the parallel workflow implementation. This report does not represent those failures as passing schema evidence or claim whole T09 completion. An earlier broad baseline unittest run was invalidated by concurrent schema changes and was cancelled on the root agent's instruction; no full baseline-suite pass is claimed. No deployment, commit or reset was performed by the schema subtask.


Final integration follow-up: earlier Owner failures were corrected and verified. See [T09 final local acceptance](../t09-manual-signature/README.md). Historical failed runs above remain recorded as failed.
