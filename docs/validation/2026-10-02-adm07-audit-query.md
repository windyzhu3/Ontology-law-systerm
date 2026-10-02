# ADM-07 查询版验证记录

日期：2026-10-02。基线：`d48f9df`；分支：`codex/adm07-audit-query`。

范围：只读查询、详情、既有相关链／更正链；没有导出权限、接口、文件、按钮或审计修改命令。新增独立直接任职权限 `AUDIT_READ`，不加入管理员初始化集，不自动赋权。

## 已执行的源码验证

- 精确合同 successor 测试 6 项通过；移除具名新增项后与基线合同等价，改变权限代码的反例被拒绝。
- 前端 114 文件／1144 测试通过（单进程最终复验）；TypeScript 与显式海华 OIDC 参数 SPA 构建通过。原 AI 表单时序失败单独复跑及完整串行复验通过，未修改该业务代码／测试。
- 架构测试 13 项通过。
- 原身份管理读取、HTTP、会话、授权及初始化回归 81 项集成测试通过；岗位配置与业务任职另 6 项通过。
- 最终版本审计集成共 18 项通过：读取 8、披露事务 6、授权 3、真实 HTTP 1；摘要及响应序列化单元 5 项通过。覆盖审计追加失败、提交确认未知、审计期间权限到期、游标绑定、源对象和审计事实 DENY、基表拒绝、安全摘要及长更正链分页。
- 最后一次全后端单元回归运行 499 项，4 项失败、0 错误、4 项跳过；失败仅为下述已在基线复现的旧门禁。
- 实际环境暴露的兼容性修复已验证：既有工作卡 R1／R2 摘要使用冻结字段顺序核验；终页省略可选 nextCursor；SPA 精确审计深链接可进入。搜索使用参数化安全名称／闭合标签候选，再执行准确授权和最终匹配，保留 Unicode／安全回退及 SQL 通配符字面量覆盖，未放宽 5 秒扫描预算。
- 查询／详情／空结果每次恰有一条披露审计；Command、Receipt、Event、Outbox、Task 基数不变；记录静态筛选类别与本页数量，不记录搜索词／原请求／原响应。
- 数据库迁移、manifest 和既有 GRANT 无改动。

## 原有门禁

完整后端单元回归首先发现五项失败。其中会话字段的本次具名增量已准确更新并通过。其余四项在独立 `d48f9df` 工作树实跑 29 项测试后同样复现：

1. `OpenApiContractTest.freezesNamedR1AndRegisteredR2Operations`：此前岗位配置的五个操作未纳入旧断言。
2. `OpenApiContractTest.successfulReceiptsRejectRejectionsAndBindExactCompletionFacts`：既有 PublicFactRef 57／58 分支差异。
3. `CommandEnvelopeTest.fourteen_identity_types_are_human_internal_admin_and_reject_service`：既有命令数 76／80 差异。
4. `RuntimeSchemaVersionTest.additive_contract_source_schema_is_registered_without_weakening_expected_digests`：既有 schema 注册与旧断言不一致。

不放宽这些旧门禁，不据此宣称完整后端全绿，不绕过门禁合并 main。本次只准确加入三个审计 GET、一个权限和一个可选会话资格。

## 独立审查与海华环境

一次只读整分支独立审查已完成，无 Critical，六项 Important 均已修复并通过对应验证：未知分类拒绝、分页 lookahead 的披露前后复验、原命令恢复优先、历史起止时间、注册操作／结果的业务说明、长更正链逐页读取。更正链遍历有 5 秒／1000 节点上限，超限整次拒绝，不返回截断结果。

- 已部署至保留海华环境 `https://localhost:20544`；当前后端、SPA、服务脚本摘要与部署登记逐字核对一致，数据库 schema／manifest 无变化。
- 完整真实浏览器验收通过：本部列表、详情、既有两类关系链、稳定分页、历史日期、无匹配搜索、越部门／根范围拒绝、无权菜单隐藏、旧详情清理。1440px 桌面七列完整，800px 窄屏只在表格内滚动，键盘焦点可见；截图均已实际查看，无脚本错误。
- 十账号实际登录及原入口检查全部通过；同一浏览器 sys_manager01 登出再登录 sales_manager01 通过。撤销临时权限后十账号的 canReadAuditRecords 均为 false，未自动授予管理员。
- 多轮验收与修复共七笔临时 sales05／销售二部 AUDIT_READ，均通过既有授权命令授予并以准确版本撤销；14 个命令均有确定成功回执，七笔授权均为 REVOKED，现有 ACTIVE 审计授权为零。此前失败运行的证据完整保留。
- 排除这七笔已撤销的新授权后，原主体、组织、任职、授权、线索、商机、合同、移交及接案快照与升级前完全一致；保留原十个 HUMAN 任职、八个已接案事项。执行槽／回执仅各增加已知的 14 笔授权或撤销命令，按租户及执行槽关联核对准确成员；Event／Outbox／Task 增量均为零。
- 披露审计未保存搜索词、原请求或原响应。升级前检查点 ADM07_BEFORE 已保存并核验摘要，未进行恢复演练；旧制品及原始事实保留。私有请求、回执、制品摘要、截图及检查点位于忽略目录，不进入 Git。

审计访问需要通过既有身份管理对本人任职另行授予 AUDIT_READ 及明确组织范围；仅系统管理员身份不能进入审计查询。

叠加草稿 PR：[PR #24](https://github.com/windyzhu3/Ontology-law-systerm/pull/24)，基于 codex/bulk-authority-grants；已推送并附着当前任务，未合并 main。
