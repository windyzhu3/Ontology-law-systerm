-- 审计域：只追加不可变审计事实，以准确类型化引用冻结对象、授权依据及更正目标。

CREATE TABLE audit.audit_entry (
    tenant_id uuid NOT NULL,
    audit_entry_id uuid NOT NULL,
    entry_type varchar(64) NOT NULL,
    audit_scope_code varchar(64) NOT NULL,
    trusted_at timestamptz(6) NOT NULL,
    action_code varchar(64) NOT NULL,
    result_code varchar(64) NOT NULL,
    actor_principal_id uuid NOT NULL,
    actor_appointment_id uuid,
    on_behalf_of_principal_id uuid,
    on_behalf_of_appointment_id uuid,
    command_id uuid,
    command_type varchar(64),
    correlation_id uuid NOT NULL,
    causation_id uuid,
    authorization_slot_code varchar(64) NOT NULL,
    authorization_path_code varchar(64) NOT NULL,
    authorization_scope_organization_unit_id uuid,
    authorization_snapshot_digest bytea NOT NULL,
    trace_id uuid NOT NULL,
    service_role_code varchar(64) NOT NULL,
    execution_node_code varchar(128) NOT NULL,
    session_id_hmac bytea,
    client_ip_ciphertext bytea,
    summary_schema_code varchar(64) NOT NULL,
    summary_schema_version integer NOT NULL,
    change_summary jsonb NOT NULL,
    change_summary_digest bytea NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    subject_revision bigint,
    subject_hash bytea,
    correction_target_type varchar(64),
    correction_target_id uuid,
    correction_target_revision bigint,
    correction_target_hash bytea,
    authorization_fact_type varchar(64),
    authorization_fact_id uuid,
    authorization_fact_revision bigint,
    authorization_fact_hash bytea,
    CONSTRAINT pk_audit_entry PRIMARY KEY (tenant_id, audit_entry_id),
    CONSTRAINT ck_audit_entry__entry_type CHECK (entry_type IN ('EVENT', 'CORRECTION')),
    CONSTRAINT ck_audit_entry__result_code CHECK (result_code IN ('SUCCEEDED', 'NO_CHANGE', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_audit_entry__command_pair CHECK ((command_id IS NULL AND command_type IS NULL) OR (command_id IS NOT NULL AND command_type IS NOT NULL)),
    CONSTRAINT ck_audit_entry__on_behalf_pair CHECK ((on_behalf_of_principal_id IS NULL AND on_behalf_of_appointment_id IS NULL) OR (on_behalf_of_principal_id IS NOT NULL AND on_behalf_of_appointment_id IS NOT NULL)),
    CONSTRAINT ck_audit_entry__correction_shape CHECK ((entry_type = 'EVENT' AND correction_target_type IS NULL) OR (entry_type = 'CORRECTION' AND correction_target_type IS NOT NULL)),
    CONSTRAINT ck_audit_entry__summary_schema_version CHECK (summary_schema_version > 0),
    CONSTRAINT ck_audit_entry__subject_exact CHECK ((subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))),
    CONSTRAINT ck_audit_entry__correction_target_exact CHECK (((correction_target_type IS NOT NULL AND correction_target_id IS NOT NULL AND ((correction_target_revision IS NOT NULL AND correction_target_revision >= 0 AND correction_target_hash IS NULL) OR (correction_target_revision IS NULL AND correction_target_hash IS NOT NULL))) OR (correction_target_type IS NULL AND correction_target_id IS NULL AND correction_target_revision IS NULL AND correction_target_hash IS NULL))),
    CONSTRAINT ck_audit_entry__authorization_fact_exact CHECK (((authorization_fact_type IS NOT NULL AND authorization_fact_id IS NOT NULL AND ((authorization_fact_revision IS NOT NULL AND authorization_fact_revision >= 0 AND authorization_fact_hash IS NULL) OR (authorization_fact_revision IS NULL AND authorization_fact_hash IS NOT NULL))) OR (authorization_fact_type IS NULL AND authorization_fact_id IS NULL AND authorization_fact_revision IS NULL AND authorization_fact_hash IS NULL))),
    CONSTRAINT ck_audit_entry__authorization_snapshot_digest_length CHECK (octet_length(authorization_snapshot_digest) = 32),
    CONSTRAINT ck_audit_entry__session_id_hmac_length CHECK (octet_length(session_id_hmac) = 32),
    CONSTRAINT ck_audit_entry__change_summary_digest_length CHECK (octet_length(change_summary_digest) = 32),
    CONSTRAINT ck_audit_entry__subject_hash_length CHECK (octet_length(subject_hash) = 32),
    CONSTRAINT ck_audit_entry__correction_target_hash_length CHECK (octet_length(correction_target_hash) = 32),
    CONSTRAINT ck_audit_entry__authorization_fact_hash_length CHECK (octet_length(authorization_fact_hash) = 32)
);

COMMENT ON TABLE audit.audit_entry IS 'Fact Owner：AuditAppender；审计条目：一行冻结谁在何种准确Scope、单一路径授权和可信执行上下文下做了什么及其结果；只能追加，CORRECTION准确引用原条目，不复制领域事实、请求响应或正文。';
COMMENT ON CONSTRAINT pk_audit_entry ON audit.audit_entry IS '主键：在租户内唯一标识一条audit_entry记录。';
COMMENT ON INDEX audit.pk_audit_entry IS '主键：在租户内唯一标识一条audit_entry记录。';
COMMENT ON COLUMN audit.audit_entry.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN audit.audit_entry.audit_entry_id IS '审计条目标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN audit.audit_entry.entry_type IS '条目类型：EVENT表示原始审计事实，CORRECTION表示对一条原记录的单链修正。';
COMMENT ON COLUMN audit.audit_entry.audit_scope_code IS '审计Scope：静态分类的租户、组织、对象或安全管理范围。';
COMMENT ON COLUMN audit.audit_entry.trusted_at IS '可信时间：被审计写入、拒绝或披露提交审计事务的服务端时间。';
COMMENT ON COLUMN audit.audit_entry.action_code IS '动作代码：来自静态审计动作注册表，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry.result_code IS '结果代码：SUCCEEDED、NO_CHANGE、REJECTED或FAILED，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry.actor_principal_id IS '实际发起身份主体标识：以同租户复合外键关联身份主体，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry.actor_appointment_id IS '实际采用的任职标识：以同租户复合外键关联任职；不适用时为空。';
COMMENT ON COLUMN audit.audit_entry.on_behalf_of_principal_id IS '被代表Principal标识：非代办时为空，存在时与被代表任职一起冻结。';
COMMENT ON COLUMN audit.audit_entry.on_behalf_of_appointment_id IS '被代表任职标识：非代办时为空，存在时与被代表Principal一起冻结。';
COMMENT ON COLUMN audit.audit_entry.command_id IS '命令标识：由CommandRuntime产生的事件准确关联命令；非命令事件为空。';
COMMENT ON COLUMN audit.audit_entry.command_type IS '命令类型：与command_id同时存在；非命令事件为空。';
COMMENT ON COLUMN audit.audit_entry.correlation_id IS '关联标识：贯穿一次用户或服务请求的稳定UUID。';
COMMENT ON COLUMN audit.audit_entry.causation_id IS '因果标识：存在直接上游命令或事件时记录其稳定UUID。';
COMMENT ON COLUMN audit.audit_entry.authorization_slot_code IS '授权槽：本动作实际满足的唯一静态authoritySlot。';
COMMENT ON COLUMN audit.audit_entry.authorization_path_code IS '授权路径：DIRECT、DELEGATED、OBJECT或SYSTEM等静态单路径类型。';
COMMENT ON COLUMN audit.audit_entry.authorization_scope_organization_unit_id IS '授权组织Scope根：按提交时当前组织树解释；全租户系统路径时可为空。';
COMMENT ON COLUMN audit.audit_entry.authorization_snapshot_digest IS '授权依据快照摘要：冻结实际Actor、Appointment、路径、范围、限制和决定依据。';
COMMENT ON COLUMN audit.audit_entry.trace_id IS '追踪标识：把同一请求链上的审计事实关联起来，不是业务对象外键。';
COMMENT ON COLUMN audit.audit_entry.service_role_code IS '后端执行角色：API、WORKER或受控管理角色等静态代码。';
COMMENT ON COLUMN audit.audit_entry.execution_node_code IS '执行节点代码：冻结实际服务实例或受控运行环境，不保存主机Secret。';
COMMENT ON COLUMN audit.audit_entry.session_id_hmac IS '会话标识HMAC：固定HMAC-SHA-256的32字节值，用于安全关联且不能还原原始会话Token。';
COMMENT ON COLUMN audit.audit_entry.client_ip_ciphertext IS '客户端地址密文：仅高风险审计需要时保存，数据库不可解密。';
COMMENT ON COLUMN audit.audit_entry.summary_schema_code IS '变更摘要Schema：静态允许列表定义可出现的字段。';
COMMENT ON COLUMN audit.audit_entry.summary_schema_version IS '变更摘要Schema版本：解释允许列表化JSON结构的正整数版本。';
COMMENT ON COLUMN audit.audit_entry.change_summary IS '允许列表化变更摘要：仅保存必要字段变化，不得复制完整领域事实、请求响应、密码、Token、Secret或正文。';
COMMENT ON COLUMN audit.audit_entry.change_summary_digest IS '变更摘要摘要：规范化允许列表JSON的32字节SHA-256。';
COMMENT ON COLUMN audit.audit_entry.subject_type IS '本条审计所针对的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry.subject_id IS '本条审计所针对的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry.subject_revision IS '本条审计所针对的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry.subject_hash IS '本条审计所针对的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN audit.audit_entry.correction_target_type IS '本条更正所指向的原审计事实的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry.correction_target_id IS '本条更正所指向的原审计事实在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry.correction_target_revision IS '本条更正所指向的原审计事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry.correction_target_hash IS '本条更正所指向的原审计事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN audit.audit_entry.authorization_fact_type IS '执行被审计动作时实际采用的授权或委托Fact的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry.authorization_fact_id IS '执行被审计动作时实际采用的授权或委托Fact在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry.authorization_fact_revision IS '执行被审计动作时实际采用的授权或委托Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry.authorization_fact_hash IS '执行被审计动作时实际采用的授权或委托Fact的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_audit_entry__entry_type ON audit.audit_entry IS '审计条目只允许原始事件或追加更正。';
COMMENT ON CONSTRAINT ck_audit_entry__result_code ON audit.audit_entry IS '审计结果只允许成功、无变化、拒绝或失败。';
COMMENT ON CONSTRAINT ck_audit_entry__command_pair ON audit.audit_entry IS '命令上下文：命令标识和类型必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_audit_entry__on_behalf_pair ON audit.audit_entry IS '代办上下文：被代表Principal和Appointment必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_audit_entry__correction_shape ON audit.audit_entry IS '更正单链：只有CORRECTION必须准确引用一条原AuditEntry。';
COMMENT ON CONSTRAINT ck_audit_entry__summary_schema_version ON audit.audit_entry IS '变更摘要Schema版本必须为正数。';
COMMENT ON CONSTRAINT ck_audit_entry__subject_exact ON audit.audit_entry IS '准确引用：本条审计所针对的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_audit_entry__correction_target_exact ON audit.audit_entry IS '准确引用：本条更正所指向的原审计事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_audit_entry__authorization_fact_exact ON audit.audit_entry IS '准确引用：执行被审计动作时实际采用的授权或委托Fact必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_audit_entry__authorization_snapshot_digest_length ON audit.audit_entry IS '摘要格式：authorization_snapshot_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_audit_entry__session_id_hmac_length ON audit.audit_entry IS '摘要格式：session_id_hmac必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_audit_entry__change_summary_digest_length ON audit.audit_entry IS '摘要格式：change_summary_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_audit_entry__subject_hash_length ON audit.audit_entry IS '摘要格式：subject_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_audit_entry__correction_target_hash_length ON audit.audit_entry IS '摘要格式：correction_target_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_audit_entry__authorization_fact_hash_length ON audit.audit_entry IS '摘要格式：authorization_fact_hash必须保存32字节的规范二进制值。';
