# Task9 Real User Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将原 Task9 扩展为可受控开户、真实登录、取得本人责任卡并完成提交/恢复的生产用户入口。

**Architecture:** 使用用户已确认的自托管 Keycloak/OIDC 管理凭据与登录会话，业务系统继续以 Identity Fact、Appointment、直接 Grant 与当前 DENY 裁定权限。一个 SPA、一个业务 OpenAPI、一个 `api|worker` Jar；新增 Identity 管理与 self-context 的具名接口，复用现有四张身份业务表和审计/回执机制。

**Tech Stack:** 仓库锁定 Java 25、PostgreSQL 18、Spring Boot/jOOQ、React/TypeScript、Node 24.20.0、npm 11.9.0；新增 Keycloak、OIDC adapter 和浏览器测试依赖必须在 Task9.1 固定版本/镜像 digest，不能用浮动 latest。不顺带升级既有依赖。

**Spec:** [Task9 扩展设计](../specs/2026-09-08-task9-real-user-access-design.md)。[验收矩阵](../../acceptance/2026-09-08-task9-real-user-access-acceptance.md)是本计划每个交付单元的共同验收输入。

**Status:** DRAFT。扩大范围和 Keycloak 方向已由用户确认；详细设计须书面确认后才允许派发 Task9.1。当前没有执行下列新增任务，不将本计划当成批准生产权限或活动合同。

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
| Task9.2 | Keycloak 环境、可信 HUMAN 动态映射、self context 与 bootstrap | 9.1 | T9-L02～05、I01、I13、C04 |
| Task9.3 | 四类 Identity 管理命令、查询、权限和事务 | 9.2 | T9-I02～12 的 HTTP/实库部分 |
| Task9.4 | SPA 登录、会话、任职与安全恢复接线 | 9.2；管理导航资格依赖 9.3 | T9-L01～12 |
| Task9.5 | ADM-01～04 页面、完整工作台状态、统一视觉 | 9.3、9.4 | T9-I 页面部分、T9-W02～12 |
| Task9.6 | 真实全链路、七类卡与真实使用者 UAT | 9.1～9.5 | 全部必需 ID |

9.3 与 9.4 只能在 9.2 的共享 DTO/接口冻结后并行；共享 Runtime/认证变更串行。每单元记录自己的 BASE，先 RED、再 GREEN、独立评审后单独提交。不能把“页面存在”作为 9.3 后端完成证明。

## Task9.1：受控合同后继与精确接口

**Files:**

- Create: `docs/adr/ADR-0014-task9-real-user-access.md`、`docs/contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md`。
- Modify: `docs/baseline/CURRENT-MVP-BASELINE.md`、`docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md`、`R1-WORKBENCH-PRESENTATION-CONTRACT.md`、`R1-COMMAND-POLICY-EVENT-CONTRACT.md`、`database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`。
- Modify: `contracts/openapi/ontology-law-api.yaml`、`scripts/baseline/verify_baseline.py`、相关 `scripts/baseline/tests/`、`scripts/verify_topology.py`、`tests/test_topology.py`、`backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java`。
- Regenerate: `apps/workbench/src/generated/api/schema.d.ts`；禁止手改生成文件。
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

- [ ] 为原 inventory/排除身份管理条款、遗漏 terminal NOT_FOUND、self-query 冒用业务 Actor、管理请求可选 Tenant、无任职伪 Actor、权限扩大写 mutation tests；观察 RED。
- [ ] 创建 ADR-0014，逐条具名替代排除条款、HUMAN 逐人注册、Token rotation 与恢复持久线索边界；区分外部 Keycloak 拓扑和业务表数。
- [ ] 冻结全部 DTO 字段/required/长度/条件规则、上述 operation、命令码、权限 allowlist、Identity ETag、Subject/Receipt/拒绝/重试/delta、管理披露及原 Actor 回执授权，不能使用任意表名或通用 JSON command。
- [ ] 冻结 offline bootstrap 的准确清单、初始 delta、SYSTEM 例外、幂等与关闭条件；核查数据库能力，必要时另附最小前向 GRANT 迁移，不修改原迁移。
- [ ] 登记会话密码 policy、TLS、Origin、CSP、introspection 2 秒超时、5/30/480 分钟参数、恢复标记字段与 24 小时上限；验证固定供应链版本，不部署浮动镜像。
- [ ] 正常生成类型并执行合同/拓扑/OpenAPI 检查，记录旧业务 DTO/事件保持的精确对比；通过后才移除前端 `PublicReceipt` 临时兼容。
- [ ] 独立审阅完整合同范围，提交 `feat(contract): activate Task9 real user access`。

静态验收用例 `test_task9_inventory` 必须读取真实 OpenAPI 的 paths，逐一枚举 HTTP method operation，并按 operation 的 security 分类，断言实际 37/32/5、21 个新增 operationId 唯一且与上表完全一致，再读取 `TerminalRejectionCode` 断言包含 NOT_FOUND。另分别删除一个 operation、改变一次 security、加入任意 Tenant 字段、移除 NOT_FOUND 运行拒绝变异；不得把期望计数写成输入常量冒充实际解析结果。

## Task9.2：Keycloak、动态映射、context 与引导

**Files:**

- Create: `deploy/identity/compose.yaml`、`deploy/identity/realm-template.json`；模板不含用户密码或 client secret。
- Create Java: `identity/HumanIdentityReader.java`、`identity/internal/persistence/JooqHumanIdentityReader.java`、`identity/IdentityBootstrapService.java`、`api/security/HumanCredentialVerifier.java`、`api/SessionContextController.java`、`execution/IdentitySelfReadRuntime.java`。
- Modify Java: `api/security/ActorContextResolver.java`、`api/security/R1SecurityConfiguration.java`、`api/R1ApiDeployment.java`、`identity/ExternalSubjectProtection.java`、`audit/AuditAppender.java` 及其 Owner 内部实现。
- Tests Java: `api/HumanLoginMappingIT.java`、`api/SessionContextHttpIT.java`、`identity/IdentityBootstrapIT.java`、原 `api/R1ReceiptIdentityHttpIT.java` 与角色隔离测试。

Java 路径统一以 `backend/src/main/java/io/github/windyzhu3/ontologylaw/` 为前缀，测试以 `backend/src/test/java/io/github/windyzhu3/ontologylaw/` 为前缀；SQL 只在 Owner 的 internal.persistence 中。

**Consumes:** 受信 issuer/audience/provider→Tenant 配置、IdP 活动性复核、原 Subject HMAC、现有身份事实。

**Produces:** `VerifiedHumanIdentity`（服务端 tenantId/principalId/provider，不含任职权限）；`HumanIdentityReader` 的唯一映射与本人任职读取；经确认的 `SessionContext` DTO；已明确选择任职才产生现有非空 `AuthorizationService.Actor`。不得给原 Actor 构造器塞 null Appointment。

- [ ] 编写 L02～L05/I01/I13 的真实 IdP＋DB 失败用例；先证明当前逐人 registration 无法接入新建用户。
- [ ] 搭建锁定身份环境，以受限客户端验证 JWT＋在线活动性；错 issuer、audience、过期、撤销、断网都失败关闭，SERVICE 路径不放宽。
- [ ] 实现 trusted Tenant 内 provider-subject HMAC 动态 HUMAN 映射、本人 context 的先审计后披露、多任职选择与每次请求归属复验。
- [ ] 实现一次性 bootstrap，先 dry-run 展示无秘密的创建集合，只有明确离线执行才写；正向、同 manifest 重放、部分存在和冲突各自有准确断言。
- [ ] 运行定向 IT、ArchitectureTest 与 Task8 安全/角色回归，检查没有 Worker 用户管理 Bean/权限，独立评审后提交。

## Task9.3：Identity 管理后端

**Files:**

- Create Java: `identity/IdentityCommands.java`、`identity/IdentityAdminReader.java`、`identity/IdentityProviderDirectory.java`、`identity/internal/persistence/JooqIdentityRepository.java`、`execution/IdentityCommandRuntime.java`、`execution/IdentityAdminReadRuntime.java`、`api/IdentityAdminController.java`、`responsibility/IdentityDependencyReader.java` 及其 Owner 内部实现。
- Modify Java: `execution/CommandEnvelope.java`、命令 store/receipt policy/metadata 的现有静态注册、`audit/AuditAppender.java`、API 装配；不复制普通 R1 command engine。
- Tests Java: `identity/PrincipalCommandsIT.java`、`identity/OrganizationCommandsIT.java`、`identity/AppointmentCommandsIT.java`、`identity/AuthorityGrantCommandsIT.java`、`identity/IdentityMutationConcurrencyIT.java`、`api/IdentityAdminHttpIT.java`。

**Consumes:** 9.1 精确 14 mutation 合同、9.2 Actor；**Produces:** `IdentityCommands.handlers()` 静态集合、受 scope 限制的查询、准确 Identity Fact Receipt、原 Actor 当前权限下的恢复。

- [ ] 编写 I02～I12 的实库/HTTP RED：创建、CAS、状态机、冻结字段、跨 scope、自授权、开放责任依赖、最后管理员、回执重放与注入失败。
- [ ] 通过 Owner 窄口实现四类事实与受信 IdP 账号目录读取；落实 Principal/IdP 候选与新任职用户候选的根 scope 边界，不能从局部任职扩大到 Tenant 级用户权限。只有密码/账号凭据在 Keycloak，不在本系统创建密码 handler。
- [ ] 实现具名身份排他路径、依赖读取与先审计后披露，不取得反序业务锁，不新增业务事件/投影路由。
- [ ] 扩展回执元数据与授权 resolver 以处理 Identity Subject；直接查回执也必须当前管理授权，普通业务原 Actor 规则不退化。
- [ ] 通过精确 delta、双连接锁序、跨 Tenant、撤权与技术回滚测试，独立评审后提交。

## Task9.4：SPA 登录、会话与恢复

**Files:**

- Create: `apps/workbench/src/features/session/SessionProvider.tsx`、`LoginPage.tsx`、`AppointmentChooser.tsx`、`sessionController.ts`、`recoveryMarker.ts` 及同名测试。
- Modify: `apps/workbench/src/main.tsx`、`App.tsx`、`lib/api.ts`、`features/workcard/useCurrentCard.ts`、`recovery.test.tsx`、`package.json` 与根锁文件。

**Interfaces:** session adapter 提供稳定 `identityEpoch`、`actorScopeKey`、`getValidAccessToken(): Promise<string>`、`login()`、`logout()`、`selectAppointment(id)`；续期不改变 identityEpoch，变换 Actor 必须改变。原 `OriginalWrite` 只存业务请求和 precondition，不保存 Bearer；transport 每次取有效凭据。

- [ ] 写 L01/L06～L12 DOM/transport RED，包括先输入 dirty 文本再 token rotation、POST 结果未知再到期重登、跨 scope 迟到响应、标记存储失败不写入。
- [ ] 实现 PKCE 回跳、真实本人 context、多任职选择、退出/跨标签信号和安全错误；不以 Mock session 进入 main。
- [ ] 实现同 scope 单飞续期与稳定 epoch，普通 401/权限失效按设计清屏；现有 Task/Draft/Workbench 三类 ETag 不混用。
- [ ] 实现四字段恢复标记的严格 schema/大小/时限及同 scope 查询；未决时全 SPA 阻止新写请求覆盖标记，只有准确终态或合同明确未提交的响应才按键策略清除/替换。丢失正文后禁用重放而非重建，scope key 跨同 Actor 重登/应用重启保持稳定。
- [ ] 通过原 75 项加新增前端测试、typecheck/build，独立评审；登录及任职选择视觉须先经用户确认，再将 UI 定稿提交。

关键新增用例必须观察公共行为：在首联页面向“结果说明”输入“尚未保存的输入”，通过受控 OIDC 测试 adapter 实际完成一次同身份凭据更新，不重新挂载 App；等待续期完成后断言文本仍在、未保存状态仍在、“记录联系结果”仍禁用且没有 POST。随后保存候选并确认一次，断言 HTTP 携带新 Bearer、原正确任职且只产生一次业务写入。只断言 identityEpoch/private ref 或省略触发续期事件不能通过 T9-L07。

## Task9.5：管理页面与完整状态提示

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

## Task9.6：真实用户全链路与总验收

**Files:**

- Create: `e2e/compose.yaml`、`e2e/fixtures/identity-setup.ts`、`e2e/fixtures/r1-business-setup.ts`、`e2e/tests/task9-login-session.spec.ts`、`task9-identity-admin.spec.ts`、`task9-workbench-states.spec.ts`、`task9-seven-workcards.spec.ts`、`playwright.config.ts`。
- Modify: 根 `package.json` / `package-lock.json`，增加精确 `test:e2e:task9` 脚本及锁定浏览器依赖。
- Create: `docs/acceptance/task9-real-user-uat-template.md`，不把空模板当实际 UAT 结果。
- Update: 扩展验收矩阵、本轮进度报告、设计 QA；只在证据合格后更新对应交付状态。

- [ ] 为全部 T9-C/L/I/W 自动项建立命名测试并先保存缺能力 RED，不使用 skip/空测试绕过。
- [ ] 通过真实 IdP＋管理接口创建目标身份、组织、任职、授权；从真实 capture/分配生成业务责任，逐类七卡操作并断言数据库 Fact/Receipt/Task/Wait/Event/Audit。
- [ ] 实测 credential rotation、退出/撤销、HTTP/CAS/网络故障、恢复标记、跨 Tenant、四管理状态机和实际 Worker 等待恢复。
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

当前停点：详细设计审阅。本文已经把用户新增要求加入原 Task9 范围、分段和验收，但没有批准任何真实用户/权限变更，也没有自动开始上述实施。
