# R1 Lead Contact Vertical Slice Implementation Plan

Plan ID: R1-LEAD-CONTACT-V1

Status: FROZEN

确认日期：2026-09-02

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以一份OpenAPI、一个响应式SPA和一个Spring Boot模块化单体制品，实现Lead接入、P0-01至P0-04入口处置、唯一分配、CONTACT_LEAD工作卡、ActionDraft、ContactResult、重试/无效复核及可恢复CommandReceipt的首个真实垂直切片。

**Architecture:** 单一后端Jar按`ols.runtime-role=api|worker`互斥启动；业务模块按Spring Modulith包边界组织，领域包不依赖Spring/jOOQ。所有写入通过CommandRuntime的READ COMMITTED短事务与能力角色切换完成；jOOQ是唯一业务持久化方式。SPA只消费`contracts/openapi/ontology-law-api.yaml`生成的类型，并始终只完整展示一张CurrentCard。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Modulith 2.1.1、jOOQ 3.21.7、Flyway 13.4.0、Testcontainers 2.0.0、PostgreSQL 18、OpenAPI Generator 7.25.0、Node 24.20.0、npm 11.9.0、React 19.2.8、Vite 8.2.2、TypeScript 7.0.2、Vitest 4.1.11、Playwright 1.62.1。

**Spec:** `docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md`

**Frozen implementation contracts:** [ADR-0004](../../adr/ADR-0004-r1-scaffold-and-http-contract.md)、[Task completion matrix](../../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)、[HTTP/error/precondition matrix](../../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)、[Workbench presentation contract](../../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)。本计划与这些合同冲突时，以合同中的受控表为准；修改必须显式修订合同和 baseline verifier。

## Global Constraints

- 只有PR #2已合并且`DB-52P2-PG18-RUNTIME`达到`RUNTIME_VERIFIED`后，才从最新`main`创建`feat/r1-lead-contact-slice`。
- Task 0完成V850后，旧PostgreSQL证据立即只代表v1；必须对v1.1重新运行实库门禁，成功前不得开始Task 1生产代码。
- 只构建一个SPA、一份OpenAPI、一个后端Jar；不得恢复历史三SPA/四OpenAPI拓扑。
- 不引入JPA/Hibernate/Spring Data、H2、Kafka、Redis、BPMN、通用工作流、通用Job、EAV或运行时规则DSL。
- R1不调用LLM；AI关闭时全部路径必须可完成。
- 领域Fact是完成Task的唯一依据。ActionDraft确认、HTTP成功、用户点击、Audit、Event、Outbox或Receipt都不能替代完成Fact。
- 所有领域ID和Receipt ID由服务端生成UUIDv7；调用方提供的UUID `Idempotency-Key`是唯一例外，服务端原样保存为Command ID。所有时间以数据库`clock_timestamp()`或同事务可信时间写入；客户端不能生成或决定HMAC、领域摘要、密文、Owner或可信时间，只能原样回传服务器签发的ActionDraft digest或HTTP ETag用于并发校验。
- 每个行为严格遵循RED→GREEN→REFACTOR；每个任务提交前运行其列出的测试。

---

## Task 0: 用V850补齐P0-02权威完成槽

> 状态：已完成并由`DB-52P2-PG18-RUNTIME-V1-1`真实PostgreSQL 18证据验证；保留下列步骤作为可复现历史，不得重复生成另一套迁移。

**Files:**

- Create: `docs/adr/ADR-0002-lead-ingress-completion-slot.md`
- Create: `database/schema-contract-52-plus-2/contract/evolutions/__init__.py`
- Create: `database/schema-contract-52-plus-2/contract/evolutions/v850_lead_ingress_completion.py`
- Modify: `database/schema-contract-52-plus-2/contract/model.py`
- Modify: `database/schema-contract-52-plus-2/contract/schema_contract.py`
- Modify: `database/schema-contract-52-plus-2/contract/render.py`
- Modify: `database/schema-contract-52-plus-2/contract/reference_registry.py`
- Modify: `database/schema-contract-52-plus-2/tests/test_schema_contract.py`
- Modify: `database/schema-contract-52-plus-2/tests/test_domain_semantics.py`
- Modify: `database/schema-contract-52-plus-2/tests/test_generated_sql.py`
- Modify: `database/schema-contract-52-plus-2/runtime/tests/test_runtime_harness.py`
- Modify: `database/schema-contract-52-plus-2/runtime/sql/assert_schema_contract.sql`
- Modify: `database/schema-contract-52-plus-2/runtime/verify_runtime.py`
- Generate: `database/schema-contract-52-plus-2/generated/db/migration/V850__lead_ingress_completion_slot.sql`
- Regenerate: `database/schema-contract-52-plus-2/generated/field-contract.md`
- Regenerate: `database/schema-contract-52-plus-2/generated/schema-contract-manifest.json`
- Create after pass: `docs/evidence/schema-runtime/2026-08-28-postgresql-18-v1.1-summary.json`
- Create after pass: `docs/evidence/schema-runtime/2026-08-28-postgresql-18-v1.1-report.md`
- Modify: `docs/progress/MVP-DELIVERY-LEDGER.md`

- [ ] 先写失败测试，冻结V001至V840的文件名、字节SHA和顺序；应用Evolution后迁移数必须为20、最高版本850、应用表仍为52、物理表仍为54、复合FK由206变为207、合同版本为`52-plus-2-v1.1`。

- [ ] 增加Lead当前形态测试，要求V850之后`lead.lead`恰有以下新列：

```text
ingress_completion_phone_ciphertext bytea NULL
ingress_completion_phone_hmac bytea NULL
ingress_completion_email_ciphertext bytea NULL
ingress_completion_email_hmac bytea NULL
ingress_completion_source_code varchar(64) NULL
ingress_completion_source_summary_ciphertext bytea NULL
ingress_completed_by_appointment_id uuid NULL
ingress_completed_at timestamptz(6) NULL
ingress_completion_digest bytea NULL
```

- [ ] 测试精确约束：phone与HMAC配对；email与HMAC配对；整槽为空或至少一组联系方式＋其余五个元数据全部存在；HMAC和digest均32字节；原始`captured_*`仍不可更新；九个新列只能从全NULL一次写入完整值，之后任何覆盖或清空均失败。

- [ ] 测试P0-02只可在原始phone/email均为空、槽为空、Task为准确Owner的OPEN `COMPLETE_LEAD_INGRESS`时执行；完成Fact固定为更新后的`lead.lead` revision。

- [ ] 运行测试并确认失败，且失败来自尚无Evolution支持。

```bash
cd database/schema-contract-52-plus-2
python3 -m unittest discover -s tests -v
```

- [ ] 在ADR记录已确认选择：保留52＋2表数；不覆盖原始渠道值；不用Party、ActionDraft、Audit或Event保存联系方式；使用Lead一次写入槽和revision型完成Fact；错误补全的后续纠正不覆盖本槽，必须在新版本ADR中引入准确追加Fact。

- [ ] 实现`ContractEvolution`模型：基础迁移从冻结基础Schema渲染，Evolution只向当前物理模型应用增量并生成独立前向迁移；任何Evolution导致旧迁移字节变化都使生成失败。

- [ ] 生成V850：添加九列、中文注释、配对/整槽/digest约束、`ingress_completed_by_appointment_id`同租户FK；重建`trg_lead__mutation_guard`以把九列列入write-once允许集；向`law_app_command`占位角色补充列级UPDATE，其他能力角色权限不扩张。

- [ ] 重新生成字段合同与manifest，检查没有第53张应用表、没有通用JSON字段、V001至V840零字节变化。

- [ ] 把运行时断言和证据Schema推进到合同v1.1，保留v1证据不变；随后运行全部静态和真实PostgreSQL验证。实库期望更新为20迁移、current version 850、52应用表、54物理表、207个复合FK和53个mutation guard。

```bash
python3 generate.py --check
python3 -m unittest discover -s tests -v
python3 scripts/verify_generated_sql.py
python3 runtime/verify_runtime.py verify --runs 2 --evidence-dir ../../.artifacts/schema-runtime-v1-1
```

- [ ] 保存v1.1脱敏证据并把`DB-52P2-PG18-RUNTIME`的新版本行推进为`RUNTIME_VERIFIED`；旧v1证据保留，不覆盖。

- [ ] Commit:

```bash
git add docs/adr database/schema-contract-52-plus-2 docs/evidence/schema-runtime docs/progress/MVP-DELIVERY-LEDGER.md
git commit -m "feat(db): add lead ingress completion slot"
```

## Task 1: 建立单制品工程和架构门禁

> 状态：已由PR #6合并；本任务只允许随ADR-0004修订同步维护机械边界，不再扩建空壳。

**Files:**

- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/OntologyLawApplication.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/bootstrap/RuntimeRole.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/bootstrap/RuntimeRoleConfiguration.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/audit/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/identity/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/opportunity/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/party/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/query/package-info.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/package-info.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/ArchitectureTest.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/bootstrap/RuntimeRoleTest.java`
- Create: `.node-version`
- Create: `package.json`
- Create: `apps/workbench/package.json`
- Create: `apps/workbench/vite.config.ts`
- Create: `apps/workbench/tsconfig.json`
- Create: `apps/workbench/src/main.tsx`
- Create: `scripts/verify_topology.py`
- Create: `tests/test_topology.py`

- [ ] 先写失败测试：仓库只能有`apps/workbench`一个可部署SPA、`contracts/openapi/ontology-law-api.yaml`一份OpenAPI、`backend/pom.xml`一个后端项目；`api`和`worker`必须互斥，缺失/同时启用均启动失败。

- [ ] 写ArchUnit测试：Domain包不得依赖Spring/Jackson/jOOQ/HTTP；jOOQ类型只能出现在各Owner模块`internal.persistence`；API不得暴露Repository、jOOQ Record或内部Command模型；模块不得访问其他模块`internal`。

- [ ] 运行测试并确认因工程尚未完整而失败。

```bash
python3 -m unittest tests.test_topology -v
./mvnw -f backend/pom.xml test -Dtest=ArchitectureTest,RuntimeRoleTest
```

- [ ] 创建单Jar工程，groupId固定`io.github.windyzhu3`、artifactId固定`ontology-law-system`；依赖使用上方精确版本并由Maven Enforcer拒绝Java非25、依赖上界冲突和动态版本。

- [ ] `RuntimeRoleConfiguration`只接受`ols.runtime-role=api`或`worker`；API加载Controller/同步Query/Command入口，Worker只加载Outbox及内部定时调用器；二者共享同一编译制品但Bean集合互斥。

- [ ] 创建npm workspace，只包含`apps/workbench`；所有依赖使用`--save-exact`并提交`package-lock.json`。禁止创建第二个前端package来模拟身份管理或其他体验模式。

- [ ] 运行拓扑、架构和空应用启动测试至通过。

- [ ] Commit:

```bash
git add .mvn mvnw mvnw.cmd backend .node-version package.json package-lock.json apps scripts/verify_topology.py tests/test_topology.py
git commit -m "build: scaffold single-artifact R1 topology"
```

## Task 2: OpenAPI-first冻结R1命令与查询

> 状态：初始12-operation OpenAPI制品已由PR #9合并；2026-09-05按ADR-0005增加第13个具名routing recovery operation并收紧Revision安全整数边界，不改变其余12项行为。

**Files:**

- Create: `contracts/openapi/ontology-law-api.yaml`
- Create: `contracts/openapi/examples/capture-lead.request.json`
- Create: `contracts/openapi/examples/current-work-card.response.json`
- Create: `contracts/openapi/examples/save-action-draft.request.json`
- Create: `contracts/openapi/examples/resolve-duplicate-lead.request.json`
- Create: `contracts/openapi/examples/complete-lead-ingress.request.json`
- Create: `contracts/openapi/examples/assign-lead.request.json`
- Create: `contracts/openapi/examples/record-routing-disposition.request.json`
- Create: `contracts/openapi/examples/acknowledge-source-intake-stop-request.request.json`
- Create: `contracts/openapi/examples/record-contact-result.request.json`
- Create: `contracts/openapi/examples/review-lead-validity.request.json`
- Create: `contracts/openapi/examples/command-receipt.response.json`
- Create: `contracts/openapi/examples/reopen-due-contact-tasks.request.json`
- Create: `contracts/openapi/examples/reopen-due-routing-review-tasks.request.json`
- Create: `contracts/openapi/examples/problem.response.json`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`
- Create: `apps/workbench/src/generated/api/schema.d.ts`
- Create: `apps/workbench/src/lib/api.ts`
- Modify: `backend/pom.xml`
- Modify: `apps/workbench/package.json`

- [ ] 先写契约测试，要求唯一OpenAPI为3.1；公共请求使用Bearer认证，Tenant只从服务端ActorContext取得；每个写操作按HTTP矩阵要求UUID `Idempotency-Key`和各自前置条件。错误统一为RFC 9457 `application/problem+json`，公共合同不得暴露调用方可指定的Tenant；幂等键是调用方可指定Command ID的唯一明确例外。

- [ ] 逐字段实现HTTP合同的Request DTO catalog、成功响应、安全Receipt/Problem投影、strong ETag、Draft确认生命周期和Bearer/mTLS绑定；OpenAPI是这些wire schema的唯一可执行来源，不在Java或TypeScript手写第二套DTO。

- [ ] 冻结以下具名端点，禁止通用`POST /commands`或自由`actionCode`：

| Method | Path | Operation |
|---|---|---|
| POST | `/api/v1/leads` | `captureLead` |
| GET | `/api/v1/workcards/current` | `getCurrentWorkCard` |
| PUT | `/api/v1/tasks/{taskId}/draft` | `saveActionDraft` |
| POST | `/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead` | `resolveDuplicateLead` |
| POST | `/api/v1/tasks/{taskId}/commands/complete-lead-ingress` | `completeLeadIngress` |
| POST | `/api/v1/tasks/{taskId}/commands/assign-lead` | `assignLead` |
| POST | `/api/v1/tasks/{taskId}/commands/record-routing-disposition` | `recordRoutingDisposition` |
| POST | `/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request` | `acknowledgeSourceIntakeStopRequest` |
| POST | `/api/v1/tasks/{taskId}/commands/record-contact-result` | `recordContactResult` |
| POST | `/api/v1/tasks/{taskId}/commands/review-lead-validity` | `reviewLeadValidity` |
| GET | `/api/v1/commands/{commandId}/receipt` | `getCommandReceipt` |
| POST | `/internal/v1/tasks/commands/reopen-due-contact-tasks` | `reopenDueContactTasks` |
| POST | `/internal/v1/tasks/commands/reopen-due-routing-review-tasks` | `reopenDueRoutingReviewTasks` |

`reopenDueContactTasks`名称保留复数仅表示Worker逐项调用；一次HTTP请求、一个CommandId和一张Receipt只恢复一张由Task合同准确绑定的WAITING Task，禁止空批成功。

`reopenDueRoutingReviewTasks`同样是单Task静态命令，只恢复`RESOLVE_LEAD_ROUTING_GAP`及最新`R1_ROUTING_REVIEW_WAIT_V1`；两种恢复scope都包含`commandType`。wire Revision只接受`0..9007199254740991`，数据库bigint不变；超界与递增溢出按ADR-0005拒绝。

- [ ] `CurrentWorkCardEnvelope`固定包含一句`todaySummary`、零或一张完整`currentCard`、最多两条`nextSummaries`、`waitingCount`和一个固定底部`chatComposer`；精确基数与路由模式由Workbench合同控制。卡片冻结taskId/revision、Subject选择器、Owner Appointment、businessPurpose、primaryCommand、expectedCompletionFact、SLA及版本化表单Schema。`currentCard.actionDraft`固定为null或当前Task唯一已授权草稿，字段恰为`draftId`、`draftRevision`、`actionCode`、`schemaVersion`、`values`、`digest`、`updatedAt`、`editable`；刷新恢复只通过`GET /api/v1/workcards/current`完成，不新增第二个Draft读取端点。

- [ ] 命令结果码冻结为数据库现有枚举：ContactResult只允许`CONNECTED_VALID|NOT_CONNECTED|SUSPECT_INVALID`。P0-04 Decision只允许`SCHEDULE_ROUTING_REVIEW|RETRY_ASSIGNMENT_NOW|REQUEST_SOURCE_INTAKE_STOP`；最后一项只是请求，不证明来源已停用。

- [ ] 实现HTTP矩阵的封闭错误注册表、per-operation引用、`currentETag`种类和重试策略。零分配候选是P0-04正常完成分支，必须创建`RESOLVE_LEAD_ROUTING_GAP`，不得建模为HTTP错误。

- [ ] 配置OpenAPI Generator 7.25.0只生成Spring interface/model到`target/generated-sources/openapi`；前端使用`openapi-typescript@7.13.0`生成`schema.d.ts`，并以`openapi-fetch@0.17.0`调用。两端生成物必须可重复，不能手改。

- [ ] 运行契约、服务端生成和前端类型检查至通过。

```bash
./mvnw -f backend/pom.xml test -Dtest=OpenApiContractTest
npm run openapi:check
npm run typecheck
```

- [ ] Commit:

```bash
git add contracts backend/pom.xml backend/src/test apps/workbench
git commit -m "feat(api): define R1 OpenAPI contract"
```

## Task 3: 建立真实PostgreSQL、jOOQ和能力角色测试底座

**Files:**

- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/testing/PostgresIntegrationTest.java`
- Create: `backend/src/test/resources/db/bootstrap-runtime-logins.sql`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/CapabilityRoleExecutor.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/CapabilityRoleExecutorIT.java`
- Create: `backend/scripts/generate-jooq.sh`
- Generate: `backend/src/generated/jooq/io/github/windyzhu3/ontologylaw/{identity,audit,execution,responsibility,party,lead,opportunity}/internal/persistence/jooq/`
- Create: `backend/src/generated/jooq/MANIFEST.sha256`
- Modify: `backend/pom.xml`

- [ ] 先写Testcontainers测试：从`database/schema-contract-52-plus-2/runtime/toolchain.lock.json`读取PostgreSQL 18 RepoDigest并拒绝未锁定tag；从空库运行20个迁移；创建`law_api_login LOGIN NOINHERIT`并只授Command/Query/Audit成员关系，创建`law_worker_login LOGIN NOINHERIT`并只授Worker成员关系。

- [ ] 测试`CapabilityRoleExecutor`每个事务显式`SET LOCAL ROLE`，事务结束后角色恢复；API登录不能长期继承权限并集，Worker不能写Lead/Task/Audit，浏览器无数据库凭据。

- [ ] 运行测试确认失败，然后实现最小role executor与测试数据源配置。

- [ ] 使用已迁移PostgreSQL只为R1实际访问的表生成jOOQ；每个生成的record/POJO写入对应Fact Owner的`internal.persistence.jooq`，禁止`shared`生成根和跨Owner引用。提交统一`MANIFEST.sha256`，`generate-jooq.sh --check`重生成到临时目录并逐字节比较。R1不得生成DAO或Active Record。

- [ ] 运行实库、权限、jOOQ漂移与ArchUnit测试。

```bash
./mvnw -f backend/pom.xml verify -Pit
backend/scripts/generate-jooq.sh --check
```

- [ ] Commit:

```bash
git add backend
git commit -m "build: add PostgreSQL jOOQ integration base"
```

## Task 4: 实现CommandRuntime原子合同

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

## Task 5: 实现Lead接入和P0-01至P0-04

**Files:**

- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/LeadCommands.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/LeadIngressService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/AssignmentPolicy.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/internal/persistence/JooqLeadRepository.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/TaskFactory.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/internal/persistence/JooqTaskRepository.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/LeadIngressIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/LeadIngressCompletionIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/LeadAssignmentIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/LeadRoutingDispositionIT.java`

- [ ] 先写失败测试覆盖来源幂等、业务疑似重复、缺联系方式、自动分配、人工分配、零候选、跨租户候选和并发双分配。

- [ ] 严格实现Task合同冻结的`R1SourcePolicyRegistry`、`R1_DUPLICATE_CANDIDATE_V1`、business SLA/window、规范化/HMAC profiles及R1代码allowlist，不增加配置表或运行时规则DSL。候选继续按policy priority、appointment start、Appointment UUID升序选择第一项，不散列、不随机。

- [ ] `captureLead`只接收明文输入到应用边界；服务端加密/HMAC并写不可变Lead。疑似重复创建`RESOLVE_LEAD_DUPLICATE`；缺联系方式创建`COMPLETE_LEAD_INGRESS`；人工模式创建`ASSIGN_LEAD`；其余路径原子创建LeadAssignment、更新current pointer并创建CONTACT_LEAD。

- [ ] P0-01用`DecisionRecord(LEAD_DUPLICATE_RESOLUTION)`完成Task；`LINK_EXISTING_PARTY`必须把当前Lead解析到候选Lead的同一活动Party并标记处置，`KEEP_SEPARATE`继续缺失/分配判断。不得删除或合并Lead行。

- [ ] P0-02用V850槽完成：只允许原始联系方式缺失且槽全空；服务器生成密文/HMAC/摘要；Lead CAS修订、Task DONE及后续分配/零候选责任同事务提交。

- [ ] P0-03创建唯一OPEN LeadAssignment、回填Lead current pointer并创建CONTACT_LEAD；Owner不符合Appointment/授权时拒绝且无部分写入。

- [ ] P0-04用`DecisionRecord(LEAD_ROUTING_DISPOSITION)`完成：`SCHEDULE_ROUTING_REVIEW`创建新同Owner Task并OPEN→WAITING＋WaitReceipt；`RETRY_ASSIGNMENT_NOW`只运行一次当前候选政策；`REQUEST_SOURCE_INTAKE_STOP`先按Source Policy来源接入root唯一解析ACTIVE且具备`SOURCE_INTAKE_REQUEST_ACK`、无DENY的Appointment，零个或多个返回`SOURCE_INTAKE_OWNER_UNRESOLVED`且全部delta为0。解析成功才创建`ACK_SOURCE_INTAKE_STOP_REQUEST`，不改写任何全局来源状态；后者由`ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST`和`DecisionRecord(SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED)`完成。

- [ ] 运行四组实库测试及并发测试至通过。

- [ ] Commit:

```bash
git add backend/src/main/java/io/github/windyzhu3/ontologylaw/{lead,responsibility} backend/src/test/java/io/github/windyzhu3/ontologylaw/lead
git commit -m "feat: implement R1 lead intake responsibilities"
```

## Task 6: 实现CurrentCard与ActionDraft

**Files:**

- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardQuery.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCard.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardProjectionService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/ActionDraftService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/internal/persistence/JooqActionDraftRepository.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/responsibility/ActionDraftIT.java`

- [ ] 先写失败测试：任一Appointment最多返回一张完整OPEN卡；排序固定为逾期、原始SLA截止、任务创建时间、UUID；envelope还必须有一句`todaySummary`、最多两条摘要、等待计数和固定`chatComposer`。WAITING/他人责任/未授权对象不能成为完整卡。

- [ ] 测试一Task最多一份Draft；保存使用revision CAS；action/schema必须等于Task静态注册；确认时只能回传服务器签发的digest且必须匹配；`GET /api/v1/workcards/current`按冻结字段返回草稿供刷新恢复；确认后不可编辑；确认本身不完成Task。

- [ ] Query只组合各Fact Owner的具名read port；SQL和jOOQ Record留在对应Owner的`internal.persistence`，每条SQL显式绑定tenantId并先解析Subject后做四轴授权。敏感字段仅按卡片Schema最小解密，不把jOOQ Record暴露给Query或API。

- [ ] 实现并运行两组测试至通过。

- [ ] Commit:

```bash
git add backend/src/main/java/io/github/windyzhu3/ontologylaw/{query,responsibility} backend/src/test/java/io/github/windyzhu3/ontologylaw/{query,responsibility}
git commit -m "feat: serve one current work card"
```

## Task 7: 实现ContactResult、重试与主管复核

**Files:**

- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/ContactResultService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/lead/RetryPolicy.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/opportunity/OpportunityOpeningService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/WaitLifecycleService.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/ContactResultIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/responsibility/WaitLifecycleIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/LeadValidityReviewIT.java`

- [ ] 先写失败测试：Task类型/Owner/Assignment/Lead路径不一致拒绝；每个Task至多一条ContactResult；contactNo在Lead内递增；并发提交只有一个成功；同Command重放返回原Receipt。

- [ ] `CONNECTED_VALID`要求确认Draft携带HTTP合同的`legalNeed: SafeText2000`，以该原文生成Opportunity的受保护`legal_need_ciphertext/digest`，并原子写ContactResult、唯一Opportunity锚点、Task DONE、Audit/Event/Outbox/Receipt；不得静默复用Lead捕获摘要。R1只产生`OpportunityOpened`边界，不创建无Handler的R2 Task，也不实现报价或实质商机推进。

- [ ] `NOT_CONNECTED`严格消费`CONTACT_RETRY_V1`：初次计attempt 1、总次数最多3；attempt 1/2分别按Source Policy IANA时区与`CN_WEEKDAY_V1`下一工作日10:00/15:00恢复，due为恢复后30分钟，并优先切换到另一受控可用channel。可重试时原子完成当前Task并创建新CONTACT_LEAD Task：先OPEN revision=0，再同事务CAS到WAITING revision=1并追加WaitReceipt；attempt 3耗尽时不再创建联系重试，只创建主管复核。到期由内部具名命令WAITING→OPEN，不能直接插入WAITING。

- [ ] `SUSPECT_INVALID`或重试耗尽完成当前Task并创建主管`REVIEW_LEAD_VALIDITY` OPEN Task；复核用DecisionRecord完成，`CONFIRM_INVALID`和`CLOSE_UNREACHED`结束，`REOPEN_CONTACT`创建新CONTACT_LEAD而不重开旧Task。

- [ ] 测试`WAITING→DONE`只能由准确冻结完成Fact触发；到期本身只恢复OPEN。等待客户、Provider或其他Owner不得把原人工Task挂WAITING。

- [ ] 运行三组实库和故障注入测试至通过。

- [ ] Commit:

```bash
git add backend/src/main/java/io/github/windyzhu3/ontologylaw/{lead,opportunity,responsibility} backend/src/test/java/io/github/windyzhu3/ontologylaw/{lead,responsibility}
git commit -m "feat: close the first-contact fact loop"
```

## Task 8: 接通API、Worker和安全恢复路径

**Files:**

- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/R1ApiDelegate.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/ProblemDetailsAdvice.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/security/ActorContextResolver.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/worker/DueTaskScheduler.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/worker/InternalApiClient.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/worker/RuntimeRoleIT.java`

- [ ] 先写HTTP集成测试覆盖所有OpenAPI端点、错误码、ETag/If-Match、Receipt恢复及未知提交结果。生产认证使用OAuth2 Resource Server；测试profile使用进程内签名JWT，Claims只提供候选Tenant/Principal/Appointment，数据库仍实时重验。

- [ ] API delegate只做DTO转换和调用具名Application Service；不得直接使用DSLContext或生成jOOQ类型。

- [ ] Worker不持有Command/Query/Audit数据库能力。`DueTaskScheduler`逐张Task调用mTLS保护的internal端点；每次提交Task/WaitReceipt准确selector并只恢复一张Task，internal端点使用Service Principal且仍走CommandRuntime。

- [ ] 测试API角色无Worker Bean、Worker角色无Controller/Command数据库成员资格；两个角色可由同一Jar分别启动，缺少对端时不会伪造任务恢复成功。

- [ ] 运行HTTP与双角色测试至通过。

- [ ] Commit:

```bash
git add backend/src/main/java/io/github/windyzhu3/ontologylaw/{api,worker} backend/src/test/java/io/github/windyzhu3/ontologylaw/{api,worker}
git commit -m "feat: expose R1 API and worker roles"
```

## Task 9: 实现单SPA工作台

**Files:**

- Create: `apps/workbench/src/App.tsx`
- Create: `apps/workbench/src/features/workcard/CurrentCard.tsx`
- Create: `apps/workbench/src/features/workcard/ActionDraftForm.tsx`
- Create: `apps/workbench/src/features/workcard/WaitingSummary.tsx`
- Create: `apps/workbench/src/features/workcard/useCurrentCard.ts`
- Create: `apps/workbench/src/features/workcard/CurrentCard.test.tsx`
- Create: `apps/workbench/src/features/workcard/ActionDraftForm.test.tsx`
- Create: `apps/workbench/src/features/workcard/WaitingSummary.test.tsx`
- Create: `apps/workbench/src/styles/tokens.css`
- Create: `apps/workbench/src/styles/workbench.css`
- Create: `apps/workbench/src/test/setup.ts`

- [ ] 先按P0-01至P0-04与基础首联视觉写Vitest/Testing Library失败测试：一句今日摘要、一张展开卡、一个绿色主按钮、选项只改变主命令参数、最多两条摘要、等待计数、固定Chat Composer、刷新恢复Draft、提交未知时按CommandId恢复Receipt。

- [ ] 实现响应式工作台，使用暖白/石墨/翡翠绿/浅薄荷tokens；DTO保留taskType/revision供合同与并发使用，但普通用户只看到本地化业务目的和安全版本状态，不渲染原始Task/Event/Decision/hash/WAITING代码。只保留受保护`IDENTITY_ADMIN` route mode壳，不创建Customer壳、独立应用或R1身份管理页面。

- [ ] 对四类入口卡、CONTACT_LEAD、重试摘要和无效复核卡分别实现由OpenAPI discriminator驱动的固定表单；不接受服务器未注册的动态组件或任意Schema执行。

- [ ] 加入键盘焦点、ARIA错误关联、颜色对比和360/768/1440宽度测试；主命令提交期间防双击但不依赖按钮禁用实现幂等。

- [ ] 运行测试、类型检查和构建至通过。

```bash
npm test
npm run typecheck
npm run build
```

- [ ] Commit:

```bash
git add apps/workbench package.json package-lock.json
git commit -m "feat(web): implement the R1 workbench"
```

## Task 10: 真实端到端验收和R2门禁

**2026-09-13 执行衔接：** 用户要求加速R1并暂缓既有等待记录的真实到期Worker恢复（T9-W09=`DEFERRED_BY_USER`）。该时间项不阻塞本Task实施；不得将暂缓记为运行通过。先核对/复用Task9已有真实身份链、六卡及等待刷新证据，补齐下列黄金/关键失败路径、可复现环境与CI缺口。Task9其他安全/授权/恢复必需项不豁免，最终报告明确区分已证实、缺失及用户延期项，不自动推进R1/R2状态。原本节是2026-08-28计划，实施必须服从后续批准的Keycloak/OIDC、动态身份管理、当前OpenAPI和物理合同，不能回退旧身份假设或照抄旧版本值。

**Files:**

- Create: `e2e/compose.yaml`
- Create: `e2e/fixtures/r1-fixture.json`
- Create: `e2e/tests/r1-golden-path.spec.ts`
- Create: `e2e/tests/r1-failure-paths.spec.ts`
- Create: `e2e/tests/r1-waiting-path.spec.ts`
- Create: `.github/workflows/r1-vertical-slice.yml`
- Create: `docs/evidence/r1/README.md`
- Create after pass: `docs/evidence/r1/2026-08-28-r1-runtime-report.md`
- Modify: `docs/progress/MVP-DELIVERY-LEDGER.md`

- [ ] 用`database/schema-contract-52-plus-2/runtime/toolchain.lock.json`中的PostgreSQL 18 RepoDigest、API、Worker和SPA建立一次性环境；禁止浮动tag、H2或stub后端。Fixture包含主Tenant、隔离哨兵Tenant、来源负责人、销售、主管、撤销Appointment和自动/人工/零候选来源政策。

- [ ] 黄金路径：Capture Lead→自动Assignment→CONTACT_LEAD CurrentCard→保存/刷新Draft→CONNECTED_VALID→ContactResult＋Opportunity＋Event/Audit/Receipt→Task DONE→按CommandId恢复相同Receipt。

- [ ] 失败/分支路径逐行覆盖Task完成矩阵全部BranchID和E2E delta，包括来源幂等、P0-01两种决定、P0-02一次补全和二次覆盖拒绝、P0-03越权Owner、P0-04三种处置、来源停用请求ACK、联系重试/耗尽/疑似无效、主管三结果、同Command异payload/异Scope零新增、过期revision、Appointment提交前撤销、Audit插入失败全回滚、跨租户拒绝、浏览器双击及网络响应丢失后Receipt恢复。

- [ ] 等待路径：NOT_CONNECTED创建新OPEN后同事务WAITING r1＋WaitReceipt；到期internal命令恢复OPEN；旧Task不重开；SUSPECT_INVALID创建主管Task并以DecisionRecord收口。

- [ ] 保存浏览器、API和数据库证据：Git SHA、镜像digest、Java/Node/PostgreSQL版本、OpenAPI摘要、合同摘要、命令、退出码、测试计数、关键Fact ID和脱敏事务一致性摘要；不得保存联系方式明文、密文、HMAC或JWT。

- [ ] CI顺序固定为baseline→schema static→schema PostgreSQL v1.1→backend unit/architecture→backend integration→OpenAPI generation→SPA test/build→Playwright。任一步失败时不推进台账。

- [ ] 只有全部通过后创建并推进：`R1-OPENAPI`、`R1-BACKEND`、`R1-SPA`为`IMPLEMENTED`；`R1-E2E-GOLDEN`、`R1-E2E-FAILURES`为`RUNTIME_VERIFIED`。不要把视觉FROZEN或单元测试当运行验证。

- [ ] Commit:

```bash
git add e2e .github/workflows/r1-vertical-slice.yml docs/evidence/r1 docs/progress/MVP-DELIVERY-LEDGER.md
git commit -m "test: verify the R1 vertical slice end to end"
```

## Exit Gate

- [ ] V850以前的迁移零字节变化，v1.1在两次空PostgreSQL 18中以20迁移验证通过。
- [ ] 一份OpenAPI、一个SPA、一个Jar及api/worker互斥角色由CI机械证明。
- [ ] P0-01至P0-04、首联三结果、Draft恢复、Command幂等、授权竞态、全事务回滚和WAITING规则均有真实PostgreSQL测试。
- [ ] R1黄金与关键失败路径有浏览器/API/数据库联合证据。
- [ ] R1不包含报价、冲突、合同、签署、付款、转案、AI或通用平台扩张。
- [ ] 只有R1三层`IMPLEMENTED`且黄金/失败路径`RUNTIME_VERIFIED`后，R2计划才可进入执行。

## Task 10.1: 串联既有CI预检入口（非R1运行验收）

这是原Task10 CI顺序的首批接线，用户已要求继续，不新增产品或测试框架。真实compose/黄金/失败浏览器仍待后续；本单元绿色仅为preflight，不是R1 runtime或R2准入。W09延期、原本地私密runtime/服务/账号/证据不动。

Files: 新增`.github/workflows/r1-vertical-slice.yml`、`scripts/ci/r1-preflight.sh`、`tests/test_r1_preflight.py`；修改根`package.json`仅新增具名离线E2E脚本；可在`e2e/README.md`追加简短CI入口说明。Root负责本计划、总索引和进度。禁止业务/UI/合同/DDL/依赖版本/已有workflow/现有E2E消费者修改，不创建空真实测试或假runtime报告。

实现一个短小固定顺序Bash驱动，不建调度/配置引擎：baseline→schema static→当前PG18两轮runtime→backend unit/architecture/integration→OpenAPI生成漂移→SPA typecheck/test/build→明确offline Playwright。调用仓库已有命令和锁定工具，任何阶段非零即非零退出、不执行后续阶段。Maven用一次`verify -Pit`按生命周期覆盖unit/integration，不再提前重复unit全套；jOOQ漂移按现有foundation命令核对。schema runtime沿现有workflow的`verify_runtime.py verify --ci-only --runs 2`和有效证据检查，不用旧v1.1覆写当前v1.2。各阶段简短输出名称，不导出秘密或成功runtime报告。

新增npm离线入口必须显式`--config playwright.config.ts --project offline-harness`及`--config e2e/business.config.ts --project offline-business`，不能调用默认包含approved-local的Task9入口、不添加任何真实授权环境变量。受控发现两配置只选择上述项目；需要Chromium的纯合成DOM由CI安装锁定Playwright所属Chromium，不使用系统Chrome或连接原本地runtime。

新workflow明确命名R1 preflight（not runtime acceptance），pull_request/push main/workflow_dispatch，contents read，checkout无持久凭据且完整历史；复用现有固定action SHA与Python3.12.14、Java25.0.4.1精确build、Node24.20.0/npm11.9.0、已锁PyYAML/pglast/仓库依赖，不用新浮动action、不升级依赖。setup/install后调用同一个Bash驱动。失败不continue-on-error、不绕过步骤；不给任何R1/R2交付行写权限，不触发push/deploy，不上传私密文件。summary明确本次只预检，真实E2E仍未执行/W09按用户延期，绿色不等于runtime通过。

TDD验证真正Bash驱动行为：使用临时目录/PATH的外部命令替身，记录实际调用和退出；固定期望阶段顺序；至少一个中间失败验证原非零退出且后续没有运行；正例走完且仅声称preflight；参数/路径不越界。不要只grep脚本/YAML文本、不要复制整个调度器到测试、不要实际运行数据库或业务服务来测试失败顺序。若需要校验workflow接线，解析真实YAML取实际run命令/工作目录并运行相关消费者，静态检查的限制如实说明，不模拟GitHub Actions成功。

最终只跑本单元新测试、shell语法、两个实际Playwright离线项目`--list`及必要既有topology核对；不重跑backend/SPA/215业务/identity全套或任何真实本地链。完整CI执行留到托管runner，不能声称本地已跑通GitHub。先RED再GREEN，报告命令/退出/限制，提交仅ownedfiles，独立评审后由Root更新完成状态。

状态（2026-09-13）：Task10.1范围内完成，实现`b0ffd32`、工作目录修复`5b7e663`；4项新增测试通过，独立评审唯一Important修复后限定复核APPROVED。托管CI/完整驱动未执行；Task10整体仍开放。下一批是隔离环境和受控fixture，再补黄金/关键失败真实链，W09不在当前关键路径。详见[统一证据索引](../../evidence/r1/README.md)。

## Task 10.2: 隔离端到端环境装配

实施原Task10的一次性环境要求，复用现有local-login的真实服务装配方式，不复用其runtime或旧验收记录。用户已要求继续完整实施验收；本单元不改产品、UI、OpenAPI、权限或迁移。现有本机Docker约8GiB且旧环境运行中，禁止停止旧服务释放容量；新环境为功能验收，不代表参考容量验收。

Files: 新建`e2e/compose.yaml`、`e2e/fixtures/r1-fixture.json`、`e2e/runtime/`下职责明确的准备/启动工具及必要HTTPS静态代理、`tests/test_r1_environment.py`；可更新`e2e/README.md`。Root负责计划/总证据索引，不修改已有Task9消费者或私密目录。不增加npm/Maven依赖或第二SPA/Jar，不建通用部署框架。

Topology: 使用现有锁定PG18、Flyway、Keycloak镜像，以新的`ontology-law-r1-e2e-<run>` compose project隔离两个数据库、网络和卷，无container_name或external volume；所有对主机发布端口绑定127.0.0.1，与旧19443–19446分离。API和Worker复用同一个从本工作树构建的Jar、同版本宿主Java25.0.4.1+1，SPA由既有固定Node24.20.0运行的HTTPS静态代理托管同一构建产物；此本地/CI宿主模式无需新增Java/Node镜像。compose负责PG/Keycloak/Flyway，启动工具负责三宿主进程，未来Linux runner同构装配，不声称所有进程均容器化。

Preparation: 仅接受本worktree内gitignored的专用`.artifacts/r1-e2e/<run>`目录，规范run ID、路径/链接检查；首次独占创建且拒绝覆盖/接管既存或不完整run。秘密文件先保护再写（Windows仅当前用户与SYSTEM，POSIX0700/0600），不得接收旧Task9runtime或打印秘密。准备工具产生随机合成账号密码、独立tenant密钥、PG角色密码、临时CA及准确SAN服务器证书/Java truststore；不安装全局信任、不关闭TLS。Secret通过文件注入，不入命令行或提交。模板仅非秘密角色/来源场景：主Tenant、隔离哨兵Tenant、founder、销售、主管、来源负责人、撤销任职、自动/人工/零候选来源；fixture是准备输入，不是业务结果或PASS声明。Keycloak复用realm-template安全参数和只读目录角色，准确issuer/redirect/origin，账号只在新realm合成导入，不修改旧Keycloak、不引入默认管理员。

Startup: 完整校验锁定版本、所需文件/摘要、空闲端口与精确project隔离后才启动。只启动本run新基础设施；schema用当前生成迁移migrate→validate，不重写DDL、关闭guard或绕过能力角色；应用数据库与IdP数据库独立TLS及凭据。原子保存run manifest、命令阶段和实际进程/制品信息；失败不自动重试未知写入、不删除卷或原证据。API/Worker配置和身份前置若尚未具备，明确状态止于基础设施就绪，不生成“全环境READY”；真实HUMAN仍走现有IdentityBootstrapCommand/Identity管理API，不用SQL伪造HUMAN事实。后续fixture装载和黄金链执行属于原Task10后续步骤，须消费这些准确准备产物，不能把环境工具测试冒充真实验收。

Verification: 先RED→GREEN覆盖实际准备器输出/文件副作用、重复run拒绝、路径/秘密边界、真实compose config解析、失败阶段停止；外部openssl/keytool/docker进程可在失败用例替换，不能只grep源代码或证明mock存在。尽量使用当前工具实际生成一次临时合成准备产物，并`docker compose config --quiet`，不输出完整配置/凭据。实现者不启动服务、不执行bootstrap/业务写入；Root在独立评审后执行新隔离环境，记录真实退出与就绪阶段，再进入业务fixture及黄金/失败路径。W09延期、UAT关闭、R2/附件通知/语音范围均不变。提交owned source files，完整报告命令/退出/差距，不伪称托管CI通过。

2026-09-13实际运行网络澄清（用户已明确批准，仅新隔离环境）：Docker29.4.3下仅internal网络的服务出现声明端口但实际映射为空。保留identity/business两个internal网络，只给Keycloak和business-db分别增加独立的identity-host/business-host普通bridge；两个host桥不得共享，不使用host网络模式，身份数据库仍只有internal网络。发布仍严格127.0.0.1:29443/29446；普通bridge具备出站能力，但不增加外网业务请求、不更改旧环境或宿主防火墙。启动工具必须核对实际NetworkSettings.Ports而不只看声明配置，映射缺失或错误立即失败并保留现场，不继续等TLS。失败run只停止保留，源变更后新建run，不在线修改旧网络或manifest。

## Task 10.3: 消费隔离环境的受控身份引导

这是原Task10 fixture前置，不新增身份产品能力。只消费Task10.2新环境，使用已有同一Jar的`IdentityBootstrapCommand`完成主Tenant和隔离哨兵Tenant的受控初始化。API/Worker/SPA进程装配及人类业务授权由后续消费者完成，本单元不得把bootstrap成功写成应用READY或黄金链PASS。

Files: 新增`e2e/runtime/r1_bootstrap.py`和`tests/test_r1_bootstrap.py`；仅必要时向`e2e/runtime/r1_environment.py`增加可复用的已准备输入验证入口，不修改既有manifest或接管旧run；可更新`e2e/README.md`。不改业务Java、DDL、OpenAPI、前端、依赖、Task9消费者或旧私密目录。Root负责计划/证据索引。

输入只接受本工作树`.artifacts/r1-e2e/<run>`且真实状态为`INFRASTRUCTURE_READY`。首先验证其精确Compose项目、输入摘要、受保护目录和当前制品，验证数据库部署状态与Jar/schema摘要一致。环境manifest保持原样，新引导记录独占建立在该run内的受保护子目录。所有秘密只引用本run已有文件；为两个Tenant分别生成UUID及独立subject HMAC，不能与bootstrap签名密钥或其他用途密钥混用。主Tenant代码`R1_E2E_MAIN`，哨兵`R1_E2E_ISOLATION`；ROOT组织，创始管理员仅`IDENTITY_ADMIN`及现有四项管理授权，语义基线`MVP-2026-09-08.3`、物理合同`52-plus-2-v1.2`。创始账号使用本run已导入的founder合成账号；哨兵与主Tenant可以映射同一个IdP账号，但分别绑定Tenant及独立HMAC，本步骤不配置浏览器的跨租户选择或新增信任规则。

命令固定复用`IdentityBootstrapCommand`的candidate→dry-run→execute --confirm-bootstrap→verify；本地离线Java明确使用`-Xmx256m`避免按整机内存推导堆，不作为参考容量配置。所有selector、settings、original manifest和原commandId只保存在受保护文件。candidate标准输出必须捕获至秘密文件，不写普通日志；不打印selector、subject HMAC、密码或令牌。保留原操作manifest与每阶段退出码，失败/不确定停止，不创建替代command或重发execute；重复入口拒绝覆盖，另有只读verify-original入口只能复用原文件和原command，不修复/篡改证据。不得SQL创建HUMAN/组织/任职/授权，不使用管理员密码grant或绕过目录服务。两个Tenant都通过实际original verify后只标记`IDENTITY_BOOTSTRAP_VERIFIED`（applicationReady仍false）；保留每Tenant精确Fact ID的脱敏引用，来源须是原bootstrap验证或受约束只读DB查询，不凭测试构造猜测ID。

固定引导字段：两个Tenant的`identityProviderCode`分别等于其`tenantCode`；共同`operatorAssertion=Controlled R1 isolated synthetic identity bootstrap`、`node=R1_E2E_BOOTSTRAP`、`activeBootstrapKeyId=r1-e2e-bootstrap-v1`。Tenant展示名取公开fixture，两个ROOT展示名分别为`R1 synthetic firm root`与`R1 isolation sentinel root`，管理员展示名为`Synthetic Founder`。后继API仅对主Tenant使用`R1_E2E_MAIN` HUMAN trust，不因哨兵bootstrap重复注册相同issuer/audience。

Java标准输出/错误在调用源头固定`-Dstdout.encoding=UTF-8`与`-Dstderr.encoding=UTF-8`，以bytes捕获再严格UTF-8解码；秘密输出原始字节仅留受保护文件，解码/JSON异常必须失败关闭，不使用替换或猜测编码。Task10.2已由真实固定keytool使用相应`-J-D…`参数验证Windows输出边界；本命令调用Java本体不加`-J`。

TDD覆盖真实准备/阶段调度的副作用和停止行为：错误环境/摘要/权限在任何candidate前拒绝；重复初始化不重放；candidate失败、execute非零不进入后续写入；验证入口只执行verify；两个Tenant参数和独立密钥确切；普通输出无秘密。外部Java/数据库可用进程替身验证调度，不复制实现逻辑，不把替身结果当真实bootstrap。只运行本单元定点测试和必要受影响环境测试；实现者不执行真实bootstrap，由Root在独立评审通过后运行并记录实际命令/退出/阶段。W09延期、UAT关闭以及待授权的历史Party/Delegation/账号禁用/故障注入均保持原状态。

## Task 10.4: 新隔离环境的既有应用装配

承接Task10.3实际原始引导核验，只装配当前唯一Jar的API/Worker和当前唯一SPA，不新增业务端点、前端页面、身份能力或通用部署平台。HUMAN主体、组织、任职、业务DIRECT授权仍由后继真实管理API建立，本单元不得用SQL提前填入。尚未获准的历史Party/Delegation/账号禁用及故障注入仍不执行。

**Files:** 新增`e2e/runtime/r1_applications.py`、`e2e/runtime/r1_server.mjs`、`tests/test_r1_applications.py`、`e2e/runtime/r1_server.test.mjs`；允许更新`e2e/README.md`。不改Task10.2已摘要的源文件、旧部署脚本、业务Java、DDL、OpenAPI、SPA源代码或依赖。

**接口和输入：** `prepare_applications(root: Path, run: str) -> Path`独占建立本run下受保护`applications/`；`start_applications(root: Path, run: str) -> None`只消费已准备且摘要一致的输入；`verify_applications(root: Path, run: str) -> dict`只读检查原进程/配置/数据库前置，不执行补写或重启。入口分别为`prepare`、`start`、`verify`，参数仅run，凭据不进命令行。消费`r1_bootstrap.verify_original(root, run)`返回的`R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1`，其`tenants[R1_E2E_MAIN|R1_E2E_ISOLATION]`包含`tenantId/rootOrganizationId/founderPrincipalId/appointmentId/authorityGrantIds/subjectHmacPath/originalVerificationEvidenceSha256`；该调用只核验原命令、返回路径而非秘密字节。不得用手写完成marker替代原核验，不复制其验证算法。

- [ ] **先写失败测试。** 外部数据库/进程用替身，但真实执行保护、配置生成、命令构造及状态机。测试必须证明：bootstrap缺失/不匹配在任何SERVICE写入前拒绝；输入摘要漂移在启动前拒绝；prepare重复调用不重放；原SERVICE事务结果未知不重发；API失败不启动Worker/SPA；启动进程PID/创建时间不匹配不能READY；正常配置只绑定29444/29445、仅主Tenant HUMAN trust、Worker三项授权、来源三项政策、全部不同用途密钥。测试入口固定如下：

```powershell
D:/soft/python3/python.exe -m unittest tests.test_r1_applications
```

- [ ] **受控SERVICE前置和配置。** 参考`deploy/local-login/local_login.py`中SERVICE-only初始化及`local_worker.py`中的授权闭包，不导入其旧私密runtime、不运行旧命令。仅本主Tenant新建一个`SERVICE/LOCAL_SERVICE`主体及ROOT下SERVICE任职；三个DIRECT Grant仅`R1_PROJECTION_CONSUME`、`CONTACT_TASK_RECOVER`、`ROUTING_REVIEW_TASK_RECOVER`，grantor必须为原bootstrap创始任职。一次事务先核验原Tenant/ROOT/founder/manifest及部署摘要，写入原已保存UUID，准确核对新增行数；只读核验原UUID/字段集合，不按名称收养既存数据。此基础设施fixture不伪造HTTP命令回执。

- [ ] **生成保护配置。** API使用`law_api_login`，Worker使用`law_worker_login`，均`sslmode=verify-full`，准确Jar/schema摘要和当前合同。API只配置主Tenant `R1_E2E_MAIN`、已冻结issuer/audience/directory/introspection；六项TenantKeys用途独立，credential-subject-hmac必须沿用主Tenantbootstrap密钥。为新SERVICE创建独立clientAuth证书/私钥/PKCS12及公钥，API绑定准确指纹，Worker使用同一原SERVICE任职及指纹，严格CA/mTLS，不复用服务器私钥为SERVICE。所有新增秘密在`applications/`保护边界内，原准备manifest和秘密保持原样。

来源政策固定`R1_AUTO=AUTOMATIC`、`R1_MANUAL=MANUAL`、`R1_ZERO_CANDIDATE=AUTOMATIC`。前两项候选根`OWNED_ROOT`，零候选根`EMPTY_ROOT`；主管根、接入根均`ROOT`，时区`Asia/Shanghai`。两子组织由后继管理API在任何业务capture前建立；本单元只配置，不SQL创建HUMAN组织。SERVICE注册只允许这三来源，服务issuer=`urn:r1-e2e:service`、audience=`r1-e2e-api`。

- [ ] **同制品启动。** 原Jar同时用于互斥api/worker启动，分别`-Xmx384m`；固定Java stdout/stderr UTF-8，API只监听127.0.0.1:29445，Worker无HTTP监听。Node只监听127.0.0.1:29444，使用原SPA dist字节；新`r1_server.mjs`保留旧`server.mjs`的准确路由白名单、CSP、缓存、路径拒绝与严格TLS代理语义，端口改为新环境固定值，不引入任意origin/路径配置或生产改动。进程隐藏启动、立即保存PID/命令/创建时间；部分启动或不确定结果保留现场，不自动杀旧进程或重启。

- [ ] **就绪和反例。** 新Node入口用原始字节摘要确认dist，要求准确Host，代理移除转发/代理头，10秒上游超时，CA严格校验；测试真实本机临时TLS服务器验证错误CA拒绝和正确CA代理，临时测试端口不得占用原/新固定服务端口。Node单元入口：

```powershell
node --test e2e/runtime/r1_server.test.mjs
```

实际Root启动后，只有准确当前PID/启动时刻的API/Worker隔离及READY日志、严格TLS的SPA200/未认证SELF401和SERVICE readiness200同时满足才记录`APPLICATION_INFRASTRUCTURE_READY`，不能记真实用户登录、责任卡或黄金链PASS。不调用新的业务主命令；后继浏览器验收另建记录。实现者只运行本单元定点测试，Root独立评审后执行一次真实入口。

- [ ] **提交和评审。** 提交只含本单元文件，报告列准确测试命令/退出码/覆盖边界；按Task10.3原始消费者接口交接，不复制其验证算法，不给原环境添加恢复或重放机制。
