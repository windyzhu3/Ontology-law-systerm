-- 证据域：保存单文件上传会话、固定对象版本、不可变提交与固定目标用途绑定组成的严格一对一物理链。

CREATE TABLE evidence.upload_session (
    tenant_id uuid NOT NULL,
    upload_session_id uuid NOT NULL,
    object_store_code varchar(64) NOT NULL,
    object_key text NOT NULL,
    purpose_code varchar(64) NOT NULL,
    intake_contract_code varchar(64) NOT NULL,
    intake_contract_version integer NOT NULL,
    intake_contract_digest bytea NOT NULL,
    upload_capability_hash bytea NOT NULL,
    status varchar(32) NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    expires_at timestamptz(6) NOT NULL,
    received_at timestamptz(6),
    finalized_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id uuid NOT NULL,
    target_revision bigint,
    target_hash bytea,
    CONSTRAINT pk_upload_session PRIMARY KEY (tenant_id, upload_session_id),
    CONSTRAINT ck_upload_session__status CHECK (status IN ('OPEN', 'OBJECT_RECEIVED', 'FINALIZED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_upload_session__received_at CHECK (((status IN ('OBJECT_RECEIVED', 'FINALIZED') AND received_at IS NOT NULL) OR (status IN ('OPEN', 'EXPIRED', 'CANCELLED') AND received_at IS NULL))),
    CONSTRAINT ck_upload_session__finalized_at CHECK ((status = 'FINALIZED' AND finalized_at IS NOT NULL) OR (status <> 'FINALIZED' AND finalized_at IS NULL)),
    CONSTRAINT ck_upload_session__expiry_order CHECK (expires_at > created_at),
    CONSTRAINT ck_upload_session__contract_version CHECK (intake_contract_version > 0),
    CONSTRAINT ck_upload_session__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_upload_session__object_key UNIQUE (tenant_id, object_store_code, object_key),
    CONSTRAINT ck_upload_session__target_exact CHECK ((target_type IS NOT NULL AND target_id IS NOT NULL AND ((target_revision IS NOT NULL AND target_revision >= 0 AND target_hash IS NULL) OR (target_revision IS NULL AND target_hash IS NOT NULL)))),
    CONSTRAINT ck_upload_session__intake_contract_digest_length CHECK (octet_length(intake_contract_digest) = 32),
    CONSTRAINT ck_upload_session__upload_capability_hash_length CHECK (octet_length(upload_capability_hash) = 32),
    CONSTRAINT ck_upload_session__target_hash_length CHECK (octet_length(target_hash) = 32)
);

COMMENT ON TABLE evidence.upload_session IS 'Fact Owner：EvidenceIngress；上传会话：一行只授权向冻结目标和用途上传一个文件，事实Owner为EvidenceIngress；仅允许单向关闭状态更新，不表示文件已经接收、扫描或成为证据。';
COMMENT ON CONSTRAINT pk_upload_session ON evidence.upload_session IS '主键：在租户内唯一标识一条upload_session记录。';
COMMENT ON INDEX evidence.pk_upload_session IS '主键：在租户内唯一标识一条upload_session记录。';
COMMENT ON COLUMN evidence.upload_session.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.upload_session.upload_session_id IS '上传会话标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.upload_session.object_store_code IS '对象存储代码：标识静态私有对象存储适配器。';
COMMENT ON COLUMN evidence.upload_session.object_key IS 'Opaque对象键：由服务端生成且不包含租户、案情、文件名或可用凭据。';
COMMENT ON COLUMN evidence.upload_session.purpose_code IS '上传用途：来自静态用途注册表，会话创建后冻结且不可改。';
COMMENT ON COLUMN evidence.upload_session.intake_contract_code IS '接收合同代码：静态注册的大小、媒体类型和安全门禁合同。';
COMMENT ON COLUMN evidence.upload_session.intake_contract_version IS '接收合同版本：解释本会话技术门禁的正整数版本。';
COMMENT ON COLUMN evidence.upload_session.intake_contract_digest IS '接收合同摘要：冻结实际允许规则的32字节摘要。';
COMMENT ON COLUMN evidence.upload_session.upload_capability_hash IS '上传能力摘要：一次性上传能力的SHA-256，数据库不保存可用凭据。';
COMMENT ON COLUMN evidence.upload_session.status IS '会话状态：OPEN、OBJECT_RECEIVED、FINALIZED、EXPIRED或CANCELLED。';
COMMENT ON COLUMN evidence.upload_session.created_by_appointment_id IS '创建任职标识：发起受控上传会话的准确Appointment。';
COMMENT ON COLUMN evidence.upload_session.created_at IS '创建时间：EvidenceIngress签发会话的数据库时间，创建后不可变。';
COMMENT ON COLUMN evidence.upload_session.expires_at IS '到期时间：创建时冻结的上传截止时间，创建后不可变。';
COMMENT ON COLUMN evidence.upload_session.received_at IS '对象接收时间：唯一文件的准确ObjectVersion被固定时一次写入。';
COMMENT ON COLUMN evidence.upload_session.finalized_at IS '最终晋级时间：技术检查、最终授权和Subject版本重验全部通过后一次写入。';
COMMENT ON COLUMN evidence.upload_session.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN evidence.upload_session.target_type IS '上传会话创建时冻结的准确业务目标的静态注册类型。';
COMMENT ON COLUMN evidence.upload_session.target_id IS '上传会话创建时冻结的准确业务目标在所属租户内的准确标识。';
COMMENT ON COLUMN evidence.upload_session.target_revision IS '上传会话创建时冻结的准确业务目标的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN evidence.upload_session.target_hash IS '上传会话创建时冻结的准确业务目标的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_upload_session__status ON evidence.upload_session IS '上传会话状态域：只允许开放、对象已接收、已最终晋级、已过期或已取消。';
COMMENT ON CONSTRAINT ck_upload_session__received_at ON evidence.upload_session IS '对象接收：只有对象已接收或最终晋级状态具有唯一接收时间。';
COMMENT ON CONSTRAINT ck_upload_session__finalized_at ON evidence.upload_session IS '最终晋级：只有FINALIZED必须记录完成时间。';
COMMENT ON CONSTRAINT ck_upload_session__expiry_order ON evidence.upload_session IS '会话期限：冻结的到期时间必须晚于创建时间。';
COMMENT ON CONSTRAINT ck_upload_session__contract_version ON evidence.upload_session IS '接收合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_upload_session__revision_nonnegative ON evidence.upload_session IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT uq_upload_session__object_key ON evidence.upload_session IS 'create-only对象唯一：一个Opaque Key只允许一个上传会话和一次原始字节写入。';
COMMENT ON INDEX evidence.uq_upload_session__object_key IS 'create-only对象唯一：一个Opaque Key只允许一个上传会话和一次原始字节写入。';
COMMENT ON CONSTRAINT ck_upload_session__target_exact ON evidence.upload_session IS '准确引用：上传会话创建时冻结的准确业务目标必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_upload_session__intake_contract_digest_length ON evidence.upload_session IS '摘要格式：intake_contract_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_upload_session__upload_capability_hash_length ON evidence.upload_session IS '摘要格式：upload_capability_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_upload_session__target_hash_length ON evidence.upload_session IS '摘要格式：target_hash必须保存32字节的规范二进制值。';

CREATE TABLE evidence.received_source_object (
    tenant_id uuid NOT NULL,
    received_source_object_id uuid NOT NULL,
    upload_session_id uuid NOT NULL,
    object_store_code varchar(64) NOT NULL,
    object_key text NOT NULL,
    object_version text NOT NULL,
    size_bytes bigint NOT NULL,
    server_sha256 bytea NOT NULL,
    detected_media_type varchar(255) NOT NULL,
    scan_result varchar(32) NOT NULL,
    scan_engine_code varchar(128) NOT NULL,
    scan_contract_version integer NOT NULL,
    scan_failure_code varchar(64),
    scanned_at timestamptz(6) NOT NULL,
    received_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_received_source_object PRIMARY KEY (tenant_id, received_source_object_id),
    CONSTRAINT ck_received_source_object__scan_result CHECK (scan_result IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_received_source_object__scan_shape CHECK ((scan_result = 'PASSED' AND scan_failure_code IS NULL) OR (scan_result = 'FAILED' AND scan_failure_code IS NOT NULL)),
    CONSTRAINT ck_received_source_object__scan_contract_version CHECK (scan_contract_version > 0),
    CONSTRAINT ck_received_source_object__size_bytes_nonnegative CHECK (size_bytes >= 0),
    CONSTRAINT uq_received_source_object__upload_session UNIQUE (tenant_id, upload_session_id),
    CONSTRAINT uq_received_source_object__object_version UNIQUE (tenant_id, object_store_code, object_key, object_version),
    CONSTRAINT ck_received_source_object__server_sha256_length CHECK (octet_length(server_sha256) = 32)
);

COMMENT ON TABLE evidence.received_source_object IS 'Fact Owner：EvidenceIngress；接收来源对象：一行是一个上传会话唯一文件经服务端读取、类型识别和恶意文件扫描后的不可变来源事实，事实Owner为EvidenceIngress；不代表业务提交或目标绑定。';
COMMENT ON CONSTRAINT pk_received_source_object ON evidence.received_source_object IS '主键：在租户内唯一标识一条received_source_object记录。';
COMMENT ON INDEX evidence.pk_received_source_object IS '主键：在租户内唯一标识一条received_source_object记录。';
COMMENT ON COLUMN evidence.received_source_object.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.received_source_object.received_source_object_id IS '接收来源对象标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.received_source_object.upload_session_id IS '上传会话标识：强关联同租户会话且一会话至多一个来源对象。';
COMMENT ON COLUMN evidence.received_source_object.object_store_code IS '对象存储代码：标识静态存储适配器，创建后不可变。';
COMMENT ON COLUMN evidence.received_source_object.object_key IS '对象键：服务端控制的非公开定位键，创建后不可变且不得包含可用凭据。';
COMMENT ON COLUMN evidence.received_source_object.object_version IS '对象版本：对象存储返回的真实不可变ObjectVersion，创建后不可改。';
COMMENT ON COLUMN evidence.received_source_object.size_bytes IS '服务端读取的对象字节数：非负，创建后不可变。';
COMMENT ON COLUMN evidence.received_source_object.server_sha256 IS '服务端摘要：服务端读取固定ObjectVersion全部字节计算的SHA-256。';
COMMENT ON COLUMN evidence.received_source_object.detected_media_type IS '真实媒体类型：服务端内容识别结果，不信任客户端声明，创建后不可变。';
COMMENT ON COLUMN evidence.received_source_object.scan_result IS '扫描结果：PASSED或FAILED；PASSED只表示技术门禁通过，不表示业务VERIFIED。';
COMMENT ON COLUMN evidence.received_source_object.scan_engine_code IS '扫描引擎代码：标识静态扫描器及其规则版本，创建后不可变。';
COMMENT ON COLUMN evidence.received_source_object.scan_contract_version IS '扫描合同版本：解释引擎规则和真实类型门禁的正整数版本。';
COMMENT ON COLUMN evidence.received_source_object.scan_failure_code IS '扫描失败代码：FAILED时必填的安全原因，PASSED时为空。';
COMMENT ON COLUMN evidence.received_source_object.scanned_at IS '扫描时间：固定ObjectVersion完成恶意文件扫描的数据库时间，创建后不可变。';
COMMENT ON COLUMN evidence.received_source_object.received_at IS '接收时间：服务端固定对象版本并完成读取的数据库时间，创建后不可变。';
COMMENT ON CONSTRAINT ck_received_source_object__scan_result ON evidence.received_source_object IS '扫描结果域：只允许技术门禁通过或失败；失败细分使用安全原因代码。';
COMMENT ON CONSTRAINT ck_received_source_object__scan_shape ON evidence.received_source_object IS '扫描结果完整性：FAILED必须有安全原因，PASSED不得携带失败码。';
COMMENT ON CONSTRAINT ck_received_source_object__scan_contract_version ON evidence.received_source_object IS '扫描合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_received_source_object__size_bytes_nonnegative ON evidence.received_source_object IS '服务端观测的对象字节数不得为负。';
COMMENT ON CONSTRAINT uq_received_source_object__upload_session ON evidence.received_source_object IS '单文件会话：一个上传会话至多形成一个接收来源对象。';
COMMENT ON INDEX evidence.uq_received_source_object__upload_session IS '单文件会话：一个上传会话至多形成一个接收来源对象。';
COMMENT ON CONSTRAINT uq_received_source_object__object_version ON evidence.received_source_object IS '来源对象唯一：同租户同存储对象键的固定ObjectVersion只接收一次。';
COMMENT ON INDEX evidence.uq_received_source_object__object_version IS '来源对象唯一：同租户同存储对象键的固定ObjectVersion只接收一次。';
COMMENT ON CONSTRAINT ck_received_source_object__server_sha256_length ON evidence.received_source_object IS '摘要格式：server_sha256必须保存32字节的规范二进制值。';

CREATE TABLE evidence.evidence_submission (
    tenant_id uuid NOT NULL,
    evidence_submission_id uuid NOT NULL,
    received_source_object_id uuid NOT NULL,
    submission_contract_code varchar(64) NOT NULL,
    submission_contract_version integer NOT NULL,
    submitted_by_appointment_id uuid NOT NULL,
    submitted_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_evidence_submission PRIMARY KEY (tenant_id, evidence_submission_id),
    CONSTRAINT ck_evidence_submission__contract_version CHECK (submission_contract_version > 0),
    CONSTRAINT uq_evidence_submission__source_object UNIQUE (tenant_id, received_source_object_id)
);

COMMENT ON TABLE evidence.evidence_submission IS 'Fact Owner：EvidenceRuntime；证据提交：一行把一个已接收且扫描结论可接受的唯一来源对象声明为不可变提交事实，事实Owner为EvidenceRuntime；只可插入，不代表已绑定到业务目标。';
COMMENT ON CONSTRAINT pk_evidence_submission ON evidence.evidence_submission IS '主键：在租户内唯一标识一条evidence_submission记录。';
COMMENT ON INDEX evidence.pk_evidence_submission IS '主键：在租户内唯一标识一条evidence_submission记录。';
COMMENT ON COLUMN evidence.evidence_submission.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.evidence_submission.evidence_submission_id IS '证据提交标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.evidence_submission.received_source_object_id IS '接收来源对象标识：强关联同租户来源对象且一对象至多一次提交。';
COMMENT ON COLUMN evidence.evidence_submission.submission_contract_code IS '提交合同代码：静态注册的EvidenceSubmission结构和用途规则。';
COMMENT ON COLUMN evidence.evidence_submission.submission_contract_version IS '提交合同版本：解释不可变提交事实的正整数版本。';
COMMENT ON COLUMN evidence.evidence_submission.submitted_by_appointment_id IS '提交任职标识：最终授权和Subject重验通过时实际执行晋级的Appointment。';
COMMENT ON COLUMN evidence.evidence_submission.submitted_at IS '提交时间：EvidenceRuntime接受该来源对象的数据库时间，创建后不可变。';
COMMENT ON CONSTRAINT ck_evidence_submission__contract_version ON evidence.evidence_submission IS '提交合同版本必须为正数。';
COMMENT ON CONSTRAINT uq_evidence_submission__source_object ON evidence.evidence_submission IS '证据链唯一：一个接收来源对象至多形成一条不可变证据提交。';
COMMENT ON INDEX evidence.uq_evidence_submission__source_object IS '证据链唯一：一个接收来源对象至多形成一条不可变证据提交。';

CREATE TABLE evidence.evidence_binding (
    tenant_id uuid NOT NULL,
    evidence_binding_id uuid NOT NULL,
    evidence_submission_id uuid NOT NULL,
    purpose_code varchar(64) NOT NULL,
    bound_by_appointment_id uuid NOT NULL,
    bound_at timestamptz(6) NOT NULL,
    revoked_at timestamptz(6),
    revoked_by_appointment_id uuid,
    revocation_authorization_digest bytea,
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id uuid NOT NULL,
    target_revision bigint,
    target_hash bytea,
    CONSTRAINT pk_evidence_binding PRIMARY KEY (tenant_id, evidence_binding_id),
    CONSTRAINT ck_evidence_binding__revocation_shape CHECK (((revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL))),
    CONSTRAINT ck_evidence_binding__revocation_order CHECK (revoked_at IS NULL OR revoked_at >= bound_at),
    CONSTRAINT ck_evidence_binding__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_evidence_binding__submission UNIQUE (tenant_id, evidence_submission_id),
    CONSTRAINT ck_evidence_binding__target_exact CHECK ((target_type IS NOT NULL AND target_id IS NOT NULL AND ((target_revision IS NOT NULL AND target_revision >= 0 AND target_hash IS NULL) OR (target_revision IS NULL AND target_hash IS NOT NULL)))),
    CONSTRAINT ck_evidence_binding__revocation_authorization_digest_length CHECK (octet_length(revocation_authorization_digest) = 32),
    CONSTRAINT ck_evidence_binding__target_hash_length CHECK (octet_length(target_hash) = 32)
);

COMMENT ON TABLE evidence.evidence_binding IS 'Fact Owner：EvidenceRuntime；证据绑定：一行把一个不可变提交按冻结用途绑定到冻结准确目标，事实Owner为EvidenceRuntime；只允许单向撤回，不移动目标、不改用途且不删除历史。';
COMMENT ON CONSTRAINT pk_evidence_binding ON evidence.evidence_binding IS '主键：在租户内唯一标识一条evidence_binding记录。';
COMMENT ON INDEX evidence.pk_evidence_binding IS '主键：在租户内唯一标识一条evidence_binding记录。';
COMMENT ON COLUMN evidence.evidence_binding.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.evidence_binding.evidence_binding_id IS '证据绑定标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.evidence_binding.evidence_submission_id IS '证据提交标识：强关联同租户提交且一次提交至多一个绑定。';
COMMENT ON COLUMN evidence.evidence_binding.purpose_code IS '绑定用途：来自静态用途注册表，创建后冻结且不可改。';
COMMENT ON COLUMN evidence.evidence_binding.bound_by_appointment_id IS '绑定任职标识：最终四轴授权和Subject版本重验通过时执行绑定的Appointment。';
COMMENT ON COLUMN evidence.evidence_binding.bound_at IS '绑定时间：EvidenceRuntime创建目标绑定的数据库时间，创建后不可变。';
COMMENT ON COLUMN evidence.evidence_binding.revoked_at IS '撤回时间：空值表示有效；首次撤回时写入且不得清空或改写。';
COMMENT ON COLUMN evidence.evidence_binding.revoked_by_appointment_id IS '撤回任职标识：授权撤回命令的准确Appointment；未撤回为空。';
COMMENT ON COLUMN evidence.evidence_binding.revocation_authorization_digest IS '撤回授权摘要：冻结撤回命令提交前四轴复验的单路径授权快照；未撤回为空。';
COMMENT ON COLUMN evidence.evidence_binding.revocation_reason_code IS '撤回原因代码：仅撤回时必填，不保存文档正文或非必要案情。';
COMMENT ON COLUMN evidence.evidence_binding.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN evidence.evidence_binding.target_type IS '证据绑定创建时冻结的准确业务目标的静态注册类型。';
COMMENT ON COLUMN evidence.evidence_binding.target_id IS '证据绑定创建时冻结的准确业务目标在所属租户内的准确标识。';
COMMENT ON COLUMN evidence.evidence_binding.target_revision IS '证据绑定创建时冻结的准确业务目标的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN evidence.evidence_binding.target_hash IS '证据绑定创建时冻结的准确业务目标的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_evidence_binding__revocation_shape ON evidence.evidence_binding IS '撤回形态：撤回时间、任职、授权摘要和安全原因必须同时为空或一次性全部写入。';
COMMENT ON CONSTRAINT ck_evidence_binding__revocation_order ON evidence.evidence_binding IS '撤回顺序：撤回时间不得早于绑定时间。';
COMMENT ON CONSTRAINT ck_evidence_binding__revision_nonnegative ON evidence.evidence_binding IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT uq_evidence_binding__submission ON evidence.evidence_binding IS '证据链唯一：一个不可变证据提交至多形成一个目标绑定。';
COMMENT ON INDEX evidence.uq_evidence_binding__submission IS '证据链唯一：一个不可变证据提交至多形成一个目标绑定。';
COMMENT ON CONSTRAINT ck_evidence_binding__target_exact ON evidence.evidence_binding IS '准确引用：证据绑定创建时冻结的准确业务目标必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_evidence_binding__revocation_authorization_digest_length ON evidence.evidence_binding IS '摘要格式：revocation_authorization_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_evidence_binding__target_hash_length ON evidence.evidence_binding IS '摘要格式：target_hash必须保存32字节的规范二进制值。';
