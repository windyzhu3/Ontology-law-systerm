# F10 执行条件与收款：实施及验收记录

当前状态（2026-09-27）：执行条件责任、人工核验、合同激活、待办完成、工作台入口与回执恢复已联通；直接授权路径的实际 HTTP 验收通过，报价路径实际 HTTP 验收亦已通过。产品组件沿用 P 冻结样式。收款仍未接通独立办理命令和责任闭环，P1 补稿与非先款合同收款待办结束口径待用户确认。没有部署到现有验收环境，F10 与 R2 均未完成验收。

下列早期记录及追加日志用于保留验证过程，不代表整项完成。

## 已实现

- `ContractExecutionConditions`：对 Owner 读取的准确版本收款事实计算条件状态；非先款合同不要求虚构到账，部分到账保留差额，足额到账不能替代人工执行条件确认。
- 租户、合同、版本、币种必须一致；同一收款事实不能重复计数；金额使用安全整数最小货币单位；累计溢出拒绝；返回不可变且顺序稳定的事实集合。
- `ContractExecutionSourceReader`：关联当前批准版本、完整签署流程、准确归档和交接，仅返回准确来源元数据。它不授予办理权限，也不写入执行事实。

## 验证

先运行缺失实现测试，再增加实现：`output/f10-conditions-red.log` 和 `output/f10-source-red.log` 均记录缺失类型导致的失败。

`output/f10-conditions-green.log`：条件规则 8、签署流程 6、合同输入 5、架构 13 项通过。`output/f10-source-green.log`：真实数据库集成 1 项通过，24.91 秒；经实际签署归档后读取来源，跨租户不可读，重复读取一致，未生成合同执行事实。该测试沿用已有显式权限测试端口，不能代替真实角色 API 验收。

## 尚未接通

执行条件待办生成/回补、人工核验命令、财务独立责任与收款持久化、执行事实及转案准备、API/Worker/P 版产品页面尚未实现。本批没有部署到本地运行系统，不能据此认定签后断点已经修复或 F10 完成。
`output/f10-source-architecture.log`：来源读取实现加入后再次验证，架构 13 项、条件规则 8 项通过，退出 0。

## 历史恢复输入补充

`output/f10-source-page-red.log` 先记录缺失分页实现；`output/f10-source-page-green.log` 退出 0，真实数据库测试通过。补充租户内 1–100 条的游标分页及不可变交接时间，核对原数据库时间、重复扫描结果、游标排除已读行、跨租户空结果和非法分页边界。未来首次期限从阶段交接时间按既有营业时间规则计算，恢复不得以当前时间重新起算。本批仍未创建执行条件待办或上线页面。

## 2026-09-26 后续实现（尚未部署）

- 签署归档消费、撤权恢复、并发只接续一次、原回执/事件唯一、失败回滚：`f10-handoff-final.log` 6 项通过。
- P 版的首款顺序已接入：先款未足额进入真实 WAITING，未伪造人工核验；部分款继续等待，足额后重开同一任务，原期限不变。`f10-prepay-wake-green.log` 8 项通过，包括来源消费及权限恢复。收款记录由测试夹具提供，仅证明 Contract Owner 消费，不代表财务录入流程已验收。
- 人工确认后，同事务写入受保护条件核验、准确执行事实、合同激活来源、待办完成和 READY_TRANSFER。新增具名 R2 执行守卫，保留旧执行包校验，复核准确审批、签署归档及约定到账条件。`f10-human-execution-green2.log` 3 项通过：非先款成功且无虚构收款、先款等待不可被复选框绕过、缺人工确认拒绝及审计失败全部回滚。
- Context 增加执行责任信息，OpenAPI/TS 增加对应类型；未调整冻结 CSS。人工核验尚待实际 CommandRuntime/HTTP/页面接入验证，财务独立责任与收款命令、F11 转案仍未完成；READY_TRANSFER 是执行事实边界，不是已经创建转案或案件。

以上均在一次性集成数据库验证，运行中的本地系统仍为原 v17。本轮不可据此标记 F10 整体或 R2 发布完成。

2026-09-27 continuation: real imported direct-authorized HTTP chain through execution passes (R2ContractExecutionHttpIT 1, 241.5s; f10-execution-http2.log). Same-record HTTP asserts current workcard, immutable success, same-key replay, GET original receipt, READY_TRANSFER and zero leftover active contract duties. The first run exposed missing CONTRACT_EXECUTION_VERIFY in AuditAppender's Opportunity workcard disclosure allowlist (503); actual sensitive-read regression RED -> GREEN plus runtime replay/event check (1 pass,42.52s). Final contract/session frontend 291 passed (21 files), typecheck exit0. Actual component visual harness CHECK/WAIT/READY x1440/390/360 no horizontal overflow; screenshots inspected, output/playwright/f10-execution. Finance module input + keyed transaction boundary added after missing-type RED: 6 input and4 HMAC tests pass, architecture13 and HTTP boundary7 pass (f10-payment-model-green.log). These do not persist or authorize receipts. P1 high fidelity prepared for actual accepted-material selector, readonly configured account, and RETURN without fabricated receipt fields; frozen CSS4/4 identical and 3viewport/return/proof-presence checks pass. P1 pending explicit user confirmation per prior missing-fidelity constraint; no corresponding finance UI implementation yet. Continue independent execution final checks and finance Owner design. No local deployment, no original Cxx mutation, no full F10/R2 completion claim.

Final named execution batch before independent-finance edge test: f10-execution-final-acceptance.log exit0; handoff14 + source1 + imported quote HTTP1 =16 passed. Quote HTTP 289.1s; direct-authorized HTTP prior241.5s. Both exact same-record chains reach READY_TRANSFER, current card and original receipt. Added a separate non-prepay foreign-currency financial fact test to ensure independent finance is not accidentally promoted to an execution gate; RED running.

Independent-finance boundary verified: f10-independent-finance-red.log reproduced non-prepay handoff failure on an independent foreign-currency receipt attribution fact. Gate checks and SQL manual-execution guard now consult receipts only when the exact approved contract requires prepayment; non-prepay human execution proof carries no receipt prerequisite. Existing financial fact is retained unchanged. f10-independent-finance-green.log full verify exit0, all15 handoff/human/current-card/replay/concurrency/receipt-gate IT pass (175.4s), conditions8 + boundary7 + payment10 unit pass. This does NOT mark the foreign receipt as valid or close any finance duty. V1040 remains unshipped; schema regenerated deterministically, no running local database migration.

2026-09-27 decision update: P1 and per-submitted-evidence non-prepay finance closure are both explicitly user-confirmed. Prior pending-decision entries are historical. No fee-cap-as-receivable inference. Required prepay retains original duty/SLA until threshold; subsequent independent receipts do not reopen completed execution. Execution status-order unit batch exit0 (conditions9, payment inputs6, HMAC4, HTTP boundary7). P1 product form RED missing-component -> GREEN3, still awaiting Owner/runtime integration; not live-deployed.

2026-09-27 P1 implementation checkpoint: PaymentReview.tsx uses existing field/detail-facts/contract-checkbox/ledger-detail-actions classes with no shared CSS changes. RETURN serializes reason alone; CONFIRM requires accepted-material selector/digest, exact minor-unit amount, actual timestamp and manual attribution. Account is readonly display only and is not caller input. Unexpected submission rejection locks repeat financial writes pending owning-card original receipt recovery. Missing-component RED ->3 GREEN; uncertainty RED ->4 GREEN. Combined PaymentReview/ContractCard33 passed, TypeScript exit0 (f10-payment-form-final.log / f10-payment-form-typecheck.log). PaymentReviewOutcome implements user-confirmed per-evidence non-prepay closure, prepay partial retention, threshold-crossing-only wake request and safe totals:5 unit PASS; combined conditions9+input6+HMAC4+closure5=24 PASS in f10-payment-closure-green.log. These are isolated UI/calculation boundaries, not a connected finance service. Next required work: immutable finance review/correction responsibilities and actual confirmation transaction, then runtime/API/current-selected-task integration, atomic audit and receipt recovery, end-to-end/browser acceptance. Do not expose the form before those are connected. P1 and closure decisions no longer need confirmation. Live environment remains v17; no DB migration/deployment this checkpoint.

2026-09-27 Q CONFIRMED: user replied “确认，按 Q 实施”. Q covers F11 submission/independent conflict review/correction/intake/post-intake classification and F10 later-evidence entry. Four P/P1 CSS files byte-identical;33 viewport scenarios (1440/390/360), no JS errors/overflow, return preserves intake actor; desktop review/mobile classification screenshots inspected. Assets frozen in docs/design/r2-sales-mvp/review/2026-09-27-q/approval.json. No further UI confirmation needed within Q. F10 PaymentWorkflowService + immutable payment_request/payment_review/payment_workflow added to unshipped V1040. Real DB four-case gate passed, then six-case owner-recovery/duplicate gate passed (73.11s); original due retained, partial same task, return/supplement no money, rollback atomic. Initial SQL CASE parser issue fixed via parenthesized expression; fresh migration succeeds39. PaymentTaskContractTest1 + outcome5 + Architecture13 pass. Production PaymentWorkflowPorts authority/material composition just added; seven-case actual finance-authority batch running output/f10-payment-real-authority-green.log. No finance API/worker/context/deployment yet; do not mark F10 complete. No live DB mutations or original Cxx changes.

2026-09-27 acceptance checkpoint: R2ContractPaymentHttpIT#imported_direct_request* passed 1/1 (451.0s): actual imported record through distinct sales/authorizer/contract-review/approval/signature/finance credentials; actual generated approved PDF, archive/execution, finance recovery, sales forbidden to confirm money (403), RETURN without fabricated amount, sales supplement, first confirmation, independent later-evidence request and second confirmation. Exactly 2 confirmations/2 requests, one execution and no open finance tasks; original receipts retrieved over HTTP. Evidence output/f10-payment-actual-http.log and fresh failsafe report. Separate finance-incumbent real-authority test passed; ledger/current-card same-duty test passed. Fresh backend 14/14 + Architecture 13 and frontend 119/119, typecheck and deterministic schema/2 Python tests passed. No live deployment or overall release acceptance claimed. Q approved F11 input-contract implementation now started; F11/F12 and browser whole-chain/deployment gates remain.
