# R2 T01 负责人异常闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将商机负责人异常从有界发现、持久化、授权查询接到主管交接或协调处置，并使合格接收人的待办可继续执行。

**Architecture:** 保留 Opportunity 与来源 Assignment 的冻结 Owner；新增具名负责人异常事实、交接事实及有效责任解析口，通过现有 CommandRuntime 原子写入与审计回执交付。Responsibility 独占任务交接变更；Worker 只发现和发送正式命令，技术检查点不充当异常台账，不引入通用流程引擎或隐式权限授予。

**Tech Stack:** Java 25、Spring Boot 4.1.1、jOOQ 3.21.7、Flyway 13.4.0、PostgreSQL、Python schema contract generator、React 19.2.8、TypeScript 7.0.2、Vitest。

**Spec:** `docs/superpowers/plans/2026-09-13-r2-complete-sales-mvp-plan.md`；`docs/contracts/r2-initial-opportunity-responsibility-v1.md`；`docs/contracts/r2-opportunity-{activation-command,discovery,checkpoint,progress,followup-recovery}-v1.md`；`docs/design/r2-sales-mvp/review/2026-09-14-e/README.md`。

## Global Constraints

- R2-Scope: APPROVED；R1-Acceptance: PAUSED。范围批准不是代码完成或发布验收。
- E/F 已获用户批准；2026-09-15 用户回复确认，F覆盖的T01状态可实施。历史D未覆盖内容不自动获批，范围不变。
- 实现已按批准范围推进；下面逐项要求的完成状态以文末矩阵和真实日志为准，生产启用仍为独立门禁。
- 不改 Opportunity.Owner 或原 Assignment；不伪造完成、不重开旧终态任务、不提前恢复 WAITING、不刷新已有期限。
- 首次真实承接使用既定业务日历与承接时刻；新人不继承旧人草稿。
- 所有选择器、锁、外键、游标、幂等键均含租户；当前任职与当前授权复验，不以姓名、角色标签或前端按钮代替授权。
- 不自动创建权限授予；新的具名权限须经合同注册和已有受控授权流程配置。运营权限不等于主管处置权限。

## 已核对的落点与需要补齐的边界

代码路径以下均相对仓库根目录；Java 主路径缩写 `J=backend/src/main/java/io/github/windyzhu3/ontologylaw`，测试路径 `T=backend/src/test/java/io/github/windyzhu3/ontologylaw`。

| 现有落点 | 可复用能力 | T01 不能直接沿用的假设 |
|---|---|---|
| `J/api/R2OpportunityDiscoveryService.java`、`J/opportunity/internal/persistence/JooqOpportunityActivationCandidates.java` | QUERY 事务、1–100 扫描记录、时间+ID 翻页、五分钟 HMAC 游标、稳定命令键 | INITIAL 用有效 ownerAppointments 过滤；失效 Owner 在查询之前就被排除。DUE 同样按有权 Owner 扫描；不能在返回候选上补一段异常判断解决漏发现 |
| `J/opportunity/internal/persistence/JooqOpportunityTaskActivationService.java`、`J/api/R2OpportunityActivationCommand.java` | 准确来源链、首次任务串行、所有状态去重、当前时间业务日历、正式审计/回执 | 激活仍读取冻结原 Owner；仅替换 UI 当前负责人无法使新负责人接续 |
| `J/responsibility/TaskFactory.java`、`J/responsibility/internal/persistence/JooqTaskRepository.java` | 专用初始与后继任务、WAITING 到期恢复、准确完成事实 | `createOpportunityFollowup` 前序必须 DONE；交接不能调用 complete 伪造进展，也不能使用普通 create 绕过初始任务唯一性 |
| `J/query/OpportunityWorkCardQuery.java`、`J/api/CurrentWorkCardSources.java` | 准确任务/草稿、ETag、源事实保护 | 工作卡明确比较 task.owner 与 opportunity.owner；交接后需具名有效责任解析，不能只新增交接命令 |
| `J/identity/R2OpportunityServiceScopeReader.java`、`R1AuthorityReader.java`、`AuthorizationIdentityReader.java` | 当前 SERVICE 任职直接授权、对象 DENY、身份状态 | 原人的授权不能成为主管发现该异常的先决条件；不可借原人的失效授权放宽主管或接收人的权限 |
| `J/execution/CommandRuntime.java` | 初始授权→业务 fence→根锁→命令键→业务写入→最终授权→提交后返回；同键重放 | 新 handler 必须保护原 resultFact 的历史版本和当前对象，不能让恢复接口绕开授权 |
| `J/worker/R2OpportunityTaskScheduler.java`、`R2OpportunityCheckpointCodec.java`、`J/execution/internal/persistence/JdbcR2OpportunityCheckpointPort.java` | 请求前持久化、未知结果原键原输入优先重放、会话锁、坏状态停止 | V890 仅 INITIAL/DUE，正文是技术状态，不能承载业务异常/主管处置或冒充恢复成功 |
| `database/schema-contract-52-plus-2/contract/evolutions/v890_r2_opportunity_checkpoint.py` | 后继迁移、列权限、修订守卫、生成式 DDL | 现有表数为55；新增业务表需具名 ADR/后继合同，不能改写 V001–V890 或把业务事实塞进 checkpoint |

## 具名扩展合同（实施前固定）

以下为 T01 的准确合同名称；当前实现与验收见文末矩阵，不表示已经向真实身份授予权限。

1. `opportunity.owner_exception`：异常身份、商机准确引用、被冻结责任人、可空任务/等待引用、原因码、首次/最近观察时间、当前修订、处置状态与准确解决事实。原因码限定 `OWNER_INACTIVE`、`OWNER_AUTHORITY_MISSING`、`OWNER_DENIED`、`SUPERVISOR_UNRESOLVED`、`SOURCE_INCONSISTENT`。同一商机/责任槽的一个异常周期只建一个活动异常；恢复后再次失效是新周期，旧历史保留。
2. `opportunity.owner_exception_disposition`：不可变人工决定；类别 `TRANSFER` 或 `COORDINATION`，原因与操作者、准确异常修订、协调复查时刻或接收任职、准确交接结果。协调只记录处置和复查，不冒充已解决，不授予权限、不创建销售 WAITING。
3. `opportunity.responsibility_handoff`：不可变责任链；商机、原有效责任引用、新任职、可空旧任务与新任务、原期限/等待引用、处置决定引用。每条消费准确前任引用，只允许唯一后继；冻结原业务 Owner 保持。
4. `OpportunityResponsibilityReader.current(Connection, UUID tenant, Subject opportunity)` 返回 `Responsibility(Subject basis, UUID appointmentId)`：无交接时 basis 为原商机，之后为当前交接事实。所有相关读取/写入一起改用这一口，不能局部放宽 Owner 等式。
5. `OpportunityOwnerExceptionService.observe(...)`、`dispose(...)` 为 Opportunity Owner 端口；Responsibility 新增 `handoffOpportunityTask(...)` 专用入口。服务不提交事务、不切能力角色、不写其他模块表；API 只组合公开端口。
6. 命令 `OBSERVE_OPPORTUNITY_OWNER_EXCEPTION`（SERVICE）、`TRANSFER_OPPORTUNITY_RESPONSIBILITY`（HUMAN）、`RECORD_OPPORTUNITY_OWNER_COORDINATION`（HUMAN）；独立 V1 幂等 scope，正文包含准确版本而非任意任务头。权限分别命名 `OPPORTUNITY_OWNER_EXCEPTION_DISCOVER`、`OPPORTUNITY_OWNER_EXCEPTION_READ`、`OPPORTUNITY_OWNER_EXCEPTION_RESOLVE`、`OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ`。这些是待注册合同，不得复用 R1 投影权限或自动 grant。

## Task 1：冻结业务与视觉合同，登记 schema 后继

进度（2026-09-15）：用户已确认 F，实施沿用 E/F 冻结母版。ADR-0017、V900、具名交接等待 R2_OPPORTUNITY_HANDOFF_WAIT_V1 与异常终态规则已登记并实现；实际数据库已验证迁移与生成。命令和权限已在代码合同中注册，未向真实身份授予权限，后台观察默认关闭。本文的原计划清单保留作为要求，当前完成状态以文末矩阵和验收报告为准。

**Files:** 创建 `docs/contracts/r2-opportunity-owner-exception-v1.md`、`docs/contracts/r2-opportunity-responsibility-handoff-v1.md`；后续按治理流程分配ADR编号（本轮不占用编号）；修改 `database/schema-contract-52-plus-2/R2-SCHEMA-SUCCESSOR.md`；补图引用 `docs/design/r2-sales-mvp/review/2026-09-15-f/`（图由设计任务负责）。

- [x] 合同草案已将上面的三个事实类型及当前责任解析规则写入合同，明确异常活动唯一键、再次发生新周期、协调未解决、来源不一致仅协调修复，禁止随意交接掩盖坏来源。
- [ ] 明确 OPEN 交接、未到期 WAITING 交接、首次尚无任务、已终态/已解决四种命令前提。旧任务以具名交接取消事实进入 CANCELLED，新任务带 handoff relation；不能写 DONE 或伪造 opportunity_progress。现有 CANCELLED 守卫不满足时仅由具名后继扩展，不旁路守卫。
- [ ] 固定主管范围解析与运营降级摘要：主管无法解析时持久化异常，运营仅得异常编号、原因码、时间、受限组织标识与修复指引，不含客户名、联系信息、进展/草稿正文或可转派人员列表。
- [ ] 对照 E/F 标明列表、详情、候选、确认、协调、只读、无候选、无主管运营、空列表、未知结果、过期/无权、360px 图的批准状态。未批准状态的页面实现门禁保持关闭，后端合同工作可继续。

**验收:** 每个状态均可指向业务合同和已确认 HF；明确新增事实使表总账的目标从55变为58，并列明原因，ADR 不声称部署已批准。

## Task 2：持久化事实与窄能力迁移

**Files:** 创建 `database/schema-contract-52-plus-2/contract/evolutions/v900_r2_owner_exception.py`、`database/schema-contract-52-plus-2/tests/test_r2_owner_exception.py`；修改 `contract/schema_contract.py`、`contract/reference_registry.py`、`contract/evolutions/__init__.py`（均位于同一 schema 目录）；由 generate.py 生成 V900 与清单；新增 `J/opportunity/OpportunityOwnerExceptionService.java`、`J/opportunity/internal/persistence/JooqOpportunityOwnerExceptionService.java`。

- [ ] 先写失败合同测试：三个表 tenant-first 外键、活动周期去重、不可变决定/交接、受控异常更新、修订上界、QUERY 窄读取、WORKER 无业务写入、旧迁移字节不变。
- [ ] 在静态字段合同定义三个事实；活动异常与前任交接使用数据库唯一约束兜底，并按商机锁串行所有受支持写入口。历史状态保存明确解决依据，不删除历史。
- [ ] 定义接口输入只含 tenant、准确 Subject、服务观察时刻或人工决定；任职有效性与当前权限必须数据库重读。观察重复只更新当前周期观察信息，不产生多个台账事项。
- [ ] 在 schema 目录执行 `python generate.py`、`python generate.py --check`、`python -m unittest discover -s tests -v`；执行仓库现有 jOOQ 生成流程，禁止手改生成代码。新增 `T/opportunity/OpportunityOwnerExceptionPersistenceIT.java` 实跑迁移及两个连接重复观察竞争。

**验收:** 空库与 V890 升级均为58表；重复/跨租户引用/不合法修订/未经授权角色写入失败；独立事务同时观察仅一个活动异常；回滚无半条事实。

## Task 3：包括失效 Owner 的有界发现与正式观察命令

**Files:** 创建 `J/opportunity/OpportunityOwnerExceptionCandidates.java`、`J/opportunity/internal/persistence/JooqOpportunityOwnerExceptionCandidates.java`、`J/api/R2OpportunityOwnerExceptionDiscoveryService.java`、`J/api/R2OpportunityOwnerExceptionCommand.java`；修改 `J/identity/R2OpportunityServiceScopeReader.java`，通过新的具名 scope 方法返回组织范围，不改变 INITIAL/DUE 含义；新增 `T/api/R2OpportunityOwnerExceptionDiscoveryIT.java`、`R2OpportunityOwnerExceptionCommandIT.java`。

- [ ] 写红测：失效人类任职、已撤销 owner 权限、对象 DENY、无主管、空组织范围、跨租户游标与被拒绝记录后的下一条均被准确处理。
- [ ] 异常扫描以服务授权组织范围和商机记录定位，不能由有效 ownerAppointments 建候选集；历史任职归属由 identity 具名读取口解析。Owner 不可解析时只在可证明服务组织范围内落异常，未知范围拒绝披露并返回受限技术诊断。
- [ ] 用观察时刻+ID 稳定 keyset，每次最多100扫描记录；下一游标取最后扫描行。HMAC 绑定 tenant/principal/appointment/kind/观察时刻并限时。按商机逐项授权与最新事实判断；不把只读查询偷偷变成写入。
- [ ] 观察命令重读当前原因，已恢复返回有审计的 NO_CHANGE；同键同正文恢复原结果，同键异正文冲突。resultFact 引用异常版本；历史与当前授权复验失败不泄漏。

**验收:** 有效负责人正常业务零误报；已失效 Owner 无遗漏；一页全拒绝仍推进；查询无写入；从发现到执行期间恢复/关闭/换责任时不落过时异常。

## Task 4：授权查询、候选与受限运营视图

**Files:** 创建 `J/api/R2OpportunityOwnerExceptionReadService.java`、`J/identity/OpportunityOwnerExceptionAuthorityReader.java`、`J/identity/internal/persistence/JooqOpportunityOwnerExceptionAuthorityReader.java`、`J/api/R2OpportunityOwnerExceptionApiDelegate.java`；修改 `contracts/openapi/ontology-law-api.yaml`；新增 `T/api/R2OpportunityOwnerExceptionHttpIT.java`。

- [ ] 定义列表、准确详情、接收候选与原命令回执恢复契约。主管必须在当前所选任职拥有当前对象与组织上的具名读取/处置权限；恢复结果同样授权。
- [ ] 候选只返回已拥有 SALES_OPPORTUNITY_OWNER 的有效 HUMAN 任职，检查商机、来源、任务与接收所需准确事实的 DENY；空候选明确无可转派人员，不显示授予权限捷径。
- [ ] 协调与转派分别返回 allowedActions 和准确前置版本；运营投影独立构建字段允许列表，不能先序列化完整 DTO 再隐藏。
- [ ] 测试身份更换、撤销、组织范围、对象 DENY、他人回执ID猜测、查询后候选失效、分页上限及运营响应递归无客户字段。仅受限摘要获得授权不授予完整事实读取能力。

**验收:** 隐藏按钮无法绕过服务端授权；运营无客户资料/原草稿/候选；同租户也不能越主管范围；当前权限撤销后回执恢复不暴露原结果。

## Task 5：原子交接与协调正式命令

**Files:** 创建 `J/opportunity/OpportunityResponsibilityReader.java`、`J/opportunity/internal/persistence/JooqOpportunityResponsibilityReader.java`、`J/api/R2OpportunityOwnerExceptionDispositionCommand.java`；修改 `J/responsibility/TaskFactory.java`、`J/responsibility/internal/persistence/JooqTaskRepository.java`；新增 `T/opportunity/OpportunityResponsibilityHandoffIT.java`、`T/api/R2OpportunityOwnerExceptionDispositionIT.java`。

- [ ] 先写双事务测试：两主管同时转不同接收人；转派与原人提交进展竞争；转派与到期恢复竞争；双击原键；提交后回包丢失；授权撤销等待锁。
- [ ] 在 CommandRuntime 现有 fence/identity/root/key 顺序下执行；锁商机后重读异常、当前责任、旧任务/等待与候选。交接对旧期望版本不接受自动追上；并发输家返回过期而不是覆盖赢家。
- [ ] 原子写 disposition、handoff、旧任务具名取消、新任务、异常解决事实、审计、回执/outbox。旧 OPEN 的原截止时刻保留给接续卡；旧 WAITING 保留原 resumeDue 与期限，新卡仍 WAITING，到期后才恢复。尚无初始任务时按现行业务日历从真实承接时刻起算 R2_BUSINESS_4H_V1。
- [ ] 新任务与旧任务用 handoff 关系区分跟进 predecessor；首次任务存在性规则显式识别交接链，不能为了建新卡绕过所有状态唯一性。只复用来源/业务事实，不读取/复制旧人草稿为新人草稿。
- [ ] 协调写不可变 disposition 与明确下一复查时间，异常保持未解决；不造销售任务完成/等待。原结果恢复仅重放原键/原输入；NO_CHANGE 也有准确回执，不把新选择塞入旧键。

**验收:** 两个接收人中至多一人得卡；旧人过期页面提交失败；失败全回滚；完整保留原 Owner/Assignment/期限/等待；新人草稿为空；旧历史可追溯且无伪造进展。

## Task 6：让现有推进、发现、工作卡实际使用有效责任

**Files:** 修改 `J/opportunity/internal/persistence/JooqOpportunityTaskActivationService.java`、`J/opportunity/internal/persistence/JooqOpportunityProgressService.java`、`J/api/R2OpportunityActivationCommand.java`、`J/api/R2OpportunityRecoveryCommand.java`、`J/api/R2OpportunityDiscoveryService.java`、`J/query/OpportunityWorkCardQuery.java`、`J/api/CurrentWorkCardSources.java`；连同这些调用链使用的 authorization/protection 文件以 `OpportunityResponsibilityReader.current` 为唯一接口；扩充 `T/opportunity/OpportunityProgressIT.java`、`OpportunityTaskActivationIT.java`、`OpportunityFollowupIT.java`、`T/api/R2OpportunityWorkcardIT.java`。

- [ ] 写交接后读卡→空草稿→保存→提交进展→生成后继→到期恢复的完整失败用例。
- [ ] 将原 Owner 的等式分为来源冻结验证与当前责任验证：来源仍验证原 Owner/Assignment，执行资格验证有效责任及 basis 版本。双方不可混为一谈；新的 basis 进入受保护源事实与 ETag。
- [ ] INITIAL/DUE 扫描改用有效责任查询口定位，保留其原来授权与结果类型；涉及新交接后继的 due 读取在 Responsibility 公开端口内实现，API 不跨模块读内部表。
- [ ] 验证旧负责人无操作入口，新人无权限读取旧草稿；历史原回执按当前权限和准确历史结果复验，不能把历史事实改成新人事实。

**验收:** 交接不只是台账成功；新人能完成真实推进并继续后继，原人不能处理旧卡；未到期交接卡仍不能提交；无交接业务回归保持。

## Task 7：可靠调度与观察检查点

**Files:** 修改 `J/worker/R2OpportunityTaskScheduler.java`、`J/worker/R2OpportunityCheckpointCodec.java`、`J/execution/R2OpportunityCheckpointPort.java`、`J/execution/internal/persistence/JdbcR2OpportunityCheckpointPort.java`、具名内部 transport/client；在新后继迁移中追加 checkpoint `OWNER_EXCEPTION` 类型（不能改 V890）；扩充 `T/worker/R2OpportunityCheckpointTest.java`、`R2OpportunityTaskSchedulerTest.java`、`T/execution/R2OpportunityCheckpointIT.java`。

- [ ] 设计 codec 后继版本，旧 INITIAL/DUE 正文仍可读；异常页与原候选请求字段有界，禁止客户文本进入检查点。固定原命令键和输入存入检查点后才发 HTTP。
- [ ] 三种扫描各有准确会话锁，任意存储损坏/失锁停止派发；未知结果必须先恢复原观察命令，再推进页内位置，不能跳过失败候选。
- [ ] 验证观察成功回包丢失、页内位置提交丢失、进程重启、锁接管、跨身份拒绝、游标到期再扫描去重；协调到复查时再次验证，而不是按时间自动标已解决。
- [ ] 异常发现自身的生产接线属于 T01 必交付：完成具名内部路由、SERVICE 配置校验、持久化检查点、受控调度注册与启停诊断；不得推迟到 T02。T01 后端/界面/端到端证据齐备后才能启用该循环，实际启用单列发布门禁，不把开发测试等同生产已运行。

**验收:** 重启不丢异常、不重复活动异常/处置；Worker 无业务 SQL 写权限；技术失败显示技术状态，台账保留业务状态。

## Task 8：管理界面与未知结果恢复（依赖 HF 批准）

**Files:** 创建 `apps/workbench/src/features/ownerExceptions/OwnerExceptionPage.tsx`、`OwnerExceptionDetail.tsx`、`OwnerExceptionDisposition.tsx`、`OwnerExceptionPage.test.tsx`、`apps/workbench/src/lib/ownerExceptionTransport.ts`、`ownerExceptionTransport.test.ts`；接入现有应用路由与 E 母版样式；由 OpenAPI 生成 `apps/workbench/src/generated/api/schema.d.ts`。

- [ ] 先在 Vitest 编写主管列表/详情、候选选择、交接确认、协调、只读/运营摘要、无候选、空列表、过期刷新、网络结果未知的状态测试。
- [ ] 按已批准 E/F 画面实现；显示原责任与接收后的期限说明，转派前明确人工确认。列表/详情只消费服务器投影，不能客户端推断权限或把协调显示为已解决。
- [ ] 未知结果仅提供核对原结果；保存原命令ID和封闭请求所需的会话内恢复信息，切换用户后不得继续显示前用户响应。核对成功刷新准确异常与新待办；核对无权显示受限状态。
- [ ] 执行 `npm --prefix apps/workbench run test -- src/features/ownerExceptions src/lib/ownerExceptionTransport.test.ts`、`npm --prefix apps/workbench run typecheck`、`npm --prefix apps/workbench run build`。桌面与360px实拍，核对表格/按钮/对话框不溢出、键盘可达与反馈可读。

**验收:** 无客户端模拟成功；双击不双写；旧选择过期要求重新获取；未知结果不诱导新键再提交；新负责人进入实际工作卡并从空草稿开始。

## Task 9：闭环证据与门禁

**Files:** 创建 `docs/evidence/r2/t01-owner-exceptions-report.md`；修改 `docs/superpowers/plans/2026-09-14-r2-implementation-progress.md` 与新合同中的当前交付状态。

- [ ] 执行 schema 全检查及 backend Maven verify（按本机 Java25/Maven 启动方式），包括新增 IT；执行 workbench tests/typecheck/build。失败不记通过，保留命令和实际输出路径。
- [ ] 在真实数据库/HTTP/Worker/浏览器完成：历史无初卡且 Owner 失效→异常→主管转派→新人实际推进；WAITING 失效→交接→原到期恢复；主管失效→运营摘要→权限外部受控修复→主管处理；协调复查→仍异常/可解决；交接成功断网→原结果恢复。
- [ ] 记录两个租户与多个任职、对象 DENY、并发提交、重启恢复证据；核对数据库中的原 Assignment/Opportunity Owner 不变、旧任务状态/期限保留、新任务唯一、没有虚构进展和旧草稿复制。
- [ ] 更新范围矩阵：T01 每一项独立完成/阻塞与证据链接；R1 PAUSED 保留，R2 生产启用/发布验收单列，不以开发门禁代替。

**最终验收判据:** 发现包含失效 Owner，异常跨重启可见；有权主管能交接/协调，运营只见受限摘要；新责任实际可处理并连续生成/恢复待办；旧责任历史可信，重复/并发/未知结果不多建、不越权、不丢任务。

## 最大架构风险

1. **冻结 Owner 与有效责任的多处硬编码耦合。** 工作卡、激活、发现、恢复和授权若只改部分，新人会拿到卡却无法操作，或旧人仍能提交。任务5必须与任务6一起形成可验收闭环。
2. **任务交接不能复用现有“完成后继”语义。** 原链要求 DONE+progress，直接复用会伪造完成；直接改 Owner 又破坏冻结事实。具名 handoff 与取消依据、期限/WAITING 继承、初始唯一性都须在 schema 和 Responsibility 一起治理。
3. **现有发现过滤失效 Owner，检查点不能补救。** 仅给调度错误加界面会继续漏异常；必须以独立授权组织范围发现，并将业务台账与技术检查点分开，防止为看见异常而泄漏客户资料或扩权。



## 执行启动（2026-09-15）

F已确认，开始受治理Schema、异常领域持久化和Responsibility交接入口的生产实现。Task1-9完成状态必须以各项实际证据更新，不把并行启动写成整体交付。

## 2026-09-15 实施检查点

| 子任务 | 当前状态 |
|---|---|
| T01-01 合同与高保真 | E/F已确认；ADR0017及Schema后继已登记，运行未启用 |
| T01-02 持久化 | PostgreSQL18.6真实应用25个迁移；jOOQ27个POJO已生成；周期/回滚/准确历史专项11项通过 |
| T01-03 发现与观察 | 具名SERVICE发现/正式观察、真实audit hash验证证据已接入；有界游标、诊断与真实HTTP测试通过 |
| T01-04 授权管理读取 | 列表/详情/候选/独立运营DTO、具名审计及管理准入已实现；HTTP6项及真实Keycloak准入专项通过 |
| T01-05 处置命令 | 完成：正式命令6项通过；交接与进展/到期恢复两项真实竞态通过，唯一有效卡且无伪进展 |
| T01-06 责任接续 | 真实转交→读卡/空草稿→进展→等待→再次交接→原回执及DENY复验通过；原Owner/Assignment和期限保留 |
| T01-07 调度 | 完成：独立持久检查点/重启原请求恢复通过；真实jar/TLS/数据库组合启用、撤权降健康及正常停止通过，生产仍默认关闭 |
| T01-08 管理界面 | E/F布局及桌面/360px通过；真实React→授权HTTP转交成功并显示已解决，数据库闭环断言通过 |
| T01-09 整体验收 | 开发验收完成：197后端单测、623前端测试通过；全量921项IT扫描后修正旧断言，定向62项通过，当前合并证据105类/916项零失败；真实浏览器、Worker及精确发展门禁通过。原非零全量日志保留 |

证据：[T01实施及验收记录](../../evidence/r2/t01-owner-exceptions-report.md)。用户已重启Docker，数据库阻塞已解除。当前生产观察开关仍关闭，R1 PAUSED、R2 NOT_GRANTED保持；真实浏览器组件验收不冒充生产OAuth登录或发布验收。
