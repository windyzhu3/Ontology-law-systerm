# 待办驱动律所管理系统：总体架构与本体完整设计

> [!WARNING]
> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。

历史元数据（原版本）：1.0
日期：2026-08-18  
历史元数据（原状态）：已冻结设计汇总，待用户复核文档
产品基线：六个销售至转案业务聚合 + 最小责任内核 + 一卡多态交互

## 1. 文档定位与适用顺序

本规格汇总并统一以下已确认设计：

1. 目标产品基线 v2.0。
2. 销售MVP工作卡与对话状态设计 v1.0。
3. 三份原始流程图的闭环追踪结论。
4. 固定版本时效策略、静态责任注册表、最小码表、条件材料目录和外部端口设计。
5. 《跨模型一致性与运行契约 v1.0》。
6. 《最小Matter身份与后MVP扩展契约 v1.0》。

第17–18章是该独立冻结契约的并入文本；两者必须同步演进。若后续编辑造成不一致，应视为规格缺陷并停止实施解释，而不能任选一个较宽松版本。

本规格负责总体架构、上下文边界、本体、跨模型契约和范围裁决；逐张工作卡、提交预算、响应式交互及无障碍细节继续以《销售MVP工作卡与对话状态设计 v1.0》为准。

如果既有文档与本规格冲突，以本规格为准。主要替换项包括：六聚合的适用范围、正式最小Matter、报价回复四态、条件材料X/Y、WAITING与WaitReceipt、MatterRef所有权、租户唯一键、新增DecisionKind，以及MVP中AI没有任何业务命令写权。即使是低风险动作，也必须由真实用户确认后的确定性命令或明确授权的服务Actor执行。

本规格不依赖旧代码仓库、旧表结构、旧页面或旧流程引擎，也不是实施计划。

## 2. 已冻结的产品决策

| 主题 | 决策 |
|---|---|
| 产品入口 | 内部统一Chat工作台；客户使用轻量安全入口 |
| 用户交互 | 首页一句摘要、一张当前工作卡、最多两条后续摘要、等待计数和一个输入框 |
| MVP范围 | 线索接入至转案接收，并原子创建正式最小Matter |
| 六聚合边界 | 只约束销售至转案上下文，不限制整个律所系统未来聚合数量 |
| 架构形态 | 领域边界模块化单体 + 最小共享内核 |
| 租户 | 逻辑多租户，初期单律所部署 |
| Matter | 统一Matter内核；MVP只创建身份，后续接入登记、分类、分配和能力包 |
| 案件办理 | 统一内核 + 案件类型能力包；综法、非诉、诉讼、执行等不硬编码在内核 |
| 财务 | 法律业务财务内建；会计总账、完整开票、退款、冲正外接或后续扩展 |
| 法域 | 中国大陆优先；规则通过法域策略包版本化 |
| 内容治理 | 法律内容治理内建；Office/WPS等编辑工具外接 |
| 时效 | 固定版本策略，不建设规则引擎、运行时DSL或配置平台 |
| 责任 | 静态代码注册；一个Task一个具体Owner、一个命令、一个完成契约 |
| AI | 只提取、草拟、解释、分类建议；没有审批、核验、签章、付款、接收或分案权力 |

## 3. MVP范围

### 3.1 MVP包含

- 外部渠道和人工线索接入。
- 去重异常、缺失补充和自动/异常分配。
- 首次联系、T0/T+1/T+2重试和疑似无效复核。
- 商机实质进展、30日无进展回公海和明确拒绝处置。
- 报价版本、折扣权限、客户商业回复。
- PRE_CONTRACT冲突审查和必要豁免。
- 合同版本、审批、签署、用印、电子归档和执行条件。
- 首款PaymentGate、财务到账确认、D7催款、D15决定。
- DealActivated、条件材料Manifest和转案提交。
- PRE_TRANSFER冲突审查、案管接收或退回。
- 接收时原子产生TransferAccepted、MatterCreated、Matter Identity Core签发的MatterRef，以及TransferRequest.MatterLink（write-once）。
- 销售、主管、部长、报价审批人、合伙人、行政/签章人员、财务、案管和运营的必要垂直协作。

### 3.2 MVP终点
superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md
replacement-section: matter-endpoint

```text
DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
+ TransferAccepted
+ MatterCreated
+ TransferRequest.MatterLink(write-once)
+ 案管Task DONE
+ 销售结果回执
```

以上结果在同一本地事务中原子提交。

### 3.3 MVP不包含

- Matter登记资料补充、案件分类和法域确认。
- 案管分案、承办团队、主办律师接受。
- 综法、非诉、诉讼、执行等办理能力包。
- 法律期限、程序节点、工作包、工作产品和结案归档。
- 后续分期、案件节点收费、完整开票、退款、冲正、佣金和总账。
- 纸质档案附卷。
- 通用BPM、流程设计器、运行时本体平台、EAV和通用规则引擎。

MatterCreated之后，MVP不得自动生成任何登记、分案或办理Task。

## 4. 总体架构

### 4.1 逻辑分层

```text
内部Chat / 客户安全入口
            │
            ▼
      Chat Orchestrator
            │ typed command
            ▼
     Application Use Cases
            │
   ┌────────┼──────────────────────────────────┐
   ▼        ▼                                  ▼
销售至转案业务模块                     Matter Identity Core
Party / Lead / Opportunity /           最小Matter身份
Conflict / Contract / Transfer                 │
   │                                            │ MatterCreated
   └───────────────┬────────────────────────────┘
                   ▼
        Responsibility / Temporal Kernel
                   │
   ┌───────────────┼────────────────┐
   ▼               ▼                ▼
Identity/AuthZ  Evidence/Audit  External Action
                                      │
                                      ▼
             电话、消息、电子签、银行、文档、对象存储适配器
```

### 4.2 运行形态

- 内部MVP只有一个响应式Chat工作台。
- 客户入口是最小受控动作页，只承载被授权的上传、回复或签署，不承载内部工作卡、队列和模块导航。
- 一个领域模块化单体后端。
- 一个关系事实库。
- 一个证据对象存储，通过应用下载网关实时鉴权。
- 一个后台Worker，处理到期扫描、通知、Outbox和外部恢复。
- 一个受控LLM Gateway，只能调用固定候选工具，不能绕过业务命令层。

数据库、对象存储、调度器和模型产品属于实施ADR，本规格不锁定品牌。

### 4.3 模块边界

| 模块 | 单一职责 |
|---|---|
| Identity/AuthZ | Tenant、用户、组织、能力角色、对象关系、代理、职责分离和客户授权 |
| Jurisdiction Policy | 中国大陆默认业务日历、签署/材料政策及版本引用；不承担Matter分类 |
| Legal Content Governance | 模板、条款和内容版本治理；外部编辑器只处理受控文档产物 |
| Party | 租户内客观主体、别名和去重关系 |
| Lead | 来源、分配、联系、资格和线索处置 |
| Opportunity | 法律需求、销售归属、实质进展、QuoteRevision和客户商业回复 |
| Conflict | 目的限定审查、scopeHash、Finding最小披露和豁免决定 |
| Contract | 合同版本、审批、签署、收费、PaymentGate、执行和成交门槛 |
| Transfer | 转案快照、MaterialManifest、补正、接收结果和MatterLink |
| Matter Identity Core | 签发matterId/MatterRef，保存不可变来源引用和MatterCreated |
| Responsibility Kernel | Task、Decision、Event、责任槽、完成契约和取消替代 |
| Temporal Policy | 固定版本时效、提醒、到期、升级和策略触发 |
| Chat Orchestrator | 当前卡、候选输入、确认令牌、WaitReceipt和AI降级 |
| Evidence/Audit | 不可变证据版本、哈希、ACL、审计和敏感访问记录 |
| External Action | 外部意图、Outbox/Inbox、Provider状态和UNKNOWN恢复 |

模块只能通过固定命令、稳定读取端口和版本化Event协作。禁止跨模块直接修改私有表。

### 4.4 法域、内容与财务边界

中国大陆首版策略包至少固定`policyPackId/version/effectiveRange/timezone/calendarRef/signaturePolicyRef/transferMaterialPolicyRef`。每次政策判断保存实际使用的版本；MVP不提供运行时政策编辑器，法域策略也不替代后MVP的Matter法域和管辖分类。

法律内容治理内部拥有模板、条款、审批基线和内容摘要。每个ContractRevision必须绑定`templateVersion、clauseVersionSet、contentDigest`；Office/WPS等外部工具只编辑受控副本，重新导入必然形成新Revision，不能直接把正文标记为已审批、已签署或已归档。

法律业务财务由Contract拥有FeeTerms、PaymentGate、PaymentConfirmation和成交门槛；外部银行或财务系统只提供可信交易与匹配输入。会计总账通过RESERVED的LedgerExportPort消费已确认事实，不能反向改写付款门槛、成交或委托终止。

## 5. 总体本体

### 5.1 销售至转案六聚合

| 聚合 | 权威事实 | 明确不拥有 |
|---|---|---|
| Party | 自然人/组织身份、别名和租户内去重关联 | 客户、委托人、对方永久角色；销售阶段 |
| Lead | 来源、分配、联系尝试、联系结果、资格和归档/公海处置 | 商机进展、报价、合同 |
| Opportunity | 法律需求、上下文角色快照、销售归属、进展、QuoteRevision、客户回复 | 签署、付款、Matter |
| ConflictReview | purpose、参与方/需求快照、scopeHash、Finding和解决结果 | 通用冲突状态或其他聚合业务状态 |
| Contract | ContractRevision、FeeTerms、SignaturePlan、PaymentGate、PaymentConfirmation、ContractExecuted、DealActivated | TransferSnapshot和MatterRef签发 |
| TransferRequest | TransferSnapshot、MaterialManifest、退回项、补正版本、TransferAccepted和MatterLink | Matter身份、案件分类和办理 |

### 5.2 上下文角色

“客户、委托人、付款人、对方、关联方”是Party在某个Opportunity、ConflictReview、Contract或TransferSnapshot中的上下文角色，不是Party永久标签。

同一Party可以：

- 在一个Opportunity中是客户。
- 在另一个Opportunity中是对方或关联方。
- 拥有多个互不混淆的法律需求。

### 5.3 聚合内部对象

以下是所属聚合内部对象，不新增业务聚合：

- QuoteRevision属于Opportunity。
- SignaturePlan、PaymentGate和PaymentConfirmation属于Contract。
- MaterialManifest属于TransferRequest。
- DealActivated是Contract事实，不建立Deal聚合。
- Assignment事实分别属于Lead、Opportunity或未来Matter Assignment模块。

Contract独占写入PaymentGateSatisfied、EngagementTerminated和DealActivated；TransferRequest独占写入TransferSubmitted、TransferResubmitted、TransferAccepted、TransferReturned及MatterLink；Matter Identity Core独占写入Matter、MatterRef和MatterCreated。Responsibility Kernel只保存Task、Decision和Event责任记录，不拥有或改写上述业务事实。

### 5.4 禁止全局业务状态

系统不得建立跨Lead、Opportunity、Contract、Transfer和Matter的单一`businessStatus`。

以下状态轴互相独立：

- Lead联系、资格和归属事实。
- Opportunity商业推进事实。
- ConflictReview目的限定结果。
- Contract审批、签署、执行和付款门槛。
- Transfer材料、冲突和接收结果。
- Task状态。
- ExternalAction状态。
- Material验证状态。
- Chat视图状态。

用户看到的“接单、推进、签约、转案”只是可重建投影，不能参与领域命令校验。

## 6. 统一因果与事务契约

### 6.1 普通内部命令

```text
从认证上下文取得tenant和actor
→ 校验Task Owner/有效代理
→ 校验requiredCapability与authorityAssignment
→ 校验completionContract要求的全部subjectBindings[]
→ 校验领域不变量
→ 修改唯一事实Owner聚合
→ 追加Event或Decision
→ 完成/取消当前Task
→ 创建下一Task或WaitReceipt
→ 写Audit与CommandReceipt
→ 原子提交
```

所有命令和事件因果信封统一携带：

```text
tenantId
commandId
correlationId
causationId
subjectBindings[]
policyVersion
```

`subjectBindings[]`中的每一项为：

```text
{ subjectRef, bindingKind, bindingValue }
```

`bindingKind`只允许：

```text
AGGREGATE_VERSION
REVISION_CONTENT_DIGEST
SNAPSHOT_DIGEST
```

每个高风险命令、Decision和completionContract必须声明需要哪些绑定，服务端必须逐项精确匹配，不能把version、revision或hash实现成任选其一。

### 6.2 明确允许的跨聚合本地事务

MVP只允许代码显式列出的业务用例跨聚合原子提交，包括：

1. 有效联系结果与Opportunity创建。
2. QuoteAccepted与PRE_CONTRACT审查实例创建。
3. TransferSubmitted与PRE_TRANSFER审查实例创建。
4. DealActivated、TransferRequestInitialized与绑定准确transferRequestId/version的SUBMIT_TRANSFER Task创建。
5. 绑定当前案管Task的TRANSFER_REVIEW(ACCEPT)、TransferAccepted、正式最小Matter创建和TransferRequest.MatterLink写入。

这不是通用Saga或流程编排能力。

### 6.3 创建型责任

为保证Task创建时已有准确subject：

```text
PRE_CONTRACT解决
→ ContractInitialized
→ 创建绑定contractId/version的PREPARE_CONTRACT Task

DealActivated
→ TransferRequestInitialized
→ 创建绑定transferRequestId/version的SUBMIT_TRANSFER Task
```

Initialized是内部事实，不向用户增加步骤。Matter不得预建MatterShell。

## 7. 最小责任内核

### 7.1 Task

Task是当前人工责任投影，不是业务事实源。

| 字段 | 规则 |
|---|---|
| taskId/taskType | 固定代码注册 |
| tenantId | 强制租户边界 |
| subjectBindings[] | 创建时冻结准确业务对象、版本、Revision或Digest |
| ownerRef | 创建时解析为一个具体内部人员并冻结 |
| requiredCapability | 执行命令必须具备的能力 |
| authorityAssignmentRef/version | 执行时重新验证权力仍有效 |
| commandVariant | 生命周期内唯一且不可改变 |
| completionContractId | 代码注册的唯一完成契约，禁止通用表达式 |
| temporalPolicyRef/version | 创建时冻结 |
| status | OPEN、WAITING、DONE、CANCELLED |
| predecessorTaskId | 退回、补正、替代的审计关系，不是流程连边 |

一个completionContract固定：

- completionEventType。
- 决定类的decisionKind。
- completionContract声明的全部subjectBindings。
- taskId或causationId匹配规则。

下游OpportunityOpened、QuoteAuthorized、ContractExecuted、DealActivated、TransferAccepted或MatterCreated均不能替代前置Task自己的完成事实。

### 7.2 Task状态机
superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md
replacement-section: task-waiting-contract

```text
新建WAITING ──nextCheckAt到达──> OPEN
OPEN ──准确完成事实──> DONE
OPEN/WAITING ──取消或替代事实──> CANCELLED
OPEN ──仅SYSTEM_RECOVERY──> WAITING ──恢复并重验──> OPEN
```

- DONE和CANCELLED为终态。
- 改派、退回、重试、换渠道、修订和补证全部创建新taskId。
- SYSTEM_RECOVERY恢复时若业务版本已变化，取消旧Task并创建新Task。
- Owner撤权、离岗或决定换人时，取消旧Task并重新解析，不得静默改Owner。
- executedBy可以是有效代理，但ownerRef保持原责任人并记录代理依据。

### 7.3 DecisionKind

| decisionKind | 核心合法结果 |
|---|---|
| LEAD_VALIDITY_REVIEW | INVALID、REOPEN |
| LEAD_AUTOARCHIVE_RECHECK | KEEP_ARCHIVED、REOPEN |
| CONTACT_RETRY_BREACH_DISPOSITION | RETRY_SAME_OWNER、REASSIGN |
| OPPORTUNITY_REJECTION_DISPOSITION | STOP_FOLLOW_UP、CONTINUE_SAME_OWNER_REVISED_PLAN、CONTINUE_NEW_OWNER_REVISED_PLAN |
| QUOTE_DISCOUNT_APPROVAL | APPROVE、REJECT |
| CONFLICT_WAIVER | WAIVE、BLOCK |
| CONTRACT_APPROVAL | APPROVE、REJECT；纯风险批准需PURE_RISK_ENGAGEMENT授权范围 |
| PAYMENT_D15_DISPOSITION | CONTINUE、TERMINATE |
| TRANSFER_REVIEW | ACCEPT、RETURN |

Decision必须保存Actor、authorityBasis、准确subject版本、理由、证据、policyVersion和三个时间：业务发生、服务端记录及必要时生效时间。

### 7.4 Owner与Authority

Owner回答“谁当前负责”，Authority回答“谁有权执行”。每次高风险命令同时验证：

1. Actor是冻结Owner或有效代理。
2. Actor拥有requiredCapability。
3. AuthorityAssignment仍有效且覆盖当前Subject。
4. Actor、Task和Subject属于同一Tenant。
5. 职责分离未被破坏。

运营角色只能修复系统阻塞，不能代替业务人员作报价、冲突、合同、付款或转案决定。

### 7.5 固定Owner Resolver

所有Resolver必须在Task创建时返回一个具体、有效的内部人员；角色、组、队列、客户或供应商不能成为ownerRef。解析失败只创建SystemRecovery责任。

```text
SOURCE_STEWARD
ASSIGNED_SALES
SALES_SUPERVISOR
SALES_DEPARTMENT_HEAD
QUOTE_AUTHORITY_SLOT
CONFLICT_AUTHORITY_SLOT
CONTRACT_APPROVER
SIGNATURE_STEP_OWNER
FINANCE_VERIFIER
CASE_ADMIN
OPERATIONS_OWNER
DECISION_FOLLOW_UP_OWNER
```

### 7.6 取消与替代

| 权威事实 | 必须取消或替代的责任 |
|---|---|
| Lead重新分配 | 旧Owner全部未完成联系Task；为新Owner创建新taskId |
| ContactResultRecorded(CONNECTED) | 全部未来T0/T+1/T+2 occurrence |
| SuspectedInvalidRecorded | 普通联系/重试责任；复核REOPEN后创建新首联Task |
| CustomerExplicitlyDeclined | 销售通用5日推进；部长决定后生成准确新责任 |
| QuoteAccepted | 报价回复和普通推进责任 |
| QuoteRevision superseded | 旧版本审批、发送和回复责任 |
| ContractRevision superseded | 旧版本审批、签署Task、提醒和WaitReceipt |
| ContractExecuted | 全部未完成签署提醒 |
| PaymentGateSatisfied | 同Gate付款承诺提醒、D7及尚未打开的D15 |
| EngagementTerminated | 未完成签署、付款和转案责任；永久阻断DealActivated |
| TransferSubmitted | 销售提交Task完成，仅保留WaitReceipt |
| TransferReturned | 创建新FIX_TRANSFER Task，不重开旧提交Task |
| TransferAccepted | 取消其余转案责任并完成结果回执 |

付款承诺提醒和D7催款使用同一责任槽：`paymentGateId + gateVersion + REMINDER + deliveryWindow`。同一投递窗口合并原因，只保留一个Task occurrence。

## 8. 时效、义务与提醒

### 8.1 时间字段必须分开

- `occurredAt/effectiveAt`：业务事实发生时间。
- `recordedAt/receivedAt`：可信服务端记录时间。
- `availableAt/nextCheckAt`：Owner动作可执行时间。
- `actionDueAt`：当前Owner动作截止。
- `reminderAt`：提醒投递时间。
- `escalationAt`：升级可见时间。
- `resolutionDueAt`：外部动作技术恢复期限。
- 法律期限：后续Matter能力，不与销售SLA混用。

客户端自报时间不能直接作为时效锚点；必须由有权命令或可信Provider证据确认。

### 8.2 TemporalPolicy冻结字段

Task创建时冻结：

```text
policyRef/version
timezone
anchorEventId/anchorAt
availableAt/nextCheckAt
actionDueAt?
reminderAt[]
escalationAt[]
cancellationFacts
```

- 默认业务时区为Asia/Shanghai。
- 策略更新不静默重算存量Task。
- 调度器晚运行不能把截止时间向后推。
- 原业务流程未提供正式数值SLA的Task必须令actionDueAt为空，禁止为了完整性虚构时限。
- 安静时段只延迟通知，不改变到期或升级事实。
- 超时不自动完成Task或伪造Decision。

### 8.3 MVP固定时效

| 场景 | 锚点 | 策略 |
|---|---|---|
| 首联 | LeadAssigned | 24分钟软提醒、30分钟到期、45分钟主管升级 |
| 疑似无效复核 | SuspectedInvalidRecorded | 主管24小时；超时服务Actor自动归档 |
| 自动归档复查 | LeadAutoArchived | 7日后开放；开放后24小时动作SLA |
| T0重试 | 联系计划起点 | 使用代码注册的CONTACT_RETRY_CHANNEL_PROFILE_v1；每个启用渠道必须在上线前绑定不可变次数和联系窗口，未绑定Profile的渠道不得启用自动重试 |
| T+1/T+2 | 联系计划 | 09:00–11:30、13:30–17:30、18:30–20:30各一次真实尝试 |
| 商机推进 | 最新实质进展 | 第4日提醒、第5日到期、第7.5日升级、30日释放公海 |
| 客户承诺 | 由用户确认的准确时间 | 保存原始表达、解析时区、确认人、确认时间和策略版本；到点开放新Task，1日内行动，不得突破30日上限 |
| 首款 | PaymentGate.dueAt | D7销售催款、D15部长决定 |

时效内核对30日策略只产生`OpportunityNoProgressThresholdReached(expectedProgressClockVersion)`；Opportunity重新验证后才能写`OpportunityReleasedToPublicPool`。

### 8.4 24小时自动归档

主管未在24小时内复核时：

```text
服务Actor写LeadAutoArchived
→ 原LEAD_VALIDITY_REVIEW Task CANCELLED
→ 不产生任何人工Decision
→ 创建7日后的LEAD_AUTOARCHIVE_RECHECK Task
```

复查结果只允许KEEP_ARCHIVED或REOPEN。

### 8.5 SYSTEM_RECOVERY

SYSTEM_RECOVERY必须保存：

```text
recoveryReasonCode
enteredAt
recoveryTaskId/recoveryOwner
recoveryDueAt
resumePrecondition
原actionDueAt
暂停区间
auditRef
```

- 创建独立OPERATIONS_OWNER恢复Task。
- 逾期只升级运营异常，不得隐式延长或重置原SLA。
- 修复完成后恢复原Draft并重新验证subjectBindings。
- Subject版本已经变化时，取消旧Task并创建新Task，不得恢复旧命令。
- 模糊客户承诺无法确定日期、时区或准确时刻时，不得生成带精确SLA的WAITING Task，只能创建澄清责任。

## 9. WAITING、WaitReceipt与Chat状态
superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md
replacement-section: task-waiting-contract

### 9.1 WAITING Task

WAITING只用于：

1. Owner和未来动作已经确定，但nextCheckAt尚未到达。
2. SYSTEM_RECOVERY安全暂停当前未完成责任。

客户承诺完成当前Task后，应创建一个新的WAITING Task。客户提前回复时，权威事实取消该未来Task。

### 9.2 WaitReceipt

内部移交或外部执行等待统一使用只读WaitReceipt：

- 审批、财务、行政、案管、运营接手。
- 外部消息、签章、文档渲染或Provider结果等待。
- 原用户已经完成本人动作。

有正式dueAt时显示最晚反馈时间；没有正式SLA时显示：

> 暂无承诺反馈时间，状态变化后会通知你。

WaitReceipt没有业务按钮，不进入WorkCard状态机，也不能关闭Task。

WaitReceipt最小字段：

```text
receiptId
tenantId
subjectBindings[]
waitingForRole/provider
sourceActionId/sourceEventId
receiptStatus
formalDueAt?
lastVerifiedAt
```

receiptStatus只能由权威领域Event、Decision或Provider结果更新，Chat和AI不得改写。

### 9.3 一卡多态

```text
ACTION → ASK → REVIEW → SUBMITTING → DONE
                    └→ 可修复RECOVERY
新补正Task可视觉显示RETURNED
```

- 当前卡只有OPEN且Actor有权执行的Task。
- WAITING和他人责任只进入等待区。
- DONE只有用户点击“继续下一项”或离开页面后折叠。
- 新Task、回执和计时器不能自动抢走未折叠DONE卡。
- AI降级不改变Task、Owner、优先级、命令、时效和完成事实。

### 9.4 当前卡优先级

1. 本人可执行的合规阻断或退回补正。
2. 已到期或已升级事项。
3. 临近到期事项。
4. 已到达的客户承诺动作。
5. 正常推进责任。

同一级按`dueAt → commitmentAt → createdAt → taskId`稳定排序。AI只能解释，不能改变排序。

### 9.5 MVP界面禁止项

除认证、有限设置、必要证据预览和受控操作记录外，内部MVP不得提供：

- 按Lead、Opportunity、Contract或Transfer分列的业务菜单。
- 平行工作队列、看板、流程图或流程中心。
- 可绕过当前WorkCard的对象编辑页。
- 长表单和批量“勾选完成”。

所有可执行业务动作只能从当前WorkCard或Chat引导进入。对象详情只能作为当前卡的只读或受控补充，不能形成第二业务入口。客户轻入口是独立的最小动作页，不属于内部工作台。

## 10. 销售MVP主因果链

```text
LeadCaptured
→ LeadAssigned
→ ContactResultRecorded
→ OpportunityOpened
→ ProgressRecorded
→ QuoteRevisionCreated
→ QuoteAuthorized
→ QuoteIssued
→ QuoteResponseRecorded(ACCEPTED)
→ QuoteAccepted
→ ConflictReviewResolved(PRE_CONTRACT)
→ ContractInitialized
→ ContractApprovalRequested
→ ContractApprovalSatisfied
→ ContractExecuted
→ PaymentGateSatisfied 或 当前合同PURE_RISK专门授权
→ DealActivated
→ TransferRequestInitialized
→ TransferSubmitted
→ ConflictReviewResolved(PRE_TRANSFER)
→ DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
→ TransferAccepted + MatterCreated + MatterLink
```

### 10.1 首联与重试

- 首联Task绑定Lead，以`ContactResultRecorded`完成，并可在同一命令保存联系尝试证据。
- 重试Task以`ContactAttemptRecorded`完成。
- CONNECTED只结束当前尝试并创建独立结果采集责任。
- 全部规定窗口均有真实未接通证据时，才能产生LeadContactExhausted并释放公海。
- 漏做窗口不能判定失联，必须创建CONTACT_RETRY_BREACH_DISPOSITION主管决定。
- 真无效进入Dead-Pool；失联耗尽和30日无进展进入Public Pool；明确拒绝停止合作进入No-Cooperation Pool。
- MVP中的Public Pool只表示终止当前销售归属并取消该Owner未完成责任；不实现领取、冷却、重新分配、分配上限或自动创建下一销售Task。

### 10.2 报价回复四态

```text
ACCEPTED
NOT_ACCEPTED_YET
EXPLICIT_DECLINE
UNCLEAR
```

- ACCEPTED进入PRE_CONTRACT。
- NOT_ACCEPTED_YET重新生成销售5日推进责任。
- EXPLICIT_DECLINE生成销售部长OPPORTUNITY_REJECTION_DISPOSITION决定。
- UNCLEAR继续澄清，不能形成接受或拒绝事实。

QuoteResponseRecorded必须绑定已QuoteIssued且仍为当前可用版本的`quoteRevisionId、contentHash、quoteIssuedEventId、EvidenceRef/version`。只有全部绑定匹配且结果为ACCEPTED时，才可在同一事务派生QuoteAccepted。新QuoteRevision发出、原版本被替代或任一版本/hash不匹配后，旧QuoteAccepted只保留审计价值，不得创建或推进PRE_CONTRACT、ContractInitialized或合同Task。

“考虑一下”“原则同意”“价格可以再谈”不得视为ACCEPTED；明确拒绝当前报价但仍愿意继续洽谈属于NOT_ACCEPTED_YET；只有明确拒绝整体合作才属于EXPLICIT_DECLINE；语义无法可靠确定时属于UNCLEAR。

### 10.3 DealActivated聚合门

DealActivated是Contract聚合唯一派生事实。普通或半风险要求以下事实全部绑定同一当前ContractRevision及approvedContentDigest：

```text
ContractExecuted(contractRevisionId, approvedContentDigest)
+ PaymentGateSatisfied(paymentGateId, gateVersion, contractRevisionId)
+ 不存在有效EngagementTerminated
= DealActivated
```

纯风险：

```text
ContractExecuted(contractRevisionId, approvedContentDigest)
+ CONTRACT_APPROVAL DecisionRecorded绑定同一合同版本
+ authorizationScope = PURE_RISK_ENGAGEMENT且authority仍有效
+ 不存在有效EngagementTerminated
= DealActivated
```

任一revision、digest、gateVersion或授权范围不匹配，均不得产生或维持可转案资格。PAYMENT_D15_DISPOSITION=TERMINATE必须写入EngagementTerminated、取消全部未完成签署/付款/转案责任并永久阻断DealActivated；其后迟到PaymentConfirmed只保留财务事实，不得重新满足门槛或激活成交。

付款到账、客户付款截图、普通合同批准或ExternalAction成功均不能单独激活成交。

### 10.4 报价、合同与付款政策

执行标准产品的折扣权限：

| 折扣 | 权力槽 |
|---|---|
| 不超过20% | 销售本人权限内 |
| 超过20%且不超过40% | 组长 |
| 超过40%且不超过50% | 销售部长 |
| 超过50% | 主任和合伙人两个独立Task、两个不同Actor、ALL_OF |

综法等非标准服务由销售形成报价版本，是否接受合同条款由后续有权合同审批人决定；报价权限与合同审批不得合并。

分期政策首版固定为：最多两期、总跨度不超过一个月、首付不低于基础服务费的50%。半风险的50%只计算基础服务费，不包含或有风险费。

ContractExecuted只有在同一approvedContentDigest下同时满足以下条件时产生：

- 所有当前SignaturePlan要求的签署证据均已验证。
- 必要机构用印和骑缝章要求已经满足。
- 当前电子合同包已经归档。
- 相关外部动作不存在UNKNOWN、FAILED、撤销或过期状态。

物理纸质附卷不属于MVP执行门槛。

### 10.5 PRE_TRANSFER唯一路由

TransferSubmitted只创建或绑定`purpose=PRE_TRANSFER`、绑定当前TransferSnapshot和scopeHash的ConflictReview；不得直接创建REVIEW_TRANSFER。

| 结果 | 唯一路由 |
|---|---|
| CLEAR | 写ConflictReviewResolved，为当前snapshotVersion唯一创建REVIEW_TRANSFER |
| NEED_INFO | 只创建补明确冲突字段的FIX_TRANSFER；以TransferResubmitted及新snapshotVersion完成 |
| FINDING | 按每个findingId和authoritySlot创建独立CONFLICT_WAIVER Task；全部WAIVE后才解决 |
| BLOCK | 写ConflictReviewBlocked，不得创建REVIEW_TRANSFER |

TransferResubmitted后：

- scopeHash不变且存在有效ConflictReviewResolved：为新snapshotVersion直接创建唯一REVIEW_TRANSFER。
- scopeHash变化或不存在有效结论：必须创建或复用新的PRE_TRANSFER。
- 普通材料补齐不得使无关冲突结论失效。
- 参与方或法律需求变化必须使旧结论失效。

冲突BLOCK只证明业务被阻断；不得自动声称退款、推荐合作律所或其他外部处置已经完成。

## 11. 最小码表

### 11.1 联系与资格

```text
LeadContactOutcome:
  VALID | NOT_CONNECTED | SUSPECTED_INVALID

ContactAttemptOutcome:
  CONNECTED | NOT_CONNECTED | CONTACT_POINT_UNUSABLE | TECHNICAL_FAILURE

SuspectedInvalidReason:
  NO_LEGAL_NEED | DENIES_SUBMISSION | PEER_INTERFERENCE | OTHER_WITH_NOTE
```

`OTHER_WITH_NOTE`必须有说明；TECHNICAL_FAILURE不能算客户联系尝试。

### 11.2 商业回复与进展

```text
CustomerCommercialResponse:
  ACCEPTED | NOT_ACCEPTED_YET | EXPLICIT_DECLINE | UNCLEAR

SubstantiveProgressKind:
  CONNECTED_SUBSTANTIVE_CALL
  CLIENT_INITIATED_VISIT
  IN_PERSON_DISCUSSION
  QUOTE_ISSUED
  EXTERNAL_VISIT
  SUBSTANTIVE_WECHAT_CONVERSATION
```

### 11.3 销售与合同分类

```text
SalesOfferingClass:
  GENERAL_LEGAL_SERVICE | EXECUTION_STANDARD_PRODUCT

FeeArrangementKind:
  ONE_TIME | HYBRID_RISK | PURE_RISK

SignatureMethod:
  OFFLINE_WET_INK | ELECTRONIC_PROVIDER
```

SalesOfferingClass不是Matter案件类型。

### 11.4 冲突

```text
ConflictPurpose:
  PRE_CONTRACT | PRE_TRANSFER

ConflictScreeningResult:
  CLEAR | NEED_INFO | FINDING
```

PRE_CONTRACT和PRE_TRANSFER必须是两个不同ConflictReview实例。

## 12. 条件材料本体

### 12.1 MaterialManifest

MaterialManifest属于TransferRequest，并冻结：

- catalogueVersion。
- transferMaterialPolicyVersion；MVP固定使用PRC_MAINLAND_TRANSFER_MATERIAL_v1，不进行Matter法域分类。
- transferSnapshotVersion/hash。
- 每个MaterialItem的适用性、证据引用和核验结果。

适用性：

```text
REQUIRED | NOT_APPLICABLE | DEFERRED
```

核验状态：

```text
MISSING | PENDING_REVIEW | VERIFIED | REJECTED | STALE
```

- SubmitReady允许阻断材料已提交但待案管核验。
- AcceptReady要求所有阻断REQUIRED项均VERIFIED。
- NOT_APPLICABLE和DEFERRED不进入阻断分母。
- AI不能决定适用性、核验、豁免或接收。

适用性和blocking属性只能由TransferRequest依据冻结的`transferSnapshotVersion/hash、catalogueVersion、transferMaterialPolicyVersion`及快照中已经由用户明确确认的事实确定性生成。MVP不提供把REQUIRED人工改成NOT_APPLICABLE或DEFERRED的通用编辑、豁免按钮或AI判断。

如果某项适用性所需的明确事实缺失，销售卡只追问该一个事实，例如“本事项是否需要客户出具授权委托书”；这只是转案输入确认，不产生Matter类型、程序阶段或法域分类。

每个VERIFIED必须绑定准确MaterialItem版本、EvidenceRef/version、核验人和核验时的TransferSnapshot版本/hash。任何影响适用性、证据关联或核验依据的快照、合同执行版本、材料政策或MaterialItem版本变化，必须令旧项进入STALE并生成新Manifest版本。STALE、MISSING、PENDING_REVIEW和REJECTED均不能满足AcceptReady。

### 12.2 目录全集

| MaterialType | MVP规则 |
|---|---|
| EXECUTED_CONTRACT | 所有转案REQUIRED，由当前ContractExecuted及归档包满足 |
| INITIAL_PAYMENT_PROOF | 一次性/半风险REQUIRED；纯风险NOT_APPLICABLE |
| POWER_OF_ATTORNEY | TransferSnapshot中明确确认`representationRequired=true`时REQUIRED |
| CLIENT_IDENTITY_EVIDENCE | 所有转案REQUIRED |
| COUNTERPARTY_IDENTITY | 存在对方或利益相关方时REQUIRED |
| SUPPORTING_EVIDENCE_BUNDLE | TransferSnapshot中明确确认`supportingEvidenceRequired=true`时REQUIRED |
| TRANSFER_REGISTRATION_SNAPSHOT | 所有转案REQUIRED，由六聚合自动生成 |
| RISK_DISCLOSURE | 半风险、纯风险，或快照明确确认`specialRiskDisclosureRequired=true`时REQUIRED |
| LEGAL_AID_FIRST_INQUIRY_NOTICE | 受控入口事实明确确认`legalAidFirstInquiryApplicable=true`时REQUIRED |
| ENGAGEMENT_INTERVIEW_RECORD | 所有转案REQUIRED，AI草稿需人工确认 |
| MATTER_SCORECARD | DEFERRED，不阻塞转案接收 |

用户界面只显示：

- “当前适用材料X/Y已提交”。
- 当前最高优先级的一个缺项。
- 案管核验时“阻断材料X/Y已核验”。

禁止继续显示固定“X/11”或“11/11”。

案管RETURN必须绑定准确materialType、materialVersion、固定issueCode和具体补正说明。

## 13. ExternalAction与外部端口

### 13.1 端口

```text
LeadIngressPort
TelephonyPort
MessagingPort
ESignPort
BankTransactionPort
DocumentRenderPort
BlobStorePort
ConflictSearchPort
NotificationPort
LedgerExportPort（RESERVED）
```

适配模式：

```text
DIRECT | ASSISTED_MANUAL | DISABLED
```

### 13.2 ExternalAction状态

```text
PENDING → DISPATCHING → DISPATCHED | FAILED | UNKNOWN
DISPATCHED → SUCCEEDED | FAILED | UNKNOWN
UNKNOWN → SUCCEEDED | FAILED
```

- ExternalActionRequested完成的是人工发起责任。
- SUCCEEDED只表示Provider契约下的动作成功，不自动等于法律业务事实。
- ExternalAction至少保存`externalActionId`、`effectKey`、`dispatchAttemptId`、`attemptNo`、`providerAccountRef`、准确`subjectBindings[]`、`dispatchLeaseUntil`、`nextProbeAt`、`resolutionDueAt`和`probeCount`。
- `effectKey`由Tenant、Provider账户、业务动作语义、责任Occurrence和准确Subject绑定共同派生；同一个拟产生的外部效果在任一时刻只能有一个活动Action。
- 发起进程丢失、租约到期、Provider响应不明确或请求成功但回执未知时，必须先进入UNKNOWN，不能假定FAILED。
- UNKNOWN只能由权威查询、已验证回调或有权运营核验解除；查询和核验本身不得重发原动作。
- 只有确认外部效果没有发生后，才允许创建带`retryOf`的新ExternalAction；它沿用业务`effectKey`并递增`attemptNo`，Provider幂等键为`effectKey + attemptNo`。
- SUCCEEDED和FAILED是单个Action的单调终态；迟到或冲突回调进入隔离审计，不得回退终态或重复产生领域事实。

### 13.3 Provider Inbox

Inbox记录状态固定为：

```text
RECEIVED | QUARANTINED | PROCESSED
```

回调必须验证：

- Provider签名或双向认证。
- timestamp、nonce和重放窗口。
- ProviderAccount到Tenant的预登记映射。
- providerEventId、`canonicalPayloadDigestRef`和关联ExternalAction；Digest Ref必须带算法与Canonicalization Profile代码/版本。
- subjectRef及准确revision/hash。

伪造、重放、跨租户或无法关联的回调只进入隔离审计，不得修改业务事实或WaitReceipt。

合法回调的处理必须在一个本地事务中完成：

```text
按 tenantId + providerAccountRef + providerEventId 唯一写入Inbox
→ 校验签名、时序、Tenant、ExternalAction与准确Subject绑定
→ 单调推进ExternalAction
→ 必要时产生唯一领域事实
→ 更新WaitReceipt或创建新的人工责任
→ 写Audit
→ Inbox标记PROCESSED
→ 原子提交
```

任一步失败均不得把Inbox标为PROCESSED。只有“相同事件唯一键 + 相同`canonicalPayloadDigestRef`”才是合法重放并返回原处理结果；相同事件唯一键携带不同`canonicalPayloadDigestRef`必须标记QUARANTINED、告警且不产生任何领域副作用。该Digest Ref基于规范化业务载荷计算，包含算法与Canonicalization Profile代码/版本，且只排除每次可变的传输签名、nonce、timestamp及等价包装字段；数据库不得保存裸Hash列。乱序事件只能补齐尚未确定的事实，不能使终态回退，也不能再次生成Task、Receipt或领域事件。

### 13.4 人工证据兜底

```text
ManualEvidenceSubmitted
→ 有权人员核验准确Evidence版本
→ ManualEvidenceVerified
→ 领域重新验证当前版本与完整条件
→ 正式业务事实
```

人工证据不能把FAILED或UNKNOWN的ExternalAction改写成SUCCEEDED；它只能作为独立证据进入领域重新验证。

## 14. 幂等与并发

| 层次 | 幂等口径 |
|---|---|
| 所有可变命令 | tenantId + commandScope + commandId，并保存带算法与规范化Profile版本的`payloadDigestRef` |
| Task完成 | taskId只能有一个终态完成事实 |
| Task创建 | 领域责任槽responsibilitySlotKey唯一 |
| 聚合写入 | expectedSubjectVersion/revision/hash乐观锁 |
| Temporal里程碑 | taskId + policyVersion + milestoneCode |
| External效果 | effectKey/externalActionId；UNKNOWN禁止重复效果 |
| Provider Inbox | tenantId + providerAccount + providerEventId |
| 付款流水 | tenantId + trustedSourceAccount + externalTransactionId |
| Matter创建 | tenantId + sourceTransferRequestId |

相同commandId携带不同payload必须拒绝。并发终态命令只允许一个成功；失败方返回最新工作卡或原CommandReceipt。

内部工作卡命令的`commandScope`绑定内部Actor与taskId；客户入口命令绑定CustomerAccessGrant、入口会话、准确Subject、allowedCommand、`payloadDigestRef`和一次性确认Token。确认Token重放必须返回同一结果，不能重复上传、回复、签署或产生领域事实。

## 15. 逻辑多租户、权限与客户入口

### 15.1 Tenant边界

即使初期只有一个律所，也必须从第一天保证：

- 聚合、Task、Decision、Evidence、MaterialManifest、ExternalAction、Outbox、Inbox、Audit和客户授权均有tenantId。
- Tenant从认证会话、CustomerAccessGrant或ProviderAccount绑定取得，不信任请求正文。
- 数据引用、唯一键、缓存、搜索、对象存储路径、通知和Worker任务都受Tenant边界约束。
- 所有跨表、跨聚合和跨模块引用都必须携带相同tenantId；应用层在写入前验证，关系库可表达时使用包含tenantId的复合外键或等价约束。
- 不得通过全局对象ID反推Tenant，也不得先按对象ID查询后再做Tenant过滤。
- 向量索引、全文索引、缓存键、消息分区、临时文件和导出文件同样必须以Tenant分区并在读取时重新鉴权。
- 初期只注入唯一默认Tenant，不建设租户运营控制台。

### 15.2 权限判定

每次读写同时考虑：

- Tenant。
- 全局能力角色和组织范围。
- 当前Task Owner或有效代理。
- 当前业务对象关系。
- Matter范围团队关系（后续）。
- 字段敏感等级和访问目的。
- Subject准确版本。
- AuthorityAssignment有效期。
- 职责分离。

Chat、搜索、附件、通知、审计和导出使用同一ACL，菜单隐藏不是安全控制。

### 15.3 客户轻入口

CustomerAccessGrant属于Identity模块，不是业务聚合。它只绑定：

- Tenant。
- 客户/联系人。
- 明确Subject。
- 允许动作。
- 有效期、撤销状态和身份确认强度。

客户不能枚举业务ID，不能查看内部Task、冲突详情、内部评论、银行后台信息或其他Subject。链接不携带客户姓名和案情明文。

内部命令与客户命令是两个互斥的安全通道：

```text
InternalTaskCommand
  必须有内部身份、taskId、Owner或有效代理、Capability、Authority、职责分离校验和准确subjectBindings[]
  禁止使用CustomerAccessGrant补足内部权限

CustomerGrantCommand
  禁止携带内部taskId、Owner或AuthorityAssignment
  必须绑定tenantId、grantId、入口会话、准确subjectBindings[]、枚举allowedCommand、允许的证据类型、有效期、撤销状态和身份确认强度
```

CustomerAccessGrant不能转换成内部Capability或Authority，不能读取内部Task、WaitReceipt、Audit、Decision理由或冲突详情。客户动作仅形成受控外部输入或证据；是否推进正式业务事实仍由对应聚合按当前版本验证。

CustomerAccessGrant的生命周期也必须经过固定高风险命令：

```text
ISSUE_CUSTOMER_ACCESS_GRANT
REVOKE_CUSTOMER_ACCESS_GRANT
```

- 签发人必须是内部身份，并同时满足专用Capability、Authority、职责分离、客户与Subject关系或身份核验依据。
- 签发命令冻结准确`subjectBindings[]`、枚举allowedCommand、允许Evidence类型、有效期、认证强度和grantVersion。
- 审计必须保存`issuedBy/revokedBy/reason/evidence/occurredAt/recordedAt`。
- Grant秘密只保存不可逆哈希；明文不得进入日志、URL查询串或普通审计字段。
- 每次客户命令都在线校验当前grantVersion和revocationGeneration；撤销立即使既有入口会话、一次性确认Token和未完成命令失效。

## 16. Evidence、Audit与AI治理

### 16.1 Evidence

Evidence至少包含：

```text
tenantId
evidenceId/version
sourceType
uploader/providerRef
receivedAt/effectiveAt
contentHash
storageObjectRef
relatedSubjectRef/version
externalActionRef/providerEventRef
verificationResult及验证人
```

- 原始文件不可覆盖；补正产生新版本。
- 上传重试按uploadSessionId幂等，不能仅按相同Hash合并法律证据。
- 外部上传先隔离和安全扫描。
- 下载必须经应用层重新鉴权和短时地址。
- Chat、普通日志和向量索引只保存受控引用或最小摘要。

### 16.2 Audit

正式事实必须可追溯：

```text
Actor/系统身份
→ AuthorityRef
→ Command
→ Task/Decision
→ Subject版本
→ Policy版本
→ Evidence/ExternalAction
→ Result
```

审计仅追加写入；纠错追加新事实，不覆盖历史。普通业务日志不能替代法律审计。

### 16.3 AI

AI可以：

- 从文字、语音或文件提取候选值。
- OCR、材料分类建议和重复提示。
- 起草报价、合同、提醒和摘要。
- 解释当前责任、缺项和排序原因。

AI不能：

- 改Owner、时效、优先级和Task状态。
- 作Decision或选择Decision结果。
- 判定冲突、证据有效、付款到账、合同执行或材料VERIFIED。
- 接收转案、创建Matter、确定Matter类型、分配律师或激活能力包。
- 使用服务身份替用户完成受保护命令。

上传文件中的文字视为不可信输入，不能通过文档指令扩大工具或数据权限。

每次AI调用至少记录：

```text
tenantId / aiInteractionId / purposeCode
provider / modelId / modelVersion
promptTemplateVersion / toolSchemaVersion / extractionPolicyVersion
输入EvidenceRef及最小化后的inputDigest
发起Actor、可调用工具白名单、输出候选摘要
人工采纳/修改/拒绝结果及后续CommandRef
startedAt / completedAt / failureCode
```

- 只传完成当前明确目的所需的最少字段；默认脱敏，禁止把全案材料、内部冲突详情或无关个人信息拼入Prompt。
- Prompt、模型、工具Schema和提取规则升级必须版本化；旧输出可按原版本解释，不能静默重算正式事实。
- 模型日志、评测集、缓存和向量索引遵守Tenant、法域、留存和删除策略；未经明确合同与授权，不得把客户材料用于Provider训练。
- LLM Gateway只能调用候选提取、草拟和受控查询工具；没有任何正式业务写端点或服务Actor凭证。
- 必须提供Tenant级AI关闭开关。关闭、超时或模型不可用时，用户仍能通过同一工作卡手工完成全部MVP责任，时效和排序不变。

## 17. 《最小Matter身份与后MVP扩展契约 v1.0》（冻结）
superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md
replacement-section: matter-endpoint

本章与第18章共同构成已冻结契约。正式采用方案B：最小Matter保存身份和对被采纳不可变TransferSnapshot的准确引用；不复制销售域客户、合同、需求、材料或冲突快照。任何破坏该边界的变更必须升级契约版本，不得以实现便利静默加入字段。

### 17.1 Matter

```text
Matter
├── tenantId
├── matterId
├── matterRef
├── origin
│   ├── originKind = ACCEPTED_TRANSFER
│   ├── sourceTransferRequestId
│   ├── sourceSnapshotVersion
│   ├── sourceSnapshotDigest
│   ├── acceptanceDecisionId
│   ├── transferAcceptedEventId
│   └── acceptedAt
├── createdAt
└── aggregateVersion = 1
```

- matterId为内部不透明主键。
- matterRef由Matter模块签发，Tenant内唯一、不可修改、不可复用。
- origin创建后不可改写；来源事实补正只能形成后续显式事实，不得覆盖原始采纳语境。
- Matter只采纳不可变TransferSnapshot引用，不复制客户、合同、需求、材料或冲突事实；MVP没有status、matterType、jurisdiction、owner、team或handlingStatus，也不得以metadata/extensions JSON、EAV或预留空字段承载未来业务。

### 17.2 MatterOpeningPort

```text
openFromAcceptedTransfer(
  tenantId,
  transferRequestId,
  snapshotVersion,
  snapshotDigest,
  acceptanceDecisionId,
  transferAcceptedEventId,
  acceptedAt,
  correlationId,
  causationId,
  idempotencyKey
) -> { matterId, matterRef, aggregateVersion }
```

Matter模块签发MatterRef；调用方不能指定matterId或matterRef。`tenantId + sourceTransferRequestId`是Matter创建业务幂等键；相同完整输入重放返回同一Matter。同一来源携带不同snapshotVersion、snapshotDigest或acceptanceDecisionId必须拒绝并进入SYSTEM_RECOVERY。MatterOpeningPort是模块化单体内参与同一UnitOfWork的本地事务端口，不是网络调用。

### 17.3 接收事务

```text
校验当前案管Task、唯一完成契约和TRANSFER_REVIEW Authority
→ 校验TransferRequest、snapshotVersion/digest及materialManifestVersion/hash
→ 校验DealActivated与ContractExecuted绑定同一当前ContractRevision/contentDigest
→ 校验MaterialManifest AcceptReady
→ 校验当前conflictReviewId/scopeHash的PRE_TRANSFER已解决
→ 校验无终止事实
→ DecisionRecorded(ACCEPT，绑定taskId、完成契约及全部准确Subject)
→ TransferAccepted并取得transferAcceptedEventId
→ 调用同一UnitOfWork内的MatterOpeningPort
→ MatterCreated（origin引用transferAcceptedEventId）
→ TransferRequest写MatterLink
→ 案管Task DONE
→ 销售回执更新
→ Audit/CommandReceipt
→ 原子提交
```

中间写入在提交前对其他事务不可见，任一步失败全部回滚；不能出现“已接收但无Matter”或“有Matter但未接收”。MVP的MatterOpeningPort是模块化单体内的本地事务端口，不是网络调用。MatterCreated提交后，经持久化Outbox供未来模块幂等消费；下游消费失败不得撤销已合法完成的接收。

## 18. 后MVP Matter扩展契约
superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md
replacement-section: matter-endpoint

### 18.1 后MVP责任链

```text
MatterCreated
→ MatterRegistered
→ MatterClassified
→ MatterCapabilitiesSelected
→ MatterTeamAssigned
→ MatterAssignmentAccepted
→ MatterHandlingActivated
→ 能力包创建第一张办理Task
```

MVP中只产生MatterCreated，不启用后续消费者。

### 18.2 后续模块

| 模块 | 未来权威事实 |
|---|---|
| Matter Intake & Registration | 名称、参与方角色、法域、管辖、保密等级和登记事实 |
| Classification | Matter业务分类和选择依据；SalesOfferingClass只能作为候选 |
| Capability Binding | primaryPackage、supportingPackages、包版本和兼容性 |
| Allocation & Team | MatterTeamMembership、团队版本、分配和接受事实 |
| Handling Kernel | Matter范围责任、法律事件、工作包和通用稳定端口 |
| Legal Deadline | 法律事实、规则版本、日历、期限和调整决定 |
| Work Product | 成果类型、版本、作者、审阅、提交和Evidence |
| Capability Packages | 综法、非诉、诉讼、执行等具体命令、事实、任务和规则 |

### 18.3 依赖方向

```text
Matter Core <── Registration / Classification / Allocation
      ▲
      └──── Handling Kernel <── Capability Packages
                 ▲
                 ├── Legal Deadline
                 └── Work Product
```

- Matter Core不导入或枚举具体能力包。
- 能力包依赖Core稳定端口，不能直接改Core表。
- 包内责任仍使用静态Task注册，不得建设流程引擎。
- 包之间只能通过显式Event或端口协作。

### 18.4 CapabilitySet

后续模块采用：

```text
MatterCapabilitySet {
  matterId,
  capabilitySetRevision,
  primaryPackageKey,
  supportingPackageKeys[],
  packageVersions,
  selectionBasis,
  policyVersion
}
```

初期界面只选择一个主能力包；辅助包必须显式声明兼容。包版本在已激活Matter上冻结，升级通过显式迁移，不静默改变在途Task或已计算期限。

### 18.5 Matter团队授权

```text
全局Capability/Authority
+ MatterTeamMembership
= 能否在当前Matter执行某类命令
```

MatterTeamMembership至少包含memberId、teamRoleKey、responsibilityScope、有效期、assignmentDecisionRef和teamVersion。团队角色不等于具体Task Owner。

### 18.6 未来模块启用

- 不在MVP预建后续表、默认团队、能力包或Task。
- 新模块启用后，新Matter消费MatterCreated创建登记责任。
- 已有最小Matter通过一次性、幂等产品迁移创建登记责任。
- 历史Matter不能被默认标记为已登记或已分配。
- 禁止使用通用`extensions JSON`、EAV或metadata表承载未来业务。

## 19. 原流程图覆盖结论

三份流程图的全部内容已归位为：

```text
COVERED   业务语义直接覆盖
REPLACED  保留业务目的，以更安全简单的机制替换
DEFERRED  有效需求，但不进入销售MVP
RESERVED  保留未来模块或端口边界
```

覆盖统计：

| 流程图 | 结果 |
|---|---|
| 阶段1–2 | 20节点：11 COVERED、9 REPLACED；23边：13 COVERED、10 REPLACED |
| 阶段3 | 31规则：15 COVERED、12 REPLACED、2 DEFERRED、2 RESERVED |
| 阶段4–5 | 42规则：22 COVERED、11 REPLACED、8 DEFERRED、1 RESERVED |

不存在未归位的MISSING或PARTIAL项。主要有意替换包括：

- 取消全量来源标签确认。
- 取消跨域全局状态。
- 取消超时伪造主管批准。
- 区分Dead-Pool、Public Pool和No-Cooperation Pool。
- 区分报价尚未接受与明确拒绝合作。
- PRE_TRANSFER先于案管接收审核。
- 固定11/11改为条件材料Manifest。
- 到账、合同批准或ExternalAction成功均不单独等于成交。
- Matter分类、分配和办理不进入销售MVP。

## 20. 明确DEFERRED与RESERVED

### 20.1 DEFERRED

- 90日回访自动Task。
- 后续分期和案件里程碑收费。
- 完整开票、退款、冲正、佣金和总账。
- 推荐合作律所。
- 纸质合同和案件纸档附卷。
- Matter登记、分类、团队、办理、期限、成果和结案。
- Matter创建后的持续冲突管理。

### 20.2 RESERVED

- LedgerExportPort。
- MatterCreated事件消费者边界。
- Matter能力包注册契约。
- MatterScopedAuthorization端口。
- Public Pool冷却、领取上限和再分配政策。
- QualitySignal只读投影；不建设质检工作流。

保留不等于提前建表、建Task、建页面或建配置平台。

因此，进入Public Pool在MVP是责任链终点；只有后续版本明确启用领取/再分配政策后，才可由新的权威事件创建新Owner责任。

## 21. 验收级不变量

1. 六聚合只限制销售至转案上下文。
2. 每项业务事实只有一个权威聚合Owner。
3. 系统没有跨业务域的全局状态。
4. 每个Task只有一个具体Owner、一个命令和一个完成契约。
5. Owner有责任但无Authority时仍不能执行。
6. 下游事实不能关闭前置Task。
7. DONE和CANCELLED永不重新OPEN。
8. 内部移交使用WaitReceipt，不把上游Task设为WAITING。
9. 除SYSTEM_RECOVERY外，任何路径都不能暂停原Task动作SLA。
10. 无正式SLA的WaitReceipt不显示具体反馈时间。
11. 超时不伪造人工Decision。
12. PRE_CONTRACT与PRE_TRANSFER是两个不同ConflictReview实例。
13. NOT_ACCEPTED_YET与EXPLICIT_DECLINE进入不同责任链。
14. 到账本身不能产生DealActivated。
15. 文件上传、AI识别或Provider成功不能单独产生VERIFIED、ContractExecuted或MatterCreated。
16. 转案界面不再出现固定11/11。
17. 旧Task、Decision、证据或回调不能推进当前业务版本。
18. 相同命令、Provider事件、时效里程碑和Matter创建重放只产生一次结果。
19. MatterRef只能由Matter模块签发且永不复用。
20. Matter必须保存采纳TransferSnapshot的准确版本和摘要。
21. Matter创建与TransferAccepted、MatterLink、案管Task和销售回执原子一致。
22. Matter在MVP没有状态、类型、Owner、团队或办理字段。
23. MatterCreated之后MVP不生成登记、分配或办理Task。
24. 新增综法、非诉、诉讼或执行能力包不需要修改Matter Core。
25. AI关闭后，Task、命令、优先级、时效、权限和业务结果保持不变。

## 22. 设计完成边界

至本规格，销售MVP总体架构、本体、责任、时效、交互、安全、材料、外部集成、流程覆盖和Matter扩展边界已经闭合。本文冻结的是领域及运行契约，包括命令责任、端口语义、版本绑定、幂等和事务边界；不锁定HTTP/消息传输编码、物理数据库结构或技术框架。

尚未进入：

- 数据库表和索引设计。
- API和Event Schema详细定义。
- 模块包结构和技术选型ADR。
- 测试用例清单。
- 迭代、工作量和部署实施计划。

上述内容只能在本规格复核通过后进入下一阶段，并应按模块和风险边界拆分为多份实施规格，不能把本总体设计直接当成一个超大实施任务。本轮不输出实施计划。

## 历史修订记录（已被当前基线替代）

- TaskOccurrence只以OPEN创建；WAITING是Query Facade根据四类权威来源形成的只读WaitingProjection。
- QuoteIssued只触发报价审批；授权事实触发报价发送责任，外部发送结果由ProviderInbox收敛。
- 每个TransferSnapshot独占一个PRE_TRANSFER Review；RETURN和补正重提均创建新责任/新实例。
- PaymentGate必须携带due_at、业务时区和准确付款条款来源。
- 同一冲突审查中BLOCK决定必须取消其他OPEN决定槽。
- P0-01至P0-15及视觉证据曾被列为销售主链架构验收门槛；当前验收映射以当前MVP基线为准。
