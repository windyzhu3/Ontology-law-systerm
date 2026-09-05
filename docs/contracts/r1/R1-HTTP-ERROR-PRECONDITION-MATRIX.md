# R1 HTTP、错误与前置条件合同

Contract ID: R1-HTTP-V1

Status: FROZEN

确认日期：2026-09-02

本合同冻结 R1 唯一公共 HTTP 面、内部到期恢复入口、幂等和并发语义。它不证明 OpenAPI 或后端已经实现。

## Operations

| OperationId | Method | Path | TenantSource | IdempotencyKey | Preconditions | SubjectBinding | SuccessStatus | ErrorCodes |
|---|---|---|---|---|---|---|---|---|
| captureLead | POST | /api/v1/leads | ACTOR_CONTEXT | REQUIRED | NONE | SOURCE_NATURAL_KEY | 201 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,COMMAND_PAYLOAD_CONFLICT,SUPERVISOR_UNRESOLVED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| getCurrentWorkCard | GET | /api/v1/workcards/current | ACTOR_CONTEXT | NONE | OPTIONAL_WORKBENCH_ETAG | ACTOR_SCOPE | 200/304 | UNAUTHENTICATED,NOT_AUTHORIZED,NOT_FOUND,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| saveActionDraft | PUT | /api/v1/tasks/{taskId}/draft | ACTOR_CONTEXT | REQUIRED | IF_NONE_MATCH_STAR_OR_DRAFT_ETAG | TASK_AND_DRAFT | 200/201 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,DRAFT_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| resolveDuplicateLead | POST | /api/v1/tasks/{taskId}/commands/resolve-duplicate-lead | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_AND_LEAD_REVISION | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| completeLeadIngress | POST | /api/v1/tasks/{taskId}/commands/complete-lead-ingress | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_AND_LEAD_REVISION | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,INGRESS_COMPLETION_ALREADY_RECORDED,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| assignLead | POST | /api/v1/tasks/{taskId}/commands/assign-lead | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_LEAD_AND_ASSIGNMENT | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| recordRoutingDisposition | POST | /api/v1/tasks/{taskId}/commands/record-routing-disposition | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_AND_LEAD_REVISION | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SOURCE_INTAKE_OWNER_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| acknowledgeSourceIntakeStopRequest | POST | /api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_AND_CAUSAL_DECISION | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| recordContactResult | POST | /api/v1/tasks/{taskId}/commands/record-contact-result | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_LEAD_AND_ASSIGNMENT | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| reviewLeadValidity | POST | /api/v1/tasks/{taskId}/commands/review-lead-validity | ACTOR_CONTEXT | REQUIRED | TASK_ETAG | TASK_AND_CAUSAL_RESULT | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| getCommandReceipt | GET | /api/v1/commands/{commandId}/receipt | ACTOR_CONTEXT | NONE | NONE | COMMAND_ID_AND_ACTOR_SCOPE | 200 | UNAUTHENTICATED,NOT_AUTHORIZED,NOT_FOUND,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| reopenDueContactTasks | POST | /internal/v1/tasks/commands/reopen-due-contact-tasks | ACTOR_CONTEXT | REQUIRED | NONE | DUE_CUTOFF_AND_OWNER_QUEUE | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,COMMAND_PAYLOAD_CONFLICT,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |
| reopenDueRoutingReviewTasks | POST | /internal/v1/tasks/commands/reopen-due-routing-review-tasks | ACTOR_CONTEXT | REQUIRED | NONE | DUE_CUTOFF_AND_OWNER_QUEUE | 200 | VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,COMMAND_PAYLOAD_CONFLICT,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE |

TenantSource 的唯一含义是：认证完成后由服务端 ActorContext 提供 Tenant，并用于授权、查询和 SQL 绑定。公共调用方不能提交或覆盖 Tenant；内部入口只接受 mTLS worker 身份并映射到受限 ActorContext。

## Wire type conventions

除非下表另有说明，所有对象都必须拒绝未声明字段（OpenAPI `additionalProperties: false`）。请求和响应都不得出现 `tenantId`、Principal/Grant/organization 内部标识、`commandExecutionSlotId`、SQL 名称、密文、HMAC、授权路径或其他持久化内部标识。`taskId`、`draftId`、`commandId`、`receiptId` 以及 Request DTO 明列的准确领域 selector UUID 是本合同明确公开、受 Actor scope 约束的 resource identifier；它们不能用来读取任意数据库行。未明列的内部主键不得投影，Receipt 的领域结果使用不透明 `factRef`。

| WireType | Exact shape |
|---|---|
| `Uuid` | RFC 4122/9562 UUID 字符串；请求接受大小写，规范响应使用小写连字符形式 |
| `Revision` | JSON integer，`0..9007199254740991`；超界值在API边界拒绝且不得舍入，数据库`bigint`不变，旧导入超界值不得发出 |
| `Instant` | RFC 3339 `date-time`，响应规范化为 UTC `Z`，最多微秒精度 |
| `Code64` | 字符串，`^[A-Z][A-Z0-9_]{0,63}$` |
| `OpaqueRef` | 服务端签发的 Actor-scoped 不透明字符串，长度 `16..512`；不是数据库 ID，也不能跨 Actor 重用 |
| `Digest32` | 32 字节摘要的无填充 base64url，正则 `^[A-Za-z0-9_-]{43}$` |
| `SafeText200` / `SafeText500` / `SafeText2000` | trim 后分别为 `1..200` / `1..500` / `1..2000` 个 Unicode code point；禁止控制字符 |

正文 `Content-Type` 固定为 `application/json`；Problem Details 固定为 `application/problem+json`。所有具名 DTO 都是版本 1；`schemaVersion` 的数值 `1` 不允许调用方请求另一版本。

## Request transport catalog

| OperationId | Path fields | Headers | Body |
|---|---|---|---|
| captureLead | none | `Authorization: Bearer …`; `Idempotency-Key: Uuid` | `CaptureLeadV1` |
| getCurrentWorkCard | none | `Authorization: Bearer …`; optional `If-None-Match: WorkbenchETag` | none |
| saveActionDraft | `taskId: Uuid` | `Authorization: Bearer …`; `Idempotency-Key: Uuid`; exactly one of `If-None-Match: *` or `If-Match: DraftETag` | `SaveActionDraftV1` |
| resolveDuplicateLead | `taskId: Uuid` | `Authorization: Bearer …`; `Idempotency-Key: Uuid`; `If-Match: TaskETag` | `ResolveDuplicateLeadV1` |
| completeLeadIngress | `taskId: Uuid` | same command headers | `CompleteLeadIngressV1` |
| assignLead | `taskId: Uuid` | same command headers | `AssignLeadV1` |
| recordRoutingDisposition | `taskId: Uuid` | same command headers | `RecordRoutingDispositionV1` |
| acknowledgeSourceIntakeStopRequest | `taskId: Uuid` | same command headers | `AcknowledgeSourceIntakeStopRequestV1` |
| recordContactResult | `taskId: Uuid` | same command headers | `RecordContactResultV1` |
| reviewLeadValidity | `taskId: Uuid` | same command headers | `ReviewLeadValidityV1` |
| getCommandReceipt | `commandId: Uuid` | `Authorization: Bearer …` | none |
| reopenDueContactTasks | none | mutual-TLS client identity; `Idempotency-Key: Uuid` | `ReopenDueContactTaskV1` |
| reopenDueRoutingReviewTasks | none | mutual-TLS client identity; `Idempotency-Key: Uuid` | `ReopenDueRoutingReviewTaskV1` |

“same command headers”恰指表中三项，不允许额外的 Tenant、subject revision、Draft ETag 或自由 command/action header。`If-Match`、`If-None-Match` 都只接受一个标签，不接受逗号列表、弱标签或 `If-Match: *`。`reopenDueContactTasks` 保留冻结的 operationId/path，但一次请求准确恢复一张 Task，不是批处理。

两个具名internal reopen operation先在认证/授权及canonical scope后处理已有key：同scope同payload重放原Receipt，即使Task已越过OPEN；scope/payload冲突返回`COMMAND_PAYLOAD_CONFLICT`和原Receipt引用。新key才在`LEAD→TASK→COMMAND advisory lock`下进入pre-insert eligibility gate，验证Task类型、WAITING状态、expected revision、最新WaitReceipt ID/hash/profile和due条件。before-due、wrong-type和stale-selector返回`VALIDATION_FAILED`，且slot、Receipt、Audit及业务写入delta全部为0；通过验证后才插slot并CAS。

## Request DTO catalog

### Reusable request members

| DTO | Field | Cardinality | Type | Exact validation |
|---|---|---:|---|---|
| `DraftConfirmationV1` | draftId | 1 | Uuid | 必须是 path `taskId` 的唯一 Draft |
| `DraftConfirmationV1` | expectedDraftRevision | 1 | Revision | 必须等于当前 Draft revision |
| `DraftConfirmationV1` | draftDigest | 1 | Digest32 | 必须等于服务端最近签发、只覆盖 command-specific values 的候选 payload digest |
| `DecisionInputV1` | decisionCode | 1 | Code64 | 每个 operation 使用下表自己的封闭枚举 |
| `DecisionInputV1` | rationaleSummary | 1 | SafeText500 | 脱敏审查理由；不得含正文、凭据或 Secret |

七个 Task command 把 `DraftConfirmationV1` 三个字段平铺在正文顶层；其余字段共同组成 Draft 的 `values`，因此保存 Draft 和正式 command 使用同一业务字段校验，不存在第二套候选 Schema。

### Operation bodies

| Schema | Exact fields | Conditional validation |
|---|---|---|
| `CaptureLeadV1` | `sourceChannelCode: Code64` (1), `sourceAccountCode: string[1..128]` (1), `sourceRecordKey: string[1..256]` (1), `capturedAt: Instant` (1), `capturedName: string[1..200]` (0..1), `phone: string[2..16]` (0..1), `email: email-string[1..320]` (0..1), `cityCode: Code64` (0..1), `serviceCategoryCode: Code64` (1), `jurisdictionCode: Code64` (1), `urgencyCode: Code64` (1), `legalNeedSummary: SafeText2000` (1) | phone 若出现必须匹配 `^\+[1-9][0-9]{0,14}$`；phone/email 可同时缺失并进入 P0-02。sourceRecordKey 区分大小写且不 trim。客户端不得提交 Lead/Party/Assignment ID、摘要、密文、HMAC、捕获内容 digest 或 Tenant；服务端按 Task 合同规范化并保护自然键和敏感值 |
| `SaveActionDraftV1` | `actionCode: Code64` (1), `schemaVersion: integer` (1), `values: object` (1) | `actionCode` 必须等于 Task 冻结 `primaryCommand`；`schemaVersion=1`；`values` 必须逐字段等于相应 command DTO 去掉 `draftId`、`expectedDraftRevision`、`draftDigest` 后的 Schema |
| `ResolveDuplicateLeadV1` | Draft confirmation fields (各 1), `decisionCode` (1), `candidateLeadId: Uuid` (1), `candidateLeadRevision: Revision` (1), `partyId: Uuid` (1), `partyRevision: Revision` (1), `rationaleSummary: SafeText500` (1) | `decisionCode ∈ {LINK_EXISTING_PARTY,KEEP_SEPARATE}`；两个 exact selector 两分支均必填并须等于 Task 创建时按 Task 合同确定、提交时重验的候选及其同一活动 Party；两分支均要求当前Lead仍为`CAPTURED`并做`revision=old+1` CAS：LINK写入Party解析和`LINK_EXISTING_PARTY`处置，KEEP只写`disposition_code=KEEP_SEPARATE`且禁止修改Party解析、assignment、捕获/V850字段及candidate Lead/Party |
| `CompleteLeadIngressV1` | Draft confirmation fields (各 1), `phone: string[2..16]` (0..1), `email: email-string[1..320]` (0..1), `sourceCode: Code64` (1), `sourceSummary: SafeText500` (1) | phone/email 至少一个；phone 若出现必须匹配 `^\+[1-9][0-9]{0,14}$`；`sourceCode ∈ {OWNER_CONFIRMED,CUSTOMER_PROVIDED}`；仅当原始 phone/email 和完整 ingress 槽均为空时允许；服务端生成密文、HMAC、完成时间和 digest |
| `AssignLeadV1` | Draft confirmation fields (各 1), `ownerAppointmentId: Uuid` (1) | Appointment 必须来自当前卡允许候选，且提交前仍为同 Tenant、ACTIVE、有准确 authority 且无 DENY |
| `RecordRoutingDispositionV1` | Draft confirmation fields (各 1), `decisionCode` (1), `rationaleSummary: SafeText500` (1) | `decisionCode ∈ {SCHEDULE_ROUTING_REVIEW,RETRY_ASSIGNMENT_NOW,REQUEST_SOURCE_INTAKE_STOP}`；恢复时间、候选选择和准确 intake Owner 均由服务器策略决定 |
| `AcknowledgeSourceIntakeStopRequestV1` | Draft confirmation fields (各 1), `causalDecisionId: Uuid` (1), `causalDecisionHash: Digest32` (1), `rationaleSummary: SafeText500` (1) | causal selector 必须等于 Task 创建时按 Task 合同确定、提交时重验的 routing Decision selector；唯一 outcome 隐含为 `SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED`，不接受自由 decisionCode |
| `RecordContactResultV1` | Draft confirmation fields (各 1), `leadAssignmentId: Uuid` (1), `leadAssignmentRevision: Revision` (1), `contactChannelCode: Code64` (1), `resultCode: Code64` (1), `resultSummary: SafeText500` (0..1), `legalNeed: SafeText2000` (0..1), `evidenceSubmissionId: Uuid` (0..1) | `contactChannelCode ∈ {PHONE,EMAIL}`；`resultCode ∈ {CONNECTED_VALID,NOT_CONNECTED,SUSPECT_INVALID}`；`legalNeed`在CONNECTED_VALID时必填并作为新Opportunity的受保护原始描述，在其他结果时禁止；Assignment selector 必须等于Task创建时按Task合同确定、提交时重验的绑定；Evidence若出现必须为同Tenant可见的准确Submission |
| `ReviewLeadValidityV1` | Draft confirmation fields (各 1), `triggeringContactResultId: Uuid` (1), `triggeringContactResultHash: Digest32` (1), `decisionCode` (1), `rationaleSummary: SafeText500` (1) | `decisionCode ∈ {CONFIRM_INVALID,CLOSE_UNREACHED,REOPEN_CONTACT}`；ContactResult selector 必须等于 Task 创建时按 Task 合同确定、提交时重验的触发结果 |
| `ReopenDueContactTaskV1` | `taskId: Uuid` (1), `expectedTaskRevision: Revision` (1), `waitReceiptId: Uuid` (1), `waitReceiptHash: Digest32` (1), `dueCutoff: Instant` (1) | Task 必须为 WAITING `CONTACT_LEAD`；最新 WaitReceipt 必须绑定 expected revision 且 `resumeDueAt <= dueCutoff <=` 服务端事务可信当前时间；恰做一次 WAITING→OPEN CAS。若同一 selector 已使 Task 成为 OPEN/revision=`expectedTaskRevision+1`，返回 NO_CHANGE；不允许空批成功 |
| `ReopenDueRoutingReviewTaskV1` | `taskId: Uuid` (1), `expectedTaskRevision: Revision` (1), `waitReceiptId: Uuid` (1), `waitReceiptHash: Digest32` (1), `dueCutoff: Instant` (1) | Task 必须为 WAITING `RESOLVE_LEAD_ROUTING_GAP`且最新WaitReceipt为`R1_ROUTING_REVIEW_WAIT_V1`；其余due、CAS和NO_CHANGE语义与contact恢复相同 |

七种 TaskOccurrence 的唯一持久 `subject` 均为 `lead.lead@revision`（revision 必填、hash 为空）。RESOLVE 的 candidate Lead/Party、ACK 的 causal Decision、CONTACT 的 LeadAssignment、REVIEW 的 triggering ContactResult 是具名次级 command-scope/提交前重验 selector，不得伪装成第二个 Task subject。scope digest 覆盖 commandType、taskId、准确持久 Lead selector 和按字段名排序的次级 selector。

## Successful response projections

| OperationId | Status | Headers | Exact body |
|---|---|---|---|
| captureLead | 201 | `Location: /api/v1/commands/{commandId}/receipt` | `CommandReceipt`；resultFact 必须为 `LEAD@postCommitRevision` |
| getCurrentWorkCard | 200 | `ETag: WorkbenchETag` | Workbench 合同的 `CurrentWorkCardEnvelope` |
| getCurrentWorkCard | 304 | `ETag: WorkbenchETag` | no body |
| saveActionDraft | 201 create / 200 update | `Location: /api/v1/commands/{commandId}/receipt`; `ETag: DraftETag` | `ActionDraftWriteResult`；receipt resultFact 必须为 `ACTION_DRAFT@postWriteRevision` |
| seven Task commands | 200 | `Location: /api/v1/commands/{commandId}/receipt` | `CommandReceipt`；resultFact 按 Task 完成矩阵绑定准确 completion Fact |
| getCommandReceipt | 200 | none | `CommandReceipt`，逐字段等于原终态 Receipt 投影 |
| reopenDueContactTasks | 200 | `Location: /api/v1/commands/{commandId}/receipt`; `ETag: TaskETag` | `CommandReceipt`；resultFact 必须为恢复后的 `TASK_OCCURRENCE@postReopenRevision` |
| reopenDueRoutingReviewTasks | 200 | `Location: /api/v1/commands/{commandId}/receipt`; `ETag: TaskETag` | `CommandReceipt`；resultFact 必须为恢复后的 `TASK_OCCURRENCE@postReopenRevision` |

`ActionDraftWriteResult` 恰含 `receipt: CommandReceipt`、`draft: ActionDraftProjection` 和 `preconditions: PreconditionTokens`，三者均必填；其中 `preconditions.draftETag` 必须等于响应 `ETag`。七个 Task command 指 Operations 表中从 `resolveDuplicateLead` 到 `reviewLeadValidity` 的七行。

| Task command operationId | CommandReceipt resultFact |
|---|---|
| resolveDuplicateLead | `DECISION_RECORD@content digest` |
| completeLeadIngress | `LEAD@postCommitRevision` |
| assignLead | `LEAD_ASSIGNMENT@revision` |
| recordRoutingDisposition | `DECISION_RECORD@content digest` |
| acknowledgeSourceIntakeStopRequest | `DECISION_RECORD@content digest` |
| recordContactResult | `LEAD_CONTACT_RESULT@exact immutable-row digest` |
| reviewLeadValidity | `DECISION_RECORD@content digest` |

### Public-safe receipt and error extension DTOs

| DTO | Field | Cardinality | Type / constraint |
|---|---|---:|---|
| `CommandReceipt` | commandId | 1 | Uuid；等于调用方 `Idempotency-Key` |
| `CommandReceipt` | receiptId | 1 | 服务端 UUIDv7 |
| `CommandReceipt` | outcome | 1 | `SUCCEEDED\|NO_CHANGE\|REJECTED` |
| `CommandReceipt` | completedAt | 1 | Instant |
| `CommandReceipt` | resultFact | 0..1 | `PublicFactRef`；SUCCEEDED/NO_CHANGE 时恰为 1，REJECTED 时为 0 |
| `CommandReceipt` | rejectionCode | 0..1 | 当前 operation ErrorCodes 中的安全 code；仅 REJECTED 时恰为 1 |
| `PublicFactRef` | factType | 1 | `LEAD\|ACTION_DRAFT\|TASK_OCCURRENCE\|DECISION_RECORD\|LEAD_ASSIGNMENT\|LEAD_CONTACT_RESULT` |
| `PublicFactRef` | factRef | 1 | OpaqueRef |
| `PublicFactRef` | revision | 0..1 | Revision；与 digest 二选一 |
| `PublicFactRef` | digest | 0..1 | Digest32；与 revision 二选一 |
| `ReceiptRef` | commandId | 1 | Uuid |
| `ReceiptRef` | href | 1 | 精确相对 URI `/api/v1/commands/{commandId}/receipt` |
| `FieldError` | pointer | 1 | RFC 6901 JSON Pointer；header/path 使用 `/headers/{name}` 或 `/path/{name}` |
| `FieldError` | code | 1 | `REQUIRED\|INVALID_FORMAT\|OUT_OF_RANGE\|NOT_ALLOWED\|CONDITION_FAILED` |
| `FieldError` | detail | 1 | SafeText200 |
| `CurrentETag` | resourceKind | 1 | `WORKBENCH\|TASK\|DRAFT\|SUBJECT` |
| `CurrentETag` | value | 1 | 下节相同 kind 的 strong ETag |

`CommandReceipt` 不投影 slot ID、Tenant、内部 fact ID、payload/scope digest、Audit/Event/Outbox 或授权细节。同 key/scope/payload replay 必须返回同一 `receiptId`、`completedAt` 和其余字段，不能伪造新的“replayed receipt”。

## ETag contract

强标签语法固定为：

```abnf
strong-etag = DQUOTE etag-kind "." digest43 DQUOTE
etag-kind   = "wb" / "task" / "draft" / "subject"
digest43    = 43(ALPHA / DIGIT / "-" / "_")
```

`digest43` 是服务端对 kind、Actor-scoped resource identity、准确 revision/hash 及该标签所保护投影版本做规范编码后的 SHA-256 base64url；不可逆且不能包含 Tenant 或内部 ID。标签必须逐字节比较、永不以 `W/` 开头，客户端必须把整个带双引号值当成不透明 token。

| Kind | Client obtains it from | Client submits it at | Meaning |
|---|---|---|---|
| Workbench | `getCurrentWorkCard` 的 200/304 `ETag` header | 同 GET 的可选 `If-None-Match` | 整份 Actor-scoped envelope 缓存版本 |
| Task | `currentCard.preconditions.taskETag`; stale/task-state Problem 的 `currentETag`; reopen 成功 `ETag` | 七个 Task command 的 `If-Match` | 当前 Task state/revision；不能代替 subject 校验 |
| Draft | `currentCard.preconditions.draftETag`; save 成功 `ETag`; Draft Problem 的 `currentETag` | Draft 更新的 `If-Match` | 当前 Draft state/revision；首次创建改用 `If-None-Match: *` |
| Subject | `currentCard.preconditions.subjectETag`; Draft response preconditions；subject Problem 的 `currentETag` | R1 没有直接 subject `If-Match` header | 当前 Lead subject selector；服务端在 Task command 锁内重验，供刷新/诊断但不能替代 Task ETag |

每张可执行 currentCard 必须同时给出 Task 和 Subject 标签；存在 Draft 时必须给出 Draft 标签，不存在时 `draftETag=null`。因此调用方不需要从 revision 自造任何标签。

## ActionDraft confirmation lifecycle

1. `saveActionDraft` 只创建或更新候选：不存在 Draft 时必须用 `If-None-Match: *` 并返回 201；已存在且仍为 DRAFT 时必须用准确 Draft `If-Match` 并返回 200。保存成功写一张以 post-write Draft revision 为 result Fact 的 Receipt，但不完成 Task。
2. 服务端按 RFC 8785 规范化 `values` 并签发 `digest`；`actionCode` 和 `schemaVersion` 创建后不可改。编辑使用 Draft revision CAS，同时替换 values/digest、更新 `updatedAt` 和 Draft ETag。
3. 用户显式确认只能通过对应具名 Task command：body 的 `draftId`、`expectedDraftRevision`、`draftDigest`、业务字段和已保存候选必须解析为同一 action/schema/规范 payload/digest。缺少任一字段返回 VALIDATION_FAILED；不存在、不可见或不属于该 Task 的 Draft 返回 NOT_FOUND；revision stale 返回 STALE_DRAFT；digest/action/schema/payload 不一致返回 DRAFT_DIGEST_MISMATCH；已 CONFIRMED 的不同 command 不可再次执行并按当前 Task/Receipt 状态返回冻结 allowlist 中的拒绝。
4. 新 command 成功时，在同一业务事务把准确 Draft `DRAFT→CONFIRMED`（确认 digest 等于候选 digest）、写 completion Fact、Task DONE、Audit/Event/Outbox/Receipt；任一失败整体回滚。Draft 确认本身永远不是 completion Fact。
5. R1 Workbench 的七个 Task command 均不允许无 Draft 直提，三个 confirmation fields 因此都必填。只有同 key/scope/payload replay 可在 Draft 已 CONFIRMED 后返回原 Receipt，不能重新确认或重复写业务事实。

## OpenAPI security binding

唯一可执行 OpenAPI authority 仍且只位于 `contracts/openapi/ontology-law-api.yaml`；本合同冻结其语义输入，但不创建第二份 OpenAPI 或生成物。该 OpenAPI 必须只定义以下两个 security scheme；这里不冻结认证产品、issuer、audience、claim 名或 OAuth scope：

| Scheme name | OpenAPI shape | Exact operation binding |
|---|---|---|
| `publicBearer` | `type: http`, `scheme: bearer` | 所有 `/api/v1/**` 十一个 operation 各自且只使用 `[{publicBearer: []}]` |
| `internalMutualTls` | `type: mutualTLS` | 两个具名reopen operation各自且只使用 `[{internalMutualTls: []}]` |

不得使用空 security、两个 scheme 的 OR/AND 组合、API key、`X-Tenant-Id` 或浏览器持有的内部证书。mTLS 身份只在服务端映射受限 ActorContext；它不允许请求提交 Tenant。

## Authentication challenge binding

| SecurityScheme | Operations | UnauthenticatedTransport |
|---|---|---|
| publicBearer | /api/v1/** | HTTP_401_PROBLEM_WITH_WWW_AUTHENTICATE_BEARER |
| internalMutualTls | reopenDueContactTasks,reopenDueRoutingReviewTasks | TLS_REJECTION_OR_HTTP_401_PROBLEM_WITHOUT_WWW_AUTHENTICATE |

公网Bearer operation的HTTP 401使用`application/problem+json`并带标准`WWW-Authenticate: Bearer` challenge。内部mTLS operation优先在TLS握手层拒绝无证书/无效证书；若证书已通过握手但服务端身份映射失败而产生HTTP 401，则仍返回Problem，但禁止发送Bearer challenge或任何`WWW-Authenticate` header。

## Idempotency binding

| Property | FrozenValue |
|---|---|
| Header | Idempotency-Key |
| ValueType | UUID |
| SlotColumn | execution.command_execution_slot.command_id |
| CommandId | EXACT_CALLER_KEY |
| ReceiptId | SERVER_UUIDV7 |
| SlotScope | TENANT_ENVELOPE_SUBJECT_SCOPE |
| PayloadConflict | ORIGINAL_RECEIPT_NO_NEW_WRITES |

`Idempotency-Key` 是“所有业务 UUID 均由服务端生成”的唯一明确例外：调用方生成稳定 UUID，服务端原样保存为 `execution.command_execution_slot.command_id`，并把相同值作为 `commandId` 返回；`receiptId` 与领域 ID 仍由服务端生成 UUIDv7。服务端在取得 Lead/Task 锁后，以 Tenant＋command UUID 的事务级 advisory lock 保证同一 Tenant 内 command ID 全局唯一，再校验 envelope、准确 subject scope 和规范化 payload。同 scope、同 payload 重放原 Receipt，持久化 delta 为零；异 scope 或异 payload 返回 `COMMAND_PAYLOAD_CONFLICT`和原 Receipt 安全引用，且不新增 Slot、Receipt、Audit 或业务 delta。这样 `/api/v1/commands/{commandId}/receipt` 在 Tenant 内保持单义，且不修改 52＋2 Schema。

capture 不对尚不存在的资源要求 `If-Match`。Draft 首次创建使用 `If-None-Match: *`，更新使用 Draft ETag。Task command 使用 Task ETag；服务端仍在锁内重验 subject revision、Assignment、causal Fact 和 Actor authority，不能把 Task ETag 当成 subject ETag。

按[ADR-0006](../../adr/ADR-0006-command-runtime-authorization-boundary.md)，授权自然有效期在最终持Tenant授权共享事务锁、使用新鲜数据库`clock_timestamp()`完整复验时裁定，不保证物理COMMIT瞬间仍未过期。所有Identity写者使用配对排他锁，复验前已提交的撤销/DENY/组织变化被观察，之后写者等待。COMMIT确认丢失不能解释成确定失败或Receipt不存在；客户端保留相同Idempotency-Key和原请求重试恢复原Receipt，不产生FAILED/UNKNOWN命令回执。

## Error registry

`Problem` 的 shape 和 requiredness 固定如下；没有值的 extension 必须省略，不能返回 `null` 或空数组：

| Field | Cardinality | Exact type / condition |
|---|---:|---|
| type | 1 | absolute URI；按 `code` 稳定映射，不含请求数据 |
| title | 1 | safe localized string `1..200` |
| status | 1 | integer；必须等于实际 HTTP status |
| code | 1 | 当前 operation `ErrorCodes` 中恰好一个值 |
| detail | 1 | SafeText500；只含公开安全说明 |
| instance | 1 | 本次 HTTP occurrence 的不透明 absolute-path URI；不得含 Tenant/subject/internal ID |
| retryPolicy | 1 | 本表对应枚举值 |
| fieldErrors | 0..1 | array `1..64` of `FieldError`；仅 VALIDATION_FAILED 必须且只允许出现 |
| currentETag | 0..1 | `CurrentETag`；本表指定 kind 且当前资源存在并对 Actor 可见时必须出现；`NONE` 时禁止出现 |
| receiptRef | 0..1 | `ReceiptRef`；COMMAND_PAYLOAD_CONFLICT 必须出现并指向原 Receipt；其他 code 仅当该同 key 请求已有可恢复的终态 REJECTED Receipt 时允许出现 |

公网Bearer operation的401必须带标准`WWW-Authenticate: Bearer` challenge；内部mTLS operation遵循上方Authentication challenge binding，绝不发送Bearer challenge。429 和 503 可带整数秒 `Retry-After`。这些 header 不改变 Problem shape，且不得透露 Tenant、对象存在性、授权规则或内部故障原因。

| ErrorCode | HttpStatus | RetryPolicy | FieldErrors | CurrentETag | SafeText |
|---|---|---|---|---|---|
| VALIDATION_FAILED | 400 | SAME_KEY_AFTER_FIX | REQUIRED | NONE | 请求字段未通过校验 |
| IDEMPOTENCY_KEY_REQUIRED | 400 | SAME_KEY_AFTER_FIX | NONE | NONE | 写操作缺少幂等键 |
| IDEMPOTENCY_KEY_INVALID | 400 | SAME_KEY_AFTER_FIX | NONE | NONE | 幂等键格式或长度无效 |
| UNAUTHENTICATED | 401 | SAME_KEY_AFTER_REAUTH | NONE | NONE | 需要有效认证 |
| NOT_AUTHORIZED | 403 | NO | NONE | NONE | 当前身份无权执行此操作 |
| APPOINTMENT_INACTIVE | 403 | NO | NONE | NONE | 当前任职不可用于此操作 |
| NOT_FOUND | 404 | NO | NONE | NONE | 资源不存在或不可见 |
| COMMAND_PAYLOAD_CONFLICT | 409 | NO | NONE | NONE | 幂等键已绑定其他请求 |
| TASK_NOT_OPEN | 409 | NO | NONE | TASK | Task 当前不可执行 |
| TASK_ALREADY_COMPLETED | 409 | NO | NONE | TASK | Task 已完成 |
| DRAFT_DIGEST_MISMATCH | 409 | NEW_KEY_AFTER_REFRESH | NONE | DRAFT | 提交内容与草稿摘要不一致 |
| INGRESS_COMPLETION_ALREADY_RECORDED | 409 | NO | NONE | SUBJECT | Ingress completion 已存在 |
| STALE_TASK | 412 | NEW_KEY_AFTER_REFRESH | NONE | TASK | Task 版本已变化 |
| STALE_DRAFT | 412 | NEW_KEY_AFTER_REFRESH | NONE | DRAFT | Draft 版本已变化 |
| STALE_SUBJECT | 412 | NEW_KEY_AFTER_REFRESH | NONE | SUBJECT | 业务对象版本已变化 |
| SUPERVISOR_UNRESOLVED | 422 | NEW_KEY_AFTER_ADMIN_FIX | NONE | NONE | 无法唯一解析准确主管 |
| SOURCE_INTAKE_OWNER_UNRESOLVED | 422 | NEW_KEY_AFTER_ADMIN_FIX | NONE | NONE | 无法唯一解析准确来源接入负责人 |
| DRAFT_PRECONDITION_REQUIRED | 428 | SAME_KEY_AFTER_FIX | NONE | DRAFT | 缺少 Draft 创建或更新前置条件 |
| TASK_PRECONDITION_REQUIRED | 428 | SAME_KEY_AFTER_FIX | NONE | TASK | 缺少 Task 命令前置条件 |
| RATE_LIMITED | 429 | SAME_KEY_AFTER_BACKOFF | NONE | NONE | 请求过于频繁，请稍后重试 |
| INTERNAL_ERROR | 500 | SAME_KEY_AFTER_BACKOFF | NONE | NONE | 服务暂时无法完成请求 |
| SERVICE_UNAVAILABLE | 503 | SAME_KEY_AFTER_BACKOFF | NONE | NONE | 服务暂时不可用 |

错误响应使用 RFC 9457 Problem Details，并只允许 `type`、`title`、`status`、`code`、`detail`、`instance`、`fieldErrors`、`currentETag`、`receiptRef`、`retryPolicy`。`detail` 和 `SafeText` 不得泄露 SQL、堆栈、Tenant、主体可见性、内部 ID 或授权规则。`fieldErrors` 只在表中标为 REQUIRED 时出现；`currentETag` 只返回表中指定的资源种类，首次创建 Draft 且资源尚不存在时可以不返回具体值。

零分配候选是 P0-04 正常业务分支：完成当前责任并创建 `RESOLVE_LEAD_ROUTING_GAP`，不是 HTTP 错误。技术异常整体回滚；业务拒绝若已占用 command slot，则只留下不可变 REJECTED Receipt 和 REJECTED Audit。
