# R1 Business Closure Implementation Plan

> 2026-09-06具名supersession：[ADR-0010](../../adr/ADR-0010-lead-ingress-query-read-capability.md)与[有限能力计划](2026-09-06-r1-ingress-query-capability-plan.md)是原Task 5之前的独立门禁，仅允许V860四列QUERY SELECT及v1.2活动合同同步。历史步骤不重写；物理合同禁止修改条款只对此具名例外豁免。原Task 5消费者须在门禁评审后继续，原Task 8生产readiness组装仍待实施。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现并验证 R1 接入、七类责任卡、首联、等待恢复、Worker 与单 SPA 闭环，逐项满足已批准规格。

**Architecture:** 保留 Fact Owner、单 Jar、单 SPA、单 OpenAPI 与 PostgreSQL 能力隔离。先统一活动合同，再修订可信 Actor、实例 envelope 和集中业务锁，随后按原 Task 5–10 完成业务、HTTP、Worker、界面及联合验收。

**Tech Stack:** 仓库锁定的 Java 25、Spring Boot、jOOQ、PostgreSQL 18、React/TypeScript、Vitest；版本以当前 toolchain lock 为准，不因本计划升级。

**Spec:** [批准规格](../specs/2026-09-05-r1-business-closure-alignment-design.md)，尤其第 2、4–11 节。原计划 Task 1–4 保留历史，本计划不重新实现它们。

2026-09-06受控前置修订：[ADR-0009](../../adr/ADR-0009-p0-duplicate-automatic-assignment.md)和`MVP-2026-09-06.1`/`R1-TASK-COMPLETION-V1.1`承接Task 3发现的P0-01自动后继指针冲突。下文Task 1的`.3`及Task版本不变陈述保留为当时实施记录，当前仅以ADR-0009批准的单次CAS有界例外替代。Task 3生产Handler仍待完成；本修订不改变Tasks 4–10或容量向量。

## Global Constraints

- 一个响应式 SPA、一份 OpenAPI、一个模块化单体 Jar。
- `APP_ROLE=api|worker` 互斥。
- 13 Schema、52 应用表加 2 技术表、`52-plus-2-v1.1`；不修改物理合同、manifest、字段合同或 V001–V850 字节，不新增投影表。
- jOOQ 是唯一业务持久化方式，Fact Owner 拥有 SQL；SQL 与生成类型留在 Owner 的 `internal.persistence`。
- 锁顺序：`R1_BUSINESS_TENANT_LOCK → LEAD → TASK → COMMAND_SLOT → identity shared`。
- 11 public + 4 internal operation；公共 DTO 现有 shape 保持。
- 七种非空 CurrentCard 一律属于 `R1_CURRENT_WORKCARD_DISCLOSURE_V1` 敏感披露；200/304 均审计提交后返回。
- `R1_PROJECTION` DELIVERED 只表示有效 claim 下 API 重读当前事实并确认、Worker CAS 成功；不代表物化视图或客户端刷新。
- 所有完成声明必须有当前构建的实测证据；合同、底座、业务、E2E、容量证据分别记录。
- Provider 发送、AI、ADM-01～07、R2+ 不属于 R1。

## Delivery units and dependency map

| 本计划 | 对应原计划 | 交付接口 | 后续消费者 |
|---|---|---|---|
| 1 | 合同前置 | 活动合同、15 operation、生成类型 | 2–10 |
| 2 | Task 5 前置 | PrincipalKind、实例 envelope、R1BusinessFence | 3–8 |
| 3 | Task 5 | LeadCommands、TaskFactory、AssignmentPolicy | 4–8 |
| 4 | Task 6 | ActionDraftService、Owner read ports | 5–8 |
| 5 | Task 6 | CurrentWorkCardDisclosureService | 8–9 |
| 6 | Task 7 | ContactResultService、WaitLifecycleService | 7–10 |
| 7 | Task 8 Worker | due discovery、projection consumer、Outbox port | 8、10 |
| 8 | Task 8 API | 15 operation、安全/角色装配 | 9–10 |
| 9 | Task 9 | 生产工作台 | 10 |
| 10 | Task 10 | 联合验收、CI、R1 容量门禁 | R1 完成判断 |

工作根：`C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business`。Java 路径前缀 `backend/src/main/java/io/github/windyzhu3/ontologylaw/`，测试路径前缀 `backend/src/test/java/io/github/windyzhu3/ontologylaw/`。下文 Java 路径以此前缀展开；这样每个文件都有唯一位置。

每个交付单元先补可观察失败测试、保存 RED 证据、实现、运行覆盖变更的测试、独立复核后提交。不得以 mock 验收生产事务。若一个单元需要多个提交，保持提交范围完整，并以该单元开始前的 BASE 对整个范围复核。

## Task 1: Activate the approved contract amendments

**Files:**
- Create: `docs/adr/ADR-0008-r1-business-closure-alignment.md`.
- Create: `scripts/baseline/r1_business_closure_contract.py` and `scripts/baseline/tests/test_r1_business_closure_contract.py`.
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md`, `docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md`, `docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md`, `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`, `docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md`, `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`, `docs/progress/MVP-DELIVERY-LEDGER.md`.
- Modify: `contracts/openapi/ontology-law-api.yaml`, `scripts/baseline/verify_baseline.py`, `scripts/baseline/r1_command_contract.py`, affected tests under `scripts/baseline/tests/`, `tests/test_topology.py`, `scripts/verify_topology.py` when their exact operation assumptions change.
- Modify: `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`.
- Regenerate: `apps/workbench/src/generated/api/schema.d.ts`.

**Interfaces:** Existing `verify_baseline` remains its public CLI. New verifier exports `validate(root: Path) -> list[str]`, called by baseline verification. Mechanical command policy keys become `(CommandType, PrincipalKind)` so two capture rows cannot overwrite each other. Existing callers/tests must migrate to the same composite key. No production Handler is registered by this task.

- [ ] Read approved spec sections 2, 4–6, 9 and 11 plus affected active contracts. Record the old operation set and public schema digest before editing.
- [ ] Add negative mutation tests using temporary copies, matching the repository's existing test fixture patterns: missing SERVICE row; SERVICE DIRECT; capture envelope mismatch; missing either internal operation; broadened public error allowlist; mutable projection; read-before-audit/304 exemption; wrong max attempts; missing capacity profile; baseline mismatch. Positive fixture must pass every contract verifier.

```python
# In test_r1_business_closure_contract.py; ROOT and validate are module imports.
def test_current_contract_is_consistent(self):
    self.assertEqual([], validate(ROOT))
```

- [ ] Run `python -m unittest scripts.baseline.tests.test_r1_business_closure_contract -v`; preserve expected failures against the old contract before implementation.
- [ ] Freeze Baseline ID `MVP-2026-09-05.3`; retain `.2` evidence as historical. ADR-0008 supersedes only the relevant ADR-0004/0006/0007 clauses. Keep event schema versions and existing command/Task registry codes stable. Command policy contract becomes `R1-COMMAND-POLICY-EVENT-V1.1`, HTTP contract `R1-HTTP-V1.1`, Workbench contract `R1-WORKBENCH-V1.1`; unchanged Task/physical versions remain unchanged. Record the additive OpenAPI version as `1.1.0`.
- [ ] Add the SERVICE capture policy row and exact static source binding, instance envelope/replay rules, centralized fence, typed disclosure/source-anchor semantics, worker tenant readiness, cursor and stable key, 14-event projection routes, lease/retry/reaper rules and R1 capacity gate. Use tables with exact enumerable values where a verifier needs to compare them. The new verifier checks these tables and meaningful cross-file invariants, not only the presence of a heading.
- [ ] Add `listDueR1Tasks` GET `/internal/v1/tasks/due` with mutualTLS, `recoveryType=CONTACT_TASK|ROUTING_REVIEW_TASK`, limit default 50 and bounds 1..100, optional opaque cursor, `DueR1TaskPageV1` containing `candidates` and optional `nextCursor`. Each candidate has exactly recoveryType/taskId/expectedTaskRevision/waitReceiptId/waitReceiptHash/dueCutoff/idempotencyKey. No Tenant/Grant/organization or display content.
- [ ] Add `consumeR1Projection` POST `/internal/v1/projections/r1/consume`, mutualTLS, exact five-field DTO from spec §6.3, success 204 without body. Both DTOs reject unknown fields and reuse safe UUID/revision/digest types. Lease owner is a nonempty bounded technical identifier and fencing token is positive JSON-safe integer, consistent with wire Revision. New error codes `STALE_OUTBOX_CLAIM` and `PROJECTION_EVENT_INVALID` are accepted only by this internal operation; existing public operation allowlists remain exact. Add typed internal Problem shape if the existing shared public enum cannot represent this without broadening public schemas.
- [ ] Update OpenApiContractTest to prove 15 operations, 11 Bearer/4 mTLS, exact new DTO properties/required fields/bounds/security/status/error sets; demonstrate existing public DTOs and error sets remain unchanged. Update contract tests to validate two capture policies separately, including duplicate composite-row rejection.
- [ ] Sync presentation caching (`private, no-cache`, `Vary: Authorization`), audit 200/304 rules, actor-scoped ETag dependencies and client generation handling. Correct runtime contract's obsolete unfrozen-event text. Update ledger baseline/link context only; do not advance R1 business statuses.
- [ ] Run `python -m unittest discover -s scripts/baseline/tests -v`, `python -m unittest tests.test_topology -v`, `python scripts/verify_topology.py`, `./mvnw -f backend/pom.xml -Dtest=OpenApiContractTest test`, `npm run openapi:generate`, `npm run openapi:check`, `npm run typecheck`, `git diff --check`. Use locked runtimes. Run full baseline verifier separately and record actual findings; missing partial-clone assets cannot be replaced by fabricated images or softened assertions.
- [ ] Commit all contract amendments together as `feat(contract): activate R1 business closure alignment`; report RED/GREEN, operation/public-schema comparison, exact command outcomes, and any verifier environmental findings. No production code, migration or business-completion status change belongs in this commit.

## Task 2: Trusted Actor kind, instance envelope and centralized business fence

**Files:** Modify `identity/AuthorizationService.java`, `identity/internal/persistence/JooqAuthorizationService.java`, `execution/CommandEnvelope.java`, `execution/CommandRuntime.java`, `execution/R1CommandPolicy.java`, `execution/internal/persistence/JooqCommandStore.java`, `lead/R1SourcePolicyRegistry.java`; create `execution/R1BusinessFence.java`, `execution/internal/persistence/JooqR1BusinessFence.java`, `lead/R1ServiceSourceBinding.java`; tests `execution/CommandEnvelopeTest.java`, `execution/R1BusinessFenceIT.java`, `execution/R1ServiceCapturePolicyIT.java`.

**Interfaces:** Actor gains closed `PrincipalKind { HUMAN, SERVICE }`; adapters supply verified kind and DB authorization compares it. `CommandEnvelope.envelope()` returns the instance mapping. `R1BusinessFence` exposes `shared(Connection, UUID)` and `exclusive(Connection, UUID)` throwing SQLException; implementation resides in execution internal. Existing five-field Actor construction remains a HUMAN-only compatibility constructor for callers until trusted adapter migration; it cannot infer SERVICE.

- [ ] Add unit tests for the complete `(Type, PrincipalKind)` mapping, rejected combinations and on-behalf SERVICE. Update recovery fixtures to explicitly use SERVICE.
- [ ] Add PostgreSQL tests for SERVICE normal capture authorization, source allowlist, separate recovery Grant, replay/envelope conflict and revocation while waiting. Use real Owner readers; test Handlers may isolate runtime semantics but are not business acceptance.
- [ ] Observe RED with `./mvnw -f backend/pom.xml -Dtest=CommandEnvelopeTest test` and focused `-Pit -Dit.test=R1BusinessFenceIT,R1ServiceCapturePolicyIT verify`.
- [ ] Implement the approved closed mapping and persistence comparison. In Runtime acquire Tenant exclusive fence before Handler root locking and before any R1 business writes. Hash `R1_BUSINESS_TENANT_LOCK_V1:<canonical tenant UUID>` with SHA-256; first eight bytes are signed big-endian bigint. Shared read uses the identical key.
- [ ] Prove same-Tenant read/write exclusion, shared/shared progress, different-Tenant concurrency and rollback lock release with independent connections and deterministic latches. Assert roots cannot run until fence acquired. Final identity revalidation and existing LEAD→TASK→COMMAND order remain.

```java
// CommandEnvelopeTest uses the existing CanonicalJson-compatible empty payload.
assertEquals(CommandEnvelope.Envelope.SERVICE_ACTOR,
    new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD, UUID.randomUUID(),
        UUID.randomUUID(), serviceActor, Map.of()).envelope());
```

- [ ] Run authorization/runtime regression ITs and ArchitectureTest, then commit `feat: restore trusted service capture and fence R1 writes`.

## Task 3: Lead intake and P0 responsibility handlers

**Files:** Create `lead/LeadCommands.java`, `lead/LeadIngressService.java`, `lead/AssignmentPolicy.java`, `lead/LeadProtection.java`, `lead/internal/persistence/JooqLeadRepository.java`, `party/R1PartyReader.java`, `party/internal/persistence/JooqR1PartyReader.java`, `responsibility/TaskFactory.java`, `responsibility/internal/persistence/JooqTaskRepository.java`; tests `lead/LeadIngressIT.java`, `lead/LeadIngressCompletionIT.java`, `lead/LeadAssignmentIT.java`, `lead/LeadRoutingDispositionIT.java`.

**Interfaces:** `LeadCommands.handlers()` returns `List<CommandHandler>` for capture and P0 commands. Commands consume `CommandEnvelope` and produce existing `CommandHandler.Result`/`CommandOutcome` through Runtime. Fact Owner ports take tenantId and exact selector, return plain records; they never expose jOOQ. `TaskFactory` owns creation/completion/waiting writes. Protection interface offers tenant-bound encrypt/decrypt/HMAC methods with keys supplied by deployment, never repository constants.

- [ ] Build PostgreSQL fixtures for every P0 BranchID and source policy outcome, then assert exact Task/Fact/Receipt/Audit/Event/Outbox deltas from frozen matrix. Exercise fresh capture, natural-key NO_CHANGE, missing contact, duplicate ranking, MANUAL/AUTO/empty candidates, source stop owner zero/one/multiple, stale selectors and two competing assignments.
- [ ] Run the four named ITs and preserve failures due missing production handlers.
- [ ] Implement fixed source policies, normalization/HMAC, candidate order, exact active Party links, V850 ingress one-shot update and all Task successors. Protect personal fields at the boundary. User-supplied authority or source organization never reaches policy selection.
- [ ] Complete P0-04 WAITING through OPEN revision 0 then CAS revision 1 plus immutable WaitReceipt. Source stop requests create acknowledgement responsibility without global source mutation.
- [ ] Verify rollback at Fact/Audit/Event/Receipt/Outbox failures, final authorization, idempotency and concurrent stale selectors. Run `./mvnw -f backend/pom.xml verify -Pit -Dit.test=LeadIngressIT,LeadIngressCompletionIT,LeadAssignmentIT,LeadRoutingDispositionIT` and commit `feat: implement R1 lead intake responsibilities`.

## Task 4: ActionDraft persistence and Owner read ports

**Files:** Create `responsibility/ActionDraftService.java`, `responsibility/CurrentTaskReader.java`, `responsibility/internal/persistence/JooqActionDraftRepository.java`, `responsibility/internal/persistence/JooqCurrentTaskReader.java`, `lead/CurrentLeadReader.java`, `lead/internal/persistence/JooqCurrentLeadReader.java`, `identity/WorkcardOwnerReader.java`, `identity/internal/persistence/JooqWorkcardOwnerReader.java`; tests `responsibility/ActionDraftIT.java`.

**Interfaces:** Draft Handler uses SAVE_ACTION_DRAFT via Runtime; confirms only as part of a main-command transaction. Read ports return immutable task/draft/lead/owner records including exact source selectors needed by §5. Query consumes these ports; no SQL leaks.

- [ ] Test create/update preconditions, one Draft per Task, canonical digest, partial values, schema/action mismatch, NO_CHANGE, refresh, confirmed immutability and owner/delegation/DENY.
- [ ] Run `./mvnw -f backend/pom.xml verify -Pit -Dit.test=ActionDraftIT` for RED.
- [ ] Implement saved payload canonicalization/CAS; reuse confirmed values for main commands, with exact digest and revision checks. Draft confirmation alone never completes Task.
- [ ] Run named IT, command event regression and architecture tests; commit `feat: persist R1 action drafts and owner read ports`.

## Task 5: CurrentCard with audited disclosure

**Files:** Create `query/CurrentWorkCard.java`, `query/CurrentWorkCardQuery.java`, `api/CurrentWorkCardDisclosureService.java`, `execution/SensitiveReadRuntime.java`, `execution/DisclosurePlan.java`; modify `audit/AuditAppender.java`, `audit/internal/persistence/JooqAuditAppender.java`; tests `query/CurrentWorkCardIT.java`, `api/CurrentWorkCardDisclosureIT.java`.

**Interfaces:** Query produces a plain immutable envelope plus source dependencies. SensitiveReadRuntime owns one READ COMMITTED connection/role changes/fence/commit. DisclosurePlan entries explicitly carry disclosedSource and authorizationAnchor. HTTP receives only a committed response envelope/status/ETag from the service.

- [ ] Test all seven cards and zero state, exact ordering, at most two summaries, no other Owner full card. Seed Owner Appointment/Principal/OrganizationUnit and candidate Party/labels as separate sources.
- [ ] Add latch-controlled HTTP/DB tests proving no bytes/ETag/304 before all Audit inserts commit. Inject failure in the Nth Audit and commit acknowledgement; assert 503 and no sensitive response. Repeat GET and matching ETag increases exact Audit counts without Slot/Receipt/Event/Outbox.
- [ ] Observe RED in named ITs, then implement §5 static classification and permission registry, shared fence, final identity lock/re-read, typed read Audit, source-anchor binding and Actor-scoped canonical ETag. Use `READ_CURRENT_WORKCARD`, NULL command ID/type and BODY/CACHE_REVALIDATED summary.
- [ ] Assert DIRECT owner or DELEGATED represented owner only; exclude hidden candidates and authorize summaries separately. Test revocation/new DENY/Draft insertion/Task completion/candidate changes while locks contend.
- [ ] Run named ITs plus authorization/architecture and same-Tenant fence tests; commit `feat: audit current card disclosure before response`.

## Task 6: Contact, WAITING recovery and Opportunity boundary

**Files:** Create `lead/ContactResultService.java`, `lead/RetryPolicy.java`, `lead/internal/persistence/JooqContactResultRepository.java`, `opportunity/OpportunityOpeningService.java`, `opportunity/internal/persistence/JooqOpportunityRepository.java`, `responsibility/WaitLifecycleService.java`; tests `lead/ContactResultIT.java`, `lead/LeadValidityReviewIT.java`, `responsibility/WaitLifecycleIT.java`.

**Interfaces:** Handlers produce frozen BranchID result facts/events; recovery uses existing single-task DTO selector and Runtime. OpportunityOpeningService consumes accurate Lead/Assignment/ContactResult and confirmed legalNeed, produces stable Opportunity source tuple.

- [ ] Seed and test all three contact outcomes, retry attempts 1/2/3, weekday timezone boundary and all three review results with exact deltas. CONNECTED_VALID asserts exactly two Event/Outbox and one Receipt/Audit.
- [ ] Run named ITs for RED; implement contact ordinal/uniqueness, protected legalNeed, closed retry calendar/channel rules and immutable ContactResult.
- [ ] Implement WAITING via TaskFactory OPEN→WAITING and latest WaitReceipt; due recovery only WAITING→OPEN, never old terminal reopening. REVIEW_REOPEN_CONTACT creates a new Task.
- [ ] Test double submits, stale selectors, final authorization and fault rollback. Run named ITs and event-policy regression; commit `feat: close R1 contact and waiting fact loop`.

## Task 7: Due discovery and real projection delivery

**Files:** Create `responsibility/DueR1TaskReader.java`, `responsibility/internal/persistence/JooqDueR1TaskReader.java`, `api/DueR1TaskDiscoveryService.java`, `api/R1ProjectionConsumer.java`, `execution/R1ProjectionOutboxPort.java`, `execution/internal/persistence/JooqR1ProjectionOutboxPort.java`, `worker/R1WorkerTenantBindings.java`, `worker/InternalApiClient.java`, `worker/DueTaskScheduler.java`, `worker/R1ProjectionDispatcher.java`; tests `api/DueR1TaskDiscoveryIT.java`, `api/R1ProjectionConsumerIT.java`, `execution/R1ProjectionOutboxIT.java`, `worker/R1ProjectionDispatcherTest.java`.

**Interfaces:** Due service returns generated safe page DTO. Outbox port exposes claim/ack/retry/exhaust/reap with tenant and exact revision/owner/fencing tuple, plus bounded counts; transactions stay execution internal. Worker uses only this port and mTLS transport. Projection consumes exact claim DTO and returns no content.

- [ ] Test scope-prefiltered pagination, current Appointment only, one complete Grant, stable observedAt/cursor (5 minutes), UUIDv5 recovery key, replay/restart and invalid candidates without blocking valid later pages.
- [ ] Test real PostgreSQL SKIP LOCKED claims, counter/revision increments, valid vs expired CAS, lease reaper and eight-attempt ceiling. Preserve RED before port implementation.
- [ ] Implement §6: concurrency/claim 4, poll 1s, lease 60s, HTTP 10s; seven exact retry delays. Acquire permit before claim and dispatch immediately. 401/403 freezes binding without failure CAS; reaper still counts the current attempt. 409 ignores stale result. Permanent errors exhaust only current valid claim.
- [ ] Validate mTLS unique Tenant binding and Tenant-wide projection readiness before any claim. Consume all 14 event types with current Owner facts, immutable hashes and zero business delta; ContactResult summary remains Owner-internal.
- [ ] Run the four named suites including late/duplicate/old notifications and both sides of lease expiry; commit `feat: deliver due recovery and R1 projection worker`.

## Task 8: Production authentication, delegates and role assembly

**Files:** Create `api/R1ApiDelegate.java`, `api/ProblemDetailsAdvice.java`, `api/security/ActorContextResolver.java`, `api/security/R1SecurityConfiguration.java`; modify `api/ApiRuntimeAssembly.java`, `worker/WorkerRuntimeAssembly.java`, `backend/pom.xml`; tests `api/R1ApiIT.java`, `api/security/ActorContextResolverIT.java`, `worker/RuntimeRoleIT.java`.

**Interfaces:** Generated OpenAPI Java interfaces are the wire contract. ActorContextResolver returns trusted Actor including kind; services accept Actor and server-derived correlation, never caller authority. Registry narrows verified issuer/audience/subject candidates, computes each candidate Tenant's HMAC and requires exactly one DB match.

- [ ] Test 15 HTTP operations, missing/invalid auth, wrong issuer/audience/algorithm/expiry, duplicate cross-Tenant subject registration and forged claims. Test mTLS separately and SERVICE restrictions.
- [ ] Run named suites for RED; wire locked Spring Security Resource Server dependencies through the existing Boot dependency management. Use real signed JWT/cert fixture only under test profile.
- [ ] Implement DTO/header/precondition mapping, safe Problem Details, private revalidation cache and Receipt current-scope recovery; controllers contain no SQL. Ensure audit service returns only after commit before framework serialization.
- [ ] Start both roles from the same Jar and prove forbidden Beans/DB roles absent, missing trust/registry readiness fails closed. Run named suites plus all backend ITs; commit `feat: expose authenticated R1 API and isolated worker`.

## Task 9: Single SPA workbench

**Files:** Create `apps/workbench/src/App.tsx`, `apps/workbench/src/features/workcard/CurrentCard.tsx`, `ActionDraftForm.tsx`, `WaitingSummary.tsx`, `useCurrentCard.ts` in that feature folder with corresponding test files; create `apps/workbench/src/styles/tokens.css`, `workbench.css`, `apps/workbench/src/test/setup.ts`; modify `apps/workbench/src/main.tsx`, `apps/workbench/src/lib/api.ts`, `apps/workbench/package.json`.

**Interfaces:** Use generated OpenAPI types and existing API transport; unknown discriminator is unavailable state. Hook returns envelope/loading/error and explicit refresh/save/submit actions; request generation prevents stale overwrite and retains current envelope on 304.

- [ ] Remove zero-test exemption; write failing DOM/transport tests for seven forms, one main action, summary/next/waiting/Chat Composer, Draft refresh and original-command Receipt recovery.
- [ ] Run Vitest RED; implement static forms and localized safe copy, warm-white/graphite/emerald/mint tokens and keyboard/ARIA behavior.
- [ ] Test old GET arriving after new GET, lost submit response, double-click, focus refresh and bounded waiting poll. Draft conditional headers and Task ETag remain distinct.
- [ ] Run `npm test`, `npm run typecheck`, `npm run build`; verify real browser at 360/768/1440 in Task 10. Commit `feat(web): implement R1 responsibility workbench`.

## Task 10: Real E2E, strict CI and capacity acceptance

**Files:** Create `e2e/compose.yaml`, `e2e/fixtures/r1-fixture.json`, `e2e/tests/r1-golden-path.spec.ts`, `r1-failure-paths.spec.ts`, `r1-worker-disclosure.spec.ts` in that test directory, `playwright.config.ts`, `.github/workflows/r1-vertical-slice.yml`, `scripts/ci/verify_r1_evidence.py`, `tests/test_r1_evidence.py`, `scripts/capacity/r1_profile.json`, `scripts/capacity/generate_r1_fixture.py`, `scripts/capacity/run_r1_capacity.py`; modify root package files and affected delivery ledger rows only after evidence qualifies.

**Interfaces:** Evidence JSON records build SHA, exact toolchain/digests, command exits and executed test identities. CI validator matches BranchID to successful actual report cases; capacity receipt binds Build/Profile/Generator/Fixture digest.

- [ ] Write evidence validator mutation tests for zero/all-skipped tests, missing reports/artifacts, failed mapped BranchID, wrong build/digest and incomplete layer chain. Observe RED then implement strict parsing of actual machine reports.
- [ ] Add pinned Playwright and browser, real PG/API/Worker/SPA fixture boot, golden path, failure/recovery/SERVICE/audit/worker tests. Fixtures contain test-only synthetic contacts, no real secrets in reports.
- [ ] Implement pathless PR/main workflow in the exact nine-layer order of spec §9, always-run aggregate including artifact outcome. Schema/Postgres/jOOQ/OpenAPI drift checks retain existing locked commands; no empty test counts accepted.
- [ ] Implement deterministic R1 capacity generator from §2 BranchID vector and derived counts, not independent row targets. Small fixture first proves causal Task/Draft/wait/Event/Audit closure and distribution; full fixture has its own manifest/digests. Add same-Tenant contention smoke to ordinary PR gate.
- [ ] Run real E2E, responsive/keyboard checks and per-branch database deltas. Preserve sanitized success/failure evidence. Execute full R1-CAPACITY-V1 only on the specified reference resources; record missing resources as unmet capacity acceptance, not a pass from a scaled local run.
- [ ] Run complete CI-equivalent chain. Only qualified evidence permits R1-OPENAPI/BACKEND/SPA IMPLEMENTED and E2E RUNTIME_VERIFIED; original full C1-V1 remains separate. Commit `test: verify R1 business closure and release gates`.

## Plan self-review and completion rules

Spec §4 maps to Tasks 1–3/8; §5 to Tasks 2/4/5/8/9; §6 to Tasks 6–8/10; §8 to Tasks 8–9; §9 to Task 10; §2 capacity to Tasks 2/10. All seven cards and all frozen BranchIDs are required in production and evidence. Changes across shared Runtime/Actor/Owner interfaces execute serially. No production status is inferred from a completed contract task.

The preceding stage descriptions retain exact files and acceptance cases; when an Owner interface is first implemented, record its final signature in the execution ledger before its consumer task is dispatched. Interface adaptations are ordinary implementation decisions constrained by this plan/spec, not new product approvals. Missing external capacity resources or trust configuration must be named precisely with local work still completed where possible.
