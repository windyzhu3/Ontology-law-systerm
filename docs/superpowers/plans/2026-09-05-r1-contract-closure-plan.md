# R1 Contract Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four non-completion command policies and five event descriptors with executable contracts, real authorization enforcement and atomic event verification, without claiming the R1 business slice is complete.

**Architecture:** Keep the existing modular monolith and CommandRuntime transaction protocol. Freeze command-specific static bindings; query real facts through Owner read ports; enforce exact event sets for persisted branch facts. Keep all test handlers in test sources.

**Tech Stack:** Existing Java 25/Spring Boot 4.1.1/jOOQ 3.21.7/PostgreSQL 18/Testcontainers 2.0.0, Python 3.12 baseline CI, existing React/TypeScript/OpenAPI toolchain; no dependency upgrades.

**Spec:** [Approved closure design](../specs/2026-09-05-r1-contract-closure-design.md), confirmed by user on 2026-09-05.

## Global Constraints

- 一个响应式 SPA、一份 OpenAPI、一个模块化单体 Jar；保留 api/worker 互斥角色。
- 13 Schema、52 应用表加 2 技术表、52-plus-2-v1.1；不修改物理合同、manifest、字段合同和 V001–V850 字节。
- jOOQ 为唯一业务持久化方式；新 SQL 只在所属模块 internal.persistence；跨 Owner 使用具名接口。
- 领域 Fact 为业务真源；Draft、Receipt、Audit、Event、Outbox 不替代完成 Fact。
- Task DONE/CANCELLED 永久终态；recovery 只恢复准确 WAITING Task，不重开终态、不修改 Owner/SLA。
- R1 不接入 AI；不赋予 AI 主命令权限。
- READ COMMITTED、最终持锁授权、单事务全写入；保留 ADR-0006 锁协议、savepoint、最终新鲜 clock_timestamp 和提交确认丢失语义。
- 不增加动态权限 DSL、BPMN、Saga、Job、Kafka、Redis、EAV 或事件溯源平台。
- 本轮仅交接 OpportunityOpened，不创建 R2 Task 或 Matter 表；ADM-01～07 不前置。
- 13 个 HTTP operation、依赖版本、ETag、幂等 scope 字段与摘要向量不变；不授予任何真实生产人员或服务权限。

## Execution and environment

Worktree: `C:/Users/Jacob/.cache/codex-worktrees/ontology-law-c0`, branch `codex/r1-contract-closure`; main base `5b21dbfc06e4d278f8b5097209e2fd76a8465c00`; approved spec commit `a20321454cb914f22eaf926505b08fb2ae1b1605`.

Use apply_patch for file edits. Use JDK `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1`, Node `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64`, npm CLI `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/npm/bin/npm-cli.js`. Java verification: set process JAVA_HOME/PATH then run `./mvnw.cmd -f backend/pom.xml ...`. Real Docker is available. Baseline Python tests require Linux symlinks; run in Python 3.12 Linux with the worktree mounted read-only, and fixtures in the container's writable temporary directory. Do not skip or weaken symlink tests. The sparse Windows checkout omits design PNG assets; full repository/snapshot validation uses GitHub's complete checkout, not a fabricated local success.

## Task 1: Freeze the semantic contract and executable baseline gate

**Files:**
- Create: `docs/adr/ADR-0007-r1-command-policy-event-closure.md`, `docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md`.
- Create: `contracts/events/r1-domain-notification-v1.schema.json` (shared empty payload Schema for registered version-1 R1 notifications).
- Create: `scripts/baseline/r1_command_contract.py`, `scripts/baseline/tests/test_r1_command_contract.py`.
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md`, `docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md`, `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`, `docs/adr/ADR-0006-command-runtime-authorization-boundary.md`, `scripts/baseline/verify_baseline.py`, `scripts/baseline/tests/test_verify_baseline.py`, approved spec status/header and current-state historical wording.
- Modify: `README.md`, `docs/progress/MVP-DELIVERY-LEDGER.md` only for truthful new contract-version entries and links; retain historical merge evidence.

**Interfaces:**
- Consumes: all exact policy/event/branch values in approved spec sections 2–5.
- Produces: authoritative Markdown static policy/event/branch tables in the new R1 contract, shared JSON Schema, `validate_r1_command_contract(root: Path) -> list[str]` integrated into `verify_repository`; empty list means consistent, nonempty means fail closed.
- Task 2 consumes policy rows; Task 3 consumes event and branch rows. Do not create a second JSON event registry with independent mutable values.

- [ ] Write focused failing tests that run the new validator on an actual fixture copied from the R1 contract, then mutate one rule at a time. Catch missing capture policy, recovery code swap, missing OpportunityOpened, wrong source selector/queue, duplicate event/branch, nonempty permissive payload Schema and CONNECTED_VALID old 1/1 counts. Literal expected branch counts are 2/2 for CONNECTED_VALID and 1/1 for other successful branches.

```python
def test_connected_branch_cannot_lose_opportunity_notification(self):
    root = self.copy_contract_fixture()
    self.replace_contract(root, 'LeadContactResultRecordedV1,OpportunityOpened',
                         'LeadContactResultRecordedV1')
    self.assertTrue(validate_r1_command_contract(root))
```

The fixture helper copies the actual relevant contract/schema files to a TemporaryDirectory; replacement must assert exactly one match so a stale fixture cannot make a negative test vacuous. Existing verifier tests continue exercising repository inputs, not merely searching source code strings.

- [ ] Run `python -m unittest scripts.baseline.tests.test_r1_command_contract -v`; record RED before implementation. Implement exact closed tables from spec, Schema `{ "type":"object", "properties":{}, "additionalProperties":false }` with the JSON Schema dialect declared, and validator. Reject ambiguous/duplicate/missing rows and malformed Schema rather than counting names alone.
- [ ] Advance semantic baseline to MVP-2026-09-05.2 via ADR-0007. Preserve accepted ADR-0006 as history with explicit supersession for the four missing descriptors only. Update active statements that still say these policies are unfrozen. Update Task branch and E2E connected row to two events and two outboxes; replace generic one-success-one-event wording with cardinality formula. Preserve all original receipt and failure rules and old baseline evidence.
- [ ] Record R2 delayed-consumer and current-state projection rules from spec; management remains outside R1 gate. Do not set R1-BACKEND/SPA/E2E implemented.
- [ ] Run focused tests GREEN, full 3.12 Linux baseline verifier suite, schema generation check and schema tests. Commit `feat(contracts): freeze R1 command policies and event mappings`. Report exact commands, RED/GREEN, counts and environmental limitations.

## Task 2: Enforce the four policies against real identity and Owner facts

**Files:**
- Modify: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandHandler.java`, `CommandRuntime.java`, `identity/AuthorizationService.java`, `identity/internal/persistence/JooqAuthorizationService.java` only as needed for closed policy integration.
- Create: `execution/R1CommandPolicy.java` for command-specific orchestration, `execution/CommandAuthorizationBinding.java` for sealed server bindings.
- Create: `lead/R1SourcePolicyRegistry.java` for immutable static source policy values, matching exactly the five fields of the frozen SourcePolicy contract.
- Create: narrowly scoped public read ports and implementations under `lead/internal/persistence`, `responsibility/internal/persistence`, and `identity/internal/persistence` for persisted authorization binding and Owner identity; each module owns its SQL. No shared jOOQ package or cross-module internal access.
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/R1CommandPolicyIT.java` and test-only fixtures under `testing` as needed; modify existing CommandRuntimeIT/AuthorizationServiceIT for the changed registration contract, retaining their existing behavioral coverage.

**Interfaces:**
- Consumes: Task 1 exact policy rows, existing AuthorizationService.Request and Snapshot, CommandScope, and Owner fact selectors.
- Produces: a closed binding on CommandHandler.Context (legacy seven-primary context construction can remain compatible), `R1CommandPolicy.authorize(Connection, CommandEnvelope, CommandHandler.Context, boolean finalCheck) -> AuthorizationSnapshot`, used at all existing runtime authorization phases including replay/conflict. The runtime must not trust a free-form policy supplied by handlers or clients.
- Source registry values contain exactly assignmentMode, routingOrganizationRootCodes, routingSupervisorRootCode, sourceIntakeRootCode, businessTimezone; immutable, validate code/zone/unique roots, no database configuration table. Production source values are supplied by trusted application assembly, not request input; tests use explicit fixture source registrations.
- Frozen DAG detail: execution may depend only on identity/audit, not lead/responsibility/opportunity. Declare the narrow fact-reader contract and plain result records on execution's public side; implement the concrete composition in lead (which already may depend on responsibility/opportunity/execution). Leaf Owner ports return their own public plain records; the lead composition maps them. R1CommandPolicy receives this closed, named reader interface; it never imports a downstream Owner class. Preserve ArchitectureTest's DAG unchanged. Missing reader support fails closed for new commands; no generic callback policy override.

- [ ] RED: build real tenant/principal/appointment/grant/Lead/Task fixtures using existing migrated Testcontainers database. Attempt registration of a correctly bound capture Handler; current registration must fail. Then exercise the new policy's allowed/denied decisions directly on the real connection. Independently test non-owner draft and wrong recovery Grant fail with zero persistence, not an always-denying mock. Full non-completion success transactions are Task 3's gate because event descriptors have not landed yet.

```java
assertDoesNotThrow(() -> new CommandRuntime(List.of(captureHandler), authorization, "POLICY_IT"));
assertTrue(capturePolicyDecision.allowed());
assertFalse(nonOwnerDraftPolicyDecision.allowed());
assertEquals(List.of(0L, 0L, 0L, 0L, 0L), commandEffectCounts(tenant));
```

Fixture helpers execute real SQL via existing test capabilities; policy decision variables are results of the real R1CommandPolicy.authorize method, not mocks. Fixtures use a test Handler only; do not add production handlers. First observe expected failure before changing production registration/policy code.

- [ ] Implement closed bindings that cross-check envelope/scope/task/source identity and actual persisted facts; missing bindings fail closed, including NO_CHANGE. Preserve the existing seven primary policies and scope vector. Capture resolves root from trusted static registry, authorizes real org@revision, and rechecks existing Lead DENY including replay. Draft selects one of seven policies from persisted Task purpose/primary command; require exact Owner or legal one-hop represented Owner and Task/Lead DENY. Recovery SYSTEM validates real service/direct grant, actual Owner organization/liveness, Task/Lead DENY and internal NOT_AUTHORIZED error semantics. No generic OBJECT fallback.
- [ ] Final policy evaluation obtains the existing shared identity lock before current identity/Owner/scope checks, retains it to commit and aggregates complete evidence used in Audit; no extra Audit on replay. Do not overwrite an earlier denied decision with a later allowed snapshot. Extra task binding checks on replay must not become new business eligibility checks.
- [ ] Test all seven draft mappings, wrong action/schema/Task/Draft binding, delegated Owner/scope, capture shared root and wrong root, org revision change and Lead DENY, both recovery policies, HUMAN misuse, wrong code/type/scope, inactive Owner, revoked service grant and new DENY observed after lock waits. Preserve pre-slot/post-slot rollback and recovery replay-before-eligibility. Keep exact successful event writes for Task 3; temporary tests may exercise policy directly until events land, but report this boundary honestly.
- [ ] Run focused policy IT during iteration; run `./mvnw.cmd -f backend/pom.xml verify -Pit` once at task end (events remain governed by preexisting gates until Task 3). Commit `feat(auth): enforce scoped capture draft and recovery policies`. Report source interfaces and all test evidence for the next task.

## Task 3: Enforce exact event sets, prove atomic writes, and report readiness

**Files:**
- Modify: `execution/CommandHandler.java`, `CommandRuntime.java`, `execution/internal/persistence/JooqCommandStore.java` as necessary.
- Create: `execution/R1EventPolicy.java`, Owner-specific read ports under lead/responsibility/opportunity and their internal.persistence implementations as required to verify persisted branch and exact sources. Reuse Task 2 ports when appropriate, without crossing internal boundaries.
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/R1EventPolicyTest.java`, `R1ContractClosureIT.java`; modify existing multi-notification tests that currently accept multiple copies of one event type for arbitrary sources.
- Modify: backend test resources/build resource inclusion only if necessary to verify the canonical shared Schema and R1 tables, not hand-copy them.
- Create: `docs/progress/2026-09-05-r1-contract-closure-acceptance.md`; update README/ledger only with observed evidence and readiness, keeping full business gates unmet.

**Interfaces:**
- Consumes: Task 1 event tables and Schema, Task 2 policy integration and binding ports, existing `CommandHandler.Result(status, fact, notifications)`.
- Produces: `R1EventPolicy.validate(Connection, CommandEnvelope, CommandHandler.Context, CommandHandler.Result) -> void` throwing SQLException on technical contract violation. Compute branch from persisted exact ContactResult/Decision facts (including contact_no), not from arbitrary result labels; use accurate Owner read ports. Keep the unique Receipt fact distinct from each notification source.

- [ ] RED: run a real CONNECTED_VALID fixture with ContactResult plus Opportunity@0 and assert two distinct source events/outboxes, one Receipt/Audit and no R2 Task. Assert dropping OpportunityOpened, adding it to NOT_CONNECTED, selecting the wrong Opportunity/ContactResult/Owner or duplicating a notification causes full rollback.

```java
assertEquals(List.of(1L, 1L, 1L, 2L, 2L), commandEffectCounts(tenant));
assertEquals("lead.lead_contact_result", outcome.resultFact().type());
assertEquals(outcome, retrySameKey());
assertEquals(List.of(1L, 1L, 1L, 2L, 2L), commandEffectCounts(tenant));
```

Counts order is Slot, Receipt, Audit, Event, Outbox. Use literal exact source IDs/hashes/revisions from independent fixtures. Do not let a fake validateBeforeCommit that accepts anything prove production event validation.

- [ ] Add five exact Event descriptors and selector requirements; validate equality of each branch's event set, not a subset. Enforce unique event type per expected branch and exact sources. Contact connected requires Opportunity revision 0 and frozen relationship to Receipt ContactResult. Other four non-completion events reference the exact Receipt selector and proper persisted source type. NO_CHANGE/REJECTED have no events; recovery success still cannot complete a Task or change its Owner/SLA.
- [ ] Ensure store writes `{}` and correct schemaVersion/queue mapping for each validated event in the same transaction; retain all0 on technical failure, audit failure, commit-ack recovery and duplicate-key paths. Add contract drift test comparing Java descriptors and canonical tables/Schema without a second hand-maintained test registry; behavior tests independently prove exact intended outcomes.
- [ ] Run focused new tests and all original runtime tests, then full `./mvnw.cmd -f backend/pom.xml verify -Pit`; run Linux baseline/schema tests, OpenAPI check, frontend typecheck and frontend tests. Inspect exact physical/DDL/HTTP diffs for zero changes. Full GitHub checkout verifies omitted visuals and history before merge.
- [ ] Write evidence-backed acceptance report by contract/backend/frontend/integration/ADM/business stage. Record newly frozen semantics as deliberate amendments, original constraints preserved, no R1-BACKEND/SPA/E2E completion claim, and original Task 5 next. Commit `feat(events): enforce R1 event closure and report readiness`.

## Finish

Review each task with a fresh reviewer, then the entire branch on the most capable available reviewer. Fix Critical/Important findings before merge. User has already authorized pushing this scoped branch and merging it into main after verification. Publish through the authenticated GitHub connector if git transport remains read-only; compare exact trees and retain PR/CI/head/merge evidence. Synchronize local main to the verified remote merge without force reset or overwriting user changes. No new thread, recurring monitor or deployment is requested.
