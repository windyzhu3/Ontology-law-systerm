# 待办驱动律所系统完整字段合同：52业务表＋3技术表

本文件由静态合同机械生成。`flyway_schema_history`由Flyway管理，因此仅记录管理边界，不重复描述其版本相关物理结构。

## `identity`

身份域：保存租户、身份、组织、任职及授权锚点；不保存凭据，也不替代命令时动态授权复验。

- Fact Owner：`IdentityRuntime`

### `identity.tenant`

一行代表一个租户边界根，由身份域拥有；仅显示名称、生命周期状态和关闭事实可CAS更新，不代表客户、律所法律主体或授权本身。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id)`
- 允许更新字段：`display_name, state, closed_at, revision`
- Write-once字段：`closed_at`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → SUSPENDED`, `SUSPENDED → ACTIVE`, `ACTIVE → CLOSED`, `SUSPENDED → CLOSED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：由应用生成的UUIDv7，是所有租户数据边界的根。 |
| `tenant_code` | `varchar(64)` | 否 | `—` | 租户代码：外部配置使用的稳定非敏感代码，创建后不可修改。 |
| `display_name` | `text` | 否 | `—` | 租户显示名称：仅供界面展示，可受控修改，不承载法律主体真相。 |
| `state` | `varchar(64)` | 否 | `—` | 租户状态：ACTIVE、SUSPENDED或CLOSED；关闭后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：租户根首次持久化的数据库时间，创建后不可修改。 |
| `closed_at` | `timestamptz(6)` | 是 | `—` | 关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `uq_tenant__tenant_code`（`UNIQUE`：`tenant_code`）：租户代码在全系统唯一，防止外部配置串租户。
- `ck_tenant__state`（`CHECK`：`state IN ('ACTIVE', 'SUSPENDED', 'CLOSED')`）：租户状态只能取冻结的三个生命周期值。
- `ck_tenant__closed_fields`（`CHECK`：`(state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)`）：关闭一致性：只有CLOSED租户具有关闭时间，且CLOSED必须具有关闭时间。
- `ck_tenant__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。

索引：

- `ix_tenant__state`：列`(state)`；唯一=`否`；谓词=`None`。按生命周期状态执行租户运维筛选。

### `identity.principal`

身份主体：一行锚定一个可认证的人或服务身份，由身份域拥有；仅显示名称和生命周期可CAS更新，不保存凭据、Token或任职授权。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, principal_id)`
- 允许更新字段：`display_name, state, disabled_at, revision`
- Write-once字段：`disabled_at`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → SUSPENDED`, `SUSPENDED → ACTIVE`, `ACTIVE → DISABLED`, `SUSPENDED → DISABLED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `principal_id` | `uuid` | 否 | `—` | 身份主体标识：由应用生成的UUIDv7。 |
| `principal_kind` | `varchar(64)` | 否 | `—` | 身份主体种类：HUMAN或SERVICE，创建后不可修改。 |
| `identity_provider_code` | `varchar(64)` | 否 | `—` | 身份提供方代码：标识静态配置中的认证来源，不保存提供方密钥。 |
| `external_subject_hmac` | `bytea` | 否 | `—` | 外部主体HMAC：对提供方主体标识做租户密钥HMAC后的32字节值，不保存外部原文。 |
| `display_name` | `text` | 否 | `—` | 显示名称：非权威界面标签，可受控修改，不作为身份匹配依据。 |
| `state` | `varchar(64)` | 否 | `—` | 身份状态：ACTIVE、SUSPENDED或DISABLED；禁用后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：身份主体首次持久化的时间，创建后不可修改。 |
| `disabled_at` | `timestamptz(6)` | 是 | `—` | 禁用时间：状态首次变为DISABLED时一次写入；未禁用为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_principal__principal_kind`（`CHECK`：`principal_kind IN ('HUMAN', 'SERVICE')`）：身份主体种类只能为人员或服务身份。
- `ck_principal__state`（`CHECK`：`state IN ('ACTIVE', 'SUSPENDED', 'DISABLED')`）：身份状态只能取冻结的生命周期值。
- `uq_principal__provider_subject`（`UNIQUE`：`tenant_id, identity_provider_code, external_subject_hmac`）：同一租户和身份提供方内，一个外部主体HMAC只锚定一个身份主体。
- `ck_principal__disabled_fields`（`CHECK`：`(state = 'DISABLED' AND disabled_at IS NOT NULL) OR (state <> 'DISABLED' AND disabled_at IS NULL)`）：禁用一致性：只有DISABLED身份具有禁用时间，且DISABLED必须具有禁用时间。
- `ck_principal__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_principal__external_subject_hmac_length`（`CHECK`：`octet_length(external_subject_hmac) = 32`）：摘要格式：external_subject_hmac必须保存32字节的规范二进制值。

物理外键：

- `fk_principal__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

索引：

- `ix_principal__state`：列`(tenant_id, state)`；唯一=`否`；谓词=`None`。按租户和生命周期状态查找身份主体。

### `identity.organization_unit`

组织单元：一行锚定租户内一个组织节点，由身份域拥有；名称、上级和生命周期可CAS更新，不代表任职或权限。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, organization_unit_id)`
- 允许更新字段：`display_name, parent_organization_unit_id, state, closed_at, revision`
- Write-once字段：`closed_at`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → CLOSED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `organization_unit_id` | `uuid` | 否 | `—` | 组织单元标识：由应用生成的UUIDv7。 |
| `unit_code` | `varchar(64)` | 否 | `—` | 组织单元代码：租户内稳定唯一代码，创建后不可修改。 |
| `display_name` | `text` | 否 | `—` | 组织单元显示名称：可受控修改的界面标签。 |
| `parent_organization_unit_id` | `uuid` | 是 | `—` | 上级组织单元标识：同租户复合自外键；根节点为空。 |
| `state` | `varchar(64)` | 否 | `—` | 组织单元状态：ACTIVE或CLOSED；关闭后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：组织单元首次持久化的时间，创建后不可修改。 |
| `closed_at` | `timestamptz(6)` | 是 | `—` | 关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `uq_organization_unit__unit_code`（`UNIQUE`：`tenant_id, unit_code`）：组织单元代码在租户内唯一。
- `ck_organization_unit__state`（`CHECK`：`state IN ('ACTIVE', 'CLOSED')`）：组织单元状态只能为ACTIVE或CLOSED。
- `ck_organization_unit__not_own_parent`（`CHECK`：`parent_organization_unit_id IS NULL OR parent_organization_unit_id <> organization_unit_id`）：层级局部约束：组织单元不能直接把自身设为上级；更长环路由命令运行时复验。
- `ck_organization_unit__closed_fields`（`CHECK`：`(state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)`）：关闭一致性：只有CLOSED组织单元具有关闭时间。
- `ck_organization_unit__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。

物理外键：

- `fk_organization_unit__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_organization_unit__parent_organization_unit`：`(tenant_id, parent_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。组织层级：上级组织单元必须存在于同一租户；禁止级联删除。

索引：

- `ix_organization_unit__parent`：列`(tenant_id, parent_organization_unit_id)`；唯一=`否`；谓词=`parent_organization_unit_id IS NOT NULL`。按同租户上级节点遍历直属组织单元。

### `identity.appointment`

任职：一行锚定一个身份主体在一个组织单元中的单一岗位任期，由身份域拥有；计划期限创建时冻结，生命周期可CAS推进，不等同于任何具体权限。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, appointment_id)`
- 允许更新字段：`state, ended_at, revision`
- Write-once字段：`ended_at`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → SUSPENDED`, `SUSPENDED → ACTIVE`, `ACTIVE → ENDED`, `SUSPENDED → ENDED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `appointment_id` | `uuid` | 否 | `—` | 任职标识：由应用生成的UUIDv7。 |
| `principal_id` | `uuid` | 否 | `—` | 任职主体标识：以同租户复合外键关联身份主体，创建后不可修改。 |
| `organization_unit_id` | `uuid` | 否 | `—` | 任职组织单元标识：以同租户复合外键关联组织节点，创建后不可修改。 |
| `role_code` | `varchar(64)` | 否 | `—` | 岗位代码：关联同租户可配置目录，创建后不变，不授予权限。 |
| `effective_from` | `timestamptz(6)` | 否 | `—` | 任职生效时间：原始任期起点，创建后不可修改。 |
| `effective_until` | `timestamptz(6)` | 是 | `—` | 任职计划结束时间：无预定结束时为空，创建时冻结且不得延长或改写。 |
| `state` | `varchar(64)` | 否 | `—` | 任职状态：ACTIVE、SUSPENDED或ENDED；结束后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：任职锚点首次持久化的时间，创建后不可修改。 |
| `ended_at` | `timestamptz(6)` | 是 | `—` | 实际结束时间：状态首次变为ENDED时一次写入；未结束为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_appointment__state`（`CHECK`：`state IN ('ACTIVE', 'SUSPENDED', 'ENDED')`）：任职状态只能取冻结的生命周期值。
- `ck_appointment__effective_window`（`CHECK`：`effective_until IS NULL OR effective_until > effective_from`）：任期窗口：计划结束时间必须晚于生效时间。
- `ck_appointment__ended_fields`（`CHECK`：`(state = 'ENDED' AND ended_at IS NOT NULL) OR (state <> 'ENDED' AND ended_at IS NULL)`）：结束一致性：只有ENDED任职具有实际结束时间。
- `ck_appointment__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `uq_appointment__id_principal`（`UNIQUE`：`tenant_id, appointment_id, principal_id`）：准确任职候选键：供审计Actor与on-behalf-of复合关系证明Appointment确属该Principal。

物理外键：

- `fk_appointment__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_appointment__principal`：`(tenant_id, principal_id) → identity.principal(tenant_id, principal_id)`。任职主体必须是同租户已存在的身份主体。
- `fk_appointment__organization_unit`：`(tenant_id, organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。任职组织单元必须是同租户已存在的组织节点。
- `fk_appointment__role`：`(tenant_id, role_code) → identity.appointment_role(tenant_id, role_code)`。任职关联同租户岗位；停用不改变旧任职。

索引：

- `ix_appointment__principal`：列`(tenant_id, principal_id, state)`；唯一=`否`；谓词=`None`。按身份主体查找当前及历史任职。
- `ix_appointment__unit`：列`(tenant_id, organization_unit_id, state)`；唯一=`否`；谓词=`None`。按组织单元查找任职。

### `identity.authority_grant`

权限授予：一行代表向一个任职直接授予一种权限的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不代表某次命令已获授权。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, authority_grant_id)`
- 允许更新字段：`state, revoked_at, revocation_reason_code, revision`
- Write-once字段：`revoked_at, revocation_reason_code`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → REVOKED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `authority_grant_id` | `uuid` | 否 | `—` | 权限授予标识：由应用生成的UUIDv7。 |
| `grantee_appointment_id` | `uuid` | 否 | `—` | 受权任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `granted_by_appointment_id` | `uuid` | 否 | `—` | 授予人任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `scope_organization_unit_id` | `uuid` | 否 | `—` | 组织范围根标识：授权只沿提交时的当前组织树向下适用，创建后不可修改。 |
| `authority_code` | `varchar(64)` | 否 | `—` | 权限代码：来自静态代码允许列表，创建后不可修改。 |
| `valid_from` | `timestamptz(6)` | 否 | `—` | 权限生效时间：创建后不可修改。 |
| `valid_until` | `timestamptz(6)` | 是 | `—` | 权限失效时间：无预定失效时为空，创建时冻结且不得延长或改写。 |
| `state` | `varchar(64)` | 否 | `—` | 授权状态：ACTIVE或REVOKED；撤销后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：授予事实首次持久化的时间，创建后不可修改。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤销原因代码：撤销时一次写入；未撤销为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_authority_grant__state`（`CHECK`：`state IN ('ACTIVE', 'REVOKED')`）：授权状态只能为ACTIVE或REVOKED。
- `ck_authority_grant__valid_window`（`CHECK`：`valid_until IS NULL OR valid_until > valid_from`）：有效期窗口：失效时间必须晚于生效时间。
- `ck_authority_grant__revocation_fields`（`CHECK`：`(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)`）：撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。
- `ck_authority_grant__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `uq_authority_grant__id_grantee`（`UNIQUE`：`tenant_id, authority_grant_id, grantee_appointment_id`）：准确授权路径候选键：供转授权证明来源Grant确实授予委托人Appointment。

物理外键：

- `fk_authority_grant__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_authority_grant__grantee_appointment`：`(tenant_id, grantee_appointment_id) → identity.appointment(tenant_id, appointment_id)`。受权任职必须存在于同一租户。
- `fk_authority_grant__granted_by_appointment`：`(tenant_id, granted_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。授予人任职必须存在于同一租户。
- `fk_authority_grant__scope_org`：`(tenant_id, scope_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。授权组织范围根必须存在于同一租户，树关系在命令提交前按当前结构复验。

索引：

- `ix_authority_grant__grantee`：列`(tenant_id, grantee_appointment_id, authority_code, state)`；唯一=`否`；谓词=`None`。授权复验时按受权任职、权限和状态定位候选授予。
- `ix_authority_grant__scope`：列`(tenant_id, scope_organization_unit_id, authority_code, state)`；唯一=`否`；谓词=`None`。授权复验：按当前组织树范围根、权限和状态定位授予。

### `identity.delegation_grant`

转授权：一行代表一个任职把一项既有授权委托给另一任职的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不证明调用时委托链仍有效。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, delegation_grant_id)`
- 允许更新字段：`state, revoked_at, revocation_reason_code, revision`
- Write-once字段：`revoked_at, revocation_reason_code`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → REVOKED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `delegation_grant_id` | `uuid` | 否 | `—` | 转授权标识：由应用生成的UUIDv7。 |
| `source_authority_grant_id` | `uuid` | 否 | `—` | 来源直接授权标识：以同租户复合外键关联权限授予，创建后不可修改。 |
| `delegator_appointment_id` | `uuid` | 否 | `—` | 委托人任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `delegate_appointment_id` | `uuid` | 否 | `—` | 受托人任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `scope_organization_unit_id` | `uuid` | 否 | `—` | 委托组织范围根标识：必须不宽于来源授权并按当前组织树实时解释。 |
| `valid_from` | `timestamptz(6)` | 否 | `—` | 委托生效时间：创建后不可修改。 |
| `valid_until` | `timestamptz(6)` | 是 | `—` | 委托失效时间：无预定失效时为空，创建时冻结且不得延长或改写。 |
| `state` | `varchar(64)` | 否 | `—` | 转授权状态：ACTIVE或REVOKED；撤销后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：转授权锚点首次持久化的时间，创建后不可修改。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤销原因代码：撤销时一次写入；未撤销为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_delegation_grant__state`（`CHECK`：`state IN ('ACTIVE', 'REVOKED')`）：转授权状态只能为ACTIVE或REVOKED。
- `ck_delegation_grant__different_appointments`（`CHECK`：`delegator_appointment_id <> delegate_appointment_id`）：委托人和受托人任职不能相同。
- `ck_delegation_grant__valid_window`（`CHECK`：`valid_until IS NULL OR valid_until > valid_from`）：有效期窗口：失效时间必须晚于生效时间。
- `ck_delegation_grant__revocation_fields`（`CHECK`：`(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)`）：撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。
- `ck_delegation_grant__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。

物理外键：

- `fk_delegation_grant__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_delegation_grant__source_grantee`：`(tenant_id, source_authority_grant_id, delegator_appointment_id) → identity.authority_grant(tenant_id, authority_grant_id, grantee_appointment_id)`。同一路径来源：转授权来源Grant必须准确授予本行委托人Appointment。
- `fk_delegation_grant__delegator_appointment`：`(tenant_id, delegator_appointment_id) → identity.appointment(tenant_id, appointment_id)`。委托人任职必须存在于同一租户。
- `fk_delegation_grant__delegate_appointment`：`(tenant_id, delegate_appointment_id) → identity.appointment(tenant_id, appointment_id)`。受托人任职必须存在于同一租户。
- `fk_delegation_grant__scope_org`：`(tenant_id, scope_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。委托组织范围根必须存在于同一租户，范围收窄由命令提交前复验。

索引：

- `ix_delegation_grant__delegate`：列`(tenant_id, delegate_appointment_id, state)`；唯一=`否`；谓词=`None`。授权复验时按受托任职和状态查找委托。
- `ix_delegation_grant__source`：列`(tenant_id, source_authority_grant_id)`；唯一=`否`；谓词=`None`。按来源直接授权查找全部委托。

### `identity.object_access_grant`

对象访问授予：一行代表对一个Principal设置一个准确业务Subject的允许或限制，由身份域拥有；有效期创建时冻结且只允许单向撤销，实际命令仍必须沿同一Appointment授权路径复验。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, object_access_grant_id)`
- 允许更新字段：`state, revoked_at, revocation_reason_code, revision`
- Write-once字段：`revoked_at, revocation_reason_code`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → REVOKED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `object_access_grant_id` | `uuid` | 否 | `—` | 对象访问授予标识：由应用生成的UUIDv7。 |
| `grantee_principal_id` | `uuid` | 否 | `—` | 受约束Principal标识：对象级允许或限制固定到身份主体，创建后不可修改。 |
| `granted_by_appointment_id` | `uuid` | 否 | `—` | 授予人任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `access_code` | `varchar(64)` | 否 | `—` | 访问能力代码：来自静态代码允许列表，创建后不可修改。 |
| `effect_code` | `varchar(64)` | 否 | `—` | 对象规则效果：DENY优先于ALLOW，创建后不可修改。 |
| `valid_from` | `timestamptz(6)` | 否 | `—` | 访问授权生效时间：创建后不可修改。 |
| `valid_until` | `timestamptz(6)` | 是 | `—` | 访问授权失效时间：无预定失效时为空，创建时冻结且不得延长或改写。 |
| `state` | `varchar(64)` | 否 | `—` | 对象访问授权状态：ACTIVE或REVOKED；撤销后不可恢复。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：对象访问授权首次持久化的时间，创建后不可修改。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤销原因代码：撤销时一次写入；未撤销为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `object_subject_type` | `varchar(64)` | 否 | `—` | 访问授权所绑定的准确业务Subject的静态注册类型。 |
| `object_subject_id` | `uuid` | 否 | `—` | 访问授权所绑定的准确业务Subject在所属租户内的准确标识。 |
| `object_subject_revision` | `bigint` | 是 | `—` | 访问授权所绑定的准确业务Subject的准确修订号；按哈希冻结时为空。 |
| `object_subject_hash` | `bytea` | 是 | `—` | 访问授权所绑定的准确业务Subject的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_object_access_grant__state`（`CHECK`：`state IN ('ACTIVE', 'REVOKED')`）：对象访问授权状态只能为ACTIVE或REVOKED。
- `ck_object_access_grant__effect_code`（`CHECK`：`effect_code IN ('DENY', 'ALLOW')`）：对象规则效果只允许限制或允许，并始终先限后允。
- `ck_object_access_grant__valid_window`（`CHECK`：`valid_until IS NULL OR valid_until > valid_from`）：有效期窗口：失效时间必须晚于生效时间。
- `ck_object_access_grant__revocation_fields`（`CHECK`：`(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)`）：撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。
- `ck_object_access_grant__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_object_access_grant__object_subject_exact`（`CHECK`：`(object_subject_type IS NOT NULL AND object_subject_id IS NOT NULL AND ((object_subject_revision IS NOT NULL AND object_subject_revision >= 0 AND object_subject_hash IS NULL) OR (object_subject_revision IS NULL AND object_subject_hash IS NOT NULL)))`）：准确引用：访问授权所绑定的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_object_access_grant__object_subject_hash_length`（`CHECK`：`octet_length(object_subject_hash) = 32`）：摘要格式：object_subject_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_object_access_grant__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_object_access_grant__grantee_principal`：`(tenant_id, grantee_principal_id) → identity.principal(tenant_id, principal_id)`。对象规则必须绑定同租户准确Principal。
- `fk_object_access_grant__granted_by_appointment`：`(tenant_id, granted_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。授予人任职必须存在于同一租户。

类型化准确引用：

- `object_subject`：访问授权所绑定的准确业务Subject；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_object_access_grant__grantee`：列`(tenant_id, grantee_principal_id, access_code, effect_code, state)`；唯一=`否`；谓词=`None`。授权复验时按Principal、能力、先限后允效果和状态定位对象规则。
- `ix_object_access_grant__object`：列`(tenant_id, object_subject_type, object_subject_id, state)`；唯一=`否`；谓词=`None`。按准确业务Subject查找对象访问授予。

### `identity.appointment_role`

租户岗位目录；名称和状态可CAS修改，代码不变，不自动授予权限。

- Fact Owner：`IdentityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, appointment_role_id)`
- 允许更新字段：`display_name, state, revision`
- 状态字段与初态：`state = ACTIVE`
- 允许状态转换：`ACTIVE → INACTIVE`, `INACTIVE → ACTIVE`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `appointment_role_id` | `uuid` | 否 | `—` | 租户岗位目录；名称和状态可CAS修改，代码不变，不自动授予权限。标识：由应用生成的UUIDv7。 |
| `role_code` | `varchar(64)` | 否 | `—` | 租户内唯一稳定岗位代码。 |
| `display_name` | `text` | 否 | `—` | 岗位显示名称。 |
| `state` | `varchar(64)` | 否 | `—` | ACTIVE或INACTIVE；停用仅禁止新增任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 目录创建时间。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `uq_appointment_role__code`（`UNIQUE`：`tenant_id, role_code`）：代码在租户内唯一。
- `ck_appointment_role__state`（`CHECK`：`state IN ('ACTIVE', 'INACTIVE')`）：岗位可停用和恢复。
- `ck_appointment_role__code_format`（`CHECK`：`role_code ~ '^[A-Z][A-Z0-9_]{0,63}$'`）：受约束岗位代码。
- `ck_appointment_role__name`（`CHECK`：`char_length(btrim(display_name)) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]'`）：安全显示名称。
- `ck_appointment_role__revision`（`CHECK`：`revision BETWEEN 0 AND 9007199254740991`）：JSON安全修订号。

物理外键：

- `fk_appointment_role__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

## `audit`

审计域：只追加不可变审计事实，以准确类型化引用冻结对象、授权依据及更正目标。

- Fact Owner：`AuditAppender`

### `audit.audit_entry`

审计条目：一行冻结谁在何种准确Scope、单一路径授权和可信执行上下文下做了什么及其结果；只能追加，CORRECTION准确引用原条目，不复制领域事实、请求响应或正文。

- Fact Owner：`AuditAppender`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, audit_entry_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `audit_entry_id` | `uuid` | 否 | `—` | 审计条目标识：由应用生成的UUIDv7。 |
| `entry_type` | `varchar(64)` | 否 | `—` | 条目类型：EVENT表示原始审计事实，CORRECTION表示对一条原记录的单链修正。 |
| `audit_scope_code` | `varchar(64)` | 否 | `—` | 审计Scope：静态分类的租户、组织、对象或安全管理范围。 |
| `trusted_at` | `timestamptz(6)` | 否 | `—` | 可信时间：被审计写入、拒绝或披露提交审计事务的服务端时间。 |
| `action_code` | `varchar(64)` | 否 | `—` | 动作代码：来自静态审计动作注册表，创建后不可修改。 |
| `result_code` | `varchar(64)` | 否 | `—` | 结果代码：SUCCEEDED、NO_CHANGE、REJECTED或FAILED，创建后不可修改。 |
| `actor_principal_id` | `uuid` | 否 | `—` | 实际发起身份主体标识：以同租户复合外键关联身份主体，创建后不可修改。 |
| `actor_appointment_id` | `uuid` | 是 | `—` | 实际采用的任职标识：以同租户复合外键关联任职；不适用时为空。 |
| `on_behalf_of_principal_id` | `uuid` | 是 | `—` | 被代表Principal标识：非代办时为空，存在时与被代表任职一起冻结。 |
| `on_behalf_of_appointment_id` | `uuid` | 是 | `—` | 被代表任职标识：非代办时为空，存在时与被代表Principal一起冻结。 |
| `command_id` | `uuid` | 是 | `—` | 命令标识：由CommandRuntime产生的事件准确关联命令；非命令事件为空。 |
| `command_type` | `varchar(64)` | 是 | `—` | 命令类型：与command_id同时存在；非命令事件为空。 |
| `correlation_id` | `uuid` | 否 | `—` | 关联标识：贯穿一次用户或服务请求的稳定UUID。 |
| `causation_id` | `uuid` | 是 | `—` | 因果标识：存在直接上游命令或事件时记录其稳定UUID。 |
| `authorization_slot_code` | `varchar(64)` | 否 | `—` | 授权槽：本动作实际满足的唯一静态authoritySlot。 |
| `authorization_path_code` | `varchar(64)` | 否 | `—` | 授权路径：DIRECT、DELEGATED、OBJECT或SYSTEM等静态单路径类型。 |
| `authorization_scope_organization_unit_id` | `uuid` | 是 | `—` | 授权组织Scope根：按提交时当前组织树解释；全租户系统路径时可为空。 |
| `authorization_snapshot_digest` | `bytea` | 否 | `—` | 授权依据快照摘要：冻结实际Actor、Appointment、路径、范围、限制和决定依据。 |
| `trace_id` | `uuid` | 否 | `—` | 追踪标识：把同一请求链上的审计事实关联起来，不是业务对象外键。 |
| `service_role_code` | `varchar(64)` | 否 | `—` | 后端执行角色：API、WORKER或受控管理角色等静态代码。 |
| `execution_node_code` | `varchar(128)` | 否 | `—` | 执行节点代码：冻结实际服务实例或受控运行环境，不保存主机Secret。 |
| `session_id_hmac` | `bytea` | 是 | `—` | 会话标识HMAC：固定HMAC-SHA-256的32字节值，用于安全关联且不能还原原始会话Token。 |
| `client_ip_ciphertext` | `bytea` | 是 | `—` | 客户端地址密文：仅高风险审计需要时保存，数据库不可解密。 |
| `summary_schema_code` | `varchar(64)` | 否 | `—` | 变更摘要Schema：静态允许列表定义可出现的字段。 |
| `summary_schema_version` | `integer` | 否 | `—` | 变更摘要Schema版本：解释允许列表化JSON结构的正整数版本。 |
| `change_summary` | `jsonb` | 否 | `—` | 允许列表化变更摘要：仅保存必要字段变化，不得复制完整领域事实、请求响应、密码、Token、Secret或正文。 |
| `change_summary_digest` | `bytea` | 否 | `—` | 变更摘要摘要：规范化允许列表JSON的32字节SHA-256。 |
| `subject_type` | `varchar(64)` | 否 | `—` | 本条审计所针对的准确业务Subject的静态注册类型。 |
| `subject_id` | `uuid` | 否 | `—` | 本条审计所针对的准确业务Subject在所属租户内的准确标识。 |
| `subject_revision` | `bigint` | 是 | `—` | 本条审计所针对的准确业务Subject的准确修订号；按哈希冻结时为空。 |
| `subject_hash` | `bytea` | 是 | `—` | 本条审计所针对的准确业务Subject的准确规范摘要；按修订冻结时为空。 |
| `correction_target_type` | `varchar(64)` | 是 | `—` | 本条更正所指向的原审计事实的静态注册类型。 |
| `correction_target_id` | `uuid` | 是 | `—` | 本条更正所指向的原审计事实在所属租户内的准确标识。 |
| `correction_target_revision` | `bigint` | 是 | `—` | 本条更正所指向的原审计事实的准确修订号；按哈希冻结时为空。 |
| `correction_target_hash` | `bytea` | 是 | `—` | 本条更正所指向的原审计事实的准确规范摘要；按修订冻结时为空。 |
| `authorization_fact_type` | `varchar(64)` | 是 | `—` | 执行被审计动作时实际采用的授权或委托Fact的静态注册类型。 |
| `authorization_fact_id` | `uuid` | 是 | `—` | 执行被审计动作时实际采用的授权或委托Fact在所属租户内的准确标识。 |
| `authorization_fact_revision` | `bigint` | 是 | `—` | 执行被审计动作时实际采用的授权或委托Fact的准确修订号；按哈希冻结时为空。 |
| `authorization_fact_hash` | `bytea` | 是 | `—` | 执行被审计动作时实际采用的授权或委托Fact的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_audit_entry__entry_type`（`CHECK`：`entry_type IN ('EVENT', 'CORRECTION')`）：审计条目只允许原始事件或追加更正。
- `ck_audit_entry__result_code`（`CHECK`：`result_code IN ('SUCCEEDED', 'NO_CHANGE', 'REJECTED', 'FAILED')`）：审计结果只允许成功、无变化、拒绝或失败。
- `ck_audit_entry__command_pair`（`CHECK`：`(command_id IS NULL AND command_type IS NULL) OR (command_id IS NOT NULL AND command_type IS NOT NULL)`）：命令上下文：命令标识和类型必须同时存在或同时为空。
- `ck_audit_entry__on_behalf_pair`（`CHECK`：`(on_behalf_of_principal_id IS NULL AND on_behalf_of_appointment_id IS NULL) OR (on_behalf_of_principal_id IS NOT NULL AND on_behalf_of_appointment_id IS NOT NULL)`）：代办上下文：被代表Principal和Appointment必须同时存在或同时为空。
- `ck_audit_entry__correction_shape`（`CHECK`：`(entry_type = 'EVENT' AND correction_target_type IS NULL) OR (entry_type = 'CORRECTION' AND correction_target_type IS NOT NULL)`）：更正单链：只有CORRECTION必须准确引用一条原AuditEntry。
- `ck_audit_entry__summary_schema_version`（`CHECK`：`summary_schema_version > 0`）：变更摘要Schema版本必须为正数。
- `ck_audit_entry__subject_exact`（`CHECK`：`(subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))`）：准确引用：本条审计所针对的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_audit_entry__correction_target_exact`（`CHECK`：`((correction_target_type IS NOT NULL AND correction_target_id IS NOT NULL AND ((correction_target_revision IS NOT NULL AND correction_target_revision >= 0 AND correction_target_hash IS NULL) OR (correction_target_revision IS NULL AND correction_target_hash IS NOT NULL))) OR (correction_target_type IS NULL AND correction_target_id IS NULL AND correction_target_revision IS NULL AND correction_target_hash IS NULL))`）：准确引用：本条更正所指向的原审计事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_audit_entry__authorization_fact_exact`（`CHECK`：`((authorization_fact_type IS NOT NULL AND authorization_fact_id IS NOT NULL AND ((authorization_fact_revision IS NOT NULL AND authorization_fact_revision >= 0 AND authorization_fact_hash IS NULL) OR (authorization_fact_revision IS NULL AND authorization_fact_hash IS NOT NULL))) OR (authorization_fact_type IS NULL AND authorization_fact_id IS NULL AND authorization_fact_revision IS NULL AND authorization_fact_hash IS NULL))`）：准确引用：执行被审计动作时实际采用的授权或委托Fact必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_audit_entry__authorization_snapshot_digest_length`（`CHECK`：`octet_length(authorization_snapshot_digest) = 32`）：摘要格式：authorization_snapshot_digest必须保存32字节的规范二进制值。
- `ck_audit_entry__session_id_hmac_length`（`CHECK`：`octet_length(session_id_hmac) = 32`）：摘要格式：session_id_hmac必须保存32字节的规范二进制值。
- `ck_audit_entry__change_summary_digest_length`（`CHECK`：`octet_length(change_summary_digest) = 32`）：摘要格式：change_summary_digest必须保存32字节的规范二进制值。
- `ck_audit_entry__subject_hash_length`（`CHECK`：`octet_length(subject_hash) = 32`）：摘要格式：subject_hash必须保存32字节的规范二进制值。
- `ck_audit_entry__correction_target_hash_length`（`CHECK`：`octet_length(correction_target_hash) = 32`）：摘要格式：correction_target_hash必须保存32字节的规范二进制值。
- `ck_audit_entry__authorization_fact_hash_length`（`CHECK`：`octet_length(authorization_fact_hash) = 32`）：摘要格式：authorization_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_audit_entry__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_audit_entry__actor_principal`：`(tenant_id, actor_principal_id) → identity.principal(tenant_id, principal_id)`。实际发起身份必须存在于同一租户。
- `fk_audit_entry__actor_appointment`：`(tenant_id, actor_appointment_id) → identity.appointment(tenant_id, appointment_id)`。实际采用的任职若存在，必须属于同一租户。
- `fk_audit_entry__on_behalf_principal`：`(tenant_id, on_behalf_of_principal_id) → identity.principal(tenant_id, principal_id)`。被代表Principal若存在，必须属于同一租户。
- `fk_audit_entry__on_behalf_of_appointment`：`(tenant_id, on_behalf_of_appointment_id) → identity.appointment(tenant_id, appointment_id)`。代办任职若存在，必须属于同一租户。
- `fk_audit_entry__actor_appointment_principal`：`(tenant_id, actor_appointment_id, actor_principal_id) → identity.appointment(tenant_id, appointment_id, principal_id)`。Actor一致性：实际Appointment若存在必须属于同一实际Principal。
- `fk_audit_entry__on_behalf_appointment_principal`：`(tenant_id, on_behalf_of_appointment_id, on_behalf_of_principal_id) → identity.appointment(tenant_id, appointment_id, principal_id)`。代办一致性：被代表Appointment必须属于同一被代表Principal。
- `fk_audit_entry__authorization_scope_org`：`(tenant_id, authorization_scope_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。授权组织Scope若存在，必须属于同一租户。

类型化准确引用：

- `subject`：本条审计所针对的准确业务Subject；由静态允许列表、同租户Resolver和提交前复验保证。
- `correction_target`：本条更正所指向的原审计事实；由静态允许列表、同租户Resolver和提交前复验保证。
- `authorization_fact`：执行被审计动作时实际采用的授权或委托Fact；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_audit_entry__subject_time`：列`(tenant_id, subject_type, subject_id, trusted_at)`；唯一=`否`；谓词=`None`。按准确Subject和可信时间检索审计轨迹。
- `ix_audit_entry__actor_time`：列`(tenant_id, actor_principal_id, trusted_at)`；唯一=`否`；谓词=`None`。按实际发起身份和可信时间检索审计轨迹。
- `ix_audit_entry__correlation`：列`(tenant_id, correlation_id, trusted_at)`；唯一=`否`；谓词=`None`。按Correlation标识重建一次动作链的审计顺序。
- `ix_audit_entry__scope_time`：列`(tenant_id, audit_scope_code, trusted_at)`；唯一=`否`；谓词=`None`。分类查询：按准确审计Scope和可信时间检索。
- `ux_audit_entry__correction_target`：列`(tenant_id, correction_target_type, correction_target_id)`；唯一=`是`；谓词=`entry_type = 'CORRECTION'`。更正单链唯一：一条AuditEntry最多只有一个直接CORRECTION后继；继续修正必须引用上一条CORRECTION。

## `responsibility`

责任域：保存待办责任实例及其不可变决策、等待回执和唯一行动草案；不建设通用工作流或作业系统。

- Fact Owner：`ResponsibilityRuntime`

### `responsibility.task_occurrence`

待办发生：一行代表针对一个冻结Subject、由一个Owner任职承担且只有一个主命令的责任实例，由责任域拥有；只可CAS推进等待或终态，不是通用工作流或作业。

- Fact Owner：`ResponsibilityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, task_occurrence_id)`
- 允许更新字段：`state, completed_at, cancelled_at, cancellation_reason_code, completion_fact_type, completion_fact_id, completion_fact_revision, completion_fact_hash, revision, cancellation_fact_type, cancellation_fact_id, cancellation_fact_revision, cancellation_fact_hash`
- Write-once字段：`completed_at, cancelled_at, cancellation_reason_code, completion_fact_type, completion_fact_id, completion_fact_revision, completion_fact_hash, cancellation_fact_type, cancellation_fact_id, cancellation_fact_revision, cancellation_fact_hash`
- 状态字段与初态：`state = OPEN`
- 允许状态转换：`OPEN → WAITING`, `WAITING → OPEN`, `OPEN → DONE`, `WAITING → DONE`, `OPEN → CANCELLED`, `WAITING → CANCELLED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `task_occurrence_id` | `uuid` | 否 | `—` | 待办发生标识：由应用生成的UUIDv7。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | Owner任职标识：任务创建时冻结并以同租户复合外键关联任职，之后不可改派。 |
| `business_purpose_code` | `varchar(64)` | 否 | `—` | 业务目的代码：来自静态代码注册表，任务创建后不可修改。 |
| `primary_command_code` | `varchar(64)` | 否 | `—` | 固定主命令代码：完成该任务所允许提交的唯一主命令，创建后不可修改。 |
| `expected_completion_fact_type` | `varchar(64)` | 否 | `—` | 预期完成Fact类型：静态注册类型，创建后不可修改；DONE时必须与准确完成Fact类型一致。 |
| `original_sla_code` | `varchar(64)` | 否 | `—` | 原始SLA代码：任务发生时采用的静态规则代码，创建后不可修改。 |
| `original_sla_seconds` | `bigint` | 否 | `—` | 原始SLA时长：任务发生时冻结的非负秒数，创建后不可修改。 |
| `original_sla_due_at` | `timestamptz(6)` | 否 | `—` | 原始SLA截止时间：任务发生时计算并冻结，后续等待或策略变化均不改写。 |
| `state` | `varchar(64)` | 否 | `—` | 任务状态：OPEN、WAITING、DONE或CANCELLED；只允许OPEN与WAITING互转并从二者进入终态。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：任务发生并冻结责任信息的时间，创建后不可修改。 |
| `completed_at` | `timestamptz(6)` | 是 | `—` | 完成时间：状态首次变为DONE时一次写入；其他状态为空。 |
| `cancelled_at` | `timestamptz(6)` | 是 | `—` | 取消时间：状态首次变为CANCELLED时一次写入；其他状态为空。 |
| `cancellation_reason_code` | `varchar(64)` | 是 | `—` | 取消原因代码：取消时一次写入；其他状态为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `subject_type` | `varchar(64)` | 否 | `—` | 待办发生时冻结的准确业务Subject的静态注册类型。 |
| `subject_id` | `uuid` | 否 | `—` | 待办发生时冻结的准确业务Subject在所属租户内的准确标识。 |
| `subject_revision` | `bigint` | 是 | `—` | 待办发生时冻结的准确业务Subject的准确修订号；按哈希冻结时为空。 |
| `subject_hash` | `bytea` | 是 | `—` | 待办发生时冻结的准确业务Subject的准确规范摘要；按修订冻结时为空。 |
| `completion_fact_type` | `varchar(64)` | 是 | `—` | 完成待办所产生的准确业务Fact的静态注册类型。 |
| `completion_fact_id` | `uuid` | 是 | `—` | 完成待办所产生的准确业务Fact在所属租户内的准确标识。 |
| `completion_fact_revision` | `bigint` | 是 | `—` | 完成待办所产生的准确业务Fact的准确修订号；按哈希冻结时为空。 |
| `completion_fact_hash` | `bytea` | 是 | `—` | 完成待办所产生的准确业务Fact的准确规范摘要；按修订冻结时为空。 |
| `predecessor_task_occurrence_id` | `uuid` | 是 | `—` | 前序责任身份：R2商机后继跟进绑定已完成责任；初始责任与历史记录为空，创建后不可变。 |
| `responsibility_basis_type` | `varchar(64)` | 是 | `—` | 当前有效责任依据的静态注册类型。 |
| `responsibility_basis_id` | `uuid` | 是 | `—` | 当前有效责任依据在所属租户内的准确标识。 |
| `responsibility_basis_revision` | `bigint` | 是 | `—` | 当前有效责任依据的准确修订号；按哈希冻结时为空。 |
| `responsibility_basis_hash` | `bytea` | 是 | `—` | 当前有效责任依据的准确规范摘要；按修订冻结时为空。 |
| `cancellation_fact_type` | `varchar(64)` | 是 | `—` | 交接取消依据的静态注册类型。 |
| `cancellation_fact_id` | `uuid` | 是 | `—` | 交接取消依据在所属租户内的准确标识。 |
| `cancellation_fact_revision` | `bigint` | 是 | `—` | 交接取消依据的准确修订号；按哈希冻结时为空。 |
| `cancellation_fact_hash` | `bytea` | 是 | `—` | 交接取消依据的准确规范摘要；按修订冻结时为空。 |
| `handoff_predecessor_task_occurrence_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |

约束：

- `ck_task_occurrence__state`（`CHECK`：`state IN ('OPEN', 'WAITING', 'DONE', 'CANCELLED')`）：任务状态只能取冻结的四个生命周期值。
- `ck_task_occurrence__original_sla_seconds_nonnegative`（`CHECK`：`original_sla_seconds >= 0`）：原始SLA秒数不得为负数。
- `ck_task_occurrence__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_task_occurrence__completion_type`（`CHECK`：`completion_fact_type IS NULL OR completion_fact_type = expected_completion_fact_type`）：完成类型一致性：实际准确完成Fact的类型必须等于任务创建时冻结的预期类型。
- `ck_task_occurrence__terminal_fields`（`CHECK`：`(state = 'DONE' AND completed_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NOT NULL) OR (state = 'CANCELLED' AND completed_at IS NULL AND cancelled_at IS NOT NULL AND cancellation_reason_code IS NOT NULL AND completion_fact_type IS NULL) OR (state IN ('OPEN', 'WAITING') AND completed_at IS NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NULL)`）：终态一致性：DONE必须准确记录完成Fact和完成时间，CANCELLED必须记录取消时间与原因，非终态不得预填终态事实。
- `ck_task_occurrence__subject_exact`（`CHECK`：`(subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))`）：准确引用：待办发生时冻结的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_task_occurrence__completion_fact_exact`（`CHECK`：`((completion_fact_type IS NOT NULL AND completion_fact_id IS NOT NULL AND ((completion_fact_revision IS NOT NULL AND completion_fact_revision >= 0 AND completion_fact_hash IS NULL) OR (completion_fact_revision IS NULL AND completion_fact_hash IS NOT NULL))) OR (completion_fact_type IS NULL AND completion_fact_id IS NULL AND completion_fact_revision IS NULL AND completion_fact_hash IS NULL))`）：准确引用：完成待办所产生的准确业务Fact必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_task_occurrence__subject_hash_length`（`CHECK`：`octet_length(subject_hash) = 32`）：摘要格式：subject_hash必须保存32字节的规范二进制值。
- `ck_task_occurrence__completion_fact_hash_length`（`CHECK`：`octet_length(completion_fact_hash) = 32`）：摘要格式：completion_fact_hash必须保存32字节的规范二进制值。
- `uq_task_occurrence__progress_successor`（`UNIQUE`：`tenant_id, predecessor_task_occurrence_id`）：商机跟进因果幂等：同一前序责任最多生成一个后继责任。
- `ck_task_occurrence__progress_predecessor`（`CHECK`：`predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY') AND predecessor_task_occurrence_id <> task_occurrence_id)`）：后继关系限定为商机推进且不得自指。
- `ck_task_occurrence__responsibility_basis_exact`（`CHECK`：`((responsibility_basis_type IS NOT NULL AND responsibility_basis_id IS NOT NULL AND ((responsibility_basis_revision IS NOT NULL AND responsibility_basis_revision >= 0 AND responsibility_basis_hash IS NULL) OR (responsibility_basis_revision IS NULL AND responsibility_basis_hash IS NOT NULL))) OR (responsibility_basis_type IS NULL AND responsibility_basis_id IS NULL AND responsibility_basis_revision IS NULL AND responsibility_basis_hash IS NULL))`）：准确引用：当前有效责任依据必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_task_occurrence__cancellation_fact_exact`（`CHECK`：`((cancellation_fact_type IS NOT NULL AND cancellation_fact_id IS NOT NULL AND ((cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision >= 0 AND cancellation_fact_hash IS NULL) OR (cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL))) OR (cancellation_fact_type IS NULL AND cancellation_fact_id IS NULL AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NULL))`）：准确引用：交接取消依据必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_task_occurrence__responsibility_basis_hash_length`（`CHECK`：`octet_length(responsibility_basis_hash) = 32`）：摘要格式：responsibility_basis_hash必须保存32字节的规范二进制值。
- `ck_task_occurrence__cancellation_fact_hash_length`（`CHECK`：`octet_length(cancellation_fact_hash) = 32`）：摘要格式：cancellation_fact_hash必须保存32字节的规范二进制值。
- `ck_task_occurrence__handoff_predecessor`（`CHECK`：`handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','DELIVER_QUOTE','RECORD_QUOTE_REPLY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE') AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff')`）：T01具名事实一致性。
- `ck_task_occurrence__handoff_cancellation`（`CHECK`：`(((cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure')))) OR (state='CANCELLED' AND cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND cancellation_fact_type='opportunity.followup_attempt' AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL)) OR (state='CANCELLED' AND cancellation_reason_code='R2_QUOTE_TERMINATION_V1' AND cancellation_fact_type='opportunity.quote_termination' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL)) OR (state='CANCELLED' AND cancellation_reason_code='R2_CONTRACT_NEGOTIATION_V1' AND cancellation_fact_type='contract.negotiation_disposition' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL)`）：T01具名事实一致性。

物理外键：

- `fk_task_occurrence__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_task_occurrence__owner_appointment`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。任务Owner必须是同租户已存在的任职。
- `fk_task_occurrence__progress_predecessor`：`(tenant_id, predecessor_task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。前序责任必须存在于同一租户。
- `fk_task_occurrence__handoff_predecessor_task_occurrence_id`：`(tenant_id, handoff_predecessor_task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户身份存在性，准确版本由Owner复验。

类型化准确引用：

- `subject`：待办发生时冻结的准确业务Subject；由静态允许列表、同租户Resolver和提交前复验保证。
- `completion_fact`：完成待办所产生的准确业务Fact；由静态允许列表、同租户Resolver和提交前复验保证。
- `responsibility_basis`：当前有效责任依据；由静态允许列表、同租户Resolver和提交前复验保证。
- `cancellation_fact`：交接取消依据；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_task_occurrence__owner_state_due`：列`(tenant_id, owner_appointment_id, state, original_sla_due_at)`；唯一=`否`；谓词=`None`。按Owner、状态和原始SLA截止时间生成责任待办视图。
- `ix_task_occurrence__subject`：列`(tenant_id, subject_type, subject_id, state)`；唯一=`否`；谓词=`None`。按冻结Subject查找相关责任实例。

### `responsibility.decision_record`

决策记录：一行代表某个待办的一个不可变、显式版本决策事实，由责任域拥有；只能追加新版本，不覆盖旧决策，也不保存完整案情或文档正文。

- Fact Owner：`ResponsibilityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, decision_record_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `decision_record_id` | `uuid` | 否 | `—` | 决策记录标识：由应用生成的UUIDv7。 |
| `task_occurrence_id` | `uuid` | 否 | `—` | 所属待办标识：以同租户复合外键关联待办发生。 |
| `decision_version` | `integer` | 否 | `—` | 决策版本：同一待办内从一开始的正整数；唯一但连续性由命令运行时串行保证。 |
| `predecessor_decision_record_id` | `uuid` | 是 | `—` | 前序决定标识：首版本为空，后续版本准确引用同Task直接前序。 |
| `decided_by_appointment_id` | `uuid` | 否 | `—` | 决策人任职标识：以同租户复合外键关联任职。 |
| `authority_slot_code` | `varchar(64)` | 否 | `—` | 授权槽：本决定所满足或拒绝的唯一静态authoritySlot。 |
| `decision_contract_code` | `varchar(64)` | 否 | `—` | 决定合同代码：静态注册的结论Schema及允许结果集合。 |
| `decision_contract_version` | `integer` | 否 | `—` | 决定合同版本：解释本版本决定内容的正整数。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 决策代码：来自该业务目的的静态允许列表。 |
| `content_digest` | `bytea` | 否 | `—` | 决定内容摘要：覆盖准确Subject、authoritySlot、结论和规范依据。 |
| `rationale_summary` | `text` | 否 | `—` | 脱敏理由摘要：只记录可审查的简短依据，不得包含凭据、文档正文或非必要案情。 |
| `decided_at` | `timestamptz(6)` | 否 | `—` | 决策时间：该版本决策完成并持久化的时间。 |
| `decision_subject_type` | `varchar(64)` | 否 | `—` | 本版本Decision实际裁定的准确业务Subject的静态注册类型。 |
| `decision_subject_id` | `uuid` | 否 | `—` | 本版本Decision实际裁定的准确业务Subject在所属租户内的准确标识。 |
| `decision_subject_revision` | `bigint` | 是 | `—` | 本版本Decision实际裁定的准确业务Subject的准确修订号；按哈希冻结时为空。 |
| `decision_subject_hash` | `bytea` | 是 | `—` | 本版本Decision实际裁定的准确业务Subject的准确规范摘要；按修订冻结时为空。 |

约束：

- `uq_decision_record__task_version`（`UNIQUE`：`tenant_id, task_occurrence_id, decision_version`）：每个待办的决策版本号唯一，旧版本不可覆盖。
- `uq_decision_record__predecessor`（`UNIQUE`：`tenant_id, predecessor_decision_record_id`）：单后继链：一个DecisionRecord最多只有一个直接后继版本。
- `ck_decision_record__positive_version`（`CHECK`：`decision_version > 0`）：决策版本必须为正整数。
- `ck_decision_record__contract_version`（`CHECK`：`decision_contract_version > 0`）：决定合同版本必须为正整数。
- `ck_decision_record__predecessor_shape`（`CHECK`：`(decision_version = 1 AND predecessor_decision_record_id IS NULL) OR (decision_version > 1 AND predecessor_decision_record_id IS NOT NULL)`）：决定版本链：首版本无前序，后续版本必须准确引用直接前序。
- `ck_decision_record__decision_subject_exact`（`CHECK`：`(decision_subject_type IS NOT NULL AND decision_subject_id IS NOT NULL AND ((decision_subject_revision IS NOT NULL AND decision_subject_revision >= 0 AND decision_subject_hash IS NULL) OR (decision_subject_revision IS NULL AND decision_subject_hash IS NOT NULL)))`）：准确引用：本版本Decision实际裁定的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_decision_record__content_digest_length`（`CHECK`：`octet_length(content_digest) = 32`）：摘要格式：content_digest必须保存32字节的规范二进制值。
- `ck_decision_record__decision_subject_hash_length`（`CHECK`：`octet_length(decision_subject_hash) = 32`）：摘要格式：decision_subject_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_decision_record__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_decision_record__task_occurrence`：`(tenant_id, task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。决策必须属于同租户已存在的待办。
- `fk_decision_record__predecessor`：`(tenant_id, predecessor_decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。版本链：后续决定必须引用同租户直接前序。
- `fk_decision_record__decided_by_appointment`：`(tenant_id, decided_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。决策人任职必须存在于同一租户。

类型化准确引用：

- `decision_subject`：本版本Decision实际裁定的准确业务Subject；由静态允许列表、同租户Resolver和提交前复验保证。

### `responsibility.wait_receipt`

等待回执：一行代表某待办一次进入WAITING的不可变追加事实，由责任域拥有；每次进入等待均新增回执，不覆盖历史，也不代表通用工作流步骤。

- Fact Owner：`ResponsibilityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, wait_receipt_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `wait_receipt_id` | `uuid` | 否 | `—` | 等待回执标识：由应用生成的UUIDv7。 |
| `task_occurrence_id` | `uuid` | 否 | `—` | 所属待办标识：以同租户复合外键关联待办发生。 |
| `task_revision` | `bigint` | 否 | `—` | 入等待后的待办准确修订号：用于把回执绑定到那次状态迁移。 |
| `wait_sequence` | `integer` | 否 | `—` | 等待序号：同一待办内从一开始的正整数，用于稳定排序。 |
| `wait_reason_code` | `varchar(64)` | 否 | `—` | 等待原因代码：来自该主命令的静态允许列表。 |
| `wait_contract_code` | `varchar(64)` | 否 | `—` | 等待合同代码：静态注册的等待原因和恢复Fact约束。 |
| `wait_contract_version` | `integer` | 否 | `—` | 等待合同版本：解释本次无状态WaitReceipt的正整数版本。 |
| `entered_waiting_at` | `timestamptz(6)` | 否 | `—` | 进入等待时间：该次OPEN到WAITING迁移发生的时间。 |
| `resume_due_at` | `timestamptz(6)` | 是 | `—` | 预期恢复时间：未知时为空，不改写原始SLA。 |
| `recorded_by_appointment_id` | `uuid` | 否 | `—` | 记录人任职标识：以同租户复合外键关联执行该次迁移的任职。 |
| `awaited_fact_type` | `varchar(64)` | 是 | `—` | 本次进入等待所等待的准确外部或领域Fact的静态注册类型。 |
| `awaited_fact_id` | `uuid` | 是 | `—` | 本次进入等待所等待的准确外部或领域Fact在所属租户内的准确标识。 |
| `awaited_fact_revision` | `bigint` | 是 | `—` | 本次进入等待所等待的准确外部或领域Fact的准确修订号；按哈希冻结时为空。 |
| `awaited_fact_hash` | `bytea` | 是 | `—` | 本次进入等待所等待的准确外部或领域Fact的准确规范摘要；按修订冻结时为空。 |
| `handoff_fact_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `handoff_fact_revision` | `bigint` | 是 | `—` | T01准确版本，JSON安全整数。 |
| `inherited_wait_receipt_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `inherited_wait_hash` | `bytea` | 是 | `—` | 准确原等待摘要。 |
| `origin_progress_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `origin_progress_hash` | `bytea` | 是 | `—` | 准确原进展摘要。 |
| `original_sla_due_at` | `timestamptz(6)` | 是 | `—` | T01数据库业务时刻。 |

约束：

- `uq_wait_receipt__task_revision`（`UNIQUE`：`tenant_id, task_occurrence_id, task_revision`）：一个待办修订号至多对应一次进入等待回执。
- `uq_wait_receipt__task_sequence`（`UNIQUE`：`tenant_id, task_occurrence_id, wait_sequence`）：同一待办内等待序号唯一。
- `ck_wait_receipt__positive_task_revision`（`CHECK`：`task_revision > 0 OR (wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND task_revision=0)`）：等待回执绑定的待办修订号必须为正数。
- `ck_wait_receipt__positive_sequence`（`CHECK`：`wait_sequence > 0`）：等待序号必须为正整数。
- `ck_wait_receipt__contract_version`（`CHECK`：`wait_contract_version > 0`）：等待合同版本必须为正整数。
- `ck_wait_receipt__resume_after_entry`（`CHECK`：`resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1')`）：预期恢复时间若存在必须晚于进入等待时间。
- `ck_wait_receipt__awaited_fact_exact`（`CHECK`：`((awaited_fact_type IS NOT NULL AND awaited_fact_id IS NOT NULL AND ((awaited_fact_revision IS NOT NULL AND awaited_fact_revision >= 0 AND awaited_fact_hash IS NULL) OR (awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL))) OR (awaited_fact_type IS NULL AND awaited_fact_id IS NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NULL))`）：准确引用：本次进入等待所等待的准确外部或领域Fact必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_wait_receipt__awaited_fact_hash_length`（`CHECK`：`octet_length(awaited_fact_hash) = 32`）：摘要格式：awaited_fact_hash必须保存32字节的规范二进制值。
- `ck_wait_receipt__inherited_wait_hash_length`（`CHECK`：`octet_length(inherited_wait_hash) = 32`）：摘要格式：inherited_wait_hash必须保存32字节的规范二进制值。
- `ck_wait_receipt__origin_progress_hash_length`（`CHECK`：`octet_length(origin_progress_hash) = 32`）：摘要格式：origin_progress_hash必须保存32字节的规范二进制值。
- `ck_wait_receipt__handoff_shape`（`CHECK`：`(wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL) OR (wait_contract_code='R2_QUOTE_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.quote_response' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL) OR (wait_contract_code='R2_ATTEMPT_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.followup_attempt' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL)`）：T01具名事实一致性。

物理外键：

- `fk_wait_receipt__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_wait_receipt__task_occurrence`：`(tenant_id, task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。等待回执必须属于同租户已存在的待办。
- `fk_wait_receipt__recorded_by_appointment`：`(tenant_id, recorded_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。记录人任职必须存在于同一租户。
- `fk_wait_receipt__handoff_fact_id`：`(tenant_id, handoff_fact_id) → opportunity.responsibility_handoff(tenant_id, responsibility_handoff_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_wait_receipt__inherited_wait_receipt_id`：`(tenant_id, inherited_wait_receipt_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_wait_receipt__origin_progress_id`：`(tenant_id, origin_progress_id) → opportunity.opportunity_progress(tenant_id, opportunity_progress_id)`。同租户身份存在性，准确版本由Owner复验。

类型化准确引用：

- `awaited_fact`：本次进入等待所等待的准确外部或领域Fact；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_wait_receipt__task_time`：列`(tenant_id, task_occurrence_id, entered_waiting_at)`；唯一=`否`；谓词=`None`。按待办和时间读取不可变等待历史。

### `responsibility.action_draft`

行动草案：一行代表某待办唯一一份按静态Schema校验的候选主命令载荷，由责任域拥有；确认前可CAS编辑且只能确认一次，不是业务最终事实或通用文档。

- Fact Owner：`ResponsibilityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, action_draft_id)`
- 允许更新字段：`candidate_payload, candidate_payload_digest, last_edited_at, state, confirmed_by_appointment_id, confirmed_at, confirmed_payload_digest, revision`
- Write-once字段：`confirmed_by_appointment_id, confirmed_at, confirmed_payload_digest`
- 状态字段与初态：`state = DRAFT`
- 允许状态转换：`DRAFT → CONFIRMED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `action_draft_id` | `uuid` | 否 | `—` | 行动草案标识：由应用生成的UUIDv7。 |
| `task_occurrence_id` | `uuid` | 否 | `—` | 所属待办标识：以同租户复合外键关联待办；唯一约束保证每个待办最多一份草案。 |
| `action_code` | `varchar(64)` | 否 | `—` | 候选行动代码：必须等于待办冻结主命令所允许的静态代码，创建后不可修改。 |
| `payload_schema_code` | `varchar(64)` | 否 | `—` | 候选载荷Schema代码：来自静态应用注册表，创建后不可修改。 |
| `payload_schema_version` | `integer` | 否 | `—` | 候选载荷Schema版本：正整数，创建后不可修改。 |
| `candidate_payload` | `jsonb` | 否 | `—` | 候选载荷：按指定静态Schema校验的JSONB，确认前可CAS编辑；不得用作其他业务真相。 |
| `candidate_payload_digest` | `bytea` | 否 | `—` | 候选载荷摘要：规范化JSON的32字节SHA-256，随确认前编辑一并CAS更新。 |
| `state` | `varchar(64)` | 否 | `—` | 草案状态：DRAFT或CONFIRMED；只允许从DRAFT一次进入CONFIRMED。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 创建人任职标识：以同租户复合外键关联任职，创建后不可修改。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：草案首次持久化的时间，创建后不可修改。 |
| `last_edited_at` | `timestamptz(6)` | 否 | `—` | 最近编辑时间：每次候选载荷CAS修改时更新；未编辑时等于创建时间。 |
| `confirmed_by_appointment_id` | `uuid` | 是 | `—` | 确认人任职标识：确认时一次写入；未确认为空。 |
| `confirmed_at` | `timestamptz(6)` | 是 | `—` | 确认时间：从DRAFT进入CONFIRMED时一次写入；未确认为空。 |
| `confirmed_payload_digest` | `bytea` | 是 | `—` | 确认载荷摘要：确认时一次复制候选载荷摘要，只绑定输入，不代表主命令执行成功。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `uq_action_draft__task`（`UNIQUE`：`tenant_id, task_occurrence_id`）：每个待办最多存在一份行动草案。
- `ck_action_draft__state`（`CHECK`：`state IN ('DRAFT', 'CONFIRMED')`）：行动草案状态只能为DRAFT或CONFIRMED。
- `ck_action_draft__positive_schema_version`（`CHECK`：`payload_schema_version > 0`）：候选载荷Schema版本必须为正整数。
- `ck_action_draft__confirmation_fields`（`CHECK`：`(state = 'CONFIRMED' AND confirmed_by_appointment_id IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_payload_digest IS NOT NULL AND confirmed_payload_digest = candidate_payload_digest) OR (state = 'DRAFT' AND confirmed_by_appointment_id IS NULL AND confirmed_at IS NULL AND confirmed_payload_digest IS NULL)`）：确认一致性：CONFIRMED一次冻结当前候选载荷摘要，DRAFT不得预填；确认本身不产生业务执行Fact。
- `ck_action_draft__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_action_draft__candidate_payload_digest_length`（`CHECK`：`octet_length(candidate_payload_digest) = 32`）：摘要格式：candidate_payload_digest必须保存32字节的规范二进制值。
- `ck_action_draft__confirmed_payload_digest_length`（`CHECK`：`octet_length(confirmed_payload_digest) = 32`）：摘要格式：confirmed_payload_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_action_draft__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_action_draft__task_occurrence`：`(tenant_id, task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。行动草案必须属于同租户已存在的待办。
- `fk_action_draft__created_by_appointment`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。草案创建人任职必须存在于同一租户。
- `fk_action_draft__confirmed_by_appointment`：`(tenant_id, confirmed_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。草案确认人任职若存在，必须属于同一租户。

索引：

- `ix_action_draft__state`：列`(tenant_id, state, last_edited_at)`；唯一=`否`；谓词=`None`。按租户、草案状态和最近编辑时间查找待处理草案。

### `responsibility.contract_task_resumption`

销售办理处置的不可变准确事实；不解除合同或撤销执行。

- Fact Owner：`ResponsibilityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_task_resumption_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_task_resumption_id` | `uuid` | 否 | `—` | 销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `disposition_id` | `uuid` | 否 | `—` | 主管CONTINUE决定。 |
| `cancelled_task_id` | `uuid` | 否 | `—` | 准确取消明细。 |
| `prior_task_id` | `uuid` | 否 | `—` | 被暂停的原责任。 |
| `next_task_id` | `uuid` | 否 | `—` | 新建的同目的责任。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库可信记录时间。 |

约束：

- `ck_contract_task_resumption__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_contract_task_resumption__cancelled_task_id`（`UNIQUE`：`tenant_id, cancelled_task_id`）：引用只能接续一次。
- `uq_contract_task_resumption__prior_task_id`（`UNIQUE`：`tenant_id, prior_task_id`）：引用只能接续一次。
- `uq_contract_task_resumption__next_task_id`（`UNIQUE`：`tenant_id, next_task_id`）：引用只能接续一次。

物理外键：

- `fk_contract_task_resumption__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_task_resumption__disposition_id`：`(tenant_id, disposition_id) → contract.negotiation_disposition(tenant_id, negotiation_disposition_id)`。同租户准确事实引用。
- `fk_contract_task_resumption__cancelled_task_id`：`(tenant_id, cancelled_task_id) → contract.negotiation_cancelled_task(tenant_id, negotiation_cancelled_task_id)`。同租户准确事实引用。
- `fk_contract_task_resumption__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_contract_task_resumption__next_task_id`：`(tenant_id, next_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。

## `execution`

执行域：保存永久命令占位、不可变终态回执、准确事实事件及带租约围栏的投递队列。

- Fact Owner：`CommandRuntime`

### `execution.command_execution_slot`

命令执行占位：一行永久占用一个租户内命令标识，事实Owner为CommandRuntime；只可插入且永远无状态，不表示执行成功、失败或锁租约。

- Fact Owner：`CommandRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, command_execution_slot_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `command_execution_slot_id` | `uuid` | 否 | `—` | 命令执行占位标识：由应用生成的UUIDv7。 |
| `command_id` | `uuid` | 否 | `—` | 命令标识：调用方提供的稳定UUID，与静态信封和Scope共同形成永久占位键。 |
| `envelope_type` | `varchar(64)` | 否 | `—` | 命令信封类型：四类静态注册信封之一，创建后不可变。 |
| `command_type` | `varchar(64)` | 否 | `—` | 命令类型：来自静态命令注册表，创建后不可变。 |
| `command_scope_digest` | `bytea` | 否 | `—` | 命令Scope摘要：覆盖Tenant、命令种类和准确Subject范围，用于永久占位键。 |
| `payload_digest` | `bytea` | 否 | `—` | 载荷摘要：规范命令载荷的SHA-256，只用于同CommandId冲突判定。 |
| `occupied_at` | `timestamptz(6)` | 否 | `—` | 占位时间：CommandRuntime首次接纳该命令标识的数据库时间，创建后不可变。 |

约束：

- `uq_command_execution_slot__command_key`（`UNIQUE`：`tenant_id, envelope_type, command_scope_digest, command_id`）：永久占位唯一：Tenant、静态信封、命令Scope摘要与CommandId组合不得复用。
- `ck_command_execution_slot__command_scope_digest_length`（`CHECK`：`octet_length(command_scope_digest) = 32`）：摘要格式：command_scope_digest必须保存32字节的规范二进制值。
- `ck_command_execution_slot__payload_digest_length`（`CHECK`：`octet_length(payload_digest) = 32`）：摘要格式：payload_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_command_execution_slot__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

索引：

- `ix_command_execution_slot__occupied_at`：列`(tenant_id, occupied_at)`；唯一=`否`；谓词=`None`。运维查询：按租户和占位时间定位永久命令占位，不承担队列语义。

### `execution.command_receipt`

命令终态回执：一行记录一个命令唯一且不可变的最终裁定，事实Owner为CommandRuntime；只可插入，不表示处理中状态。

- Fact Owner：`CommandRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, command_receipt_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `command_receipt_id` | `uuid` | 否 | `—` | 命令终态回执标识：由应用生成的UUIDv7。 |
| `command_execution_slot_id` | `uuid` | 否 | `—` | 命令执行槽标识：强关联唯一永久占位，创建后不可变。 |
| `outcome` | `varchar(32)` | 否 | `—` | 终态结果：仅允许SUCCEEDED、NO_CHANGE或REJECTED，创建后不可变。 |
| `rejection_code` | `varchar(64)` | 是 | `—` | 拒绝原因代码：仅REJECTED时必填，不保存输入正文、案情或密钥。 |
| `completed_at` | `timestamptz(6)` | 否 | `—` | 完成时间：CommandRuntime形成终态裁定的数据库时间，创建后不可变。 |
| `result_fact_type` | `varchar(64)` | 是 | `—` | 命令成功或无变化时产生或确认的结果事实的静态注册类型。 |
| `result_fact_id` | `uuid` | 是 | `—` | 命令成功或无变化时产生或确认的结果事实在所属租户内的准确标识。 |
| `result_fact_revision` | `bigint` | 是 | `—` | 命令成功或无变化时产生或确认的结果事实的准确修订号；按哈希冻结时为空。 |
| `result_fact_hash` | `bytea` | 是 | `—` | 命令成功或无变化时产生或确认的结果事实的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_command_receipt__outcome`（`CHECK`：`outcome IN ('SUCCEEDED', 'NO_CHANGE', 'REJECTED')`）：回执终态：只允许成功、无变化或拒绝，不存在处理中或可回退状态。
- `ck_command_receipt__outcome_result`（`CHECK`：`((outcome IN ('SUCCEEDED', 'NO_CHANGE') AND result_fact_type IS NOT NULL AND rejection_code IS NULL) OR (outcome = 'REJECTED' AND result_fact_type IS NULL AND rejection_code IS NOT NULL))`）：结果准确性：成功和无变化必须引用准确结果事实且不得带拒绝码；拒绝不得引用结果事实且必须带安全原因代码。
- `uq_command_receipt__slot`（`UNIQUE`：`tenant_id, command_execution_slot_id`）：命令终局唯一：一个永久命令占位至多形成一张终态回执。
- `ck_command_receipt__result_fact_exact`（`CHECK`：`((result_fact_type IS NOT NULL AND result_fact_id IS NOT NULL AND ((result_fact_revision IS NOT NULL AND result_fact_revision >= 0 AND result_fact_hash IS NULL) OR (result_fact_revision IS NULL AND result_fact_hash IS NOT NULL))) OR (result_fact_type IS NULL AND result_fact_id IS NULL AND result_fact_revision IS NULL AND result_fact_hash IS NULL))`）：准确引用：命令成功或无变化时产生或确认的结果事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_command_receipt__result_fact_hash_length`（`CHECK`：`octet_length(result_fact_hash) = 32`）：摘要格式：result_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_command_receipt__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_command_receipt__command_execution_slot`：`(tenant_id, command_execution_slot_id) → execution.command_execution_slot(tenant_id, command_execution_slot_id)`。命令归属：终态回执必须关联同租户已存在的永久命令占位。

类型化准确引用：

- `result_fact`：命令成功或无变化时产生或确认的结果事实；由静态允许列表、同租户Resolver和提交前复验保证。

### `execution.domain_event`

领域事件通知：一行仅声明某个准确来源事实发生了静态类型事件，事实Owner为提交该事实的CommandRuntime；只可插入，不复制业务事实、文档正文或密钥。

- Fact Owner：`CommandRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, domain_event_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `domain_event_id` | `uuid` | 否 | `—` | 领域事件通知标识：由应用生成的UUIDv7。 |
| `event_type` | `varchar(64)` | 否 | `—` | 事件类型：来自静态事件注册表，创建后不可变。 |
| `event_schema_version` | `integer` | 否 | `—` | 事件Schema版本：解释通知载荷的正整数静态版本。 |
| `event_payload` | `jsonb` | 否 | `—` | 事件通知载荷：只保存允许列表化路由信息，不复制领域事实或文档正文。 |
| `payload_digest` | `bytea` | 否 | `—` | 事件载荷摘要：规范化通知JSON的32字节SHA-256。 |
| `command_id` | `uuid` | 否 | `—` | 来源命令标识：把事件关联到同事务命令，不作为业务Fact外键。 |
| `correlation_id` | `uuid` | 否 | `—` | 关联标识：贯穿一次业务请求和下游通知链。 |
| `causation_event_id` | `uuid` | 是 | `—` | 上游事件标识：由另一个事件触发时记录；非事件触发时为空。 |
| `occurred_at` | `timestamptz(6)` | 否 | `—` | 发生时间：来源事实与事件在同一事务提交时记录的数据库时间，创建后不可变。 |
| `source_fact_type` | `varchar(64)` | 否 | `—` | 领域事件所通知的唯一来源事实的静态注册类型。 |
| `source_fact_id` | `uuid` | 否 | `—` | 领域事件所通知的唯一来源事实在所属租户内的准确标识。 |
| `source_fact_revision` | `bigint` | 是 | `—` | 领域事件所通知的唯一来源事实的准确修订号；按哈希冻结时为空。 |
| `source_fact_hash` | `bytea` | 是 | `—` | 领域事件所通知的唯一来源事实的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_domain_event__schema_version`（`CHECK`：`event_schema_version > 0`）：事件Schema版本必须为正数。
- `ck_domain_event__source_fact_exact`（`CHECK`：`(source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL)))`）：准确引用：领域事件所通知的唯一来源事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_domain_event__payload_digest_length`（`CHECK`：`octet_length(payload_digest) = 32`）：摘要格式：payload_digest必须保存32字节的规范二进制值。
- `ck_domain_event__source_fact_hash_length`（`CHECK`：`octet_length(source_fact_hash) = 32`）：摘要格式：source_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_domain_event__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

类型化准确引用：

- `source_fact`：领域事件所通知的唯一来源事实；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_domain_event__source_fact`：列`(tenant_id, source_fact_type, source_fact_id)`；唯一=`否`；谓词=`None`。事实追溯：按租户和来源事实定位通知；准确版本选择器仍由行内约束限定。

### `execution.domain_event_outbox`

领域事件投递队列：一行代表一个事件向一个静态队列Owner的唯一投递，Owner为OutboxDispatcher；仅允许租约围栏式受控更新，不是领域事实副本。

- Fact Owner：`OutboxDispatcher`
- 更新策略：`QUEUE`
- 主键：`(tenant_id, domain_event_outbox_id)`
- 允许更新字段：`status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision`
- Write-once字段：`delivered_at`
- 状态字段与初态：`status = PENDING`
- 允许状态转换：`PENDING → CLAIMED`, `CLAIMED → PENDING`, `CLAIMED → DELIVERED`, `CLAIMED → EXHAUSTED`, `EXHAUSTED → PENDING`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `domain_event_outbox_id` | `uuid` | 否 | `—` | 领域事件投递队列标识：由应用生成的UUIDv7。 |
| `domain_event_id` | `uuid` | 否 | `—` | 领域事件标识：强关联同租户不可变事件，创建后不可变。 |
| `queue_owner` | `varchar(64)` | 否 | `—` | 队列Owner：静态消费者通道代码，同一事件与Owner只允许一行，创建后不可变。 |
| `status` | `varchar(32)` | 否 | `—` | 队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。 |
| `available_at` | `timestamptz(6)` | 否 | `—` | 可领取时间：PENDING重试时可由队列CAS推进，其他事实列不可借此改写。 |
| `lease_owner` | `varchar(64)` | 是 | `—` | 租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。 |
| `lease_until` | `timestamptz(6)` | 是 | `—` | 租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。 |
| `fencing_token` | `bigint` | 否 | `0` | 围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。 |
| `attempt_count` | `integer` | 否 | `0` | 投递尝试次数：非负且仅由队列CAS递增。 |
| `delivered_at` | `timestamptz(6)` | 是 | `—` | 投递完成时间：仅DELIVERED终态存在，首次写入后不可改。 |
| `last_error_code` | `varchar(64)` | 是 | `—` | 最近一次安全错误代码：不得保存Secret、Token、正文或非必要案情。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_domain_event_outbox__status`（`CHECK`：`status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'EXHAUSTED')`）：队列状态域：只允许待领取、已领取、已投递和耗尽四种机器状态。
- `ck_domain_event_outbox__lease_shape`（`CHECK`：`((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))`）：租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。
- `ck_domain_event_outbox__delivered_at`（`CHECK`：`((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))`）：投递终态：只有DELIVERED必须且可以记录完成时间。
- `ck_domain_event_outbox__fencing_token_nonnegative`（`CHECK`：`fencing_token >= 0`）：围栏令牌不得为负。
- `ck_domain_event_outbox__attempt_count_nonnegative`（`CHECK`：`attempt_count >= 0`）：投递尝试次数不得为负。
- `ck_domain_event_outbox__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `uq_domain_event_outbox__event_owner`（`UNIQUE`：`tenant_id, domain_event_id, queue_owner`）：投递唯一：每个事件与队列Owner组合至多存在一行。

物理外键：

- `fk_domain_event_outbox__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_domain_event_outbox__domain_event`：`(tenant_id, domain_event_id) → execution.domain_event(tenant_id, domain_event_id)`。强关系例外：Outbox投递必须关联同租户已存在的领域事件。

索引：

- `ix_domain_event_outbox__claim`：列`(tenant_id, queue_owner, status, available_at)`；唯一=`否`；谓词=`status = 'PENDING'`。队列领取：按租户、Owner、状态和可用时间扫描可领取投递。
- `ix_domain_event_outbox__lease_expiry`：列`(tenant_id, lease_until)`；唯一=`否`；谓词=`status = 'CLAIMED'`。租约回收：定位已过期的CLAIMED投递并执行带围栏CAS。

## `external_action`

外部动作域：保存一次性外部效果尝试、派发或探测队列，以及验签后不可变的Provider入站事件指纹。

- Fact Owner：`ExternalActionRuntime`

### `external_action.external_action`

外部效果尝试：一行冻结一个准确Subject、版本化动作合同、Provider账号、规范请求、稳定意图和一次attempt；状态只单向收敛，UNKNOWN不得恢复PENDING。

- Fact Owner：`ExternalActionRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, external_action_id)`
- 允许更新字段：`status, dispatched_at, provider_action_id, completed_at, result_code, result_digest, resolution_method_code, resolution_source_type, resolution_source_id, resolution_source_revision, resolution_source_hash, last_error_code, revision`
- Write-once字段：`dispatched_at, provider_action_id, completed_at, result_code, result_digest, resolution_method_code, resolution_source_type, resolution_source_id, resolution_source_revision, resolution_source_hash`
- 状态字段与初态：`status = PENDING`
- 允许状态转换：`PENDING → DISPATCHED`, `PENDING → UNKNOWN`, `DISPATCHED → SUCCEEDED`, `DISPATCHED → FAILED`, `DISPATCHED → UNKNOWN`, `UNKNOWN → SUCCEEDED`, `UNKNOWN → FAILED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `external_action_id` | `uuid` | 否 | `—` | 外部效果尝试标识：由应用生成的UUIDv7。 |
| `action_contract_code` | `varchar(64)` | 否 | `—` | 动作合同代码：静态注册的外部效果请求与结果Schema。 |
| `action_contract_version` | `integer` | 否 | `—` | 动作合同版本：解释本次请求Envelope和结果的正整数版本。 |
| `provider_code` | `varchar(64)` | 否 | `—` | Provider代码：标识静态适配器，创建后不可变。 |
| `provider_account_id` | `uuid` | 否 | `—` | Provider账户标识：租户内配置账户的稳定标识，创建后不可变。 |
| `request_envelope` | `jsonb` | 否 | `—` | 规范请求Envelope：按动作合同校验的允许列表JSON，不保存Secret、Token或非必要正文。 |
| `request_digest` | `bytea` | 否 | `—` | 请求摘要：规范请求Envelope的32字节SHA-256，用于冲突判定。 |
| `intent_key` | `varchar(160)` | 否 | `—` | 稳定意图键：同一业务意图跨attempt保持不变，非Provider凭据。 |
| `attempt_no` | `integer` | 否 | `—` | 尝试序号：同一intentKey下从一开始递增，每行只代表一次不可重开的外部效果尝试。 |
| `provider_idempotency_key` | `varchar(160)` | 否 | `—` | Provider幂等键：该attempt使用的稳定非秘密键，创建后不可变。 |
| `status` | `varchar(32)` | 否 | `—` | 动作状态：PENDING、DISPATCHED、SUCCEEDED、FAILED或UNKNOWN。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：CommandRuntime批准该次外部效果尝试的数据库时间，创建后不可变。 |
| `dispatched_at` | `timestamptz(6)` | 是 | `—` | 网络边界时间：请求首次可能越过网络边界时写入；即使崩溃后只能判定UNKNOWN也不得为空。 |
| `provider_action_id` | `text` | 是 | `—` | Provider动作标识：Provider返回的非秘密远端标识，首次写入后不可改。 |
| `completed_at` | `timestamptz(6)` | 是 | `—` | 收敛时间：SUCCEEDED或FAILED终态形成时写入，空值表示未收敛。 |
| `result_code` | `varchar(64)` | 是 | `—` | Provider安全结果代码：成功或失败收敛时可写入，不保存响应正文。 |
| `result_digest` | `bytea` | 是 | `—` | 外部结果摘要：收敛时覆盖可信结果证明的32字节摘要。 |
| `resolution_method_code` | `varchar(64)` | 是 | `—` | 收敛方法：PROVIDER_INBOX、PROBE或DECISION；只有SUCCEEDED/FAILED终态存在。 |
| `last_error_code` | `varchar(64)` | 是 | `—` | 最近安全错误代码：不得保存Secret、Token、响应正文或非必要案情。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `subject_type` | `varchar(64)` | 否 | `—` | 本次外部效果尝试所作用的准确业务Subject的静态注册类型。 |
| `subject_id` | `uuid` | 否 | `—` | 本次外部效果尝试所作用的准确业务Subject在所属租户内的准确标识。 |
| `subject_revision` | `bigint` | 是 | `—` | 本次外部效果尝试所作用的准确业务Subject的准确修订号；按哈希冻结时为空。 |
| `subject_hash` | `bytea` | 是 | `—` | 本次外部效果尝试所作用的准确业务Subject的准确规范摘要；按修订冻结时为空。 |
| `resolution_source_type` | `varchar(64)` | 是 | `—` | SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的静态注册类型。 |
| `resolution_source_id` | `uuid` | 是 | `—` | SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision在所属租户内的准确标识。 |
| `resolution_source_revision` | `bigint` | 是 | `—` | SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的准确修订号；按哈希冻结时为空。 |
| `resolution_source_hash` | `bytea` | 是 | `—` | SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_external_action__status`（`CHECK`：`status IN ('PENDING', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')`）：动作状态域：只允许待派发、已派发、成功、失败和未知五种状态。
- `ck_external_action__dispatch_shape`（`CHECK`：`((status = 'PENDING' AND dispatched_at IS NULL) OR (status IN ('DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN') AND dispatched_at IS NOT NULL))`）：派发证据：PENDING尚未确认派发；其余状态必须保留首次派发时间。
- `ck_external_action__completion_shape`（`CHECK`：`((status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL AND result_code IS NOT NULL AND result_digest IS NOT NULL AND ((resolution_method_code = 'PROVIDER_INBOX' AND resolution_source_type = 'external_action.provider_inbox') OR (resolution_method_code = 'DECISION' AND resolution_source_type = 'responsibility.decision_record') OR (resolution_method_code = 'PROBE' AND resolution_source_type IS NULL))) OR (status IN ('PENDING', 'DISPATCHED', 'UNKNOWN') AND completed_at IS NULL AND result_code IS NULL AND result_digest IS NULL AND resolution_method_code IS NULL AND resolution_source_type IS NULL))`）：收敛证据：ProviderInbox和Decision必须引用准确Fact；无副作用权威PROBE以本行结果摘要和同事务Audit证明且不得伪造来源Fact。
- `ck_external_action__resolution_method_code`（`CHECK`：`resolution_method_code IN ('PROVIDER_INBOX', 'PROBE', 'DECISION')`）：收敛方法只允许验签Provider消息、无副作用权威探测或授权裁决。
- `ck_external_action__contract_version`（`CHECK`：`action_contract_version > 0`）：动作合同版本必须为正数。
- `ck_external_action__attempt_no`（`CHECK`：`attempt_no > 0`）：同一意图下的尝试序号必须为正数。
- `ck_external_action__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `uq_external_action__provider_idempotency`（`UNIQUE`：`tenant_id, provider_account_id, provider_idempotency_key`）：Provider幂等：同一租户Provider账户内一个幂等键只代表一次外部效果尝试。
- `uq_external_action__intent_attempt`（`UNIQUE`：`tenant_id, intent_key, attempt_no`）：业务尝试唯一：同一稳定意图下attemptNo不得重复。
- `ck_external_action__subject_exact`（`CHECK`：`(subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))`）：准确引用：本次外部效果尝试所作用的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_external_action__resolution_source_exact`（`CHECK`：`((resolution_source_type IS NOT NULL AND resolution_source_id IS NOT NULL AND ((resolution_source_revision IS NOT NULL AND resolution_source_revision >= 0 AND resolution_source_hash IS NULL) OR (resolution_source_revision IS NULL AND resolution_source_hash IS NOT NULL))) OR (resolution_source_type IS NULL AND resolution_source_id IS NULL AND resolution_source_revision IS NULL AND resolution_source_hash IS NULL))`）：准确引用：SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_external_action__request_digest_length`（`CHECK`：`octet_length(request_digest) = 32`）：摘要格式：request_digest必须保存32字节的规范二进制值。
- `ck_external_action__result_digest_length`（`CHECK`：`octet_length(result_digest) = 32`）：摘要格式：result_digest必须保存32字节的规范二进制值。
- `ck_external_action__subject_hash_length`（`CHECK`：`octet_length(subject_hash) = 32`）：摘要格式：subject_hash必须保存32字节的规范二进制值。
- `ck_external_action__resolution_source_hash_length`（`CHECK`：`octet_length(resolution_source_hash) = 32`）：摘要格式：resolution_source_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_external_action__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

类型化准确引用：

- `subject`：本次外部效果尝试所作用的准确业务Subject；由静态允许列表、同租户Resolver和提交前复验保证。
- `resolution_source`：SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_external_action__provider_action`：列`(tenant_id, provider_account_id, provider_action_id)`；唯一=`是`；谓词=`provider_action_id IS NOT NULL`。远端对账：按Provider账户和远端动作标识定位本地唯一尝试。
- `ix_external_action__unknown`：列`(tenant_id, provider_code, status, dispatched_at)`；唯一=`否`；谓词=`status = 'UNKNOWN'`。UNKNOWN收敛：定位需要通过Provider探测确认结果的动作。

### `external_action.external_action_outbox`

外部动作队列：一行代表一个动作的DISPATCH或PROBE唯一工作项，Owner为ExternalActionDispatcher；仅允许租约围栏式受控更新，不产生第二次外部效果尝试。

- Fact Owner：`ExternalActionDispatcher`
- 更新策略：`QUEUE`
- 主键：`(tenant_id, external_action_outbox_id)`
- 允许更新字段：`status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision`
- Write-once字段：`delivered_at`
- 状态字段与初态：`status = PENDING`
- 允许状态转换：`PENDING → CLAIMED`, `CLAIMED → PENDING`, `CLAIMED → DELIVERED`, `CLAIMED → EXHAUSTED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `external_action_outbox_id` | `uuid` | 否 | `—` | 外部动作队列标识：由应用生成的UUIDv7。 |
| `external_action_id` | `uuid` | 否 | `—` | 外部动作标识：强关联同租户的一次效果尝试，创建后不可变。 |
| `operation` | `varchar(32)` | 否 | `—` | 工作类型：DISPATCH派发一次效果，PROBE仅探测UNKNOWN结果，创建后不可变。 |
| `status` | `varchar(32)` | 否 | `—` | 队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。 |
| `available_at` | `timestamptz(6)` | 否 | `—` | 可领取时间：PENDING重试或延迟探测时可由队列CAS推进。 |
| `lease_owner` | `varchar(64)` | 是 | `—` | 租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。 |
| `lease_until` | `timestamptz(6)` | 是 | `—` | 租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。 |
| `fencing_token` | `bigint` | 否 | `0` | 围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。 |
| `attempt_count` | `integer` | 否 | `0` | 工作尝试次数：非负且仅由队列CAS递增。 |
| `delivered_at` | `timestamptz(6)` | 是 | `—` | 工作项完成时间：仅DELIVERED终态存在，首次写入后不可改。 |
| `last_error_code` | `varchar(64)` | 是 | `—` | 最近安全错误代码：不得保存Secret、Token、正文或非必要案情。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_external_action_outbox__operation`（`CHECK`：`operation IN ('DISPATCH', 'PROBE')`）：工作类型域：只允许一次派发工作或UNKNOWN结果探测工作。
- `ck_external_action_outbox__status`（`CHECK`：`status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'EXHAUSTED')`）：队列状态域：只允许待领取、已领取、已完成和耗尽四种机器状态。
- `ck_external_action_outbox__lease_shape`（`CHECK`：`((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))`）：租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。
- `ck_external_action_outbox__delivered_at`（`CHECK`：`((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))`）：工作终态：只有DELIVERED必须且可以记录完成时间。
- `ck_external_action_outbox__fencing_token_nonnegative`（`CHECK`：`fencing_token >= 0`）：围栏令牌不得为负。
- `ck_external_action_outbox__attempt_count_nonnegative`（`CHECK`：`attempt_count >= 0`）：工作尝试次数不得为负。
- `ck_external_action_outbox__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `uq_external_action_outbox__action_operation`（`UNIQUE`：`tenant_id, external_action_id, operation`）：工作唯一：每个外部动作的DISPATCH与PROBE各至多存在一行。

物理外键：

- `fk_external_action_outbox__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_external_action_outbox__external_action`：`(tenant_id, external_action_id) → external_action.external_action(tenant_id, external_action_id)`。强关系例外：Outbox工作项必须关联同租户已存在的外部动作。

索引：

- `ix_external_action_outbox__claim`：列`(tenant_id, operation, status, available_at)`；唯一=`否`；谓词=`status = 'PENDING'`。队列领取：按租户、工作类型、状态和可用时间扫描可领取工作项。
- `ix_external_action_outbox__lease_expiry`：列`(tenant_id, lease_until)`；唯一=`否`；谓词=`status = 'CLAIMED'`。租约回收：定位已过期的CLAIMED工作项并执行带围栏CAS。

### `external_action.provider_inbox`

Provider入站事实：一行保存一个Provider账户已通过验签的不可变事件指纹，事实Owner为ProviderIngress；只可插入，不表示事件已被业务接受或成功处理。

- Fact Owner：`ProviderIngress`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, provider_inbox_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `provider_inbox_id` | `uuid` | 否 | `—` | Provider入站事实标识：由应用生成的UUIDv7。 |
| `provider_code` | `varchar(64)` | 否 | `—` | Provider代码：标识完成验签的静态适配器，创建后不可变。 |
| `provider_account_id` | `uuid` | 否 | `—` | Provider账户标识：验签所使用租户配置账户的稳定标识，创建后不可变。 |
| `provider_event_id` | `text` | 否 | `—` | Provider事件标识：验签载荷声明的稳定非秘密标识，创建后不可变。 |
| `provider_event_type` | `varchar(64)` | 否 | `—` | Provider事件类型：验签后解析出的静态类型代码，创建后不可变。 |
| `payload_digest` | `bytea` | 否 | `—` | 载荷摘要：验签原始字节的SHA-256，仅用于证据核对，不保存正文。 |
| `nonce_digest` | `bytea` | 否 | `—` | Nonce摘要：验签窗口内去重使用的32字节摘要，不保存原始Nonce。 |
| `signature_method_code` | `varchar(64)` | 否 | `—` | 验签方法：静态注册的Provider签名算法和账号绑定方式。 |
| `message_schema_version` | `integer` | 否 | `—` | 消息Schema版本：解释允许列表化规范消息的正整数版本。 |
| `normalized_message` | `jsonb` | 否 | `—` | 规范消息：验签和Schema校验后的允许列表JSON，不保存原始请求、Token或Secret。 |
| `normalized_message_digest` | `bytea` | 否 | `—` | 规范消息摘要：用于同ProviderEventId同Hash返回原结果、异Hash隔离。 |
| `external_action_id` | `uuid` | 是 | `—` | 准确外部动作标识：接入时可证明关联Action及Subject时填写，否则为空且不得推进业务。 |
| `provider_occurred_at` | `timestamptz(6)` | 是 | `—` | Provider发生时间：验签载荷声明的时间；Provider未提供时为空。 |
| `signature_verified_at` | `timestamptz(6)` | 否 | `—` | 验签时间：ProviderIngress确认签名有效的数据库时间，创建后不可变。 |
| `received_at` | `timestamptz(6)` | 否 | `—` | 接收时间：系统首次持久化该已验签事件的数据库时间，创建后不可变。 |

约束：

- `ck_provider_inbox__schema_version`（`CHECK`：`message_schema_version > 0`）：Provider消息Schema版本必须为正数。
- `uq_provider_inbox__account_event`（`UNIQUE`：`tenant_id, provider_account_id, provider_event_id`）：Provider去重：租户、Provider账户与Provider事件标识组合全局唯一。
- `uq_provider_inbox__account_nonce`（`UNIQUE`：`tenant_id, provider_account_id, nonce_digest`）：Nonce防重：同一Provider账户不得重复接受相同Nonce摘要；时间窗口仍由ProviderIngress先行校验。
- `ck_provider_inbox__payload_digest_length`（`CHECK`：`octet_length(payload_digest) = 32`）：摘要格式：payload_digest必须保存32字节的规范二进制值。
- `ck_provider_inbox__nonce_digest_length`（`CHECK`：`octet_length(nonce_digest) = 32`）：摘要格式：nonce_digest必须保存32字节的规范二进制值。
- `ck_provider_inbox__normalized_message_digest_length`（`CHECK`：`octet_length(normalized_message_digest) = 32`）：摘要格式：normalized_message_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_provider_inbox__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_provider_inbox__external_action`：`(tenant_id, external_action_id) → external_action.external_action(tenant_id, external_action_id)`。准确关联：仅能物理关联同租户ExternalAction，Subject一致性由固定内部命令复验。

索引：

- `ix_provider_inbox__received_at`：列`(tenant_id, provider_code, received_at)`；唯一=`否`；谓词=`None`。入站审计：按租户、Provider和接收时间追溯已验签事件指纹。

## `evidence`

证据域：保存单文件上传会话、固定对象版本、不可变提交与固定目标用途绑定组成的严格一对一物理链。

- Fact Owner：`EvidenceRuntime`

### `evidence.upload_session`

上传会话：一行只授权向冻结目标和用途上传一个文件，事实Owner为EvidenceIngress；仅允许单向关闭状态更新，不表示文件已经接收、扫描或成为证据。

- Fact Owner：`EvidenceIngress`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, upload_session_id)`
- 允许更新字段：`status, received_at, finalized_at, revision`
- Write-once字段：`received_at, finalized_at`
- 状态字段与初态：`status = OPEN`
- 允许状态转换：`OPEN → OBJECT_RECEIVED`, `OBJECT_RECEIVED → FINALIZED`, `OPEN → EXPIRED`, `OPEN → CANCELLED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `upload_session_id` | `uuid` | 否 | `—` | 上传会话标识：由应用生成的UUIDv7。 |
| `object_store_code` | `varchar(64)` | 否 | `—` | 对象存储代码：标识静态私有对象存储适配器。 |
| `object_key` | `text` | 否 | `—` | Opaque对象键：由服务端生成且不包含租户、案情、文件名或可用凭据。 |
| `purpose_code` | `varchar(64)` | 否 | `—` | 上传用途：来自静态用途注册表，会话创建后冻结且不可改。 |
| `intake_contract_code` | `varchar(64)` | 否 | `—` | 接收合同代码：静态注册的大小、媒体类型和安全门禁合同。 |
| `intake_contract_version` | `integer` | 否 | `—` | 接收合同版本：解释本会话技术门禁的正整数版本。 |
| `intake_contract_digest` | `bytea` | 否 | `—` | 接收合同摘要：冻结实际允许规则的32字节摘要。 |
| `upload_capability_hash` | `bytea` | 否 | `—` | 上传能力摘要：一次性上传能力的SHA-256，数据库不保存可用凭据。 |
| `status` | `varchar(32)` | 否 | `—` | 会话状态：OPEN、OBJECT_RECEIVED、FINALIZED、EXPIRED或CANCELLED。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 创建任职标识：发起受控上传会话的准确Appointment。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：EvidenceIngress签发会话的数据库时间，创建后不可变。 |
| `expires_at` | `timestamptz(6)` | 否 | `—` | 到期时间：创建时冻结的上传截止时间，创建后不可变。 |
| `received_at` | `timestamptz(6)` | 是 | `—` | 对象接收时间：唯一文件的准确ObjectVersion被固定时一次写入。 |
| `finalized_at` | `timestamptz(6)` | 是 | `—` | 最终晋级时间：技术检查、最终授权和Subject版本重验全部通过后一次写入。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `target_type` | `varchar(64)` | 否 | `—` | 上传会话创建时冻结的准确业务目标的静态注册类型。 |
| `target_id` | `uuid` | 否 | `—` | 上传会话创建时冻结的准确业务目标在所属租户内的准确标识。 |
| `target_revision` | `bigint` | 是 | `—` | 上传会话创建时冻结的准确业务目标的准确修订号；按哈希冻结时为空。 |
| `target_hash` | `bytea` | 是 | `—` | 上传会话创建时冻结的准确业务目标的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_upload_session__status`（`CHECK`：`status IN ('OPEN', 'OBJECT_RECEIVED', 'FINALIZED', 'EXPIRED', 'CANCELLED')`）：上传会话状态域：只允许开放、对象已接收、已最终晋级、已过期或已取消。
- `ck_upload_session__received_at`（`CHECK`：`((status IN ('OBJECT_RECEIVED', 'FINALIZED') AND received_at IS NOT NULL) OR (status IN ('OPEN', 'EXPIRED', 'CANCELLED') AND received_at IS NULL))`）：对象接收：只有对象已接收或最终晋级状态具有唯一接收时间。
- `ck_upload_session__finalized_at`（`CHECK`：`(status = 'FINALIZED' AND finalized_at IS NOT NULL) OR (status <> 'FINALIZED' AND finalized_at IS NULL)`）：最终晋级：只有FINALIZED必须记录完成时间。
- `ck_upload_session__expiry_order`（`CHECK`：`expires_at > created_at`）：会话期限：冻结的到期时间必须晚于创建时间。
- `ck_upload_session__contract_version`（`CHECK`：`intake_contract_version > 0`）：接收合同版本必须为正数。
- `ck_upload_session__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `uq_upload_session__object_key`（`UNIQUE`：`tenant_id, object_store_code, object_key`）：create-only对象唯一：一个Opaque Key只允许一个上传会话和一次原始字节写入。
- `ck_upload_session__target_exact`（`CHECK`：`(target_type IS NOT NULL AND target_id IS NOT NULL AND ((target_revision IS NOT NULL AND target_revision >= 0 AND target_hash IS NULL) OR (target_revision IS NULL AND target_hash IS NOT NULL)))`）：准确引用：上传会话创建时冻结的准确业务目标必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_upload_session__intake_contract_digest_length`（`CHECK`：`octet_length(intake_contract_digest) = 32`）：摘要格式：intake_contract_digest必须保存32字节的规范二进制值。
- `ck_upload_session__upload_capability_hash_length`（`CHECK`：`octet_length(upload_capability_hash) = 32`）：摘要格式：upload_capability_hash必须保存32字节的规范二进制值。
- `ck_upload_session__target_hash_length`（`CHECK`：`octet_length(target_hash) = 32`）：摘要格式：target_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_upload_session__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_upload_session__creator`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。会话创建主体必须是同租户准确Appointment。

类型化准确引用：

- `target`：上传会话创建时冻结的准确业务目标；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_upload_session__open_expiry`：列`(tenant_id, status, expires_at)`；唯一=`否`；谓词=`status = 'OPEN'`。会话回收：按租户和到期时间定位仍OPEN的会话。

### `evidence.received_source_object`

接收来源对象：一行是一个上传会话唯一文件经服务端读取、类型识别和恶意文件扫描后的不可变来源事实，事实Owner为EvidenceIngress；不代表业务提交或目标绑定。

- Fact Owner：`EvidenceIngress`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, received_source_object_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `received_source_object_id` | `uuid` | 否 | `—` | 接收来源对象标识：由应用生成的UUIDv7。 |
| `upload_session_id` | `uuid` | 否 | `—` | 上传会话标识：强关联同租户会话且一会话至多一个来源对象。 |
| `object_store_code` | `varchar(64)` | 否 | `—` | 对象存储代码：标识静态存储适配器，创建后不可变。 |
| `object_key` | `text` | 否 | `—` | 对象键：服务端控制的非公开定位键，创建后不可变且不得包含可用凭据。 |
| `object_version` | `text` | 否 | `—` | 对象版本：对象存储返回的真实不可变ObjectVersion，创建后不可改。 |
| `size_bytes` | `bigint` | 否 | `—` | 服务端读取的对象字节数：非负，创建后不可变。 |
| `server_sha256` | `bytea` | 否 | `—` | 服务端摘要：服务端读取固定ObjectVersion全部字节计算的SHA-256。 |
| `detected_media_type` | `varchar(255)` | 否 | `—` | 真实媒体类型：服务端内容识别结果，不信任客户端声明，创建后不可变。 |
| `scan_result` | `varchar(32)` | 否 | `—` | 扫描结果：PASSED或FAILED；PASSED只表示技术门禁通过，不表示业务VERIFIED。 |
| `scan_engine_code` | `varchar(128)` | 否 | `—` | 扫描引擎代码：标识静态扫描器及其规则版本，创建后不可变。 |
| `scan_contract_version` | `integer` | 否 | `—` | 扫描合同版本：解释引擎规则和真实类型门禁的正整数版本。 |
| `scan_failure_code` | `varchar(64)` | 是 | `—` | 扫描失败代码：FAILED时必填的安全原因，PASSED时为空。 |
| `scanned_at` | `timestamptz(6)` | 否 | `—` | 扫描时间：固定ObjectVersion完成恶意文件扫描的数据库时间，创建后不可变。 |
| `received_at` | `timestamptz(6)` | 否 | `—` | 接收时间：服务端固定对象版本并完成读取的数据库时间，创建后不可变。 |

约束：

- `ck_received_source_object__scan_result`（`CHECK`：`scan_result IN ('PASSED', 'FAILED')`）：扫描结果域：只允许技术门禁通过或失败；失败细分使用安全原因代码。
- `ck_received_source_object__scan_shape`（`CHECK`：`(scan_result = 'PASSED' AND scan_failure_code IS NULL) OR (scan_result = 'FAILED' AND scan_failure_code IS NOT NULL)`）：扫描结果完整性：FAILED必须有安全原因，PASSED不得携带失败码。
- `ck_received_source_object__scan_contract_version`（`CHECK`：`scan_contract_version > 0`）：扫描合同版本必须为正数。
- `ck_received_source_object__size_bytes_nonnegative`（`CHECK`：`size_bytes >= 0`）：服务端观测的对象字节数不得为负。
- `uq_received_source_object__upload_session`（`UNIQUE`：`tenant_id, upload_session_id`）：单文件会话：一个上传会话至多形成一个接收来源对象。
- `uq_received_source_object__object_version`（`UNIQUE`：`tenant_id, object_store_code, object_key, object_version`）：来源对象唯一：同租户同存储对象键的固定ObjectVersion只接收一次。
- `ck_received_source_object__server_sha256_length`（`CHECK`：`octet_length(server_sha256) = 32`）：摘要格式：server_sha256必须保存32字节的规范二进制值。

物理外键：

- `fk_received_source_object__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_received_source_object__upload_session`：`(tenant_id, upload_session_id) → evidence.upload_session(tenant_id, upload_session_id)`。证据链第一段：来源对象必须强关联同租户上传会话。

### `evidence.evidence_submission`

证据提交：一行把一个已接收且扫描结论可接受的唯一来源对象声明为不可变提交事实，事实Owner为EvidenceRuntime；只可插入，不代表已绑定到业务目标。

- Fact Owner：`EvidenceRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, evidence_submission_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `evidence_submission_id` | `uuid` | 否 | `—` | 证据提交标识：由应用生成的UUIDv7。 |
| `received_source_object_id` | `uuid` | 否 | `—` | 接收来源对象标识：强关联同租户来源对象且一对象至多一次提交。 |
| `submission_contract_code` | `varchar(64)` | 否 | `—` | 提交合同代码：静态注册的EvidenceSubmission结构和用途规则。 |
| `submission_contract_version` | `integer` | 否 | `—` | 提交合同版本：解释不可变提交事实的正整数版本。 |
| `submitted_by_appointment_id` | `uuid` | 否 | `—` | 提交任职标识：最终授权和Subject重验通过时实际执行晋级的Appointment。 |
| `submitted_at` | `timestamptz(6)` | 否 | `—` | 提交时间：EvidenceRuntime接受该来源对象的数据库时间，创建后不可变。 |

约束：

- `ck_evidence_submission__contract_version`（`CHECK`：`submission_contract_version > 0`）：提交合同版本必须为正数。
- `uq_evidence_submission__source_object`（`UNIQUE`：`tenant_id, received_source_object_id`）：证据链唯一：一个接收来源对象至多形成一条不可变证据提交。

物理外键：

- `fk_evidence_submission__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_evidence_submission__received_source_object`：`(tenant_id, received_source_object_id) → evidence.received_source_object(tenant_id, received_source_object_id)`。证据链第二段：证据提交必须强关联同租户接收来源对象。
- `fk_evidence_submission__submitter`：`(tenant_id, submitted_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。提交主体必须是同租户准确Appointment。

### `evidence.evidence_binding`

证据绑定：一行把一个不可变提交按冻结用途绑定到冻结准确目标，事实Owner为EvidenceRuntime；只允许单向撤回，不移动目标、不改用途且不删除历史。

- Fact Owner：`EvidenceRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, evidence_binding_id)`
- 允许更新字段：`revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision`
- Write-once字段：`revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `evidence_binding_id` | `uuid` | 否 | `—` | 证据绑定标识：由应用生成的UUIDv7。 |
| `evidence_submission_id` | `uuid` | 否 | `—` | 证据提交标识：强关联同租户提交且一次提交至多一个绑定。 |
| `purpose_code` | `varchar(64)` | 否 | `—` | 绑定用途：来自静态用途注册表，创建后冻结且不可改。 |
| `bound_by_appointment_id` | `uuid` | 否 | `—` | 绑定任职标识：最终四轴授权和Subject版本重验通过时执行绑定的Appointment。 |
| `bound_at` | `timestamptz(6)` | 否 | `—` | 绑定时间：EvidenceRuntime创建目标绑定的数据库时间，创建后不可变。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤回时间：空值表示有效；首次撤回时写入且不得清空或改写。 |
| `revoked_by_appointment_id` | `uuid` | 是 | `—` | 撤回任职标识：授权撤回命令的准确Appointment；未撤回为空。 |
| `revocation_authorization_digest` | `bytea` | 是 | `—` | 撤回授权摘要：冻结撤回命令提交前四轴复验的单路径授权快照；未撤回为空。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤回原因代码：仅撤回时必填，不保存文档正文或非必要案情。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `target_type` | `varchar(64)` | 否 | `—` | 证据绑定创建时冻结的准确业务目标的静态注册类型。 |
| `target_id` | `uuid` | 否 | `—` | 证据绑定创建时冻结的准确业务目标在所属租户内的准确标识。 |
| `target_revision` | `bigint` | 是 | `—` | 证据绑定创建时冻结的准确业务目标的准确修订号；按哈希冻结时为空。 |
| `target_hash` | `bytea` | 是 | `—` | 证据绑定创建时冻结的准确业务目标的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_evidence_binding__revocation_shape`（`CHECK`：`((revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL))`）：撤回形态：撤回时间、任职、授权摘要和安全原因必须同时为空或一次性全部写入。
- `ck_evidence_binding__revocation_order`（`CHECK`：`revoked_at IS NULL OR revoked_at >= bound_at`）：撤回顺序：撤回时间不得早于绑定时间。
- `ck_evidence_binding__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `uq_evidence_binding__submission`（`UNIQUE`：`tenant_id, evidence_submission_id`）：证据链唯一：一个不可变证据提交至多形成一个目标绑定。
- `ck_evidence_binding__target_exact`（`CHECK`：`(target_type IS NOT NULL AND target_id IS NOT NULL AND ((target_revision IS NOT NULL AND target_revision >= 0 AND target_hash IS NULL) OR (target_revision IS NULL AND target_hash IS NOT NULL)))`）：准确引用：证据绑定创建时冻结的准确业务目标必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_evidence_binding__revocation_authorization_digest_length`（`CHECK`：`octet_length(revocation_authorization_digest) = 32`）：摘要格式：revocation_authorization_digest必须保存32字节的规范二进制值。
- `ck_evidence_binding__target_hash_length`（`CHECK`：`octet_length(target_hash) = 32`）：摘要格式：target_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_evidence_binding__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_evidence_binding__evidence_submission`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据链第三段：证据绑定必须强关联同租户不可变提交。
- `fk_evidence_binding__binder`：`(tenant_id, bound_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。绑定主体必须是同租户准确Appointment。
- `fk_evidence_binding__revoker`：`(tenant_id, revoked_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。撤回主体若存在必须是同租户准确Appointment。

类型化准确引用：

- `target`：证据绑定创建时冻结的准确业务目标；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_evidence_binding__active_target`：列`(tenant_id, target_type, target_id, purpose_code)`；唯一=`否`；谓词=`revoked_at IS NULL`。有效证据查询：按租户、准确目标标识和用途定位尚未撤回的绑定。

### `evidence.material_upload_basis`

材料上传准确责任及受保护元数据；不改变既有证据链。

- Fact Owner：`EvidenceRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, material_upload_basis_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `material_upload_basis_id` | `uuid` | 否 | `—` | 材料上传准确责任及受保护元数据；不改变既有证据链。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `upload_session_id` | `uuid` | 否 | `—` | 唯一既有上传会话。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 准确商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 准确责任类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 准确责任身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 准确责任版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 当前责任任职。 |
| `customer_confirmation_id` | `uuid` | 是 | `—` | 可选准确客户确认不可变版本。 |
| `original_task_id` | `uuid` | 是 | `—` | 原跟进事项。 |
| `original_task_revision` | `bigint` | 是 | `—` | 原事项准确版本。 |
| `expected_previous_version_id` | `uuid` | 是 | `—` | 补交所期望的准确前版。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 冻结时间。 |

约束：

- `ck_material_upload_basis__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_material_upload_basis__session`（`UNIQUE`：`tenant_id, upload_session_id`）：一会话唯一依据。
- `ck_material_upload_basis__identity`（`CHECK`：`material_upload_basis_id=upload_session_id`）：会话与不可变依据共享身份但版本语义独立。
- `ck_material_upload_basis__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：受保护文件名及说明大小。
- `ck_material_upload_basis__selectors`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740991 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND ((original_task_id IS NULL AND original_task_revision IS NULL) OR (original_task_id IS NOT NULL AND original_task_revision IS NOT NULL AND original_task_revision BETWEEN 0 AND 9007199254740991))`）：完整准确选择器。
- `ck_material_upload_basis__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_material_upload_basis__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_material_upload_basis__upload_session_id`：`(tenant_id, upload_session_id) → evidence.upload_session(tenant_id, upload_session_id)`。同租户准确事实引用。
- `fk_material_upload_basis__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_material_upload_basis__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_material_upload_basis__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_material_upload_basis__original_task_id`：`(tenant_id, original_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_material_upload_basis__expected_previous_version_id`：`(tenant_id, expected_previous_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。

### `evidence.material_upload_check`

技术处理状态不可变历史；未知禁止静默重传。

- Fact Owner：`EvidenceRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, material_upload_check_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `material_upload_check_id` | `uuid` | 否 | `—` | 技术处理状态不可变历史；未知禁止静默重传。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `upload_basis_id` | `uuid` | 否 | `—` | 准确上传依据。 |
| `previous_check_id` | `uuid` | 是 | `—` | 准确前次检查状态。 |
| `status` | `varchar(32)` | 否 | `—` | 技术状态。 |
| `result_code` | `varchar(64)` | 是 | `—` | 静态安全结果码，不保存正文。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 状态形成时间。 |

约束：

- `ck_material_upload_check__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_material_upload_check__previous`（`UNIQUE`：`tenant_id, previous_check_id`）：检查状态单一后继。
- `ck_material_upload_check__status`（`CHECK`：`status IN ('CHECKING','UNKNOWN','SCAN_UNAVAILABLE','PASSED','REJECTED')`）：技术状态域。
- `ck_material_upload_check__code`（`CHECK`：`result_code IS NULL OR result_code ~ '^[A-Z][A-Z0-9_]{0,63}$'`）：有界安全结果代码。

物理外键：

- `fk_material_upload_check__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_material_upload_check__upload_basis_id`：`(tenant_id, upload_basis_id) → evidence.material_upload_basis(tenant_id, material_upload_basis_id)`。同租户准确事实引用。
- `fk_material_upload_check__previous_check_id`：`(tenant_id, previous_check_id) → evidence.material_upload_check(tenant_id, material_upload_check_id)`。同租户准确事实引用。

## `party`

主体域：保存跨业务流程共享的当前态主体锚点、受保护主标识与一跳合并关系。

- Fact Owner：`PartyRuntime`

### `party.party`

主体锚点：一行保存自然人或组织当前规范名、至多一个受保护主标识及一跳合并指向，事实Owner为PartyRuntime；仅允许受控当前态更新，不是案件、客户关系或历史版本表。

- Fact Owner：`PartyRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, party_id)`
- 允许更新字段：`canonical_name, primary_identifier_type, primary_identifier_ciphertext, primary_identifier_hmac, status, merged_into_party_id, merged_at, revision`
- Write-once字段：`merged_into_party_id, merged_at`
- 状态字段与初态：`status = ACTIVE`
- 允许状态转换：`ACTIVE → MERGED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `party_id` | `uuid` | 否 | `—` | 主体锚点标识：由应用生成的UUIDv7。 |
| `party_type` | `varchar(32)` | 否 | `—` | 主体类型：PERSON或ORGANIZATION，创建后不可变。 |
| `canonical_name` | `text` | 否 | `—` | 规范名：当前用于检索和展示的名称，可受控更新；不得混入受保护主标识。 |
| `primary_identifier_type` | `varchar(64)` | 是 | `—` | 主标识类型：静态类型代码；空值表示主体没有受保护主标识。 |
| `primary_identifier_ciphertext` | `bytea` | 是 | `—` | 主标识密文：应用层加密的唯一主标识；空值表示未设置，数据库不可解密。 |
| `primary_identifier_hmac` | `bytea` | 是 | `—` | 主标识HMAC：用于租户内精确匹配的32字节受保护摘要；空值表示未设置。 |
| `status` | `varchar(32)` | 否 | `—` | 主体状态：ACTIVE或MERGED，合并后不得恢复。 |
| `merged_into_party_id` | `uuid` | 是 | `—` | 合并目标主体标识：仅MERGED时存在且直接指向最终活动主体，禁止多跳链。 |
| `merged_at` | `timestamptz(6)` | 是 | `—` | 合并时间：仅MERGED时存在，首次写入后不得清空或改写。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |

约束：

- `ck_party__party_type`（`CHECK`：`party_type IN ('PERSON', 'ORGANIZATION')`）：主体类型域：只允许自然人或组织两种静态类型。
- `ck_party__status`（`CHECK`：`status IN ('ACTIVE', 'MERGED')`）：主体状态域：只允许活动或已合并，且状态转换只能单向发生。
- `ck_party__primary_identifier_shape`（`CHECK`：`((primary_identifier_type IS NULL AND primary_identifier_ciphertext IS NULL AND primary_identifier_hmac IS NULL) OR (primary_identifier_type IS NOT NULL AND primary_identifier_ciphertext IS NOT NULL AND primary_identifier_hmac IS NOT NULL))`）：主标识形态：一行至多容纳一个受保护主标识，其类型、密文和HMAC必须同时为空或同时存在。
- `ck_party__merge_shape`（`CHECK`：`((status = 'ACTIVE' AND merged_into_party_id IS NULL AND merged_at IS NULL) OR (status = 'MERGED' AND merged_into_party_id IS NOT NULL AND merged_at IS NOT NULL))`）：合并形态：活动主体没有合并指向；已合并主体必须同时记录直接目标和合并时间。
- `ck_party__not_self_merge`（`CHECK`：`merged_into_party_id IS NULL OR merged_into_party_id <> party_id`）：合并目标：主体不得合并到自身；目标必须由运行时复验为未合并的最终活动主体。
- `ck_party__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负。
- `ck_party__primary_identifier_hmac_length`（`CHECK`：`octet_length(primary_identifier_hmac) = 32`）：摘要格式：primary_identifier_hmac必须保存32字节的规范二进制值。

物理外键：

- `fk_party__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_party__merged_into_party`：`(tenant_id, merged_into_party_id) → party.party(tenant_id, party_id)`。一跳合并：合并行直接关联同租户目标主体；CommandRuntime必须复验目标仍为ACTIVE。

索引：

- `ux_party__active_primary_identifier`：列`(tenant_id, primary_identifier_type, primary_identifier_hmac)`；唯一=`是`；谓词=`status = 'ACTIVE' AND primary_identifier_hmac IS NOT NULL`。受保护主标识匹配：活动主体的类型与HMAC组合在租户内唯一。
- `ix_party__canonical_name`：列`(tenant_id, canonical_name)`；唯一=`否`；谓词=`None`。主体检索：按租户和当前规范名定位活动或已合并主体锚点。
- `ix_party__merge_target`：列`(tenant_id, merged_into_party_id)`；唯一=`否`；谓词=`merged_into_party_id IS NOT NULL`。合并追溯：定位直接并入某个最终活动主体的一跳来源。

### `party.profile_version`

主体资料不可变版本；复用Party身份，不建立第二主体库。

- Fact Owner：`PartyRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, profile_version_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `profile_version_id` | `uuid` | 否 | `—` | 主体资料不可变版本；复用Party身份，不建立第二主体库。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `party_id` | `uuid` | 否 | `—` | 准确Party主体。 |
| `party_revision` | `bigint` | 否 | `—` | 保存时准确主体版本。 |
| `party_type` | `varchar(32)` | 否 | `—` | 主体种类。 |
| `canonical_name` | `text` | 否 | `—` | 当时规范名称。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 实际维护者。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 资料版本形成时间。 |

约束：

- `ck_profile_version__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_profile_version__party_revision`（`UNIQUE`：`tenant_id, party_id, party_revision`）：主体准确版本唯一。
- `ck_profile_version__party_revision`（`CHECK`：`party_revision BETWEEN 0 AND 9007199254740991`）：准确主体版本。
- `ck_profile_version__type`（`CHECK`：`party_type IN ('PERSON','ORGANIZATION')`）：主体类型域。
- `ck_profile_version__name`（`CHECK`：`length(btrim(canonical_name)) BETWEEN 1 AND 300`）：必要名称范围。

物理外键：

- `fk_profile_version__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_profile_version__party_id`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。同租户准确事实引用。
- `fk_profile_version__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

## `lead`

销售接入域：保存不可覆盖Lead、追加分派链与追加联系结果，不承载机会、报价或冲突决定。

- Fact Owner：`LeadRuntime`

### `lead.lead`

Lead接入事实：一行代表渠道一次不可覆盖的原始接入，由销售接入域负责；仅允许更新Party解析、当前处置、当前Assignment和CAS修订号，不代表已形成法律需求或客户关系。

- Fact Owner：`LeadRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, lead_id)`
- 允许更新字段：`parsed_party_id, party_resolution_code, disposition_code, current_assignment_id, revision, ingress_completion_phone_ciphertext, ingress_completion_phone_hmac, ingress_completion_email_ciphertext, ingress_completion_email_hmac, ingress_completion_source_code, ingress_completion_source_summary_ciphertext, ingress_completed_by_appointment_id, ingress_completed_at, ingress_completion_digest`
- Write-once字段：`ingress_completion_phone_ciphertext, ingress_completion_phone_hmac, ingress_completion_email_ciphertext, ingress_completion_email_hmac, ingress_completion_source_code, ingress_completion_source_summary_ciphertext, ingress_completed_by_appointment_id, ingress_completed_at, ingress_completion_digest`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `lead_id` | `uuid` | 否 | `—` | Lead接入事实标识：由应用生成的UUIDv7。 |
| `source_channel_code` | `varchar(64)` | 否 | `—` | 来源渠道代码：由接入适配器写入并永久冻结，不含凭据。 |
| `source_account_code` | `varchar(128)` | 否 | `—` | 渠道账号代码：标识静态配置的接入账号，不保存账号凭据。 |
| `source_record_key_digest` | `bytea` | 否 | `—` | 来源记录键摘要：渠道账号内稳定记录键的32字节HMAC或规范摘要，用于来源幂等。 |
| `captured_at` | `timestamptz(6)` | 否 | `—` | 渠道捕获时间：使用带时区微秒精度时间，由可信接入适配器写入并永久冻结。 |
| `captured_name_ciphertext` | `bytea` | 是 | `—` | 捕获姓名密文：渠道提供的姓名受保护值；缺失时为空，写入后不可覆盖。 |
| `captured_phone_ciphertext` | `bytea` | 是 | `—` | 捕获电话密文：渠道提供的电话受保护值；缺失时为空。 |
| `captured_phone_hmac` | `bytea` | 是 | `—` | 捕获电话HMAC：用于受控精确匹配；电话缺失时为空。 |
| `captured_email_ciphertext` | `bytea` | 是 | `—` | 捕获邮箱密文：渠道提供的邮箱受保护值；缺失时为空。 |
| `captured_email_hmac` | `bytea` | 是 | `—` | 捕获邮箱HMAC：用于受控精确匹配；邮箱缺失时为空。 |
| `city_code` | `varchar(64)` | 是 | `—` | 捕获城市代码：规范化地域代码；渠道未提供时为空。 |
| `service_category_code` | `varchar(64)` | 否 | `—` | 服务类别代码：静态注册的拟咨询法律服务类别。 |
| `jurisdiction_code` | `varchar(64)` | 否 | `—` | 法域代码：静态注册的主要适用法域；尚不明确时使用明确UNKNOWN代码。 |
| `urgency_code` | `varchar(64)` | 否 | `—` | 紧急度代码：静态注册的销售接入紧急程度。 |
| `legal_need_summary_ciphertext` | `bytea` | 否 | `—` | 法律需求摘要密文：最小必要的受保护需求摘要，不保存完整咨询正文。 |
| `captured_content_digest` | `bytea` | 否 | `—` | 接入内容摘要：覆盖上述规范化结构化捕获字段，用于业务疑似重复提示而非来源幂等。 |
| `parsed_party_id` | `uuid` | 是 | `—` | Party解析结果：为空表示尚未或无法唯一解析；可随解析结论受控更新，关系由复合外键证明。 |
| `party_resolution_code` | `varchar(64)` | 否 | `—` | Party解析状态：仅可取UNRESOLVED、RESOLVED或AMBIGUOUS，可受控更新。 |
| `disposition_code` | `varchar(64)` | 否 | `—` | 当前处置代码：销售接入域的当前处置结论，可受控更新但不得改写原捕获事实。 |
| `current_assignment_id` | `uuid` | 是 | `—` | 当前Assignment标识：为空表示尚未分派；只作为当前指针受控更新，历史由LeadAssignment链保留。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：该Lead首次持久化的带时区微秒精度时间，永久冻结。 |
| `ingress_completion_phone_ciphertext` | `bytea` | 是 | `—` | 补全电话密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。 |
| `ingress_completion_phone_hmac` | `bytea` | 是 | `—` | 补全电话HMAC：与补全电话密文配对的32字节受控精确匹配值；缺失时为空。 |
| `ingress_completion_email_ciphertext` | `bytea` | 是 | `—` | 补全邮箱密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。 |
| `ingress_completion_email_hmac` | `bytea` | 是 | `—` | 补全邮箱HMAC：与补全邮箱密文配对的32字节受控精确匹配值；缺失时为空。 |
| `ingress_completion_source_code` | `varchar(64)` | 是 | `—` | 补全来源代码：标识静态注册的补全来源类型，不保存凭据或自由文本。 |
| `ingress_completion_source_summary_ciphertext` | `bytea` | 是 | `—` | 补全来源说明密文：保存最小必要的受保护来源说明，不写入审计摘要或事件载荷。 |
| `ingress_completed_by_appointment_id` | `uuid` | 是 | `—` | 补全执行任命：指向同租户执行完成接入命令的准确Appointment。 |
| `ingress_completed_at` | `timestamptz(6)` | 是 | `—` | 补全完成时间：完成接入命令写入整槽的带时区微秒精度时间。 |
| `ingress_completion_digest` | `bytea` | 是 | `—` | 补全完成摘要：覆盖规范化补全值、来源、执行任命与完成时间的32字节摘要。 |
| `customer_name_ciphertext` | `bytea` | 是 | `—` | 客户名称密文：独立可选原始接入事实，创建后不可变。 |
| `contact_name_ciphertext` | `bytea` | 是 | `—` | 联系人名称密文：独立可选原始接入事实，创建后不可变。 |

约束：

- `ck_lead__party_resolution_code`（`CHECK`：`party_resolution_code IN ('UNRESOLVED', 'RESOLVED', 'AMBIGUOUS')`）：Party解析状态域：限制为未解析、已唯一解析或存在歧义三种机器状态。
- `ck_lead__party_resolution_pair`（`CHECK`：`((party_resolution_code = 'RESOLVED' AND parsed_party_id IS NOT NULL) OR (party_resolution_code <> 'RESOLVED' AND parsed_party_id IS NULL))`）：Party解析配对：只有RESOLVED状态必须且仅能携带一个Party标识。
- `ck_lead__phone_pair`（`CHECK`：`(captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL) OR (captured_phone_ciphertext IS NOT NULL AND captured_phone_hmac IS NOT NULL)`）：电话保护字段：电话密文和HMAC必须同时存在或同时为空。
- `ck_lead__email_pair`（`CHECK`：`(captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL) OR (captured_email_ciphertext IS NOT NULL AND captured_email_hmac IS NOT NULL)`）：邮箱保护字段：邮箱密文和HMAC必须同时存在或同时为空。
- `ck_lead__revision_nonnegative`（`CHECK`：`revision >= 0`）：修订号范围：Lead受控更新的CAS修订号不得为负数。
- `ck_lead__source_record_key_digest_length`（`CHECK`：`octet_length(source_record_key_digest) = 32`）：摘要格式：source_record_key_digest必须保存32字节的规范二进制值。
- `ck_lead__captured_phone_hmac_length`（`CHECK`：`octet_length(captured_phone_hmac) = 32`）：摘要格式：captured_phone_hmac必须保存32字节的规范二进制值。
- `ck_lead__captured_email_hmac_length`（`CHECK`：`octet_length(captured_email_hmac) = 32`）：摘要格式：captured_email_hmac必须保存32字节的规范二进制值。
- `ck_lead__captured_content_digest_length`（`CHECK`：`octet_length(captured_content_digest) = 32`）：摘要格式：captured_content_digest必须保存32字节的规范二进制值。
- `ck_lead__ingress_completion_phone_pair`（`CHECK`：`(ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL) OR (ingress_completion_phone_ciphertext IS NOT NULL AND ingress_completion_phone_hmac IS NOT NULL)`）：补全电话配对：电话密文与HMAC必须同时存在或同时为空。
- `ck_lead__ingress_completion_email_pair`（`CHECK`：`(ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL) OR (ingress_completion_email_ciphertext IS NOT NULL AND ingress_completion_email_hmac IS NOT NULL)`）：补全邮箱配对：邮箱密文与HMAC必须同时存在或同时为空。
- `ck_lead__ingress_completion_slot`（`CHECK`：`(ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL AND ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL AND ingress_completion_source_code IS NULL AND ingress_completion_source_summary_ciphertext IS NULL AND ingress_completed_by_appointment_id IS NULL AND ingress_completed_at IS NULL AND ingress_completion_digest IS NULL) OR (captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL AND captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL AND (ingress_completion_phone_ciphertext IS NOT NULL OR ingress_completion_email_ciphertext IS NOT NULL) AND ingress_completion_source_code IS NOT NULL AND ingress_completion_source_summary_ciphertext IS NOT NULL AND ingress_completed_by_appointment_id IS NOT NULL AND ingress_completed_at IS NOT NULL AND ingress_completion_digest IS NOT NULL)`）：补全槽完整性：整槽必须全空，或在原始电话与邮箱均缺失时一次写入至少一组联系方式及全部来源元数据。
- `ck_lead__ingress_completion_phone_hmac_length`（`CHECK`：`octet_length(ingress_completion_phone_hmac) = 32`）：摘要格式：ingress_completion_phone_hmac必须保存32字节的规范二进制值。
- `ck_lead__ingress_completion_email_hmac_length`（`CHECK`：`octet_length(ingress_completion_email_hmac) = 32`）：摘要格式：ingress_completion_email_hmac必须保存32字节的规范二进制值。
- `ck_lead__ingress_completion_digest_length`（`CHECK`：`octet_length(ingress_completion_digest) = 32`）：摘要格式：ingress_completion_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_lead__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_lead__parsed_party`：`(tenant_id, parsed_party_id) → party.party(tenant_id, party_id)`。Party解析关系：解析结果必须指向同租户Party。
- `fk_lead__current_assignment`：`(tenant_id, current_assignment_id) → lead.lead_assignment(tenant_id, lead_assignment_id)`。当前分派关系：当前指针必须指向同租户LeadAssignment；所属Lead一致性由命令提交前复验。
- `fk_lead__ingress_completed_by_appointment`：`(tenant_id, ingress_completed_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。补全执行任命关系：完成接入的Appointment必须存在于同一租户。

索引：

- `ux_lead__source_idempotency`：列`(tenant_id, source_account_code, source_record_key_digest)`；唯一=`是`；谓词=`None`。来源幂等索引：阻止同一渠道投递重复落库，不用于判定业务疑似重复。
- `ix_lead__current_disposition`：列`(tenant_id, disposition_code, captured_at)`；唯一=`否`；谓词=`None`。处置查询索引：支持租户内按当前处置和捕获时间检索Lead。

### `lead.lead_assignment`

Lead分派事实：一行代表Lead分派链中一次追加分派，由销售接入域负责；分派核心永久冻结，仅允许一次性关闭和CAS修订，不代表可覆盖的当前负责人历史。

- Fact Owner：`LeadRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, lead_assignment_id)`
- 允许更新字段：`assignment_status_code, closed_at, close_reason_code, revision`
- Write-once字段：`closed_at, close_reason_code`
- 状态字段与初态：`assignment_status_code = OPEN`
- 允许状态转换：`OPEN → CLOSED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `lead_assignment_id` | `uuid` | 否 | `—` | Lead分派事实标识：由应用生成的UUIDv7。 |
| `lead_id` | `uuid` | 否 | `—` | 所属Lead标识：指向同租户不可覆盖的渠道接入记录，写入后不可变。 |
| `assignment_no` | `bigint` | 否 | `—` | 分派序号：从一开始按Lead单调分配，用于检查追加顺序，写入后不可变。 |
| `previous_assignment_id` | `uuid` | 是 | `—` | 前序Assignment标识：为空仅表示链首；非空时指向同一Lead的直接前序，写入后不可变。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 承接Owner任命标识：指向同租户有效Appointment；资格有效性在提交前复验，写入后不可变。 |
| `assignment_reason_code` | `varchar(64)` | 否 | `—` | 分派原因代码：说明本次追加分派的业务原因，写入后不可变。 |
| `assigned_at` | `timestamptz(6)` | 否 | `—` | 分派时间：本次Assignment生效的带时区微秒精度时间，写入后不可变。 |
| `assignment_status_code` | `varchar(64)` | 否 | `—` | 分派状态：仅可由OPEN单向变为CLOSED。 |
| `closed_at` | `timestamptz(6)` | 是 | `—` | 关闭时间：为空表示尚未关闭；仅允许一次从空写入，之后不可更改。 |
| `close_reason_code` | `varchar(64)` | 是 | `—` | 关闭原因代码：仅在关闭时一次写入，之后不可更改。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：本次分派事实首次持久化的时间，永久冻结。 |

约束：

- `ck_lead_assignment__assignment_no_positive`（`CHECK`：`assignment_no > 0`）：分派序号范围：Lead内分派序号必须为正整数。
- `ck_lead_assignment__assignment_status_code`（`CHECK`：`assignment_status_code IN ('OPEN', 'CLOSED')`）：分派状态域：仅允许开放或已关闭。
- `ck_lead_assignment__close_pair`（`CHECK`：`((assignment_status_code = 'OPEN' AND closed_at IS NULL AND close_reason_code IS NULL) OR (assignment_status_code = 'CLOSED' AND closed_at IS NOT NULL AND close_reason_code IS NOT NULL))`）：关闭配对：开放分派不得有关闭信息，已关闭分派必须同时记录关闭时间和原因。
- `ck_lead_assignment__not_self_previous`（`CHECK`：`previous_assignment_id IS NULL OR previous_assignment_id <> lead_assignment_id`）：前序链防自环：Assignment不得把自身声明为前序。
- `ck_lead_assignment__chain_shape`（`CHECK`：`(assignment_no = 1 AND previous_assignment_id IS NULL) OR (assignment_no > 1 AND previous_assignment_id IS NOT NULL)`）：分派链形态：链首序号必须为一且无前序，后续分派必须具有准确前序。
- `ck_lead_assignment__revision_nonnegative`（`CHECK`：`revision >= 0`）：修订号范围：LeadAssignment受控更新的CAS修订号不得为负数。
- `uq_lead_assignment__lead_no`（`UNIQUE`：`tenant_id, lead_id, assignment_no`）：追加顺序唯一性：同一Lead的分派序号不得重复。
- `uq_lead_assignment__id_lead_owner`（`UNIQUE`：`tenant_id, lead_assignment_id, lead_id, owner_appointment_id`）：准确销售路径候选键：供Opportunity证明来源Lead和Owner来自同一Assignment。

物理外键：

- `fk_lead_assignment__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_lead_assignment__lead`：`(tenant_id, lead_id) → lead.lead(tenant_id, lead_id)`。Lead关系：分派必须属于同租户已存在Lead。
- `fk_lead_assignment__previous_assignment`：`(tenant_id, previous_assignment_id) → lead.lead_assignment(tenant_id, lead_assignment_id)`。前序关系：非链首分派必须引用同租户前序Assignment；同属一个Lead由提交前复验。
- `fk_lead_assignment__owner_appointment`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。Owner关系：承接人必须是同租户Appointment。

索引：

- `ux_lead_assignment__previous`：列`(tenant_id, previous_assignment_id)`；唯一=`是`；谓词=`previous_assignment_id IS NOT NULL`。前序链唯一索引：一个前序Assignment最多只有一个直接后继，避免链分叉。
- `ux_lead_assignment__chain_head`：列`(tenant_id, lead_id)`；唯一=`是`；谓词=`previous_assignment_id IS NULL`。链首唯一索引：每个Lead最多存在一个无前序Assignment的链首。
- `ux_lead_assignment__open`：列`(tenant_id, lead_id)`；唯一=`是`；谓词=`assignment_status_code = 'OPEN'`。当前分派唯一索引：每个Lead最多保留一个OPEN Assignment。

### `lead.lead_contact_result`

Lead联系结果事实：一行代表一个CONTACT_LEAD Task对某Lead的第几次联系结果，Fact Owner为LeadRuntime并只追加；任务执行人只是Actor，结果不可覆盖且不代表分派关闭或机会成立。

- Fact Owner：`LeadRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, lead_contact_result_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `lead_contact_result_id` | `uuid` | 否 | `—` | Lead联系结果事实标识：由应用生成的UUIDv7。 |
| `lead_id` | `uuid` | 否 | `—` | 所属Lead标识：联系结果必须归属同租户Lead，写入后不可变。 |
| `lead_assignment_id` | `uuid` | 否 | `—` | 所属分派标识：联系结果必须绑定执行该CONTACT_LEAD Task时的准确Owner任期。 |
| `contact_no` | `bigint` | 否 | `—` | 联系序号：从一开始在Lead内追加，写入后不可变。 |
| `contact_task_id` | `uuid` | 否 | `—` | CONTACT_LEAD TaskOccurrence标识：每个任务至多产生一个联系结果；任务类型由CommandRuntime复验。 |
| `contact_channel_code` | `varchar(64)` | 否 | `—` | 联系渠道代码：电话、邮件等静态注册代码，写入后不可变且不含凭据。 |
| `result_code` | `varchar(64)` | 否 | `—` | 联系结果：仅可取CONNECTED_VALID、NOT_CONNECTED或SUSPECT_INVALID，写入后不可变。 |
| `result_summary` | `text` | 是 | `—` | 结果摘要：仅保存必要的非敏感业务摘要，不得保存沟通正文、Secret或Token；写入后不可变。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | EvidenceRef：为空表示该结果无独立证据提交；非空必须物理关联同租户EvidenceSubmission。 |
| `resulted_at` | `timestamptz(6)` | 否 | `—` | 结果发生时间：带时区微秒精度时间，写入后不可变。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：联系结果首次持久化的时间，永久冻结。 |

约束：

- `ck_lead_contact_result__contact_no_positive`（`CHECK`：`contact_no > 0`）：联系序号范围：Lead内联系序号必须为正整数。
- `ck_lead_contact_result__result_code`（`CHECK`：`result_code IN ('CONNECTED_VALID', 'NOT_CONNECTED', 'SUSPECT_INVALID')`）：联系结果域：严格限制为有效接通、未接通或疑似无效三种冻结结论。
- `uq_lead_contact_result__lead_no`（`UNIQUE`：`tenant_id, lead_id, contact_no`）：追加幂等：同一Lead的联系序号不得重复。
- `uq_lead_contact_result__task`（`UNIQUE`：`tenant_id, contact_task_id`）：任务唯一结果：每个CONTACT_LEAD TaskOccurrence至多写入一个联系结果。
- `uq_lead_contact_result__id_path`（`UNIQUE`：`tenant_id, lead_contact_result_id, lead_id, lead_assignment_id`）：准确资格来源候选键：供Opportunity证明ContactResult、Lead及Assignment来自同一路径。

物理外键：

- `fk_lead_contact_result__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_lead_contact_result__lead`：`(tenant_id, lead_id) → lead.lead(tenant_id, lead_id)`。Lead关系：联系结果必须属于同租户Lead。
- `fk_lead_contact_result__lead_assignment`：`(tenant_id, lead_assignment_id) → lead.lead_assignment(tenant_id, lead_assignment_id)`。分派关系：联系结果必须绑定同租户准确LeadAssignment，Lead一致性由提交前复验。
- `fk_lead_contact_result__contact_task`：`(tenant_id, contact_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。任务关系：结果必须关联同租户TaskOccurrence；CONTACT_LEAD类型由运行时复验。
- `fk_lead_contact_result__evidence_submission`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。

索引：

- `ix_lead_contact_result__lead_time`：列`(tenant_id, lead_id, resulted_at)`；唯一=`否`；谓词=`None`。联系历史索引：支持按Lead和发生时间读取追加结果。

## `opportunity`

机会与报价域：保存单项法律需求、冻结参与角色、追加进展及不可变报价版本包、逐收件人Issue和Response。

- Fact Owner：`OpportunityRuntime`

### `opportunity.opportunity`

Opportunity锚点：一行代表从一个Lead及其唯一Assignment路径形成的一项准确法律需求和Owner；只保存当前报价指针及一次终结槽，不保存通用Stage或Status。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, opportunity_id)`
- 允许更新字段：`current_quote_revision_id, close_outcome_code, closed_at, revision`
- Write-once字段：`close_outcome_code, closed_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `opportunity_id` | `uuid` | 否 | `—` | Opportunity锚点标识：由应用生成的UUIDv7。 |
| `source_lead_id` | `uuid` | 否 | `—` | 来源Lead标识：法律需求由该不可覆盖接入事实转化。 |
| `source_assignment_id` | `uuid` | 否 | `—` | 来源LeadAssignment标识：证明机会沿哪次分派形成，写入后不可变。 |
| `source_contact_result_id` | `uuid` | 否 | `—` | 来源联系结果标识：必须是同一Lead和Assignment上的准确CONNECTED_VALID事实。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | Opportunity Owner任命标识：必须与来源Assignment冻结的Owner一致，该一致性由命令提交前复验。 |
| `legal_need_ciphertext` | `bytea` | 否 | `—` | 法律需求密文：一项法律需求的受保护原始描述，写入后不可覆盖。 |
| `legal_need_digest` | `bytea` | 否 | `—` | 法律需求摘要：规范化法律需求的SHA-256原始32字节摘要，写入后不可变。 |
| `current_quote_revision_id` | `uuid` | 是 | `—` | 当前QuoteRevision指针：为空表示尚无报价版本；仅为当前导航，历史版本不可覆盖。 |
| `close_outcome_code` | `varchar(64)` | 是 | `—` | 终结结果：明确结束该销售机会时一次写入的静态业务结论；未终结为空。 |
| `closed_at` | `timestamptz(6)` | 是 | `—` | 终结时间：形成明确终结事实时一次写入；未终结为空。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：该法律需求首次形成Opportunity的时间，永久冻结。 |

约束：

- `ck_opportunity__closed_pair`（`CHECK`：`(close_outcome_code IS NULL AND closed_at IS NULL) OR (close_outcome_code IS NOT NULL AND closed_at IS NOT NULL)`）：机会终结配对：终结结果和时间必须同时为空或一次写入。
- `ck_opportunity__revision_nonnegative`（`CHECK`：`revision >= 0`）：修订号范围：Opportunity受控更新的CAS修订号不得为负数。
- `uq_opportunity__source_contact_result`（`UNIQUE`：`tenant_id, source_contact_result_id`）：资格来源唯一：一条CONNECTED_VALID联系结果至多形成一个Opportunity。
- `ck_opportunity__legal_need_digest_length`（`CHECK`：`octet_length(legal_need_digest) = 32`）：摘要格式：legal_need_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_opportunity__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_opportunity__source_lead`：`(tenant_id, source_lead_id) → lead.lead(tenant_id, lead_id)`。来源关系：Opportunity必须引用同租户准确Lead。
- `fk_opportunity__source_assignment`：`(tenant_id, source_assignment_id) → lead.lead_assignment(tenant_id, lead_assignment_id)`。来源关系：Opportunity必须沿同租户LeadAssignment形成。
- `fk_opportunity__owner_appointment`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。Owner关系：Opportunity Owner必须为同租户Appointment。
- `fk_opportunity__source_contact_result`：`(tenant_id, source_contact_result_id) → lead.lead_contact_result(tenant_id, lead_contact_result_id)`。资格来源：Opportunity必须引用同租户准确LeadContactResult。
- `fk_opportunity__assignment_path`：`(tenant_id, source_assignment_id, source_lead_id, owner_appointment_id) → lead.lead_assignment(tenant_id, lead_assignment_id, lead_id, owner_appointment_id)`。销售路径：来源Assignment必须同时属于来源Lead并冻结同一Owner。
- `fk_opportunity__contact_path`：`(tenant_id, source_contact_result_id, source_lead_id, source_assignment_id) → lead.lead_contact_result(tenant_id, lead_contact_result_id, lead_id, lead_assignment_id)`。资格路径：来源ContactResult必须同时属于来源Lead和Assignment；CONNECTED_VALID由提交前守卫复验。
- `fk_opportunity__current_quote_revision`：`(tenant_id, current_quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。当前报价关系：指针必须指向同租户QuoteRevision；属于本Opportunity由提交前复验。

索引：

- `ux_opportunity__source_assignment`：列`(tenant_id, source_assignment_id)`；唯一=`是`；谓词=`None`。来源唯一索引：一次LeadAssignment最多形成一项法律需求Opportunity。
- `ix_opportunity__owner_open`：列`(tenant_id, owner_appointment_id, created_at)`；唯一=`否`；谓词=`closed_at IS NULL`。Owner工作台：按Owner和创建时间读取尚未终结的机会。

### `opportunity.opportunity_participation`

Opportunity参与方事实：一行代表某次完整参与集合revision中的一个Party上下文角色，由OpportunityRuntime只追加；同一集合revision共享大小和摘要，不代表Party全局身份或合同角色。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, opportunity_participation_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `opportunity_participation_id` | `uuid` | 否 | `—` | Opportunity参与方事实标识：由应用生成的UUIDv7。 |
| `opportunity_id` | `uuid` | 否 | `—` | 所属Opportunity标识：参与方上下文必须属于同租户法律需求。 |
| `participation_set_revision` | `integer` | 否 | `—` | 完整参与集合版本：同一Opportunity每次重新冻结全部参与方时递增。 |
| `participation_no` | `integer` | 否 | `—` | 集合内序号：从一开始连续编号，按完整集合稳定排序。 |
| `participation_set_size` | `integer` | 否 | `—` | 完整集合大小：同一Opportunity和集合版本的全部行必须保存相同正整数。 |
| `participation_set_digest` | `bytea` | 否 | `—` | 完整集合摘要：覆盖本版本全部参与方、角色、Party快照和上下文的规范摘要。 |
| `party_id` | `uuid` | 否 | `—` | 参与Party标识：物理关联同租户Party；Party可演进但本行角色上下文永久冻结。 |
| `party_revision` | `bigint` | 否 | `—` | Party CAS修订号：形成本集合时用于提交前重验，不声称可从当前态Party回读历史版本。 |
| `party_snapshot_digest` | `bytea` | 否 | `—` | 主体业务快照摘要：冻结本法律需求所需的最小规范名称、主标识选择和角色上下文，不复制完整Party。 |
| `context_role_code` | `varchar(64)` | 否 | `—` | 上下文角色代码：委托人、付款方、对方等静态业务角色，写入后不可变。 |
| `role_context_ciphertext` | `bytea` | 是 | `—` | 角色上下文密文：冻结与该法律需求有关的受保护补充上下文，写入后不可覆盖。 |
| `role_context_digest` | `bytea` | 是 | `—` | 角色上下文摘要：无补充上下文时为空；否则保存SHA-256原始32字节摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：参与方角色首次纳入Opportunity的时间，永久冻结。 |

约束：

- `ck_opportunity_participation__context_pair`（`CHECK`：`((role_context_ciphertext IS NULL AND role_context_digest IS NULL) OR (role_context_ciphertext IS NOT NULL AND role_context_digest IS NOT NULL))`）：角色上下文配对：受保护上下文密文与其摘要必须同时存在或同时为空。
- `ck_opportunity_participation__set_revision`（`CHECK`：`participation_set_revision > 0`）：完整参与集合版本必须为正数。
- `ck_opportunity_participation__participation_no`（`CHECK`：`participation_no > 0 AND participation_no <= participation_set_size`）：集合序号必须为正且不得超过冻结集合大小。
- `ck_opportunity_participation__set_size`（`CHECK`：`participation_set_size > 0`）：完整参与集合大小必须为正数。
- `ck_opportunity_participation__party_revision`（`CHECK`：`party_revision >= 0`）：冻结的Party修订号不得为负数。
- `uq_opportunity_participation__set_no`（`UNIQUE`：`tenant_id, opportunity_id, participation_set_revision, participation_no`）：集合序号唯一：完整参与集合内序号不得重复。
- `uq_opportunity_participation__set_party_role`（`UNIQUE`：`tenant_id, opportunity_id, participation_set_revision, party_id, context_role_code`）：集合角色唯一：同一完整集合内同一Party的同一角色不得重复。
- `ck_opportunity_participation__participation_set_digest_length`（`CHECK`：`octet_length(participation_set_digest) = 32`）：摘要格式：participation_set_digest必须保存32字节的规范二进制值。
- `ck_opportunity_participation__party_snapshot_digest_length`（`CHECK`：`octet_length(party_snapshot_digest) = 32`）：摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。
- `ck_opportunity_participation__role_context_digest_length`（`CHECK`：`octet_length(role_context_digest) = 32`）：摘要格式：role_context_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_opportunity_participation__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_opportunity_participation__opportunity`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。Opportunity关系：参与方角色必须属于同租户Opportunity。
- `fk_opportunity_participation__party`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。Party关系：参与方必须指向同租户Party。

### `opportunity.opportunity_progress`

Opportunity进展事实：一行代表一项法律需求的一次已发生进展，Fact Owner为OpportunityRuntime并按序追加；机会Owner只是责任Actor，不可覆盖且不代表可变的当前机会阶段。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, opportunity_progress_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `opportunity_progress_id` | `uuid` | 否 | `—` | Opportunity进展事实标识：由应用生成的UUIDv7。 |
| `opportunity_id` | `uuid` | 否 | `—` | 所属Opportunity标识：进展必须属于同租户法律需求。 |
| `progress_no` | `bigint` | 否 | `—` | 进展序号：从一开始在Opportunity内追加，写入后不可变。 |
| `progress_type_code` | `varchar(64)` | 否 | `—` | 进展类型代码：描述会谈、材料收到、方案确认等已发生事实，写入后不可变。 |
| `progress_contract_code` | `varchar(64)` | 否 | `—` | 进展事实合同代码：静态注册并准确解释该类型进展的来源与语义。 |
| `progress_contract_version` | `integer` | 否 | `—` | 进展事实合同版本：静态注册合同的正整数版本。 |
| `progress_digest` | `bytea` | 否 | `—` | 进展事实摘要：历史合同覆盖类型与准确来源；R2_OPPORTUNITY_PROGRESS_V1覆盖规范受保护正文及准确业务身份。 |
| `occurred_at` | `timestamptz(6)` | 否 | `—` | 发生时间：进展实际发生的带时区微秒精度时间，写入后不可变。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：进展事实首次持久化的时间，永久冻结。 |
| `source_fact_type` | `varchar(64)` | 是 | `—` | 触发本次OpportunityProgress的多态准确来源事实的静态注册类型。 |
| `source_fact_id` | `uuid` | 是 | `—` | 触发本次OpportunityProgress的多态准确来源事实在所属租户内的准确标识。 |
| `source_fact_revision` | `bigint` | 是 | `—` | 触发本次OpportunityProgress的多态准确来源事实的准确修订号；按哈希冻结时为空。 |
| `source_fact_hash` | `bytea` | 是 | `—` | 触发本次OpportunityProgress的多态准确来源事实的准确规范摘要；按修订冻结时为空。 |
| `progress_body_ciphertext` | `bytea` | 是 | `—` | R2进展正文密文：绑定租户、商机与进展身份；历史无正文记录为空，创建后不可变。 |

约束：

- `ck_opportunity_progress__progress_no_positive`（`CHECK`：`progress_no > 0`）：进展序号范围：Opportunity内进展序号必须为正整数。
- `ck_opportunity_progress__contract_version`（`CHECK`：`progress_contract_version > 0`）：进展事实合同版本必须为正整数。
- `uq_opportunity_progress__opportunity_no`（`UNIQUE`：`tenant_id, opportunity_id, progress_no`）：追加幂等：同一Opportunity的进展序号不得重复。
- `ck_opportunity_progress__source_fact_exact`（`CHECK`：`((source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL))) OR (source_fact_type IS NULL AND source_fact_id IS NULL AND source_fact_revision IS NULL AND source_fact_hash IS NULL))`）：准确引用：触发本次OpportunityProgress的多态准确来源事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_opportunity_progress__progress_digest_length`（`CHECK`：`octet_length(progress_digest) = 32`）：摘要格式：progress_digest必须保存32字节的规范二进制值。
- `ck_opportunity_progress__source_fact_hash_length`（`CHECK`：`octet_length(source_fact_hash) = 32`）：摘要格式：source_fact_hash必须保存32字节的规范二进制值。
- `ck_opportunity_progress__protected_body`（`CHECK`：`(progress_contract_code = 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_contract_version = 1 AND progress_body_ciphertext IS NOT NULL AND octet_length(progress_body_ciphertext) >= 29) OR (progress_contract_code <> 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_body_ciphertext IS NULL)`）：R2受保护正文仅由准确进展合同写入；历史合同不混用此正文。

物理外键：

- `fk_opportunity_progress__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_opportunity_progress__opportunity`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。Opportunity关系：进展必须属于同租户Opportunity。

类型化准确引用：

- `source_fact`：触发本次OpportunityProgress的多态准确来源事实；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_opportunity_progress__timeline`：列`(tenant_id, opportunity_id, occurred_at)`；唯一=`否`；谓词=`None`。进展时间线索引：支持按Opportunity和发生时间读取追加事实。

### `opportunity.quote_revision`

QuoteRevision事实：一行代表某Opportunity的一版不可变报价包头，与Scope、Line及PaymentTerm在同一事务完整写入，由机会域负责；不可覆盖，授权归Responsibility的DecisionRecord，不代表已向任何收件人发出。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_revision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_revision_id` | `uuid` | 否 | `—` | QuoteRevision事实标识：由应用生成的UUIDv7。 |
| `opportunity_id` | `uuid` | 否 | `—` | 所属Opportunity标识：报价版本必须属于同租户一项法律需求。 |
| `quote_revision_no` | `bigint` | 否 | `—` | 报价版本号：从一开始在Opportunity内递增，写入后不可变。 |
| `predecessor_quote_revision_id` | `uuid` | 是 | `—` | 前序报价版本标识：首版本为空，后续版本准确引用直接前序。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 确认草案标识：形成该不可变报价版本包的准确候选输入。 |
| `participation_set_revision` | `integer` | 否 | `—` | 参与集合版本：本QuoteRevision采用的完整OpportunityParticipation集合版本。 |
| `participation_set_digest` | `bytea` | 否 | `—` | 参与集合摘要：必须与该完整集合全部行共享的准确摘要一致。 |
| `package_contract_code` | `varchar(64)` | 否 | `—` | 报价包合同代码：静态注册的Scope、Line和PaymentTerm结构。 |
| `package_contract_version` | `integer` | 否 | `—` | 报价包合同版本：解释全部版本子项的正整数版本。 |
| `currency_code` | `varchar(3)` | 否 | `—` | 报价币种：ISO 4217三位大写代码，整个版本包内金额必须一致。 |
| `total_minor` | `bigint` | 否 | `—` | 报价总金额：以最小货币单位记录，不得为负，写入后不可变。 |
| `content_digest` | `bytea` | 否 | `—` | 版本包内容摘要：覆盖QuoteRevision及同事务Scope、Line、PaymentTerm的规范SHA-256。 |
| `valid_until` | `timestamptz(6)` | 是 | `—` | 自然失效时间：报价版本自身的可信截止时间；旧Issue是否替代仍由准确Issue事实决定。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 创建任职标识：确认并形成该报价版本包的准确Appointment。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：不可变报价版本包在同一事务中首次持久化的时间。 |

约束：

- `ck_quote_revision__quote_revision_no_positive`（`CHECK`：`quote_revision_no > 0`）：报价版本号范围：Opportunity内报价版本号必须为正整数。
- `ck_quote_revision__currency_code`（`CHECK`：`currency_code ~ '^[A-Z]{3}$'`）：币种格式：报价币种必须为三位大写字母。
- `ck_quote_revision__total_minor_nonnegative`（`CHECK`：`total_minor >= 0`）：金额范围：报价总金额最小货币单位不得为负。
- `ck_quote_revision__package_version`（`CHECK`：`package_contract_version > 0`）：报价包合同版本必须为正数。
- `ck_quote_revision__participation_set_revision`（`CHECK`：`participation_set_revision > 0`）：报价采用的完整参与集合版本必须为正数。
- `ck_quote_revision__predecessor_shape`（`CHECK`：`(quote_revision_no = 1 AND predecessor_quote_revision_id IS NULL) OR (quote_revision_no > 1 AND predecessor_quote_revision_id IS NOT NULL)`）：报价版本链：首版本无前序，后续版本必须引用直接前序。
- `ck_quote_revision__valid_until`（`CHECK`：`valid_until IS NULL OR valid_until > created_at`）：自然失效时间若存在必须晚于版本创建时间。
- `uq_quote_revision__opportunity_no`（`UNIQUE`：`tenant_id, opportunity_id, quote_revision_no`）：报价版本唯一性：同一Opportunity的版本号不得重复。
- `uq_quote_revision__predecessor`（`UNIQUE`：`tenant_id, predecessor_quote_revision_id`）：单后继链：一个报价版本最多只有一个直接后继。
- `uq_quote_revision__confirmed_draft`（`UNIQUE`：`tenant_id, confirmed_action_draft_id`）：草案唯一：一份确认草案只能形成一个报价版本包。
- `ck_quote_revision__participation_set_digest_length`（`CHECK`：`octet_length(participation_set_digest) = 32`）：摘要格式：participation_set_digest必须保存32字节的规范二进制值。
- `ck_quote_revision__content_digest_length`（`CHECK`：`octet_length(content_digest) = 32`）：摘要格式：content_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_revision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_revision__opportunity`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。Opportunity关系：报价版本必须属于同租户Opportunity。
- `fk_quote_revision__predecessor`：`(tenant_id, predecessor_quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。报价版本链：后续版本必须引用同租户直接前序。
- `fk_quote_revision__action_draft`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。候选输入：报价版本包必须引用同租户准确确认草案。
- `fk_quote_revision__creator`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。创建主体：报价版本必须记录同租户准确Appointment。

索引：

- `ix_quote_revision__opportunity_created`：列`(tenant_id, opportunity_id, created_at)`；唯一=`否`；谓词=`None`。报价版本索引：支持按Opportunity读取不可变版本历史。

### `opportunity.quote_service_scope`

QuoteServiceScope事实：一行代表某不可变QuoteRevision包中的一项服务范围，由机会域在版本同一事务写入；写入后不可覆盖，不代表另一个报价版本的范围。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_service_scope_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_service_scope_id` | `uuid` | 否 | `—` | QuoteServiceScope事实标识：由应用生成的UUIDv7。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 所属QuoteRevision标识：服务范围必须属于同租户不可变报价版本。 |
| `scope_no` | `bigint` | 否 | `—` | 服务范围序号：从一开始在QuoteRevision内排序，写入后不可变。 |
| `service_code` | `varchar(64)` | 否 | `—` | 服务代码：静态业务代码，写入后不可变。 |
| `scope_summary` | `text` | 否 | `—` | 服务范围摘要：仅保存履约边界的必要非敏感摘要，写入后不可变。 |
| `included` | `boolean` | 否 | `—` | 是否包含：真表示纳入报价服务，假表示明确排除，写入后不可变。 |
| `scope_hash` | `bytea` | 否 | `—` | 范围摘要：本项服务范围规范表示的SHA-256原始32字节摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随QuoteRevision版本包在同一事务持久化的时间。 |

约束：

- `ck_quote_service_scope__scope_no_positive`（`CHECK`：`scope_no > 0`）：范围序号：QuoteRevision内服务范围序号必须为正整数。
- `uq_quote_service_scope__revision_no`（`UNIQUE`：`tenant_id, quote_revision_id, scope_no`）：版本范围唯一性：同一QuoteRevision的服务范围序号不得重复。
- `ck_quote_service_scope__scope_hash_length`（`CHECK`：`octet_length(scope_hash) = 32`）：摘要格式：scope_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_service_scope__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_service_scope__quote_revision`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。版本包关系：服务范围必须属于同租户QuoteRevision。

### `opportunity.quote_line`

QuoteLine事实：一行代表某不可变QuoteRevision包中的一条计价行，由机会域在版本同一事务写入；写入后不可覆盖，不代表收款或付款确认。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_line_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_line_id` | `uuid` | 否 | `—` | QuoteLine事实标识：由应用生成的UUIDv7。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 所属QuoteRevision标识：计价行必须属于同租户不可变报价版本。 |
| `quote_service_scope_id` | `uuid` | 是 | `—` | 关联QuoteServiceScope标识：为空表示包级计价；非空必须属于同一QuoteRevision，后者由提交前复验。 |
| `line_no` | `bigint` | 否 | `—` | 计价行序号：从一开始在QuoteRevision内排序，写入后不可变。 |
| `line_type_code` | `varchar(64)` | 否 | `—` | 计价行类型代码：固定费、阶段费、折扣等静态业务代码，写入后不可变。 |
| `line_summary` | `text` | 否 | `—` | 计价行摘要：仅保存必要的非敏感计价说明，写入后不可变。 |
| `amount_minor` | `bigint` | 否 | `—` | 计价行金额：以最小货币单位记录，可用负值表达明确折扣。 |
| `currency_code` | `varchar(3)` | 否 | `—` | 计价行币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随QuoteRevision版本包在同一事务持久化的时间。 |

约束：

- `ck_quote_line__line_no_positive`（`CHECK`：`line_no > 0`）：计价行序号：QuoteRevision内计价行序号必须为正整数。
- `ck_quote_line__currency_code`（`CHECK`：`currency_code ~ '^[A-Z]{3}$'`）：币种格式：计价行币种必须为三位大写字母。
- `uq_quote_line__revision_no`（`UNIQUE`：`tenant_id, quote_revision_id, line_no`）：版本计价行唯一性：同一QuoteRevision的计价行序号不得重复。

物理外键：

- `fk_quote_line__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_line__quote_revision`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。版本包关系：计价行必须属于同租户QuoteRevision。
- `fk_quote_line__service_scope`：`(tenant_id, quote_service_scope_id) → opportunity.quote_service_scope(tenant_id, quote_service_scope_id)`。服务范围关系：非空时计价行必须关联同租户QuoteServiceScope。

### `opportunity.quote_payment_term`

QuotePaymentTerm事实：一行代表某不可变QuoteRevision包中的一项付款条件，由机会域在版本同一事务写入；写入后不可覆盖，不代表已收款或支付门禁。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_payment_term_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_payment_term_id` | `uuid` | 否 | `—` | QuotePaymentTerm事实标识：由应用生成的UUIDv7。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 所属QuoteRevision标识：付款条件必须属于同租户不可变报价版本。 |
| `term_no` | `bigint` | 否 | `—` | 付款条件序号：从一开始在QuoteRevision内排序，写入后不可变。 |
| `due_basis_code` | `varchar(64)` | 否 | `—` | 到期基准代码：签署、开票、里程碑等静态业务代码，写入后不可变。 |
| `due_offset_days` | `integer` | 否 | `—` | 到期偏移天数：相对到期基准的自然日偏移，可为零但不得为负。 |
| `amount_minor` | `bigint` | 否 | `—` | 应付金额：以最小货币单位记录，不得为负，写入后不可变。 |
| `currency_code` | `varchar(3)` | 否 | `—` | 付款条件币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。 |
| `term_summary` | `text` | 是 | `—` | 付款条件摘要：仅保存必要的非敏感条件说明，写入后不可变。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随QuoteRevision版本包在同一事务持久化的时间。 |

约束：

- `ck_quote_payment_term__term_no_positive`（`CHECK`：`term_no > 0`）：付款条件序号：QuoteRevision内付款条件序号必须为正整数。
- `ck_quote_payment_term__due_offset_nonnegative`（`CHECK`：`due_offset_days >= 0`）：到期偏移范围：到期偏移天数不得为负。
- `ck_quote_payment_term__amount_nonnegative`（`CHECK`：`amount_minor >= 0`）：金额范围：应付金额最小货币单位不得为负。
- `ck_quote_payment_term__currency_code`（`CHECK`：`currency_code ~ '^[A-Z]{3}$'`）：币种格式：付款条件币种必须为三位大写字母。
- `uq_quote_payment_term__revision_no`（`UNIQUE`：`tenant_id, quote_revision_id, term_no`）：版本付款条件唯一性：同一QuoteRevision的付款条件序号不得重复。

物理外键：

- `fk_quote_payment_term__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_payment_term__quote_revision`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。版本包关系：付款条件必须属于同租户QuoteRevision。

### `opportunity.quote_issue`

QuoteIssue事实：一行代表某不可变QuoteRevision向一个冻结收件人发出的一次报价，由机会域负责；新Issue可准确引用其替代的旧Issue，旧Issue不被自动改写，只允许授权单向撤回。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, quote_issue_id)`
- 允许更新字段：`issue_status_code, revoked_at, revocation_reason_code, revision`
- Write-once字段：`revoked_at, revocation_reason_code`
- 状态字段与初态：`issue_status_code = ACTIVE`
- 允许状态转换：`ACTIVE → REVOKED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_issue_id` | `uuid` | 否 | `—` | QuoteIssue事实标识：由应用生成的UUIDv7。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 发出的QuoteRevision标识：写入后不可变，保证内容来自完整不可变版本包。 |
| `recipient_participation_id` | `uuid` | 否 | `—` | 收件人Participation标识：冻结收件人在Opportunity中的上下文角色，写入后不可变。 |
| `recipient_context_digest` | `bytea` | 否 | `—` | 收件人上下文摘要：冻结准确Participation版本、Party和送达地址选择。 |
| `authorization_set_digest` | `bytea` | 否 | `—` | 授权集合摘要：覆盖绑定该QuoteRevision及contentDigest的全部必要DecisionRecord。 |
| `delivery_channel_code` | `varchar(64)` | 否 | `—` | 送达渠道代码：邮件、门户等静态代码，写入后不可变且不含凭据。 |
| `external_action_id` | `uuid` | 是 | `—` | 外部送达ExternalAction标识：为空表示无需外部动作；非空时物理关联同租户外部动作。 |
| `provider_inbox_id` | `uuid` | 是 | `—` | Provider消息标识：权威送达证明来自验签消息时准确引用。 |
| `issued_at` | `timestamptz(6)` | 否 | `—` | 发出时间：逐收件人报价实际发出的带时区微秒精度时间，写入后不可变。 |
| `replaces_quote_issue_id` | `uuid` | 是 | `—` | 被替代的旧QuoteIssue标识：由新Issue在创建时准确引用；为空表示不替代其他Issue。 |
| `issue_status_code` | `varchar(64)` | 否 | `—` | 发出状态：ACTIVE或REVOKED；创建新Issue不自动改变旧Issue。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤回时间：仅在REVOKED时一次写入。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤回原因代码：仅在REVOKED时一次写入，不得包含自由文本案情。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：逐收件人QuoteIssue首次持久化的时间，永久冻结。 |
| `delivery_fact_type` | `varchar(64)` | 否 | `—` | 逐收件人报价已权威发送的准确证明Fact的静态注册类型。 |
| `delivery_fact_id` | `uuid` | 否 | `—` | 逐收件人报价已权威发送的准确证明Fact在所属租户内的准确标识。 |
| `delivery_fact_revision` | `bigint` | 是 | `—` | 逐收件人报价已权威发送的准确证明Fact的准确修订号；按哈希冻结时为空。 |
| `delivery_fact_hash` | `bytea` | 是 | `—` | 逐收件人报价已权威发送的准确证明Fact的准确规范摘要；按修订冻结时为空。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。 |

约束：

- `ck_quote_issue__issue_status_code`（`CHECK`：`issue_status_code IN ('ACTIVE', 'REVOKED')`）：发出状态域：仅允许有效或已撤回；替代通过新Issue不可变引用旧Issue表达。
- `ck_quote_issue__terminal_payload`（`CHECK`：`((issue_status_code = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL) OR (issue_status_code = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL))`）：单向终态载荷：ACTIVE无撤回字段，REVOKED必须一次写入撤回时间和原因。
- `ck_quote_issue__not_self_replacement`（`CHECK`：`replaces_quote_issue_id IS NULL OR replaces_quote_issue_id <> quote_issue_id`）：替代关系防自环：新QuoteIssue不得声明替代自身。
- `ck_quote_issue__revision_nonnegative`（`CHECK`：`revision >= 0`）：修订号范围：QuoteIssue受控更新的CAS修订号不得为负数。
- `uq_quote_issue__replaces`（`UNIQUE`：`tenant_id, replaces_quote_issue_id`）：单后继链：一个旧QuoteIssue最多被一个新Issue直接替代；收件人及版本顺序由提交前重验。
- `ck_quote_issue__delivery_fact_exact`（`CHECK`：`(delivery_fact_type IS NOT NULL AND delivery_fact_id IS NOT NULL AND ((delivery_fact_revision IS NOT NULL AND delivery_fact_revision >= 0 AND delivery_fact_hash IS NULL) OR (delivery_fact_revision IS NULL AND delivery_fact_hash IS NOT NULL)))`）：准确引用：逐收件人报价已权威发送的准确证明Fact必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_quote_issue__recipient_context_digest_length`（`CHECK`：`octet_length(recipient_context_digest) = 32`）：摘要格式：recipient_context_digest必须保存32字节的规范二进制值。
- `ck_quote_issue__authorization_set_digest_length`（`CHECK`：`octet_length(authorization_set_digest) = 32`）：摘要格式：authorization_set_digest必须保存32字节的规范二进制值。
- `ck_quote_issue__delivery_fact_hash_length`（`CHECK`：`octet_length(delivery_fact_hash) = 32`）：摘要格式：delivery_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_issue__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_issue__quote_revision`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。报价版本关系：Issue必须发出同租户不可变QuoteRevision。
- `fk_quote_issue__recipient_participation`：`(tenant_id, recipient_participation_id) → opportunity.opportunity_participation(tenant_id, opportunity_participation_id)`。收件人关系：Issue必须指向同租户冻结Participation。
- `fk_quote_issue__external_action`：`(tenant_id, external_action_id) → external_action.external_action(tenant_id, external_action_id)`。外部动作关系：非空时送达必须关联同租户ExternalAction。
- `fk_quote_issue__provider_inbox`：`(tenant_id, provider_inbox_id) → external_action.provider_inbox(tenant_id, provider_inbox_id)`。Provider证明：非空时必须引用同租户验签消息。
- `fk_quote_issue__replaces`：`(tenant_id, replaces_quote_issue_id) → opportunity.quote_issue(tenant_id, quote_issue_id)`。替代关系：新Issue必须指向同租户准确旧Issue；同一收件人及版本顺序由提交前重验。

类型化准确引用：

- `delivery_fact`：逐收件人报价已权威发送的准确证明Fact；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ux_quote_issue__active_recipient`：列`(tenant_id, quote_revision_id, recipient_participation_id)`；唯一=`是`；谓词=`issue_status_code = 'ACTIVE'`。逐收件人有效Issue唯一索引：同一报价版本对同一收件人最多一个ACTIVE Issue。
- `ix_quote_issue__recipient_time`：列`(tenant_id, recipient_participation_id, issued_at)`；唯一=`否`；谓词=`None`。收件人发出历史索引：支持按冻结收件人和时间读取Issue链。

### `opportunity.quote_response`

QuoteResponse事实：一行代表收件人对准确QuoteIssue版本的一次已收到响应，由机会域按序追加；写入后不可覆盖，不代表合同已成立或报价Issue可被改写。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_response_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_response_id` | `uuid` | 否 | `—` | QuoteResponse事实标识：由应用生成的UUIDv7。 |
| `quote_issue_id` | `uuid` | 否 | `—` | 报价发出标识：响应只能物理引用同租户准确不可变QuoteIssue。 |
| `response_no` | `bigint` | 否 | `—` | 响应序号：从一开始在准确Issue标识内追加，写入后不可变。 |
| `response_code` | `varchar(64)` | 否 | `—` | 响应代码：ACCEPTED、NOT_ACCEPTED、REJECTED或AMBIGUOUS，写入后不可变。 |
| `response_content_ciphertext` | `bytea` | 是 | `—` | 响应内容密文：保存受保护原始响应，写入后不可覆盖。 |
| `response_content_digest` | `bytea` | 是 | `—` | 响应内容摘要：无原始内容时为空；否则保存SHA-256原始32字节摘要。 |
| `provider_inbox_id` | `uuid` | 是 | `—` | Provider消息标识：响应由可信外部回调形成时准确引用。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | EvidenceRef：响应由受控文件证明时准确引用。 |
| `recorded_by_appointment_id` | `uuid` | 是 | `—` | 记录任职标识：由内部人员确认响应时记录；纯Provider事实可为空。 |
| `received_at` | `timestamptz(6)` | 否 | `—` | 收到时间：响应实际接收的带时区微秒精度时间，写入后不可变。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：响应事实首次持久化的时间，永久冻结。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。 |

约束：

- `ck_quote_response__response_no_positive`（`CHECK`：`response_no > 0`）：响应序号范围：准确Issue标识内响应序号必须为正整数。
- `ck_quote_response__response_code`（`CHECK`：`response_code IN ('ACCEPTED', 'NOT_ACCEPTED', 'REJECTED', 'AMBIGUOUS')`）：响应结论只允许接受、暂不接受、明确拒绝或不明确回应。
- `ck_quote_response__content_pair`（`CHECK`：`((response_content_ciphertext IS NULL AND response_content_digest IS NULL) OR (response_content_ciphertext IS NOT NULL AND response_content_digest IS NOT NULL))`）：响应内容配对：受保护内容密文与其摘要必须同时存在或同时为空。
- `ck_quote_response__proof_present`（`CHECK`：`provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL OR recorded_by_appointment_id IS NOT NULL`）：响应证明：每条响应必须至少具有可信Provider消息、EvidenceRef或内部确认任职之一。
- `uq_quote_response__issue_no`（`UNIQUE`：`tenant_id, quote_issue_id, response_no`）：追加幂等：同一QuoteIssue标识下的响应序号不得重复。
- `ck_quote_response__response_content_digest_length`（`CHECK`：`octet_length(response_content_digest) = 32`）：摘要格式：response_content_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_response__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_response__issue`：`(tenant_id, quote_issue_id) → opportunity.quote_issue(tenant_id, quote_issue_id)`。Issue关系：响应必须指向同租户准确不可变QuoteIssue。
- `fk_quote_response__provider_inbox`：`(tenant_id, provider_inbox_id) → external_action.provider_inbox(tenant_id, provider_inbox_id)`。Provider来源：非空时必须引用同租户验签消息。
- `fk_quote_response__evidence_submission`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。
- `fk_quote_response__recorder`：`(tenant_id, recorded_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。人工确认：非空时必须引用同租户准确Appointment。

索引：

- `ix_quote_response__issue_time`：列`(tenant_id, quote_issue_id, received_at)`；唯一=`否`；谓词=`None`。响应历史索引：支持按QuoteIssue标识和接收时间读取追加响应。

### `opportunity.owner_exception`

负责人异常版本：每次观察保留准确历史版本。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, owner_exception_id, revision)`
- 允许更新字段：`is_current`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `owner_exception_id` | `uuid` | 否 | `—` | 负责人异常版本标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `is_current` | `boolean` | 否 | `true` | 当前版本定位标记，仅允许退役。 |
| `opportunity_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `opportunity_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `responsibility_slot` | `varchar(64)` | 否 | `'OPPORTUNITY_OWNER'` | 冻结责任槽。 |
| `frozen_owner_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `current_owner_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `basis_type` | `varchar(64)` | 否 | `—` | 当前有效责任依据类型。 |
| `basis_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `basis_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `task_occurrence_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `task_revision` | `bigint` | 是 | `—` | T01准确版本，JSON安全整数。 |
| `wait_receipt_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `wait_hash` | `bytea` | 是 | `—` | 准确等待摘要。 |
| `reason_codes` | `varchar(64)[]` | 否 | `—` | 规范有序的异常原因集合。 |
| `state` | `varchar(64)` | 否 | `—` | 活动或终态。 |
| `first_observed_at` | `timestamptz(6)` | 否 | `—` | T01数据库业务时刻。 |
| `last_observed_at` | `timestamptz(6)` | 否 | `—` | T01数据库业务时刻。 |
| `last_disposition_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `review_due_at` | `timestamptz(6)` | 是 | `—` | T01数据库业务时刻。 |
| `resolution_kind` | `varchar(64)` | 是 | `—` | 解决类别。 |
| `resolution_type` | `varchar(64)` | 是 | `—` | 准确解决事实类型。 |
| `resolution_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `resolution_revision` | `bigint` | 是 | `—` | T01准确版本，JSON安全整数。 |
| `resolution_hash` | `bytea` | 是 | `—` | 准确不可变验证审计摘要；与解决版本互斥。 |

约束：

- `ck_owner_exception__slot`（`CHECK`：`responsibility_slot = 'OPPORTUNITY_OWNER'`）：T01具名事实一致性。
- `ck_owner_exception__state`（`CHECK`：`state IN ('ACTIVE','COORDINATING','RESOLVED','NO_LONGER_APPLICABLE')`）：T01具名事实一致性。
- `ck_owner_exception__basis`（`CHECK`：`basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')`）：T01具名事实一致性。
- `ck_owner_exception__times`（`CHECK`：`last_observed_at >= first_observed_at`）：T01具名事实一致性。
- `ck_owner_exception__task`（`CHECK`：`(task_occurrence_id IS NULL) = (task_revision IS NULL)`）：T01具名事实一致性。
- `ck_owner_exception__wait`（`CHECK`：`(wait_receipt_id IS NULL) = (wait_hash IS NULL)`）：T01具名事实一致性。
- `ck_owner_exception__coordination`（`CHECK`：`state <> 'COORDINATING' OR (last_disposition_id IS NOT NULL AND review_due_at IS NOT NULL)`）：T01具名事实一致性。
- `ck_owner_exception__resolution`（`CHECK`：`(state IN ('ACTIVE','COORDINATING') AND resolution_kind IS NULL AND resolution_type IS NULL AND resolution_id IS NULL AND resolution_revision IS NULL AND resolution_hash IS NULL) OR (resolution_id IS NOT NULL AND resolution_kind IS NOT NULL AND resolution_type IS NOT NULL AND ((state='RESOLVED' AND resolution_kind='TRANSFER' AND resolution_type='opportunity.responsibility_handoff' AND resolution_revision IS NOT NULL AND resolution_revision=0 AND resolution_hash IS NULL) OR (state='RESOLVED' AND resolution_kind='OWNER_VALIDATED' AND resolution_type='audit.audit_entry' AND resolution_revision IS NULL AND resolution_hash IS NOT NULL) OR (state='NO_LONGER_APPLICABLE' AND resolution_kind='OPPORTUNITY_CLOSED' AND resolution_type='opportunity.opportunity' AND resolution_revision IS NOT NULL AND resolution_hash IS NULL)))`）：T01具名事实一致性。
- `ck_owner_exception__wait_hash_length`（`CHECK`：`octet_length(wait_hash) = 32`）：摘要格式：wait_hash必须保存32字节的规范二进制值。
- `ck_owner_exception__resolution_hash_length`（`CHECK`：`octet_length(resolution_hash) = 32`）：摘要格式：resolution_hash必须保存32字节的规范二进制值。
- `ck_owner_exception__revision_bound`（`CHECK`：`revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_owner_exception__opportunity_revision_bound`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_owner_exception__basis_revision_bound`（`CHECK`：`basis_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_owner_exception__task_revision_bound`（`CHECK`：`task_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_owner_exception__resolution_revision_bound`（`CHECK`：`resolution_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。

物理外键：

- `fk_owner_exception__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_owner_exception__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception__frozen_owner_appointment_id`：`(tenant_id, frozen_owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception__current_owner_appointment_id`：`(tenant_id, current_owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception__task_occurrence_id`：`(tenant_id, task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception__wait_receipt_id`：`(tenant_id, wait_receipt_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception__last_disposition_id`：`(tenant_id, last_disposition_id) → opportunity.owner_exception_disposition(tenant_id, owner_exception_disposition_id)`。同租户身份存在性，准确版本由Owner复验。

索引：

- `uq_owner_exception__current`：列`(tenant_id, owner_exception_id)`；唯一=`是`；谓词=`is_current`。单当前版本。
- `uq_owner_exception__active_slot`：列`(tenant_id, opportunity_id, responsibility_slot)`；唯一=`是`；谓词=`is_current AND state IN ('ACTIVE','COORDINATING')`。同商机责任槽至多一个活动异常周期。

### `opportunity.owner_exception_disposition`

负责人异常处置：不可变人工决定。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, owner_exception_disposition_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `owner_exception_disposition_id` | `uuid` | 否 | `—` | 负责人异常处置标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `owner_exception_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `owner_exception_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `kind` | `varchar(64)` | 否 | `—` | TRANSFER或COORDINATION。 |
| `actor_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `reason` | `text` | 否 | `—` | 规范人工原因。 |
| `decided_at` | `timestamptz(6)` | 否 | `—` | T01数据库业务时刻。 |
| `review_due_at` | `timestamptz(6)` | 是 | `—` | T01数据库业务时刻。 |
| `receiver_appointment_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `responsibility_handoff_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |

约束：

- `ck_owner_exception_disposition__revision_zero`（`CHECK`：`revision=0`）：T01具名事实一致性。
- `ck_owner_exception_disposition__reason`（`CHECK`：`char_length(btrim(reason)) BETWEEN 1 AND 2000`）：T01具名事实一致性。
- `ck_owner_exception_disposition__kind`（`CHECK`：`(kind='COORDINATION' AND review_due_at IS NOT NULL AND review_due_at > decided_at AND receiver_appointment_id IS NULL AND responsibility_handoff_id IS NULL) OR (kind='TRANSFER' AND review_due_at IS NULL AND receiver_appointment_id IS NOT NULL AND responsibility_handoff_id IS NOT NULL)`）：T01具名事实一致性。
- `ck_owner_exception_disposition__revision_bound`（`CHECK`：`revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_owner_exception_disposition__owner_exception_revision_bound`（`CHECK`：`owner_exception_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。

物理外键：

- `fk_owner_exception_disposition__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_owner_exception_disposition__exact_exception`：`(tenant_id, owner_exception_id, owner_exception_revision) → opportunity.owner_exception(tenant_id, owner_exception_id, revision)`。准确异常历史版本。
- `fk_owner_exception_disposition__actor_appointment_id`：`(tenant_id, actor_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception_disposition__receiver_appointment_id`：`(tenant_id, receiver_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_owner_exception_disposition__responsibility_handoff_id`：`(tenant_id, responsibility_handoff_id) → opportunity.responsibility_handoff(tenant_id, responsibility_handoff_id)`。同租户身份存在性，准确版本由Owner复验。

### `opportunity.responsibility_handoff`

商机责任交接：不可变唯一责任链。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, responsibility_handoff_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `responsibility_handoff_id` | `uuid` | 否 | `—` | 商机责任交接标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `opportunity_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `prior_basis_type` | `varchar(64)` | 否 | `—` | 准确前任责任类型。 |
| `prior_basis_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `prior_basis_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `from_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `to_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `actor_appointment_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `owner_exception_disposition_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `old_task_occurrence_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `old_task_revision` | `bigint` | 是 | `—` | T01准确版本，JSON安全整数。 |
| `new_task_occurrence_id` | `uuid` | 否 | `—` | T01同租户准确身份。 |
| `new_task_revision` | `bigint` | 否 | `—` | T01准确版本，JSON安全整数。 |
| `original_due_at` | `timestamptz(6)` | 否 | `—` | T01数据库业务时刻。 |
| `original_wait_receipt_id` | `uuid` | 是 | `—` | T01同租户准确身份。 |
| `original_wait_hash` | `bytea` | 是 | `—` | 准确原等待摘要。 |
| `handed_off_at` | `timestamptz(6)` | 否 | `—` | T01数据库业务时刻。 |

约束：

- `ck_responsibility_handoff__revision_zero`（`CHECK`：`revision=0`）：T01具名事实一致性。
- `uq_responsibility_handoff__prior`（`UNIQUE`：`tenant_id, opportunity_id, prior_basis_type, prior_basis_id, prior_basis_revision`）：准确前任至多一个后继。
- `uq_responsibility_handoff__decision`（`UNIQUE`：`tenant_id, owner_exception_disposition_id`）：决定至多一个交接。
- `ck_responsibility_handoff__basis`（`CHECK`：`prior_basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND prior_basis_id <> responsibility_handoff_id`）：T01具名事实一致性。
- `ck_responsibility_handoff__receiver`（`CHECK`：`from_appointment_id <> to_appointment_id`）：T01具名事实一致性。
- `ck_responsibility_handoff__tasks`（`CHECK`：`(old_task_occurrence_id IS NULL) = (old_task_revision IS NULL) AND (old_task_occurrence_id IS NULL OR old_task_occurrence_id <> new_task_occurrence_id)`）：T01具名事实一致性。
- `ck_responsibility_handoff__wait`（`CHECK`：`(original_wait_receipt_id IS NULL) = (original_wait_hash IS NULL)`）：T01具名事实一致性。
- `ck_responsibility_handoff__original_wait_hash_length`（`CHECK`：`octet_length(original_wait_hash) = 32`）：摘要格式：original_wait_hash必须保存32字节的规范二进制值。
- `ck_responsibility_handoff__revision_bound`（`CHECK`：`revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_responsibility_handoff__opportunity_revision_bound`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_responsibility_handoff__prior_basis_revision_bound`（`CHECK`：`prior_basis_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_responsibility_handoff__old_task_revision_bound`（`CHECK`：`old_task_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `ck_responsibility_handoff__new_task_revision_bound`（`CHECK`：`new_task_revision BETWEEN 0 AND 9007199254740991`）：T01具名事实一致性。
- `uq_responsibility_handoff__new_task`（`UNIQUE`：`tenant_id, new_task_occurrence_id`）：新任务只能由一个交接创建。

物理外键：

- `fk_responsibility_handoff__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_responsibility_handoff__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__from_appointment_id`：`(tenant_id, from_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__to_appointment_id`：`(tenant_id, to_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__actor_appointment_id`：`(tenant_id, actor_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__owner_exception_disposition_id`：`(tenant_id, owner_exception_disposition_id) → opportunity.owner_exception_disposition(tenant_id, owner_exception_disposition_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__old_task_occurrence_id`：`(tenant_id, old_task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__new_task_occurrence_id`：`(tenant_id, new_task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户身份存在性，准确版本由Owner复验。
- `fk_responsibility_handoff__original_wait_receipt_id`：`(tenant_id, original_wait_receipt_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户身份存在性，准确版本由Owner复验。

索引：

- `uq_responsibility_handoff__initial`：列`(tenant_id, opportunity_id)`；唯一=`是`；谓词=`prior_basis_type='opportunity.opportunity'`。商机只能有一个首次交接。

### `opportunity.closure`

不可变商机终结事实；无待办也保留明确依据；说明加密且仅授权后解密。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, closure_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `closure_id` | `uuid` | 否 | `—` | 不可变商机终结事实；无待办也保留明确依据；说明加密且仅授权后解密。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 终结的准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 终结前准确商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 有效责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 有效责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 有效责任依据版本。 |
| `task_occurrence_id` | `uuid` | 是 | `—` | 实际取消的当前普通任务；未建卡时为空。 |
| `task_revision` | `bigint` | 是 | `—` | 取消前任务版本。 |
| `wait_receipt_id` | `uuid` | 是 | `—` | 取消前准确等待事实。 |
| `wait_hash` | `bytea` | 是 | `—` | 取消前准确等待摘要。 |
| `closed_by_appointment_id` | `uuid` | 否 | `—` | 实际终结操作者。 |
| `reason_code` | `varchar(64)` | 否 | `—` | 明确终结原因。 |
| `closure_summary_ciphertext` | `bytea` | 否 | `—` | 受保护简短终结说明；独立AAD绑定租户商机终结事实。 |
| `summary_digest` | `bytea` | 否 | `—` | 规范说明摘要，只用于完整性复验。 |
| `closed_at` | `timestamptz(6)` | 否 | `—` | 数据库终结时间。 |

约束：

- `uq_closure__opportunity`（`UNIQUE`：`tenant_id, opportunity_id`）：每个商机至多一个终结事实。
- `ck_closure__revision`（`CHECK`：`revision=0`）：商机终结具名一致性。
- `ck_closure__opportunity_revision`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740990`）：商机终结具名一致性。
- `ck_closure__responsibility_revision`（`CHECK`：`responsibility_revision BETWEEN 0 AND 9007199254740991`）：商机终结具名一致性。
- `ck_closure__responsibility_type`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')`）：商机终结具名一致性。
- `ck_closure__reason`（`CHECK`：`reason_code IN ('CLIENT_DECLINED','NEED_CANCELLED','OTHER')`）：商机终结具名一致性。
- `ck_closure__task`（`CHECK`：`(task_occurrence_id IS NULL) = (task_revision IS NULL) AND (task_revision IS NULL OR task_revision BETWEEN 0 AND 9007199254740990)`）：商机终结具名一致性。
- `ck_closure__wait`（`CHECK`：`(wait_receipt_id IS NULL) = (wait_hash IS NULL) AND (wait_receipt_id IS NULL OR task_occurrence_id IS NOT NULL)`）：商机终结具名一致性。
- `ck_closure__protected_body`（`CHECK`：`octet_length(closure_summary_ciphertext) BETWEEN 29 AND 16384`）：商机终结具名一致性。
- `ck_closure__wait_hash_length`（`CHECK`：`octet_length(wait_hash) = 32`）：摘要格式：wait_hash必须保存32字节的规范二进制值。
- `ck_closure__summary_digest_length`（`CHECK`：`octet_length(summary_digest) = 32`）：摘要格式：summary_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_closure__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_closure__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确来源。
- `fk_closure__task_occurrence_id`：`(tenant_id, task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确来源。
- `fk_closure__wait_receipt_id`：`(tenant_id, wait_receipt_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户准确来源。
- `fk_closure__closed_by_appointment_id`：`(tenant_id, closed_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确来源。

### `opportunity.customer_requirement_draft`

责任人独立不可变草稿；不依赖普通任务且不会成为确认事实。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, customer_requirement_draft_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `customer_requirement_draft_id` | `uuid` | 否 | `—` | 责任人独立不可变草稿；不依赖普通任务且不会成为确认事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 读取的商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 责任依据版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 保存/确认的当前负责人。 |
| `previous_draft_id` | `uuid` | 是 | `—` | 准确前一保存；首次为空。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 保存时间。 |

约束：

- `ck_customer_requirement_draft__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_customer_requirement_draft__basis`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991`）：准确责任和版本。
- `ck_customer_requirement_draft__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界加密正文。
- `uq_customer_requirement_draft__previous`（`UNIQUE`：`tenant_id, previous_draft_id`）：同一保存版本只能有一个后继。
- `ck_customer_requirement_draft__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_customer_requirement_draft__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_customer_requirement_draft__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_customer_requirement_draft__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_customer_requirement_draft__previous_draft_id`：`(tenant_id, previous_draft_id) → opportunity.customer_requirement_draft(tenant_id, customer_requirement_draft_id)`。同租户准确事实引用。

### `opportunity.customer_requirement_confirmation`

客户参与方及服务需求的不可变完整确认版本；不完成普通任务。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, customer_requirement_confirmation_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `customer_requirement_confirmation_id` | `uuid` | 否 | `—` | 客户参与方及服务需求的不可变完整确认版本；不完成普通任务。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 读取的商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 责任依据版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 保存/确认的当前负责人。 |
| `draft_id` | `uuid` | 否 | `—` | 准确不可变草稿。 |
| `previous_confirmation_id` | `uuid` | 是 | `—` | 前一完整确认；首次为空。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `confirmed_at` | `timestamptz(6)` | 否 | `—` | 确认时间。 |

约束：

- `ck_customer_requirement_confirmation__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_customer_requirement_confirmation__basis`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991`）：准确责任和版本。
- `ck_customer_requirement_confirmation__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界加密正文。
- `uq_customer_requirement_confirmation__draft`（`UNIQUE`：`tenant_id, draft_id`）：一份草稿至多确认一次。
- `uq_customer_requirement_confirmation__previous`（`UNIQUE`：`tenant_id, previous_confirmation_id`）：完整确认版本不允许分叉。
- `ck_customer_requirement_confirmation__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_customer_requirement_confirmation__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_customer_requirement_confirmation__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_customer_requirement_confirmation__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_customer_requirement_confirmation__draft_id`：`(tenant_id, draft_id) → opportunity.customer_requirement_draft(tenant_id, customer_requirement_draft_id)`。同租户准确事实引用。
- `fk_customer_requirement_confirmation__previous_confirmation_id`：`(tenant_id, previous_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。

### `opportunity.customer_requirement_participant`

完整确认中的准确参与方；未知对方只存正文状态，不创建占位主体。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, customer_requirement_participant_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `customer_requirement_participant_id` | `uuid` | 否 | `—` | 完整确认中的准确参与方；未知对方只存正文状态，不创建占位主体。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `confirmation_id` | `uuid` | 否 | `—` | 完整确认版本。 |
| `party_id` | `uuid` | 否 | `—` | 准确主体。 |
| `party_revision` | `bigint` | 否 | `—` | 当时主体版本。 |
| `role` | `varchar(32)` | 否 | `—` | 参与角色。 |
| `profile_version_id` | `uuid` | 否 | `—` | 准确不可变主体显示快照。 |

约束：

- `ck_customer_requirement_participant__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_customer_requirement_participant__role`（`UNIQUE`：`tenant_id, confirmation_id, party_id, role`）：集合中主体角色唯一。
- `ck_customer_requirement_participant__role`（`CHECK`：`role IN ('CLIENT','OPPONENT','OTHER')`）：具名参与方角色。
- `ck_customer_requirement_participant__party_revision`（`CHECK`：`party_revision BETWEEN 0 AND 9007199254740991`）：准确主体版本。

物理外键：

- `fk_customer_requirement_participant__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_customer_requirement_participant__confirmation_id`：`(tenant_id, confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_customer_requirement_participant__party_id`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。同租户准确事实引用。
- `fk_customer_requirement_participant__profile_version_id`：`(tenant_id, profile_version_id) → party.profile_version(tenant_id, profile_version_id)`。同租户准确事实引用。

### `opportunity.customer_requirement_draft_party`

草稿准确主体来源集合；回执逐事实授权无需解密正文。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, customer_requirement_draft_party_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `customer_requirement_draft_party_id` | `uuid` | 否 | `—` | 草稿准确主体来源集合；回执逐事实授权无需解密正文。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `draft_id` | `uuid` | 否 | `—` | 准确不可变草稿。 |
| `party_id` | `uuid` | 否 | `—` | 人工选定的准确主体。 |
| `party_revision` | `bigint` | 否 | `—` | 保存时准确主体版本。 |

约束：

- `ck_customer_requirement_draft_party__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_customer_requirement_draft_party__party`（`UNIQUE`：`tenant_id, draft_id, party_id`）：一份草稿每个主体来源唯一。
- `ck_customer_requirement_draft_party__party_revision`（`CHECK`：`party_revision BETWEEN 0 AND 9007199254740991`）：准确主体版本。

物理外键：

- `fk_customer_requirement_draft_party__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_customer_requirement_draft_party__draft_id`：`(tenant_id, draft_id) → opportunity.customer_requirement_draft(tenant_id, customer_requirement_draft_id)`。同租户准确事实引用。
- `fk_customer_requirement_draft_party__party_id`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。同租户准确事实引用。

### `opportunity.material_version`

材料条目不可变版本；当前版以无后继派生，历史引用保留。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, material_version_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `material_version_id` | `uuid` | 否 | `—` | 材料条目不可变版本；当前版以无后继派生，历史引用保留。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `material_item_id` | `uuid` | 否 | `—` | 首版本身份作为稳定条目身份。 |
| `previous_version_id` | `uuid` | 是 | `—` | 准确前版。 |
| `upload_basis_id` | `uuid` | 否 | `—` | 准确上传依据。 |
| `upload_session_id` | `uuid` | 否 | `—` | 既有上传会话。 |
| `received_source_object_id` | `uuid` | 否 | `—` | 既有扫描来源。 |
| `evidence_submission_id` | `uuid` | 否 | `—` | 既有不可变提交。 |
| `evidence_binding_id` | `uuid` | 否 | `—` | 既有准确绑定。 |
| `opportunity_id` | `uuid` | 否 | `—` | 所属商机。 |
| `purpose_code` | `varchar(64)` | 否 | `—` | 静态材料用途。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `received_by_appointment_id` | `uuid` | 否 | `—` | 实际接收任职。 |
| `received_at` | `timestamptz(6)` | 否 | `—` | 人工接收时间。 |

约束：

- `ck_material_version__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_material_version__previous_version_id`（`UNIQUE`：`tenant_id, previous_version_id`）：准确链节点只能接收一次。
- `uq_material_version__upload_basis_id`（`UNIQUE`：`tenant_id, upload_basis_id`）：准确链节点只能接收一次。
- `uq_material_version__upload_session_id`（`UNIQUE`：`tenant_id, upload_session_id`）：准确链节点只能接收一次。
- `uq_material_version__evidence_submission_id`（`UNIQUE`：`tenant_id, evidence_submission_id`）：准确链节点只能接收一次。
- `uq_material_version__evidence_binding_id`（`UNIQUE`：`tenant_id, evidence_binding_id`）：准确链节点只能接收一次。
- `ck_material_version__purpose`（`CHECK`：`purpose_code IN ('CONTRACT_BUSINESS','CORRESPONDENCE','OTHER')`）：用途不是案件分类。
- `ck_material_version__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：加密材料元数据有界。
- `ck_material_version__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_material_version__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_material_version__previous_version_id`：`(tenant_id, previous_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_material_version__upload_basis_id`：`(tenant_id, upload_basis_id) → evidence.material_upload_basis(tenant_id, material_upload_basis_id)`。同租户准确事实引用。
- `fk_material_version__upload_session_id`：`(tenant_id, upload_session_id) → evidence.upload_session(tenant_id, upload_session_id)`。同租户准确事实引用。
- `fk_material_version__received_source_object_id`：`(tenant_id, received_source_object_id) → evidence.received_source_object(tenant_id, received_source_object_id)`。同租户准确事实引用。
- `fk_material_version__evidence_submission_id`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。同租户准确事实引用。
- `fk_material_version__evidence_binding_id`：`(tenant_id, evidence_binding_id) → evidence.evidence_binding(tenant_id, evidence_binding_id)`。同租户准确事实引用。
- `fk_material_version__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_material_version__received_by_appointment_id`：`(tenant_id, received_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_draft`

不可变报价草稿；保存不完成任何待办。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_draft_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_draft_id` | `uuid` | 否 | `—` | 不可变报价草稿；保存不完成任何待办。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 读取的商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 责任依据版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 保存/确认的当前负责人。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 准确已确认客户需求。 |
| `previous_draft_id` | `uuid` | 是 | `—` | 同一责任人的前次草稿。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 保存时间。 |

约束：

- `ck_quote_draft__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_quote_draft__basis`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991`）：准确责任和版本。
- `ck_quote_draft__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界加密正文。
- `uq_quote_draft__previous`（`UNIQUE`：`tenant_id, previous_draft_id`）：草稿不分叉。
- `ck_quote_draft__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_draft__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_draft__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_quote_draft__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_quote_draft__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_quote_draft__previous_draft_id`：`(tenant_id, previous_draft_id) → opportunity.quote_draft(tenant_id, quote_draft_id)`。同租户准确事实引用。

### `opportunity.quote_package_basis`

既有报价版本的唯一受保护依据；不建立第二报价身份。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_package_basis_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_package_basis_id` | `uuid` | 否 | `—` | 既有报价版本的唯一受保护依据；不建立第二报价身份。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由守卫写入报价包形成事务，防止后来追加子项。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 唯一既有报价版本。 |
| `quote_draft_id` | `uuid` | 否 | `—` | 准确被确认草稿。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 准确客户确认。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 形成时间。 |

约束：

- `ck_quote_package_basis__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_package_basis__quote`（`UNIQUE`：`tenant_id, quote_revision_id`）：每个报价一个依据。
- `uq_quote_package_basis__draft`（`UNIQUE`：`tenant_id, quote_draft_id`）：草稿只能形成一个报价。
- `ck_quote_package_basis__identity`（`CHECK`：`quote_package_basis_id=quote_revision_id`）：同一报价身份。
- `ck_quote_package_basis__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：受保护正文有界。
- `ck_quote_package_basis__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_package_basis__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_package_basis__quote_revision_id`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。同租户准确事实引用。
- `fk_quote_package_basis__quote_draft_id`：`(tenant_id, quote_draft_id) → opportunity.quote_draft(tenant_id, quote_draft_id)`。同租户准确事实引用。
- `fk_quote_package_basis__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。

### `opportunity.quote_approval_policy`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_approval_policy_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_approval_policy_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `organization_unit_id` | `uuid` | 否 | `—` | 明确适用组织。 |
| `policy_code` | `varchar(64)` | 否 | `—` | 具名审批策略。 |
| `policy_version` | `bigint` | 否 | `—` | 策略版本。 |
| `mode` | `varchar(64)` | 否 | `—` | 审批方式。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 配置时间。 |

约束：

- `ck_quote_approval_policy__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_quote_approval_policy__values`（`CHECK`：`policy_code='R2_QUOTE_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode IN ('REQUIRE_APPROVAL','SELF_AUTHORIZED')`）：明确策略及授权模式。
- `uq_quote_approval_policy__version`（`UNIQUE`：`tenant_id, organization_unit_id, policy_code, policy_version`）：同组织策略版本唯一。

物理外键：

- `fk_quote_approval_policy__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_approval_policy__organization_unit_id`：`(tenant_id, organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。同租户准确事实引用。

### `opportunity.quote_approval_policy_signer`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_approval_policy_signer_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_approval_policy_signer_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `policy_id` | `uuid` | 否 | `—` | 准确配置策略。 |
| `appointment_id` | `uuid` | 否 | `—` | 明确获授权的审批或权限内任职。 |

约束：

- `ck_quote_approval_policy_signer__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_approval_policy_signer__member`（`UNIQUE`：`tenant_id, policy_id, appointment_id`）：配置成员不重复。

物理外键：

- `fk_quote_approval_policy_signer__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_approval_policy_signer__policy_id`：`(tenant_id, policy_id) → opportunity.quote_approval_policy(tenant_id, quote_approval_policy_id)`。同租户准确事实引用。
- `fk_quote_approval_policy_signer__appointment_id`：`(tenant_id, appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_approval_request`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_approval_request_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_approval_request_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 准确报价。 |
| `policy_id` | `uuid` | 否 | `—` | 准确策略版本。 |
| `policy_code` | `varchar(64)` | 否 | `—` | 策略代码。 |
| `policy_version` | `bigint` | 否 | `—` | 策略版本。 |
| `requested_by` | `uuid` | 否 | `—` | 提交任职。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 提交时间。 |

约束：

- `ck_quote_approval_request__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_approval_request__quote`（`UNIQUE`：`tenant_id, quote_revision_id`）：准确报价仅一次审批请求。

物理外键：

- `fk_quote_approval_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_approval_request__quote_revision_id`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。同租户准确事实引用。
- `fk_quote_approval_request__policy_id`：`(tenant_id, policy_id) → opportunity.quote_approval_policy(tenant_id, quote_approval_policy_id)`。同租户准确事实引用。
- `fk_quote_approval_request__requested_by`：`(tenant_id, requested_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_approval_member`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_approval_member_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_approval_member_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `request_id` | `uuid` | 否 | `—` | 准确审批请求。 |
| `appointment_id` | `uuid` | 否 | `—` | 明确审批人。 |
| `task_id` | `uuid` | 否 | `—` | 准确审批待办。 |

约束：

- `ck_quote_approval_member__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_approval_member__appointment`（`UNIQUE`：`tenant_id, request_id, appointment_id`）：请求成员唯一。
- `uq_quote_approval_member__task`（`UNIQUE`：`tenant_id, task_id`）：待办只属于一个成员。

物理外键：

- `fk_quote_approval_member__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_approval_member__request_id`：`(tenant_id, request_id) → opportunity.quote_approval_request(tenant_id, quote_approval_request_id)`。同租户准确事实引用。
- `fk_quote_approval_member__appointment_id`：`(tenant_id, appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_quote_approval_member__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。

### `opportunity.quote_approval_decision`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_approval_decision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_approval_decision_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `member_id` | `uuid` | 否 | `—` | 准确审批成员。 |
| `decision` | `varchar(64)` | 否 | `—` | 批准或退回。 |
| `reason_ciphertext` | `bytea` | 否 | `—` | 审批说明密文。 |
| `reason_digest` | `bytea` | 否 | `—` | 审批说明摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 决定时间。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。 |

约束：

- `ck_quote_approval_decision__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_approval_decision__member`（`UNIQUE`：`tenant_id, member_id`）：每个审批成员一次决定。
- `ck_quote_approval_decision__values`（`CHECK`：`decision IN ('APPROVED','RETURNED') AND octet_length(reason_ciphertext) BETWEEN 29 AND 131072`）：决定及有界密文。
- `ck_quote_approval_decision__reason_digest_length`（`CHECK`：`octet_length(reason_digest) = 32`）：摘要格式：reason_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_approval_decision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_approval_decision__member_id`：`(tenant_id, member_id) → opportunity.quote_approval_member(tenant_id, quote_approval_member_id)`。同租户准确事实引用。

### `opportunity.quote_manual_delivery`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_manual_delivery_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_manual_delivery_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `quote_revision_id` | `uuid` | 否 | `—` | 准确报价。 |
| `material_version_id` | `uuid` | 否 | `—` | T06准确证据版本。 |
| `recipient_ciphertext` | `bytea` | 否 | `—` | 实际接收人受保护内容。 |
| `body_digest` | `bytea` | 否 | `—` | 交付内容摘要。 |
| `channel` | `varchar(64)` | 否 | `—` | 实际交付方式。 |
| `occurred_at` | `timestamptz(6)` | 否 | `—` | 实际发生时间。 |
| `recorded_by` | `uuid` | 否 | `—` | 确认交付任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 记录时间。 |

约束：

- `ck_quote_manual_delivery__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_quote_manual_delivery__values`（`CHECK`：`octet_length(recipient_ciphertext) BETWEEN 29 AND 131072 AND channel ~ '^[A-Z][A-Z0-9_]{0,63}$' AND occurred_at<=created_at`）：必要交付信息及时间。
- `ck_quote_manual_delivery__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_manual_delivery__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_manual_delivery__quote_revision_id`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。同租户准确事实引用。
- `fk_quote_manual_delivery__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_quote_manual_delivery__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_response_basis`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_response_basis_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_response_basis_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `quote_response_id` | `uuid` | 否 | `—` | 既有准确客户回复。 |
| `material_version_id` | `uuid` | 否 | `—` | T06准确证明版本。 |
| `next_check_at` | `timestamptz(6)` | 是 | `—` | 暂不接受或不明确时下一行动。 |

约束：

- `ck_quote_response_basis__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_response_basis__response`（`UNIQUE`：`tenant_id, quote_response_id`）：回复唯一补充依据。
- `ck_quote_response_basis__identity`（`CHECK`：`quote_response_basis_id=quote_response_id`）：与既有回复身份一致。

物理外键：

- `fk_quote_response_basis__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_response_basis__quote_response_id`：`(tenant_id, quote_response_id) → opportunity.quote_response(tenant_id, quote_response_id)`。同租户准确事实引用。
- `fk_quote_response_basis__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。

### `opportunity.contract_preparation_source`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_preparation_source_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_preparation_source_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `quote_response_id` | `uuid` | 否 | `—` | 准确接受回复。 |
| `source_kind` | `varchar(64)` | 否 | `—` | 本阶段只允许报价接受来源。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 形成时间。 |

约束：

- `ck_contract_preparation_source__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_contract_preparation_source__response`（`UNIQUE`：`tenant_id, quote_response_id`）：接受来源唯一。
- `ck_contract_preparation_source__kind`（`CHECK`：`source_kind='ACCEPTED_QUOTE'`）：不伪造直接授权来源。

物理外键：

- `fk_contract_preparation_source__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_preparation_source__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_contract_preparation_source__quote_response_id`：`(tenant_id, quote_response_id) → opportunity.quote_response(tenant_id, quote_response_id)`。同租户准确事实引用。

### `opportunity.quote_workflow`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_workflow_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `quote_revision_id` | `uuid` | 是 | `—` | 准确报价。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | 直接前一工作流事实。 |
| `stage` | `varchar(64)` | 否 | `—` | 有界业务阶段。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 下一责任任职。 |
| `task_id` | `uuid` | 是 | `—` | 对应已有待办。 |
| `prior_task_id` | `uuid` | 是 | `—` | 移交的原跟进待办。 |
| `next_check_at` | `timestamptz(6)` | 是 | `—` | 约定下一行动。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 形成时间。 |

约束：

- `ck_quote_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_workflow__previous`（`UNIQUE`：`tenant_id, previous_workflow_id`）：工作流事实不分叉。
- `ck_quote_workflow__stage`（`CHECK`：`stage IN ('PREPARE','SUBMIT_APPROVAL','AWAIT_APPROVAL','DELIVER','AWAIT_REPLY','FOLLOW_UP','CLARIFY_REPLY','SALES_DISPOSITION','ACCEPTED','RETURNED','OWNER_EXCEPTION')`）：报价业务阶段域。
- `ck_quote_workflow__initial_preparation`（`CHECK`：`quote_revision_id IS NOT NULL OR (stage='PREPARE' AND previous_workflow_id IS NULL AND task_id IS NOT NULL AND next_check_at IS NULL)`）：正式报价前仅允许具名准备责任。

物理外键：

- `fk_quote_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_quote_workflow__quote_revision_id`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。同租户准确事实引用。
- `fk_quote_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → opportunity.quote_workflow(tenant_id, quote_workflow_id)`。同租户准确事实引用。
- `fk_quote_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_quote_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_quote_workflow__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。

### `opportunity.quote_preparation_intent`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_preparation_intent_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_preparation_intent_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 提交时商机版本。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 人工确认的准确客户资料。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 准确责任来源类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 准确责任来源。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 准确责任版本。 |
| `workflow_id` | `uuid` | 否 | `—` | 本次报价准备工作流。 |
| `task_id` | `uuid` | 否 | `—` | 接管后的报价准备待办。 |
| `prior_task_id` | `uuid` | 是 | `—` | 被接管的普通跟进待办。 |
| `requested_by` | `uuid` | 否 | `—` | 明确开始准备的任职。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 可信提交时间。 |

约束：

- `ck_quote_preparation_intent__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_preparation_intent__opportunity`（`UNIQUE`：`tenant_id, opportunity_id`）：首次准备意图唯一。
- `uq_quote_preparation_intent__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：准备工作流唯一来源。
- `ck_quote_preparation_intent__versions`（`CHECK`：`opportunity_revision>=0 AND responsibility_revision>=0 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')`）：准确责任及版本。

物理外键：

- `fk_quote_preparation_intent__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_preparation_intent__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_quote_preparation_intent__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_quote_preparation_intent__workflow_id`：`(tenant_id, workflow_id) → opportunity.quote_workflow(tenant_id, quote_workflow_id)`。同租户准确事实引用。
- `fk_quote_preparation_intent__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_quote_preparation_intent__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_quote_preparation_intent__requested_by`：`(tenant_id, requested_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.followup_attempt`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, followup_attempt_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `followup_attempt_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 提交时版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 准确责任来源。 |
| `responsibility_id` | `uuid` | 否 | `—` | 准确责任来源身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 准确责任来源版本。 |
| `context_code` | `varchar(64)` | 否 | `—` | 普通跟进或报价回复。 |
| `attempt_code` | `varchar(64)` | 否 | `—` | 本次真实情况。 |
| `prior_task_id` | `uuid` | 否 | `—` | 被本次安排接管的任务。 |
| `prior_task_revision` | `bigint` | 否 | `—` | 原任务准确版本。 |
| `prior_wait_id` | `uuid` | 是 | `—` | 原等待。 |
| `prior_wait_hash` | `bytea` | 是 | `—` | 原等待摘要。 |
| `task_id` | `uuid` | 否 | `—` | 唯一后继任务。 |
| `quote_workflow_id` | `uuid` | 是 | `—` | 本次报价前序工作流。 |
| `next_quote_workflow_id` | `uuid` | 是 | `—` | 本次报价等待工作流。 |
| `recorded_by` | `uuid` | 否 | `—` | 实际记录任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系尝试说明密文。 |
| `body_digest` | `bytea` | 否 | `—` | 规范尝试正文与来源摘要。 |
| `occurred_at` | `timestamptz(6)` | 否 | `—` | 实际联系时间。 |
| `next_check_at` | `timestamptz(6)` | 否 | `—` | 约定下一次联系。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_followup_attempt__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_followup_attempt__prior_task`（`UNIQUE`：`tenant_id, prior_task_id`）：每项原责任仅一次安排。
- `uq_followup_attempt__task`（`UNIQUE`：`tenant_id, task_id`）：后继只由一次尝试形成。
- `ck_followup_attempt__values`（`CHECK`：`context_code IN ('OPPORTUNITY','QUOTE') AND attempt_code IN ('NOT_CONNECTED','NO_REPLY','NO_EFFECTIVE_PROGRESS') AND prior_task_id<>task_id AND opportunity_revision BETWEEN 0 AND 9007199254740991 AND prior_task_revision BETWEEN 0 AND 9007199254740990 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND occurred_at<=created_at AND next_check_at>created_at AND (prior_wait_id IS NULL)=(prior_wait_hash IS NULL) AND ((context_code='QUOTE' AND quote_workflow_id IS NOT NULL AND next_quote_workflow_id IS NOT NULL) OR (context_code='OPPORTUNITY' AND quote_workflow_id IS NULL AND next_quote_workflow_id IS NULL))`）：准确来源及过去尝试、未来安排。
- `ck_followup_attempt__prior_wait_hash_length`（`CHECK`：`octet_length(prior_wait_hash) = 32`）：摘要格式：prior_wait_hash必须保存32字节的规范二进制值。
- `ck_followup_attempt__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_followup_attempt__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_followup_attempt__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_followup_attempt__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_followup_attempt__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_followup_attempt__prior_wait_id`：`(tenant_id, prior_wait_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户准确事实引用。
- `fk_followup_attempt__quote_workflow_id`：`(tenant_id, quote_workflow_id) → opportunity.quote_workflow(tenant_id, quote_workflow_id)`。同租户准确事实引用。
- `fk_followup_attempt__next_quote_workflow_id`：`(tenant_id, next_quote_workflow_id) → opportunity.quote_workflow(tenant_id, quote_workflow_id)`。同租户准确事实引用。
- `fk_followup_attempt__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_termination`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_termination_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_termination_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `opportunity_id` | `uuid` | 否 | `—` | 本次商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 结束前准确商机版本。 |
| `quote_workflow_id` | `uuid` | 否 | `—` | 准确末端报价工作流。 |
| `quote_revision_id` | `uuid` | 是 | `—` | 准确当前报价；准备阶段可空。 |
| `closure_id` | `uuid` | 否 | `—` | 本次主线终点及受保护原因。 |
| `recorded_by` | `uuid` | 否 | `—` | 现任销售。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 可信记录时间。 |

约束：

- `ck_quote_termination__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_termination__opportunity`（`UNIQUE`：`tenant_id, opportunity_id`）：每个报价主线仅一个终点。
- `uq_quote_termination__closure`（`UNIQUE`：`tenant_id, closure_id`）：准确终点唯一。
- `ck_quote_termination__opportunity_revision`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740990`）：版本可安全递增。

物理外键：

- `fk_quote_termination__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_termination__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_quote_termination__quote_workflow_id`：`(tenant_id, quote_workflow_id) → opportunity.quote_workflow(tenant_id, quote_workflow_id)`。同租户准确事实引用。
- `fk_quote_termination__quote_revision_id`：`(tenant_id, quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。同租户准确事实引用。
- `fk_quote_termination__closure_id`：`(tenant_id, closure_id) → opportunity.closure(tenant_id, closure_id)`。同租户准确事实引用。
- `fk_quote_termination__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `opportunity.quote_termination_task`

报价闭环不可变准确事实；不构成合同签署。

- Fact Owner：`OpportunityRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, quote_termination_task_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `quote_termination_task_id` | `uuid` | 否 | `—` | 报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `quote_termination_id` | `uuid` | 否 | `—` | 准确终止依据。 |
| `task_id` | `uuid` | 否 | `—` | 取消的未完成任务。 |
| `task_revision` | `bigint` | 否 | `—` | 取消前准确版本。 |
| `prior_state` | `varchar(64)` | 否 | `—` | 取消前状态。 |
| `wait_id` | `uuid` | 是 | `—` | 准确末端等待。 |
| `wait_hash` | `bytea` | 是 | `—` | 准确末端等待摘要。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 可信记录时间。 |

约束：

- `ck_quote_termination_task__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_quote_termination_task__task`（`UNIQUE`：`tenant_id, task_id`）：未完成责任仅取消一次。
- `ck_quote_termination_task__state`（`CHECK`：`task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))`）：准确取消前状态和等待。
- `ck_quote_termination_task__wait_hash_length`（`CHECK`：`octet_length(wait_hash) = 32`）：摘要格式：wait_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_quote_termination_task__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_quote_termination_task__quote_termination_id`：`(tenant_id, quote_termination_id) → opportunity.quote_termination(tenant_id, quote_termination_id)`。同租户准确事实引用。
- `fk_quote_termination_task__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_quote_termination_task__wait_id`：`(tenant_id, wait_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户准确事实引用。

## `conflict`

冲突审查域：冻结PRE_CONTRACT或PRE_TRANSFER完整范围、规则与语料，保存不可变参与方和Finding；决定统一归Responsibility。

- Fact Owner：`ConflictReviewRuntime`

### `conflict.conflict_review`

ConflictReview事实：一行与Party和Finding集合在同一事务封存一次PRE_CONTRACT或PRE_TRANSFER审查及初始结论；仅Finding集合可按Decision单向收敛为BLOCKED或WAIVED。

- Fact Owner：`ConflictReviewRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, conflict_review_id)`
- 允许更新字段：`resolution_code, resolution_digest, resolved_at, revision`
- Write-once字段：`resolution_code, resolution_digest, resolved_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `conflict_review_id` | `uuid` | 否 | `—` | ConflictReview事实标识：由应用生成的UUIDv7。 |
| `review_type_code` | `varchar(64)` | 否 | `—` | 审查类型：仅可取PRE_CONTRACT或PRE_TRANSFER，写入后不可变。 |
| `legal_need_digest` | `bytea` | 否 | `—` | 法律需求摘要：冻结本次审查对应的准确法律需求语义，不复制需求正文。 |
| `review_contract_code` | `varchar(64)` | 否 | `—` | 审查合同代码：静态注册并解释范围、规则输入和结论结构。 |
| `review_contract_version` | `integer` | 否 | `—` | 审查合同版本：静态注册审查合同的正整数版本。 |
| `scope_hash` | `bytea` | 否 | `—` | 完整审查范围摘要：覆盖所有ConflictReviewParty及其冻结角色的规范SHA-256原始32字节摘要。 |
| `rule_set_code` | `varchar(64)` | 否 | `—` | 冲突规则集代码：静态注册的规则集身份，写入后不可变。 |
| `rule_set_revision` | `bigint` | 否 | `—` | 冲突规则集修订号：冻结本次实际执行的准确规则版本，必须为非负。 |
| `rule_set_hash` | `bytea` | 否 | `—` | 冲突规则语义摘要：实际执行规则语料的SHA-256原始32字节摘要。 |
| `corpus_code` | `varchar(64)` | 否 | `—` | 比对语料代码：静态注册的审查语料身份，写入后不可变。 |
| `corpus_revision` | `bigint` | 否 | `—` | 比对语料修订号：冻结本次使用的准确语料版本，必须为非负。 |
| `corpus_hash` | `bytea` | 否 | `—` | 比对语料摘要：本次实际审查语料的SHA-256原始32字节摘要。 |
| `initial_conclusion_code` | `varchar(64)` | 否 | `—` | 初始结论：CLEAR、NEED_INFO或FINDINGS，在审查封存事务中不可变写入。 |
| `finding_count` | `integer` | 否 | `—` | Finding数量：与同事务写入的ConflictFinding集合准确一致。 |
| `reviewed_at` | `timestamptz(6)` | 否 | `—` | 审查执行时间：使用已冻结范围、规则和语料完成计算的时间，写入后不可变。 |
| `resolution_code` | `varchar(64)` | 是 | `—` | Finding裁决收敛：BLOCKED或WAIVED；CLEAR和NEED_INFO不使用本槽。 |
| `resolution_digest` | `bytea` | 是 | `—` | 裁决集合摘要：覆盖本Review、scopeHash、全部Finding和各authoritySlot Decision；未收敛为空。 |
| `resolved_at` | `timestamptz(6)` | 是 | `—` | 裁决收敛时间：BLOCKED或全部必要槽WAIVE后一次写入。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：审查快照首次持久化的时间，永久冻结。 |
| `trigger_fact_type` | `varchar(64)` | 否 | `—` | 触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的静态注册类型。 |
| `trigger_fact_id` | `uuid` | 否 | `—` | 触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实在所属租户内的准确标识。 |
| `trigger_fact_revision` | `bigint` | 是 | `—` | 触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的准确修订号；按哈希冻结时为空。 |
| `trigger_fact_hash` | `bytea` | 是 | `—` | 触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_conflict_review__review_type_code`（`CHECK`：`review_type_code IN ('PRE_CONTRACT', 'PRE_TRANSFER')`）：审查类型域：冲突门禁仅允许合同前或转移前。
- `ck_conflict_review__contract_version`（`CHECK`：`review_contract_version > 0`）：审查合同版本必须为正整数。
- `ck_conflict_review__rule_set_revision_nonnegative`（`CHECK`：`rule_set_revision >= 0`）：规则修订范围：冻结的冲突规则集修订号不得为负数。
- `ck_conflict_review__corpus_revision_nonnegative`（`CHECK`：`corpus_revision >= 0`）：语料修订范围：冻结的比对语料修订号不得为负数。
- `ck_conflict_review__initial_conclusion_code`（`CHECK`：`initial_conclusion_code IN ('CLEAR', 'NEED_INFO', 'FINDINGS')`）：初始结论仅允许完整零Finding、明确业务信息缺失或存在Finding。
- `ck_conflict_review__finding_count`（`CHECK`：`finding_count >= 0 AND ((initial_conclusion_code = 'CLEAR' AND finding_count = 0) OR (initial_conclusion_code = 'NEED_INFO' AND finding_count = 0) OR (initial_conclusion_code = 'FINDINGS' AND finding_count > 0))`）：Finding集合：CLEAR必须完整且零Finding，NEED_INFO只表示业务信息缺失，FINDINGS必须至少一项。
- `ck_conflict_review__resolution_code`（`CHECK`：`resolution_code IN ('BLOCKED', 'WAIVED')`）：裁决收敛只允许任一阻断或全部必要授权槽豁免。
- `ck_conflict_review__resolution_pair`（`CHECK`：`(resolution_code IS NULL AND resolution_digest IS NULL AND resolved_at IS NULL) OR (initial_conclusion_code = 'FINDINGS' AND resolution_code IS NOT NULL AND resolution_digest IS NOT NULL AND resolved_at IS NOT NULL)`）：裁决一次写入：只有FINDINGS可把结果、Decision集合摘要和时间一次性全部写入。
- `ck_conflict_review__revision_nonnegative`（`CHECK`：`revision >= 0`）：修订号范围：ConflictReview受控更新的CAS修订号不得为负数。
- `ck_conflict_review__trigger_fact_exact`（`CHECK`：`(trigger_fact_type IS NOT NULL AND trigger_fact_id IS NOT NULL AND ((trigger_fact_revision IS NOT NULL AND trigger_fact_revision >= 0 AND trigger_fact_hash IS NULL) OR (trigger_fact_revision IS NULL AND trigger_fact_hash IS NOT NULL)))`）：准确引用：触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_conflict_review__legal_need_digest_length`（`CHECK`：`octet_length(legal_need_digest) = 32`）：摘要格式：legal_need_digest必须保存32字节的规范二进制值。
- `ck_conflict_review__scope_hash_length`（`CHECK`：`octet_length(scope_hash) = 32`）：摘要格式：scope_hash必须保存32字节的规范二进制值。
- `ck_conflict_review__rule_set_hash_length`（`CHECK`：`octet_length(rule_set_hash) = 32`）：摘要格式：rule_set_hash必须保存32字节的规范二进制值。
- `ck_conflict_review__corpus_hash_length`（`CHECK`：`octet_length(corpus_hash) = 32`）：摘要格式：corpus_hash必须保存32字节的规范二进制值。
- `ck_conflict_review__resolution_digest_length`（`CHECK`：`octet_length(resolution_digest) = 32`）：摘要格式：resolution_digest必须保存32字节的规范二进制值。
- `ck_conflict_review__trigger_fact_hash_length`（`CHECK`：`octet_length(trigger_fact_hash) = 32`）：摘要格式：trigger_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_conflict_review__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。

类型化准确引用：

- `trigger_fact`：触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_conflict_review__trigger_scope`：列`(tenant_id, review_type_code, trigger_fact_type, trigger_fact_id, trigger_fact_revision, trigger_fact_hash, scope_hash, rule_set_hash, corpus_hash)`；唯一=`否`；谓词=`None`。审查来源索引：按准确触发版本或摘要、范围、规则和语料读取历史Review；不设置自然唯一，以允许Decision或有效性变化后创建新Review。
- `ix_conflict_review__unresolved`：列`(tenant_id, reviewed_at)`；唯一=`否`；谓词=`initial_conclusion_code = 'FINDINGS' AND resolution_code IS NULL`。待收敛审查索引：只定位存在Finding且尚未写入裁决结果的Review。

### `conflict.conflict_review_party`

ConflictReviewParty事实：一行代表某Review完整scope内一个Party及其冻结审查角色，由冲突审查域随Review写入且不可变；不代表Party当前全局资料或最终冲突结论。

- Fact Owner：`ConflictReviewRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, conflict_review_party_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `conflict_review_party_id` | `uuid` | 否 | `—` | ConflictReviewParty事实标识：由应用生成的UUIDv7。 |
| `conflict_review_id` | `uuid` | 否 | `—` | 所属ConflictReview标识：范围参与方必须属于同租户审查。 |
| `party_id` | `uuid` | 否 | `—` | 范围Party标识：物理关联同租户Party，身份资料有效性在审查提交前复验。 |
| `scope_role_code` | `varchar(64)` | 否 | `—` | 审查范围角色：委托方、对方、关联方等静态角色，写入后不可变。 |
| `party_snapshot_hash` | `bytea` | 否 | `—` | Party审查快照摘要：冻结本次用于匹配的必要规范字段，保存SHA-256原始32字节摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：该Party角色纳入完整审查范围的时间，永久冻结。 |
| `source_item_type` | `varchar(64)` | 否 | `—` | 本次Review实际纳入该Party和上下文角色的准确来源Fact的静态注册类型。 |
| `source_item_id` | `uuid` | 否 | `—` | 本次Review实际纳入该Party和上下文角色的准确来源Fact在所属租户内的准确标识。 |
| `source_item_revision` | `bigint` | 是 | `—` | 本次Review实际纳入该Party和上下文角色的准确来源Fact的准确修订号；按哈希冻结时为空。 |
| `source_item_hash` | `bytea` | 是 | `—` | 本次Review实际纳入该Party和上下文角色的准确来源Fact的准确规范摘要；按修订冻结时为空。 |

约束：

- `uq_conflict_review_party__party_role`（`UNIQUE`：`tenant_id, conflict_review_id, party_id, scope_role_code`）：范围角色唯一性：同一Party在同一Review的同一审查角色只出现一次。
- `ck_conflict_review_party__source_item_exact`（`CHECK`：`(source_item_type IS NOT NULL AND source_item_id IS NOT NULL AND ((source_item_revision IS NOT NULL AND source_item_revision >= 0 AND source_item_hash IS NULL) OR (source_item_revision IS NULL AND source_item_hash IS NOT NULL)))`）：准确引用：本次Review实际纳入该Party和上下文角色的准确来源Fact必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_conflict_review_party__party_snapshot_hash_length`（`CHECK`：`octet_length(party_snapshot_hash) = 32`）：摘要格式：party_snapshot_hash必须保存32字节的规范二进制值。
- `ck_conflict_review_party__source_item_hash_length`（`CHECK`：`octet_length(source_item_hash) = 32`）：摘要格式：source_item_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_conflict_review_party__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_conflict_review_party__conflict_review`：`(tenant_id, conflict_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。Review关系：范围参与方必须属于同租户ConflictReview。
- `fk_conflict_review_party__party`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。Party关系：审查范围参与方必须指向同租户Party。

类型化准确引用：

- `source_item`：本次Review实际纳入该Party和上下文角色的准确来源Fact；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_conflict_review_party__review`：列`(tenant_id, conflict_review_id, scope_role_code)`；唯一=`否`；谓词=`None`。完整范围读取索引：支持按Review和审查角色枚举全部Party。

### `conflict.conflict_finding`

ConflictFinding事实：一行代表某Review基于冻结规则与语料产生的一条不可变命中，由冲突审查域只追加；每个Finding及authoritySlot的决定归Responsibility DecisionRecord，不在本域建决定表。

- Fact Owner：`ConflictReviewRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, conflict_finding_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `conflict_finding_id` | `uuid` | 否 | `—` | ConflictFinding事实标识：由应用生成的UUIDv7。 |
| `conflict_review_id` | `uuid` | 否 | `—` | 所属ConflictReview标识：Finding必须归属同租户已冻结审查。 |
| `finding_no` | `bigint` | 否 | `—` | 命中序号：从一开始在ConflictReview内追加，写入后不可变。 |
| `conflict_review_party_id` | `uuid` | 否 | `—` | 命中的范围Party标识：指向同租户ConflictReviewParty；必须属于本Review并由提交前复验。 |
| `rule_code` | `varchar(64)` | 否 | `—` | 命中规则代码：本条Finding实际采用的确定性规则。 |
| `rule_revision` | `bigint` | 否 | `—` | 命中规则修订号：冻结该规则的准确版本，不得为负数。 |
| `risk_classification_code` | `varchar(64)` | 否 | `—` | 风险分类：由冻结规则确定，静态authoritySlot集合由代码注册表按本分类解析。 |
| `finding_summary` | `text` | 否 | `—` | 命中摘要：仅保存必要的非敏感说明，不得保存语料正文、Secret或Token。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | EvidenceRef：为空表示匹配事实本身足以追溯；非空必须物理关联同租户EvidenceSubmission。 |
| `finding_digest` | `bytea` | 否 | `—` | Finding摘要：覆盖Review、范围Party、规则、风险分类、匹配对象和EvidenceRef。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：Finding首次持久化的时间，永久冻结。 |
| `matched_fact_type` | `varchar(64)` | 否 | `—` | 产生本条ConflictFinding的多态准确匹配事实的静态注册类型。 |
| `matched_fact_id` | `uuid` | 否 | `—` | 产生本条ConflictFinding的多态准确匹配事实在所属租户内的准确标识。 |
| `matched_fact_revision` | `bigint` | 是 | `—` | 产生本条ConflictFinding的多态准确匹配事实的准确修订号；按哈希冻结时为空。 |
| `matched_fact_hash` | `bytea` | 是 | `—` | 产生本条ConflictFinding的多态准确匹配事实的准确规范摘要；按修订冻结时为空。 |
| `source_fact_type` | `varchar(64)` | 是 | `—` | 支持本条ConflictFinding的多态准确来源事实的静态注册类型。 |
| `source_fact_id` | `uuid` | 是 | `—` | 支持本条ConflictFinding的多态准确来源事实在所属租户内的准确标识。 |
| `source_fact_revision` | `bigint` | 是 | `—` | 支持本条ConflictFinding的多态准确来源事实的准确修订号；按哈希冻结时为空。 |
| `source_fact_hash` | `bytea` | 是 | `—` | 支持本条ConflictFinding的多态准确来源事实的准确规范摘要；按修订冻结时为空。 |

约束：

- `ck_conflict_finding__finding_no_positive`（`CHECK`：`finding_no > 0`）：命中序号范围：ConflictReview内Finding序号必须为正整数。
- `ck_conflict_finding__rule_revision`（`CHECK`：`rule_revision >= 0`）：命中规则修订号不得为负数。
- `uq_conflict_finding__review_no`（`UNIQUE`：`tenant_id, conflict_review_id, finding_no`）：追加幂等：同一ConflictReview的Finding序号不得重复。
- `ck_conflict_finding__matched_fact_exact`（`CHECK`：`(matched_fact_type IS NOT NULL AND matched_fact_id IS NOT NULL AND ((matched_fact_revision IS NOT NULL AND matched_fact_revision >= 0 AND matched_fact_hash IS NULL) OR (matched_fact_revision IS NULL AND matched_fact_hash IS NOT NULL)))`）：准确引用：产生本条ConflictFinding的多态准确匹配事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_conflict_finding__source_fact_exact`（`CHECK`：`((source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL))) OR (source_fact_type IS NULL AND source_fact_id IS NULL AND source_fact_revision IS NULL AND source_fact_hash IS NULL))`）：准确引用：支持本条ConflictFinding的多态准确来源事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_conflict_finding__finding_digest_length`（`CHECK`：`octet_length(finding_digest) = 32`）：摘要格式：finding_digest必须保存32字节的规范二进制值。
- `ck_conflict_finding__matched_fact_hash_length`（`CHECK`：`octet_length(matched_fact_hash) = 32`）：摘要格式：matched_fact_hash必须保存32字节的规范二进制值。
- `ck_conflict_finding__source_fact_hash_length`（`CHECK`：`octet_length(source_fact_hash) = 32`）：摘要格式：source_fact_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_conflict_finding__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_conflict_finding__conflict_review`：`(tenant_id, conflict_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。Review关系：Finding必须属于同租户ConflictReview。
- `fk_conflict_finding__review_party`：`(tenant_id, conflict_review_party_id) → conflict.conflict_review_party(tenant_id, conflict_review_party_id)`。范围Party关系：Finding必须命中同租户ConflictReviewParty。
- `fk_conflict_finding__evidence_submission`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。

类型化准确引用：

- `matched_fact`：产生本条ConflictFinding的多态准确匹配事实；由静态允许列表、同租户Resolver和提交前复验保证。
- `source_fact`：支持本条ConflictFinding的多态准确来源事实；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_conflict_finding__risk`：列`(tenant_id, conflict_review_id, risk_classification_code)`；唯一=`否`；谓词=`None`。任务生成：按Review和风险分类解析静态authoritySlot并创建逐Finding责任卡。
- `ix_conflict_finding__review_party`：列`(tenant_id, conflict_review_id, conflict_review_party_id)`；唯一=`否`；谓词=`None`。命中查询索引：支持按Review和范围Party读取全部不可变Finding。

## `contract`

合同事实域：保存版本化合同包、准确签署、执行、付款、激活和终止事实。

- Fact Owner：`ContractRuntime`

### `contract.contract`

合同锚点：一行对应一份由准确商机和接受报价形成的合同，只保存当前版本与当前批准指针，以及执行、激活、终止的单向槽位，不代表合同已经生效。

- Fact Owner：`ContractRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, contract_id)`
- 允许更新字段：`current_revision_id, approved_revision_id, contract_execution_id, deal_activated_at, activation_source_type, activation_source_id, activation_source_revision, activation_source_hash, contract_termination_id, revision, changed_at`
- Write-once字段：`contract_execution_id, deal_activated_at, activation_source_type, activation_source_id, activation_source_revision, activation_source_hash, contract_termination_id`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_id` | `uuid` | 否 | `—` | 合同锚点标识：由应用生成的UUIDv7。 |
| `opportunity_id` | `uuid` | 否 | `—` | 来源商机标识：合同所承接的唯一法律需求。 |
| `accepted_quote_response_id` | `uuid` | 是 | `—` | 接受报价回应标识：合同成立准备工作的准确销售来源。 |
| `current_revision_id` | `uuid` | 是 | `—` | 当前合同版本标识：可随新版本前移，但不改变旧版本。 |
| `approved_revision_id` | `uuid` | 是 | `—` | 当前已批准合同版本标识：只能等于当前版本；形成新版本时可原子清空或前移，旧批准历史保留在准确DecisionRecord中，执行后冻结。 |
| `contract_execution_id` | `uuid` | 是 | `—` | 合同执行事实标识：全部执行门禁通过后一次写入。 |
| `deal_activated_at` | `timestamptz(6)` | 是 | `—` | 交易激活时间：首款门禁或风险决定满足后一次写入。 |
| `contract_termination_id` | `uuid` | 是 | `—` | 合同取消或终止事实标识：形成终止事实后一次写入。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：合同锚点首次建立的可信服务端时间。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：最近一次受控槽位更新的可信服务端时间。 |
| `activation_source_type` | `varchar(64)` | 是 | `—` | 合同激活依据事实的静态注册类型。 |
| `activation_source_id` | `uuid` | 是 | `—` | 合同激活依据事实在所属租户内的准确标识。 |
| `activation_source_revision` | `bigint` | 是 | `—` | 合同激活依据事实的准确修订号；按哈希冻结时为空。 |
| `activation_source_hash` | `bytea` | 是 | `—` | 合同激活依据事实的准确规范摘要；按修订冻结时为空。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `created_by_appointment_id` | `uuid` | 是 | `—` | R2合同创建者；旧合同可空。 |
| `direct_preparation_decision_id` | `uuid` | 是 | `—` | 最初消费的直接准备授权；后续版本独立记录来源。 |
| `preparation_contract_code` | `varchar(64)` | 是 | `—` | 具名R2双入口锚点；旧合同为空。 |

约束：

- `uk_contract__accepted_quote_response`（`UNIQUE`：`tenant_id, accepted_quote_response_id`）：来源唯一：一条接受报价回应只能形成一份合同。
- `uk_contract__id_opportunity_execution`（`UNIQUE`：`tenant_id, contract_id, opportunity_id, contract_execution_id`）：准确转案来源候选键：供TransferRequest证明Opportunity、Contract及Execution属于同一合同链。
- `ck_contract__activation_complete`（`CHECK`：`(deal_activated_at IS NULL AND activation_source_type IS NULL) OR (deal_activated_at IS NOT NULL AND activation_source_type IS NOT NULL)`）：激活完整性：激活时间与准确激活依据必须同时存在或同时为空。
- `ck_contract__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_contract__activation_source_exact`（`CHECK`：`((activation_source_type IS NOT NULL AND activation_source_id IS NOT NULL AND ((activation_source_revision IS NOT NULL AND activation_source_revision >= 0 AND activation_source_hash IS NULL) OR (activation_source_revision IS NULL AND activation_source_hash IS NOT NULL))) OR (activation_source_type IS NULL AND activation_source_id IS NULL AND activation_source_revision IS NULL AND activation_source_hash IS NULL))`）：准确引用：合同激活依据事实必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_contract__activation_source_hash_length`（`CHECK`：`octet_length(activation_source_hash) = 32`）：摘要格式：activation_source_hash必须保存32字节的规范二进制值。
- `uq_contract__direct_source`（`UNIQUE`：`tenant_id, direct_preparation_decision_id`）：准确事实唯一。
- `ck_contract__r2_source`（`CHECK`：`(preparation_contract_code IS NULL AND accepted_quote_response_id IS NOT NULL AND direct_preparation_decision_id IS NULL) OR (preparation_contract_code IS NOT NULL AND preparation_contract_code='R2_CONTRACT_PREPARATION_V1' AND created_by_appointment_id IS NOT NULL AND num_nonnulls(accepted_quote_response_id,direct_preparation_decision_id)=1)`）：具名双入口；旧结构不放宽。

物理外键：

- `fk_contract__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract__opportunity`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。来源完整性：合同必须属于同租户准确商机。
- `fk_contract__accepted_quote_response`：`(tenant_id, accepted_quote_response_id) → opportunity.quote_response(tenant_id, quote_response_id)`。来源完整性：合同必须引用同租户准确接受回应。
- `fk_contract__current_revision`：`(tenant_id, current_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。当前版本槽：必须指向本合同的准确版本，归属关系由延迟守卫复验。
- `fk_contract__approved_revision`：`(tenant_id, approved_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。批准槽：必须指向同租户准确合同版本。
- `fk_contract__execution`：`(tenant_id, contract_execution_id) → contract.contract_execution(tenant_id, contract_execution_id)`。执行槽：必须指向同租户唯一合同执行事实。
- `fk_contract__termination`：`(tenant_id, contract_termination_id) → contract.contract_termination(tenant_id, contract_termination_id)`。终止槽：必须指向同租户准确终止事实。
- `fk_contract__direct_preparation_decision_id`：`(tenant_id, direct_preparation_decision_id) → contract.preparation_decision(tenant_id, preparation_decision_id)`。同租户准确事实引用。
- `fk_contract__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

类型化准确引用：

- `activation_source`：合同激活依据事实；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_contract__opportunity`：列`(tenant_id, opportunity_id)`；唯一=`否`；谓词=`None`。来源查询：按商机定位其合同。

### `contract.contract_revision`

合同版本：一行连同其参与方、费用、付款门禁和签署计划构成一个不可变版本包，旧批准、签名和正文不得复用。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_revision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：由应用生成的UUIDv7。 |
| `contract_id` | `uuid` | 否 | `—` | 合同标识：该版本所属的合同锚点。 |
| `revision_no` | `integer` | 否 | `—` | 版本序号：从一开始在同一合同内连续递增。 |
| `predecessor_revision_id` | `uuid` | 是 | `—` | 前序合同版本标识：首版本为空，其余版本准确引用直接前序。 |
| `confirmed_action_draft_id` | `uuid` | 是 | `—` | 确认草案标识：形成该版本包的准确候选输入。 |
| `source_quote_revision_id` | `uuid` | 是 | `—` | 来源报价版本标识：商业条件的准确来源。 |
| `source_quote_response_id` | `uuid` | 是 | `—` | 来源报价回应标识：客户接受的准确发出与回应链。 |
| `body_evidence_submission_id` | `uuid` | 是 | `—` | 合同正文证据提交标识：准确指向不可变正文对象。 |
| `body_sha256` | `bytea` | 否 | `—` | 合同正文SHA-256：正文准确对象字节的32字节服务端摘要。 |
| `pre_contract_review_id` | `uuid` | 是 | `—` | 签约前冲突审查标识：该版本包冻结的独立PRE_CONTRACT审查。 |
| `pre_contract_scope_hash` | `bytea` | 是 | `—` | 签约前审查范围摘要：准确参与方、规则和语料范围的32字节摘要。 |
| `pre_contract_resolution_digest` | `bytea` | 是 | `—` | 签约前审查结论摘要：可用于本版本放行的准确结论摘要。 |
| `package_contract_code` | `varchar(64)` | 否 | `—` | 版本包合同代码：静态注册的合同结构类型。 |
| `package_contract_version` | `integer` | 否 | `—` | 版本包合同版本：解释全部子项结构的正整数版本。 |
| `content_digest` | `bytea` | 否 | `—` | 版本包内容摘要：覆盖正文对象版本、全部子项和签约前审查快照。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 创建任职标识：确认并提交该版本包的准确任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：版本包在同一短事务封存的可信时间。 |
| `preparation_draft_id` | `uuid` | 是 | `—` | 真实准备草稿。 |
| `source_direct_decision_id` | `uuid` | 是 | `—` | 本版准确直接授权。 |
| `customer_confirmation_id` | `uuid` | 是 | `—` | 准确客户确认。 |
| `commercial_digest` | `bytea` | 是 | `—` | 准确商业摘要。 |
| `body_evidence_version_id` | `uuid` | 是 | `—` | 准确T06正文版本。 |
| `template_version_id` | `uuid` | 是 | `—` | 已审核真实模板版本。 |
| `party_snapshot_digest` | `bytea` | 是 | `—` | 准确参与方快照。 |
| `package_ciphertext` | `bytea` | 是 | `—` | 本版完整规范包受保护密文。 |
| `receipt_required_before_transfer` | `boolean` | 是 | `—` | 仅明确付款约定阻断转案。 |
| `required_amount_minor` | `bigint` | 是 | `—` | 明确先到账金额。 |
| `approval_requirement_count` | `integer` | 是 | `—` | 在版本形成事务冻结的必要审批数量。 |
| `clause_count` | `integer` | 是 | `—` | 本版条款集合数量。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |

约束：

- `uk_contract_revision__contract_revision_no`（`UNIQUE`：`tenant_id, contract_id, revision_no`）：版本唯一：同一合同内版本序号不得重复。
- `uk_contract_revision__predecessor`（`UNIQUE`：`tenant_id, predecessor_revision_id`）：单后继链：一个合同版本最多只有一个直接后继。
- `uk_contract_revision__confirmed_draft`（`UNIQUE`：`tenant_id, confirmed_action_draft_id`）：草案唯一：一份确认草案只能形成一个合同版本包。
- `uk_contract_revision__id_contract`（`UNIQUE`：`tenant_id, contract_revision_id, contract_id`）：准确版本归属候选键：供执行、付款和终止事实证明版本属于同一Contract。
- `ck_contract_revision__revision_no`（`CHECK`：`revision_no > 0`）：版本序号必须为正数。
- `ck_contract_revision__predecessor_shape`（`CHECK`：`(revision_no = 1 AND predecessor_revision_id IS NULL) OR (revision_no > 1 AND predecessor_revision_id IS NOT NULL)`）：版本链形态：首版本无前序，后续版本必须有直接前序。
- `ck_contract_revision__package_version`（`CHECK`：`package_contract_version > 0`）：版本包合同版本必须为正数。
- `ck_contract_revision__body_sha256_length`（`CHECK`：`octet_length(body_sha256) = 32`）：摘要格式：body_sha256必须保存32字节的规范二进制值。
- `ck_contract_revision__pre_contract_scope_hash_length`（`CHECK`：`octet_length(pre_contract_scope_hash) = 32`）：摘要格式：pre_contract_scope_hash必须保存32字节的规范二进制值。
- `ck_contract_revision__pre_contract_resolution_digest_length`（`CHECK`：`octet_length(pre_contract_resolution_digest) = 32`）：摘要格式：pre_contract_resolution_digest必须保存32字节的规范二进制值。
- `ck_contract_revision__content_digest_length`（`CHECK`：`octet_length(content_digest) = 32`）：摘要格式：content_digest必须保存32字节的规范二进制值。
- `ck_contract_revision__commercial_digest_length`（`CHECK`：`octet_length(commercial_digest) = 32`）：摘要格式：commercial_digest必须保存32字节的规范二进制值。
- `ck_contract_revision__party_snapshot_digest_length`（`CHECK`：`octet_length(party_snapshot_digest) = 32`）：摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。
- `ck_contract_revision__r2_shape`（`CHECK`：`(package_contract_code='R2_CONTRACT_PREPARATION_V1' AND package_contract_version=1 AND preparation_draft_id IS NOT NULL AND customer_confirmation_id IS NOT NULL AND commercial_digest IS NOT NULL AND body_evidence_version_id IS NOT NULL AND template_version_id IS NOT NULL AND party_snapshot_digest IS NOT NULL AND package_ciphertext IS NOT NULL AND receipt_required_before_transfer IS NOT NULL AND approval_requirement_count IS NOT NULL AND clause_count IS NOT NULL AND pre_contract_review_id IS NULL AND pre_contract_scope_hash IS NULL AND pre_contract_resolution_digest IS NULL AND confirmed_action_draft_id IS NULL AND num_nonnulls(source_quote_response_id,source_direct_decision_id)=1 AND ((source_quote_response_id IS NOT NULL AND source_quote_revision_id IS NOT NULL) OR (source_direct_decision_id IS NOT NULL AND source_quote_revision_id IS NULL)) AND octet_length(package_ciphertext) BETWEEN 29 AND 1048576 AND approval_requirement_count BETWEEN 1 AND 100 AND clause_count BETWEEN 0 AND 200 AND ((receipt_required_before_transfer AND required_amount_minor IS NOT NULL AND required_amount_minor BETWEEN 1 AND 9007199254740991) OR (NOT receipt_required_before_transfer AND required_amount_minor IS NULL))) OR (package_contract_code<>'R2_CONTRACT_PREPARATION_V1' AND confirmed_action_draft_id IS NOT NULL AND source_quote_revision_id IS NOT NULL AND source_quote_response_id IS NOT NULL AND body_evidence_submission_id IS NOT NULL AND pre_contract_review_id IS NOT NULL AND pre_contract_scope_hash IS NOT NULL AND pre_contract_resolution_digest IS NOT NULL AND source_direct_decision_id IS NULL AND preparation_draft_id IS NULL AND customer_confirmation_id IS NULL AND commercial_digest IS NULL AND body_evidence_version_id IS NULL AND template_version_id IS NULL AND party_snapshot_digest IS NULL AND package_ciphertext IS NULL AND receipt_required_before_transfer IS NULL AND approval_requirement_count IS NULL AND clause_count IS NULL AND required_amount_minor IS NULL)`）：具名准备版本与完整旧版本分离；不伪造审查。
- `uq_contract_revision__preparation_draft_id`（`UNIQUE`：`tenant_id, preparation_draft_id`）：准确事实唯一。

物理外键：

- `fk_contract_revision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_revision__contract`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。归属完整性：合同版本必须属于同租户合同。
- `fk_contract_revision__predecessor`：`(tenant_id, predecessor_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本链：后续版本准确引用同租户直接前序。
- `fk_contract_revision__action_draft`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。输入来源：版本包必须引用准确确认草案。
- `fk_contract_revision__quote_revision`：`(tenant_id, source_quote_revision_id) → opportunity.quote_revision(tenant_id, quote_revision_id)`。报价来源：版本包必须引用准确报价版本。
- `fk_contract_revision__quote_response`：`(tenant_id, source_quote_response_id) → opportunity.quote_response(tenant_id, quote_response_id)`。接受来源：版本包必须引用准确报价回应。
- `fk_contract_revision__body_evidence`：`(tenant_id, body_evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。正文来源：版本包必须引用准确EvidenceSubmission。
- `fk_contract_revision__pre_contract_review`：`(tenant_id, pre_contract_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。审查来源：版本包必须引用独立PRE_CONTRACT审查。
- `fk_contract_revision__creator`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。创建主体：版本包必须记录同租户准确任职。
- `fk_contract_revision__preparation_draft_id`：`(tenant_id, preparation_draft_id) → contract.preparation_draft(tenant_id, preparation_draft_id)`。同租户准确事实引用。
- `fk_contract_revision__source_direct_decision_id`：`(tenant_id, source_direct_decision_id) → contract.preparation_decision(tenant_id, preparation_decision_id)`。同租户准确事实引用。
- `fk_contract_revision__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_contract_revision__body_evidence_version_id`：`(tenant_id, body_evidence_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_contract_revision__template_version_id`：`(tenant_id, template_version_id) → contract.template_version(tenant_id, template_version_id)`。同租户准确事实引用。

### `contract.contract_participation`

合同参与方：一行冻结一个合同版本中的一项主体角色和准确Party修订，不表示该主体已经签署。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_participation_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_participation_id` | `uuid` | 否 | `—` | 合同参与方标识：由应用生成的UUIDv7。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：该参与方所属的不可变版本包。 |
| `participation_no` | `integer` | 否 | `—` | 参与项序号：在合同版本内稳定排序。 |
| `party_id` | `uuid` | 否 | `—` | 主体标识：参与合同的当前态Party锚点。 |
| `party_revision` | `bigint` | 否 | `—` | Party CAS修订号：形成版本包时用于提交前重验，不声称可从当前态Party回读历史版本。 |
| `party_snapshot_digest` | `bytea` | 否 | `—` | 合同主体快照摘要：冻结本版本所需的最小规范名称、主标识选择及角色上下文。 |
| `context_role_code` | `varchar(64)` | 否 | `—` | 上下文角色：静态注册的委托人、对方、签署人等角色。 |
| `source_opportunity_participation_id` | `uuid` | 是 | `—` | 来源商机参与项标识：可追溯到销售阶段的准确参与事实。 |
| `signature_required` | `boolean` | 否 | `—` | 签署要求：该参与方是否必须拥有至少一个签署计划槽。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随合同版本包封存的可信时间。 |

约束：

- `uk_contract_participation__revision_no`（`UNIQUE`：`tenant_id, contract_revision_id, participation_no`）：参与项唯一：同一合同版本内序号不得重复。
- `uk_contract_participation__revision_party_role`（`UNIQUE`：`tenant_id, contract_revision_id, party_id, context_role_code`）：角色唯一：同一版本内同一主体的同一上下文角色不得重复。
- `uk_contract_participation__id_revision_party`（`UNIQUE`：`tenant_id, contract_participation_id, contract_revision_id, party_id`）：准确签署参与候选键：供SignaturePlan证明参与项、版本和签署Party一致。
- `ck_contract_participation__no_positive`（`CHECK`：`participation_no > 0`）：参与项序号必须为正数。
- `ck_contract_participation__party_revision`（`CHECK`：`party_revision >= 0`）：冻结的Party修订号不得为负数。
- `ck_contract_participation__party_snapshot_digest_length`（`CHECK`：`octet_length(party_snapshot_digest) = 32`）：摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_contract_participation__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_participation__contract_revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：参与项必须属于准确合同版本。
- `fk_contract_participation__party`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。主体完整性：参与项必须引用同租户Party。
- `fk_contract_participation__source_opportunity_participation`：`(tenant_id, source_opportunity_participation_id) → opportunity.opportunity_participation(tenant_id, opportunity_participation_id)`。销售来源：可选引用准确商机参与项。

### `contract.contract_fee_term`

合同费用条款：一行保存合同版本中的一项不可变费用约定，不代表付款已经发生。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_fee_term_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_fee_term_id` | `uuid` | 否 | `—` | 合同费用条款标识：由应用生成的UUIDv7。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：费用条款所属版本包。 |
| `term_no` | `integer` | 否 | `—` | 条款序号：在合同版本内稳定排序。 |
| `fee_type_code` | `varchar(64)` | 否 | `—` | 费用类型：静态注册的固定费、计时费、风险代理等类型。 |
| `amount_minor` | `bigint` | 否 | `—` | 约定金额：以currency_code最小货币单位表示的非负金额。 |
| `currency_code` | `varchar(3)` | 否 | `—` | 币种代码：三位大写ISO 4217代码。 |
| `calculation_contract_code` | `varchar(64)` | 否 | `—` | 计费合同代码：解释费用计算方式的静态代码。 |
| `calculation_contract_version` | `integer` | 否 | `—` | 计费合同版本：解释计算参数的正整数版本。 |
| `source_quote_line_id` | `uuid` | 是 | `—` | 来源报价行标识：费用来源于报价时准确引用。 |
| `term_digest` | `bytea` | 否 | `—` | 条款摘要：规范化费用条款的32字节摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随合同版本包封存的可信时间。 |

约束：

- `uk_contract_fee_term__revision_no`（`UNIQUE`：`tenant_id, contract_revision_id, term_no`）：条款唯一：同一合同版本内条款序号不得重复。
- `ck_contract_fee_term__no_positive`（`CHECK`：`term_no > 0`）：条款序号必须为正数。
- `ck_contract_fee_term__amount_nonnegative`（`CHECK`：`amount_minor >= 0`）：费用金额不得为负数。
- `ck_contract_fee_term__currency`（`CHECK`：`currency_code ~ '^[A-Z]{3}$'`）：币种必须为三位大写代码。
- `ck_contract_fee_term__contract_version`（`CHECK`：`calculation_contract_version > 0`）：计费合同版本必须为正数。
- `ck_contract_fee_term__term_digest_length`（`CHECK`：`octet_length(term_digest) = 32`）：摘要格式：term_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_contract_fee_term__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_fee_term__contract_revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：费用条款必须属于准确合同版本。
- `fk_contract_fee_term__source_quote_line`：`(tenant_id, source_quote_line_id) → opportunity.quote_line(tenant_id, quote_line_id)`。报价来源：可选引用准确报价行。

### `contract.payment_gate`

付款门禁：一行冻结合同版本的首款或风险激活条件，并只允许从未满足单向写入准确满足事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, payment_gate_id)`
- 允许更新字段：`gate_state, satisfied_at, satisfaction_digest, payment_confirmation_ids, confirmation_set_digest, risk_decision_record_id, revision, changed_at`
- Write-once字段：`satisfied_at, satisfaction_digest, payment_confirmation_ids, confirmation_set_digest, risk_decision_record_id`
- 状态字段与初态：`gate_state = PENDING`
- 允许状态转换：`PENDING → SATISFIED`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `payment_gate_id` | `uuid` | 否 | `—` | 付款门禁标识：由应用生成的UUIDv7。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：付款门禁所属的不可变版本包。 |
| `gate_kind` | `varchar(64)` | 否 | `—` | 门禁类型：FIRST_PAYMENT表示首款，RISK_DECISION表示专门风险决定。 |
| `required_amount_minor` | `bigint` | 是 | `—` | 首款要求金额：最小货币单位；风险决定门禁为空。 |
| `currency_code` | `varchar(3)` | 是 | `—` | 首款币种：三位大写代码；风险决定门禁为空。 |
| `gate_state` | `varchar(64)` | 否 | `—` | 门禁状态：PENDING或SATISFIED，只允许单向满足。 |
| `satisfied_at` | `timestamptz(6)` | 是 | `—` | 满足时间：准确Confirmation集合或风险决定通过后一次写入。 |
| `satisfaction_digest` | `bytea` | 是 | `—` | 满足摘要：覆盖门禁条件及其准确满足依据。 |
| `payment_confirmation_ids` | `uuid[]` | 是 | `—` | 到账确认集合：FIRST_PAYMENT满足时按UUID字节升序去重冻结的准确PaymentConfirmation标识；其他状态或门禁类型为空。 |
| `confirmation_set_digest` | `bytea` | 是 | `—` | 到账确认集合摘要：FIRST_PAYMENT满足时准确冻结。 |
| `risk_decision_record_id` | `uuid` | 是 | `—` | 风险激活决定标识：RISK_DECISION满足时准确引用。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随合同版本包封存的可信时间。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：门禁最近一次受控更新的可信时间。 |

约束：

- `uk_payment_gate__contract_revision`（`UNIQUE`：`tenant_id, contract_revision_id`）：版本唯一：一个合同版本最多具有一个付款门禁。
- `ck_payment_gate__gate_kind`（`CHECK`：`gate_kind IN ('FIRST_PAYMENT', 'RISK_DECISION')`）：门禁类型仅允许首款或专门风险决定。
- `ck_payment_gate__gate_state`（`CHECK`：`gate_state IN ('PENDING', 'SATISFIED')`）：门禁状态仅允许未满足或已满足。
- `ck_payment_gate__kind_payload`（`CHECK`：`(gate_kind = 'FIRST_PAYMENT' AND required_amount_minor IS NOT NULL AND required_amount_minor > 0 AND currency_code ~ '^[A-Z]{3}$') OR (gate_kind = 'RISK_DECISION' AND required_amount_minor IS NULL AND currency_code IS NULL)`）：门禁载荷：首款门禁必须有正金额和币种，风险决定门禁不得伪造零元付款。
- `ck_payment_gate__satisfaction`（`CHECK`：`(gate_state = 'PENDING' AND satisfied_at IS NULL AND satisfaction_digest IS NULL AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NULL) OR (gate_state = 'SATISFIED' AND satisfied_at IS NOT NULL AND satisfaction_digest IS NOT NULL AND ((gate_kind = 'FIRST_PAYMENT' AND payment_confirmation_ids IS NOT NULL AND cardinality(payment_confirmation_ids) > 0 AND confirmation_set_digest IS NOT NULL AND risk_decision_record_id IS NULL) OR (gate_kind = 'RISK_DECISION' AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NOT NULL)))`）：满足完整性：首款门禁冻结非空准确Confirmation集合和摘要，风险门禁只引用专门DecisionRecord。
- `ck_payment_gate__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_payment_gate__satisfaction_digest_length`（`CHECK`：`octet_length(satisfaction_digest) = 32`）：摘要格式：satisfaction_digest必须保存32字节的规范二进制值。
- `ck_payment_gate__confirmation_set_digest_length`（`CHECK`：`octet_length(confirmation_set_digest) = 32`）：摘要格式：confirmation_set_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_payment_gate__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_payment_gate__contract_revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：门禁必须属于准确合同版本。
- `fk_payment_gate__risk_decision`：`(tenant_id, risk_decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。风险依据：风险门禁满足时必须引用准确决定。

### `contract.signature_plan`

签署计划槽：一行冻结合同版本中的一个必需或可选签署槽，不代表已经签署。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_plan_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_plan_id` | `uuid` | 否 | `—` | 签署计划槽标识：由应用生成的UUIDv7。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：签署槽所属的准确版本包。 |
| `slot_no` | `integer` | 否 | `—` | 签署槽序号：在合同版本内稳定排序。 |
| `authority_slot_code` | `varchar(64)` | 否 | `—` | 签署授权槽代码：静态注册的签署能力要求。 |
| `contract_participation_id` | `uuid` | 是 | `—` | 合同参与项标识：计划签署人对应的准确参与事实。 |
| `signer_party_id` | `uuid` | 否 | `—` | 签署主体标识：必须与合同参与项中的Party一致。 |
| `signature_method_code` | `varchar(64)` | 否 | `—` | 签署方式：静态注册的电子、线下等方式。 |
| `seal_required` | `boolean` | 否 | `—` | 印章要求：该槽是否必须核验准确印章事实。 |
| `required` | `boolean` | 否 | `—` | 必需标志：执行前该槽是否必须具有有效签署事实。 |
| `plan_digest` | `bytea` | 否 | `—` | 计划摘要：该签署槽规范内容的32字节摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：随合同版本包封存的可信时间。 |
| `arrangement_id` | `uuid` | 是 | `—` | 具名人工签署安排；历史计划为空。 |
| `signature_required` | `boolean` | 否 | `true` | 本槽是否必须签字。 |
| `template_signing_party_id` | `uuid` | 是 | `—` | 审核模板冻结的律所绑定；与合同参与项互斥。 |

约束：

- `uk_signature_plan__id_revision_signer`（`UNIQUE`：`tenant_id, signature_plan_id, contract_revision_id, signer_party_id`）：准确签署计划候选键：供ContractSignature证明Plan、版本和签署Party一致。
- `ck_signature_plan__slot_no_positive`（`CHECK`：`slot_no > 0`）：签署槽序号必须为正数。
- `ck_signature_plan__plan_digest_length`（`CHECK`：`octet_length(plan_digest) = 32`）：摘要格式：plan_digest必须保存32字节的规范二进制值。
- `ck_signature_plan__manual`（`CHECK`：`(arrangement_id IS NULL AND contract_participation_id IS NOT NULL AND template_signing_party_id IS NULL) OR (arrangement_id IS NOT NULL AND signature_method_code='MANUAL' AND num_nonnulls(contract_participation_id,template_signing_party_id)=1 AND (signature_required OR seal_required))`）：历史参与引用不放宽；人工安排独立登记。

物理外键：

- `fk_signature_plan__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_plan__contract_revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：签署槽必须属于准确合同版本。
- `fk_signature_plan__participation`：`(tenant_id, contract_participation_id) → contract.contract_participation(tenant_id, contract_participation_id)`。参与方归属：签署槽必须引用准确合同参与项。
- `fk_signature_plan__signer_party`：`(tenant_id, signer_party_id) → party.party(tenant_id, party_id)`。签署主体：签署槽必须引用同租户Party。
- `fk_signature_plan__participation_path`：`(tenant_id, contract_participation_id, contract_revision_id, signer_party_id) → contract.contract_participation(tenant_id, contract_participation_id, contract_revision_id, party_id)`。签署计划路径：参与项必须属于同一合同版本且其Party就是计划签署主体。
- `fk_signature_plan__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_plan__template_signing_party_id`：`(tenant_id, template_signing_party_id) → contract.template_signing_party(tenant_id, template_signing_party_id)`。同租户准确事实引用。

索引：

- `uk_signature_plan__revision_slot_no`：列`(tenant_id, contract_revision_id, slot_no)`；唯一=`是`；谓词=`arrangement_id IS NULL`。历史计划槽唯一。
- `uk_signature_plan__revision_authority_slot`：列`(tenant_id, contract_revision_id, authority_slot_code)`；唯一=`是`；谓词=`arrangement_id IS NULL`。历史计划槽唯一。
- `uq_signature_plan__arrangement_slot`：列`(tenant_id, arrangement_id, slot_no)`；唯一=`是`；谓词=`arrangement_id IS NOT NULL`。同安排槽唯一。
- `uq_signature_plan__arrangement_authority`：列`(tenant_id, arrangement_id, authority_slot_code)`；唯一=`是`；谓词=`arrangement_id IS NOT NULL`。同安排权限唯一。
- `uq_signature_plan__arrangement_party`：列`(tenant_id, arrangement_id, signer_party_id)`；唯一=`是`；谓词=`arrangement_id IS NOT NULL`。同安排主体唯一。

### `contract.contract_signature`

合同签署事实：一行表示一个计划槽上经证据、身份授权、签署内容和可信外部结果核验通过的签署；外部发送或回调本身不构成本事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, contract_signature_id)`
- 允许更新字段：`revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision, changed_at`
- Write-once字段：`revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_signature_id` | `uuid` | 否 | `—` | 合同签署事实标识：由应用生成的UUIDv7。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：签署内容对应的准确版本。 |
| `signature_plan_id` | `uuid` | 否 | `—` | 签署计划标识：本签署满足的准确计划槽。 |
| `signature_no` | `integer` | 否 | `—` | 签署序号：同一计划槽重新签署时递增。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | 签署证据提交标识：经核验的准确EvidenceSubmission。 |
| `external_action_id` | `uuid` | 是 | `—` | 外部签署动作标识：使用外部签署服务时准确引用。 |
| `provider_inbox_id` | `uuid` | 是 | `—` | Provider消息标识：可信外部结果来自回调时准确引用。 |
| `signer_party_id` | `uuid` | 否 | `—` | 实际签署Party标识：必须符合计划槽和版本参与方。 |
| `signer_identity_digest` | `bytea` | 否 | `—` | 签署身份摘要：冻结核验通过的身份材料与方法。 |
| `signer_authority_digest` | `bytea` | 否 | `—` | 签署授权摘要：冻结签署时有效的授权路径。 |
| `signed_content_digest` | `bytea` | 否 | `—` | 签署内容摘要：必须与合同版本内容摘要准确匹配。 |
| `verification_method_code` | `varchar(64)` | 否 | `—` | 核验方法：静态注册的签署真实性验证方法。 |
| `signed_at` | `timestamptz(6)` | 否 | `—` | 签署时间：可信签署结果中的业务发生时间。 |
| `verified_at` | `timestamptz(6)` | 否 | `—` | 核验时间：服务端完成全部签署门禁的时间。 |
| `revoked_at` | `timestamptz(6)` | 是 | `—` | 撤回时间：仅合同执行前可由授权命令一次写入。 |
| `revoked_by_appointment_id` | `uuid` | 是 | `—` | 撤回任职标识：执行撤回命令的准确任职。 |
| `revocation_authorization_digest` | `bytea` | 是 | `—` | 撤回授权摘要：冻结执行前撤回命令提交时的单路径四轴授权快照。 |
| `revocation_reason_code` | `varchar(64)` | 是 | `—` | 撤回原因：允许列表化原因代码。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：签署事实经核验后追加的可信时间。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：签署撤回槽最近一次受控写入时间。 |
| `verification_id` | `uuid` | 是 | `—` | 人工证据核验通过事实；历史签署为空。 |

约束：

- `uk_contract_signature__plan_no`（`UNIQUE`：`tenant_id, signature_plan_id, signature_no`）：签署唯一：同一计划槽内签署序号不得重复。
- `ck_contract_signature__signature_no`（`CHECK`：`signature_no > 0`）：签署序号必须为正数。
- `ck_contract_signature__provider_pair`（`CHECK`：`(external_action_id IS NULL AND provider_inbox_id IS NULL) OR external_action_id IS NOT NULL`）：外部证明：Provider消息存在时必须同时能定位准确外部动作。
- `ck_contract_signature__revocation_complete`（`CHECK`：`(revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL)`）：撤回完整性：撤回时间、主体、授权摘要和原因必须一次性完整写入。
- `ck_contract_signature__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_contract_signature__signer_identity_digest_length`（`CHECK`：`octet_length(signer_identity_digest) = 32`）：摘要格式：signer_identity_digest必须保存32字节的规范二进制值。
- `ck_contract_signature__signer_authority_digest_length`（`CHECK`：`octet_length(signer_authority_digest) = 32`）：摘要格式：signer_authority_digest必须保存32字节的规范二进制值。
- `ck_contract_signature__signed_content_digest_length`（`CHECK`：`octet_length(signed_content_digest) = 32`）：摘要格式：signed_content_digest必须保存32字节的规范二进制值。
- `ck_contract_signature__revocation_authorization_digest_length`（`CHECK`：`octet_length(revocation_authorization_digest) = 32`）：摘要格式：revocation_authorization_digest必须保存32字节的规范二进制值。
- `uq_contract_signature__verification_id`（`UNIQUE`：`tenant_id, verification_id`）：准确事实唯一。
- `ck_contract_signature__manual`（`CHECK`：`(verification_id IS NULL AND evidence_submission_id IS NOT NULL) OR (verification_id IS NOT NULL AND evidence_submission_id IS NULL AND external_action_id IS NULL AND provider_inbox_id IS NULL AND verification_method_code='MANUAL')`）：人工核验与旧外部证据互斥。

物理外键：

- `fk_contract_signature__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_signature__contract_revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：签署必须对应准确合同版本。
- `fk_contract_signature__plan`：`(tenant_id, signature_plan_id) → contract.signature_plan(tenant_id, signature_plan_id)`。计划归属：签署必须对应准确签署槽。
- `fk_contract_signature__evidence`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据来源：签署必须引用准确EvidenceSubmission。
- `fk_contract_signature__external_action`：`(tenant_id, external_action_id) → external_action.external_action(tenant_id, external_action_id)`。外部动作：可选引用准确外部签署尝试。
- `fk_contract_signature__provider_inbox`：`(tenant_id, provider_inbox_id) → external_action.provider_inbox(tenant_id, provider_inbox_id)`。Provider证明：可选引用准确可信入站消息。
- `fk_contract_signature__signer_party`：`(tenant_id, signer_party_id) → party.party(tenant_id, party_id)`。签署主体：实际签署人必须是同租户Party。
- `fk_contract_signature__revoker`：`(tenant_id, revoked_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。撤回主体：撤回必须记录准确任职。
- `fk_contract_signature__plan_path`：`(tenant_id, signature_plan_id, contract_revision_id, signer_party_id) → contract.signature_plan(tenant_id, signature_plan_id, contract_revision_id, signer_party_id)`。签署路径：签署事实的Plan、版本和实际Party必须完全一致。
- `fk_contract_signature__verification_id`：`(tenant_id, verification_id) → contract.signature_verification(tenant_id, signature_verification_id)`。同租户准确事实引用。

索引：

- `ux_contract_signature__active_plan`：列`(tenant_id, signature_plan_id)`；唯一=`是`；谓词=`revoked_at IS NULL`。有效签署唯一：一个计划槽同时最多有一条未撤回签署。

### `contract.contract_execution`

合同执行事实：一行表示准确合同版本的审批、审查、签署、印章和归档条件全部经提交前复验通过，不代表首款到账。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, contract_execution_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_execution_id` | `uuid` | 否 | `—` | 合同执行事实标识：由应用生成的UUIDv7。 |
| `contract_id` | `uuid` | 否 | `—` | 合同标识：被执行的合同锚点。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：实际执行的唯一准确版本。 |
| `approval_set_digest` | `bytea` | 否 | `—` | 审批集合摘要：覆盖全部静态授权槽的准确DecisionRecord。 |
| `signature_set_digest` | `bytea` | 否 | `—` | 签署集合摘要：覆盖全部必要且未撤回的签署事实。 |
| `review_scope_hash` | `bytea` | 否 | `—` | 审查范围摘要：执行时复验的PRE_CONTRACT准确范围。 |
| `review_resolution_digest` | `bytea` | 否 | `—` | 审查结论摘要：执行时仍可用于放行的准确结论。 |
| `archive_evidence_submission_id` | `uuid` | 是 | `—` | 归档证据提交标识：执行版本的准确归档文件。 |
| `execution_digest` | `bytea` | 否 | `—` | 执行摘要：覆盖合同版本及全部执行门禁结果。 |
| `executed_by_appointment_id` | `uuid` | 否 | `—` | 执行任职标识：实施执行命令的准确任职。 |
| `executed_at` | `timestamptz(6)` | 否 | `—` | 执行时间：唯一合同执行事实提交的可信时间。 |
| `execution_verification_id` | `uuid` | 是 | `—` | R2准确归档后的人工执行条件核验；与旧归档证据互斥。 |

约束：

- `uk_contract_execution__contract`（`UNIQUE`：`tenant_id, contract_id`）：合同唯一：一份合同最多形成一个执行事实。
- `uk_contract_execution__revision`（`UNIQUE`：`tenant_id, contract_revision_id`）：版本唯一：一个合同版本最多形成一个执行事实。
- `uk_contract_execution__id_contract_revision`（`UNIQUE`：`tenant_id, contract_execution_id, contract_id, contract_revision_id`）：准确执行候选键：供终止及转案证明Execution、Contract和Revision为同一事实链。
- `ck_contract_execution__approval_set_digest_length`（`CHECK`：`octet_length(approval_set_digest) = 32`）：摘要格式：approval_set_digest必须保存32字节的规范二进制值。
- `ck_contract_execution__signature_set_digest_length`（`CHECK`：`octet_length(signature_set_digest) = 32`）：摘要格式：signature_set_digest必须保存32字节的规范二进制值。
- `ck_contract_execution__review_scope_hash_length`（`CHECK`：`octet_length(review_scope_hash) = 32`）：摘要格式：review_scope_hash必须保存32字节的规范二进制值。
- `ck_contract_execution__review_resolution_digest_length`（`CHECK`：`octet_length(review_resolution_digest) = 32`）：摘要格式：review_resolution_digest必须保存32字节的规范二进制值。
- `ck_contract_execution__execution_digest_length`（`CHECK`：`octet_length(execution_digest) = 32`）：摘要格式：execution_digest必须保存32字节的规范二进制值。
- `ck_contract_execution__manual`（`CHECK`：`num_nonnulls(archive_evidence_submission_id,execution_verification_id)=1`）：保留完整旧执行依据；具名R2执行必须引用人工核验。

物理外键：

- `fk_contract_execution__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_execution__contract`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。合同归属：执行事实必须属于准确合同。
- `fk_contract_execution__revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：执行事实必须引用准确合同版本。
- `fk_contract_execution__revision_contract`：`(tenant_id, contract_revision_id, contract_id) → contract.contract_revision(tenant_id, contract_revision_id, contract_id)`。执行归属：被执行Revision必须属于同一Contract。
- `fk_contract_execution__archive_evidence`：`(tenant_id, archive_evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。归档证据：执行事实必须引用准确EvidenceSubmission。
- `fk_contract_execution__executor`：`(tenant_id, executed_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。执行主体：执行事实必须记录准确任职。
- `fk_contract_execution__execution_verification_id`：`(tenant_id, execution_verification_id) → contract.execution_verification(tenant_id, execution_verification_id)`。同租户准确事实引用。

### `contract.payment_confirmation`

付款确认事实：一行保存可信来源已确认且准确归属合同的一笔到账、撤销或退款，不代表付款门禁已经满足。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, payment_confirmation_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `payment_confirmation_id` | `uuid` | 否 | `—` | 付款确认事实标识：由应用生成的UUIDv7。 |
| `contract_id` | `uuid` | 否 | `—` | 合同标识：付款被准确归属的合同。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：确认付款时适用的准确合同版本。 |
| `confirmation_no` | `integer` | 否 | `—` | 确认序号：在合同内按追加顺序递增。 |
| `confirmation_type` | `varchar(64)` | 否 | `—` | 确认类型：RECEIPT、REVERSAL或REFUND。 |
| `amount_minor` | `bigint` | 否 | `—` | 确认金额：以currency_code最小货币单位表示的非负绝对金额。 |
| `currency_code` | `varchar(3)` | 否 | `—` | 币种代码：三位大写ISO 4217代码。 |
| `provider_account_code` | `varchar(64)` | 否 | `—` | 资金来源账号代码：静态配置的支付或银行账号。 |
| `provider_transaction_key_hmac` | `bytea` | 否 | `—` | Provider交易键HMAC：用于同账号内安全去重的32字节值，不保存原始交易凭据。 |
| `external_action_id` | `uuid` | 是 | `—` | 外部动作标识：本系统发起资金动作时可准确引用。 |
| `provider_inbox_id` | `uuid` | 是 | `—` | Provider消息标识：由可信入站通知形成确认时准确引用。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | 付款证据提交标识：用于确认归属的准确EvidenceSubmission。 |
| `reverses_payment_confirmation_id` | `uuid` | 是 | `—` | 被撤销或退款的原付款确认标识；普通到账为空。 |
| `attribution_digest` | `bytea` | 否 | `—` | 归属摘要：冻结付款与合同、版本及来源的准确匹配依据。 |
| `effective_at` | `timestamptz(6)` | 否 | `—` | 资金事实发生时间：可信来源确认的到账、撤销或退款时间。 |
| `confirmed_at` | `timestamptz(6)` | 否 | `—` | 确认时间：Fact Owner完成真实性和合同归属核验的时间。 |
| `recorded_by_appointment_id` | `uuid` | 否 | `—` | 记录任职标识：确认并写入付款事实的准确任职。 |

约束：

- `uk_payment_confirmation__contract_no`（`UNIQUE`：`tenant_id, contract_id, confirmation_no`）：确认唯一：同一合同内确认序号不得重复。
- `uk_payment_confirmation__provider_key`（`UNIQUE`：`tenant_id, provider_account_code, provider_transaction_key_hmac, confirmation_type`）：来源幂等：同Provider账号、交易键和事实类型不得重复确认。
- `ck_payment_confirmation__confirmation_type`（`CHECK`：`confirmation_type IN ('RECEIPT', 'REVERSAL', 'REFUND')`）：确认类型只允许到账、撤销或退款。
- `ck_payment_confirmation__no_positive`（`CHECK`：`confirmation_no > 0`）：确认序号必须为正数。
- `ck_payment_confirmation__amount_positive`（`CHECK`：`amount_minor > 0`）：确认金额使用正绝对值，方向由确认类型表达；零金额不得伪造资金事实。
- `ck_payment_confirmation__currency`（`CHECK`：`currency_code ~ '^[A-Z]{3}$'`）：币种必须为三位大写代码。
- `ck_payment_confirmation__reversal_source`（`CHECK`：`(confirmation_type = 'RECEIPT' AND reverses_payment_confirmation_id IS NULL) OR (confirmation_type IN ('REVERSAL', 'REFUND') AND reverses_payment_confirmation_id IS NOT NULL)`）：来源完整性：撤销和退款必须准确引用原确认，到账不得引用。
- `ck_payment_confirmation__trusted_source`（`CHECK`：`provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL`）：可信来源：付款确认必须至少引用验签ProviderInbox或经核验EvidenceSubmission；ExternalAction成功本身不足以证明到账。
- `uk_payment_confirmation__id_contract_revision`（`UNIQUE`：`tenant_id, payment_confirmation_id, contract_id, contract_revision_id`）：准确付款候选键：供付款集合Resolver证明Confirmation属于准确合同版本。
- `ck_payment_confirmation__provider_transaction_key_hmac_length`（`CHECK`：`octet_length(provider_transaction_key_hmac) = 32`）：摘要格式：provider_transaction_key_hmac必须保存32字节的规范二进制值。
- `ck_payment_confirmation__attribution_digest_length`（`CHECK`：`octet_length(attribution_digest) = 32`）：摘要格式：attribution_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_payment_confirmation__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_payment_confirmation__contract`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。合同归属：付款确认必须属于准确合同。
- `fk_payment_confirmation__revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：付款确认必须冻结准确合同版本。
- `fk_payment_confirmation__revision_contract`：`(tenant_id, contract_revision_id, contract_id) → contract.contract_revision(tenant_id, contract_revision_id, contract_id)`。付款归属：Confirmation引用的Revision必须属于同一Contract。
- `fk_payment_confirmation__external_action`：`(tenant_id, external_action_id) → external_action.external_action(tenant_id, external_action_id)`。外部动作：可选引用准确外部资金动作。
- `fk_payment_confirmation__provider_inbox`：`(tenant_id, provider_inbox_id) → external_action.provider_inbox(tenant_id, provider_inbox_id)`。Provider来源：可选引用准确可信消息。
- `fk_payment_confirmation__evidence`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据来源：可选引用准确EvidenceSubmission。
- `fk_payment_confirmation__reverses`：`(tenant_id, reverses_payment_confirmation_id) → contract.payment_confirmation(tenant_id, payment_confirmation_id)`。反向事实：撤销或退款必须引用同租户原付款确认。
- `fk_payment_confirmation__recorder`：`(tenant_id, recorded_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。记录主体：付款确认必须记录准确任职。

### `contract.contract_termination`

合同终止事实：一行单向保存合同取消或执行后终止，并可一次补入退款计算；实际退款仍追加PaymentConfirmation。

- Fact Owner：`ContractRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, contract_termination_id)`
- 允许更新字段：`refund_calculation_minor, refund_currency_code, refund_calculation_digest, refund_calculated_at, revision, changed_at`
- Write-once字段：`refund_calculation_minor, refund_currency_code, refund_calculation_digest, refund_calculated_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `contract_termination_id` | `uuid` | 否 | `—` | 合同终止事实标识：由应用生成的UUIDv7。 |
| `contract_id` | `uuid` | 否 | `—` | 合同标识：被取消或终止的准确合同。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 合同版本标识：取消或终止时适用的准确版本。 |
| `contract_execution_id` | `uuid` | 是 | `—` | 合同执行事实标识：执行后终止时必须存在，执行前取消时为空。 |
| `termination_kind` | `varchar(64)` | 否 | `—` | 终止类型：CANCELLED表示执行前取消，TERMINATED表示执行后终止。 |
| `decision_record_id` | `uuid` | 否 | `—` | 终止决定标识：授权取消或终止的准确DecisionRecord。 |
| `evidence_submission_id` | `uuid` | 是 | `—` | 终止证据提交标识：存在正式材料时准确引用。 |
| `reason_code` | `varchar(64)` | 否 | `—` | 终止原因：允许列表化的业务原因代码。 |
| `reason_summary` | `text` | 否 | `—` | 原因摘要：最小必要且允许列表化的说明，不保存完整案情。 |
| `terminated_at` | `timestamptz(6)` | 否 | `—` | 终止时间：取消或终止事实生效的可信业务时间。 |
| `terminated_by_appointment_id` | `uuid` | 否 | `—` | 终止任职标识：执行授权命令的准确任职。 |
| `refund_calculation_minor` | `bigint` | 是 | `—` | 退款计算金额：最小货币单位，尚未计算时为空，不代表已经退款。 |
| `refund_currency_code` | `varchar(3)` | 是 | `—` | 退款计算币种：三位大写代码，尚未计算时为空。 |
| `refund_calculation_digest` | `bytea` | 是 | `—` | 退款计算摘要：覆盖计算输入、规则与结果，尚未计算时为空。 |
| `refund_calculated_at` | `timestamptz(6)` | 是 | `—` | 退款计算时间：Fact Owner完成计算后一次写入。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：取消或终止事实首次写入的可信时间。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：退款计算槽最近一次受控写入的可信时间。 |

约束：

- `uk_contract_termination__contract`（`UNIQUE`：`tenant_id, contract_id`）：合同唯一：一份合同最多形成一个取消或终止事实。
- `ck_contract_termination__termination_kind`（`CHECK`：`termination_kind IN ('CANCELLED', 'TERMINATED')`）：终止类型仅允许执行前取消或执行后终止。
- `ck_contract_termination__execution_shape`（`CHECK`：`(termination_kind = 'CANCELLED' AND contract_execution_id IS NULL) OR (termination_kind = 'TERMINATED' AND contract_execution_id IS NOT NULL)`）：执行关系：取消发生在执行前，终止必须引用执行事实。
- `ck_contract_termination__refund_complete`（`CHECK`：`(refund_calculation_minor IS NULL AND refund_currency_code IS NULL AND refund_calculation_digest IS NULL AND refund_calculated_at IS NULL) OR (refund_calculation_minor IS NOT NULL AND refund_calculation_minor >= 0 AND refund_currency_code ~ '^[A-Z]{3}$' AND refund_calculation_digest IS NOT NULL AND refund_calculated_at IS NOT NULL)`）：退款计算完整性：金额、币种、摘要和时间必须一次性全部写入或全部为空。
- `ck_contract_termination__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_contract_termination__refund_calculation_digest_length`（`CHECK`：`octet_length(refund_calculation_digest) = 32`）：摘要格式：refund_calculation_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_contract_termination__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_contract_termination__contract`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。合同归属：终止事实必须属于准确合同。
- `fk_contract_termination__revision`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。版本归属：终止事实必须冻结准确合同版本。
- `fk_contract_termination__execution`：`(tenant_id, contract_execution_id) → contract.contract_execution(tenant_id, contract_execution_id)`。执行来源：执行后终止必须引用准确执行事实。
- `fk_contract_termination__revision_contract`：`(tenant_id, contract_revision_id, contract_id) → contract.contract_revision(tenant_id, contract_revision_id, contract_id)`。终止版本归属：取消或终止采用的Revision必须属于同一Contract。
- `fk_contract_termination__execution_path`：`(tenant_id, contract_execution_id, contract_id, contract_revision_id) → contract.contract_execution(tenant_id, contract_execution_id, contract_id, contract_revision_id)`。执行后终止路径：Execution、Contract和Revision必须完全一致；CANCELLED时空值按MATCH SIMPLE跳过。
- `fk_contract_termination__decision`：`(tenant_id, decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。决定依据：终止事实必须引用准确授权决定。
- `fk_contract_termination__evidence`：`(tenant_id, evidence_submission_id) → evidence.evidence_submission(tenant_id, evidence_submission_id)`。证据来源：可选引用准确终止材料。
- `fk_contract_termination__terminator`：`(tenant_id, terminated_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。执行主体：终止事实必须记录准确任职。

### `contract.preparation_request`

直接准备合同的准确申请；不是报价接受、合同或批准。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, preparation_request_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `preparation_request_id` | `uuid` | 否 | `—` | 直接准备合同的准确申请；不是报价接受、合同或批准。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 读取的商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 责任依据版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 保存/确认的当前负责人。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 准确客户需求确认。 |
| `commercial_digest` | `bytea` | 否 | `—` | 已申请服务范围及费用付款条款的规范摘要。 |
| `previous_request_id` | `uuid` | 是 | `—` | 本商机的准确前次申请；旧决定不适用于新申请。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 服务端形成时间。 |

约束：

- `ck_preparation_request__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_preparation_request__basis`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991`）：准确责任和版本。
- `ck_preparation_request__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界加密正文。
- `uq_preparation_request__previous`（`UNIQUE`：`tenant_id, previous_request_id`）：申请单后继。
- `ck_preparation_request__commercial_digest_length`（`CHECK`：`octet_length(commercial_digest) = 32`）：摘要格式：commercial_digest必须保存32字节的规范二进制值。
- `ck_preparation_request__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_preparation_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_preparation_request__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_preparation_request__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_preparation_request__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_preparation_request__previous_request_id`：`(tenant_id, previous_request_id) → contract.preparation_request(tenant_id, preparation_request_id)`。同租户准确事实引用。

索引：

- `uq_preparation_request__root`：列`(tenant_id, opportunity_id)`；唯一=`是`；谓词=`previous_request_id IS NULL`。每商机只有一个首申请；不依赖事务快照刷新。

### `contract.preparation_decision`

准确申请的不可变决定；仍需命令运行时授权，不代表合同审批。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, preparation_decision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `preparation_decision_id` | `uuid` | 否 | `—` | 准确申请的不可变决定；仍需命令运行时授权，不代表合同审批。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `preparation_request_id` | `uuid` | 否 | `—` | 被决定的唯一准确申请。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 批准或退回。 |
| `decided_by_appointment_id` | `uuid` | 否 | `—` | 实际有权决定任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `effective_from` | `timestamptz(6)` | 是 | `—` | 批准生效起点；由服务端决定时间写入。 |
| `effective_until` | `timestamptz(6)` | 是 | `—` | 批准自然到期；空表示未设自然到期。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 服务端决定时间。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |

约束：

- `ck_preparation_decision__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_preparation_decision__request`（`UNIQUE`：`tenant_id, preparation_request_id`）：每份申请只有一个最终决定。
- `ck_preparation_decision__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：决定说明有界密文。
- `ck_preparation_decision__shape`（`CHECK`：`(decision_code='APPROVED' AND effective_from IS NOT NULL AND (effective_until IS NULL OR effective_until>effective_from)) OR (decision_code='RETURNED' AND effective_from IS NULL AND effective_until IS NULL)`）：退回没有授权区间。
- `ck_preparation_decision__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_preparation_decision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_preparation_decision__preparation_request_id`：`(tenant_id, preparation_request_id) → contract.preparation_request(tenant_id, preparation_request_id)`。同租户准确事实引用。
- `fk_preparation_decision__decided_by_appointment_id`：`(tenant_id, decided_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.approval_policy`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, approval_policy_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `approval_policy_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `organization_unit_id` | `uuid` | 否 | `—` | 明确适用组织。 |
| `policy_code` | `varchar(64)` | 否 | `—` | 具名合同审批策略。 |
| `policy_version` | `bigint` | 否 | `—` | 明确策略版本。 |
| `mode` | `varchar(64)` | 否 | `—` | 明确要求批准；不复用报价自授权。 |
| `policy_digest` | `bytea` | 否 | `—` | 策略和审批成员规范摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_approval_policy__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_approval_policy__version`（`UNIQUE`：`tenant_id, organization_unit_id, policy_code, policy_version`）：组织策略版本唯一。
- `ck_approval_policy__values`（`CHECK`：`policy_code='R2_CONTRACT_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode='REQUIRE_APPROVAL'`）：明确合同审批策略。
- `ck_approval_policy__policy_digest_length`（`CHECK`：`octet_length(policy_digest) = 32`）：摘要格式：policy_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_approval_policy__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_approval_policy__organization_unit_id`：`(tenant_id, organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。同租户准确事实引用。

### `contract.approval_policy_member`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, approval_policy_member_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `approval_policy_member_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `policy_id` | `uuid` | 否 | `—` | 准确策略版本。 |
| `requirement_code` | `varchar(64)` | 否 | `—` | 明确审批要求。 |
| `appointment_id` | `uuid` | 否 | `—` | 明确有权审批任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_approval_policy_member__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_approval_policy_member__requirement`（`UNIQUE`：`tenant_id, policy_id, requirement_code`）：每个策略要求唯一。

物理外键：

- `fk_approval_policy_member__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_approval_policy_member__policy_id`：`(tenant_id, policy_id) → contract.approval_policy(tenant_id, approval_policy_id)`。同租户准确事实引用。
- `fk_approval_policy_member__appointment_id`：`(tenant_id, appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.preparation_workflow`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, preparation_workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `preparation_workflow_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `contract_id` | `uuid` | 是 | `—` | 形成后的唯一合同身份。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | 直接前一流程事实。 |
| `stage_code` | `varchar(64)` | 否 | `—` | 明确合同准备责任阶段。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 实际后继负责人。 |
| `task_id` | `uuid` | 是 | `—` | 可办理的后继待办。 |
| `prior_task_id` | `uuid` | 是 | `—` | 移交的准确前序待办。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 真实流程写入任职；与后继责任人分离。 |
| `recovery_resume_stage` | `varchar(64)` | 是 | `—` | 失权时保留的准确恢复阶段；原任务类型和期限不变。 |

约束：

- `ck_preparation_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_preparation_workflow__previous_workflow_id`（`UNIQUE`：`tenant_id, previous_workflow_id`）：准确事实唯一。
- `ck_preparation_workflow__stage`（`CHECK`：`stage_code IN ('DIRECT_REQUEST','DIRECT_REVIEW','DIRECT_RETURNED','PREPARE','RETURNED','SUBMIT_REVIEW','AWAIT_REVIEW','REVIEW_SUPPLEMENT','REVIEW_BLOCKED','SUBMIT_APPROVAL','AWAIT_APPROVAL','READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE','OWNER_EXCEPTION') AND (stage_code NOT IN ('READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE') OR task_id IS NULL)`）：明确责任阶段；下阶段边界不创建占位待办。
- `ck_preparation_workflow__recovery_resume`（`CHECK`：`recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('DIRECT_RETURNED','PREPARE','RETURNED','REVIEW_BLOCKED','SUBMIT_REVIEW','SUBMIT_APPROVAL','REVIEW_SUPPLEMENT') AND task_id IS NULL AND prior_task_id IS NOT NULL)`）：仅无可办理任务的负责人异常保留恢复目标；不伪造退回决定。

物理外键：

- `fk_preparation_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_preparation_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_preparation_workflow__contract_id`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。同租户准确事实引用。
- `fk_preparation_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → contract.preparation_workflow(tenant_id, preparation_workflow_id)`。同租户准确事实引用。
- `fk_preparation_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_preparation_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_preparation_workflow__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_preparation_workflow__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_preparation_workflow__root`：列`(tenant_id, opportunity_id)`；唯一=`是`；谓词=`previous_workflow_id IS NULL`。每商机只有一个流程根。

### `contract.revision_approval_request`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_approval_request_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_approval_request_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 准确版本。 |
| `review_binding_id` | `uuid` | 否 | `—` | 本版通过审查。 |
| `requested_by_appointment_id` | `uuid` | 否 | `—` | 提交任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_revision_approval_request__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_approval_request__contract_revision_id`（`UNIQUE`：`tenant_id, contract_revision_id`）：准确事实唯一。
- `uq_revision_approval_request__review_binding_id`（`UNIQUE`：`tenant_id, review_binding_id`）：准确事实唯一。

物理外键：

- `fk_revision_approval_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_approval_request__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_revision_approval_request__review_binding_id`：`(tenant_id, review_binding_id) → contract.revision_review_binding(tenant_id, revision_review_binding_id)`。同租户准确事实引用。
- `fk_revision_approval_request__requested_by_appointment_id`：`(tenant_id, requested_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.preparation_draft`

准备草稿；保存不等于形成版本或完成责任。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, preparation_draft_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `preparation_draft_id` | `uuid` | 否 | `—` | 准备草稿；保存不等于形成版本或完成责任。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 读取的商机版本。 |
| `responsibility_type` | `varchar(64)` | 否 | `—` | 责任依据类型。 |
| `responsibility_id` | `uuid` | 否 | `—` | 责任依据身份。 |
| `responsibility_revision` | `bigint` | 否 | `—` | 责任依据版本。 |
| `owner_appointment_id` | `uuid` | 否 | `—` | 保存/确认的当前负责人。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 当前客户需求确认。 |
| `previous_draft_id` | `uuid` | 是 | `—` | 同商机直接前稿；换负责人也沿用单链。 |
| `source_quote_response_id` | `uuid` | 是 | `—` | 准确接受报价。 |
| `source_direct_decision_id` | `uuid` | 是 | `—` | 准确直接授权。 |
| `commercial_digest` | `bytea` | 否 | `—` | 准确商业摘要。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 保存时间。 |

约束：

- `ck_preparation_draft__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_preparation_draft__basis`（`CHECK`：`responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991`）：准确责任和版本。
- `ck_preparation_draft__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界加密正文。
- `uq_preparation_draft__previous_draft_id`（`UNIQUE`：`tenant_id, previous_draft_id`）：准确事实唯一。
- `ck_preparation_draft__source`（`CHECK`：`num_nonnulls(source_quote_response_id,source_direct_decision_id)=1`）：双入口互斥。
- `ck_preparation_draft__commercial_digest_length`（`CHECK`：`octet_length(commercial_digest) = 32`）：摘要格式：commercial_digest必须保存32字节的规范二进制值。
- `ck_preparation_draft__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_preparation_draft__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_preparation_draft__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_preparation_draft__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_preparation_draft__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_preparation_draft__previous_draft_id`：`(tenant_id, previous_draft_id) → contract.preparation_draft(tenant_id, preparation_draft_id)`。同租户准确事实引用。
- `fk_preparation_draft__source_quote_response_id`：`(tenant_id, source_quote_response_id) → opportunity.quote_response(tenant_id, quote_response_id)`。同租户准确事实引用。
- `fk_preparation_draft__source_direct_decision_id`：`(tenant_id, source_direct_decision_id) → contract.preparation_decision(tenant_id, preparation_decision_id)`。同租户准确事实引用。

索引：

- `uq_preparation_draft__root`：列`(tenant_id, opportunity_id)`；唯一=`是`；谓词=`previous_draft_id IS NULL`。每个商机只有一个草稿根。

### `contract.template_version`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, template_version_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `template_version_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `document_code` | `varchar(64)` | 否 | `—` | 受控文档代码。 |
| `version_no` | `integer` | 否 | `—` | 已审核文档版本。 |
| `evidence_version_id` | `uuid` | 否 | `—` | 真实T06原件版本。 |
| `body_sha256` | `bytea` | 否 | `—` | 真实文档字节摘要。 |
| `approved_by_appointment_id` | `uuid` | 否 | `—` | 有权审核者。 |
| `approved_at` | `timestamptz(6)` | 否 | `—` | 真实审核时间。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_template_version__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_template_version__code_version`（`UNIQUE`：`tenant_id, document_code, version_no`）：已审核文档版本唯一。
- `ck_template_version__version`（`CHECK`：`version_no>0 AND approved_at<=created_at`）：真实审核版本和时间。
- `ck_template_version__body_sha256_length`（`CHECK`：`octet_length(body_sha256) = 32`）：摘要格式：body_sha256必须保存32字节的规范二进制值。

物理外键：

- `fk_template_version__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_template_version__evidence_version_id`：`(tenant_id, evidence_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_template_version__approved_by_appointment_id`：`(tenant_id, approved_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.clause_version`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, clause_version_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `clause_version_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `document_code` | `varchar(64)` | 否 | `—` | 受控文档代码。 |
| `version_no` | `integer` | 否 | `—` | 已审核文档版本。 |
| `evidence_version_id` | `uuid` | 否 | `—` | 真实T06原件版本。 |
| `body_sha256` | `bytea` | 否 | `—` | 真实文档字节摘要。 |
| `approved_by_appointment_id` | `uuid` | 否 | `—` | 有权审核者。 |
| `approved_at` | `timestamptz(6)` | 否 | `—` | 真实审核时间。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_clause_version__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_clause_version__code_version`（`UNIQUE`：`tenant_id, document_code, version_no`）：已审核文档版本唯一。
- `ck_clause_version__version`（`CHECK`：`version_no>0 AND approved_at<=created_at`）：真实审核版本和时间。
- `ck_clause_version__body_sha256_length`（`CHECK`：`octet_length(body_sha256) = 32`）：摘要格式：body_sha256必须保存32字节的规范二进制值。

物理外键：

- `fk_clause_version__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_clause_version__evidence_version_id`：`(tenant_id, evidence_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_clause_version__approved_by_appointment_id`：`(tenant_id, approved_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.revision_clause`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_clause_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_clause_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 准确合同版本。 |
| `clause_version_id` | `uuid` | 否 | `—` | 准确已审核条款。 |
| `clause_no` | `integer` | 否 | `—` | 版本内顺序。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_revision_clause__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_clause__number`（`UNIQUE`：`tenant_id, contract_revision_id, clause_no`）：序号唯一。
- `uq_revision_clause__clause`（`UNIQUE`：`tenant_id, contract_revision_id, clause_version_id`）：条款唯一。
- `ck_revision_clause__no`（`CHECK`：`clause_no BETWEEN 1 AND 200`）：有界条款集合。

物理外键：

- `fk_revision_clause__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_clause__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_revision_clause__clause_version_id`：`(tenant_id, clause_version_id) → contract.clause_version(tenant_id, clause_version_id)`。同租户准确事实引用。

### `contract.revision_review_request`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_review_request_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_review_request_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 请求审查的准确版本。 |
| `scope_hash` | `bytea` | 否 | `—` | 本次准确审查范围。 |
| `requested_by_appointment_id` | `uuid` | 否 | `—` | 提交任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |
| `previous_request_id` | `uuid` | 是 | `—` | 前次补正审查申请。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |

约束：

- `ck_revision_review_request__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_revision_review_request__scope_hash_length`（`CHECK`：`octet_length(scope_hash) = 32`）：摘要格式：scope_hash必须保存32字节的规范二进制值。
- `uq_revision_review_request__previous_request_id`（`UNIQUE`：`tenant_id, previous_request_id`）：准确事实唯一。
- `ck_revision_review_request__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。

物理外键：

- `fk_revision_review_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_review_request__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_revision_review_request__requested_by_appointment_id`：`(tenant_id, requested_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_revision_review_request__previous_request_id`：`(tenant_id, previous_request_id) → contract.revision_review_request(tenant_id, revision_review_request_id)`。同租户准确事实引用。

索引：

- `uq_revision_review_request__root`：列`(tenant_id, contract_revision_id)`；唯一=`是`；谓词=`previous_request_id IS NULL`。每版只有一个审查申请根。

### `contract.revision_review_decision`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_review_decision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_review_decision_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `request_id` | `uuid` | 否 | `—` | 准确审查申请。 |
| `conflict_review_id` | `uuid` | 否 | `—` | 真实独立冲突审查。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 审查结果CLEAR/WAIVED/NEED_INFO/BLOCKED。 |
| `scope_hash` | `bytea` | 否 | `—` | 准确范围。 |
| `resolution_digest` | `bytea` | 是 | `—` | 准确结论摘要；补正阻断可空。 |
| `decided_by_appointment_id` | `uuid` | 否 | `—` | 实际审查任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_revision_review_decision__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_review_decision__request_id`（`UNIQUE`：`tenant_id, request_id`）：准确事实唯一。
- `ck_revision_review_decision__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_revision_review_decision__code`（`CHECK`：`decision_code IN ('CLEAR','WAIVED','NEED_INFO','BLOCKED') AND (decision_code NOT IN ('CLEAR','WAIVED') OR resolution_digest IS NOT NULL)`）：通过必须有准确结论依据。
- `ck_revision_review_decision__scope_hash_length`（`CHECK`：`octet_length(scope_hash) = 32`）：摘要格式：scope_hash必须保存32字节的规范二进制值。
- `ck_revision_review_decision__resolution_digest_length`（`CHECK`：`octet_length(resolution_digest) = 32`）：摘要格式：resolution_digest必须保存32字节的规范二进制值。
- `ck_revision_review_decision__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_revision_review_decision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_review_decision__request_id`：`(tenant_id, request_id) → contract.revision_review_request(tenant_id, revision_review_request_id)`。同租户准确事实引用。
- `fk_revision_review_decision__conflict_review_id`：`(tenant_id, conflict_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。同租户准确事实引用。
- `fk_revision_review_decision__decided_by_appointment_id`：`(tenant_id, decided_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.revision_review_binding`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_review_binding_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_review_binding_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 被放行版本。 |
| `review_decision_id` | `uuid` | 否 | `—` | 真实通过决定。 |
| `conflict_review_id` | `uuid` | 否 | `—` | 准确审查。 |
| `scope_hash` | `bytea` | 否 | `—` | 准确审查范围。 |
| `resolution_digest` | `bytea` | 否 | `—` | 准确可用结论。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_revision_review_binding__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_review_binding__contract_revision_id`（`UNIQUE`：`tenant_id, contract_revision_id`）：准确事实唯一。
- `uq_revision_review_binding__review_decision_id`（`UNIQUE`：`tenant_id, review_decision_id`）：准确事实唯一。
- `ck_revision_review_binding__scope_hash_length`（`CHECK`：`octet_length(scope_hash) = 32`）：摘要格式：scope_hash必须保存32字节的规范二进制值。
- `ck_revision_review_binding__resolution_digest_length`（`CHECK`：`octet_length(resolution_digest) = 32`）：摘要格式：resolution_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_revision_review_binding__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_review_binding__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_revision_review_binding__review_decision_id`：`(tenant_id, review_decision_id) → contract.revision_review_decision(tenant_id, revision_review_decision_id)`。同租户准确事实引用。
- `fk_revision_review_binding__conflict_review_id`：`(tenant_id, conflict_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。同租户准确事实引用。

### `contract.revision_approval_requirement`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_approval_requirement_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_approval_requirement_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 准确版本。 |
| `requirement_code` | `varchar(64)` | 否 | `—` | 明确审批要求。 |
| `approver_appointment_id` | `uuid` | 否 | `—` | 明确审批任职。 |
| `policy_digest` | `bytea` | 否 | `—` | 本版审批策略摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |
| `policy_id` | `uuid` | 否 | `—` | 本版明确合同审批策略。 |

约束：

- `ck_revision_approval_requirement__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_approval_requirement__code`（`UNIQUE`：`tenant_id, contract_revision_id, requirement_code`）：审批要求唯一。
- `ck_revision_approval_requirement__policy_digest_length`（`CHECK`：`octet_length(policy_digest) = 32`）：摘要格式：policy_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_revision_approval_requirement__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_approval_requirement__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_revision_approval_requirement__approver_appointment_id`：`(tenant_id, approver_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_revision_approval_requirement__policy_id`：`(tenant_id, policy_id) → contract.approval_policy(tenant_id, approval_policy_id)`。同租户准确事实引用。

### `contract.revision_approval_decision`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, revision_approval_decision_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `revision_approval_decision_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `requirement_id` | `uuid` | 否 | `—` | 准确审批要求。 |
| `review_binding_id` | `uuid` | 否 | `—` | 本版通过审查。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 批准或退回。 |
| `decided_by_appointment_id` | `uuid` | 否 | `—` | 实际审批任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |
| `approval_request_id` | `uuid` | 否 | `—` | 正式提交的准确审批申请。 |

约束：

- `ck_revision_approval_decision__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_revision_approval_decision__requirement_id`（`UNIQUE`：`tenant_id, requirement_id`）：准确事实唯一。
- `ck_revision_approval_decision__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_revision_approval_decision__code`（`CHECK`：`decision_code IN ('APPROVED','RETURNED')`）：明确审批结果。
- `ck_revision_approval_decision__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_revision_approval_decision__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_revision_approval_decision__requirement_id`：`(tenant_id, requirement_id) → contract.revision_approval_requirement(tenant_id, revision_approval_requirement_id)`。同租户准确事实引用。
- `fk_revision_approval_decision__review_binding_id`：`(tenant_id, review_binding_id) → contract.revision_review_binding(tenant_id, revision_review_binding_id)`。同租户准确事实引用。
- `fk_revision_approval_decision__decided_by_appointment_id`：`(tenant_id, decided_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_revision_approval_decision__approval_request_id`：`(tenant_id, approval_request_id) → contract.revision_approval_request(tenant_id, revision_approval_request_id)`。同租户准确事实引用。

### `contract.signature_readiness`

T08准确不可变事实；不是签署或执行事实。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_readiness_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_readiness_id` | `uuid` | 否 | `—` | T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 已完成审查与审批的准确当前版本。 |
| `review_binding_id` | `uuid` | 否 | `—` | 本版通过审查依据。 |
| `state_code` | `varchar(64)` | 否 | `—` | 明确下一阶段交接边界；不创建签署待办。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库形成时间。 |

约束：

- `ck_signature_readiness__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_readiness__contract_revision_id`（`UNIQUE`：`tenant_id, contract_revision_id`）：准确事实唯一。
- `ck_signature_readiness__state`（`CHECK`：`state_code='READY_FOR_SIGNATURE'`）：等待下一阶段，不是已签署。

物理外键：

- `fk_signature_readiness__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_readiness__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_signature_readiness__review_binding_id`：`(tenant_id, review_binding_id) → contract.revision_review_binding(tenant_id, revision_review_binding_id)`。同租户准确事实引用。

### `contract.template_signing_party`

审核模板同事务冻结的律所签约主体；不得为旧模板补造绑定。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, template_signing_party_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `template_signing_party_id` | `uuid` | 否 | `—` | 审核模板同事务冻结的律所签约主体；不得为旧模板补造绑定。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `template_version_id` | `uuid` | 否 | `—` | 准确审核模板。 |
| `party_id` | `uuid` | 否 | `—` | 真实律所主体。 |
| `party_revision` | `bigint` | 否 | `—` | 批准时主体版本。 |
| `profile_version_id` | `uuid` | 否 | `—` | 准确不可变主体资料。 |
| `party_snapshot_digest` | `bytea` | 否 | `—` | 资料身份规范摘要。 |
| `role_code` | `varchar(64)` | 否 | `—` | 明确模板签署角色FIRM。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 与模板审核者相同的任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库冻结时间。 |

约束：

- `ck_template_signing_party__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_template_signing_party__template_role`（`UNIQUE`：`tenant_id, template_version_id, role_code`）：模板内律所角色唯一。
- `uq_template_signing_party__template_party`（`UNIQUE`：`tenant_id, template_version_id, party_id`）：模板主体唯一。
- `ck_template_signing_party__values`（`CHECK`：`role_code='FIRM' AND party_revision BETWEEN 0 AND 9007199254740991`）：明确律所绑定及准确资料版本。
- `ck_template_signing_party__party_snapshot_digest_length`（`CHECK`：`octet_length(party_snapshot_digest) = 32`）：摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_template_signing_party__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_template_signing_party__template_version_id`：`(tenant_id, template_version_id) → contract.template_version(tenant_id, template_version_id)`。同租户准确事实引用。
- `fk_template_signing_party__party_id`：`(tenant_id, party_id) → party.party(tenant_id, party_id)`。同租户准确事实引用。
- `fk_template_signing_party__profile_version_id`：`(tenant_id, profile_version_id) → party.profile_version(tenant_id, profile_version_id)`。同租户准确事实引用。
- `fk_template_signing_party__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.signature_arrangement`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_arrangement_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_arrangement_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `readiness_id` | `uuid` | 否 | `—` | 准确签署准备依据。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 批准正文版本。 |
| `previous_arrangement_id` | `uuid` | 是 | `—` | 同正文安排补正前序。 |
| `registered_by_appointment_id` | `uuid` | 否 | `—` | 登记任职。 |
| `slot_count` | `integer` | 否 | `—` | 完整明确槽数量。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_arrangement__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_arrangement__previous_arrangement_id`（`UNIQUE`：`tenant_id, previous_arrangement_id`）：准确事实唯一。
- `ck_signature_arrangement__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_arrangement__count`（`CHECK`：`slot_count BETWEEN 1 AND 100`）：有限非空安排。
- `ck_signature_arrangement__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_arrangement__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_arrangement__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_arrangement__readiness_id`：`(tenant_id, readiness_id) → contract.signature_readiness(tenant_id, signature_readiness_id)`。同租户准确事实引用。
- `fk_signature_arrangement__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_signature_arrangement__previous_arrangement_id`：`(tenant_id, previous_arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_arrangement__registered_by_appointment_id`：`(tenant_id, registered_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_signature_arrangement__root`：列`(tenant_id, readiness_id)`；唯一=`是`；谓词=`previous_arrangement_id IS NULL`。同一准确依据唯一链根。

### `contract.signature_draft`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_draft_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_draft_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `readiness_id` | `uuid` | 否 | `—` | 准确签署准备依据。 |
| `arrangement_id` | `uuid` | 是 | `—` | 已登记安排；登记前草稿为空。 |
| `previous_draft_id` | `uuid` | 是 | `—` | 上一不可变草稿。 |
| `saved_by_appointment_id` | `uuid` | 否 | `—` | 保存任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_draft__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_draft__previous_draft_id`（`UNIQUE`：`tenant_id, previous_draft_id`）：准确事实唯一。
- `ck_signature_draft__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_draft__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_draft__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_draft__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_draft__readiness_id`：`(tenant_id, readiness_id) → contract.signature_readiness(tenant_id, signature_readiness_id)`。同租户准确事实引用。
- `fk_signature_draft__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_draft__previous_draft_id`：`(tenant_id, previous_draft_id) → contract.signature_draft(tenant_id, signature_draft_id)`。同租户准确事实引用。
- `fk_signature_draft__saved_by_appointment_id`：`(tenant_id, saved_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_signature_draft__root`：列`(tenant_id, readiness_id)`；唯一=`是`；谓词=`previous_draft_id IS NULL`。同一准确依据唯一链根。

### `contract.signature_submission`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_submission_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_submission_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `arrangement_id` | `uuid` | 否 | `—` | 准确安排。 |
| `signature_plan_id` | `uuid` | 否 | `—` | 准确计划槽。 |
| `previous_submission_id` | `uuid` | 是 | `—` | 同槽前次补证。 |
| `submitted_by_appointment_id` | `uuid` | 否 | `—` | 提交任职。 |
| `material_version_id` | `uuid` | 否 | `—` | 正式已接收签字件。 |
| `authority_material_version_id` | `uuid` | 否 | `—` | 正式权限材料。 |
| `material_sha256` | `bytea` | 否 | `—` | 签字文件字节摘要。 |
| `authority_material_sha256` | `bytea` | 否 | `—` | 权限文件字节摘要。 |
| `approved_body_sha256` | `bytea` | 否 | `—` | 批准正文摘要；无需等于签字文件。 |
| `signed_at` | `timestamptz(6)` | 否 | `—` | 实际签署业务时间。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_submission__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_submission__previous_submission_id`（`UNIQUE`：`tenant_id, previous_submission_id`）：准确事实唯一。
- `ck_signature_submission__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_submission__time`（`CHECK`：`signed_at<=created_at`）：业务时间不晚于系统记录。
- `ck_signature_submission__material_sha256_length`（`CHECK`：`octet_length(material_sha256) = 32`）：摘要格式：material_sha256必须保存32字节的规范二进制值。
- `ck_signature_submission__authority_material_sha256_length`（`CHECK`：`octet_length(authority_material_sha256) = 32`）：摘要格式：authority_material_sha256必须保存32字节的规范二进制值。
- `ck_signature_submission__approved_body_sha256_length`（`CHECK`：`octet_length(approved_body_sha256) = 32`）：摘要格式：approved_body_sha256必须保存32字节的规范二进制值。
- `ck_signature_submission__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_submission__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_submission__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_submission__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_submission__signature_plan_id`：`(tenant_id, signature_plan_id) → contract.signature_plan(tenant_id, signature_plan_id)`。同租户准确事实引用。
- `fk_signature_submission__previous_submission_id`：`(tenant_id, previous_submission_id) → contract.signature_submission(tenant_id, signature_submission_id)`。同租户准确事实引用。
- `fk_signature_submission__submitted_by_appointment_id`：`(tenant_id, submitted_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_signature_submission__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_signature_submission__authority_material_version_id`：`(tenant_id, authority_material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。

索引：

- `uq_signature_submission__root`：列`(tenant_id, signature_plan_id)`；唯一=`是`；谓词=`previous_submission_id IS NULL`。同一准确依据唯一链根。

### `contract.signature_verification`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_verification_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_verification_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `submission_id` | `uuid` | 否 | `—` | 准确不可变提交。 |
| `verified_by_appointment_id` | `uuid` | 否 | `—` | 有权核验任职。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 准确核验结论。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_verification__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_verification__submission_id`（`UNIQUE`：`tenant_id, submission_id`）：准确事实唯一。
- `ck_signature_verification__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_verification__decision`（`CHECK`：`decision_code IN ('VERIFIED','NEED_INFO','REVISION_REQUIRED','ARRANGEMENT_CORRECTION')`）：明确结果，不直接等于全合同完成。
- `ck_signature_verification__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_verification__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_verification__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_verification__submission_id`：`(tenant_id, submission_id) → contract.signature_submission(tenant_id, signature_submission_id)`。同租户准确事实引用。
- `fk_signature_verification__verified_by_appointment_id`：`(tenant_id, verified_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.signature_archive`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_archive_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_archive_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `arrangement_id` | `uuid` | 否 | `—` | 全部必要签署的准确安排。 |
| `material_version_id` | `uuid` | 否 | `—` | 完整正式归档文件。 |
| `archived_by_appointment_id` | `uuid` | 否 | `—` | 归档任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_archive__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_archive__arrangement_id`（`UNIQUE`：`tenant_id, arrangement_id`）：准确事实唯一。
- `ck_signature_archive__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_archive__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_archive__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_archive__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_archive__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_archive__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_signature_archive__archived_by_appointment_id`：`(tenant_id, archived_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.signature_revision_return`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_revision_return_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_revision_return_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `readiness_id` | `uuid` | 否 | `—` | 失效前准确准备依据。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 需要真实修订的正文。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 退回任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_revision_return__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_revision_return__readiness_id`（`UNIQUE`：`tenant_id, readiness_id`）：准确事实唯一。
- `ck_signature_revision_return__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_revision_return__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_revision_return__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_revision_return__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_revision_return__readiness_id`：`(tenant_id, readiness_id) → contract.signature_readiness(tenant_id, signature_readiness_id)`。同租户准确事实引用。
- `fk_signature_revision_return__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_signature_revision_return__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.signature_workflow`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_workflow_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `readiness_id` | `uuid` | 否 | `—` | 唯一消费准确准备依据。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 准确批准正文。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | CAS直接前序流程。 |
| `stage_code` | `varchar(64)` | 否 | `—` | 真实办理阶段。 |
| `owner_appointment_id` | `uuid` | 是 | `—` | 实际责任任职；异常可空。 |
| `task_id` | `uuid` | 是 | `—` | 可办理待办；终态无占位任务。 |
| `prior_task_id` | `uuid` | 是 | `—` | 前序待办。 |
| `arrangement_id` | `uuid` | 是 | `—` | 准确当前安排。 |
| `submission_id` | `uuid` | 是 | `—` | 等待核验的准确提交。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 实际写入任职。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 原责任期限，恢复不延长。 |
| `recovery_resume_stage` | `varchar(64)` | 是 | `—` | 异常恢复原阶段。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_workflow__previous_workflow_id`（`UNIQUE`：`tenant_id, previous_workflow_id`）：准确事实唯一。
- `ck_signature_workflow__stage`（`CHECK`：`stage_code IN ('ARRANGE','COLLECT','AWAIT_VERIFICATION','SUPPLEMENT','PARTIAL','ARCHIVE','SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION') AND (stage_code NOT IN ('SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION') OR task_id IS NULL) AND (stage_code IN ('OWNER_EXCEPTION','SIGNATURE_COMPLETE','REVISION_REQUIRED') OR owner_appointment_id IS NOT NULL)`）：终态不创建不可办理占位任务。
- `ck_signature_workflow__recovery`（`CHECK`：`recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('ARRANGE','COLLECT','AWAIT_VERIFICATION','SUPPLEMENT','PARTIAL','ARCHIVE','SIGNATURE_COMPLETE','REVISION_REQUIRED'))`）：异常保留准确恢复阶段。

物理外键：

- `fk_signature_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_workflow__readiness_id`：`(tenant_id, readiness_id) → contract.signature_readiness(tenant_id, signature_readiness_id)`。同租户准确事实引用。
- `fk_signature_workflow__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_signature_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → contract.signature_workflow(tenant_id, signature_workflow_id)`。同租户准确事实引用。
- `fk_signature_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_signature_workflow__created_by_appointment_id`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_signature_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_signature_workflow__prior_task_id`：`(tenant_id, prior_task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_signature_workflow__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_workflow__submission_id`：`(tenant_id, submission_id) → contract.signature_submission(tenant_id, signature_submission_id)`。同租户准确事实引用。

索引：

- `uq_signature_workflow__root`：列`(tenant_id, readiness_id)`；唯一=`是`；谓词=`previous_workflow_id IS NULL`。同一准确依据唯一链根。

### `contract.signature_handoff`

人工签署不可变准确事实；不是合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, signature_handoff_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `signature_handoff_id` | `uuid` | 否 | `—` | 人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `readiness_id` | `uuid` | 否 | `—` | 已消费准备依据。 |
| `arrangement_id` | `uuid` | 否 | `—` | 已全部核验的安排。 |
| `archive_id` | `uuid` | 否 | `—` | 准确完整归档。 |
| `state_code` | `varchar(64)` | 否 | `—` | 持久化后续等待边界。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_signature_handoff__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_signature_handoff__readiness_id`（`UNIQUE`：`tenant_id, readiness_id`）：准确事实唯一。
- `uq_signature_handoff__arrangement_id`（`UNIQUE`：`tenant_id, arrangement_id`）：准确事实唯一。
- `uq_signature_handoff__archive_id`（`UNIQUE`：`tenant_id, archive_id`）：准确事实唯一。
- `ck_signature_handoff__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_signature_handoff__state`（`CHECK`：`state_code='AWAITING_EXECUTION_CONDITIONS'`）：不等于合同执行、到账或建案。
- `ck_signature_handoff__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_signature_handoff__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_signature_handoff__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_signature_handoff__readiness_id`：`(tenant_id, readiness_id) → contract.signature_readiness(tenant_id, signature_readiness_id)`。同租户准确事实引用。
- `fk_signature_handoff__arrangement_id`：`(tenant_id, arrangement_id) → contract.signature_arrangement(tenant_id, signature_arrangement_id)`。同租户准确事实引用。
- `fk_signature_handoff__archive_id`：`(tenant_id, archive_id) → contract.signature_archive(tenant_id, signature_archive_id)`。同租户准确事实引用。

### `contract.negotiation_disposition`

销售办理处置的不可变准确事实；不解除合同或撤销执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, negotiation_disposition_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `negotiation_disposition_id` | `uuid` | 否 | `—` | 销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 销售主线。 |
| `opportunity_revision` | `bigint` | 否 | `—` | 准确商机修订。 |
| `previous_disposition_id` | `uuid` | 是 | `—` | 直接前序处置，单链。 |
| `request_disposition_id` | `uuid` | 是 | `—` | 主管处置所核对的准确请求。 |
| `kind` | `varchar(64)` | 否 | `—` | 协商处置类型：未签终止、请求复核、复核后终止或继续协商。 |
| `contract_id` | `uuid` | 是 | `—` | 当前合同身份；直接授权申请阶段可空。 |
| `contract_revision` | `bigint` | 是 | `—` | 合同身份准确修订。 |
| `contract_version_id` | `uuid` | 是 | `—` | 准确合同正文版本。 |
| `preparation_workflow_id` | `uuid` | 否 | `—` | 准确准备办理依据。 |
| `signature_workflow_id` | `uuid` | 是 | `—` | 当前签署办理依据。 |
| `recorded_by` | `uuid` | 否 | `—` | 实际申请或核对任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库可信记录时间。 |

约束：

- `ck_negotiation_disposition__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_negotiation_disposition__previous_disposition_id`（`UNIQUE`：`tenant_id, previous_disposition_id`）：引用只能接续一次。
- `ck_negotiation_disposition__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_negotiation_disposition__shape`（`CHECK`：`opportunity_revision BETWEEN 0 AND 9007199254740991 AND ((contract_id IS NULL AND contract_revision IS NULL AND contract_version_id IS NULL) OR (contract_id IS NOT NULL AND contract_revision BETWEEN 0 AND 9007199254740991)) AND ((kind IN ('STOP_UNSIGNED','REQUEST_REVIEW') AND request_disposition_id IS NULL) OR (kind IN ('STOP_REVIEWED','CONTINUE') AND request_disposition_id=previous_disposition_id AND request_disposition_id IS NOT NULL))`）：明确形状与准确版本。
- `ck_negotiation_disposition__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_negotiation_disposition__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_negotiation_disposition__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__previous_disposition_id`：`(tenant_id, previous_disposition_id) → contract.negotiation_disposition(tenant_id, negotiation_disposition_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__request_disposition_id`：`(tenant_id, request_disposition_id) → contract.negotiation_disposition(tenant_id, negotiation_disposition_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__contract_id`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__contract_version_id`：`(tenant_id, contract_version_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__preparation_workflow_id`：`(tenant_id, preparation_workflow_id) → contract.preparation_workflow(tenant_id, preparation_workflow_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__signature_workflow_id`：`(tenant_id, signature_workflow_id) → contract.signature_workflow(tenant_id, signature_workflow_id)`。同租户准确事实引用。
- `fk_negotiation_disposition__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_negotiation_disposition__root`：列`(tenant_id, opportunity_id)`；唯一=`是`；谓词=`previous_disposition_id IS NULL`。每条销售主线仅一个处置链根。

### `contract.termination_review_assignment`

销售办理处置的不可变准确事实；不解除合同或撤销执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, termination_review_assignment_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `termination_review_assignment_id` | `uuid` | 否 | `—` | 销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `request_id` | `uuid` | 否 | `—` | 准确终止核对请求。 |
| `previous_assignment_id` | `uuid` | 是 | `—` | 直接前序责任安排。 |
| `owner_appointment_id` | `uuid` | 是 | `—` | 有权主管；未配置时为空。 |
| `task_id` | `uuid` | 是 | `—` | 实际主管任务；未配置时为空。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 原核对期限，重新分配不延长。 |
| `recorded_by` | `uuid` | 否 | `—` | 申请者或恢复服务任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库可信记录时间。 |

约束：

- `ck_termination_review_assignment__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_termination_review_assignment__previous_assignment_id`（`UNIQUE`：`tenant_id, previous_assignment_id`）：引用只能接续一次。
- `uq_termination_review_assignment__task_id`（`UNIQUE`：`tenant_id, task_id`）：引用只能接续一次。
- `ck_termination_review_assignment__owner`（`CHECK`：`(owner_appointment_id IS NULL)=(task_id IS NULL)`）：无主管不伪造可办理任务。

物理外键：

- `fk_termination_review_assignment__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_termination_review_assignment__request_id`：`(tenant_id, request_id) → contract.negotiation_disposition(tenant_id, negotiation_disposition_id)`。同租户准确事实引用。
- `fk_termination_review_assignment__previous_assignment_id`：`(tenant_id, previous_assignment_id) → contract.termination_review_assignment(tenant_id, termination_review_assignment_id)`。同租户准确事实引用。
- `fk_termination_review_assignment__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_termination_review_assignment__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_termination_review_assignment__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_termination_review_assignment__root`：列`(tenant_id, request_id)`；唯一=`是`；谓词=`previous_assignment_id IS NULL`。每个请求一个责任安排链根。

### `contract.negotiation_cancelled_task`

销售办理处置的不可变准确事实；不解除合同或撤销执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, negotiation_cancelled_task_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `negotiation_cancelled_task_id` | `uuid` | 否 | `—` | 销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `disposition_id` | `uuid` | 否 | `—` | 停止或暂停的准确依据。 |
| `task_id` | `uuid` | 否 | `—` | 原未完成责任。 |
| `task_revision` | `bigint` | 否 | `—` | 取消前准确修订。 |
| `prior_state` | `varchar(64)` | 否 | `—` | 取消前OPEN/WAITING。 |
| `wait_id` | `uuid` | 是 | `—` | WAITING的末端等待。 |
| `wait_hash` | `bytea` | 是 | `—` | 准确等待摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库可信记录时间。 |

约束：

- `ck_negotiation_cancelled_task__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_negotiation_cancelled_task__task_id`（`UNIQUE`：`tenant_id, task_id`）：引用只能接续一次。
- `ck_negotiation_cancelled_task__state`（`CHECK`：`task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))`）：准确取消集合。
- `ck_negotiation_cancelled_task__wait_hash_length`（`CHECK`：`octet_length(wait_hash) = 32`）：摘要格式：wait_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_negotiation_cancelled_task__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_negotiation_cancelled_task__disposition_id`：`(tenant_id, disposition_id) → contract.negotiation_disposition(tenant_id, negotiation_disposition_id)`。同租户准确事实引用。
- `fk_negotiation_cancelled_task__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_negotiation_cancelled_task__wait_id`：`(tenant_id, wait_id) → responsibility.wait_receipt(tenant_id, wait_receipt_id)`。同租户准确事实引用。

### `contract.execution_verification`

执行条件办理不可变事实；不代替到账或合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, execution_verification_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `execution_verification_id` | `uuid` | 否 | `—` | 执行条件办理不可变事实；不代替到账或合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确销售主线。 |
| `handoff_id` | `uuid` | 否 | `—` | 准确签署归档交接。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 批准合同版本。 |
| `recorded_by` | `uuid` | 否 | `—` | 人工核验任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_execution_verification__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_execution_verification__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_execution_verification__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_execution_verification__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_execution_verification__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_execution_verification__handoff_id`：`(tenant_id, handoff_id) → contract.signature_handoff(tenant_id, signature_handoff_id)`。同租户准确事实引用。
- `fk_execution_verification__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_execution_verification__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `contract.execution_workflow`

执行条件办理不可变事实；不代替到账或合同执行。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, execution_workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `execution_workflow_id` | `uuid` | 否 | `—` | 执行条件办理不可变事实；不代替到账或合同执行。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确销售主线。 |
| `handoff_id` | `uuid` | 否 | `—` | 唯一归档交接来源。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 当前批准合同版本。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | 直接前序责任；根为空。 |
| `stage_code` | `varchar(64)` | 否 | `—` | 执行条件办理阶段。 |
| `owner_appointment_id` | `uuid` | 是 | `—` | 合格责任人；异常为空。 |
| `task_id` | `uuid` | 是 | `—` | 可办理责任；异常或完成为空。 |
| `verification_id` | `uuid` | 是 | `—` | 已人工核验条件。 |
| `execution_id` | `uuid` | 是 | `—` | 满足门禁后的合同执行事实。 |
| `recorded_by` | `uuid` | 否 | `—` | 办理者或恢复服务任职。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 首次交接确定的原期限，恢复不延长。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_execution_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_execution_workflow__previous`（`UNIQUE`：`tenant_id, previous_workflow_id`）：只允许一个后继。
- `ck_execution_workflow__stage`（`CHECK`：`stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT','READY_TRANSFER','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT') AND owner_appointment_id IS NOT NULL AND task_id IS NOT NULL) OR (stage_code IN ('READY_TRANSFER','OWNER_EXCEPTION') AND task_id IS NULL)) AND (stage_code='READY_TRANSFER')=(execution_id IS NOT NULL) AND (stage_code<>'READY_TRANSFER' OR verification_id IS NOT NULL)`）：阶段与可办理责任一致。

物理外键：

- `fk_execution_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_execution_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_execution_workflow__handoff_id`：`(tenant_id, handoff_id) → contract.signature_handoff(tenant_id, signature_handoff_id)`。同租户准确事实引用。
- `fk_execution_workflow__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_execution_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → contract.execution_workflow(tenant_id, execution_workflow_id)`。同租户准确事实引用。
- `fk_execution_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_execution_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_execution_workflow__verification_id`：`(tenant_id, verification_id) → contract.execution_verification(tenant_id, execution_verification_id)`。同租户准确事实引用。
- `fk_execution_workflow__execution_id`：`(tenant_id, execution_id) → contract.contract_execution(tenant_id, contract_execution_id)`。同租户准确事实引用。
- `fk_execution_workflow__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_execution_workflow__root`：列`(tenant_id, handoff_id)`；唯一=`是`；谓词=`previous_workflow_id IS NULL`。交接只形成一个责任链根。

### `contract.payment_request`

收款办理不可变事实；独立于销售执行责任。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, payment_request_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `payment_request_id` | `uuid` | 否 | `—` | 收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `handoff_id` | `uuid` | 否 | `—` | 准确签署归档交接。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 准确批准合同版本。 |
| `material_version_id` | `uuid` | 是 | `—` | 后续逐笔核对的明确凭证；首项可空。 |
| `recorded_by` | `uuid` | 否 | `—` | 发起任职。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 本次责任原期限。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_payment_request__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_payment_request__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `ck_payment_request__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_payment_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_payment_request__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_payment_request__handoff_id`：`(tenant_id, handoff_id) → contract.signature_handoff(tenant_id, signature_handoff_id)`。同租户准确事实引用。
- `fk_payment_request__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_payment_request__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_payment_request__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_payment_request__initial`：列`(tenant_id, handoff_id)`；唯一=`是`；谓词=`material_version_id IS NULL`。交接首项唯一。
- `uq_payment_request__material`：列`(tenant_id, handoff_id, material_version_id)`；唯一=`是`；谓词=`material_version_id IS NOT NULL`。后续同版凭证不可重复起项。

### `contract.payment_workflow`

收款办理不可变事实；独立于销售执行责任。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, payment_workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `payment_workflow_id` | `uuid` | 否 | `—` | 收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `request_id` | `uuid` | 否 | `—` | 本次核对请求。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | 直接前序。 |
| `stage_code` | `varchar(64)` | 否 | `—` | 财务核对、销售补正、结束或责任异常。 |
| `target_stage_code` | `varchar(64)` | 否 | `—` | 责任异常时仍保留原办理环节。 |
| `owner_appointment_id` | `uuid` | 是 | `—` | 责任任职。 |
| `task_id` | `uuid` | 是 | `—` | 办理事项。 |
| `review_id` | `uuid` | 是 | `—` | 触发本次变化的核对事实。 |
| `recorded_by` | `uuid` | 否 | `—` | 记录任职。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 原请求期限，不随补正重置。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_payment_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_payment_workflow__previous`（`UNIQUE`：`tenant_id, previous_workflow_id`）：前序唯一后继。
- `ck_payment_workflow__stage`（`CHECK`：`target_stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE') AND (stage_code=target_stage_code OR (stage_code='OWNER_EXCEPTION' AND target_stage_code<>'COMPLETE')) AND stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL) OR (stage_code IN ('COMPLETE','OWNER_EXCEPTION') AND task_id IS NULL))`）：办理责任与阶段一致。

物理外键：

- `fk_payment_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_payment_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_payment_workflow__request_id`：`(tenant_id, request_id) → contract.payment_request(tenant_id, payment_request_id)`。同租户准确事实引用。
- `fk_payment_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → contract.payment_workflow(tenant_id, payment_workflow_id)`。同租户准确事实引用。
- `fk_payment_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_payment_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_payment_workflow__review_id`：`(tenant_id, review_id) → contract.payment_review(tenant_id, payment_review_id)`。同租户准确事实引用。
- `fk_payment_workflow__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_payment_workflow__root`：列`(tenant_id, request_id)`；唯一=`是`；谓词=`previous_workflow_id IS NULL`。一请求一个责任链根。

### `contract.payment_review`

收款办理不可变事实；独立于销售执行责任。

- Fact Owner：`ContractRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, payment_review_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `payment_review_id` | `uuid` | 否 | `—` | 收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `workflow_id` | `uuid` | 否 | `—` | 核对的准确当前责任。 |
| `decision_code` | `varchar(64)` | 否 | `—` | 确认到账、退回或补正。 |
| `material_version_id` | `uuid` | 是 | `—` | 准确材料；退回不要求。 |
| `confirmation_id` | `uuid` | 是 | `—` | 本笔到账；退回及补正不得填写。 |
| `recorded_by` | `uuid` | 否 | `—` | 实际办理任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_payment_review__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_payment_review__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_payment_review__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：同一责任版本只记录一次结果。
- `uq_payment_review__confirmation`（`UNIQUE`：`tenant_id, confirmation_id`）：同一到账只归入一次核对。
- `ck_payment_review__decision`（`CHECK`：`decision_code IN ('CONFIRMED','RETURNED','SUPPLEMENTED') AND (decision_code='CONFIRMED')=(confirmation_id IS NOT NULL) AND (decision_code='RETURNED' OR material_version_id IS NOT NULL)`）：退回补正不能伪造到账。
- `ck_payment_review__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_payment_review__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_payment_review__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_payment_review__workflow_id`：`(tenant_id, workflow_id) → contract.payment_workflow(tenant_id, payment_workflow_id)`。同租户准确事实引用。
- `fk_payment_review__material_version_id`：`(tenant_id, material_version_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_payment_review__confirmation_id`：`(tenant_id, confirmation_id) → contract.payment_confirmation(tenant_id, payment_confirmation_id)`。同租户准确事实引用。
- `fk_payment_review__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

## `transfer`

转案事实域：保存转案请求锚点、不可变提交快照和逐项退回要求。

- Fact Owner：`TransferRuntime`

### `transfer.transfer_request`

转案请求锚点：一行对应一次由准确DealActivated来源发起的组织间转案，只保存一次性接收和MatterRef槽，不保存通用状态或当前快照指针。

- Fact Owner：`TransferRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, transfer_request_id)`
- 允许更新字段：`accepted_snapshot_id, accept_decision_record_id, matter_id, matter_no, matter_type_code, matter_capability_pack_code, matter_capability_pack_version, matter_created_at, revision, changed_at`
- Write-once字段：`accepted_snapshot_id, accept_decision_record_id, matter_id, matter_no, matter_type_code, matter_capability_pack_code, matter_capability_pack_version, matter_created_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 转案请求锚点标识：由应用生成的UUIDv7。 |
| `opportunity_id` | `uuid` | 否 | `—` | 来源商机标识：转案所承接的唯一法律需求。 |
| `contract_id` | `uuid` | 否 | `—` | 来源合同标识：已经形成DealActivated的准确合同。 |
| `contract_execution_id` | `uuid` | 否 | `—` | 合同执行事实标识：转案必须以准确执行事实为前提。 |
| `deal_activated_at` | `timestamptz(6)` | 否 | `—` | 交易激活时间：来源合同DealActivated槽的准确时间。 |
| `deal_activation_digest` | `bytea` | 否 | `—` | 交易激活摘要：冻结激活依据、合同和版本的32字节摘要。 |
| `from_organization_unit_id` | `uuid` | 否 | `—` | 转出组织标识：发起转案的准确组织单元。 |
| `to_organization_unit_id` | `uuid` | 否 | `—` | 接收组织标识：负责案管审查和Matter接收的准确组织单元。 |
| `transfer_purpose_code` | `varchar(64)` | 否 | `—` | 转案目的：静态注册的业务目的代码。 |
| `proposed_matter_type_code` | `varchar(64)` | 否 | `—` | 拟建Matter类型：首次请求时冻结的静态类型。 |
| `proposed_capability_pack_code` | `varchar(64)` | 否 | `—` | 拟建能力包代码：接收方应具备的静态能力包。 |
| `proposed_capability_pack_version` | `integer` | 否 | `—` | 拟建能力包版本：解释Matter能力的正整数版本。 |
| `accepted_snapshot_id` | `uuid` | 是 | `—` | 接收快照标识：ACCEPT事务中一次写入的当前叶Snapshot。 |
| `accept_decision_record_id` | `uuid` | 是 | `—` | 接收决定标识：ACCEPT事务中一次写入的准确DecisionRecord。 |
| `matter_id` | `uuid` | 是 | `—` | Matter稳定标识：接收成功时一次生成，MVP不建立Matter表。 |
| `matter_no` | `varchar(80)` | 是 | `—` | Matter编号：接收成功时一次生成的租户内稳定编号。 |
| `matter_type_code` | `varchar(64)` | 是 | `—` | Matter类型：接收成功时冻结在MatterRef中的静态类型。 |
| `matter_capability_pack_code` | `varchar(64)` | 是 | `—` | Matter能力包代码：接收成功时冻结的准确能力包。 |
| `matter_capability_pack_version` | `integer` | 是 | `—` | Matter能力包版本：接收成功时冻结的正整数版本。 |
| `matter_created_at` | `timestamptz(6)` | 是 | `—` | Matter创建时间：ACCEPT与MatterCreated同事务提交的可信时间。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_by_appointment_id` | `uuid` | 否 | `—` | 创建任职标识：发起转案请求的准确任职。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：转案请求锚点建立的可信时间。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：一次性接收槽最近写入的可信时间。 |

约束：

- `uk_transfer_request__deal_activation`（`UNIQUE`：`tenant_id, contract_id, deal_activation_digest`）：来源唯一：同一合同的准确DealActivated事实只能形成一个转案请求。
- `ck_transfer_request__different_orgs`（`CHECK`：`from_organization_unit_id <> to_organization_unit_id`）：组织边界：转出组织和接收组织不得相同。
- `ck_transfer_request__capability_version`（`CHECK`：`proposed_capability_pack_version > 0`）：拟建能力包版本必须为正数。
- `ck_transfer_request__accept_complete`（`CHECK`：`(accepted_snapshot_id IS NULL AND accept_decision_record_id IS NULL AND matter_id IS NULL AND matter_no IS NULL AND matter_type_code IS NULL AND matter_capability_pack_code IS NULL AND matter_capability_pack_version IS NULL AND matter_created_at IS NULL) OR (accepted_snapshot_id IS NOT NULL AND accept_decision_record_id IS NOT NULL AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND matter_type_code IS NOT NULL AND matter_capability_pack_code IS NOT NULL AND matter_capability_pack_version > 0 AND matter_created_at IS NOT NULL)`）：原子接收：acceptedSnapshot、acceptDecision和完整MatterRef必须全部为空或一次性全部写入。
- `ck_transfer_request__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_transfer_request__deal_activation_digest_length`（`CHECK`：`octet_length(deal_activation_digest) = 32`）：摘要格式：deal_activation_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_transfer_request__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_transfer_request__opportunity`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。销售来源：转案请求必须引用准确商机。
- `fk_transfer_request__contract`：`(tenant_id, contract_id) → contract.contract(tenant_id, contract_id)`。合同来源：转案请求必须引用准确合同。
- `fk_transfer_request__contract_execution`：`(tenant_id, contract_execution_id) → contract.contract_execution(tenant_id, contract_execution_id)`。执行来源：转案请求必须引用准确合同执行事实。
- `fk_transfer_request__from_org`：`(tenant_id, from_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。转出组织：必须是同租户当前组织树中的准确组织单元。
- `fk_transfer_request__to_org`：`(tenant_id, to_organization_unit_id) → identity.organization_unit(tenant_id, organization_unit_id)`。接收组织：必须是同租户当前组织树中的准确组织单元。
- `fk_transfer_request__accepted_snapshot`：`(tenant_id, accepted_snapshot_id) → transfer.transfer_snapshot(tenant_id, transfer_snapshot_id)`。接收快照：必须引用本请求当前叶Snapshot，归属由延迟守卫复验。
- `fk_transfer_request__accept_decision`：`(tenant_id, accept_decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。接收决定：必须引用准确ACCEPT DecisionRecord。
- `fk_transfer_request__creator`：`(tenant_id, created_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。创建主体：转案请求必须记录准确任职。
- `fk_transfer_request__contract_path`：`(tenant_id, contract_id, opportunity_id, contract_execution_id) → contract.contract(tenant_id, contract_id, opportunity_id, contract_execution_id)`。转案合同主链：Opportunity、Contract及Execution必须来自同一已执行合同锚点。

索引：

- `ux_transfer_request__matter_id`：列`(tenant_id, matter_id)`；唯一=`是`；谓词=`matter_id IS NOT NULL`。Matter标识唯一：已接收请求生成的matterId在租户内唯一。
- `ux_transfer_request__matter_no`：列`(tenant_id, matter_no)`；唯一=`是`；谓词=`matter_no IS NOT NULL`。Matter编号唯一：已接收请求生成的matterNo在租户内唯一。

### `transfer.transfer_snapshot`

转案快照：一行表示首次提交或补正后的完整不可变版本，准确绑定一张提交Task、确认草案、材料合同、EvidenceRef集合和独立PRE_TRANSFER审查。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, transfer_snapshot_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `transfer_snapshot_id` | `uuid` | 否 | `—` | 转案快照标识：由应用生成的UUIDv7。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 转案请求标识：快照所属的一次转案请求。 |
| `snapshot_no` | `integer` | 否 | `—` | 快照序号：从一开始沿单后继链递增。 |
| `predecessor_snapshot_id` | `uuid` | 是 | `—` | 前序快照标识：首次提交为空，补正提交准确引用直接前序。 |
| `submission_task_occurrence_id` | `uuid` | 否 | `—` | 提交Task标识：该Snapshot完成的唯一SUBMIT或RESUBMIT责任卡。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 确认草案标识：本次提交使用的准确候选输入。 |
| `action_draft_digest` | `bytea` | 否 | `—` | 草案摘要：确认时冻结的ActionDraft准确内容摘要。 |
| `contract_context_digest` | `bytea` | 否 | `—` | 合同上下文摘要：冻结合同、版本、执行和DealActivated来源。 |
| `legal_need_context_digest` | `bytea` | 否 | `—` | 法律需求上下文摘要：冻结来源商机的准确法律需求。 |
| `material_contract_code` | `varchar(64)` | 否 | `—` | 材料合同代码：静态注册的完整材料结构。 |
| `material_contract_version` | `integer` | 否 | `—` | 材料合同版本：解释本快照材料范围的正整数版本。 |
| `evidence_submission_ids` | `uuid[]` | 否 | `—` | EvidenceRef集合：按UUID字节升序、去重保存的准确EvidenceSubmission标识数组，由同租户Resolver逐项复验。 |
| `evidence_set_digest` | `bytea` | 否 | `—` | EvidenceRef集合摘要：覆盖排序后全部EvidenceSubmission标识及用途。 |
| `pre_transfer_review_id` | `uuid` | 否 | `—` | 转案前冲突审查标识：为本快照独立创建的PRE_TRANSFER审查。 |
| `pre_transfer_scope_hash` | `bytea` | 否 | `—` | 转案前审查范围摘要：必须与所引用Review的准确scopeHash一致。 |
| `previous_return_decision_record_id` | `uuid` | 是 | `—` | 前序RETURN决定标识：补正快照必须引用，首次提交为空。 |
| `previous_return_items_digest` | `bytea` | 是 | `—` | 前序退回项集合摘要：补正快照覆盖全部ReturnItem时必填。 |
| `snapshot_digest` | `bytea` | 否 | `—` | 快照摘要：覆盖全部上下文、材料、EvidenceRef、审查和前序退回信息。 |
| `submitted_by_appointment_id` | `uuid` | 否 | `—` | 提交任职标识：执行提交主命令的准确任职。 |
| `submitted_at` | `timestamptz(6)` | 否 | `—` | 提交时间：快照与Task完成事实同事务封存的可信时间。 |

约束：

- `uk_transfer_snapshot__request_no`（`UNIQUE`：`tenant_id, transfer_request_id, snapshot_no`）：快照唯一：同一转案请求内快照序号不得重复。
- `uk_transfer_snapshot__predecessor`（`UNIQUE`：`tenant_id, predecessor_snapshot_id`）：单后继链：一个快照最多只有一个补正后继。
- `uk_transfer_snapshot__submission_task`（`UNIQUE`：`tenant_id, submission_task_occurrence_id`）：单完成事实：一张提交Task只能由一个TransferSnapshot完成。
- `uk_transfer_snapshot__confirmed_draft`（`UNIQUE`：`tenant_id, confirmed_action_draft_id`）：草案唯一：一份确认草案只能形成一个转案快照。
- `uk_transfer_snapshot__id_request`（`UNIQUE`：`tenant_id, transfer_snapshot_id, transfer_request_id`）：准确快照候选键：供退回项证明reviewedSnapshot与TransferRequest属于同一条转案链。
- `ck_transfer_snapshot__snapshot_no`（`CHECK`：`snapshot_no > 0`）：快照序号必须为正数。
- `ck_transfer_snapshot__material_version`（`CHECK`：`material_contract_version > 0`）：材料合同版本必须为正数。
- `ck_transfer_snapshot__evidence_nonempty`（`CHECK`：`cardinality(evidence_submission_ids) > 0`）：材料完整性：每个转案快照至少包含一个准确EvidenceRef。
- `ck_transfer_snapshot__chain_shape`（`CHECK`：`(snapshot_no = 1 AND predecessor_snapshot_id IS NULL AND previous_return_decision_record_id IS NULL AND previous_return_items_digest IS NULL) OR (snapshot_no > 1 AND predecessor_snapshot_id IS NOT NULL AND previous_return_decision_record_id IS NOT NULL AND previous_return_items_digest IS NOT NULL)`）：补正链：首次提交无前序和RETURN依据，补正必须同时引用前序快照、RETURN决定和完整退回项集合摘要。
- `ck_transfer_snapshot__action_draft_digest_length`（`CHECK`：`octet_length(action_draft_digest) = 32`）：摘要格式：action_draft_digest必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__contract_context_digest_length`（`CHECK`：`octet_length(contract_context_digest) = 32`）：摘要格式：contract_context_digest必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__legal_need_context_digest_length`（`CHECK`：`octet_length(legal_need_context_digest) = 32`）：摘要格式：legal_need_context_digest必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__evidence_set_digest_length`（`CHECK`：`octet_length(evidence_set_digest) = 32`）：摘要格式：evidence_set_digest必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__pre_transfer_scope_hash_length`（`CHECK`：`octet_length(pre_transfer_scope_hash) = 32`）：摘要格式：pre_transfer_scope_hash必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__previous_return_items_digest_length`（`CHECK`：`octet_length(previous_return_items_digest) = 32`）：摘要格式：previous_return_items_digest必须保存32字节的规范二进制值。
- `ck_transfer_snapshot__snapshot_digest_length`（`CHECK`：`octet_length(snapshot_digest) = 32`）：摘要格式：snapshot_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_transfer_snapshot__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_transfer_snapshot__transfer_request`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。请求归属：快照必须属于准确转案请求。
- `fk_transfer_snapshot__predecessor`：`(tenant_id, predecessor_snapshot_id) → transfer.transfer_snapshot(tenant_id, transfer_snapshot_id)`。补正链：补正快照必须引用同租户直接前序。
- `fk_transfer_snapshot__submission_task`：`(tenant_id, submission_task_occurrence_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。责任完成：快照必须完成准确提交Task。
- `fk_transfer_snapshot__action_draft`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。输入来源：快照必须引用准确确认草案。
- `fk_transfer_snapshot__pre_transfer_review`：`(tenant_id, pre_transfer_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。审查来源：快照必须引用独立PRE_TRANSFER审查。
- `fk_transfer_snapshot__previous_return_decision`：`(tenant_id, previous_return_decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。补正依据：后续快照必须引用前序RETURN决定。
- `fk_transfer_snapshot__submitter`：`(tenant_id, submitted_by_appointment_id) → identity.appointment(tenant_id, appointment_id)`。提交主体：快照必须记录准确任职。

### `transfer.transfer_return_item`

转案退回项：一行保存针对准确已审快照和RETURN决定的一项不可变补正要求，不设置OPEN或RESOLVED状态。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, transfer_return_item_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `transfer_return_item_id` | `uuid` | 否 | `—` | 转案退回项标识：由应用生成的UUIDv7。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 转案请求标识：退回项所属请求，用于同链一致性复验。 |
| `reviewed_snapshot_id` | `uuid` | 否 | `—` | 已审快照标识：RETURN决定实际审查的准确Snapshot。 |
| `return_decision_record_id` | `uuid` | 否 | `—` | RETURN决定标识：与本退回项同事务创建的准确DecisionRecord。 |
| `item_no` | `integer` | 否 | `—` | 退回项序号：在一次RETURN决定内稳定排序。 |
| `requirement_code` | `varchar(64)` | 否 | `—` | 要求代码：允许列表化的缺失或不符合项类型。 |
| `requirement_contract_version` | `integer` | 否 | `—` | 要求合同版本：解释目标和补正指令的正整数版本。 |
| `reason_code` | `varchar(64)` | 否 | `—` | 原因代码：允许列表化的退回原因。 |
| `correction_instruction` | `text` | 否 | `—` | 补正指令：最小必要的结构化文字说明，不保存文档正文。 |
| `required_evidence_purpose_code` | `varchar(64)` | 是 | `—` | 所需证据用途：要求新增Evidence时使用的静态用途代码。 |
| `item_digest` | `bytea` | 否 | `—` | 退回项摘要：覆盖要求、目标、版本、原因和补正指令。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 创建时间：与RETURN决定同事务写入的可信时间。 |
| `required_target_type` | `varchar(64)` | 否 | `—` | 退回项要求补正的准确目标的静态注册类型。 |
| `required_target_id` | `uuid` | 否 | `—` | 退回项要求补正的准确目标在所属租户内的准确标识。 |
| `required_target_revision` | `bigint` | 是 | `—` | 退回项要求补正的准确目标的准确修订号；按哈希冻结时为空。 |
| `required_target_hash` | `bytea` | 是 | `—` | 退回项要求补正的准确目标的准确规范摘要；按修订冻结时为空。 |

约束：

- `uk_transfer_return_item__decision_no`（`UNIQUE`：`tenant_id, return_decision_record_id, item_no`）：退回项唯一：一次RETURN决定内项目序号不得重复。
- `ck_transfer_return_item__item_no`（`CHECK`：`item_no > 0`）：退回项序号必须为正数。
- `ck_transfer_return_item__contract_version`（`CHECK`：`requirement_contract_version > 0`）：要求合同版本必须为正数。
- `ck_transfer_return_item__required_target_exact`（`CHECK`：`(required_target_type IS NOT NULL AND required_target_id IS NOT NULL AND ((required_target_revision IS NOT NULL AND required_target_revision >= 0 AND required_target_hash IS NULL) OR (required_target_revision IS NULL AND required_target_hash IS NOT NULL)))`）：准确引用：退回项要求补正的准确目标必须完整给出类型、标识以及修订号或摘要二者之一。
- `ck_transfer_return_item__item_digest_length`（`CHECK`：`octet_length(item_digest) = 32`）：摘要格式：item_digest必须保存32字节的规范二进制值。
- `ck_transfer_return_item__required_target_hash_length`（`CHECK`：`octet_length(required_target_hash) = 32`）：摘要格式：required_target_hash必须保存32字节的规范二进制值。

物理外键：

- `fk_transfer_return_item__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_transfer_return_item__transfer_request`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。请求归属：退回项必须属于准确转案请求。
- `fk_transfer_return_item__reviewed_snapshot`：`(tenant_id, reviewed_snapshot_id) → transfer.transfer_snapshot(tenant_id, transfer_snapshot_id)`。审查对象：退回项必须引用准确已审快照。
- `fk_transfer_return_item__snapshot_request`：`(tenant_id, reviewed_snapshot_id, transfer_request_id) → transfer.transfer_snapshot(tenant_id, transfer_snapshot_id, transfer_request_id)`。退回路径：reviewedSnapshot必须属于本ReturnItem冻结的同一TransferRequest。
- `fk_transfer_return_item__return_decision`：`(tenant_id, return_decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。决定归属：退回项必须引用准确RETURN决定。

类型化准确引用：

- `required_target`：退回项要求补正的准确目标；由静态允许列表、同租户Resolver和提交前复验保证。

索引：

- `ix_transfer_return_item__snapshot`：列`(tenant_id, reviewed_snapshot_id, item_no)`；唯一=`否`；谓词=`None`。审查查询：按已审快照读取全部不可变退回项。

### `transfer.workflow`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, workflow_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `workflow_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `previous_workflow_id` | `uuid` | 是 | `—` | 直接前序责任。 |
| `stage_code` | `varchar(64)` | 否 | `—` | 当前可办理阶段或责任异常。 |
| `target_stage_code` | `varchar(64)` | 否 | `—` | 责任异常仍保留原阶段。 |
| `owner_appointment_id` | `uuid` | 是 | `—` | 当前责任任职。 |
| `task_id` | `uuid` | 是 | `—` | 当前待办。 |
| `submission_id` | `uuid` | 是 | `—` | 准确待审提交。 |
| `review_id` | `uuid` | 是 | `—` | 本次独立审查。 |
| `intake_id` | `uuid` | 是 | `—` | 准确案管接收或退回。 |
| `classification_id` | `uuid` | 是 | `—` | 本案分类及承接事实。 |
| `recorded_by` | `uuid` | 否 | `—` | 记录任职。 |
| `due_at` | `timestamptz(6)` | 否 | `—` | 原责任期限，恢复和补正不重置。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_workflow__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `uq_transfer_workflow__previous`（`UNIQUE`：`tenant_id, previous_workflow_id`）：同一责任只有一个后继。
- `ck_transfer_workflow__stage`（`CHECK`：`target_stage_code IN ('PREPARE','REVIEW_TRANSFER','INTAKE','SUPPLEMENT','CLASSIFY','COMPLETE') AND (stage_code=target_stage_code OR stage_code='OWNER_EXCEPTION') AND ((stage_code IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NULL AND owner_appointment_id IS NULL) OR (stage_code NOT IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL)) AND (target_stage_code='PREPARE')=(submission_id IS NULL)`）：阶段、待审提交与责任一致。

物理外键：

- `fk_workflow__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_workflow__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_workflow__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_workflow__previous_workflow_id`：`(tenant_id, previous_workflow_id) → transfer.workflow(tenant_id, workflow_id)`。同租户准确事实引用。
- `fk_workflow__owner_appointment_id`：`(tenant_id, owner_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_workflow__task_id`：`(tenant_id, task_id) → responsibility.task_occurrence(tenant_id, task_occurrence_id)`。同租户准确事实引用。
- `fk_workflow__submission_id`：`(tenant_id, submission_id) → transfer.submission(tenant_id, submission_id)`。同租户准确事实引用。
- `fk_workflow__review_id`：`(tenant_id, review_id) → transfer.review(tenant_id, review_id)`。同租户准确事实引用。
- `fk_workflow__intake_id`：`(tenant_id, intake_id) → transfer.intake(tenant_id, intake_id)`。同租户准确事实引用。
- `fk_workflow__classification_id`：`(tenant_id, classification_id) → transfer.classification(tenant_id, classification_id)`。同租户准确事实引用。
- `fk_workflow__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

索引：

- `uq_transfer_workflow__root`：列`(tenant_id, transfer_request_id)`；唯一=`是`；谓词=`previous_workflow_id IS NULL`。一请求仅有一个责任链根。

### `transfer.submission`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, submission_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `submission_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `evidence_submission_ids` | `uuid[]` | 否 | `—` | 本次完整已接收证据集合，包含补正证据。 |
| `previous_submission_id` | `uuid` | 是 | `—` | 补正对应的上一提交。 |
| `previous_review_id` | `uuid` | 是 | `—` | 补正对应的准确退回审查。 |
| `previous_intake_id` | `uuid` | 是 | `—` | 案管退回的准确决定。 |
| `workflow_id` | `uuid` | 否 | `—` | 本次销售办理的准确责任。 |
| `contract_revision_id` | `uuid` | 否 | `—` | 批准合同版本。 |
| `customer_confirmation_id` | `uuid` | 否 | `—` | 当前客户及需求确认。 |
| `client_identity_material_id` | `uuid` | 否 | `—` | 已接收主体证明。 |
| `signature_archive_material_id` | `uuid` | 否 | `—` | 已接收完整签署归档。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 人工确认的准确输入草案。 |
| `action_draft_digest` | `bytea` | 否 | `—` | 准确确认草案摘要。 |
| `contract_context_digest` | `bytea` | 否 | `—` | 执行来源与合同上下文摘要。 |
| `legal_need_context_digest` | `bytea` | 否 | `—` | 提交时法律需求摘要。 |
| `recorded_by` | `uuid` | 否 | `—` | 实际销售提交任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_submission__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_submission__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_transfer_submission__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：一销售责任只提交一次。
- `uq_transfer_submission__draft`（`UNIQUE`：`tenant_id, confirmed_action_draft_id`）：一确认草案只形成一次提交。
- `ck_submission__action_draft_digest_length`（`CHECK`：`octet_length(action_draft_digest) = 32`）：摘要格式：action_draft_digest必须保存32字节的规范二进制值。
- `ck_submission__contract_context_digest_length`（`CHECK`：`octet_length(contract_context_digest) = 32`）：摘要格式：contract_context_digest必须保存32字节的规范二进制值。
- `ck_submission__legal_need_context_digest_length`（`CHECK`：`octet_length(legal_need_context_digest) = 32`）：摘要格式：legal_need_context_digest必须保存32字节的规范二进制值。
- `ck_submission__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_submission__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_submission__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_submission__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_submission__previous_submission_id`：`(tenant_id, previous_submission_id) → transfer.submission(tenant_id, submission_id)`。同租户准确事实引用。
- `fk_submission__previous_review_id`：`(tenant_id, previous_review_id) → transfer.review(tenant_id, review_id)`。同租户准确事实引用。
- `fk_submission__previous_intake_id`：`(tenant_id, previous_intake_id) → transfer.intake(tenant_id, intake_id)`。同租户准确事实引用。
- `fk_submission__workflow_id`：`(tenant_id, workflow_id) → transfer.workflow(tenant_id, workflow_id)`。同租户准确事实引用。
- `fk_submission__contract_revision_id`：`(tenant_id, contract_revision_id) → contract.contract_revision(tenant_id, contract_revision_id)`。同租户准确事实引用。
- `fk_submission__customer_confirmation_id`：`(tenant_id, customer_confirmation_id) → opportunity.customer_requirement_confirmation(tenant_id, customer_requirement_confirmation_id)`。同租户准确事实引用。
- `fk_submission__client_identity_material_id`：`(tenant_id, client_identity_material_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_submission__signature_archive_material_id`：`(tenant_id, signature_archive_material_id) → opportunity.material_version(tenant_id, material_version_id)`。同租户准确事实引用。
- `fk_submission__confirmed_action_draft_id`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。同租户准确事实引用。
- `fk_submission__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `transfer.review`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, review_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `review_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `workflow_id` | `uuid` | 否 | `—` | 被办理的独立审查责任。 |
| `submission_id` | `uuid` | 否 | `—` | 准确销售提交。 |
| `conflict_review_id` | `uuid` | 否 | `—` | 独立PRE_TRANSFER事实。 |
| `outcome_code` | `varchar(64)` | 否 | `—` | 人工审查结果。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 准确确认草案。 |
| `action_draft_digest` | `bytea` | 否 | `—` | 准确草案摘要。 |
| `scope_digest` | `bytea` | 否 | `—` | 范围摘要。 |
| `corpus_digest` | `bytea` | 否 | `—` | 语料摘要。 |
| `recorded_by` | `uuid` | 否 | `—` | 实际独立审查人。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_review__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_review__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_transfer_review__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：一次责任一个审查结果。
- `uq_transfer_review__conflict`（`UNIQUE`：`tenant_id, conflict_review_id`）：审查事实专属本提交。
- `ck_transfer_review__outcome`（`CHECK`：`outcome_code IN ('CLEAR','NEED_INFO','BLOCKED')`）：不自动豁免。
- `ck_review__action_draft_digest_length`（`CHECK`：`octet_length(action_draft_digest) = 32`）：摘要格式：action_draft_digest必须保存32字节的规范二进制值。
- `ck_review__scope_digest_length`（`CHECK`：`octet_length(scope_digest) = 32`）：摘要格式：scope_digest必须保存32字节的规范二进制值。
- `ck_review__corpus_digest_length`（`CHECK`：`octet_length(corpus_digest) = 32`）：摘要格式：corpus_digest必须保存32字节的规范二进制值。
- `ck_review__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_review__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_review__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_review__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_review__workflow_id`：`(tenant_id, workflow_id) → transfer.workflow(tenant_id, workflow_id)`。同租户准确事实引用。
- `fk_review__submission_id`：`(tenant_id, submission_id) → transfer.submission(tenant_id, submission_id)`。同租户准确事实引用。
- `fk_review__conflict_review_id`：`(tenant_id, conflict_review_id) → conflict.conflict_review(tenant_id, conflict_review_id)`。同租户准确事实引用。
- `fk_review__confirmed_action_draft_id`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。同租户准确事实引用。
- `fk_review__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `transfer.review_return_item`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, review_return_item_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `review_return_item_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `review_id` | `uuid` | 否 | `—` | 准确非通过审查。 |
| `requirement_code` | `varchar(64)` | 否 | `—` | 准确补正要求。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_review_return_item__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_review_return_item__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_transfer_review_return_item__requirement`（`UNIQUE`：`tenant_id, review_id, requirement_code`）：一次审查一个具名要求。
- `ck_review_return_item__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_review_return_item__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_review_return_item__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_review_return_item__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_review_return_item__review_id`：`(tenant_id, review_id) → transfer.review(tenant_id, review_id)`。同租户准确事实引用。

### `transfer.intake`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, intake_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `intake_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `workflow_id` | `uuid` | 否 | `—` | 准确案管待办。 |
| `submission_id` | `uuid` | 否 | `—` | 接收的提交。 |
| `review_id` | `uuid` | 否 | `—` | 准确独立审查。 |
| `snapshot_id` | `uuid` | 否 | `—` | 案管核对的完整冻结快照。 |
| `decision_record_id` | `uuid` | 否 | `—` | 独立案管决定。 |
| `outcome_code` | `varchar(64)` | 否 | `—` | 接收或退回。 |
| `requirement_code` | `varchar(64)` | 是 | `—` | 退回项目。 |
| `matter_id` | `uuid` | 是 | `—` | 接收后唯一案件身份。 |
| `matter_no` | `varchar(64)` | 是 | `—` | 唯一案件编号。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 接收核对草案。 |
| `action_draft_digest` | `bytea` | 否 | `—` | 草案摘要。 |
| `recorded_by` | `uuid` | 否 | `—` | 案管接收任职。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_intake__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_intake__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_transfer_intake__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：一责任一决定。
- `uq_transfer_intake__snapshot`（`UNIQUE`：`tenant_id, snapshot_id`）：一快照一决定。
- `ck_transfer_intake__outcome`（`CHECK`：`(outcome_code='ACCEPT' AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND requirement_code IS NULL) OR (outcome_code='RETURN' AND matter_id IS NULL AND matter_no IS NULL AND requirement_code IS NOT NULL)`）：未接收不得生成案件。
- `ck_intake__action_draft_digest_length`（`CHECK`：`octet_length(action_draft_digest) = 32`）：摘要格式：action_draft_digest必须保存32字节的规范二进制值。
- `ck_intake__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_intake__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_intake__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_intake__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_intake__workflow_id`：`(tenant_id, workflow_id) → transfer.workflow(tenant_id, workflow_id)`。同租户准确事实引用。
- `fk_intake__submission_id`：`(tenant_id, submission_id) → transfer.submission(tenant_id, submission_id)`。同租户准确事实引用。
- `fk_intake__review_id`：`(tenant_id, review_id) → transfer.review(tenant_id, review_id)`。同租户准确事实引用。
- `fk_intake__snapshot_id`：`(tenant_id, snapshot_id) → transfer.transfer_snapshot(tenant_id, transfer_snapshot_id)`。同租户准确事实引用。
- `fk_intake__decision_record_id`：`(tenant_id, decision_record_id) → responsibility.decision_record(tenant_id, decision_record_id)`。同租户准确事实引用。
- `fk_intake__confirmed_action_draft_id`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。同租户准确事实引用。
- `fk_intake__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

### `transfer.classification`

转案办理不可变事实；接收之前不生成案件。

- Fact Owner：`TransferRuntime`
- 更新策略：`IMMUTABLE`
- 主键：`(tenant_id, classification_id)`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户标识：复合主键和所有租户内关联的第一列。 |
| `classification_id` | `uuid` | 否 | `—` | 转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `created_in_transaction` | `xid8` | 否 | `pg_current_xact_id()` | 冻结配置或审批成员集合的形成事务。 |
| `transfer_request_id` | `uuid` | 否 | `—` | 准确转案来源。 |
| `opportunity_id` | `uuid` | 否 | `—` | 准确商机。 |
| `previous_classification_id` | `uuid` | 是 | `—` | 明确更正时保留前次分类。 |
| `workflow_id` | `uuid` | 否 | `—` | 已接收案件的分类责任。 |
| `intake_id` | `uuid` | 否 | `—` | 准确接收事实。 |
| `matter_id` | `uuid` | 否 | `—` | 保持已接收案件身份。 |
| `category_code` | `varchar(64)` | 否 | `—` | 综法、执行或其他。 |
| `recipient_appointment_id` | `uuid` | 否 | `—` | 有权承接任职。 |
| `confirmed_action_draft_id` | `uuid` | 否 | `—` | 人工分类草案。 |
| `action_draft_digest` | `bytea` | 否 | `—` | 分类草案摘要。 |
| `recorded_by` | `uuid` | 否 | `—` | 分类确认人。 |
| `body_ciphertext` | `bytea` | 否 | `—` | 联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。 |
| `body_digest` | `bytea` | 否 | `—` | 受保护规范正文完整性摘要。 |
| `created_at` | `timestamptz(6)` | 否 | `—` | 数据库记录时间。 |

约束：

- `ck_classification__revision`（`CHECK`：`revision=0`）：不可变保存版本。
- `ck_classification__body`（`CHECK`：`octet_length(body_ciphertext) BETWEEN 29 AND 131072`）：有界受保护正文。
- `uq_transfer_classification__previous`（`UNIQUE`：`tenant_id, previous_classification_id`）：前次分类只允许一个更正后继。
- `uq_transfer_classification__workflow`（`UNIQUE`：`tenant_id, workflow_id`）：一个分类责任一个结果。
- `ck_transfer_classification__category`（`CHECK`：`category_code IN ('GENERAL','ENFORCEMENT','OTHER')`）：限定MVP分类。
- `ck_classification__action_draft_digest_length`（`CHECK`：`octet_length(action_draft_digest) = 32`）：摘要格式：action_draft_digest必须保存32字节的规范二进制值。
- `ck_classification__body_digest_length`（`CHECK`：`octet_length(body_digest) = 32`）：摘要格式：body_digest必须保存32字节的规范二进制值。

物理外键：

- `fk_classification__tenant`：`(tenant_id) → identity.tenant(tenant_id)`。租户边界：该记录必须属于一个已存在的租户。
- `fk_classification__transfer_request_id`：`(tenant_id, transfer_request_id) → transfer.transfer_request(tenant_id, transfer_request_id)`。同租户准确事实引用。
- `fk_classification__opportunity_id`：`(tenant_id, opportunity_id) → opportunity.opportunity(tenant_id, opportunity_id)`。同租户准确事实引用。
- `fk_classification__previous_classification_id`：`(tenant_id, previous_classification_id) → transfer.classification(tenant_id, classification_id)`。同租户准确事实引用。
- `fk_classification__workflow_id`：`(tenant_id, workflow_id) → transfer.workflow(tenant_id, workflow_id)`。同租户准确事实引用。
- `fk_classification__intake_id`：`(tenant_id, intake_id) → transfer.intake(tenant_id, intake_id)`。同租户准确事实引用。
- `fk_classification__recipient_appointment_id`：`(tenant_id, recipient_appointment_id) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。
- `fk_classification__confirmed_action_draft_id`：`(tenant_id, confirmed_action_draft_id) → responsibility.action_draft(tenant_id, action_draft_id)`。同租户准确事实引用。
- `fk_classification__recorded_by`：`(tenant_id, recorded_by) → identity.appointment(tenant_id, appointment_id)`。同租户准确事实引用。

## `platform_meta`

平台元数据域：仅保存部署门禁；Flyway历史表由Flyway独占管理。

- Fact Owner：`DeploymentRuntime`

### `platform_meta.deployment_state`

部署门禁：唯一一行记录当前运行模式、发布摘要和Schema合同版本，不保存业务事实。

- Fact Owner：`DeploymentRuntime`
- 更新策略：`CONTROLLED`
- 主键：`(deployment_state_key)`
- 允许更新字段：`operating_mode, active_release_digest, active_manifest_hash, schema_contract_version, revision, changed_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `deployment_state_key` | `varchar(16)` | 否 | `—` | 单行主键：固定为PRIMARY，用于定位唯一部署门禁。 |
| `operating_mode` | `varchar(16)` | 否 | `—` | 运行模式：ACTIVE、MAINTENANCE或BLOCKED。 |
| `active_release_digest` | `bytea` | 否 | `—` | 当前发布摘要：运行中应用发布物的32字节规范摘要。 |
| `active_manifest_hash` | `bytea` | 否 | `—` | 当前部署清单摘要：类型、路由、Schema和策略清单的32字节摘要。 |
| `schema_contract_version` | `varchar(40)` | 否 | `—` | Schema合同版本：与本次52＋2字段合同对应的静态版本。 |
| `revision` | `bigint` | 否 | `0` | CAS修订号：每次受控更新必须精确递增一，初始为零。 |
| `changed_at` | `timestamptz(6)` | 否 | `—` | 变更时间：部署门禁最近一次受控切换的可信时间。 |

约束：

- `ck_deployment_state__singleton`（`CHECK`：`deployment_state_key = 'PRIMARY'`）：单行约束：门禁主键只能为PRIMARY。
- `ck_deployment_state__operating_mode`（`CHECK`：`operating_mode IN ('ACTIVE', 'MAINTENANCE', 'BLOCKED')`）：运行模式只允许正常、维护或阻断。
- `ck_deployment_state__revision_nonnegative`（`CHECK`：`revision >= 0`）：CAS修订号不得为负数。
- `ck_deployment_state__active_release_digest_length`（`CHECK`：`octet_length(active_release_digest) = 32`）：摘要格式：active_release_digest必须为32字节规范摘要。
- `ck_deployment_state__active_manifest_hash_length`（`CHECK`：`octet_length(active_manifest_hash) = 32`）：摘要格式：active_manifest_hash必须为32字节规范摘要。

### `platform_meta.r2_opportunity_checkpoint`

R2 Worker技术检查点；仅用于跨重启调度恢复，不构成业务事实或通用任务队列。

- Fact Owner：`R2OpportunityCheckpointStore`
- 更新策略：`CONTROLLED`
- 主键：`(tenant_id, principal_id, appointment_id, scan_kind)`
- 允许更新字段：`checkpoint_body, revision, updated_at`

| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |
|---|---|---:|---|---|
| `tenant_id` | `uuid` | 否 | `—` | 租户身份：仅技术调度键，不替代应用授权。 |
| `principal_id` | `uuid` | 否 | `—` | 服务主体身份：冻结当前配置的服务绑定。 |
| `appointment_id` | `uuid` | 否 | `—` | 服务任职身份：与主体和租户共同隔离检查点。 |
| `scan_kind` | `varchar(32)` | 否 | `—` | 具名扫描种类：INITIAL承接或DUE到期恢复。 |
| `checkpoint_body` | `bytea` | 否 | `—` | 版本化技术检查点：仅候选准确引用、游标、重试与诊断；不得保存客户正文或业务事实。 |
| `revision` | `bigint` | 否 | `0` | 并发修订号：初始为零，每次写入准确递增一。 |
| `updated_at` | `timestamptz(6)` | 否 | `clock_timestamp()` | 数据库更新时间：每次写入由数据库时钟设置。 |

约束：

- `ck_r2_opportunity_checkpoint__kind`（`CHECK`：`scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION','CONTRACT_PREPARATION')`）：种类仅允许两种已注册商机维护扫描。
- `ck_r2_opportunity_checkpoint__body`（`CHECK`：`octet_length(checkpoint_body) BETWEEN 1 AND 65536`）：技术正文不能为空且不得超过64KiB。
- `ck_r2_opportunity_checkpoint__revision`（`CHECK`：`revision BETWEEN 0 AND 9007199254740991`）：修订号必须是JSON安全非负整数。

## 跨域复合外键矩阵

只列出Schema之间的稳定单表关系；所有租户内关系都以`tenant_id`为第一列并使用`NO ACTION`，不存在级联删除。

| 子表 | 外键 | 子列 | 父表 | 父列 | 延迟 |
|---|---|---|---|---|---:|
| `audit.audit_entry` | `fk_audit_entry__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__actor_principal` | `(tenant_id, actor_principal_id)` | `identity.principal` | `(tenant_id, principal_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__actor_appointment` | `(tenant_id, actor_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__on_behalf_principal` | `(tenant_id, on_behalf_of_principal_id)` | `identity.principal` | `(tenant_id, principal_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__on_behalf_of_appointment` | `(tenant_id, on_behalf_of_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__actor_appointment_principal` | `(tenant_id, actor_appointment_id, actor_principal_id)` | `identity.appointment` | `(tenant_id, appointment_id, principal_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__on_behalf_appointment_principal` | `(tenant_id, on_behalf_of_appointment_id, on_behalf_of_principal_id)` | `identity.appointment` | `(tenant_id, appointment_id, principal_id)` | 否 |
| `audit.audit_entry` | `fk_audit_entry__authorization_scope_org` | `(tenant_id, authorization_scope_organization_unit_id)` | `identity.organization_unit` | `(tenant_id, organization_unit_id)` | 否 |
| `responsibility.task_occurrence` | `fk_task_occurrence__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `responsibility.task_occurrence` | `fk_task_occurrence__owner_appointment` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `responsibility.decision_record` | `fk_decision_record__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `responsibility.decision_record` | `fk_decision_record__decided_by_appointment` | `(tenant_id, decided_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `responsibility.wait_receipt` | `fk_wait_receipt__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `responsibility.wait_receipt` | `fk_wait_receipt__recorded_by_appointment` | `(tenant_id, recorded_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `responsibility.wait_receipt` | `fk_wait_receipt__handoff_fact_id` | `(tenant_id, handoff_fact_id)` | `opportunity.responsibility_handoff` | `(tenant_id, responsibility_handoff_id)` | 是 |
| `responsibility.wait_receipt` | `fk_wait_receipt__origin_progress_id` | `(tenant_id, origin_progress_id)` | `opportunity.opportunity_progress` | `(tenant_id, opportunity_progress_id)` | 是 |
| `responsibility.action_draft` | `fk_action_draft__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `responsibility.action_draft` | `fk_action_draft__created_by_appointment` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `responsibility.action_draft` | `fk_action_draft__confirmed_by_appointment` | `(tenant_id, confirmed_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `responsibility.contract_task_resumption` | `fk_contract_task_resumption__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `responsibility.contract_task_resumption` | `fk_contract_task_resumption__disposition_id` | `(tenant_id, disposition_id)` | `contract.negotiation_disposition` | `(tenant_id, negotiation_disposition_id)` | 否 |
| `responsibility.contract_task_resumption` | `fk_contract_task_resumption__cancelled_task_id` | `(tenant_id, cancelled_task_id)` | `contract.negotiation_cancelled_task` | `(tenant_id, negotiation_cancelled_task_id)` | 否 |
| `execution.command_execution_slot` | `fk_command_execution_slot__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `execution.command_receipt` | `fk_command_receipt__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `execution.domain_event` | `fk_domain_event__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `execution.domain_event_outbox` | `fk_domain_event_outbox__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `external_action.external_action` | `fk_external_action__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `external_action.external_action_outbox` | `fk_external_action_outbox__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `external_action.provider_inbox` | `fk_provider_inbox__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.upload_session` | `fk_upload_session__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.upload_session` | `fk_upload_session__creator` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `evidence.received_source_object` | `fk_received_source_object__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.evidence_submission` | `fk_evidence_submission__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.evidence_submission` | `fk_evidence_submission__submitter` | `(tenant_id, submitted_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `evidence.evidence_binding` | `fk_evidence_binding__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.evidence_binding` | `fk_evidence_binding__binder` | `(tenant_id, bound_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `evidence.evidence_binding` | `fk_evidence_binding__revoker` | `(tenant_id, revoked_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__customer_confirmation_id` | `(tenant_id, customer_confirmation_id)` | `opportunity.customer_requirement_confirmation` | `(tenant_id, customer_requirement_confirmation_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__original_task_id` | `(tenant_id, original_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `evidence.material_upload_basis` | `fk_material_upload_basis__expected_previous_version_id` | `(tenant_id, expected_previous_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `evidence.material_upload_check` | `fk_material_upload_check__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `party.party` | `fk_party__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `party.profile_version` | `fk_profile_version__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `party.profile_version` | `fk_profile_version__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `lead.lead` | `fk_lead__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `lead.lead` | `fk_lead__parsed_party` | `(tenant_id, parsed_party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `lead.lead` | `fk_lead__ingress_completed_by_appointment` | `(tenant_id, ingress_completed_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `lead.lead_assignment` | `fk_lead_assignment__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `lead.lead_assignment` | `fk_lead_assignment__owner_appointment` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `lead.lead_contact_result` | `fk_lead_contact_result__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `lead.lead_contact_result` | `fk_lead_contact_result__contact_task` | `(tenant_id, contact_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `lead.lead_contact_result` | `fk_lead_contact_result__evidence_submission` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__source_lead` | `(tenant_id, source_lead_id)` | `lead.lead` | `(tenant_id, lead_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__source_assignment` | `(tenant_id, source_assignment_id)` | `lead.lead_assignment` | `(tenant_id, lead_assignment_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__owner_appointment` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__source_contact_result` | `(tenant_id, source_contact_result_id)` | `lead.lead_contact_result` | `(tenant_id, lead_contact_result_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__assignment_path` | `(tenant_id, source_assignment_id, source_lead_id, owner_appointment_id)` | `lead.lead_assignment` | `(tenant_id, lead_assignment_id, lead_id, owner_appointment_id)` | 否 |
| `opportunity.opportunity` | `fk_opportunity__contact_path` | `(tenant_id, source_contact_result_id, source_lead_id, source_assignment_id)` | `lead.lead_contact_result` | `(tenant_id, lead_contact_result_id, lead_id, lead_assignment_id)` | 否 |
| `opportunity.opportunity_participation` | `fk_opportunity_participation__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.opportunity_participation` | `fk_opportunity_participation__party` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `opportunity.opportunity_progress` | `fk_opportunity_progress__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_revision` | `fk_quote_revision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_revision` | `fk_quote_revision__action_draft` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `opportunity.quote_revision` | `fk_quote_revision__creator` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_service_scope` | `fk_quote_service_scope__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_line` | `fk_quote_line__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_payment_term` | `fk_quote_payment_term__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_issue` | `fk_quote_issue__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_issue` | `fk_quote_issue__external_action` | `(tenant_id, external_action_id)` | `external_action.external_action` | `(tenant_id, external_action_id)` | 否 |
| `opportunity.quote_issue` | `fk_quote_issue__provider_inbox` | `(tenant_id, provider_inbox_id)` | `external_action.provider_inbox` | `(tenant_id, provider_inbox_id)` | 否 |
| `opportunity.quote_response` | `fk_quote_response__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_response` | `fk_quote_response__provider_inbox` | `(tenant_id, provider_inbox_id)` | `external_action.provider_inbox` | `(tenant_id, provider_inbox_id)` | 否 |
| `opportunity.quote_response` | `fk_quote_response__evidence_submission` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `opportunity.quote_response` | `fk_quote_response__recorder` | `(tenant_id, recorded_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.owner_exception` | `fk_owner_exception__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.owner_exception` | `fk_owner_exception__frozen_owner_appointment_id` | `(tenant_id, frozen_owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.owner_exception` | `fk_owner_exception__current_owner_appointment_id` | `(tenant_id, current_owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.owner_exception` | `fk_owner_exception__task_occurrence_id` | `(tenant_id, task_occurrence_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 是 |
| `opportunity.owner_exception` | `fk_owner_exception__wait_receipt_id` | `(tenant_id, wait_receipt_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 是 |
| `opportunity.owner_exception_disposition` | `fk_owner_exception_disposition__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.owner_exception_disposition` | `fk_owner_exception_disposition__actor_appointment_id` | `(tenant_id, actor_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.owner_exception_disposition` | `fk_owner_exception_disposition__receiver_appointment_id` | `(tenant_id, receiver_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__from_appointment_id` | `(tenant_id, from_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__to_appointment_id` | `(tenant_id, to_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__actor_appointment_id` | `(tenant_id, actor_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__old_task_occurrence_id` | `(tenant_id, old_task_occurrence_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__new_task_occurrence_id` | `(tenant_id, new_task_occurrence_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 是 |
| `opportunity.responsibility_handoff` | `fk_responsibility_handoff__original_wait_receipt_id` | `(tenant_id, original_wait_receipt_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 是 |
| `opportunity.closure` | `fk_closure__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.closure` | `fk_closure__task_occurrence_id` | `(tenant_id, task_occurrence_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.closure` | `fk_closure__wait_receipt_id` | `(tenant_id, wait_receipt_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 否 |
| `opportunity.closure` | `fk_closure__closed_by_appointment_id` | `(tenant_id, closed_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.customer_requirement_draft` | `fk_customer_requirement_draft__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.customer_requirement_draft` | `fk_customer_requirement_draft__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.customer_requirement_confirmation` | `fk_customer_requirement_confirmation__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.customer_requirement_confirmation` | `fk_customer_requirement_confirmation__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.customer_requirement_participant` | `fk_customer_requirement_participant__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.customer_requirement_participant` | `fk_customer_requirement_participant__party_id` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `opportunity.customer_requirement_participant` | `fk_customer_requirement_participant__profile_version_id` | `(tenant_id, profile_version_id)` | `party.profile_version` | `(tenant_id, profile_version_id)` | 否 |
| `opportunity.customer_requirement_draft_party` | `fk_customer_requirement_draft_party__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.customer_requirement_draft_party` | `fk_customer_requirement_draft_party__party_id` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__upload_basis_id` | `(tenant_id, upload_basis_id)` | `evidence.material_upload_basis` | `(tenant_id, material_upload_basis_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__upload_session_id` | `(tenant_id, upload_session_id)` | `evidence.upload_session` | `(tenant_id, upload_session_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__received_source_object_id` | `(tenant_id, received_source_object_id)` | `evidence.received_source_object` | `(tenant_id, received_source_object_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__evidence_submission_id` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__evidence_binding_id` | `(tenant_id, evidence_binding_id)` | `evidence.evidence_binding` | `(tenant_id, evidence_binding_id)` | 否 |
| `opportunity.material_version` | `fk_material_version__received_by_appointment_id` | `(tenant_id, received_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_draft` | `fk_quote_draft__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_draft` | `fk_quote_draft__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_package_basis` | `fk_quote_package_basis__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_approval_policy` | `fk_quote_approval_policy__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_approval_policy` | `fk_quote_approval_policy__organization_unit_id` | `(tenant_id, organization_unit_id)` | `identity.organization_unit` | `(tenant_id, organization_unit_id)` | 否 |
| `opportunity.quote_approval_policy_signer` | `fk_quote_approval_policy_signer__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_approval_policy_signer` | `fk_quote_approval_policy_signer__appointment_id` | `(tenant_id, appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_approval_request` | `fk_quote_approval_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_approval_request` | `fk_quote_approval_request__requested_by` | `(tenant_id, requested_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_approval_member` | `fk_quote_approval_member__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_approval_member` | `fk_quote_approval_member__appointment_id` | `(tenant_id, appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_approval_member` | `fk_quote_approval_member__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_approval_decision` | `fk_quote_approval_decision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_manual_delivery` | `fk_quote_manual_delivery__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_manual_delivery` | `fk_quote_manual_delivery__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_response_basis` | `fk_quote_response_basis__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.contract_preparation_source` | `fk_contract_preparation_source__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_workflow` | `fk_quote_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_workflow` | `fk_quote_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_workflow` | `fk_quote_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_workflow` | `fk_quote_workflow__prior_task_id` | `(tenant_id, prior_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_preparation_intent` | `fk_quote_preparation_intent__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_preparation_intent` | `fk_quote_preparation_intent__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_preparation_intent` | `fk_quote_preparation_intent__prior_task_id` | `(tenant_id, prior_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_preparation_intent` | `fk_quote_preparation_intent__requested_by` | `(tenant_id, requested_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.followup_attempt` | `fk_followup_attempt__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.followup_attempt` | `fk_followup_attempt__prior_task_id` | `(tenant_id, prior_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.followup_attempt` | `fk_followup_attempt__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.followup_attempt` | `fk_followup_attempt__prior_wait_id` | `(tenant_id, prior_wait_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 否 |
| `opportunity.followup_attempt` | `fk_followup_attempt__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_termination` | `fk_quote_termination__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_termination` | `fk_quote_termination__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `opportunity.quote_termination_task` | `fk_quote_termination_task__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `opportunity.quote_termination_task` | `fk_quote_termination_task__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `opportunity.quote_termination_task` | `fk_quote_termination_task__wait_id` | `(tenant_id, wait_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 否 |
| `conflict.conflict_review` | `fk_conflict_review__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `conflict.conflict_review_party` | `fk_conflict_review_party__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `conflict.conflict_review_party` | `fk_conflict_review_party__party` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `conflict.conflict_finding` | `fk_conflict_finding__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `conflict.conflict_finding` | `fk_conflict_finding__evidence_submission` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.contract` | `fk_contract__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract` | `fk_contract__opportunity` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.contract` | `fk_contract__accepted_quote_response` | `(tenant_id, accepted_quote_response_id)` | `opportunity.quote_response` | `(tenant_id, quote_response_id)` | 否 |
| `contract.contract` | `fk_contract__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__action_draft` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__quote_revision` | `(tenant_id, source_quote_revision_id)` | `opportunity.quote_revision` | `(tenant_id, quote_revision_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__quote_response` | `(tenant_id, source_quote_response_id)` | `opportunity.quote_response` | `(tenant_id, quote_response_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__body_evidence` | `(tenant_id, body_evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__pre_contract_review` | `(tenant_id, pre_contract_review_id)` | `conflict.conflict_review` | `(tenant_id, conflict_review_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__creator` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__customer_confirmation_id` | `(tenant_id, customer_confirmation_id)` | `opportunity.customer_requirement_confirmation` | `(tenant_id, customer_requirement_confirmation_id)` | 否 |
| `contract.contract_revision` | `fk_contract_revision__body_evidence_version_id` | `(tenant_id, body_evidence_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.contract_participation` | `fk_contract_participation__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_participation` | `fk_contract_participation__party` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `contract.contract_participation` | `fk_contract_participation__source_opportunity_participation` | `(tenant_id, source_opportunity_participation_id)` | `opportunity.opportunity_participation` | `(tenant_id, opportunity_participation_id)` | 否 |
| `contract.contract_fee_term` | `fk_contract_fee_term__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_fee_term` | `fk_contract_fee_term__source_quote_line` | `(tenant_id, source_quote_line_id)` | `opportunity.quote_line` | `(tenant_id, quote_line_id)` | 否 |
| `contract.payment_gate` | `fk_payment_gate__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.payment_gate` | `fk_payment_gate__risk_decision` | `(tenant_id, risk_decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `contract.signature_plan` | `fk_signature_plan__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_plan` | `fk_signature_plan__signer_party` | `(tenant_id, signer_party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__evidence` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__external_action` | `(tenant_id, external_action_id)` | `external_action.external_action` | `(tenant_id, external_action_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__provider_inbox` | `(tenant_id, provider_inbox_id)` | `external_action.provider_inbox` | `(tenant_id, provider_inbox_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__signer_party` | `(tenant_id, signer_party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `contract.contract_signature` | `fk_contract_signature__revoker` | `(tenant_id, revoked_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.contract_execution` | `fk_contract_execution__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_execution` | `fk_contract_execution__archive_evidence` | `(tenant_id, archive_evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.contract_execution` | `fk_contract_execution__executor` | `(tenant_id, executed_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.payment_confirmation` | `fk_payment_confirmation__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.payment_confirmation` | `fk_payment_confirmation__external_action` | `(tenant_id, external_action_id)` | `external_action.external_action` | `(tenant_id, external_action_id)` | 否 |
| `contract.payment_confirmation` | `fk_payment_confirmation__provider_inbox` | `(tenant_id, provider_inbox_id)` | `external_action.provider_inbox` | `(tenant_id, provider_inbox_id)` | 否 |
| `contract.payment_confirmation` | `fk_payment_confirmation__evidence` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.payment_confirmation` | `fk_payment_confirmation__recorder` | `(tenant_id, recorded_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.contract_termination` | `fk_contract_termination__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.contract_termination` | `fk_contract_termination__decision` | `(tenant_id, decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `contract.contract_termination` | `fk_contract_termination__evidence` | `(tenant_id, evidence_submission_id)` | `evidence.evidence_submission` | `(tenant_id, evidence_submission_id)` | 否 |
| `contract.contract_termination` | `fk_contract_termination__terminator` | `(tenant_id, terminated_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_request` | `fk_preparation_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.preparation_request` | `fk_preparation_request__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.preparation_request` | `fk_preparation_request__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_request` | `fk_preparation_request__customer_confirmation_id` | `(tenant_id, customer_confirmation_id)` | `opportunity.customer_requirement_confirmation` | `(tenant_id, customer_requirement_confirmation_id)` | 否 |
| `contract.preparation_decision` | `fk_preparation_decision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.preparation_decision` | `fk_preparation_decision__decided_by_appointment_id` | `(tenant_id, decided_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.approval_policy` | `fk_approval_policy__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.approval_policy` | `fk_approval_policy__organization_unit_id` | `(tenant_id, organization_unit_id)` | `identity.organization_unit` | `(tenant_id, organization_unit_id)` | 否 |
| `contract.approval_policy_member` | `fk_approval_policy_member__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.approval_policy_member` | `fk_approval_policy_member__appointment_id` | `(tenant_id, appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__prior_task_id` | `(tenant_id, prior_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.preparation_workflow` | `fk_preparation_workflow__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.revision_approval_request` | `fk_revision_approval_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_approval_request` | `fk_revision_approval_request__requested_by_appointment_id` | `(tenant_id, requested_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_draft` | `fk_preparation_draft__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.preparation_draft` | `fk_preparation_draft__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.preparation_draft` | `fk_preparation_draft__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.preparation_draft` | `fk_preparation_draft__customer_confirmation_id` | `(tenant_id, customer_confirmation_id)` | `opportunity.customer_requirement_confirmation` | `(tenant_id, customer_requirement_confirmation_id)` | 否 |
| `contract.preparation_draft` | `fk_preparation_draft__source_quote_response_id` | `(tenant_id, source_quote_response_id)` | `opportunity.quote_response` | `(tenant_id, quote_response_id)` | 否 |
| `contract.template_version` | `fk_template_version__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.template_version` | `fk_template_version__evidence_version_id` | `(tenant_id, evidence_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.template_version` | `fk_template_version__approved_by_appointment_id` | `(tenant_id, approved_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.clause_version` | `fk_clause_version__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.clause_version` | `fk_clause_version__evidence_version_id` | `(tenant_id, evidence_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.clause_version` | `fk_clause_version__approved_by_appointment_id` | `(tenant_id, approved_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.revision_clause` | `fk_revision_clause__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_review_request` | `fk_revision_review_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_review_request` | `fk_revision_review_request__requested_by_appointment_id` | `(tenant_id, requested_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.revision_review_decision` | `fk_revision_review_decision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_review_decision` | `fk_revision_review_decision__conflict_review_id` | `(tenant_id, conflict_review_id)` | `conflict.conflict_review` | `(tenant_id, conflict_review_id)` | 否 |
| `contract.revision_review_decision` | `fk_revision_review_decision__decided_by_appointment_id` | `(tenant_id, decided_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.revision_review_binding` | `fk_revision_review_binding__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_review_binding` | `fk_revision_review_binding__conflict_review_id` | `(tenant_id, conflict_review_id)` | `conflict.conflict_review` | `(tenant_id, conflict_review_id)` | 否 |
| `contract.revision_approval_requirement` | `fk_revision_approval_requirement__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_approval_requirement` | `fk_revision_approval_requirement__approver_appointment_id` | `(tenant_id, approver_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.revision_approval_decision` | `fk_revision_approval_decision__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.revision_approval_decision` | `fk_revision_approval_decision__decided_by_appointment_id` | `(tenant_id, decided_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_readiness` | `fk_signature_readiness__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.template_signing_party` | `fk_template_signing_party__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.template_signing_party` | `fk_template_signing_party__party_id` | `(tenant_id, party_id)` | `party.party` | `(tenant_id, party_id)` | 否 |
| `contract.template_signing_party` | `fk_template_signing_party__profile_version_id` | `(tenant_id, profile_version_id)` | `party.profile_version` | `(tenant_id, profile_version_id)` | 否 |
| `contract.template_signing_party` | `fk_template_signing_party__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_arrangement` | `fk_signature_arrangement__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_arrangement` | `fk_signature_arrangement__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_arrangement` | `fk_signature_arrangement__registered_by_appointment_id` | `(tenant_id, registered_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_draft` | `fk_signature_draft__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_draft` | `fk_signature_draft__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_draft` | `fk_signature_draft__saved_by_appointment_id` | `(tenant_id, saved_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_submission` | `fk_signature_submission__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_submission` | `fk_signature_submission__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_submission` | `fk_signature_submission__submitted_by_appointment_id` | `(tenant_id, submitted_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_submission` | `fk_signature_submission__material_version_id` | `(tenant_id, material_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.signature_submission` | `fk_signature_submission__authority_material_version_id` | `(tenant_id, authority_material_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.signature_verification` | `fk_signature_verification__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_verification` | `fk_signature_verification__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_verification` | `fk_signature_verification__verified_by_appointment_id` | `(tenant_id, verified_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_archive` | `fk_signature_archive__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_archive` | `fk_signature_archive__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_archive` | `fk_signature_archive__material_version_id` | `(tenant_id, material_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.signature_archive` | `fk_signature_archive__archived_by_appointment_id` | `(tenant_id, archived_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_revision_return` | `fk_signature_revision_return__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_revision_return` | `fk_signature_revision_return__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_revision_return` | `fk_signature_revision_return__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__created_by_appointment_id` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.signature_workflow` | `fk_signature_workflow__prior_task_id` | `(tenant_id, prior_task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.signature_handoff` | `fk_signature_handoff__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.signature_handoff` | `fk_signature_handoff__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.negotiation_disposition` | `fk_negotiation_disposition__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.negotiation_disposition` | `fk_negotiation_disposition__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.negotiation_disposition` | `fk_negotiation_disposition__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.termination_review_assignment` | `fk_termination_review_assignment__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.termination_review_assignment` | `fk_termination_review_assignment__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.termination_review_assignment` | `fk_termination_review_assignment__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.termination_review_assignment` | `fk_termination_review_assignment__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.negotiation_cancelled_task` | `fk_negotiation_cancelled_task__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.negotiation_cancelled_task` | `fk_negotiation_cancelled_task__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.negotiation_cancelled_task` | `fk_negotiation_cancelled_task__wait_id` | `(tenant_id, wait_id)` | `responsibility.wait_receipt` | `(tenant_id, wait_receipt_id)` | 否 |
| `contract.execution_verification` | `fk_execution_verification__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.execution_verification` | `fk_execution_verification__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.execution_verification` | `fk_execution_verification__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.execution_workflow` | `fk_execution_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.execution_workflow` | `fk_execution_workflow__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.execution_workflow` | `fk_execution_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.execution_workflow` | `fk_execution_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.execution_workflow` | `fk_execution_workflow__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.payment_request` | `fk_payment_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.payment_request` | `fk_payment_request__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.payment_request` | `fk_payment_request__material_version_id` | `(tenant_id, material_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.payment_request` | `fk_payment_request__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.payment_workflow` | `fk_payment_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.payment_workflow` | `fk_payment_workflow__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.payment_workflow` | `fk_payment_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.payment_workflow` | `fk_payment_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `contract.payment_workflow` | `fk_payment_workflow__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `contract.payment_review` | `fk_payment_review__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `contract.payment_review` | `fk_payment_review__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `contract.payment_review` | `fk_payment_review__material_version_id` | `(tenant_id, material_version_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `contract.payment_review` | `fk_payment_review__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__opportunity` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__contract` | `(tenant_id, contract_id)` | `contract.contract` | `(tenant_id, contract_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__contract_execution` | `(tenant_id, contract_execution_id)` | `contract.contract_execution` | `(tenant_id, contract_execution_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__from_org` | `(tenant_id, from_organization_unit_id)` | `identity.organization_unit` | `(tenant_id, organization_unit_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__to_org` | `(tenant_id, to_organization_unit_id)` | `identity.organization_unit` | `(tenant_id, organization_unit_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__accept_decision` | `(tenant_id, accept_decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__creator` | `(tenant_id, created_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.transfer_request` | `fk_transfer_request__contract_path` | `(tenant_id, contract_id, opportunity_id, contract_execution_id)` | `contract.contract` | `(tenant_id, contract_id, opportunity_id, contract_execution_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__submission_task` | `(tenant_id, submission_task_occurrence_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__action_draft` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__pre_transfer_review` | `(tenant_id, pre_transfer_review_id)` | `conflict.conflict_review` | `(tenant_id, conflict_review_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__previous_return_decision` | `(tenant_id, previous_return_decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `transfer.transfer_snapshot` | `fk_transfer_snapshot__submitter` | `(tenant_id, submitted_by_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.transfer_return_item` | `fk_transfer_return_item__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.transfer_return_item` | `fk_transfer_return_item__return_decision` | `(tenant_id, return_decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `transfer.workflow` | `fk_workflow__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.workflow` | `fk_workflow__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.workflow` | `fk_workflow__owner_appointment_id` | `(tenant_id, owner_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.workflow` | `fk_workflow__task_id` | `(tenant_id, task_id)` | `responsibility.task_occurrence` | `(tenant_id, task_occurrence_id)` | 否 |
| `transfer.workflow` | `fk_workflow__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.submission` | `fk_submission__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.submission` | `fk_submission__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.submission` | `fk_submission__contract_revision_id` | `(tenant_id, contract_revision_id)` | `contract.contract_revision` | `(tenant_id, contract_revision_id)` | 否 |
| `transfer.submission` | `fk_submission__customer_confirmation_id` | `(tenant_id, customer_confirmation_id)` | `opportunity.customer_requirement_confirmation` | `(tenant_id, customer_requirement_confirmation_id)` | 否 |
| `transfer.submission` | `fk_submission__client_identity_material_id` | `(tenant_id, client_identity_material_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `transfer.submission` | `fk_submission__signature_archive_material_id` | `(tenant_id, signature_archive_material_id)` | `opportunity.material_version` | `(tenant_id, material_version_id)` | 否 |
| `transfer.submission` | `fk_submission__confirmed_action_draft_id` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `transfer.submission` | `fk_submission__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.review` | `fk_review__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.review` | `fk_review__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.review` | `fk_review__conflict_review_id` | `(tenant_id, conflict_review_id)` | `conflict.conflict_review` | `(tenant_id, conflict_review_id)` | 否 |
| `transfer.review` | `fk_review__confirmed_action_draft_id` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `transfer.review` | `fk_review__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.review_return_item` | `fk_review_return_item__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.review_return_item` | `fk_review_return_item__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.intake` | `fk_intake__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.intake` | `fk_intake__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.intake` | `fk_intake__decision_record_id` | `(tenant_id, decision_record_id)` | `responsibility.decision_record` | `(tenant_id, decision_record_id)` | 否 |
| `transfer.intake` | `fk_intake__confirmed_action_draft_id` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `transfer.intake` | `fk_intake__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.classification` | `fk_classification__tenant` | `(tenant_id)` | `identity.tenant` | `(tenant_id)` | 否 |
| `transfer.classification` | `fk_classification__opportunity_id` | `(tenant_id, opportunity_id)` | `opportunity.opportunity` | `(tenant_id, opportunity_id)` | 否 |
| `transfer.classification` | `fk_classification__recipient_appointment_id` | `(tenant_id, recipient_appointment_id)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |
| `transfer.classification` | `fk_classification__confirmed_action_draft_id` | `(tenant_id, confirmed_action_draft_id)` | `responsibility.action_draft` | `(tenant_id, action_draft_id)` | 否 |
| `transfer.classification` | `fk_classification__recorded_by` | `(tenant_id, recorded_by)` | `identity.appointment` | `(tenant_id, appointment_id)` | 否 |

## 类型化准确引用矩阵

这些关系不伪造物理外键；类型、标识与revision/hash选择器由静态允许列表、同租户Resolver和命令提交前重验共同保证。

| 表 | 引用槽 | 可空 | 物理列 | 允许目标类型 |
|---|---|---:|---|---|
| `identity.object_access_grant` | `object_subject` | 否 | `object_subject_type, object_subject_id, object_subject_revision, object_subject_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action, opportunity.closure, party.profile_version, opportunity.customer_requirement_draft, opportunity.customer_requirement_confirmation, opportunity.customer_requirement_participant, opportunity.customer_requirement_draft_party, evidence.material_upload_basis, evidence.material_upload_check, opportunity.material_version, opportunity.quote_draft, opportunity.quote_package_basis, opportunity.quote_approval_policy, opportunity.quote_approval_policy_signer, opportunity.quote_approval_request, opportunity.quote_approval_member, opportunity.quote_approval_decision, opportunity.quote_manual_delivery, opportunity.quote_response_basis, opportunity.contract_preparation_source, opportunity.quote_workflow, contract.approval_policy, contract.approval_policy_member, contract.preparation_workflow, contract.revision_approval_request, contract.preparation_draft, contract.template_version, contract.clause_version, contract.revision_clause, contract.revision_review_request, contract.revision_review_decision, contract.revision_review_binding, contract.revision_approval_requirement, contract.revision_approval_decision, contract.signature_readiness, contract.preparation_request, contract.preparation_decision, contract.signature_arrangement, contract.signature_draft, contract.signature_submission, contract.signature_verification, contract.signature_archive, contract.signature_revision_return, contract.signature_workflow, contract.signature_handoff, contract.template_signing_party, transfer.workflow, transfer.submission, transfer.review, transfer.review_return_item, transfer.intake, transfer.classification` |
| `audit.audit_entry` | `subject` | 否 | `subject_type, subject_id, subject_revision, subject_hash` | `identity.tenant, identity.principal, identity.organization_unit, identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, audit.audit_entry, responsibility.task_occurrence, responsibility.decision_record, responsibility.wait_receipt, responsibility.action_draft, execution.command_execution_slot, execution.command_receipt, execution.domain_event, execution.domain_event_outbox, external_action.external_action, external_action.external_action_outbox, external_action.provider_inbox, evidence.upload_session, evidence.received_source_object, evidence.evidence_submission, evidence.evidence_binding, party.party, lead.lead, lead.lead_assignment, lead.lead_contact_result, opportunity.opportunity, opportunity.opportunity_participation, opportunity.opportunity_progress, opportunity.quote_revision, opportunity.quote_service_scope, opportunity.quote_line, opportunity.quote_payment_term, opportunity.quote_issue, opportunity.quote_response, conflict.conflict_review, conflict.conflict_review_party, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.contract_participation, contract.contract_fee_term, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, opportunity.owner_exception, opportunity.owner_exception_disposition, opportunity.responsibility_handoff, opportunity.closure, party.profile_version, opportunity.customer_requirement_draft, opportunity.customer_requirement_confirmation, opportunity.customer_requirement_participant, opportunity.customer_requirement_draft_party, evidence.material_upload_basis, evidence.material_upload_check, opportunity.material_version, opportunity.quote_draft, opportunity.quote_package_basis, opportunity.quote_approval_policy, opportunity.quote_approval_policy_signer, opportunity.quote_approval_request, opportunity.quote_approval_member, opportunity.quote_approval_decision, opportunity.quote_manual_delivery, opportunity.quote_response_basis, opportunity.contract_preparation_source, opportunity.quote_workflow, contract.approval_policy, contract.approval_policy_member, contract.preparation_workflow, contract.revision_approval_request, contract.preparation_draft, contract.template_version, contract.clause_version, contract.revision_clause, contract.revision_review_request, contract.revision_review_decision, contract.revision_review_binding, contract.revision_approval_requirement, contract.revision_approval_decision, contract.signature_readiness, contract.preparation_request, contract.preparation_decision, contract.signature_arrangement, contract.signature_draft, contract.signature_submission, contract.signature_verification, contract.signature_archive, contract.signature_revision_return, contract.signature_workflow, contract.signature_handoff, contract.template_signing_party, transfer.workflow, transfer.submission, transfer.review, transfer.review_return_item, transfer.intake, transfer.classification, identity.appointment_role` |
| `audit.audit_entry` | `correction_target` | 是 | `correction_target_type, correction_target_id, correction_target_revision, correction_target_hash` | `audit.audit_entry` |
| `audit.audit_entry` | `authorization_fact` | 是 | `authorization_fact_type, authorization_fact_id, authorization_fact_revision, authorization_fact_hash` | `identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, responsibility.decision_record` |
| `responsibility.task_occurrence` | `subject` | 否 | `subject_type, subject_id, subject_revision, subject_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action` |
| `responsibility.task_occurrence` | `completion_fact` | 是 | `completion_fact_type, completion_fact_id, completion_fact_revision, completion_fact_hash` | `identity.tenant, identity.principal, identity.organization_unit, identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, responsibility.task_occurrence, responsibility.decision_record, responsibility.wait_receipt, responsibility.action_draft, external_action.external_action, external_action.provider_inbox, evidence.upload_session, evidence.received_source_object, evidence.evidence_submission, evidence.evidence_binding, party.party, lead.lead, lead.lead_assignment, lead.lead_contact_result, opportunity.opportunity, opportunity.opportunity_participation, opportunity.opportunity_progress, opportunity.quote_revision, opportunity.quote_service_scope, opportunity.quote_line, opportunity.quote_payment_term, opportunity.quote_issue, opportunity.quote_response, conflict.conflict_review, conflict.conflict_review_party, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.contract_participation, contract.contract_fee_term, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, opportunity.quote_approval_request, opportunity.quote_approval_decision, contract.preparation_request, contract.preparation_decision, contract.revision_review_request, contract.revision_review_decision, contract.revision_approval_request, contract.revision_approval_decision, contract.signature_readiness, contract.signature_arrangement, contract.signature_submission, contract.signature_verification, contract.signature_archive, contract.signature_revision_return, contract.signature_handoff, transfer.submission, transfer.review, transfer.intake, transfer.classification` |
| `responsibility.task_occurrence` | `responsibility_basis` | 是 | `responsibility_basis_type, responsibility_basis_id, responsibility_basis_revision, responsibility_basis_hash` | `opportunity.opportunity, opportunity.responsibility_handoff` |
| `responsibility.task_occurrence` | `cancellation_fact` | 是 | `cancellation_fact_type, cancellation_fact_id, cancellation_fact_revision, cancellation_fact_hash` | `opportunity.responsibility_handoff, opportunity.closure` |
| `responsibility.decision_record` | `decision_subject` | 否 | `decision_subject_type, decision_subject_id, decision_subject_revision, decision_subject_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action` |
| `responsibility.wait_receipt` | `awaited_fact` | 是 | `awaited_fact_type, awaited_fact_id, awaited_fact_revision, awaited_fact_hash` | `identity.tenant, identity.principal, identity.organization_unit, identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, responsibility.task_occurrence, responsibility.decision_record, responsibility.wait_receipt, responsibility.action_draft, external_action.external_action, external_action.provider_inbox, evidence.upload_session, evidence.received_source_object, evidence.evidence_submission, evidence.evidence_binding, party.party, lead.lead, lead.lead_assignment, lead.lead_contact_result, opportunity.opportunity, opportunity.opportunity_participation, opportunity.opportunity_progress, opportunity.quote_revision, opportunity.quote_service_scope, opportunity.quote_line, opportunity.quote_payment_term, opportunity.quote_issue, opportunity.quote_response, conflict.conflict_review, conflict.conflict_review_party, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.contract_participation, contract.contract_fee_term, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item` |
| `execution.command_receipt` | `result_fact` | 是 | `result_fact_type, result_fact_id, result_fact_revision, result_fact_hash` | `identity.tenant, identity.principal, identity.organization_unit, identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, responsibility.task_occurrence, responsibility.decision_record, responsibility.wait_receipt, responsibility.action_draft, external_action.external_action, external_action.provider_inbox, evidence.upload_session, evidence.received_source_object, evidence.evidence_submission, evidence.evidence_binding, party.party, lead.lead, lead.lead_assignment, lead.lead_contact_result, opportunity.opportunity, opportunity.opportunity_participation, opportunity.opportunity_progress, opportunity.quote_revision, opportunity.quote_service_scope, opportunity.quote_line, opportunity.quote_payment_term, opportunity.quote_issue, opportunity.quote_response, conflict.conflict_review, conflict.conflict_review_party, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.contract_participation, contract.contract_fee_term, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, opportunity.owner_exception, opportunity.owner_exception_disposition, opportunity.responsibility_handoff, opportunity.closure, party.profile_version, opportunity.customer_requirement_draft, opportunity.customer_requirement_confirmation, opportunity.customer_requirement_participant, opportunity.customer_requirement_draft_party, evidence.material_upload_basis, evidence.material_upload_check, opportunity.material_version, opportunity.quote_draft, opportunity.quote_package_basis, opportunity.quote_approval_policy, opportunity.quote_approval_policy_signer, opportunity.quote_approval_request, opportunity.quote_approval_member, opportunity.quote_approval_decision, opportunity.quote_manual_delivery, opportunity.quote_response_basis, opportunity.contract_preparation_source, opportunity.quote_workflow, contract.approval_policy, contract.approval_policy_member, contract.preparation_workflow, contract.revision_approval_request, contract.preparation_draft, contract.template_version, contract.clause_version, contract.revision_clause, contract.revision_review_request, contract.revision_review_decision, contract.revision_review_binding, contract.revision_approval_requirement, contract.revision_approval_decision, contract.signature_readiness, contract.preparation_request, contract.preparation_decision, contract.signature_arrangement, contract.signature_draft, contract.signature_submission, contract.signature_verification, contract.signature_archive, contract.signature_revision_return, contract.signature_workflow, contract.signature_handoff, contract.template_signing_party, transfer.workflow, transfer.submission, transfer.review, transfer.review_return_item, transfer.intake, transfer.classification, identity.appointment_role` |
| `execution.domain_event` | `source_fact` | 否 | `source_fact_type, source_fact_id, source_fact_revision, source_fact_hash` | `identity.tenant, identity.principal, identity.organization_unit, identity.appointment, identity.authority_grant, identity.delegation_grant, identity.object_access_grant, responsibility.task_occurrence, responsibility.decision_record, responsibility.wait_receipt, responsibility.action_draft, external_action.external_action, external_action.provider_inbox, evidence.upload_session, evidence.received_source_object, evidence.evidence_submission, evidence.evidence_binding, party.party, lead.lead, lead.lead_assignment, lead.lead_contact_result, opportunity.opportunity, opportunity.opportunity_participation, opportunity.opportunity_progress, opportunity.quote_revision, opportunity.quote_service_scope, opportunity.quote_line, opportunity.quote_payment_term, opportunity.quote_issue, opportunity.quote_response, conflict.conflict_review, conflict.conflict_review_party, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.contract_participation, contract.contract_fee_term, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, opportunity.owner_exception, opportunity.owner_exception_disposition, opportunity.responsibility_handoff, opportunity.closure, party.profile_version, opportunity.customer_requirement_draft, opportunity.customer_requirement_confirmation, opportunity.customer_requirement_participant, opportunity.customer_requirement_draft_party, evidence.material_upload_basis, evidence.material_upload_check, opportunity.material_version, opportunity.quote_draft, opportunity.quote_package_basis, opportunity.quote_approval_policy, opportunity.quote_approval_policy_signer, opportunity.quote_approval_request, opportunity.quote_approval_member, opportunity.quote_approval_decision, opportunity.quote_manual_delivery, opportunity.quote_response_basis, opportunity.contract_preparation_source, opportunity.quote_workflow, contract.approval_policy, contract.approval_policy_member, contract.preparation_workflow, contract.revision_approval_request, contract.preparation_draft, contract.template_version, contract.clause_version, contract.revision_clause, contract.revision_review_request, contract.revision_review_decision, contract.revision_review_binding, contract.revision_approval_requirement, contract.revision_approval_decision, contract.signature_readiness, contract.preparation_request, contract.preparation_decision, contract.signature_arrangement, contract.signature_draft, contract.signature_submission, contract.signature_verification, contract.signature_archive, contract.signature_revision_return, contract.signature_workflow, contract.signature_handoff, contract.template_signing_party, transfer.workflow, transfer.submission, transfer.review, transfer.review_return_item, transfer.intake, transfer.classification` |
| `external_action.external_action` | `subject` | 否 | `subject_type, subject_id, subject_revision, subject_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action` |
| `external_action.external_action` | `resolution_source` | 是 | `resolution_source_type, resolution_source_id, resolution_source_revision, resolution_source_hash` | `external_action.provider_inbox, responsibility.decision_record` |
| `evidence.upload_session` | `target` | 否 | `target_type, target_id, target_revision, target_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action` |
| `evidence.evidence_binding` | `target` | 否 | `target_type, target_id, target_revision, target_hash` | `party.party, lead.lead, lead.lead_assignment, opportunity.opportunity, opportunity.opportunity_participation, opportunity.quote_revision, opportunity.quote_issue, conflict.conflict_review, conflict.conflict_finding, contract.contract, contract.contract_revision, contract.payment_gate, contract.signature_plan, contract.contract_signature, contract.contract_execution, contract.payment_confirmation, contract.contract_termination, transfer.transfer_request, transfer.transfer_snapshot, transfer.transfer_return_item, evidence.evidence_submission, evidence.evidence_binding, responsibility.task_occurrence, responsibility.decision_record, external_action.external_action` |
| `opportunity.opportunity_progress` | `source_fact` | 是 | `source_fact_type, source_fact_id, source_fact_revision, source_fact_hash` | `lead.lead_contact_result, opportunity.quote_issue, opportunity.quote_response, responsibility.decision_record, external_action.external_action, external_action.provider_inbox, evidence.evidence_submission` |
| `opportunity.quote_issue` | `delivery_fact` | 否 | `delivery_fact_type, delivery_fact_id, delivery_fact_revision, delivery_fact_hash` | `external_action.external_action, external_action.provider_inbox, opportunity.quote_manual_delivery` |
| `conflict.conflict_review` | `trigger_fact` | 否 | `trigger_fact_type, trigger_fact_id, trigger_fact_revision, trigger_fact_hash` | `opportunity.quote_revision, opportunity.quote_response, contract.contract_revision, transfer.transfer_request, responsibility.action_draft, transfer.submission` |
| `conflict.conflict_review_party` | `source_item` | 否 | `source_item_type, source_item_id, source_item_revision, source_item_hash` | `party.party, opportunity.opportunity_participation, contract.contract_participation, transfer.transfer_snapshot, opportunity.customer_requirement_participant` |
| `conflict.conflict_finding` | `matched_fact` | 否 | `matched_fact_type, matched_fact_id, matched_fact_revision, matched_fact_hash` | `party.party, opportunity.opportunity_participation, contract.contract_participation, conflict.conflict_review_party, transfer.transfer_request` |
| `conflict.conflict_finding` | `source_fact` | 是 | `source_fact_type, source_fact_id, source_fact_revision, source_fact_hash` | `party.party, opportunity.opportunity_participation, contract.contract_participation, transfer.transfer_snapshot, evidence.evidence_submission` |
| `contract.contract` | `activation_source` | 是 | `activation_source_type, activation_source_id, activation_source_revision, activation_source_hash` | `contract.payment_gate, responsibility.decision_record` |
| `transfer.transfer_return_item` | `required_target` | 否 | `required_target_type, required_target_id, required_target_revision, required_target_hash` | `party.party, evidence.evidence_submission, evidence.evidence_binding, contract.contract_revision, conflict.conflict_review, transfer.transfer_request, transfer.transfer_snapshot` |

## 跨行守卫与运行时重验边界

| 不变量 | 数据库可证明部分 | 提交前必须由Owner/CommandRuntime重验部分 |
|---|---|---|
| 当前指针与单向槽位归属 | 七个归属trigger证明目标同租户同根；Lead Assignment和Opportunity Quote指针另以根锁守卫证明只沿直接后继前移，禁止清空、回拨或跳版 | 目标业务版本、当前有效性、授权和命令预期revision |
| Evidence晋级 | 根与三个成员反向延迟守卫共同证明Session、准确Opaque Key、PASSED SourceObject、唯一Submission及同目标同用途有效Binding只形成一条完整FINALIZED链 | 私有对象存储准确ObjectVersion、服务端Hash/真实类型/扫描可信性、最终四轴授权和Subject版本；下载仍须网关重验并先审计 |
| 类型化准确引用 | 行内CHECK证明type/id完整且revision或hash二选一 | 静态类型允许列表、同租户实体存在性、准确版本或摘要匹配 |
| 组织树与授权Scope | 复合FK和局部防自环证明直接邻接 | 当前组织树无长环、先限后允、同一Appointment路径及提交前四轴复验 |
| Task完成 | 行内单向槽位、物理FK与不可变结果事实 | 完成Fact类型属于Task冻结合同，且由准确Fact Owner同事务写入 |
| 命令原子结果 | Slot/Receipt/Event/Outbox的唯一性、终态和更新守卫 | SUCCEEDED新事实分支同事务写Slot、Fact/CAS、Audit、Event、Owner Outbox、Receipt；NO_CHANGE只写Slot、Audit及引用既有Fact的Receipt；REJECTED只写Slot、拒绝Audit及无结果Fact的Receipt；技术失败整体回滚 |
| Audit与披露 | AuditEntry不可变、CORRECTION单链、原始审计表与分类视图权限分离 | 业务写同事务追加；拒绝、敏感读取和导出先提交Audit再返回；分类视图之外仍实时四轴授权 |
| 不可变版本包 | 子项强引用准确QuoteRevision、ConflictReview或ContractRevision，旧版本不可更新 | 同一短事务写齐参与方、Scope/Line/PaymentTerm、Finding或合同子项并按规范算法复算集合与contentDigest |
| 外部效果 | Action/Outbox/Inbox的状态、唯一键、Nonce和不可变消息指纹 | Provider调用前先CAS持久化DISPATCHED；网络后UNKNOWN；下一attempt与旧UNKNOWN→FAILED在同一根锁事务；Outbox兼容、异Hash隔离、可信Provider、无副作用PROBE或授权Decision收敛 |
| 合同生命周期 | 锚点创建时证明销售来源Quote/Issue/最新ACCEPTED Response同链且有效；后续Revision只证明沿同一已消费历史来源和直接后继前移；approved=current，Execution回填当前批准版本并具备每个必需Plan的有效准确内容签署；Termination反向回填单向槽且禁止与首次激活同时形成；签署变动锁根且执行、取消或终止后封存 | 审查/审批槽完整，签署身份授权、印章、归档条件与最终四轴授权 |
| 付款与激活 | PaymentGate冻结准确Confirmation UUID集合和摘要，PaymentConfirmation保存正绝对金额、币种及可信来源 | Resolver逐项证明合同版本，按RECEIPT/REVERSAL/REFUND方向和单一币种聚合；Gate满足与DealActivated同事务 |
| 转案链与接收 | 复合FK和根锁延迟守卫证明直接前序、ReturnItem归属、当前叶无ReturnItem、来源Contract仍已执行/已激活/未终止，接收后禁止新增Snapshot/ReturnItem | RETURN/ACCEPT Decision须绑定准确叶、唯一REVIEW_TRANSFER Task及固定主命令；ReturnItem全集摘要、独立PRE_TRANSFER Review、材料/Evidence和接收授权全部仍有效 |
| 权限与部署 | 四个能力角色必须NOLOGIN且无父角色；物理ACL断言精确比对Schema USAGE、SELECT/INSERT、列UPDATE，禁止Owner/DDL/Delete/Truncate/Trigger/References/函数执行 | IaC只向API/Worker LOGIN NOINHERIT角色直授目标库CONNECT，并以SET LOCAL ROLE选择单一能力；迁移Owner仅由发布作业使用；制品与manifest匹配后才CAS切ACTIVE |

## 更新白名单

未列出的应用事实表均由触发器拒绝`UPDATE`和`DELETE`；列出的表仍必须通过CAS、write-once和状态转换守卫。

| 表 | 策略 | 允许更新列 |
|---|---|---|
| `identity.tenant` | `CONTROLLED` | `display_name, state, closed_at, revision` |
| `identity.principal` | `CONTROLLED` | `display_name, state, disabled_at, revision` |
| `identity.organization_unit` | `CONTROLLED` | `display_name, parent_organization_unit_id, state, closed_at, revision` |
| `identity.appointment` | `CONTROLLED` | `state, ended_at, revision` |
| `identity.authority_grant` | `CONTROLLED` | `state, revoked_at, revocation_reason_code, revision` |
| `identity.delegation_grant` | `CONTROLLED` | `state, revoked_at, revocation_reason_code, revision` |
| `identity.object_access_grant` | `CONTROLLED` | `state, revoked_at, revocation_reason_code, revision` |
| `identity.appointment_role` | `CONTROLLED` | `display_name, state, revision` |
| `responsibility.task_occurrence` | `CONTROLLED` | `state, completed_at, cancelled_at, cancellation_reason_code, completion_fact_type, completion_fact_id, completion_fact_revision, completion_fact_hash, revision, cancellation_fact_type, cancellation_fact_id, cancellation_fact_revision, cancellation_fact_hash` |
| `responsibility.action_draft` | `CONTROLLED` | `candidate_payload, candidate_payload_digest, last_edited_at, state, confirmed_by_appointment_id, confirmed_at, confirmed_payload_digest, revision` |
| `execution.domain_event_outbox` | `QUEUE` | `status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision` |
| `external_action.external_action` | `CONTROLLED` | `status, dispatched_at, provider_action_id, completed_at, result_code, result_digest, resolution_method_code, resolution_source_type, resolution_source_id, resolution_source_revision, resolution_source_hash, last_error_code, revision` |
| `external_action.external_action_outbox` | `QUEUE` | `status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision` |
| `evidence.upload_session` | `CONTROLLED` | `status, received_at, finalized_at, revision` |
| `evidence.evidence_binding` | `CONTROLLED` | `revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision` |
| `party.party` | `CONTROLLED` | `canonical_name, primary_identifier_type, primary_identifier_ciphertext, primary_identifier_hmac, status, merged_into_party_id, merged_at, revision` |
| `lead.lead` | `CONTROLLED` | `parsed_party_id, party_resolution_code, disposition_code, current_assignment_id, revision, ingress_completion_phone_ciphertext, ingress_completion_phone_hmac, ingress_completion_email_ciphertext, ingress_completion_email_hmac, ingress_completion_source_code, ingress_completion_source_summary_ciphertext, ingress_completed_by_appointment_id, ingress_completed_at, ingress_completion_digest` |
| `lead.lead_assignment` | `CONTROLLED` | `assignment_status_code, closed_at, close_reason_code, revision` |
| `opportunity.opportunity` | `CONTROLLED` | `current_quote_revision_id, close_outcome_code, closed_at, revision` |
| `opportunity.quote_issue` | `CONTROLLED` | `issue_status_code, revoked_at, revocation_reason_code, revision` |
| `opportunity.owner_exception` | `CONTROLLED` | `is_current` |
| `conflict.conflict_review` | `CONTROLLED` | `resolution_code, resolution_digest, resolved_at, revision` |
| `contract.contract` | `CONTROLLED` | `current_revision_id, approved_revision_id, contract_execution_id, deal_activated_at, activation_source_type, activation_source_id, activation_source_revision, activation_source_hash, contract_termination_id, revision, changed_at` |
| `contract.payment_gate` | `CONTROLLED` | `gate_state, satisfied_at, satisfaction_digest, payment_confirmation_ids, confirmation_set_digest, risk_decision_record_id, revision, changed_at` |
| `contract.contract_signature` | `CONTROLLED` | `revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision, changed_at` |
| `contract.contract_termination` | `CONTROLLED` | `refund_calculation_minor, refund_currency_code, refund_calculation_digest, refund_calculated_at, revision, changed_at` |
| `transfer.transfer_request` | `CONTROLLED` | `accepted_snapshot_id, accept_decision_record_id, matter_id, matter_no, matter_type_code, matter_capability_pack_code, matter_capability_pack_version, matter_created_at, revision, changed_at` |
| `platform_meta.deployment_state` | `CONTROLLED` | `operating_mode, active_release_digest, active_manifest_hash, schema_contract_version, revision, changed_at` |
| `platform_meta.r2_opportunity_checkpoint` | `CONTROLLED` | `checkpoint_body, revision, updated_at` |

## Platform Meta边界

- `platform_meta.deployment_state`是发布状态技术表，由受控发布作业使用迁移Owner维护且四个应用角色只读；迁移Owner不是应用启动角色，凭据边界由IaC验证。
- `platform_meta.flyway_schema_history`是第二张技术表，由固定版本Flyway独占创建和维护；本合同只补中文注释，不创建、修改或授权应用写入。
- 历史V840验证52张业务表和2张技术表；追加V890后为52张业务表和3张技术表，共55张。版本标识52-plus-2-r2-v3沿用合同谱系名称，不表示物理表数仍为54。
- `platform_meta.r2_opportunity_checkpoint`是新增Worker专用技术检查点，仅保存有界版本化技术状态；Worker只可读取、插入和更新正文/修订/数据库时间，其他应用角色无访问权。
