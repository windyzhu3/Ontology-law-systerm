# R1 Foundation C0, C and D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close two cross-layer contract gaps, establish real PostgreSQL/jOOQ integration, and implement the R1 atomic command runtime.
**Architecture:** One SPA, one OpenAPI, one modular Spring Boot Jar with mutually exclusive api and worker roles; owner-local jOOQ persistence and a single READ COMMITTED command transaction.
**Tech Stack:** Versions remain those frozen in the R1 plan and repository lock files.
**Spec:** docs/baseline/CURRENT-MVP-BASELINE.md; docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md; docs/contracts/r1/*.md. C0 records the authorized refinements in ADR-0005.
**Authorization:** User approved C0 then C and D on 2026-09-05. This plan does not implement Tasks 5-10 or mark the R1 application complete.

## Global Constraints

- One SPA, one OpenAPI, one backend Jar; 52 application and 2 technical tables; no changes to V001-V850 bytes.
- Java 25.0.4.1+1; Node 24.20.0; npm 11.9.0; other exact versions inherited from the frozen R1 plan.
- Real PostgreSQL 18 with the repository's pinned RepoDigest, Flyway and jOOQ; no H2 or fake persistence.
- No JPA/Hibernate/Spring Data, Kafka, Redis, generic command endpoint or dynamic rule registry.
- TDD for behavior; regenerate generated files, never hand-edit them.
- Preserve main; separate commits and branches for C0, C, D. Run focused tests while iterating and required full checks before handoff.

## Task 0: C0 cross-layer contract readiness

**Files:** Update current baseline, R1 plan, R1 task/HTTP/workbench contracts, OpenAPI source and generated TypeScript, Java OpenAPI contract tests, baseline verifier and its tests, delivery ledger and README where current status is stale. Create docs/adr/ADR-0005-r1-foundation-readiness.md and a routing-reopen request example. No business production services in C0.

**Decisions and interfaces:**
- Add a distinct internal operation `reopenDueRoutingReviewTasks` at `POST /internal/v1/tasks/commands/reopen-due-routing-review-tasks`, request `ReopenDueRoutingReviewTaskV1`. This is a static single-task command, not a general scheduler API.
- It has the same exact selector fields, idempotency, mTLS, error set, Receipt/TaskETag and CAS/replay semantics as contact reopen, but only permits `RESOLVE_LEAD_ROUTING_GAP` with latest `R1_ROUTING_REVIEW_WAIT_V1` WaitReceipt. Contact reopen remains CONTACT_LEAD-only with CONTACT_RETRY_V1. Include commandType in reopen scope to prevent cross-command scope reuse. No browser calls internal operations.
- Freeze 13 operations and 14 individually mounted examples, preserving existing 12 operation behaviors. Add the full WAITING routing -> due -> OPEN -> CurrentCard -> next disposition acceptance scenario and zero-delta before-due/wrong-type/stale-selector tests in the contracts.
- Wire Revision remains JSON integer, now `0..9007199254740991`. Database bigint does not change. Values outside safe range must fail at API boundaries, never round/truncate. A mutation needing revision+1 must fail atomically before writes when revision is already at max; use existing safe INTERNAL_ERROR with no durable slot/Receipt for this technical overflow. Document that legacy imported out-of-range values cannot be emitted; no silent coercion. ETag numeric revisions use the same bounds. scope/draft canonicalization must reject non-safe integers. Task C/D will implement server guard where applicable.
- Central `Revision` schema supplies bounds; generated TS remains number. A real apiClient fetch test must prove max safe revision round-trips unchanged through response parse and request serialization; contract negative tests reject max+1. Avoid assertions that merely test JavaScript behavior.
- ADR-0005 explains the two changes, unchanged physical contract, static recovery type mapping, and compatibility (R1 has no deployed business clients).
- Update delivery tracking truthfully: OpenAPI artifacts are merged in #9 with evidence; backend/SP A/E2E business capabilities are not claimed implemented. Existing FROZEN input rows remain FROZEN; record source progress without bypassing exit gates.

- [ ] Add focused failing contract/baseline/frontend tests.
- [ ] Record RED output.
- [ ] Amend authority documents and canonical OpenAPI.
- [ ] Update gates and generate TypeScript and Java from canonical OpenAPI.
- [ ] Run Java contract tests, frontend typecheck/tests/build, baseline and topology checks.
- [ ] Commit C0 and record exact test evidence and any follow-up constraints for C/D.

## Task 1: C — 建立真实PostgreSQL、jOOQ和能力角色测试底座

**Files:**

- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/testing/PostgresIntegrationTest.java`
- Create: `backend/src/test/resources/db/bootstrap-runtime-logins.sql`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/CapabilityRoleExecutor.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/CapabilityRoleExecutorIT.java`
- Create: `backend/scripts/generate-jooq.sh`
- Generate: `backend/src/generated/jooq/io/github/windyzhu3/ontologylaw/{identity,audit,execution,responsibility,party,lead,opportunity}/internal/persistence/jooq/`
- Create: `backend/src/generated/jooq/MANIFEST.sha256`
- Modify: `backend/pom.xml`
- Create: `.github/workflows/r1-foundation-gate.yml`

- [ ] 先写Testcontainers测试：从`database/schema-contract-52-plus-2/runtime/toolchain.lock.json`读取PostgreSQL 18 RepoDigest并拒绝未锁定tag；从空库运行20个迁移；创建`law_api_login LOGIN NOINHERIT`并只授Command/Query/Audit成员关系，创建`law_worker_login LOGIN NOINHERIT`并只授Worker成员关系。

- [ ] 测试`CapabilityRoleExecutor`每个事务显式`SET LOCAL ROLE`，事务结束后角色恢复；API登录不能长期继承权限并集，Worker不能写Lead/Task/Audit，浏览器无数据库凭据。

- [ ] 运行测试确认失败，然后实现最小role executor与测试数据源配置。

- [ ] 使用已迁移PostgreSQL只为R1实际访问的表生成jOOQ；每个生成的record/POJO写入对应Fact Owner的`internal.persistence.jooq`，禁止`shared`生成根和跨Owner引用。提交统一`MANIFEST.sha256`，`generate-jooq.sh --check`重生成到临时目录并逐字节比较。R1不得生成DAO或Active Record。

- [ ] 运行实库、权限、jOOQ漂移与ArchUnit测试。
- [ ] 增加独立foundation CI工作流，复用当前固定SHA actions及JDK版本，必跑`verify -Pit`和`generate-jooq.sh --check`；不覆盖或削弱现有scaffold/baseline/schema门禁，也不声称完整R1出口通过。

```bash
./mvnw -f backend/pom.xml verify -Pit
backend/scripts/generate-jooq.sh --check
```

- [ ] Commit:

```bash
git add backend
git commit -m "build: add PostgreSQL jOOQ integration base"
```

## Task 2: D — 实现CommandRuntime原子合同

**Files:**

- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandEnvelope.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandHandler.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandRuntime.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandOutcome.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/JooqCommandStore.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/audit/AuditAppender.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/audit/internal/persistence/JooqAuditAppender.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/identity/AuthorizationSnapshot.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/identity/AuthorizationService.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/CommandRuntimeIT.java`

- [ ] 先写实库失败测试：同一slot键＋同payload返回原Receipt；同一Command UUID异payload或异Scope返回`COMMAND_PAYLOAD_CONFLICT`和原Receipt引用且所有新增delta为0；成功事务同时存在Slot、Fact、Audit、Event、Owner Outbox、Receipt；Audit失败或Handler技术异常时全部回滚且不留Slot/Receipt。

- [ ] 测试授权在事务开始与提交前都加载准确Tenant/Principal/Appointment/组织Scope/authority path；Appointment撤销、Subject revision变化或存在DENY时拒绝。

- [ ] 实现静态Handler注册表，不用反射扫描自由命令名。Payload使用RFC 8785规范化JSON摘要；Scope严格使用Task合同`R1_COMMAND_SCOPE_V1`，覆盖Tenant、命令类型、taskId、唯一持久Lead subject及具名次级selector。按Task合同区分pre-slot零Command写入与post-slot终局REJECTED Receipt；遵循`LEAD→TASK→COMMAND_SLOT`锁序，在Command阶段以Tenant＋Command UUID取得事务级advisory lock并拒绝同Tenant跨Scope复用，保证Receipt查询单义而不修改52＋2 Schema。

- [ ] 成功分支、NO_CHANGE分支、REJECTED分支严格遵循运行时合同；连接中断、锁超时和SQL异常回滚，不伪造FAILED Receipt。

- [ ] 运行`CommandRuntimeIT`至通过并检查日志不含payload、密文、HMAC、Token或正文。

- [ ] Commit:

```bash
git add backend/src/main/java/io/github/windyzhu3/ontologylaw/{execution,audit,identity} backend/src/test/java/io/github/windyzhu3/ontologylaw/execution
git commit -m "feat: implement atomic command runtime"
```


## C/D integration clarifications

C must expose a small typed capability-role transaction executor and a reusable real-Postgres fixture. Record exact public signatures in the report before D begins. Test and production connection sources are separate; no credentials committed. jOOQ uses owner-local generated classes and never publishes them across module ports. C must bind Failsafe to real ITs and fail if the required integration suite is missing. The generated manifest is reproducible from pinned migrated PostgreSQL; no manually manufactured jOOQ sources.

D must implement concrete database-backed authorization, audit, command slot/receipt/event/outbox stores with owner-local persistence. Tests may supply static test handlers to produce the frozen Lead fact, but production cannot contain testing hooks or fake business handlers. Static registry allows only named R1 commands, including the C0 routing reopen command. No HTTP controller or Lead business handler is delivered in D. The transaction boundary preserves the same JDBC connection across capability changes, checks authorization before work and before commit, handles business rejection without residual fact writes, and rolls back all effects for technical failure. C0 safe integer guards must be enforced in revision/scope/payload handling.

End-to-end browser/business readiness remains for subsequent PRs; do not conflate infrastructure integration evidence with the full R1 exit gate.
