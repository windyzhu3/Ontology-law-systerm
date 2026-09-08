# Task9.1 implementation report

Status: REVIEW SNAPSHOT — bounded contract implementation delivered for review; final full baseline verification pending. No Task9 runtime, login, UAT, Task10 or R1 release completion claim.

Review BASE: 0cf0646. Worktree: C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business. Root separately owns approval/progress/deployment files and commits dee06ea/389eb55. This report covers the contract implementation agent's files.

## Scope and exact successor

MVP-2026-09-08.2 / HTTP V1.4 / Command V1.3 / Workbench V1.2 / Identity V1.0 / OpenAPI1.3.0. New actual HTTP inventory37 operations,32 publicBearer,5 internalMutualTls; exact21 new method/path/operationId pairs and14 static HUMAN/DIRECT management mutations. Existing16 pairs/security retained. All32 public operations explicitly accept X-Appointment-Id as an authentication selector; existing delegate only receives the generated optional argument, with trusted validation explicitly deferred to Task9.2 and no handler/auth/runtime change.

The closed Identity contract freezes DTO required/nullable/bounds/conditions, HUMAN-only lists/candidates,8 grantable R1 codes,3 online role labels without grant implication, root-scope Principal/new-Principal handling, CAS Identity ETag, lifecycle dependencies, exclusive lock sequence, exact Slot/Receipt/Audit deltas, self context without a fabricated Appointment, original-Actor/current-authorization receipt recovery, bounded audited disclosure, offline bootstrap11-row initial delta (8 Identity rows plus Slot/Receipt/Audit), and session/recovery marker boundaries. Keycloak/password/toolchain policy references the root-owned deploy/identity artifacts. No new table, GRANT, role membership or migration change.

Provider candidate uses encrypted/authenticated AES-256-GCM opaque selection (5-minute expiry, realm/Actor/search binding), no server session table/raw subject. Original-key terminal replay happens before fresh candidate expiry/availability checks. Bootstrap SYSTEM is an offline authorization-origin exception, not a Principal kind; initial four grants point granted_by to founder Appointment, Audit explicitly attributes founder target/operator assertion with nullable appointment. No public bootstrap or online self-grant exception.

## Changed files

- New ADR-0014 and R1-IDENTITY-ACCESS-CONTRACT; active baseline, HTTP/Command/Workbench and runtime-validation successors.
- Single OpenAPI plus normally regenerated workbench schema.d.ts; removed only PublicReceipt temporary NOT_FOUND union.
- R1ApiDelegate generated optional selector signatures only (root explicitly approved this compile compatibility change).
- OpenApiContractTest: exact37 inventory/33 paths, new request/response/security/ETag fixtures, precise admin selector exception, no-Appointment state and duplicate-option schema validation.
- New scripts/baseline/task9_identity_contract.py and test_task9_identity_contract.py; old baseline/command/contact/readiness/receipt guard active-version/inventory/ledger alignment; synthetic fixture updates preserve original mutation assertions.
- scripts/verify_topology.py and tests/test_topology.py: explicit independent Keycloak storage/pinned dependency guard.

## Actual validation evidence

All commands ran in this worktree. Python uses python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970 and PyYAML6.0.3. Node24.20.0/npm11.9.0/JDK25.0.4.1+1 are from C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb. No global tool upgrade or GIT_DIR override for temporary-repository tests.

| Run | Command/result | Evidence |
|---|---|---|
| Parent baseline | Before source edits: pinned full baseline289 cases PASS; topology31 PASS | root-owned report/log |
| Expected feature RED | python -m unittest scripts.baseline.tests.test_task9_identity_contract -v:4 failures, exit1. Actual37!=16 and explicit missing named guard assertion; no environment failure | tool output, excerpt below |
| Task9 initial GREEN | same command:4 cases PASS, exit0 | tool output |
| Task9+topology final | python -m unittest scripts.baseline.tests.test_task9_identity_contract tests.test_topology -v:37 cases PASS, exit0 (5 new Task9 cases plus32 topology) | task9-topology-final.log |
| Generated TypeScript | npm run openapi:generate, npm run openapi:check, npm run typecheck all exit0 | tool output |
| Frontend regression | npm test:7 files/75 cases PASS, exit0 | tool output; parent independent rerun also75 PASS |
| Frontend build | npm run build: tsc and Vite pass, exit0 | tool output |
| Java generation diagnostic | Initial compile failed only new uniqueItems→Set output importing unavailable Jackson2 JsonDeserialize | tool output |
| Java resolution | Equivalent array allOf(uniqueItems) produces List<IdentityRoleCodeV1>/List<GrantableAuthorityCodeV1>; no dependency/generator version change. JSON schema valid lists accepted, exact duplicates rejected | OpenApiContractTest |
| Java contract | ./mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest test:19/19 PASS, exit0; final repeat pending at snapshot | java-contract.log; java-contract-final.log |
| Actual HTTP regression | ./mvnw.cmd -f backend/pom.xml -Pit -Dtest=OpenApiContractTest -Dit.test=R1CommandHttpIT verify:11 HTTP cases PASS plus19 contract cases, BUILD SUCCESS exit0 | http-regression-final.log; backend/target/failsafe-reports |
| Exact preservation | Nine business request DTOs and47 transitive request schemas equal BASE; original16 method/path/id/security equal BASE | preservation-final.log |
| Physical/event preservation | git diff --exit-code 0cf0646 -- contracts/events database/schema-contract-52-plus-2/generated backend/src/generated/jooq: exit0, no diff | tool output |
| Complete baseline diagnostics | Earlier82-case run had443 failing subtests due stale full-schema fingerprints and old-fixture controls during updates; earlier202-case run had112 failures predominantly new baseline MERGED fixture evidence missing. These are integration diagnostics, not feature RED | contract-python.log; baseline-python.log |
| Final complete baseline | python -m unittest discover -s scripts/baseline/tests -v currently running after fixture/metadata corrections | baseline-final.log |
| Real baseline/topology CLI | Pending tracked-index final snapshot check using root-provided verify-baseline-locked.ps1 | pending |

HTTP cases: seven_generated_primary_operations_commit_exact_facts_and_original_receipt_projection[7]; capture_generated_operation_uses_inputless_authority_context_and_same_key_original_receipt; draft_save_update_and_old_key_replay_keep_original_receipt_but_current_authorized_projection; rejected_inactive_assignee_or_stale_candidate_receipt_is_recovered_without_new_command_eligibility[2]. Actual PostgreSQL/HTTP regression is existing business behavior only, not new real-login acceptance.

Feature RED excerpt: `test_task9_inventory ... FAIL`, `AssertionError: 37 != 16`; `test_successor_contract_is_active ... FAIL`, `AssertionError: Task9 fail-closed contract validator is not implemented`; `Ran 4 tests ... FAILED (failures=4)`, exit1.

## Remaining review/verification work

Wait for final complete baseline log; fix only real remaining fixture/contract issues without suppressing semantic mutations. Run actual tracked baseline/topology CLI and final generator drift checks, preserve logs, then update this report and commit final evidence. Independent reviewer dispatched by root must review final diff; contract textual precision may need review refinements. No new capability or production deployment is requested. Actual IdP configuration, trusted resolver selection, management execution, bootstrap runtime and real-user UAT remain Task9.2–9.6 unimplemented.
