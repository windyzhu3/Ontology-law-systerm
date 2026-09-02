# R1 Task 完成合同矩阵

Contract ID: R1-TASK-COMPLETION-V1

Status: FROZEN

确认日期：2026-09-02

本合同冻结 P0-01 至 P0-04、`CONTACT_LEAD` 三结果、来源停用请求确认和主管有效性复核。它只使用当前 52＋2 事实，不新增表，也不实现 R2/R3/Matter。

## Task registry

| TaskType | BusinessPurpose | SubjectSelector | OwnerAuthoritySlot | PrimaryCommand | PayloadSchema | CompletionFactType | CompletionBinding | NaturalIdempotencyKey | LockRoot | SLA |
|---|---|---|---|---|---|---|---|---|---|---|
| RESOLVE_LEAD_DUPLICATE | P0-01 确认疑似重复 Lead 的归属，不删除或合并 Lead | `lead.lead@revision` | `SOURCE_INTAKE_OWNER + LEAD_INGRESS_RESOLVE` | RESOLVE_DUPLICATE_LEAD | `ResolveDuplicateLeadV1@1` | responsibility.decision_record | `decisionType=LEAD_DUPLICATE_RESOLUTION; subject=Lead@revision` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |
| COMPLETE_LEAD_INGRESS | P0-02 一次写入 V850 Ingress Completion 槽 | `lead.lead@revision` | `SOURCE_INTAKE_OWNER + LEAD_INGRESS_COMPLETE` | COMPLETE_LEAD_INGRESS | `CompleteLeadIngressV1@1` | lead.lead | `new revision with complete ingress slot` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |
| ASSIGN_LEAD | P0-03 创建唯一 OPEN Assignment 并冻结首联 Owner | `lead.lead@revision` | `ROUTING_SUPERVISOR + LEAD_ASSIGN` | ASSIGN_LEAD | `AssignLeadV1@1` | lead.lead_assignment | `new assignment@revision; Lead current pointer exact match` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |
| RESOLVE_LEAD_ROUTING_GAP | P0-04 记录零候选调配处置 | `lead.lead@revision` | `ROUTING_SUPERVISOR + LEAD_ROUTING_DECIDE` | RECORD_ROUTING_DISPOSITION | `RecordRoutingDispositionV1@1` | responsibility.decision_record | `decisionType=LEAD_ROUTING_DISPOSITION; subject=Lead@revision` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |
| ACK_SOURCE_INTAKE_STOP_REQUEST | 确认来源停用请求已被准确 Owner 接收，不改变 SourceAccount | `lead.lead@revision + routing decision hash` | `SOURCE_INTAKE_OWNER + SOURCE_INTAKE_REQUEST_ACK` | ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST | `AcknowledgeSourceIntakeStopRequestV1@1` | responsibility.decision_record | `decisionType=SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED; causal decision hash` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_1D_V1` |
| CONTACT_LEAD | 由当前唯一 Assignment Owner 记录一次真实首联结果 | `lead.lead@revision + assignment@revision` | `ASSIGNMENT_OWNER + SALES_CONTACT_OWNER` | RECORD_CONTACT_RESULT | `RecordContactResultV1@1` | lead.lead_contact_result | `new result bound to taskId and leadId` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_CONTACT_30M_V1` |
| REVIEW_LEAD_VALIDITY | 主管解析疑似无效或重试耗尽 Lead | `lead.lead@revision + triggering result hash` | `ROUTING_SUPERVISOR + LEAD_VALIDITY_REVIEW` | REVIEW_LEAD_VALIDITY | `ReviewLeadValidityV1@1` | responsibility.decision_record | `decisionType=LEAD_VALIDITY_REVIEW; causal result hash` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |

`CompletionFactType` 是完成原 Task 的唯一事实类型。ActionDraft、HTTP 200、Audit、Event、Outbox、Receipt 或 UI 点击不能替代该事实。

Task行的自然幂等槽是Tenant＋Task subject scope＋调用方UUID `Idempotency-Key`；物理映射服从现有`tenant_id, envelope_type, command_scope_digest, command_id`。`LockRoot`中的`COMMAND_SLOT`阶段先取得Tenant＋Command UUID事务级advisory lock，再查询或插入slot，从而拒绝同Tenant跨Scope复用并保持Receipt查询单义。capture与internal操作使用HTTP矩阵各自的SubjectBinding形成scope，不借用Task ID。

## Completion branches

| BranchID | TaskType | OutcomeCode | ReceiptResult | CompletionFactType | CompletionBinding | EventType | QueueOwner | AllowedSuccessorTaskTypes | SuccessorPolicy | SuccessorOwnerSlot |
|---|---|---|---|---|---|---|---|---|---|---|
| P0_01_LINK_EXISTING | RESOLVE_LEAD_DUPLICATE | LINK_EXISTING_PARTY | SUCCEEDED | responsibility.decision_record | `LEAD_DUPLICATE_RESOLUTION@hash` | `LeadDuplicateResolutionRecordedV1` | R1_PROJECTION | COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP | R1_LEAD_NEXT_RESPONSIBILITY_V1 | POLICY_SELECTED |
| P0_01_KEEP_SEPARATE | RESOLVE_LEAD_DUPLICATE | KEEP_SEPARATE | SUCCEEDED | responsibility.decision_record | `LEAD_DUPLICATE_RESOLUTION@hash` | `LeadDuplicateResolutionRecordedV1` | R1_PROJECTION | COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP | R1_LEAD_NEXT_RESPONSIBILITY_V1 | POLICY_SELECTED |
| P0_02_COMPLETE | COMPLETE_LEAD_INGRESS | INGRESS_COMPLETED | SUCCEEDED | lead.lead | `lead@newRevision` | `LeadIngressCompletedV1` | R1_PROJECTION | ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP | R1_LEAD_NEXT_RESPONSIBILITY_V1 | POLICY_SELECTED |
| P0_03_ASSIGN | ASSIGN_LEAD | ASSIGNED | SUCCEEDED | lead.lead_assignment | `assignment@revision0` | `LeadAssignedV1` | R1_PROJECTION | CONTACT_LEAD | DIRECT | ASSIGNMENT_OWNER |
| P0_04_SCHEDULE_ROUTING_REVIEW | RESOLVE_LEAD_ROUTING_GAP | SCHEDULE_ROUTING_REVIEW | SUCCEEDED | responsibility.decision_record | `LEAD_ROUTING_DISPOSITION@hash` | `LeadRoutingDispositionRecordedV1` | R1_PROJECTION | RESOLVE_LEAD_ROUTING_GAP | NEXT_BUSINESS_WINDOW | SAME_ROUTING_SUPERVISOR |
| P0_04_RETRY_ASSIGNMENT_NOW | RESOLVE_LEAD_ROUTING_GAP | RETRY_ASSIGNMENT_NOW | SUCCEEDED | responsibility.decision_record | `LEAD_ROUTING_DISPOSITION@hash` | `LeadRoutingDispositionRecordedV1` | R1_PROJECTION | CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP | R1_ASSIGNMENT_RETRY_V1 | POLICY_SELECTED |
| P0_04_REQUEST_SOURCE_INTAKE_STOP | RESOLVE_LEAD_ROUTING_GAP | REQUEST_SOURCE_INTAKE_STOP | SUCCEEDED | responsibility.decision_record | `LEAD_ROUTING_DISPOSITION@hash` | `SourceIntakeStopRequestedV1` | R1_PROJECTION | ACK_SOURCE_INTAKE_STOP_REQUEST | DIRECT | SOURCE_INTAKE_OWNER |
| ACK_SOURCE_INTAKE_STOP_REQUEST | ACK_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED | SUCCEEDED | responsibility.decision_record | `SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED@hash` | `SourceIntakeStopRequestAcknowledgedV1` | R1_PROJECTION | NONE | NONE | NONE |
| CONTACT_CONNECTED_VALID | CONTACT_LEAD | CONNECTED_VALID | SUCCEEDED | lead.lead_contact_result | `contactResult@hash` | `LeadContactResultRecordedV1` | R1_PROJECTION | NONE | OPPORTUNITY_BOUNDARY_V1 | NONE |
| CONTACT_NOT_CONNECTED_RETRY | CONTACT_LEAD | NOT_CONNECTED | SUCCEEDED | lead.lead_contact_result | `contactResult@hash; attemptNo<3` | `LeadContactResultRecordedV1` | R1_PROJECTION | CONTACT_LEAD | CONTACT_RETRY_V1 | SAME_ASSIGNMENT_OWNER |
| CONTACT_NOT_CONNECTED_EXHAUSTED | CONTACT_LEAD | NOT_CONNECTED | SUCCEEDED | lead.lead_contact_result | `contactResult@hash; attemptNo=3` | `LeadContactRetryExhaustedV1` | R1_PROJECTION | REVIEW_LEAD_VALIDITY | CONTACT_RETRY_V1 | ROUTING_SUPERVISOR |
| CONTACT_SUSPECT_INVALID | CONTACT_LEAD | SUSPECT_INVALID | SUCCEEDED | lead.lead_contact_result | `contactResult@hash` | `LeadContactResultRecordedV1` | R1_PROJECTION | REVIEW_LEAD_VALIDITY | DIRECT | ROUTING_SUPERVISOR |
| REVIEW_CONFIRM_INVALID | REVIEW_LEAD_VALIDITY | CONFIRM_INVALID | SUCCEEDED | responsibility.decision_record | `LEAD_VALIDITY_REVIEW@hash` | `LeadValidityReviewedV1` | R1_PROJECTION | NONE | NONE | NONE |
| REVIEW_CLOSE_UNREACHED | REVIEW_LEAD_VALIDITY | CLOSE_UNREACHED | SUCCEEDED | responsibility.decision_record | `LEAD_VALIDITY_REVIEW@hash` | `LeadValidityReviewedV1` | R1_PROJECTION | NONE | NONE | NONE |
| REVIEW_REOPEN_CONTACT | REVIEW_LEAD_VALIDITY | REOPEN_CONTACT | SUCCEEDED | responsibility.decision_record | `LEAD_VALIDITY_REVIEW@hash` | `LeadValidityReviewedV1` | R1_PROJECTION | CONTACT_LEAD | DIRECT | CURRENT_ASSIGNMENT_OWNER |

Receipt outcome 的封闭集合只有 `SUCCEEDED`、`NO_CHANGE`、`REJECTED`。Contact/Decision 的 `OutcomeCode` 是领域结果，不是 Receipt outcome。技术异常整体回滚，禁止创建 `FAILED` Receipt。

## Deterministic owner and successor rules

### Source policy registry

静态版本化`R1SourcePolicyRegistry[sourceAccountCode]`必须恰含：`assignmentMode`、非空有序`routingOrganizationRootCodes`、`routingSupervisorRootCode`、`sourceIntakeRootCode`和IANA `businessTimezone`。这些值随代码和合同版本发布，不建配置表；未知sourceAccountCode失败关闭。销售候选的policy priority就是候选覆盖的第一个`routingOrganizationRootCodes`索引，禁止实现者自造权重。

### 销售候选

候选集合同时满足：同 Tenant；覆盖Source Policy有序routing organization root之一；Appointment 在可信当前时刻为 ACTIVE；具备范围覆盖该root的 `SALES_CONTACT_OWNER` AuthorityGrant；没有 DENY。集合按冻结的 policy priority 升序、appointment start 升序、appointment UUID 升序选择第一项；空集合创建 P0-04，不返回伪 Assignment，不使用散列取模或随机选择。

### 主管解析

系统使用Source Policy的`routingSupervisorRootCode`，以当前可信时刻查询 ACTIVE Appointment 和范围覆盖该 root 的准确 AuthorityGrant：P0-04 使用 `LEAD_ROUTING_DECIDE`，有效性复核使用 `LEAD_VALIDITY_REVIEW`。结果必须恰好一个；零个或多个均返回 `SUPERVISOR_UNRESOLVED`，且 Fact、Task transition、Event、Outbox、Receipt、Audit 全部为零。不得沿组织 parent 猜主管，也不得按 UUID 任取一个。

### 来源接入负责人解析

`REQUEST_SOURCE_INTAKE_STOP`在写Decision前，使用Source Policy的`sourceIntakeRootCode`查询同Tenant、当前ACTIVE且具备范围覆盖该root之`SOURCE_INTAKE_REQUEST_ACK` AuthorityGrant、没有DENY的Appointment。结果必须恰好一个并成为`ACK_SOURCE_INTAKE_STOP_REQUEST` Owner；零个或多个均返回`SOURCE_INTAKE_OWNER_UNRESOLVED`，当前P0-04 Task、Decision、后继Task、Event、Outbox、Receipt、Audit全部新增0。不得回退到当前主管、组织parent或任意UUID第一项。

### 下一责任选择器

`R1_LEAD_NEXT_RESPONSIBILITY_V1` 在同一事务、同一 Lead lock 下重验：疑似重复则 `RESOLVE_LEAD_DUPLICATE`；缺少联系方式且 V850 槽为空则 `COMPLETE_LEAD_INGRESS`；已有明确人工 Owner 请求则 `ASSIGN_LEAD`；可自动分配则原子创建 Assignment 和 `CONTACT_LEAD`；零候选则 `RESOLVE_LEAD_ROUTING_GAP`。必须恰建一个后继或在已有 OPEN 同类型自然唯一键命中时返回 NO_CHANGE，禁止同时建立两张责任卡。

`RETRY_ASSIGNMENT_NOW` 只执行一次候选选择：命中时创建一条 Assignment 和一张 `CONTACT_LEAD`；仍为空时创建一张新的 `RESOLVE_LEAD_ROUTING_GAP`，不得递归自动重试。

`REQUEST_SOURCE_INTAKE_STOP` 只创建 `ACK_SOURCE_INTAKE_STOP_REQUEST`。确认事实只证明请求被准确 intake Owner 接收；不更新 SourceAccount state，不声称来源已停用。

## CONTACT_RETRY_V1

- 初次 `CONTACT_LEAD` 计 attempt 1，总次数最多 3。
- 时区固定为 Source Policy 的 IANA `businessTimezone`；R1 静态 `businessCalendar=CN_WEEKDAY_V1`，工作日为当地周一至周五，暂不排除法定节假日。DST 间隙向后移动到首个有效时刻，重叠取较早 offset。
- attempt 1 的 `NOT_CONNECTED`：后继恢复时间为该日历的下一工作日当地 10:00，原始 due 为恢复后 30 分钟。
- attempt 2 的 `NOT_CONNECTED`：后继恢复时间为该日历的下一工作日当地 15:00，原始 due 为恢复后 30 分钟。
- 每次优先切换到另一种已经受控捕获且可用的 channel；没有另一 channel 时保持当前 channel，不得发明联系方式。
- attempt 3 的 `NOT_CONNECTED`：不再创建联系重试，创建 `REVIEW_LEAD_VALIDITY`，reason=`CONTACT_RETRY_EXHAUSTED`。
- 重试 Task 必须先以 OPEN/revision 0 创建，再在同事务转 WAITING/revision 1 并追加一条 WaitReceipt。到期 internal command 只做 WAITING→OPEN CAS，不完成 Task、不改变 Owner 或 SLA。

## Transaction and replay invariants

- 锁顺序固定：`LEAD` → `TASK` → `COMMAND_SLOT`。没有 Task 的 capture 使用 source natural key → `COMMAND_SLOT`。
- 成功：completion Fact 1、原 Task `DONE/revision+1`、Receipt 1、DomainEvent 1、对应 Owner Outbox 1、Audit `SUCCEEDED` 1，全部同事务。
- 同一 UUID `Idempotency-Key` 和同规范化 payload：返回原 Receipt，所有持久 delta 为 0。
- 同 key 异 payload或异 Scope：`COMMAND_PAYLOAD_CONFLICT`；返回指向原终态 Receipt 的安全引用，Slot/Receipt/Fact/Task/Event/Outbox/Audit 全部新增 0。一个既有 slot 的唯一 Receipt 永不被第二张 REJECTED Receipt替换或追加。
- 业务拒绝：Fact/Task/Event/Outbox 为 0；若已占用 slot，则 `REJECTED` Receipt 和 Audit 各 1。技术异常、Audit 失败、锁超时和连接中断：Slot/Receipt/Fact/Task/Event/Outbox/Audit 全部 0。
- 所有读取和写入绑定 ActorContext Tenant；另一个 Tenant 对 Task、Fact、Receipt、Audit、Event、Outbox 的可见和可写 delta 均为 0。

## E2E deltas

| ScenarioID | BranchID | FactDelta | TaskDelta | SuccessorDelta | ReceiptEventOutboxAudit | IsolationRollback |
|---|---|---|---|---|---|---|
| E2E_P0_01_LINK | P0_01_LINK_EXISTING | `decision_record:+1` | `current:DONE,r+1` | `R1 selector:exactly1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; replay:all-0; technical-failure:all-0` |
| E2E_P0_01_SEPARATE | P0_01_KEEP_SEPARATE | `decision_record:+1` | `current:DONE,r+1` | `R1 selector:exactly1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; replay:all-0; technical-failure:all-0` |
| E2E_P0_02 | P0_02_COMPLETE | `lead rows:+0; ingress slot:0-to-1; lead revision:+1` | `current:DONE,r+1` | `R1 selector:exactly1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `stale:domain-0; other-tenant:0; technical-failure:all-0` |
| E2E_P0_03 | P0_03_ASSIGN | `assignment:+1; lead revision:+1` | `current:DONE,r+1` | `CONTACT_LEAD:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; duplicate-open-assignment:rejected; technical-failure:all-0` |
| E2E_P0_04_SCHEDULE | P0_04_SCHEDULE_ROUTING_REVIEW | `decision_record:+1; wait_receipt:+1` | `current:DONE,r+1` | `routing task:+1,WAITING,r1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_P0_04_RETRY_CANDIDATE | P0_04_RETRY_ASSIGNMENT_NOW | `decision_record:+1; assignment:+1; lead revision:+1` | `current:DONE,r+1` | `CONTACT_LEAD:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_P0_04_RETRY_EMPTY | P0_04_RETRY_ASSIGNMENT_NOW | `decision_record:+1; assignment:+0` | `current:DONE,r+1` | `routing task:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `no-recursion:true; other-tenant:0; technical-failure:all-0` |
| E2E_P0_04_STOP_REQUEST | P0_04_REQUEST_SOURCE_INTAKE_STOP | `decision_record:+1; source state:+0` | `current:DONE,r+1` | `ACK_SOURCE_INTAKE_STOP_REQUEST:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_STOP_ACK | ACK_SOURCE_INTAKE_STOP_REQUEST | `decision_record:+1; source state:+0` | `current:DONE,r+1` | `NONE` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_CONTACT_CONNECTED | CONTACT_CONNECTED_VALID | `contact_result:+1; opportunity:+1` | `current:DONE,r+1` | `R2 task:+0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_CONTACT_RETRY | CONTACT_NOT_CONNECTED_RETRY | `contact_result:+1; wait_receipt:+1` | `current:DONE,r+1` | `CONTACT_LEAD:+1,WAITING,r1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; due-before-resume:0; technical-failure:all-0` |
| E2E_CONTACT_EXHAUSTED | CONTACT_NOT_CONNECTED_EXHAUSTED | `contact_result:+1` | `current:DONE,r+1` | `REVIEW_LEAD_VALIDITY:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `retry-task:+0; other-tenant:0; technical-failure:all-0` |
| E2E_CONTACT_SUSPECT | CONTACT_SUSPECT_INVALID | `contact_result:+1` | `current:DONE,r+1` | `REVIEW_LEAD_VALIDITY:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_REVIEW_INVALID | REVIEW_CONFIRM_INVALID | `decision_record:+1` | `current:DONE,r+1` | `NONE` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_REVIEW_UNREACHED | REVIEW_CLOSE_UNREACHED | `decision_record:+1` | `current:DONE,r+1` | `NONE` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `other-tenant:0; technical-failure:all-0` |
| E2E_REVIEW_REOPEN | REVIEW_REOPEN_CONTACT | `decision_record:+1` | `current:DONE,r+1` | `CONTACT_LEAD:+1,OPEN,r0` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `old-task-reopen:0; other-tenant:0; technical-failure:all-0` |

每个 E2E 必须同时断言准确 Fact selector、原 Task ID/state/revision、后继 Task ID/state/revision/Owner、Receipt result selector、Event source Fact、Outbox owner、Audit result、同 key replay、异 payload rejection、Tenant 哨兵不可见以及技术失败全回滚。
