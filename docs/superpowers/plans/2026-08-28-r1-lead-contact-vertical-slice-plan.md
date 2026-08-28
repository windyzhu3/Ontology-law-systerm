# R1 Lead Contact Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以一份OpenAPI、一个响应式SPA和一个Spring Boot模块化单体制品，实现Lead接入、P0-01至P0-04入口处置、唯一分配、CONTACT_LEAD工作卡、ActionDraft、ContactResult、重试/无效复核及可恢复CommandReceipt的首个真实垂直切片。

**Architecture:** 单一后端Jar按`ols.runtime-role=api|worker`互斥启动；业务模块按Spring Modulith包边界组织，领域包不依赖Spring/jOOQ。所有写入通过CommandRuntime的READ COMMITTED短事务与能力角色切换完成；jOOQ是唯一业务持久化方式。SPA只消费`contracts/openapi/ontology-law-api.yaml`生成的类型，并始终只完整展示一张CurrentCard。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring Modulith 2.1.1、jOOQ 3.21.7、Flyway 13.4.0、Testcontainers 2.0.0、PostgreSQL 18、OpenAPI Generator 7.25.0、Node 24.20.0、npm 11.9.0、React 19.2.8、Vite 8.2.2、TypeScript 7.0.2、Vitest 4.1.11、Playwright 1.62.1。

**Spec:** `docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md`

## Global Constraints

- 只有PR #2已合并且`DB-52P2-PG18-RUNTIME`达到`RUNTIME_VERIFIED`后，才从最新`main`创建`feat/r1-lead-contact-slice`。
- Task 0完成V850后，旧PostgreSQL证据立即只代表v1；必须对v1.1重新运行实库门禁，成功前不得开始Task 1生产代码。
- 只构建一个SPA、一份OpenAPI、一个后端Jar；不得恢复历史三SPA/四OpenAPI拓扑。
- 不引入JPA/Hibernate/Spring Data、H2、Kafka、Redis、BPMN、通用工作流、通用Job、EAV或运行时规则DSL。
- R1不调用LLM；AI关闭时全部路径必须可完成。
- 领域Fact是完成Task的唯一依据。ActionDraft确认、HTTP成功、用户点击、Audit、Event、Outbox或Receipt都不能替代完成Fact。
- 所有UUID由服务端生成UUIDv7；所有时间以数据库`clock_timestamp()`或同事务可信时间写入；客户端不能生成或决定HMAC、领域摘要、密文、Owner或可信时间，只能原样回传服务器签发的ActionDraft digest或HTTP ETag用于并发校验。
- 每个行为严格遵循RED→GREEN→REFACTOR；每个任务提交前运行其列出的测试。

---

## Task 0: 用V850补齐P0-02权威完成槽

**Files:**

- Create: `docs/adr/ADR-0001-lead-ingress-completion-slot.md`
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

- [ ] 创建npm workspace，只包含`apps/workbench`；所有依赖使用`--save-exact`并提交`package-lock.json`。禁止创建第二个前端package来模拟Admin/Customer。

- [ ] 运行拓扑、架构和空应用启动测试至通过。

- [ ] Commit:

```bash
git add .mvn mvnw mvnw.cmd backend .node-version package.json package-lock.json apps scripts/verify_topology.py tests/test_topology.py
git commit -m "build: scaffold single-artifact R1 topology"
```

## Task 2: OpenAPI-first冻结R1命令与查询

**Files:**

- Create: `contracts/openapi/ontology-law-api.yaml`
- Create: `contracts/openapi/examples/capture-lead.request.json`
- Create: `contracts/openapi/examples/current-work-card.response.json`
- Create: `contracts/openapi/examples/save-action-draft.request.json`
- Create: `contracts/openapi/examples/resolve-duplicate-lead.request.json`
- Create: `contracts/openapi/examples/complete-lead-ingress.request.json`
- Create: `contracts/openapi/examples/assign-lead.request.json`
- Create: `contracts/openapi/examples/record-routing-disposition.request.json`
- Create: `contracts/openapi/examples/record-contact-result.request.json`
- Create: `contracts/openapi/examples/review-lead-validity.request.json`
- Create: `contracts/openapi/examples/command-receipt.response.json`
- Create: `contracts/openapi/examples/reopen-due-contact-tasks.request.json`
- Create: `contracts/openapi/examples/problem.response.json`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`
- Create: `apps/workbench/src/generated/api/schema.d.ts`
- Create: `apps/workbench/src/lib/api.ts`
- Modify: `backend/pom.xml`
- Modify: `apps/workbench/package.json`

- [ ] 先写契约测试，要求唯一OpenAPI为3.1，所有写请求携带Bearer认证、`X-Tenant-Id`、UUID `X-Command-Id`、UUID `X-Correlation-Id`和准确`If-Match`；错误统一为RFC 9457 `application/problem+json`。

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
| POST | `/api/v1/tasks/{taskId}/commands/record-contact-result` | `recordContactResult` |
| POST | `/api/v1/tasks/{taskId}/commands/review-lead-validity` | `reviewLeadValidity` |
| GET | `/api/v1/commands/{commandId}/receipt` | `getCommandReceipt` |
| POST | `/internal/v1/tasks/commands/reopen-due-contact-tasks` | `reopenDueContactTasks` |

- [ ] `CurrentWorkCardEnvelope`固定包含零或一张完整`currentCard`、最多两条`nextSummaries`和`waitingCount`；卡片冻结taskId/revision、Subject选择器、Owner Appointment、businessPurpose、primaryCommand、expectedCompletionFact、SLA及版本化表单Schema。`currentCard.actionDraft`固定为null或当前Task唯一已授权草稿，字段恰为`draftId`、`draftRevision`、`actionCode`、`schemaVersion`、`values`、`digest`、`updatedAt`、`editable`；刷新恢复只通过`GET /api/v1/workcards/current`完成，不新增第二个Draft读取端点。

- [ ] 命令结果码冻结为数据库现有枚举：ContactResult只允许`CONNECTED_VALID|NOT_CONNECTED|SUSPECT_INVALID`。P0-04 Decision只允许`SCHEDULE_ROUTING_REVIEW|RETRY_ASSIGNMENT_NOW|REQUEST_SOURCE_INTAKE_STOP`；最后一项只是请求，不证明来源已停用。

- [ ] 固定安全错误码：`COMMAND_PAYLOAD_CONFLICT`、`STALE_SUBJECT`、`APPOINTMENT_INACTIVE`、`NOT_AUTHORIZED`、`TASK_NOT_OPEN`、`TASK_ALREADY_COMPLETED`、`DRAFT_DIGEST_MISMATCH`、`INGRESS_COMPLETION_ALREADY_RECORDED`、`NO_ASSIGNMENT_CANDIDATE`。

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
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/shared/internal/persistence/CapabilityRoleExecutor.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/shared/internal/persistence/CapabilityRoleExecutorIT.java`
- Create: `backend/scripts/generate-jooq.sh`
- Generate: `backend/src/generated/jooq/io/github/windyzhu3/ontologylaw/shared/internal/persistence/jooq/`
- Create: `backend/src/generated/jooq/MANIFEST.sha256`
- Modify: `backend/pom.xml`

- [ ] 先写Testcontainers测试：从`database/schema-contract-52-plus-2/runtime/toolchain.lock.json`读取PostgreSQL 18 RepoDigest并拒绝未锁定tag；从空库运行20个迁移；创建`law_api_login LOGIN NOINHERIT`并只授Command/Query/Audit成员关系，创建`law_worker_login LOGIN NOINHERIT`并只授Worker成员关系。

- [ ] 测试`CapabilityRoleExecutor`每个事务显式`SET LOCAL ROLE`，事务结束后角色恢复；API登录不能长期继承权限并集，Worker不能写Lead/Task/Audit，浏览器无数据库凭据。

- [ ] 运行测试确认失败，然后实现最小role executor与测试数据源配置。

- [ ] 使用已迁移PostgreSQL按Owner Schema生成jOOQ，并统一写入`backend/src/generated/jooq/io/github/windyzhu3/ontologylaw/shared/internal/persistence/jooq/`下的Schema子包；领域包不得依赖该根包。提交生成快照和`MANIFEST.sha256`，`generate-jooq.sh --check`重生成到临时目录并逐字节比较。R1不得生成DAO或Active Record。

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

- [ ] 先写实库失败测试：同一slot键＋同payload返回原Receipt；同键异payload提交REJECTED Receipt并审计；成功事务同时存在Slot、Fact、Audit、Event、Owner Outbox、Receipt；Audit失败或Handler技术异常时全部回滚且不留Slot/Receipt。

- [ ] 测试授权在事务开始与提交前都加载准确Tenant/Principal/Appointment/组织Scope/authority path；Appointment撤销、Subject revision变化或存在DENY时拒绝。

- [ ] 实现静态Handler注册表，不用反射扫描自由命令名。Payload使用RFC 8785规范化JSON摘要；Scope摘要覆盖Tenant、命令类型和准确Subject。

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

- [ ] 冻结确定性分配：候选仅为同Tenant、ACTIVE Appointment、静态`SALES_CONTACT_OWNER`角色且授权实时有效者；按Appointment UUID排序后，用`SHA-256(tenantId || leadId)`前8字节无符号值取模。无候选时创建主管P0-04 Task，不伪造Assignment。需要人工模式由版本化`R1SourcePolicyRegistry`按sourceAccountCode静态选择，不建配置表。

- [ ] `captureLead`只接收明文输入到应用边界；服务端加密/HMAC并写不可变Lead。疑似重复创建`RESOLVE_LEAD_DUPLICATE`；缺联系方式创建`COMPLETE_LEAD_INGRESS`；人工模式创建`ASSIGN_LEAD`；其余路径原子创建LeadAssignment、更新current pointer并创建CONTACT_LEAD。

- [ ] P0-01用`DecisionRecord(LEAD_DUPLICATE_RESOLUTION)`完成Task；`LINK_EXISTING_PARTY`必须把当前Lead解析到候选Lead的同一活动Party并标记处置，`KEEP_SEPARATE`继续缺失/分配判断。不得删除或合并Lead行。

- [ ] P0-02用V850槽完成：只允许原始联系方式缺失且槽全空；服务器生成密文/HMAC/摘要；Lead CAS修订、Task DONE及后续分配/零候选责任同事务提交。

- [ ] P0-03创建唯一OPEN LeadAssignment、回填Lead current pointer并创建CONTACT_LEAD；Owner不符合Appointment/授权时拒绝且无部分写入。

- [ ] P0-04用`DecisionRecord(LEAD_ROUTING_DISPOSITION)`完成：`SCHEDULE_ROUTING_REVIEW`创建新同Owner Task并OPEN→WAITING＋WaitReceipt；`RETRY_ASSIGNMENT_NOW`重新运行当前候选政策；`REQUEST_SOURCE_INTAKE_STOP`只把当前Lead处置标记为已请求，不改写任何全局来源状态。

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
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/query/internal/persistence/JooqCurrentWorkCardQuery.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/ActionDraftService.java`
- Create: `backend/src/main/java/io/github/windyzhu3/ontologylaw/responsibility/internal/persistence/JooqActionDraftRepository.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardIT.java`
- Create: `backend/src/test/java/io/github/windyzhu3/ontologylaw/responsibility/ActionDraftIT.java`

- [ ] 先写失败测试：任一Appointment最多返回一张完整OPEN卡；排序固定为逾期、原始SLA截止、任务创建时间、UUID；另外只返回两条摘要和等待计数。WAITING/他人责任/未授权对象不能成为完整卡。

- [ ] 测试一Task最多一份Draft；保存使用revision CAS；action/schema必须等于Task静态注册；确认时只能回传服务器签发的digest且必须匹配；`GET /api/v1/workcards/current`按冻结字段返回草稿供刷新恢复；确认后不可编辑；确认本身不完成Task。

- [ ] Query每条SQL显式绑定tenantId并先解析Subject后做四轴授权；敏感字段仅按卡片Schema最小解密，不把jOOQ Record暴露给API。

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

- [ ] `CONNECTED_VALID`要求legalNeed并原子写ContactResult、唯一Opportunity锚点、Task DONE、Audit/Event/Outbox/Receipt。R1只产生`OpportunityOpened`边界，不创建无Handler的R2 Task，也不实现报价或实质商机推进。

- [ ] `NOT_CONNECTED`原子完成当前Task并创建新CONTACT_LEAD Task：先OPEN revision=0，再同事务CAS到WAITING revision=1并追加WaitReceipt；静态`CONTACT_RETRY_V1`计算resumeDueAt。到期由内部具名命令WAITING→OPEN，不能直接插入WAITING。

- [ ] `SUSPECT_INVALID`完成当前Task并创建主管`REVIEW_LEAD_VALIDITY` OPEN Task；复核用DecisionRecord完成，`CONFIRM_INVALID`结束，`REOPEN_CONTACT`创建新CONTACT_LEAD而不重开旧Task。

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

- [ ] Worker不持有Command/Query/Audit数据库能力。`DueTaskScheduler`只通过mTLS保护的internal端点请求API执行具名到期恢复命令；internal端点使用Service Principal并仍走CommandRuntime。

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

- [ ] 先按P0-01至P0-04与基础首联视觉写Vitest/Testing Library失败测试：一张展开卡、一个绿色主按钮、选项只改变主命令参数、最多两条摘要、等待计数、刷新恢复Draft、提交未知时按CommandId恢复Receipt。

- [ ] 实现响应式工作台，使用暖白/石墨/翡翠绿/浅薄荷tokens；普通用户文案不得显示Task/Event/Decision/revision/hash/WAITING等内部术语。Admin/Customer只保留受保护路由壳，不创建独立应用或R1页面。

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

- [ ] 失败路径至少覆盖：来源幂等；P0-01两种决定；P0-02一次补全和二次覆盖拒绝；P0-03越权Owner；P0-04三种处置；同Command异payload；过期revision；Appointment提交前撤销；Audit插入失败全回滚；跨租户读写拒绝；浏览器双击；网络响应丢失后Receipt恢复。

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
