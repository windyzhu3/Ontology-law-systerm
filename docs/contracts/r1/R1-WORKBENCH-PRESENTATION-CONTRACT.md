# R1 Workbench 呈现合同

Contract ID: R1-WORKBENCH-V1.2

Task9 identity authority: [ADR-0014](../../adr/ADR-0014-task9-real-user-access.md); profile: R1_IDENTITY_ACCESS_V1

2026-09-08 named successor activates [Identity V1.0](R1-IDENTITY-ACCESS-CONTRACT.md), baseline MVP-2026-09-08.2, HTTP V1.4, Command V1.3, Workbench V1.2 and OpenAPI1.3.0: exactly37 operations (32 public Bearer +5 internal mTLS),14 static Identity commands and the principal-only self context. Prior activation paragraphs/counts below are historical. ADM-01–04 HUMAN production work and real login are now in Task9 scope; Task9.1 is static FROZEN only. Existing nine business request DTOs, seven Task types and fourteen events are unchanged. Keycloak independently owns external identity storage outside the business13 schemas/52+2 tables at52-plus-2-v1.2; one SPA/OpenAPI/Jar and exclusive APP_ROLE=api|worker remain. No Task9 runtime, human UAT, Task10/capacity or R1 release status is advanced.


Receipt recovery authority: [ADR-0013](../../adr/ADR-0013-r1-command-receipt-recovery.md); profile: R1_RECEIPT_LEGACY_RECOVERY_V1

At semantic baseline `MVP-2026-09-08.1`, receipt GET is always no-store with no ETag/304 and discloses only after current authorization and committed read Audit. The CurrentCard cache protocol below is unchanged. Preserve the complete original request and original Idempotency-Key for ambiguous writes. Old or unsupported recovery metadata safely fails; never parse/backfill legacy Audit text. Replay at the original endpoint under current authorization returns the original terminal result or conflict with no read Audit, metadata repair, business reexecution or other delta. A failed GET is not command failure and must never generate a new key automatically. Without the original request and valid metadata there is no automatic recovery or admin repair path. Browser Bearer GET cannot recover internal mTLS commands; those retain original mTLS request/key recovery.

All seven nonempty CurrentCard variants are `R1_CURRENT_WORKCARD_DISCLOSURE_V1`. Both 200 BODY and 304 CACHE_REVALIDATED return only after disclosure Audit commit. Task, Lead, Owner Appointment/Principal/OrganizationUnit and every actually returned Draft/candidate/fact are separate typed disclosedSource anchors. Responses use `Cache-Control: private, no-cache` and `Vary: Authorization`; Actor-scoped ETags include authorization and every disclosed source revision/digest. The SPA uses generation/AbortController so a late older request cannot overwrite a newer envelope.

[ADR-0011](../../adr/ADR-0011-r1-contact-reopen-evidence-read.md) activates semantic baseline `MVP-2026-09-06.3` while this contract ID and wire DTOs remain unchanged. Its Evidence rule only secures the existing optional Contact Draft candidate; it creates no browser, upload, download or management feature.

Status: FROZEN

确认日期：2026-09-02

R1 只交付一份响应式 Workbench。ADR-0014 纳入同一 SPA 的受保护 ADM-01～04 HUMAN 身份管理及真实登录；静态合同不代表生产能力完成。

## Envelope fields

| Field | Cardinality | Contract |
|---|---|---|
| todaySummary | 1 | 一句安全、可本地化的今日摘要；没有工作时仍返回零态句子 |
| currentCard | 0..1 | 唯一可展开完整业务内容与动作区的当前责任卡 |
| nextSummaries | 0..2 | 只含下一责任摘要、优先级和安全时间提示，不含完整 payload 或动作 |
| waitingCount | 1 | 当前 Actor 可见 WAITING Task 的非负计数 |
| chatComposer | 1 | 固定底部候选输入区；只生成或修改 ActionDraft，不自行完成 Task |

`currentCard` 缺失时必须显示明确零态，不能用 `nextSummaries` 提升成第二张完整卡。任何普通销售 Workbench 响应最多暴露一张完整卡和两条摘要。

## CurrentWorkCardEnvelope wire projection

所有对象都拒绝未声明字段。字段名、requiredness 和 null 形态固定如下；本文的 `Uuid`、`Revision`、`Instant`、`Digest32`、`Code64` 和 strong ETag 使用 HTTP 合同定义。响应不含 Tenant、Principal/Grant/组织内部 ID、SQL/Repository 名称、密文、HMAC、授权路径、原始 Task/Fact hash 或不可见主体数据。

所有`Revision`响应和请求均限于JSON安全整数`0..9007199254740991`；投影不得发出旧导入的超界值或静默舍入。具名routing-review恢复完成后，刷新必须把该`RESOLVE_LEAD_ROUTING_GAP` Task以OPEN及递增revision投影为`CurrentCard`，随后只接受其冻结的`RECORD_ROUTING_DISPOSITION`流程；恢复前、错误Task类型或stale selector均不得改变卡片或等待计数。

### Top-level and card DTOs

| DTO | Field | Cardinality | Exact type / constraint |
|---|---|---:|---|
| `CurrentWorkCardEnvelope` | todaySummary | 1 | safe localized string `1..500`；零工作时仍必填 |
| `CurrentWorkCardEnvelope` | currentCard | 1 nullable | `CurrentCard|null` |
| `CurrentWorkCardEnvelope` | nextSummaries | 1 | array of `NextSummary`，`0..2` |
| `CurrentWorkCardEnvelope` | waitingCount | 1 | integer `>=0` |
| `CurrentWorkCardEnvelope` | chatComposer | 1 | `ChatComposer` |
| `CurrentCard` | taskId | 1 | Uuid；本合同明确公开的 Task resource ID |
| `CurrentCard` | taskType | 1 | `RESOLVE_LEAD_DUPLICATE\|COMPLETE_LEAD_INGRESS\|ASSIGN_LEAD\|RESOLVE_LEAD_ROUTING_GAP\|ACK_SOURCE_INTAKE_STOP_REQUEST\|CONTACT_LEAD\|REVIEW_LEAD_VALIDITY` |
| `CurrentCard` | taskRevision | 1 | Revision |
| `CurrentCard` | subject | 1 | `SubjectSummary` |
| `CurrentCard` | owner | 1 | `OwnerSummary` |
| `CurrentCard` | businessPurpose | 1 | `LabeledCode` |
| `CurrentCard` | primaryCommand | 1 | `PrimaryCommand` |
| `CurrentCard` | expectedCompletionFact | 1 | Code64 |
| `CurrentCard` | sla | 1 | `SlaSummary` |
| `CurrentCard` | versionStatus | 1 | `CURRENT\|REFRESH_RECOMMENDED`；不能只靠颜色呈现 |
| `CurrentCard` | commandForm | 1 | `CommandForm` |
| `CurrentCard` | actionDraft | 1 nullable | `ActionDraftProjection|null` |
| `CurrentCard` | preconditions | 1 | `PreconditionTokens` |

### Nested DTOs

| DTO | Exact fields |
|---|---|
| `SubjectSummary` | `subjectType: "LEAD"` (1), `subjectRef: OpaqueRef` (1), `subjectRevision: Revision` (1), `title: string[1..200]` (1), `subtitle: string[1..300]` (0..1) |
| `OwnerSummary` | `displayName: string[1..200]` (1), `organizationLabel: string[1..200]` (1)；不投影 Appointment/Principal 内部 ID |
| `LabeledCode` | `code: Code64` (1), `label: string[1..200]` (1) |
| `PrimaryCommand` | `code: Code64` (1), `label: string[1..200]` (1), `enabled: boolean` (1)；code 必须等于 Task 冻结主命令 |
| `SlaSummary` | `code: Code64` (1), `dueAt: Instant` (1), `status: ON_TRACK\|DUE_SOON\|OVERDUE` (1), `timeHint: string[1..200]` (1) |
| `PreconditionTokens` | `taskETag: TaskETag` (1), `subjectETag: SubjectETag` (1), `draftETag: DraftETag|null` (1) |
| `NextSummary` | `taskId: Uuid` (1), `businessPurpose: LabeledCode` (1), `priority: URGENT\|NORMAL` (1), `timeHint: string[1..200]` (1)；不含 subject payload、form、Draft、ETag 或 action |
| `ChatComposer` | `mode: "ACTION_DRAFT"` (1), `targetTaskId: Uuid|null` (1), `placeholder: string[1..200]` (1), `enabled: boolean` (1)；currentCard 为空时 targetTaskId 必须为 null 且 enabled=false，否则 targetTaskId 等于 currentCard.taskId |

`subjectRef` 只是用于详情呈现和客户端稳定 key 的 Actor-scoped 不透明引用；Task command 的准确 subject 仍由 path `taskId` 在服务端解析，调用方不能从 ref 或 revision 构造 subject selector。

## Command form and Draft projection

| DTO | Field | Cardinality | Exact type / constraint |
|---|---|---:|---|
| `CommandForm` | actionCode | 1 | Code64；等于 currentCard.primaryCommand.code |
| `CommandForm` | schemaVersion | 1 | integer，固定 `1` |
| `CommandForm` | values | 1 | object；key 只允许 HTTP 合同中对应 command DTO 去掉 `draftId`、`expectedDraftRevision`、`draftDigest` 后的字段；服务端固定 selector 必须有准确值，尚未输入的可编辑字段可缺失或为空，保存前必须通过完整 DTO 校验 |
| `CommandForm` | fields | 1 | array of `FormField`，按视觉/键盘顺序，至少 1 |
| `FormField` | name | 1 | 必须是 `values` 中可编辑字段名 |
| `FormField` | label | 1 | localized string `1..200` |
| `FormField` | control | 1 | `TEXT\|TEXTAREA\|SELECT\|EMAIL\|TEL` |
| `FormField` | required | 1 | boolean；必须与 HTTP 条件校验一致 |
| `FormField` | readOnly | 1 | boolean |
| `FormField` | options | 1 | array of `FormOption`，SELECT 非空，其他 control 为空数组 |
| `FormOption` | value | 1 | HTTP DTO 允许的 code、Uuid 或 revision 字符串表示 |
| `FormOption` | label | 1 | localized safe string `1..200` |
| `FormOption` | disabled | 1 | boolean |

RESOLVE 的 candidate Lead/Party selector、ACK 的 causal Decision selector、CONTACT 的 LeadAssignment selector、REVIEW 的 triggering ContactResult selector以及 ASSIGN 的可选 Owner Appointment 都由服务器从准确 Task/可见候选生成到 `commandForm.values` 或 SELECT option value；客户端只能回传，不能发现 Tenant 或授权内部结构。

`ActionDraftProjection` 的字段恰为以下八项，不能添加 state、Tenant、creator、confirmedBy、内部 digest 或 Draft ETag：

| Field | Cardinality | Exact type / constraint |
|---|---:|---|
| draftId | 1 | Uuid |
| draftRevision | 1 | Revision |
| actionCode | 1 | Code64 |
| schemaVersion | 1 | integer，固定 `1` |
| values | 1 | 与对应 command-specific DTO 相同且已通过全部 required/conditional validation 的准确对象 shape |
| digest | 1 | Digest32；RFC 8785 规范化 values 的 SHA-256，无填充 base64url |
| updatedAt | 1 | Instant |
| editable | 1 | boolean；state=DRAFT 且 Task/Actor 仍可编辑时为 true，CONFIRMED 时恒为 false |

刷新恢复 Draft 只通过本 envelope；没有第二个 Draft GET endpoint。Draft strong ETag 单独位于 `preconditions.draftETag`，避免改变冻结的八字段 shape。

## Route modes

| RouteMode | PathPattern | Navigation | Sidebar |
|---|---|---|---|
| WORKBENCH | /workbench | NONE | NONE |
| IDENTITY_ADMIN | /admin/identity/* | IDENTITY_ONLY | LEFT |

Workbench 普通路径不显示全局菜单或左侧栏；操作流围绕当前卡和固定 composer。身份管理只有在受保护的 `IDENTITY_ADMIN` route mode 中显示身份专用导航和左侧栏，不能把管理入口混入普通销售页面。

## Presentation and interaction invariants

- 首屏读取 `getCurrentWorkCard` envelope；Workbench ETag 只由 HTTP `ETag` header 携带并缓存整份投影，不替代 `preconditions` 中的 Task、Draft 或 subject strong ETag。
- DTO必须携带taskType与revision供合同分派和并发控制；卡片只向普通用户显示本地化业务目的、安全主体摘要、Owner、SLA、安全版本状态和允许动作，不直接渲染原始Task/Command/Event/Decision/hash代码、Repository或不可见Tenant信息。
- composer 的候选动作先按 `commandForm` 保存为 Draft；保存响应或 envelope 提供 draft digest/revision/ETag。用户显式确认时，七个 command 必须回传 `draftId`、`expectedDraftRevision`、`draftDigest` 和完全相同的 command-specific values；服务端把 DRAFT→CONFIRMED 与业务完成 Fact 原子提交。没有 Draft 不允许直提。网络重试保持原幂等键；stale 或需要刷新后改正的拒绝按 HTTP 合同生成新键。
- 写入成功后以 Receipt 为准刷新 envelope；同 key replay 不重复乐观插入卡、消息或计数。
- 错误显示只消费 HTTP 合同的安全 Problem Details。403/404 不推断对象是否存在；412/428 明确提示刷新或补齐前置条件。
- 键盘顺序固定为摘要、当前卡、动作、composer；焦点在刷新后回到相同逻辑控件。状态不能只靠颜色表达，动态摘要和错误使用合适的 live region，并尊重 reduced motion。
- 手机、平板和桌面共享相同信息层级；响应式变化不得引入第二套路由、第二份合同或另一 SPA。

## R1 boundary

原 Task9.0 的 `/workbench` 及其 P0-01 至 P0-04、联系和有效性复核卡保留历史证据。ADR-0014 将真实登录、会话与受控 `/admin/identity/*` ADM-01～04 纳入扩大 Task9；新增生产实现、真实账号链路与人工 UAT 均须独立通过，不能由旧证据替代。Token 只在内存；唯一持久恢复例外为 Identity 合同的四字段、每标签页一条、24 小时 sessionStorage 标记。同 Actor Token rotation 保留 epoch、输入及轮询额度，换身份/任职清除旧视图。状态优先级：身份失效 > 未决恢复 > 已确认但刷新失败 > 读取错误 > 普通摘要；NO_APPOINTMENT、无入口权限、未知读取与业务零态必须分别呈现。

## R1 Evidence selector disclosure

CONTACT Draft中的`evidenceSubmissionId`只是候选值。保存Draft不证明Evidence资格；CurrentCard恢复该候选时，必须按主命令相同规则重验当前Task绑定的准确Lead revision、ACTIVE未撤回的Binding，以及Task、Lead、Submission、Binding四个准确来源的授权和DENY。只有全部通过才可在既有`commandForm.values`或`ActionDraftProjection.values`中回显原ID。

若Evidence缺失、跨Tenant、绑定其他Lead/revision、撤回或不可见，该完整卡不合格；按既有排序选择下一张合格卡或返回安全零态，不得静默删去ID而改变Draft digest，也不得显示失败来源。Workbench不得返回文件内容、文件名、对象位置或下载URL；Submission不可变行hash和Binding revision只作为内部披露来源selector。

准确Submission/Binding selector和其Task/Lead/Owner/授权依赖加入Actor-scoped Workbench ETag与`R1_CURRENT_WORKCARD_DISCLOSURE_V1` Audit来源集合。200 BODY和304 CACHE_REVALIDATED均必须重新验证引用与DENY，并在披露Audit提交后返回；旧ETag不得绕过撤权或Binding撤回。该静态合同不声称CurrentCard或SPA生产实现已完成。
