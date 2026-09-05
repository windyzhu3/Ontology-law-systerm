# R1 基础功能闭环与原始设计对齐规格

日期：2026-09-05。状态：APPROVED（用户已明确确认本书面规格，授权进入详细计划及实施）。

基准提交：`3250636d98fe8abab24e83d9c66ec6d1dd42bc73`。

本文件回答两个问题：当前 R1 计划是否偏离最初设计，以及怎样才可以诚实地宣称“基础功能完全实现”。方向批准不等于本文件已经成为活动合同；在本文件获书面确认、对应 ADR/合同/基线完成受控修订以前，[当前基线](../../baseline/CURRENT-MVP-BASELINE.md)、既有 ADR 和冻结合同仍然优先。

## 1. 结论

当前实现没有偏离“一张责任卡驱动、领域 Fact 为真源、命令原子提交、单 SPA/单 OpenAPI/单 Jar、API 与 Worker 权限隔离”的原始架构。已经合并的是 Schema、能力角色、CommandRuntime、授权与事件合同底座；它们并不等于 R1 业务已经可用。

对照[原 R1 实施计划](../plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)后，Task 5–10 的生产业务 Handler、CurrentCard、API、安全适配、Worker、SPA 和真实浏览器验收仍未完成。若现在推进台账中的 R1-BACKEND、R1-SPA 或 R1-E2E，会把“底座通过”误报为“业务闭环完成”。

继续实施前必须收口五项差异：

| 差异 | 与最初设计的关系 | 本规格的裁定 |
|---|---|---|
| 公共 capture 被收窄为 HUMAN-only | 偏离原始“人员或受信服务 Actor 可接入” | 恢复同一个 `captureLead` 的受信 SERVICE 路径，不新增用户入口、表或 SPA |
| CurrentCard 敏感读取没有披露前审计门禁 | 漏实现既有运行时合同 | 同一短事务读取、复验、追加 Audit 并确认提交后才返回 200/304 |
| DueTaskScheduler 没有可重建的到期任务发现通路 | 原计划只写了“逐张调用”，Worker 实际无法获得冻结请求 selector | API 提供只返回安全 selector 的具名 mTLS 分页读取；Worker 仍不获得 Query 数据库能力 |
| `R1_PROJECTION` 有路由而没有生产消费者 | 计划与可运行能力之间存在缺口 | Worker 真实领取、API 重读当前事实并确认、Worker 用当前租约 CAS 为 DELIVERED；不伪造持久化投影 |
| CI 可因路径过滤、零前端测试或缺少 E2E 而绿色 | 验收门禁弱于原计划 | 新增无路径过滤的完整 R1 顺序门禁，零测试、全 skip、缺证据一律失败 |

其中“新增一个 mTLS 到期任务发现 operation”和“新增一个 mTLS 投影消费确认 operation”改变现行 13-operation HTTP 冻结面。两者分别是让 due scheduler 可在重启后重建工作、以及形成真实跨进程投影消费者而不扩大 Worker 数据库权限的最小受控修订；批准本文件即批准将 R1 OpenAPI 从 13 个 operation 改为 15 个。若不批准这两个修订，就必须分别把 due scheduler 和投影消费者记为未实现，不能用进程内遗留列表或“读取 Event 后直接标 DELIVERED”代替。

## 2. 不变的设计边界

| 原始约束 | 本轮保持方式 |
|---|---|
| 一个响应式 SPA、一份 OpenAPI、一个模块化单体 Jar | 只在同一 OpenAPI 增加两个 internal operation；不增加应用或部署制品 |
| `APP_ROLE=api|worker` 互斥 | API 装配 Controller/Command/Query/Audit；Worker 装配 Scheduler/Outbox/HTTP client；彼此 Bean 不出现 |
| 13 Schema、52 应用表加 2 技术表、`52-plus-2-v1.1` | 不修改物理合同、manifest、字段合同或 V001–V850 字节，不新增投影表 |
| jOOQ 是唯一业务持久化方式，Fact Owner 拥有 SQL | SQL 与生成类型留在 Owner 的 `internal.persistence`；API/Worker 不直接使用 `DSLContext` |
| 领域 Fact 是业务真源 | Receipt、Audit、Event、Outbox 和 Draft 都不替代 Lead/Task/Decision/ContactResult/Opportunity |
| 一张卡一个 Owner、一个主命令、一个完成 Fact 类型 | CurrentCard 只展示准确 Owner 的一张 OPEN 卡；选项仅决定主命令参数 |
| Task DONE/CANCELLED 永久终态 | recovery 只恢复准确 WAITING Task；投影消费不修改 Task，更不能用旧事件重开终态 |
| 四轴实时授权、单一路径、DENY 优先 | 每次命令、敏感读取和内部消费都用服务端确定的 Tenant/Actor/Subject/Scope 并最终复验 |
| READ COMMITTED 命令、最终持锁复验、同事务写入 | 保留现有命令语义；增加明确的 CurrentCard 业务读取围栏，不用独立审计事务拼接旧结果 |
| 无状态 Query、不持久化查询结果 | CurrentCard 永远同步回源；Worker 不创建第二份卡片、缓存表或 Outbox 内业务视图 |
| Event 是通知而非历史事实副本 | 消费者只把旧 source selector 当重读信号，永远按当前 Fact 求值 |
| AI 只提取、草拟、解释 | R1 不接 AI；SERVICE capture 不是 AI 主命令权限 |
| 不建设通用平台 | 不增加动态权限 DSL、BPMN、Saga、Kafka、Redis、通用 Job/回放平台或 EAV |
| 销售 MVP 最终仍到转案接受与稳定 MatterRef | 本轮只完成 R1 接入至首联 Opportunity 边界，不把 R2 及以后算作完成 |

锁顺序在原 `LEAD → TASK → COMMAND_SLOT → identity shared` 相对顺序前增加一个保守的 R1 读取稳定边界：`R1_BUSINESS_TENANT_LOCK → LEAD → TASK → COMMAND_SLOT → identity shared`。这是为了在零新增表条件下让整张 CurrentCard、候选集合、Draft、ETag、投影求值和披露 Audit 对应同一个稳定业务版本。CommandRuntime 在进入任何 R1 Handler 根锁以前经公共窄 `R1BusinessFence` 集中取得该 Tenant 锁的排他事务锁，不能依赖每个 Handler 自觉调用；CurrentCard 与 projection 求值取得共享事务锁。不同 Tenant 仍可并行，读之间仍可并行。未经等价并发证明，不把它缩窄为仅 Task 或仅 Appointment 锁。

Identity writer 仍先取得现有 Tenant identity 排他锁，之后不得取得业务锁；普通业务命令和披露读取先取得业务锁，最后取得 identity shared 锁。Identity writer 不反向请求业务锁，因此该顺序不形成锁环。所有锁键继续使用版本化前缀、规范 Tenant UUID、SHA-256 前八字节大端有符号 bigint；哈希碰撞只允许增加竞争，不能改变授权结论。

该 Tenant 锁粒度是本规格选择的正确性方案，但其容量兼容性不能靠推断宣称。它改变 CurrentCard 与热点写路径，因此命中既有 CapacityGate 触发原则；R1 验收使用独立命名的 `R1-CAPACITY-V1`，不冒充尚包含 R2+、Matter、搜索投影和 Evidence 负载的完整 `C1-V1`。`R1-CAPACITY-V1` 沿用 C1-V1 的参考资源（1 个 4 vCPU/8 GiB API、1 个 4 vCPU/8 GiB Worker、PostgreSQL 18 的 8 vCPU/32 GiB 与至少 3000 IOPS SSD）、一个主 Tenant 加 1% 隔离哨兵 Tenant、10 分钟预热、30 分钟稳态和 60 秒突发、以及同一 SLO；它拥有自己的 Profile/Generator/Fixture Digest 和种子 `OLS-R1-CAPACITY-V1-20260905`。

R1 Fixture 只使用当前 52＋2 物理合同内的身份与 R1 Fact。Identity 七表冻结为 2 Tenant、500 Principal（含 HUMAN 与各受信 SERVICE）、80 OrganizationUnit、1,000 Appointment、4,000 AuthorityGrant、5,000 DelegationGrant（含 2,000 有效）和 250,000 ObjectAccessGrant（其中 10,000 为准确 DENY）；撤权与限制用这些表已有的 state、有效期和 DENY 表达，不虚构 Membership、Position、CustomerAccessGrant、Restriction 或历史 Registry 表。

业务历史不能通过彼此独立的表行目标凑数，只能由下列确定性 BranchID 执行向量和冻结 delta 矩阵生成；每行次数属于主 Tenant，哨兵 Tenant 按同一分布取 1%，四舍五入规则写入 Generator manifest。`fixture=CANDIDATE|EMPTY` 只是同一个冻结 BranchID 的确定性候选政策结果，不新增合同 BranchID：

| BranchID | 执行次数 |
|---|---:|
| CAPTURE_LEAD_CREATED | 500,000 |
| SAVE_ACTION_DRAFT_CHANGED | 2,470,000 |
| REOPEN_DUE_CONTACT_TASKS_REOPENED | 250,000 |
| REOPEN_DUE_ROUTING_REVIEW_TASKS_REOPENED | 300,000 |
| P0_01_LINK_EXISTING | 80,000 |
| P0_01_KEEP_SEPARATE | 80,000 |
| P0_02_COMPLETE | 350,000 |
| P0_03_ASSIGN | 300,000 |
| P0_04_SCHEDULE_ROUTING_REVIEW | 350,000 |
| P0_04_RETRY_ASSIGNMENT_NOW / fixture=CANDIDATE | 20,000 |
| P0_04_RETRY_ASSIGNMENT_NOW / fixture=EMPTY | 190,000 |
| P0_04_REQUEST_SOURCE_INTAKE_STOP | 80,000 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | 75,000 |
| CONTACT_CONNECTED_VALID | 135,000 |
| CONTACT_NOT_CONNECTED_RETRY | 300,000 |
| CONTACT_NOT_CONNECTED_EXHAUSTED | 100,000 |
| CONTACT_SUSPECT_INVALID | 100,000 |
| REVIEW_CONFIRM_INVALID | 75,000 |
| REVIEW_CLOSE_UNREACHED | 65,000 |
| REVIEW_REOPEN_CONTACT | 50,000 |

向量中的 2,350,000 次人工主命令各确认一张唯一 Draft；另有当前 OPEN Task 的 30,000 张 DRAFT，共 2,380,000 个 ActionDraft 行。2,470,000 次 save 分支由每张 Draft 的首次保存加确定性选择的 90,000 次再次修改组成，不能解释成 2,470,000 个不同 Draft。

Task 也完全由向量推导：500,000 次 capture 各创建一张初始 Task，2,350,000 次人工完成分支中恰有 2,000,000 次按冻结矩阵创建一张后继 Task，因此历史上恰有 2,500,000 张 Task。其当前状态为 2,350,000 DONE、100,000 WAITING（50,000 routing、50,000 contact）和 50,000 OPEN；OPEN 在 duplicate/ingress/assign/routing/contact/ack/review 七类中的数量依次为 5,000/5,000/5,000/10,000/10,000/5,000/10,000，其中 20,000 逾期，不虚构 R1 没有生产路径的 CANCELLED。350,000 次 routing-wait 分支由 300,000 次 routing recovery 和当前 50,000 个 WAITING episode 消耗，300,000 次 contact-retry 分支由 250,000 次 contact recovery 和当前 50,000 个 WAITING episode 消耗，因而恰好推导 650,000 WaitReceipt。

Task 类型因果也由 Generator 固定验证：初始 500,000 张分配为 165,000 duplicate、195,000 ingress、115,000 routing 和 25,000 contact；最后一组来自 capture 自动分配并同时生成 25,000 LeadAssignment。duplicate 的 160,000 张后继补齐 ingress，ingress 的 350,000 张后继分为 305,000 assign 与 45,000 routing，routing retry 的 20,000 candidate/190,000 empty 分别产生 contact/routing，其他后继遵循冻结矩阵。最终各类型创建/完成数必须精确闭合为 duplicate 165,000/160,000、ingress 355,000/350,000、assign 305,000/300,000、routing 700,000/640,000、contact 695,000/635,000、ack 80,000/75,000、review 200,000/190,000；差额就是上述 100,000 WAITING 与七类 50,000 OPEN，不能另行填充。

其余派生基数由矩阵机械计算为 500,000 Lead、345,000 LeadAssignment（capture 自动分配 25,000＋P0-03 300,000＋routing candidate 20,000）、635,000 LeadContactResult、1,065,000 Decision、135,000 Opportunity、5,870,000 Receipt、6,005,000 Event 和 6,005,000 R1_PROJECTION Outbox。每个新 key 命令生成一条命令 Audit。

历史读取向量按七种 TaskType 各冻结 500,000 次已审计的非空 CurrentCard 响应，每种均为 BODY 375,000 次、CACHE_REVALIDATED 125,000 次，并使用下列准确最小响应形状。Owner 显示名和组织标签分别来自 Principal、OrganizationUnit，而 Appointment 决定两者与 Task 的准确绑定，因此三者都是独立 `disclosedSource`，不能合并成一个虚构复合 Subject：

| TaskType | 每次准确披露 Subject | Audit 条数/响应 |
|---|---|---:|
| RESOLVE_LEAD_DUPLICATE | Task、Lead、Owner Appointment/Principal/OrganizationUnit、一个候选 Lead、该候选 Party | 7 |
| COMPLETE_LEAD_INGRESS | Task、Lead、Owner Appointment/Principal/OrganizationUnit、Draft | 6 |
| ASSIGN_LEAD | Task、Lead、Owner Appointment/Principal/OrganizationUnit、一个候选 Appointment 及其 Principal/OrganizationUnit label 来源 | 8 |
| RESOLVE_LEAD_ROUTING_GAP | Task、Lead、Owner Appointment/Principal/OrganizationUnit、Draft | 6 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | Task、Lead、Owner Appointment/Principal/OrganizationUnit、因果 Decision | 6 |
| CONTACT_LEAD | Task、Lead、Owner Appointment/Principal/OrganizationUnit、Assignment | 6 |
| REVIEW_LEAD_VALIDITY | Task、Lead、Owner Appointment/Principal/OrganizationUnit、ContactResult | 6 |

读取 profile 固定 `nextSummaries=[]`，候选集合恰含表中一个可见项，未列 Draft 的卡使用 `actionDraft=null`，所以不会产生未计数来源。3,500,000 次响应由此精确生成 22,500,000 条第 5 节披露 Audit，加上 5,870,000 条命令 Audit 后总数为 28,370,000；重复读取复用业务 Fact，但每次使用唯一 Audit/correlation，304 均有更早的匹配 BODY/ETag。500,000 Party 作为已解析及重复候选事实按 Lead selector 约束生成。Generator 必须逐 BranchID 和读取 profile 重算并验证 Draft 保存/确认、Task 创建/终态、完成 Fact、Receipt、Event、Outbox、响应模式、实际披露 Subject 与 Audit delta；任何派生计数或因果关系不一致都使 Fixture 构建失败，禁止直接插入不可能的 R1 历史凑基数。

数据在已冻结 R1 v1 注册表下保持五年时间分布、80/20 Owner 偏斜及撤权/过期/代理/DENY 边界，但不生成 ConflictReview、Contract、TransferRequest、Matter、搜索投影或 Evidence 对象。

同一主 Tenant 的稳态/突发负载仍为业务写 10/50 次每秒、当前卡及操作查询 100/300 次每秒；写入权重固定为 capture 10%、save draft 20%、七类人工主命令各 8%、两类 recovery 各 7%，读取固定为 60% CurrentCard 200、20% CurrentCard 304、10% due discovery、10% projection consume。200 样本至少一半含多个披露 Subject，同时以 1/10 次每秒稳态/突发执行 identity 变更。CurrentCard P95/P99 不超过 1.5/3 秒，本地确定性命令 P95/P99 不超过 2/5 秒。证据记录吞吐、错误率、锁等待、超时、死锁/饥饿、p95/p99、构建与 Profile/Generator/Fixture Digest；普通 PR 另保留小规模同 Tenant 锁竞争冒烟。完整系统进入其适用发布阶段时，仍必须按原合同重新执行完整 C1-V1，R1 证据不能替代它。

若该 R1 CapacityGate 未通过，就不得降低原目标、以 503 卸载流量冒充通过或宣称基础能力完成；必须回到书面设计细化锁粒度并重新证明集合、排序、版本和 Audit 的稳定性。首选调查方向是把纯计算和候选初读移到锁外，只在最终事实重验与提交区持有较短的 Tenant fence；未经新的集合并发与锁顺序证明，不能直接改成单 Task 锁。

## 3. “R1 基础功能完全实现”的精确范围

只有下列能力全部存在于生产代码并通过真实联合验收，才可使用“R1 基础功能完全实现”：

1. HUMAN 与受信 SERVICE 都能通过同一 `captureLead` 创建或幂等恢复 Lead；服务端完成加密/HMAC、来源政策、重复/缺字段/自动或人工分配分支。
2. P0-01 至 P0-04、来源停用请求确认、Assignment 和全部后继 Task 按冻结矩阵完成，跨 Tenant、越权、并发与 stale selector 均失败关闭。
3. 一个 HUMAN Appointment 每次只获得一张准确 OPEN CurrentCard；Draft 可保存、刷新恢复、确认后封存，敏感读取先审计后披露。
4. 首联三结果、重试、WAITING 到期恢复、主管复核和 `OpportunityOpened` 原子完成；CONNECTED_VALID 精确产生两 Event、两 Outbox、一个 Receipt 和一个 Audit。
5. API 的 11 个公共 operation、4 个 internal operation、安全错误、ETag、幂等与 Receipt 恢复均由真实生产适配器实现。
6. Worker 真实运行两类 due recovery 和 `R1_PROJECTION` 消费；租约、fencing、重试、EXHAUSTED、延迟/重复/乱序均有 PostgreSQL 证据。
7. 单 SPA 完成七类卡片、Draft、提交、Receipt 恢复、等待摘要、竞态保护、可访问性和 360/768/1440 响应式表现。
8. Playwright 使用真实 PostgreSQL、API、Worker 和 SPA 覆盖黄金、失败、等待及 SERVICE capture 路径；完整 CI 不允许零测试或跳过层级。

以下不属于 R1 完成范围：Provider 真实发送/回调、AI 接入、ADM-01～07 管理页面、R2 报价与商机推进、冲突审查、合同、签署、付款、转案和通用运维平台。它们不能阻止 R1 验收，也不能被 R1 结果冒充为已实现。

## 4. 受信 SERVICE 捕获 Lead

### 4.1 唯一入口与认证

HUMAN 和 SERVICE 都使用现有 `POST /api/v1/leads`、`captureLead`、`publicBearer` 和 `CaptureLeadV1`。这里的 Bearer 是经 OAuth2 Resource Server 验证的 access token；SERVICE 不使用 recovery 的 mTLS 入口，也不新增第二个 capture endpoint。

认证适配器必须验证签名、允许算法、可信 issuer、目标 audience、有效期和服务端登记的认证来源。经验证的 issuer/audience 与外部 subject/client 只用于从受信静态登记集合缩小候选，不能先信任 token 自报 Tenant 再选择密钥。适配器对每个剩余登记项使用该项准确 Tenant 的密钥计算 subject HMAC，再以同 Tenant 的 `identity_provider_code + external_subject_hmac` 交叉核对登记 Principal、Appointment 与数据库 `principal_kind`；最终必须恰好一项匹配。token 中的 Tenant、Principal、Appointment、roles、groups 或 scopes 只可进一步缩小已有候选，不能创建或扩展候选，更不能直接授予业务权限。零匹配、多匹配、数据库 kind 不一致或无法唯一确定 Tenant/Appointment 均失败关闭。

调用方的 body/header 不能选择 PrincipalKind、Envelope、Path、Grant、Tenant、组织或 authority code。生产 `ActorContextResolver` 创建带已验证 `PrincipalKind` 的可信 Actor，`JooqAuthorizationService` 再用数据库 `principal_kind` 复核，二者不一致即拒绝。

### 4.2 封闭授权组合

| 身份与入口 | Envelope | Path | Authority slot/code | on-behalf-of |
|---|---|---|---|---|
| Bearer HUMAN capture | INTERNAL_ADMIN | DIRECT 或合法一跳 DELEGATED | SOURCE_INTAKE_OWNER / LEAD_CAPTURE | DIRECT 为空；DELEGATED 必须完整 |
| Bearer SERVICE capture | SERVICE_ACTOR | SYSTEM | SOURCE_INTAKE_OWNER / LEAD_CAPTURE | 必须为空 |
| mTLS SERVICE due recovery | SERVICE_ACTOR | SYSTEM | SYSTEM_RECOVERY / 既有专属 recovery code | 必须为空 |

SYSTEM 只表示“准确 SERVICE Principal 使用自己的直接 Grant 的受控审计路径”，不是管理员旁路。SERVICE capture 不能保存人工 Draft、执行七个 Task 主命令、读取 Workbench 或借用 recovery Grant；HUMAN 不能选择 SYSTEM。通用授权仍保留 `SYSTEM → SERVICE`、其他路径默认 `HUMAN` 的类型限制，不把 SERVICE 放进所有 DIRECT 命令。

`CommandEnvelope` 的实际 envelope 由封闭的 `(CommandType, 已验证 PrincipalKind)` 注册表计算；未登记组合拒绝。`CAPTURE_LEAD + HUMAN → INTERNAL_ADMIN`，`CAPTURE_LEAD + SERVICE → SERVICE_ACTOR`，两种 recovery 只允许 SERVICE_ACTOR，人工 Task 命令只允许 INTERNAL_TASK。`JooqCommandStore` 必须保存和比较实例 envelope，不能继续调用 `type.envelope()` 的静态假设。

同一 CommandId、scope、payload 和 envelope 才能返回原 Receipt。HUMAN 成功后改用 SERVICE 重试，或 SERVICE 成功后改用 HUMAN 重试，属于 envelope conflict；不得悄悄复用另一信任边界的终态。payload digest 不包含 token 或 ActorContext，仍只覆盖冻结业务 payload。

### 4.3 来源绑定与实时授权

SERVICE 除了准确 ACTIVE Principal、有效服务 Appointment、有效组织链和 `LEAD_CAPTURE` 直接 Grant，还必须通过版本化 `R1_TRUSTED_SERVICE_SOURCE_BINDING_V1` 绑定允许的 `sourceAccountCode`。该绑定是部署时受信、启动时严格校验的静态注册表，不是请求字段、动态管理表或 Provider 专有平台。每个注册项至少绑定可信 issuer、audience、identity provider code、准确 `tenantId`、数据库 SERVICE `principalId`、`appointmentId` 和非空 source account 集合；Principal、Appointment 与 Tenant 不一致时启动失败。注册表不得保存原 token、secret 或外部 subject 原文。

请求的 `sourceAccountCode` 必须同时存在于该 SERVICE 的绑定集合及 `R1SourcePolicyRegistryV1`。随后按 source policy 在当前 Tenant 唯一解析 ACTIVE `sourceIntakeRootCode`，直接 Grant scope 必须覆盖该组织；不回退到服务任职组织、调用方组织或任意父组织。

新建 Lead 前，以 `identity.organization_unit@revision` 作为准确授权及 Audit subject。该锚点只用于组织链、scope、Grant 状态/有效期/撤销复验；它不在不变的物理 `BUSINESS_SUBJECT_TYPES` allowlist 内，因此不伪造 organization_unit ObjectAccessGrant DENY。ObjectAccessGrant DENY 仅应用于 allowlist 内的真实业务 Subject。自然键已存在或命令重放时，还要重验准确现有 Lead 的可见性与 LEAD_CAPTURE DENY。初始授权、工作前复验及最终 identity shared 锁下复验覆盖 Principal、Appointment、Grant、组织链/scope、source binding、Lead DENY 和准确 selector；等待锁期间撤权、到期、新 Lead DENY 或组织重挂必须阻止披露或提交。

成功沿用原 capture Handler、scope、Receipt、`LeadCapturedV1` 和 `R1_PROJECTION` 路由。Audit 冻结实际 SERVICE Principal/Appointment、空 on-behalf-of、SYSTEM、SOURCE_INTAKE_OWNER、准确 Grant/组织 subject 及授权摘要；不保存 token、secret、外部 subject 原文或联系方式正文。

### 4.4 SERVICE capture 必测项

- 正常 SERVICE capture、来源 allowlist、准确 Grant/scope、SYSTEM Audit 和空 on-behalf-of。
- 未知 issuer、错 audience、过期/伪签 token、歧义映射、数据库 kind 不一致和自报 Tenant/Principal/Appointment；相同 issuer/subject 在两个 Tenant 有登记时不得默认选择第一项，伪造 tenant claim 不得扩大候选，登记 Appointment 与 Principal 不匹配必须失败。
- 同一 intake root 下另一个未绑定 sourceAccountCode，及来源 root 不存在、关闭、跨 Tenant 或 revision 改变。
- SERVICE 的 DIRECT/DELEGATED/OBJECT、HUMAN 的 SYSTEM、SERVICE on-behalf、仅 recovery Grant、仅 OBJECT ALLOW 全部拒绝。
- 组织链/scope/Grant 状态、有效期与撤销，以及现有 Lead DENY；等待锁期间撤权/新 Lead DENY、失效 Appointment/Principal/Grant。组织不伪造为对象 DENY Subject。
- 同 key 正常重放零新增；payload/source/envelope 改变 conflict；重放不能披露已经失去访问权的原结果。
- HUMAN DIRECT/DELEGATED 既有行为不回归；Bearer SERVICE 不能调用 mTLS recovery，SERVICE 不能进入 Draft/Task/Workbench。
- Fact、Receipt、Audit、Event、Outbox 任一点故障全部回滚；提交确认丢失只允许以原 key 恢复。

## 5. CurrentCard 敏感读取的审计提交门禁

### 5.1 静态敏感分类

七种非空 CurrentCard 一律属于 `R1_CURRENT_WORKCARD_DISCLOSURE_V1` 敏感披露，不因某次 values 为空、字符串看似脱敏或 Draft 为空而动态豁免。受控字段组包括：

- `currentCard.subject.title/subtitle` 和 Owner 显示信息；
- `commandForm.values`、selector/option label 及联系方式、来源摘要、法律需求、理由摘要；
- `actionDraft.values`；
- 任何从 Lead/Party/Assignment/Decision/ContactResult 等 Subject 生成且能识别个人、案件或业务结论的内容。

`SafeText` 只是格式约束，不代表公开数据。表单 schema 与静态标签本身可为非敏感，但不把一张非空卡拆成“部分未审计响应”。`todaySummary`、最多两条 `nextSummaries` 和 `waitingCount` 仍逐对象授权并最小化；它们不得复制姓名、联系方式、案情或正文。

只有 `currentCard = null` 且 envelope 仅含已授权的静态安全摘要/计数时，才可作为零态豁免敏感读取 Audit。若无法证明摘要安全，整个请求走披露审计门禁。

### 5.2 事务与锁顺序

增加具名 `CurrentWorkCardDisclosureService` 与窄接口 `SensitiveReadRuntime`。纯 `CurrentWorkCardQuery` 继续只组合各 Fact Owner 的具名 read port；它不依赖 Audit、execution internal 或 jOOQ。API 层通过公共窄接口编排 Query 与 Audit，不把数据库连接、Record 或审计实现泄漏给 Query。

非空卡使用真实 Task 的主命令权限作为披露权限；可读并不产生一项更宽的通用权限。服务器按 TaskType 冻结如下映射：

| TaskType | Authority slot/code | 可用路径与 Owner | Scope | 必须复验的披露锚点 |
|---|---|---|---|---|
| RESOLVE_LEAD_DUPLICATE | SOURCE_INTAKE_OWNER / LEAD_INGRESS_RESOLVE | HUMAN DIRECT 且 Actor Appointment=Task Owner，或 HUMAN 一跳 DELEGATED 且 represented Appointment=Task Owner | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、实际返回的候选 Lead/Party |
| COMPLETE_LEAD_INGRESS | SOURCE_INTAKE_OWNER / LEAD_INGRESS_COMPLETE | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、Draft（如有） |
| ASSIGN_LEAD | ROUTING_SUPERVISOR / LEAD_ASSIGN | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、受控候选 Appointment 及其 Principal/OrganizationUnit label 来源 |
| RESOLVE_LEAD_ROUTING_GAP | ROUTING_SUPERVISOR / LEAD_ROUTING_DECIDE | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、Draft（如有） |
| ACK_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_OWNER / SOURCE_INTAKE_REQUEST_ACK | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、因果 Decision、Draft（如有） |
| CONTACT_LEAD | ASSIGNMENT_OWNER / SALES_CONTACT_OWNER | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、当前 Assignment、Draft（如有） |
| REVIEW_LEAD_VALIDITY | ROUTING_SUPERVISOR / LEAD_VALIDITY_REVIEW | 同上 | Task Owner organization | Task、Lead、Owner Appointment/Principal/OrganizationUnit、因果 ContactResult、Draft（如有） |

SERVICE/SYSTEM 和仅 OBJECT ALLOW 不能取得人工 Workbench。Task、Lead、Party、Assignment、Decision 等处于对象授权允许列表的锚点检查相同 purpose 的准确 DENY；Draft、ContactResult、Appointment、Principal 或 OrganizationUnit 等不能按该对象规则直接授权的披露来源不伪装为对象授权目标，而是通过真实 Task/Lead/Assignment 归属、静态候选政策、组织 scope 和主权限证明。`nextSummaries`/WAITING 摘要逐 Task 使用各自表中映射，不因主卡获准就自动披露其他责任。

每次 GET 使用同一连接的短 `READ COMMITTED` 事务：

1. 解析可信 HUMAN Actor、服务端 correlation/trace，并进入 QUERY 能力；SERVICE、歧义 Appointment 和跨 Tenant 立即拒绝。
2. 通过 `R1BusinessFence` 取得 `R1_BUSINESS_TENANT_LOCK_V1:<tenant>` 共享事务锁，再读取准确当前 Task、Lead、Draft、候选、Assignment 及安全摘要；所有 SQL 显式绑定 tenantId。
3. 按上表对主 Task 及每个实际披露来源分别执行实时四轴授权，构建内存中的不可变 `DisclosurePlan`。计划分别记录准确 `disclosedSource` 与 `authorizationAnchor`：可直接授权的来源使用自身锚点，其他来源必须使用真实 Task/Lead/Assignment 等允许锚点的授权快照，并把来源绑定及版本纳入最终复验和摘要。调用方不能提交 Grant、authoritySlot、Purpose、Subject 或授权路径。
4. 取得现有 Tenant identity shared 锁；用新鲜 `clock_timestamp()` 重读 Principal、Appointment、组织、Grant/DENY、业务 selector 和整份 envelope 依赖集。任何版本、可见性或排序结果变化都在锁内重新生成，不能混合旧 values 与新 ETag。
5. 生成准确的 Actor-scoped Workbench ETag 和审计条目集合；200 的 body 仍只在内存，304 也必须有完整的缓存重验证 DisclosurePlan。
6. 切换 AUDIT 能力，在同一事务追加全部披露 Audit；确认 `commit()` 成功后，API 才能把 200 body 或 304 交给序列化/网络层。

禁止在审计前缓存、流式写出、调用 Controller callback 或返回可序列化对象；禁止用独立 `REQUIRES_NEW` 审计拼接已经结束的读取；禁止提交后重新查询并返回另一版本。Audit 已提交但网络发送失败允许留下记录，重试产生新记录；这不是 exactly-once 披露。

所有 R1 写命令由 CommandRuntime 在现有 LEAD/TASK 锁之前集中取得同一 Tenant 锁的排他事务锁。这样“读到无 Draft 后并发首次创建”、Task OPEN/DONE、Lead revision、候选变化和 Assignment 改变都不能跨越审计版本边界。`R1BusinessFence` 的 SQL/连接实现留在 execution internal，API 只能使用公开窄接口；Query 不依赖 execution。生产写路径禁止绕过协议，由集中 Runtime、模块边界以及覆盖全部生产写入口的集成测试保证；数据库能力角色本身并不能证明业务 DML 执行前一定取得了 advisory lock。

### 5.3 Audit 形状

`AuditAppender` 增加类型化 `ReadDisclosureEntry`（或语义等价的封闭 overload），不能伪造 GET 命令复用 `R1_COMMAND_AUDIT_V1`。每条记录使用：

| 字段 | 固定语义 |
|---|---|
| entry_type | EVENT |
| command_id / command_type | 都为 NULL |
| action_code | READ_CURRENT_WORKCARD |
| result_code | SUCCEEDED |
| summary_schema_code/version | R1_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1 / 1 |
| service_role_code | API |
| responseMode | BODY 或 CACHE_REVALIDATED，仅出现在允许列表摘要中 |

一个 AuditEntry 只能有一个准确 Subject 和一个授权快照，因此 `DisclosurePlan` 对实际披露的 Task、Lead、Draft 及其他受控 Subject 去重后逐一追加记录，共用同一 correlation/trace。Audit `subject` 是实际被披露的准确 APPLICATION_FACT_TYPE；用于作出权限判定的 `authorizationSubject` 则必须来自第 5.2 节的 Task/Lead/Party/Assignment/Decision 等静态业务锚点，两者可不同但绑定关系必须由服务器注册表确定并写入无正文摘要。类型化 `ReadDisclosureEntry` 显式携带 `disclosedSource` 与 `authorizationAnchor`，禁止把授权快照 Subject 静默改成不可授权来源。Draft、WaitReceipt、ContactResult、Appointment、Principal 或 OrganizationUnit 可以是 Audit subject；不能按对象规则直接授权的来源必须绑定合法业务锚点，不能伪装为独立 OBJECT 授权目标。每条记录冻结披露 Subject 的 revision/hash、实际 Actor/Appointment/on-behalf、唯一授权 Fact/path/scope、可信时间和执行节点。若多个 Subject 依赖不同授权路径，分别审计，不能把不完整路径拼成一个允许。

允许列表摘要只记录静态 profile/version、BODY 或 CACHE_REVALIDATED、允许字段组和准确 source selector；不得复制 values、原始响应、phone/email、密文/HMAC、案情、正文或普通响应摘要。Audit 只证明服务端批准并审计了披露，不证明客户端实际收到了字节。

非空敏感卡或其他按静态分类需要审计的 200 与 ETag 匹配 304 都是新的披露尝试，必须重新认证、授权、版本复验并新增 Audit；第 5.1 节已证明安全的零态保持明确豁免。304 允许客户端继续使用敏感缓存，不能成为审计旁路。Workbench ETag 不含 Audit ID、correlation、请求处理时间或 Audit 可信时间；它覆盖 Actor scope、静态投影 profile 及当前 envelope 的规范内容/selector，包括实际进入 envelope 的 SLA 时间、状态和安全时间提示。HTTP 缓存只允许 `private, no-cache` 并 `Vary: Authorization`，每次复用都必须回源重验证。

### 5.4 失败映射与测试

- 未认证为 401；当前 Actor/Appointment 无 Workbench 权限为 403 `NOT_AUTHORIZED`；不可见资源和存在性统一为 404。
- Audit append 失败、锁超时或提交确认丢失为 503 `SERVICE_UNAVAILABLE`；schema、解密或程序错误为 500 `INTERNAL_ERROR`。这些响应没有敏感 body、成功 ETag 或 304，也不存在“无审计降级返回”。
- 候选不可见时排除；没有可见 Task 时返回安全零态 200，不用 404 泄露“存在他人任务”。
- 每个 GET/刷新/304 都按静态分类新增必要 Audit；安全零态按第 5.1 节豁免。读取不创建 Slot、Receipt、Event 或 Outbox。

真实 PostgreSQL/HTTP 测试必须覆盖七种卡、Draft null/DRAFT/CONFIRMED、安全零态、200/304、多 Subject Audit、重复 GET、故障注入、commit 确认丢失、跨 Tenant、伪造 If-None-Match，以及并发撤权、新 DENY、组织重挂、Task/Draft/Lead/候选变化。DIRECT 非 Owner、DELEGATED represented 非 Owner 必须拒绝；Owner Appointment/Principal/OrganizationUnit、Draft、ContactResult 及实际候选分别引用准确披露来源并使用合法授权锚点，不能漏审候选 Party/label 来源或生成不存在的 OBJECT 规则。第 N 条 Audit 插入失败时全部回滚且无 200/304；同一敏感 200/304 重复请求逐次增加必要 Audit，安全零态豁免保持零新增。独立连接要证明调用方收到 200/304 前 Audit 已提交可见；任何 append/commit 前失败都不能观测到敏感字节或成功 ETag。

除功能与并发正确性测试外，`R1BusinessFence` 必须执行第 2 节冻结的同 Tenant 小规模竞争冒烟和完整 `R1-CAPACITY-V1` 混合负载 CapacityGate；未取得与当前构建/Profile/Generator/Fixture Digest 绑定的合格证据，R1 不能被标为满足本阶段容量合同。这份证据不替代完整系统适用阶段的 C1-V1。

## 6. Worker 的可重建 due 与 `R1_PROJECTION` 闭环

### 6.1 到期任务发现与稳定恢复请求

Worker 没有 Responsibility/Query 数据库能力，因此不能直接扫描 `task_occurrence` 或 `wait_receipt`。每个 Worker 部署使用受信、启动时严格校验的 `R1_WORKER_TENANT_BINDING_V1`，为每个被服务 Tenant 绑定 mTLS 凭据别名和准确 SERVICE `tenantId`、`principalId`、`appointmentId`；HTTP 请求仍不携带 tenantId。每个证书身份（含证书指纹或受信 SPIFFE ID）只能映射一个 Tenant/Principal/Appointment，同一证书身份不得跨 Tenant 复用；一个部署服务多个 Tenant 时使用不同凭据别名。空注册表、重复 Tenant、证书/Actor 映射不唯一或 release 不匹配时 Worker 保持 not ready。

`listDueR1Tasks` 每次只查询一个冻结 `recoveryType`：CONTACT_TASK 要求 `SYSTEM_RECOVERY / CONTACT_TASK_RECOVER`，ROUTING_REVIEW_TASK 要求 `SYSTEM_RECOVERY / ROUTING_REVIEW_TASK_RECOVER`。组织 root 只来自 ActorContext 已选定 Appointment 的匹配有效直接 Grant；API 在 SQL 中以 Tenant 和这些 root 预过滤，不先跨 scope 取全量数据再在内存过滤。每个返回项必须选择并复验一张 scope 覆盖真实 Task Owner 组织的准确 Grant，再以当前 Task、Lead、Owner Appointment、最新 WaitReceipt、适用 DENY 和数据库时间完整复验；不得合并多个 Appointment，亦不得拼接多张各自不完整的 Grant 形成授权。

分页按 `(resume_due_at, task_id)` 升序，`limit` 默认 50、范围 1..100。第一页用数据库 `clock_timestamp()` 冻结 `observedAt`，只列出 `resume_due_at <= observedAt` 的准确 WAITING Task；Actor-scoped HMAC cursor 固化 Tenant、recoveryType、observedAt 和最后一个排序键，最长 5 分钟，失效时 Worker 从第一页开始。查询只返回安全技术 selector，不返回姓名、联系方式、标题、案情、Draft values、Grant 或组织 ID。

每个 `DueR1TaskCandidateV1` 精确包含 `recoveryType`、`taskId`、`expectedTaskRevision`、`waitReceiptId`、`waitReceiptHash`、`dueCutoff` 和 `idempotencyKey`。`dueCutoff` 固定等于该 WaitReceipt 的 `resumeDueAt`，所以重复扫描的 command payload 不漂移；`idempotencyKey` 由 API 按 UUIDv5 的标准 URL namespace，对字符串 `ontology-law:R1_DUE_RECOVERY_COMMAND_V1:<canonical tenant UUID>:<command type>:<task UUID>:<wait receipt UUID>:<wait receipt digest>` 计算。Worker 原样把该 UUID 作为现有单 Task recovery operation 的 `Idempotency-Key`，把其余字段映射到冻结 request DTO。

多个 Worker、重复分页、进程重启或响应丢失可发现同一候选；稳定 key 使同 selector 同 payload 重放原 Receipt。发现与执行之间 Task 已变化时，既有 CommandRuntime 的 NEW-key eligibility/重放顺序仍决定结果；发现接口本身不锁 Task、不修改 Task、不创建 Slot/Receipt/Audit/Event/Outbox，也不把一页结果当持久工作队列。无效 Owner/Appointment、越 scope 或 DENY 的 Task 不返回，并产生无敏感内容的受控运行告警，不能阻塞分页中其他合法 Task。

### 6.2 DELIVERED 的诚实语义

R1 不新增物化 CurrentCard、搜索缓存或 projection table。`R1_PROJECTION` 的 DELIVERED 精确表示：

> 当前 Worker 在有效租约与 fencing 下，请求 API 依据真实 Event/Outbox 及当前 Owner facts 完成版本 1 的投影求值；API 成功确认后，Worker 又以同一有效 claim CAS 提交了 DELIVERED。

DELIVERED 不表示某个浏览器已刷新、不表示写入第二份业务状态，也不是 Task 完成或前端读取的前置条件。CurrentCard 始终同步回源；Worker 停止或 Outbox EXHAUSTED 时，已提交业务事实仍可被 API 正确读取。

该闭环具有真实可观察效果：Outbox 状态、attempt/fencing/revision/delivered_at 被受控推进，错误 Event/来源会进入重试或 EXHAUSTED；但它不冒充可靠异步物化视图。若以后要求可查询的异步发现缓存，必须单独修改“零新增表/无状态 Query”约束并设计存储，不能把业务 JSON 塞入 Outbox 或依赖单进程内存。

### 6.3 两个受控 HTTP 修订

在同一 OpenAPI 增加：

| OperationId | Method/Path | Security | Success | Purpose |
|---|---|---|---|---|
| listDueR1Tasks | GET `/internal/v1/tasks/due` | mutualTLS | 200 `DueR1TaskPageV1` | 按一个 recoveryType 有界发现准确到期 selector |
| consumeR1Projection | POST `/internal/v1/projections/r1/consume` | mutualTLS | 204，无 body | 验证当前 claim，重读当前事实并确认本次投影求值 |

`listDueR1Tasks` 只接受 `recoveryType`、`limit` 和可选 cursor，响应只含候选列表和可选 nextCursor；其认证、授权、排序、cursor 和稳定命令键使用第 6.1 节的精确规则。

两个 operation 使用封闭错误表：

| Operation | 安全错误 | Worker 行为 |
|---|---|---|
| listDueR1Tasks | 400 VALIDATION_FAILED；401 UNAUTHENTICATED；403 NOT_AUTHORIZED；429 RATE_LIMITED；500 INTERNAL_ERROR；503 SERVICE_UNAVAILABLE | 400 丢弃 cursor 并从第一页重启；401/403 使该 Tenant binding not ready；429/5xx 有界退避 |
| consumeR1Projection | 400 VALIDATION_FAILED；401 UNAUTHENTICATED；403 NOT_AUTHORIZED；404 NOT_FOUND；409 STALE_OUTBOX_CLAIM；422 PROJECTION_EVENT_INVALID；429 RATE_LIMITED；500 INTERNAL_ERROR；503 SERVICE_UNAVAILABLE | 409 直接丢弃旧结果且不改 Outbox；400/404/422 作为永久合同错误用当前有效 claim 转 EXHAUSTED；401/403 关闭该 Tenant binding 的继续领取且不改当前 claim，由租约回收器按统一次数上限恢复 PENDING 或转 EXHAUSTED；429、5xx/网络按可重试策略，至上限转 EXHAUSTED |

`STALE_OUTBOX_CLAIM` 和 `PROJECTION_EVENT_INVALID` 只加入 internal operation 的 ProblemCode allowlist，不扩散到公共 API。错误 detail 不含 Tenant、行 ID、lease/token、Event/source、SQL 或授权规则。对这个受信 internal operation，缺少当前 Grant、scope 不覆盖、DENY、Principal/Appointment 失效等所有授权失败固定返回 403 并暂停 binding；404 只统一真实不存在与跨 Tenant selector，不能把授权失败伪装成会永久耗尽的 NOT_FOUND。

请求 DTO `ConsumeR1ProjectionV1` 只含 `domainEventOutboxId`、`domainEventId`、`expectedOutboxRevision`、`leaseOwner` 和 `fencingToken`；不含 tenantId、Event payload、source fact、业务正文或调用方选择的 queueOwner。Tenant 来自 mTLS ActorContext；API 从数据库加载真实行。

projection operation 只允许 SERVICE/SYSTEM，静态权限为 `SYSTEM_PROJECTION + R1_PROJECTION_CONSUME`，使用独立直接 Grant；recovery Grant、LEAD_CAPTURE、OBJECT ALLOW 或 HUMAN 身份不能替代。它是只读求值，不进入 CommandRuntime，不创建 Slot/Receipt/Audit/Event/Outbox，也不修改业务 Fact。API 只返回 204 或安全 Problem Details，不返回业务投影内容。

API 必须重读并验证：同 Tenant 的真实 Outbox/Event、queueOwner=`R1_PROJECTION`、status=`CLAIMED`、请求 revision/owner/token 完全相等、lease 在数据库当前时间仍有效、事件类型/版本/空 payload/摘要/source selector 均符合静态注册表。请求携带的 ID 只是候选，不能作为事实。求值事务通过 `R1BusinessFence` 取得 Tenant shared 锁，再在最终 identity shared 锁下重验 SERVICE、Grant、scope、DENY 和当前业务锚点；不能从服务任职组织或 Grant 自身 root 猜测目标 scope。

14 个事件类型使用如下封闭授权/Owner 路由：

| EventType 组 | 当前主授权 Subject | Scope 来源 | 额外准确锚点与 DENY |
|---|---|---|---|
| LeadCapturedV1 | 当前 Lead | source policy 的 intake root | Event source selector、Lead 的 R1_PROJECTION_CONSUME DENY |
| LeadIngressCompletedV1 | 当前 Lead | 完成该入口 Task 的 Owner organization | Task、Lead DENY |
| ActionDraftSavedV1 | 当前 Task | Task Owner organization | Draft 必须属于该 Task；Task、Lead DENY |
| ContactTaskReopenedV1、RoutingReviewTaskReopenedV1 | 当前 Task | Task Owner organization | 最新 WaitReceipt、Task、Lead DENY |
| LeadDuplicateResolutionRecordedV1、LeadRoutingDispositionRecordedV1、SourceIntakeStopRequestedV1、SourceIntakeStopRequestAcknowledgedV1、LeadValidityReviewedV1 | 当前 Decision | 产生该 Decision 的 Task Owner organization | Decision hash、Task、Lead DENY |
| LeadAssignedV1 | 当前 Assignment | Assignment Owner organization | Assignment→Lead/current pointer、Lead DENY |
| LeadContactResultRecordedV1、LeadContactRetryExhaustedV1 | 绑定该结果的当前 Task | Assignment Owner organization | ContactResult hash、Assignment、Lead/Task DENY |
| OpportunityOpened | 当前 Opportunity | source Assignment Owner organization | Opportunity 稳定来源、ContactResult hash、Lead DENY |

每行使用 `SYSTEM_PROJECTION / R1_PROJECTION_CONSUME / SYSTEM`，直接 Grant scope 必须覆盖表中由 Owner facts 推导的组织。多个相关 Subject 分别验证 Tenant/selector/binding；只在 `BUSINESS_SUBJECT_TYPES` 允许的 Lead/Task/Assignment/Decision/Opportunity 上应用对象 DENY，不把 Draft、WaitReceipt 或 ContactResult 伪装成可授权对象。Grant 覆盖一个组织不自动授权另一个组织的事件。

projection dispatcher 是 Tenant 级完整消费者，不是按组织无差别竞争同一 Tenant 队列的分片消费者。每个 Tenant binding 在启用 claim loop 前，必须由部署 readiness 检查证明准确 SERVICE/Appointment 的有效直接 Grant 集合覆盖该 Tenant 当前全部 R1 允许 source/Owner 组织，并保证每个事件可由其中一张完整 Grant 独立授权；不得拼接多张各自不完整的 Grant。组织、Appointment 或 Grant 变更使该 readiness 失效并重新验证。运行时 401/403 视为 binding/权限故障：立即关闭该 Tenant 的新领取，不 ack、不 failure-CAS 当前 claim；修复授权并重新通过 readiness 后才能继续。由于 `attempt_count` 已在领取时递增，当前在途领取仍消耗一次额度，过期回收器严格按第 6.4 节的统一上限恢复 PENDING 或转 EXHAUSTED；这项竞态必须告警，不能虚构“权限故障免计数”。领取前 readiness 将持续配置失配挡在队列外，但不能承诺撤权恰好发生在领取后时永不耗尽。若以后需要多个按组织授权的消费者，必须另行设计领取路由，不能让它们竞争本 Tenant 全量队列。

求值服务从各 Owner read port 读取当前状态：

- Lead/Draft/Task 事件接受当前 revision 高于事件 source revision；旧 revision 只触发重读。
- Draft 已 CONFIRMED、Task 已 DONE/CANCELLED 时不得恢复旧 Draft 或 OPEN 卡。
- Assignment、Decision、ContactResult 和 Opportunity 使用其稳定绑定重读 Lead/Task/Assignment；不可变 source hash 必须精确验证。
- `OpportunityOpened` 重读 Opportunity 的稳定 source_lead/source_assignment/source_contact_result 并验证 ContactResult，不要求 Opportunity 当前 revision 仍为 0，不创建 R2 Task。
- 求值只选择准确 selector 与完成完整性验证所需的最小字段。现有 ContactResult immutable-row hash 明确包含 `result_summary`；其字段合同把该列限定为必要的非敏感业务摘要，因此授权后在 Fact Owner 内部读取并仅用于 hash 重算属于受控内部完整性计算，不是向 HUMAN/Worker 披露：正文不得离开 Owner、进入响应或日志，Worker 只得到 204。若未来该列分类变为敏感、hash 引入敏感字段或需要解密，必须先采用第 5 节等价的系统读取 Audit，或重新设计 Event selector；不得跳过精确 hash 验证。联系方式、Draft values 和案情始终不返回 Worker、不进入日志。

旧事件不能直接写业务状态或向 SPA 推送 payload。SPA 对并发 GET 使用 generation/AbortController：先发的旧请求晚返回时不得覆盖更新后的 envelope；不能只比较不同 Task 的 revision。

### 6.4 Outbox 状态机、租约与 fencing

静态 `R1_PROJECTION_DELIVERY_V1` profile 固定：最大并发 4、每次最多领取 4、poll interval 1 秒、lease 60 秒、HTTP timeout 10 秒、最大累计领取 8 次、七次可重试延迟依次为 1 秒、5 秒、30 秒、2 分钟、10 分钟、30 分钟、2 小时。只有空闲执行 permit 才领取一行，claim 提交后立即发出；本地等待队列不得预占租约，也不实现无围栏续租。发送前若本地时钟已显示租约到期则不发，交由数据库回收器裁定。测试时只允许通过注入时钟/调度器加速，不改变状态语义。

Worker 使用 execution 暴露的具名 `R1ProjectionOutboxPort`；jOOQ 实现在 execution 的 `internal.persistence`。Worker 模块只依赖 execution DTO/port 及自己的 HTTP transport，不导入 query 或任何 Owner jOOQ 类型。

状态推进规则：

1. 按 Tenant、queueOwner 和 `(available_at, domain_event_outbox_id)` 有界排序，以 `FOR UPDATE SKIP LOCKED` 领取 PENDING。
2. `PENDING → CLAIMED` 时 revision、fencing_token、attempt_count 各加一，写 lease_owner/lease_until，短事务提交后才发 HTTP。
3. 当前持租 Worker 的完成或失败 CAS 同时比较 Tenant、Outbox ID、CLAIMED、expected revision、lease_owner、fencing_token 及 `lease_until > clock_timestamp()`；影响行数为零就丢弃旧结果。
4. 204 后 `CLAIMED → DELIVERED`，清 lease、清 last_error_code、写 delivered_at、revision 加一；DELIVERED 永不再改。
5. 网络错误、408/429/5xx 和可恢复 API 不可用在未达 8 次时 `CLAIMED → PENDING`，清 lease、写下一 available_at、安全 last_error_code、revision 加一；达到上限转 EXHAUSTED。
6. 400/404/422 所代表的无效事件合同、来源不存在及第 6.3 节永久错误转 EXHAUSTED；409 STALE_OUTBOX_CLAIM 只丢弃旧结果，不再尝试修改已经失去的 claim。401/403 不修改 claim，按第 6.3 节冻结 Tenant binding 并等待租约回收；当前领取已计数，回收时仍执行同一个八次上限。不保存异常原文、payload 或业务数据。
7. API 204 响应丢失或 Worker 在 ack 前崩溃，会由租约过期后重新读取当前事实；重复求值无业务写入，因此安全。

回收器与旧 Worker 结果使用不同 CAS。回收器以 `FOR UPDATE SKIP LOCKED` 锁住过期行，匹配 Tenant、当前 revision、lease_owner、fencing_token 和 `lease_until <= clock_timestamp()`；未达到上限时执行 `CLAIMED → PENDING`、清 lease、按本次 attempt 写 retry available_at/安全错误码并使 revision 加一，之后只能再走正常 PENDING claim。达到上限时以同一过期条件 `CLAIMED → EXHAUSTED`、清 lease、revision 加一。数据库更新守卫不允许 `CLAIMED → CLAIMED` 时换 owner 或递增 token/attempt，因此绝不直接“过期换 owner”；旧 Worker 随后的未过期 CAS 和旧 revision/token 都影响零行。

EXHAUSTED 在 R1 不自动重驱。现有数据库只允许授权 CommandRuntime 将 EXHAUSTED 原位恢复为 PENDING；本轮不发布通用重驱入口，也不让 Worker 越权自重驱。EXHAUSTED 必须进入指标、日志安全码和 E2E 证据，不能静默丢弃。未来若交付重驱命令，须另行冻结累计 attempt 的新一轮额度语义。

### 6.5 Worker 必测项

- due discovery 的双 recoveryType、组织 scope/DENY、分页边界/cursor 篡改或过期、稳定 dueCutoff/idempotencyKey、重复扫描、多 Worker 和重启恢复；同 Principal 的另一 Appointment 即使持有更宽 scope，也不能扩大当前 Appointment 的结果。
- 多 Worker 竞争 Outbox、不同 Tenant 隔离、未到 available_at 不领取、只处理 R1_PROJECTION。
- claim 三个计数、过期回收、旧 owner/token/revision/过期 lease 的结果全部无效；有效结果路径与回收路径的时间谓词互斥。
- 一个批次中四个请求均接近 10 秒 timeout 时不发生“领取后本地排队过期”；无 permit 不领取。
- API 不可用、成功响应丢失、ack 前崩溃、有限重试至 EXHAUSTED、Worker 自重驱被数据库拒绝；Tenant dispatcher 覆盖不全时不得开始领取。授权失败必须为 403 而不伪装成永久 404；运行时撤销 projection Grant 后，当前 claim 在未达上限时回到 PENDING、恰为第八次时转 EXHAUSTED 并告警；后续领取停止，恢复 readiness 后仅未耗尽行可自动继续，并覆盖第八次失败加 Worker 重启的状态机测试。
- 伪造 Outbox/Event/source、未知版本、非空 payload、错 queueOwner、缺 source 或 hash 不一致不 DELIVERED。
- 14 个 EventType 全路由；延迟、重复、乱序和双 Worker 交错。
- DraftSaved r1 晚于 r2、晚于 CONFIRMED/DONE；TaskReopened 旧通知晚于 DONE；旧 Lead/Assignment/Opportunity revision。
- 消费前后 Lead/Task/Draft/Decision/ContactResult/Opportunity/Receipt/Audit/Event 行数与内容零变化；只有目标 Outbox 技术状态变化。ContactResult hash 测试还要证明 `result_summary` 参与精确重算但不会离开 Owner、响应或日志。
- CurrentCard 直接 HTTP 查询证明 UI 不依赖 Worker 内存或 DELIVERED。

## 7. Task 5–10 的对齐执行顺序

本规格确认原计划的业务顺序正确，不重写已完成的 Task 1–4。后续实施计划必须把以下增量嵌入 Task 5–10，而不是另开一条不相交的“修补线”：

| 阶段 | 原交付 | 本次必须补入的闭环 | 阶段退出条件 |
|---|---|---|---|
| 合同前置 | 已冻结的 R1 合同 | ADR、SERVICE capture、敏感披露、两个 internal operation、due/projection profile、verifier 和基线版本 | 合同测试先红后绿，活动文档无相互冲突 |
| Task 5 | Lead 接入、P0-01～P0-04 | HUMAN/SERVICE 双 capture、实例 envelope、来源绑定、CommandRuntime 集中接入 R1BusinessFence | 四组 Owner 实库、授权/并发/故障测试通过 |
| Task 6 | CurrentCard、ActionDraft | SensitiveReadRuntime、ReadDisclosureEntry、共享读取锁、200/304 审计门禁 | 七卡/Draft/多 Subject Audit 与故障测试通过 |
| Task 7 | ContactResult、重试、复核、Opportunity | 保持原事实/事件矩阵；所有可见性写入遵循新业务锁前缀 | 三组实库、两事件原子性、WAITING 测试通过 |
| Task 8 | API、安全、due Worker | 真实 OAuth2/mTLS Actor mapper、11 public + 4 internal、due discovery、Outbox port/consumer、API/Worker 装配隔离 | 15 operation HTTP、双角色、due/租约/fencing IT 通过 |
| Task 9 | 单 SPA 工作台 | 去除零测试豁免、请求 generation、200/304、Receipt 恢复和真实错误状态 | Vitest 非零、typecheck、build、可访问性/响应式通过 |
| Task 10 | 真实联合验收 | SERVICE capture、敏感 Audit、due 重启发现、投影延迟/重试/EXHAUSTED、完整 CI 与证据 | Playwright 全矩阵、数据库 delta、证据与台账同时通过 |

每个阶段使用测试先行：先写能证明缺口的失败测试，再实现最小生产代码，再运行该层与受影响回归测试。每个阶段单独提交并进行需求符合性与代码质量复核；不得在最后一次性堆叠所有业务功能，也不得通过放宽合同/删除断言获得绿色。

## 8. API、Worker 与 SPA 的生产装配

### 8.1 API

- Spring Security 使用 OAuth2 Resource Server 验证 public Bearer，使用独立 mTLS trust boundary 验证四个 internal operation；测试 JWT 只能存在于 test profile。
- `ActorContextResolver` 只产出可信候选，数据库在每次业务调用中实时复核 Principal/Appointment/Grant/kind；安全配置不把 JWT scope 当 authority code。
- OpenAPI 生成 Delegate 只做 wire DTO、header 和 Problem Details 映射，调用具名 application service；Controller/API 包不能依赖 jOOQ 或 Owner internal。
- 所有命令把 `Idempotency-Key`、Task/Draft ETag 和 ActorContext 映射到冻结类型；GET 的审计失败绝不进入响应序列化。
- `getCommandReceipt` 继续按当前 Actor scope 复验，不因知道 CommandId 就披露另一 Actor 的结果。

### 8.2 Worker

- due scheduler 先通过 mTLS `listDueR1Tasks` 取得有界、安全、可重建的 selector，再调用两个既有 recovery operation；一次命令请求只恢复一张准确 Task/WaitReceipt，不批处理，不直接写 Task。发现结果的稳定 idempotencyKey 原样复用，重启不依赖进程内遗留队列。
- projection scheduler 只有 Worker DB 能力：SELECT Event/Outbox 和白名单 UPDATE Outbox；没有 Command/Query/Audit 数据库能力。
- Worker 缺少 API、证书、release/registry 匹配或数据库安全门禁时保持 not ready，不把“请求已发”记为成功。
- API profile 不装配 scheduler/worker database port；Worker profile 不装配 Controller/Command runtime。ArchitectureTest 和启动 IT 同时证明 Bean 与角色隔离。

### 8.3 SPA

- 首页必须真实渲染一句今日摘要、一张展开卡、一个主按钮、最多两条下一摘要、等待计数及固定 Chat Composer；不再保留空 `<main>`。
- 七个 discriminator 使用静态组件/表单；未知 discriminator 显示安全不可用状态，不能执行服务器下发的任意 schema。
- 保存 Draft 使用 If-None-Match/If-Match，提交主命令使用 Task ETag 与服务器签发的 Draft digest；未知提交结果按原 CommandId 轮询 Receipt。
- 页面载入、命令/Receipt 成功、窗口重新聚焦及有界 WAITING 轮询重新 GET CurrentCard。ETag 304 保留现有 envelope，但仍由服务器完成审计重验证。
- 并发 GET 使用递增 generation 或 AbortController；旧请求晚返回不能覆盖新卡。提交防双击只是 UX，幂等仍由 CommandRuntime 保证。
- 原始 Task/Event/Decision/hash/WAITING 代码、Tenant、Grant、密文/HMAC、内部错误不得显示；360/768/1440、键盘焦点、ARIA 错误关联、aria-live 和颜色对比纳入测试。

## 9. 不允许假阳性的完整 CI

新增 `.github/workflows/r1-vertical-slice.yml`，对 pull_request 与 main push 无 `paths` 过滤。它是 R1 唯一 required aggregate；现有 baseline/schema workflow 移除路径过滤或明确为辅助检查，不能让局部绿色被解释成完整 R1 绿色。

严格顺序为：

1. baseline verifier 单测及真实 `verify_baseline.py`；
2. schema generate `--check`、schema tests、generated SQL 校验；
3. 锁定 PostgreSQL 18 RepoDigest 的 v1.1 runtime harness 与两次空库验证；
4. backend Surefire/Architecture 与 Failsafe/Testcontainers，随后 jOOQ drift；
5. OpenAPI Java/TypeScript 生成一致性及 `git diff --exit-code`；
6. Workbench Vitest、typecheck、build；
7. 真实 compose 的 Playwright 黄金、失败、等待、SERVICE/audit/projection 路径；
8. 证据完整性与 BranchID→测试映射；
9. always-run aggregate 检查前八层结果全部为 success，skipped/cancelled/failure 均失败。

门禁还必须满足：

- `apps/workbench` 删除 `vitest run --passWithNoTests`，改为 `vitest run`；Surefire 和 Failsafe 都显式在零测试时失败。
- Playwright 有锁定依赖、浏览器版本和根 `test:e2e` 命令；测试数为零、全部 skip、真实后端未启动或 fixture 未加载均失败。
- 含测试的层生成机器可读报告并断言实际执行数大于零、必需用例无 skipped；生成/漂移/typecheck/build 层改为断言具名命令成功、期望输出存在且工作树零漂移，不伪造“测试数”。
- BranchID→测试映射必须关联本次实际成功的报告用例；静态存在但未执行、失败或 skipped 的映射不计覆盖。
- 成功与失败证据都脱敏上传，包含 Git SHA、镜像 digest、Java/Node/PostgreSQL 版本、OpenAPI/合同摘要、命令、退出码和测试计数；缺文件使 artifact step 失败。
- CI 不保存联系方式、Draft values、密文、HMAC、JWT、证书私钥或数据库凭据。
- always-run aggregate 同时检查证据/artifact 上传 job；缺文件导致的上传失败不能被前面测试绿色掩盖。任一层失败都不推进台账；只有完整链成功才把 R1-OPENAPI/R1-BACKEND/R1-SPA 记为 IMPLEMENTED，把黄金/失败 E2E 记为 RUNTIME_VERIFIED。
- `R1BusinessFence` 属于 CurrentCard/热点路径变化，必须使旧 R1 容量认证失效并触发独立 `R1-CAPACITY-V1` CapacityGate。常规 PR workflow 运行小规模同 Tenant 竞争冒烟并产出 trigger manifest；本阶段 ReleaseGate 只有在与当前 Build/Profile/Generator/Fixture Digest 精确绑定的 R1 CapacityGate receipt 合格时才允许推进，不把 30 分钟完整容量测试伪装成每次 PR 的普通单测。该 receipt 不得登记为完整 C1-V1；完整系统发布仍按原合同验收。

## 10. 验收矩阵

| 能力 | 单元/静态 | PostgreSQL/HTTP | 浏览器/联合 | 允许推进的状态 |
|---|---|---|---|---|
| SERVICE capture | 映射注册表、envelope、合同 verifier | token/kind/Grant/source/DENY/重放/回滚 | API 真实 capture 并在 SPA 出现正确人类卡 | R1-OPENAPI + R1-BACKEND |
| Lead P0-01～04 | policy/候选/分支 | 全 BranchID、并发、跨租户、delta | 人工卡完成及后继卡出现 | R1-BACKEND + R1-SPA |
| CurrentCard/Draft | 七 discriminator、ETag、摘要 allowlist | Audit commit-before-return、200/304、版本竞态 | 保存、刷新恢复、确认、错误恢复 | R1-BACKEND + R1-SPA |
| Contact/WAITING/Review | retry/calendar/event exact set | ContactResult/Opportunity/WaitReceipt 原子与 due recovery | 黄金、等待和三种复核结果 | R1-E2E-GOLDEN/FAILURES |
| Due + projection Worker | due 授权/分页/稳定 key、14 事件路由、失败分类 | 重启发现、claim/fencing/过期/乱序/EXHAUSTED、零业务 delta | UI 在 Worker 中断时仍同步正确 | R1-BACKEND + R1-E2E-FAILURES |
| 架构与安全 | Modulith/ArchUnit/OpenAPI/verifier | 真实 capability role、api/worker 启动隔离 | 浏览器无数据库凭据/内部入口不可达 | 全部 R1 状态的前置门禁 |

测试替身可以用于先写单元测试，但最终验收不得以 H2、mock backend、伪 AuditAppender、内存 Outbox、空 Worker 或静态 SPA fixture 替代 PostgreSQL/API/Worker/浏览器联合证据。

## 11. 活动合同修订清单

本文件获批后，实施前置提交必须一起更新并机械校验：

- 新增 ADR，明确替代 ADR-0006/0007 中 capture 只按 CommandType 取 envelope、HUMAN-only capture、13-operation、缺少 due discovery 及未具名消费语义的局部条款；其他部分继续有效。
- [R1 命令授权及事件合同](../../contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md)：增加 HUMAN/SERVICE 两条 capture 映射与消费确认语义。
- [R1 HTTP/错误合同](../../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)：增加 `listDueR1Tasks`、`consumeR1Projection`、mTLS/Grant、请求/响应形状及安全错误；operation 总数改为 15。
- [R1 Workbench 展示合同](../../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)：增加敏感分类、200/304 披露 Audit、缓存和请求 generation 规则。
- [OpenAPI](../../../contracts/openapi/ontology-law-api.yaml)：capture 描述允许已验证 HUMAN/SERVICE Bearer，新增两个 internal operation/DTO，不改变既有 DTO shape。
- [运行时验证合同](../../../database/schema-contract-52-plus-2/docs/runtime-validation-contract.md)：把仍称“未冻结非完成命令事件”的过时表述同步为现行完整 R1 事件集合，并补读取稳定锁/消费语义；不改生成迁移。
- 当前基线、Task 完成矩阵、delivery ledger、baseline verifiers 及测试：统一版本、operation 数、SERVICE capture、披露 Audit 和 R1_PROJECTION 定义。
- 原 Task 5–10 计划保留历史，不原地伪造已完成勾选；另写执行计划引用本规格并逐任务给出测试、文件、命令和 commit 边界。

所有活动文件必须在同一合同提交中一致改变，不能只改 OpenAPI、只放宽 verifier 或只补说明文字。合同提交不实现生产业务，也不能推进业务台账。

## 12. 书面规格自审

- 核心产品和架构没有改向：责任卡、Fact 真源、强一致 CurrentCard、原子命令、Owner 边界和单体部署均保持。
- SERVICE capture 恢复的是最初允许的受信入口；它不获得人工 Task、Workbench、recovery 或 Provider 权限。
- 敏感读取 Audit 是既有强制运行时合同的落地；200 和 304 都不存在无审计旁路。
- 读取稳定性不是口头承诺：明确了 R1 Tenant 共享/排他业务锁、原业务锁相对顺序、identity 锁和最终重读。
- Tenant 锁的正确性与容量结论没有混写：锁方案必须经过独立 `R1-CAPACITY-V1` 混合负载认证，失败即重开锁粒度设计，不能降低 SLO 或提前宣称完成；该证据不冒充完整 C1-V1。
- due scheduler 有可分页、可重建、稳定幂等的发现通路；不再依赖测试预喂 Task ID 或 Worker 进程内状态。
- R1_PROJECTION 有真实 claim、跨进程 API 求值、确认和 DELIVERED，但没有虚构持久化投影或前端刷新承诺；Tenant dispatcher 覆盖在领取前验证，权限故障不再永久耗尽合法事件。
- 13→15 operation、独立 projection authority code 和锁顺序前缀是明确、可审查的受控修订，没有偷偷混入实现。
- 52＋2 物理模型、V001–V850、模块 DAG、Worker 数据库能力和无状态 Query 均未扩大。
- Task 5–10、SPA、Worker、Playwright 和 CI 都有退出条件；任何底座测试不能单独冒充基础功能完成。
- Provider 真实发送、AI、管理 UI 和 R2+ 明确不在 R1，不因排除而误报为完整销售 MVP。

本文件已获书面批准，进入详细实施计划与测试先行执行。活动合同须先按第 11 节统一修订；规格批准和合同修订均不构成业务实现或运行验收证据。
