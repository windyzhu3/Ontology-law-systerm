# R2 商机负责人异常 V1（合同草案）

Contract-Status: CONTRACT-DRAFT
Schema-Successor: REGISTERED-V900 (52-plus-2-r2-v4; ADR-0017)
Runtime-Activation: NOT-ACTIVATED
R2-Acceptance: NOT_ACCEPTED
R1-Acceptance: PAUSED

本合同承接已批准 T01 范围。事实名、状态、命令及权限已在代码中登记；V900 已按 ADR-0017 生成，并在隔离 PostgreSQL 中验证。真实管理 HTTP、正式命令和观察调度已接线，当前整体证据见[T01验收记录](../evidence/r2/t01-owner-exceptions-report.md)。这些开发验证不表示生产部署、真实权限授予或 R2 发布验收；后台观察默认关闭。E 母版已批准，F 特殊状态已于 2026-09-15 获用户确认，视觉批准不由本合同代行。

关联：[实施计划](../superpowers/plans/2026-09-15-r2-t01-owner-exceptions.md)、[责任交接草案](r2-opportunity-responsibility-handoff-v1.md)、[首次责任](r2-initial-opportunity-responsibility-v1.md)、[调度检查点](r2-opportunity-checkpoint-v1.md)。

## 1. 事实归属与发现范围

Opportunity Owner 拟拥有 `opportunity.owner_exception` 与不可变 `opportunity.owner_exception_disposition`。前者保存异常周期及受控修订，后者保存人工决定。Responsibility 继续独占 Task/Wait/ActionDraft；API 通过公开 Owner 端口组合，不跨域写内部表。Worker 的 checkpoint 只保技术恢复信息，不充当异常事实。

异常观察对象为授权组织范围内未关闭商机的 `OPPORTUNITY_OWNER` 槽，包括尚未建初始任务、当前 OPEN 和 WAITING 责任；已完成进展存在合法后继时观察后继，不能重新激活旧 DONE/CANCELLED 初始任务。来源必须能验证同租户准确商机、来源 Lead/Assignment、CONNECTED_VALID ContactResult 及原联系任务完成引用。来源不一致记录 `SOURCE_INCONSISTENT`，不借转派补造来源或完成事实。

负责人判断使用交接合同的当前有效责任，不改 Opportunity.Owner 或原 Assignment。观察理由的封闭集合为 `OWNER_INACTIVE`、`OWNER_AUTHORITY_MISSING`、`OWNER_DENIED`、`SUPERVISOR_UNRESOLVED`、`SOURCE_INCONSISTENT`。同次观察可记录多个成立理由，按上述固定枚举顺序规范编码；无主管是附加路由故障，不得覆盖真实 Owner 故障。无异常而仅无主管不强行中断正常销售责任。

有界发现以 SERVICE 当前直接授权覆盖的组织范围定位商机；禁止先按有效 ownerAppointments 筛掉失效任职。历史任职归属通过 Identity 具名口读取，用于范围证明，不恢复该任职的权限。无法证明组织归属时不得扩大扫描/披露范围，仅返回受限诊断，不假称已完成该业务的异常登记。

每页1–100扫描记录，固定观察时刻，按创建时间及ID稳定翻页；游标 HMAC 绑定租户、主体、所选任职、扫描种类、观察时刻，五分钟有效。游标位置取最后扫描记录，包含被拒绝或不产生候选的记录。查询只读，正式观察命令重新判断；服务不能通过传入未来时间提前唤醒待办。

## 2. 身份、活动唯一键与状态

异常 UUID 是一个周期身份；准确 Subject 包含类型、ID、revision 与合同要求的摘要。同一 `(tenant_id, opportunity_id, responsibility_slot='OPPORTUNITY_OWNER')` 最多一个活动周期，与理由码、任务ID或发现服务身份无关。ACTIVE 与 COORDINATING 都计入活动唯一约束；理由变化或当前任务变化更新同一周期的准确观察依据，不能产生并排活动异常。

拟保存：商机准确引用、当前有效责任 basis、冻结来源 Owner、当前接收任职、可空任务/等待准确引用、规范理由集合、首次/最近观察时间、修订、状态、最近协调决定引用、复查时间、可空解决依据。准确旧版本不可被“最新”替换而丢失审计依据；物理历史实现由 schema 后继明确。

| 当前状态 | 允许变化 | 事实要求 |
|---|---|---|
| 无活动周期 | ACTIVE | 正式观察确认至少一项业务异常，创建新UUID |
| ACTIVE | ACTIVE | 重复观察更新最近观察/依据，不改首次时间 |
| ACTIVE/COORDINATING | COORDINATING | 有权人工协调决定及未来复查时间；异常仍活动 |
| COORDINATING | ACTIVE | 复查仍异常且既定复查时间已到，保留协调历史 |
| ACTIVE/COORDINATING | RESOLVED | 准确交接事实，或正式复查验证责任有效且未遗留本周期故障 |
| ACTIVE/COORDINATING | NO_LONGER_APPLICABLE | 准确既有商机关闭/合法终止事实，不伪造异常“成功处理” |
| RESOLVED/NO_LONGER_APPLICABLE | 不回开 | 后续再次异常创建新周期、新UUID |

协调到时不自动解决。原人恢复有效可由正式观察命令记录 `OWNER_VALIDATED` 解决依据：数据库观察时间、当前责任 basis、身份/授权复验源与审计引用；必须同时验证本周期所有阻塞已消除。商机关闭仅依据可信已存在关闭事实，不由异常命令关商机。理由未消除、无主管未修复或来源仍坏时不得 RESOLVED。

## 3. 命令与协调

拟命令 `OBSERVE_OPPORTUNITY_OWNER_EXCEPTION` 仅 SERVICE；人工命令 `RECORD_OPPORTUNITY_OWNER_COORDINATION` 与 `TRANSFER_OPPORTUNITY_RESPONSIBILITY` 仅 HUMAN。观察输入绑定准确商机与发现依据；服务端决定当前理由。人工协调输入为异常ID/准确revision、当前责任basis、原因（规范1–2000字符）和复查时刻；复查必须晚于数据库当前时刻。转派输入详见交接合同。用户不能填写 tenant、任意任务头、权限授予或“已完成”标志来改变命令语义。

协调仅形成不可变 disposition（`COORDINATION`），保存操作者、异常准确版本、原因、复查时刻、决定时间与审计。不得据此解决异常、完成销售任务、将 OPEN 变 WAITING、改变原 SLA，或创建一项没有具名业务合同的通用协调任务。下一行动是到时重新观察并在同一异常台账提醒有权主管；本 T01 不自动向外发送消息。

## 4. 主管、候选与运营权限

以下为待注册权限名称，不自动授予任何现有身份：

| 权限 | 范围与允许行为 |
|---|---|
| OPPORTUNITY_OWNER_EXCEPTION_DISCOVER | 当前 SERVICE 任职的直接组织范围授权，用于有界发现及正式观察；R1投影权限不能替代 |
| OPPORTUNITY_OWNER_EXCEPTION_READ | HUMAN 当前所选任职在组织/对象上的列表与详情读取 |
| OPPORTUNITY_OWNER_EXCEPTION_RESOLVE | HUMAN 当前所选任职的协调/交接能力；同时具备所需读取保护 |
| OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ | 运营受限摘要投影；不蕴含完整读取、候选或处置权限 |

主管解析以当前任职、组织范围、具名权限与对象策略为依据，不能按姓名或角色标签自动授予。主管无效/无法解析时异常仍持久化；运营只得其自身授权范围的摘要。对象 DENY 对读取、人工处置和接收人均生效，检查商机、来源、任务、等待、相关处置与结果版本。原人无权不阻断有独立权限主管的异常读取，也不成为放宽任何人的授权理由。

候选须为同租户有效 HUMAN 任职并已具有当前商机上 SALES_OPPORTUNITY_OWNER 权限；主体、组织、任职以及接收后处理所需对象均复验。候选结果是提示，不是资格锁定；列表读取后任职失效/授权撤销/对象DENY/责任变化时，提交必须拒绝并重新获取，不能静默换人或临时 grant。无候选只给协调入口（如有权），不默认主管成为销售负责人。

运营响应采用独立 DTO 允许列表：异常编号、理由码、首次/最近观察时间、活动/终态、受限组织标识与固定修复指引。排除客户名、联系方式、商机正文/标题、进展、原因自由文本、旧草稿、候选任职与可操作控制。不得先加载完整敏感响应再前端隐藏。运营摘要的授权资源是具名受限投影；仍检查其范围与摘要对象 DENY，不能由摘要授权推导完整业务事实读取。查询失败不得返回部分敏感正文。

## 5. 串行、版本与原请求恢复

复用 CommandRuntime 的当前授权、租户业务 fence、身份锁、商机根锁、命令锁、最终复验及提交确认后返回边界。在同一商机根锁内重读活动周期与责任，活动唯一键作为数据库兜底。两个观察服务不得建立重复周期；人工命令期望revision过期即拒绝，不自动覆盖更新后的决定。

观察、协调、交接使用独立命名 V1 scope，同租户/所选身份边界与规范正文摘要绑定。观察稳定键包含扫描种类和准确观察依据；未知结果必须保留原键原输入。相同键同正文返回原回执，同键不同正文冲突。不同键但同一活动异常的重复观察可返回准确 NO_CHANGE 或受控最近观察修订，不另建周期；已解决版本不能被旧请求复活。

返回 SUCCEEDED/NO_CHANGE 均有审计和准确 resultFact；查询回执或重放先验证当前权限，再验证原结果历史准确版本与当前对象的披露策略。状态已推进不重新执行原业务决定。提交结果未知时只核对原结果，不发送新键猜测成功；旧键恢复不能夹带修改后的接收人或协调时间。任职切换、跨租户、原结果DENY均拒绝披露。

## 6. T01 接线与验收

异常发现自身的具名内部路由、SERVICE配置、周期派发、持久化检查点、原请求恢复与启停诊断属于T01，不延期到T02。WORKER仅写技术检查点；业务异常由正式命令事务写入。损坏/未知版本/存储失败停止派发，不清空状态重扫冒充成功。生产启用需完成后端、已批准界面与真实端到端证据，发布门禁另记。

| 场景 | 必须验证的结果 |
|---|---|
| Owner离职/撤权/对象DENY | 有权服务可发现；同一商机槽只有一个活动周期 |
| 连续100条被拒绝 | 扫描位置推进且无越权正文，下一页可达 |
| 无主管 | 异常不丢，运营仅受限摘要；不能直接处置 |
| 协调后到复查时间 | 保留未解决与历史，不造WAITING，不改期限 |
| 候选读取后失效 | 交接失败且无部分写入，要求重新获取 |
| 两主管并发/观察与转派竞争 | 准确revision串行；输家拒绝，无双卡/双解决 |
| 提交成功回包丢失后重启 | 原键原输入恢复原resultFact，无第二处置 |
| 跨租户/跨身份/当前权限撤销 | 列表、详情、候选、回执恢复均不泄漏 |
| 已解决后重新失效 | 新周期保留旧历史，不回开旧周期 |

本文所有验收为待执行要求，不构成实现通过证据。

## 2026-09-15 实现状态

E/F 视觉已确认，T01 正在整体验收。用户重启Docker后，真实PostgreSQL18.6迁移、jOOQ生成、交接/协调/等待接续及具名HTTP测试已进入业务断言。真实React管理组件连接授权HTTP完成转派，数据库核验一条handoff、新人一张OPEN卡、冻结Owner不变、没有伪造进展；终态记录保留为已解决而不是删除历史。Worker持久重启恢复和真实Keycloak管理准入亦有独立测试。最终组合回归及具体计数以[验收记录](../evidence/r2/t01-owner-exceptions-report.md)为准；本文顶部的生产运行启用门禁保持关闭。
Schema登记更新（2026-09-15）：ADR-0017与V900已登记开发实现；三张事实及具名Task/Wait扩展已生成，schema与精确发展投影测试通过。前文“拟”描述保留为命令、授权及生产激活边界；真实数据库验证与完整闭环验收未因此完成。


## 2026-09-15 T01-04 executable read contract

The management surface is `GET /api/v1/opportunity-owner-exceptions`, exact current detail at `/{exceptionId}`, receiver hints at `/{exceptionId}/candidates?expectedRevision=...`, and a separate `/operations` projection. All requests select the current HUMAN appointment; every object and organization is independently checked under QUERY capability and the identity fence. List cursors are HMAC-bound to tenant, principal, appointment, view, and five-minute observation window; the position advances by scanned rows even when all rows are denied. Each scan is bounded to 1-100 rows. Receiver cursors also bind the exact exception revision and detail ETag. Unknown historical organization is not disclosed.

The operations allowlist is exactly exceptionId, reasonCodes, state, firstObservedAt, lastObservedAt, organizationLabel (the current restricted organization unit code), and fixed repairGuidance. The supervisor response adds precise exception/opportunity/basis/task/wait selectors and current allowedActions. READ and RESOLVE remain independent: absence or denial of RESOLVE yields an authorized read-only response and a forbidden candidate query. Existing current facts must still match before an action is advertised. Candidate labels come from current Identity facts and are not authority evidence. The final command rechecks receiver qualification.

Human-readable lead/owner/receiver labels use the named `READ_OPPORTUNITY_OWNER_EXCEPTION` audit action and `R2_OWNER_EXCEPTION_DISCLOSURE_AUDIT_V1` summary schema (profile `R2_OWNER_EXCEPTION_DISCLOSURE_V1`, version 1). Its closed summary fields are profile, version, responseMode, fieldGroups, disclosedSource, authorizationAnchor. The only fieldGroup is OWNER_EXCEPTION_LABELS; the allowed disclosed types are exact lead.lead and Identity principal/appointment/organization selectors, authorized against the exact source Lead with the named exception READ authority. No label is released before audit and transaction commit acknowledgement. Operations summaries do not materialize those labels.

SessionContext adds canManageOwnerExceptions, derived from the selected active direct HUMAN appointment's named READ or OPERATIONS_READ grant; this is an entry hint, not object authorization. Human transfer/coordination bodies retain required explicit null expectedTask/expectedWait fields through raw-byte validation. Unknown fields, non-safe revisions, missing null placeholders, and mismatched selectors fail before command execution. Native receipt recovery and original-key replay remain independently authorized.
