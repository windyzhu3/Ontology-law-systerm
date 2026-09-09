# Task9 Real User Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将原 Task9 扩展为可受控开户、真实登录、取得本人责任卡并完成提交/恢复的生产用户入口。

**Architecture:** 使用用户已确认的自托管 Keycloak/OIDC 管理凭据与登录会话，业务系统继续以 Identity Fact、Appointment、直接 Grant 与当前 DENY 裁定权限。一个 SPA、一个业务 OpenAPI、一个 `api|worker` Jar；新增 Identity 管理与 self-context 的具名接口，复用现有四张身份业务表和审计/回执机制。

**Tech Stack:** 仓库锁定 Java 25、PostgreSQL 18、Spring Boot/jOOQ、React/TypeScript、Node 24.20.0、npm 11.9.0；新增 Keycloak、OIDC adapter 和浏览器测试依赖必须在 Task9.1 固定版本/镜像 digest，不能用浮动 latest。不顺带升级既有依赖。

**Spec:** [Task9 扩展设计](../specs/2026-09-08-task9-real-user-access-design.md)。[验收矩阵](../../acceptance/2026-09-08-task9-real-user-access-acceptance.md)是本计划每个交付单元的共同验收输入。

**Status:** APPROVED。用户于 2026-09-08 确认详细设计及计划；当前交付位置见下节最新进度。后续功能与实际用户/权限变更仍按各单元门禁，不将设计批准当成完成证据。

**Execution:** Task9.1/9.2a合同、Task9.2身份接入后端、Task9.3受控身份管理后端、Task9.4会话源码及Task9.5a/b/c/d均已阶段验收；9.5前端源码阶段关闭，9.6真实整链与人工UAT尚未完成。见[运行时证据](../../progress/2026-09-09-task9-identity-runtime-acceptance.md)、[9.3记录](../../progress/2026-09-09-task9-identity-admin-acceptance.md)与[当前前端进度](../../progress/2026-09-09-task9-identity-frontend-integration.md)。保留完整用户名精确候选0～1项、其他管理列表正常分页；未扩展功能／权限，Task10/R1发布不晋级。

## Global Constraints

**最新进度（2026-09-09）：** Task9.4源码阶段及本地真实登录先行检查已完成；真实登录/SELF/任职确认/刷新SSO/退出/未映射拒绝已实测。9.6a原bootstrap集合核验修正也已完成：源码e925380、测试补强9a7ea56，99项受影响回归及补强后35项定向回归、原本地清单零变化核验、独立复审通过，见[核验修正记录](../../progress/2026-09-09-task9-bootstrap-original-set-verification.md)。Task9.5a非视觉API适配、9.5b四页读取／受保护入口、9.5c十四写入和9.5d工作台状态全部通过源码阶段验收。9.5c交付`4a6ee77`；9.5d实现`366468b`／修复`da9d289`，首版443项完整回归、修复后93项受影响回归与实际浏览器复验，独立spec／quality复审关闭唯一I1。9.5前端源码阶段现已关闭，完整Task9.6／Task9仍未完成；用户现已批准仅在原本地测试环境更新API／SPA、启用Worker并建立专用合成测试账号及最小资格，不推送仓库、不操作生产或真实人员资料。

**本地整链执行更新：** 9.6b已完成独立复审、真实新旧制品回退／恢复、登录与四管理入口及原闭包核验。最终应用制品来自`04bd695`；四个专用Keycloak账号已创建，临时管理权限／容器已移除并核验，原目录权限与账号／公钥保持不变。9.6c Worker装配已完成源码复审与本地三项固定授权、三循环就绪及停启复验；HUMAN业务建档、七卡整链及人工UAT尚未完成，见[本地整链进度](../../progress/2026-09-09-task9-local-chain-acceptance.md)。

**2026-09-10联通前置发现：** 9.6e浏览器测试实施中核实管理成功响应Location偏离冻结ReceiptLocation：前端正确要求原命令回执地址，后端返回资源地址。新增9.6f最小修复单元；9.6e测试代码先完成离线门与独立评审，真实建档写入必须等9.6f修复、评审及同环境制品更新后执行。不是新增产品需求或合同变更。U01～U03由用户本人执行，仍未执行。

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

**当前状态（2026-09-09）：** Task9.4源码实施阶段验收通过。生产入口`774a301`及SELF正文超时窄修复`958e190`完成，最终222项、typecheck/build、完整独立评审和同席复审通过，未改OpenAPI的既有检查及真实组件浏览器抽检通过。下列复选框只关闭本任务源码实施，不将L组／D08混合项或9.6整链提前验收。用户明确没有实际部署配置：缺配置时关闭登录。以下增量段落为历史提交记录。

**生产装配执行（2026-09-09）：** 用户明确继续生产入口装配，BASE `9dbe530`，起点197项前端测试通过。将已确认的LOGIN／CHOICE／RECOVERY及原工作台接入同一稳定会话生命周期；仅使用构建时固定的非秘密`VITE_OIDC_ISSUER`、`VITE_OIDC_CLIENT_ID`、`VITE_OIDC_AUDIENCE`、`VITE_APP_ORIGIN`，准确回跳路径由固定Origin派生并复用原校验，缺项失败关闭，无示例默认值、URL选身份或测试认证回退。按设计§7在原顶栏放紧凑身份切换／退出及会话提示，不另造导航。补`sessionConfiguration.ts`、`SessionApplication.tsx`及相应测试；窄改main/App/provider接点，部署说明只列配置项，不写实际秘密。重点验证回跳、显式身份确认、初次未决恢复、同身份续期不重挂工作台／不丢原请求、失效清屏及跨标签退出。完整9.4与真实9.6验收仍按实际证据裁定，不扩展到9.5。

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

- [x] 写 L01/L06～L12 DOM/transport RED，包括先输入 dirty 文本再 token rotation、POST 结果未知再到期重登、跨 scope 迟到响应、标记存储失败不写入。
- [x] 实现 PKCE 回跳、真实本人 context、多任职选择、退出/跨标签信号和安全错误；不以 Mock session 进入 main。
- [x] 实现同 scope 单飞续期与稳定 epoch，普通 401/权限失效按设计清屏；现有 Task/Draft/Workbench 三类 ETag 不混用。
- [x] 实现四字段恢复标记的严格 schema/大小/时限及同 scope 查询；未决时全 SPA 阻止新写请求覆盖标记，只有准确终态或合同明确未提交的响应才按键策略清除/替换。丢失正文后禁用重放而非重建，scope key 跨同 Actor 重登/应用重启保持稳定。
- [x] 通过原 75 项加新增前端测试、typecheck/build，独立评审；登录及任职选择视觉须先经用户确认，再将 UI 定稿提交。

证据范围：23类命令注册表／回执验证与共享写入门已具备，当前SPA实际装配的7Task＋1Draft写入已验证；CAPTURE_LEAD和14管理写入不是本轮页面交付。服务端重启scope稳定、真实IdP轮换／SSO／撤销、最终管理写入全路径及人工UAT按9.6实际同构建复验。详见[阶段记录](../../progress/2026-09-09-task9-session-acceptance.md)。

关键新增用例必须观察公共行为：在首联页面向“结果说明”输入“尚未保存的输入”，通过受控 OIDC 测试 adapter 实际完成一次同身份凭据更新，不重新挂载 App；等待续期完成后断言文本仍在、未保存状态仍在、“记录联系结果”仍禁用且没有 POST。随后保存候选并确认一次，断言 HTTP 携带新 Bearer、原正确任职且只产生一次业务写入。只断言 identityEpoch/private ref 或省略触发续期事件不能通过 T9-L07。

## Task 9.5: 管理页面与完整状态提示

**局部视觉确认（2026-09-09）：** 用户审阅ADM-01～04修订图后明确确认[内容补充](../../design/identity-admin-mvp/revisions/2026-09-09-contract-alignment/README.md)。实施按“原冻结布局／样式＋已确认字段／控件／分页／层级与操作文案修订”执行，不采用此前作废的替代布局，不把图片编辑的细微间距偏移作为新规范。范围仍为四管理页和既定状态提示；此确认不等于创建表单、恢复流程或真实联通验收完成。未覆盖的实质性视觉选择继续先确认。

**执行分段（2026-09-09）：** 9.6a前置已关闭，开始执行已批准9.5，不扩展产品设计。先以9.5a交付六类管理读取、十四条管理写入与9.4共享会话／恢复的非视觉接线，再交付冻结ADM-01～04页面及工作台状态、最后进行本阶段联合浏览器验收。下列9.5整体复选框只能在相应页面与集成证据齐备后关闭；API适配器通过不等于页面完成。

**Files:**

- Create: `apps/workbench/src/features/identity/IdentityAdminLayout.tsx`、`PrincipalPage.tsx`、`OrganizationPage.tsx`、`AppointmentPage.tsx`、`AuthorityGrantPage.tsx`、`identityApi.ts` 及相应测试。
- Create: `apps/workbench/src/features/workcard/WorkbenchStatus.tsx`、`WorkbenchStatus.test.tsx`。
- Modify: `App.tsx`、`features/workcard/CurrentCard.tsx`、`WaitingSummary.tsx`、`useCurrentCard.ts`、`styles/workbench.css`；复用 tokens，不替换视觉体系。
- Update evidence: `design-qa.md`、`docs/design/identity-admin-mvp/README.md`（只增加实际验收索引，冻结图片不篡改）。

- [ ] 将设计 §6 全部状态写成表驱动 RED：未知/零/等待、无资格、提交与刷新分离、stale、恢复、轮询耗尽、会话状态；每项断言安全文案与允许/禁止动作。
- [ ] 按冻结 ADM-01～04 构建仅相应模式的列表、表单、前置确认和回执反馈；使用真实 API，不用客户端状态冒充成功；不搭建其余三张管理页。
- [ ] 十四个管理写入接入9.4既有共享未决标记门并逐路径验证；9.4的23类命令注册表／回执策略不等于23条生产页面写入均已实现，不另建管理专属恢复系统。
- [ ] 分离 session/read/draft/command 状态轴，保留唯一业务主按钮；提交成功后刷新失败只可重读，不提示重复提交。
- [ ] 按 360/768/1440 在真实浏览器检查状态、焦点、弹层、权限屏与 composer，无遮挡或横向溢出；登录/任职页采用已获确认的设计。
- [ ] 运行管理与工作台 DOM 回归、typecheck/build，保存冻结图与实际页面联合比较证据，独立评审后提交。

## Task 9.5a: 管理API与共享会话／恢复接线

**验收状态（2026-09-09）：已完成本非视觉单元。** 实现`f2891d4`／修复`0c5b2b0`，独立评审3项Important全部关闭，最终336项前端回归、类型／隔离构建及实际基线／拓扑通过；M1读取pathname测试覆盖与M2预期构建提示为非阻断记录。详见[阶段进度](../../progress/2026-09-09-task9-identity-frontend-integration.md)。这不关闭9.5页面／状态或9.6整链／人工UAT；页面字段与分页等局部修订已获用户确认，见9.5视觉确认记录。

**范围：** 这是9.5既有`identityApi.ts`与十四管理写入接线的可独立审阅交付，不建设页面、导航、样式或新后端能力。沿用设计§3～5、§7和冻结Identity合同。保留一个SPA、一份OpenAPI、四字段恢复标记和当前语义／物理版本；不加入SERVICE管理、ADM-05～07、R2、依赖升级、登录回退或任意路径命令。

**Files:** 新建`apps/workbench/src/features/identity/identityApi.ts`、`identityContract.ts`及对应`.test.ts`；按需要把`apps/workbench/src/lib/api.ts`已有会话验证／原回执判定公共部分提取到`lib/sessionTransport.ts`并添加相应测试。只做本轮共用接线所需提取，不重写9.4会话状态机或业务卡协议。现有`features/session/recoveryMarker.ts`、`recoveryOutcome.ts`、`sessionTransport.test.ts`与业务测试仅作直接覆盖所需修改；冻结OpenAPI／生成文件／依赖锁文件／后端／UI／部署不改。Root拥有本计划与进度证据。

**消费接口：** `WorkbenchSession`、同一个`SessionController.recovery: RecoveryStore`、`RecoveryStore.reserveWrite/clear/read`、`matchesReceipt`、`provenWriteOutcome`及OpenAPI1.4.0生成类型。`createWorkbenchApi`既有调用接口和七卡／草稿语义保持兼容。不能为管理模式新建一套Storage key、WeakMap原请求系统、Receipt endpoint或令牌缓存。

**产出接口：** `createIdentityApi(recovery: RecoveryStore, fetcher?: (request: Request) => Promise<Response>, baseUrl?: string)`。返回`recovery`、六个与OpenAPI operationId同名的读取方法（各接收`session, query, signal`）、`write(session, original: IdentityOriginalWrite, signal)`、`receipt(session, key, signal)`。query/body/response以生成的准确类型与具名静态检查为准，不能`any`贯穿或用任意operation/path字符串派发。`IdentityOriginalWrite`是14个静态commandType的判别联合，保存原key、准确目标（创建不虚构目标）、body和适用的If-Match，绝不保存Bearer或当前权限证明。`IdentityApi`为工厂返回类型。内部共享transport只承接既有验证与回执职责，不成为通用命令平台。

**内部字段澄清：** 10个既有资源变更采用`{commandType,key,body,targetId,ifMatch}`，4个创建采用`{commandType,key,body}`；各分支body维持准确生成类型。封闭校验拒绝创建时夹带targetId/ifMatch，变更必须准确UUID目标与强Identity ETag；不接受任意headers包。这只是前端内存原请求的具名字段，不改变HTTP DTO。

- [x] **Step 1 — 表驱动RED证明实际请求映射。** 逐条以冻结Identity registry为独立期望，覆盖14个commandType对应method/path/body、4创建无If-Match、10修改／生命周期携带原强Identity ETag；六读取验证准确query、分页、no-store和本人header。断言真实`Request`输出，不只检查registry常量。读取包括`listIdentityProviderUsers/getIdentityAdminOptions/listIdentityPrincipals/listOrganizationUnits/listAppointments/listAuthorityGrants`；候选完整用户名0～1／无nextCursor、列表最多50、options按page／optionKind限制，不能从客户端扩充权限集合。
- [x] **Step 2 — RED证明共享门和迟到响应。** 一条管理写入结果未知后尝试工作台写入，以及反方向，必须在网络派发前拒绝覆盖；同一原对象／key／body可用更新后的Bearer重放，克隆／改body／改key不能冒充原请求。存储失败或Actor改变前后均不得派发／披露／清除其他身份标记。示例断言遵循以下公共行为，使用真实工厂而非替身方法：

```typescript
const identity = createIdentityApi(sharedRecovery, captureRequest);
const workbench = createWorkbenchApi(captureRequest, location.origin, sharedRecovery);
await expect(identity.write(session, originalIdentityRequest, signal)).rejects.toThrow();
expect(sharedRecovery.read()?.commandId).toBe(originalIdentityRequest.key);
const sentBefore = capturedRequests.length;
await expect(workbench.write(session, originalTaskRequest, signal)).rejects.toThrow();
expect(capturedRequests).toHaveLength(sentBefore);
```

- [x] **Step 3 — 最小实现与GREEN。** 先保留实际RED再实现typed factory、静态路由、窄响应检查和共用transport。管理入口拒绝非null代办选择，不去掉header降级成本人；不依赖`canEnterWorkbench`授予管理资格，服务端仍逐请求验证管理授权。每请求取得当前token，前后复验epoch／isCurrent／AbortSignal；公共业务仍支持原合法代办，且同身份token轮转不丢原请求。
- [x] **Step 4 — 完整回执／错误／读取回归。** 14路径分别验证成功／NO_CHANGE适用项、错误Fact类型／commandId、未知结果、404回执、损坏正文、取消、401/403、并发迟到响应、re-auth token轮转。只按已有完整终态或明确未提交错误规则清标记；GET失败、技术失败、HTTP状态本身不证明未提交，不生成新key；完整刷新仅按标记查原回执，不能凭新表单重建旧写。管理读取拒绝畸形／越界／SERVICE投影／非法enum等正文，不能把失败伪造成空列表；Identity读取没有304成功分支。错误文案为本地安全静态说明，不直接展示原始错误、标识或凭据。生命周期／自锁／最后管理员／依赖／stale等按冻结安全code和retryPolicy保留可供后续表单处理的语义，不改变其重试规则。
- [x] **Step 5 — 复验与独立评审。** 聚焦测试后在稳定源码运行完整前端tests/typecheck/build和openapi:check；记录锁定Node24.20.0/npm11.9.0、实际用例与退出码，Root运行基线／拓扑。构建显式使用此计划忽略目录中的隔离输出：`npm run build --workspace apps/workbench -- --outDir ../../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/task95a-dist`；不覆盖本地登录服务正在使用的`apps/workbench/dist`，不使用`--emptyOutDir`清除其他目录。独立评审包含六读取＋十四写入逐路径与共享门回归；只提交本地本单元文件，不推送、不改在线制品／配置／数据。未实际完成页面前不得关闭9.5或声称管理页面前后端已经打通。

## Task 9.5b: 四张管理页面读取与受保护入口

**源码阶段已验收（2026-09-09）：** 实现`b7f8524`、必需代办入口补测`687252f`；完整351项／22文件通过，补测后受影响33项／2文件及typecheck通过；独立spec与quality复审批准。实际baseline／topology通过、四页冻结视觉与360/768/1440受控浏览器检查通过，运行中dist哈希不变。M1组织展开按钮`aria-expanded`作为非阻断后续项保留。仅关闭读取／准入，不关闭十四写入、9.5或9.6。见[阶段证据](../../progress/2026-09-09-task9-identity-frontend-integration.md)。

**Scope:** 本单元交付四张真实API列表／详情与分页、读取状态及同SPA管理准入。14写入的表单／二次确认在9.5后续单元接入，不缩减整体9.5验收。用户现已确认新增／改名沿用右侧详情区编辑、危险操作沿用同风格二次确认框并显示影响及原因选项；不重新设计四张主页面。保留当前读取单元的独立评审边界，不能把只读阶段说成完整管理功能。主操作位置保留但未接线动作禁用并明确说明“当前仅开放查询，写入功能尚未接入”，不伪造成功或继续显示待用户确认。

**Files:** Create `features/identity/IdentityAdminLayout.tsx`, `PrincipalPage.tsx`, `OrganizationPage.tsx`, `AppointmentPage.tsx`, `AuthorityGrantPage.tsx`, `IdentityAdminApplication.tsx`, `identityRoutes.ts`, `identityLabels.ts`, `useIdentityList.ts`, `styles/identity-admin.css` 及对应测试（均在apps/workbench/src下）；必要的共用列表状态／分页小组件可置于同目录，禁止通用管理框架。Modify `features/session/SessionApplication.tsx`及测试，仅为管理静态route和同会话准入；不改会话控制器／已有业务卡行为。

**Interfaces:** 消费9.5a `IdentityApi`的四个具名列表与现有 `useActorSession()`。生产 `createIdentityApi(controller.recovery, undefined, location.origin)`，共用同一恢复对象。新增静态route只有 `/admin/identity/principals`, `/admin/identity/organizations`, `/admin/identity/appointments`, `/admin/identity/authority-grants`；不开放任意通配路径或URL身份选择。

**验证前置窄修正（实际检查发现）：** 既有`verify_visual_asset_counts`递归计算整个identity-admin-mvp目录，把已批准的四张内容修订图与原冻结七张混计，实际baseline退出1／topology退出0。允许修改`scripts/baseline/verify_baseline.py`和既有`scripts/baseline/tests/test_verify_baseline.py`，只明确区分原七张集合与`revisions/2026-09-09-contract-alignment/`下四张具名已批准修订图。原冻结数量／路径及其他视觉门保持；不得简单将7改11或忽略整个revisions目录。先RED覆盖已批准补充不污染原计数、原图缺少／多余仍拒绝、未批准额外图仍拒绝，再实际CLI复验；不改图片、交付状态或物理合同。

- [ ] **Step 1 — 准入RED/GREEN。** 测试实际SessionApplication：管理地址在本人任职明确确认之前不派发管理GET；READY且canEnterIdentityAdmin=true、DIRECT且非空准确Actor才可进，不依赖canEnterWorkbench；代办不可自动删header回退本人，无资格不披露列表。普通workbench不出现管理侧栏。注销／epoch变化清旧列表和选择；初次未决恢复使用同RecoveryPage，不绕过原标记处理。
- [ ] **Step 2 — 四列表RED/GREEN。** 表驱动四静态路由，真实组件+createIdentityApi+受控fetch捕获Request；验证HTTP pathname、limit=20、cursor、准确Bearer和本人header，不能只mock组件返回固定数组。断言真实响应的中文名称／状态／详情，禁止显示UUID/ETag及旧稿已删除字段。例：
```ts
expect(captured[0].url).toContain('/api/v1/admin/identity/principals?limit=20');
expect(await screen.findByRole('button', {name: '陈晓'})).toBeVisible();
expect(screen.queryByText('创建时间')).not.toBeInTheDocument();
```
实现各具名页面，生成DTO类型不降为any，映射固定角色／权限中文标签；角色／权限只读披露不等于在线可建／可授予。
- [ ] **Step 3 — 有界分页／组织RED/GREEN。** 下一页仅服务端nextCursor，上一页仅本次已访问游标；不自动拉完整目录、不总页数、不隐藏过滤当前页充当搜索。页变更清旧selection，刷新仅保留仍出现在新响应的准确id。未知计数不假0。组织仅当前获权已加载关系，未加载父节点显示“上级组织未加载”，不称根；无孩子不称不存在。防cycle／缺父异常投影导致无限递归或遗漏显示，禁止二次目录扩权。
- [ ] **Step 4 — 读取状态RED/GREEN。** 有界加载／空列表／失败区别，安全静态错误及重读；401/403及身份改变立即清敏感结果；GET失败不能假空，不把原始网络错误或DTO内部id放文案。取消与迟到响应不得覆盖新页/新身份，同身份token更新不重置当前页或选择。详情主操作/生命周期未接线时明确禁用，不用按钮点击伪造写成功。
- [ ] **Step 5 — 同源接线与回归。** 现有固定回跳不变；管理地址只作内存中的页面意图，不增加sessionStorage/localStorage/OIDC字段。完整IdP跳转后不承诺自动回到原管理子页，可按原静态地址重新进入；无任职或无资格不猜选。清楚提示已具备管理资格时可使用管理地址，不把它说成尚未开放。生产入口不加入夹具／mock／测试身份回退。
- [ ] **Step 6 — 验证与评审。** 固定Node24.20.0/npm11.9.0，定向RED/GREEN后完整前端一次、typecheck、隔离build、openapi:check；Root实际baseline/topology。构建outDir为 `../../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/task95b-dist`，绝不覆盖当前apps/workbench/dist，不使用--emptyOutDir。实际浏览器360/768/1440及原1487视口核对读取页面与冻结布局，视觉夹具仅用于UI测试不能冒充真实登录／后端E2E。独立spec+quality评审。无部署／数据变更／推送；四页面写入、工作台完整状态和9.6仍需后续证据。


## Task 9.5c: 详情区表单与十四条管理写入

**状态（2026-09-09）：已完成源码与受控UI阶段验收。** `4a6ee77`交付十四写入、受控候选、详情编辑／二次确认、共享恢复及会话guard；416项完整前端、类型／OpenAPI／隔离构建、实际baseline／topology及360/768/1440浏览器通过。独立spec compliant／quality Approved，无Critical／Important。通用成功分页提示P3保留；不据此关闭9.5d或9.6真实整链。用户批准的交互不再重复索取确认。

**Scope:** 用户已明确批准新增／改名使用原右侧详情区、保留左侧列表，危险操作同风格二次确认并显示影响与原因；恢复继续遵守既定确认和原因要求。依赖9.5b独立评审通过，实施全部十四条既定管理写入，不重新出四张主页面、不新增接口／生命周期／权限。此单元是管理页面功能接线，不能替代9.6真实IdP与数据库整链或人工UAT。

**Files:** Create `apps/workbench/src/features/identity/useIdentityCommand.ts`, `IdentityCreateForm.tsx`, `IdentityRenameForm.tsx`, `IdentityActionConfirmation.tsx`, `useIdentityOptions.ts`及相应测试；Modify同目录四个既有Page、`IdentityAdminApplication.tsx`、必要的`useIdentityList.ts`刷新接口及`styles/identity-admin.css`。每个文件仅承担具名职责，可拆出同目录强类型字段组件而不建立通用表单／命令平台。仅在直接集成所需时调整`SessionApplication.tsx`与测试；不改会话控制器、合同／生成文件、后端、依赖或运行配置。Root拥有计划／验收／QA证据。

**Interfaces:** 消费9.5b实际导出的页面／路由／刷新接口、`IdentityApi`、`IdentityOriginalWrite`、`WorkbenchSession`和同一个`controller.recovery`。`useIdentityCommand(session, api)`只保存本次页面内的编辑与原请求，不新建Storage或第二个恢复协议。对不确定原请求的发送必须再次传同一个`IdentityOriginalWrite`对象；创建只含commandType/key/body，更新额外准确targetId/ifMatch。工厂现有`write`返回具名终态，`receipt`使用原回执端点；`TransportError`的`provenOutcome`和冻结`retryPolicy`决定后续动作，不能靠HTTP状态或错误文案猜未提交。

- [ ] **Step 1 — 十四路径DOM RED。** 通过真实页面控件、真实`createIdentityApi`与受控fetch捕获Request；四创建、两改名、八生命周期分别验证准确path/method/body、UUID key、更新If-Match、当前本人header/Bearer。测试取消二次确认不POST、快速重复点击只发送一次、提交前确认目标中文名和影响、列表换选中后不能把旧ETag用于新目标。至少一项先保存明确断言失败输出，再实现；不能只测试请求构造函数来替代按钮接线。

```ts
await user.click(screen.getByRole('button', { name: '暂停用户' }));
expect(writes).toHaveLength(0);
await user.selectOptions(screen.getByLabelText('操作原因'), 'ADMINISTRATIVE_ACTION');
await user.click(screen.getByRole('button', { name: '确认暂停' }));
expect(writes).toHaveLength(1);
expect(await writes[0].clone().json()).toEqual({ reasonCode: 'ADMINISTRATIVE_ACTION' });
expect(writes[0].headers.get('If-Match')).toBe(selectedRow.etag);
```

测试操作使用仓库已装Testing Library的`fireEvent`或现有user工具，不为上述示意增加依赖。

- [ ] **Step 2 — 受控候选RED/GREEN。** 新增用户先输入完整用户名并显式精确查询`listIdentityProviderUsers`，仅0/1候选，无cursor／模糊搜索；选准确providerUserSelector且不显示原selector/subject，输入本地显示名。查询变更立即清旧候选；无结果、加载失败、过期候选不能假成功或手填subject。组织创建通过`getIdentityAdminOptions({page:'ORGANIZATIONS',optionKind:'ORGANIZATION',limit:20})`选择准确父项。任职分别用APPOINTMENTS/PRINCIPAL和APPOINTMENTS/ORGANIZATION；授权分别用AUTHORITY_GRANTS/APPOINTMENT和AUTHORITY_GRANTS/ORGANIZATION。各候选独立有界游标、只展示获权标签、不自动遍历全目录；重名仍按准确id选择。option/page或Actor切换清旧结果，旧请求迟到不能替换新候选。代码只能来自正确页面的服务端roleCodes/grantableAuthorityCodes，并与冻结三角色／八业务码相交；IDENTITY_ADMIN和四管理码仅列表披露，不能提交。

- [ ] **Step 3 — 详情编辑RED/GREEN。** 用户创建只发送providerUserSelector/displayName；组织只发送parentOrganizationId/code/displayName；任职只发送principalId/organizationId/roleCode/effectiveFrom/effectiveUntil；授权只发送appointmentId/authorityCode/scopeOrganizationId/validFrom/validUntil。结束时间可空且序列化为null；日期输入明确显示本机时区并转换为UTC instant，结束必须晚于开始；服务端仍验证任职包含授权窗口及范围，不从不含任期的选项DTO臆造前置证明。名称trim后1～200安全文本，组织code满足`[A-Z][A-Z0-9_]{0,63}`，不得改已建code/parent/role/任期。改名只发送displayName。字段错误关联输入，取消返回原详情无写入；dirty离开／换页／换目标须明确舍弃确认。新增入口进入编辑后只保留一个视觉主提交按钮，原创建按钮不能与提交竞争。

- [ ] **Step 4 — 生命周期RED/GREEN。** ACTIVE主体可暂停／禁用，SUSPENDED可恢复／禁用，DISABLED无恢复；ACTIVE组织可关闭，CLOSED无恢复；ACTIVE任职可暂停／结束，SUSPENDED可恢复／结束，ENDED无恢复；未撤销授权仅可撤销。原因选项只`ADMINISTRATIVE_ACTION`（行政调整）与`SECURITY_RESPONSE`（安全处置），需主动选择，不新增自由文本reason。确认说明暂停影响所有相关任职资格、禁用／关闭／结束／撤销不可恢复，以及依赖检查由服务端裁定，不承诺自动转派或结束责任。最后管理员、自锁、未结束任职、有效子组织／任职及OPEN/WAITING责任等拒绝用具名安全说明；不绕过或在线修复。对话框可键盘进入、Tab不逃逸、Escape取消未提交确认，关闭后焦点返回原触发按钮；提交中不可通过取消制造已撤销假象。

- [ ] **Step 5 — 原请求与键策略RED/GREEN。** 提交中双击／切页不能再派发新写；网络／技术失败保留原对象、key、body、If-Match与共享标记，显示“结果尚未确认”，提供原请求重试和原回执查询。回执查询复用已有有界规则（最多3次自动且可见性门控；可仅提供显式手动查询，不引入新的自动轮询器）；404或查询失败不证明未提交。SAME_KEY_AFTER_FIX仅在完整明确未提交响应后允许修正并保持key；NEW_KEY_AFTER_REFRESH必须重新读取／核对后新key；NEW_KEY_AFTER_ADMIN_FIX先解释需管理员处理再重新核对，不能自动补事实；NO不显示重试写入；SAME_KEY_AFTER_REAUTH和SAME_KEY_AFTER_BACKOFF不改原key。页面未保存任何payload到Storage，刷新／离开丢失原对象后只能共享RecoveryPage按标记查询，不能重建原请求或自动新key。

```ts
const before = writes[0];
await user.click(screen.getByRole('button', { name: '重试原请求' }));
expect(writes[1].headers.get('Idempotency-Key')).toBe(before.headers.get('Idempotency-Key'));
expect(await writes[1].clone().text()).toBe(await before.clone().text());
expect(api.recovery.read()?.commandId).toBe(before.headers.get('Idempotency-Key'));
```

- [ ] **Step 6 — 结果与会话RED/GREEN。** 完整已确认成功／NO_CHANGE与REJECTED分开；只有两改名允许NO_CHANGE。成功后刷新失败显示“结果已记录，列表刷新失败”，只能重读不得重复写。重新读取服务端记录，不本地乐观伪造revision／授权；翻页刷新不保证新建记录在当前页出现。401/403或epoch变化清敏感表单／候选／列表，取消迟到反馈但不清其他Actor标记；同Actor token rotation保留dirty、原请求、分页和选择，重试携带新Bearer。跨管理／业务写共享未决门，恢复页不得绕过原查询与授权。测试保存失败、标记损坏、重登无正文、stale后ETag与key更新、终态刷新失败只GET。

- [ ] **Step 7 — 验证与本地提交。** 稳定源码一次完整前端测试及typecheck/openapi:check，构建隔离outDir `../../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/task95c-dist`，不覆盖线上dist、不使用--emptyOutDir。保存RED/GREEN实际命令／退出码，独立spec+quality评审覆盖十四按钮与共享恢复；Root实际baseline/topology，浏览器360/768/1440及1487冻结稿对照，检查编辑／确认焦点、错误、取消、提交反馈与响应式。受控fetch夹具仅UI集成证据，不冒充实际IdP／API整链；不推送、不部署或变更真实账号／授权。9.5工作台状态及9.6全链仍按对应门验收。

## Task 9.5d: 既定工作台状态提示收口

**阶段验收：已完成。** `348e933→366468b→da9d289`，真实App／Request状态与恢复测试、浏览器原冻结稿及响应式核对、Root实际baseline／topology、运行中dist不变；独立评审I1经两例RED及93项受影响GREEN、浏览器复验和独立复审关闭，无开放必修项。首版443项完整回归与修复后93项是不同快照，见[最新前端证据](../../progress/2026-09-09-task9-identity-frontend-integration.md)。下列步骤均由这些证据覆盖，仅关闭9.5前端源码／受控UI阶段，不替代9.6。

**Scope:** 只补设计§6、§7及T9-W02～W08/W10/W11的前端状态／交互缺口，复用Task9.0七卡及9.4会话／恢复，不重做卡片、菜单、业务协议、登录或管理页面。真实Worker恢复、权限实库与七卡开户整链仍归9.6，不能用DOM夹具替代。

**Files:** Create `apps/workbench/src/features/workcard/WorkbenchStatus.tsx`及`.test.tsx`；Modify `App.tsx`/`.test.tsx`、`features/workcard/useCurrentCard.ts`、`WaitingSummary.tsx`、`CurrentCard.tsx`及直接相关测试、`styles/workbench.css`。只按职责提取状态显示；不得创建全局状态框架、第二个恢复器、客户端计数器或通用错误平台。Root维护QA和验收证据。

**Interfaces:** 保持`App({session,api,sessionActions,sessionNotice})`、`useCurrentCard(session,api,options)`既有公共动作refresh/save/submit/recover/replay/abandonRecovery及RecoveryPage消费者兼容。在hook已存在read/busy/pending/recovery状态基础上补准确状态字段，`WorkbenchStatus`仅消费这些字段而不自行发请求或重推业务结论。状态优先级为身份失效／越权＞未决恢复＞已确认结果但刷新失败＞普通读取错误＞摘要。

- [x] **Step 1 — 状态表DOM RED。** 受控Request通过真实`createWorkbenchApi`和App分别返回初始慢响应／503、有效currentCard=null且waitingCount=0、仅等待正数、0/1/2后续摘要。断言加载未知不假0，零态“当前暂无可处理责任”与仅等待“当前无可处理责任，另有等待事项”可区分；今日摘要直接来自envelope.todaySummary，不用当前卡数量伪造今日总量，后续只摘要不提供提交按钮。SessionApplication已有无任职／无管理资格情形仍不能落入业务零态。

```ts
expect(screen.queryByText('等待 0')).not.toBeInTheDocument(); // 请求尚未完成
resolveCurrent(envelopeWithoutCardWithWaiting(2));
expect(await screen.findByText('当前无可处理责任，另有等待事项')).toBeVisible();
expect(screen.getByText('等待 2')).toBeVisible();
```

上述测试辅助须由本测试的准确Schema fixture明确构造，不新增生产mock入口。

- [x] **Step 2 — 提交／刷新分离RED/GREEN。** 分别保存候选、业务提交、回执恢复后读取失败；保留Receipt确定性而不让“正在刷新”永久残留。业务成功后明确“结果已记录，当前责任刷新失败”，仅显示重读；成功且刷新成功停止加载说明；REJECTED不得用success配色／已记录成功文案。未知POST仍保留原key/body／恢复标记与原请求重试，GET失败不能生成新key。用实际按钮流断言确认成功后手动刷新只增加GET、不增加POST。保留唯一业务主按钮与已保存候选被修改后的提交禁用。

- [x] **Step 3 — 轮询额度RED/GREEN。** 使用假时钟驱动30秒周期，准确记录最多6次等待自动读取／3次回执自动查询；额度耗尽显示“自动刷新已暂停，可手动刷新”或对应回执查询暂停说明，不能显示仍实时。token rotation不重置额度，切后台不消耗派发额度，卸载移除timer/listener；真正的新identity epoch才重新开始。不增加独立轮询器、不触发Worker。手动重读仍允许但不能重置自动额度；回执404不清原标记。

- [x] **Step 4 — 刷新／恢复与焦点RED/GREEN。** 同scope304和语义相同200保留dirty与逻辑焦点；旧GET不能覆盖新读，selector/Task/Draft上下文改变按原规则安全重载。503/429/网络错误若保留旧内容须显式陈旧并停写，清除则不显示假0；401/403/404清敏感内容。400/428修正键与412/digest/422后续键策略不退化。共用RecoveryPage在recoveryOnly或readAfterRecovery=false时不新增业务读取／自动计数，也不把管理拒绝回执说成业务完成。

- [x] **Step 5 — 最小实现与视觉回归。** 沿用冻结工作台tokens/布局及紧凑会话操作；在既有摘要／反馈区域呈现状态，不加看板或管理导航。live region有状态变化才公告，不每次倒计时刷屏；加载／错误不只依靠颜色。真实浏览器360/768/1440检查composer不遮挡字段／结果提示／主按钮，以及刷新、续期、身份切换后的焦点；与原工作台冻结图联合比较，新增管理样式必须保持作用域隔离。

- [x] **Step 6 — 验证与评审。** 保存实际RED/GREEN，稳定源码完整前端一次、typecheck/openapi:check及隔离outDir `../../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/task95d-dist`，不覆盖运行中dist。独立spec+quality评审包括RecoveryPage共享消费和七卡／管理回归；Root实际baseline/topology与浏览器证据。只有9.5b/c/d相应门均通过才能关闭9.5前端源码阶段；9.6真实用户整链、人工UAT、Task10/R1容量发布不得晋级。无推送、部署或实际账号／授权变更。

## Task 9.6a: 原 bootstrap 集合核验修正（9.5 前置窄修复）

**批准范围（2026-09-09）：** 用户确认只核验原始事实和原凭据闭包，不冻结整个租户；严格遵循设计 §5.3 和 Identity 合同 Offline bootstrap 的同日澄清。本单元是本地登录检查发现问题的后续修正，不表示整个9.6完成，也不扩展9.5。

**Files:** `backend/src/main/java/io/github/windyzhu3/ontologylaw/identity/internal/persistence/JooqIdentityBootstrapService.java`、`backend/src/test/java/io/github/windyzhu3/ontologylaw/identity/IdentityBootstrapIT.java`；必要时可新建同域聚焦集成测试／测试夹具，不重构其他领域。Root 同步合同／设计／计划／验收进度；不修改 OpenAPI、旧迁移、运行权限、候选发行策略或共享锁序。

- [x] 先写并观察真实 PostgreSQL RED：原初始化后新增 Principal（包含必要 SERVICE）、组织、任职、普通业务 Grant、DelegationGrant、ObjectAccessGrant 和其他命令闭包记录时，原 manifest 核验应返回原 receipt、`VERIFIED_ORIGINAL`、空 plannedDelta，且数据库内容无变化。分别隔离覆盖各类新增记录，防止只修第一处计数。
- [x] 最小生产修复：按 trusted Tenant 与原闭包 Facts 的准确 ID 限定原组织／Principal／任职／四 Grant 检查和权限码分组；保留原 Grant ID 与四 management code 的一一对应，保留原 Tenant 和所有字段／时间／版本／生命周期严格一致。原 bootstrap 未建立的委托／对象规则不作租户为空检查。新增记录合法性不由 bootstrap 判定。
- [x] 补原始集合缺失、原字段／revision／生命周期被改、原 Grant code 互换等负例；保留 manifest／command／Slot／Receipt／Audit 完整性、错误／回滚与未知提交恢复用例。正／负例须断言无写入而不只看返回码；时间到期用准确 candidate expiry 且显式证明，不用从测试开始固定睡眠冒充到期。
- [x] 验证扩展后、候选已过期且 IdP 不可用时仅完整原结果仍能核验；未完成／损坏原结果仍拒绝。真实管理路径建立的合成身份可作为集成证据；底层 SQL 隔离夹具只用于精确 verifier／损坏边界测试，不能冒充9.6真实用户开户E2E。
- [x] 运行全部 bootstrap 相关测试和受影响身份／架构回归，记录实际 RED/GREEN 与退出码；独立评审通过后本地提交，不推送。Root 再用保留的本地原 manifest／密钥做只读核验；不得重建租户、换 key、补事实、修改激活发布凭据来掩盖问题。

阶段证据：新增8项先7个生产冲突／1项原本通过，修复后15项通过；完整受影响99项通过，评审I1测试证据补强有3项RED及35项GREEN、复审关闭。Root修复后两次本地原清单verify退出0，54张表内容和原manifest／密钥／配置／Jar前后不变。M1旧生成器／编译器噪声作为非阻断事项保留；不把此门当作9.5页面或完整9.6通过。

## Task 9.6b: 本地受控制品切换与管理页入口

本单元是已批准Task9.6的本地部署前置，不新增产品功能，不涉及Worker授权或HUMAN建档。遵守设计及`database/schema-contract-52-plus-2/docs/runtime-validation-contract.md` §14。原登录环境运行字节不是最新源码，不能把源码测试当成已部署证据。

**Files:**

- Modify: `deploy/local-login/local_login.py`（只接线必要生命周期／具名操作）、`deploy/local-login/server.mjs`、`deploy/local-login/README.md`。
- Create: `deploy/local-login/local_release.py`（本地发布描述、摘要、staging／激活／回退、CAS与恢复状态）；允许同目录一个专用发布测试辅助模块，不建立通用部署框架。
- Create/Modify: `deploy/local-login/tests/test_local_release.py`、`test_local_login.py`、`server.test.mjs`。
- 不修改业务Java／OpenAPI／数据库迁移／冻结前端页面；源制品及静态元数据仅读取以固定摘要。实际激活由控制者在评审后执行，实施者只用临时目录和假的外部边界运行测试，不访问私有runtime或运行中服务。

- [ ] RED：四条准确管理导航返回SPA；未知路径、编码穿越、API HTML fallback仍拒绝。路径为`/admin/identity/principals`、`/admin/identity/organizations`、`/admin/identity/appointments`、`/admin/identity/authority-grants`。
- [ ] RED：候选Jar或SPA文件集摘要不符、缺失文件、配置漂移、构建／拷贝失败、错误旧部署状态、原材料漂移、未知PID均在相关写入前失败；激活中断能够识别状态，不能把部分操作当成功；回退只恢复已保存并复核的字节与gate，不回滚业务事实。
- [ ] 实现具名本地stage／activate／rollback操作。stage消费明确的已构建Jar和dist，不在启动／resume中构建；在运行目录外保存不可变release目录和排序SPA文件摘要。第一次转换须先保存现有Jar、dist、配置和旧gate，绝不在备份前覆盖活动字节。允许停止仅本环境拥有的API／SPA后构建候选，失败用已保存的旧集恢复；不必为了零停机增加平台。
- [ ] 首次legacy快照保留`01213b1`中的原托管server字节作为历史证据。原server硬编码工作树路径，不能伪称复制后可在release目录运行；因此旧Jar／dist／配置／gate的回退通过单独固定摘要的新路径托管器提供，明确称为“业务制品回退”，不声称恢复历史托管器行为。测试确认不混入候选assets，不回写tracked源码或活动dist。
- [ ] 发布描述固定Jar、SPA、托管server、公开OIDC配置、源commit／工具链和schema-contract-manifest，以及当前OpenAPI／静态信封Resolver／事件路由策略的精确来源摘要。来源位置从既有实现发现，不发明静态合同。旧toolchain-only manifest记录保留为历史，不能声称其证明新整链发布。
- [ ] deployment_state只由既有迁移Owner受保护凭据以CAS切换，比较完整旧gate并严格断言一行成功；不使用postgres无条件UPDATE、不新增角色／表／迁移。确认现有schema不变才允许本次字节回退。文件替换与DB事务分阶段落受保护日志；冲突／不确定状态安全关闭，提供核对与显式恢复路径，不静默重试新结果或初始化。
- [ ] API和后续Worker消费同一当前release／manifest；SPA读取已固定dist和server，resume只验证并消费已激活字节。保留原legacy Jar漂移拒绝；新路径不接受漂移／任意不受控目录。停止／重启校验PID对应本环境准确可执行文件和制品，不按名称杀进程，不停止Keycloak／DB来完成制品切换。
- [ ] 原operator.json／original-manifest／所有原密钥和证书保持字节不变。增加具名`bootstrap-verify-current-release`，用另名ACL受控派生operator，仅修改database当前expected release／manifest，其余逐项相等，调用既有完整原manifest verify；禁止重candidate／dry-run／execute。实际命令不通过旧wrapper重写原operator。
- [ ] 所有实际文件在原gitignored、当前用户与SYSTEM ACL的runtime目录；每个运维入口先确认边界及保护。密码只由受保护文件传入，stdout/stderr不输出密码、Token、原subject或数据库连接秘密。说明升级、失败核对、回退、停止／恢复命令；不承诺尚未执行的运行结果。
- [ ] GREEN：针对该单元的Python全部测试和Node托管测试通过，记录真实RED／GREEN、命令和退出码；自评后提交且不推送，独立spec／quality评审。控制者随后保存真实构建、激活、TLS/API/SPA管理直达、原闭包及回退／恢复证据，再关闭本单元的部署门。

## Task 9.6c: 既有Worker的本地最小装配

**本单元已完成（2026-09-09）：** `5fae852`实施、`b2c5ad4`锁序修复；独立spec／quality复审通过。真实授权delta3再delta0、原闭包与材料保护、同当前Jar的Worker联合三循环READY／零监听端口、准确停启与登录入口复验通过。M1中断启动pending标记需显式核对的限制保留。此结论不关闭W09真实等待恢复、七卡、人工UAT或整体Task9。

依赖9.6b发布接口通过独立评审；真实启动只在当前API发布及原bootstrap核验通过后执行。本单元复用生产Worker，不实现新调度器、Job、管理页面或业务权限接口。实施者先纯测试与源码交付，控制者随后执行已批准的本地服务配置／授权／启动。

本地接线说明：全局的api／worker互斥角色由既有生产`ols.runtime-role`（或`OLS_RUNTIME_ROLE`）实现；本地runner须显式设置该已存在的配置，不能只设置源码未读取的`APP_ROLE`并声称已启动Worker。不新增生产角色选择机制。

**Files:**

- Create: `deploy/local-login/local_worker.py`、`deploy/local-login/tests/test_local_worker.py`。
- Modify: `deploy/local-login/local_login.py`（命令分派与既有生命周期登记）、`deploy/local-login/local_release.py`（只承接已登记Worker的发布停启边界）、`deploy/local-login/README.md`，以及必要的现有本地生命周期测试。
- 不修改生产Java、身份管理allowlist、数据库迁移、前端或依赖版本。发现生产缺陷先报告准确失败证据，不能用runner绕过。

- [ ] RED：原`local-service` alias不可直接成为Worker binding；副本`local_service`保持同证书DER／私钥与指纹，原keystore字节不变；错alias／证书／有效期／trust拒绝且不重生成身份。配置缺少或错release／manifest、API origin、数据库能力时安全失败。
- [ ] RED：准确原SERVICE与ROOT前置不成立、既有授权部分／多余／被修改、重复操作形状不一致时拒绝且不写；第一次只建固定三项，完整同原清单重试delta0；HUMAN授予不通过此路径。
- [ ] 原`service-fixture.json`精确定位Tenant／Principal／Appointment，核对ACTIVE的`LOCAL_R1`、根`ROOT`、`LOCAL_SERVICE`和`SERVICE`任职。证书仍有效时仅在ACL受控目录新副本中修改alias，不改原service.p12／service.crt／API信任／密钥。
- [ ] 具名本地operator-only授权步骤只在原SERVICE任职建立`R1_PROJECTION_CONSUME`、`CONTACT_TASK_RECOVER`、`ROUTING_REVIEW_TASK_RECOVER`，scope为原ROOT以覆盖固定来源及其下本轮Owner。只保留这三项基础能力，不授HUMAN或加入ADM。使用既有迁移Owner受保护连接，锁定准确原事实并单事务写入。原清单先保存准确ID、授予任职依据、时间与形状，重试核对全字段；不伪造HTTP回执／用户操作，操作证据明确为经批准的本地基础设施事务。不得运行旧service-fixture或插入Task/业务事实。
- [ ] 生成独立Worker properties：`ols.runtime-role=worker`、`MVP-2026-09-08.3`、node=`LOCAL_LOGIN_WORKER`、API=`https://localhost:19445`，数据库`law_worker_login`仅`law_app_worker`成员，TLS verify-full、当前相同schema／release／manifest及原worker-db secret。绑定原身份、`local_service`、准确证书指纹、绝对受控keystore／truststore。不得把API properties、OIDC目录／introspection／offline密钥或迁移Owner凭据交给Worker。
- [ ] 使用当前已激活同一Jar启动独立无Web Worker，限制内存并隐藏Windows窗口。登记准确PID／可执行路径／Jar／Worker配置，启动拒绝不确定已有进程；stop／resume／release切换同样准确管理Worker，不能用API的端口或PID代替。Worker不开新监听端口，SPA不得代理internal接口。
- [ ] 就绪检查基于本次进程启动后既有`WorkerRuntimeHealth`的准确ISOLATED／READY状态及最新失败状态、当前DB gate／角色与mTLS读就绪，不把PID存活或旧日志中的READY当健康。保留三个loop共同健康要求，缺恢复权限不能称ready；无需增加新产品健康接口。运行日志保存在保护目录，只返回固定状态／计数；不泄露候选或业务内容。
- [ ] 所有失败和中断保留原材料与操作状态；不自动删除授权、重建SERVICE或回滚业务事实。服务停止不会撤销、重造固定授权；有冲突先明确核对。后续真实等待恢复仍由七卡验收证明，启动READY本身不替代W09。
- [ ] GREEN：本地Worker单测及受影响release／runner／Node回归，保存RED/GREEN与退出码，自评提交，不推送，独立spec／quality评审。真实启动前后核对原bootstrap、原证书／密钥摘要；收集三loop READY、精确无新增监听、停止／恢复同资格证据，才能关闭本单元部署门。

## Task 9.6d: 已批准的固定自动路由合成来源配置发布

**本单元已完成（2026-09-10）：** `17a1e1d`，17项定向／75项受影响Python／4项Node通过，独立spec／quality评审通过。真实配置暂存、发布、未使用时回退、旧包Worker就绪、再发布以及最终同manifest／原闭包／登录入口通过。最终配置包`aabce4e3252946e4952cbcb41ff280d1`／revision9，业务制品仍来自`04bd695`。未创建七卡或HUMAN业务事实；已有来源事实时的回退拒绝当前仅合成测试证据，后续实际建Lead时再补现场证据。

依赖9.6c已通过。用户已明确批准一个额外固定AUTOMATIC本地合成来源，原人工来源／规则／数据库结构不变。本单元只扩展既有本地不可变配置制品流程，不是来源管理产品或动态配置平台。复用当前已核验的Jar、SPA和host；不重新构建或将新runner提交冒充业务二进制的构建来源。

**Files:** 新建`deploy/local-login/local_source_release.py`与`deploy/local-login/tests/test_local_source_release.py`；仅必要修改`local_release.py`的配置切换／恢复防护、`local_login.py`具名命令分派、README与受影响本地发布测试。禁止生产Java、前端、迁移、依赖、IdP、SERVICE绑定／授权变更。

**Interfaces:** 复用`LocalRelease.current/load/stable/seal/switch/activate/rollback/recover`与现有运行时ACL、精确三消费者停机、七字段Owner CAS、schema/Flyway比对、历史制品保留。新增无可选来源参数的`stage-local-auto-source <operatorCommit>`命令，仅暂存并返回新制品ID；激活／停启／回退沿用现有命令。当前业务源码出处从父制品继承，operatorCommit独立记录为配置工具出处，不能改写为新构建证明。

- [ ] RED：固定增量只允许以下五行；原`LOCAL_SYNTHETIC`五项MANUAL策略以及其余API／TLS／OIDC／SERVICE绑定逐字保留。重复／冲突／已有AUTO／错误ROOT或时区、配置漂移、非精确当前父制品、未提交工具代码、未知配置输入均拒绝，不覆盖任何旧包。

```properties
ols.api.sources[LOCAL_SYNTHETIC_AUTO].assignment-mode=AUTOMATIC
ols.api.sources[LOCAL_SYNTHETIC_AUTO].routing-organization-root-codes[0]=ROOT
ols.api.sources[LOCAL_SYNTHETIC_AUTO].routing-supervisor-root-code=ROOT
ols.api.sources[LOCAL_SYNTHETIC_AUTO].source-intake-root-code=ROOT
ols.api.sources[LOCAL_SYNTHETIC_AUTO].business-timezone=Asia/Shanghai
```

- [ ] 将纯增量校验与新包暂存放在单一职责模块。封闭profile=`LOCAL_SYNTHETIC_SOURCE_RELEASE_V1`；manifest绑定父制品ID／descriptorHash／manifestHash、原配置摘要、固定五项增量、operatorCommit、继承业务制品出处及完全相同Jar／SPA／host摘要。沿用当前release digest，仅manifest变更；生成API gate期待和deployment元数据。不得从live target/dist取字节，不伪造buildExitCodes、不把新工具提交称业务构建。配置中秘密只留原受保护制品，不打印。
- [ ] RED：已登记Worker未停止则切换拒绝；应用启动／恢复仍从同一新包更新API和Worker manifest期待。原bootstrap／证书／三项固定SERVICE授权不变。不同gate／schema／父包／原材料、损坏配置或丢失CAS响应不能自动重试或将暂存当激活；保留原journal恢复路径。
- [ ] 新来源尚未产生事实时，允许经过既有Owner CAS回退到父包，并可重新暂存／激活同一固定增量。任何切换／回退／中断恢复若会移除或改变AUTO配置，均须在三个消费者停止后，以既有Owner只读检查准确原Tenant的`lead.lead.source_account_code='LOCAL_SYNTHETIC_AUTO'`；已有任意来源Lead事实则拒绝丢失该来源配置，不删事实，不以Task已终态放行。事实存在性检查不引入新Owner产品接口／权限或SQL写入。正向保留原配置的普通业务制品升级不受无关限制。
- [ ] RED/GREEN覆盖固定增量、来源／绑定不扩权、继承二进制来源、精确same-bytes、冲突／未提交输入／链接边界、三消费者、manifest更新、来源事实阻断和journal恢复路径。覆盖测试示意（夹具沿用既有临时release fixture，不访问真实环境）：

```python
def test_fixed_source_delta_preserves_every_existing_property(self):
    before = self.original_api_config()
    after = add_fixed_auto_source(before)
    self.assertEqual(before + AUTO_SOURCE_LINES, after)
    self.assertRaises(RuntimeError, add_fixed_auto_source, after)
    self.assertNotIn(b'source-account-codes[1]', after)
```

上述`add_fixed_auto_source(bytes)->bytes`与`AUTO_SOURCE_LINES`由新模块定义；夹具`original_api_config`须使用合成非秘密属性，不依赖真实runtime。对含事实的移除场景断言CAS未调用、pointer/journal/原包未变；仅测试fixture可制造来源存在，不向真实库插入Lead/Task。
- [ ] 运行新来源模块及受影响release／Worker／runner测试，保存命令、实际RED/GREEN输出与退出码；自查提交后独立spec／quality评审。控制者随后才执行真实暂存→停机→激活→启动、API／Worker同manifest、登录入口、原闭包核验，以及来源未使用时的真实回退／再发布。本单元不创建七卡业务事实，不关闭七卡或人工UAT。

## Task 9.6e: 真实浏览器受控建档与动态资格链

**类型检查补充（2026-09-10）：** 根依赖不存在Node类型声明，真实测试fixture使用Node内置模块，独立tsc不能完成。允许同一根package／lock额外加入精确开发依赖`@types/node=24.13.3`（npm元数据integrity为`sha512-Dh8vAsV36ig5wa9OX4pXvMc9D3Veibfw2wix0CUwYODLD8nkj9UsLjASr49nPg+2eKzxhBV+v7L8pXvT4e639Q==`），其`undici-types`依赖按lock精确解析。此为下文“仅加入Playwright”的最小具名例外；不从机器外部类型目录获取不可复现的编译前提，不加入生产polyfill或改变应用依赖版本。

依赖9.6d完成；这是既定9.6真实管理链的第一个可独立验收单元，不新增产品界面或业务能力。实现者只交付测试代码与合成离线测试，不访问本地私有runtime或实际账号；经独立评审后由控制者执行真实浏览器写入。

**Files:** 新建`playwright.config.ts`、`e2e/fixtures/local-environment.ts`、`e2e/fixtures/operation-journal.ts`、`e2e/fixtures/identity-setup.ts`、`e2e/reporters/safe-reporter.ts`、`e2e/tests/task9-harness.spec.ts`、`e2e/tests/task9-identity-entry.spec.ts`、`e2e/README.md`。修改根`package.json`／`package-lock.json`，仅加入锁定`@playwright/test=1.63.0`及`test:e2e:task9=playwright test --config playwright.config.ts`。现有应用依赖版本不得漂移；不修改生产Java／SPA／本地部署工具。既有本地runner已部署真实Keycloak、独立数据库、API、SPA、Worker，本单元不另建compose服务或第二套身份系统；原9.6的compose交付项在本地路径由该已验收runner承担，CI适配仍由Task10处理，不造空compose文件。

**固定资料与路径：** 复用当前来源与四个已创建IdP账号`task9-local-intake/supervisor/contact/delegate`；对应显示名为“本地合成受理／本地合成主管／本地合成首联／本地合成代办”。在准确原ROOT下仅建立`LOCAL_ACCEPTANCE`组织（“本地合成验收组织”），四个任职依次为`INTAKE_OPERATOR`、`ROUTING_SUPERVISOR`、`CONTACT_OPERATOR`、`CONTACT_OPERATOR`。受理DIRECT权限为`LEAD_CAPTURE`、`LEAD_INGRESS_RESOLVE`、`LEAD_INGRESS_COMPLETE`、`SOURCE_INTAKE_REQUEST_ACK`；主管为`LEAD_ASSIGN`、`LEAD_ROUTING_DECIDE`、`LEAD_VALIDITY_REVIEW`，scope为原ROOT。首联和代办任职暂不授业务Grant；不得提前授`SALES_CONTACT_OWNER`破坏下一单元零销售Owner路由场景。组织、Principal、任职、Grant一律由真实管理页面提交，不能SQL插入或静态注册HUMAN。

- [ ] RED：离线安全测试先验证固定Origin／issuer／当前制品与精确浏览器版本、无显式本轮标志则拒绝、受保护目录／链接／操作清单冲突则拒绝、日志禁止秘密、未决写入不能继续另一条写入。使用临时合成目录与非秘密假值，不读真实runtime，不冒充真实身份测试。
- [ ] `local-environment.ts`只接受既有本地固定地址及当前已核验配置，真实运行要求进程环境`TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY`。在读取私有资料前调用现有`RuntimeBoundary.protect()`完成Git忽略／ACL／无链接检查；不新增产品配置入口。读取原`browser-credentials.json`与已完成`task9-test-account-operation.json`／`task9-browser-credentials.json`只在执行阶段且只入内存。逐一核对四个固定账号及准确原操作provider ID，不采用同名未知账号。检查当前release文件、镜像锁、浏览器实际版本；同一运行期间release／API进程身份变化则停，不自动重启服务。
- [ ] `operation-journal.ts`仅保存受保护的测试操作证据：runId、step、commandId（真实`Idempotency-Key`）、HTTP方法／固定路径、bodySha256、原actorScopeKey、时间、状态及已确认resultFact引用。不得写入password、token、providerUserSelector、原始subject/HMAC、完整请求正文或auth storageState。通过真实浏览器的请求观察／拦截，在POST/PATCH发往本机前保存准确原请求标识；记录失败则中止派发。响应未知时保留原commandId并停止，不新建key、不根据404推断未执行。恢复只查原回执和准确已登记事实，不按显示名收养既有业务事实，也不自动重发缺正文的写入。此清单是本地测试证据，不改SPA四字段恢复标记合同。
- [ ] `safe-reporter.ts`仅输出固定测试ID／阶段／状态／退出结果和无秘密证据路径。禁用trace、HAR、video、storageState及自动失败截图；凭据敏感操作失败不得透传可能包含fill参数或callback code的Playwright原始错误。自定义报告中的错误为闭合阶段码；不会将失败吞掉或改为成功。截图仅在已验证非凭据页面显式采集，原材料不进入Git或公开报告。
- [ ] `identity-setup.ts`使用真实Keycloak托管登录及生产SPA，不Mock SELF、Token、管理接口或响应。角色／字段／按钮必须依据已有页面与合同，不改冻结UI适应测试。先验证原管理员管理可进入但无销售卡、原unmapped账号拒绝；四个账号各自验证未映射状态，随后管理员在ADM-01完整用户名精确查找并绑定准确候选。记录实际CREATE命令和Receipt；再登录验证已映射但无任职提示。创建组织及四任职后，验证无业务授权不等于有工作台资格；再授上述七项DIRECT权限，验证受理／主管取得准确本人任职与有效零卡状态、首联／代办仍无业务权限。全过程不改逐人部署注册、不重启API，核对相同API进程启动标识。
- [ ] 真实spec逐段测试，编号使用`T9-L01-entry`、`T9-L03-unmapped`、`T9-L04-qualification-stages`、`T9-I02-exact-directory-binding`、`T9-I05-appointment-no-implicit-grant`、`T9-I06-minimum-business-grants`、`T9-I13-dynamic-entry`等明确子场景，不能将这些子集叫整个ID通过。全部实际步骤均有断言；不放置未实现ID的空测试／skip，不用serial失败后的自动skip伪装执行。前置阶段失败则后继阶段安全失败且无写入。仅当前已登记同运行且相同制品可显式继续；未完成清单不自动删除。
- [ ] 配置`workers:1`、`retries:0`，离线harness项目与显式批准的真实本地项目分开。以下纯安全测试接口由新模块定义，且在真实写入前使用同一校验：

```typescript
// local-environment.ts
export function requireLocalAcceptance(value: string | undefined): void;
// safe-reporter.ts
export function safeFailureCode(stage: string): string;

// task9-harness.spec.ts uses Playwright test/expect without requesting a browser.
test("offline explicit-local gate", () => {
  expect(() => requireLocalAcceptance(undefined)).toThrow();
  expect(() => requireLocalAcceptance("APPROVED_SYNTHETIC_ONLY")).not.toThrow();
  expect(() => requireLocalAcceptance("production")).toThrow();
  expect(safeFailureCode("password=do-not-log")).not.toContain("do-not-log");
});
```

- [ ] 同一最终环境证据包括应用buildSha（从真实二进制provenance继承，不冒充测试提交）、environmentDigest、执行时间、原测试case identity、退出码、status、reportPath。收集真实HTTP成功／拒绝、准确Receipt与resultFact关系及API进程不变；更深的Fact／Audit／Slot实库闭包由控制者具名只读核对，不授浏览器Owner凭据。用户尚未操作，U01～U03始终NOT_EXECUTED。
- [ ] 固定npm11.9.0／Node24.20.0及锁中Playwright完整integrity；运行离线harness RED/GREEN、类型／测试列表和受影响前端回归（根依赖变动需确认既有445项不退化），保留命令／输出／退出码。不得运行实际本地项目、自评后提交并交独立spec／quality评审；控制者随后执行已批准真实链并如实记录失败。这一单元不测十四管理生命周期全覆盖、IdP禁用／撤销、七卡／代办／等待恢复或人工签认；它们仍是后续既定9.6项目。

## Task 9.6: 真实用户全链路与总验收

### 当前执行授权（2026-09-09）

用户在Task9.5验收后明确批准：仅在现有本地隔离测试环境更新构建、启用Worker、建立专用合成测试账号及最小任职／权限，推进Task9.6整链。此授权不包含Git推送、生产环境、真实人员开户、真实业务资料、扩大权限、改变冻结设计或容量门槛。人工U01～U03须由指定使用者自行输入凭据并确认结果，自动化不能代签。用户现已指定由本人执行；待环境与专用测试责任卡就绪后提供步骤，未实际操作与签认前保持未执行。

补充授权：用户随后明确批准仅本轮本地测试短暂停止Keycloak，按官方恢复流程建立临时管理账号，仅创建本轮专用测试账号，完成后立即移除临时管理权限并验证。原realm／数据／密钥保留，目录client继续只有只读能力，不向API或Worker交付管理凭据。实施前固定目标用户名、缺失／已存在／部分成功的核对规则和清理失败处置；临时账号不能变成常驻超级管理员。官方操作约束见[Keycloak管理恢复](https://www.keycloak.org/server/bootstrap-admin-recovery)，恢复命令使用原数据库配置且所有本地Keycloak节点先停止。

执行起点`c3083de`。先核验原bootstrap闭包和运行中资源，再以可核验、可回退的显式本地部署步骤更新同一API／SPA制品；不得借重启重建数据库、替换原manifest或轮换原密钥。保持现有Origin／issuer／端口／TLS、13 Schema及52＋2表，目标HUMAN事实只能经Keycloak与受控管理路径建立，责任只能经实际业务命令产生。现有`resume`拒绝Jar漂移的安全门不删除；受控升级须有明确独立操作和验证。

后续具名补充授权：用户已批准仅本地合成测试增加一个固定AUTOMATIC来源，通过受控配置发布，原`LOCAL_SYNTHETIC` MANUAL来源／业务规则／数据库结构保持。用户亦已批准仅`task9-local-intake`、`task9-local-supervisor`、`task9-local-contact`、`task9-local-delegate`四个新IdP账号的禁用／恢复及管理端会话撤销，测试结束后移除临时管理权限；不涉及原管理员、真实账号或密码重置。此为七卡路径及登录安全验收所需，不扩展产品来源管理、IdP在线管理或SERVICE管理功能。

后续执行按可独立验收单元细分：本地制品／Worker装配，真实管理建档和资格链，七卡与故障／代办恢复整链，最终同构建及人工UAT。此为原9.6内部顺序，不扩展业务功能；每单元先失败测试、最小实现、定向验证、独立评审。下列首次本地登录检查为历史证据，不混作当前整链完成。

### 本地登录联通先行检查（2026-09-09用户批准）

用户要求先在本地部署并验证登录，且明确批准只在Windows当前用户信任本次开发CA。此为既定9.6登录子集的先行检查，不提前实施9.5页面或勾选全部9.6。保持生产SPA、API生产配置装配、真实Keycloak和独立身份／业务PostgreSQL；合成账号不冒充人工UAT。全部端口仅loopback，固定SPA`https://localhost:19444`、issuer`https://localhost:19443/realms/local-r1`、SPA client`local-r1-spa`、API audience/introspection client`local-r1-api`、只读目录client`local-r1-directory`；冲突则停止改端口前记录，不静默换issuer。API内端口19445，准确TLS反代；两个数据库分别独立存储，原表／迁移不改。

- [x] 复核锁定制品与官方安全公告、隔离资源、准确端口；生成短期本地证书并记录当前用户信任指纹／移除方法，不关闭TLS校验。
- [x] 本地秘密只在忽略且ACL受控目录生成持久文件，重启不轮换HMAC／候选密钥；启动锁定数据库／Keycloak，填充原realm模板，仅合成账号，注册精确回跳和Origin。
- [x] 启动同一API生产装配及固定构建SPA。生产启动要求ACTIVE租户，故先使用既有离线bootstrap dry-run并核对后初始化本地合成管理员，再用独立未映射账号验证安全拒绝。仅为满足既有启动校验建立最小合成SERVICE任职与来源绑定，不授予SERVICE业务权限，不直接写HUMAN事实，不插入责任卡。没有业务资格时明确提示。
- [x] 实际浏览器验证登录→Code/PKCE→准确回跳→SELF身份状态、刷新／退出／退出后无旧卡；不在日志／截图输出密码、Token、subject或密钥。
- [x] 保存本地复现／停止／证书移除说明，独立检查配置和验收证据；七卡业务／管理全链／真实使用者UAT仍待后续。

本地工具允许新增`deploy/local-login/`内具名单用途启动／检查脚本与说明；实际文件／密钥在`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/`忽略目录，不能进入Git。若发现生产实现缺陷，先报告并按最小回归修复，不采用测试Bean／Mock SELF／HUMAN registration绕过真实装配。

本地启动补充：宿主Java进程通过`127.0.0.1:19446`访问独立业务数据库，TLS verify-full，先检查端口空闲；身份数据库不发布宿主端口。最小SERVICE基础记录和管理员bootstrap均须先核对脱敏目标清单，再执行到本次隔离数据库；不放宽生产配置必填项。

本轮新增后续核对门：已有bootstrap验证器按整个Tenant计数检查首次快照，新增必要SERVICE后返回`BOOTSTRAP_ORIGINAL_STATE_CONFLICT`，虽原管理员及四授权/回执闭包的具名只读核对仍完整。保留原manifest与密钥，不修补/重建；扩大身份管理或声明恢复验收前须单独明确并验证扩展后的原结果核对规则。此次不修改该既有后端规则。

上述为本地登录首次检查的历史观察；该后续门现已通过本计划9.6a用户批准的窄修复关闭。完整9.6其余门及最终同构建验收仍未关闭。

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

上述全量命令属于最终验收入口，不表示已全部执行；当前部署及各分层证据以上方执行更新与本地整链进度为准。本文不是任何生产账号／权限写入的执行凭据；Task9.6真实整链与人工UAT仍有各自门禁。

## Task 9.6f: 管理命令成功响应的最小合同修复

本单元解决9.6e源代码联通检查发现的既有缺陷，不修改冻结设计。9.6e代码门完成后串行实施；9.6e真实写入依赖本修复部署完成。用户既定的完整功能修复与本地更新范围不扩大。

**Files:** 仅修改`backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/IdentityCommandRuntime.java`与`backend/src/test/java/io/github/windyzhu3/ontologylaw/api/IdentityAdminHttpIT.java`。不修改OpenAPI／前端／数据库迁移／生产依赖／管理权限；若证明还需其他生产文件，先向控制者提供准确原因，不自行扩散。

**Interfaces:** 消费冻结OpenAPI的`ReceiptLocation`（`contracts/openapi/ontology-law-api.yaml:3417`）：`^/api/v1/commands/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/receipt$`。四个CREATE和十个更新命令成功响应的Location均应为原`commandId`回执，不是`receiptId`或resultFact资源地址。保留`Result`类型、201/200、ETag、no-store、Receipt投影及错误／冲突／重放语义。

- [ ] RED：在真实隔离Keycloak＋PostgreSQL＋HTTP的`IdentityAdminHttpIT`中，给现有`write`成功路径增加准确Location与body.commandId对应断言，覆盖现有十四操作；添加成功创建组织及同key／同body重放的定向测试。断言首次和重放Location相同、body回执相同、重放Fact／Slot／Receipt／命令Audit无新增；使用原测试自己的独立数据库，不读或写本地验收runtime。

```java
assertEquals("/api/v1/commands/" + headers.get("Idempotency-Key") + "/receipt",
        response.headers().firstValue("Location").orElseThrow());
assertEquals(headers.get("Idempotency-Key"), json(response.body()).path("commandId").asString());
```

- [ ] 运行新增定向HTTP测试并记录真实期望失败（旧代码返回`/api/v1/admin/identity/organizations/{uuid}`），不能以编译错误代替行为RED。固定工具链执行`mvnw.cmd -B -f backend/pom.xml -Pit -Dit.test=IdentityAdminHttpIT#success_and_replay_keep_original_receipt_location test-compile failsafe:integration-test failsafe:verify`，保留输出／退出码；不执行package、不改变运行中API／Worker。
- [ ] 最小修复`IdentityCommandRuntime.result`中Location构造，成功有resultFact时指向原命令回执，其余处理不变：

```java
String location = fact == null ? null : "/api/v1/commands/" + e.commandId() + "/receipt";
```

- [ ] 修正现有`IdentityAdminHttpIT.factId`不再从Location尾部提取Fact UUID。仅在隔离测试自身的数据库，以response.body.commandId与准确测试Tenant读取原Slot→Receipt的result_fact_id，核对receiptId／事实类型／revision与对应实际Receipt投影；不得将不透明`factRef`当UUID、按显示名取第一条或通过新增生产GET接口方便测试。该只读辅助不插入目标身份事实，现有十四真实HTTP命令路径保持。
- [ ] GREEN：运行完整`IdentityAdminHttpIT`一次覆盖十四命令、准确头、重放、错误和CAS；运行后端单元`mvnw.cmd -B -f backend/pom.xml test`及既有前端`identityApi.test.ts`受影响合同回归，记录实际数量／退出码与原工具警告，不冒充完整Task9／全部IT。自评、提交这两份文件，由控制者独立spec／quality评审；无推送、无真实runtime操作。
- [ ] 控制者通过现有已评审制品发布流程更新本地API／SPA／Worker同包，保留AUTO及原MANUAL配置、原SERVICE绑定与已存在全部身份／原引导事实、密钥和回退包。固定新的业务buildSha与环境摘要后执行9.6e真实建档；不能继续将04bd695旧二进制说成包含本修复，也不能由测试劫持响应头伪装修好。
