# R1 授权与事件合同收口设计

日期：2026-09-05。状态：APPROVED（用户已确认书面规格；MVP-2026-09-05.2由ADR-0007承接）。

基准提交：`5b21dbfc06e4d278f8b5097209e2fd76a8465c00`。

本文件的书面规格已获用户确认；新增语义由[ADR-0007](../../adr/ADR-0007-r1-command-policy-event-closure.md)、基线版本MVP-2026-09-05.2和[R1命令授权及事件合同](../../contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md)显式承接。活动权威仍以[当前基线](../../baseline/CURRENT-MVP-BASELINE.md)、ADR及实施合同为准。

## 1. 目标和交付边界

关闭 CAPTURE_LEAD、SAVE_ACTION_DRAFT、REOPEN_DUE_CONTACT_TASKS、REOPEN_DUE_ROUTING_REVIEW_TASKS 的专属授权描述和成功事件描述；完整定义 OpportunityOpened；修正 CONNECTED_VALID 的单 Event/Outbox 旧计数，建立可机器验证的 R1 分支事件集合。

交付是生产可复用的静态策略、事件合同及 CommandRuntime 校验，加上真实 PostgreSQL 的授权、原子写入和失败测试。测试 Handler 只放测试目录，不为了证明注册成功而发布空业务 Handler。业务 Owner Handler、API、Worker、Workbench 和浏览器业务验收继续按[原 R1 计划](../plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)的 Task 5–10 交付。

不将 ADM-01～07 全部纳入 R1 基础功能整体验收，不实现 R2/R3、管理 CRUD、认证产品配置平台或新的通用组件。合同收口完成不等于四类业务入口已可用，更不等于完整销售 MVP 完成。

## 2. 不可偏离的原始约束

| 约束 | 本轮保持方式 |
|---|---|
| 一个响应式 SPA、一份 OpenAPI、一个模块化单体 Jar | 不增加应用或部署制品；保留 api/worker 互斥角色 |
| 13 Schema、52 应用表加 2 技术表、52-plus-2-v1.1 | 不修改物理合同、manifest、字段合同和 V001–V850 字节 |
| jOOQ 为唯一业务持久化方式；Fact Owner 模块隔离 | 新 SQL 只在所属模块 internal.persistence；跨 Owner 使用具名接口 |
| 领域 Fact 为业务真源 | Draft、Receipt、Audit、Event、Outbox 不替代完成 Fact |
| 一张卡一个 Owner、一个主命令、一个完成 Fact 类型 | Draft 继承实际 Task 的权限和动作，不新增通用任务动作 |
| Task DONE/CANCELLED 永久终态 | recovery 只恢复准确 WAITING Task，不重开终态、不修改 Owner/SLA |
| AI 仅提取、草拟、解释 | 保存草稿不确认业务；R1 不接入 AI；不赋予 AI 主命令权限 |
| 租户、身份和可信时间由服务端确定 | 不新增调用方可指定的 Tenant、权限代码、组织范围或事件字段 |
| READ COMMITTED、最终持锁授权、单事务全写入 | 保留 ADR-0006 锁协议、savepoint、最终新鲜 clock_timestamp 和提交确认丢失语义 |
| 不建设通用平台 | 不增加动态权限 DSL、BPMN、Saga、Job、Kafka、Redis、EAV 或事件溯源平台 |
| 销售 MVP 终点仍为转案接受和稳定 MatterRef | 本轮仅交接 OpportunityOpened，不创建 R2 Task 或 Matter 表 |

依赖版本、13 个现行 HTTP operation、ETag、幂等 scope 的字段与摘要向量、错误公开形态保持不变。若实现确需改变这些约束，停止该变更并提出新的明确决策，不通过削弱验证器放行。

## 3. 授权设计

### 3.1 共同规则

静态命令策略由服务端 CommandType 决定；调用方不能选择 policy、authority code、scope organization、授权路径或 Grant ID。所有策略都验证真实 Tenant、Principal、Appointment、准确授权事实、组织范围及有效期；对象 DENY 优先。OBJECT-only ALLOW 不能替代这四类命令所需的直接权限或合法一跳委托。

业务 Owner 负责查询并证明真实 Task、Lead、Draft、来源策略与 secondary binding，授权模块负责身份/Grant/Scope 判定；不能仅因 Handler 提供了字符串就相信责任归属。通过具名、类型化的服务端绑定传入静态策略，不引入自由策略回调或 always-allow 开关。

初始授权、工作前复验和最终持 Tenant 身份共享锁的复验全部使用同连接。最终复验重新观察组织和授权，不缓存最初允许结果。重放和冲突同样重新验证当前访问权限，但不重新执行 Task 状态 CAS、Draft 可编辑性或 recovery 到期 eligibility。新命令业务拒绝及技术故障的持久化差额严格服从现行 pre-slot/post-slot 规则。

### 3.2 capture

- 静态策略为 `SOURCE_INTAKE_OWNER + LEAD_CAPTURE`，信封仍为 INTERNAL_ADMIN；该名称不赋予管理权限。
- 当前公共 capture 接受 HUMAN 的 DIRECT 或有效一跳 DELEGATED 路径。委托必须引用相同 LEAD_CAPTURE 原始 Grant 并缩小或保持范围；不能借用其他主命令权限。SERVICE、SYSTEM 和 CUSTOMER_GRANT 不作为此公共命令的旁路；未来 Provider 自动接入属于独立阶段的真实身份接入设计。
- 通过静态 `R1SourcePolicyRegistryV1[sourceAccountCode].sourceIntakeRootCode` 在当前 Tenant 解析准确 ACTIVE 组织。Grant 的组织范围必须覆盖该 root。来源不存在、组织无效或解析不唯一时失败关闭，不能回退到调用方组织或任意组织。
- Lead 创建前，以真实 `identity.organization_unit@revision` 作为授权/审计锚点，不创建虚构 Lead。现行 object_access_grant 不允许 organization_unit 类型，因此这个阶段是组织范围 Grant 判定，不宣称支持来源账号级对象 ALLOW/DENY。
- 同一 intake root 下的已注册来源账号共享此 capture 权限。需要账号级隔离时，应先调整来源组织划分或经新合同引入准确账号授权模型，不能靠来源字符串冒充受控对象。
- 来源自然键已存在以及返回已有 Receipt 时，必须额外检查准确已有 Lead 的 LEAD_CAPTURE 对象 DENY 和 Tenant 安全可见性；有 DENY 就不披露 Receipt/Fact。已有 Lead 访问检查不再借用 LEAD_INGRESS_COMPLETE 等无关权限。最终复验覆盖组织根、Grant 及已有 Lead 的当前拒绝规则。
- 组织锚点的原始 selector 作为本次审计请求依据保留；若组织 revision 在最终复验前变化，当前新命令失败关闭，不静默替换最初授权依据。组织变化发生在最终持锁之后则由配对 Identity 排他锁串行化。
- 成功 Receipt 与事件都引用全部同事务 Lead 写入之后的最终 revision；来源自然键重复的 NO_CHANGE 不追加事件。

### 3.3 draft

Draft 使用专属判定规则，不引入可操作任意 Task 的 DRAFT_SAVE 通用权限。服务端先读取真实 TaskType，再按下表选择主权限；请求 actionCode 必须精确等于该 Task 的 PrimaryCommand，Schema 和 candidate 字段使用现行注册表。

| 真实 TaskType | Authority slot | Authority code |
|---|---|---|
| RESOLVE_LEAD_DUPLICATE | SOURCE_INTAKE_OWNER | LEAD_INGRESS_RESOLVE |
| COMPLETE_LEAD_INGRESS | SOURCE_INTAKE_OWNER | LEAD_INGRESS_COMPLETE |
| ASSIGN_LEAD | ROUTING_SUPERVISOR | LEAD_ASSIGN |
| RESOLVE_LEAD_ROUTING_GAP | ROUTING_SUPERVISOR | LEAD_ROUTING_DECIDE |
| ACK_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_OWNER | SOURCE_INTAKE_REQUEST_ACK |
| CONTACT_LEAD | ASSIGNMENT_OWNER | SALES_CONTACT_OWNER |
| REVIEW_LEAD_VALIDITY | ROUTING_SUPERVISOR | LEAD_VALIDITY_REVIEW |

DIRECT 路径的真实 Appointment 必须等于 Task Owner；DELEGATED 路径的 onBehalf Appointment 必须等于 Task Owner，实际代理人与原授权人均有效，委托是一跳并引用匹配的原始权限。具备相同权限但不是 Owner 或合法代理的其他人仍被拒绝。SERVICE/SYSTEM 不得保存人工草稿。

授权 scope 使用实际 Task Owner 任职所属组织，并用相应主命令的范围约束验证；不接受请求指定组织。精确 Task 和其 Lead subject 上适用于该权限的 DENY 均必须检查。Draft 不在现行对象授权目标集合中，不新增 Draft 对象授权类型。

新 key 的保存只允许准确 OPEN Task 及其唯一可编辑 DRAFT，创建/更新条件继续使用 Task/Draft 各自 ETag。最终校验 Task Owner、Task/action/Lead binding、Draft 归属、Schema 和安全 revision。保存仅更新候选值，既不确认 Draft，也不完成 Task。主命令才在同一事务确认 Draft 并写准确完成 Fact。

### 3.4 两类 recovery

| CommandType | Authority slot | Authority code | TaskType | Wait profile |
|---|---|---|---|---|
| REOPEN_DUE_CONTACT_TASKS | SYSTEM_RECOVERY | CONTACT_TASK_RECOVER | CONTACT_LEAD | CONTACT_RETRY_V1 |
| REOPEN_DUE_ROUTING_REVIEW_TASKS | SYSTEM_RECOVERY | ROUTING_REVIEW_TASK_RECOVER | RESOLVE_LEAD_ROUTING_GAP | R1_ROUTING_REVIEW_WAIT_V1 |

只接受服务端 mTLS 映射的 SERVICE Principal、有效服务 Appointment 和 SYSTEM 路径的准确直接 Grant；禁止 HUMAN、onBehalf、委托或对象 ALLOW 代替专属 Grant。同一服务可被明确授予两项权限，但一项权限不能替代另一项。

组织范围取真实 Task Owner Appointment 的组织，不取服务自身组织，也不由请求提交。服务 Grant 必须覆盖该组织；同时检查准确 Task 和 Lead 上适用于本 recovery 权限的 DENY。Owner Appointment 或其 Principal 已失效、组织关闭时，恢复失败关闭并使用 internal 合同已有的 NOT_AUTHORIZED，不借恢复转派责任或绕过 Owner 失效。恢复不要求服务具有人工主命令权限，也不授予其记录联系结果或调配决定的权力。

先识别已有 key 并执行当前授权下的重放/冲突；只有新 key 才在 LEAD→TASK→COMMAND 锁内检查准确 Task/最新 WaitReceipt ID、hash、profile、expected revision 和 `resume_due_at <= dueCutoff <= trustedNow`。错误类型、提前到期或陈旧 selector 保持 VALIDATION_FAILED 且全零差额。成功只做 WAITING→OPEN 一次 CAS；Owner、subject、原 SLA 和 WaitReceipt 均不变。

原 Owner 失效导致不可行动的责任必须在后续运行验收中可发现并有受控处置说明；本轮不新增转派命令或把该问题计为已解决。恢复 HTTP adapter 对身份失效使用 internal allowlist 的 NOT_AUTHORIZED，不向内部 operation 扩散公共 APPOINTMENT_INACTIVE 错误。

### 3.5 身份配置与生产接入的验收边界

测试使用隔离数据库中的真实身份、任职和 Grant，不发布这些测试数据为默认生产授权。获得一个静态权限代码不等于任何用户已被授予该权限。

原计划 Task 8 的真实 Bearer/mTLS→ActorContext 接入、以及 R1 运行验收所需的受控身份/授权配置和撤销途径，属于业务可用门禁。没有 ADM 页面可以使用经单独审查的运维途径，但该途径仍须遵守 Identity 写锁、最小权限和审计，不允许裸 SQL 绕过协议。本规格不授权现在创建真实人员/服务 Grant 或配置外部身份系统。

## 4. 统一事件合同

### 4.1 事件语义和 Schema

事件是“准确事实已发生指定变化”的不可变通知，不是业务事实副本，不是命令执行许可，也不是事件溯源日志。一个成功命令可产生多个通知，每个通知拥有自己的 sourceFact；Receipt 仍只有一个准确 resultFact。

所有 R1 事件统一显式注册 exact event type、schema version、source type、revision/hash selector、分支、QueueOwner 和消费目的。保留既有 PascalCase 名称，不批量改名；OpportunityOpened 使用基线已确定的精确名称，不增设 OpportunityOpenedV1 别名。新事件 Schema version=1，payload 的 JSON Schema 为 object、零 properties、additionalProperties=false，唯一合法实例是 `{}`。

类型、source selector、command/correlation/causation、可信发生时间等继续使用现有类型化列，不复制到 payload。payload 摘要仍为规范 JSON 的 SHA-256。不写入法律需求、联系方式、Draft 候选值、权限细节、密文或 HMAC。破坏性语义变化使用新版本及兼容策略，不能原地重新解释既有事件。

| 新增事件精确名称 | sourceFact | Receipt result | R1 QueueOwner | 消费目的 |
|---|---|---|---|---|
| LeadCapturedV1 | lead.lead@同事务最终 revision | 相同 Lead selector | R1_PROJECTION | 刷新接入及当前责任投影 |
| ActionDraftSavedV1 | responsibility.action_draft@保存后 revision | 相同 Draft selector | R1_PROJECTION | 刷新已授权草稿呈现，不触发 Task 完成 |
| ContactTaskReopenedV1 | responsibility.task_occurrence@CAS 后 revision | 相同 Task selector | R1_PROJECTION | 刷新可行动联系卡 |
| RoutingReviewTaskReopenedV1 | responsibility.task_occurrence@CAS 后 revision | 相同 Task selector | R1_PROJECTION | 刷新可行动调配卡 |
| OpportunityOpened | opportunity.opportunity@初始 revision 0 | 联系命令仍为 lead.lead_contact_result@hash | R1_PROJECTION | R1 边界投影；保留供 R2 启用读取的不可变通知 |

### 4.2 成功分支的精确事件集合

下表为完整 R1 成功分支集合。运行时不能只校验“事件在允许列表内”，还要验证集合相等、来源准确、无缺失、无多余和无重复。分支描述必须与持久化 OutcomeCode/contact_no 等事实相符，由所属 Owner 在同事务内复验，不直接相信客户端或孤立枚举。

| 命令/分支 | 必须且只允许的事件 |
|---|---|
| CAPTURE_LEAD：创建新 Lead 及任一合法后继选择 | LeadCapturedV1 |
| SAVE_ACTION_DRAFT：创建/改变候选值 | ActionDraftSavedV1 |
| REOPEN_DUE_CONTACT_TASKS：准确 WAITING→OPEN | ContactTaskReopenedV1 |
| REOPEN_DUE_ROUTING_REVIEW_TASKS：准确 WAITING→OPEN | RoutingReviewTaskReopenedV1 |
| P0_01_LINK_EXISTING、P0_01_KEEP_SEPARATE | LeadDuplicateResolutionRecordedV1 |
| P0_02_COMPLETE | LeadIngressCompletedV1 |
| P0_03_ASSIGN | LeadAssignedV1 |
| P0_04_SCHEDULE_ROUTING_REVIEW、P0_04_RETRY_ASSIGNMENT_NOW | LeadRoutingDispositionRecordedV1 |
| P0_04_REQUEST_SOURCE_INTAKE_STOP | SourceIntakeStopRequestedV1 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | SourceIntakeStopRequestAcknowledgedV1 |
| CONTACT_CONNECTED_VALID | LeadContactResultRecordedV1 + OpportunityOpened |
| CONTACT_NOT_CONNECTED_RETRY、CONTACT_SUSPECT_INVALID | LeadContactResultRecordedV1 |
| CONTACT_NOT_CONNECTED_EXHAUSTED | LeadContactRetryExhaustedV1 |
| REVIEW_CONFIRM_INVALID、REVIEW_CLOSE_UNREACHED、REVIEW_REOPEN_CONTACT | LeadValidityReviewedV1 |

按现行合同，分支的后继 Task/Assignment 应由业务命令在同一事务中准确创建，不额外假设“每张新增行都要发一个事件”。例如 capture 自动分配只发布 LeadCapturedV1；R1 投影通过具名 Owner read port 刷新 Lead 的当前责任。这保留原分支事件语义，不额外引入消费者依赖。如果未来确有独立消费者需要 Assignment 创建通知，应通过新合同补齐所有产生 Assignment 的分支，而不是只给其中一个分支偷偷加事件。

### 4.3 CONNECTED_VALID 事务和计数

同事务写入准确 ContactResult、由该结果唯一产生的 Opportunity 锚点、确认 Draft、原 Task DONE、两条 Event、两条 R1_PROJECTION Outbox、一个 Receipt、一个 Audit。ContactResult 事件 source 为该结果的精确 hash；OpportunityOpened source 为 Opportunity@0，两者必须通过准确 source_lead_id/source_assignment_id/source_contact_result_id 及 Owner 绑定相互印证。

最终差额为 Slot +1、Receipt +1、Event +2、Outbox +2、Audit +1，R2 Task +0。缺少任一事件、引用另一商机/联系结果或任意部分持久化失败，均不得留下部分业务效果。NO_CHANGE 无通知；同 key 重放、冲突和提交确认丢失后的原 key 恢复不新增事件、Outbox、Receipt 或 Audit。

一般公式为 EventCount=分支准确通知集合基数；OutboxCount=各事件静态 QueueOwner 数量之和。目前每条 R1 事件只有 R1_PROJECTION 一个 Owner，因此本分支是 2/2。未来新增消费者时以其新阶段合同更新数量，不能把“2”定义成跨版本永久常数。更新 Task 矩阵的通用成功段落及 E2E_CONTACT_CONNECTED 行，其他单事件分支的计数不批量改成 2。

## 5. 消费、历史事实及完整 MVP 衔接

### 5.1 R1 投影不承诺历史快照

对可变 Lead/Draft/Task 的通知，source revision 是事件发生时的准确依据，不承诺可读出该行旧版本。R1_PROJECTION 只把它作为“需要重新读取当前事实”的信号，经 Tenant 绑定和 Owner read port 读取当前状态；允许事件延迟、重复和乱序，禁止用旧事件覆盖较新投影、把 DONE Task 恢复成 OPEN 或恢复过时 Draft。

需要依据历史业务结论行动的消费者必须读取对应不可变 Decision/ContactResult/WaitReceipt，或已冻结的 write-once 边界字段，不能将当前可变行伪装为历史快照。无法验证准确来源时不猜测成功，也不把私人数据补进事件 payload。

### 5.2 R2 后启用的 OpportunityOpened

R1 保留不可变 OpportunityOpened，不因 R1_PROJECTION 已 DELIVERED 而删除、改写或重置事件/旧 Outbox。R2 启用的具名消费者需枚举 Tenant 内历史及新增 OpportunityOpened，经 Opportunity Owner 读取稳定创建来源字段，再验证不可变 ContactResult；不要求 Opportunity 当前 revision 仍为 0，也不把当前业务状态当成创建时状态。

R2 的交接验收必须覆盖：已由 R1 投影消费的历史事件、启用时并发新事件、重复投递、乱序、消费者中断后重试，以及每个准确 Opportunity 首个推进责任至多创建一次。去重的业务边界是 Tenant + Opportunity + 首个推进责任类型，不依赖投递次数或把时间游标当成完备性证明。

R2 按其新阶段合同启用自己的静态消费路径；本轮不注册无消费者的 R2 QueueOwner、不创建 R2 Outbox 或 Task、不实现通用回放平台。R2 必须在启用前提交上述历史覆盖和幂等证据。该要求是交接合同，不宣称本轮已完成 R2 消费运行验证。

### 5.3 全销售 MVP 的事件语义目录

| 业务阶段/事实族 | 通知的业务含义 | 冻结深度 |
|---|---|---|
| Lead、Assignment、入口 Decision、Draft、联系重试/复核 | 接入、责任产生、候选保存和真实联系结论 | 本轮冻结全部 R1 事件及分支集合 |
| Opportunity 创建锚点 | 有效首联形成可推进商机 | OpportunityOpened 的生产和跨阶段交接合同本轮冻结 |
| OpportunityProgress、QuoteRevision/Issue/Response | 商机推进、报价版本、发出及客户响应的准确事实 | 保留 Fact Owner、版本和消费者设计原则；具体事件在原后续阶段先冻结再实施 |
| ConflictReview/Finding、ContractRevision、Signature、Execution、PaymentConfirmation | 冲突结论、合同版本、签署履行及准确到账事实 | 不用通用成功事件替代审批/签署/到账事实；后续阶段逐项冻结 |
| TransferRequest/Snapshot、TRANSFER_REVIEW Decision、一次写入 MatterRef | 转案接受与稳定 Matter 身份交接 | 保持基线 MatterCreated 及无第二身份约束，不增加 Matter 聚合 |

后续阶段不能直接复用 R1 的 LEAD_CAPTURE、主命令权限或 SYSTEM_RECOVERY 来审批报价、确认冲突、签署、到账或接受转案。各阶段必须为真实责任建立专属命令/权限/事件映射；统一规则不等于共享万能权限或冻结全部未来事件名称。

## 6. 实施单元和验收证据

本轮一个收口分支包含三个可审查单元：静态合同与分支验证器；四类授权/运行时策略及真实数据库测试；事件 Schema/精确集合/原子多事件测试和整体进度报告。各单元必须先有针对性失败测试再实现，最终统一审查并合并 main。

| 验收项 | 必须提供的证据 |
|---|---|
| 原始约束零漂移 | 基线一致性、物理生成物/迁移字节不变、架构/拓扑门禁、未新增 HTTP operation |
| capture | 正确 Grant；错码/错组织拒绝；共享 root 的明确语义；Lead 不存在时真实组织锚点；已有 Lead DENY；撤销/组织变更复验 |
| draft | 七种 Task 映射；相同权限非 Owner 拒绝；合法一跳代理及越范围代理；action/Schema/Task/Draft 错配；保存不确认/不完成 |
| 两种 recovery | 真实 SERVICE 专属 Grant；HUMAN/错权限/错组织拒绝；Owner 失效；类型/profile 隔离；到期 CAS；重放先于新 key eligibility |
| 事件合同 | 注册表/Schema/分支矩阵一致；仅 `{}`；准确 revision/hash；缺失/多余/错源/重复事件拒绝 |
| CONNECTED_VALID | 精确 2 Event/2 Outbox、单 Receipt/Audit、异源事实绑定、失败全回滚、重放零新增 |
| 身份并发和事务 | 保留既有最终持锁复验、撤销/DENY/组织变更、NO_CHANGE savepoint、冲突安全投影、提交确认丢失测试 |
| 前后端一致性 | OpenAPI 生成客户端检查和 TypeScript 类型检查；本轮无 UI 业务交付，不冒充浏览器联调 |
| 完整链路准备 | R1→R2 历史通知和幂等门禁可定位；无无 Handler 的 R2 Task；无新增通用平台 |

最终报告必须将“合同已冻结/代码已合并/机制实库验证/真实业务联调”分别记账。不得因为测试 Handler 能跑就把 R1-BACKEND、R1-SPA 或 E2E 行推进为完成。测试环境缺失或命令失败须原样记录，不跳过关键断言、不修改冻结门禁换取绿色状态。

## 7. 当前整体进度及本轮不宣称完成的能力

以下是本规格起草时按基准提交核对的状态，不是本轮实施结果。

| 能力 | 后端/合同 | 前端 | 联调/整体可用性 |
|---|---|---|---|
| 数据结构、单制品工程、能力角色、CommandRuntime/授权/Audit 底座 | A/B/C0/C/D 及对应证据已合并；四类命令目前仍有注册门禁 | 单 SPA 壳、生成类型和客户端底座 | 基础设施证据不等于业务 E2E |
| 本轮四类授权及五个事件描述 | 书面设计已确认；静态合同和基线已冻结，生产运行时接入仍待后续任务 | 不新增页面；合同变更不提升SPA状态 | 合同门禁已收口，真实业务联调尚未完成 |
| R1 接入至首联完整业务 | 原 Task 5–8 的业务 Owner、API 和 Worker 仍须交付 | 原 Task 9 的 CurrentCard、Draft 和恢复交互仍须交付 | 原 Task 10 黄金/失败 E2E 尚未完成 |
| 后续销售 MVP | 继续按阶段门禁推进 | 不把冻结视觉当实现 | R1 未整体验收前不启动 R2 实施 |
| ADM-01～07 | 身份数据模型和授权原语不等于管理 CRUD | 七项视觉设计不等于可操作页面 | 不作为本轮必要验收项；仍单独报告未交付 |

## 8. 书面规格自审

- 本文件明确区分方向批准、书面规格、生产代码、业务运行验收；不提前改变活动基线。
- 四类策略各有真实授权锚点、Actor 路径、准确责任/组织边界和拒绝规则；没有借用无关权限。
- Task subject 仍只有 Lead；组织仅用于 capture 授权/审计，未扩展对象授权目标白名单；Draft 也未伪造为对象授权目标。
- 全 R1 成功事件集合完整列出；单 Receipt 与多通知不冲突；CONTACT_NOT_CONNECTED_EXHAUSTED 保持原单个耗尽事件。
- 空 payload 不承诺历史快照；R2 交接明确历史覆盖与业务幂等，但不声称其消费代码已实现。
- 原始拓扑、表数、迁移、角色隔离、Task 真源、AI 权限及完整 MVP 终点保持不变；新增语义必须由实施 ADR 和新基线显式记录。

批准前书面规格阶段的历史验证记录：当时仅新增本文件，未修改活动合同或生产代码；本文件的5个本地Markdown链接均可解析。Windows基线测试在`test_artifact_rejects_absolute_outside_and_symlink_paths`的`Path.symlink_to`处因WinError 1314失败，单项重跑复现；当时完整套件停止且未声称通过，也未修改测试、权限或门禁绕过该失败。实施验证另由Task报告记录，并在支持符号链接的Python 3.12 Linux环境执行完整基线套件。
