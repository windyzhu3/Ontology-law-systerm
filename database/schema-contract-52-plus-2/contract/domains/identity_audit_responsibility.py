from __future__ import annotations

from ..helpers import (
    bigint_col,
    check,
    code_col,
    col,
    digest_col,
    entity_fk,
    enum_check,
    fk,
    index,
    int_col,
    json_col,
    nonnegative_check,
    revision_col,
    tenant_table,
    text_col,
    time_col,
    typed_ref,
    unique,
    uuid_col,
)
from ..model import Schema, Table


def _controlled_columns(*names: str) -> tuple[str, ...]:
    """把业务可变列与每次受控更新必变的修订号组成允许列表。"""

    return (*names, "revision")


TENANT = Table(
    schema="identity",
    name="tenant",
    id_column="tenant_id",
    columns=(
        uuid_col("tenant_id", "租户标识：由应用生成的UUIDv7，是所有租户数据边界的根。"),
        code_col("tenant_code", "租户代码：外部配置使用的稳定非敏感代码，创建后不可修改。"),
        text_col("display_name", "租户显示名称：仅供界面展示，可受控修改，不承载法律主体真相。"),
        code_col("state", "租户状态：ACTIVE、SUSPENDED或CLOSED；关闭后不可恢复。"),
        time_col("created_at", "创建时间：租户根首次持久化的数据库时间，创建后不可修改。"),
        time_col("closed_at", "关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。", nullable=True),
        revision_col(),
    ),
    primary_key=("tenant_id",),
    primary_key_comment="主键：全局唯一标识一个租户边界。",
    comment="一行代表一个租户边界根，由身份域拥有；仅显示名称、生命周期状态和关闭事实可CAS更新，不代表客户、律所法律主体或授权本身。",
    constraints=(
        unique("uq_tenant__tenant_code", ("tenant_code",), "租户代码在全系统唯一，防止外部配置串租户。"),
        enum_check("tenant", "state", ("ACTIVE", "SUSPENDED", "CLOSED"), "租户状态只能取冻结的三个生命周期值。"),
        check(
            "ck_tenant__closed_fields",
            "(state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)",
            "关闭一致性：只有CLOSED租户具有关闭时间，且CLOSED必须具有关闭时间。",
        ),
        nonnegative_check("tenant", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_tenant__state", ("state",), "按生命周期状态执行租户运维筛选。"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("display_name", "state", "closed_at"),
    write_once_columns=("closed_at",),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(
        ("ACTIVE", "SUSPENDED"),
        ("SUSPENDED", "ACTIVE"),
        ("ACTIVE", "CLOSED"),
        ("SUSPENDED", "CLOSED"),
    ),
)


PRINCIPAL = tenant_table(
    "identity",
    "principal",
    "principal_id",
    "身份主体：一行锚定一个可认证的人或服务身份，由身份域拥有；仅显示名称和生命周期可CAS更新，不保存凭据、Token或任职授权。",
    (
        code_col("principal_kind", "身份主体种类：HUMAN或SERVICE，创建后不可修改。"),
        code_col("identity_provider_code", "身份提供方代码：标识静态配置中的认证来源，不保存提供方密钥。"),
        digest_col("external_subject_hmac", "外部主体HMAC：对提供方主体标识做租户密钥HMAC后的32字节值，不保存外部原文。"),
        text_col("display_name", "显示名称：非权威界面标签，可受控修改，不作为身份匹配依据。"),
        code_col("state", "身份状态：ACTIVE、SUSPENDED或DISABLED；禁用后不可恢复。"),
        time_col("created_at", "创建时间：身份主体首次持久化的时间，创建后不可修改。"),
        time_col("disabled_at", "禁用时间：状态首次变为DISABLED时一次写入；未禁用为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("principal", "principal_kind", ("HUMAN", "SERVICE"), "身份主体种类只能为人员或服务身份。"),
        enum_check("principal", "state", ("ACTIVE", "SUSPENDED", "DISABLED"), "身份状态只能取冻结的生命周期值。"),
        unique(
            "uq_principal__provider_subject",
            ("tenant_id", "identity_provider_code", "external_subject_hmac"),
            "同一租户和身份提供方内，一个外部主体HMAC只锚定一个身份主体。",
        ),
        check(
            "ck_principal__disabled_fields",
            "(state = 'DISABLED' AND disabled_at IS NOT NULL) OR (state <> 'DISABLED' AND disabled_at IS NULL)",
            "禁用一致性：只有DISABLED身份具有禁用时间，且DISABLED必须具有禁用时间。",
        ),
        nonnegative_check("principal", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_principal__state", ("tenant_id", "state"), "按租户和生命周期状态查找身份主体。"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("display_name", "state", "disabled_at"),
    write_once_columns=("disabled_at",),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(
        ("ACTIVE", "SUSPENDED"),
        ("SUSPENDED", "ACTIVE"),
        ("ACTIVE", "DISABLED"),
        ("SUSPENDED", "DISABLED"),
    ),
)


ORGANIZATION_UNIT = tenant_table(
    "identity",
    "organization_unit",
    "organization_unit_id",
    "组织单元：一行锚定租户内一个组织节点，由身份域拥有；名称、上级和生命周期可CAS更新，不代表任职或权限。",
    (
        code_col("unit_code", "组织单元代码：租户内稳定唯一代码，创建后不可修改。"),
        text_col("display_name", "组织单元显示名称：可受控修改的界面标签。"),
        uuid_col("parent_organization_unit_id", "上级组织单元标识：同租户复合自外键；根节点为空。", nullable=True),
        code_col("state", "组织单元状态：ACTIVE或CLOSED；关闭后不可恢复。"),
        time_col("created_at", "创建时间：组织单元首次持久化的时间，创建后不可修改。"),
        time_col("closed_at", "关闭时间：状态首次变为CLOSED时一次写入；未关闭为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        unique("uq_organization_unit__unit_code", ("tenant_id", "unit_code"), "组织单元代码在租户内唯一。"),
        enum_check("organization_unit", "state", ("ACTIVE", "CLOSED"), "组织单元状态只能为ACTIVE或CLOSED。"),
        check(
            "ck_organization_unit__not_own_parent",
            "parent_organization_unit_id IS NULL OR parent_organization_unit_id <> organization_unit_id",
            "层级局部约束：组织单元不能直接把自身设为上级；更长环路由命令运行时复验。",
        ),
        check(
            "ck_organization_unit__closed_fields",
            "(state = 'CLOSED' AND closed_at IS NOT NULL) OR (state <> 'CLOSED' AND closed_at IS NULL)",
            "关闭一致性：只有CLOSED组织单元具有关闭时间。",
        ),
        nonnegative_check("organization_unit", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index(
            "ix_organization_unit__parent",
            ("tenant_id", "parent_organization_unit_id"),
            "按同租户上级节点遍历直属组织单元。",
            where="parent_organization_unit_id IS NOT NULL",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "organization_unit",
            "parent_organization_unit_id",
            "identity",
            "organization_unit",
            "organization_unit_id",
            "组织层级：上级组织单元必须存在于同一租户；禁止级联删除。",
            suffix="parent_organization_unit",
            deferrable=True,
            initially_deferred=True,
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("display_name", "parent_organization_unit_id", "state", "closed_at"),
    write_once_columns=("closed_at",),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "CLOSED"),),
)


APPOINTMENT = tenant_table(
    "identity",
    "appointment",
    "appointment_id",
    "任职：一行锚定一个身份主体在一个组织单元中的单一岗位任期，由身份域拥有；计划期限创建时冻结，生命周期可CAS推进，不等同于任何具体权限。",
    (
        uuid_col("principal_id", "任职主体标识：以同租户复合外键关联身份主体，创建后不可修改。"),
        uuid_col("organization_unit_id", "任职组织单元标识：以同租户复合外键关联组织节点，创建后不可修改。"),
        code_col("role_code", "岗位代码：来自静态应用注册表，创建后不可修改，不直接授予业务权限。"),
        time_col("effective_from", "任职生效时间：原始任期起点，创建后不可修改。"),
        time_col("effective_until", "任职计划结束时间：无预定结束时为空，创建时冻结且不得延长或改写。", nullable=True),
        code_col("state", "任职状态：ACTIVE、SUSPENDED或ENDED；结束后不可恢复。"),
        time_col("created_at", "创建时间：任职锚点首次持久化的时间，创建后不可修改。"),
        time_col("ended_at", "实际结束时间：状态首次变为ENDED时一次写入；未结束为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("appointment", "state", ("ACTIVE", "SUSPENDED", "ENDED"), "任职状态只能取冻结的生命周期值。"),
        check(
            "ck_appointment__effective_window",
            "effective_until IS NULL OR effective_until > effective_from",
            "任期窗口：计划结束时间必须晚于生效时间。",
        ),
        check(
            "ck_appointment__ended_fields",
            "(state = 'ENDED' AND ended_at IS NOT NULL) OR (state <> 'ENDED' AND ended_at IS NULL)",
            "结束一致性：只有ENDED任职具有实际结束时间。",
        ),
        nonnegative_check("appointment", "revision", "CAS修订号不得为负数。"),
        unique("uq_appointment__id_principal", ("tenant_id", "appointment_id", "principal_id"), "准确任职候选键：供审计Actor与on-behalf-of复合关系证明Appointment确属该Principal。"),
    ),
    indexes=(
        index("ix_appointment__principal", ("tenant_id", "principal_id", "state"), "按身份主体查找当前及历史任职。"),
        index("ix_appointment__unit", ("tenant_id", "organization_unit_id", "state"), "按组织单元查找任职。"),
    ),
    foreign_keys=(
        entity_fk("appointment", "principal_id", "identity", "principal", "principal_id", "任职主体必须是同租户已存在的身份主体。", suffix="principal"),
        entity_fk(
            "appointment",
            "organization_unit_id",
            "identity",
            "organization_unit",
            "organization_unit_id",
            "任职组织单元必须是同租户已存在的组织节点。",
            suffix="organization_unit",
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("state", "ended_at"),
    write_once_columns=("ended_at",),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(
        ("ACTIVE", "SUSPENDED"),
        ("SUSPENDED", "ACTIVE"),
        ("ACTIVE", "ENDED"),
        ("SUSPENDED", "ENDED"),
    ),
)


AUTHORITY_GRANT = tenant_table(
    "identity",
    "authority_grant",
    "authority_grant_id",
    "权限授予：一行代表向一个任职直接授予一种权限的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不代表某次命令已获授权。",
    (
        uuid_col("grantee_appointment_id", "受权任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        uuid_col("granted_by_appointment_id", "授予人任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        uuid_col("scope_organization_unit_id", "组织范围根标识：授权只沿提交时的当前组织树向下适用，创建后不可修改。"),
        code_col("authority_code", "权限代码：来自静态代码允许列表，创建后不可修改。"),
        time_col("valid_from", "权限生效时间：创建后不可修改。"),
        time_col("valid_until", "权限失效时间：无预定失效时为空，创建时冻结且不得延长或改写。", nullable=True),
        code_col("state", "授权状态：ACTIVE或REVOKED；撤销后不可恢复。"),
        time_col("created_at", "创建时间：授予事实首次持久化的时间，创建后不可修改。"),
        time_col("revoked_at", "撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。", nullable=True),
        code_col("revocation_reason_code", "撤销原因代码：撤销时一次写入；未撤销为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("authority_grant", "state", ("ACTIVE", "REVOKED"), "授权状态只能为ACTIVE或REVOKED。"),
        check("ck_authority_grant__valid_window", "valid_until IS NULL OR valid_until > valid_from", "有效期窗口：失效时间必须晚于生效时间。"),
        check(
            "ck_authority_grant__revocation_fields",
            "(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)",
            "撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。",
        ),
        nonnegative_check("authority_grant", "revision", "CAS修订号不得为负数。"),
        unique("uq_authority_grant__id_grantee", ("tenant_id", "authority_grant_id", "grantee_appointment_id"), "准确授权路径候选键：供转授权证明来源Grant确实授予委托人Appointment。"),
    ),
    indexes=(
        index("ix_authority_grant__grantee", ("tenant_id", "grantee_appointment_id", "authority_code", "state"), "授权复验时按受权任职、权限和状态定位候选授予。"),
        index("ix_authority_grant__scope", ("tenant_id", "scope_organization_unit_id", "authority_code", "state"), "授权复验：按当前组织树范围根、权限和状态定位授予。"),
    ),
    foreign_keys=(
        entity_fk("authority_grant", "grantee_appointment_id", "identity", "appointment", "appointment_id", "受权任职必须存在于同一租户。", suffix="grantee_appointment"),
        entity_fk("authority_grant", "granted_by_appointment_id", "identity", "appointment", "appointment_id", "授予人任职必须存在于同一租户。", suffix="granted_by_appointment"),
        entity_fk("authority_grant", "scope_organization_unit_id", "identity", "organization_unit", "organization_unit_id", "授权组织范围根必须存在于同一租户，树关系在命令提交前按当前结构复验。", suffix="scope_org"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("state", "revoked_at", "revocation_reason_code"),
    write_once_columns=("revoked_at", "revocation_reason_code"),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "REVOKED"),),
)


DELEGATION_GRANT = tenant_table(
    "identity",
    "delegation_grant",
    "delegation_grant_id",
    "转授权：一行代表一个任职把一项既有授权委托给另一任职的生命周期锚点，由身份域拥有；有效期创建时冻结且只允许单向撤销，不证明调用时委托链仍有效。",
    (
        uuid_col("source_authority_grant_id", "来源直接授权标识：以同租户复合外键关联权限授予，创建后不可修改。"),
        uuid_col("delegator_appointment_id", "委托人任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        uuid_col("delegate_appointment_id", "受托人任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        uuid_col("scope_organization_unit_id", "委托组织范围根标识：必须不宽于来源授权并按当前组织树实时解释。"),
        time_col("valid_from", "委托生效时间：创建后不可修改。"),
        time_col("valid_until", "委托失效时间：无预定失效时为空，创建时冻结且不得延长或改写。", nullable=True),
        code_col("state", "转授权状态：ACTIVE或REVOKED；撤销后不可恢复。"),
        time_col("created_at", "创建时间：转授权锚点首次持久化的时间，创建后不可修改。"),
        time_col("revoked_at", "撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。", nullable=True),
        code_col("revocation_reason_code", "撤销原因代码：撤销时一次写入；未撤销为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("delegation_grant", "state", ("ACTIVE", "REVOKED"), "转授权状态只能为ACTIVE或REVOKED。"),
        check("ck_delegation_grant__different_appointments", "delegator_appointment_id <> delegate_appointment_id", "委托人和受托人任职不能相同。"),
        check("ck_delegation_grant__valid_window", "valid_until IS NULL OR valid_until > valid_from", "有效期窗口：失效时间必须晚于生效时间。"),
        check(
            "ck_delegation_grant__revocation_fields",
            "(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)",
            "撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。",
        ),
        nonnegative_check("delegation_grant", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_delegation_grant__delegate", ("tenant_id", "delegate_appointment_id", "state"), "授权复验时按受托任职和状态查找委托。"),
        index("ix_delegation_grant__source", ("tenant_id", "source_authority_grant_id"), "按来源直接授权查找全部委托。"),
    ),
    foreign_keys=(
        fk("fk_delegation_grant__source_grantee", ("tenant_id", "source_authority_grant_id", "delegator_appointment_id"), "identity", "authority_grant", ("tenant_id", "authority_grant_id", "grantee_appointment_id"), "同一路径来源：转授权来源Grant必须准确授予本行委托人Appointment。"),
        entity_fk("delegation_grant", "delegator_appointment_id", "identity", "appointment", "appointment_id", "委托人任职必须存在于同一租户。", suffix="delegator_appointment"),
        entity_fk("delegation_grant", "delegate_appointment_id", "identity", "appointment", "appointment_id", "受托人任职必须存在于同一租户。", suffix="delegate_appointment"),
        entity_fk("delegation_grant", "scope_organization_unit_id", "identity", "organization_unit", "organization_unit_id", "委托组织范围根必须存在于同一租户，范围收窄由命令提交前复验。", suffix="scope_org"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("state", "revoked_at", "revocation_reason_code"),
    write_once_columns=("revoked_at", "revocation_reason_code"),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "REVOKED"),),
)


ACCESS_OBJECT = typed_ref("object_subject", "访问授权所绑定的准确业务Subject")

OBJECT_ACCESS_GRANT = tenant_table(
    "identity",
    "object_access_grant",
    "object_access_grant_id",
    "对象访问授予：一行代表对一个Principal设置一个准确业务Subject的允许或限制，由身份域拥有；有效期创建时冻结且只允许单向撤销，实际命令仍必须沿同一Appointment授权路径复验。",
    (
        uuid_col("grantee_principal_id", "受约束Principal标识：对象级允许或限制固定到身份主体，创建后不可修改。"),
        uuid_col("granted_by_appointment_id", "授予人任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        code_col("access_code", "访问能力代码：来自静态代码允许列表，创建后不可修改。"),
        code_col("effect_code", "对象规则效果：DENY优先于ALLOW，创建后不可修改。"),
        time_col("valid_from", "访问授权生效时间：创建后不可修改。"),
        time_col("valid_until", "访问授权失效时间：无预定失效时为空，创建时冻结且不得延长或改写。", nullable=True),
        code_col("state", "对象访问授权状态：ACTIVE或REVOKED；撤销后不可恢复。"),
        time_col("created_at", "创建时间：对象访问授权首次持久化的时间，创建后不可修改。"),
        time_col("revoked_at", "撤销时间：状态首次变为REVOKED时一次写入；未撤销为空。", nullable=True),
        code_col("revocation_reason_code", "撤销原因代码：撤销时一次写入；未撤销为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("object_access_grant", "state", ("ACTIVE", "REVOKED"), "对象访问授权状态只能为ACTIVE或REVOKED。"),
        enum_check("object_access_grant", "effect_code", ("DENY", "ALLOW"), "对象规则效果只允许限制或允许，并始终先限后允。"),
        check("ck_object_access_grant__valid_window", "valid_until IS NULL OR valid_until > valid_from", "有效期窗口：失效时间必须晚于生效时间。"),
        check(
            "ck_object_access_grant__revocation_fields",
            "(state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL) OR (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL)",
            "撤销一致性：REVOKED必须同时记录时间和原因，ACTIVE不得预填撤销信息。",
        ),
        nonnegative_check("object_access_grant", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_object_access_grant__grantee", ("tenant_id", "grantee_principal_id", "access_code", "effect_code", "state"), "授权复验时按Principal、能力、先限后允效果和状态定位对象规则。"),
        index("ix_object_access_grant__object", ("tenant_id", "object_subject_type", "object_subject_id", "state"), "按准确业务Subject查找对象访问授予。"),
    ),
    foreign_keys=(
        entity_fk("object_access_grant", "grantee_principal_id", "identity", "principal", "principal_id", "对象规则必须绑定同租户准确Principal。", suffix="grantee_principal"),
        entity_fk("object_access_grant", "granted_by_appointment_id", "identity", "appointment", "appointment_id", "授予人任职必须存在于同一租户。", suffix="granted_by_appointment"),
    ),
    typed_references=(ACCESS_OBJECT,),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns("state", "revoked_at", "revocation_reason_code"),
    write_once_columns=("revoked_at", "revocation_reason_code"),
    state_column="state",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "REVOKED"),),
)


AUDIT_SUBJECT = typed_ref("subject", "本条审计所针对的准确业务Subject")
AUDIT_CORRECTION_TARGET = typed_ref("correction_target", "本条更正所指向的原审计事实", optional=True)
AUDIT_AUTHORIZATION_FACT = typed_ref("authorization_fact", "执行被审计动作时实际采用的授权或委托Fact", optional=True)

AUDIT_ENTRY = tenant_table(
    "audit",
    "audit_entry",
    "audit_entry_id",
    "审计条目：一行冻结谁在何种准确Scope、单一路径授权和可信执行上下文下做了什么及其结果；只能追加，CORRECTION准确引用原条目，不复制领域事实、请求响应或正文。",
    (
        code_col("entry_type", "条目类型：EVENT表示原始审计事实，CORRECTION表示对一条原记录的单链修正。"),
        code_col("audit_scope_code", "审计Scope：静态分类的租户、组织、对象或安全管理范围。"),
        time_col("trusted_at", "可信时间：被审计写入、拒绝或披露提交审计事务的服务端时间。"),
        code_col("action_code", "动作代码：来自静态审计动作注册表，创建后不可修改。"),
        code_col("result_code", "结果代码：SUCCEEDED、NO_CHANGE、REJECTED或FAILED，创建后不可修改。"),
        uuid_col("actor_principal_id", "实际发起身份主体标识：以同租户复合外键关联身份主体，创建后不可修改。"),
        uuid_col("actor_appointment_id", "实际采用的任职标识：以同租户复合外键关联任职；不适用时为空。", nullable=True),
        uuid_col("on_behalf_of_principal_id", "被代表Principal标识：非代办时为空，存在时与被代表任职一起冻结。", nullable=True),
        uuid_col("on_behalf_of_appointment_id", "被代表任职标识：非代办时为空，存在时与被代表Principal一起冻结。", nullable=True),
        uuid_col("command_id", "命令标识：由CommandRuntime产生的事件准确关联命令；非命令事件为空。", nullable=True),
        code_col("command_type", "命令类型：与command_id同时存在；非命令事件为空。", nullable=True),
        uuid_col("correlation_id", "关联标识：贯穿一次用户或服务请求的稳定UUID。"),
        uuid_col("causation_id", "因果标识：存在直接上游命令或事件时记录其稳定UUID。", nullable=True),
        code_col("authorization_slot_code", "授权槽：本动作实际满足的唯一静态authoritySlot。"),
        code_col("authorization_path_code", "授权路径：DIRECT、DELEGATED、OBJECT或SYSTEM等静态单路径类型。"),
        uuid_col("authorization_scope_organization_unit_id", "授权组织Scope根：按提交时当前组织树解释；全租户系统路径时可为空。", nullable=True),
        digest_col("authorization_snapshot_digest", "授权依据快照摘要：冻结实际Actor、Appointment、路径、范围、限制和决定依据。"),
        uuid_col("trace_id", "追踪标识：把同一请求链上的审计事实关联起来，不是业务对象外键。"),
        code_col("service_role_code", "后端执行角色：API、WORKER或受控管理角色等静态代码。"),
        code_col("execution_node_code", "执行节点代码：冻结实际服务实例或受控运行环境，不保存主机Secret。", length=128),
        digest_col("session_id_hmac", "会话标识HMAC：固定HMAC-SHA-256的32字节值，用于安全关联且不能还原原始会话Token。", nullable=True),
        col("client_ip_ciphertext", "bytea", "客户端地址密文：仅高风险审计需要时保存，数据库不可解密。", nullable=True),
        code_col("summary_schema_code", "变更摘要Schema：静态允许列表定义可出现的字段。"),
        int_col("summary_schema_version", "变更摘要Schema版本：解释允许列表化JSON结构的正整数版本。"),
        json_col("change_summary", "允许列表化变更摘要：仅保存必要字段变化，不得复制完整领域事实、请求响应、密码、Token、Secret或正文。"),
        digest_col("change_summary_digest", "变更摘要摘要：规范化允许列表JSON的32字节SHA-256。"),
    ),
    constraints=(
        enum_check("audit_entry", "entry_type", ("EVENT", "CORRECTION"), "审计条目只允许原始事件或追加更正。"),
        enum_check("audit_entry", "result_code", ("SUCCEEDED", "NO_CHANGE", "REJECTED", "FAILED"), "审计结果只允许成功、无变化、拒绝或失败。"),
        check("ck_audit_entry__command_pair", "(command_id IS NULL AND command_type IS NULL) OR (command_id IS NOT NULL AND command_type IS NOT NULL)", "命令上下文：命令标识和类型必须同时存在或同时为空。"),
        check("ck_audit_entry__on_behalf_pair", "(on_behalf_of_principal_id IS NULL AND on_behalf_of_appointment_id IS NULL) OR (on_behalf_of_principal_id IS NOT NULL AND on_behalf_of_appointment_id IS NOT NULL)", "代办上下文：被代表Principal和Appointment必须同时存在或同时为空。"),
        check("ck_audit_entry__correction_shape", "(entry_type = 'EVENT' AND correction_target_type IS NULL) OR (entry_type = 'CORRECTION' AND correction_target_type IS NOT NULL)", "更正单链：只有CORRECTION必须准确引用一条原AuditEntry。"),
        check("ck_audit_entry__summary_schema_version", "summary_schema_version > 0", "变更摘要Schema版本必须为正数。"),
    ),
    indexes=(
        index("ix_audit_entry__subject_time", ("tenant_id", "subject_type", "subject_id", "trusted_at"), "按准确Subject和可信时间检索审计轨迹。"),
        index("ix_audit_entry__actor_time", ("tenant_id", "actor_principal_id", "trusted_at"), "按实际发起身份和可信时间检索审计轨迹。"),
        index("ix_audit_entry__correlation", ("tenant_id", "correlation_id", "trusted_at"), "按Correlation标识重建一次动作链的审计顺序。"),
        index("ix_audit_entry__scope_time", ("tenant_id", "audit_scope_code", "trusted_at"), "分类查询：按准确审计Scope和可信时间检索。"),
        index("ux_audit_entry__correction_target", ("tenant_id", "correction_target_type", "correction_target_id"), "更正单链唯一：一条AuditEntry最多只有一个直接CORRECTION后继；继续修正必须引用上一条CORRECTION。", unique_=True, where="entry_type = 'CORRECTION'"),
    ),
    foreign_keys=(
        entity_fk("audit_entry", "actor_principal_id", "identity", "principal", "principal_id", "实际发起身份必须存在于同一租户。", suffix="actor_principal"),
        entity_fk("audit_entry", "actor_appointment_id", "identity", "appointment", "appointment_id", "实际采用的任职若存在，必须属于同一租户。", suffix="actor_appointment"),
        entity_fk("audit_entry", "on_behalf_of_principal_id", "identity", "principal", "principal_id", "被代表Principal若存在，必须属于同一租户。", suffix="on_behalf_principal"),
        entity_fk("audit_entry", "on_behalf_of_appointment_id", "identity", "appointment", "appointment_id", "代办任职若存在，必须属于同一租户。", suffix="on_behalf_of_appointment"),
        fk("fk_audit_entry__actor_appointment_principal", ("tenant_id", "actor_appointment_id", "actor_principal_id"), "identity", "appointment", ("tenant_id", "appointment_id", "principal_id"), "Actor一致性：实际Appointment若存在必须属于同一实际Principal。"),
        fk("fk_audit_entry__on_behalf_appointment_principal", ("tenant_id", "on_behalf_of_appointment_id", "on_behalf_of_principal_id"), "identity", "appointment", ("tenant_id", "appointment_id", "principal_id"), "代办一致性：被代表Appointment必须属于同一被代表Principal。"),
        entity_fk("audit_entry", "authorization_scope_organization_unit_id", "identity", "organization_unit", "organization_unit_id", "授权组织Scope若存在，必须属于同一租户。", suffix="authorization_scope_org"),
    ),
    typed_references=(AUDIT_SUBJECT, AUDIT_CORRECTION_TARGET, AUDIT_AUTHORIZATION_FACT),
)


TASK_SUBJECT = typed_ref("subject", "待办发生时冻结的准确业务Subject")
TASK_COMPLETION_FACT = typed_ref("completion_fact", "完成待办所产生的准确业务Fact", optional=True)
DECISION_SUBJECT = typed_ref("decision_subject", "本版本Decision实际裁定的准确业务Subject")
AWAITED_FACT = typed_ref("awaited_fact", "本次进入等待所等待的准确外部或领域Fact", optional=True)

TASK_OCCURRENCE = tenant_table(
    "responsibility",
    "task_occurrence",
    "task_occurrence_id",
    "待办发生：一行代表针对一个冻结Subject、由一个Owner任职承担且只有一个主命令的责任实例，由责任域拥有；只可CAS推进等待或终态，不是通用工作流或作业。",
    (
        uuid_col("owner_appointment_id", "Owner任职标识：任务创建时冻结并以同租户复合外键关联任职，之后不可改派。"),
        code_col("business_purpose_code", "业务目的代码：来自静态代码注册表，任务创建后不可修改。"),
        code_col("primary_command_code", "固定主命令代码：完成该任务所允许提交的唯一主命令，创建后不可修改。"),
        code_col("expected_completion_fact_type", "预期完成Fact类型：静态注册类型，创建后不可修改；DONE时必须与准确完成Fact类型一致。"),
        code_col("original_sla_code", "原始SLA代码：任务发生时采用的静态规则代码，创建后不可修改。"),
        bigint_col("original_sla_seconds", "原始SLA时长：任务发生时冻结的非负秒数，创建后不可修改。"),
        time_col("original_sla_due_at", "原始SLA截止时间：任务发生时计算并冻结，后续等待或策略变化均不改写。"),
        code_col("state", "任务状态：OPEN、WAITING、DONE或CANCELLED；只允许OPEN与WAITING互转并从二者进入终态。"),
        time_col("created_at", "创建时间：任务发生并冻结责任信息的时间，创建后不可修改。"),
        time_col("completed_at", "完成时间：状态首次变为DONE时一次写入；其他状态为空。", nullable=True),
        time_col("cancelled_at", "取消时间：状态首次变为CANCELLED时一次写入；其他状态为空。", nullable=True),
        code_col("cancellation_reason_code", "取消原因代码：取消时一次写入；其他状态为空。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check("task_occurrence", "state", ("OPEN", "WAITING", "DONE", "CANCELLED"), "任务状态只能取冻结的四个生命周期值。"),
        nonnegative_check("task_occurrence", "original_sla_seconds", "原始SLA秒数不得为负数。"),
        nonnegative_check("task_occurrence", "revision", "CAS修订号不得为负数。"),
        check(
            "ck_task_occurrence__completion_type",
            "completion_fact_type IS NULL OR completion_fact_type = expected_completion_fact_type",
            "完成类型一致性：实际准确完成Fact的类型必须等于任务创建时冻结的预期类型。",
        ),
        check(
            "ck_task_occurrence__terminal_fields",
            "(state = 'DONE' AND completed_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NOT NULL) OR "
            "(state = 'CANCELLED' AND completed_at IS NULL AND cancelled_at IS NOT NULL AND cancellation_reason_code IS NOT NULL AND completion_fact_type IS NULL) OR "
            "(state IN ('OPEN', 'WAITING') AND completed_at IS NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NULL)",
            "终态一致性：DONE必须准确记录完成Fact和完成时间，CANCELLED必须记录取消时间与原因，非终态不得预填终态事实。",
        ),
    ),
    indexes=(
        index("ix_task_occurrence__owner_state_due", ("tenant_id", "owner_appointment_id", "state", "original_sla_due_at"), "按Owner、状态和原始SLA截止时间生成责任待办视图。"),
        index("ix_task_occurrence__subject", ("tenant_id", "subject_type", "subject_id", "state"), "按冻结Subject查找相关责任实例。"),
    ),
    foreign_keys=(
        entity_fk("task_occurrence", "owner_appointment_id", "identity", "appointment", "appointment_id", "任务Owner必须是同租户已存在的任职。", suffix="owner_appointment"),
    ),
    typed_references=(TASK_SUBJECT, TASK_COMPLETION_FACT),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns(
        "state",
        "completed_at",
        "cancelled_at",
        "cancellation_reason_code",
        "completion_fact_type",
        "completion_fact_id",
        "completion_fact_revision",
        "completion_fact_hash",
    ),
    write_once_columns=(
        "completed_at",
        "cancelled_at",
        "cancellation_reason_code",
        "completion_fact_type",
        "completion_fact_id",
        "completion_fact_revision",
        "completion_fact_hash",
    ),
    state_column="state",
    initial_state="OPEN",
    state_transitions=(
        ("OPEN", "WAITING"),
        ("WAITING", "OPEN"),
        ("OPEN", "DONE"),
        ("WAITING", "DONE"),
        ("OPEN", "CANCELLED"),
        ("WAITING", "CANCELLED"),
    ),
)


DECISION_RECORD = tenant_table(
    "responsibility",
    "decision_record",
    "decision_record_id",
    "决策记录：一行代表某个待办的一个不可变、显式版本决策事实，由责任域拥有；只能追加新版本，不覆盖旧决策，也不保存完整案情或文档正文。",
    (
        uuid_col("task_occurrence_id", "所属待办标识：以同租户复合外键关联待办发生。"),
        int_col("decision_version", "决策版本：同一待办内从一开始的正整数；唯一但连续性由命令运行时串行保证。"),
        uuid_col("predecessor_decision_record_id", "前序决定标识：首版本为空，后续版本准确引用同Task直接前序。", nullable=True),
        uuid_col("decided_by_appointment_id", "决策人任职标识：以同租户复合外键关联任职。"),
        code_col("authority_slot_code", "授权槽：本决定所满足或拒绝的唯一静态authoritySlot。"),
        code_col("decision_contract_code", "决定合同代码：静态注册的结论Schema及允许结果集合。"),
        int_col("decision_contract_version", "决定合同版本：解释本版本决定内容的正整数。"),
        code_col("decision_code", "决策代码：来自该业务目的的静态允许列表。"),
        digest_col("content_digest", "决定内容摘要：覆盖准确Subject、authoritySlot、结论和规范依据。"),
        text_col("rationale_summary", "脱敏理由摘要：只记录可审查的简短依据，不得包含凭据、文档正文或非必要案情。"),
        time_col("decided_at", "决策时间：该版本决策完成并持久化的时间。"),
    ),
    constraints=(
        unique("uq_decision_record__task_version", ("tenant_id", "task_occurrence_id", "decision_version"), "每个待办的决策版本号唯一，旧版本不可覆盖。"),
        unique("uq_decision_record__predecessor", ("tenant_id", "predecessor_decision_record_id"), "单后继链：一个DecisionRecord最多只有一个直接后继版本。"),
        check("ck_decision_record__positive_version", "decision_version > 0", "决策版本必须为正整数。"),
        check("ck_decision_record__contract_version", "decision_contract_version > 0", "决定合同版本必须为正整数。"),
        check("ck_decision_record__predecessor_shape", "(decision_version = 1 AND predecessor_decision_record_id IS NULL) OR (decision_version > 1 AND predecessor_decision_record_id IS NOT NULL)", "决定版本链：首版本无前序，后续版本必须准确引用直接前序。"),
    ),
    foreign_keys=(
        entity_fk("decision_record", "task_occurrence_id", "responsibility", "task_occurrence", "task_occurrence_id", "决策必须属于同租户已存在的待办。", suffix="task_occurrence"),
        entity_fk("decision_record", "predecessor_decision_record_id", "responsibility", "decision_record", "decision_record_id", "版本链：后续决定必须引用同租户直接前序。", suffix="predecessor"),
        entity_fk("decision_record", "decided_by_appointment_id", "identity", "appointment", "appointment_id", "决策人任职必须存在于同一租户。", suffix="decided_by_appointment"),
    ),
    typed_references=(DECISION_SUBJECT,),
)


WAIT_RECEIPT = tenant_table(
    "responsibility",
    "wait_receipt",
    "wait_receipt_id",
    "等待回执：一行代表某待办一次进入WAITING的不可变追加事实，由责任域拥有；每次进入等待均新增回执，不覆盖历史，也不代表通用工作流步骤。",
    (
        uuid_col("task_occurrence_id", "所属待办标识：以同租户复合外键关联待办发生。"),
        bigint_col("task_revision", "入等待后的待办准确修订号：用于把回执绑定到那次状态迁移。"),
        int_col("wait_sequence", "等待序号：同一待办内从一开始的正整数，用于稳定排序。"),
        code_col("wait_reason_code", "等待原因代码：来自该主命令的静态允许列表。"),
        code_col("wait_contract_code", "等待合同代码：静态注册的等待原因和恢复Fact约束。"),
        int_col("wait_contract_version", "等待合同版本：解释本次无状态WaitReceipt的正整数版本。"),
        time_col("entered_waiting_at", "进入等待时间：该次OPEN到WAITING迁移发生的时间。"),
        time_col("resume_due_at", "预期恢复时间：未知时为空，不改写原始SLA。", nullable=True),
        uuid_col("recorded_by_appointment_id", "记录人任职标识：以同租户复合外键关联执行该次迁移的任职。"),
    ),
    constraints=(
        unique("uq_wait_receipt__task_revision", ("tenant_id", "task_occurrence_id", "task_revision"), "一个待办修订号至多对应一次进入等待回执。"),
        unique("uq_wait_receipt__task_sequence", ("tenant_id", "task_occurrence_id", "wait_sequence"), "同一待办内等待序号唯一。"),
        check("ck_wait_receipt__positive_task_revision", "task_revision > 0", "等待回执绑定的待办修订号必须为正数。"),
        check("ck_wait_receipt__positive_sequence", "wait_sequence > 0", "等待序号必须为正整数。"),
        check("ck_wait_receipt__contract_version", "wait_contract_version > 0", "等待合同版本必须为正整数。"),
        check("ck_wait_receipt__resume_after_entry", "resume_due_at IS NULL OR resume_due_at > entered_waiting_at", "预期恢复时间若存在必须晚于进入等待时间。"),
    ),
    indexes=(
        index("ix_wait_receipt__task_time", ("tenant_id", "task_occurrence_id", "entered_waiting_at"), "按待办和时间读取不可变等待历史。"),
    ),
    foreign_keys=(
        entity_fk("wait_receipt", "task_occurrence_id", "responsibility", "task_occurrence", "task_occurrence_id", "等待回执必须属于同租户已存在的待办。", suffix="task_occurrence"),
        entity_fk("wait_receipt", "recorded_by_appointment_id", "identity", "appointment", "appointment_id", "记录人任职必须存在于同一租户。", suffix="recorded_by_appointment"),
    ),
    typed_references=(AWAITED_FACT,),
)


ACTION_DRAFT = tenant_table(
    "responsibility",
    "action_draft",
    "action_draft_id",
    "行动草案：一行代表某待办唯一一份按静态Schema校验的候选主命令载荷，由责任域拥有；确认前可CAS编辑且只能确认一次，不是业务最终事实或通用文档。",
    (
        uuid_col("task_occurrence_id", "所属待办标识：以同租户复合外键关联待办；唯一约束保证每个待办最多一份草案。"),
        code_col("action_code", "候选行动代码：必须等于待办冻结主命令所允许的静态代码，创建后不可修改。"),
        code_col("payload_schema_code", "候选载荷Schema代码：来自静态应用注册表，创建后不可修改。"),
        int_col("payload_schema_version", "候选载荷Schema版本：正整数，创建后不可修改。"),
        json_col("candidate_payload", "候选载荷：按指定静态Schema校验的JSONB，确认前可CAS编辑；不得用作其他业务真相。"),
        digest_col("candidate_payload_digest", "候选载荷摘要：规范化JSON的32字节SHA-256，随确认前编辑一并CAS更新。"),
        code_col("state", "草案状态：DRAFT或CONFIRMED；只允许从DRAFT一次进入CONFIRMED。"),
        uuid_col("created_by_appointment_id", "创建人任职标识：以同租户复合外键关联任职，创建后不可修改。"),
        time_col("created_at", "创建时间：草案首次持久化的时间，创建后不可修改。"),
        time_col("last_edited_at", "最近编辑时间：每次候选载荷CAS修改时更新；未编辑时等于创建时间。"),
        uuid_col("confirmed_by_appointment_id", "确认人任职标识：确认时一次写入；未确认为空。", nullable=True),
        time_col("confirmed_at", "确认时间：从DRAFT进入CONFIRMED时一次写入；未确认为空。", nullable=True),
        digest_col("confirmed_payload_digest", "确认载荷摘要：确认时一次复制候选载荷摘要，只绑定输入，不代表主命令执行成功。", nullable=True),
        revision_col(),
    ),
    constraints=(
        unique("uq_action_draft__task", ("tenant_id", "task_occurrence_id"), "每个待办最多存在一份行动草案。"),
        enum_check("action_draft", "state", ("DRAFT", "CONFIRMED"), "行动草案状态只能为DRAFT或CONFIRMED。"),
        check("ck_action_draft__positive_schema_version", "payload_schema_version > 0", "候选载荷Schema版本必须为正整数。"),
        check(
            "ck_action_draft__confirmation_fields",
            "(state = 'CONFIRMED' AND confirmed_by_appointment_id IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_payload_digest IS NOT NULL AND confirmed_payload_digest = candidate_payload_digest) OR "
            "(state = 'DRAFT' AND confirmed_by_appointment_id IS NULL AND confirmed_at IS NULL AND confirmed_payload_digest IS NULL)",
            "确认一致性：CONFIRMED一次冻结当前候选载荷摘要，DRAFT不得预填；确认本身不产生业务执行Fact。",
        ),
        nonnegative_check("action_draft", "revision", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_action_draft__state", ("tenant_id", "state", "last_edited_at"), "按租户、草案状态和最近编辑时间查找待处理草案。"),
    ),
    foreign_keys=(
        entity_fk("action_draft", "task_occurrence_id", "responsibility", "task_occurrence", "task_occurrence_id", "行动草案必须属于同租户已存在的待办。", suffix="task_occurrence"),
        entity_fk("action_draft", "created_by_appointment_id", "identity", "appointment", "appointment_id", "草案创建人任职必须存在于同一租户。", suffix="created_by_appointment"),
        entity_fk("action_draft", "confirmed_by_appointment_id", "identity", "appointment", "appointment_id", "草案确认人任职若存在，必须属于同一租户。", suffix="confirmed_by_appointment"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=_controlled_columns(
        "candidate_payload",
        "candidate_payload_digest",
        "last_edited_at",
        "state",
        "confirmed_by_appointment_id",
        "confirmed_at",
        "confirmed_payload_digest",
    ),
    write_once_columns=(
        "confirmed_by_appointment_id",
        "confirmed_at",
        "confirmed_payload_digest",
    ),
    state_column="state",
    initial_state="DRAFT",
    state_transitions=(("DRAFT", "CONFIRMED"),),
)


SCHEMAS = (
    Schema(
        name="identity",
        comment="身份域：保存租户、身份、组织、任职及授权锚点；不保存凭据，也不替代命令时动态授权复验。",
        tables=(TENANT, PRINCIPAL, ORGANIZATION_UNIT, APPOINTMENT, AUTHORITY_GRANT, DELEGATION_GRANT, OBJECT_ACCESS_GRANT),
    ),
    Schema(
        name="audit",
        comment="审计域：只追加不可变审计事实，以准确类型化引用冻结对象、授权依据及更正目标。",
        tables=(AUDIT_ENTRY,),
    ),
    Schema(
        name="responsibility",
        comment="责任域：保存待办责任实例及其不可变决策、等待回执和唯一行动草案；不建设通用工作流或作业系统。",
        tables=(TASK_OCCURRENCE, DECISION_RECORD, WAIT_RECEIPT, ACTION_DRAFT),
    ),
)


__all__ = ("SCHEMAS",)
