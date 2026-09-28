# T03 商机台账与办理接续：实施与验收

日期：2026-09-15

状态：IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED。T03 开发验收完成。R1 PAUSED / R2 NOT_GRANTED。

## 本轮交付

G 高保真已确认。正常商机台账使用 E 冻结母版，提供授权列表、搜索和状态筛选、详情、有效负责人、可展示的最近进展与下一事项。手机保留返回列表。本人可办理时进入原工作卡，不新增另一套销售表单。

T01 的有效责任、T02 的自动任务继续作为业务依据。主管具名只读权限不产生办理权；销售仅处理本人有权任务。读取失败、撤权和迟到响应清除旧详情，脏草稿、提交中和未知结果保护继续生效。进入工作卡核对准确任务 ID / revision / ETag，拒绝静默切到另一事项。只读主管不显示无权进入的“我的待办”入口。

新增两个 GET、具名读取端口、入口提示和受控授权码；分页使用认证加密游标，准确授权后才解密客户名称和进展，披露审计提交后才返回。没有新 Schema、商机关闭、报价、合同、客户编辑、材料、AI、统计或案管扩展。

## 验证结果

- 前端完整回归 **50 文件 / 634 项**通过，typecheck 和生产构建通过。构建保留非阻断的 bundle 大小提示。日志：`output/t03-frontend-final.log`、`output/t03-typecheck-final.log`、`output/t03-build-final.log`。
- 后端完整单元 **206 项**通过，后续序列化修复新增 **3 项**定向单元通过，并复跑 OpenAPI 20 项。不将分次运行称为一次全量通过。
- 按类取最新验证，**9 类 / 65 项相关集成**零失败、错误、跳过：台账读取 11、台账 HTTP 3、真实 Keycloak 台账准入 1、T01 交接 6、T02 Worker 1、既有代办会话 27、R1 工作卡 HTTP 8、R2 工作卡 7、真实浏览器 1。合并证据：`output/t03-current-evidence.json`。
- T01 实际交接后，台账显示新有效负责人，新人可办理准确任务，旧负责人不能凭原销售责任继续读取，冻结 Opportunity.Owner / Assignment 不变。
- T02 实际打包 API / TLS / PostgreSQL / Keycloak Worker 组合覆盖无待办→自动首次 OPEN→正式进展 WAITING→到期 OPEN；台账与自动周期读取同一事实。
- 浏览器使用真实 React、真实 HTTP、隔离数据库：台账→准确本人工作卡→创建草稿 201→确认进展 200→刷新台账显示已确认摘要与后继等待。恰好一次草稿创建和一次确认；数据库确认一条进展、原任务 DONE、一个后继 WAITING，冻结负责人不变。最终 Maven 退出 0（11:31:35）：`output/t03-browser-accepted-verification.log`。桌面 1487 和手机 360 无横向溢出，页面错误与传输错误均 0；截图及机器证据在 `output/t03-live-browser/`。
- 精确 T03/T01 传输基线变异 **8 项**通过；发展门禁通过，原 7 项非致命发布阻塞保留。日志：`output/t03-baseline-targeted.log`、`output/t03-development-gate.log`。额外的全部基线测试扫描运行超过 20 分钟未产生总结，已停止，不计为通过；本项要求的精确增量与历史投影检查已通过。

## 修复与复验说明

独立审查发现并修复：分页隐藏记录标识泄露、无关范围读取授权遮蔽合法销售权限、办理判定缺少负责人身份授权。对应回归用例通过，复核无剩余发现。

真实浏览器发现现有工作卡可选 selectionNotice 被序列化成 null，触发严格前端合同拒绝；已仅对该 getter 省略空值，保留 currentCard / actionDraft 必需的 null，R1/R2 HTTP 和序列化测试通过。没有放宽前端校验。测试夹具 Path 名冲突、草稿创建状态码误断言也已修正。

失败验证日志保留：`output/t03-unit-verification.log`、`output/t03-integration-verification.log`、`output/t03-recheck-verification.log`、`output/t03-browser-final-verification.log`；不将失败命令计作通过。后端合并结果明确按类采用最新复验，不声称这些原命令退出 0。

## 复现与交付边界

使用仓库 Maven wrapper、Java 25，串行运行；Node 使用固定 24.20.0。完整的相关 IT 选择为：

```text
./mvnw.cmd -f backend/pom.xml -Pit verify -Dit.test=R2OpportunityLedgerReadIT,R2OpportunityLedgerHttpIT,R2OpportunityLedgerSessionHttpIT,R2OpportunityOwnerExceptionCommandIT,OpportunityWorkerAssemblyIT,DelegatedSessionContextHttpIT,R1WorkcardHttpIT,R2OpportunityWorkcardIT,OpportunityLedgerBrowserIT -Dt03.browser.node=<Node24绝对路径>
```

Maven 堆 384MB、测试 JVM 堆 512MB、ActiveProcessorCount=2、SerialGC。浏览器测试通过 stdin 向 Node 进程传入临时身份凭据，不将 Bearer 写入浏览器存储、截图元数据或日志；浏览器只持有测试占位令牌，Node 转发真实 HTTP。测试身份、授权和数据库均来自隔离夹具；不冒充生产 OAuth 全旅程。

T02 自动调度由单独真实 Worker 组合测试证明；浏览器夹具通过正式激活命令准备待办，不把这一步称为浏览器内自动调度。没有提交、推送、部署、真实授权或打开默认关闭的 Worker 开关。

[实施计划](../../superpowers/plans/2026-09-15-r2-t03-opportunity-ledger.md)；[读取合同](../../contracts/r2-opportunity-ledger-read-v1.md)。本项完成不等于报价、合同到案管的整个销售 MVP 完成。
