# T01 负责人异常与责任交接：实施及验收记录

日期：2026-09-15

状态：IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED。用户已确认 E/F 高保真并重启 Docker；T01 整体开发验收已完成。生产观察默认关闭，R1 PAUSED、R2 NOT_GRANTED。不将开发测试等同生产发布或完整销售 MVP 完成。

## 当前交付范围

T01 在既有 R1 线索联系形成商机的基础上，连接负责人异常发现、主管管理处置、真实责任交接以及新负责人后续办理，不增加报价、合同或案管范围。

- **发现与复验**：具名 SERVICE 在当前授权范围有界扫描。失效原负责人不会在扫描前被过滤。异常按活动周期保留不可变历史；恢复正常使用真实持久化 audit id/hash，关闭引用准确商机版本。SOURCE_INCONSISTENT 只能协调，不能借转交掩盖来源错误。
- **管理读取**：主管列表、准确详情、当前合格接收人；独立运营摘要仅七个允许字段。READ、RESOLVE、OPERATIONS_READ 与 SERVICE DISCOVER 分开，复验当前身份、组织及准确对象 DENY。管理用户不必拥有销售工作卡权限。
- **正式处置**：协调记录原因及复查时点，保持未解决；交接原子保存处置、责任链、任务、审计、事件和回执。重试恢复原键原输入，不用新请求覆盖未知结果。
- **业务接续**：冻结 Opportunity.Owner 和来源 Assignment 不改。旧 OPEN/WAITING 卡凭具名交接依据 CANCELLED，不伪造 DONE/进展。新卡保留原 SLA/等待时点，不复制旧草稿；首次无卡按真实承接时刻起算。新负责人可以读取工作卡、从空草稿提交进展并继续后继与到期恢复。
- **可靠调度**：OWNER_EXCEPTION 独立持久检查点，发 HTTP 前保存原请求；失锁、坏状态与未知结果停止新派发。R2C2 可读旧 INITIAL/DUE 正文。受限诊断计数保留并形成 DEGRADED，不误报 READY。只在显式启用时运行该循环。
- **管理界面**：沿用 E/F 冻结布局、既有卡片和管理样式；人工确认单一主操作，过期重新读取，未知结果仅核对原结果。授权管理仅补既定权限的严格枚举与中文标签，没有自动授予权限。

## 验证结果

| 检查 | 当前结果 | 日志 / 证据 |
|---|---|---|
| Schema 单元与生成校验 | 64 项通过，generate --check 通过 | `output/t01-schema-validation-evidence-tests.log`、`output/t01-schema-validation-evidence-generation.log` |
| PostgreSQL / Flyway / jOOQ | PostgreSQL18.6 应用25个迁移；27个POJO由真实数据库生成 | `output/t01-jooq-v900-generation.log` |
| 负责人基础业务 | Scope2、Checks6、Handoff4、Followup9、Persistence11 项专项通过 | `output/t01-integrated-business-it.log`、`output/t01-formal-http-worker-it.log` 及全量复核日志；早轮日志含当时失败，不能把整轮记为通过 |
| 正式命令与业务接续 | 命令6项通过；包括实际读卡、草稿、进展、后继、再次交接、旧回执恢复与当前 DENY | `R2OpportunityOwnerExceptionCommandIT`，见全量及定向日志 |
| 管理 HTTP / 身份 | HTTP6项、真实Keycloak管理准入1项通过 | `R2OpportunityOwnerExceptionHttpIT`、`R2OwnerExceptionSessionHttpIT` |
| 内部 HTTP / 检查点 | 发现2、内部HTTP2、检查点5、Worker client6项通过 | 对应 IT，见全量日志；覆盖TLS、丢响应后原请求恢复 |
| 前端全量 | **48文件 / 623测试通过**；类型检查与构建退出0 | `output/t01-final-frontend-tests.log`、`output/t01-final-frontend-typecheck.log`、`output/t01-final-frontend-build.log` |
| 冻结布局 | 桌面1487与360px、16个状态通过；无横向溢出，确认及未知结果单一主操作 | [视觉记录](../../../output/t01-visual-review.md) |
| 真实组件→HTTP→数据库 | 浏览器测试通过；正式POST一次、200、刷新为已解决，再打开无转交按钮；数据库确认唯一新卡、冻结原人、零伪进展 | [浏览器证据](../../../output/t01-live-browser/t01-live-ui-evidence.json)、`output/t01-browser-real-http.log` |
| 完整后端回归与修复复测 | 197项单元测试通过；全量扫描921项IT发现9处旧断言失败，修正后定向62项全部通过。按类合并当前证据为105类/916项，零失败/错误/跳过；不是声称原全量命令退出0 | `output/t01-complete-maven-verify.log`、`output/t01-final-targeted-retest.log`、[当前逐类证据](../../../output/t01-current-backend-evidence.json) |
| 两项跨命令竞态 | 2项通过：交接与进展、交接与到期恢复竞争，一方成功、一方按旧期望拒绝，唯一有效卡且无伪进展 | `R2OpportunityHandoffConcurrencyIT`，`output/t01-final-targeted-retest.log` |
| 生产 Worker 组合 | 1项通过：真实jar/TLS/数据库，显式启用、撤权后降健康、正常停止；不启动INITIAL/DUE，实际受限Worker登录 | `OwnerExceptionWorkerAssemblyIT`，`output/t01-complete-maven-verify.log` |
| 精确合同门禁 | 4项新增传输变异及32项相关投影/旧R1回归通过；发展门禁PASS，原7项非致命发布阻塞保持 | `output/t01-owner-exception-transport-tests.log`、`output/t01-transport-projection-regression.log`、`output/t01-final-development-gate.log` |

浏览器使用真实 React 组件、真实 HTTP 与隔离 PostgreSQL，身份由测试夹具提供，不模拟成功响应；它不冒充生产 OAuth 全旅程。真实 Keycloak 管理准入另有 HTTP IT 覆盖。复现见 [e2e/T01-ACCEPTANCE.md](../../../e2e/T01-ACCEPTANCE.md)。

## 本轮发现和修正

1. PostgreSQL 时间类型绑定、fixture 初始任职状态与迁移版本钉住不一致：修正后重新运行真实事务测试。
2. 健康复验在 COMMAND 角色读取 AUDIT 事实：改为 QUERY 准备、AUDIT 持久化、COMMAND 保存业务结果及最终 QUERY 验证，不扩大数据库角色权限。
3. 交接后的责任依据被原工作卡披露门禁拒绝：只为准确 HUMAN R2 商机权限加入具名 handoff 事实，不放宽 R1 或其他事实。
4. 商机关闭后准确责任链丢失：仅支持已关闭根的准确 +1 版本封存，任意版本跳跃继续拒绝。
5. 旧成功进展回执被当前有效任务门禁误拒绝：分离历史回执准确事实读取与当前新写入检查；恢复仍受当前权限和准确历史 DENY 约束。
6. InternalApiClient 未返回新增路由响应正文、无发现 scope 被映射503、受限诊断计数丢失：补准确路由、初始授权及有界诊断传递，真实HTTP/重启和codec测试覆盖。
7. 浏览器初版断言期望已解决记录消失，与保留历史的合同不符：修正测试为“已解决且不可再次转交”，未修改生产行为迁就测试。
8. 全量回归发现旧会话字段计数仍为9，以及旧投影测试误遍历R2专属事件：改为准确10字段并校验新增权限标志；R1测试按队列归属选取并固定14类事件。生产事件路由未修改，27项会话和25项投影复测全部通过。
9. 旧数据库能力测试仍要求54张表：更新为准确58张，额外固定检查点与三张T01事实表，所有权与受限登录断言保持。8项复测全部通过。

失败原始日志保留，不删除失败测试、不改生产安全规则迁就夹具。Docker重启前 `output/t01-foundation-it.log` 是基础设施阻断的历史结果，已被本轮真实执行接续。

全量命令为 `./mvnw.cmd -f backend/pom.xml -Pit verify -Dt01.browser.node=<Node24绝对路径>`，运行34分49秒，结束于09:39:41；它因上述旧断言退出1。随后 `-Pit verify -Dit.test=DelegatedSessionContextHttpIT,R1ProjectionConsumerIT,CapabilityRoleExecutorIT,R2OpportunityHandoffConcurrencyIT` 于09:43:40退出0，62项通过，同时重新执行197项单元测试。两轮均限制Maven堆384MB、测试JVM堆512MB和2个处理器。只修改测试与文档后执行定向复测，未把原非零运行改写为成功。

## 治理与边界

ADR：[ADR-0017](../../adr/ADR-0017-r2-owner-exception-handoff.md)。迁移只新增 V900，不改 V001–V890。58张物理表，25个迁移，三张具名新业务事实；Worker没有新增业务SQL写能力。

- manifest SHA-256：`6d0eec2e6672f882de768502ca25f5b5453e85b9e4cd4d02d538b6949963004d`
- field contract SHA-256：`bd3518d89a3b35b6f7bdce4f455ed816dcf44c3320c99a3c09ef5a0d1b01c4da`
- V900 SQL SHA-256：`366e370330858db713bcd04a53c4542ba47175e53527f581cff29d14f61a9039`

生产配置 `ols.worker.owner-exception-observation-enabled` 默认 false。真实 SERVICE 的 DISCOVER 授权和启用属于既有受控部署配置；本轮没有自动授权、提交、推送或部署。R1 PAUSED / R2 NOT_GRANTED 保持；未推进 T02，也不声称报价至合同签署、案管接收的全部 MVP 已完成。
