# 修复计划整体验收

状态：进行中，尚未整体通过。用户已明确：先整体验收修复计划，再继续 R2 后续新增功能。

范围为 F01–F12、D01–D10；保持冻结 E/P/P1/Q/Q1 设计。不新增管理模块或 AI 能力，不将局部通过标为整体完成。R1 PAUSED / R2 NOT_GRANTED。

## 当前收口状态

- F07/F08来源接续与存量修复已完成：以原业务责任和当前有效授权接续，未绑定测试账号；新一轮复扫0普通残留/0来源遗漏。
- 四种入口/付款组合真实HTTP链已有证据；本轮新增同一XLSX导入记录的浏览器主链至唯一案件及分类、更正，独立案管与财务读取保留。HTTP与浏览器证据分别记录，不互相冒充。
- 新增6项支付/转案边界验收全部通过，详见本文后部；销售/案管重新登录及1440/390/360页面回归通过。
- 仍在收口合同台账读取性能，以及页面特有的报价审批交接、先款等待接续、未知结果跨登录恢复证据。未授权新增管理汇总及AI不作为本轮新增实现；已有事实与台账口径需独立核对。
- 现有基线PASS，7项原发布限制保留。修复整体验收尚未放行，R2后续新增功能尚未启动。

## 本轮开始时的记录（历史快照，后续结果见下）

- 存量只读重扫：`python output/f08-local-repair.py rescan`，错误普通任务 0，历史来源接续 1。主管配置不唯一，待明确承接任职；未撤销或扩大权限。
- 前端：`output/repair-final-frontend.log`，42 文件、365 测试通过，覆盖工作卡、商机、合同、转案、任务及 App。
- 开发基线：`output/repair-final-baseline.log` PASS，原 7 项非致命发布阻断保留。
- 实际浏览器：`repair-final-browser-inspect.log` 和 `repair-final-browser-sales.log`，重新登录核对 C49 更正已持久化、销售历史可读且不能更正，1440/390/360 通过。
- 真实 XLSX 导入的“F09整链待修正乙”：本轮明确使用销售 contact 任职提交有效首联，HTTP200/SUCCEEDED，原联系卡退出；重新登录后“推进客户委托”后继存在。原 C01–C48 未提交任何动作。
- 当前来源/存量/签约/转案/收款组合数据库回归仍在运行：`output/repair-final-exceptions.log`。已发现旧测试断言仍期待签署协议提示，而 V1040 现已更早要求准确人工执行核验；修订测试为准确新防线并增加零执行事实断言，待定向重跑。不修改业务防线。

## 本轮开始时的收口清单（历史快照）

1. F08 历史来源记录的主管责任配置及受控修复复扫。
2. F09 同记录、多角色浏览器从导入至签署的完整验收及异常恢复；现有 HTTP 与分段浏览器证据不能替代。
3. F10/F11 收款、转案异常分支与管理投影综合核对。
4. F12 最终四路径证据、D01–D10逐项对照及最终复核。

现有四条 HTTP 完整路径证据见 ../2026-09-27-sales-mvp-matrix/README.md；本轮尚未据此宣告浏览器整体验收通过。

## 19:40 组合验收结果

`repair-final-exceptions.log`：106 项，105 通过、1 旧断言失败，无跳过。范围包含来源接续、存量修复及继承的客户/合同/签署/终止防线，并定向包含转案退回补正、部分到账、收款退回补正。

唯一失败为 `R2SalesChainRepairIT#preparation_cannot_execute_through_an_empty_legacy_signature_set`，V1040 已将执行拦截提前到准确人工核验。测试改为校验现行错误及零执行事实；基础版本与原失败继承版本分别定向重跑 1/1 通过，见 `repair-final-execution-guard-green.log`、`repair-final-inherited-guard-green.log`。未修改业务生产代码，未修改迁移或冻结设计。不能将原组合日志标为全绿。

真实浏览器客户资料草稿及确认均 HTTP200/SUCCEEDED，见 `output/playwright/repair-final-customer/receipts.json`；确认后三屏无溢出。脚本随后等待旧按钮“继续原跟进事项”失败，实际按钮为“返回原事项继续办理”，未重复提交。重新登录读取已确认资料成功；返回时实际进入“推进或结束本次商机”选择卡，原脚本期待普通跟进标题，正在按实际已实现入口修正验收定位。

用户要求推荐历史来源主管，已核实原主管任职为 `task9-local-supervisor` / 本地合成主管 / LOCAL_ACCEPTANCE。建议只将 LOCAL_SYNTHETIC_AUTO 的主管查找范围 ROOT 收窄到 LOCAL_ACCEPTANCE，先验证候选唯一和现行授权再备份应用；该来源的后续主管路由也会受影响。配置口径已提交用户确认，尚未实施，不撤销其他权限。

客户资料重新登录核对及返回下一环节选择卡复验通过：output/repair-final-customer-reread2.log，退出0；未重复写入客户资料。

## 用户纠正：测试身份与业务授权解耦

用户明确：当前测试用户不代表未来真实用户或角色，不得将权限、路由与测试账号强绑定。撤回上一条待确认的 task9-local-supervisor / LOCAL_ACCEPTANCE 定向配置建议；该建议未应用，原确认问题不再待执行。

验收与后续修复约束：
- 账号只用于登录；办理权限仍由有效任职、明确授权、组织/数据范围及当前业务事实共同判定，角色名称不等于授权。
- 待办可以记录实际承接任职，这是责任与审计事实；不得把测试账号名、固定测试任职 ID、测试组织代码写成通用授权或自动分配条件。
- 多个有权主管不等于配置错误；区分“谁有权办理”和“本事项由谁承接”。需要依据已有明确责任关系或业务路由确定一个承接任职；不能任取第一个、撤销其他人的权限或收窄到测试组织来让测试通过。
- 该历史来源记录继续保留为未闭环，先核对可复用的责任指派能力；新增路由行为若必要，先补最小方案，沿用原 MVP 与冻结设计，不扩建组织权限平台。
- 验收增加替换登录用户、任职变更、撤权、多个合法候选场景；相同授权与业务条件应得到一致行为，原责任历史保持。

本轮只读核对：backend/src/main 和 apps/workbench/src（排除测试）未检出 task9-local / LOCAL_ACCEPTANCE / VISUAL_REVIEW / LOCAL_SYNTHETIC 标识。AssignmentPolicy 当前按来源组织范围与有效授权查候选，但 unique 要求恰好一个候选；SourceRequestRepairCommand 也强制该条件。当前问题是“有权候选与唯一责任路由耦合”，不是已证实的生产代码账号硬编码。未改运行配置、权限或生产代码。

## 责任与授权解耦修复

已按用户确认的业务方向实施，无账号或测试组织绑定。正常 ACK 和历史修复从既有准确来源请求决策找到原调配责任的承接任职，当前有权则保留；原任职失效时仅接受唯一合格替代。已有来源复查责任保留当前有权承接人。多名有权主管正常并存，不撤权、不收窄来源组织。

`source-owner-red.log` 两项行为失败复现；首轮 `source-owner-green.log`21项通过。横查到期扫描后，`source-owner-discovery-red.log` 真实HTTPS测试复现候选遗漏；统一扫描与命令的责任选择后 `source-owner-final-green.log`25项通过。审查提出ACK最终校验必须重新对照原请求，不可将刚选出的替代视作既定承接；`source-owner-commit-red.log` 复现不拒绝错误，补齐后正在整体回归。

架构验证：独立临时类目录运行器引入旧测试类后触发 Spring Modulith 类归属异常，该次运行不算通过；改用标准 Maven surefire，ArchitectureTest13项通过、零失败/跳过。代码打包与开发基线通过，审查修复后将重新打包。运行环境配置和所有授权保持原样；历史修复尚未提交。

## F07/F08 本地收口

最终26项定向测试通过，`source-owner-commit-green.log`；开发基线PASS，`source-owner-final-baseline.log`。最终打包、部署退出0，备份 before-source-owner-20260927-201032。

`source-owner-live-repair-replay.log`：历史来源修复成功，同键重放回执完全一致，0普通残留/0来源遗漏。首次启动未就绪时的传输失败保留在 `source-owner-live-repair.log`，未换键重复办理。后台Worker重启后再次只读扫描仍0/0；`source-owner-restart.log`同时验证部署摘要匹配且配置仅变更部署摘要字段，来源路由等所有配置保持原样。

真实主管登录、我的待办和来源后续安排卡通过1440/390/360验收，原期限仍显示逾期，不重置期限；三种办理选项可见。没有提交任意业务去向。截图 `output/playwright/source-owner-live/restored-*.png`；首次脚本误期待单任职登录仍有确认任职按钮，修正脚本后通过，无产品修改。

F07/F08已本地收口。此前“待确认主管配置”的条目全部被本节替代；不需用户选择测试账号或调整组织权限。整体F09/F12验收仍未结项。

## 导入同记录浏览器续验与修订入口修复

F09整链待修正乙（01a0e2a3-bfe0-7235-b35f-3c7452809d6a）实际浏览器完成客户资料确认、直接授权申请/批准、正文生成与材料检查、形成第1版、独立步骤的签前审查/审批提交与结论，回执均SUCCEEDED。合同环节使用既有销售验收任职的现行授权，不能宣称不同人员审批。

发现旧模板无律所签署主体映射时，后端允许RETURN_CONTRACT_FOR_REVISION，但台账前往办理只接受已有待办，导致已有修订路径无法进入。修复为仅在准确上下文明确允许该命令且无当前任务时，进入同一工作台的原ContractRuntimeCard，以商机ID重新读取权限。无补造任务，无权限/账户配置修改，保留失权清空、提交回执保护及无草稿动作的离开保护。

失败复现 `repair-taskless-contract-red.log`；针对性22项及回归100项通过，`repair-taskless-contract-green.log` / `repair-taskless-contract-regression.log`；类型检查和本地SPA构建通过，`repair-taskless-contract-build.log`。只部署SPA，备份和摘要见 `output/repair-taskless-contract-deploy.json`。

真实浏览器从合同台账进入原卡并提交退回修订成功，`repair-final-revise2.log`，三宽截图 `output/playwright/repair-final-contract-revise/recovery-*.png`。首轮脚本误以完整行按钮只含客户名而翻页失败，未提交业务。修订保留原第1版，不修改已批准模板。继续用现有完整签署主体模板形成新版本后重新审查审批；整体链路验收仍进行中。

## 新版本签署与完成后刷新保护

新版本生成/材料接收/形成成功 `repair-final-regenerate3.log`；四步重新审查审批 `repair-final-v2-{submitReview,review,submitApproval,approve}.log` 全部SUCCEEDED。早两次重生成脚本因待办标签和已有值的严格label定位失败，尚未写入；第三次按实际表单定位完成。

签署首次重登录 session/context 503，未提交业务，`repair-final-sign2.log`；重登录恢复后，安排、3份材料真实上传扫描接收、双方提交/核验、归档已通过生产命令保存。数据库确认最新 signature_workflow SIGNATURE_COMPLETE（12:49:56Z）。`repair-final-sign3.log`最终等待完成标题失败，原因是工作台定时/焦点刷新读取已完成旧任务，将页面切到另一推荐任务；不能将该脚本标成全通过。

修正合同运行卡在写入或原回执确认后通知共用工作台暂停自动切换，保留当前合同完成/交接信息；准确本人后继仍自动继续，用户选待办/返回管理或切身份解除保护。权限不由前端放宽，所有后续读取和命令仍核对真实权限。失败用例 `repair-contract-completion-red.log`；100项回归 `repair-contract-completion-green.log` 和包含焦点刷新验证的2项壳层测试 `repair-contract-completion-shell.log`通过；类型检查、构建通过，SPA部署备份摘要 `repair-contract-completion-deploy.json`。本次不重放已成功的签署归档，改从该记录后继执行责任继续验证。

签署异常提示使用服务端准确原因，已有原因时不再叠加“无合格责任人”通用文案。`repair-taskless-message-green2.log`100项通过；原冻结布局和CSS未改。

## 同记录签后整链已通过，整体验收仍未结项

真实XLSX导入的F09整链待修正乙继续通过：执行条件确认 `repair-final-browser-execution.log`；转案准备、独立审查、案管接收、首次分类、同案更正、案管重登录 `repair-final-browser2-{prepare,review,intake,classify,correct,inspect}.log`；销售重新登录历史与权限边界 `repair-final-browser3-sales.log`。各阶段1440/390/360通过，无页面脚本错误，均有成功回执。案号R2-01a0e2f1-e118-74ca-8f43-98bc8f656136，首次综法后更正执行，未生成第二案件。

只读接口再次确认同案更正历史2条及独立财务待办仍有权可办：`repair-final-state2.log`、`repair-final-finance-preserved4.log`。数据库按准确商机ID汇总，唯一OPEN责任为CHECK_CONTRACT_RECEIPT；普通跟进CANCELLED，其余销售/合同/签署/执行/转案/分类责任DONE。财务不随销售结项关闭。

未通过/限制：本地session/context间歇503多次复现，销售历史/财务读取曾受影响，后续重试只读成功；未为恢复访问修改权限或硬编码账号。首次财务辅助脚本使用contact登录却选择finance任职得到403，这是正确拒绝，修正测试调用为delegate后重试；部分重试遇到503。不能以最后一次成功覆盖运行可用性问题，继续诊断。仍未宣告修复计划整体验收或R2发布通过，R2新增管理/AI尚未启动。

迟到自动请求防护失败复现 `repair-contract-inflight-red.log`；共用工作台对已暂停自动导航的迟到成功响应不再发布新卡，明确手动刷新仍可用。`repair-contract-inflight-green.log`12文件116项通过，类型检查及构建通过；SPA备份摘要 `repair-contract-inflight-deploy.json`。

## 会话503：连接池耗尽证据与后台并发修正

只读JFR诊断捕获 API HikariPool-1：`Connection is not available, request timed out after 3000ms (total=8, active=8, idle=0, waiting=1)`，时间21:05:32。这确认存在连接池耗尽，不是测试账号授权错误。诊断期间12次顺序会话读取均200，18次三并发读取均200（61～138ms）；不能用空闲时读取通过否认后台争用。

生产R2OpportunityTaskScheduler按扫描种类开启最多8路，API技术池总量8；再叠加R1到期恢复和用户读取，缺少容量余量。将R2后台扫描全局并发上限收紧为2，扫描种类、游标、重试键、检查点与业务SLA保持原样。没有增加数据库池、缓存授权、设置测试账号例外或停用扫描种类。

`repair-background-capacity-red.log`复现后台同时占用超过2路；`repair-background-capacity-green2.log`29项全部通过、零跳过，覆盖并发上限、全部种类最终得到扫描、准确原候选重试及检查点恢复。第一轮green脚本误填不存在的测试类，保留失败日志，不计为通过。正在打包部署与带后台运行的重登录复验，尚未据此关闭可用性问题。

## 迟到失败响应与台账并发验收（仍未结项）

独立审查指出迟到普通失败响应也可能清空已确认页面，现已修正：自动读取暂停后丢弃迟到成功、503、网络失败和超时，401/403/404仍优先清除私有状态。`repair-contract-latefailure-green2.log`12文件120项通过；类型检查及构建通过，SPA部署 `repair-contract-latefailure-deploy.json`。独立复核P2已闭合。销售、案管、财务重登录与唯一案件/独立收款待办复查通过，不重复提交已成功签署事实。

后台扫描并发2版本已部署，后续重扫0/0、基线通过（保留7项原有非致命限制）。但并发只读验收 `repair-final-mixed-read.json`发现6次合同台账均超过20秒客户端期限，不能将连接池问题标为完全解决。独立单请求 `repair-contract-single-read.log`返回200但耗时17,563ms；带诊断计数 `repair-contract-category-read2.log`19,401ms。不是请求路径或账号权限错误。

仅固定分类数字的SQL诊断：29,321次调用，contract3,310/databaseClock10,211/fence2/identity1,470/other8,070/responsibility6,258。日志不记录SQL文本、绑定值、身份或响应正文。事务长期持有业务共享锁时，后台独占申请与后续读取出现排队。先限定同一个受fence保护的只读范围复用合同准确事实，仍保留实时权限和最终核验；`repair-ledger-facts-red.log`复现同一事实图重复构建，`repair-ledger-facts-green.log`通过。完整回归和部署后实测仍在继续。未放行修复整体验收，未开始R2新增功能。


事实复用标准Maven4项通过（范围隔离/命令拒绝1，转案历史/撤权/准确待办3），独立审查无阻断。部署 `repair-ledger-facts-deploy.log` 后，`repair-ledger-facts-live-read.log`200/14,370ms；SQL降至22,129，仍未达标。

继续仅收敛读取核验开销：StableAuthorizationBatch 在明确身份锁定范围内按一批准确请求完整核验，并在批末重新取数据库时间；到达下一有效性边界（含任职、直接/委托/对象授权及未来DENY）或时钟回拨则整批重算，3轮不稳定以40001暂时失败闭合。每批重新取时、不跨批缓存允许结果；无scope仍走原逐条路径，任务及事实最终验证保留。每个事实必须有一个完整grant，不能拼接权限。台账初次授权和任务验证后的最终授权均接入完整批次，原逐事实审计保留。

`repair-auth-batch-red.log`6项失败复现 → `repair-auth-batch-green.log`6项通过；`repair-auth-batch-it2.log`3项通过（时钟往返预算、批间授权到期、未来DENY）；`repair-auth-batch-boundary2.log`真实Jooq批内跨授权到期/未来DENY2项通过。首次boundary测试尝试UPDATE受保护grant被正确拒绝，改用新增限时fixture grant，未修改生产权限。`repair-auth-batch-maven.log`标准构建36项通过（算法6、授权原有11、批核验3、架构13、转案台账3）；台账初次批核验接入后的最终打包回归正在进行，尚未部署。独立最终复核无阻断。此前诊断用固定SQL分类计数已从源码还原，仅保留原有opt-in总量指标。


批核验最终6项标准集成回归/打包通过 `repair-auth-batch-final-package.log`，部署后SQL降至12,630；但 `repair-final-mixed-read2.json`18次混合读取仍出现2个503，成功合同读取约8.9～9.8秒，仍未达标，不能整体放行。

后续准确定位材料读取反复调用相同商机Closure来源图，R2LedgerSourceFacts只在合同台账已持有业务及身份锁时启用，按线程/连接引用/租户/准确商机选择器复用事实列表（含null），所有其他路径直读。`repair-ledger-source-red.log`复现；green2项范围测试通过，`repair-ledger-source-package.log`标准5项通过（来源/合同范围2+转案台账3）。独立审查未发现时间语义或跨命令缓存问题。

更正此前“全局2路”的描述：R2OpportunityTaskScheduler上限2是每个实例的上限；R1WorkerDeployment另有owner实例、R1到期调度和投影，共用客户端但可以叠加超过API池容量。现将InternalApiClient所有后台请求统一限制为公平4路，最多等待10秒，拿到槽位后重新核对deploymentGate；HTTP自身仍10秒，键/游标/租约令牌/业务状态保持。`repair-worker-budget-red.log`复现第5路进入；`repair-worker-budget-green2.log`4项实际HTTPS通过，覆盖8请求并发、拒绝gate后槽位释放、部署gate、业务返回。首轮green旧测试配置仍期望v10而实际fixture为v19导致正确503，已仅修正测试期望版本；生产gate不变。独立审查无阻断。

限制：客户端4路不等于强制数据库分区，取消HTTP不能证明服务端立即释放连接；投影在等待时仍占有原租约，需要部署后混合负载核对无本地等待造成的重试耗尽。此刻最终worker回归/打包进行中，来源复用及统一后台上限尚未合并部署实测。

## 后台统一容量部署及后续验收

来源图复用与后台共享4路已完成标准打包，`repair-worker-budget-package.log`31项通过，部署记录 `repair-capacity-final-deploy.log`（备份 before-repair-acceptance-20260927-221034）。`repair-final-mixed-read3.json`三并发18次只读请求全部200：session 129～4433ms，工作卡1494～2162ms，合同台账5097～9685ms。台账SQL已降至6562次，但仍有锁等待和明显延迟；此结果只证明本次样本未出现503，不能判定整体验收通过。

部署后后台已恢复READY，本轮观察没有新增后台告警。只读投递队列核对R1仍为DELIVERED 227、最大尝试1，无新增EXHAUSTED；R2 PENDING从269012增至269162且尝试0，未删除或篡改队列，不能将其宣称为已投递。继续定位后台读取和台账重复核验，不改变权限、范围、冻结UI或业务SLA。

进一步复现后台共用权限读取每100项产生200次数据库时钟往返：`repair-owner-batch-red.log`13项中仅预算断言失败，其他12项通过。改为复用已有完整授权批核验，锁外保持原逐项核验，每项仍需完整授权路径；空事实与明确DENY继续拒绝。正在运行范围及授权有效期回归，尚未部署此后续改动。

## 读取优化第二轮与验收边界补测

`repair-owner-transfer-final-package.log`标准Maven打包成功；21项（台账及更正权限4、Owner权限批核验2、批内过期/未来DENY2、架构13）全部通过。Owner共用权限读取复用已审查批核验；列表投影不再构建仅详情才用的分类更正命令事实图，详情的更正权限与准确待办办理保持。`repair-owner-transfer-focused-red.log`明确复现多余图构建，Owner2项在同批通过。独立复核两处改动均无阻断。

已部署 `repair-owner-transfer-deploy.log`，备份 before-repair-acceptance-20260927-222834。未改变冻结UI、源组织配置、授权或账号规则。整体验收仍未放行，部署后的延迟复测待边界测试完成后进行，避免构建/集成测试资源负载干扰读数。

补测清单（待成功日志确认）：支付错误合同/版本/材料摘要不能创建到账事实；财务并发仅一方成功；案管并发接收只有一件案件/一份接收快照/一项分类后继；真实案管撤权后恢复可办原责任；分类责任恢复保留案件及期限；首款部分到账保持等待、足额唤醒原任务一次。凭证中的实际金额仍由人工核对，本次不添加OCR自动判款能力，不将金额输入校验说成已自动读取核对凭证正文。

### 支付与转案边界：本轮6项已通过

`repair-final-boundary-acceptance2.log`正常结束，`repair-final-boundary-acceptance2-results.json`保存具体方法及0失败/0错误/0跳过结果。包括：错误合同、版本或材料摘要不得创建到账事实；并发收款唯一确认；并发案管接收唯一案件/接收事实/快照/分类待办；真实TRANSFER_ACCEPT授权撤销后拒绝、重新授权后完成原待办且原期限未重置；分类责任恢复保留案件及原期限；部分首款仍等待、足额后只重新开放原任务一次。首次运行在测试编译期因lambda内辅助函数声明Exception失败，已修正测试准备方式，不涉及生产行为；失败日志保留、不计通过。

以上补齐数据库事务及真实授权端口的边界证据，不等同于新增浏览器全链验收，也不等同于自动识别凭证金额。整体验收状态仍进行中。

### 部署后页面及重复读取定位

`repair-owner-transfer-browser-sales.log`、`repair-owner-transfer-browser-inspect.log`均通过真实重新登录及1440/390/360检查：销售合同历史可见但无分类更正权限；案管详情可进入现有更正卡并读取已保存的执行分类。未重复提交业务动作。

`repair-final-mixed-read4.log`18次只读混合请求全200，会话76～129ms、工作卡1286～1683ms、合同5405～7449ms。合同指标6206次SQL，业务锁约1～13ms，本轮慢点主要已不是锁排队；仍不通过整体性能验收。随后两次带JFR诊断采样请求也全200；诊断期间的耗时不混作无采样性能基线。

`repair-ledger-socket-summary.txt`仅聚合应用调用栈和次数，不输出SQL、身份或材料正文，定位到报价事实、客户确认来源及材料来源重复构建。扩展原ledger内事实复用范围到报价及准确客户确认来源，独立key、同连接/同租户/同scope，不缓存授权结果；`repair-ledger-quote-source-red.log`已复现重复构建，当前6项定向回归进行中，尚未部署该扩展。JFR已自动停止。首次尝试JSON输出诊断内存开销较高，已停止并改为Java逐事件流式聚合，不影响业务库。

### 同次读取事实复用补充

客户确认测试首轮使用了错误的测试选择器类型 `opportunity.customer_confirmation`，生产代码正确拒绝，改为准确的 `opportunity.customer_requirement_confirmation` 后 `repair-ledger-quote-source-green2.log`3项标准回归通过。该更正不修改生产校验。财务metadata合同图另用独立Map，保持与主服务实例的ports结果隔离；`repair-ledger-finance-source-red.log`明确复现重复读取，当前 `repair-ledger-all-source-green.log`4项事实隔离测试已通过，余3项台账回归及打包待完成。两次独立只读复核均无阻断。

验收范围澄清：原F09/F12没有要求四种组合分别重复完整浏览器写链；四组合真实HTTP、独立角色及原回执断言，与同记录真实浏览器链可组成互补证据。仍补页面特有交接/恢复分支，不将HTTP冒充浏览器，也不擅自新增销售漏斗或管理汇总页面来满足尚未实现的R2后续统计能力。

## 后台异常观察重复写入（本轮继续收口）

合同台账全部来源事实缓存包验证7项通过并部署，真实浏览器只读重登录、独立财务读取和存量复扫0/0通过；`repair-final-mixed-read5.log` 18次混合读取全部200，但合同仍3.8–8.2秒，不能据此验收性能。

进一步只读调查发现：无人工办理的最近5分钟仍新增75条商机责任异常观察事件，历史累计约26.9万条。扫描将每个ACTIVE/COORDINATING异常无条件重复发布，正式命令不断追加相同状态版本及事件。保留全部历史数据，不删除事件或改权限。

`repair-owner-observation-churn-red.log` 两项测试明确失败复现；最小修复在扫描重新检查现行权限后，按准确商机、责任依据/承接人、任务/等待回执、原因集合和协调期限比较，只有相同未解决状态才跳过。变化、健康恢复、关闭、复查到期仍继续正式命令。`repair-owner-observation-churn-green.log` 两项通过；分页/权限/架构与打包、部署后真实写入量及延迟仍待验证。未修改正式观察命令事件协议、测试账号配置和冻结页面。

最终7项观察发现回归通过（`repair-owner-observation-churn-final2.log`），此前独立架构13项亦通过。审查补充了同一分页游标跨复查期限场景：判断改用新的数据库时间。初次补测将游标设在夹具创建时间之后，导致该记录未被扫描；修正测试游标后通过，保留失败日志。当前进行备份部署与实测。

另确认既有任务检查误报：合同接管后，同商机合同/财务/转案任务被普通跟进检查当成来源异常。将以准确接管事实限定普通责任边界，真实来源、失权、残留普通任务仍检查；这项尚未完成，不能标记整体验收通过。

## 合同接管后的商机异常检查

`repair-owner-takeover-red.log` 2项失败，证实合法合同任务和已修正的旧普通任务仍被误报。修复依据具名Owner提供的准确接管事实区分普通任务边界；全部当前任务须属于合同或转案阶段，并保护其selectors。残留普通任务、未知任务类型、首联来源错误、现行owner失权等仍保留检查。空任务交接增加原阶段防线，防止重新创建普通跟进。

3项初轮通过后，`repair-owner-takeover-package.log` 最终19项全部通过：4项接管/残留/撤权/防再生/正式审计解决重放、2项原普通责任观察交接、13项架构。独立复核未发现新增阻断。正在备份部署，不修改权限配置及冻结页面。

重复观察部署后只读两次计数保持269491，最新事件仍为15:10:00 UTC，15:14:55 UTC检查最近2分钟新增0（`repair-owner-churn-idle.log`）。与之前5分钟75条不同，重复写入已停止。`repair-final-mixed-read6.log` 18请求均200，但与Maven构建并行且冷启动，合同耗时仍高，不能作为性能通过证据；将在无构建干扰时复测。

跨登录浏览器两次补测保留：第一次脚本误期待重新进入录入页，实际正确进入受限原回执恢复页；第二次已完成原成功回执逐字段核对、页面显示“原操作结果已确认”，脚本误期待自动进入工作台，实际需点击现有“继续”。无产品代码修改，不将脚本超时当通过；两条新的合成输入各仅提交一次，原人工案例未推进。

## 最新运行与页面验证

合同接管修复已备份部署（before-repair-acceptance-20260927-232121）；存量只读显示20条旧接管误报经原审计流程解决，3条实际OWNER_DENIED保持。剩余2条SOURCE_INCONSISTENT均为APPROVE_QUOTE：独立报价审批被当成普通销售任务，已补RED真实DB复现，正在按准确当前报价/审批申请/成员/任务归属修复，不放宽权限。

`repair-import-relogin3.log` 完整PASS：真实CSV提交成功后丢弃响应、清除SSO cookies重新认证、原身份进入恢复页、查询同commandId/receiptId成功回执、点击继续返回工作台；仅一次录入，1440/390/360无溢出。该合成记录F12跨登录原回执验收丙专用于异常恢复，不冒充另一条全链业务。

`repair-final-mixed-read7.log` 无构建干扰18请求全部200；session76–452ms，工作卡1217–1814ms，合同5152–7218ms。合同读取SQL约5286，主要耗时仍在读取及授权，业务锁1–2ms；不能继续将高延迟归因于重复观察锁，性能未通过。开发基线再次PASS，7项原限制保留。

## 报价审批责任边界与查询往返优化

`repair-owner-boundaries-final.log` 19项通过：2项独立报价审批/成员任务精确匹配/失权、4项合同接管及审计解决、13项架构。新具名报价Owner读取端口仅读取当前报价审批关联selectors，不读取商业正文；当前pending成员任务集合必须与全部活跃任务完全一致。独立复核无新增阻断，仍由既有审批失权通道检查审批人资格。本轮尚未部署此批。

JFR当前采样 `repair-ledger-remaining-summary.txt` 显示合同事实及签署事实逐表读取是主要往返来源。针对同租户同业务键的只读引用查询做UNION ALL合并，保持事实集合、真实授权及审计。`repair-contract-reference-batch-red.log` 复现原16次往返，应合为合同/签署两次；GREEN与最终回归进行中，不缓存权限决定或跳过时间检查。

## 23:48 本轮部署与复验

- 最终引用集合补测及打包通过：`repair-contract-fact-batch-reviewed.log`，R2ContractFactBatchIT 两项、零失败/错误/跳过。独立逐表期望集覆盖8张商机合同表、8张签署表和5张合同版本表；材料事实复用覆盖同连接/同租户/同锁定事务及离开作用域后重新读取。此前支付防线组合4项通过见 `repair-contract-fact-batch-final.log`。
- 部署完成，完整备份 `before-repair-acceptance-20260927-234409`；日志 `repair-final-batch-deploy.log`。包括报价审批责任边界、引用查询合并和事务内材料关系复用，未变更权限、路由或冻结UI。
- 实库只读核对：当前SOURCE_INCONSISTENT异常已为0，原2条报价审批误报经既有审计观察变为RESOLVED；RESOLVED/SOURCE_INCONSISTENT为29。仍有OWNER_DENIED两条及OWNER_DENIED+SUPERVISOR_UNRESOLVED一条，真实权限异常不为验收清零而消除。
- `repair-final-batch-rescan.log`：普通残留0、来源遗漏0；`repair-final-batch-finance.log`：F09独立财务待办仍可由财务任职读取。
- 真实浏览器只读复验：`repair-final-batch-execution-ui.log`执行条件卡、`repair-final-batch-sales-ui.log`销售历史、`repair-final-batch-intake-ui.log`案管分类更正持久化均PASS。均重新登录、1440/390/360验证，无业务提交；销售未获得分类权限。沿用已冻结页面。
- 跨登录未知结果恢复的证据界限：`repair-import-relogin3.log`已证明原commandId/receiptId一致、仅一次录入及点击继续；其恢复后三屏截图捕获的是工作台加载壳，不作为工作卡加载完成的截图证据。真实已加载执行条件卡由本轮独立只读浏览器复验补充，不重复提交导入。
- `repair-final-batch-baseline.log`：开发基线PASS，原7项非致命发布限制保持。R1 PAUSED / R2 NOT_GRANTED。

### 未通过项与下一步

`repair-final-mixed-read8.log/json`：无Maven并行的18次、3并发只读请求全部200。会话116–296ms，工作卡1398–3240ms，合同台账4291–6825ms。合同SQL由约5286降到4029（约24%），但真实体验改善不足，不能宣告性能验收通过。运行指标合同业务锁1–9ms，读取/授权3430–5959ms，审计442–1577ms；剩余主要是约4000次数据库往返及审计成本，不是主管数量、测试账号或主要业务锁等待造成。

整体验收继续保持“尚未整体通过”。下一步只围绕剩余台账读取/授权事实构建和审计往返优化，保留现行权限实时复核、逐事实审计、冻结页面和原结果集合；优化后重测并完成F09–F12/D01–D10证据对照，再进入R2后续新增功能。不得用本轮18次200代替延迟达标，也不通过跨请求权限缓存绕过授权校验。
