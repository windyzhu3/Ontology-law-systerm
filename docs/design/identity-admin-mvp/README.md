# 身份与组织管理MVP高保真验收集

> 状态：三批已冻结（7张）
> 用户确认日期：2026-08-27（第一、第二、第三批）

本目录是身份与组织管理页面的视觉证据索引，不是新的产品规格。页面属于同一个SPA内受保护的“管理模式”，不建设独立管理应用；字段、状态和生命周期仍以52＋2冻结合同为唯一权威。

## 共同视觉基线

- 沿用“律所工作助手”统一顶栏、管理侧栏和响应式主内容区。
- 暖白背景、石墨文字、翡翠绿唯一主强调色、浅薄荷选中态、1px边框和弱阴影。
- 可执行管理页只有一个视觉上占主导的主操作；只读披露页不强造主操作，次级受控操作不得与主操作竞争。
- 面向管理员展示业务含义，不泄漏UUID、修订号或内部枚举值。
- 业务范围严格停留在身份域MVP，不延伸到HR、人事档案、薪酬绩效、传统RBAC矩阵、动态策略配置或指标看板。

## 已冻结基准（7张）

| 编号 | 页面 | 唯一主操作 | 冻结边界 | 高保真 |
|---|---|---|---|---|
| ADM-01 | 用户与身份主体 | 新增身份主体 | 只管理HUMAN/SERVICE身份、身份源、显示名称和生命周期；不保存凭据、Token、邮箱或电话，不把身份等同于任职或授权 | [查看](frozen/ADM-01-identity-principals.png) |
| ADM-02 | 组织架构 | 新增组织 | 只维护稳定组织代码、名称、父子层级和ACTIVE→CLOSED单向关闭；组织节点不等同于人员编制、任职或授权 | [查看](frozen/ADM-02-organization-units.png) |
| ADM-03 | 任职管理 | 新建任职 | 只管理身份主体、组织、静态岗位和创建时冻结的任期；岗位不直接授予业务权限，不扩展HR或劳动关系能力 | [查看](frozen/ADM-03-appointments.png) |
| ADM-04 | 直接授权 | 新增直接授权 | 只向一个准确任职授予一项静态权限，并冻结组织范围根和有效期；授权只能单向撤销，不支持编辑、暂停、恢复、续期或角色权限矩阵 | [查看](frozen/ADM-04-authority-grants.png) |
| ADM-05 | 代理授权 | 新增代理授权 | 只把一项准确直接授权限时、限范围地一跳委托给另一任职；范围不得扩大，不等同于Task转派或HR代理 | [查看](frozen/ADM-05-delegation-grants.png) |
| ADM-06 | 对象访问 | 新增对象规则 | 只为一个Principal在一个准确业务对象版本上设置允许或限制；限制优先，对象规则不能单独产生业务权限 | [查看](frozen/ADM-06-object-access-grants.png) |
| ADM-07 | 审计记录 | 无业务主命令；仅受控导出当前结果 | 只按可信时间和授权范围查询只读审计轨迹，查询与导出均先审计后披露；不提供新增、编辑、删除、更正、重试或回滚，不披露会话HMAC、客户端IP密文或执行节点 | [查看](frozen/ADM-07-audit-records.png) |

## 合同依据与冲突处理

- ADM-01映射`identity.principal`。
- ADM-02映射`identity.organization_unit`。
- ADM-03映射`identity.appointment`。
- ADM-04映射`identity.authority_grant`。
- ADM-05映射`identity.delegation_grant`。
- ADM-06映射`identity.object_access_grant`。
- ADM-07只读披露映射`audit.audit_entry_classified_v`，不可变证据源为`audit.audit_entry`。
- 详细字段、状态、可变性和约束见[52＋2完整字段合同](../../../database/schema-contract-52-plus-2/generated/field-contract.md)。

如高保真文案或可见操作与52＋2合同冲突，以字段合同为准；修订视觉稿时必须保持本目录已冻结的布局、组件和MVP边界一致。
