from __future__ import annotations

from ..helpers import (
    bigint_col,
    check,
    code_col,
    digest_col,
    encrypted_col,
    entity_fk,
    enum_check,
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
from ..model import Schema


# execution：命令幂等占位、终态回执与“事实指针式”领域事件。
_receipt_result = typed_ref(
    "result_fact",
    "命令成功或无变化时产生或确认的结果事实",
    optional=True,
)
_event_source = typed_ref(
    "source_fact",
    "领域事件所通知的唯一来源事实",
)

_command_execution_slot = tenant_table(
    "execution",
    "command_execution_slot",
    "command_execution_slot_id",
    "命令执行占位：一行永久占用一个租户内命令标识，事实Owner为CommandRuntime；只可插入且永远无状态，不表示执行成功、失败或锁租约。",
    (
        uuid_col("command_id", "命令标识：调用方提供的稳定UUID，与静态信封和Scope共同形成永久占位键。"),
        code_col("envelope_type", "命令信封类型：四类静态注册信封之一，创建后不可变。"),
        code_col("command_type", "命令类型：来自静态命令注册表，创建后不可变。"),
        digest_col("command_scope_digest", "命令Scope摘要：覆盖Tenant、命令种类和准确Subject范围，用于永久占位键。"),
        digest_col("payload_digest", "载荷摘要：规范命令载荷的SHA-256，只用于同CommandId冲突判定。"),
        time_col("occupied_at", "占位时间：CommandRuntime首次接纳该命令标识的数据库时间，创建后不可变。"),
    ),
    constraints=(
        unique("uq_command_execution_slot__command_key", ("tenant_id", "envelope_type", "command_scope_digest", "command_id"), "永久占位唯一：Tenant、静态信封、命令Scope摘要与CommandId组合不得复用。"),
    ),
    indexes=(
        index(
            "ix_command_execution_slot__occupied_at",
            ("tenant_id", "occupied_at"),
            "运维查询：按租户和占位时间定位永久命令占位，不承担队列语义。",
        ),
    ),
)

_command_receipt = tenant_table(
    "execution",
    "command_receipt",
    "command_receipt_id",
    "命令终态回执：一行记录一个命令唯一且不可变的最终裁定，事实Owner为CommandRuntime；只可插入，不表示处理中状态。",
    (
        uuid_col("command_execution_slot_id", "命令执行槽标识：强关联唯一永久占位，创建后不可变。"),
        code_col("outcome", "终态结果：仅允许SUCCEEDED、NO_CHANGE或REJECTED，创建后不可变。", length=32),
        code_col("rejection_code", "拒绝原因代码：仅REJECTED时必填，不保存输入正文、案情或密钥。", nullable=True),
        time_col("completed_at", "完成时间：CommandRuntime形成终态裁定的数据库时间，创建后不可变。"),
    ),
    typed_references=(_receipt_result,),
    constraints=(
        enum_check(
            "command_receipt",
            "outcome",
            ("SUCCEEDED", "NO_CHANGE", "REJECTED"),
            "回执终态：只允许成功、无变化或拒绝，不存在处理中或可回退状态。",
        ),
        check(
            "ck_command_receipt__outcome_result",
            "((outcome IN ('SUCCEEDED', 'NO_CHANGE') AND result_fact_type IS NOT NULL AND rejection_code IS NULL) OR (outcome = 'REJECTED' AND result_fact_type IS NULL AND rejection_code IS NOT NULL))",
            "结果准确性：成功和无变化必须引用准确结果事实且不得带拒绝码；拒绝不得引用结果事实且必须带安全原因代码。",
        ),
        unique(
            "uq_command_receipt__slot",
            ("tenant_id", "command_execution_slot_id"),
            "命令终局唯一：一个永久命令占位至多形成一张终态回执。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "command_receipt",
            "command_execution_slot_id",
            "execution",
            "command_execution_slot",
            "command_execution_slot_id",
            "命令归属：终态回执必须关联同租户已存在的永久命令占位。",
        ),
    ),
)

_domain_event = tenant_table(
    "execution",
    "domain_event",
    "domain_event_id",
    "领域事件通知：一行仅声明某个准确来源事实发生了静态类型事件，事实Owner为提交该事实的CommandRuntime；只可插入，不复制业务事实、文档正文或密钥。",
    (
        code_col("event_type", "事件类型：来自静态事件注册表，创建后不可变。"),
        int_col("event_schema_version", "事件Schema版本：解释通知载荷的正整数静态版本。"),
        json_col("event_payload", "事件通知载荷：只保存允许列表化路由信息，不复制领域事实或文档正文。"),
        digest_col("payload_digest", "事件载荷摘要：规范化通知JSON的32字节SHA-256。"),
        uuid_col("command_id", "来源命令标识：把事件关联到同事务命令，不作为业务Fact外键。"),
        uuid_col("correlation_id", "关联标识：贯穿一次业务请求和下游通知链。"),
        uuid_col("causation_event_id", "上游事件标识：由另一个事件触发时记录；非事件触发时为空。", nullable=True),
        time_col("occurred_at", "发生时间：来源事实与事件在同一事务提交时记录的数据库时间，创建后不可变。"),
    ),
    typed_references=(_event_source,),
    constraints=(
        check("ck_domain_event__schema_version", "event_schema_version > 0", "事件Schema版本必须为正数。"),
    ),
    indexes=(
        index(
            "ix_domain_event__source_fact",
            ("tenant_id", "source_fact_type", "source_fact_id"),
            "事实追溯：按租户和来源事实定位通知；准确版本选择器仍由行内约束限定。",
        ),
    ),
)

_domain_event_outbox = tenant_table(
    "execution",
    "domain_event_outbox",
    "domain_event_outbox_id",
    "领域事件投递队列：一行代表一个事件向一个静态队列Owner的唯一投递，Owner为OutboxDispatcher；仅允许租约围栏式受控更新，不是领域事实副本。",
    (
        uuid_col("domain_event_id", "领域事件标识：强关联同租户不可变事件，创建后不可变。"),
        code_col("queue_owner", "队列Owner：静态消费者通道代码，同一事件与Owner只允许一行，创建后不可变。"),
        code_col("status", "队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。", length=32),
        time_col("available_at", "可领取时间：PENDING重试时可由队列CAS推进，其他事实列不可借此改写。"),
        code_col("lease_owner", "租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。", nullable=True),
        time_col("lease_until", "租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。", nullable=True),
        bigint_col("fencing_token", "围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。", default="0"),
        int_col("attempt_count", "投递尝试次数：非负且仅由队列CAS递增。", default="0"),
        time_col("delivered_at", "投递完成时间：仅DELIVERED终态存在，首次写入后不可改。", nullable=True),
        code_col("last_error_code", "最近一次安全错误代码：不得保存Secret、Token、正文或非必要案情。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check(
            "domain_event_outbox",
            "status",
            ("PENDING", "CLAIMED", "DELIVERED", "EXHAUSTED"),
            "队列状态域：只允许待领取、已领取、已投递和耗尽四种机器状态。",
        ),
        check(
            "ck_domain_event_outbox__lease_shape",
            "((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))",
            "租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。",
        ),
        check(
            "ck_domain_event_outbox__delivered_at",
            "((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))",
            "投递终态：只有DELIVERED必须且可以记录完成时间。",
        ),
        nonnegative_check("domain_event_outbox", "fencing_token", "围栏令牌不得为负。"),
        nonnegative_check("domain_event_outbox", "attempt_count", "投递尝试次数不得为负。"),
        nonnegative_check("domain_event_outbox", "revision", "CAS修订号不得为负。"),
        unique(
            "uq_domain_event_outbox__event_owner",
            ("tenant_id", "domain_event_id", "queue_owner"),
            "投递唯一：每个事件与队列Owner组合至多存在一行。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "domain_event_outbox",
            "domain_event_id",
            "execution",
            "domain_event",
            "domain_event_id",
            "强关系例外：Outbox投递必须关联同租户已存在的领域事件。",
        ),
    ),
    indexes=(
        index(
            "ix_domain_event_outbox__claim",
            ("tenant_id", "queue_owner", "status", "available_at"),
            "队列领取：按租户、Owner、状态和可用时间扫描可领取投递。",
            where="status = 'PENDING'",
        ),
        index(
            "ix_domain_event_outbox__lease_expiry",
            ("tenant_id", "lease_until"),
            "租约回收：定位已过期的CLAIMED投递并执行带围栏CAS。",
            where="status = 'CLAIMED'",
        ),
    ),
    update_policy="QUEUE",
    mutable_columns=(
        "status",
        "available_at",
        "lease_owner",
        "lease_until",
        "fencing_token",
        "attempt_count",
        "delivered_at",
        "last_error_code",
        "revision",
    ),
    write_once_columns=("delivered_at",),
    state_column="status",
    initial_state="PENDING",
    state_transitions=(
        ("PENDING", "CLAIMED"),
        ("CLAIMED", "PENDING"),
        ("CLAIMED", "DELIVERED"),
        ("CLAIMED", "EXHAUSTED"),
        ("EXHAUSTED", "PENDING"),
    ),
)

EXECUTION_SCHEMA = Schema(
    "execution",
    "执行域：保存永久命令占位、不可变终态回执、准确事实事件及带租约围栏的投递队列。",
    (
        _command_execution_slot,
        _command_receipt,
        _domain_event,
        _domain_event_outbox,
    ),
)


# external_action：一次外部效果尝试、其派发/探测队列，以及验签后的Provider事实。
_external_action_subject = typed_ref("subject", "本次外部效果尝试所作用的准确业务Subject")
_external_resolution_source = typed_ref("resolution_source", "SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision", optional=True)

_external_action = tenant_table(
    "external_action",
    "external_action",
    "external_action_id",
    "外部效果尝试：一行冻结一个准确Subject、版本化动作合同、Provider账号、规范请求、稳定意图和一次attempt；状态只单向收敛，UNKNOWN不得恢复PENDING。",
    (
        code_col("action_contract_code", "动作合同代码：静态注册的外部效果请求与结果Schema。"),
        int_col("action_contract_version", "动作合同版本：解释本次请求Envelope和结果的正整数版本。"),
        code_col("provider_code", "Provider代码：标识静态适配器，创建后不可变。"),
        uuid_col("provider_account_id", "Provider账户标识：租户内配置账户的稳定标识，创建后不可变。"),
        json_col("request_envelope", "规范请求Envelope：按动作合同校验的允许列表JSON，不保存Secret、Token或非必要正文。"),
        digest_col("request_digest", "请求摘要：规范请求Envelope的32字节SHA-256，用于冲突判定。"),
        code_col("intent_key", "稳定意图键：同一业务意图跨attempt保持不变，非Provider凭据。", length=160),
        int_col("attempt_no", "尝试序号：同一intentKey下从一开始递增，每行只代表一次不可重开的外部效果尝试。"),
        code_col("provider_idempotency_key", "Provider幂等键：该attempt使用的稳定非秘密键，创建后不可变。", length=160),
        code_col("status", "动作状态：PENDING、DISPATCHED、SUCCEEDED、FAILED或UNKNOWN。", length=32),
        time_col("created_at", "创建时间：CommandRuntime批准该次外部效果尝试的数据库时间，创建后不可变。"),
        time_col("dispatched_at", "网络边界时间：请求首次可能越过网络边界时写入；即使崩溃后只能判定UNKNOWN也不得为空。", nullable=True),
        text_col("provider_action_id", "Provider动作标识：Provider返回的非秘密远端标识，首次写入后不可改。", nullable=True),
        time_col("completed_at", "收敛时间：SUCCEEDED或FAILED终态形成时写入，空值表示未收敛。", nullable=True),
        code_col("result_code", "Provider安全结果代码：成功或失败收敛时可写入，不保存响应正文。", nullable=True),
        digest_col("result_digest", "外部结果摘要：收敛时覆盖可信结果证明的32字节摘要。", nullable=True),
        code_col("resolution_method_code", "收敛方法：PROVIDER_INBOX、PROBE或DECISION；只有SUCCEEDED/FAILED终态存在。", nullable=True),
        code_col("last_error_code", "最近安全错误代码：不得保存Secret、Token、响应正文或非必要案情。", nullable=True),
        revision_col(),
    ),
    typed_references=(_external_action_subject, _external_resolution_source),
    constraints=(
        enum_check(
            "external_action",
            "status",
            ("PENDING", "DISPATCHED", "SUCCEEDED", "FAILED", "UNKNOWN"),
            "动作状态域：只允许待派发、已派发、成功、失败和未知五种状态。",
        ),
        check(
            "ck_external_action__dispatch_shape",
            "((status = 'PENDING' AND dispatched_at IS NULL) OR (status IN ('DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN') AND dispatched_at IS NOT NULL))",
            "派发证据：PENDING尚未确认派发；其余状态必须保留首次派发时间。",
        ),
        check(
            "ck_external_action__completion_shape",
            "((status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL AND result_code IS NOT NULL AND result_digest IS NOT NULL AND ((resolution_method_code = 'PROVIDER_INBOX' AND resolution_source_type = 'external_action.provider_inbox') OR (resolution_method_code = 'DECISION' AND resolution_source_type = 'responsibility.decision_record') OR (resolution_method_code = 'PROBE' AND resolution_source_type IS NULL))) OR (status IN ('PENDING', 'DISPATCHED', 'UNKNOWN') AND completed_at IS NULL AND result_code IS NULL AND result_digest IS NULL AND resolution_method_code IS NULL AND resolution_source_type IS NULL))",
            "收敛证据：ProviderInbox和Decision必须引用准确Fact；无副作用权威PROBE以本行结果摘要和同事务Audit证明且不得伪造来源Fact。",
        ),
        enum_check("external_action", "resolution_method_code", ("PROVIDER_INBOX", "PROBE", "DECISION"), "收敛方法只允许验签Provider消息、无副作用权威探测或授权裁决。"),
        check("ck_external_action__contract_version", "action_contract_version > 0", "动作合同版本必须为正数。"),
        check("ck_external_action__attempt_no", "attempt_no > 0", "同一意图下的尝试序号必须为正数。"),
        nonnegative_check("external_action", "revision", "CAS修订号不得为负。"),
        unique(
            "uq_external_action__provider_idempotency",
            ("tenant_id", "provider_account_id", "provider_idempotency_key"),
            "Provider幂等：同一租户Provider账户内一个幂等键只代表一次外部效果尝试。",
        ),
        unique("uq_external_action__intent_attempt", ("tenant_id", "intent_key", "attempt_no"), "业务尝试唯一：同一稳定意图下attemptNo不得重复。"),
    ),
    indexes=(
        index(
            "ix_external_action__provider_action",
            ("tenant_id", "provider_account_id", "provider_action_id"),
            "远端对账：按Provider账户和远端动作标识定位本地唯一尝试。",
            unique_=True,
            where="provider_action_id IS NOT NULL",
        ),
        index(
            "ix_external_action__unknown",
            ("tenant_id", "provider_code", "status", "dispatched_at"),
            "UNKNOWN收敛：定位需要通过Provider探测确认结果的动作。",
            where="status = 'UNKNOWN'",
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=(
        "status",
        "dispatched_at",
        "provider_action_id",
        "completed_at",
        "result_code",
        "result_digest",
        "resolution_method_code",
        "resolution_source_type",
        "resolution_source_id",
        "resolution_source_revision",
        "resolution_source_hash",
        "last_error_code",
        "revision",
    ),
    write_once_columns=("dispatched_at", "provider_action_id", "completed_at", "result_code", "result_digest", "resolution_method_code", "resolution_source_type", "resolution_source_id", "resolution_source_revision", "resolution_source_hash"),
    state_column="status",
    initial_state="PENDING",
    state_transitions=(
        ("PENDING", "DISPATCHED"),
        ("PENDING", "UNKNOWN"),
        ("DISPATCHED", "SUCCEEDED"),
        ("DISPATCHED", "FAILED"),
        ("DISPATCHED", "UNKNOWN"),
        ("UNKNOWN", "SUCCEEDED"),
        ("UNKNOWN", "FAILED"),
    ),
)

_external_action_outbox = tenant_table(
    "external_action",
    "external_action_outbox",
    "external_action_outbox_id",
    "外部动作队列：一行代表一个动作的DISPATCH或PROBE唯一工作项，Owner为ExternalActionDispatcher；仅允许租约围栏式受控更新，不产生第二次外部效果尝试。",
    (
        uuid_col("external_action_id", "外部动作标识：强关联同租户的一次效果尝试，创建后不可变。"),
        code_col("operation", "工作类型：DISPATCH派发一次效果，PROBE仅探测UNKNOWN结果，创建后不可变。", length=32),
        code_col("status", "队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。", length=32),
        time_col("available_at", "可领取时间：PENDING重试或延迟探测时可由队列CAS推进。"),
        code_col("lease_owner", "租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。", nullable=True),
        time_col("lease_until", "租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。", nullable=True),
        bigint_col("fencing_token", "围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。", default="0"),
        int_col("attempt_count", "工作尝试次数：非负且仅由队列CAS递增。", default="0"),
        time_col("delivered_at", "工作项完成时间：仅DELIVERED终态存在，首次写入后不可改。", nullable=True),
        code_col("last_error_code", "最近安全错误代码：不得保存Secret、Token、正文或非必要案情。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check(
            "external_action_outbox",
            "operation",
            ("DISPATCH", "PROBE"),
            "工作类型域：只允许一次派发工作或UNKNOWN结果探测工作。",
        ),
        enum_check(
            "external_action_outbox",
            "status",
            ("PENDING", "CLAIMED", "DELIVERED", "EXHAUSTED"),
            "队列状态域：只允许待领取、已领取、已完成和耗尽四种机器状态。",
        ),
        check(
            "ck_external_action_outbox__lease_shape",
            "((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))",
            "租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。",
        ),
        check(
            "ck_external_action_outbox__delivered_at",
            "((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))",
            "工作终态：只有DELIVERED必须且可以记录完成时间。",
        ),
        nonnegative_check("external_action_outbox", "fencing_token", "围栏令牌不得为负。"),
        nonnegative_check("external_action_outbox", "attempt_count", "工作尝试次数不得为负。"),
        nonnegative_check("external_action_outbox", "revision", "CAS修订号不得为负。"),
        unique(
            "uq_external_action_outbox__action_operation",
            ("tenant_id", "external_action_id", "operation"),
            "工作唯一：每个外部动作的DISPATCH与PROBE各至多存在一行。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "external_action_outbox",
            "external_action_id",
            "external_action",
            "external_action",
            "external_action_id",
            "强关系例外：Outbox工作项必须关联同租户已存在的外部动作。",
        ),
    ),
    indexes=(
        index(
            "ix_external_action_outbox__claim",
            ("tenant_id", "operation", "status", "available_at"),
            "队列领取：按租户、工作类型、状态和可用时间扫描可领取工作项。",
            where="status = 'PENDING'",
        ),
        index(
            "ix_external_action_outbox__lease_expiry",
            ("tenant_id", "lease_until"),
            "租约回收：定位已过期的CLAIMED工作项并执行带围栏CAS。",
            where="status = 'CLAIMED'",
        ),
    ),
    update_policy="QUEUE",
    mutable_columns=(
        "status",
        "available_at",
        "lease_owner",
        "lease_until",
        "fencing_token",
        "attempt_count",
        "delivered_at",
        "last_error_code",
        "revision",
    ),
    write_once_columns=("delivered_at",),
    state_column="status",
    initial_state="PENDING",
    state_transitions=(
        ("PENDING", "CLAIMED"),
        ("CLAIMED", "PENDING"),
        ("CLAIMED", "DELIVERED"),
        ("CLAIMED", "EXHAUSTED"),
    ),
)

_provider_inbox = tenant_table(
    "external_action",
    "provider_inbox",
    "provider_inbox_id",
    "Provider入站事实：一行保存一个Provider账户已通过验签的不可变事件指纹，事实Owner为ProviderIngress；只可插入，不表示事件已被业务接受或成功处理。",
    (
        code_col("provider_code", "Provider代码：标识完成验签的静态适配器，创建后不可变。"),
        uuid_col("provider_account_id", "Provider账户标识：验签所使用租户配置账户的稳定标识，创建后不可变。"),
        text_col("provider_event_id", "Provider事件标识：验签载荷声明的稳定非秘密标识，创建后不可变。"),
        code_col("provider_event_type", "Provider事件类型：验签后解析出的静态类型代码，创建后不可变。"),
        digest_col("payload_digest", "载荷摘要：验签原始字节的SHA-256，仅用于证据核对，不保存正文。"),
        digest_col("nonce_digest", "Nonce摘要：验签窗口内去重使用的32字节摘要，不保存原始Nonce。"),
        code_col("signature_method_code", "验签方法：静态注册的Provider签名算法和账号绑定方式。"),
        int_col("message_schema_version", "消息Schema版本：解释允许列表化规范消息的正整数版本。"),
        json_col("normalized_message", "规范消息：验签和Schema校验后的允许列表JSON，不保存原始请求、Token或Secret。"),
        digest_col("normalized_message_digest", "规范消息摘要：用于同ProviderEventId同Hash返回原结果、异Hash隔离。"),
        uuid_col("external_action_id", "准确外部动作标识：接入时可证明关联Action及Subject时填写，否则为空且不得推进业务。", nullable=True),
        time_col("provider_occurred_at", "Provider发生时间：验签载荷声明的时间；Provider未提供时为空。", nullable=True),
        time_col("signature_verified_at", "验签时间：ProviderIngress确认签名有效的数据库时间，创建后不可变。"),
        time_col("received_at", "接收时间：系统首次持久化该已验签事件的数据库时间，创建后不可变。"),
    ),
    constraints=(
        check("ck_provider_inbox__schema_version", "message_schema_version > 0", "Provider消息Schema版本必须为正数。"),
        unique(
            "uq_provider_inbox__account_event",
            ("tenant_id", "provider_account_id", "provider_event_id"),
            "Provider去重：租户、Provider账户与Provider事件标识组合全局唯一。",
        ),
        unique("uq_provider_inbox__account_nonce", ("tenant_id", "provider_account_id", "nonce_digest"), "Nonce防重：同一Provider账户不得重复接受相同Nonce摘要；时间窗口仍由ProviderIngress先行校验。"),
    ),
    indexes=(
        index(
            "ix_provider_inbox__received_at",
            ("tenant_id", "provider_code", "received_at"),
            "入站审计：按租户、Provider和接收时间追溯已验签事件指纹。",
        ),
    ),
    foreign_keys=(
        entity_fk("provider_inbox", "external_action_id", "external_action", "external_action", "external_action_id", "准确关联：仅能物理关联同租户ExternalAction，Subject一致性由固定内部命令复验。", suffix="external_action"),
    ),
)

EXTERNAL_ACTION_SCHEMA = Schema(
    "external_action",
    "外部动作域：保存一次性外部效果尝试、派发或探测队列，以及验签后不可变的Provider入站事件指纹。",
    (
        _external_action,
        _external_action_outbox,
        _provider_inbox,
    ),
)


# evidence：从单文件上传到来源对象、提交事实和目标绑定的严格一对一物理链。
_upload_target = typed_ref(
    "target",
    "上传会话创建时冻结的准确业务目标",
)
_binding_target = typed_ref(
    "target",
    "证据绑定创建时冻结的准确业务目标",
)

_upload_session = tenant_table(
    "evidence",
    "upload_session",
    "upload_session_id",
    "上传会话：一行只授权向冻结目标和用途上传一个文件，事实Owner为EvidenceIngress；仅允许单向关闭状态更新，不表示文件已经接收、扫描或成为证据。",
    (
        code_col("object_store_code", "对象存储代码：标识静态私有对象存储适配器。"),
        text_col("object_key", "Opaque对象键：由服务端生成且不包含租户、案情、文件名或可用凭据。"),
        code_col("purpose_code", "上传用途：来自静态用途注册表，会话创建后冻结且不可改。"),
        code_col("intake_contract_code", "接收合同代码：静态注册的大小、媒体类型和安全门禁合同。"),
        int_col("intake_contract_version", "接收合同版本：解释本会话技术门禁的正整数版本。"),
        digest_col("intake_contract_digest", "接收合同摘要：冻结实际允许规则的32字节摘要。"),
        digest_col("upload_capability_hash", "上传能力摘要：一次性上传能力的SHA-256，数据库不保存可用凭据。"),
        code_col("status", "会话状态：OPEN、OBJECT_RECEIVED、FINALIZED、EXPIRED或CANCELLED。", length=32),
        uuid_col("created_by_appointment_id", "创建任职标识：发起受控上传会话的准确Appointment。"),
        time_col("created_at", "创建时间：EvidenceIngress签发会话的数据库时间，创建后不可变。"),
        time_col("expires_at", "到期时间：创建时冻结的上传截止时间，创建后不可变。"),
        time_col("received_at", "对象接收时间：唯一文件的准确ObjectVersion被固定时一次写入。", nullable=True),
        time_col("finalized_at", "最终晋级时间：技术检查、最终授权和Subject版本重验全部通过后一次写入。", nullable=True),
        revision_col(),
    ),
    typed_references=(_upload_target,),
    constraints=(
        enum_check(
            "upload_session",
            "status",
            ("OPEN", "OBJECT_RECEIVED", "FINALIZED", "EXPIRED", "CANCELLED"),
            "上传会话状态域：只允许开放、对象已接收、已最终晋级、已过期或已取消。",
        ),
        check(
            "ck_upload_session__received_at",
            "((status IN ('OBJECT_RECEIVED', 'FINALIZED') AND received_at IS NOT NULL) OR (status IN ('OPEN', 'EXPIRED', 'CANCELLED') AND received_at IS NULL))",
            "对象接收：只有对象已接收或最终晋级状态具有唯一接收时间。",
        ),
        check("ck_upload_session__finalized_at", "(status = 'FINALIZED' AND finalized_at IS NOT NULL) OR (status <> 'FINALIZED' AND finalized_at IS NULL)", "最终晋级：只有FINALIZED必须记录完成时间。"),
        check(
            "ck_upload_session__expiry_order",
            "expires_at > created_at",
            "会话期限：冻结的到期时间必须晚于创建时间。",
        ),
        check("ck_upload_session__contract_version", "intake_contract_version > 0", "接收合同版本必须为正数。"),
        nonnegative_check("upload_session", "revision", "CAS修订号不得为负。"),
        unique("uq_upload_session__object_key", ("tenant_id", "object_store_code", "object_key"), "create-only对象唯一：一个Opaque Key只允许一个上传会话和一次原始字节写入。"),
    ),
    indexes=(
        index(
            "ix_upload_session__open_expiry",
            ("tenant_id", "status", "expires_at"),
            "会话回收：按租户和到期时间定位仍OPEN的会话。",
            where="status = 'OPEN'",
        ),
    ),
    foreign_keys=(
        entity_fk("upload_session", "created_by_appointment_id", "identity", "appointment", "appointment_id", "会话创建主体必须是同租户准确Appointment。", suffix="creator"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("status", "received_at", "finalized_at", "revision"),
    write_once_columns=("received_at", "finalized_at"),
    state_column="status",
    initial_state="OPEN",
    state_transitions=(
        ("OPEN", "OBJECT_RECEIVED"),
        ("OBJECT_RECEIVED", "FINALIZED"),
        ("OPEN", "EXPIRED"),
        ("OPEN", "CANCELLED"),
    ),
)

_received_source_object = tenant_table(
    "evidence",
    "received_source_object",
    "received_source_object_id",
    "接收来源对象：一行是一个上传会话唯一文件经服务端读取、类型识别和恶意文件扫描后的不可变来源事实，事实Owner为EvidenceIngress；不代表业务提交或目标绑定。",
    (
        uuid_col("upload_session_id", "上传会话标识：强关联同租户会话且一会话至多一个来源对象。"),
        code_col("object_store_code", "对象存储代码：标识静态存储适配器，创建后不可变。"),
        text_col("object_key", "对象键：服务端控制的非公开定位键，创建后不可变且不得包含可用凭据。"),
        text_col("object_version", "对象版本：对象存储返回的真实不可变ObjectVersion，创建后不可改。"),
        bigint_col("size_bytes", "服务端读取的对象字节数：非负，创建后不可变。"),
        digest_col("server_sha256", "服务端摘要：服务端读取固定ObjectVersion全部字节计算的SHA-256。"),
        code_col("detected_media_type", "真实媒体类型：服务端内容识别结果，不信任客户端声明，创建后不可变。", length=255),
        code_col("scan_result", "扫描结果：PASSED或FAILED；PASSED只表示技术门禁通过，不表示业务VERIFIED。", length=32),
        code_col("scan_engine_code", "扫描引擎代码：标识静态扫描器及其规则版本，创建后不可变。", length=128),
        int_col("scan_contract_version", "扫描合同版本：解释引擎规则和真实类型门禁的正整数版本。"),
        code_col("scan_failure_code", "扫描失败代码：FAILED时必填的安全原因，PASSED时为空。", nullable=True),
        time_col("scanned_at", "扫描时间：固定ObjectVersion完成恶意文件扫描的数据库时间，创建后不可变。"),
        time_col("received_at", "接收时间：服务端固定对象版本并完成读取的数据库时间，创建后不可变。"),
    ),
    constraints=(
        enum_check(
            "received_source_object",
            "scan_result",
            ("PASSED", "FAILED"),
            "扫描结果域：只允许技术门禁通过或失败；失败细分使用安全原因代码。",
        ),
        check("ck_received_source_object__scan_shape", "(scan_result = 'PASSED' AND scan_failure_code IS NULL) OR (scan_result = 'FAILED' AND scan_failure_code IS NOT NULL)", "扫描结果完整性：FAILED必须有安全原因，PASSED不得携带失败码。"),
        check("ck_received_source_object__scan_contract_version", "scan_contract_version > 0", "扫描合同版本必须为正数。"),
        nonnegative_check("received_source_object", "size_bytes", "服务端观测的对象字节数不得为负。"),
        unique(
            "uq_received_source_object__upload_session",
            ("tenant_id", "upload_session_id"),
            "单文件会话：一个上传会话至多形成一个接收来源对象。",
        ),
        unique(
            "uq_received_source_object__object_version",
            ("tenant_id", "object_store_code", "object_key", "object_version"),
            "来源对象唯一：同租户同存储对象键的固定ObjectVersion只接收一次。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "received_source_object",
            "upload_session_id",
            "evidence",
            "upload_session",
            "upload_session_id",
            "证据链第一段：来源对象必须强关联同租户上传会话。",
        ),
    ),
)

_evidence_submission = tenant_table(
    "evidence",
    "evidence_submission",
    "evidence_submission_id",
    "证据提交：一行把一个已接收且扫描结论可接受的唯一来源对象声明为不可变提交事实，事实Owner为EvidenceRuntime；只可插入，不代表已绑定到业务目标。",
    (
        uuid_col("received_source_object_id", "接收来源对象标识：强关联同租户来源对象且一对象至多一次提交。"),
        code_col("submission_contract_code", "提交合同代码：静态注册的EvidenceSubmission结构和用途规则。"),
        int_col("submission_contract_version", "提交合同版本：解释不可变提交事实的正整数版本。"),
        uuid_col("submitted_by_appointment_id", "提交任职标识：最终授权和Subject重验通过时实际执行晋级的Appointment。"),
        time_col("submitted_at", "提交时间：EvidenceRuntime接受该来源对象的数据库时间，创建后不可变。"),
    ),
    constraints=(
        check("ck_evidence_submission__contract_version", "submission_contract_version > 0", "提交合同版本必须为正数。"),
        unique(
            "uq_evidence_submission__source_object",
            ("tenant_id", "received_source_object_id"),
            "证据链唯一：一个接收来源对象至多形成一条不可变证据提交。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "evidence_submission",
            "received_source_object_id",
            "evidence",
            "received_source_object",
            "received_source_object_id",
            "证据链第二段：证据提交必须强关联同租户接收来源对象。",
        ),
        entity_fk("evidence_submission", "submitted_by_appointment_id", "identity", "appointment", "appointment_id", "提交主体必须是同租户准确Appointment。", suffix="submitter"),
    ),
)

_evidence_binding = tenant_table(
    "evidence",
    "evidence_binding",
    "evidence_binding_id",
    "证据绑定：一行把一个不可变提交按冻结用途绑定到冻结准确目标，事实Owner为EvidenceRuntime；只允许单向撤回，不移动目标、不改用途且不删除历史。",
    (
        uuid_col("evidence_submission_id", "证据提交标识：强关联同租户提交且一次提交至多一个绑定。"),
        code_col("purpose_code", "绑定用途：来自静态用途注册表，创建后冻结且不可改。"),
        uuid_col("bound_by_appointment_id", "绑定任职标识：最终四轴授权和Subject版本重验通过时执行绑定的Appointment。"),
        time_col("bound_at", "绑定时间：EvidenceRuntime创建目标绑定的数据库时间，创建后不可变。"),
        time_col("revoked_at", "撤回时间：空值表示有效；首次撤回时写入且不得清空或改写。", nullable=True),
        uuid_col("revoked_by_appointment_id", "撤回任职标识：授权撤回命令的准确Appointment；未撤回为空。", nullable=True),
        digest_col("revocation_authorization_digest", "撤回授权摘要：冻结撤回命令提交前四轴复验的单路径授权快照；未撤回为空。", nullable=True),
        code_col("revocation_reason_code", "撤回原因代码：仅撤回时必填，不保存文档正文或非必要案情。", nullable=True),
        revision_col(),
    ),
    typed_references=(_binding_target,),
    constraints=(
        check(
            "ck_evidence_binding__revocation_shape",
            "((revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL))",
            "撤回形态：撤回时间、任职、授权摘要和安全原因必须同时为空或一次性全部写入。",
        ),
        check(
            "ck_evidence_binding__revocation_order",
            "revoked_at IS NULL OR revoked_at >= bound_at",
            "撤回顺序：撤回时间不得早于绑定时间。",
        ),
        nonnegative_check("evidence_binding", "revision", "CAS修订号不得为负。"),
        unique(
            "uq_evidence_binding__submission",
            ("tenant_id", "evidence_submission_id"),
            "证据链唯一：一个不可变证据提交至多形成一个目标绑定。",
        ),
    ),
    foreign_keys=(
        entity_fk(
            "evidence_binding",
            "evidence_submission_id",
            "evidence",
            "evidence_submission",
            "evidence_submission_id",
            "证据链第三段：证据绑定必须强关联同租户不可变提交。",
        ),
        entity_fk("evidence_binding", "bound_by_appointment_id", "identity", "appointment", "appointment_id", "绑定主体必须是同租户准确Appointment。", suffix="binder"),
        entity_fk("evidence_binding", "revoked_by_appointment_id", "identity", "appointment", "appointment_id", "撤回主体若存在必须是同租户准确Appointment。", suffix="revoker"),
    ),
    indexes=(
        index(
            "ix_evidence_binding__active_target",
            ("tenant_id", "target_type", "target_id", "purpose_code"),
            "有效证据查询：按租户、准确目标标识和用途定位尚未撤回的绑定。",
            where="revoked_at IS NULL",
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code", "revision"),
    write_once_columns=("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code"),
)

EVIDENCE_SCHEMA = Schema(
    "evidence",
    "证据域：保存单文件上传会话、固定对象版本、不可变提交与固定目标用途绑定组成的严格一对一物理链。",
    (
        _upload_session,
        _received_source_object,
        _evidence_submission,
        _evidence_binding,
    ),
)


# party：跨业务流程共享的一张当前态主体锚点。
_party = tenant_table(
    "party",
    "party",
    "party_id",
    "主体锚点：一行保存自然人或组织当前规范名、至多一个受保护主标识及一跳合并指向，事实Owner为PartyRuntime；仅允许受控当前态更新，不是案件、客户关系或历史版本表。",
    (
        code_col("party_type", "主体类型：PERSON或ORGANIZATION，创建后不可变。", length=32),
        text_col("canonical_name", "规范名：当前用于检索和展示的名称，可受控更新；不得混入受保护主标识。"),
        code_col("primary_identifier_type", "主标识类型：静态类型代码；空值表示主体没有受保护主标识。", nullable=True),
        encrypted_col("primary_identifier_ciphertext", "主标识密文：应用层加密的唯一主标识；空值表示未设置，数据库不可解密。", nullable=True),
        digest_col("primary_identifier_hmac", "主标识HMAC：用于租户内精确匹配的32字节受保护摘要；空值表示未设置。", nullable=True),
        code_col("status", "主体状态：ACTIVE或MERGED，合并后不得恢复。", length=32),
        uuid_col("merged_into_party_id", "合并目标主体标识：仅MERGED时存在且直接指向最终活动主体，禁止多跳链。", nullable=True),
        time_col("merged_at", "合并时间：仅MERGED时存在，首次写入后不得清空或改写。", nullable=True),
        revision_col(),
    ),
    constraints=(
        enum_check(
            "party",
            "party_type",
            ("PERSON", "ORGANIZATION"),
            "主体类型域：只允许自然人或组织两种静态类型。",
        ),
        enum_check(
            "party",
            "status",
            ("ACTIVE", "MERGED"),
            "主体状态域：只允许活动或已合并，且状态转换只能单向发生。",
        ),
        check(
            "ck_party__primary_identifier_shape",
            "((primary_identifier_type IS NULL AND primary_identifier_ciphertext IS NULL AND primary_identifier_hmac IS NULL) OR (primary_identifier_type IS NOT NULL AND primary_identifier_ciphertext IS NOT NULL AND primary_identifier_hmac IS NOT NULL))",
            "主标识形态：一行至多容纳一个受保护主标识，其类型、密文和HMAC必须同时为空或同时存在。",
        ),
        check(
            "ck_party__merge_shape",
            "((status = 'ACTIVE' AND merged_into_party_id IS NULL AND merged_at IS NULL) OR (status = 'MERGED' AND merged_into_party_id IS NOT NULL AND merged_at IS NOT NULL))",
            "合并形态：活动主体没有合并指向；已合并主体必须同时记录直接目标和合并时间。",
        ),
        check(
            "ck_party__not_self_merge",
            "merged_into_party_id IS NULL OR merged_into_party_id <> party_id",
            "合并目标：主体不得合并到自身；目标必须由运行时复验为未合并的最终活动主体。",
        ),
        nonnegative_check("party", "revision", "CAS修订号不得为负。"),
    ),
    foreign_keys=(
        entity_fk(
            "party",
            "merged_into_party_id",
            "party",
            "party",
            "party_id",
            "一跳合并：合并行直接关联同租户目标主体；CommandRuntime必须复验目标仍为ACTIVE。",
            suffix="merged_into_party",
        ),
    ),
    indexes=(
        index(
            "ux_party__active_primary_identifier",
            ("tenant_id", "primary_identifier_type", "primary_identifier_hmac"),
            "受保护主标识匹配：活动主体的类型与HMAC组合在租户内唯一。",
            unique_=True,
            where="status = 'ACTIVE' AND primary_identifier_hmac IS NOT NULL",
        ),
        index(
            "ix_party__canonical_name",
            ("tenant_id", "canonical_name"),
            "主体检索：按租户和当前规范名定位活动或已合并主体锚点。",
        ),
        index(
            "ix_party__merge_target",
            ("tenant_id", "merged_into_party_id"),
            "合并追溯：定位直接并入某个最终活动主体的一跳来源。",
            where="merged_into_party_id IS NOT NULL",
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=(
        "canonical_name",
        "primary_identifier_type",
        "primary_identifier_ciphertext",
        "primary_identifier_hmac",
        "status",
        "merged_into_party_id",
        "merged_at",
        "revision",
    ),
    write_once_columns=("merged_into_party_id", "merged_at"),
    state_column="status",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "MERGED"),),
)

PARTY_SCHEMA = Schema(
    "party",
    "主体域：保存跨业务流程共享的当前态主体锚点、受保护主标识与一跳合并关系。",
    (_party,),
)


SCHEMAS = (
    EXECUTION_SCHEMA,
    EXTERNAL_ACTION_SCHEMA,
    EVIDENCE_SCHEMA,
    PARTY_SCHEMA,
)


__all__ = ["SCHEMAS"]
