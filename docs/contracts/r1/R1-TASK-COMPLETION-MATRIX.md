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
| ACK_SOURCE_INTAKE_STOP_REQUEST | 确认来源停用请求已被准确 Owner 接收，不改变SourceAccount | `lead.lead@revision` | `SOURCE_INTAKE_OWNER + SOURCE_INTAKE_REQUEST_ACK` | ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST | `AcknowledgeSourceIntakeStopRequestV1@1` | responsibility.decision_record | `decisionType=SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED; causal decision exact selector in confirmed draft` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_1D_V1` |
| CONTACT_LEAD | 由当前唯一Assignment Owner记录一次真实首联结果 | `lead.lead@revision` | `ASSIGNMENT_OWNER + SALES_CONTACT_OWNER` | RECORD_CONTACT_RESULT | `RecordContactResultV1@1` | lead.lead_contact_result | `new result bound to taskId, leadId and assignmentId` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_CONTACT_30M_V1` |
| REVIEW_LEAD_VALIDITY | 主管解析疑似无效或重试耗尽Lead | `lead.lead@revision` | `ROUTING_SUPERVISOR + LEAD_VALIDITY_REVIEW` | REVIEW_LEAD_VALIDITY | `ReviewLeadValidityV1@1` | responsibility.decision_record | `decisionType=LEAD_VALIDITY_REVIEW; causal result exact selector in confirmed draft` | `tenant+taskId+Idempotency-Key` | `LEAD then TASK then COMMAND_SLOT` | `R1_BUSINESS_4H_V1` |

`CompletionFactType` 是完成原 Task 的唯一事实类型。ActionDraft、HTTP 200、Audit、Event、Outbox、Receipt 或 UI 点击不能替代该事实。

Task行的自然幂等槽是Tenant＋Task subject scope＋调用方UUID `Idempotency-Key`；物理映射服从现有`tenant_id, envelope_type, command_scope_digest, command_id`。`LockRoot`中的`COMMAND_SLOT`阶段先取得Tenant＋Command UUID事务级advisory lock，再查询或插入slot，从而拒绝同Tenant跨Scope复用并保持Receipt查询单义。capture与internal操作使用HTTP矩阵各自的SubjectBinding形成scope，不借用Task ID。

### Persisted subject and secondary bindings

每张R1 `responsibility.task_occurrence`只持久化一个准确Subject：`subject_type=lead.lead`、`subject_id=leadId`、`subject_revision=任务创建时适用的Lead revision`、`subject_hash=NULL`。表格或DTO中的Assignment、候选Lead/Party、因果Decision、触发ContactResult都不是第二个Task subject，也不得拼进`subject_type`、伪造复合ID或增加表。后继Task在导致其创建的全部同事务Lead CAS之后冻结新的适用Lead revision；没有Lead CAS时沿用当前准确revision。

| TaskType | SecondaryBinding | Deterministic creation proof | Submit-time revalidation | Command scope binding |
|---|---|---|---|---|
| RESOLVE_LEAD_DUPLICATE | `candidateLead@revision + candidateParty@revision` | 只采用下文重复候选算法在`candidate.created_at <= task.created_at`集合中的第一项；候选必须已解析到该ACTIVE Party | 重算同一有界候选集合；候选Lead仍为该revision并仍以`parsed_party_id`指向同revision ACTIVE Party；确认草稿中的四个selector必须全等 | `candidateLeadId,candidateLeadRevision,partyId,partyRevision` |
| COMPLETE_LEAD_INGRESS | NONE | Lead原始phone/email及整个V850槽均为空 | 锁内重验Task subject revision、原始字段仍空且V850槽仍全空 | NONE |
| ASSIGN_LEAD | `selectedOwnerAppointmentId` | MANUAL模式由已授权草稿选定；Appointment必须在Source Policy root范围内满足销售候选谓词 | 重验Lead revision、唯一OPEN Assignment仍不存在、Owner候选资格及草稿值 | `selectedOwnerAppointmentId` |
| RESOLVE_LEAD_ROUTING_GAP | NONE | 自动选择零销售候选且准确主管唯一 | 锁内重验Lead revision、分支所需主管/来源Owner与当前策略 | NONE |
| ACK_SOURCE_INTAKE_STOP_REQUEST | `causalDecision@hash` | 取`decision_code=REQUEST_SOURCE_INTAKE_STOP`、其原Task completion_fact准确指回该Decision、原Task subject等于本Task Lead selector且`decided_at <= task.created_at`的最大`(decided_at, decision_record_id)`；它必须就是创建本Task的同事务Decision | 草稿提供`causalDecisionId,causalDecisionHash`；重验该不可变Decision、原Task、completion_fact、Lead selector和最大项全链 | `causalDecisionId,causalDecisionHash` |
| CONTACT_LEAD | `leadAssignment@revision` | 取Lead `current_assignment_id`指向、属于同Lead、状态OPEN且Owner等于Task Owner的唯一Assignment | 草稿提供`leadAssignmentId,leadAssignmentRevision`；重验Lead当前指针、Assignment所属Lead/OPEN/revision/Owner及Task Owner全部相等 | `leadAssignmentId,leadAssignmentRevision` |
| REVIEW_LEAD_VALIDITY | `triggeringContactResult@hash` | 取同Lead、其`contact_task_id` Task completion_fact准确指回该结果、结果为`SUSPECT_INVALID`或`NOT_CONNECTED且contact_no=3`、且`resulted_at <= task.created_at`的最大`(resulted_at, lead_contact_result_id)`；它必须就是创建本Task的同事务结果 | 草稿提供`triggeringContactResultId,triggeringContactResultHash`；重验不可变结果、来源CONTACT_LEAD Task、completion_fact、Lead与允许触发条件全链 | `triggeringContactResultId,triggeringContactResultHash` |

以上“最大”比较先按`timestamptz(6)`、再按UUID的RFC 4122网络字节无符号字典序；不得按数据库未指定collation或显示文本排序。所有SecondaryBinding都进入对应Task唯一ActionDraft的candidate payload；主命令成功时该Draft以同一事务`DRAFT→CONFIRMED`，使`confirmed_payload_digest=candidate_payload_digest`。它们仍须由Fact Owner重验，不能把Draft当业务真相。任何selector缺失、不匹配或已失效都拒绝，不能降级到只按ID、最新任意行或UUID第一项。

`R1_COMMAND_SCOPE_V1`固定为RFC 8785 JCS对象的UTF-8字节之SHA-256原始32字节：`{"profile":"R1_COMMAND_SCOPE_V1","tenantId":...,"commandType":...,"taskId":...,"lead":{"type":"lead.lead","id":...,"revision":...},"bindings":[...]}`。`bindings`按上表固定名称字节升序排列；revision用JSON整数，hash用无padding base64url，NONE时为空数组。Tenant、commandType、Task、Lead selector或任一secondary selector不同即scope不同；调用方不得提交scope或digest。

### Non-completion command Receipt results

| Command | SUCCEEDED exact `result_fact` | NO_CHANGE exact `result_fact` | Scope |
|---|---|---|---|
| captureLead | `lead.lead@同事务全部Lead写入后的最终revision` | 来源自然唯一键已存在时的`lead.lead@锁内当前revision` | `R1_CAPTURE_SCOPE_V1(tenantId,sourceAccountCode,sourceRecordKeyDigest)` |
| saveActionDraft | `responsibility.action_draft@本次insert/CAS后的revision` | 规范candidate payload未变化时的`responsibility.action_draft@锁内当前revision` | `R1_DRAFT_SCOPE_V1(tenantId,taskId,actionCode)` |
| reopenDueContactTasks | `responsibility.task_occurrence@WAITING→OPEN CAS后的revision` | 同一准确WaitReceipt已经导致该Task为OPEN时的`responsibility.task_occurrence@锁内当前revision` | `R1_REOPEN_SCOPE_V1(tenantId,taskId,waitReceiptId,waitReceiptHash)` |

`reopenDueContactTasks`保留现有operationId和`POST /internal/v1/tasks/commands/reopen-due-contact-tasks`，但复数只表示worker会逐项调用；一次请求、一个CommandId和一张Receipt只处理一个准确Task，绝不是批命令。请求必须携带`taskId`、`expectedTaskRevision`、`waitReceiptId`、32字节`waitReceiptHash`和`dueCutoff`。锁内必须证明Task为`CONTACT_LEAD/WAITING/expectedTaskRevision`，该WaitReceipt是本Task最新一条且`task_revision=expectedTaskRevision`，其`resume_due_at`非空并满足`resume_due_at <= dueCutoff <= trustedNow`；随后只做一次`WAITING→OPEN, revision+1` CAS，不改Owner、subject或原SLA。若竞争者已用同一WaitReceipt完成该迁移且Task恰为OPEN/revision=`expectedTaskRevision+1`，返回引用该Task当前revision的NO_CHANGE；其他不满足条件按HTTP合同拒绝。scheduler没有due Task时不调用本命令，因此不存在无准确result Fact的“空批次成功”。

七个公共Task主命令都必须确认本Task既有DRAFT ActionDraft：请求携带`draftId`、`expectedDraftRevision`、`draftDigest`及与candidate payload完全相同的命令字段。缺少draft selector属于输入校验失败；不可见或不存在的Draft按安全NOT_FOUND；摘要不等于锁内`candidate_payload_digest`则`DRAFT_DIGEST_MISMATCH`。成功时Draft确认、完成Fact、Task DONE、Event/Outbox、Receipt和Audit同事务提交；任何拒绝或技术失败不得留下CONFIRMED Draft。Task主命令的SUCCEEDED/NO_CHANGE Receipt始终引用该行`CompletionFactType`的一个准确Fact，绝不引用Draft或Task。

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

`CONTACT_CONNECTED_VALID`的确认草稿必须且只在该分支携带`legalNeed: SafeText2000`；服务端以其规范化UTF-8原文生成`opportunity.opportunity.legal_need_ciphertext`和SHA-256 `legal_need_digest`。`NOT_CONNECTED`与`SUSPECT_INVALID`禁止携带该字段，Lead捕获时的`legalNeedSummary`不得被静默当作本次已确认法律需求。

`P0_01_LINK_EXISTING`在同一Lead锁和事务中完成两类写入，二者缺一即整体回滚：先按冻结候选规则重验候选Lead准确revision及其`parsed_party_id`所指向Party仍为准确revision的ACTIVE最终Party；再对当前Lead执行一次CAS，仅把`parsed_party_id=candidateLead.parsed_party_id`、`party_resolution_code=RESOLVED`、`disposition_code=LINK_EXISTING_PARTY`和`revision=old+1`写入数据库合同允许更新列。它不更新候选Lead或Party，不合并/删除Lead或Party，也不复制Party标识。`DecisionRecord(LEAD_DUPLICATE_RESOLUTION, LINK_EXISTING_PARTY)`的`content_digest`覆盖当前Lead旧selector、候选Lead/Party selector、上述新值和新Lead revision；它仍是Task唯一完成Fact及Receipt/Event准确结果Fact。后继选择器只消费CAS后的Lead revision。

`P0_01_KEEP_SEPARATE`也必须在同一Lead锁和事务中重验创建Task时冻结的候选Lead/Party准确selector；随后只对当前Lead执行一次CAS，把`disposition_code=KEEP_SEPARATE`、`revision=old+1`写入允许更新列。它不得修改当前Lead的`parsed_party_id`、`party_resolution_code`、`current_assignment_id`、捕获字段或V850 ingress槽，也不得更新候选Lead/Party、关联Party、合并或删除任何记录。`DecisionRecord(LEAD_DUPLICATE_RESOLUTION, KEEP_SEPARATE)`的`content_digest`覆盖当前Lead旧selector、候选Lead/Party selector、`KEEP_SEPARATE`和新Lead revision；它仍是唯一完成Fact。后继选择器只消费CAS后的Lead revision，并因该Lead已不再是`CAPTURED`而跳过重复候选规则。

## Deterministic owner and successor rules

### Duplicate candidate registry

`R1_DUPLICATE_CANDIDATE_V1`只对当前`disposition_code=CAPTURED`的Lead执行受保护精确匹配，不做姓名模糊匹配、全文搜索或`captured_content_digest`相等推断。对该Lead `L`，候选`C`必须同时满足：同Tenant；`C.lead_id <> L.lead_id`；`C.created_at <= duplicate Task.created_at`；`C.party_resolution_code=RESOLVED`；`C.parsed_party_id`指向同Tenant且在可信时刻仍为`ACTIVE`的Party；并且至少一个非空标准化联系方式HMAC相等。phone匹配比较`L.captured_phone_hmac/ingress_completion_phone_hmac`中非空值与`C`对应两列中非空值；email同理，不跨phone/email用途比较。

候选按`matchRank`升序、`C.captured_at`升序、`C.lead_id`的RFC 4122网络字节无符号字典序升序取第一项；phone和email都匹配的`matchRank=0`，只phone为1，只email为2。没有候选就不创建`RESOLVE_LEAD_DUPLICATE`；禁止按姓名、展示文本、数据库默认collation、随机数或未冻结权重补候选。执行`LINK_EXISTING_PARTY`时必须重验原有界集合第一项、候选Lead revision及其同一ACTIVE Party revision；有变化返回stale拒绝，不能悄然改连新候选。

### Source policy registry

静态版本化`R1SourcePolicyRegistryV1[sourceAccountCode]`的值必须恰含：`assignmentMode`、非空有序且不重复的`routingOrganizationRootCodes`、`routingSupervisorRootCode`、`sourceIntakeRootCode`和IANA `businessTimezone`。`assignmentMode`封闭为`MANUAL|AUTOMATIC`；四类code均为区分大小写的ASCII注册代码，`businessTimezone`必须是制品所带IANA tzdb的Zone ID，禁止固定offset别名。每个organization code在当前Tenant必须解析为恰好一个有效`identity.organization_unit`；零个或多个都失败关闭。这些值随代码和合同版本发布，不建配置表；未知`sourceAccountCode`在占slot前按输入/静态路由错误拒绝。销售候选的policy priority就是候选覆盖的第一个`routingOrganizationRootCodes`索引；MANUAL只创建`ASSIGN_LEAD`，AUTOMATIC才执行自动候选选择，禁止实现者自造权重或fallback模式。

### 销售候选

候选集合同时满足：同 Tenant；覆盖Source Policy有序routing organization root之一；Appointment 在可信当前时刻为 ACTIVE；具备范围覆盖该root的 `SALES_CONTACT_OWNER` AuthorityGrant；没有 DENY。集合按冻结的 policy priority 升序、appointment start 升序、appointment UUID 升序选择第一项；空集合创建 P0-04，不返回伪 Assignment，不使用散列取模或随机选择。

### 主管解析

系统使用Source Policy的`routingSupervisorRootCode`，以当前可信时刻查询 ACTIVE Appointment 和范围覆盖该 root 的准确 AuthorityGrant：P0-04 使用 `LEAD_ROUTING_DECIDE`，有效性复核使用 `LEAD_VALIDITY_REVIEW`。结果必须恰好一个；零个或多个均返回 `SUPERVISOR_UNRESOLVED`，领域Fact、Task transition、Event和Outbox全部新增0；新Command已进入下文slot终局阶段时只提交Slot、REJECTED Receipt和REJECTED Audit各1。不得沿组织parent猜主管，也不得按UUID任取一个。

### 来源接入负责人解析

`REQUEST_SOURCE_INTAKE_STOP`在写Decision前，使用Source Policy的`sourceIntakeRootCode`查询同Tenant、当前ACTIVE且具备范围覆盖该root之`SOURCE_INTAKE_REQUEST_ACK` AuthorityGrant、没有DENY的Appointment。结果必须恰好一个并成为`ACK_SOURCE_INTAKE_STOP_REQUEST` Owner；零个或多个均返回`SOURCE_INTAKE_OWNER_UNRESOLVED`，当前P0-04 Task、Decision、后继Task、Event和Outbox全部新增0；新Command已进入slot终局阶段时只提交Slot、REJECTED Receipt和REJECTED Audit各1。不得回退到当前主管、组织parent或任意UUID第一项。

### 下一责任选择器

`R1_LEAD_NEXT_RESPONSIBILITY_V1` 在同一事务、同一 Lead lock 下重验：仅当当前`disposition_code=CAPTURED`且疑似重复时才创建`RESOLVE_LEAD_DUPLICATE`；缺少联系方式且 V850 槽为空则 `COMPLETE_LEAD_INGRESS`；已有明确人工 Owner 请求则 `ASSIGN_LEAD`；可自动分配则原子创建 Assignment 和 `CONTACT_LEAD`；零候选则 `RESOLVE_LEAD_ROUTING_GAP`。必须恰建一个后继或在已有 OPEN 同类型自然唯一键命中时返回 NO_CHANGE，禁止同时建立两张责任卡。LINK与KEEP_SEPARATE都从CAS后的Lead revision进入本选择器；二者均不会重复创建刚完成的duplicate-resolution Task。

`RETRY_ASSIGNMENT_NOW` 只执行一次候选选择：命中时创建一条 Assignment 和一张 `CONTACT_LEAD`；仍为空时创建一张新的 `RESOLVE_LEAD_ROUTING_GAP`，不得递归自动重试。

`REQUEST_SOURCE_INTAKE_STOP` 只创建 `ACK_SOURCE_INTAKE_STOP_REQUEST`。确认事实只证明请求被准确 intake Owner 接收；不更新 SourceAccount state，不声称来源已停用。

### Business time and SLA registry

`R1_BUSINESS_WINDOW_V1`使用Source Policy的`businessTimezone`及静态`CN_WEEKDAY_V1`：当地周一至周五为工作日，暂不排除法定节假日；每日唯一窗口为`09:00:00.000000`（含）至`18:00:00.000000`（不含）。从可信`created_at`开始逐秒累计窗口内时间；起点在窗口外时先移动到不早于起点的下一窗口起点，到18:00尚未耗尽的余量从下一窗口09:00继续。结果精确到微秒，不按自然日、UTC日或24小时换算；当地时间落入DST间隙时向后移到首个有效时刻，重叠时取较早offset。

| SLA code | `original_sla_seconds` | `original_sla_due_at` |
|---|---:|---|
| R1_BUSINESS_4H_V1 | 14400 | 从Task `created_at`累计14400个`R1_BUSINESS_WINDOW_V1`有效秒 |
| R1_BUSINESS_1D_V1 | 32400 | 一个9小时工作窗口；从Task `created_at`累计32400个有效秒 |
| R1_CONTACT_30M_V1 | 1800 | 从Task `created_at`累计1800个有效秒；CONTACT_RETRY_V1明确给定恢复时间时从`resume_due_at`累计 |

`NEXT_BUSINESS_WINDOW`的`resume_due_at`是严格晚于可信决定时间的最早当地工作窗口起点：决定时间早于当日09:00时取当日09:00，否则取下一工作日09:00。后继Task仍先OPEN/revision 0创建，再CAS至WAITING/revision 1并追加WaitReceipt；其原SLA字段按上表一次冻结且以后不改。

### Canonicalization, digest and HMAC registry

- 所有UUID先校验RFC 4122文本，再转成小写连字符形式；时间统一为UTC、六位小数和`Z`；写入`varchar(64)`的code只接受`^[A-Z][A-Z0-9_]{0,63}$`且不大小写折叠。HTTP中的32字节digest/hash用无padding base64url，数据库保存解码后的原始32字节。
- `R1_JSON_JCS_SHA256_V1`是RFC 8785 JCS结果UTF-8字节的SHA-256。命令`payload_digest`覆盖请求body全部业务字段，但排除HTTP `Idempotency-Key`、认证、条件header及服务端ActorContext；ActionDraft `candidate_payload_digest`只覆盖对应具名主命令的candidate values，不覆盖`draftId`、`expectedDraftRevision`或回传的`draftDigest`。
- phone采用`R1_PHONE_E164_V1`：输入必须已是`+`及1至15位十进制数字，首位数字1至9；验证后原样作为规范值。email采用`R1_EMAIL_V1`：先Unicode NFC并删除两端Unicode White_Space；必须恰有一个`@`，local-part以Unicode默认无区域小写，domain按IDNA2008转ASCII并小写；空值与空串均视为缺失。其他受保护文本采用`R1_TEXT_NFC_V1`：CRLF/CR转LF、Unicode NFC、删除两端Unicode White_Space、拒绝除LF/TAB外的C0/C1控制符，不作大小写折叠。
- 三种盲索引用途封闭为`LEAD_PHONE_EXACT`、`LEAD_EMAIL_EXACT`、`SOURCE_RECORD_KEY`。KMS/Secret Manager按Tenant＋用途提供独立`R1_HMAC_SHA256_V1`密钥；HMAC输入为UTF-8 JCS对象`{"profile":"R1_HMAC_SHA256_V1","purpose":...,"sourceAccountCode":...或null,"value":...}`。phone/email的`sourceAccountCode=null`，使捕获值与V850补全值可安全精确比较；source record key按请求中区分大小写、不trim的原始Unicode标量串并带准确sourceAccountCode计算。R1不轮换或双写key version，也不保存明文、密钥或可逆输入。
- `captured_content_digest`使用`R1_JSON_JCS_SHA256_V1`覆盖`sourceChannelCode,sourceAccountCode,sourceRecordKeyDigest,capturedAt,capturedName,phone,email,cityCode,serviceCategoryCode,jurisdictionCode,urgencyCode,legalNeedSummary`，其中受保护值先按上述profile规范化、digest以base64url放入JCS；不包含ciphertext、Lead ID、解析/处置/currentAssignment、revision或创建时间。`ingress_completion_digest`同法只覆盖`phone,email,sourceCode,sourceSummary,completedByAppointmentId,completedAt`。
- Decision exact hash使用其持久化`content_digest`。`lead_contact_result`和`responsibility.wait_receipt`的exact hash使用`R1_JSON_JCS_SHA256_V1`覆盖该不可变行除`tenant_id`以外的全部持久字段，并把`tenantId`作为JCS顶层必填字段；NULL显式为JSON null。实现不得以序列化对象、数据库行文本或显示DTO临时计算替代。

### R1 code allowlists

Task registry的`TaskType`、`PrimaryCommand`、`PayloadSchema`、`CompletionFactType`和`SLA`列，Completion branches的`OutcomeCode`、`EventType`和`QueueOwner`列，以及Owner解析段落出现的authority slot，分别就是R1对应代码域的封闭允许列表。除此之外R1只允许下列代码；未列值失败关闭，不能作为自由字符串落库：

| Code domain | Allowed values |
|---|---|
| Source assignment mode | `MANUAL`, `AUTOMATIC` |
| Task business purpose | 恰等于该行`TaskType` |
| Decision contract (`version=1`) | `LEAD_DUPLICATE_RESOLUTION`, `LEAD_ROUTING_DISPOSITION`, `SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED`, `LEAD_VALIDITY_REVIEW` |
| Lead disposition used by R1 | `CAPTURED`, `LINK_EXISTING_PARTY`, `KEEP_SEPARATE` |
| Lead assignment reason | `MANUAL_SELECTION`, `SOURCE_POLICY_AUTOMATIC`, `ROUTING_RETRY` |
| Contact channel | `PHONE`, `EMAIL` |
| Ingress completion source | `OWNER_CONFIRMED`, `CUSTOMER_PROVIDED` |
| Wait contract (`version=1`) | `R1_ROUTING_REVIEW_WAIT_V1`, `CONTACT_RETRY_V1` |
| Wait reason | `ROUTING_REVIEW_WINDOW`, `CONTACT_RETRY` |
| Business calendar/window | `CN_WEEKDAY_V1`, `R1_BUSINESS_WINDOW_V1` |
| Receipt outcome | `SUCCEEDED`, `NO_CHANGE`, `REJECTED` |
| Review reason | `SUSPECT_INVALID`, `CONTACT_RETRY_EXHAUSTED` |

## Candidate payload registry

| PrimaryCommand | ExactCandidateFields |
|---|---|
| RESOLVE_DUPLICATE_LEAD | decisionCode,candidateLeadId,candidateLeadRevision,partyId,partyRevision,rationaleSummary |
| COMPLETE_LEAD_INGRESS | phone?,email?,sourceCode,sourceSummary |
| ASSIGN_LEAD | ownerAppointmentId |
| RECORD_ROUTING_DISPOSITION | decisionCode,rationaleSummary |
| ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST | causalDecisionId,causalDecisionHash,rationaleSummary |
| RECORD_CONTACT_RESULT | leadAssignmentId,leadAssignmentRevision,contactChannelCode,resultCode,resultSummary?,legalNeed?,evidenceSubmissionId? |
| REVIEW_LEAD_VALIDITY | triggeringContactResultId,triggeringContactResultHash,decisionCode,rationaleSummary |

`COMPLETE_LEAD_INGRESS`的phone/email至少一个。`RECORD_CONTACT_RESULT`的`legalNeed`在`CONNECTED_VALID`时必填，在其他结果时禁止。所有主命令请求另带固定Draft确认三元组，不属于candidate payload。

## Candidate payload condition registry

| PrimaryCommand | ExactConditionalValidation |
|---|---|
| COMPLETE_LEAD_INGRESS | at-least-one-of-phone-email |
| RECORD_CONTACT_RESULT | legalNeed-required-when-CONNECTED_VALID-and-forbidden-otherwise |

## Duplicate resolution transition registry

| BranchID | RequiredCurrentDisposition | CandidateSelectors | CurrentLeadCAS | ForbiddenCurrentLeadChanges | CandidateLeadPartyMutation | DecisionDigest | SuccessorSelector |
|---|---|---|---|---|---|---|---|
| P0_01_LINK_EXISTING | CAPTURED | candidateLead@revision+party@revision:revalidate | parsed_party_id=candidate.parsed_party_id;party_resolution_code=RESOLVED;disposition_code=LINK_EXISTING_PARTY;revision=old+1 | current_assignment_id,capture_fields,ingress_slot | NONE | old-current-lead-selector+candidate-lead-party-selectors+new-values+new-revision | post-CAS-lead-revision;duplicate-only-when-CAPTURED |
| P0_01_KEEP_SEPARATE | CAPTURED | candidateLead@revision+party@revision:revalidate | disposition_code=KEEP_SEPARATE;revision=old+1 | parsed_party_id,party_resolution_code,current_assignment_id,capture_fields,ingress_slot | NONE | old-current-lead-selector+candidate-lead-party-selectors+KEEP_SEPARATE+new-revision | post-CAS-lead-revision;duplicate-only-when-CAPTURED |

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
- **Pre-slot gate：**静态路由、请求JSON/字段Schema、必填/格式、`Idempotency-Key`存在和UUID格式、认证、初始四轴授权与Appointment、Tenant安全可见性/NOT_FOUND、必需条件header、Draft存在性以及rate limit都在持有业务锁和尝试占slot之前完成。这里的拒绝不创建Slot或CommandReceipt；需要记录的安全审计不是该Command的终局Receipt。调用方修正请求或重新认证后可复用原key，但不得把pre-slot错误响应伪装为可查询Receipt。
- **Slot acquisition：**业务根锁内已能由服务端构造准确scope与payload digest后，先按Tenant＋Command UUID取得事务级advisory lock，并在Tenant内查找任意既有slot。既有slot同envelope/scope/payload只返回其唯一终态Receipt；异任一项返回`COMMAND_PAYLOAD_CONFLICT`及原Receipt安全引用，新增写入全为0。不存在时插入唯一Slot，然后进入submit-time revalidation；Slot与终态Receipt必须在同一短事务提交，故不存在已提交的孤立Slot。
- **Post-slot terminal gate：**Task/Draft/Subject CAS、当前授权复验、Task状态、重复/Assignment/causal Fact、Ingress槽、主管/来源Owner和其他领域前置条件在新Slot之后判定。失败只提交该Slot、无result Fact的REJECTED Receipt和REJECTED Audit各1；同key同scope同payload以后永久重放该拒绝，条件修复后必须使用新key。`COMMAND_PAYLOAD_CONFLICT`不为既有slot追加第二Receipt或Audit。
- 成功：completion Fact 1、原 Task `DONE/revision+1`、Receipt 1、DomainEvent 1、对应 Owner Outbox 1、Audit `SUCCEEDED` 1，全部同事务。
- 同一 UUID `Idempotency-Key` 和同规范化 payload：返回原 Receipt，所有持久 delta 为 0。
- 同 key 异 payload或异 Scope：`COMMAND_PAYLOAD_CONFLICT`；返回指向原终态 Receipt 的安全引用，Slot/Receipt/Fact/Task/Event/Outbox/Audit 全部新增 0。一个既有 slot 的唯一 Receipt 永不被第二张 REJECTED Receipt替换或追加。
- 业务拒绝：Fact/Task/Event/Outbox 为0；按上述阶段要么pre-slot命令写入全0，要么post-slot的Slot/`REJECTED` Receipt/Audit各1，禁止“已占slot但允许同key重试执行”的中间语义。技术异常、Audit失败、锁超时和连接中断使整个当前事务回滚，Slot/Receipt/Fact/Task/Event/Outbox/Audit全部0。
- 所有读取和写入绑定 ActorContext Tenant；另一个 Tenant 对 Task、Fact、Receipt、Audit、Event、Outbox 的可见和可写 delta 均为 0。

## E2E deltas

| ScenarioID | BranchID | FactDelta | TaskDelta | SuccessorDelta | ReceiptEventOutboxAudit | IsolationRollback |
|---|---|---|---|---|---|---|
| E2E_P0_01_LINK | P0_01_LINK_EXISTING | `decision_record:+1; lead rows:+0; parsed_party_id:candidate.parsed_party_id; party_resolution_code:RESOLVED; disposition_code:LINK_EXISTING_PARTY; lead revision:+1` | `current:DONE,r+1` | `R1 selector on post-CAS Lead revision:exactly1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `candidate Lead/Party mutation:0; other-tenant:0; replay:all-0; technical-failure:all-0` |
| E2E_P0_01_SEPARATE | P0_01_KEEP_SEPARATE | `decision_record:+1; lead rows:+0; disposition_code:KEEP_SEPARATE; lead revision:+1` | `current:DONE,r+1` | `R1 selector on post-CAS Lead revision:exactly1` | `receipt:+1,event:+1,outbox:+1,audit:+1` | `candidate Lead/Party mutation:0; other-tenant:0; replay:all-0; technical-failure:all-0` |
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
