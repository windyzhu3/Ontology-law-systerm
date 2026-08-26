-- 外部动作域：保存一次性外部效果尝试、派发或探测队列，以及验签后不可变的Provider入站事件指纹。

CREATE TABLE external_action.external_action (
    tenant_id uuid NOT NULL,
    external_action_id uuid NOT NULL,
    action_contract_code varchar(64) NOT NULL,
    action_contract_version integer NOT NULL,
    provider_code varchar(64) NOT NULL,
    provider_account_id uuid NOT NULL,
    request_envelope jsonb NOT NULL,
    request_digest bytea NOT NULL,
    intent_key varchar(160) NOT NULL,
    attempt_no integer NOT NULL,
    provider_idempotency_key varchar(160) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    dispatched_at timestamptz(6),
    provider_action_id text,
    completed_at timestamptz(6),
    result_code varchar(64),
    result_digest bytea,
    resolution_method_code varchar(64),
    last_error_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    subject_revision bigint,
    subject_hash bytea,
    resolution_source_type varchar(64),
    resolution_source_id uuid,
    resolution_source_revision bigint,
    resolution_source_hash bytea,
    CONSTRAINT pk_external_action PRIMARY KEY (tenant_id, external_action_id),
    CONSTRAINT ck_external_action__status CHECK (status IN ('PENDING', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_external_action__dispatch_shape CHECK (((status = 'PENDING' AND dispatched_at IS NULL) OR (status IN ('DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN') AND dispatched_at IS NOT NULL))),
    CONSTRAINT ck_external_action__completion_shape CHECK (((status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL AND result_code IS NOT NULL AND result_digest IS NOT NULL AND ((resolution_method_code = 'PROVIDER_INBOX' AND resolution_source_type = 'external_action.provider_inbox') OR (resolution_method_code = 'DECISION' AND resolution_source_type = 'responsibility.decision_record') OR (resolution_method_code = 'PROBE' AND resolution_source_type IS NULL))) OR (status IN ('PENDING', 'DISPATCHED', 'UNKNOWN') AND completed_at IS NULL AND result_code IS NULL AND result_digest IS NULL AND resolution_method_code IS NULL AND resolution_source_type IS NULL))),
    CONSTRAINT ck_external_action__resolution_method_code CHECK (resolution_method_code IN ('PROVIDER_INBOX', 'PROBE', 'DECISION')),
    CONSTRAINT ck_external_action__contract_version CHECK (action_contract_version > 0),
    CONSTRAINT ck_external_action__attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_external_action__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_external_action__provider_idempotency UNIQUE (tenant_id, provider_account_id, provider_idempotency_key),
    CONSTRAINT uq_external_action__intent_attempt UNIQUE (tenant_id, intent_key, attempt_no),
    CONSTRAINT ck_external_action__subject_exact CHECK ((subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))),
    CONSTRAINT ck_external_action__resolution_source_exact CHECK (((resolution_source_type IS NOT NULL AND resolution_source_id IS NOT NULL AND ((resolution_source_revision IS NOT NULL AND resolution_source_revision >= 0 AND resolution_source_hash IS NULL) OR (resolution_source_revision IS NULL AND resolution_source_hash IS NOT NULL))) OR (resolution_source_type IS NULL AND resolution_source_id IS NULL AND resolution_source_revision IS NULL AND resolution_source_hash IS NULL))),
    CONSTRAINT ck_external_action__request_digest_length CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_external_action__result_digest_length CHECK (octet_length(result_digest) = 32),
    CONSTRAINT ck_external_action__subject_hash_length CHECK (octet_length(subject_hash) = 32),
    CONSTRAINT ck_external_action__resolution_source_hash_length CHECK (octet_length(resolution_source_hash) = 32)
);

COMMENT ON TABLE external_action.external_action IS 'Fact Owner：ExternalActionRuntime；外部效果尝试：一行冻结一个准确Subject、版本化动作合同、Provider账号、规范请求、稳定意图和一次attempt；状态只单向收敛，UNKNOWN不得恢复PENDING。';
COMMENT ON CONSTRAINT pk_external_action ON external_action.external_action IS '主键：在租户内唯一标识一条external_action记录。';
COMMENT ON INDEX external_action.pk_external_action IS '主键：在租户内唯一标识一条external_action记录。';
COMMENT ON COLUMN external_action.external_action.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN external_action.external_action.external_action_id IS '外部效果尝试标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN external_action.external_action.action_contract_code IS '动作合同代码：静态注册的外部效果请求与结果Schema。';
COMMENT ON COLUMN external_action.external_action.action_contract_version IS '动作合同版本：解释本次请求Envelope和结果的正整数版本。';
COMMENT ON COLUMN external_action.external_action.provider_code IS 'Provider代码：标识静态适配器，创建后不可变。';
COMMENT ON COLUMN external_action.external_action.provider_account_id IS 'Provider账户标识：租户内配置账户的稳定标识，创建后不可变。';
COMMENT ON COLUMN external_action.external_action.request_envelope IS '规范请求Envelope：按动作合同校验的允许列表JSON，不保存Secret、Token或非必要正文。';
COMMENT ON COLUMN external_action.external_action.request_digest IS '请求摘要：规范请求Envelope的32字节SHA-256，用于冲突判定。';
COMMENT ON COLUMN external_action.external_action.intent_key IS '稳定意图键：同一业务意图跨attempt保持不变，非Provider凭据。';
COMMENT ON COLUMN external_action.external_action.attempt_no IS '尝试序号：同一intentKey下从一开始递增，每行只代表一次不可重开的外部效果尝试。';
COMMENT ON COLUMN external_action.external_action.provider_idempotency_key IS 'Provider幂等键：该attempt使用的稳定非秘密键，创建后不可变。';
COMMENT ON COLUMN external_action.external_action.status IS '动作状态：PENDING、DISPATCHED、SUCCEEDED、FAILED或UNKNOWN。';
COMMENT ON COLUMN external_action.external_action.created_at IS '创建时间：CommandRuntime批准该次外部效果尝试的数据库时间，创建后不可变。';
COMMENT ON COLUMN external_action.external_action.dispatched_at IS '网络边界时间：请求首次可能越过网络边界时写入；即使崩溃后只能判定UNKNOWN也不得为空。';
COMMENT ON COLUMN external_action.external_action.provider_action_id IS 'Provider动作标识：Provider返回的非秘密远端标识，首次写入后不可改。';
COMMENT ON COLUMN external_action.external_action.completed_at IS '收敛时间：SUCCEEDED或FAILED终态形成时写入，空值表示未收敛。';
COMMENT ON COLUMN external_action.external_action.result_code IS 'Provider安全结果代码：成功或失败收敛时可写入，不保存响应正文。';
COMMENT ON COLUMN external_action.external_action.result_digest IS '外部结果摘要：收敛时覆盖可信结果证明的32字节摘要。';
COMMENT ON COLUMN external_action.external_action.resolution_method_code IS '收敛方法：PROVIDER_INBOX、PROBE或DECISION；只有SUCCEEDED/FAILED终态存在。';
COMMENT ON COLUMN external_action.external_action.last_error_code IS '最近安全错误代码：不得保存Secret、Token、响应正文或非必要案情。';
COMMENT ON COLUMN external_action.external_action.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN external_action.external_action.subject_type IS '本次外部效果尝试所作用的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN external_action.external_action.subject_id IS '本次外部效果尝试所作用的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN external_action.external_action.subject_revision IS '本次外部效果尝试所作用的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN external_action.external_action.subject_hash IS '本次外部效果尝试所作用的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN external_action.external_action.resolution_source_type IS 'SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的静态注册类型。';
COMMENT ON COLUMN external_action.external_action.resolution_source_id IS 'SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision在所属租户内的准确标识。';
COMMENT ON COLUMN external_action.external_action.resolution_source_revision IS 'SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN external_action.external_action.resolution_source_hash IS 'SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_external_action__status ON external_action.external_action IS '动作状态域：只允许待派发、已派发、成功、失败和未知五种状态。';
COMMENT ON CONSTRAINT ck_external_action__dispatch_shape ON external_action.external_action IS '派发证据：PENDING尚未确认派发；其余状态必须保留首次派发时间。';
COMMENT ON CONSTRAINT ck_external_action__completion_shape ON external_action.external_action IS '收敛证据：ProviderInbox和Decision必须引用准确Fact；无副作用权威PROBE以本行结果摘要和同事务Audit证明且不得伪造来源Fact。';
COMMENT ON CONSTRAINT ck_external_action__resolution_method_code ON external_action.external_action IS '收敛方法只允许验签Provider消息、无副作用权威探测或授权裁决。';
COMMENT ON CONSTRAINT ck_external_action__contract_version ON external_action.external_action IS '动作合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_external_action__attempt_no ON external_action.external_action IS '同一意图下的尝试序号必须为正数。';
COMMENT ON CONSTRAINT ck_external_action__revision_nonnegative ON external_action.external_action IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT uq_external_action__provider_idempotency ON external_action.external_action IS 'Provider幂等：同一租户Provider账户内一个幂等键只代表一次外部效果尝试。';
COMMENT ON INDEX external_action.uq_external_action__provider_idempotency IS 'Provider幂等：同一租户Provider账户内一个幂等键只代表一次外部效果尝试。';
COMMENT ON CONSTRAINT uq_external_action__intent_attempt ON external_action.external_action IS '业务尝试唯一：同一稳定意图下attemptNo不得重复。';
COMMENT ON INDEX external_action.uq_external_action__intent_attempt IS '业务尝试唯一：同一稳定意图下attemptNo不得重复。';
COMMENT ON CONSTRAINT ck_external_action__subject_exact ON external_action.external_action IS '准确引用：本次外部效果尝试所作用的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_external_action__resolution_source_exact ON external_action.external_action IS '准确引用：SUCCEEDED或FAILED收敛所依据的可信ProviderInbox或授权Decision必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_external_action__request_digest_length ON external_action.external_action IS '摘要格式：request_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_external_action__result_digest_length ON external_action.external_action IS '摘要格式：result_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_external_action__subject_hash_length ON external_action.external_action IS '摘要格式：subject_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_external_action__resolution_source_hash_length ON external_action.external_action IS '摘要格式：resolution_source_hash必须保存32字节的规范二进制值。';

CREATE TABLE external_action.external_action_outbox (
    tenant_id uuid NOT NULL,
    external_action_outbox_id uuid NOT NULL,
    external_action_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    available_at timestamptz(6) NOT NULL,
    lease_owner varchar(64),
    lease_until timestamptz(6),
    fencing_token bigint DEFAULT 0 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    delivered_at timestamptz(6),
    last_error_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_external_action_outbox PRIMARY KEY (tenant_id, external_action_outbox_id),
    CONSTRAINT ck_external_action_outbox__operation CHECK (operation IN ('DISPATCH', 'PROBE')),
    CONSTRAINT ck_external_action_outbox__status CHECK (status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'EXHAUSTED')),
    CONSTRAINT ck_external_action_outbox__lease_shape CHECK (((status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL))),
    CONSTRAINT ck_external_action_outbox__delivered_at CHECK (((status = 'DELIVERED' AND delivered_at IS NOT NULL) OR (status <> 'DELIVERED' AND delivered_at IS NULL))),
    CONSTRAINT ck_external_action_outbox__fencing_token_nonnegative CHECK (fencing_token >= 0),
    CONSTRAINT ck_external_action_outbox__attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT ck_external_action_outbox__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_external_action_outbox__action_operation UNIQUE (tenant_id, external_action_id, operation)
);

COMMENT ON TABLE external_action.external_action_outbox IS 'Fact Owner：ExternalActionDispatcher；外部动作队列：一行代表一个动作的DISPATCH或PROBE唯一工作项，Owner为ExternalActionDispatcher；仅允许租约围栏式受控更新，不产生第二次外部效果尝试。';
COMMENT ON CONSTRAINT pk_external_action_outbox ON external_action.external_action_outbox IS '主键：在租户内唯一标识一条external_action_outbox记录。';
COMMENT ON INDEX external_action.pk_external_action_outbox IS '主键：在租户内唯一标识一条external_action_outbox记录。';
COMMENT ON COLUMN external_action.external_action_outbox.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN external_action.external_action_outbox.external_action_outbox_id IS '外部动作队列标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN external_action.external_action_outbox.external_action_id IS '外部动作标识：强关联同租户的一次效果尝试，创建后不可变。';
COMMENT ON COLUMN external_action.external_action_outbox.operation IS '工作类型：DISPATCH派发一次效果，PROBE仅探测UNKNOWN结果，创建后不可变。';
COMMENT ON COLUMN external_action.external_action_outbox.status IS '队列状态：PENDING、CLAIMED、DELIVERED或EXHAUSTED。';
COMMENT ON COLUMN external_action.external_action_outbox.available_at IS '可领取时间：PENDING重试或延迟探测时可由队列CAS推进。';
COMMENT ON COLUMN external_action.external_action_outbox.lease_owner IS '租约持有者代码：仅CLAIMED时存在，不保存凭据或主机秘密。';
COMMENT ON COLUMN external_action.external_action_outbox.lease_until IS '租约截止时间：仅CLAIMED时存在，过期后可通过CAS重新领取。';
COMMENT ON COLUMN external_action.external_action_outbox.fencing_token IS '围栏令牌：每次成功领取严格递增，用于拒绝过期Worker提交。';
COMMENT ON COLUMN external_action.external_action_outbox.attempt_count IS '工作尝试次数：非负且仅由队列CAS递增。';
COMMENT ON COLUMN external_action.external_action_outbox.delivered_at IS '工作项完成时间：仅DELIVERED终态存在，首次写入后不可改。';
COMMENT ON COLUMN external_action.external_action_outbox.last_error_code IS '最近安全错误代码：不得保存Secret、Token、正文或非必要案情。';
COMMENT ON COLUMN external_action.external_action_outbox.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_external_action_outbox__operation ON external_action.external_action_outbox IS '工作类型域：只允许一次派发工作或UNKNOWN结果探测工作。';
COMMENT ON CONSTRAINT ck_external_action_outbox__status ON external_action.external_action_outbox IS '队列状态域：只允许待领取、已领取、已完成和耗尽四种机器状态。';
COMMENT ON CONSTRAINT ck_external_action_outbox__lease_shape ON external_action.external_action_outbox IS '租约形态：只有CLAIMED行同时持有租约Owner与截止时间，离开领取态必须清空二者。';
COMMENT ON CONSTRAINT ck_external_action_outbox__delivered_at ON external_action.external_action_outbox IS '工作终态：只有DELIVERED必须且可以记录完成时间。';
COMMENT ON CONSTRAINT ck_external_action_outbox__fencing_token_nonnegative ON external_action.external_action_outbox IS '围栏令牌不得为负。';
COMMENT ON CONSTRAINT ck_external_action_outbox__attempt_count_nonnegative ON external_action.external_action_outbox IS '工作尝试次数不得为负。';
COMMENT ON CONSTRAINT ck_external_action_outbox__revision_nonnegative ON external_action.external_action_outbox IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT uq_external_action_outbox__action_operation ON external_action.external_action_outbox IS '工作唯一：每个外部动作的DISPATCH与PROBE各至多存在一行。';
COMMENT ON INDEX external_action.uq_external_action_outbox__action_operation IS '工作唯一：每个外部动作的DISPATCH与PROBE各至多存在一行。';

CREATE TABLE external_action.provider_inbox (
    tenant_id uuid NOT NULL,
    provider_inbox_id uuid NOT NULL,
    provider_code varchar(64) NOT NULL,
    provider_account_id uuid NOT NULL,
    provider_event_id text NOT NULL,
    provider_event_type varchar(64) NOT NULL,
    payload_digest bytea NOT NULL,
    nonce_digest bytea NOT NULL,
    signature_method_code varchar(64) NOT NULL,
    message_schema_version integer NOT NULL,
    normalized_message jsonb NOT NULL,
    normalized_message_digest bytea NOT NULL,
    external_action_id uuid,
    provider_occurred_at timestamptz(6),
    signature_verified_at timestamptz(6) NOT NULL,
    received_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_provider_inbox PRIMARY KEY (tenant_id, provider_inbox_id),
    CONSTRAINT ck_provider_inbox__schema_version CHECK (message_schema_version > 0),
    CONSTRAINT uq_provider_inbox__account_event UNIQUE (tenant_id, provider_account_id, provider_event_id),
    CONSTRAINT uq_provider_inbox__account_nonce UNIQUE (tenant_id, provider_account_id, nonce_digest),
    CONSTRAINT ck_provider_inbox__payload_digest_length CHECK (octet_length(payload_digest) = 32),
    CONSTRAINT ck_provider_inbox__nonce_digest_length CHECK (octet_length(nonce_digest) = 32),
    CONSTRAINT ck_provider_inbox__normalized_message_digest_length CHECK (octet_length(normalized_message_digest) = 32)
);

COMMENT ON TABLE external_action.provider_inbox IS 'Fact Owner：ProviderIngress；Provider入站事实：一行保存一个Provider账户已通过验签的不可变事件指纹，事实Owner为ProviderIngress；只可插入，不表示事件已被业务接受或成功处理。';
COMMENT ON CONSTRAINT pk_provider_inbox ON external_action.provider_inbox IS '主键：在租户内唯一标识一条provider_inbox记录。';
COMMENT ON INDEX external_action.pk_provider_inbox IS '主键：在租户内唯一标识一条provider_inbox记录。';
COMMENT ON COLUMN external_action.provider_inbox.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN external_action.provider_inbox.provider_inbox_id IS 'Provider入站事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN external_action.provider_inbox.provider_code IS 'Provider代码：标识完成验签的静态适配器，创建后不可变。';
COMMENT ON COLUMN external_action.provider_inbox.provider_account_id IS 'Provider账户标识：验签所使用租户配置账户的稳定标识，创建后不可变。';
COMMENT ON COLUMN external_action.provider_inbox.provider_event_id IS 'Provider事件标识：验签载荷声明的稳定非秘密标识，创建后不可变。';
COMMENT ON COLUMN external_action.provider_inbox.provider_event_type IS 'Provider事件类型：验签后解析出的静态类型代码，创建后不可变。';
COMMENT ON COLUMN external_action.provider_inbox.payload_digest IS '载荷摘要：验签原始字节的SHA-256，仅用于证据核对，不保存正文。';
COMMENT ON COLUMN external_action.provider_inbox.nonce_digest IS 'Nonce摘要：验签窗口内去重使用的32字节摘要，不保存原始Nonce。';
COMMENT ON COLUMN external_action.provider_inbox.signature_method_code IS '验签方法：静态注册的Provider签名算法和账号绑定方式。';
COMMENT ON COLUMN external_action.provider_inbox.message_schema_version IS '消息Schema版本：解释允许列表化规范消息的正整数版本。';
COMMENT ON COLUMN external_action.provider_inbox.normalized_message IS '规范消息：验签和Schema校验后的允许列表JSON，不保存原始请求、Token或Secret。';
COMMENT ON COLUMN external_action.provider_inbox.normalized_message_digest IS '规范消息摘要：用于同ProviderEventId同Hash返回原结果、异Hash隔离。';
COMMENT ON COLUMN external_action.provider_inbox.external_action_id IS '准确外部动作标识：接入时可证明关联Action及Subject时填写，否则为空且不得推进业务。';
COMMENT ON COLUMN external_action.provider_inbox.provider_occurred_at IS 'Provider发生时间：验签载荷声明的时间；Provider未提供时为空。';
COMMENT ON COLUMN external_action.provider_inbox.signature_verified_at IS '验签时间：ProviderIngress确认签名有效的数据库时间，创建后不可变。';
COMMENT ON COLUMN external_action.provider_inbox.received_at IS '接收时间：系统首次持久化该已验签事件的数据库时间，创建后不可变。';
COMMENT ON CONSTRAINT ck_provider_inbox__schema_version ON external_action.provider_inbox IS 'Provider消息Schema版本必须为正数。';
COMMENT ON CONSTRAINT uq_provider_inbox__account_event ON external_action.provider_inbox IS 'Provider去重：租户、Provider账户与Provider事件标识组合全局唯一。';
COMMENT ON INDEX external_action.uq_provider_inbox__account_event IS 'Provider去重：租户、Provider账户与Provider事件标识组合全局唯一。';
COMMENT ON CONSTRAINT uq_provider_inbox__account_nonce ON external_action.provider_inbox IS 'Nonce防重：同一Provider账户不得重复接受相同Nonce摘要；时间窗口仍由ProviderIngress先行校验。';
COMMENT ON INDEX external_action.uq_provider_inbox__account_nonce IS 'Nonce防重：同一Provider账户不得重复接受相同Nonce摘要；时间窗口仍由ProviderIngress先行校验。';
COMMENT ON CONSTRAINT ck_provider_inbox__payload_digest_length ON external_action.provider_inbox IS '摘要格式：payload_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_provider_inbox__nonce_digest_length ON external_action.provider_inbox IS '摘要格式：nonce_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_provider_inbox__normalized_message_digest_length ON external_action.provider_inbox IS '摘要格式：normalized_message_digest必须保存32字节的规范二进制值。';
