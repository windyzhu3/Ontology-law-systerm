# R2 销售链路闭环修复实施计划

> **For agentic workers:** 使用 superpowers:executing-plans 在现有工作树按任务实施。每个任务独立验证和留证；保留原改动，不提交或推送。新交互高保真确认前不实施对应 UI。

**Goal:** 修复 D01～D08、D10 的责任衔接与退出缺口，并独立交付 D09 的签后执行条件、转案与接收后分类，使每次业务动作都有可解释、可恢复的后继或合法终点。

**Architecture:** 继续由既有领域拥有事实，受控命令组合任务接管；查询投影从准确业务事实推导当前阶段和有权下一行动。修复优先覆盖后台和命令守卫，再处理显示、交互和存量，不以隐藏旧卡代替业务收口。

**Tech Stack:** 既有 Java/PostgreSQL/React/OpenAPI/Worker；不引入通用流程引擎、Redis 或新服务。

**Spec:** [闭环修复设计](../specs/2026-09-23-r2-sales-chain-closure-repair-design.md)；上位依据为 [已批准 R2 范围](2026-09-13-r2-complete-sales-mvp-plan.md)。

**Status:** EXECUTING（2026-09-27）。F01～F08既有修复保留；F09～F11的主业务链、收款和转案已接入本地v19。F12四种来源/付款条件的完整HTTP矩阵4/4通过，真实登录、台账及执行/财务工作卡页面接入通过。分类纠正入口Q1已确认、实现并部署，真实HTTP更正链路通过，完整浏览器业务写入/重登录与R2后续管理/AI收口仍未完成。详见[矩阵续验](../../evidence/r2/2026-09-27-sales-mvp-matrix/README.md)及 `.superpowers/sdd/2026-09-23-r2-sales-chain-closure-repair/progress.md`。

## Global Constraints

- 工作目录 `C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r2-sales`；保留既有改动，禁止重置、混入无关重构。
- R1 PAUSED / R2 NOT_GRANTED；任务通过不改变发布验收。
- 旧迁移不修改；新增事实、命令、任务、权限、回执、事件、OpenAPI 及基线投影具名追加并同步生成。当前已观察到 V990，执行新增迁移前重新核对最高版本。
- 一个负责人、一个业务目的、一个固定主命令；MyTasksControl、BusinessNavigation 和冻结母版保持。
- 范围内新交互先高保真确认；不新增主题、导航体系、流程驾驶舱或通用规则编排。
- 幂等、准确版本、授权重验、可信时间、事务审计及原回执恢复不可省略；旧事实不篡改。
- 未签销售终止；已有签署或签字件待核验走主管核对；停止办理不表示解除合同。
- 来源请求确认后回到主管调配；不实现来源全局启停。
- 合同明确先款才阻断；财务事实独立；案管接收后生成案件，再分类。

## Review Focus

1. 补建扫描与报价/合同接管并发：无早到/晚到旧任务；F02/F08 验证。
2. 多审批人、独立财务责任：允许合法并行，不按总任务数粗暴取消；F02/F06/F10 验证。
3. 终止与批准、签署核验同时提交：以准确锁与版本决定唯一结果，不能先停止后继续签署；F06 验证。
4. 只填草稿、维护资料、无回复及未知结果：不伪造进展、不丢原责任、不重复命令；F04/F05/F09 验证。
5. 已签、已执行、已转案历史及责任人撤权：不以关闭界面消灭义务或越权转案；F06/F10/F11 验证。

## 总体顺序及交付门

`F01 → F02 → F03 → F04 → F05 → F06 → F07 → F08 → F09 → F10 → F11 → F12`

F01 中高保真制作可与 F02/F03 的既有行为缺陷修复并行推进，但本计划默认由当前任务顺序执行，不另开用户任务。F04～F07 对应 UI 依赖高保真确认。F10/F11 各自再交付范围内高保真，不把整个 R2 页面一次性重画。

| 阶段 | 任务 | 对应问题 | 阶段交付 |
| --- | --- | --- | --- |
| 修复基础与防错 | F01～F03 | D05/D06/D07/D08，统一规则 | 不再补错卡；同一业务各入口显示一致 |
| 补齐实际办理路径 | F04～F07 | D01/D02/D03/D04/D10 | 能推进、能等、能退回、能结束 |
| 存量与签前/签署复验 | F08～F09 | D01～D08/D10 | 存量可继续，导入至归档两条真实链通过 |
| 签后业务交付 | F10～F12 | D09 | 执行条件、转案接收、唯一案件与分类闭环 |

## F01：冻结前后依赖表与补充高保真

**交付：** 每个结果的前置、后继、负责人、期限与终点明确；新交互有可审阅画面。

**Files:** 本设计/计划；`docs/design/r2-sales-mvp/review/2026-09-23-closure/README.md` 与 `index.html`（实施本任务时新建）；`docs/design/r2-sales-mvp/review/2026-09-23-closure/transition-matrix.md`（新建）。参考 N/N1 及既有商机、报价、管理母版，不改冻结原稿。

**输入/输出：** 输入 D01～D10 与 Q1/Q2；输出具名分支表及 F04～F07 的高保真。分支表包含“前置事实、固定命令、旧任务处置、下一负责人、到期规则、权限异常、终点”七列；不另建可编辑业务阶段。

- [x] 填齐导入/去重/补齐/分配、首联重试/有效/无效、商机推进、报价双向结果、合同修订/终止、签署补证/归档、来源请求的分支表。
- [x] 画出 C06 的推进入口、资料缺项与返回、无回复安排、报价结束、未签合同结束、有签署事实主管核对、来源调配三个结果；均含等待、失败、只读、无负责人及 1440/390/360。
- [x] 明确浏览器点击入口不构成责任完成；草稿保存不触发接管；跨角色结果只显示交接不切身份。
- [x] 按分支表逐项人工审查：不得有只写“成功”却无下一责任/终点的格子。高保真经用户确认后解锁对应 UI。

## F02：阻断错误补建及重复业务主线（P0）

**对应：** D05、D06。依赖 F01 的责任规则，不依赖新增 UI。

**Files:** `backend/src/main/java/io/github/windyzhu3/ontologylaw/api/R2OpportunityDiscoveryService.java`、`R2OpportunityActivationCommand.java`；`opportunity/internal/persistence/JooqOpportunityActivationCandidates.java`、`JooqOpportunityTaskActivationService.java`、`JdbcQuoteWorkflowService.java`；`contract/OpportunityContractReader.java` 及实现；`responsibility/internal/persistence/JooqTaskRepository.java`。上述缩写包路径均位于同一 Java 根目录。

**输入/输出：** 从 Quote/Contract 公共 Owner 读口取得准确后续事实。发现只是候选，正式命令再次检查。输出“不应创建”结果或准确现有责任；不能通过把商机 closed_at 改掉来阻止扫描。

- [x] 在 `R2OpportunityDiscoveryIT`、`R2OpportunityActivationCommandIT` 增加失败用例：先进入合同、尚无首个普通跟进、再运行补建；结果应无新增跟进。覆盖扫描先读后合同提交、两个后台重复消费及重启。
- [x] 在 `R2QuoteWorkflowIT` 增加直接合同准备/审批/签署/归档阶段重新报价的失败用例，要求读侧不开放、写侧拒绝，旧合法任务不被取消。
- [x] 在发现、命令及事务边界落实接管守卫；保留正式报价/准确直接授权两条合法入口；合同收费变化走既有修订/重新授权。
- [x] 重放上述案例并检查：无新增旧卡、无丢失合同责任、无重复事实。多审批人用例必须保持全部合法审批任务。

## F03：统一当前责任、结束上下文与提交后接续（P0）

**对应：** D07、D08。依赖 F02。

**Files:** `api/R2OpportunityLedgerReadService.java`、`R2QuoteLedgerProjection.java`、`R2OpportunityClosureReadService.java`、`R2OpportunityOwnerExceptionAssembly.java`、`R2ContractReadService.java`；`responsibility/internal/persistence/JooqOpportunityTaskClosure.java`；`apps/workbench/src/App.tsx`、`features/opportunities/OpportunityLedgerPage.tsx`、`QuoteCard.tsx`、`features/contracts/ContractRuntimeCard.tsx`、`features/workcard/useCurrentCard.ts`。

**输入/输出：** 同一准确业务快照投影“当前领域、待办、负责人、允许动作、阻塞或终点”；领域内仍各自写事实。结束上下文明确区分无可关闭普通任务、合同接管、真正版本冲突，不拿不同任务集合比较后假报陈旧。

- [x] 在 `R2OpportunityClosureReadIT` 复现 C30/C32/C39 结构，读取应返回准确阻断/交接状态而非稳定 412；真正并发版本变化仍拒绝。
- [x] 在 `OpportunityLedgerPage.test.tsx`、`QuoteCard.test.tsx`、`ContractRuntimeCard.test.tsx` 覆盖报价接受已有合同准备、合同审核交给别人、签署完成无下一本任职任务。
- [x] 去掉过时“合同功能未开放”提示；各入口指向同一个有权工作台。成功后只接续本任职有权任务，否则显示交接人/等待原因及我的待办。
- [x] 核对页刷新、重新登录、原回执恢复仍指向同一业务事实，失权清空，编辑中不被定时刷新带走。

## F04：补齐商机向报价/直接合同/结束的连续入口

**对应：** D01。依赖 F03 及对应高保真确认。

**Files:** `features/workcard/CurrentCard.tsx`、`OpportunityProgressForm.tsx`；`features/opportunities/CustomerRequirementsCard.tsx`、`MaterialsCard.tsx`、`QuoteCard.tsx`；Opportunity 业务命令、Owner 及 `api/QuoteWorkflowPorts.java`、`ContractWorkflowPorts.java`；`contracts/openapi/ontology-law-api.yaml` 与生成投影。

**输入/输出：** 输入准确商机、客户确认、当前任务及版本；明确的“开始报价准备”意图产生具名事实及 PREPARE_QUOTE 后继（正式命令名/回执随 F01 字段合同登记）。直接合同仍走现有准确授权申请。只读入口与写入意图严格分开。

- [x] 先固定失败场景：资料未齐指向准确缺项；补齐返回原业务；资料齐备可进入报价/直接授权；普通跟进只在后续准备事实已成功形成后被接管。
- [x] 保留原记录进展主命令，使用冻结次要入口进入对应准备卡。不同业务命令不塞成一个万能“完成”按钮。
- [x] 客户确认和材料接收仅返回上下文及允许后继，不自动生成报价、合同或假有效进展。继续跟进仍需下一时间；切入正式后续不强填虚假的下次时间。
- [x] 通过 `OpportunityProgressIT`、`R2QuoteWorkflowIT`、`R2ContractWorkflowPersistenceIT` 与对应前端测试验证两条入口、放弃编辑、草稿恢复、缺权限和原回执恢复。

## F05：补齐未接通/未回复的跟进安排

**对应：** D02。依赖 F04 及对应高保真确认。

**Files:** `opportunity/OpportunityProgressInput.java`、`OpportunityProgressService.java` 及持久化实现；新增 `opportunity/OpportunityFollowupAttempt.java` 及具名存储/命令适配；`api/R2OpportunityProgressServices.java`、`R2OpportunityDiscoveryService.java`；报价 Owner 及 `worker/R2OpportunityTaskScheduler.java`；商机/报价卡及传输合同。

**输入/输出：** 将“真实有效进展”“联系尝试/尚无回复”“下次行动安排”分开。联系尝试不进入有效进展统计，不能变成 QuoteResponse 的接受/拒绝；保存准确来源和时间，旧本次责任完成后创建一项后继等待。

- [x] 新增 `OpportunityFollowupAttemptIT`：无有效进展也可记录尝试并等待；有效进展计数不增加；下一时间必须未来，重复提交只有一个后继。
- [x] 新增报价到期仍无回复用例：没有客户回复证据时仍可如实安排跟进，不制造 NOT_ACCEPTED/REJECTED。
- [x] Worker 到期恢复、提前主动安排、撤权、负责人变更及时区/工作日边界与既有规则一致；调整约定不能覆盖原 SLA 或抹掉逾期历史。
- [x] 页面沿用同一表单风格和固定业务目的，输入随真实结果显示；刷新后本次尝试、等待原因、责任人和下次时间可查。

## F06：报价/合同的终止及主管处置

**对应：** D03、D04。依赖 F02～F05 及对应高保真确认。

**Files:** `opportunity/internal/persistence/JooqOpportunityClosureService.java`、`JdbcQuoteWorkflowService.java`；`contract/ContractWorkflowProtocol.java`、`contract/internal/persistence/JdbcContractWorkflowService.java`、`JdbcManualSignatureWorkflow.java`；各 Owner 的 termination 输入/事实/命令；`responsibility/OpportunityTaskClosure.java`；`features/opportunities/OpportunityClosure.tsx`、`QuoteCard.tsx` 与合同卡。

**输入/输出：** 具名终止事实保留原因、准确版本、操作者和时间；取消未完成相关责任并收口整个主线，终止不是删除合同。历史批准、客户接受、签署、凭证均保留。

- [x] `R2OpportunityClosureDownstreamIT`、`R2OpportunityClosureConcurrencyIT` 及 `R2QuoteTermination*IT` 覆盖报价待审批、退回、待回复、明确拒绝及已接受但未被合同接管。存在合同则交合同 Owner，不从报价侧绕过。
- [x] 新增 `R2SalesTerminationIT`：未签合同销售结束；签字材料待核验/部分签署/全部签署请求主管核对；无主管进入可见责任异常。主管可以退回继续或确认停止本次办理；已执行/转案事实不被自动撤销。
- [x] 与报价批准、合同批准、签署核验并发，锁内核对准确版本；同一主线不能同时“已终止”和继续产生后续成功事实。
- [x] 所有终止取消有明确事实依据；已完成任务不改回未完成，不将取消标成有效进展。恢复继续时建新责任、保留原期限历史，不重开旧任务。
- [x] 受控下载、只读历史、团队逾期和统计同步反映终点；主管处置待办不通过偷偷扩大销售权限实现。

## F07：来源请求后的线索去向

**对应：** D10。依赖 F01/F03 及对应高保真确认。

**Files:** `lead/LeadCommands.java`、`LeadInputs.java`、`AssignmentPolicy.java`；`responsibility/TaskFactory.java`、`WaitLifecycleService.java`；`query/CurrentWorkCardForms.java`；原工作卡表单、线索录入结果及原 R1 事件/回执注册。

**输入/输出：** ACK 请求完成后形成一项主管路由责任。三个合法结果为继续分配、约时复查、明确结束线索。来源全局配置不变，不把请求回执当线索终点。

- [x] 新增 `SourceRequestContinuationIT`：ACK 后存在唯一主管任务；重放不多建；有效候选分配后进入首联；无候选不能假分配。
- [x] 复查等待由既有 Worker 恢复；明确结束保存原因及终点、不再恢复首联；主管缺失显示既有管理配置异常。
- [x] 覆盖既有已 ACK 而无后继记录的发现/回补，只有准确未终止且未被后续接管者可补，纳入 F08 只读清单。
- [x] 重跑原首联有效、两次自动重试、主管复核结束/复联、重复关联、补齐及分配用例，避免修复 D10 破坏已通路径。

## F08：存量任务受控修复

**对应：** D05、D08、D10 的存量。依赖 F02/F03/F07 防再生能力完成。

**Files:** 新增 `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R2SalesChainRepairIT.java`；对应受控维修命令及 Owner 读写口；新增 `docs/evidence/r2/sales-chain-repair/` 记录候选、已处理、因版本变化跳过的脱敏结果。查询不得导出客户正文。

- [x] 备份本地业务库、材料与运行信息，生成只读候选；初始 C41～C47 仅为验收样本，发现按真实事实，不按 case 名称匹配。
- [x] 每项修复要求后续事实、普通任务、负责人及修订号准确相符；仅取消确被接管的错误普通任务，不删历史、不伪造进展、不取消合同/审批/财务任务。
- [x] CAS 变化或有人正在处理则跳过并报告，不以覆盖更新追求候选数清零。存在合法独立任务允许保留；每项保留理由。
- [x] 修复重复执行和 Worker 重启后不再生；检验 C45 只显示已完成签署和真实后继状态，不能再推荐普通联系。

只读候选核对 SQL（仅发现，不能作为直接更新脚本）：

```sql
BEGIN READ ONLY;
SELECT t.tenant_id, t.subject_id, t.task_occurrence_id,
       t.revision, t.state, c.contract_id
FROM responsibility.task_occurrence t
JOIN contract.contract c
  ON c.tenant_id = t.tenant_id AND c.opportunity_id = t.subject_id
WHERE t.subject_type = 'opportunity.opportunity'
  AND t.business_purpose_code = 'PROGRESS_OPPORTUNITY'
  AND t.state IN ('OPEN', 'WAITING');
COMMIT;
```

2026-09-27 补充验收：来源责任按准确业务前因与当前有效授权接续；26项定向回归、13项架构检查通过。存量受控修复后0普通残留/0来源遗漏，原回执重放一致；Worker重启复扫仍为0/0。真实主管页面1440/390/360通过，原期限保留；未调整授权、来源组织范围或测试账号绑定。证据见 `docs/evidence/r2/2026-09-27-repair-final-acceptance/README.md`。

## F09：导入至签署归档的整链验收

依赖 F02～F08。新增业务记录用于走全链；保留原人工验收案例状态，只有 F08 明确错误任务作有审计的纠正。

**Files:** 新增 `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R2SalesChainClosureIT.java`；沿用真实浏览器验收框架；`docs/evidence/r2/sales-chain-repair/README.md`。

- [ ] CSV/XLSX 输入 → 错误行修正/结果未知恢复 → 去重/补齐/分配 → 首联有效 → 商机；不能从预置商机开始替代该链。
- [ ] 报价线：资料确认 → 发起报价 → 审批/交付/客户接受 → 合同生成/审查/审批 → 双方签署核验/归档。
- [ ] 直接线：资料确认 → 准确授权 → 同一合同审批签署链；无伪造报价接受，无签前案件分类。
- [ ] 异常线：首联重试/主管结束、无回复安排、报价终止、合同未签结束、已签主管处置、来源请求后分配/复查/结束；存量恢复及原回执重放。
- [ ] 每次动作检查真实业务事实、旧卡、下一卡、负责人、期限、台账、重登录；等待交给别人、合法结束均可清楚解释。检查无额外普通跟进及冲突主线。
- [ ] 用现有多角色测试任职真实办理，不能只靠全权单账号绕过交接；核对 1440/390/360 高保真及主按钮、我的待办、管理导航一致性。

此门只能写“导入至签署及既有异常链验收通过”，D09 未完成前不能写销售 MVP 全链通过。

2026-09-26 验收进展：报价与授权直签两条真实导入 HTTP 链均通过至双方签署核验/归档（2 项，零失败），入口回归 5 项通过。真实 CSV 结果未知恢复仅一次录入，错误行修正后 XLSX 导入、进入分配卡、分配后生成联系责任及重登录保留均通过。HTTP 与浏览器为不同数据，尚不等于同记录多角色浏览器全链通过。详细证据见 `docs/evidence/r2/2026-09-26-imported-sales-chain/README.md`。F10 P 高保真于 2026-09-26 获用户确认并冻结，进入实施；尚未完成产品实现。

## F10：签署归档后的执行条件与收款责任

**对应：** D09 第一部分。依赖 F09，属于已批准 R2 后续业务交付。

**Files:** `contract/internal/persistence/JdbcManualSignatureWorkflow.java` 的既有 handoff 消费边界；新增 `contract/ContractExecutionService.java`、对应持久化实现；新增 `payment/PaymentConfirmationService.java`（具名领域模块及架构注册）；API、TaskFactory、Worker、OpenAPI、追加字段迁移；前端合同执行条件及收款卡。实施前针对该切片单独细化 Owner 字段合同及高保真，不把现有表/生成类当成已实现功能。

**输入/输出：** 消费准确签署归档交接，形成执行条件核验责任；按合同约定形成财务责任。条件通过形成执行/激活事实与转案准备来源；非先款合同的财务责任可并行，不拖住转案。

- [x] 先冻结本切片准确输入输出及最小高保真，明确收款未通过、部分到账、错误归属、财务撤权和无负责人状态。（P 版，2026-09-26 用户确认；Owner 边界见 f10-brief.md。）
- [ ] 新增 `R2ContractExecutionHandoffIT`：历史 C45 与新归档各只接续一次；先款未满足不能进入允许转案状态；无先款合同可继续，不虚构到账。
- [ ] 新增 `R2PaymentConfirmationIT`：交易/凭证重复、合同/币种/金额不符、部分到账、并发核验、审计失败回滚，核验通过后准确唤醒原阻塞责任。
- [ ] 真实 API/浏览器验收两种付款条件、等待财务、多角色交接及重启回补；单独记录本切片证据。

## F11：转案提交、案管接收与接收后分类

**对应：** D09 第二部分。依赖 F10；业务分类仍在案件形成之后。

**Files:** 既有 `transfer/` 模块新增 `TransferSubmissionService.java`、`TransferReviewService.java` 及持久化实现；新增 `matter/MatterClassificationService.java` 与模块注册；对应 API/任务/事件/权限/字段迁移/前端转案卡及台账。补充本切片高保真，不重画现有页面。

**输入/输出：** 准确执行/激活与合同/客户/材料事实 → 不可变转案快照 → 案管审查和独立转案前冲突审查 → 退回逐项补正或接收；接收原子形成唯一案件身份、销售结果回执和案管分类责任。

- [x] 冻结准确快照、退回项回应、接收及分类的字段合同与高保真；销售不能自审接收，姓名相同不代表同一主体。（Q及Q1已确认，冻结摘要保留。）
- [ ] 新增 `R2TransferClosureIT`：缺材料阻断、退回/补正/重提、旧快照拒收、独立冲突审查、接收并发/重放仅一个案件，案管撤权可恢复。
- [ ] 新增 `R2MatterClassificationIT`：接收前无案件分类；接收后选择综法/执行/其他，改分类不生成第二案件，明确承接人/入口。
- [x] 真实多角色从销售提交到案管接收、销售收到结果、案管完成分类；财务并行项独立保留，不能随销售责任完成被误删。（2026-09-27 C49真实浏览器及授权接口通过，含Q1同案更正；见Q1证据。）

## F12：销售 MVP 整体验收与管理一致性收口

依赖 F10/F11。新增 `backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R2SalesMvpClosureIT.java`，沿用浏览器验收方式；证据归 `docs/evidence/r2/sales-chain-repair/`。

- [ ] 两条来源（报价、直接授权）× 两种付款条件均从新导入走至唯一案件与分类；结果未知恢复及重新登录贯穿关键交接。
- [ ] 普通跟进、报价、合同、签署、执行、转案各终点或等待原因可查，所有待办均有准确依据与责任人；管理台账和当前工作卡一致。
- [ ] 统计从实际事实计算：跟进尝试不算有效进展，签署不算转案，转案不算分类；无权限不显示全量业务。
- [ ] 独立审查执行/签署并发、旧卡再生、主管处置、原回执与部分到账场景；证据逐项回指 D01～D10，未过项不可用测试总数覆盖。
- [ ] 发布前按既有准入检查，不擅自授予 R2 发布验收。三项 AI、其他未完成管理能力仍在 R2 原清单单独核对，不借本次修复提前勾选。

## 验证执行约定

每任务先复现指定错误，再最小实现，运行该任务的单元/真实数据库或 HTTP 测试，最后做影响范围内的前端/浏览器验证。Maven 串行，不在共用构建目录并发跑数据库迁移和生成。

```powershell
# F02/F03 示例：项目 Maven 环境沿用已配置的 JDK，先确认版本
./mvnw.cmd -q -f backend/pom.xml '-Dit.test=R2OpportunityDiscoveryIT,R2OpportunityActivationCommandIT,R2OpportunityClosureReadIT' '-DfailIfNoTests=false' verify

# 工作台定向用例在 apps/workbench 下运行，使用本机已配置 Node
node ../../node_modules/vitest/vitest.mjs run src/features/opportunities/QuoteCard.test.tsx src/features/opportunities/OpportunityLedgerPage.test.tsx src/features/contracts/ContractRuntimeCard.test.tsx --maxWorkers=2

# 仓库根目录：修改 API/类型/样式后的构建前检查及开发基线
node node_modules/typescript/bin/tsc --noEmit -p apps/workbench/tsconfig.json
python scripts/baseline/verify_baseline.py --r2-development
```

最终每个门的记录包含：范围、准确环境、真实输入来源、各阶段原任务和后继任务、终止/等待依据、角色、重放、管理投影、截图及未解决限制。不得将旧日志或某个子流程的成功替代本轮全链验证。

## 覆盖自检

| 审计问题 | 实施任务 | 最终验证 |
| --- | --- | --- |
| D01 商机缺分流 | F01/F04 | F09 报价和直接合同两条线 |
| D02 无有效进展安排 | F05 | F09 未接通/未回复等待恢复 |
| D03 报价终止不完整 | F06 | F09 各报价阶段真实终止 |
| D04 合同终止不完整 | F06 | F09 未签销售结束/已签主管处置 |
| D05 后台补错卡 | F02/F08 | F09/F12 扫描并发及重启无再生 |
| D06 回头报价入口 | F02 | F09 已入合同后读写均拒绝 |
| D07 接受后页面不衔接 | F03 | F09 C30 结构及新链下一卡 |
| D08 结束上下文冲突 | F03/F08 | F09 C30/C32/C39 结构与真实并发 |
| D09 签后断点 | F10/F11 | F12 到案管接收及分类 |
| D10 来源请求未收口 | F07/F08 | F09 三种主管处理结果 |
