# Task9.1 implementation report

Status: REVIEW CORRECTION SNAPSHOT — three Important findings addressed; stable-source final full baseline verification pending. No Task9 runtime, login, UAT, Task10 or R1 release completion claim.

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
| Java contract | ./mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest test:19/19 PASS, exit0; repeat after review correction also19/19 PASS,29.519s | java-contract.log; java-contract-final.log; java-contract-review-final.log |
| Actual HTTP regression | ./mvnw.cmd -f backend/pom.xml -Pit -Dtest=OpenApiContractTest -Dit.test=R1CommandHttpIT verify:11 HTTP cases PASS plus19 contract cases, BUILD SUCCESS exit0 | http-regression-final.log; backend/target/failsafe-reports |
| Exact preservation | Nine business request DTOs and47 transitive request schemas equal BASE; original16 method/path/id/security equal BASE | preservation-final.log |
| Physical/event preservation | git diff --exit-code 0cf0646 -- contracts/events database/schema-contract-52-plus-2/generated backend/src/generated/jooq: exit0, no diff | tool output |
| Complete baseline diagnostics | Earlier82-case run had443 failing subtests due stale full-schema fingerprints and old-fixture controls during updates; earlier202-case run had112 failures predominantly new baseline MERGED fixture evidence missing. These are integration diagnostics, not feature RED | contract-python.log; baseline-python.log |
| Complete baseline diagnostic2 |294 tests/725.916s/17 failures:1 stale baseline mutation literal;1 null-operation guard crash;15 exact legacy assertions gained the same spurious finding because a mid-run guard edit temporarily rejected valid enum null/bool. This changing-source diagnostic is NOT acceptance | baseline-final.log |
| Real baseline/topology CLI | verify-baseline-locked.ps1 at74697c4: baseline consistency PASS; topology PASS; exit0. R2 stays BLOCKED with7 non-fatal out-of-scope blockers | cli-final-v2.log; root independent repeat |

HTTP cases: seven_generated_primary_operations_commit_exact_facts_and_original_receipt_projection[7]; capture_generated_operation_uses_inputless_authority_context_and_same_key_original_receipt; draft_save_update_and_old_key_replay_keep_original_receipt_but_current_authorized_projection; rejected_inactive_assignee_or_stale_candidate_receipt_is_recovered_without_new_command_eligibility[2]. Actual PostgreSQL/HTTP regression is existing business behavior only, not new real-login acceptance.

Feature RED excerpt: `test_task9_inventory ... FAIL`, `AssertionError: 37 != 16`; `test_successor_contract_is_active ... FAIL`, `AssertionError: Task9 fail-closed contract validator is not implemented`; `Ran 4 tests ... FAILED (failures=4)`, exit1.

## Review corrections and stable targeted verification

Owned commits6e9a0e2 (contract implementation),74697c4 (real-test topology guard). Root review01 found no Critical and three Important issues; fixes are limited to the approved contract/guard boundary:

- Offline bootstrap now names R1_IDENTITY_BOOTSTRAP_CANDIDATE_V1: approved operator in restricted offline environment, exact unique fixed-realm directory lookup, separate authenticated-encryption purpose, operator/target binding, no Actor/session/table/endpoint; dry-run zero writes; NEW requires fresh existing account; complete original-key/digest/set verification can precede freshness but never envelope integrity. This is the already-approved offline trust root, not new authority.
- createAppointment is ROOT consistently in Identity registry, OpenAPI x-subject-binding and HTTP matrix; existing Appointment lifecycle operations remain SCOPED. This metadata-only OpenAPI correction regenerates identical TypeScript DTO bytes.
- Malformed path/operation/security/components/schema/properties produces findings rather than a traceback. Primitive enum null/bool remains legal JSON Schema; malformed nested shapes remain fail-closed. Existing malformed readiness integration exercises both total verifier and actual CLI, preserving exact stdout/stderr assertions. Old baseline mutation now replaces active .2 instead of searching for obsolete .1.

RED: guard-malformed-red.log has2 failures (old literal and actual null-operation AttributeError); task9-malformed-red.log has1 test with5 malformed-shape errors; review-correction-red.log has1 assertion failure for absent offline profile. The first attempted shape fix incorrectly rejected legal primitive enum values (guard-malformed-green.log,8 tests/5 failures), corrected without weakening mutations.
GREEN: guard-malformed-green-v2.log8 tests/25.604s; review-correction-green.log9 tests/23.117s; all15 affected old exact diagnostic assertions separately pass in51.187s (baseline-diagnostic-targets-green.log). Final Task9+topology40 tests/7.983s PASS (review-topology-green.log). Pinned Node openapi:generate/check/typecheck/test/build all exit0 after the correction;75 tests/7 files PASS at17:17:20. No source changes will run concurrently with the next full suite.

## Warning provenance and generation limitations

HTTP/regeneration logs are not warning-free. They retain OAS3.1 beta, default JsonInclude/JsonSetter annotation settings, failed null schema name, oneOf advisory, CurrentWorkCardEnvelope_currentCard discriminator/null and ModelNull naming, complex request example, JAXB XmlAccessType and Flyway already-exists warnings. CurrentWorkCardEnvelope schema, old request schemas, generator/dependency settings, JooqAuditAppender and generated migrations are unchanged; these point to existing surfaces, but this task does not claim a measured before/after count for every warning. Generic oneOf/complex-example warnings have no precise schema attribution in the generator output, so new IdentityProblemV1 conditional schemas may contribute; they are not asserted all pre-existing.

The concrete new generation failure was direct uniqueItems on IdentityAdminOptionsV1.roleCodes/grantableAuthorityCodes yielding Set and unavailable Jackson2 JsonDeserialize (original raw tool output retained in the task). Equivalent allOf uniqueness constraints keep typed List<IdentityRoleCodeV1>/List<GrantableAuthorityCodeV1> output. Valid arrays and exact duplicates are actually schema-validated in the19-case contract test. No Jackson2 dependency was added, no uniqueness or wire condition was relaxed, and successful Java compile/contract tests plus11 real business HTTP tests are the bounded mitigation evidence. They do not prove new Identity handlers/runtime validation are implemented.

## Remaining review/verification work

Run stable-source full baseline, final tracked CLI, and same-reviewer delta review; preserve diagnostics and append actual final evidence. No new capability or production deployment is requested. Actual IdP configuration, trusted resolver selection, management execution, bootstrap runtime and real-user UAT remain Task9.2–9.6 unimplemented.
