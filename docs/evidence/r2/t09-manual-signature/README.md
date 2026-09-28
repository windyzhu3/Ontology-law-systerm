# T09 manual signing implementation and acceptance

Status: LOCAL_IMPLEMENTATION_AND_ACCEPTANCE_COMPLETE. N and N1 are approved. R1 remains PAUSED and R2 release acceptance remains NOT_GRANTED.

## Implemented scope

Seven named commands cover draft, arrangement confirmation, evidence submission, authorized verification, complete archive, sales revision return and verifier revision return. Contract signing is manual and human-confirmed. No electronic signing, payment decision, execution decision, transfer approval or case creation is introduced.

The exact approved template carries immutable firm Party/profile binding. New templates are bound at creation. Existing approved versions and participation facts are never rewritten; unmapped templates require explicit revision. Signed evidence can differ bytewise from the approved unsigned body and is checked by an authorized person.

Readiness consumption and authority restoration preserve the original deadline. Multiple eligible verifiers use deterministic routing. With no eligible verifier, the durable exception remains visible in the contract ledger and existing authority administration restores eligibility; no supervisor task is falsely reported as assigned.

UI uses approved N/N1 and the existing workbench shell, shared controls and ledger/task navigation. Supplement stays bound to the returned signing item. Correction retains old material and verification history in the left context column.

## Verification evidence

- `output/sign-final-backend3.log`: 141 selected unit tests, 0 failures/errors, 1 existing skipped; 14 actual Owner integration cases + 4 schema integration cases + 1 migration integration case, all passed.
- `output/sign-frontend-final.log`: 76 frontend files / 843 tests passed, including the final responsibility-continuation fix. Contract/transport focused suite previously passed 7 files / 57 tests; the continuation and R1 compatibility focused suite passed 4 files / 52 tests.
- `output/sign-spa-build-final.log`: type checking and local review build passed; existing bundle-size warning retained.
- `output/sign-baseline-final.log`: development admission and consistency passed, seven existing nonfatal release prerequisites retained.
- Schema evidence: ../t09-signature-schema/README.md.

- `output/sign-quote-production4.log`: actual accepted-quote production command flow through two required signers, archive and durable handoff passed; every original command key and receipt replayed, without execution or transfer facts.
- `output/sign-live-recovery-build.log`: signing recovery JSON binding, worker receipt validation, persisted checkpoint recovery, exact OpenAPI/command contract tests and package passed.

## Local recovery finding

Live browser acceptance exposed a committed signature task followed by HTTP 500: the generated two-variant recovery receipt interface had no JSON deserializer. The API binding and worker fact-type validation now select the exact preparation/signature variant. The original C44 recovery key returned its original successful receipt after deployment; the committed task was not duplicated.

A second live cause was the two-connection worker pool: concurrent checkpoint sessions held both connections while nested deployment checks needed another connection. Pool capacity now follows enabled scan concurrency (10 for this single-tenant four-scan environment, globally capped at 32; R1-only stays at 2). Temporary HTTP 5xx retry is bounded at 60 seconds, including restored checkpoints. Pending delivery retains its exact key, page position and original receipt; authorization backoff is unchanged and checkpoints are never erased. New RED/GREEN tests cover both pool capacity and persisted uncertain-command retry.

Completed scan rounds yield before rediscovery: owner-exception validation waits 60 seconds, other R2 scans wait 5 seconds. Pending pages still advance normally. This prevents repeated NO_CHANGE validation from continuously contending with interactive tenant locks. `output/sign-worker-idle-green.log` verifies the final scheduler and deployment changes; `output/sign-worker-http-final.log` verifies seven mutual-TLS client integration tests, including all four preparation/signature recovery sources and generated response serialization.

The live worker acknowledged the original command and four subsequent readiness records, reached READY with no pending page, zero failures and zero rejections (`output/sign-worker-final-status.json`). `output/sign-variants.log` covers C46 SUPPLEMENT, C47 arrangement correction back to ARRANGE, and C48 return to contract revision. All eight branch commands replayed the exact original receipt through the real API.

The real browser also exposed the workbench's 30-second poll rereading a completed signing task. Confirmed contract receipts now follow the next task only when it belongs to the same appointment and remains actionable; editing/submission pauses automatic timer/focus refresh for the embedded contract. R1 form refresh behavior remains unchanged. The next responsibility still loads through the existing authorized workbench endpoint and shared shell. RED/GREEN evidence: `output/sign-continuation-red.log`, `output/sign-continuation-green.log` and the full frontend suite.

C44 ledger-to-workbench browser acceptance passed at 1440/390/360 with no horizontal overflow, one shared workbench shell and one primary action. Full C45 signing and the C46鈥揅48 browser checks remain in progress; this document does not yet grant whole-task acceptance.
