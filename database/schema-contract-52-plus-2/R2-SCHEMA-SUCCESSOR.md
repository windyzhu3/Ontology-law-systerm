# R2 independent Lead names schema successor

> Current implementation index (2026-09-28): [R2.5 v19 inventory](../../docs/baseline/R2.5-CURRENT-IMPLEMENTATION.md). Current generated inventory is 40 SQL migrations through V1050, 119 application tables and 122 physical tables after Flyway bootstrap. The 41st local Flyway history row is SCHEMA. Earlier successor sections below describe their own historical stages.

The approved R2 sales MVP adds `customerName` and `contactName` as independent optional SafeText200 capture facts. `capturedName` retains its frozen meaning. Omitted new keys are absent from the capture digest; supplied values are normalized with the existing text profile and included independently. Null, blank, control characters and overlength values are rejected.

`52-plus-2-r2-v1` appends V870 after V860: 22 migrations, 54 tables, 207 foreign keys, 53 mutation guards, deployment revision 3. V001-V860 and the historical v1/v1.1/v1.2 artifact expectations remain unchanged. The standard Flyway location and normal generated manifest include V870; there is no test-only migration.

The only model additions are nullable bytea `lead.lead.customer_name_ciphertext` and `contact_name_ciphertext`. They are immutable initial facts: existing controlled-update guards and column grants forbid later writes. COMMAND retains its existing INSERT and SELECT; QUERY receives column SELECT only. WORKER, audit and PUBLIC receive no capability. AES-GCM uses distinct CUSTOMER_NAME and CONTACT_NAME AAD fields with tenant binding; no plaintext name appears in receipt/event/audit summaries.

The normal live PostgreSQL jOOQ generator produces both fields. `CurrentLeadReader.Lead` appends `customerName()` and `contactName()` and retains its old constructor for legacy callers. Disclosure authorization and audit must complete before API projection releases these fields.

Runtime configuration must explicitly request `52-plus-2-r2-v1` and matching release/manifest hashes. RuntimeDatabase accepts this named successor while retaining exact version/hash matching and fail-closed capability validation. Historical hosted v1.2 runtime artifact verification is not R2 release evidence and remains unchanged. R2 local schema/domain tests and Maven PostgreSQL integration are development validation only; no deployment is authorized or claimed here.

## R2 development admission guard

Only `python scripts/baseline/verify_baseline.py --r2-development` enables `R2_SCHEMA_SUCCESSOR_V1`. Default verification and `--strict-r2` retain the exact historical v1.2 schema boundary; the development flag does not grant release acceptance or replace readiness evidence.

The profile pins the reviewed R2 canonical manifest hash `a946864d2dbedc78f62171e08ef5123cb36d5a6b98a52f938299087742a822be`, field document hash `b0de9450e0ada02a435f200ac62c729895de1093ab4a090c75cc797a8305b8f9`, and V870 hash `36799c6888c4b19b2338a1a884d046191e873ade0af4257c5fafe8c0bd94e548`. It checks all 22 migration paths and bytes and the actual field document. Missing, extra, modified, or relabelled artifacts fail closed.

After validating the full successor, the guard removes only V870 and the two appended name columns from an in-memory copy, restores the historical version and field-document hash, and verifies the original v1.2 canonical hash `a4beeb91ed93be455736eafa3abb829f6a94fed3a263be5996832e458b7c4b39`. The unchanged historical validator then checks all 21 retained migration hashes. This projection writes no files and never accepts changes to historical fields, constraints, grants, or migrations.

## R2 protected progress successor (V880)

Current development schema is `52-plus-2-r2-v2`: 23 migrations. The V870 description above remains historical. V880 adds nullable immutable `opportunity.opportunity_progress.progress_body_ciphertext` and `responsibility.task_occurrence.predecessor_task_occurrence_id`, a named progress body check, a tenant-scoped unique successor constraint, and a same-tenant self foreign key. No business table is added. R2 progress rows require protected bodies; historical progress contracts keep null bodies and their original digest meaning.

COMMAND retains its existing insert capability; QUERY gains SELECT for the new columns. Existing immutable-column and table guards forbid updates. The initial Opportunity factory now explicitly selects rows without a predecessor; completed initial identities remain stable when later follow-ups exist. New follow-ups are distinct rows with immutable lineage and an independent WAITING receipt.

`R2_PROGRESS_SCHEMA_V1` verifies every V001–V880 byte, the exact current manifest and field contract, strips only the named V880 delta in memory, and proves the previous R2 V1 hash before the unchanged R1 projection. Runtime configuration must explicitly select v2 and matching artifact hashes. Old v1 expectations remain supported only against an actual matching v1 deployment, not the current v2 database. This is development evidence, not deployment or release approval.

## R2 V890 technical checkpoint successor

Current development schema is `52-plus-2-r2-v3`: 24 migrations, 52 application tables and 3 technical tables (deployment state, Flyway history and the new Worker checkpoint), 55 physical tables in total. The legacy identifier prefix is retained as a successor lineage; it does not assert the old physical count. V001–V880 remain byte-identical.

`platform_meta.r2_opportunity_checkpoint` stores only bounded technical continuation state keyed by tenant, SERVICE principal, appointment and INITIAL/DUE kind. Worker has SELECT/INSERT and column-restricted UPDATE; API command/query/audit capabilities have no access. The mutation guard freezes identity, requires exact revision advancement, uses database time and prevents deletion. V890 appends its own exact inventory/permission validation after the unchanged historical validators.

The exact successor is pinned in `scripts/baseline/r2_checkpoint_schema_contract.py`. No additional business or jOOQ module is introduced. Existing runtime publication evidence remains historical; local development verification does not grant R2 release acceptance or production auto-start.

## R2 T01 owner exception successor (V900)

`52-plus-2-r2-v4` registers the T01 schema implementation: 25 migrations, 55 application tables plus deployment state, Worker checkpoint and Flyway history = **58 physical tables**. V001–V890 bytes remain frozen. The three new Opportunity-owned tables are `owner_exception`, `owner_exception_disposition`, and `responsibility_handoff`; they cannot be represented by technical checkpoint payloads. The existing checkpoint gains only the named `OWNER_EXCEPTION` scan kind.

An exception uses `(tenant_id, owner_exception_id, revision)` history rows. Only `is_current` may change, once from true to false; every business column remains immutable. A deferred guard requires one current row at commit. New versions require prior retirement, revision +1, frozen cycle identity, monotonic observation time and a nonterminal predecessor. The partial unique index admits one ACTIVE/COORDINATING cycle per tenant/opportunity/OPPORTUNITY_OWNER slot; terminal history cannot reopen. Initial revision is zero and all versions are JSON-safe (maximum 9007199254740991). Dispositions and handoffs are immutable revision-zero facts; their circular physical foreign keys are tenant-first and deferred.

Handoff has a unique exact predecessor, a unique initial handoff per opportunity and a unique new task. Its predecessor must already exist, preventing cycles; deferred checks require matching transfer decision/exception basis, the cancelled old task, the new task's receiver/basis/deadline and exact inherited waiting identity. Opportunity/Assignment owner remains frozen. Task receives optional typed responsibility basis, distinct handoff predecessor and cancellation fact; only the cancellation fields join controlled one-time UPDATE. `R2_OPPORTUNITY_HANDOFF_V1` cancellation never writes DONE/completion. A new WAITING task is admitted only with the named handoff basis/predecessor. `R2_OPPORTUNITY_HANDOFF_WAIT_V1` admits inherited resumeDue already in the past, while its deferred handoff check retains the original wait's resumeDue and SLA. Ordinary waits retain their existing resume-after-entry rule.

Immutable WaitReceipt selectors use hashes: `owner_exception.wait_hash`, `responsibility_handoff.original_wait_hash`, and `wait_receipt.inherited_wait_hash`. New wait fields are handoff_fact_id/revision, inherited_wait_receipt_id/hash, origin_progress_id/hash and original_sla_due_at. Hash resolution and current authorization remain Fact Owner responsibilities. SQL proves stable identities and structural consistency, not current appointment eligibility or business source truth.

COMMAND receives SELECT/INSERT on the three facts and UPDATE(is_current) only on exception history; QUERY receives explicit column SELECT. PUBLIC, WORKER and AUDIT receive no business-table capability. Worker retains only its preexisting technical-checkpoint capability. No identity grants or automatic task dispatch activation are introduced.

Validation on 2026-09-15: TDD red (`V900` missing), then generation, deterministic check and all 64 schema tests passed. Live PostgreSQL upgrade/concurrency and jOOQ checks are coordinated by the parent implementation task; this paragraph does not claim they passed. ADR-0017 and R2_OWNER_EXCEPTION_SCHEMA_V1 register this named successor. The exact development projection validates V900 and reconstructs the unchanged V890 manifest before invoking the older projections. `verify_baseline.py --r2-development` passes consistency/admission with seven existing non-fatal readiness blockers. Production activation and R2 acceptance remain separate gates.


## T05 customer requirements successor (V920)

Development schema `52-plus-2-r2-v6` appends V920 only: 27 migrations, 61 application tables, two platform tables. `party.party` remains the sole identity anchor; `party.profile_version` freezes its exact name/type revision. `opportunity.customer_requirement_draft` stores immutable owner-bound saves independently of Task; `customer_requirement_confirmation` freezes the complete protected document and exact draft; `customer_requirement_participant` references exact Party/profile versions and role. Contacts and service text remain encrypted in the document.

The shared opportunity lock checks open state, exact root revision, effective responsibility and owner. Same-owner draft chains and complete confirmation chains cannot fork. A deferred guard requires at least one CLIENT. Participant insertion is limited to the confirmation transaction and verifies the current Party version, so committed sets cannot grow later. All five new tables reject UPDATE/DELETE; only COMMAND SELECT/INSERT and QUERY SELECT are granted. No original lead, assignment, Task, quote or contract history is modified.

`R2_CUSTOMER_REQUIREMENTS_SCHEMA_V1` pins exact manifest, field-document and migration bytes, removes only the five T05 tables/references in memory, and proves the original V910 manifest hash before traversing historical projections. No live database apply or deployment acceptance is implied. Local verification: 70 schema tests, 27 parsed migrations, 36 parsed PL/pgSQL functions and deterministic generated artifacts.

Draft receipts reauthorize selected exact Party revisions from immutable `opportunity.customer_requirement_draft_party` metadata without decrypting the draft body. Its rows are unique per draft/Party, tenant-scoped, and can only be inserted in the draft creation transaction; historical sets cannot be extended.

The draft/confirmation INSERT guard stamps `created_in_transaction` using the top-level `pg_current_xact_id()` (`xid8`) and overrides caller input. Child set guards compare that immutable stamp, so CommandRuntime savepoint subtransactions remain inside the same creation boundary while later transactions cannot append participants or draft Party sources. PostgreSQL row `xmin` is deliberately not used because it identifies a subtransaction under a savepoint.

## T06 exact V930 successor (2026-09-19)

`52-plus-2-r2-v7` requires exactly `52-plus-2-r2-v6` and appends V930 without changing V001-V920. The manifest now reports 64 application tables, 66 generated tables and 67 physical tables after Flyway bootstrap.

Three supplementary immutable tables preserve the original evidence chain: `evidence.material_upload_basis` freezes exact opportunity, responsibility, owner, optional customer confirmation/original task, expected previous material version, and encrypted filename/note metadata; `evidence.material_upload_check` records append-only technical states; `opportunity.material_version` connects the exact existing upload/source/submission/binding to an immutable material version. The initial version ID is the stable material item ID. A unique previous-version relation prevents branching; the current version is derived by absence of a successor. Metadata ciphertext is reused from the upload basis, retaining its original AAD.

Insertion guards serialize on the opportunity, recheck current responsibility and closure, verify the complete original evidence chain, and reject unsafe scan/type/size results. Purposes are CONTRACT_BUSINESS, CORRESPONDENCE and OTHER; accepted media are PDF/JPEG/PNG with a 20 MiB maximum. Technical states begin CHECKING; UNKNOWN and SCAN_UNAVAILABLE never authorize byte replay or acceptance. PASSED/REJECTED are terminal. Existing upload-session status semantics remain frozen.

Verification: 75 generator tests and deterministic generation check pass; exact baseline projection reconstructs V920 and every earlier frozen contract and passes three successor mutation tests. Runtime database and worker schema allowlists admit v7 explicitly.


## T07 protected quote successor (V940)

`52-plus-2-r2-v8` appends V940 to exactly V930: 66 application tables, two platform tables, 69 physical tables including Flyway. Existing quote_revision/scope/line/payment_term remain the quote truth. quote_draft is an independent encrypted immutable save bound to current opportunity responsibility and exact current T05 confirmation. quote_package_basis is a one-to-one protected companion of the existing quote revision, not a second quote identity.

Quote draft saves preserve ordinary waiting tasks. The command transaction and database opportunity lock serialize saves; exact prior and current confirmation checks prevent stale and forked writes. Draft/package cryptographic AAD distinguishes tenant, opportunity, fact identity and purpose. Only command SELECT/INSERT and query SELECT are granted.

The V940 historical projection removes only these two supplementary facts and proves the exact V930 manifest hash; V001–V930 bytes stay unchanged. Deployment into the review system is separate from disposable PostgreSQL integration verification.

V940 R2 quote child sets (scope, lines, payment terms and participants) are frozen by the package top-level transaction identity. Insert the quote header then protected basis before child rows. The deferred package check rejects incomplete commits. Draft head is derived by absence of a successor, not wall-clock order.

## V980: named contract preparation versions

`V980__r2_contract_versions.sql` / `52-plus-2-r2-v12` adds preparation to the existing contract anchor/version. It separates post-version conflict review and exact approval from the immutable body; supports both truthful source kinds; preserves the original anchor source while every version, including the first, can consume a freshly authorized source in the same opportunity; and records the explicit readiness boundary without implementing T09.

The V980 schema has 91 application tables plus 2 platform metadata tables (94 physical tables after Flyway bootstrap), across 33 generated migrations. The 14 new tables are preparation draft/workflow, approved template/clause versions, ordered revision clauses, review requests/decisions/bindings, approval policy/members, revision approval requirements/requests/decisions, and signature readiness. None replaces existing contract identity, quote acceptance, Party truth or independent ConflictReview.

Review the detailed V980 protocol and canonical CLEAR digest in `docs/runtime-validation-contract.md`. No approved document seeds are shipped. No migration or runtime activation is implied by generation.

## V990: manual signature evidence and durable handoff

`V990__r2_manual_signature.sql` / `52-plus-2-r2-v13` extends the existing signature_plan and contract_signature facts. Nine immutable supporting tables retain arrangement revisions, drafts, evidence submissions, verification decisions, archives, revision returns, workflow responsibility and a durable successor handoff. The inventory is 34 migrations, 100 application tables and two platform tables (103 physical tables including Flyway history).

V980 participants stay byte-for-byte immutable with their original signature_required=false. Each manual arrangement separately registers explicit required signing/sealing slots and protected clause bases; old completion cannot satisfy a new arrangement. Existing approved participants are referenced exactly. Missing approved firm binding is a revision blocker: V990 does not fabricate a participant or infer a Party from free text. The immutable template_signing_party metadata must be created in the same transaction as its approved template version, by that template approver, with an exact existing ORGANIZATION Party/profile snapshot. It cannot be added retroactively to an old template. Manual slots reference exactly one frozen contract participation or explicit template signer binding; every bound firm must appear as a required slot. Old templates missing this metadata require a real revised contract using an appropriately approved template.

Arrangement slot collections and VERIFIED signature facts seal in one transaction. Root and predecessor uniqueness enforce single consumption and CAS-style append chains. Accepted and scanned material versions pin signed-file and authority-file hashes independently from the approved body hash. Full required-slot verification and accepted archive material precede the unique AWAITING_EXECUTION_CONDITIONS handoff. No execution, receipt or case fact is created; the V980 execution blocker remains active.

The named development projection validates all V990 bytes, regenerates the exact historical V980 manifest and proves its reviewed hash before invoking historical projections. This is local schema evidence, not release permission: R1 PAUSED / R2 NOT_GRANTED remains unchanged.
