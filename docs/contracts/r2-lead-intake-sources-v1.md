# R2 受控线索来源读取 V1

状态：领域服务、应用接口、共用工作台的手工/CSV/XLSX录入及来源读取已实现。Linux 初始化的本人自动来源按已确认具名增量实施；实际 Linux 发布与完整业务验收以该实施计划及证据报告为准，不把组件测试作为部署证明。

## 实现

`LeadIntakeSources` 接收部署方可信配置的来源名称及现有录入字段元数据，来源账号必须已在 `R1SourcePolicyRegistry` 注册。目录最多 50 项，账号及显示名称不得重复；结果不可修改。名称供后续来源选择展示，内部代码不作为用户手填字段。

读取仅允许当前本人 HUMAN 任职，在调用方事务中获取现有租户授权读锁。按当前租户解析来源组织，复用录入命令的 `SOURCE_INTAKE_OWNER / LEAD_CAPTURE` 授权选择及最终校验。组织不存在、权限撤销、任职停用或主体不匹配时不返回来源。读取不创建线索、待办、命令或审计业务事实。

来源可见不代表永久录入许可；实际提交仍由原 `CAPTURE_LEAD` 命令重新校验，并沿用重复提交保护和后继待办逻辑。目录中的现有 `serviceCategoryCode` 为录入元数据，不改变“案管审核接收后建案、建案后再分类”的业务约定。

## 验证

真实 PostgreSQL 定向运行 `LeadIntakeSourcesIT,LeadAssignmentIT`，17 项通过；`ArchitectureTest` 13 项通过。包含授权来源读取、撤权、停用任职、跨租户、非法调用身份、配置检查、事务要求、读取来源后录入并生成首联待办、同一命令重放不新增事实，以及读取后撤权阻止录入。

## 应用接线

`GET /api/v1/leads/intake-sources` 使用现有 Bearer 认证、任职选择和权限机制。本人 HUMAN 读取返回 `{ sources: [...] }`，无授权来源返回空列表；SERVICE 和代办身份被拒绝。来源读取由 QUERY 事务承接，先取得现有业务读锁，再完成目录授权读取；异常不返回部分目录。此接口只披露受控配置元数据，不读取客户或线索事实，不伪造工作卡或身份披露审计。

来源账号使用注册表既有的大小写 ASCII 编号规则，其余四个代码使用既有 `Code64` 大写规则。精确增量合同见 [R2-LEAD-INTAKE-SOURCES-V1](../baseline/R2-LEAD-INTAKE-SOURCES-V1.md)；保留原 R1 冻结校验。

部署可在既有 `ols.api` 下显式添加配置，例如：

```yaml
intake-sources:
  - source-account-code: sales_intake
    display-name: 手工录入
    source-channel-code: MANUAL
    service-category-code: GENERAL_INTAKE
    jurisdiction-code: CN
    urgency-code: NORMAL
```

示例账号必须另已注册到既有 `sources` 策略，并配置正确来源组织及录入授权；本增量不自动创建账号、组织或授权。不配置 `intake-sources` 时目录为空，保留既有部署兼容性。不得把客户信息写入来源名称。

前端 `createLeadIntakeApi.sources` 沿用会话传输，发送 `no-store` 请求并验证响应禁止存储、字段完整性、数量上限和目录唯一性。会话变更后不接受旧响应；读取不占用或清除尚未确认的写入恢复记录。

## 本人自动来源增量

[LINUX_HUMAN_INTAKE_BINDING_V1](../baseline/LINUX-HUMAN-INTAKE-BINDING-V1.md) 在原 envelope 增加可选 `sourceSelection`。已配置租户返回 `BOUND_TO_PRINCIPAL`，只披露本人获权来源并显示只读名称；历史响应缺字段时保留可选模式。绑定模式的 0/1 条边界由服务器过滤和客户端严格解析共同执行。

`ols.api.human-intake-bindings` 的每项包含 `tenant-id`、`principal-id`、`source-account-code`。初始化通过真实 IdP subject 和 HUMAN 管理命令解析准确 UUID 后安装可信配置，不接受浏览器提供 username 或绑定。配置本身不授权；实际捕获在原回执判断后再次校验本人来源，旧事实和原回执不被改写。

手工和文件导入复用同一来源元数据。退出、切换账号或任职清除当前草稿与预览并忽略旧响应；保留原恢复标记，不为新账号自动重发。共用菜单、页面结构和按钮样式保持既有设计。
