# R2 受控线索来源读取 V1

状态：领域服务、应用接口、部署配置绑定及前端读取客户端已实现；页面入口与实际部署待完成。属于已确认 R2-1 范围，不新增管理模块或业务分类步骤。

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

## 后续接线边界

尚未增加页面入口或来源下拉框，也未部署服务，不能作为生产端到端完成证明。下一步连接已确认的录入/导入组件，并补齐未覆盖的关键界面状态。

界面遵守已确认高保真、现有卡片和管理样式、单一主操作及渐进展开；未覆盖的关键界面状态仍先补高保真确认。R1 验收保持暂停，R2-1 整包保持未完成。
