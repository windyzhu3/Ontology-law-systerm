# T04 商机明确结束与待办终点：实施与验收

日期：2026-09-15

状态：IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED。T04 开发验收完成。R1 PAUSED / R2 NOT_GRANTED。

## 本轮交付

H 已确认，使用 E 冻结样式，在台账详情次要入口填写结束原因及说明，核对影响后提交。正常办理接续、未保存内容和未知结果保护保持。无待办时明确提示不再生成普通跟进；已有待办按准确事实取消，不伪造完成或进展。

新增具名 OPPORTUNITY_CLOSE、准确上下文读取和正式命令。独立不可变 opportunity.closure 使用独立加密关联数据保护说明；V910 为精确后继迁移。读取、命令、回执核验来源链、责任、任务、草稿、负责人身份和关联客户，审计不复制正文。已有报价、合同或转案事实时拒绝本入口。

## 验证证据

- 最终前端完整 **52 文件 / 645 项**、OpenAPI 检查、类型检查和构建通过。日志 `output/t04-frontend-accepted.log`、`output/t04-openapi-accepted.log`、`output/t04-build-accepted.log`。保留非阻断 bundle 大小提示。
- 后端完整单元 **216 项**通过：`output/t04-second-verification.log`。后续仅增加边界测试及准确 Schema 注册，没有未经验证的业务逻辑修改。
- 相关集成 **13 类 / 69 项**零失败、错误、跳过，以 `output/t04-current-evidence.json` 的最新类级结果为准，覆盖终结领域、命令授权与回执、竞态、读取、HTTP、浏览器、报价阻断、T03 台账、T01 交接、数据库角色及运行准入、jOOQ、T02 Worker。
- 浏览器正常结束及提交成功后丢失响应两条链路通过；后者人工查询原回执恢复终态。两条均恰好一次 POST、一个终结事实，原任务 CANCELLED、无新增进展、无活跃后继。桌面 1487 / 手机 360 无横向溢出，页面错误和非注入传输错误为 0。截图和请求证据在 `output/t04-live-browser/`、`output/t04-live-browser-recovery/`。
- 真实并发覆盖结束与进展、首次激活、到期恢复、交接，仅一方成功，另一方不能覆盖新事实。交接后具名授权管理人员按新责任结束，新任务取消，冻结来源负责人不变。关闭后 INITIAL/DUE 不再发现候选，T01 原异常正式观察后收敛为 NO_LONGER_APPLICABLE。
- 完整持久化报价包使读取返回 BLOCKED，正式结束拒绝并可恢复拒绝回执；准确报价读取拒权时返回 READ_ONLY，不披露报价或提交选择器。报价为遵守全部现有守卫的测试夹具，不表示报价生产命令已经交付。
- Schema **65 项**及精确 V910 变异、V900 历史投影检查通过。真实 jOOQ 生成及字节比较通过：28 个 POJO、59 张物理表、26 次迁移。日志 `output/t04-schema-accepted.log`、`output/t04-exact-successor-tests.log`、`output/t04-continuity-recheck.log`。
- 发展门禁通过，原 7 项非致命发布阻塞保留。日志 `output/t04-development-accepted.log`。精确传输及发展门禁测试通过，diff 检查通过。

## 修复与复验说明

独立复核发现读取和写入权限依据不一致，已统一负责人身份、草稿、关联客户等事实，以直接命令拒权测试验证。新增领域跨模块依赖改为受信组装与公开 Owner 端口，不放宽领域 DAG。取消依据及对象拒权注册仅准确新增终结事实，保留历史迁移摘要及合同投影。

首轮旧枚举/版本/表数断言已按具名增量修复。浏览器首次失败来自下拉框定位，修复测试定位与可访问名称；关联客户夹具补齐既有 party_resolution 配对约束。无待办影响说明不再暗示存在待办。

保留失败日志 `output/t04-unit-verification.log`、`output/t04-first-integration.log`、`output/t04-schema-tests.log`、`output/t04-continuity-verification.log`（生成专用 profile 未包含所选测试）、`output/t04-final-command-verification.log`（关联客户夹具错误）。不把这些原命令计为通过。最终边界复验 `output/t04-final-boundary-verification.log` 退出 0，12 项通过；连贯性复验退出 0，35 项通过。补充来源拒权后的命令 11 项在 `output/t04-source-denial-verification.log` 全部通过、退出 0。合并证据按每类最新报告统计，不宣称单次全量通过。

## 复现及范围边界

串行使用 Java 25、仓库 Maven wrapper、`-Pit verify`，测试 JVM 512MB / ActiveProcessorCount=2，Node 固定 24.20.0。浏览器附加 `-Dt04.browser.node=<Node绝对路径>`，按机器证据中的类名选择 IT。

浏览器使用隔离夹具身份、实际 React 和实际 HTTP/数据库，不冒充生产 OAuth 全旅程。正式激活命令准备待办；自动 Worker 另有真实打包 API/TLS/PostgreSQL/Keycloak 组合回归。

不含成交、合同终止、删除历史、重新开启、材料、报价或 AI 扩展。Worker 默认关闭，未提交、推送、部署或真实授权。T04 完成不等于整个销售到案管 MVP 完成。

[任务计划](../../superpowers/plans/2026-09-15-r2-t04-opportunity-close.md)；[业务合同](../../contracts/r2-opportunity-close-v1.md)；[H高保真](../../design/r2-sales-mvp/review/2026-09-15-h/index.html)。
