# R1 Command Policy and Event Contract

Contract ID: R1-COMMAND-POLICY-EVENT-V1

Status: FROZEN

Semantic baseline: MVP-2026-09-05.2

Shared payload Schema: contracts/events/r1-domain-notification-v1.schema.json

确认日期：2026-09-05。Owner：Engineering。本文是 R1 命令专属授权、事件描述和成功分支通知集合的唯一静态合同；[ADR-0007](../../adr/ADR-0007-r1-command-policy-event-closure.md)记录其基线承接，[批准设计](../../superpowers/specs/2026-09-05-r1-contract-closure-design.md)提供解释背景。调用方不得提交或选择 policy、authority code、scope organization、授权路径、Grant ID、事件类型、source selector 或 QueueOwner。

## Command policy registry

| CommandType | Envelope | PrincipalKind | AuthorityPath | AuthoritySlot | AuthorityCode | ScopeSelector | ObjectDeny | TaskType | WaitProfile |
|---|---|---|---|---|---|---|---|---|---|
| CAPTURE_LEAD | INTERNAL_ADMIN | HUMAN | DIRECT,DELEGATED | SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |
| SAVE_ACTION_DRAFT | INTERNAL_TASK | HUMAN | DIRECT,DELEGATED | taskTypeRegistry | taskTypeRegistry | taskOwnerOrganization | task-and-lead:taskTypeAuthority-DENY | persistedTaskType | NONE |
| REOPEN_DUE_CONTACT_TASKS | SERVICE_ACTOR | SERVICE | SYSTEM | SYSTEM_RECOVERY | CONTACT_TASK_RECOVER | taskOwnerOrganization | task-and-lead:CONTACT_TASK_RECOVER-DENY | CONTACT_LEAD | CONTACT_RETRY_V1 |
| REOPEN_DUE_ROUTING_REVIEW_TASKS | SERVICE_ACTOR | SERVICE | SYSTEM | SYSTEM_RECOVERY | ROUTING_REVIEW_TASK_RECOVER | taskOwnerOrganization | task-and-lead:ROUTING_REVIEW_TASK_RECOVER-DENY | RESOLVE_LEAD_ROUTING_GAP | R1_ROUTING_REVIEW_WAIT_V1 |

每条策略都使用服务端确定的 Tenant、Principal、Appointment、准确授权事实、组织范围及有效期；对象 DENY 优先。OBJECT-only ALLOW 不替代直接 Grant 或合法一跳委托。初始授权、工作前复验和最终复验使用同一连接；最终复验持有 ADR-0006 的 Tenant identity shared lock 至事务结束，以新鲜 `clock_timestamp()`重读组织、Grant、委托及 DENY。重放和冲突重验当前访问权限，但不重新执行 Task CAS、Draft 可编辑性或 recovery 到期 eligibility。

CAPTURE_LEAD 只接受 HUMAN 的 DIRECT 或合法一跳 DELEGATED；委托引用相同 LEAD_CAPTURE 原始 Grant。`R1SourcePolicyRegistryV1[sourceAccountCode].sourceIntakeRootCode`在当前 Tenant 必须解析为恰好一个 ACTIVE organization_unit，Grant scope 覆盖该 root。创建 Lead 前以该 organization_unit@revision 为授权和审计锚点；最终复验前 revision 变化失败关闭。来源自然键已存在或返回既有 Receipt 时，额外检查准确既有 Lead 的 LEAD_CAPTURE DENY 和 Tenant 安全可见性。SERVICE、SYSTEM、CUSTOMER_GRANT、请求组织或任意 fallback 均不允许。

SAVE_ACTION_DRAFT 先从真实 TaskType 选择下表权限；actionCode 必须等于 Task PrimaryCommand。DIRECT Appointment 或 DELEGATED onBehalf Appointment 必须等于 Task Owner。scope 是真实 Task Owner Appointment 的组织；准确 Task 和 Lead 均检查适用于所选权限的 DENY。只可保存准确 OPEN Task 的唯一 DRAFT；同时复验 Task/action/Lead/Draft/Schema/revision。保存仅改变候选值，不确认 Draft、不完成 Task。

两类 recovery 只接受 mTLS 映射的 SERVICE Principal、有效服务 Appointment 和 SYSTEM 直接 Grant；禁止 HUMAN、onBehalf、委托和对象 ALLOW 替代专属 Grant。scope 取真实 Task Owner Appointment 的组织，并验证 Owner Appointment、Owner Principal 和组织仍有效。已有 key 先做当前授权下的重放/冲突；仅新 key 才检查准确 Task、最新 WaitReceipt selector/profile/revision 及 `resume_due_at <= dueCutoff <= trustedNow`。成功仅做 WAITING→OPEN 一次 CAS，不改 Owner、subject、SLA 或 WaitReceipt。身份失效对两个 internal operation 均映射为既有 NOT_AUTHORIZED，不扩散 APPOINTMENT_INACTIVE。

## Draft authority registry

| TaskType | AuthoritySlot | AuthorityCode |
|---|---|---|
| RESOLVE_LEAD_DUPLICATE | SOURCE_INTAKE_OWNER | LEAD_INGRESS_RESOLVE |
| COMPLETE_LEAD_INGRESS | SOURCE_INTAKE_OWNER | LEAD_INGRESS_COMPLETE |
| ASSIGN_LEAD | ROUTING_SUPERVISOR | LEAD_ASSIGN |
| RESOLVE_LEAD_ROUTING_GAP | ROUTING_SUPERVISOR | LEAD_ROUTING_DECIDE |
| ACK_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_OWNER | SOURCE_INTAKE_REQUEST_ACK |
| CONTACT_LEAD | ASSIGNMENT_OWNER | SALES_CONTACT_OWNER |
| REVIEW_LEAD_VALIDITY | ROUTING_SUPERVISOR | LEAD_VALIDITY_REVIEW |

## Event descriptor registry

每个事件是准确事实变化的不可变通知，不是事实副本、执行许可或事件溯源日志。事件类型、SchemaVersion、source type、revision/hash selector、QueueOwner 和用途均由下表静态确定。公共 payload 使用本文头部的共享 Schema，唯一合法值为 `{}`；command/correlation/causation/可信发生时间和 source selector 使用现有类型化列，不复制进 payload。

| EventType | SchemaVersion | SourceType | SourceSelector | QueueOwner | Purpose |
|---|---:|---|---|---|---|
| LeadCapturedV1 | 1 | lead.lead | revision:transaction-final | R1_PROJECTION | Refresh intake and current responsibility projection |
| ActionDraftSavedV1 | 1 | responsibility.action_draft | revision:post-write | R1_PROJECTION | Refresh authorized draft presentation without completing Task |
| ContactTaskReopenedV1 | 1 | responsibility.task_occurrence | revision:post-CAS | R1_PROJECTION | Refresh actionable contact card |
| RoutingReviewTaskReopenedV1 | 1 | responsibility.task_occurrence | revision:post-CAS | R1_PROJECTION | Refresh actionable routing card |
| LeadDuplicateResolutionRecordedV1 | 1 | responsibility.decision_record | hash:content | R1_PROJECTION | Refresh duplicate resolution projection |
| LeadIngressCompletedV1 | 1 | lead.lead | revision:post-CAS | R1_PROJECTION | Refresh completed ingress projection |
| LeadAssignedV1 | 1 | lead.lead_assignment | revision:0 | R1_PROJECTION | Refresh assignment projection |
| LeadRoutingDispositionRecordedV1 | 1 | responsibility.decision_record | hash:content | R1_PROJECTION | Refresh routing disposition projection |
| SourceIntakeStopRequestedV1 | 1 | responsibility.decision_record | hash:content | R1_PROJECTION | Refresh source intake stop request projection |
| SourceIntakeStopRequestAcknowledgedV1 | 1 | responsibility.decision_record | hash:content | R1_PROJECTION | Refresh source intake acknowledgement projection |
| LeadContactResultRecordedV1 | 1 | lead.lead_contact_result | hash:immutable-row | R1_PROJECTION | Refresh contact result projection |
| LeadContactRetryExhaustedV1 | 1 | lead.lead_contact_result | hash:immutable-row | R1_PROJECTION | Refresh exhausted contact projection |
| LeadValidityReviewedV1 | 1 | responsibility.decision_record | hash:content | R1_PROJECTION | Refresh lead validity projection |
| OpportunityOpened | 1 | opportunity.opportunity | revision:0 | R1_PROJECTION | R1 boundary projection; retained for delayed R2 activation |

## Success branch event registry

EventTypes 是顺序无关但成员精确的集合；每一分支必须无缺失、无多余、无重复。EventCount 等于该集合基数；OutboxCount 等于每个事件静态 QueueOwner 数量之和。当前每个事件只有 R1_PROJECTION 一个 Owner，因此 CONNECTED_VALID 为 2/2，其他成功分支均为 1/1。NO_CHANGE、REJECTED、重放和冲突均不追加事件或 Outbox。

| BranchID | CommandType | Outcome | EventTypes | EventCount | OutboxCount |
|---|---|---|---|---:|---:|
| CAPTURE_LEAD_CREATED | CAPTURE_LEAD | CREATED | LeadCapturedV1 | 1 | 1 |
| SAVE_ACTION_DRAFT_CHANGED | SAVE_ACTION_DRAFT | CREATED_OR_CHANGED | ActionDraftSavedV1 | 1 | 1 |
| REOPEN_DUE_CONTACT_TASKS_REOPENED | REOPEN_DUE_CONTACT_TASKS | WAITING_TO_OPEN | ContactTaskReopenedV1 | 1 | 1 |
| REOPEN_DUE_ROUTING_REVIEW_TASKS_REOPENED | REOPEN_DUE_ROUTING_REVIEW_TASKS | WAITING_TO_OPEN | RoutingReviewTaskReopenedV1 | 1 | 1 |
| P0_01_LINK_EXISTING | RESOLVE_DUPLICATE_LEAD | LINK_EXISTING_PARTY | LeadDuplicateResolutionRecordedV1 | 1 | 1 |
| P0_01_KEEP_SEPARATE | RESOLVE_DUPLICATE_LEAD | KEEP_SEPARATE | LeadDuplicateResolutionRecordedV1 | 1 | 1 |
| P0_02_COMPLETE | COMPLETE_LEAD_INGRESS | INGRESS_COMPLETED | LeadIngressCompletedV1 | 1 | 1 |
| P0_03_ASSIGN | ASSIGN_LEAD | ASSIGNED | LeadAssignedV1 | 1 | 1 |
| P0_04_SCHEDULE_ROUTING_REVIEW | RECORD_ROUTING_DISPOSITION | SCHEDULE_ROUTING_REVIEW | LeadRoutingDispositionRecordedV1 | 1 | 1 |
| P0_04_RETRY_ASSIGNMENT_NOW | RECORD_ROUTING_DISPOSITION | RETRY_ASSIGNMENT_NOW | LeadRoutingDispositionRecordedV1 | 1 | 1 |
| P0_04_REQUEST_SOURCE_INTAKE_STOP | RECORD_ROUTING_DISPOSITION | REQUEST_SOURCE_INTAKE_STOP | SourceIntakeStopRequestedV1 | 1 | 1 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED | SourceIntakeStopRequestAcknowledgedV1 | 1 | 1 |
| CONTACT_CONNECTED_VALID | RECORD_CONTACT_RESULT | CONNECTED_VALID | LeadContactResultRecordedV1,OpportunityOpened | 2 | 2 |
| CONTACT_NOT_CONNECTED_RETRY | RECORD_CONTACT_RESULT | NOT_CONNECTED_RETRY | LeadContactResultRecordedV1 | 1 | 1 |
| CONTACT_NOT_CONNECTED_EXHAUSTED | RECORD_CONTACT_RESULT | NOT_CONNECTED_EXHAUSTED | LeadContactRetryExhaustedV1 | 1 | 1 |
| CONTACT_SUSPECT_INVALID | RECORD_CONTACT_RESULT | SUSPECT_INVALID | LeadContactResultRecordedV1 | 1 | 1 |
| REVIEW_CONFIRM_INVALID | REVIEW_LEAD_VALIDITY | CONFIRM_INVALID | LeadValidityReviewedV1 | 1 | 1 |
| REVIEW_CLOSE_UNREACHED | REVIEW_LEAD_VALIDITY | CLOSE_UNREACHED | LeadValidityReviewedV1 | 1 | 1 |
| REVIEW_REOPEN_CONTACT | REVIEW_LEAD_VALIDITY | REOPEN_CONTACT | LeadValidityReviewedV1 | 1 | 1 |

CONNECTED_VALID 在同一事务写准确 ContactResult、由其唯一产生的 Opportunity@0、确认 Draft、原 Task DONE、两条 Event、两条 R1_PROJECTION Outbox、一个 Receipt 和一个 Audit；Receipt resultFact 仍是 ContactResult@hash。两个 source 必须以 Opportunity 的 source_lead_id/source_assignment_id/source_contact_result_id 和 Owner facts 相互印证。任一缺失、错源、重复或持久化失败均整体回滚；R2 Task 增量为 0。

## Projection and delayed-consumer rules

R1_PROJECTION 把可变 Lead、Draft、Task 的 source revision 仅作为“重新读取当前事实”信号，通过 Tenant 绑定和具名 Owner read port 读当前状态；它必须容忍延迟、重复和乱序，禁止旧事件覆盖较新投影、把 DONE Task 恢复为 OPEN 或恢复过时 Draft。需要历史结论的消费者读取不可变 Decision、ContactResult、WaitReceipt 或 write-once 边界字段，不能把当前行冒充历史快照。

R1 保留历史及新增 OpportunityOpened，即使其 R1_PROJECTION Outbox 已 DELIVERED 也不删除、改写或重置。R2 启用前必须以新阶段合同定义具名消费者，覆盖启用前历史事件、启用并发新事件、重复、乱序、中断重试，并以 Tenant + Opportunity + 首个推进责任类型保证至多一次。R2 读取 Opportunity 的稳定创建来源并验证不可变 ContactResult，不要求当前 Opportunity revision 仍为 0。本轮不注册 R2 QueueOwner，不创建 R2 Outbox/Task，不实现通用回放平台；ADM-01～07 管理能力仍在 R1 合同门禁之外。

## Delivery boundary

本文冻结合同而不宣称 Handler、API、Worker、Workbench、真实身份配置或浏览器业务验收已完成。R1-BACKEND、R1-SPA、R1-E2E-GOLDEN、R1-E2E-FAILURES 的交付状态不因本文改变。
