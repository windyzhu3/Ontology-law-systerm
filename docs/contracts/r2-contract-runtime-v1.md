# R2 contract runtime v1：T08 组合协议

状态：IMPLEMENTED_LOCAL_ACCEPTED，2026-09-21。已实现 V980 / 52-plus-2-r2-v12、合同命令与读侧、冻结 E/L 页面及责任接续；本地真实 HTTP、浏览器及原 worker 自动接续已核验，完整证据与全仓回归限制见 `../evidence/r2/2026-09-21-t08-integrated-acceptance.md`；不提升正式发布门禁。第 1—9 节保留设计时的协议拆解，涉及“建议”和“缺口”的表述是历史设计背景，实际实现以第 10 节及 Owner 模型、OpenAPI 为准。签署运行时属于 T09。依据：`../superpowers/specs/2026-09-21-r2-t08-contract-design.md`、对应实施计划、`../baseline/R2-CONTRACT-PREPARATION-V1.md`。

本轮必须覆盖准确报价接受和直接授权两入口、草稿/不可变版本、PRE_CONTRACT 结果、全部配置审批、退回修订，以及真实可追溯的签署接续边界。父任务裁定：签署证据核验属于 T09；T08 不创建无法办理的 OPEN 签署任务。批准后保存准确交接事实、回执和历史，显示 READY_FOR_SIGNATURE / AWAITING_NEXT_STAGE，不冒充已经签署或执行。

## 1. 设计时可复用能力与缺口（历史快照）

| 能力 | 当前入口 | 可复用范围 / 不能推断的能力 |
|---|---|---|
| 准备来源 | `contract/ContractPreparationSources`、`internal/persistence/JdbcContractPreparationSources`；API `R2ContractPreparationSources` | 同一 READ COMMITTED 事务内锁商机、主体、Issue 和证明绑定，核对客户确认、当前责任、商业摘要、历史合法接受或直接批准有效期。它不承担调用者授权、敏感读取审计、合同写入或任务创建。 |
| 准备输入 | `ContractVersionInput`、`ContractPreparationSource` | 精确整数 CNY、条件收费、正文原件及模板/条款版本、签署要求、付款门禁纳入摘要；不是客户端可提交的“已批准”凭证。 |
| 直接申请/决定 | V970 `contract.preparation_request` / `preparation_decision` | 不可变准确来源、前序链、当前客户确认和责任、数据库有效期。不能因此认为 HTTP 或办理任务已经存在。 |
| T06 文件 | `MaterialEvidence`、`MaterialObjectStore`、`EvidenceReferenceReader`、`OpportunityMaterials`，API `R2MaterialsServices` | 私有对象、扫描、准确提交/绑定/版本、授权下载可组合复用；上传仍以准确商机和责任为依据。形成合同时必须额外核对所选字节摘要、目标版本、扫描及未撤回状态。 |
| T07 商业方案 | `R2ContractPreparationSources` 受信任商业适配器 | 解密及核验 QuotePackage 摘要后提取商业条款。合同商业摘要与完整报价摘要不能互换。 |
| 模板/条款 | `ContractVersionInput.Document` 只接收引用 | 当前搜索未发现正式模板/条款注册表、审核服务或文档生成服务。不可把 UI 示例、随机 UUID 或硬编码演示条款写成已审核模板。缺少真实配置时输出 TEMPLATE_UNAVAILABLE，保留准备草稿。 |
| 冲突审查 | 既有 `conflict_review` / `conflict_review_party` / `conflict_finding` | 有准确范围、规则及语料快照和初始/收敛结论结构；未发现可直接调用的 Java 审查执行服务。不能把“没有查询到 Finding”解释为 CLEAR。Finding 的裁决仍归 Responsibility DecisionRecord。 |
| 签署 | 既有 `signature_plan` / `contract_signature` / `contract_execution` 物理模型 | Signature 是已核验证据、身份、授权和正文的事实；Execution 是全部执行门禁已通过的事实。均不能作为 T08 “准备交接”占位记录。 |
| 运行时 | `CommandRuntime`，T07 API/读服务/授权/回执/事件，TaskFactory，投影 Outbox | 复用锁序、精确回执、同步审计、重验、事件幂等。不得简化为服务直接 SQL 后返回成功。 |

## 2. 建议命令、权限及真实责任

以下名称是组合协议建议；API、前端和后端枚举应一次对齐，不做多套别名。新增权限须登记 `IdentityCommands.GRANTABLE` 及相应管理入口，不能自动授予已有销售或同组织所有成员。

| 命令 | 权限 | 输入重点 | 结果与后继 |
|---|---|---|---|
| REQUEST_CONTRACT_PREPARATION | CONTRACT_PREPARE | 当前客户确认、商业条款、理由、准确前次申请 | V970 preparation_request；明确配置决定人责任。保存草稿不执行接管，正式申请在同事务完成有据接管。 |
| RECORD_CONTRACT_PREPARATION_DECISION | CONTRACT_PREPARATION_DECIDE | 准确申请、本人决定任务、APPROVED/RETURNED、原因、可选有效期 | preparation_decision；批准进入统一准备，退回生成补正申请责任。不得伪造 QuoteResponse。 |
| START_CONTRACT_PREPARATION | CONTRACT_PREPARE；自动接续需专用服务权限 | 互斥的准确 ACCEPTED_QUOTE response 或 DIRECT_AUTHORIZATION decision | 按来源唯一创建/接续合同锚点和准备责任；重复事件/命令返回既有准确事实，不新增合同。 |
| SAVE_CONTRACT_DRAFT | CONTRACT_PREPARE | 准确来源、前草稿、内容与文件候选引用 | 保存 Owner 保护草稿；不完成任务、不设置 approved_revision。 |
| FORM_CONTRACT | CONTRACT_PREPARE | 准确前版、确认草稿、正文证据、已审核模板/条款、签署/付款约定 | 封存不可变 contract_revision 及全部子项；生成提交审查责任，无文件或正文摘要不符必须回滚。 |
| REQUEST_CONTRACT_REVIEW | CONTRACT_PREPARE | 当前准确合同版本、范围依赖 | 独立审查申请事实和准确审查人任务；不是完成审查。 |
| RECORD_CONTRACT_REVIEW | CONTRACT_REVIEW | 准确版本、PRE_CONTRACT 范围/规则/语料、真实结论及必要说明 | 本版审查绑定/决定；通过进入审批提交责任，NEED_INFO 形成补充/修订责任，阻断保留业务处置责任。 |
| REQUEST_CONTRACT_APPROVAL | CONTRACT_PREPARE | 本版当前有效审查、准确审批策略版本 | 冻结完整配置成员集并创建各成员审批任务；不能从“同组织任职”推断成员。 |
| RECORD_CONTRACT_DECISION | CONTRACT_APPROVE | 本人精确成员/任务、本版请求、APPROVED/RETURNED、原因 | 单个成员最终决定；全部有效批准才设置批准指针并原子追加签署接续边界；退回取消同请求其余活动审批并形成新版本准备责任。 |

CONTRACT_READ 用于合同正文/历史/下载的独立授权。冲突明细另用专门受控权限（建议 CONFLICT_DETAILS_READ）；销售读取合同不等于能够读取匹配到的其他客户或案件。后台接续服务不能冒用 HUMAN 或取得审批能力。

审批请求可由明确销售责任或明确异常主管办理，授权组织绑定合同业务来源组织，不要求祖先组织主管的 appointment.organization 等于来源组织。具体任职、配置和祖先 scope 都必须经过既有授权检查。

## 3. 建议 HTTP 和 JSON 边界

沿用 `/api/v1/opportunities/{id}/contracts` 读取商机合同入口；`/api/v1/contract-tasks/{taskId}/context` 为本人待办读取；准确版本文档路径含 contractId/versionId。命令按 action 路由到同一 CommandRuntime，必须携带 `Idempotency-Key`，响应保留既有 receiptRef 和 `/api/v1/commands/{id}/receipt` Location。路径提供 opportunityId，正文若重复指定应拒绝。

建议统一命令体（没有的期望值显式 null；不是可以省略校验的通配符）：

```json
{
  "expectedOpportunityRevision": 3,
  "responsibilityBasis": {"id": "uuid", "revision": 0},
  "customerConfirmation": {"id": "uuid", "revision": 0},
  "source": {"kind": "ACCEPTED_QUOTE", "selector": {"id": "uuid", "hash": "base64url"}},
  "expectedContract": {"id": "uuid", "revision": 1},
  "expectedVersion": {"id": "uuid", "hash": "base64url"},
  "expectedDraft": null,
  "expectedWorkflow": {"id": "uuid", "revision": 0},
  "expectedTask": {"id": "uuid", "revision": 0},
  "values": {}
}
```

直接来源 selector 是 preparation_decision 的 `{id,revision}`；申请/决定阶段尚无获批来源时 source 为 null，`values` 必须给准确 expectedRequest/expectedDecision，而不能提供客户端构造的已批准对象。具体命令严格枚举 values 的键和长度。引用类型由所在字段及 kind 决定，拒绝额外 type、rev/hash 混用、非规范 UUID 和非安全整数。正文原件 bodySha256 是十六进制 SHA-256，与 typed Subject 的 base64url hash 编码区分。

建议 context：

```json
{
  "opportunity": {"id": "uuid", "revision": 3},
  "responsibilityBasis": {"id": "uuid", "revision": 0},
  "customerConfirmation": {"id": "uuid", "revision": 0},
  "preparationRequest": null,
  "preparationDecision": null,
  "source": {"kind": "ACCEPTED_QUOTE", "selector": {"id": "uuid", "hash": "base64url"}},
  "contract": {"selector": {"id": "uuid", "revision": 1}, "currentVersion": {"id": "uuid", "hash": "base64url"}},
  "draft": null,
  "workflow": {"selector": {"id": "uuid", "revision": 0}, "stage": "AWAIT_REVIEW", "task": {"id": "uuid", "revision": 0}},
  "review": {"status": "PENDING", "summary": "签约前审查处理中", "selector": null},
  "approvals": [],
  "allowedActions": [],
  "blockers": [],
  "history": [],
  "signatureBoundary": null
}
```

已保存草稿给 `draft.selector/document`，已封存版本给准确版本及原件 metadata，不混用可编辑文档。临时上传失败、TEMPLATE_UNAVAILABLE、REVIEW_REQUIRED、SOURCE_CHANGED、AUTHORITY_UNAVAILABLE 用结构化 blocker；前端以 allowedActions 控制按钮，仍由服务端最终授权。需显示的商业条款、付款和签署要求不折叠隐藏。历史版本只读，不把历史审批显示为当前批准。

销售的 review 仅给当前版本所需 status、受控中性 summary、需要自己补充的字段/要求及受限 selector；不含 matched_fact、其他客户姓名、案件号、对方委托关系、语料正文或 reviewer 内部说明。审批人若无明细权限同样仅看最小结论。

## 4. 事务、授权及敏感披露

1. 严格输入形成 `CommandAuthorizationBinding.Contracts`（建议），`CommandScope.contracts` 规范覆盖机会、责任、客户确认、互斥来源、合同/版本/草稿/工作流/任务及动作范围。不能仅以 opportunityId 复用其他版本回执。
2. 保持业务 fence / 业务根锁先于身份锁；同一租户先商机，再合同、准确来源、材料/主体，稳定 UUID 排序。来源 Resolver 已持有的锁不另开连接或另开事务。
3. 在解密前得到完整 protectedFacts：商机和 lead/assignment/contact 来源、当前责任、客户确认/参与方资料版本、准备请求及决定或报价/包依据/交付/回应及证明链、合同锚点/版本/草稿/正文 EvidenceSubmission/Binding/MaterialVersion、模板/条款版本、审查申请/结果、审批策略/成员/决定、所有关联活动任务/等待/交接事实。决不能漏掉实际保存密文的 basis 行。
4. 正常命令和既有回执恢复均对精确事实集合和当前任务/任职独立授权；逐事实 DENY 生效。确认角色、当前任职、组织层级、期限与单路径四轴授权，不能把代码中 owner==actor 当成权限。
5. 所有新结果事实带 `created_in_transaction=pg_current_xact_id()`，或有等价严格可信的同事务证明；每次新命令事件 metadata 验证准确 result type、来源、actor、业务内容和本次顶层事务。不得使用 xmin 与顶层事务比较，savepoint 子事务会重现 T07 503。回执重放读已有结果，不应要求历史结果属于重放事务。
6. validateBeforeCommit 重读当前头部、责任/客户/来源、正文绑定状态、配置审批成员有效性与数据库当前时间，再次验证全部授权。对有到期的直接准备决定，在写入边界重新 resolve；过去合法接受报价自然到期不抹除来源，但证明撤回、客户/主体变版仍阻断。
7. 读服务复用 QuoteReadRuntime 的 QUERY + lockedReadScope + AUDIT 同事务模式，但注册合同专用披露合同。解密后再次核对精确 protectedFacts **集合**、任务 selector/owner 和授权，提交同步审计后才返回；集合不因 SQL 无序误判，但任何事实新增/替换必须 stale。
8. 下载只返回准确对象字节，no-store、nosniff、正确 MIME/文件名；不把下载当签署、发送或审查。审计失败不得输出字节。候选文件未扫描/未接收时不提供已形成版本下载。

## 5. 准确版本、审批及签署边界

- 同一合法来源接续唯一锚点，版本号连续、直接前序唯一。形成新版本原子更新 current_revision 并清除旧 approved_revision；旧正文、审查、决定和任务保留历史。已执行合同不继续修订。
- 准备版本不带虚构审查：V980 具名区分准备正文与后置审查绑定；签署接续须当前版本的有效 PRE_CONTRACT scope 与全部批准。不要只放松原非空约束而保留绕过批准的空值路径。
- 审查范围至少覆盖准确版本/法律需求、全部参与方资料版本和角色、规则及语料版本/摘要。NEED_INFO 不等于 Finding 裁决；原模型 FINDINGS->BLOCKED/WAIVED 保持准确决定集合及 scopeHash。未落地真实规则/语料协议时明确阻断，禁止合成零命中 CLEAR。
- 审批策略含精确配置版本及显式任职/authority slot 集合；版本形成时冻结必需槽；审查通过后另建准确 revision_approval_request，绑定本版及 review_binding，请求事务明确分配任职/任务。所有要求决定齐备、目前仍有权，才成立准确批准集合摘要。审批人退出/失权不得视作已批准或自动移除；形成具名主管恢复责任或可恢复异常状态，待修复后按新请求/新版本协议处理。
- 修订正文、模板/条款、客户参与方、签署要求、付款门禁或商业条款均重算摘要与依赖。商业内容改变必须得到新的准备来源授权/接受，不能沿用旧 direct decision。旧 review/approval 只可按已登记准确复用规则复用，本轮没有规则即重新办理。
- 最终批准事务写入真实签署接续事实，绑定 contract/version、review selector/scope/resolution、approval set、owner/basis、形成时间和状态 AWAITING_NEXT_STAGE；回执和历史可读取，UI 展示“审批完成，等待签署阶段接续”。不存在可办理签署协议时不创建 OPEN task，不显示伪造签署成功或下载签字按钮。
- 未来 T09 对这条准确交接幂等消费，按实际协议建立任务，失败有持久化原因、责任和重试依据；现在的自然等待与技术失败应分别标识。该边界不是 contract_signature / contract_execution / payment_confirmation。
- 付款门禁严格来自准确合同约定；不需要预先到账则无 FIRST_PAYMENT 阻断，不制造 SATISFIED 零元到账，也不改成风险豁免。T08 不生成案件类型或转案完成事实。

## 6. 责任、历史接续与集成热点

TaskFactory 必须显式注册各合同 task 的 command/authority/completionType/subjectType。当前 `subjectType()` 以 `ordinal()>PROGRESS_OPPORTUNITY` 推导 opportunity；不要直接追加 Contract enum 后误把 contract subject 校验成 opportunity。可在 API 边界统一用商机 subject 贯穿本轮，但合同版本/任务来源仍必须精确绑定，且应明确记录该选择。

准备申请/审批任务可保持商机 subject；合同成立后的准备/审查/审批适合 contract subject。两种选择不能混用 task lookup，更不能取消同商机的无关审批卡。正式接管取消原活动销售责任时保留准确原因、依据及原 SLA；草稿不接管。阶段新责任有自身 SLA，交接同一责任不得重置原 SLA。

报价接受的未来事件和历史未接续 accepted source 必须汇入同一幂等 START 逻辑；不能依赖用户再次提交 ACCEPTED 或打开页面写库。读取发现候选只做发现，受服务能力保护的维护命令才创建锚点/任务。唯一来源约束 + 同根锁 + 幂等 command/event key 同时保证重投不重复。直接批准的后继尽可能同事务创建；技术异步才使用现有 Outbox。

如果安排真实 timed wait，则复用已有 discovery/keyset/checkpoint/worker 和 exact wait CAS，保留源事实 hash、due、task revision 与原 SLA。前端刷新和日历到期不是可写授权。不创建没有恢复 handler 的新 profile。

| 范围 | 现有代码热点 |
|---|---|
| 输入/HTTP/组合 | `api/R2QuoteInput`、`R2QuoteApiDelegate`、`R2QuoteCommand`、`R2QuoteServices`、`R1ApiServices`、`R2OpportunityCommandRuntime`；新增 Contract Owner 公开 service/metadata，API 注入跨 Owner ports，避免 contract 直接依赖 opportunity 内部实现。 |
| 命令注册与授权 | `execution/CommandEnvelope`、`CommandAuthorizationBinding`、`CommandScope`、`R1CommandPolicy`、`R1AuthorizationFacts` 及 runtime adapter；`identity/IdentityCommands` grant 清单。 |
| 回执及事件 | `R1ReceiptAuthorizationPolicy`、`audit/ReceiptRecoveryMetadata`、`CommandHandler.Event`、`R1EventPolicy`、`R1EventFacts`、准确 metadata reader、typedReferenceRegistry 的 result/completion/event/audit/object slots。 |
| 敏感读 | `api/R2QuoteReadService`、`execution/QuoteReadRuntime`、`AuditAppender` 及 SQL 审计合同白名单；合同权限/日志类型单独登记。 |
| 待办/台账 | `responsibility/TaskFactory`、JooqTaskRepository、CurrentTaskReader、`query/OpportunityWorkCardQuery` / 当前卡路由、`api/R2QuoteLedgerProjection` 作为模式；不可将 contains("QUOTE") 式分类套到所有新任务。 |
| 发现/恢复 | `api/R2OpportunityDiscoveryService`、`R2OpportunityRecoveryCommand`、OwnerException 读写/发现、worker 调度及 checkpoint；没有新 due wait 就不复制一套空调度。 |
| 资料/文件 | `api/R2MaterialsServices`、`evidence/MaterialEvidence`、`EvidenceReferenceReader`、`MaterialObjectStore`；生成器不能直接跳过真实扫描、准确绑定及已审核模板。 |
| 模型/门禁 | V980 generator、typed registry、runtime-validation、部署 schema expectations、migration fixture count、历史精确投影和各原版本守卫。 |

## 7. 交付前的必要反例

双入口互斥和跨租户；未批准/退回/过期直接来源；历史合法接受已自然过期；证据撤回/Party变版/客户确认后继；伪造模板和正文 SHA；同来源并发 START；草稿不接管；形成版本失败事务回滚；普通销售不能记录审查或批准；多成员只完成部分不能签署接续；退回后旧审批不能复用；形成新版本不改旧正文；审查补充和阻断均有真实后继；缺审批人/失权可恢复；准确 DENY 阻止解密和回执读取；读取期间事实变化不泄露旧正文；事件结果不是本事务/错误来源被拒绝；命令 savepoint 下完整 HTTP 成功及同键重放；审计失败不返回文档；最终 boundary 真实存在且不存在无法办理的签署 OPEN task。


## 8. V980 Owner 对齐（实施中）

已与字段合同 Owner 对齐的事实名：`contract.preparation_draft`、`template_version`、`clause_version`、`revision_clause`、`revision_review_request`、`revision_review_decision`、`revision_review_binding`、`revision_approval_requirement`、`revision_approval_request`、`revision_approval_decision`、`signature_readiness`。新独立不可变事实采用自身 `<table>_id`、revision=0 和顶层事务标记；合同正文唯一真源继续是 `contract.contract_revision`（revision_no / content_digest），合同锚点继续是 `contract.contract`。

关键裁定：审批 requirement 在版本形成时封存，REQUEST_CONTRACT_APPROVAL 必须形成新的 `revision_approval_request`（准确版本、review_binding、实际申请任职、顶层事务）。不得把既有 requirement 或既有合同版本作为“本命令新建”的结果事实。最终 approval 决定的结果仍是准确 decision；同事务 signature_readiness 由完整集合派生，state=READY_FOR_SIGNATURE，界面将其呈现为等待下一阶段接续。


## 9. 纯 Java 注册协议

`backend/src/main/java/io/github/windyzhu3/ontologylaw/contract/ContractWorkflowProtocol.java` 提供公开 `Action`、`Stage`、`ReviewOutcome`、`Decision` 枚举及 `authority(Action)`、`resultType(Action)`、`event(Action)`、`requireAllowed(Stage,Action)`、`after(Stage,Action,ReviewOutcome,Decision,boolean allRequiredApproved)`。这是静态顺序校验，不能代替任职、来源或审批事实验证。`allRequiredApproved` 只能由服务器对当前准确配置/决定集合最终复验得出，不是客户端字段。

| Action | 精确结果类型 | 事件代码 |
|---|---|---|
| REQUEST_CONTRACT_PREPARATION | contract.preparation_request | ContractPreparationRequestedV1 |
| RECORD_CONTRACT_PREPARATION_DECISION | contract.preparation_decision | ContractPreparationDecisionRecordedV1 |
| START_CONTRACT_PREPARATION | contract.contract | ContractPreparationStartedV1 |
| SAVE_CONTRACT_DRAFT | contract.preparation_draft | ContractDraftSavedV1 |
| FORM_CONTRACT | contract.contract_revision | ContractFormedV1 |
| REQUEST_CONTRACT_REVIEW | contract.revision_review_request | ContractReviewRequestedV1 |
| RECORD_CONTRACT_REVIEW | contract.revision_review_decision | ContractReviewRecordedV1 |
| REQUEST_CONTRACT_APPROVAL | contract.revision_approval_request | ContractApprovalRequestedV1 |
| RECORD_CONTRACT_DECISION | contract.revision_approval_decision | ContractDecisionRecordedV1 |

阶段集合：INITIAL、PREPARATION_REQUESTED、PREPARATION_RETURNED、SOURCE_APPROVED、PREPARE、FORMED、AWAIT_REVIEW、NEED_INFO、BLOCKED、REVIEW_PASSED、AWAIT_APPROVAL、RETURNED、READY_FOR_SIGNATURE。保存草稿始终保持阶段，但服务仍可因 actor、来源、任务或执行状态拒绝保存。纯协议枚举曾预留批准后修订语义；本次 T08 冻结页面与 Owner 运行时在 READY_FOR_SIGNATURE 只读，不开放批准后修订命令。readiness 只属于准确版本，T09 后续消费必须复验 current_revision。直接申请批准仅到 SOURCE_APPROVED，统一 START 后才到 PREPARE。记录审查必须明确结果，记录决定必须明确 APPROVED/RETURNED；不能给无关命令附带 CLEAR 或 allRequiredApproved。单人批准但集合未齐备仍为 AWAIT_APPROVAL。

补充审查裁定：NEED_INFO 仅补充解释或材料且合同正文/客户依赖不变时，允许对同一准确版本再次 REQUEST_CONTRACT_REVIEW，形成以 prior NEED_INFO 请求为前序的新 request，再进入 AWAIT_REVIEW；这是新审查，不覆写旧结论。正文、主体或客户确认变化必须先 FORM 新版。BLOCKED 不能借补充请求直接重开；需要真实修订或既有独立处置协议。


## 10. T08 实际审查边界

本次绑定 `R2_PARTY_ID_ROLE_CONFLICT_V1` 保守准确身份规则：对本版全部合同参与方冻结来源、Party 修订、快照摘要与角色；完整读取同租户其他商机最新参与集合，以及其他商机的全部历史合同参与集合。后者保留已执行、已转案和已终止合同上下文，避免因关闭或移交丢失历史冲突线索。另读取 transfer_request 的已接收快照及 matter 标识进入语料摘要。当前数据库没有独立 matter/case 参与表；不能宣称覆盖不存在的外部案件库。

仅准确 Party ID 在 CLIENT 与 OPPONENT 对立角色之间匹配才产生候选；同角色复购不构成此规则候选。范围中 OTHER/SIGNER 或同一身份在语料中有未确定的 OTHER/SIGNER 角色时属于资料不足，禁止 CLEAR，须补充明确角色。此规则不是完整的模糊名称、别名、关联关系或法律冲突引擎。未知对方、未解析参与方或不完整范围只能 NEED_INFO。CLEAR 仅能由有权人工显式提交，且本次完整语料计算零候选。真实候选禁止 CLEAR；明确 BLOCKED 写入真实 Finding，以及 Responsibility 所有的准确 Finding 阻断 DecisionRecord，随后归并审查结果。T08 不提供自动 WAIVED 或未经逐项授权的豁免。

完整扫描复用 CommandRuntime 在任何根锁之前取得的租户 exclusive business fence，阻止并行业务语料命令直至提交；持久化实际 scope/rule/corpus SHA-256，不能用零摘要或空 Finding 占位通过。review decision 的准确版本、请求、审查、scope 和 resolution 与 V980 守卫一致。客户与文档正文由公开 Owner 端口解密，禁止跨域复制密钥语义。

HTTP 输入 `expectedVersion.hash` 可接受规范小写十六进制，命令授权 scope 统一成 base64url 32-byte digest；公开 CONTRACT_REVISION 回执也使用 digest，而不是伪 revision=0。其他八类命令结果使用准确 revision。文档下载返回服务器私有材料字节，附件/no-store/nosniff，不形成签署或送达事实。

### 内部责任恢复来源

`RECONCILE_CONTRACT_PREPARATION` 仅允许既有 SERVICE 的 `CONTRACT_TASK_RECOVER`，请求必须携带 `sourceKind`。`ACCEPTED_QUOTE` 指向 revision 0 的 `opportunity.contract_preparation_source`；`AUTHORITY_RETURN` 指向 revision 0 的当前 `contract.preparation_workflow`，且必须与 `expectedWorkflow` 完全相同。命令作用域和幂等键包括该模式，不能用一种来源冒充另一种。候选分页按商机 UUID 推进，V2 游标在过滤空页时也推进原始页位置。

独立审查/批准任职失权的处理是取消准确失效任务并形成恢复工作流，不生成审查或批准决定，不改派已冻结批准人。有权销售进入 DIRECT_RETURNED/RETURNED 重新办理；销售也失权时进入无虚假 OPEN 任务的 OWNER_EXCEPTION，并保留准确恢复阶段。恢复后的新版本仍须真实审查和全部批准。

