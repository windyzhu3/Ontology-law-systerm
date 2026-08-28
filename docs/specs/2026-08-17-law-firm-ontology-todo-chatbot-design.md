# 律所待办驱动智能管理系统：目标产品基线

> [!WARNING]
> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。

> 历史元数据（原版本补充）：v2.2（2026-08-27）。文件末尾修订曾用于更正更早的示例与状态描述，现已由当前MVP基线替代。

历史元数据（原版本）：2.0
日期：2026-08-17  
历史元数据（原状态）：正式目标产品基线
设计基线：6个业务聚合 + 最小责任内核 + 一卡多态交互

## 1. 基线决策

本规格描述从零建设的目标产品，不以任何旧代码仓库、旧数据表、旧页面、既有流程引擎或历史技术栈为前提。

目标产品不是在传统管理系统上增加聊天窗口，而是从“用户现在应完成什么责任”出发组织全部日常工作：

1. 业务聚合保存权威事实。
2. 领域命令改变事实。
3. Event和Decision记录发生了什么以及由谁负责。
4. Task把事实投影为当前责任。
5. ChatBot把当前责任呈现为一张可执行工作卡。
6. AI只理解、提取、草拟和解释，不拥有业务权力。

任何实现选择都不得破坏本规格的领域、责任、交互和安全边界。

## 2. 产品范围

### 2.1 本规格覆盖

- 面向普通用户的单一响应式Chat入口。
- 销售至转案上下文内的Party、Lead、Opportunity、ConflictReview、Contract、TransferRequest六个业务聚合；该数量不限制整个律所系统未来聚合。
- Task、Decision、Event三个最小责任内核记录。
- 销售从线索接入、首联、商机推进、报价、冲突审查、合同、签署、成交到转案的完整垂直切片。
- 销售、主管、报价审批人、合伙人、行政/案管和财务的必要协作责任。
- 权限、职责分离、证据、审计、幂等、外部动作和AI安全。

### 2.2 后续独立规格

- Matter登记、案件分类、律师分配、案件节点、法律期限和办理能力包。MVP接收时已经创建正式最小Matter身份，但不启动上述后续能力。
- 结案、归档、回访及风险代理后续回款。
- 完整财务、开票、退款和对账域。
- 管理驾驶舱、经营分析和客户服务入口。

## 3. 不可妥协的产品原则

### 3.1 一个普通用户入口

- 普通业务用户只使用响应式Chat Web。
- 首页固定为一句摘要、一张当前工作卡、最多两条后续摘要、一个等待计数和一个输入框。
- 普通用户没有模块菜单、流程中心、配置中心或第二套业务后台。
- 管理后台只服务身份权限、有限策略配置、异常修复、安全审计和运营质检。

### 3.2 一次只推动一个责任

- 首屏只完整展开一张工作卡。
- 一张卡只能有一个Owner、一个业务目的和一个主命令。
- 同一业务对象对同一用户不得同时出现两张可执行卡。
- 等待主管、客户、财务或案管时，原用户不承担对方SLA，等待事项不占当前工作卡。

### 3.3 只问缺失信息

- 已存在或可可靠提取的信息自动形成候选值。
- 每轮默认只问一个问题；只有一句话可同时回答时，最多询问三个紧密相关的净新增值。
- 高频任务全程最多要求用户补五个净新增值。
- 不允许用连续多轮问题规避字段上限。
- 转案材料可批量上传并由系统分类，卡片只展示当前最先需要补的一项。

### 3.4 事实驱动完成

- Task不能通过“勾选完成”关闭。
- Task只能由明确Event或Decision满足完成定义后关闭。
- 每次写操作必须记录Actor、对象版本、业务结果、证据和策略引用。
- 等待外部事实的时间不得计入已经完成本人动作的Owner逾期。

### 3.5 AI没有业务权力

- AI不得决定权限、金额、优惠、状态迁移、冲突结果、审批、签章、付款确认、退款、成交或转案接收。
- LLM输出只作为候选输入，服务端必须重新鉴权、校验对象版本和业务规则。
- AI不可用时，同一工作卡切换为确定性问答，不丢失输入、附件或当前任务位置。

## 4. 目标产品结构

### 4.1 运行形态

- 一个响应式Chat Web。
- 一个模块化单体后端。
- 一个关系事实库，保存业务聚合、责任、审批、审计和幂等回执。
- 一个证据对象存储，通过应用下载网关实时鉴权。
- 一个后台Worker，处理到期扫描、通知和外部动作。
- 一个受控LLM Gateway，不能绕过业务命令层写事实。

具体数据库、对象存储、调度器和模型产品属于实现ADR，不进入产品基线。

### 4.2 内部模块

| 模块 | 单一职责 |
|---|---|
| Identity/AuthZ | 用户、组织、能力角色、对象关系、字段脱敏、代理和职责分离 |
| Sales Domain | Lead与Opportunity的联系、资格、进展、报价和归属事实 |
| Conflict Domain | 两次目的限定冲突审查、Finding最小披露和豁免决定 |
| Contract Domain | 不可变合同版本、审批、签署、执行和成交门槛 |
| Transfer Domain | 不可变转案快照、退回项、案管接收和MatterLink；不得签发MatterRef |
| Matter Identity Core | 接收时签发正式matterId/MatterRef，保存来源TransferSnapshot的不可变版本与摘要 |
| Responsibility Kernel | 固定Task类型、Owner、SLA、Decision和Event |
| Chat Orchestrator | 当前任务、候选值、WorkCard和确认令牌 |
| Evidence/Audit | 文件版本、哈希、ACL、命令审计与AI遥测 |
| External Action | 签章、消息、支付等外部动作及UNKNOWN恢复 |

### 4.3 内部命令一致性

一个内部业务命令在同一事务中完成：

鉴权 → 对象版本校验 → 业务规则校验 → 修改事实 → 记录Event/Decision/Audit → 创建或关闭Task → 保存CommandReceipt

外部签章、消息或支付不得在该事务中直接调用。事务先持久化ExternalAction意图，由Worker调用供应商；回调和主动查询按供应商幂等键更新结果。

ExternalAction固定使用`PENDING → DISPATCHING → DISPATCHED | FAILED | UNKNOWN`、`DISPATCHED → SUCCEEDED | FAILED | UNKNOWN`和`UNKNOWN → SUCCEEDED | FAILED`状态转移，并保存dispatchAttemptId、attemptNo、providerAccountRef、providerRef、dispatchLeaseUntil、effectKey、nextProbeAt、resolutionDueAt和probeCount。`DISPATCHING`租约过期必须转入`UNKNOWN`并核验，不得回到`PENDING`重派。人工Owner成功提交外部动作意图后，其Task由ExternalActionRequested完成；用户只看到WaitReceipt。Worker负责主动核验，超过resolutionDueAt时生成运营异常责任。

供应商回调先进入Provider Inbox：验证供应商签名或双向认证、timestamp/nonce与重放窗口、providerAccount到tenant的绑定；以`provider + providerAccount + providerEventId`全局唯一持久化`canonicalPayloadDigestRef`和验证结果。该Digest Ref必须包含算法与Canonicalization Profile代码/版本，不得退化为裸Hash列。通过后才能关联`externalActionId + providerRef + subjectRef/revision`并检查合法事件转换。验证失败、跨租户或无法关联的回调只进入隔离审计，不得调用领域命令或修改WaitReceipt。

通过验证的供应商回调和受信主动查询必须进入同一个幂等内部命令，并在单一事务中完成：锁定ExternalAction → 校验providerRef、subjectRef/revision与当前状态 → 更新终态 → 写权威领域Event → 创建、关闭或取消后续Task → 更新WaitReceipt → 写Audit与CommandReceipt。重复或乱序回调不得使终态倒退；UNKNOWN只能由权威回调、主动查询或有权运营处置解除。任一步失败时整笔事务回滚。

## 5. 销售至转案上下文的六个业务聚合

### 5.1 Party

自然人或组织的客观主体。

不可破坏的不变量：

- “客户、委托人、对方”不是Party永久属性。
- 角色只存在于具体Opportunity或Transfer上下文。
- 去重不得删除原始Lead来源记录。

### 5.2 Lead

某渠道进入的一次线索记录。

最小内容：

- 来源、进入时间、当前销售、Party候选。
- 联系结果、资格处置和证据。
- 分配和改派历史。

### 5.3 Opportunity

围绕一项法律需求形成的拟委托商机。

最小内容：

- 法律需求。
- 客户、委托人和对方快照。
- 当前销售和处置结果。
- 不可变QuoteRevision集合及客户接受的准确版本。

一个Opportunity只代表一项拟委托；同一Party可以拥有多个Opportunity。

### 5.4 ConflictReview

在指定目的、参与方快照和法律需求快照上执行的冲突审查。

不可破坏的不变量：

- PRE_CONTRACT与PRE_TRANSFER必须是两个独立实例。
- 参与方或法律需求变化后，旧结论自动失效。
- 原始Finding只对有权风险角色披露。
- 豁免必须针对具体Finding并由有权合伙人作出。

### 5.5 Contract

不可变合同版本及其执行、收费和成交门槛。

最小内容：

- ContractRevision、批准版本和执行版本。
- FeeTerms、PaymentGate、SignaturePlan、签署证据和不可变PaymentConfirmation。
- 执行后的合同禁止普通编辑。

报价接受、合同审批、合同签署和成交必须绑定准确版本及内容哈希。

PaymentConfirmation不是银行流水或总账的权威源，只是对一个受信外部到账记录已分配至本Contract当前PaymentGate的不可变确认，必须保存外部流水引用、金额、币种、收款账户、确认时间、confirmedBy、Evidence和幂等键。`tenantId + trustedSourceAccount + externalTransactionId`形成租户范围内、跨所有Contract唯一的externalPaymentRef；财务确认必须先取得该唯一约束，已绑定其他Contract时进入受控异常。MVP禁止跨Contract拆分、冲正或退款；这些能力进入后续财务域，因此不增加第七个销售业务聚合。

SignaturePlan冻结全部必需签署方、签署人角色、授权依据、签署方式、用印和归档要求。批准版本生成approvedContentDigest；签署产物保存signedArtifactHash及能证明其承载该approvedContentDigest的签章信封、清单或有权人工比对记录。上传签字文件只形成SignatureEvidenceSubmitted。人工核验Task固定以SignatureEvidenceReviewRecorded(outcome=VERIFIED|REJECTED)完成：VERIFIED派生SignatureEvidenceVerified；REJECTED必须按evidenceSource、reasonCode和冻结SignaturePlan确定性分流。同版本线下缺页/模糊生成销售补证Task；线上信封失败、过期或撤销生成行政REISSUE_E_SIGNATURE Task及新ExternalAction；合同正文、签署人、授权或签署方式变化生成PREPARE_CONTRACT修正责任并强制新ContractRevision和重新审批；未知原因进入有权异常核验。任何路径不得复用旧审批或旧证据。确定性验签服务也可按版本化策略直接产生SignatureEvidenceVerified。归档只形成ContractArchived。

线上签署完成必须由供应商权威回调产生ElectronicSignatureCompleted，携带envelope/certificate、signedArtifactHash、approvedContentDigest、签署人身份和授权证据；确定性验签通过后派生SignatureEvidenceVerified，无法确定时创建人工核验Task。ElectronicSignatureInitiated只表示已发起，不能满足SignaturePlan。只有所有必需证据均已验证、全部SignaturePlan步骤完成、签署人身份与权限有效、必要用印和归档完成且外部状态不为UNKNOWN、FAILED或已撤销时，系统才能产生ContractExecuted。

### 5.6 TransferRequest

销售提交给案管的转案请求。

最小内容：

- Opportunity与Contract引用。
- 不可变TransferSnapshot。
- 退回项、补正版本和接收结果。
- 接收后写一次的MatterLink `{matterId, matterRef}`；MatterRef只能由Matter Identity Core签发。

第二次冲突审查未解决前不得接收；案管接收事务成功前不得生成Matter。接收时原子写入DecisionRecorded、TransferAccepted、MatterCreated和MatterLink。

## 6. 最小责任内核

### 6.1 Task

Task只是当前责任投影，不是业务事实源。

| 字段 | 规则 |
|---|---|
| taskId/taskType | taskType为代码注册的固定枚举 |
| subjectRef/revision | 高风险任务必须锁定准确对象版本或哈希 |
| ownerRef | 只能有一个Owner |
| primaryCommand/commandVariant | Task创建时固定，生命周期内不得改变 |
| actionDueAt | 只衡量当前Owner动作 |
| status | 仅OPEN、WAITING、DONE、CANCELLED |
| waitingReason/nextCheckAt | 仅用于已确定Owner的未来动作，或SYSTEM_RECOVERY安全暂停；内部/外部结果等待使用WaitReceipt |
| triggerEventId/completionContractId/completionEventId | completionContractId由代码注册并固定唯一completionEventType；决定类再固定decisionKind，禁止通用完成表达式 |
| policyRef/version | 保存适用策略并支持乐观锁 |
| predecessorTaskId | 仅用于退回补正的责任追溯，不形成可编排流程连边 |

超时和升级是派生标志，不增加Task状态。

每个Task occurrence只代表一次可执行动作。联系尝试、进展记录、签署跟进、催款或承诺记录完成当前Task；未来再次行动必须创建新Task，不能让同一实例跨多轮等待反复重置SLA。完成Event必须同时匹配taskId或causationId、subjectRef以及准确revision/hash。

### 6.2 Decision

Decision保存有权人员针对准确对象快照作出的决定：

- 固定decisionKind和合法outcome。
- subjectRef、revision或hash。
- decidedBy、authorityRole、decidedAt。
- reasonCode、comment、evidenceRefs和policyRef。
- 不覆盖旧Decision；需要变更时追加新记录并引用被替代记录。

付款到账、客户接受、签署完成和冲突检索结果是事实，不是Decision。

固定DecisionKind注册表：

| decisionKind | 锁定对象 | 合法结果 | 附加约束 |
|---|---|---|---|
| LEAD_VALIDITY_REVIEW | Lead版本 | INVALID、REOPEN | 必须有理由 |
| LEAD_AUTOARCHIVE_RECHECK | Lead自动归档版本 | KEEP_ARCHIVED、REOPEN | 只能复查系统自动归档结果 |
| CONTACT_RETRY_BREACH_DISPOSITION | Lead及联系计划版本 | RETRY_SAME_OWNER、REASSIGN | 漏做窗口不能直接判定失联 |
| OPPORTUNITY_REJECTION_DISPOSITION | Opportunity及最新商业回复版本 | STOP_FOLLOW_UP、CONTINUE_SAME_OWNER_REVISED_PLAN、CONTINUE_NEW_OWNER_REVISED_PLAN | 仅用于客户明确拒绝合作 |
| QUOTE_DISCOUNT_APPROVAL | QuoteRevision及hash | APPROVE、REJECT | 超过50%由两个不同Actor分别决定 |
| CONFLICT_WAIVER | purpose、ConflictReview、Finding和scopeHash | WAIVE、BLOCK | 只向有权风险角色披露Finding |
| CONTRACT_APPROVAL | ContractRevision及hash | APPROVE、REJECT | 固定authorizationScope；纯风险必须为PURE_RISK_ENGAGEMENT并记录有权合伙人角色 |
| PAYMENT_D15_DISPOSITION | PaymentGate及version | CONTINUE、TERMINATE | CONTINUE必须给出owner和nextCheckAt |
| TRANSFER_REVIEW | TransferSnapshot及version | ACCEPT、RETURN | RETURN必须指向具体缺项 |

MAKE_DECISION以及REVIEW_TRANSFER中的决定责任，只能由匹配同一task、decisionKind和subject版本的DecisionRecorded关闭；后续QuoteAuthorized、ConflictReviewResolved、DealActivated或TransferAccepted不能替代Decision完成事实。

### 6.3 Event

Event信封至少包含：

eventId、eventType、aggregateType、aggregateId、aggregateVersion、occurredAt、actorRef、correlationId、causationId、idempotencyKey、schemaVersion、evidenceRefs、payload

Event追加保存，不承担通用事件溯源平台职责。

### 6.4 防止演变为通用BPM

1. 状态迁移和DoD由领域代码定义。
2. 配置只允许调整Owner映射、SLA、阈值、材料清单和文案。
3. 不保存任意流程图、脚本或下一节点表达式。
4. Task之间不连边；新Event决定是否产生下一Task。
5. 联合审批只实现明确业务需要的ALL_OF/ANY_OF，不建立流程实例。
6. 不提供“通用人工任务、通用对象更新、任意HTTP动作”。

## 7. 销售可理解的四个阶段

| 阶段 | 销售责任结果 | 其他角色责任 | 阶段完成事实 |
|---|---|---|---|
| 1. 接单与判断 | 完成真实联系并给出有效、疑似无效或未接通结果 | 主管复核疑似无效 | OpportunityOpened或Lead进入重试/关闭 |
| 2. 推进委托 | 留下真实进展、形成报价、记录客户接受并补齐冲突主体 | 报价审批人和冲突豁免人作出Decision | 报价已接受且PRE_CONTRACT已解决 |
| 3. 签约成交 | 准备合同、完成本人签署动作并跟进必要首款 | 合伙人、行政/案管和财务完成各自责任 | ContractExecuted并产生DealActivated |
| 4. 转交案管 | 提交完整快照，退回时只补指定项 | 系统/风险权力槽先解决PRE_TRANSFER，案管只完成材料核验与接收 | TransferAccepted并由Matter模块产生MatterCreated和MatterRef |

等待不是第五阶段，也不是销售可执行Task。

## 8. 固定任务类型

### 8.1 销售任务

| taskType | 业务责任 |
|---|---|
| CONTACT_LEAD | 完成首次联系或政策规定的重试 |
| ADVANCE_OPPORTUNITY | 记录真实进展、失联重试或回访 |
| PREPARE_QUOTE | 形成准确报价版本并发送或提交审批 |
| RECORD_QUOTE_RESPONSE | 记录客户对准确报价版本的四态商业回复及证据 |
| PROVIDE_CONFLICT_INPUT | 补齐冲突审查所需主体 |
| PREPARE_CONTRACT | 形成准确合同版本并提交审批 |
| COMPLETE_SIGNATURE_ACTION | 完成销售负责的签署或客户跟进动作 |
| FOLLOW_FIRST_PAYMENT | 完成催款、承诺记录或付款证据转交 |
| ADJUST_FOLLOW_UP | 记录客户新承诺或取消准确的未来跟进责任，不直接编辑WAITING Task |
| SUBMIT_TRANSFER | 提交不可变转案材料快照 |
| FIX_TRANSFER | 只补案管明确退回的缺项 |

### 8.2 协作角色任务

| taskType | 责任角色 | 业务责任 |
|---|---|---|
| RESOLVE_LEAD_INGRESS | 来源负责人或销售主管 | 只处理重复候选、数据缺失或无人可分配等入口异常 |
| MAKE_DECISION | 主管、报价审批人、合伙人、部长 | 对固定decisionKind作出有权决定 |
| COMPLETE_SIGNATURE_STEP | 行政或案管 | 发起线上签章、核验签署证据、完成用印或归档 |
| CONFIRM_PAYMENT | 财务 | 将可信流水确认并满足准确PaymentGate |
| REVIEW_TRANSFER | 案管 | 接收或退回准确TransferSnapshot |
| RESOLVE_EXTERNAL_ACTION | 运营 | 核验超过恢复时限的签章、消息或支付UNKNOWN状态 |
| RESOLVE_SYSTEM_RECOVERY | 运营 | 修复阻断用户安全执行的内部规则或数据异常 |

## 9. 关键业务策略基线

策略数值全部版本化；下列为销售MVP首版业务基线。

### 9.1 线索与联系

- 正常线索导入、格式校验和自动分配不制造人工待办；只有重复候选、关键数据缺失或无人可分配时生成入口异常责任。
- 自动分配按可用销售轮询，跳过离岗、请假或不可服务人员。
- 首次联系责任时限为30个连续自然分钟。
- T0重试次数和可联系时段按渠道配置。
- T+1和T+2在早、中、晚各至少一次真实联系尝试。
- 联系重试在Task创建前固定渠道：拨号尝试只由ContactAttemptRecorded完成，CONNECTED后创建独立结果采集Task；供应商消息只由ExternalActionRequested完成，权威ContactMessageSent后才创建后续联系责任。换渠道必须取消原OPEN Task并创建新occurrence。
- 疑似无效由主管在24小时内复核；超时自动按政策归档时，系统写入LeadAutoArchived事件并生成7日主管复查任务，不伪装成人工Decision。

### 9.2 商机推进

- 实质进展包括已接通电话、主动来访、面聊、已发送报价、外访和有效微信对话。
- 草稿、未接通呼叫或内部备注不能刷新实质进展时钟。
- 最新实质进展后第4日提醒、第5日到期、第7.5日升级，并以新的Task occurrence承接下一次行动。
- 累计30个自然日无实质进展时，时效内核只发阈值事实；Opportunity域按准确progressClockVersion重验后确定性写入OpportunityReleasedToPublicPool并取消旧销售责任。

### 9.3 报价和冲突

- 执行类业务优惠不超过20%由销售权限决定；不超过40%由组长决定；不超过50%由部长决定；超过50%必须由主任与合伙人两个不同Actor分别批准。
- QuoteRevisionCreated只完成固定CREATE_QUOTE_REVISION变体的草案Task，不能关闭发送、审批提交或“客户已收到报价”的责任。创建后根据权限生成新Task及固定commandVariant：权限内的ISSUE_QUOTE在ExternalActionRequested持久化后完成用户责任；需审批以QuoteApprovalRequested关闭提交责任。
- 消息供应商权威确认后才产生QuoteIssued；该Event绑定准确QuoteRevision、内容哈希、收件人和发送证据，并由同一ExternalAction回调事务更新WaitReceipt。QuoteIssued之前不得创建客户回复Task。
- 全部报价Decision满足后产生QuoteAuthorized，并生成新的“发送已批准报价”Task；原Task保持DONE。
- 客户商业回复必须通过RECORD_QUOTE_RESPONSE绑定准确QuoteRevision、内容哈希和Evidence；只有ACCEPTED产生QuoteAccepted。没有QuoteAccepted不得创建合同任务。
- PRE_CONTRACT冲突审查在报价接受后进行。
- 销售只收到“可继续、需补信息、等待风险决定、已阻断”四类结果，不接触全所原始命中详情。

### 9.4 合同、签署和成交

- 合同一次审批同时覆盖条款风险和条件性纯风险审批。
- 分期最多两期、期限不超过一个月、首付不低于50%；例外必须走有权决定。
- 普通及半风险收费只有在准确ContractExecuted、当前PaymentGateSatisfied且不存在有效EngagementTerminated同时成立后才产生DealActivated；单独到账不得显示成交。
- 纯风险收费只有在准确ContractExecuted、当前合同版本的专门合伙人授权Decision且不存在有效EngagementTerminated同时成立后才产生DealActivated，不创建虚假首款。
- 线下签署区分客户签字、机构用印和归档责任；线上签署区分发起责任、客户等待和完成事实。

### 9.5 付款和转案

- 客户截图、回单或口述只是Evidence，不等于到账。
- 销售MVP只支持一笔可信到账对应一个Contract及其首款PaymentGate，不支持跨合同拆分。财务确认作为Contract内不可变PaymentConfirmation子记录保存；跨合同分配、退款、冲正和总账进入后续财务域。
- D0定义为PaymentGate.dueAt。PaymentGate必须有精确dueAt和组织业务时区；D7、D15分别在D0加7、15个日历日的同一当地时刻触发，以paymentGateId、gateVersion和milestone组成幂等键；门槛满足、终止或有效修订后取消旧里程碑。
- D7生成销售催款责任；D15生成部长终止或继续等待决定。CONTINUE生成指定owner和nextCheckAt的新跟进Task；TERMINATE写入EngagementTerminated，取消全部尚未完成的签署、付款和转案Task并阻断DealActivated。迟到PaymentConfirmed可作为财务事实保留，但不得重新满足已终止门槛或激活成交；存在到账时产生RefundRequired或受控财务异常。
- 只有DealActivated后才能生成SUBMIT_TRANSFER。首次提交锁定TransferSnapshot并创建或绑定独立ConflictReview，purpose为PRE_TRANSFER且保存conflictScopeHash。
- 案管退回必须指向具体缺项；销售只补退回项，不重新填写全部内容。
- FIX_TRANSFER以TransferResubmitted及新snapshotVersion完成，不能因草稿快照创建而提前完成。
- 只有参与方或法律需求哈希变化时，新的TransferSnapshot才使旧PRE_TRANSFER结论失效。
- PRE_TRANSFER确定性筛查只产生CLEAR、NEED_INFO或FINDING：CLEAR写ConflictReviewResolved并创建REVIEW_TRANSFER；NEED_INFO创建仅补明确冲突字段的FIX_TRANSFER变体，重新提交后按新scopeHash创建或复用审查；FINDING创建CONFLICT_WAIVER决定责任。全部Finding被WAIVE后才写ConflictReviewResolved并创建REVIEW_TRANSFER；任一BLOCK写ConflictReviewBlocked，不创建案管接收Task，销售只得到最小阻断说明。
- 每个TransferResubmitted必须穷尽路由：scopeHash未变且存在有效ConflictReviewResolved时，为新snapshotVersion直接创建REVIEW_TRANSFER；scopeHash变化或无有效结论时创建/复用PRE_TRANSFER，只有解决后才创建REVIEW_TRANSFER；NEED_INFO、未决定FINDING或BLOCK状态均不得创建接收Task。任务去重按责任槽定义：REVIEW_TRANSFER使用`transferRequestId + snapshotVersion + taskType`；冲突豁免至少使用`conflictReviewId + scopeHash + findingId + decisionKind + authoritySlot`，不能把多个Finding或双人决定合并成一个Task。
- 接收命令在同一事务重新校验当前快照、准确已执行且已成交的合同版本、MaterialManifest AcceptReady、相同scopeHash的PRE_TRANSFER已解决且不存在终止事实，然后原子写入DecisionRecorded、TransferAccepted、MatterCreated和write-once MatterLink；MatterRef由Matter Identity Core签发。任一步失败全部回滚，MatterCreated后MVP不生成登记、分类、分配或办理Task。

## 10. 一卡多态交互基线

用户只学习一个WorkCard。当前Task的固定命令成功后只允许SUBMITTING → DONE；补正使用RETURNED → ASK，异常使用RECOVERY后返回原状态。WAITING Task只表示新创建的同Owner定时责任，或因SYSTEM_RECOVERY安全暂停的未完成责任；只有receiptId的WaitReceipt独立于WorkCard状态机。用户不得通过次操作让原Task改期或取消，任何变更都由新的单命令卡产生领域Event后替换旧occurrence。

硬预算：

| 项目 | 上限 |
|---|---|
| 首屏 | 1张完整卡、2条摘要、1个等待计数、1个输入框 |
| WorkCard | 6行正文；服务端最多返回4项事实、任一状态最多展示3项；1个主操作、1个非破坏性次操作 |
| 每轮提问 | 默认1个问题，最多3个紧密相关值 |
| 单个高频任务 | 5个净新增值、3次用户提交、1次最终确认 |
| 消歧 | 最多1次、最多3个候选 |
| 草稿预览 | 默认最多3条关键变化 |
| 普通提醒 | 一个“打开待办”按钮 |

详细状态、卡片契约和逐任务对话见《销售MVP工作卡与对话状态设计》。

## 11. 权限、合规与AI安全

### 11.1 权限

每次读写同时考虑：

- 能力角色和组织范围。
- 当前Owner及业务对象关系。
- 对象和字段级权限。
- 附件敏感等级和访问目的。
- 对象当前版本和状态。
- 操作风险级别。
- 代理授权有效期。
- 职责分离。

菜单隐藏不是安全控制。Chat、搜索、附件、通知、审计和导出均执行相同权限判断。

### 11.2 不可删除的合规不变量

- 两次冲突审查不可合并。
- 报价审批、冲突豁免、合同审批、付款确认、退款审批和转案接收不得由AI完成。
- 发起人不得利用兼任角色审批自己提交的高风险事项。
- 已执行合同、Decision、Event和Evidence不得无痕覆盖或硬删除。
- 外部签章或支付处于UNKNOWN时，不得显示成功、失败或直接重试。
- 终止不得删除合同、收款、决定或证据；存在可退余额时移交财务处理。
- 所有写命令执行对象版本校验和持久化幂等。

### 11.3 AI治理

- 模型、Prompt、工具Schema和提取策略独立版本化。
- 工具只允许固定命令，不提供任意SQL、任意HTTP或通用实体更新。
- 确认令牌绑定Actor、Task、SubjectRevision、Command和`payloadDigestRef`，并一次性使用。`payloadDigestRef`必须带算法与规范化Profile版本。
- AI遥测与业务审计分离；不将完整案情、证件信息或合同正文写入普通模型日志。
- 模型失败、越权、Prompt注入或质量退化时，可独立关闭AI辅助能力而不影响结构化WorkCard；MVP中的AI没有正式业务命令写权。

## 12. 发布路线

### R1：Chat-only首联闭环

- 线索接入、轮询分配、首联、重试、资格判断和主管无效复核。
- WorkCard全状态、提醒、AI降级、证据和审计。

### R2：推进委托

- 实质进展、失联重试、报价、优惠审批、客户接受和PRE_CONTRACT冲突审查。

### R3：签约与转案

- 合同版本、审批、签署、PaymentGate、D7/D15、成交、转案快照、PRE_TRANSFER冲突审查和案管接收。

每个版本先交付确定性能力，再开放只读AI和候选提取/草拟能力；所有正式业务写入始终由真实用户确认后的确定性命令或明确授权的服务Actor完成，不开放AI写命令。

## 13. 验收标准

### 13.1 产品简单性

- 新销售不接受菜单培训，也能完成首联、报价、合同和转案代表任务。
- 每类高频任务至少80%的实例在三次用户提交、一次确认和零页面跳转内完成本人责任。
- 首次任务成功率逐类不低于90%。
- 90%以上用户能在10秒内回答等待事项“谁在处理、何时反馈、我还要不要做事”。
- AI降级任务成功率不低于85%，相对正常模式最多增加一次用户提交。

### 13.2 业务正确性

- 重复请求不产生重复报价、签章、成交、转案或MatterRef。
- Task只能由准确Event或Decision关闭。
- 对象版本变化后，旧确认令牌和旧高风险决定失效。
- 两次冲突审查、合同版本、付款门槛和转案快照均可追溯。

### 13.3 安全

- 越权对象在搜索、Chat、附件、通知和审计中均不可见。
- 冲突Finding按最小披露原则处理。
- AI不能调用审批、豁免、签章、付款确认、退款或转案接收命令。
- 外部UNKNOWN状态不会触发重复操作或虚假成功。

## 14. 明确不做

- 通用Todo、BPMN、可视化流程设计器和运行时Policy DSL。
- 运行时本体平台、OWL、EAV或图数据库作为首期前提。
- 微服务、内部消息总线、完整CQRS和事件溯源平台。
- 普通用户模块菜单或第二套日常业务后台。
- 同时建设PC、微信、小程序和多套交互范式。
- 自主高风险Agent。
- 在产品规格中锁定具体框架、中间件或存储品牌。

## 15. 设计结论

目标产品内部保持法律事实、责任、权限和审计的严谨；用户只面对：

1. 现在做什么。
2. 为什么现在做。
3. 还缺什么。
4. 谁在等待或处理。
5. 完成后发生什么。

销售至转案上下文的复杂性必须被六个业务聚合、最小责任内核和一卡多态交互吸收；后续Matter能力通过独立上下文和能力包扩展。任何内部复杂性都不能重新泄漏为菜单、流程图、配置平台或长表单。

## 历史修订记录（已被当前基线替代）

本节记录销售主链工作卡的历史验收修订，现已由当前MVP基线替代。普通用户入口始终保持“一句摘要＋一张已展开且可直接执行的当前WorkCard＋最多两条后续摘要＋一个等待计数＋固定Chat输入框”。TaskOccurrence初始状态只允许OPEN；等待由Query Facade依据未完成责任、外部动作、审批槽和时间门槛投影，不把已完成Task伪造成WAITING。

| P0 | 当前责任 | 唯一主命令 | 明确结果边界 |
|---|---|---|---|
| 01 | 疑似重复线索 | 确认线索归属 | 追加重复判断，不覆盖既有事实 |
| 02 | 联系方式缺失 | 保存并继续分配 | 完成接入，不代表已联系 |
| 03 | 人工指定Owner | 分配给所选销售 | 形成分配事实；联系是新责任 |
| 04 | 零候选调配 | 记录本次调配处置 | 不伪造分配结果 |
| 05 | 疑似无效复核 | 记录复核决定 | 追加主管决定，不删除历史 |
| 06 | 商机停滞或报价拒绝 | 记录处置决定 | 后续行动创建新责任 |
| 07 | 提交报价审批 | 提交这份报价审批 | 仅形成审批请求 |
| 08 | 报价授权决定 | 记录报价授权决定 | 批准不代表已发送 |
| 09 | 报价发出 | 发送这份报价给客户 | 仅形成ExternalAction请求 |
| 10 | 报价发送修正 | 按修正信息重新发送 | 新请求绑定失败记录，不覆盖历史 |
| 11 | 不可直接重发处置 | 记录发送处置 | UNKNOWN不得改写为已发送 |
| 12 | 冲突Finding决定 | 记录冲突决定 | BLOCK取消同审查其他OPEN槽 |
| 13 | 首次转案 | 提交案管审核 | 新Snapshot＋独立PRE_TRANSFER Review |
| 14 | 案管RETURN | 退回销售补正 | ReturnItem＋新FIX_TRANSFER，旧Task不重开 |
| 15 | 补正重提 | 重新提交案管审核 | 新Snapshot＋新Review，旧实例不复用 |

高保真验收证据统一位于 `docs/design/sales-mvp-workcards/`。这些图是工作卡状态，不是15个模块页面。
