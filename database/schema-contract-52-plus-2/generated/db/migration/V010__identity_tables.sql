-- 身份域：保存租户、身份、组织、任职及授权锚点；不保存凭据，也不替代命令时动态授权复验。

CREATE TABLE identity.tenant (
    tenant_id uuid NOT NULL,
    tenant_code varchar(64) NOT NULL,
    display_name text NOT NULL,
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    closed_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_tenant PRIMARY KEY (tenant_id),
    CONSTRAINT uq_tenant__tenant_code UNIQUE (tenant_code),
    CONSTRAINT ck_tenant__state CHECK (state IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_tenant__closed_fields CHECK ((state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)),
    CONSTRAINT ck_tenant__revision_nonnegative CHECK (revision >= 0)
);

COMMENT ON TABLE identity.tenant IS 'Fact Owner：IdentityRuntime；一行代表一个租户边界根，由身份域拥有；仅显示名称、生命周期状态和关闭事实可CAS更新，不代表客户、律所法律主体或授权本身。';
COMMENT ON CONSTRAINT pk_tenant ON identity.tenant IS '主键：全局唯一标识一个租户边界。';
COMMENT ON INDEX identity.pk_tenant IS '主键：全局唯一标识一个租户边界。';
COMMENT ON COLUMN identity.tenant.tenant_id IS '租户标识：由应用生成的UUIDv7，是所有租户数据边界的根。';
COMMENT ON COLUMN identity.tenant.tenant_code IS '租户代码：外部配置使用的稳定非敏感代码，创建后不可修改。';
COMMENT ON COLUMN identity.tenant.display_name IS '租户显示名称：仅供界面展示，可受控修改，不承载法律主体真相。';
COMMENT ON COLUMN identity.tenant.state IS '租户状态：ACTIVE、SUSPENDED或CLOSED；关闭后不可恢复。';
COMMENT ON COLUMN identity.tenant.created_at IS '创建时间：租户根首次持久化的数据库时间，创建后不可修改。';
COMMENT ON COLUMN identity.tenant.closed_at IS '关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。';
COMMENT ON COLUMN identity.tenant.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT uq_tenant__tenant_code ON identity.tenant IS '租户代码在全系统唯一，防止外部配置串租户。';
COMMENT ON INDEX identity.uq_tenant__tenant_code IS '租户代码在全系统唯一，防止外部配置串租户。';
COMMENT ON CONSTRAINT ck_tenant__state ON identity.tenant IS '租户状态只能取冻结的三个生命周期值。';
COMMENT ON CONSTRAINT ck_tenant__closed_fields ON identity.tenant IS '关闭一致性：只有CLOSED租户具有关闭时间，且CLOSED必须具有关闭时间。';
COMMENT ON CONSTRAINT ck_tenant__revision_nonnegative ON identity.tenant IS 'CAS修订号不得为负数。';

CREATE TABLE identity.principal (
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    principal_kind varchar(64) NOT NULL,
    identity_provider_code varchar(64) NOT NULL,
    external_subject_hmac bytea NOT NULL,
    display_name text NOT NULL,
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    disabled_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_principal PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT ck_principal__principal_kind CHECK (principal_kind IN ('HUMAN', 'SERVICE')),
    CONSTRAINT ck_principal__state CHECK (state IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT uq_principal__provider_subject UNIQUE (tenant_id, identity_provider_code, external_subject_hmac),
    CONSTRAINT ck_principal__disabled_fields CHECK ((state = 'DISABLED' AND disabled_at IS NOT NULL) OR (state <> 'DISABLED' AND disabled_at IS NULL)),
    CONSTRAINT ck_principal__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_principal__external_subject_hmac_length CHECK (octet_length(external_subject_hmac) = 32)
);

COMMENT ON TABLE identity.principal IS 'Fact Owner：IdentityRuntime；身份主体：一行锚定一个可认证的人或服务身份，由身份域拥有；仅显示名称和生命周期可CAS更新，不保存凭据、Token或任职授权。';
COMMENT ON CONSTRAINT pk_principal ON identity.principal IS '主键：在租户内唯一标识一条principal记录。';
COMMENT ON INDEX identity.pk_principal IS '主键：在租户内唯一标识一条principal记录。';
COMMENT ON COLUMN identity.principal.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.principal.principal_id IS '身份主体标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.principal.principal_kind IS '身份主体种类：HUMAN或SERVICE，创建后不可修改。';
COMMENT ON COLUMN identity.principal.identity_provider_code IS '身份提供方代码：标识静态配置中的认证来源，不保存提供方密钥。';
COMMENT ON COLUMN identity.principal.external_subject_hmac IS '外部主体HMAC：对提供方主体标识做租户密钥HMAC后的32字节值，不保存外部原文。';
COMMENT ON COLUMN identity.principal.display_name IS '显示名称：非权威界面标签，可受控修改，不作为身份匹配依据。';
COMMENT ON COLUMN identity.principal.state IS '身份状态：ACTIVE、SUSPENDED或DISABLED；禁用后不可恢复。';
COMMENT ON COLUMN identity.principal.created_at IS '创建时间：身份主体首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.principal.disabled_at IS '禁用时间：状态首次变为DISABLED时一次写入；未禁用为空。';
COMMENT ON COLUMN identity.principal.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_principal__principal_kind ON identity.principal IS '身份主体种类只能为人员或服务身份。';
COMMENT ON CONSTRAINT ck_principal__state ON identity.principal IS '身份状态只能取冻结的生命周期值。';
COMMENT ON CONSTRAINT uq_principal__provider_subject ON identity.principal IS '同一租户和身份提供方内，一个外部主体HMAC只锚定一个身份主体。';
COMMENT ON INDEX identity.uq_principal__provider_subject IS '同一租户和身份提供方内，一个外部主体HMAC只锚定一个身份主体。';
COMMENT ON CONSTRAINT ck_principal__disabled_fields ON identity.principal IS '禁用一致性：只有DISABLED身份具有禁用时间，且DISABLED必须具有禁用时间。';
COMMENT ON CONSTRAINT ck_principal__revision_nonnegative ON identity.principal IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_principal__external_subject_hmac_length ON identity.principal IS '摘要格式：external_subject_hmac必须保存32字节的规范二进制值。';

CREATE TABLE identity.organization_unit (
    tenant_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    unit_code varchar(64) NOT NULL,
    display_name text NOT NULL,
    parent_organization_unit_id uuid,
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    closed_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_organization_unit PRIMARY KEY (tenant_id, organization_unit_id),
    CONSTRAINT uq_organization_unit__unit_code UNIQUE (tenant_id, unit_code),
    CONSTRAINT ck_organization_unit__state CHECK (state IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_organization_unit__not_own_parent CHECK (parent_organization_unit_id IS NULL OR parent_organization_unit_id <> organization_unit_id),
    CONSTRAINT ck_organization_unit__closed_fields CHECK ((state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)),
    CONSTRAINT ck_organization_unit__revision_nonnegative CHECK (revision >= 0)
);

COMMENT ON TABLE identity.organization_unit IS 'Fact Owner：IdentityRuntime；组织单元：一行锚定租户内一个组织节点，由身份域拥有；名称、上级和生命周期可CAS更新，不代表任职或权限。';
COMMENT ON CONSTRAINT pk_organization_unit ON identity.organization_unit IS '主键：在租户内唯一标识一条organization_unit记录。';
COMMENT ON INDEX identity.pk_organization_unit IS '主键：在租户内唯一标识一条organization_unit记录。';
COMMENT ON COLUMN identity.organization_unit.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.organization_unit.organization_unit_id IS '组织单元标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.organization_unit.unit_code IS '组织单元代码：租户内稳定唯一代码，创建后不可修改。';
COMMENT ON COLUMN identity.organization_unit.display_name IS '组织单元显示名称：可受控修改的界面标签。';
COMMENT ON COLUMN identity.organization_unit.parent_organization_unit_id IS '上级组织单元标识：同租户复合自外键；根节点为空。';
COMMENT ON COLUMN identity.organization_unit.state IS '组织单元状态：ACTIVE或CLOSED；关闭后不可恢复。';
COMMENT ON COLUMN identity.organization_unit.created_at IS '创建时间：组织单元首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.organization_unit.closed_at IS '关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。';
COMMENT ON COLUMN identity.organization_unit.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT uq_organization_unit__unit_code ON identity.organization_unit IS '组织单元代码在租户内唯一。';
COMMENT ON INDEX identity.uq_organization_unit__unit_code IS '组织单元代码在租户内唯一。';
COMMENT ON CONSTRAINT ck_organization_unit__state ON identity.organization_unit IS '组织单元状态只能为ACTIVE或CLOSED。';
COMMENT ON CONSTRAINT ck_organization_unit__not_own_parent ON identity.organization_unit IS '层级局部约束：组织单元不能直接把自身设为上级；更长环路由命令运行时复验。';
COMMENT ON CONSTRAINT ck_organization_unit__closed_fields ON identity.organization_unit IS '关闭一致性：只有CLOSED组织单元具有关闭时间。';
COMMENT ON CONSTRAINT ck_organization_unit__revision_nonnegative ON identity.organization_unit IS 'CAS修订号不得为负数。';

CREATE TABLE identity.appointment (
    tenant_id uuid NOT NULL,
    appointment_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    role_code varchar(64) NOT NULL,
    effective_from timestamptz(6) NOT NULL,
    effective_until timestamptz(6),
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    ended_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_appointment PRIMARY KEY (tenant_id, appointment_id),
    CONSTRAINT ck_appointment__state CHECK (state IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT ck_appointment__effective_window CHECK (effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT ck_appointment__ended_fields CHECK ((state = 'ENDED' AND ended_at IS NOT NULL) OR (state <> 'ENDED' AND ended_at IS NULL)),
    CONSTRAINT ck_appointment__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_appointment__id_principal UNIQUE (tenant_id, appointment_id, principal_id)
);

COMMENT ON TABLE identity.appointment IS 'Fact Owner：IdentityRuntime；任职：一行锚定一个身份主体在一个组织单元中的单一岗位任期，由身份域拥有；计划期限创建时冻结，生命周期可CAS推进，不等同于任何具体权限。';
COMMENT ON CONSTRAINT pk_appointment ON identity.appointment IS '主键：在租户内唯一标识一条appointment记录。';
COMMENT ON INDEX identity.pk_appointment IS '主键：在租户内唯一标识一条appointment记录。';
COMMENT ON COLUMN identity.appointment.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.appointment.appointment_id IS '任职标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.appointment.principal_id IS '任职主体标识：以同租户复合外键关联身份主体，创建后不可修改。';
COMMENT ON COLUMN identity.appointment.organization_unit_id IS '任职组织单元标识：以同租户复合外键关联组织节点，创建后不可修改。';
COMMENT ON COLUMN identity.appointment.role_code IS '岗位代码：来自静态应用注册表，创建后不可修改，不直接授予业务权限。';
COMMENT ON COLUMN identity.appointment.effective_from IS '任职生效时间：原始任期起点，创建后不可修改。';
COMMENT ON COLUMN identity.appointment.effective_until IS '任职计划结束时间：无预定结束时为空，创建时冻结且不得延长或改写。';
COMMENT ON COLUMN identity.appointment.state IS '任职状态：ACTIVE、SUSPENDED或ENDED；结束后不可恢复。';
COMMENT ON COLUMN identity.appointment.created_at IS '创建时间：任职锚点首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.appointment.ended_at IS '实际结束时间：状态首次变为ENDED时一次写入；未结束为空。';
COMMENT ON COLUMN identity.appointment.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_appointment__state ON identity.appointment IS '任职状态只能取冻结的生命周期值。';
COMMENT ON CONSTRAINT ck_appointment__effective_window ON identity.appointment IS '任期窗口：计划结束时间必须晚于生效时间。';
COMMENT ON CONSTRAINT ck_appointment__ended_fields ON identity.appointment IS '结束一致性：只有ENDED任职具有实际结束时间。';
COMMENT ON CONSTRAINT ck_appointment__revision_nonnegative ON identity.appointment IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT uq_appointment__id_principal ON identity.appointment IS '准确任职候选键：供审计Actor与on-behalf-of复合关系证明Appointment确属该Principal。';
COMMENT ON INDEX identity.uq_appointment__id_principal IS '准确任职候选键：供审计Actor与on-behalf-of复合关系证明Appointment确属该Principal。';

CREATE TABLE identity.authority_grant (
    tenant_id uuid NOT NULL,
    authority_grant_id uuid NOT NULL,
    grantee_appointment_id uuid NOT NULL,
    granted_by_appointment_id uuid NOT NULL,
    scope_organization_unit_id uuid NOT NULL,
    authority_code varchar(64) NOT NULL,
    valid_from timestamptz(6) NOT NULL,
    valid_until timestamptz(6),
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    revoked_at timestamptz(6),
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_authority_grant PRIMARY KEY (tenant_id, authority_grant_id),
    CONSTRAINT ck_authority_grant__state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_authority_grant__valid_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_authority_grant__revocation_fields CHECK ((state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)),
    CONSTRAINT ck_authority_grant__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_authority_grant__id_grantee UNIQUE (tenant_id, authority_grant_id, grantee_appointment_id)
);

COMMENT ON TABLE identity.authority_grant IS 'Fact Owner：IdentityRuntime；权限授予：一行代表向一个任职直接授予一种权限的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不代表某次命令已获授权。';
COMMENT ON CONSTRAINT pk_authority_grant ON identity.authority_grant IS '主键：在租户内唯一标识一条authority_grant记录。';
COMMENT ON INDEX identity.pk_authority_grant IS '主键：在租户内唯一标识一条authority_grant记录。';
COMMENT ON COLUMN identity.authority_grant.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.authority_grant.authority_grant_id IS '权限授予标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.authority_grant.grantee_appointment_id IS '受权任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.granted_by_appointment_id IS '授予人任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.scope_organization_unit_id IS '组织范围根标识：授权只沿提交时的当前组织树向下适用，创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.authority_code IS '权限代码：来自静态代码允许列表，创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.valid_from IS '权限生效时间：创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.valid_until IS '权限失效时间：无预定失效时为空，创建时冻结且不得延长或改写。';
COMMENT ON COLUMN identity.authority_grant.state IS '授权状态：ACTIVE或REVOKED；撤销后不可恢复。';
COMMENT ON COLUMN identity.authority_grant.created_at IS '创建时间：授予事实首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.authority_grant.revoked_at IS '撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.authority_grant.revocation_reason_code IS '撤销原因代码：撤销时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.authority_grant.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_authority_grant__state ON identity.authority_grant IS '授权状态只能为ACTIVE或REVOKED。';
COMMENT ON CONSTRAINT ck_authority_grant__valid_window ON identity.authority_grant IS '有效期窗口：失效时间必须晚于生效时间。';
COMMENT ON CONSTRAINT ck_authority_grant__revocation_fields ON identity.authority_grant IS '撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。';
COMMENT ON CONSTRAINT ck_authority_grant__revision_nonnegative ON identity.authority_grant IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT uq_authority_grant__id_grantee ON identity.authority_grant IS '准确授权路径候选键：供转授权证明来源Grant确实授予委托人Appointment。';
COMMENT ON INDEX identity.uq_authority_grant__id_grantee IS '准确授权路径候选键：供转授权证明来源Grant确实授予委托人Appointment。';

CREATE TABLE identity.delegation_grant (
    tenant_id uuid NOT NULL,
    delegation_grant_id uuid NOT NULL,
    source_authority_grant_id uuid NOT NULL,
    delegator_appointment_id uuid NOT NULL,
    delegate_appointment_id uuid NOT NULL,
    scope_organization_unit_id uuid NOT NULL,
    valid_from timestamptz(6) NOT NULL,
    valid_until timestamptz(6),
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    revoked_at timestamptz(6),
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_delegation_grant PRIMARY KEY (tenant_id, delegation_grant_id),
    CONSTRAINT ck_delegation_grant__state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_delegation_grant__different_appointments CHECK (delegator_appointment_id <> delegate_appointment_id),
    CONSTRAINT ck_delegation_grant__valid_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_delegation_grant__revocation_fields CHECK ((state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)),
    CONSTRAINT ck_delegation_grant__revision_nonnegative CHECK (revision >= 0)
);

COMMENT ON TABLE identity.delegation_grant IS 'Fact Owner：IdentityRuntime；转授权：一行代表一个任职把一项既有授权委托给另一任职的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不证明调用时委托链仍有效。';
COMMENT ON CONSTRAINT pk_delegation_grant ON identity.delegation_grant IS '主键：在租户内唯一标识一条delegation_grant记录。';
COMMENT ON INDEX identity.pk_delegation_grant IS '主键：在租户内唯一标识一条delegation_grant记录。';
COMMENT ON COLUMN identity.delegation_grant.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.delegation_grant.delegation_grant_id IS '转授权标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.delegation_grant.source_authority_grant_id IS '来源直接授权标识：以同租户复合外键关联权限授予，创建后不可修改。';
COMMENT ON COLUMN identity.delegation_grant.delegator_appointment_id IS '委托人任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN identity.delegation_grant.delegate_appointment_id IS '受托人任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN identity.delegation_grant.scope_organization_unit_id IS '委托组织范围根标识：必须不宽于来源授权并按当前组织树实时解释。';
COMMENT ON COLUMN identity.delegation_grant.valid_from IS '委托生效时间：创建后不可修改。';
COMMENT ON COLUMN identity.delegation_grant.valid_until IS '委托失效时间：无预定失效时为空，创建时冻结且不得延长或改写。';
COMMENT ON COLUMN identity.delegation_grant.state IS '转授权状态：ACTIVE或REVOKED；撤销后不可恢复。';
COMMENT ON COLUMN identity.delegation_grant.created_at IS '创建时间：转授权锚点首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.delegation_grant.revoked_at IS '撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.delegation_grant.revocation_reason_code IS '撤销原因代码：撤销时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.delegation_grant.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_delegation_grant__state ON identity.delegation_grant IS '转授权状态只能为ACTIVE或REVOKED。';
COMMENT ON CONSTRAINT ck_delegation_grant__different_appointments ON identity.delegation_grant IS '委托人和受托人任职不能相同。';
COMMENT ON CONSTRAINT ck_delegation_grant__valid_window ON identity.delegation_grant IS '有效期窗口：失效时间必须晚于生效时间。';
COMMENT ON CONSTRAINT ck_delegation_grant__revocation_fields ON identity.delegation_grant IS '撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。';
COMMENT ON CONSTRAINT ck_delegation_grant__revision_nonnegative ON identity.delegation_grant IS 'CAS修订号不得为负数。';

CREATE TABLE identity.object_access_grant (
    tenant_id uuid NOT NULL,
    object_access_grant_id uuid NOT NULL,
    grantee_principal_id uuid NOT NULL,
    granted_by_appointment_id uuid NOT NULL,
    access_code varchar(64) NOT NULL,
    effect_code varchar(64) NOT NULL,
    valid_from timestamptz(6) NOT NULL,
    valid_until timestamptz(6),
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    revoked_at timestamptz(6),
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    object_subject_type varchar(64) NOT NULL,
    object_subject_id uuid NOT NULL,
    object_subject_revision bigint,
    object_subject_hash bytea,
    CONSTRAINT pk_object_access_grant PRIMARY KEY (tenant_id, object_access_grant_id),
    CONSTRAINT ck_object_access_grant__state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_object_access_grant__effect_code CHECK (effect_code IN ('DENY', 'ALLOW')),
    CONSTRAINT ck_object_access_grant__valid_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_object_access_grant__revocation_fields CHECK ((state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)),
    CONSTRAINT ck_object_access_grant__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_object_access_grant__object_subject_exact CHECK ((object_subject_type IS NOT NULL AND object_subject_id IS NOT NULL AND ((object_subject_revision IS NOT NULL AND object_subject_revision >= 0 AND object_subject_hash IS NULL) OR (object_subject_revision IS NULL AND object_subject_hash IS NOT NULL)))),
    CONSTRAINT ck_object_access_grant__object_subject_hash_length CHECK (octet_length(object_subject_hash) = 32)
);

COMMENT ON TABLE identity.object_access_grant IS 'Fact Owner：IdentityRuntime；对象访问授予：一行代表对一个Principal设置一个准确业务Subject的允许或限制，由身份域拥有；有效期创建时冻结且只允许单向撤销，实际命令仍必须沿同一Appointment授权路径复验。';
COMMENT ON CONSTRAINT pk_object_access_grant ON identity.object_access_grant IS '主键：在租户内唯一标识一条object_access_grant记录。';
COMMENT ON INDEX identity.pk_object_access_grant IS '主键：在租户内唯一标识一条object_access_grant记录。';
COMMENT ON COLUMN identity.object_access_grant.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.object_access_grant.object_access_grant_id IS '对象访问授予标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.object_access_grant.grantee_principal_id IS '受约束Principal标识：对象级允许或限制固定到身份主体，创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.granted_by_appointment_id IS '授予人任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.access_code IS '访问能力代码：来自静态代码允许列表，创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.effect_code IS '对象规则效果：DENY优先于ALLOW，创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.valid_from IS '访问授权生效时间：创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.valid_until IS '访问授权失效时间：无预定失效时为空，创建时冻结且不得延长或改写。';
COMMENT ON COLUMN identity.object_access_grant.state IS '对象访问授权状态：ACTIVE或REVOKED；撤销后不可恢复。';
COMMENT ON COLUMN identity.object_access_grant.created_at IS '创建时间：对象访问授权首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN identity.object_access_grant.revoked_at IS '撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.object_access_grant.revocation_reason_code IS '撤销原因代码：撤销时一次写入；未撤销为空。';
COMMENT ON COLUMN identity.object_access_grant.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN identity.object_access_grant.object_subject_type IS '访问授权所绑定的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN identity.object_access_grant.object_subject_id IS '访问授权所绑定的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN identity.object_access_grant.object_subject_revision IS '访问授权所绑定的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN identity.object_access_grant.object_subject_hash IS '访问授权所绑定的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_object_access_grant__state ON identity.object_access_grant IS '对象访问授权状态只能为ACTIVE或REVOKED。';
COMMENT ON CONSTRAINT ck_object_access_grant__effect_code ON identity.object_access_grant IS '对象规则效果只允许限制或允许，并始终先限后允。';
COMMENT ON CONSTRAINT ck_object_access_grant__valid_window ON identity.object_access_grant IS '有效期窗口：失效时间必须晚于生效时间。';
COMMENT ON CONSTRAINT ck_object_access_grant__revocation_fields ON identity.object_access_grant IS '撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。';
COMMENT ON CONSTRAINT ck_object_access_grant__revision_nonnegative ON identity.object_access_grant IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_object_access_grant__object_subject_exact ON identity.object_access_grant IS '准确引用：访问授权所绑定的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_object_access_grant__object_subject_hash_length ON identity.object_access_grant IS '摘要格式：object_subject_hash必须保存32字节的规范二进制值。';
