# R1 Ingress QUERY Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved four-column QUERY capability successor and pass its finite migration/permission gates before resuming original closure Task 5.

**Architecture:** Append one permission-only ContractEvolution after V850. Its generated SQL, current manifest, runtime verification and active contracts land as one reviewed unit; historical migrations and evidence remain immutable. Owner consumers and audited disclosure are original Task 5, not this amendment task.

**Tech Stack:** Existing Python generator/verifiers, PostgreSQL/Flyway locked images, Java 25, Maven, jOOQ and existing integration harness; no dependencies added.

**Spec:** `docs/superpowers/specs/2026-09-06-r1-ingress-query-capability-design.md` (APPROVED).

## Global Constraints

- Only `${app_query_role}` gains column-level SELECT without GRANT OPTION on `lead.lead.ingress_completion_phone_hmac`, `lead.lead.ingress_completion_email_hmac`, `lead.lead.ingress_completion_phone_ciphertext`, `lead.lead.ingress_completion_email_ciphertext`.
- QUERY still cannot SELECT `ingress_completion_source_code`, `ingress_completion_source_summary_ciphertext`, `ingress_completed_by_appointment_id`, `ingress_completed_at`, `ingress_completion_digest`; whole-row SELECT and writes remain denied.
- COMMAND, WORKER, AUDIT, PUBLIC and login direct grants/memberships remain unchanged. API remains NOINHERIT, but explicit QUERY role selection gains exactly the four approved reads; Worker cannot assume QUERY.
- Preserve every V001–V850 byte and historical stage/evidence assertions. Append `V860__lead_ingress_query_read_capability.sql`, physical capability `52-plus-2-v1.2`, `ADR-0010`, baseline `MVP-2026-09-06.2`.
- No schema/table/column/type/constraint/index/FK/business-trigger/business-data mutation; retain 13 schemas and 52 application + 2 technical tables.
- OpenAPI/public DTO/15 operations/event schema/Task-command codes/completion matrix semantics/module DAG remain unchanged. No new dependency or product scope.
- No push, merge, deploy, external environment changes, capacity-vector repair or Tasks 6–10. Do not promote R1-BACKEND, SPA, E2E, capacity or release status from this gate.

### Task 1: Append and verify the exact capability successor

**Binding specification and constraints:** Read `docs/superpowers/specs/2026-09-06-r1-ingress-query-capability-design.md` completely. All its constraints apply; this task implements only acceptance items 1–3. Use only the four literal approved columns and successor identifiers it names.

#### File map

- Create `database/schema-contract-52-plus-2/contract/evolutions/v860_lead_ingress_query_read_capability.py`; register in `contract/evolutions/__init__.py`.
- Generate `database/schema-contract-52-plus-2/generated/db/migration/V860__lead_ingress_query_read_capability.sql` and current manifest/generated documentation with existing `generate.py`; no handwritten generated output.
- Adapt `database/schema-contract-52-plus-2/runtime/verify_runtime.py`, `runtime/sql/assert_schema_contract.sql`, `runtime/sql/assert_capabilities.sql` and relevant `runtime/tests/test_runtime_harness.py`, `test_ci_artifact.py`, `test_hosted_evidence_promotion.py` for current successor while preserving historical artifact decoding/binding.
- Add schema generator behavior tests in `database/schema-contract-52-plus-2/tests/test_generated_sql.py`; existing V850-stage tests remain intact.
- Create `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/IngressQueryCapabilityIT.java`; narrowly extend `testing/PostgresIntegrationTest.java` test-only target/upgrade fixtures if necessary. Preserve existing `CapabilityRoleExecutorIT` and ingress mutation tests.
- Create `docs/adr/ADR-0010-lead-ingress-query-read-capability.md`; update `docs/baseline/CURRENT-MVP-BASELINE.md`, `docs/progress/MVP-DELIVERY-LEDGER.md`, `docs/progress/2026-09-06-r1-local-progress.md`, `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`, current READMEs and active R1 contract references strictly where this successor changes an active assertion.
- Update corresponding consumers in `scripts/baseline/verify_baseline.py`, `r1_command_contract.py`, `r1_business_closure_contract.py` and their tests. Amend original closure spec/plan with a named supersession note, not a historical rewrite.

**Files:** The file map above is this single task's bounded write surface. Supporting current runtime artifact profiles/schema fixtures may change only to distinguish new evidence from immutable historical evidence; document their exact paths and rationale in the report. Do not implement production Owner/query/HTTP consumers here.

**Interfaces:**
- Consumes existing `ContractEvolution(version, migration_name, contract_version, apply, render_sql)` and ordered `EVOLUTIONS`; `apply` returns the unchanged schema tuple for permission-only evolution.
- Produces ordered V860 and a current manifest accepted only with the matching full migration inventory/contract version/hash by existing runtime/readiness validators.
- Produces no new Java production interface; original Task 5 may then use explicit Owner SELECT of approved columns on its caller-owned QUERY connection.

- [ ] **Step 1: Establish baseline and independent expected assertions.** Record HEAD and SHA256 of every existing migration, OpenAPI and event schema. Run existing focused capability/Owner refresh regression before edits. Use pinned runtimes, not system Java.

```powershell
$env:JAVA_HOME='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1'
./mvnw.cmd -f backend/pom.xml verify -Pit '-Dtest=ArchitectureTest,R1ResourceTagsTest' '-Dit.test=CapabilityRoleExecutorIT,ActionDraftIT#refresh_reads_exact_task_lead_draft_and_independent_owner_sources_under_query'
```

- [ ] **Step 2: Write and run behavioral RED tests.** In a real fresh migrated database assert four column reads succeed under QUERY (expected current failure: SQLSTATE 42501), each forbidden column and SELECT * fails with 42501, INSERT/UPDATE/DELETE/TRUNCATE fail, and no GRANT OPTION exists. Use literal expected sets; never derive expected permissions from the production evolution. Preserve separate historical V850 test expecting all nine denied. Capture actual RED before adding production evolution; compiler/fixture errors are not RED evidence.

```sql
SET ROLE ontology_app_query;
SELECT ingress_completion_phone_hmac, ingress_completion_email_hmac,
       ingress_completion_phone_ciphertext, ingress_completion_email_ciphertext
FROM lead.lead WHERE false;
-- Use harness-provided actual role names; this illustrative role is not a new role.
```

Add generator tests that compare old generated bytes to the pre-change fixture and enforce an appended migration, not an edited V850. Real integration tests, not generated SQL text alone, establish grants and transactional effects.

- [ ] **Step 3: Implement the smallest evolution and regenerate.** Use the existing renderer pattern and an unchanged schema `apply`; execute the following exact grant, transition deployment state only from v1.1, increment revision once, then validate four allowed/five denied/no grant option and retained non-QUERY permissions. Failure raises and rolls back the entire Flyway transaction. No permission-repair/revoke mechanism or broad permission framework.

```sql
GRANT SELECT (
    ingress_completion_phone_hmac,
    ingress_completion_email_hmac,
    ingress_completion_phone_ciphertext,
    ingress_completion_email_ciphertext
) ON TABLE lead.lead TO "${app_query_role}";
UPDATE platform_meta.deployment_state
SET schema_contract_version = '52-plus-2-v1.2',
    revision = revision + 1, changed_at = clock_timestamp()
WHERE deployment_state_key = 'PRIMARY'
  AND schema_contract_version = '52-plus-2-v1.1';
-- In a DO block, IF NOT FOUND must raise SQLSTATE 55000.
```

```python
def apply_evolution(schemas):
    return schemas
```

Run from schema directory: `python generate.py`, then `python generate.py --check` using the locked Python container listed below. Update current runtime profile to migration maximum 860/count 21/deployment revision 2 while retaining v1.1 maximum 850/count 20/revision 1 and v1 historical evidence. If an evidence schema requires a successor, add its explicit version/profile; never rebind old evidence to new contract hashes.

- [ ] **Step 4: Cover upgrade, failure and fail-closed verification.** Extend real test-only Flyway fixtures for target 850, seed representative Lead/ingress business data through existing valid fixtures, record business row values and non-QUERY ACL/membership fingerprints, migrate to latest, and compare all retained values. Inject an invalid prior deployment version so V860 fails after its grant attempt; inspect with a fresh admin connection and prove no four-column grant or deployment-state change persisted. Restore the deliberately corrupted test fixture and retry; validate checksums and no-op restart. Test fresh migration separately. Retain one-shot ingress write/overwrite/clear rejection tests. Execute existing readiness/manifest boundary validators with the valid v1.2 inventory and wrong version/hash/omitted V860 inputs; invalid inputs must reject, not auto-repair. No new production HTTP readiness assembly is introduced.

- [ ] **Step 5: Activate contracts coherently.** ADR-0010 records user approval and only this named exception. Synchronize baseline ID/current version/hash, runtime contract, active closure spec/plan notes and verifiers. Keep old evidence files byte-identical; delivery ledger must distinguish current local unverified/verified state from old hosted RUNTIME_VERIFIED. Update active references individually; preserve historical prose/fixtures. Add mutation tests to the verifier consumers for wrong successor or unauthorized grant changes. Run them against controlled repository fixtures, not prose-only assertions.

- [ ] **Step 6: GREEN and finite regression.** Run focused new IT first, then all backend regression once, generator check, schema/runtime/baseline Python suites, and real baseline verifier on committed artifacts. Record exact test identities/counts, commands, elapsed times, exit codes and unique raw log paths. Existing expected negative-case diagnostic output must be labeled accurately.

```powershell
./mvnw.cmd -f backend/pom.xml verify -Pit '-Dtest=ArchitectureTest,R1ResourceTagsTest' '-Dit.test=IngressQueryCapabilityIT,CapabilityRoleExecutorIT'
./mvnw.cmd -f backend/pom.xml verify -Pit
```

Locked Python image: `python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`; mount this worktree at `/work`, install `scripts/baseline/requirements.txt` (PyYAML 6.0.3), run:

```sh
export PYTHONPATH=/work/database/schema-contract-52-plus-2
python -m unittest discover -s database/schema-contract-52-plus-2/tests -v
python -m unittest discover -s database/schema-contract-52-plus-2/runtime/tests -v
python -m unittest discover -s scripts/baseline/tests -v
python database/schema-contract-52-plus-2/generate.py --check
python scripts/baseline/verify_baseline.py
```

For real verifier only, mount the actual common Git directory read-only and pass Linux GIT_DIR/GIT_COMMON_DIR/GIT_WORK_TREE overrides. Do not pass these variables to unit suites that create temporary repositories. Hosted-evidence fixture suites also read source Git history: resolve the exact source gitfile through read-only container mount routing or a container-owned source clone, without changing host gitfiles; hydrate a specifically missing partial-clone object using host Git when necessary. Use current PowerShell 7; do not launch a legacy powershell.exe script that misdecodes the Chinese common Git path. PostgreSQL image comes from repository `runtime/toolchain.lock.json`.

- [ ] **Step 7: Self-review and commit the complete unit.** Compare historical migration/OpenAPI/event hashes; confirm no business production files changed. Commit all approved source/generated/contracts/tests together with `feat: add bounded ingress query read capability`. Write report with RED/GREEN, current-vs-historical evidence, remaining Task 5 obligations and exact changed file list. Independent reviewer receives the full original BASE..HEAD diff and approves both spec and quality before original Task 5 resumes.

## Exit and continuation

This plan fulfills spec acceptance items 1–3 only. Items 4–6 (matching, effective contact/AAD, seven-card audited read/disclosure) remain original closure Task 5 and begin only after this gate. Original Tasks 6–10 remain excluded. No whole-branch completion, deployment, frontend or capacity claim is authorized.

## Plan self-review

Coverage: exact grants/denials and invariants in steps 2–4; immutable history/generation in 1/3/7; upgrade/rollback/readiness in 4; current-versus-historical contracts in 5; finite evidence/review in 6–7; Task 5 consumers explicitly deferred to their original task. One coherent unit avoids intermediate active-contract mismatch. No public interface or product feature is introduced here.
