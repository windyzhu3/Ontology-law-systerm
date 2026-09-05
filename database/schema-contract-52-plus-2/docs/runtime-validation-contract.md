# 52＋2 运行时重验合同

## 1. 目的与适用范围

本文件是[当前MVP语义基线](../../../docs/baseline/CURRENT-MVP-BASELINE.md)与52＋2结构/物理合同的从属运行时补充，规定API、CommandRuntime、各 Fact Owner、Query Facade、Dispatcher、ProviderIngress 与 DeploymentRuntime共同执行的验证。这里的“必须”“不得”只在上位基线已定义的边界内构成运行时要求；如与上位基线冲突，验证必须失败关闭，不得据此放宽或改写上位规则。

物理 DDL 可以证明字段形态、复合外键、唯一性、状态转换、CAS 修订号、不可变/允许更新列以及少量稳定跨行关系；它不能证明当前授权、远端系统真实结果、对象存储内容、自然时间是否仍有效或跨领域业务结论。运行时不得把“数据库允许提交”解释为“业务条件成立”。

本合同不引入表、动态配置表、通用 Workflow/Saga/Job、ConsumerInbox、Matter、文档库、全局 Evidence 搜索或数据库内策略引擎。静态信封、Handler、Fact Resolver、事件 Schema、队列路由、重试策略、权限代码、authoritySlot 和数据披露分类均由版本化代码注册。

## 2. 总体判定顺序

所有业务命令、敏感读取和披露都按下列顺序执行；任何一步失败立即停止，禁止以“稍后补验”放行。

1. 认证实际 Principal，建立 `tenant_id`、Correlation、可信时间和执行上下文。
2. 从静态路由取得唯一命令/查询合同，不接受请求提供表名、Resolver、authoritySlot、队列 Owner 或 Provider 适配器。
3. 执行四轴授权并解析唯一 Appointment 路径。
4. 以同租户 Resolver 加载准确 Subject/Fact 及其 revision 或 hash。
5. 校验 Fact Owner 的业务前置条件、输入 Schema、自然幂等键和预期 CAS。
6. 对写命令开启 `READ COMMITTED` 短事务，锁定必要锚点或使用唯一约束/CAS 串行化竞争。
7. 在写入前再次执行四轴授权、Appointment、组织树 Scope、Subject 版本和领域前置条件复验。
8. 按终局分支一次提交：`SUCCEEDED` 新事实分支同事务写 Slot、领域 Fact/CAS、Audit、事件/Outbox 与 Receipt；`NO_CHANGE` 写 Slot、Audit 和引用既有准确 Fact 的 Receipt，不造事件/Outbox；`REJECTED` 只写 Slot、拒绝 Audit 和无结果 Fact 的 Receipt。技术失败整体回滚。

禁止在数据库事务内等待用户输入、调用 Provider、传输对象字节或进行长时扫描。外部边界只通过已经冻结的 ExternalAction、ProviderInbox、Evidence 接收事实和新命令衔接。

## 3. 四轴实时授权

四轴必须作为一次合成判定执行，不能把任一轴缓存为长期授权结果：

| 轴 | 必须验证的事实 |
|---|---|
| 租户轴 | 认证上下文、命令/查询、Appointment、授权事实、Subject 和全部加载行属于同一 `tenant_id`；所有 Repository 查询显式携带租户谓词 |
| 主体轴 | 实际 Principal 处于可用状态；使用的 Appointment 确实属于该 Principal、当前有效且只选择一条路径；代办时同时验证实际主体与 on-behalf-of 主体 |
| Scope 轴 | 根据提交时当前组织邻接树解释授权 Scope；组织树无长环，节点有效；Delegation 不宽于来源 Grant；对象规则严格先限后允，准确对象上的有效 `DENY` 先于任何 `ALLOW` |
| 行为轴 | 静态 command/action/purpose、authoritySlot、Subject 类型与准确 revision/hash 相匹配；对象级规则、直接授权或委托只沿最终选定的同一 Appointment 路径成立 |

授权判定只允许一个可审计路径，例如 `DIRECT`、`DELEGATED`、`OBJECT` 或受控 `SYSTEM`。不得拼接两条各自不完整的路径来满足一个命令。提交时在 `AuditEntry` 冻结实际 Actor/Appointment、on-behalf-of、唯一授权槽、路径、Scope、准确授权 Fact 和 `authorization_snapshot_digest`。

Command的自然有效期按[ADR-0006](../../../docs/adr/ADR-0006-command-runtime-authorization-boundary.md)在最终持锁完整复验的`clock_timestamp()`裁定，不保证物理COMMIT瞬间仍有效。最终复验前取得具名Tenant共享advisory事务锁，持有至事务结束。所有未来Identity writer须在任何身份变更之前取得对应排他锁，之后不得获取业务锁；复验前已提交的撤销、新DENY、组织重挂必须被观察，复验后的写者等待。禁止使用事务起点`now()`代替新鲜时钟，也不得宣称锁阻止自然到期。

下列时点必须重新执行完整四轴判定：业务写提交前、Evidence 最终晋级、Evidence 下载、Finding/审计披露、审计查询或导出，以及任何依赖既有 Decision 的最终放行。授权在请求开始后被撤销、Appointment 失效、组织树变化或 Subject revision/hash 变化时，当前操作必须拒绝或回滚。

## 4. 类型化准确引用

类型代码固定为未加引号的 `schema.table`，允许目标来自 `contract/reference_registry.py`。每个静态引用槽执行以下算法：

1. 由服务器根据槽位选择 Resolver；请求只能提交类型代码和准确标识/选择器，不能选择 SQL 或解析器。
2. 验证类型在该槽的允许列表中，并验证 `id` 存在。
3. 强制 `tenant_id` 相同。
4. `revision` 与 `hash` 必须恰有一个存在；加载目标 Owner 的准确行并比较对应值。
5. 校验目标类型对当前命令、目的和生命周期仍有效。
6. 在提交前重新加载或以 CAS/锁证明选择器仍未改变。

Resolver 失败属于拒绝或技术失败，绝不能降级为只按 `(type,id)` 关联。类型化引用不创建额外 FactIdentity 表，也不在数据库中动态拼接表名。

## 5. CommandRuntime 原子合同

### 5.1 静态接入

四类命令信封、Handler、Fact Resolver、事件 Schema、路由与重试策略只能由代码注册。信封至少携带 Tenant、静态类型、CommandId、Correlation、Actor/Appointment、预期 Subject 选择器、命令 Scope 摘要和规范 payload 摘要。

R1静态映射：INTERNAL_TASK对应七个Task主命令与SAVE_ACTION_DRAFT，INTERNAL_ADMIN仅对应CAPTURE_LEAD且不赋管理员权力，SERVICE_ACTOR对应两个具名恢复命令并要求真实SERVICE Principal及完整授权；CUSTOMER_GRANT没有R1命令。缺失Handler或静态policy失败关闭，不能由调用方选择信封、authority或事件描述。

`CommandExecutionSlot` 以 Tenant、静态信封类型、命令 Scope 摘要和 CommandId 形成永久占位。相同占位键与相同 `payloadDigest` 返回既有终局；同Tenant CommandId的payload、Scope或信封冲突返回原Receipt安全引用，所有新增delta（包括Audit）为零，不能覆盖Slot或另建等价幂等表。

### 5.2 短事务

一个产生新领域事实的成功命令，其提交边界固定为：

- `CommandExecutionSlot`；
- 准确 Fact Owner 的领域写入和必要受控锚点 CAS；
- `AuditEntry`；
- 指向一个准确 source Fact 的 `DomainEvent`；
- 每个静态 `queueOwner` 的一行 `DomainEventOutbox`；
- 唯一不可变 `CommandReceipt`。

这些写入在同一 `READ COMMITTED` 短事务一次提交。`NO_CHANGE` 必须解析并引用既有准确结果 Fact，不为“无变化”伪造新事实或事件；`REJECTED` 只提交永久 Slot、拒绝 Audit 和无结果 Fact 的 Receipt，不生成虚假领域事件或 Outbox。并发正确性使用唯一约束、显式行锁、预期 `revision` 和 Fact Owner 自然幂等键；不依赖提高全局隔离级别。

`CommandReceipt` 只有 `SUCCEEDED`、`NO_CHANGE`、`REJECTED` 三个终态。前两者必须准确引用一个结果 Fact；`REJECTED` 不得引用结果 Fact。确认提交前的连接中断、锁超时、进程崩溃、SQL等技术故障整体回滚，不生成Receipt；但COMMIT确认丢失无法证明数据库未提交，只能用同一CommandId和相同摘要恢复原Receipt。不得伪造FAILED/UNKNOWN Receipt或把确认丢失宣称为已证明全零。

Handler的NO_CHANGE或post-write业务拒绝必须先回滚领域savepoint，不能残留暂存Fact/Task/Draft/Event写入。成功通知为不可变列表，各项可有独立准确sourceFact；Receipt仍只有一个结果Fact，所有通知及对应Outbox同事务提交。当前事件描述只允许Task矩阵具名事件，版本1、空对象通知payload、R1_PROJECTION Owner；非完成命令与OpportunityOpened的完整生产描述未冻结前不注册对应成功Handler。

`DomainEvent` 是事实通知，不复制领域真相，不承担事件溯源。它的 Outbox 只做 Owner 定向的 at-least-once 投递；消费者必须按准确来源 Fact 自行读取当前可见结果。

## 6. AuditAppender 与受控披露

所有业务写及其结果、授权/管理事件、高风险拒绝、敏感读取、Decision 与授权依据、审计查询和导出都追加一条不可变 `AuditEntry`：

- 业务写的审计必须由固定 AuditAppender 在同一短事务同步追加，失败则业务写回滚。
- 需要审计的拒绝、敏感读取、Finding/Evidence/审计披露和审计导出必须先提交审计事务，再返回结果或字节；审计提交失败时不披露。同key重放/冲突以及C0明确的pre-insert零写拒绝不新增Audit，不受本句扩大解释。
- 记录只含准确 Scope/Tenant、实际 Actor/Appointment、on-behalf-of、Action、Subject 版本、Command/Correlation、唯一授权路径快照、结果、可信执行上下文和允许列表摘要。
- 不得保存完整领域事实、原始请求/响应、密码、Token、Secret、文档正文或非必要案情。
- 修正只追加 `CORRECTION` 并引用链上最近一条记录；原记录不得更新、隐藏或删除。

Audit 只对“谁在什么授权和上下文下做了什么”权威；业务事实仍由对应 Owner 解释。

## 7. Responsibility 运行时合同

`TaskOccurrence` 是一张责任卡：一个准确 Subject revision/hash、一个 Owner Appointment、一个业务目的、一个固定主命令、一个预期完成 Fact 类型和一份原始 SLA。运行时必须保证：

- `OPEN ↔ WAITING` 只表示同一责任仍在延续；每次进入 `WAITING` 都追加新的无状态 `WaitReceipt`，不修改原 SLA。
- 只有准确 Fact Owner 在同事务写入符合冻结类型和 Subject 的完成 Fact，Task 才能一次进入 `DONE`。
- ExternalAction 状态、CommandReceipt、ActionDraft 确认或用户点击不直接完成 Task。
- 改派、退回、补正、重试、恢复后行动或原 Owner 仍需行动时，取消/完成旧 Task 并新建固定类型 Task；旧 Task 不重开、不重置 SLA、不复用。
- `DecisionRecord` 按 Task 追加不可变版本，前序必须准确；新决定不覆盖旧决定。
- 每个 Task 至多一份 `ActionDraft`；确认只冻结候选输入摘要，不证明主命令执行成功。

外部结果不确定属于 ExternalAction，命令终局属于 CommandReceipt，候选输入属于 ActionDraft；不得为这些概念新增责任表或 RecoveryEpisode。

## 8. Evidence 接收、晋级与下载

Evidence 严格按 `UploadSession → ReceivedSourceObject → EvidenceSubmission → EvidenceBinding` 一对一晋级：

1. 服务器创建 Session，冻结随机 Opaque Key、准确目标 revision/hash、用途及版本化接收合同；一个 Session 只接受一个文件。
2. 私有对象存储以 create-only 方式单次写入原始字节。客户端不能指定存储键、Bucket、ObjectVersion、Hash、媒体类型或扫描结果。
3. EvidenceIngress 从对象存储读取准确 ObjectVersion，计算服务端 SHA-256，识别真实媒体类型并完成可信安全扫描；结果写为唯一不可变 SourceObject。`PASSED` 只表示技术门禁通过。
4. 最终晋级时，CommandRuntime 重新读取准确对象版本，复验扫描、接收合同、四轴授权和目标 revision/hash；随后在同一短事务创建不可变 Submission、唯一 Binding，并把 Session 置为 `FINALIZED`。
5. 相同 Hash 不合并；换目标、换用途、补正或重新上传都创建新 Session 和新 Submission。EvidenceRef 只引用准确 Submission。
6. Binding 的目标和用途不可修改；撤回必须经授权命令单向写入撤回槽，撤回后不能恢复或移动。

下载只能通过应用网关：解析 Submission 与仍有效 Binding，重新执行四轴授权和 Subject 版本检查，先提交敏感读取 Audit，再从私有对象存储读取准确 ObjectVersion 并流式返回。禁止签发绕过应用网关长期有效的公开 URL。

`OBJECT_RECEIVED`、`PASSED`、`FINALIZED`、Submission 和 Binding 均不代表业务 `VERIFIED`、合同签署有效、付款到账或 Task 完成；结论归对应 Fact Owner。

## 9. External Action 与 Provider

`ExternalAction` 一行只表示一个稳定 `intentKey` 下的一次 `attemptNo`，冻结版本化动作合同、Provider 账号、规范请求 Envelope/摘要和 Provider 幂等键。运行时必须遵循：

- 第一次可能跨越网络边界前，先持久化准确 Action 与 `DISPATCH` Outbox。Dispatcher 领取租约并校验围栏后，必须在调用 Provider **之前**通过固定内部命令/CAS提交 `PENDING → DISPATCHED`、准确 `dispatched_at` 及对应 Audit；只有该提交成功才允许发包。此后超时、崩溃或租约丢失一律按“可能已越界”收敛为 `UNKNOWN` 并启用 `PROBE`，禁止把 CLAIMED 工作项恢复后盲目重发。
- `DISPATCH` 与 `PROBE` 对每个 Action 各至多一个 Outbox 工作项。租约领取必须比较 revision 和 fencing token；过期 Worker 的结果无权提交。
- `UNKNOWN` 不能恢复 `PENDING`，只能由验签 `ProviderInbox`、无副作用权威 `PROBE`，或引用准确 `DecisionRecord` 的授权裁决收敛为 `SUCCEEDED`/`FAILED`。
- 再次实施效果必须在一个短事务内锁定旧 Action（`FOR UPDATE` 或等价 revision CAS），由可信 Inbox、无副作用 PROBE 或准确授权 Decision 先把 `UNKNOWN` 单向收敛为能够证明效果未发生的 `FAILED`，随后才创建相同 `intentKey`、更大 `attemptNo` 的新 ExternalAction；两步任一失败整体回滚。仅凭事务外读取或仍为 `UNKNOWN` 时不得创建下一尝试，晚到 Provider 成功必须与该根锁/CAS 串行化。

ProviderIngress 必须按静态账号绑定验证 Provider、验签、时间窗口、Nonce 和消息 Schema，再以 `(tenant_id, provider_account_id, provider_event_id)` 去重。同 Key 同 Hash 返回原接收结果；同 Key 异 Hash 先审计并隔离，不推进 Action。只有能准确关联 Action、Subject 和账号的消息，才可通过固定内部命令推进状态。

Provider 不是 Actor，不能直接创建 Task、领域 Fact 或 ServiceActor 命令。通过校验的消息只是因果输入；只有 ProviderIngress 以自身受控服务身份调用静态内部命令，才可由准确 Fact Owner 推进状态。外部 `SUCCEEDED` 只证明该外部尝试按可信依据收敛，不代表实际送达、签署有效、到账或任何业务 Task 完成。

## 10. 业务主链提交前重验

| Fact Owner | 最终写入前必须额外证明的事项 | 明确不能推导的结论 |
|---|---|---|
| PartyRuntime | 规范名称和至多一个受保护主标识满足字段用途；疑似重复只经人工确认；合并时锁定源/目标并证明目标仍为未合并的活动 Party，结果保持一跳且不形成环 | Party 不是客户角色、联系信息、地址、代表关系或身份历史的 Owner |
| LeadRuntime | Contact Task、当前 Assignment、准确 Owner 和 contactNo；`CONNECTED_VALID` 时同事务形成 Qualified Lead 与唯一 Opportunity | 点击拨号、消息已发或 ExternalAction 未决不等于联系结果 |
| OpportunityRuntime | 单项法律需求、来源 Assignment 路径、完整 Participation revision/digest；QuoteRevision 与 Scope/Line/PaymentTerm 同事务封包；全部静态 authoritySlot 的准确授权 Decision、最终授权和权威发送证明通过后，才逐收件人创建 QuoteIssue | Quote 发出或 `ACCEPTED` 不等于冲突通过、合同成立或付款到账 |
| ConflictReviewRuntime | purpose 专用的触发 Fact、法律需求、完整 Party 集合、来源 revision/hash、规则版本、语料水位及 `scopeHash`；Review/Party/Finding 同事务封存；所有 Finding/authoritySlot Decision 仍适用 | `CLEAR` 只允许完整输入且零 Finding；Resolved 不等于合同或转案成立 |
| ContractRuntime | 创建Contract锚点时锁定准确QuoteIssue并证明Response=`ACCEPTED`、Response→Issue→QuoteRevision同链、Quote属于本Opportunity、Response仍为最新、Issue未撤回/未被替代且Quote在可信提交时刻未自然失效；该接受链被锚点消费后，后续ContractRevision只证明仍引用同一历史来源，不因报价后来到期重复拒绝。正文对象版本、Participation、FeeTerm、PaymentGate、SignaturePlan及PRE_CONTRACT Review全部进入同一`contentDigest`；签署新增/撤回与Execution锁同一Contract根，且仅允许`contract_execution_id IS NULL`、`contract_termination_id IS NULL`；批准、审查、必需且未撤回的准确内容签名、印章和归档条件在Execution提交前复验 | ContractExecution 不等于首款到账或 DealActivated |
| ContractRuntime | PaymentConfirmation 来自可信 ProviderInbox 或准确 Evidence，且归属合同/版本；PaymentGate 根据准确 Confirmation 集合满足，DealActivated 与 Gate 满足同事务写入；纯风险合同必须引用专门 Decision | 单条 PaymentConfirmation 不等于 Gate 满足；不得用零元付款伪造激活 |
| TransferRuntime | Snapshot是完整不可变版本；补正准确引用前序、针对该前序的唯一RETURN Decision与全部ReturnItem digest。ACCEPT时锁定TransferRequest根与来源Contract根，证明ContractExecution、DealActivated仍准确且`contract_termination_id IS NULL`，当前叶不存在既有RETURN/ReturnItem，`acceptDecision`是绑定该叶及`snapshot_digest`、由唯一REVIEW_TRANSFER Task通过`RECORD_TRANSFER_REVIEW`主命令形成的唯一`ACCEPT`，同时重验材料/Evidence、独立PRE_TRANSFER Review、接收授权和目标能力；RETURN后只能审查并接收其完整补正后继 | Submitted/Returned 不等于接收；只有原子写入 acceptedSnapshot、acceptDecision、MatterRef 并生成 TransferAccepted 与 MatterCreated 事件才接收 |

任何正文、参与方、商业条件、签署人/方式、材料、Evidence 集合、规则/语料、Decision 版本或来源 revision/hash 变化，都必须产生对应的新版本事实或新 Review，旧批准/签名/放行不得复用。MVP 的 MatterRef 是 TransferRequest 的 write-once 槽，不新增 Matter 表。

## 11. Query Facade 与数据保护

SPA 只通过一份 OpenAPI 访问无状态 API；同步 Query Facade 不持久化查询结果，不新增表。每个查询合同必须静态定义：允许 Subject 类型、字段投影、排序、分页上限、可见性级别和所需 authoritySlot。

Query Facade 必须：

- 每条 SQL 显式绑定 `tenant_id`，Repository 方法签名不得省略 Tenant；
- 先解析准确 Subject，再做实时四轴重鉴权，禁止“先查全局再内存过滤”；
- 对 ciphertext/HMAC 字段采用最小投影，只在授权应用进程内按字段用途解密；
- 对敏感读取、Finding、Evidence、Audit 查询和导出先提交 Audit，再返回；
- 通过允许列表限制搜索、排序、导出字段和错误信息；
- 禁止全局 Evidence 搜索、跨租户聚合、任意 SQL、动态列名或直接对象存储访问。

`app_query_role` 只是数据库只读能力，不等于业务可见权限。它不能直接读取 `audit.audit_entry`，审计查询只经过排除会话HMAC、客户端IP密文和执行节点的 `audit.audit_entry_classified_v`；该视图仍不替代行级四轴鉴权和披露前审计。此角色只能由 Query Facade 服务路径持有，不能发给 SPA、报表工具或人工数据库用户。

## 12. Outbox、租约与重驱

两个 Outbox 都采用 at-least-once：`PENDING → CLAIMED → DELIVERED/EXHAUSTED`，领取时严格递增 fencing token 和 revision。Worker 提交结果必须同时比较行主键、预期 revision、lease owner、未过期 lease 和 fencing token；失败即丢弃本次结果。

`DomainEventOutbox.EXHAUSTED` 只允许授权的原位重驱，不能修改 Event 或 queueOwner。ExternalAction 的投递耗尽必须遵守网络边界语义：可能发生过外部效果时先使 Action 为 `UNKNOWN` 并安排 `PROBE`，不得把 DISPATCH 工作项简单恢复后重发。

## 13. 零新增表的支撑能力

下列能力只能作为受控代码/外部基础设施边界实现，不得扩张 52＋2 总账：

| 能力 | 固定实现边界 |
|---|---|
| 同步查询 | 无状态 Query Facade 直接读取既有事实；不建 read-model、搜索或导出表 |
| 受控 AI | AI 只生成按静态 Schema 校验的候选内容，必要时保存到既有 `ActionDraft`；AI 不是 Actor、Owner、授权者或 Decision 来源，任何事实写入仍走人工确认后的固定命令 |
| 部署配置 | 类型化配置、信封/Resolver/路由/策略清单随版本化制品发布；数据库只以既有 `deployment_state` 保存发布与清单摘要 |
| 代码策略 | authoritySlot、类型允许列表、事件 Schema、重试和披露策略由确定性代码注册并进入清单摘要；不建动态策略表 |
| 静态内容 | 表单说明、帮助、模板和静态文案随版本化内容制品发布；业务事实只保存采用的内容/合同版本或摘要 |
| 可观测性 | 指标、Trace、结构化日志和告警进入外部可观测平台；数据库只在既有事实中保存必要 Trace/Correlation 标识，不建监控表 |
| 数据保护 | 密钥与轮换由外部 KMS/Secret Manager 管理；既有受保护字段保存密文和必要 HMAC，不建密钥、解密缓存或明文镜像表 |

AI 输入还必须经过与普通查询相同的 Tenant、四轴授权、字段最小化和披露审计；Prompt、模型输出、工具原始请求响应和文档正文不得复制进 Audit、事件、Outbox 或日志。AI 建议不能直接完成 Task、形成 Decision、推进 ExternalAction 或解释为法律结论。

## 14. 部署和启动门禁

IaC 预创建迁移角色及四个 `NOLOGIN`、非 Owner 应用能力角色。迁移角色独占 Schema DDL 和 `flyway_schema_history`；应用能力角色没有表 Owner、Schema DDL、`DELETE`、`TRUNCATE` 或通用 `UPDATE` 权限。安装目标是专用数据库：除冻结的13个Schema和空`public`外不得存在其他用户Schema或用户表，`V830`/`V840`失败式验证该边界。`audit_append_role`只对`audit.audit_entry`插入，`app_worker_role`只更新两个Outbox的允许列，`app_command_role`只更新合同白名单列，`app_query_role`只读。

`api`与`worker`是仅有的两个启动角色，其数据库登录角色必须由IaC设为`LOGIN NOINHERIT`。由于`CONNECT`在会话建立前检查，IaC只向这两个登录角色直接授予目标数据库`CONNECT`，不得直授`CREATE`、`TEMPORARY`、Schema USAGE或任何对象权限；这项直授不经四个能力角色继承。API登录角色可成为Command/Query/Audit能力角色的成员，但每个受控事务必须以`SET LOCAL ROLE`选择一个准确能力；业务语句与同事务Audit语句按固定Appender协议切换，不得长期继承权限并集。Worker登录角色只能成为Worker能力角色成员，不能成为Command/Query/Audit能力角色成员；需要推进内部命令时调用固定CommandRuntime入口。四个占位符只指向能力角色，`V840`验证这些角色；登录角色的直接CONNECT、成员关系和`NOINHERIT`由IaC测试验证。

`deployment_state` 初始为 `BLOCKED`。受控发布作业以同一个受 IaC 保护的迁移 Owner 凭据充当 DeploymentRuntime；它在迁移、应用制品、OpenAPI、静态信封/Resolver/事件/路由/策略清单和 `schema-contract-manifest.json` 全部匹配后，CAS 写入两个 32 字节摘要并切换 `ACTIVE`。这不是第五个应用能力角色，不进入 API/Worker 进程，也不增加 Flyway 占位符；`V840` 只验证四个应用能力角色，迁移 Owner 的凭据隔离、非交互式发布用途和使用审计由 IaC/发布控制面验证。API 和 Worker 启动及健康检查都必须比较：

- `schema_contract_version`；
- 应用发布摘要；
- 静态部署清单摘要；
- `operating_mode`。

任一不匹配时不得接受业务写入或派发外部效果。应用进程不能自行修改部署门禁。

## 15. 验收清单

部署前至少验证：

- `python3 generate.py --check` 无生成漂移；
- 全部单元测试通过，开发环境用 `pglast` 解析所有迁移；
- Flyway `validate` 通过且迁移文件未被重写；
- `V840` 成功证明 52 应用表＋2 技术表、中文注释、租户复合外键和 mutation guard 完整；
- IaC 角色与四个占位符一一对应，均非 Owner，`clean` 与 `baselineOnMigrate` 禁用；
- API/Worker 只加载其启动角色所需能力，SPA 和 Provider 无数据库凭据；
- 集成测试覆盖授权撤销竞态、Subject revision 冲突、重复 CommandId 异摘要、Audit 失败回滚、Evidence ObjectVersion 变化、Provider 超时转 `UNKNOWN`、Outbox 过期围栏、旧 Review/Decision 失效和 Transfer 非叶 Snapshot 接收拒绝；
- 日志、错误、Audit、事件、Outbox 和导出样本均不含密码、Token、Secret、正文或非必要案情。
