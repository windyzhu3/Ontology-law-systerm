-- 执行域：保存永久命令占位、不可变终态回执、准确事实事件及带租约围栏的投递队列。

CREATE TABLE execution.command_execution_slot (
    tenant_id uuid NOT NULL,
    command_execution_slot_id uuid NOT NULL,
    command_id uuid NOT NULL,
    envelope_type varchar(64) NOT NULL,
    command_type varchar(64) NOT NULL,
    command_scope_digest bytea NOT NULL,
    payload_digest bytea NOT NULL,
    occupied_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_command_execution_slot PRIMARY KEY (tenant_id, command_execution_slot_id),
    CONSTRAINT uq_command_execution_slot__command_key UNIQUE (tenant_id, envelope_type, command_scope_digest, command_id),
    CONSTRAINT ck_command_execution_slot__command_scope_digest_length CHECK (octet_length(command_scope_digest) = 32),
    CONSTRAINT ck_command_execution_slot__payload_digest_length CHECK (octet_length(payload_digest) = 32)
);

COMMENT ON TABLE execution.command_execution_slot IS 'Fact Owner：CommandRuntime；命令执行占位：一行永久占用一个租户内命令标识，事实Owner为CommandRuntime；只可插入且永远无状态，不表示执行成功、失败或锁租约。';
COMMENT ON CONSTRAINT pk_command_execution_slot ON execution.command_execution_slot IS '主键：在租户内唯一标识一条command_execution_slot记录。';
COMMENT ON INDEX execution.pk_command_execution_slot IS '主键：在租户内唯一标识一条command_execution_slot记录。';
COMMENT ON COLUMN execution.command_execution_slot.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN execution.command_execution_slot.command_execution_slot_id IS '命令执行占位标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN execution.command_execution_slot.command_id IS '命令标识：调用方提供的稳定UUID，与静态信封和Scope共同形成永久占位键。';
COMMENT ON COLUMN execution.command_execution_slot.envelope_type IS '命令信封类型：四类静态注册信封之一，创建后不可变。';
COMMENT ON COLUMN execution.command_execution_slot.command_type IS '命令类型：来自静态命令注册表，创建后不可变。';
COMMENT ON COLUMN execution.command_execution_slot.command_scope_digest IS '命令Scope摘要：覆盖Tenant、命令种类和准确Subject范围，用于永久占位键。';
COMMENT ON COLUMN execution.command_execution_slot.payload_digest IS '载荷摘要：规范命令载荷的SHA-256，只用于同CommandId冲突判定。';
COMMENT ON COLUMN execution.command_execution_slot.occupied_at IS '占位时间：CommandRuntime首次接纳该命令标识的数据库时间，创建后不可变。';
COMMENT ON CONSTRAINT uq_command_execution_slot__command_key ON execution.command_execution_slot IS '永久占位唯一：Tenant、静态信封、命令Scope摘要与CommandId组合不得复用。';
COMMENT ON INDEX execution.uq_command_execution_slot__command_key IS '永久占位唯一：Tenant、静态信封、命令Scope摘要与CommandId组合不得复用。';
COMMENT ON CONSTRAINT ck_command_execution_slot__command_scope_digest_length ON execution.command_execution_slot IS '摘要格式：command_scope_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_command_execution_slot__payload_digest_length ON execution.command_execution_slot IS '摘要格式：payload_digest必须保存32字节的规范二进制值。';

CREATE TABLE execution.command_receipt (
    tenant_id uuid NOT NULL,
    command_receipt_id uuid NOT NULL,
    command_execution_slot_id uuid NOT NULL,
    outcome varchar(32) NOT NULL,
    rejection_code varchar(64),
    completed_at timestamptz(6) NOT NULL,
    result_fact_type varchar(64),
    result_fact_id uuid,
    result_fact_revision bigint,
    result_fact_hash bytea,
    CONSTRAINT pk_command_receipt PRIMARY KEY (tenant_id, command_receipt_id),
    CONSTRAINT ck_command_receipt__outcome CHECK (outcome IN ('SUCCEEDED', 'NO_CHANGE', 'REJECTED')),
    CONSTRAINT ck_command_receipt__outcome_result CHECK (((outcome IN ('SUCCEEDED', 'NO_CHANGE') AND result_fact_type IS NOT NULL AND rejection_code IS NULL) OR (outcome = 'REJECTED' AND result_fact_type IS NULL AND rejection_code IS NOT NULL))),
    CONSTRAINT uq_command_receipt__slot UNIQUE (tenant_id, command_execution_slot_id),
    CONSTRAINT ck_command_receipt__result_fact_exact CHECK (((result_fact_type IS NOT NULL AND result_fact_id IS NOT NULL AND ((result_fact_revision IS NOT NULL AND result_fact_revision >= 0 AND result_fact_hash IS NULL) OR (result_fact_revision IS NULL AND result_fact_hash IS NOT NULL))) OR (result_fact_type IS NULL AND result_fact_id IS NULL AND result_fact_revision IS NULL AND result_fact_hash IS NULL))),
    CONSTRAINT ck_command_receipt__result_fact_hash_length CHECK (octet_length(result_fact_hash) = 32)
);

COMMENT ON TABLE execution.command_receipt IS 'Fact Owner：CommandRuntime；命令终态回执：一行记录一个命令唯一且不可变的最终裁定，事实Owner为CommandRuntime；只可插入，不表示处理中状态。';
COMMENT ON CONSTRAINT pk_command_receipt ON execution.command_receipt IS '主键：在租户内唯一标识一条command_receipt记录。';
COMMENT ON INDEX execution.pk_command_receipt IS '主键：在租户内唯一标识一条command_receipt记录。';
COMMENT ON COLUMN execution.command_receipt.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN execution.command_receipt.command_receipt_id IS '命令终态回执标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN execution.command_receipt.command_execution_slot_id IS '命令执行槽标识：强关联唯一永久占位，创建后不可变。';
COMMENT ON COLUMN execution.command_receipt.outcome IS '终态结果：仅允许SUCCEEDED、NO_CHANGE或REJECTED，创建后不可变。';
COMMENT ON COLUMN execution.command_receipt.rejection_code IS '拒绝原因代码：仅REJECTED时必填，不保存输入正文、案情或密钥。';
COMMENT ON COLUMN execution.command_receipt.completed_at IS '完成时间：CommandRuntime形成终态裁定的数据库时间，创建后不可变。';
COMMENT ON COLUMN execution.command_receipt.result_fact_type IS '命令成功或无变化时产生或确认的结果事实的静态注册类型。';
COMMENT ON COLUMN execution.command_receipt.result_fact_id IS '命令成功或无变化时产生或确认的结果事实在所属租户内的准确标识。';
COMMENT ON COLUMN execution.command_receipt.result_fact_revision IS '命令成功或无变化时产生或确认的结果事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN execution.command_receipt.result_fact_hash IS '命令成功或无变化时产生或确认的结果事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_command_receipt__outcome ON execution.command_receipt IS '回执终态：只允许成功、无变化或拒绝，不存在处理中或可回退状态。';
COMMENT ON CONSTRAINT ck_command_receipt__outcome_result ON execution.command_receipt IS '结果准确性：成功和无变化必须引用准确结果事实且不得带拒绝码；拒绝不得引用结果事实且必须带安全原因代码。';
COMMENT ON CONSTRAINT uq_command_receipt__slot ON execution.command_receipt IS '命令终局唯一：一个永久命令占位至多形成一张终态回执。';
COMMENT ON INDEX execution.uq_command_receipt__slot IS '命令终局唯一：一个永久命令占位至多形成一张终态回执。';
COMMENT ON CONSTRAINT ck_command_receipt__result_fact_exact ON execution.command_receipt IS '准确引用：命令成功或无变化时产生或确认的结果事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_command_receipt__result_fact_hash_length ON execution.command_receipt IS '摘要格式：result_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE execution.domain_event (
    tenant_id uuid NOT NULL,
    domain_event_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    event_schema_version integer NOT NULL,
    event_payload jsonb NOT NULL,
    payload_digest bytea NOT NULL,
    command_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    causation_event_id uuid,
    occurred_at timestamptz(6) NOT NULL,
    source_fact_type varchar(64) NOT NULL,
    source_fact_id uuid NOT NULL,
    source_fact_revision bigint,
    source_fact_hash bytea,
    CONSTRAINT pk_domain_event PRIMARY KEY (tenant_id, domain_event_id),
    CONSTRAINT ck_domain_event__schema_version CHECK (event_schema_version > 0),
    CONSTRAINT ck_domain_event__source_fact_exact CHECK ((source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL)))),
    CONSTRAINT ck_domain_event__payload_digest_length CHECK (octet_length(payload_digest) = 32),
    CONSTRAINT ck_domain_event__source_fact_hash_length CHECK (octet_length(source_fact_hash) = 32)
);

COMMENT ON TABLE execution.domain_event IS 'Fact Owner：CommandRuntime；领域事件通知：一行仅声明某个准确来源事实发生了静态类型事件，事实Owner为提交该事实的CommandRuntime；只可插入，不复制业务事实、文档正文或密钥。';
COMMENT ON CONSTRAINT pk_domain_event ON execution.domain_event IS '主键：在租户内唯一标识一条domain_event记录。';
COMMENT ON INDEX execution.pk_domain_event IS '主键：在租户内唯一标识一条domain_event记录。';
COMMENT ON COLUMN execution.domain_event.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN execution.domain_event.domain_event_id IS '领域事件通知标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN execution.domain_event.event_type IS '事件类型：来自静态事件注册表，创建后不可变。';
COMMENT ON COLUMN execution.domain_event.event_schema_version IS '事件Schema版本：解释通知载荷的正整数静态版本。';
COMMENT ON COLUMN execution.domain_event.event_payload IS '事件通知载荷：只保存允许列表化路由信息，不复制领域事实或文档正文。';
COMMENT ON COLUMN execution.domain_event.payload_digest IS '事件载荷摘要：规范化通知JSON的32字节SHA-256。';
COMMENT ON COLUMN execution.domain_event.command_id IS '来源命令标识：把事件关联到同事务命令，不作为业务Fact外键。';
COMMENT ON COLUMN execution.domain_event.correlation_id IS '关联标识：贯穿一次业务请求和下游通知链。';
COMMENT ON COLUMN execution.domain_event.causation_event_id IS '上游事件标识：由另一个事件触发时记录；非事件触发时为空。';
COMMENT ON COLUMN execution.domain_event.occurred_at IS '发生时间：来源事实与事件在同一事务提交时记录的数据库时间，创建后不可变。';
COMMENT ON COLUMN execution.domain_event.source_fact_type IS '领域事件所通知的唯一来源事实的静态注册类型。';
COMMENT ON COLUMN execution.domain_event.source_fact_id IS '领域事件所通知的唯一来源事实在所属租户内的准确标识。';
COMMENT ON COLUMN execution.domain_event.source_fact_revision IS '领域事件所通知的唯一来源事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN execution.domain_event.source_fact_hash IS '领域事件所通知的唯一来源事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_domain_event__schema_version ON execution.domain_event IS '事件Schema版本必须为正数。';
COMMENT ON CONSTRAINT ck_domain_event__source_fact_exact ON execution.domain_event IS '准确引用：领域事件所通知的唯一来源事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_domain_event__payload_digest_length ON execution.domain_event IS '摘要格式：payload_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_domain_event__source_fact_hash_length ON execution.domain_event IS '摘要格式：source_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE execution.domain_event_outbox (
    tenant_id uuid NOT NULL,
    domain_event_outbox_id uuid NOT NULL,
    domain_event_id uuid NOT NULL,
    queue_owner varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    available_at timestamptz(6) NOT NULL,
    lease_owner varchar(64),
    lease_until timestamptz(6),
    fencing_token bigint DEFAULT 0 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    delivered_at timestamptz(6),
    last_error_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_domain_event_outbox PRIMARY KEY (tenant_id, domain_event_outbox_id),
    CONSTRAINT ck_domain_event_outbox__status CHECK (status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'EXHAUSTED')),
    CONSTRAINT ck_domain_event_outbox__lease_shape CHECK (((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))),
    CONSTRAINT ck_domain_event_outbox__delivered_at CHECK (((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))),
    CONSTRAINT ck_domain_event_outbox__fencing_token_nonnegative CHECK (fencing_token >= 0),
    CONSTRAINT ck_domain_event_outbox__attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT ck_domain_event_outbox__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_domain_event_outbox__event_owner UNIQUE (tenant_id, domain_event_id, queue_owner)
);

COMMENT ON TABLE execution.domain_event_outbox IS 'Fact Owner：OutboxDispatcher；领域事件投递队列：一行代表一个事件向一个静态队列Owner的唯一投递，Owner为OutboxDispatcher；仅允许租约围栏式受控更新，不是领域事实副本。';
COMMENT ON CONSTRAINT pk_domain_event_outbox ON execution.domain_event_outbox IS '主键：在租户内唯一标识一条domain_event_outbox记录。';
COMMENT ON INDEX execution.pk_domain_event_outbox IS '主键：在租户内唯一标识一条domain_event_outbox记录。';
COMMENT ON COLUMN execution.domain_event_outbox.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN execution.domain_event_outbox.domain_event_outbox_id IS '领域事件投递队列标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN execution.domain_event_outbox.domain_event_id IS '领域事件标识：强关联同租户不可变事件，创建后不可变。';
COMMENT ON COLUMN execution.domain_event_outbox.queue_owner IS '队列Owner：静态消费者通道代码，同一事件与Owner只允许一行，创建后不可变。';
COMMENT ON COLUMN execution.domain_event_outbox.status IS '队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。';
COMMENT ON COLUMN execution.domain_event_outbox.available_at IS '可领取时间：PENDING重试时可由队列CAS推进，其他事实列不可借此改写。';
COMMENT ON COLUMN execution.domain_event_outbox.lease_owner IS '租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。';
COMMENT ON COLUMN execution.domain_event_outbox.lease_until IS '租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。';
COMMENT ON COLUMN execution.domain_event_outbox.fencing_token IS '围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。';
COMMENT ON COLUMN execution.domain_event_outbox.attempt_count IS '投递尝试次数：非负且仅由队列CAS递增。';
COMMENT ON COLUMN execution.domain_event_outbox.delivered_at IS '投递完成时间：仅DELIVERED终态存在，首次写入后不可改。';
COMMENT ON COLUMN execution.domain_event_outbox.last_error_code IS '最近一次安全错误代码：不得保存Secret、Token、正文或非必要案情。';
COMMENT ON COLUMN execution.domain_event_outbox.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__status ON execution.domain_event_outbox IS '队列状态域：只允许待领取、已领取、已投递和耗尽四种机器状态。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__lease_shape ON execution.domain_event_outbox IS '租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__delivered_at ON execution.domain_event_outbox IS '投递终态：只有DELIVERED必须且可以记录完成时间。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__fencing_token_nonnegative ON execution.domain_event_outbox IS '围栏令牌不得为负。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__attempt_count_nonnegative ON execution.domain_event_outbox IS '投递尝试次数不得为负。';
COMMENT ON CONSTRAINT ck_domain_event_outbox__revision_nonnegative ON execution.domain_event_outbox IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT uq_domain_event_outbox__event_owner ON execution.domain_event_outbox IS '投递唯一：每个事件与队列Owner组合至多存在一行。';
COMMENT ON INDEX execution.uq_domain_event_outbox__event_owner IS '投递唯一：每个事件与队列Owner组合至多存在一行。';
