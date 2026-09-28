# T09 人工签署核验与归档实施计划

> 使用 superpowers:executing-plans 在现有隔离工作树内逐项执行；先失败测试再实现。保留已有改动，不提交或推送。

**Goal:** 准确批准版本的签字件提交、授权核验、补证、归档和责任接续可以连续办理。
**Architecture:** Contract Owner 继续持有唯一合同事实。签署安排、证据提交和核验决定分别保存；既有材料、回执、审计、任务及后台接续机制复用。签署完成不生成付款、合同执行或案件事实。
**Tech Stack:** 现有 Java/PostgreSQL/React；无新服务或依赖。
**Spec:** ../specs/2026-09-23-r2-manual-signature-design.md；N 高保真已确认。

## Global Constraints

复用现有工作台及 MyTasksControl、BusinessNavigation、冻结公用样式。电子签、到账、合同执行条件判定、转案与建案不在本任务内。R1 PAUSED / R2 NOT_GRANTED。所有有权业务写入沿用原幂等命令、可信时间、事务审计和回执恢复。

## Review Focus

- 旧版本的签字材料不得满足新版本要求。
- 扫描通过、材料接收、核验通过是不同事实；不互相替代。
- 一项核验通过不能遗漏其他必需签署、用印或归档要求。
- 补证保留原材料和意见；正文变更重新审查审批。
- 未知结果恢复原操作；存量 readiness 和责任恢复不得重复创建待办。

## SIGN-01：准确输入与状态规则

Files: 新建 contract/ManualSignaturePlan.java、ManualSignatureProtocol.java 及对应单元测试，后续 owner 与持久化实现复用。

- [x] 用例先固定准确批准版本、显式签署安排、证据版本、核验日期和摘要边界，验证缺项和错误输入失败。
- [x] 实现不可变输入与规范摘要；签字件摘要不必等于未签正文摘要，不能自动推断自由文本要求。
- [x] 固定提交、补证、改版、部分完成、归档和后继等待的状态规则；拒绝跳步与其他版本事实。
- [x] 以针对性测试和既有合同纯规则回归验证，记录每项证据；纯输入通过不等于 Owner 授权或持久化验收。

## SIGN-02：持久化与责任接续

Files: contract/internal/persistence 下签署 Owner；具名追加数据库演进；字段合同、生成清单及基线投影；responsibility/worker 的原接续实现。

- [x] 先以真实 PostgreSQL 用例证明准确版本、不可变证据、CAS、唯一接续及审计失败回滚。
- [x] 复用原 signature_plan / contract_signature 语义，新增提交、核验和工作流事实，不改旧迁移、不覆写已封存参与方。
- [x] readiness 可靠消费及存量补接；无负责人进入责任协调，保留原期限。

## SIGN-03～05：业务命令

Files: Contract 签署 Owner、api 委托/输入/读运行时、contracts/openapi 及准确投影。

- [x] 签署安排确认、草稿、准确已接收材料提交、核验与同版本补证。
- [x] 准确材料受权下载、签署身份和代表权限依据；客户端不能直接声明全部完成。
- [x] 内容变化回原合同修订，新版重走审查审批；不复用旧批准和核验。
- [x] 全部必需签署及归档才形成后继交接；后续尚未实现时不创建不可办理任务。
- [x] 先失败后通过验证撤权、错版本、过期、并发、重放、审计回滚及未知回执。

## SIGN-06：N 页面接入

Files: features/contracts 签署工作卡和 runtime；现有工作台路由、台账及我的待办。

- [x] 复用当前共用组件并映射 N 全部状态，不复制原型导航；必要新增字段先补高保真。
- [x] 行为测试验证单一主操作、人工核验、不可越权切角色、未保存切换及恢复原结果。
- [x] 1440/390/360 布局和真实浏览器业务接续验证。

## SIGN-07：整体验收

- [x] 两种合同来源、存量准备边界、部分签署、补证、正文改版、完整归档及管理入口联调。
- [x] 相关后端与前端测试、基线、构建通过后备份部署；新增合成案例，不改原验收案例。
- [x] 新上下文独立审查，修复重要问题；明确本地验收与发布门禁。

## Execution ledger

2026-09-23：N 已确认并获准实施，沿用 codex/r2-sales-mvp。发现既有 Signing 只有 partySnapshotDigest 与文字 requirements；V980 明确要求现有参与项 signature_required=false，不能静默回填。已向用户询问存量合同签署安排的采集方式；等待期间推进两种方式共同需要的准确输入与状态规则，不改写已批准合同。

2026-09-23 Ruling: 用户已确认销售登记及授权核验的业务方式；采用同版登记补正和真实正文修订两条路径。N1 提供三个字段状态供确认。当前仅完成独立输入/状态规则的开发，Owner、数据库、接口、worker 和真实签署页面尚未接入，不能称为 T09 或 SIGN-01 整体验收完成。

2026-09-23: SIGN-01 基础规则的四项步骤已完成，31 项定向测试通过（16 新增 + 15 原规则回归）；证据 ../../evidence/r2/t09-signature-foundation/README.md。数据库及业务接入尚未完成，因此不将 SIGN-01/T09 整体验收标为完成。N1 新增字段布局仍待确认。

2026-09-23: 用户确认 N1 字段布局。N 与 N1 均为实施依据；保持冻结共用样式与任务范围。该确认不改变既有批准正文及参与方，登记补正必须产生新安排摘要并重新核验。

2026-09-23: N1 对齐补充：后端拒绝重复主体及复用同一参与项指向不同主体；两项测试先失败后通过，最新定向回归 33 项通过。SIGN-02 及后续业务接入仍未完成，未部署。

2026-09-23 Ruling: 用户要求完整实施，使用 subagent-driven-development 分担可独立的 schema 与冻结页面实现；Owner 与接口按明确字段合同同步。仍在当前隔离工作树，不提交或推送。

| 对接项 | 生产与消费 | 决定 |
| --- | --- | --- |
| SIGN-02 / SIGN-03~05 | V990 存储与 Owner 读写 | 不修改 V980 已冻结参与项；新登记按 arrangement 版本隔离 |
| SIGN-03~05 / SIGN-06 | 七个 signing 命令及 signature 上下文 | 保留原合同 envelope，values.expectedSignatureWorkflow 约束新流程 CAS |
| SIGN-02 / SIGN-07 | readiness 消费与 worker | 复用原恢复扫描、幂等回执，新增准确来源类型 |
| SIGN-01 / SIGN-06 | N1 主体、方式、必需项与条款 | 采用已确认 N/N1，不添加视觉体系 |

Ruling: 签署销售操作复用 CONTRACT_PREPARE，核验与归档使用显式 CONTRACT_SIGNATURE_VERIFY；不因有销售权限自动获得核验权限。


2026-09-23 implementation ruling: 律所签署主体只随新批准模板版本绑定准确 Party/profile，不回填存量批准正文或参与方。旧模板缺少绑定时只能显式返回修订；N/N1 继续复用既有模板选择和主体登记字段。无核验人保留 OWNER_EXCEPTION 与原截止时间，合同台账可见；主管使用已有任职与权限管理恢复授权，原恢复扫描自动接续，不扩建新的协调界面。主动推送主管协调待办尚不在当前实现中，不得声称已自动交办主管。

2026-09-23 verification in progress: 独立审查发现的补证错事项、历史丢失、多个合格核验人停滞已修正；补证锁定准确退回事项，历史回归左侧上下文，多个有权人确定性路由。全量前端 76 files/838 tests 通过，新增传输边界与合同定向 56 tests 通过；真实数据库用例继续验收，尚未部署或授予发布验收。

2026-09-23 live verification: 已备份部署 V13，保留原案例，新增 C44～C48 合成案例。真实后台发现恢复回执缺少新 oneOf JSON 绑定、客户端只接受旧准备事实的问题；补齐准确序列化与来源匹配并以失败测试复现后修复。原后台命令以原 key 恢复原 receipt，不重建业务事实。只读发现扫描故障重试上限调整为 60 秒；未知写入的 checkpoint、原 key 和退避保持。C44 的合同台账→统一工作台及 1440/390/360 页面通过；完整签署和异常分支继续验收。


2026-09-23 final local acceptance: SIGN-02 through SIGN-07 completed locally. Evidence: ../../evidence/r2/t09-manual-signature/README.md. Accepted-quote integration verifies actual next-workcard reads after every signing command; direct-authority C44-C48 passed actual API/browser acceptance. C45 completed both signers and archive; C46 supplement, C47 arrangement correction, C48 body revision remain for review. N/N1 shared shell and 1440/390/360 layouts retained. Frontend 76 files / 843 tests, final build and R2 development baseline passed.

Final recovery policy supersedes the interim note: HTTP 5xx retry, including pending delivery, is capped at 60 seconds while preserving original key/page/receipt. Authorization backoff is unchanged. Worker pool follows enabled scan capacity. Exact CONTRACT_SIGNATURE_VERIFY workcard audit authority is registered; embedded contract editing pauses automatic rereads and successful receipts follow only an actionable task belonging to the current appointment. Missing verifier remains an existing authority-admin restoration path, without a new supervisor dispatch feature. No commit or push. R1 PAUSED / R2 NOT_GRANTED unchanged.
