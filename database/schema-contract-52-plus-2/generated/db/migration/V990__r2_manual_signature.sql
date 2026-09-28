CREATE TABLE contract.template_signing_party (
    tenant_id uuid NOT NULL,
    template_signing_party_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    template_version_id uuid NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    profile_version_id uuid NOT NULL,
    party_snapshot_digest bytea NOT NULL,
    role_code varchar(64) NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_template_signing_party PRIMARY KEY (tenant_id, template_signing_party_id),
    CONSTRAINT ck_template_signing_party__revision CHECK (revision=0),
    CONSTRAINT uq_template_signing_party__template_role UNIQUE (tenant_id, template_version_id, role_code),
    CONSTRAINT uq_template_signing_party__template_party UNIQUE (tenant_id, template_version_id, party_id),
    CONSTRAINT ck_template_signing_party__values CHECK (role_code='FIRM' AND party_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_template_signing_party__party_snapshot_digest_length CHECK (octet_length(party_snapshot_digest) = 32)
);

COMMENT ON TABLE contract.template_signing_party IS 'Fact Owner：ContractRuntime；审核模板同事务冻结的律所签约主体；不得为旧模板补造绑定。';
COMMENT ON CONSTRAINT pk_template_signing_party ON contract.template_signing_party IS '主键：在租户内唯一标识一条template_signing_party记录。';
COMMENT ON INDEX contract.pk_template_signing_party IS '主键：在租户内唯一标识一条template_signing_party记录。';
COMMENT ON COLUMN contract.template_signing_party.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.template_signing_party.template_signing_party_id IS '审核模板同事务冻结的律所签约主体；不得为旧模板补造绑定。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.template_signing_party.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.template_signing_party.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.template_signing_party.template_version_id IS '准确审核模板。';
COMMENT ON COLUMN contract.template_signing_party.party_id IS '真实律所主体。';
COMMENT ON COLUMN contract.template_signing_party.party_revision IS '批准时主体版本。';
COMMENT ON COLUMN contract.template_signing_party.profile_version_id IS '准确不可变主体资料。';
COMMENT ON COLUMN contract.template_signing_party.party_snapshot_digest IS '资料身份规范摘要。';
COMMENT ON COLUMN contract.template_signing_party.role_code IS '明确模板签署角色FIRM。';
COMMENT ON COLUMN contract.template_signing_party.created_by_appointment_id IS '与模板审核者相同的任职。';
COMMENT ON COLUMN contract.template_signing_party.created_at IS '数据库冻结时间。';
COMMENT ON CONSTRAINT ck_template_signing_party__revision ON contract.template_signing_party IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_template_signing_party__template_role ON contract.template_signing_party IS '模板内律所角色唯一。';
COMMENT ON INDEX contract.uq_template_signing_party__template_role IS '模板内律所角色唯一。';
COMMENT ON CONSTRAINT uq_template_signing_party__template_party ON contract.template_signing_party IS '模板主体唯一。';
COMMENT ON INDEX contract.uq_template_signing_party__template_party IS '模板主体唯一。';
COMMENT ON CONSTRAINT ck_template_signing_party__values ON contract.template_signing_party IS '明确律所绑定及准确资料版本。';
COMMENT ON CONSTRAINT ck_template_signing_party__party_snapshot_digest_length ON contract.template_signing_party IS '摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_arrangement (
    tenant_id uuid NOT NULL,
    signature_arrangement_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    readiness_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    previous_arrangement_id uuid,
    registered_by_appointment_id uuid NOT NULL,
    slot_count integer NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_arrangement PRIMARY KEY (tenant_id, signature_arrangement_id),
    CONSTRAINT ck_signature_arrangement__revision CHECK (revision=0),
    CONSTRAINT uq_signature_arrangement__previous_arrangement_id UNIQUE (tenant_id, previous_arrangement_id),
    CONSTRAINT ck_signature_arrangement__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_arrangement__count CHECK (slot_count BETWEEN 1 AND 100),
    CONSTRAINT ck_signature_arrangement__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_arrangement IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_arrangement ON contract.signature_arrangement IS '主键：在租户内唯一标识一条signature_arrangement记录。';
COMMENT ON INDEX contract.pk_signature_arrangement IS '主键：在租户内唯一标识一条signature_arrangement记录。';
COMMENT ON COLUMN contract.signature_arrangement.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_arrangement.signature_arrangement_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_arrangement.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_arrangement.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_arrangement.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_arrangement.readiness_id IS '准确签署准备依据。';
COMMENT ON COLUMN contract.signature_arrangement.contract_revision_id IS '批准正文版本。';
COMMENT ON COLUMN contract.signature_arrangement.previous_arrangement_id IS '同正文安排补正前序。';
COMMENT ON COLUMN contract.signature_arrangement.registered_by_appointment_id IS '登记任职。';
COMMENT ON COLUMN contract.signature_arrangement.slot_count IS '完整明确槽数量。';
COMMENT ON COLUMN contract.signature_arrangement.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_arrangement.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_arrangement.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_arrangement__revision ON contract.signature_arrangement IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_arrangement__previous_arrangement_id ON contract.signature_arrangement IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_arrangement__previous_arrangement_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_arrangement__body ON contract.signature_arrangement IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_arrangement__count ON contract.signature_arrangement IS '有限非空安排。';
COMMENT ON CONSTRAINT ck_signature_arrangement__body_digest_length ON contract.signature_arrangement IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_draft (
    tenant_id uuid NOT NULL,
    signature_draft_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    readiness_id uuid NOT NULL,
    arrangement_id uuid,
    previous_draft_id uuid,
    saved_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_draft PRIMARY KEY (tenant_id, signature_draft_id),
    CONSTRAINT ck_signature_draft__revision CHECK (revision=0),
    CONSTRAINT uq_signature_draft__previous_draft_id UNIQUE (tenant_id, previous_draft_id),
    CONSTRAINT ck_signature_draft__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_draft__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_draft IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_draft ON contract.signature_draft IS '主键：在租户内唯一标识一条signature_draft记录。';
COMMENT ON INDEX contract.pk_signature_draft IS '主键：在租户内唯一标识一条signature_draft记录。';
COMMENT ON COLUMN contract.signature_draft.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_draft.signature_draft_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_draft.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_draft.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_draft.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_draft.readiness_id IS '准确签署准备依据。';
COMMENT ON COLUMN contract.signature_draft.arrangement_id IS '已登记安排；登记前草稿为空。';
COMMENT ON COLUMN contract.signature_draft.previous_draft_id IS '上一不可变草稿。';
COMMENT ON COLUMN contract.signature_draft.saved_by_appointment_id IS '保存任职。';
COMMENT ON COLUMN contract.signature_draft.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_draft.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_draft.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_draft__revision ON contract.signature_draft IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_draft__previous_draft_id ON contract.signature_draft IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_draft__previous_draft_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_draft__body ON contract.signature_draft IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_draft__body_digest_length ON contract.signature_draft IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_submission (
    tenant_id uuid NOT NULL,
    signature_submission_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    arrangement_id uuid NOT NULL,
    signature_plan_id uuid NOT NULL,
    previous_submission_id uuid,
    submitted_by_appointment_id uuid NOT NULL,
    material_version_id uuid NOT NULL,
    authority_material_version_id uuid NOT NULL,
    material_sha256 bytea NOT NULL,
    authority_material_sha256 bytea NOT NULL,
    approved_body_sha256 bytea NOT NULL,
    signed_at timestamptz(6) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_submission PRIMARY KEY (tenant_id, signature_submission_id),
    CONSTRAINT ck_signature_submission__revision CHECK (revision=0),
    CONSTRAINT uq_signature_submission__previous_submission_id UNIQUE (tenant_id, previous_submission_id),
    CONSTRAINT ck_signature_submission__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_submission__time CHECK (signed_at<=created_at),
    CONSTRAINT ck_signature_submission__material_sha256_length CHECK (octet_length(material_sha256) = 32),
    CONSTRAINT ck_signature_submission__authority_material_sha256_length CHECK (octet_length(authority_material_sha256) = 32),
    CONSTRAINT ck_signature_submission__approved_body_sha256_length CHECK (octet_length(approved_body_sha256) = 32),
    CONSTRAINT ck_signature_submission__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_submission IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_submission ON contract.signature_submission IS '主键：在租户内唯一标识一条signature_submission记录。';
COMMENT ON INDEX contract.pk_signature_submission IS '主键：在租户内唯一标识一条signature_submission记录。';
COMMENT ON COLUMN contract.signature_submission.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_submission.signature_submission_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_submission.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_submission.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_submission.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_submission.arrangement_id IS '准确安排。';
COMMENT ON COLUMN contract.signature_submission.signature_plan_id IS '准确计划槽。';
COMMENT ON COLUMN contract.signature_submission.previous_submission_id IS '同槽前次补证。';
COMMENT ON COLUMN contract.signature_submission.submitted_by_appointment_id IS '提交任职。';
COMMENT ON COLUMN contract.signature_submission.material_version_id IS '正式已接收签字件。';
COMMENT ON COLUMN contract.signature_submission.authority_material_version_id IS '正式权限材料。';
COMMENT ON COLUMN contract.signature_submission.material_sha256 IS '签字文件字节摘要。';
COMMENT ON COLUMN contract.signature_submission.authority_material_sha256 IS '权限文件字节摘要。';
COMMENT ON COLUMN contract.signature_submission.approved_body_sha256 IS '批准正文摘要；无需等于签字文件。';
COMMENT ON COLUMN contract.signature_submission.signed_at IS '实际签署业务时间。';
COMMENT ON COLUMN contract.signature_submission.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_submission.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_submission.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_submission__revision ON contract.signature_submission IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_submission__previous_submission_id ON contract.signature_submission IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_submission__previous_submission_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_submission__body ON contract.signature_submission IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_submission__time ON contract.signature_submission IS '业务时间不晚于系统记录。';
COMMENT ON CONSTRAINT ck_signature_submission__material_sha256_length ON contract.signature_submission IS '摘要格式：material_sha256必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_signature_submission__authority_material_sha256_length ON contract.signature_submission IS '摘要格式：authority_material_sha256必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_signature_submission__approved_body_sha256_length ON contract.signature_submission IS '摘要格式：approved_body_sha256必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_signature_submission__body_digest_length ON contract.signature_submission IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_verification (
    tenant_id uuid NOT NULL,
    signature_verification_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    verified_by_appointment_id uuid NOT NULL,
    decision_code varchar(64) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_verification PRIMARY KEY (tenant_id, signature_verification_id),
    CONSTRAINT ck_signature_verification__revision CHECK (revision=0),
    CONSTRAINT uq_signature_verification__submission_id UNIQUE (tenant_id, submission_id),
    CONSTRAINT ck_signature_verification__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_verification__decision CHECK (decision_code IN ('VERIFIED','NEED_INFO','REVISION_REQUIRED','ARRANGEMENT_CORRECTION')),
    CONSTRAINT ck_signature_verification__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_verification IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_verification ON contract.signature_verification IS '主键：在租户内唯一标识一条signature_verification记录。';
COMMENT ON INDEX contract.pk_signature_verification IS '主键：在租户内唯一标识一条signature_verification记录。';
COMMENT ON COLUMN contract.signature_verification.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_verification.signature_verification_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_verification.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_verification.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_verification.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_verification.submission_id IS '准确不可变提交。';
COMMENT ON COLUMN contract.signature_verification.verified_by_appointment_id IS '有权核验任职。';
COMMENT ON COLUMN contract.signature_verification.decision_code IS '准确核验结论。';
COMMENT ON COLUMN contract.signature_verification.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_verification.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_verification.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_verification__revision ON contract.signature_verification IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_verification__submission_id ON contract.signature_verification IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_verification__submission_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_verification__body ON contract.signature_verification IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_verification__decision ON contract.signature_verification IS '明确结果，不直接等于全合同完成。';
COMMENT ON CONSTRAINT ck_signature_verification__body_digest_length ON contract.signature_verification IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_archive (
    tenant_id uuid NOT NULL,
    signature_archive_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    arrangement_id uuid NOT NULL,
    material_version_id uuid NOT NULL,
    archived_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_archive PRIMARY KEY (tenant_id, signature_archive_id),
    CONSTRAINT ck_signature_archive__revision CHECK (revision=0),
    CONSTRAINT uq_signature_archive__arrangement_id UNIQUE (tenant_id, arrangement_id),
    CONSTRAINT ck_signature_archive__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_archive__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_archive IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_archive ON contract.signature_archive IS '主键：在租户内唯一标识一条signature_archive记录。';
COMMENT ON INDEX contract.pk_signature_archive IS '主键：在租户内唯一标识一条signature_archive记录。';
COMMENT ON COLUMN contract.signature_archive.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_archive.signature_archive_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_archive.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_archive.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_archive.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_archive.arrangement_id IS '全部必要签署的准确安排。';
COMMENT ON COLUMN contract.signature_archive.material_version_id IS '完整正式归档文件。';
COMMENT ON COLUMN contract.signature_archive.archived_by_appointment_id IS '归档任职。';
COMMENT ON COLUMN contract.signature_archive.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_archive.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_archive.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_archive__revision ON contract.signature_archive IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_archive__arrangement_id ON contract.signature_archive IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_archive__arrangement_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_archive__body ON contract.signature_archive IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_archive__body_digest_length ON contract.signature_archive IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_revision_return (
    tenant_id uuid NOT NULL,
    signature_revision_return_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    readiness_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_revision_return PRIMARY KEY (tenant_id, signature_revision_return_id),
    CONSTRAINT ck_signature_revision_return__revision CHECK (revision=0),
    CONSTRAINT uq_signature_revision_return__readiness_id UNIQUE (tenant_id, readiness_id),
    CONSTRAINT ck_signature_revision_return__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_revision_return__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_revision_return IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_revision_return ON contract.signature_revision_return IS '主键：在租户内唯一标识一条signature_revision_return记录。';
COMMENT ON INDEX contract.pk_signature_revision_return IS '主键：在租户内唯一标识一条signature_revision_return记录。';
COMMENT ON COLUMN contract.signature_revision_return.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_revision_return.signature_revision_return_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_revision_return.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_revision_return.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_revision_return.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_revision_return.readiness_id IS '失效前准确准备依据。';
COMMENT ON COLUMN contract.signature_revision_return.contract_revision_id IS '需要真实修订的正文。';
COMMENT ON COLUMN contract.signature_revision_return.created_by_appointment_id IS '退回任职。';
COMMENT ON COLUMN contract.signature_revision_return.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_revision_return.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_revision_return.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_revision_return__revision ON contract.signature_revision_return IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_revision_return__readiness_id ON contract.signature_revision_return IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_revision_return__readiness_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_revision_return__body ON contract.signature_revision_return IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_revision_return__body_digest_length ON contract.signature_revision_return IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_workflow (
    tenant_id uuid NOT NULL,
    signature_workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    readiness_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    previous_workflow_id uuid,
    stage_code varchar(64) NOT NULL,
    owner_appointment_id uuid,
    task_id uuid,
    prior_task_id uuid,
    arrangement_id uuid,
    submission_id uuid,
    created_by_appointment_id uuid NOT NULL,
    due_at timestamptz(6) NOT NULL,
    recovery_resume_stage varchar(64),
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_workflow PRIMARY KEY (tenant_id, signature_workflow_id),
    CONSTRAINT ck_signature_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_signature_workflow__previous_workflow_id UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_signature_workflow__stage CHECK (stage_code IN ('ARRANGE','COLLECT','AWAIT_VERIFICATION','SUPPLEMENT','PARTIAL','ARCHIVE','SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION') AND (stage_code NOT IN ('SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION') OR task_id IS NULL) AND (stage_code IN ('OWNER_EXCEPTION','SIGNATURE_COMPLETE','REVISION_REQUIRED') OR owner_appointment_id IS NOT NULL)),
    CONSTRAINT ck_signature_workflow__recovery CHECK (recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('ARRANGE','COLLECT','AWAIT_VERIFICATION','SUPPLEMENT','PARTIAL','ARCHIVE','SIGNATURE_COMPLETE','REVISION_REQUIRED')))
);

COMMENT ON TABLE contract.signature_workflow IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_workflow ON contract.signature_workflow IS '主键：在租户内唯一标识一条signature_workflow记录。';
COMMENT ON INDEX contract.pk_signature_workflow IS '主键：在租户内唯一标识一条signature_workflow记录。';
COMMENT ON COLUMN contract.signature_workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_workflow.signature_workflow_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_workflow.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_workflow.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_workflow.readiness_id IS '唯一消费准确准备依据。';
COMMENT ON COLUMN contract.signature_workflow.contract_revision_id IS '准确批准正文。';
COMMENT ON COLUMN contract.signature_workflow.previous_workflow_id IS 'CAS直接前序流程。';
COMMENT ON COLUMN contract.signature_workflow.stage_code IS '真实办理阶段。';
COMMENT ON COLUMN contract.signature_workflow.owner_appointment_id IS '实际责任任职；异常可空。';
COMMENT ON COLUMN contract.signature_workflow.task_id IS '可办理待办；终态无占位任务。';
COMMENT ON COLUMN contract.signature_workflow.prior_task_id IS '前序待办。';
COMMENT ON COLUMN contract.signature_workflow.arrangement_id IS '准确当前安排。';
COMMENT ON COLUMN contract.signature_workflow.submission_id IS '等待核验的准确提交。';
COMMENT ON COLUMN contract.signature_workflow.created_by_appointment_id IS '实际写入任职。';
COMMENT ON COLUMN contract.signature_workflow.due_at IS '原责任期限，恢复不延长。';
COMMENT ON COLUMN contract.signature_workflow.recovery_resume_stage IS '异常恢复原阶段。';
COMMENT ON COLUMN contract.signature_workflow.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_workflow__revision ON contract.signature_workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_workflow__previous_workflow_id ON contract.signature_workflow IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_workflow__previous_workflow_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_workflow__stage ON contract.signature_workflow IS '终态不创建不可办理占位任务。';
COMMENT ON CONSTRAINT ck_signature_workflow__recovery ON contract.signature_workflow IS '异常保留准确恢复阶段。';
CREATE TABLE contract.signature_handoff (
    tenant_id uuid NOT NULL,
    signature_handoff_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    readiness_id uuid NOT NULL,
    arrangement_id uuid NOT NULL,
    archive_id uuid NOT NULL,
    state_code varchar(64) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_handoff PRIMARY KEY (tenant_id, signature_handoff_id),
    CONSTRAINT ck_signature_handoff__revision CHECK (revision=0),
    CONSTRAINT uq_signature_handoff__readiness_id UNIQUE (tenant_id, readiness_id),
    CONSTRAINT uq_signature_handoff__arrangement_id UNIQUE (tenant_id, arrangement_id),
    CONSTRAINT uq_signature_handoff__archive_id UNIQUE (tenant_id, archive_id),
    CONSTRAINT ck_signature_handoff__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_signature_handoff__state CHECK (state_code='AWAITING_EXECUTION_CONDITIONS'),
    CONSTRAINT ck_signature_handoff__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.signature_handoff IS 'Fact Owner：ContractRuntime；人工签署不可变准确事实；不是合同执行。';
COMMENT ON CONSTRAINT pk_signature_handoff ON contract.signature_handoff IS '主键：在租户内唯一标识一条signature_handoff记录。';
COMMENT ON INDEX contract.pk_signature_handoff IS '主键：在租户内唯一标识一条signature_handoff记录。';
COMMENT ON COLUMN contract.signature_handoff.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_handoff.signature_handoff_id IS '人工签署不可变准确事实；不是合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_handoff.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_handoff.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_handoff.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.signature_handoff.readiness_id IS '已消费准备依据。';
COMMENT ON COLUMN contract.signature_handoff.arrangement_id IS '已全部核验的安排。';
COMMENT ON COLUMN contract.signature_handoff.archive_id IS '准确完整归档。';
COMMENT ON COLUMN contract.signature_handoff.state_code IS '持久化后续等待边界。';
COMMENT ON COLUMN contract.signature_handoff.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.signature_handoff.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.signature_handoff.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_signature_handoff__revision ON contract.signature_handoff IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_handoff__readiness_id ON contract.signature_handoff IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_handoff__readiness_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT uq_signature_handoff__arrangement_id ON contract.signature_handoff IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_handoff__arrangement_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT uq_signature_handoff__archive_id ON contract.signature_handoff IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_handoff__archive_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_handoff__body ON contract.signature_handoff IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_signature_handoff__state ON contract.signature_handoff IS '不等于合同执行、到账或建案。';
COMMENT ON CONSTRAINT ck_signature_handoff__body_digest_length ON contract.signature_handoff IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_predecessor;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_predecessor CHECK (handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','DELIVER_QUOTE','RECORD_QUOTE_REPLY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE') AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff'));
COMMENT ON CONSTRAINT ck_task_occurrence__handoff_predecessor ON responsibility.task_occurrence IS 'T01具名事实一致性。';
ALTER TABLE contract.signature_plan ALTER COLUMN contract_participation_id DROP NOT NULL;
ALTER TABLE contract.signature_plan ADD COLUMN arrangement_id uuid;
COMMENT ON COLUMN contract.signature_plan.arrangement_id IS '具名人工签署安排；历史计划为空。';
ALTER TABLE contract.signature_plan ADD COLUMN signature_required boolean DEFAULT true NOT NULL;
COMMENT ON COLUMN contract.signature_plan.signature_required IS '本槽是否必须签字。';
ALTER TABLE contract.signature_plan ADD COLUMN template_signing_party_id uuid;
COMMENT ON COLUMN contract.signature_plan.template_signing_party_id IS '审核模板冻结的律所绑定；与合同参与项互斥。';
ALTER TABLE contract.signature_plan DROP CONSTRAINT uk_signature_plan__revision_slot_no;
ALTER TABLE contract.signature_plan DROP CONSTRAINT uk_signature_plan__revision_authority_slot;
ALTER TABLE contract.signature_plan ADD CONSTRAINT ck_signature_plan__manual CHECK ((arrangement_id IS NULL AND contract_participation_id IS NOT NULL AND template_signing_party_id IS NULL) OR (arrangement_id IS NOT NULL AND signature_method_code='MANUAL' AND num_nonnulls(contract_participation_id,template_signing_party_id)=1 AND (signature_required OR seal_required)));
COMMENT ON CONSTRAINT ck_signature_plan__manual ON contract.signature_plan IS '历史参与引用不放宽；人工安排独立登记。';
ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__arrangement_id ON contract.signature_plan IS '同租户准确事实引用。';
ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__template_signing_party_id
    FOREIGN KEY (tenant_id, template_signing_party_id)
    REFERENCES contract.template_signing_party (tenant_id, template_signing_party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__template_signing_party_id ON contract.signature_plan IS '同租户准确事实引用。';
ALTER TABLE contract.contract_signature ALTER COLUMN evidence_submission_id DROP NOT NULL;
ALTER TABLE contract.contract_signature ADD COLUMN verification_id uuid;
COMMENT ON COLUMN contract.contract_signature.verification_id IS '人工证据核验通过事实；历史签署为空。';
ALTER TABLE contract.contract_signature ADD CONSTRAINT uq_contract_signature__verification_id UNIQUE (tenant_id, verification_id);
COMMENT ON CONSTRAINT uq_contract_signature__verification_id ON contract.contract_signature IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_contract_signature__verification_id IS '准确事实唯一。';
ALTER TABLE contract.contract_signature ADD CONSTRAINT ck_contract_signature__manual CHECK ((verification_id IS NULL AND evidence_submission_id IS NOT NULL) OR (verification_id IS NOT NULL AND evidence_submission_id IS NULL AND external_action_id IS NULL AND provider_inbox_id IS NULL AND verification_method_code='MANUAL'));
COMMENT ON CONSTRAINT ck_contract_signature__manual ON contract.contract_signature IS '人工核验与旧外部证据互斥。';
ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__verification_id
    FOREIGN KEY (tenant_id, verification_id)
    REFERENCES contract.signature_verification (tenant_id, signature_verification_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__verification_id ON contract.contract_signature IS '同租户准确事实引用。';
CREATE UNIQUE INDEX uk_signature_plan__revision_slot_no ON contract.signature_plan (tenant_id, contract_revision_id, slot_no) WHERE arrangement_id IS NULL;
COMMENT ON INDEX contract.uk_signature_plan__revision_slot_no IS '历史计划槽唯一。';
CREATE UNIQUE INDEX uk_signature_plan__revision_authority_slot ON contract.signature_plan (tenant_id, contract_revision_id, authority_slot_code) WHERE arrangement_id IS NULL;
COMMENT ON INDEX contract.uk_signature_plan__revision_authority_slot IS '历史计划槽唯一。';
CREATE UNIQUE INDEX uq_signature_plan__arrangement_slot ON contract.signature_plan (tenant_id, arrangement_id, slot_no) WHERE arrangement_id IS NOT NULL;
COMMENT ON INDEX contract.uq_signature_plan__arrangement_slot IS '同安排槽唯一。';
CREATE UNIQUE INDEX uq_signature_plan__arrangement_authority ON contract.signature_plan (tenant_id, arrangement_id, authority_slot_code) WHERE arrangement_id IS NOT NULL;
COMMENT ON INDEX contract.uq_signature_plan__arrangement_authority IS '同安排权限唯一。';
CREATE UNIQUE INDEX uq_signature_plan__arrangement_party ON contract.signature_plan (tenant_id, arrangement_id, signer_party_id) WHERE arrangement_id IS NOT NULL;
COMMENT ON INDEX contract.uq_signature_plan__arrangement_party IS '同安排主体唯一。';
CREATE UNIQUE INDEX uq_signature_arrangement__root ON contract.signature_arrangement (tenant_id, readiness_id) WHERE previous_arrangement_id IS NULL;
COMMENT ON INDEX contract.uq_signature_arrangement__root IS '同一准确依据唯一链根。';
CREATE UNIQUE INDEX uq_signature_draft__root ON contract.signature_draft (tenant_id, readiness_id) WHERE previous_draft_id IS NULL;
COMMENT ON INDEX contract.uq_signature_draft__root IS '同一准确依据唯一链根。';
CREATE UNIQUE INDEX uq_signature_submission__root ON contract.signature_submission (tenant_id, signature_plan_id) WHERE previous_submission_id IS NULL;
COMMENT ON INDEX contract.uq_signature_submission__root IS '同一准确依据唯一链根。';
CREATE UNIQUE INDEX uq_signature_workflow__root ON contract.signature_workflow (tenant_id, readiness_id) WHERE previous_workflow_id IS NULL;
COMMENT ON INDEX contract.uq_signature_workflow__root IS '同一准确依据唯一链根。';
ALTER TABLE contract.template_signing_party
    ADD CONSTRAINT fk_template_signing_party__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_signing_party__tenant ON contract.template_signing_party IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.template_signing_party
    ADD CONSTRAINT fk_template_signing_party__template_version_id
    FOREIGN KEY (tenant_id, template_version_id)
    REFERENCES contract.template_version (tenant_id, template_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_signing_party__template_version_id ON contract.template_signing_party IS '同租户准确事实引用。';
ALTER TABLE contract.template_signing_party
    ADD CONSTRAINT fk_template_signing_party__party_id
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_signing_party__party_id ON contract.template_signing_party IS '同租户准确事实引用。';
ALTER TABLE contract.template_signing_party
    ADD CONSTRAINT fk_template_signing_party__profile_version_id
    FOREIGN KEY (tenant_id, profile_version_id)
    REFERENCES party.profile_version (tenant_id, profile_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_signing_party__profile_version_id ON contract.template_signing_party IS '同租户准确事实引用。';
ALTER TABLE contract.template_signing_party
    ADD CONSTRAINT fk_template_signing_party__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_signing_party__created_by_appointment_id ON contract.template_signing_party IS '同租户准确事实引用。';
CREATE TRIGGER trg_template_signing_party__mutation_guard BEFORE UPDATE OR DELETE ON contract.template_signing_party FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_template_signing_party__mutation_guard ON contract.template_signing_party IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.template_signing_party FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.template_signing_party TO ${app_command_role};
GRANT SELECT ON contract.template_signing_party TO ${app_query_role};
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__tenant ON contract.signature_arrangement IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__opportunity_id ON contract.signature_arrangement IS '同租户准确事实引用。';
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__readiness_id
    FOREIGN KEY (tenant_id, readiness_id)
    REFERENCES contract.signature_readiness (tenant_id, signature_readiness_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__readiness_id ON contract.signature_arrangement IS '同租户准确事实引用。';
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__contract_revision_id ON contract.signature_arrangement IS '同租户准确事实引用。';
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__previous_arrangement_id
    FOREIGN KEY (tenant_id, previous_arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__previous_arrangement_id ON contract.signature_arrangement IS '同租户准确事实引用。';
ALTER TABLE contract.signature_arrangement
    ADD CONSTRAINT fk_signature_arrangement__registered_by_appointment_id
    FOREIGN KEY (tenant_id, registered_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_arrangement__registered_by_appointment_id ON contract.signature_arrangement IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_arrangement__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_arrangement FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_arrangement__mutation_guard ON contract.signature_arrangement IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_arrangement FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_arrangement TO ${app_command_role};
GRANT SELECT ON contract.signature_arrangement TO ${app_query_role};
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__tenant ON contract.signature_draft IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__opportunity_id ON contract.signature_draft IS '同租户准确事实引用。';
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__readiness_id
    FOREIGN KEY (tenant_id, readiness_id)
    REFERENCES contract.signature_readiness (tenant_id, signature_readiness_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__readiness_id ON contract.signature_draft IS '同租户准确事实引用。';
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__arrangement_id ON contract.signature_draft IS '同租户准确事实引用。';
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__previous_draft_id
    FOREIGN KEY (tenant_id, previous_draft_id)
    REFERENCES contract.signature_draft (tenant_id, signature_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__previous_draft_id ON contract.signature_draft IS '同租户准确事实引用。';
ALTER TABLE contract.signature_draft
    ADD CONSTRAINT fk_signature_draft__saved_by_appointment_id
    FOREIGN KEY (tenant_id, saved_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_draft__saved_by_appointment_id ON contract.signature_draft IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_draft__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_draft FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_draft__mutation_guard ON contract.signature_draft IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_draft FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_draft TO ${app_command_role};
GRANT SELECT ON contract.signature_draft TO ${app_query_role};
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__tenant ON contract.signature_submission IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__opportunity_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__arrangement_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__signature_plan_id
    FOREIGN KEY (tenant_id, signature_plan_id)
    REFERENCES contract.signature_plan (tenant_id, signature_plan_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__signature_plan_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__previous_submission_id
    FOREIGN KEY (tenant_id, previous_submission_id)
    REFERENCES contract.signature_submission (tenant_id, signature_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__previous_submission_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__submitted_by_appointment_id
    FOREIGN KEY (tenant_id, submitted_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__submitted_by_appointment_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__material_version_id ON contract.signature_submission IS '同租户准确事实引用。';
ALTER TABLE contract.signature_submission
    ADD CONSTRAINT fk_signature_submission__authority_material_version_id
    FOREIGN KEY (tenant_id, authority_material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_submission__authority_material_version_id ON contract.signature_submission IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_submission__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_submission FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_submission__mutation_guard ON contract.signature_submission IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_submission FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_submission TO ${app_command_role};
GRANT SELECT ON contract.signature_submission TO ${app_query_role};
ALTER TABLE contract.signature_verification
    ADD CONSTRAINT fk_signature_verification__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_verification__tenant ON contract.signature_verification IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_verification
    ADD CONSTRAINT fk_signature_verification__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_verification__opportunity_id ON contract.signature_verification IS '同租户准确事实引用。';
ALTER TABLE contract.signature_verification
    ADD CONSTRAINT fk_signature_verification__submission_id
    FOREIGN KEY (tenant_id, submission_id)
    REFERENCES contract.signature_submission (tenant_id, signature_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_verification__submission_id ON contract.signature_verification IS '同租户准确事实引用。';
ALTER TABLE contract.signature_verification
    ADD CONSTRAINT fk_signature_verification__verified_by_appointment_id
    FOREIGN KEY (tenant_id, verified_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_verification__verified_by_appointment_id ON contract.signature_verification IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_verification__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_verification FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_verification__mutation_guard ON contract.signature_verification IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_verification FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_verification TO ${app_command_role};
GRANT SELECT ON contract.signature_verification TO ${app_query_role};
ALTER TABLE contract.signature_archive
    ADD CONSTRAINT fk_signature_archive__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_archive__tenant ON contract.signature_archive IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_archive
    ADD CONSTRAINT fk_signature_archive__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_archive__opportunity_id ON contract.signature_archive IS '同租户准确事实引用。';
ALTER TABLE contract.signature_archive
    ADD CONSTRAINT fk_signature_archive__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_archive__arrangement_id ON contract.signature_archive IS '同租户准确事实引用。';
ALTER TABLE contract.signature_archive
    ADD CONSTRAINT fk_signature_archive__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_archive__material_version_id ON contract.signature_archive IS '同租户准确事实引用。';
ALTER TABLE contract.signature_archive
    ADD CONSTRAINT fk_signature_archive__archived_by_appointment_id
    FOREIGN KEY (tenant_id, archived_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_archive__archived_by_appointment_id ON contract.signature_archive IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_archive__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_archive FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_archive__mutation_guard ON contract.signature_archive IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_archive FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_archive TO ${app_command_role};
GRANT SELECT ON contract.signature_archive TO ${app_query_role};
ALTER TABLE contract.signature_revision_return
    ADD CONSTRAINT fk_signature_revision_return__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_revision_return__tenant ON contract.signature_revision_return IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_revision_return
    ADD CONSTRAINT fk_signature_revision_return__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_revision_return__opportunity_id ON contract.signature_revision_return IS '同租户准确事实引用。';
ALTER TABLE contract.signature_revision_return
    ADD CONSTRAINT fk_signature_revision_return__readiness_id
    FOREIGN KEY (tenant_id, readiness_id)
    REFERENCES contract.signature_readiness (tenant_id, signature_readiness_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_revision_return__readiness_id ON contract.signature_revision_return IS '同租户准确事实引用。';
ALTER TABLE contract.signature_revision_return
    ADD CONSTRAINT fk_signature_revision_return__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_revision_return__contract_revision_id ON contract.signature_revision_return IS '同租户准确事实引用。';
ALTER TABLE contract.signature_revision_return
    ADD CONSTRAINT fk_signature_revision_return__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_revision_return__created_by_appointment_id ON contract.signature_revision_return IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_revision_return__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_revision_return FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_revision_return__mutation_guard ON contract.signature_revision_return IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_revision_return FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_revision_return TO ${app_command_role};
GRANT SELECT ON contract.signature_revision_return TO ${app_query_role};
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__tenant ON contract.signature_workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__opportunity_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__readiness_id
    FOREIGN KEY (tenant_id, readiness_id)
    REFERENCES contract.signature_readiness (tenant_id, signature_readiness_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__readiness_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__contract_revision_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES contract.signature_workflow (tenant_id, signature_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__previous_workflow_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__owner_appointment_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__created_by_appointment_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__task_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__prior_task_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__arrangement_id ON contract.signature_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.signature_workflow
    ADD CONSTRAINT fk_signature_workflow__submission_id
    FOREIGN KEY (tenant_id, submission_id)
    REFERENCES contract.signature_submission (tenant_id, signature_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_workflow__submission_id ON contract.signature_workflow IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_workflow__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_workflow__mutation_guard ON contract.signature_workflow IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_workflow TO ${app_command_role};
GRANT SELECT ON contract.signature_workflow TO ${app_query_role};
ALTER TABLE contract.signature_handoff
    ADD CONSTRAINT fk_signature_handoff__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_handoff__tenant ON contract.signature_handoff IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_handoff
    ADD CONSTRAINT fk_signature_handoff__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_handoff__opportunity_id ON contract.signature_handoff IS '同租户准确事实引用。';
ALTER TABLE contract.signature_handoff
    ADD CONSTRAINT fk_signature_handoff__readiness_id
    FOREIGN KEY (tenant_id, readiness_id)
    REFERENCES contract.signature_readiness (tenant_id, signature_readiness_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_handoff__readiness_id ON contract.signature_handoff IS '同租户准确事实引用。';
ALTER TABLE contract.signature_handoff
    ADD CONSTRAINT fk_signature_handoff__arrangement_id
    FOREIGN KEY (tenant_id, arrangement_id)
    REFERENCES contract.signature_arrangement (tenant_id, signature_arrangement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_handoff__arrangement_id ON contract.signature_handoff IS '同租户准确事实引用。';
ALTER TABLE contract.signature_handoff
    ADD CONSTRAINT fk_signature_handoff__archive_id
    FOREIGN KEY (tenant_id, archive_id)
    REFERENCES contract.signature_archive (tenant_id, signature_archive_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_handoff__archive_id ON contract.signature_handoff IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_handoff__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_handoff FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_handoff__mutation_guard ON contract.signature_handoff IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.signature_handoff FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_handoff TO ${app_command_role};
GRANT SELECT ON contract.signature_handoff TO ${app_query_role};
CREATE OR REPLACE FUNCTION opportunity.fn_check_closure() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; t responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id;
 IF o.revision IS DISTINCT FROM NEW.opportunity_revision+1 OR o.closed_at IS DISTINCT FROM NEW.closed_at OR o.close_outcome_code IS DISTINCT FROM NEW.reason_code THEN RAISE EXCEPTION 'closure root differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>NEW.opportunity_id OR NEW.responsibility_revision<>NEW.opportunity_revision OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'closure handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF (EXISTS(SELECT 1 FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) AND NOT EXISTS(
 SELECT 1 FROM opportunity.quote_workflow w JOIN opportunity.quote_revision q ON q.tenant_id=w.tenant_id AND q.quote_revision_id=w.quote_revision_id
 WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND q.quote_revision_id=o.current_quote_revision_id
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id)
 AND (w.stage='SALES_DISPOSITION' OR (w.stage IN ('DELIVER','AWAIT_REPLY') AND q.valid_until<=NEW.closed_at
      AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id WHERE i.tenant_id=q.tenant_id AND i.quote_revision_id=q.quote_revision_id)))
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id JOIN opportunity.quote_revision accepted ON accepted.tenant_id=i.tenant_id AND accepted.quote_revision_id=i.quote_revision_id WHERE accepted.tenant_id=NEW.tenant_id AND accepted.opportunity_id=NEW.opportunity_id AND r.response_code='ACCEPTED'))) OR EXISTS(SELECT 1 FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure has downstream facts' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND subject_type='opportunity.opportunity' AND subject_id=NEW.opportunity_id AND business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE','VERIFY_CONTRACT_SIGNATURE','ARCHIVE_CONTRACT_SIGNATURE') AND state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'closure retains active task' USING ERRCODE='23514'; END IF;
 IF NEW.task_occurrence_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_occurrence_id;
  IF t.state IS DISTINCT FROM 'CANCELLED' OR t.revision IS DISTINCT FROM NEW.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'opportunity.closure' OR t.cancellation_fact_id IS DISTINCT FROM NEW.closure_id OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.cancelled_at IS DISTINCT FROM NEW.closed_at THEN RAISE EXCEPTION 'closure cancellation differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_closure() IS '终结事务提交前检查准确商机、责任、取消及后续事实；不扩展查询角色权限。';
REVOKE ALL ON FUNCTION opportunity.fn_check_closure() FROM PUBLIC;
CREATE OR REPLACE FUNCTION responsibility.fn_guard_r2_handoff_task_initial() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.revision<>0 OR (NEW.state='OPEN' OR (NEW.state='WAITING' AND NEW.business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE') AND NEW.responsibility_basis_type='opportunity.responsibility_handoff' AND NEW.responsibility_basis_revision=0 AND NEW.handoff_predecessor_task_occurrence_id IS NOT NULL)) IS NOT TRUE THEN RAISE EXCEPTION 'invalid task initial state' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;

CREATE FUNCTION contract.fn_assert_manual_readiness(t uuid, ready uuid, version uuid, opp uuid, passing boolean DEFAULT true) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE c contract.contract%ROWTYPE; version_row contract.contract_revision%ROWTYPE;
BEGIN
 SELECT r.* INTO c FROM contract.signature_readiness s JOIN contract.contract_revision v ON v.tenant_id=s.tenant_id AND v.contract_revision_id=s.contract_revision_id JOIN contract.contract r ON r.tenant_id=v.tenant_id AND r.contract_id=v.contract_id WHERE s.tenant_id=t AND s.signature_readiness_id=ready AND s.contract_revision_id=version AND r.opportunity_id=opp AND r.current_revision_id=version AND (NOT passing OR r.approved_revision_id=version) AND r.contract_execution_id IS NULL AND r.contract_termination_id IS NULL FOR UPDATE OF r;
 IF NOT FOUND THEN RAISE EXCEPTION 'manual signature readiness is not current' USING ERRCODE='23514'; END IF;
 IF NOT passing THEN RETURN; END IF;
 PERFORM contract.fn_assert_r2_approval(t,c.contract_id,version);
 SELECT * INTO version_row FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=version;
 PERFORM contract.fn_assert_r2_source(t,opp,version_row.source_quote_response_id,version_row.source_direct_decision_id,version_row.customer_confirmation_id,version_row.commercial_digest);
 PERFORM contract.fn_assert_r2_material(t,version_row.body_evidence_version_id,version_row.body_sha256,opp);
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) IS '锁定准确当前批准版本并复验有效审查和全部审批。';
REVOKE ALL ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) TO ${app_command_role};

CREATE FUNCTION contract.fn_check_manual_signature() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE a contract.signature_arrangement%ROWTYPE; p contract.signature_plan%ROWTYPE; s contract.signature_submission%ROWTYPE; v contract.signature_verification%ROWTYPE; w contract.signature_workflow%ROWTYPE; r contract.contract_revision%ROWTYPE; archive contract.signature_archive%ROWTYPE; material_sha bytea; proof record; passing boolean := true; template contract.template_version%ROWTYPE; signer contract.template_signing_party%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='template_signing_party' THEN
  SELECT * INTO template FROM contract.template_version WHERE tenant_id=NEW.tenant_id AND template_version_id=NEW.template_version_id;
  IF template.template_version_id IS NULL OR template.created_in_transaction<>pg_current_xact_id() OR NEW.created_by_appointment_id<>template.approved_by_appointment_id THEN RAISE EXCEPTION 'manual template signer binding sealed' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM party.profile_version profile JOIN party.party party ON party.tenant_id=profile.tenant_id AND party.party_id=profile.party_id WHERE profile.tenant_id=NEW.tenant_id AND profile.profile_version_id=NEW.profile_version_id AND profile.party_id=NEW.party_id AND profile.party_revision=NEW.party_revision AND party.revision=NEW.party_revision AND profile.party_type='ORGANIZATION' AND sha256(convert_to(profile.profile_version_id::text,'UTF8'))=NEW.party_snapshot_digest) THEN RAISE EXCEPTION 'manual template signer profile differs' USING ERRCODE='23514'; END IF;
  NEW.created_in_transaction=pg_current_xact_id(); NEW.created_at=clock_timestamp(); RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='signature_plan' THEN IF NEW.arrangement_id IS NULL THEN
  IF EXISTS(SELECT 1 FROM contract.contract_revision x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=NEW.contract_revision_id AND x.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN RAISE EXCEPTION 'R2 signature plan requires manual arrangement' USING ERRCODE='23514'; END IF;
  RETURN NEW; END IF; END IF;
 IF TG_TABLE_NAME='contract_signature' THEN IF NEW.verification_id IS NULL THEN
  IF EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.signature_plan_id=NEW.signature_plan_id AND (x.arrangement_id IS NOT NULL OR EXISTS(SELECT 1 FROM contract.contract_revision cr WHERE cr.tenant_id=x.tenant_id AND cr.contract_revision_id=x.contract_revision_id AND cr.package_contract_code='R2_CONTRACT_PREPARATION_V1'))) THEN RAISE EXCEPTION 'manual signature requires verification' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF; END IF;
 IF TG_TABLE_NAME='signature_revision_return' THEN passing=false; END IF;
 IF TG_TABLE_NAME='signature_workflow' THEN passing=NEW.stage_code<>'REVISION_REQUIRED'; END IF;
 IF TG_TABLE_NAME='signature_verification' THEN passing=NEW.decision_code<>'REVISION_REQUIRED'; END IF;
 IF TG_TABLE_NAME NOT IN ('signature_plan','contract_signature') THEN NEW.created_in_transaction=pg_current_xact_id(); NEW.created_at=clock_timestamp(); END IF;
 IF TG_TABLE_NAME IN ('signature_arrangement','signature_workflow','signature_revision_return') THEN
  PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,NEW.readiness_id,NEW.contract_revision_id,NEW.opportunity_id,passing);
 END IF;
 IF TG_TABLE_NAME='signature_arrangement' THEN
  IF NEW.previous_arrangement_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_arrangement x WHERE x.tenant_id=NEW.tenant_id AND x.signature_arrangement_id=NEW.previous_arrangement_id AND x.readiness_id=NEW.readiness_id AND x.contract_revision_id=NEW.contract_revision_id AND x.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'manual signature arrangement predecessor differs' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM contract.signature_handoff x WHERE x.tenant_id=NEW.tenant_id AND x.readiness_id=NEW.readiness_id) THEN RAISE EXCEPTION 'manual signature already archived' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='signature_revision_return' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='signature_workflow' THEN
  IF NEW.previous_workflow_id IS NOT NULL THEN
   SELECT * INTO w FROM contract.signature_workflow WHERE tenant_id=NEW.tenant_id AND signature_workflow_id=NEW.previous_workflow_id;
   IF w.signature_workflow_id IS NULL OR w.readiness_id<>NEW.readiness_id OR w.contract_revision_id<>NEW.contract_revision_id OR w.opportunity_id<>NEW.opportunity_id OR w.due_at<>NEW.due_at THEN RAISE EXCEPTION 'manual signature workflow predecessor differs' USING ERRCODE='23514'; END IF;
  END IF;
  IF NEW.submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_submission x WHERE x.tenant_id=NEW.tenant_id AND x.signature_submission_id=NEW.submission_id AND x.arrangement_id=NEW.arrangement_id) THEN RAISE EXCEPTION 'manual signature workflow submission differs' USING ERRCODE='23514'; END IF;
  IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.task_id AND task.owner_appointment_id=NEW.owner_appointment_id AND task.subject_id=NEW.opportunity_id AND task.original_sla_due_at=NEW.due_at AND task.state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'manual signature workflow task differs' USING ERRCODE='23514'; END IF;
  IF NEW.arrangement_id IS NULL THEN RETURN NEW; END IF;
 END IF;
 IF TG_TABLE_NAME='signature_draft' THEN
  IF NEW.previous_draft_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_draft x WHERE x.tenant_id=NEW.tenant_id AND x.signature_draft_id=NEW.previous_draft_id AND x.readiness_id=NEW.readiness_id AND x.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'manual signature draft predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.arrangement_id IS NULL THEN
  SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=(SELECT contract_revision_id FROM contract.signature_readiness WHERE tenant_id=NEW.tenant_id AND signature_readiness_id=NEW.readiness_id);
  PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,NEW.readiness_id,r.contract_revision_id,NEW.opportunity_id);
  RETURN NEW;
 END IF; END IF;
 IF TG_TABLE_NAME IN ('signature_verification','contract_signature') THEN
  IF TG_TABLE_NAME='contract_signature' THEN
   SELECT * INTO v FROM contract.signature_verification WHERE tenant_id=NEW.tenant_id AND signature_verification_id=NEW.verification_id;
   SELECT * INTO s FROM contract.signature_submission WHERE tenant_id=NEW.tenant_id AND signature_submission_id=v.submission_id;
  ELSE SELECT * INTO s FROM contract.signature_submission WHERE tenant_id=NEW.tenant_id AND signature_submission_id=NEW.submission_id;
  END IF;
  SELECT * INTO a FROM contract.signature_arrangement WHERE tenant_id=NEW.tenant_id AND signature_arrangement_id=s.arrangement_id;
 ELSE SELECT * INTO a FROM contract.signature_arrangement WHERE tenant_id=NEW.tenant_id AND signature_arrangement_id=NEW.arrangement_id;
 END IF;
 IF a.signature_arrangement_id IS NULL THEN RAISE EXCEPTION 'manual signature arrangement missing' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,a.readiness_id,a.contract_revision_id,a.opportunity_id,passing);
 IF EXISTS(SELECT 1 FROM contract.signature_arrangement x WHERE x.tenant_id=NEW.tenant_id AND x.previous_arrangement_id=a.signature_arrangement_id) THEN RAISE EXCEPTION 'manual signature arrangement superseded' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME NOT IN ('signature_plan','contract_signature') THEN IF NEW.opportunity_id<>a.opportunity_id THEN RAISE EXCEPTION 'manual signature opportunity differs' USING ERRCODE='23514'; END IF; END IF;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=a.contract_revision_id;
 IF TG_TABLE_NAME IN ('signature_draft','signature_workflow') THEN
  IF NEW.readiness_id<>a.readiness_id THEN RAISE EXCEPTION 'manual signature readiness differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='signature_plan' THEN
  IF NEW.template_signing_party_id IS NOT NULL THEN
   SELECT * INTO signer FROM contract.template_signing_party WHERE tenant_id=NEW.tenant_id AND template_signing_party_id=NEW.template_signing_party_id;
   IF signer.template_signing_party_id IS NULL OR signer.template_version_id<>r.template_version_id OR signer.party_id<>NEW.signer_party_id OR NOT NEW.required OR NEW.contract_participation_id IS NOT NULL THEN RAISE EXCEPTION 'manual signature template signer differs' USING ERRCODE='23514'; END IF;
  END IF;
  IF a.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'manual signature collection sealed' USING ERRCODE='23514'; END IF;
  IF NEW.contract_revision_id<>a.contract_revision_id OR NEW.plan_digest<>a.body_digest THEN RAISE EXCEPTION 'manual signature plan basis differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='signature_submission' THEN
  SELECT * INTO p FROM contract.signature_plan WHERE tenant_id=NEW.tenant_id AND signature_plan_id=NEW.signature_plan_id;
  IF p.arrangement_id IS DISTINCT FROM a.signature_arrangement_id OR NEW.approved_body_sha256<>r.body_sha256 THEN RAISE EXCEPTION 'manual signature submission basis differs' USING ERRCODE='23514'; END IF;
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.material_version_id,NEW.material_sha256,a.opportunity_id);
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.authority_material_version_id,NEW.authority_material_sha256,a.opportunity_id);
  IF NEW.previous_submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_submission x JOIN contract.signature_verification d ON d.tenant_id=x.tenant_id AND d.submission_id=x.signature_submission_id WHERE x.tenant_id=NEW.tenant_id AND x.signature_submission_id=NEW.previous_submission_id AND x.signature_plan_id=NEW.signature_plan_id AND x.arrangement_id=NEW.arrangement_id AND d.decision_code='NEED_INFO') THEN RAISE EXCEPTION 'manual signature supplement predecessor differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='signature_verification' THEN
  IF s.signature_submission_id IS NULL OR EXISTS(SELECT 1 FROM contract.signature_submission x WHERE x.tenant_id=NEW.tenant_id AND x.previous_submission_id=s.signature_submission_id) THEN RAISE EXCEPTION 'manual signature verification basis differs' USING ERRCODE='23514'; END IF;
  IF NEW.decision_code='VERIFIED' THEN
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,s.material_version_id,s.material_sha256,a.opportunity_id);
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,s.authority_material_version_id,s.authority_material_sha256,a.opportunity_id);
  END IF;
 ELSIF TG_TABLE_NAME='contract_signature' THEN
  SELECT * INTO p FROM contract.signature_plan WHERE tenant_id=NEW.tenant_id AND signature_plan_id=s.signature_plan_id;
  IF v.decision_code IS DISTINCT FROM 'VERIFIED' OR v.created_in_transaction<>pg_current_xact_id() OR NEW.signature_plan_id<>s.signature_plan_id OR NEW.contract_revision_id<>a.contract_revision_id OR NEW.signer_party_id<>p.signer_party_id OR NEW.signed_content_digest<>s.approved_body_sha256 OR NEW.signed_at<>s.signed_at THEN RAISE EXCEPTION 'manual signature verification basis differs' USING ERRCODE='23514'; END IF;
  NEW.verified_at=clock_timestamp(); NEW.created_at=NEW.verified_at; NEW.changed_at=NEW.verified_at;
 ELSIF TG_TABLE_NAME='signature_archive' THEN
  IF (SELECT count(*) FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id)<>a.slot_count OR NOT EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id AND x.required) OR EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id AND x.required AND NOT EXISTS(SELECT 1 FROM contract.contract_signature cs WHERE cs.tenant_id=x.tenant_id AND cs.signature_plan_id=x.signature_plan_id AND cs.verification_id IS NOT NULL AND cs.revoked_at IS NULL)) THEN RAISE EXCEPTION 'manual signature archive incomplete' USING ERRCODE='23514'; END IF;
  FOR proof IN SELECT u.* FROM contract.signature_plan plan JOIN contract.contract_signature cs ON cs.tenant_id=plan.tenant_id AND cs.signature_plan_id=plan.signature_plan_id JOIN contract.signature_verification d ON d.tenant_id=cs.tenant_id AND d.signature_verification_id=cs.verification_id JOIN contract.signature_submission u ON u.tenant_id=d.tenant_id AND u.signature_submission_id=d.submission_id WHERE plan.tenant_id=NEW.tenant_id AND plan.arrangement_id=a.signature_arrangement_id AND plan.required AND cs.revoked_at IS NULL LOOP
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,proof.material_version_id,proof.material_sha256,a.opportunity_id);
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,proof.authority_material_version_id,proof.authority_material_sha256,a.opportunity_id);
  END LOOP;
  SELECT src.server_sha256 INTO material_sha FROM opportunity.material_version m JOIN evidence.received_source_object src ON src.tenant_id=m.tenant_id AND src.received_source_object_id=m.received_source_object_id WHERE m.tenant_id=NEW.tenant_id AND m.material_version_id=NEW.material_version_id;
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.material_version_id,material_sha,a.opportunity_id);
 ELSIF TG_TABLE_NAME='signature_handoff' THEN
  SELECT * INTO archive FROM contract.signature_archive WHERE tenant_id=NEW.tenant_id AND signature_archive_id=NEW.archive_id;
  IF NEW.readiness_id<>a.readiness_id OR archive.arrangement_id IS DISTINCT FROM a.signature_arrangement_id OR archive.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'manual signature handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_manual_signature() IS '准确人工签署输入、不可变补证和完整归档守卫。';
REVOKE ALL ON FUNCTION contract.fn_check_manual_signature() FROM PUBLIC;

CREATE FUNCTION contract.fn_check_manual_signature_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF TG_TABLE_NAME='signature_arrangement' THEN
  IF (SELECT count(*) FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id)<>NEW.slot_count OR NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id AND p.required) OR NOT EXISTS(SELECT 1 FROM contract.template_signing_party binding JOIN contract.contract_revision revision ON revision.tenant_id=binding.tenant_id AND revision.template_version_id=binding.template_version_id WHERE revision.tenant_id=NEW.tenant_id AND revision.contract_revision_id=NEW.contract_revision_id) OR EXISTS(SELECT 1 FROM contract.template_signing_party binding JOIN contract.contract_revision revision ON revision.tenant_id=binding.tenant_id AND revision.template_version_id=binding.template_version_id WHERE revision.tenant_id=NEW.tenant_id AND revision.contract_revision_id=NEW.contract_revision_id AND NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id AND p.template_signing_party_id=binding.template_signing_party_id AND p.required)) THEN RAISE EXCEPTION 'manual signature arrangement set incomplete' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.decision_code='VERIFIED' AND NOT EXISTS(SELECT 1 FROM contract.contract_signature cs WHERE cs.tenant_id=NEW.tenant_id AND cs.verification_id=NEW.signature_verification_id) THEN RAISE EXCEPTION 'manual signature verified fact missing' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_manual_signature_complete() IS '安排完整集合及核验通过签署同事务封存。';
REVOKE ALL ON FUNCTION contract.fn_check_manual_signature_complete() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER ctrg_signature_arrangement__complete AFTER INSERT ON contract.signature_arrangement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature_complete();
COMMENT ON TRIGGER ctrg_signature_arrangement__complete ON contract.signature_arrangement IS '完整非空安排封存。';
CREATE CONSTRAINT TRIGGER ctrg_signature_verification__complete AFTER INSERT ON contract.signature_verification DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature_complete();
COMMENT ON TRIGGER ctrg_signature_verification__complete ON contract.signature_verification IS '核验通过必须同事务形成签署。';
CREATE TRIGGER trg_template_signing_party__manual_basis BEFORE INSERT ON contract.template_signing_party FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_template_signing_party__manual_basis ON contract.template_signing_party IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_arrangement__manual_basis BEFORE INSERT ON contract.signature_arrangement FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_arrangement__manual_basis ON contract.signature_arrangement IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_draft__manual_basis BEFORE INSERT ON contract.signature_draft FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_draft__manual_basis ON contract.signature_draft IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_submission__manual_basis BEFORE INSERT ON contract.signature_submission FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_submission__manual_basis ON contract.signature_submission IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_verification__manual_basis BEFORE INSERT ON contract.signature_verification FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_verification__manual_basis ON contract.signature_verification IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_archive__manual_basis BEFORE INSERT ON contract.signature_archive FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_archive__manual_basis ON contract.signature_archive IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_revision_return__manual_basis BEFORE INSERT ON contract.signature_revision_return FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_revision_return__manual_basis ON contract.signature_revision_return IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_workflow__manual_basis BEFORE INSERT ON contract.signature_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_workflow__manual_basis ON contract.signature_workflow IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_handoff__manual_basis BEFORE INSERT ON contract.signature_handoff FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_handoff__manual_basis ON contract.signature_handoff IS '准确准备、安排、材料和核验依据。';
CREATE TRIGGER trg_signature_plan__manual_basis BEFORE INSERT ON contract.signature_plan FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_signature_plan__manual_basis ON contract.signature_plan IS '人工证据具名路径守卫。';
CREATE TRIGGER trg_contract_signature__manual_basis BEFORE INSERT ON contract.contract_signature FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();
COMMENT ON TRIGGER trg_contract_signature__manual_basis ON contract.contract_signature IS '人工证据具名路径守卫。';
DO $v990$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v13',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v12';
 IF NOT FOUND THEN RAISE EXCEPTION 'V990 requires 52-plus-2-r2-v12' USING ERRCODE='55000'; END IF;
END;
$v990$;
