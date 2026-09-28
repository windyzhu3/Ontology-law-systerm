# R2-M01 实现与自动验证

2026-09-28，R 高保真已确认。代码、自动验证及本地真实浏览器验收完成，M01 查询切片通过；不代表 R2 整体发布验收通过。R1 PAUSED / R2 NOT_GRANTED。

## 已实现

- 原合同台账内共用查看内容选择器，合同/收款/转案与案件沿用 BusinessNavigation、ledgerMaster.css 和原工作卡。
- 独立 PAYMENT_LEDGER_READ / TRANSFER_LEDGER_READ；本人任职具名视图提示 businessManagementViews，只有转案权限时直接进入转案，不请求无权收款或合同正文。管理资源403局部清空，401仍清空会话。
- 具名领域读端口、分页及过滤、准确合同版本和责任人、逐笔收款/合同累计/首款差额、转案审查/接收/分类/补正及历史。
- 准确事实授权、提交前复核、同事务具名审计；无额外业务写入接口。前往办理重新读取准确任务，交原工作台办理。
- 同一有效会话内保留查询，过期分页游标重试从第一页恢复；独立任职入口不展示无权业务导航。

## 自动验证证据

- `output/m01-core-final.log`：真实财务读取2、HTTP1、转案读取2、游标1、架构13，共19项通过。
- `output/m01-package.log`：审计故障阻断与独立财务读取重新验证，打包通过。
- `output/m01-entry-package2.log`：真实 Keycloak + PostgreSQL 验证 transfer-only 视图提示与撤权，1项通过并打包。
- `output/m01-workbench-suite.log`：96文件980项通过（完整前端回归，早于最后的视图提示补充）。
- `output/m01-view-hints-ui.log`：视图提示及会话边界17文件215项通过。
- `output/m01-last-ui.log`：最终窄幅修改3文件52项通过，含401失效、403局部拒绝、transfer-only、游标过期恢复。
- `output/m01-last-build2.log`：最终TypeScript与SPA构建通过。
- `output/m01-last-baseline.log`：R2 development及历史基线通过，原7项非致命发布限制保留。
- 独立只读代码复核发现并已修正过期游标重试问题；最后视图权限提示/403/401补充复核无重要问题。

## 本地环境恢复记录（历史）

第一版已于07:42部署并通过真实登录读取到收款列表，随后发现视图选择器准确可访问名称及transfer-only默认入口边界，已修正并重新测试。最终版构建尚未完成部署，不能按第一版环境宣称最终浏览器验收通过。

再次部署时C盘空间耗尽。未删除业务数据或备份：本次完整备份及四份历史完整备份迁至 `D:/CodexTaskBackups/ontology-law-r2-sales`，沿用原访问权限；四份历史数据库备份已逐一验证SHA256。映射见 `output/m01-backup-relocation.json`、`output/m01-preserved-backups.json`。C盘已释放约2.5GB。

Docker在磁盘耗尽后无响应，`docker ps`与数据库健康检查无返回。最终部署已在停止本项目进程后卡于数据库备份；`before-repair-acceptance-20260928-075522/law_r2_review.dump` 为0字节，明确不是可用备份。等待用户确认重启Docker，恢复后需重新完成有效备份、部署、三宽与办理接续验收、查询计数及延迟记录。M01仍为实施中。

本轮仅通过身份管理API给合成验收任职配置两项查询授权，并创建独立只读合成任职；原财务任职只追加收款读取，不追加办理权。不修改C01–C49业务事实。测试账号只存在本地验收夹具，产品代码无账号/角色名称绑定。

原修复计划合同台账4.3–6.8秒性能项仍未关闭，不因新查询接口实现而宣称解决。

## 2026-09-28 Docker 重启后的最终验收

用户已重启 Docker。本轮启动原有 business-db、identity-db、Keycloak、ClamAV 容器，未重建数据库或卷。最终版本部署成功，运行 JAR 与最终构建 SHA256 一致，SPA index 与构建一致。部署证据 `output/m01-restored-activation.log`、`output/m01-runtime-acceptance.json`。

有效备份：`D:/CodexTaskBackups/ontology-law-r2-sales/before-repair-acceptance-20260928-082707/law_r2_review.dump`，336,527,728 字节，pg_restore --list 可读取且含 TABLE DATA；本轮未执行恢复演练。先前零字节备份仍不计为有效备份。

### 浏览器验收

- sales：原页面验收任职，收款3条、转案2条，分别按需读取详情；不属于当前任职的办理事项不显示操作入口。
- readonly：独立只读任职，只有两项管理查询授权，无工作台/商机台账权限；可读两视图，不显示前往办理、我的待办或商机台账入口。
- finance：原财务任职，从C45收款详情进入原“核对本笔收款”工作卡，保持商机台账入口和原冻结工作台框架，未提交核对结果。
- 三种任职均检查搜索空结果、详情清空、清除筛选与重新查询；1440/390/360宽度均无横向溢出。人工复查桌面收款、窄屏转案、原收款工作卡截图，沿用冻结导航、布局、颜色与共用控件。
- 浏览器无页面脚本错误、无业务API写请求。身份选择与敏感查询仍执行系统原有会话/审计行为。未修改C01–C49业务事实。
- 首次并行浏览器启动中只读Chrome进程退出，单独重新启动通过；不计失败启动为产品验收通过。
- 证据：`output/playwright/m01-{sales,readonly,finance}/result.json`及同目录截图；脚本`output/m01-browser.mjs`。

### 查询记录与限制

27次成功管理请求：浏览器记录124–965ms，中位196ms。此口径是Playwright response事件所取responseEnd（不可用则responseStart），不等于用户端完整操作耗时。服务端32条管理读取记录39–846ms，SQL次数40–577；含准确办理资格复核，仍有后续降低读取次数的空间。当前样本仅为本地3条收款/2条转案的合成夹具，不代表大规模负载结果。无Redis或权限缓存。

本轮只确认M01新增收款/转案查询切片。旧合同台账4.3–6.8秒性能遗留、修复计划整体验收及R2发布准入未据此关闭。M02/M03/A01/V01尚未完成。

最终补充回归：`output/m01-delegated-final.log` 与 Surefire `DelegatedSessionContextHttpIT` 指定方法：1项通过，0失败/错误/跳过。验证代办身份选择仍明确隔离，新增管理入口提示不授予代办管理权限。
