# T08 合同准备、审查及审批整体验收

状态：T08 本地业务实现与范围内集成验收完成，2026-09-21。全仓基线测试存在下述已披露限制，不标全仓测试通过。仅本地合成数据，R1 PAUSED / R2 NOT_GRANTED。

## 业务范围

准确已接受报价与人工直接授权两入口共用同一合同真源。直接授权绑定当前客户确认、范围、收费和有效期，不制造报价接受事实。草稿、准确正文、受控模板和条款形成不可变版本；签约前审查明确通过、补充或阻断；全部冻结审批要求齐备后形成 READY_FOR_SIGNATURE。

客户、主体、正文、模板、条款、策略或审批资格变化会重新核验依据。补正可针对同一准确版本形成新审查；正文变化必须形成新版；退回不覆盖旧版或沿用旧批准。失权任务由 SERVICE 准确取消并交回销售，保留原 SLA；销售也失权则记录 OWNER_EXCEPTION 及恢复目标，不伪造办理任务或人工决定。

T08 到签署准备就绪为止，不创建签署、执行、转案事实，也不创建无法办理的 OPEN 签署任务。实际冲突检查采用准确 Party ID / CLIENT 与 OPPONENT 角色规则，并由人工确认；不声称实现完整法律冲突引擎或外部案件库。

## 冻结页面与使用连贯性

沿用用户确认的 E/L 母版、现有单卡、台账和导航。补正默认只显示事实、补充说明和一个主操作；“需要修改合同正文”才进入原修订表单。审批角色使用中文业务名称。我的待办允许主动切换有权事项，返回时直接展开队列；成功提交离开旧表单，展示真实后继。

已就绪合同从台账“查看合同内容”进入原合同卡的只读模式，保留准确正文下载，不增加写权限或虚假负责人。读取失败提供既有重新查询入口；台账读取有 30 秒期限，写命令和未知回执恢复不受该读取期限影响。

真实浏览器已确认：

- C35 提交 NEED_INFO → 新补充责任 → 提交补充 → 新 AWAIT_REVIEW，版本与原期限保持，历史追加。
- C36 默认补正和主动修订两种模式均只有一个主操作；返回我的待办直接展开。
- C32 从我的待办主动选中，显示准确范围、收费、付款和授权决定；1440px / 390px 无横向溢出。
- C38 显示准确正文、审查已通过、中文法务审批及单一决定操作。
- C40 台账只读入口正常；正文、审查与审批结论可见，0 个可编辑字段，没有空负责人；722px 下无横向溢出。

截图和核对指南见 [页面验收目录](t08-browser/review-guide.md)。

## 验证结果

| 验证 | 实际结果与证据 |
|---|---|
| 字段合同、生成物 | Schema 98 项通过；33 个迁移、67 个 PostgreSQL 函数解析通过；jOOQ 生成一致性通过 |
| 后端全量单元/架构及 HTTP 组合 | 334 项，331 通过、3 条件跳过、0 失败/错误；`.local/t08-final-http-unit-package.log` |
| 真实数据库链路 | 版本 8、工作流 6、依据变化及冲突 6、命令 3、历史报价恢复 3、独立责任失权恢复 3 项均通过；另有材料/资格、101 名候选及正式 SERVICE 回执回归 |
| 最终台账与授权组合 | 5 项 IT 通过；`.local/t08-ledger-final-package.log` |
| 最终 SERVICE 扫描修复 | 分页/恢复、失权、真实 mTLS 及授权缓存时间边界共 6 项通过并打包；`.local/t08-recovery-prefilter-green-package.log` |
| 前端最终全量 | 70 文件 / 795 项通过，类型及验收配置构建通过；`.local/t08-readonly-final-all-tests.log`、`.local/t08-readonly-entry-build.log` |
| 实际当前基线 | `verify_baseline.py --r2-development .` PASS；原 7 项非致命发布门禁保持；`.local/t08-baseline-final-current.log` |
| 历史基线测试 | 原长时运行 376 项 / 4548.996 秒，159 failures、14 errors；历史 Schema/readiness 12 项修复后定向通过。全量并非绿色，详见下面分类 |

这些组合部分重叠，数量不累加为总覆盖数。基线测试只修正历史夹具构造，不取消冻结哈希或发布门禁检查。

原全量测试在 18:04 启动，执行期间规范仍更新，老进程内注册值与后续临时仓库内容混用。127 个失败堆栈明确包含 T08 规范错位；29 个只有 findings 数量 `4 != 1`，疑似同类但不能逐项宣称已修复。其余 3 failures + 4 errors 已由上述 12 项定向回归覆盖。另有 2 个旧 transport 投影夹具错误已修正，以及 8 个 Windows 测试环境错误（符号链接权限、系统 hosts 写权限、非法文件名）。未为通过测试而修改系统权限或系统文件。完整输出：`.local/t08-baseline-tests.log`。

稳定源码后的追加验证：OpportunityCard 2 项、Task9 remove-not-found 1 项，以及两类批量失败各 1 个代表，共 5 项 / 54.112 秒全部通过。代表分别是 `test_historical_warning_block_must_remain_exact` 和 `test_merged_evidence_rejects_backslash_escaped_marker_in_label`（含两个子场景）；它们不需修改生产规则即可通过，支持混合快照归因，但不能据此声称其余失败项逐项重跑通过。

## 真实部署与案例

最新后端部署 2026-09-21 19:05:28；最终 SPA 更新 19:15。部署前已备份数据库、运行配置、材料、应用和案例日志，目录为 `.superpowers/r2-review-runtime/before-t08-20260921-190528`；SPA 另有 `before-t08-c40-ui-20260921-191512`。未重置数据库、worker checkpoint 或命令 journal。

C31—C40 已通过真实命令和材料扫描建立，API 阶段核对全部符合预期，见 [案例状态证据](t08-browser/cases-verified.json)。原 193 条案例日志与部署前备份比较，变化 0 条。原 C01—C30 保留。

| 案例 | 已核验阶段 |
|---|---|
| C31 | 无合同工作流，直接申请入口 |
| C32 | DIRECT_REVIEW |
| C33 | PREPARE |
| C34 | SUBMIT_REVIEW |
| C35 | AWAIT_REVIEW，含真实浏览器补正往返 |
| C36 | REVIEW_SUPPLEMENT |
| C37 | SUBMIT_APPROVAL |
| C38 | AWAIT_APPROVAL |
| C39 | RETURNED |
| C40 | READY_FOR_SIGNATURE，无可办理任务 |

页面使用 `task9-local-contact` 的“页面验收组（合成数据） · 联系人员”。本轮正文和模板均明确标记 SYNTHETIC / NOT LEGAL，仅用于本地验收，不能用于正式委托。

## 环境联调发现及修复

- 合同请求误入旧 Task If-Match 分支、nullable 输入漏登记，实际 HTTP 500；修正后生产 MVC/JWT/DB 提交、同键重放、回执读取通过。
- current-card 的合同权限漏注册，实际 503；新增准确闭集项后真实披露 200 且产生读取审计。
- SERVICE 合同扫描 HTTP 200 正文被客户端路径闭集丢弃；真实 mTLS 回归由失败转通过，保留严格 UTF-8 和原幂等键。
- 扫描过滤只取当前可恢复工作流，避免历史异常状态反复拖入已正常业务；锁内读取复用 P07 授权缓存，仍重验当前时间与拒绝条件。请求超时保持 10 秒，实际扫描调用含 Java 启动/TLS 从 17.362 秒降至 4.346 秒。
- 同 8 条合同台账读取从 26.82 秒降至 12.61 秒，HTTP 200，准确责任与 SLA 核对不变；见 [读取对比](t08-browser/contract-ledger-read-comparison.json)。不承诺所有页面已经达到秒开。

## 文件下载及明确限制

19:26 后 CONTRACT_PREPARATION worker 按原退避时间自行恢复：HTTP 200、failures=0、acknowledged=1、READY。C30 真实已接受报价来源自动产生 PREPARE 工作流和有权销售任务，API 提供 START_CONTRACT_PREPARATION 且 blockers 为空。没有重置检查点或手工执行恢复命令；证据见 [报价自动接续](t08-browser/accepted-quote-auto-continuation.json)。正式合同锚点仍由销售确认开始准备后生成。

浏览器刷新“我的待办”后从 28 项变为 29 项，选中 C30 后显示“准备委托合同”、原负责人/期限及“接续合同准备”主操作，截图 `t08-browser/c30-accepted-quote-task.png`。原案例未被手工推进。最终文档落地后的当前基线再次 PASS：`.local/t08-final-delivery-baseline.log`。

既有 OWNER_EXCEPTION worker 仍处于此前的 RETRY_WAIT（预计 20:24 再试），因此不声称整个 worker 聚合健康已恢复；本次合同扫描已独立恢复。INITIAL 的瞬时 503 随后自行恢复 READY。此运行状态与 T08 业务接续结果分别记录。

真实认证下载 HTTP 200，21554 字节与原材料及正文 SHA-256 完全一致，no-store / attachment / nosniff 已验证，见 [下载证据](t08-browser/document-download.json)。前端采用准确材料扩展名，不暴露服务器路径。浏览器点击未出现页面错误，但自动化 download 事件等待超时；只将真实 HTTP 字节验证和组件下载回归计为通过，不冒充浏览器下载事件通过。

正式法律模板尚未提供。签字件核验、合同执行、转案及案管接收属于后续任务；本次完成不代表整个 R2 MVP 或正式发布验收已完成。
