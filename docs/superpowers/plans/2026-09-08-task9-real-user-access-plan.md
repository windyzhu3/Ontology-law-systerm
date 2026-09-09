# Task9 Real User Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将原 Task9 扩展为可受控开户、真实登录、取得本人责任卡并完成提交/恢复的生产用户入口。

**Architecture:** 使用用户已确认的自托管 Keycloak/OIDC 管理凭据与登录会话，业务系统继续以 Identity Fact、Appointment、直接 Grant 与当前 DENY 裁定权限。一个 SPA、一个业务 OpenAPI、一个 `api|worker` Jar；新增 Identity 管理与 self-context 的具名接口，复用现有四张身份业务表和审计/回执机制。

**Tech Stack:** 仓库锁定 Java 25、PostgreSQL 18、Spring Boot/jOOQ、React/TypeScript、Node 24.20.0、npm 11.9.0；新增 Keycloak、OIDC adapter 和浏览器测试依赖必须在 Task9.1 固定版本/镜像 digest，不能用浮动 latest。不顺带升级既有依赖。

**Spec:** [Task9 扩展设计](../specs/2026-09-08-task9-real-user-access-design.md)。[验收矩阵](../../acceptance/2026-09-08-task9-real-user-access-acceptance.md)是本计划每个交付单元的共同验收输入。

**Status:** APPROVED。用户于 2026-09-08 确认详细设计及计划，现从 Task9.1 合同后继开始实施；后续功能与实际用户/权限变更仍按各单元门禁，不将设计批准当成完成证据。

**Execution:** Task9.1/9.2a静态合同及Task9.2身份接入后端均已本地阶段验收、独立复审通过，见[代办合同证据](../../progress/2026-09-08-task9-delegated-contract-acceptance.md)与[运行时证据](../../progress/2026-09-09-task9-identity-runtime-acceptance.md)。Task9.3受控身份管理后端现已本地阶段验收：实现bc637ad、最终修复da57aff，完整基线与修复后244项受影响回归、实际CLI和独立复审通过，见[9.3记录](../../progress/2026-09-09-task9-identity-admin-acceptance.md)。保留用户确认的完整用户名精确候选0～1项、其他管理列表正常分页；未扩展功能／权限。当前Task9.4部分实现（非视觉逻辑及批准的登录组件），剩余选择／恢复视觉与生产装配未完成；9.5～9.6待实施，Task10/R1发布不晋级。

## Global Constraints

- 一个响应式业务 SPA、一份业务 OpenAPI、一个模块化单体 Jar，`APP_ROLE=api|worker` 互斥。
- 业务数据库保持 13 Schema、52 应用表＋2 技术表、当前 `52-plus-2-v1.2`；Keycloak 独立拥有其外部身份存储，拓扑修订须明示这一基础设施依赖。
- 不修改旧迁移字节，不新增密码表、会话表、动态 RBAC/策略/通用平台表；新能力仅走具名最小前向授权迁移，若需新应用表则停止另议。
- 仅受控 ADM-01～04 HUMAN 子集；不实现 ADM-05～07、SERVICE 管理、附件、通知中心、语音、AI、R2 业务。
- token 只在内存；非凭据恢复标记只限设计 §4.4 四字段、每标签页一条、24 小时，无 payload 或秘密。
- 原 R1 七种 Task、完成 Fact、事件、Worker 权限和业务锁序不变；Identity writer 必须接入具名排他协议，不直接修复/迁移 Task。
- 管理命令、可信身份解析、自身 context 和回执恢复的后继合同先冻结，再实现生产代码。
- 原 Task9.0 历史结果不能替代新增真实登录、管理路径及人工 UAT 证据；Task10 和 R1 发布状态不提前晋级。

## 交付分段与依赖

| 子任务 | 可独立审阅交付物 | 依赖 | 验收组 |
|---|---|---|---|
| Task9.0 | 已有七卡/草稿/接口前端，保留历史完成记录 | 已有 424f030 | 原 75 测试及联通证据，后续持续回归 |
| Task9.1 | 受控合同后继、精确接口/安全/拓扑清单 | 本文详细设计获确认 | T9-C01～04 的静态部分 |
| Task9.2a | 合法代办接入的最小合同前置 | 9.1、补充设计书面批准 | T9-D01 |
| Task9.2 | Keycloak 环境、可信 HUMAN 动态映射、self context 与 bootstrap | 9.1、9.2a | T9-L02～05、I01、I13、C04、D02～07 的后端部分 |
| Task9.3 | 四类 Identity 管理命令、查询、权限和事务 | 9.2 | T9-I02～12 的 HTTP/实库部分 |
| Task9.4 | SPA 登录、会话、任职与安全恢复接线 | 9.2；管理导航资格依赖 9.3 | T9-L01～12 |
| Task9.5 | ADM-01～04 页面、完整工作台状态、统一视觉 | 9.3、9.4 | T9-I 页面部分、T9-W02～12 |
| Task9.6 | 真实全链路、七类卡与真实使用者 UAT | 9.1～9.5 | 全部必需 ID |

9.3 与 9.4 只能在 9.2 的共享 DTO/接口冻结后并行；共享 Runtime/认证变更串行。每单元记录自己的 BASE，先 RED、再 GREEN、独立评审后单独提交。不能把“页面存在”作为 9.3 后端完成证明。

## Task 9.1: 受控合同后继与精确接口

**Files:**

- Create: `docs/adr/ADR-0014-task9-real-user-access.md`、`docs/contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md`。
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md`、`docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`、`R1-WORKBENCH-PRESENTATION-CONTRACT.md`、`R1-COMMAND-POLICY-EVENT-CONTRACT.md`、`database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`。
- Modify: `contracts/openapi/ontology-law-api.yaml`、`scripts/baseline/verify_baseline.py`、相关 `scripts/baseline/tests/`、`scripts/verify_topology.py`、`tests/test_topology.py`、`backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`。
- Regenerate: `apps/workbench/src/generated/api/schema.d.ts`；禁止手改生成文件。
- Mechanical compatibility only: `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/R1ApiDelegate.java`，仅跟随生成接口补入 `X-Appointment-Id` 参数以保持编译；不得在本步实现任职选择、认证逻辑或改业务 Handler，实际身份选择/校验仍由 Task9.2 完成。
- Create: `deploy/identity/identity-toolchain.lock.json`、`deploy/identity/README.md`，记录选定 Keycloak/adapter/浏览器版本、digest、CSP/Origin/TLS/secret 注入与受信配置；不记录实际秘密。

**接口提案：新增 21 项，当前尚未激活。** 所有路径均以 `/api/v1` 为前缀；`{id}` 为服务端生成的准确资源选择器，不在可见文案显示。所有对象拒绝未知字段；Tenant、调用者身份、授权路径和实际管理权限来自服务端。

| operationId | Method | Path | 类型 |
|---|---|---|---|
| getSessionContext | GET | /session/context | 仅本人 Principal 的具名读取 |
| listIdentityProviderUsers | GET | /admin/identity/provider-users | 本 realm 的受控 IdP 账号候选 |
| getIdentityAdminOptions | GET | /admin/identity/options | 当前 scope 内静态岗位/可授予权限/候选选项 |
| listIdentityPrincipals | GET | /admin/identity/principals | 分页只读 |
| createIdentityPrincipal | POST | /admin/identity/principals | 绑定 HUMAN 账号 |
| renameIdentityPrincipal | PATCH | /admin/identity/principals/{id}/display-name | CAS 改名 |
| suspendIdentityPrincipal | POST | /admin/identity/principals/{id}/suspend | CAS 挂起 |
| resumeIdentityPrincipal | POST | /admin/identity/principals/{id}/resume | CAS 恢复 SUSPENDED |
| disableIdentityPrincipal | POST | /admin/identity/principals/{id}/disable | CAS 永久禁用 |
| listOrganizationUnits | GET | /admin/identity/organizations | 分页只读 |
| createOrganizationUnit | POST | /admin/identity/organizations | 建立 scope 内组织 |
| renameOrganizationUnit | PATCH | /admin/identity/organizations/{id}/display-name | CAS 改名，不重挂父级 |
| closeOrganizationUnit | POST | /admin/identity/organizations/{id}/close | CAS 关闭 |
| listAppointments | GET | /admin/identity/appointments | 分页只读 |
| createAppointment | POST | /admin/identity/appointments | 建立任职 |
| suspendAppointment | POST | /admin/identity/appointments/{id}/suspend | CAS 挂起 |
| resumeAppointment | POST | /admin/identity/appointments/{id}/resume | CAS 恢复 SUSPENDED |
| endAppointment | POST | /admin/identity/appointments/{id}/end | CAS 结束 |
| listAuthorityGrants | GET | /admin/identity/authority-grants | 分页只读 |
| createAuthorityGrant | POST | /admin/identity/authority-grants | 准确静态直接授权 |
| revokeAuthorityGrant | POST | /admin/identity/authority-grants/{id}/revoke | CAS 单向撤销 |

保留原 16 项，批准后新 inventory 应为 37 项、32 public Bearer、5 internal mTLS。新增 14 个 mutation 对应静态命令，均使用 Idempotency-Key；创建不伪造不存在资源 ETag，更新/生命周期必须 Identity ETag，成功引用准确 Identity Fact。只读分页 limit 默认 20、上限 50、固定稳定排序与不透明 cursor；options 的组织/任职候选同样分页，不返回无界整租户目录。getSessionContext 仅本人的有效任职，最多 50，超限安全配置错误，不任意截断后自动选择。

Identity self context 拟定：`displayName`、`state`、`appointmentChoices[{id,label}]`、`selectedAppointmentId|null`、`actorScopeKey|null`、`canEnterWorkbench`、`canEnterIdentityAdmin`。state 只取 `NO_APPOINTMENT|APPOINTMENT_SELECTION_REQUIRED|READY`；未映射和非 ACTIVE Principal 在该响应之前安全拒绝；READY 但 canEnterWorkbench=false 不能伪装为业务零态。只有已选 Actor 才发 actorScopeKey。返回值不含 token、权限矩阵或原始 Tenant/Principal ID。

- [x] 为原 inventory/排除身份管理条款、遗漏 terminal NOT_FOUND、self-query 冒用业务 Actor、管理请求可选 Tenant、无任职伪 Actor、权限扩大写 mutation tests；观察 RED。
- [x] 创建 ADR-0014，逐条具名替代排除条款、HUMAN 逐人注册、Token rotation 与恢复持久线索边界；区分外部 Keycloak 拓扑和业务表数。
- [x] 冻结全部 DTO 字段/required/长度/条件规则、上述 operation、命令码、权限 allowlist、Identity ETag、Subject/Receipt/拒绝/重试/delta、管理披露及原 Actor 回执授权，不能使用任意表名或通用 JSON command。
- [x] 冻结 offline bootstrap 的准确清单、初始 delta、SYSTEM 例外、幂等与关闭条件；核查数据库能力，必要时另附最小前向 GRANT 迁移，不修改原迁移。
- [x] 登记会话密码 policy、TLS、Origin、CSP、introspection 2 秒超时、5/30/480 分钟参数、恢复标记字段与 24 小时上限；验证固定供应链版本，不部署浮动镜像。
- [x] 正常生成类型并执行合同/拓扑/OpenAPI 检查，记录旧业务 DTO/事件保持的精确对比；通过后才移除前端 `PublicReceipt` 临时兼容。
- [x] 独立审阅完整合同范围，提交 `feat(contract): activate Task9 real user access`。

静态验收用例 `test_task9_inventory` 必须读取真实 OpenAPI 的 paths，逐一枚举 HTTP method operation，并按 operation 的 security 分类，断言实际 37/32/5、21 个新增 operationId 唯一且与上表完全一致，再读取 `TerminalRejectionCode` 断言包含 NOT_FOUND。另分别删除一个 operation、改变一次 security、加入任意 Tenant 字段、移除 NOT_FOUND 运行拒绝变异；不得把期望计数写成输入常量冒充实际解析结果。

## Task 9.2a: 合法代办接入合同前置（先于恢复 9.2）

**Spec:** 完整读取[已批准补充设计](../specs/2026-09-08-task9-delegated-context-amendment-design.md)；其中 §1～7 是本任务与下游实现的准确约束。此任务只激活静态合同及可执行门禁，不实现登录/委托解析。

**Files:**

- Create: `docs/adr/ADR-0015-task9-delegated-context.md`。
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md`、`docs/contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md`、`R1-HTTP-ERROR-PRECONDITION-MATRIX.md`、`R1-WORKBENCH-PRESENTATION-CONTRACT.md`、`R1-COMMAND-POLICY-EVENT-CONTRACT.md`、`contracts/openapi/ontology-law-api.yaml`。
- Modify gates and tests: `scripts/baseline/task9_identity_contract.py`、`scripts/baseline/verify_baseline.py`、`scripts/baseline/tests/test_task9_identity_contract.py`、精确引用当前后继的其他 `scripts/baseline/tests/`、`scripts/verify_topology.py`、`tests/test_topology.py`、`backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`。仅更新实际依赖的活跃版本断言，保留历史测试语义。
- Regenerate: `apps/workbench/src/generated/api/schema.d.ts`；Java 生成输出由 Maven 生成器管理。仅在生成签名需要时机械修改 `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/R1ApiDelegate.java` 参数，不增加认证或业务行为。
- Root owns: 本计划、设计批准记录、进度与验收记录。已有未提交 Task9.2 运行时草稿不在本任务范围，不修改、提交或删除。

**Consumes:** ADR-0014 生效合同、原九公共请求 DTO 及其传递依赖、原 16 method/path/operationId/security、冻结业务事件/物理字节。**Produces:** MVP-2026-09-08.3 / Identity V1.1 / HTTP V1.5 / Workbench V1.3 / OpenAPI 1.4.0，Command V1.3 与物理版本不变；37/32/5不变。下游依赖准确 header `X-On-Behalf-Appointment-Id` 及两个 required context 字段，不能提前假设运行时已接受它们。

- [x] **Step 1 — 写真实文档与拒绝变异 RED。** 在现有 `Task9IdentityContractTest` 中增加以下实际结构断言，并扩展对应变异。测试必须先因缺少 header/字段/版本而失败，而非 import/环境错误。

```python
def test_delegated_context_successor_shape(self):
    self.assertEqual('1.4.0', self.api['info']['version'])
    schemas = self.api['components']['schemas']
    context = schemas['SessionContextV1']
    self.assertEqual(set(context['properties']), set(context['required']))
    self.assertIn('delegatedAppointmentChoices', context['required'])
    self.assertIn('selectedOnBehalfAppointmentId', context['required'])
    self.assertEqual(50, context['properties']['delegatedAppointmentChoices']['maxItems'])
    self.assertEqual([], self.validator().validate_document(self.api))
```

覆盖：删除 header、required/nullable 改错、数组上限扩大、候选 item 放入 Principal/Grant/权限字段、管理 operation 接受 DELEGATED、internal operation 接入 selector、missing paired-header 规则或 SELF max101/恢复时机约束被删、malformed transport 不抛未处理异常。变异必须实际调用生产 validator，而不只是重复测试 helper；验证原业务 shape/事件/权限集合相等。

- [x] **Step 2 — 运行定向 RED 并留真实退出码。** 锁定 Linux Python 容器中执行 `python -m unittest scripts.baseline.tests.test_task9_identity_contract -v`。环境/路径错误先处理，不算功能 RED。
- [x] **Step 3 — 最小激活。** 新 ADR 精确列出替代项；同步闭合 HTTP/Identity/Workbench 规则与实际 baseline gate，保留业务 Command 注册。OpenAPI 新字段使用下面结构；state/选择互相匹配等不可由 JSON Schema 跨数组比较的约束，必须在合同与 runtime 验收中显式保留，不能声称生成器自动保证。

```yaml
delegatedAppointmentChoices:
  type: array
  maxItems: 50
  items:
    $ref: '#/components/schemas/IdentityChoiceV1'
selectedOnBehalfAppointmentId:
  type: [string, 'null']
  format: uuid
```

将两项加入 `SessionContextV1.required`；保留原七字段/三 state。未选本人时数组空/代办选择 null；代办选中时管理入口 false。header 为 optional/UUID/单值，具体哪些 operation 接受或拒绝由补充设计 §2 精确声明；不修改业务 body 来携带认证选择器。

- [x] **Step 4 — GREEN、生成与覆盖回归。** 锁定 Node 24.20.0 / npm 11.9.0 执行 `npm run openapi:generate`、`npm run openapi:check`、`npm run typecheck`、`npm test`、`npm run build`；JDK 25 用 `./mvnw.cmd -f backend/pom.xml -Dtest=OpenApiContractTest test`。定向 Python 通过后，稳定源码运行全量 `python -m unittest discover -s scripts/baseline/tests -v` 与 `python -m unittest tests.test_topology -v`，实际树 baseline/topology CLI 也必须通过。各命令独立记录退出码；全量基线只在稳定版本运行一次，修复后按实际覆盖重跑。

已有 Windows 路径/符号链接测试限制：Python suite 在锁定 Linux Python 镜像执行，挂载 worktree readonly，不向 synthetic Git repo 单元测试注入 GIT_DIR/GIT_COMMON_DIR。实际树 CLI 可用本计划 SDD 目录 `verify-baseline-locked.ps1` 的只读 Git mount。镜像固定 `python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`，PyYAML 依既有 requirements 安装，不修改依赖。

- [x] **Step 5 — 自审、准确提交与独立评审。** 只暂存本任务命名文件，`git diff --cached --check` 后提交 `feat(contract): preserve delegated identity access`。报告 RED/GREEN 命令、case/exit、源版本、旧 DTO/事件/物理相等证据及未解决项。控制代理以完整 task BASE→HEAD 生成 review package，独立审阅 spec compliance 和 quality；重要问题交回同 implementer 修复。此门通过才恢复 9.2，不将 T9-D02～08 标成 runtime PASS。

## Task 9.2: Keycloak、动态映射、context 与引导

**Files:**

- Create: `deploy/identity/compose.yaml`、`deploy/identity/realm-template.json`；模板不含用户密码或 client secret。
- Create Java: `identity/HumanIdentityReader.java`、`identity/internal/persistence/JooqHumanIdentityReader.java`、`identity/IdentityBootstrapService.java`、`api/security/HumanCredentialVerifier.java`、`api/SessionContextController.java`、`execution/IdentitySelfReadRuntime.java`。
- Modify Java: `api/security/ActorContextResolver.java`、`api/security/R1SecurityConfiguration.java`、`api/R1ApiDeployment.java`、`identity/ExternalSubjectProtection.java`、`audit/AuditAppender.java` 及其 Owner 内部实现。
- Tests Java: `api/HumanLoginMappingIT.java`、`api/SessionContextHttpIT.java`、`identity/IdentityBootstrapIT.java`、原 `api/R1ReceiptIdentityHttpIT.java` 与角色隔离测试。

版本贯通补充：ADR-0015 已激活统一 `MVP-2026-09-08.3`，本单元将 `worker/R1WorkerTenantBindings.java` 的旧 `.1` 精确校验同步为 `.3`，并覆盖拒绝旧/错版本测试。仅版本与相关夹具对齐；Worker 权限、Bean、Actor 绑定、内部接口、调度/业务行为和物理版本均保持，不构成发布晋级。

Java 路径统一以 `backend/src/main/java/io/github/windyzhu3/ontologylaw/` 为前缀，测试以 `backend/src/test/java/io/github/windyzhu3/ontologylaw/` 为前缀；SQL 只在 Owner 的 internal.persistence 中。

**Consumes:** 受信 issuer/audience/provider→Tenant 配置、IdP 活动性复核、原 Subject HMAC、现有身份事实。

**Produces:** `VerifiedHumanIdentity`（服务端 tenantId/principalId/provider，不含任职权限）；`HumanIdentityReader` 的唯一映射与本人任职读取；经确认的 `SessionContext` DTO；已明确选择任职才产生现有非空 `AuthorizationService.Actor`。不得给原 Actor 构造器塞 null Appointment。

**9.2a 后继输入：** 完整执行已批准代办补充设计 §2～5 与 T9-D02～07 后端部分：当前本人任职及既有一跳关系候选；双 header 显式选择；当前数据库资格与最终业务授权分开；SELF max101具名披露与原 Actor 回执恢复。用 `SessionContextHttpIT`、`R1ReceiptIdentityHttpIT` 承接；新增定向测试如需单独文件可用 `api/DelegatedSessionContextHttpIT.java`，不增加业务模块。不得只测试旧 Registration 构造器，必须证明动态 HUMAN 路径实际构造和恢复代办 Actor。现有暂停草稿先核对最后失败日志，再按原 TDD 继续，不假设已通过。

- [x] 编写 L02～L05/I01/I13 的真实 IdP＋DB 失败用例；先证明当前逐人 registration 无法接入新建用户。
- [x] 搭建锁定身份环境，以受限客户端验证 JWT＋在线活动性；错 issuer、audience、过期、撤销、断网都失败关闭，SERVICE 路径不放宽。
- [x] 实现 trusted Tenant 内 provider-subject HMAC 动态 HUMAN 映射、本人 context 的先审计后披露、多任职选择与每次请求归属复验。
- [x] 实现一次性 bootstrap，先 dry-run 展示无秘密的创建集合，只有明确离线执行才写；正向、同 manifest 重放、部分存在和冲突各自有准确断言。
- [x] 运行定向 IT、ArchitectureTest 与 Task8 安全/角色回归，检查没有 Worker 用户管理 Bean/权限，独立评审后提交。

阶段结论：实现43fd69c、修复581c61e；113unit＋639IT完整回归及修复后52unit＋98IT受影响安全回归退出0；四项Important复审关闭。只覆盖上述ID的9.2后端部分，管理API创建目标身份、浏览器与人工证据仍在下游。非阻断建议与证据限制见阶段验收记录。

## Task 9.3: Identity 管理后端

**Files:**

- Create Java: `identity/IdentityCommands.java`、`identity/IdentityAdminReader.java`、`identity/IdentityProviderDirectory.java`、`identity/internal/persistence/JooqIdentityRepository.java`、`execution/IdentityCommandRuntime.java`、`execution/IdentityAdminReadRuntime.java`、`api/IdentityAdminController.java`、`responsibility/IdentityDependencyReader.java` 及其 Owner 内部实现。
- Modify Java: `execution/CommandEnvelope.java`、命令 store/receipt policy/metadata 的现有静态注册、`audit/AuditAppender.java`、API 装配；不复制普通 R1 command engine。
- Tests Java: `identity/PrincipalCommandsIT.java`、`identity/OrganizationCommandsIT.java`、`identity/AppointmentCommandsIT.java`、`identity/AuthorityGrantCommandsIT.java`、`identity/IdentityMutationConcurrencyIT.java`、`api/IdentityAdminHttpIT.java`。

**Consumes:** 9.1 精确 14 mutation 合同、9.2 Actor；**Produces:** `IdentityCommands.handlers()` 静态集合、受 scope 限制的查询、准确 Identity Fact Receipt、原 Actor 当前权限下的恢复。

9.2a 不给任何管理 operation 开放代办：本单元补齐 T9-D05 的20个管理接口拒绝，不能在 Controller 抹去 on-behalf 后回落到本人权限。

**用户确认的最小搜索澄清（2026-09-09）：** 在线IdP账号候选只按完整用户名精确查询，返回0或1个当前有效HUMAN账号，`nextCursor=null`；不提供姓名／邮箱／模糊搜索。保留既有query/DTO形状、limit范围和错误结构，所供cursor因本查询从不签发后续页而按无效cursor拒绝；本地Principal／组织／任职／Grant及options分页不变。先同步设计／Identity合同／OpenAPI说明，再实现实测；不增加目录缓存、表、Provider扩展或IdP权限，不改变合同版本／operation inventory／原业务形状。验收须覆盖不存在／停用／SERVICE、不同用户名共享姓名／邮箱、查询文本不能触发通配或特殊lookup、0/1结果和无续页。

- [x] I02～I12实库／HTTP功能测试通过：创建、CAS、状态机、冻结字段、跨scope、自授权、开放责任依赖、最后管理员、回执重放与注入失败。过程偏差如实记录：初始仅入口等部分分支留有实施前RED，部分领域断言晚于初始框架，无法证明全部分支逐项预实现RED；本轮评审修复有实际RED18／19和GREEN20／22，不能补造历史。
- [x] 通过 Owner 窄口实现四类事实与受信 IdP 账号目录读取；复用9.2已有IdentityProviderDirectory，不另建子系统。落实 Principal/IdP 候选与新任职用户候选的根 scope 边界，不能从局部任职扩大到 Tenant 级用户权限。只有密码/账号凭据在 Keycloak，不在本系统创建密码 handler。
- [x] 实现具名身份排他路径、依赖读取与先审计后披露，不取得反序业务锁，不新增业务事件/投影路由。
- [x] 扩展回执元数据与授权 resolver 以处理 Identity Subject；直接查回执也必须当前管理授权，普通业务原 Actor 规则不退化。
- [x] 通过精确 delta、双连接锁序、跨 Tenant、撤权与技术回滚测试；最终提交da57aff受影响244项通过，独立复审4/4重要问题及共享邮箱证据缺口关闭，实际CLI通过。两项Minor及历史RED证据限制见阶段记录；不替代9.6最终同构建总验收。

## Task 9.4: SPA 登录、会话与恢复

**恢复评审修复：** `5a8a9fe`补齐已确认回执后的current读取在标签页隐藏中止后的手动重读状态，新增真实RED回归，最终相关42项及typecheck通过。同一评审者复审见阶段记录。下述196全量／build/openapi属于`b7dc793`，未重复完整套件；整体9.4未验收。

**恢复视觉门更新（2026-09-09）：** 用户选择本轮第2稿[RECOVERY-01](../../design/session-access/README.md)，按同一内容区实施未知／核对中、结果已确认但刷新失败、会话失效／访问受限、服务／恢复存储不可用状态。原结果未确认且正文丢失时仅查回执，禁止新写和重放；放弃本地线索必须再次确认，不撤销原操作。复用既有会话和回执判定，不修改业务命令或扩大到9.5、R2。BASE `c276900`；本轮起点177项前端测试通过。完成组件后检查生产装配所需的既定配置与接点，不以预览夹具替代真实入口或整链验收。

**恢复实现更新：** `b7dc793`完成RECOVERY组件和真实hook/transport接点；最终196项／17文件、typecheck/build/openapi均通过，按图及四尺寸／键盘抽检通过。已有READY Actor快照仅为回执查询复用，工作台资格仍独立检查；过期／损坏线索须再次确认后方可清理，存储失败继续暂停。独立评审结论见阶段记录。下一步为生产main/config装配与完整9.4集成评审；固定非秘密部署配置不得填入样例值或由URL选择身份。不启动9.5，不将本次组件验收等同真实登录领卡。

**CHOICE提示修复：** `95b9673`仅清除草稿重新选择后的旧成功提示；3项先RED回归，最终相关44项及typecheck通过。以下174项全量证据属于`d95f54d`，不累加或冒称修复后全量。完整9.4状态不变，增量复审详见阶段记录。

**任职增量更新：** 用户选择新一轮第1稿[CHOICE-01](../../design/session-access/README.md)，已实现为`d95f54d`：本人任职／合法代办组件及现有会话调用接点，先本人后显式代办，未决线索保留及同页风险确认按原合同；内部prepareAppointment只区分中间确立本人时机，无新wire／授权。最终174项前端测试、typecheck/build/openapi和浏览器视觉／交互抽检通过，增量独立评审另见阶段记录。完整恢复／异常页面及production main/config装配仍为后续门，不启动9.5；下列整体9.4复选框不提前勾选。

**登录增量修复：** 实现`73aeef4`的153项全量证据保留；独立评审指出的首次commit初始化准入窗口已由`4ebc485`修复，最终相关26项和typecheck通过。仅provider内存就绪状态与入口门禁，无新画面或业务合同。整体9.4未验收。

**视觉门更新（2026-09-09）：** 用户明确选择登录第3稿，已保存[LOGIN-01原图与确认记录](../../design/session-access/README.md)：左侧品牌区、右侧登录操作区、Logo及律所名称占位。该组件及真实会话调用接点已实施为`73aeef4`，最新153项前端测试、typecheck/build/openapi通过，浏览器四尺寸／键盘抽检通过。本人任职／合法代办选择、异常及恢复新画面不因本次选择自动批准；production main/config仍未接线。下段保留非视觉检查点时的执行记录，下列整体实施验收项继续保持未勾选；完整9.4与真实登录验收不提前晋级。

**当前执行（2026-09-09）：** BASE`c4aed14`，非视觉会话／恢复检查点`534e2ec`，最终锁定工具链145项前端测试、typecheck/build、生成漂移及实际基线／拓扑通过。新增登录／本人及代办选择画面按设计§7先确认再实施；production main接线与完整9.4独立评审仍未执行，原75项早期基线的npm版本纠正记录保留。详见[Task9.4阶段记录](../../progress/2026-09-09-task9-session-acceptance.md)。下列整项未完成，不能因非视觉测试通过勾选；9.5～9.6不提前实施。

**Files:**

- Create: `apps/workbench/src/features/session/SessionProvider.tsx`、`LoginPage.tsx`、`AppointmentChooser.tsx`、`sessionController.ts`、`recoveryMarker.ts` 及同名测试。
- Modify: `apps/workbench/src/main.tsx`、`App.tsx`、`lib/api.ts`、`features/workcard/useCurrentCard.ts`、`recovery.test.tsx`、`package.json` 与根锁文件。

**Interfaces:** session adapter 提供稳定 `identityEpoch`、`actorScopeKey`、`getValidAccessToken(): Promise<string>`、`login()`、`logout()`、`selectAppointment(id)`；续期不改变 identityEpoch，变换 Actor 必须改变。原 `OriginalWrite` 只存业务请求和 precondition，不保存 Bearer；transport 每次取有效凭据。

9.2a 增补：adapter 增加 `selectOnBehalfAppointment(id: string | null)`；null 明确本人办理，非 null 仅服务端当前任职候选，transport 同时携带本人 header。`recoveryMarker.ts` 与测试落实 T9-D08：重登建立初始 context 不等于确认改用其他身份，选择期间保留有效未决标记且禁新写，不新增持久化字段；显式换身份先确认放弃未决线索风险。新增任职/代办选择视觉仍先确认，不能以此增管理导航。

- [ ] 写 L01/L06～L12 DOM/transport RED，包括先输入 dirty 文本再 token rotation、POST 结果未知再到期重登、跨 scope 迟到响应、标记存储失败不写入。
- [ ] 实现 PKCE 回跳、真实本人 context、多任职选择、退出/跨标签信号和安全错误；不以 Mock session 进入 main。
- [ ] 实现同 scope 单飞续期与稳定 epoch，普通 401/权限失效按设计清屏；现有 Task/Draft/Workbench 三类 ETag 不混用。
- [ ] 实现四字段恢复标记的严格 schema/大小/时限及同 scope 查询；未决时全 SPA 阻止新写请求覆盖标记，只有准确终态或合同明确未提交的响应才按键策略清除/替换。丢失正文后禁用重放而非重建，scope key 跨同 Actor 重登/应用重启保持稳定。
- [ ] 通过原 75 项加新增前端测试、typecheck/build，独立评审；登录及任职选择视觉须先经用户确认，再将 UI 定稿提交。

关键新增用例必须观察公共行为：在首联页面向“结果说明”输入“尚未保存的输入”，通过受控 OIDC 测试 adapter 实际完成一次同身份凭据更新，不重新挂载 App；等待续期完成后断言文本仍在、未保存状态仍在、“记录联系结果”仍禁用且没有 POST。随后保存候选并确认一次，断言 HTTP 携带新 Bearer、原正确任职且只产生一次业务写入。只断言 identityEpoch/private ref 或省略触发续期事件不能通过 T9-L07。

## Task 9.5: 管理页面与完整状态提示

**Files:**

- Create: `apps/workbench/src/features/identity/IdentityAdminLayout.tsx`、`PrincipalPage.tsx`、`OrganizationPage.tsx`、`AppointmentPage.tsx`、`AuthorityGrantPage.tsx`、`identityApi.ts` 及相应测试。
- Create: `apps/workbench/src/features/workcard/WorkbenchStatus.tsx`、`WorkbenchStatus.test.tsx`。
- Modify: `App.tsx`、`features/workcard/CurrentCard.tsx`、`WaitingSummary.tsx`、`useCurrentCard.ts`、`styles/workbench.css`；复用 tokens，不替换视觉体系。
- Update evidence: `design-qa.md`、`docs/design/identity-admin-mvp/README.md`（只增加实际验收索引，冻结图片不篡改）。

- [ ] 将设计 §6 全部状态写成表驱动 RED：未知/零/等待、无资格、提交与刷新分离、stale、恢复、轮询耗尽、会话状态；每项断言安全文案与允许/禁止动作。
- [ ] 按冻结 ADM-01～04 构建仅相应模式的列表、表单、前置确认和回执反馈；使用真实 API，不用客户端状态冒充成功；不搭建其余三张管理页。
- [ ] 分离 session/read/draft/command 状态轴，保留唯一业务主按钮；提交成功后刷新失败只可重读，不提示重复提交。
- [ ] 按 360/768/1440 在真实浏览器检查状态、焦点、弹层、权限屏与 composer，无遮挡或横向溢出；登录/任职页采用已获确认的设计。
- [ ] 运行管理与工作台 DOM 回归、typecheck/build，保存冻结图与实际页面联合比较证据，独立评审后提交。

## Task 9.6: 真实用户全链路与总验收

**Files:**

- Create: `e2e/compose.yaml`、`e2e/fixtures/identity-setup.ts`、`e2e/fixtures/r1-business-setup.ts`、`e2e/tests/task9-login-session.spec.ts`、`task9-identity-admin.spec.ts`、`task9-workbench-states.spec.ts`、`task9-seven-workcards.spec.ts`、`playwright.config.ts`。
- Modify: 根 `package.json` / `package-lock.json`，增加精确 `test:e2e:task9` 脚本及锁定浏览器依赖。
- Create: `docs/acceptance/task9-real-user-uat-template.md`，不把空模板当实际 UAT 结果。
- Update: 扩展验收矩阵、本轮进度报告、设计 QA；只在证据合格后更新对应交付状态。

- [ ] 为全部 T9-C/L/I/W/D 自动项建立命名测试并先保存缺能力 RED，不使用 skip/空测试绕过；D03/D08 包含实际浏览器证据。
- [ ] 通过真实 IdP＋管理接口创建目标身份、组织、任职、授权；从真实 capture/分配生成业务责任，逐类七卡操作并断言数据库 Fact/Receipt/Task/Wait/Event/Audit。
- [ ] 实测 credential rotation、退出/撤销、HTTP/CAS/网络故障、恢复标记、跨 Tenant、四管理状态机和实际 Worker 等待恢复。
- [ ] 承接9.2非阻断证据建议：用同一原回执串联重登、重建服务与更换双方合法授权证据；损坏引导记录场景按候选准确到期时间等待并显式断言，不依赖从夹具开始的固定睡眠。不得把现有分离测试或未严格断言的到期前提当作本阶段完整E2E证据。
- [ ] 承接9.3非阻断评审建议：实际部署前区分在线候选有效期／密钥轮换与离线bootstrap完整清单恢复的密钥依赖；整分支评审检查Identity仓库及测试的多语句单行可读性，不借此改变密钥策略或扩展功能。初始部分测试缺少实施前RED历史证据的限制保留，不能用后续GREEN补造历史。
- [ ] 指定真实使用者执行 U01～U03；凭据由使用者自行输入，只留脱敏结果，人工未执行不能标通过。
- [ ] 运行最终构建的前端、后端、合同、真实 E2E 与权限回归，记录工具链、镜像 digest、实际 case/exit，独立评审整个 Task9 扩展范围。
- [ ] 全部必需 ID 通过后才宣布扩展 Task9 完成；交给原 Task10 复用同一身份/E2E fixture 继续全 BranchID/CI/容量，不开第二套身份或测试系统。

## 验证命令与停点

本节命令在相应代码/脚本已经按上述任务建立后运行；本次文档修改不运行不存在的测试，也不安装/部署身份服务。

```text
python -m unittest discover -s scripts/baseline/tests -v
python -m unittest tests.test_topology -v
python scripts/verify_topology.py
npm run openapi:generate
npm run openapi:check
npm test
npm run typecheck
npm run build
./mvnw -f backend/pom.xml verify -Pit
npm run test:e2e:task9
git diff --check
```

使用仓库锁定工具链；Windows 使用 `mvnw.cmd` 或 Git Bash wrapper，不误用系统全局旧 Node/npm。每一环记录实际退出码，不把最后一个命令成功覆盖前序失败。

Task9.3后端已完成；当前执行Task9.4，非视觉会话／恢复逻辑与新页面视觉确认分别跟踪，生产登录入口尚未接线。本轮没有管理页面、实际人员开户或生产授权变更。本文不是任何真实账号／权限写入的执行凭据；Task9.4～9.6、新增画面视觉确认及人工UAT仍有各自门禁。
