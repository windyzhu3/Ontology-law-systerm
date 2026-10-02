# ADM 07 审计记录查询实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在当前 SPA 完整实现有独立任职权限、先审计后披露的只读审计查询、详情及既有关系链，不实现导出。

**Architecture:** API 从可信 Actor 进入 Query Facade，Audit Owner 只读分类视图，Identity Owner 提供范围与准确事实鉴权。事务在同步披露审计提交确认后才交出安全 DTO；React 使用现有管理布局及服务端稳定游标。

**Tech Stack:** Java 25、Spring Boot、jOOQ、PostgreSQL、现有 capability 事务；OpenAPI 生成接口；React 19、TypeScript、Vitest、既有受保护浏览器验收工具。无新增产品依赖。

**Spec:** [查询设计](../specs/2026-10-02-adm07-audit-records-design.md)，用户已明确“只做查询，不做导出”。

## 全局约束

- 只增加 `AUDIT_READ`，不增加导出权限、端点、按钮、文件或下载流程。
- 只读分类视图，不读审计基表，不扩大数据库 GRANT，不增加表／物化视图，不重写历史迁移。
- 默认最近 7 天、单次最长 31 天、默认每页 20／最大 50 条、搜索最大 100 字符。
- 当前 HUMAN 本人任职、独立直接授权、逐条四轴复验、DENY 优先、提交确认后披露。
- 本人身份管理权限不等于审计权限；审计专用权限也不等于身份管理权限；初始化不得自动赋权。
- 原工作树／运行环境保留，以 `d48f9df` 为实施基线；复用现有隔离工作树，创建 `codex/adm07-audit-query` 分支。文档与真实验证证据分开，私有证据不进 Git。

## 审查重点

1. 审计专用用户的默认入口与账号切换：不能进入无权身份目录或残留上一账号记录。
2. 名称含控制字符或摘要夹带自然键 HMAC：安全搜索／说明不能把原始摘要变成侧信道。
3. 组织关闭／调整、权限到期和游标重放：缓存资格不能替代最终数据库时钟复验。
4. 相关链包含别的组织条目：不能通过链节点、计数或错误透露无权记录。
5. 读取事务提交结果未知：不能先返回记录，也不能继承回执恢复的免查询审计例外。

## 文件职责

- 合同：`contracts/openapi/ontology-law-api.yaml`；`scripts/baseline/adm07_audit_query_contract.py` 及对应测试，准确限定新增边界。
- 身份授权：新增 `identity/AuditReadAuthorization.java` 和内部持久化实现；修改 `IdentityCommands.java` 的可授予清单，不修改初始化管理权限集。
- 审计读取：新增 `audit/AuditRecordReader.java`、`audit/AuditRecordProtection.java`、`audit/internal/persistence/JooqAuditRecordReader.java`、`audit/internal/AuditRecordSummary.java`。
- 披露事务：新增 `execution/AuditReadRuntime.java`；扩展 `audit/AuditAppender.java` 及 `audit/internal/persistence/JooqAuditAppender.java` 的具名披露条目。
- API 接线：新增 `api/AuditRecordsController.java`；接入 `R1ApiDeployment.java`／`R1ApiConfiguration.java` 和会话资格提示。
- 前端：新增 `features/identity/AuditRecordPage.tsx`、`auditRecordApi.ts`、`useAuditRecordQuery.ts` 和测试；修改现有路由、公共侧栏、会话解析、业务权限标签和现有管理样式。

上述 Java 路径均位于 `backend/src/main/java/io/github/windyzhu3/ontologylaw/`；前端路径均位于 `apps/workbench/src/`，测试遵循相应现有目录。

### Task 1: 封闭合同与独立资格

**Interfaces:** `GET /api/v1/admin/audit-records`；`GET /api/v1/admin/audit-records/{auditRecordId}`；`GET /api/v1/admin/audit-records/{auditRecordId}/related`，关系只允许 CORRELATION／CORRECTION。列表查询为 start、end、scope、result、search、limit、cursor；scope 只允许 TENANT／ORGANIZATION／OBJECT／SECURITY，result 只允许 SUCCEEDED／NO_CHANGE／REJECTED／FAILED。安全 DTO 不提供完整 JSON。可选会话字段为 `canReadAuditRecords`。

- [x] 新建合同反例测试，断言只有三个只读 GET、无导出／写入路径、只有 `AUDIT_READ` 新权限、禁止字段不在 DTO；观察 RED。
- [x] 添加封闭 OpenAPI 与准确 successor 投影，仅移除具名新增项后必须与 `d48f9df` 原合同逐字等价；不放宽旧校验。
- [x] 更新生成 Java／前端类型及会话封闭解析。后端生成使用既有 Maven generate-sources，前端使用已有合同生成脚本。
- [x] 编写并验证“仅有审计权限可进入审计页，但无身份目录资格”“SERVICE／代办不具备审计资格”的真实会话 HTTP 测试。
- [x] 增加 `AUDIT_READ` 可授予标签，验证岗位创建和管理员初始化不产生新授权；执行相关测试并提交合同／资格增量。

### Task 2: Audit Owner 安全读取及四轴鉴权

**Interfaces:** `AuditRecordReader.Query(Instant start, Instant end, String scope, String result, String search, int limit, String cursor)` 定义已校验查询，`AuditRecordReader.Position(Instant trustedAt, UUID id, Instant watermark)` 定义服务端分页位置；内部响应 `Page(List<SafeRecord> items, SafeRecord lookahead)`；执行层按 Actor／筛选／授权依赖加密生成 `nextCursor`。`SafeRecord` 仅含安全显示字段、准确只读选择器及内部门限核验值，不含原始摘要。`AuditReadAuthorization.scopes(Connection, Actor)` 提供当前获权组织；`authorize(Connection, Actor, Subject auditFact, Subject sourceFact, UUID recordScope)` 返回准确授权快照。`AuditRecordReader.list(Connection, Actor, Query, Position)` 和 `find(Connection, Actor, UUID)` 只返回 Audit Owner 内部安全记录，业务 API 不接触原始摘要。

- [x] 新建 `identity/AuditReadAuthorizationIT.java` 和 `audit/AuditRecordReaderIT.java`：越租户／组织、无根范围系统条目、失权、DENY、SERVICE、代办、摘要完整性损坏及未知 schema，先观察 RED。
- [x] 在 Identity Owner 实现组织范围与当前四轴判断，在 Audit Owner 仅选择 classified view 的明确列；禁止 SELECT * 和基表读取。
- [x] 实现闭合摘要投影，安全动作／对象类别标签；HMAC、完整 JSON、正文、会话／IP／节点信息从读取端口边界内清除。
- [x] 实现稳定 `(trusted_at, audit_entry_id)` 游标、水位及授权绑定，默认／上限来自全局约束。搜索只使用允许的显示名称及类别字段。
- [x] 实现相关／更正链准确根节点检查和逐条鉴权，每页最大 50 条；更正链遍历超时或超过 1000 个获权节点时整次拒绝，不返回截断结果，不返回未授权计数或链节点。
- [x] 真实 PostgreSQL 测试验证 QUERY 无审计基表权限、历史迁移和 GRANT 不变；相关测试 GREEN 后提交。

### Task 3: 同步披露审计与 HTTP 接线

**Interfaces:** `AuditReadRuntime.read(Connection, Actor, Query)`、`detail(Connection, Actor, UUID)`、`related(Connection, Actor, UUID, Relation, Query)`；返回最小响应，内部先完成提交确认。新增 `AuditAppender.AuditRecordDisclosureEntry` 仅记录准确允许引用、筛选类别和本页数量，不保存原查询关键词／请求或响应。

- [x] 新建 `execution/AuditReadRuntimeIT.java` 及 `api/AuditRecordsHttpIT.java`：审计追加失败、提交确认丢失、读取期间授权到期／撤销、空结果也有查询审计、未知目标安全拒绝，观察 RED。
- [x] 按业务共享栅栏→身份共享锁→QUERY 读取／逐条授权→最终复验→AUDIT 追加→提交确认顺序接线；不借用回执恢复元数据例外。
- [x] 每次查询／详情／相关链恰好一条披露审计，链节点逐条判定；没有 Command／Receipt／Event／Outbox／业务任务增量。
- [x] 接入可信认证、租户服务、HTTP no-store、参数上限和封闭错误；禁止缓存 304 披露绕过审计。
- [x] 运行 `./mvnw.cmd -f backend/pom.xml -Pit -Dtest=AuditRecordSummaryTest -Dit.test=AuditReadAuthorizationIT,AuditRecordReaderIT,AuditReadRuntimeIT,AuditRecordsHttpIT verify`，审计失败无数据体和所有安全反例必须 GREEN 后提交。

### Task 4: 公共管理页面与只读详情

**Interfaces:** `auditRecordApi.list/detail/related(session, query, signal)` 消费生成 GET；`useAuditRecordQuery` 持有当前筛选、已加载页和前页游标，不保存跨 Actor 记录；页面使用现有 IdentityAdminLayout。

- [x] 新建 DOM 集成测试：筛选／分页／选择详情、关系链、取消过期请求、401／403、切换 Actor 清理、audit-only 默认入口以及没有导出／写操作，观察 RED。
- [x] 增加“审计记录”统一图标菜单、共同顶栏和受保护路由；只具审计资格默认进入审计页，不能落到身份主体页。
- [x] 按原 ADM-07 实现搜索与时间／范围／结果控件、七列表格、右侧只读详情、安全摘要、相关／更正链和校验说明；移除冻结示例的导出控件。
- [x] 当前页数量和已加载／获权说明真实，不编造总量；无权限不显示业务数据，筛选或身份变化清空旧详情与链。
- [x] 运行完整 Vitest、TypeScript 和显式海华参数 SPA 构建；查看桌面／窄屏截图、焦点与按钮统一，GREEN 后提交。

### Task 5: 独立审查与完整验收

- [x] 按 requesting-code-review 技能安排只读独立审查，覆盖权限、逐条披露、游标、摘要、关系链及提交未知；修复全部 Critical／Important 并复核。
- [x] 完成原身份管理、单项／批量授权、10 个账号登录、原审计写入和业务回执基数回归；旧门禁失败单独报告，不改断言掩盖。
- [x] 在保留海华实例升级前保存检查点、原事实行和精确部署摘要；只部署已验证后端／SPA，历史制品保留，数据库无需新迁移。
- [x] 实际浏览器使用管理员通过既有授权命令临时授予合成 `sales05` 销售二部 `AUDIT_READ`；验证本部查询、详情和关系链，越部门／无权入口拒绝，查询失败不披露，界面无导出／写操作。
- [x] 使用准确版本撤销临时验收授权。不得直接 SQL 赋权，不授予原管理员新权限，不扩大原销售任职常驻权限。
- [x] 核对原主体／组织／任职／授权及业务事实未变，新增验收授权已撤销；保存命令、查询审计、截图、结果及制品证据在忽略目录。
- [x] 写验证记录并 GitHub CLI 推送叠加 PR；附着 PR，不绕过旧门禁合并 main，不清理保留工作树或运行环境。

## 执行方式

建议在当前隔离工作树内使用 `superpowers:executing-plans` 逐项实施，由当前执行者完成；只在独立审查阶段使用审查代理。用户已于 2026-10-02 确认该实施计划，由当前会话实施。
