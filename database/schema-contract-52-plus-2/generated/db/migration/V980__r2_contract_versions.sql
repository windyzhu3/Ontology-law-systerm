CREATE TABLE contract.approval_policy (
    tenant_id uuid NOT NULL,
    approval_policy_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    organization_unit_id uuid NOT NULL,
    policy_code varchar(64) NOT NULL,
    policy_version bigint NOT NULL,
    mode varchar(64) NOT NULL,
    policy_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_approval_policy PRIMARY KEY (tenant_id, approval_policy_id),
    CONSTRAINT ck_approval_policy__revision CHECK (revision=0),
    CONSTRAINT uq_approval_policy__version UNIQUE (tenant_id, organization_unit_id, policy_code, policy_version),
    CONSTRAINT ck_approval_policy__values CHECK (policy_code='R2_CONTRACT_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode='REQUIRE_APPROVAL'),
    CONSTRAINT ck_approval_policy__policy_digest_length CHECK (octet_length(policy_digest) = 32)
);

COMMENT ON TABLE contract.approval_policy IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_approval_policy ON contract.approval_policy IS '主键：在租户内唯一标识一条approval_policy记录。';
COMMENT ON INDEX contract.pk_approval_policy IS '主键：在租户内唯一标识一条approval_policy记录。';
COMMENT ON COLUMN contract.approval_policy.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.approval_policy.approval_policy_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.approval_policy.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.approval_policy.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.approval_policy.organization_unit_id IS '明确适用组织。';
COMMENT ON COLUMN contract.approval_policy.policy_code IS '具名合同审批策略。';
COMMENT ON COLUMN contract.approval_policy.policy_version IS '明确策略版本。';
COMMENT ON COLUMN contract.approval_policy.mode IS '明确要求批准；不复用报价自授权。';
COMMENT ON COLUMN contract.approval_policy.policy_digest IS '策略和审批成员规范摘要。';
COMMENT ON COLUMN contract.approval_policy.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_approval_policy__revision ON contract.approval_policy IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_approval_policy__version ON contract.approval_policy IS '组织策略版本唯一。';
COMMENT ON INDEX contract.uq_approval_policy__version IS '组织策略版本唯一。';
COMMENT ON CONSTRAINT ck_approval_policy__values ON contract.approval_policy IS '明确合同审批策略。';
COMMENT ON CONSTRAINT ck_approval_policy__policy_digest_length ON contract.approval_policy IS '摘要格式：policy_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.approval_policy_member (
    tenant_id uuid NOT NULL,
    approval_policy_member_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    policy_id uuid NOT NULL,
    requirement_code varchar(64) NOT NULL,
    appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_approval_policy_member PRIMARY KEY (tenant_id, approval_policy_member_id),
    CONSTRAINT ck_approval_policy_member__revision CHECK (revision=0),
    CONSTRAINT uq_approval_policy_member__requirement UNIQUE (tenant_id, policy_id, requirement_code)
);

COMMENT ON TABLE contract.approval_policy_member IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_approval_policy_member ON contract.approval_policy_member IS '主键：在租户内唯一标识一条approval_policy_member记录。';
COMMENT ON INDEX contract.pk_approval_policy_member IS '主键：在租户内唯一标识一条approval_policy_member记录。';
COMMENT ON COLUMN contract.approval_policy_member.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.approval_policy_member.approval_policy_member_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.approval_policy_member.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.approval_policy_member.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.approval_policy_member.policy_id IS '准确策略版本。';
COMMENT ON COLUMN contract.approval_policy_member.requirement_code IS '明确审批要求。';
COMMENT ON COLUMN contract.approval_policy_member.appointment_id IS '明确有权审批任职。';
COMMENT ON COLUMN contract.approval_policy_member.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_approval_policy_member__revision ON contract.approval_policy_member IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_approval_policy_member__requirement ON contract.approval_policy_member IS '每个策略要求唯一。';
COMMENT ON INDEX contract.uq_approval_policy_member__requirement IS '每个策略要求唯一。';
CREATE TABLE contract.preparation_workflow (
    tenant_id uuid NOT NULL,
    preparation_workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    contract_id uuid,
    previous_workflow_id uuid,
    stage_code varchar(64) NOT NULL,
    owner_appointment_id uuid NOT NULL,
    task_id uuid,
    prior_task_id uuid,
    created_at timestamptz(6) NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    recovery_resume_stage varchar(64),
    CONSTRAINT pk_preparation_workflow PRIMARY KEY (tenant_id, preparation_workflow_id),
    CONSTRAINT ck_preparation_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_preparation_workflow__previous_workflow_id UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_preparation_workflow__stage CHECK (stage_code IN ('DIRECT_REQUEST','DIRECT_REVIEW','DIRECT_RETURNED','PREPARE','RETURNED','SUBMIT_REVIEW','AWAIT_REVIEW','REVIEW_SUPPLEMENT','REVIEW_BLOCKED','SUBMIT_APPROVAL','AWAIT_APPROVAL','READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE','OWNER_EXCEPTION') AND (stage_code NOT IN ('READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE') OR task_id IS NULL)),
    CONSTRAINT ck_preparation_workflow__recovery_resume CHECK (recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('DIRECT_RETURNED','RETURNED') AND task_id IS NULL AND prior_task_id IS NOT NULL))
);

COMMENT ON TABLE contract.preparation_workflow IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_preparation_workflow ON contract.preparation_workflow IS '主键：在租户内唯一标识一条preparation_workflow记录。';
COMMENT ON INDEX contract.pk_preparation_workflow IS '主键：在租户内唯一标识一条preparation_workflow记录。';
COMMENT ON COLUMN contract.preparation_workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.preparation_workflow.preparation_workflow_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.preparation_workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.preparation_workflow.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.preparation_workflow.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.preparation_workflow.contract_id IS '形成后的唯一合同身份。';
COMMENT ON COLUMN contract.preparation_workflow.previous_workflow_id IS '直接前一流程事实。';
COMMENT ON COLUMN contract.preparation_workflow.stage_code IS '明确合同准备责任阶段。';
COMMENT ON COLUMN contract.preparation_workflow.owner_appointment_id IS '实际后继负责人。';
COMMENT ON COLUMN contract.preparation_workflow.task_id IS '可办理的后继待办。';
COMMENT ON COLUMN contract.preparation_workflow.prior_task_id IS '移交的准确前序待办。';
COMMENT ON COLUMN contract.preparation_workflow.created_at IS '数据库形成时间。';
COMMENT ON COLUMN contract.preparation_workflow.created_by_appointment_id IS '真实流程写入任职；与后继责任人分离。';
COMMENT ON COLUMN contract.preparation_workflow.recovery_resume_stage IS '失效独立责任归还销售时的准确恢复阶段；旧报价承接异常为空。';
COMMENT ON CONSTRAINT ck_preparation_workflow__revision ON contract.preparation_workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_preparation_workflow__previous_workflow_id ON contract.preparation_workflow IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_preparation_workflow__previous_workflow_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_preparation_workflow__stage ON contract.preparation_workflow IS '明确责任阶段；下阶段边界不创建占位待办。';
COMMENT ON CONSTRAINT ck_preparation_workflow__recovery_resume ON contract.preparation_workflow IS '仅无可办理任务的负责人异常保留恢复目标；不伪造退回决定。';
CREATE TABLE contract.revision_approval_request (
    tenant_id uuid NOT NULL,
    revision_approval_request_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    review_binding_id uuid NOT NULL,
    requested_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_revision_approval_request PRIMARY KEY (tenant_id, revision_approval_request_id),
    CONSTRAINT ck_revision_approval_request__revision CHECK (revision=0),
    CONSTRAINT uq_revision_approval_request__contract_revision_id UNIQUE (tenant_id, contract_revision_id),
    CONSTRAINT uq_revision_approval_request__review_binding_id UNIQUE (tenant_id, review_binding_id)
);

COMMENT ON TABLE contract.revision_approval_request IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_approval_request ON contract.revision_approval_request IS '主键：在租户内唯一标识一条revision_approval_request记录。';
COMMENT ON INDEX contract.pk_revision_approval_request IS '主键：在租户内唯一标识一条revision_approval_request记录。';
COMMENT ON COLUMN contract.revision_approval_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_approval_request.revision_approval_request_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_approval_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_approval_request.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_approval_request.contract_revision_id IS '准确版本。';
COMMENT ON COLUMN contract.revision_approval_request.review_binding_id IS '本版通过审查。';
COMMENT ON COLUMN contract.revision_approval_request.requested_by_appointment_id IS '提交任职。';
COMMENT ON COLUMN contract.revision_approval_request.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_revision_approval_request__revision ON contract.revision_approval_request IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_approval_request__contract_revision_id ON contract.revision_approval_request IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_approval_request__contract_revision_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT uq_revision_approval_request__review_binding_id ON contract.revision_approval_request IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_approval_request__review_binding_id IS '准确事实唯一。';
CREATE TABLE contract.preparation_draft (
    tenant_id uuid NOT NULL,
    preparation_draft_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    owner_appointment_id uuid NOT NULL,
    customer_confirmation_id uuid NOT NULL,
    previous_draft_id uuid,
    source_quote_response_id uuid,
    source_direct_decision_id uuid,
    commercial_digest bytea NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_preparation_draft PRIMARY KEY (tenant_id, preparation_draft_id),
    CONSTRAINT ck_preparation_draft__revision CHECK (revision=0),
    CONSTRAINT ck_preparation_draft__basis CHECK (responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_preparation_draft__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_preparation_draft__previous_draft_id UNIQUE (tenant_id, previous_draft_id),
    CONSTRAINT ck_preparation_draft__source CHECK (num_nonnulls(source_quote_response_id,source_direct_decision_id)=1),
    CONSTRAINT ck_preparation_draft__commercial_digest_length CHECK (octet_length(commercial_digest) = 32),
    CONSTRAINT ck_preparation_draft__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.preparation_draft IS 'Fact Owner：ContractRuntime；准备草稿；保存不等于形成版本或完成责任。';
COMMENT ON CONSTRAINT pk_preparation_draft ON contract.preparation_draft IS '主键：在租户内唯一标识一条preparation_draft记录。';
COMMENT ON INDEX contract.pk_preparation_draft IS '主键：在租户内唯一标识一条preparation_draft记录。';
COMMENT ON COLUMN contract.preparation_draft.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.preparation_draft.preparation_draft_id IS '准备草稿；保存不等于形成版本或完成责任。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.preparation_draft.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.preparation_draft.created_in_transaction IS '由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。';
COMMENT ON COLUMN contract.preparation_draft.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.preparation_draft.opportunity_revision IS '读取的商机版本。';
COMMENT ON COLUMN contract.preparation_draft.responsibility_type IS '责任依据类型。';
COMMENT ON COLUMN contract.preparation_draft.responsibility_id IS '责任依据身份。';
COMMENT ON COLUMN contract.preparation_draft.responsibility_revision IS '责任依据版本。';
COMMENT ON COLUMN contract.preparation_draft.owner_appointment_id IS '保存/确认的当前负责人。';
COMMENT ON COLUMN contract.preparation_draft.customer_confirmation_id IS '当前客户需求确认。';
COMMENT ON COLUMN contract.preparation_draft.previous_draft_id IS '同商机直接前稿；换负责人也沿用单链。';
COMMENT ON COLUMN contract.preparation_draft.source_quote_response_id IS '准确接受报价。';
COMMENT ON COLUMN contract.preparation_draft.source_direct_decision_id IS '准确直接授权。';
COMMENT ON COLUMN contract.preparation_draft.commercial_digest IS '准确商业摘要。';
COMMENT ON COLUMN contract.preparation_draft.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.preparation_draft.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.preparation_draft.created_at IS '保存时间。';
COMMENT ON CONSTRAINT ck_preparation_draft__revision ON contract.preparation_draft IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_preparation_draft__basis ON contract.preparation_draft IS '准确责任和版本。';
COMMENT ON CONSTRAINT ck_preparation_draft__body ON contract.preparation_draft IS '有界加密正文。';
COMMENT ON CONSTRAINT uq_preparation_draft__previous_draft_id ON contract.preparation_draft IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_preparation_draft__previous_draft_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_preparation_draft__source ON contract.preparation_draft IS '双入口互斥。';
COMMENT ON CONSTRAINT ck_preparation_draft__commercial_digest_length ON contract.preparation_draft IS '摘要格式：commercial_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_preparation_draft__body_digest_length ON contract.preparation_draft IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.template_version (
    tenant_id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    document_code varchar(64) NOT NULL,
    version_no integer NOT NULL,
    evidence_version_id uuid NOT NULL,
    body_sha256 bytea NOT NULL,
    approved_by_appointment_id uuid NOT NULL,
    approved_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_template_version PRIMARY KEY (tenant_id, template_version_id),
    CONSTRAINT ck_template_version__revision CHECK (revision=0),
    CONSTRAINT uq_template_version__code_version UNIQUE (tenant_id, document_code, version_no),
    CONSTRAINT ck_template_version__version CHECK (version_no>0 AND approved_at<=created_at),
    CONSTRAINT ck_template_version__body_sha256_length CHECK (octet_length(body_sha256) = 32)
);

COMMENT ON TABLE contract.template_version IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_template_version ON contract.template_version IS '主键：在租户内唯一标识一条template_version记录。';
COMMENT ON INDEX contract.pk_template_version IS '主键：在租户内唯一标识一条template_version记录。';
COMMENT ON COLUMN contract.template_version.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.template_version.template_version_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.template_version.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.template_version.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.template_version.document_code IS '受控文档代码。';
COMMENT ON COLUMN contract.template_version.version_no IS '已审核文档版本。';
COMMENT ON COLUMN contract.template_version.evidence_version_id IS '真实T06原件版本。';
COMMENT ON COLUMN contract.template_version.body_sha256 IS '真实文档字节摘要。';
COMMENT ON COLUMN contract.template_version.approved_by_appointment_id IS '有权审核者。';
COMMENT ON COLUMN contract.template_version.approved_at IS '真实审核时间。';
COMMENT ON COLUMN contract.template_version.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_template_version__revision ON contract.template_version IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_template_version__code_version ON contract.template_version IS '已审核文档版本唯一。';
COMMENT ON INDEX contract.uq_template_version__code_version IS '已审核文档版本唯一。';
COMMENT ON CONSTRAINT ck_template_version__version ON contract.template_version IS '真实审核版本和时间。';
COMMENT ON CONSTRAINT ck_template_version__body_sha256_length ON contract.template_version IS '摘要格式：body_sha256必须保存32字节的规范二进制值。';
CREATE TABLE contract.clause_version (
    tenant_id uuid NOT NULL,
    clause_version_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    document_code varchar(64) NOT NULL,
    version_no integer NOT NULL,
    evidence_version_id uuid NOT NULL,
    body_sha256 bytea NOT NULL,
    approved_by_appointment_id uuid NOT NULL,
    approved_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_clause_version PRIMARY KEY (tenant_id, clause_version_id),
    CONSTRAINT ck_clause_version__revision CHECK (revision=0),
    CONSTRAINT uq_clause_version__code_version UNIQUE (tenant_id, document_code, version_no),
    CONSTRAINT ck_clause_version__version CHECK (version_no>0 AND approved_at<=created_at),
    CONSTRAINT ck_clause_version__body_sha256_length CHECK (octet_length(body_sha256) = 32)
);

COMMENT ON TABLE contract.clause_version IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_clause_version ON contract.clause_version IS '主键：在租户内唯一标识一条clause_version记录。';
COMMENT ON INDEX contract.pk_clause_version IS '主键：在租户内唯一标识一条clause_version记录。';
COMMENT ON COLUMN contract.clause_version.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.clause_version.clause_version_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.clause_version.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.clause_version.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.clause_version.document_code IS '受控文档代码。';
COMMENT ON COLUMN contract.clause_version.version_no IS '已审核文档版本。';
COMMENT ON COLUMN contract.clause_version.evidence_version_id IS '真实T06原件版本。';
COMMENT ON COLUMN contract.clause_version.body_sha256 IS '真实文档字节摘要。';
COMMENT ON COLUMN contract.clause_version.approved_by_appointment_id IS '有权审核者。';
COMMENT ON COLUMN contract.clause_version.approved_at IS '真实审核时间。';
COMMENT ON COLUMN contract.clause_version.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_clause_version__revision ON contract.clause_version IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_clause_version__code_version ON contract.clause_version IS '已审核文档版本唯一。';
COMMENT ON INDEX contract.uq_clause_version__code_version IS '已审核文档版本唯一。';
COMMENT ON CONSTRAINT ck_clause_version__version ON contract.clause_version IS '真实审核版本和时间。';
COMMENT ON CONSTRAINT ck_clause_version__body_sha256_length ON contract.clause_version IS '摘要格式：body_sha256必须保存32字节的规范二进制值。';
CREATE TABLE contract.revision_clause (
    tenant_id uuid NOT NULL,
    revision_clause_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    clause_version_id uuid NOT NULL,
    clause_no integer NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_revision_clause PRIMARY KEY (tenant_id, revision_clause_id),
    CONSTRAINT ck_revision_clause__revision CHECK (revision=0),
    CONSTRAINT uq_revision_clause__number UNIQUE (tenant_id, contract_revision_id, clause_no),
    CONSTRAINT uq_revision_clause__clause UNIQUE (tenant_id, contract_revision_id, clause_version_id),
    CONSTRAINT ck_revision_clause__no CHECK (clause_no BETWEEN 1 AND 200)
);

COMMENT ON TABLE contract.revision_clause IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_clause ON contract.revision_clause IS '主键：在租户内唯一标识一条revision_clause记录。';
COMMENT ON INDEX contract.pk_revision_clause IS '主键：在租户内唯一标识一条revision_clause记录。';
COMMENT ON COLUMN contract.revision_clause.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_clause.revision_clause_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_clause.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_clause.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_clause.contract_revision_id IS '准确合同版本。';
COMMENT ON COLUMN contract.revision_clause.clause_version_id IS '准确已审核条款。';
COMMENT ON COLUMN contract.revision_clause.clause_no IS '版本内顺序。';
COMMENT ON COLUMN contract.revision_clause.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_revision_clause__revision ON contract.revision_clause IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_clause__number ON contract.revision_clause IS '序号唯一。';
COMMENT ON INDEX contract.uq_revision_clause__number IS '序号唯一。';
COMMENT ON CONSTRAINT uq_revision_clause__clause ON contract.revision_clause IS '条款唯一。';
COMMENT ON INDEX contract.uq_revision_clause__clause IS '条款唯一。';
COMMENT ON CONSTRAINT ck_revision_clause__no ON contract.revision_clause IS '有界条款集合。';
CREATE TABLE contract.revision_review_request (
    tenant_id uuid NOT NULL,
    revision_review_request_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    scope_hash bytea NOT NULL,
    requested_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    previous_request_id uuid,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    CONSTRAINT pk_revision_review_request PRIMARY KEY (tenant_id, revision_review_request_id),
    CONSTRAINT ck_revision_review_request__revision CHECK (revision=0),
    CONSTRAINT ck_revision_review_request__scope_hash_length CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT uq_revision_review_request__previous_request_id UNIQUE (tenant_id, previous_request_id),
    CONSTRAINT ck_revision_review_request__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072)
);

COMMENT ON TABLE contract.revision_review_request IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_review_request ON contract.revision_review_request IS '主键：在租户内唯一标识一条revision_review_request记录。';
COMMENT ON INDEX contract.pk_revision_review_request IS '主键：在租户内唯一标识一条revision_review_request记录。';
COMMENT ON COLUMN contract.revision_review_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_review_request.revision_review_request_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_review_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_review_request.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_review_request.contract_revision_id IS '请求审查的准确版本。';
COMMENT ON COLUMN contract.revision_review_request.scope_hash IS '本次准确审查范围。';
COMMENT ON COLUMN contract.revision_review_request.requested_by_appointment_id IS '提交任职。';
COMMENT ON COLUMN contract.revision_review_request.created_at IS '数据库形成时间。';
COMMENT ON COLUMN contract.revision_review_request.previous_request_id IS '前次补正审查申请。';
COMMENT ON COLUMN contract.revision_review_request.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.revision_review_request.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON CONSTRAINT ck_revision_review_request__revision ON contract.revision_review_request IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_revision_review_request__scope_hash_length ON contract.revision_review_request IS '摘要格式：scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT uq_revision_review_request__previous_request_id ON contract.revision_review_request IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_review_request__previous_request_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_revision_review_request__body ON contract.revision_review_request IS '有界受保护正文。';
CREATE TABLE contract.revision_review_decision (
    tenant_id uuid NOT NULL,
    revision_review_decision_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    request_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    decision_code varchar(64) NOT NULL,
    scope_hash bytea NOT NULL,
    resolution_digest bytea,
    decided_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_revision_review_decision PRIMARY KEY (tenant_id, revision_review_decision_id),
    CONSTRAINT ck_revision_review_decision__revision CHECK (revision=0),
    CONSTRAINT uq_revision_review_decision__request_id UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_revision_review_decision__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_revision_review_decision__code CHECK (decision_code IN ('CLEAR','WAIVED','NEED_INFO','BLOCKED') AND (decision_code NOT IN ('CLEAR','WAIVED') OR resolution_digest IS NOT NULL)),
    CONSTRAINT ck_revision_review_decision__scope_hash_length CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT ck_revision_review_decision__resolution_digest_length CHECK (octet_length(resolution_digest) = 32),
    CONSTRAINT ck_revision_review_decision__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.revision_review_decision IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_review_decision ON contract.revision_review_decision IS '主键：在租户内唯一标识一条revision_review_decision记录。';
COMMENT ON INDEX contract.pk_revision_review_decision IS '主键：在租户内唯一标识一条revision_review_decision记录。';
COMMENT ON COLUMN contract.revision_review_decision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_review_decision.revision_review_decision_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_review_decision.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_review_decision.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_review_decision.request_id IS '准确审查申请。';
COMMENT ON COLUMN contract.revision_review_decision.conflict_review_id IS '真实独立冲突审查。';
COMMENT ON COLUMN contract.revision_review_decision.decision_code IS '审查结果CLEAR/WAIVED/NEED_INFO/BLOCKED。';
COMMENT ON COLUMN contract.revision_review_decision.scope_hash IS '准确范围。';
COMMENT ON COLUMN contract.revision_review_decision.resolution_digest IS '准确结论摘要；补正阻断可空。';
COMMENT ON COLUMN contract.revision_review_decision.decided_by_appointment_id IS '实际审查任职。';
COMMENT ON COLUMN contract.revision_review_decision.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.revision_review_decision.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.revision_review_decision.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_revision_review_decision__revision ON contract.revision_review_decision IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_review_decision__request_id ON contract.revision_review_decision IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_review_decision__request_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_revision_review_decision__body ON contract.revision_review_decision IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_revision_review_decision__code ON contract.revision_review_decision IS '通过必须有准确结论依据。';
COMMENT ON CONSTRAINT ck_revision_review_decision__scope_hash_length ON contract.revision_review_decision IS '摘要格式：scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_revision_review_decision__resolution_digest_length ON contract.revision_review_decision IS '摘要格式：resolution_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_revision_review_decision__body_digest_length ON contract.revision_review_decision IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.revision_review_binding (
    tenant_id uuid NOT NULL,
    revision_review_binding_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    review_decision_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    scope_hash bytea NOT NULL,
    resolution_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_revision_review_binding PRIMARY KEY (tenant_id, revision_review_binding_id),
    CONSTRAINT ck_revision_review_binding__revision CHECK (revision=0),
    CONSTRAINT uq_revision_review_binding__contract_revision_id UNIQUE (tenant_id, contract_revision_id),
    CONSTRAINT uq_revision_review_binding__review_decision_id UNIQUE (tenant_id, review_decision_id),
    CONSTRAINT ck_revision_review_binding__scope_hash_length CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT ck_revision_review_binding__resolution_digest_length CHECK (octet_length(resolution_digest) = 32)
);

COMMENT ON TABLE contract.revision_review_binding IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_review_binding ON contract.revision_review_binding IS '主键：在租户内唯一标识一条revision_review_binding记录。';
COMMENT ON INDEX contract.pk_revision_review_binding IS '主键：在租户内唯一标识一条revision_review_binding记录。';
COMMENT ON COLUMN contract.revision_review_binding.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_review_binding.revision_review_binding_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_review_binding.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_review_binding.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_review_binding.contract_revision_id IS '被放行版本。';
COMMENT ON COLUMN contract.revision_review_binding.review_decision_id IS '真实通过决定。';
COMMENT ON COLUMN contract.revision_review_binding.conflict_review_id IS '准确审查。';
COMMENT ON COLUMN contract.revision_review_binding.scope_hash IS '准确审查范围。';
COMMENT ON COLUMN contract.revision_review_binding.resolution_digest IS '准确可用结论。';
COMMENT ON COLUMN contract.revision_review_binding.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_revision_review_binding__revision ON contract.revision_review_binding IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_review_binding__contract_revision_id ON contract.revision_review_binding IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_review_binding__contract_revision_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT uq_revision_review_binding__review_decision_id ON contract.revision_review_binding IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_review_binding__review_decision_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_revision_review_binding__scope_hash_length ON contract.revision_review_binding IS '摘要格式：scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_revision_review_binding__resolution_digest_length ON contract.revision_review_binding IS '摘要格式：resolution_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.revision_approval_requirement (
    tenant_id uuid NOT NULL,
    revision_approval_requirement_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    requirement_code varchar(64) NOT NULL,
    approver_appointment_id uuid NOT NULL,
    policy_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    policy_id uuid NOT NULL,
    CONSTRAINT pk_revision_approval_requirement PRIMARY KEY (tenant_id, revision_approval_requirement_id),
    CONSTRAINT ck_revision_approval_requirement__revision CHECK (revision=0),
    CONSTRAINT uq_revision_approval_requirement__code UNIQUE (tenant_id, contract_revision_id, requirement_code),
    CONSTRAINT ck_revision_approval_requirement__policy_digest_length CHECK (octet_length(policy_digest) = 32)
);

COMMENT ON TABLE contract.revision_approval_requirement IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_approval_requirement ON contract.revision_approval_requirement IS '主键：在租户内唯一标识一条revision_approval_requirement记录。';
COMMENT ON INDEX contract.pk_revision_approval_requirement IS '主键：在租户内唯一标识一条revision_approval_requirement记录。';
COMMENT ON COLUMN contract.revision_approval_requirement.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_approval_requirement.revision_approval_requirement_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_approval_requirement.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_approval_requirement.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_approval_requirement.contract_revision_id IS '准确版本。';
COMMENT ON COLUMN contract.revision_approval_requirement.requirement_code IS '明确审批要求。';
COMMENT ON COLUMN contract.revision_approval_requirement.approver_appointment_id IS '明确审批任职。';
COMMENT ON COLUMN contract.revision_approval_requirement.policy_digest IS '本版审批策略摘要。';
COMMENT ON COLUMN contract.revision_approval_requirement.created_at IS '数据库形成时间。';
COMMENT ON COLUMN contract.revision_approval_requirement.policy_id IS '本版明确合同审批策略。';
COMMENT ON CONSTRAINT ck_revision_approval_requirement__revision ON contract.revision_approval_requirement IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_approval_requirement__code ON contract.revision_approval_requirement IS '审批要求唯一。';
COMMENT ON INDEX contract.uq_revision_approval_requirement__code IS '审批要求唯一。';
COMMENT ON CONSTRAINT ck_revision_approval_requirement__policy_digest_length ON contract.revision_approval_requirement IS '摘要格式：policy_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.revision_approval_decision (
    tenant_id uuid NOT NULL,
    revision_approval_decision_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    requirement_id uuid NOT NULL,
    review_binding_id uuid NOT NULL,
    decision_code varchar(64) NOT NULL,
    decided_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    approval_request_id uuid NOT NULL,
    CONSTRAINT pk_revision_approval_decision PRIMARY KEY (tenant_id, revision_approval_decision_id),
    CONSTRAINT ck_revision_approval_decision__revision CHECK (revision=0),
    CONSTRAINT uq_revision_approval_decision__requirement_id UNIQUE (tenant_id, requirement_id),
    CONSTRAINT ck_revision_approval_decision__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_revision_approval_decision__code CHECK (decision_code IN ('APPROVED','RETURNED')),
    CONSTRAINT ck_revision_approval_decision__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.revision_approval_decision IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_revision_approval_decision ON contract.revision_approval_decision IS '主键：在租户内唯一标识一条revision_approval_decision记录。';
COMMENT ON INDEX contract.pk_revision_approval_decision IS '主键：在租户内唯一标识一条revision_approval_decision记录。';
COMMENT ON COLUMN contract.revision_approval_decision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.revision_approval_decision.revision_approval_decision_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.revision_approval_decision.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.revision_approval_decision.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.revision_approval_decision.requirement_id IS '准确审批要求。';
COMMENT ON COLUMN contract.revision_approval_decision.review_binding_id IS '本版通过审查。';
COMMENT ON COLUMN contract.revision_approval_decision.decision_code IS '批准或退回。';
COMMENT ON COLUMN contract.revision_approval_decision.decided_by_appointment_id IS '实际审批任职。';
COMMENT ON COLUMN contract.revision_approval_decision.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.revision_approval_decision.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.revision_approval_decision.created_at IS '数据库形成时间。';
COMMENT ON COLUMN contract.revision_approval_decision.approval_request_id IS '正式提交的准确审批申请。';
COMMENT ON CONSTRAINT ck_revision_approval_decision__revision ON contract.revision_approval_decision IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_revision_approval_decision__requirement_id ON contract.revision_approval_decision IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_revision_approval_decision__requirement_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_revision_approval_decision__body ON contract.revision_approval_decision IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_revision_approval_decision__code ON contract.revision_approval_decision IS '明确审批结果。';
COMMENT ON CONSTRAINT ck_revision_approval_decision__body_digest_length ON contract.revision_approval_decision IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.signature_readiness (
    tenant_id uuid NOT NULL,
    signature_readiness_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    contract_revision_id uuid NOT NULL,
    review_binding_id uuid NOT NULL,
    state_code varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_readiness PRIMARY KEY (tenant_id, signature_readiness_id),
    CONSTRAINT ck_signature_readiness__revision CHECK (revision=0),
    CONSTRAINT uq_signature_readiness__contract_revision_id UNIQUE (tenant_id, contract_revision_id),
    CONSTRAINT ck_signature_readiness__state CHECK (state_code='READY_FOR_SIGNATURE')
);

COMMENT ON TABLE contract.signature_readiness IS 'Fact Owner：ContractRuntime；T08准确不可变事实；不是签署或执行事实。';
COMMENT ON CONSTRAINT pk_signature_readiness ON contract.signature_readiness IS '主键：在租户内唯一标识一条signature_readiness记录。';
COMMENT ON INDEX contract.pk_signature_readiness IS '主键：在租户内唯一标识一条signature_readiness记录。';
COMMENT ON COLUMN contract.signature_readiness.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_readiness.signature_readiness_id IS 'T08准确不可变事实；不是签署或执行事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_readiness.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.signature_readiness.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.signature_readiness.contract_revision_id IS '已完成审查与审批的准确当前版本。';
COMMENT ON COLUMN contract.signature_readiness.review_binding_id IS '本版通过审查依据。';
COMMENT ON COLUMN contract.signature_readiness.state_code IS '明确下一阶段交接边界；不创建签署待办。';
COMMENT ON COLUMN contract.signature_readiness.created_at IS '数据库形成时间。';
COMMENT ON CONSTRAINT ck_signature_readiness__revision ON contract.signature_readiness IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_signature_readiness__contract_revision_id ON contract.signature_readiness IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_signature_readiness__contract_revision_id IS '准确事实唯一。';
COMMENT ON CONSTRAINT ck_signature_readiness__state ON contract.signature_readiness IS '等待下一阶段，不是已签署。';
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_predecessor;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_predecessor CHECK (handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','DELIVER_QUOTE','RECORD_QUOTE_REPLY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW') AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff'));
COMMENT ON CONSTRAINT ck_task_occurrence__handoff_predecessor ON responsibility.task_occurrence IS 'T01具名事实一致性。';
ALTER TABLE contract.contract ALTER COLUMN accepted_quote_response_id DROP NOT NULL;
ALTER TABLE contract.contract ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN contract.contract.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
ALTER TABLE contract.contract ADD COLUMN created_by_appointment_id uuid;
COMMENT ON COLUMN contract.contract.created_by_appointment_id IS 'R2合同创建者；旧合同可空。';
ALTER TABLE contract.contract ADD COLUMN direct_preparation_decision_id uuid;
COMMENT ON COLUMN contract.contract.direct_preparation_decision_id IS '最初消费的直接准备授权；后续版本独立记录来源。';
ALTER TABLE contract.contract ADD COLUMN preparation_contract_code varchar(64);
COMMENT ON COLUMN contract.contract.preparation_contract_code IS '具名R2双入口锚点；旧合同为空。';
ALTER TABLE contract.contract ADD CONSTRAINT uq_contract__direct_source UNIQUE (tenant_id, direct_preparation_decision_id);
COMMENT ON CONSTRAINT uq_contract__direct_source ON contract.contract IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_contract__direct_source IS '准确事实唯一。';
ALTER TABLE contract.contract ADD CONSTRAINT ck_contract__r2_source CHECK ((preparation_contract_code IS NULL AND accepted_quote_response_id IS NOT NULL AND direct_preparation_decision_id IS NULL) OR (preparation_contract_code IS NOT NULL AND preparation_contract_code='R2_CONTRACT_PREPARATION_V1' AND created_by_appointment_id IS NOT NULL AND num_nonnulls(accepted_quote_response_id,direct_preparation_decision_id)=1));
COMMENT ON CONSTRAINT ck_contract__r2_source ON contract.contract IS '具名双入口；旧结构不放宽。';
ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__direct_preparation_decision_id
    FOREIGN KEY (tenant_id, direct_preparation_decision_id)
    REFERENCES contract.preparation_decision (tenant_id, preparation_decision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract__direct_preparation_decision_id ON contract.contract IS '同租户准确事实引用。';
ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract__created_by_appointment_id ON contract.contract IS '同租户准确事实引用。';
ALTER TABLE contract.contract_revision ALTER COLUMN confirmed_action_draft_id DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN source_quote_revision_id DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN source_quote_response_id DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN body_evidence_submission_id DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN pre_contract_review_id DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN pre_contract_scope_hash DROP NOT NULL;
ALTER TABLE contract.contract_revision ALTER COLUMN pre_contract_resolution_digest DROP NOT NULL;
ALTER TABLE contract.contract_revision ADD COLUMN preparation_draft_id uuid;
COMMENT ON COLUMN contract.contract_revision.preparation_draft_id IS '真实准备草稿。';
ALTER TABLE contract.contract_revision ADD COLUMN source_direct_decision_id uuid;
COMMENT ON COLUMN contract.contract_revision.source_direct_decision_id IS '本版准确直接授权。';
ALTER TABLE contract.contract_revision ADD COLUMN customer_confirmation_id uuid;
COMMENT ON COLUMN contract.contract_revision.customer_confirmation_id IS '准确客户确认。';
ALTER TABLE contract.contract_revision ADD COLUMN commercial_digest bytea;
COMMENT ON COLUMN contract.contract_revision.commercial_digest IS '准确商业摘要。';
ALTER TABLE contract.contract_revision ADD COLUMN body_evidence_version_id uuid;
COMMENT ON COLUMN contract.contract_revision.body_evidence_version_id IS '准确T06正文版本。';
ALTER TABLE contract.contract_revision ADD COLUMN template_version_id uuid;
COMMENT ON COLUMN contract.contract_revision.template_version_id IS '已审核真实模板版本。';
ALTER TABLE contract.contract_revision ADD COLUMN party_snapshot_digest bytea;
COMMENT ON COLUMN contract.contract_revision.party_snapshot_digest IS '准确参与方快照。';
ALTER TABLE contract.contract_revision ADD COLUMN package_ciphertext bytea;
COMMENT ON COLUMN contract.contract_revision.package_ciphertext IS '本版完整规范包受保护密文。';
ALTER TABLE contract.contract_revision ADD COLUMN receipt_required_before_transfer boolean;
COMMENT ON COLUMN contract.contract_revision.receipt_required_before_transfer IS '仅明确付款约定阻断转案。';
ALTER TABLE contract.contract_revision ADD COLUMN required_amount_minor bigint;
COMMENT ON COLUMN contract.contract_revision.required_amount_minor IS '明确先到账金额。';
ALTER TABLE contract.contract_revision ADD COLUMN approval_requirement_count integer;
COMMENT ON COLUMN contract.contract_revision.approval_requirement_count IS '在版本形成事务冻结的必要审批数量。';
ALTER TABLE contract.contract_revision ADD COLUMN clause_count integer;
COMMENT ON COLUMN contract.contract_revision.clause_count IS '本版条款集合数量。';
ALTER TABLE contract.contract_revision ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN contract.contract_revision.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
ALTER TABLE contract.contract_revision ADD CONSTRAINT ck_contract_revision__commercial_digest_length CHECK (octet_length(commercial_digest) = 32);
COMMENT ON CONSTRAINT ck_contract_revision__commercial_digest_length ON contract.contract_revision IS '摘要格式：commercial_digest必须保存32字节的规范二进制值。';
ALTER TABLE contract.contract_revision ADD CONSTRAINT ck_contract_revision__party_snapshot_digest_length CHECK (octet_length(party_snapshot_digest) = 32);
COMMENT ON CONSTRAINT ck_contract_revision__party_snapshot_digest_length ON contract.contract_revision IS '摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。';
ALTER TABLE contract.contract_revision ADD CONSTRAINT ck_contract_revision__r2_shape CHECK ((package_contract_code='R2_CONTRACT_PREPARATION_V1' AND package_contract_version=1 AND preparation_draft_id IS NOT NULL AND customer_confirmation_id IS NOT NULL AND commercial_digest IS NOT NULL AND body_evidence_version_id IS NOT NULL AND template_version_id IS NOT NULL AND party_snapshot_digest IS NOT NULL AND package_ciphertext IS NOT NULL AND receipt_required_before_transfer IS NOT NULL AND approval_requirement_count IS NOT NULL AND clause_count IS NOT NULL AND pre_contract_review_id IS NULL AND pre_contract_scope_hash IS NULL AND pre_contract_resolution_digest IS NULL AND confirmed_action_draft_id IS NULL AND num_nonnulls(source_quote_response_id,source_direct_decision_id)=1 AND ((source_quote_response_id IS NOT NULL AND source_quote_revision_id IS NOT NULL) OR (source_direct_decision_id IS NOT NULL AND source_quote_revision_id IS NULL)) AND octet_length(package_ciphertext) BETWEEN 29 AND 1048576 AND approval_requirement_count BETWEEN 1 AND 100 AND clause_count BETWEEN 0 AND 200 AND ((receipt_required_before_transfer AND required_amount_minor IS NOT NULL AND required_amount_minor BETWEEN 1 AND 9007199254740991) OR (NOT receipt_required_before_transfer AND required_amount_minor IS NULL))) OR (package_contract_code<>'R2_CONTRACT_PREPARATION_V1' AND confirmed_action_draft_id IS NOT NULL AND source_quote_revision_id IS NOT NULL AND source_quote_response_id IS NOT NULL AND body_evidence_submission_id IS NOT NULL AND pre_contract_review_id IS NOT NULL AND pre_contract_scope_hash IS NOT NULL AND pre_contract_resolution_digest IS NOT NULL AND source_direct_decision_id IS NULL AND preparation_draft_id IS NULL AND customer_confirmation_id IS NULL AND commercial_digest IS NULL AND body_evidence_version_id IS NULL AND template_version_id IS NULL AND party_snapshot_digest IS NULL AND package_ciphertext IS NULL AND receipt_required_before_transfer IS NULL AND approval_requirement_count IS NULL AND clause_count IS NULL AND required_amount_minor IS NULL));
COMMENT ON CONSTRAINT ck_contract_revision__r2_shape ON contract.contract_revision IS '具名准备版本与完整旧版本分离；不伪造审查。';
ALTER TABLE contract.contract_revision ADD CONSTRAINT uq_contract_revision__preparation_draft_id UNIQUE (tenant_id, preparation_draft_id);
COMMENT ON CONSTRAINT uq_contract_revision__preparation_draft_id ON contract.contract_revision IS '准确事实唯一。';
COMMENT ON INDEX contract.uq_contract_revision__preparation_draft_id IS '准确事实唯一。';
ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__preparation_draft_id
    FOREIGN KEY (tenant_id, preparation_draft_id)
    REFERENCES contract.preparation_draft (tenant_id, preparation_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__preparation_draft_id ON contract.contract_revision IS '同租户准确事实引用。';
ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__source_direct_decision_id
    FOREIGN KEY (tenant_id, source_direct_decision_id)
    REFERENCES contract.preparation_decision (tenant_id, preparation_decision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__source_direct_decision_id ON contract.contract_revision IS '同租户准确事实引用。';
ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__customer_confirmation_id ON contract.contract_revision IS '同租户准确事实引用。';
ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__body_evidence_version_id
    FOREIGN KEY (tenant_id, body_evidence_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__body_evidence_version_id ON contract.contract_revision IS '同租户准确事实引用。';
ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__template_version_id
    FOREIGN KEY (tenant_id, template_version_id)
    REFERENCES contract.template_version (tenant_id, template_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__template_version_id ON contract.contract_revision IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_decision ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN contract.preparation_decision.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
ALTER TABLE platform_meta.r2_opportunity_checkpoint ALTER COLUMN scan_kind TYPE varchar(32);
ALTER TABLE platform_meta.r2_opportunity_checkpoint DROP CONSTRAINT ck_r2_opportunity_checkpoint__kind;
ALTER TABLE platform_meta.r2_opportunity_checkpoint ADD CONSTRAINT ck_r2_opportunity_checkpoint__kind CHECK (scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION','CONTRACT_PREPARATION'));
COMMENT ON CONSTRAINT ck_r2_opportunity_checkpoint__kind ON platform_meta.r2_opportunity_checkpoint IS '种类仅允许两种已注册商机维护扫描。';
ALTER TABLE contract.approval_policy
    ADD CONSTRAINT fk_approval_policy__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_approval_policy__tenant ON contract.approval_policy IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.approval_policy
    ADD CONSTRAINT fk_approval_policy__organization_unit_id
    FOREIGN KEY (tenant_id, organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_approval_policy__organization_unit_id ON contract.approval_policy IS '同租户准确事实引用。';
CREATE TRIGGER trg_approval_policy__mutation_guard BEFORE UPDATE OR DELETE ON contract.approval_policy FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_approval_policy__mutation_guard ON contract.approval_policy IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.approval_policy FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.approval_policy TO ${app_command_role};
GRANT SELECT ON contract.approval_policy TO ${app_query_role};
ALTER TABLE contract.approval_policy_member
    ADD CONSTRAINT fk_approval_policy_member__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_approval_policy_member__tenant ON contract.approval_policy_member IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.approval_policy_member
    ADD CONSTRAINT fk_approval_policy_member__policy_id
    FOREIGN KEY (tenant_id, policy_id)
    REFERENCES contract.approval_policy (tenant_id, approval_policy_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_approval_policy_member__policy_id ON contract.approval_policy_member IS '同租户准确事实引用。';
ALTER TABLE contract.approval_policy_member
    ADD CONSTRAINT fk_approval_policy_member__appointment_id
    FOREIGN KEY (tenant_id, appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_approval_policy_member__appointment_id ON contract.approval_policy_member IS '同租户准确事实引用。';
CREATE TRIGGER trg_approval_policy_member__mutation_guard BEFORE UPDATE OR DELETE ON contract.approval_policy_member FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_approval_policy_member__mutation_guard ON contract.approval_policy_member IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.approval_policy_member FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.approval_policy_member TO ${app_command_role};
GRANT SELECT ON contract.approval_policy_member TO ${app_query_role};
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__tenant ON contract.preparation_workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__opportunity_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__contract_id
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__contract_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES contract.preparation_workflow (tenant_id, preparation_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__previous_workflow_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__owner_appointment_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__task_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__prior_task_id ON contract.preparation_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_workflow
    ADD CONSTRAINT fk_preparation_workflow__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_workflow__created_by_appointment_id ON contract.preparation_workflow IS '同租户准确事实引用。';
CREATE TRIGGER trg_preparation_workflow__mutation_guard BEFORE UPDATE OR DELETE ON contract.preparation_workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_preparation_workflow__mutation_guard ON contract.preparation_workflow IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.preparation_workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.preparation_workflow TO ${app_command_role};
GRANT SELECT ON contract.preparation_workflow TO ${app_query_role};
ALTER TABLE contract.revision_approval_request
    ADD CONSTRAINT fk_revision_approval_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_request__tenant ON contract.revision_approval_request IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_approval_request
    ADD CONSTRAINT fk_revision_approval_request__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_request__contract_revision_id ON contract.revision_approval_request IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_request
    ADD CONSTRAINT fk_revision_approval_request__review_binding_id
    FOREIGN KEY (tenant_id, review_binding_id)
    REFERENCES contract.revision_review_binding (tenant_id, revision_review_binding_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_request__review_binding_id ON contract.revision_approval_request IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_request
    ADD CONSTRAINT fk_revision_approval_request__requested_by_appointment_id
    FOREIGN KEY (tenant_id, requested_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_request__requested_by_appointment_id ON contract.revision_approval_request IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_approval_request__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_approval_request FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_approval_request__mutation_guard ON contract.revision_approval_request IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_approval_request FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_approval_request TO ${app_command_role};
GRANT SELECT ON contract.revision_approval_request TO ${app_query_role};
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__tenant ON contract.preparation_draft IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__opportunity_id ON contract.preparation_draft IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__owner_appointment_id ON contract.preparation_draft IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__customer_confirmation_id ON contract.preparation_draft IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__previous_draft_id
    FOREIGN KEY (tenant_id, previous_draft_id)
    REFERENCES contract.preparation_draft (tenant_id, preparation_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__previous_draft_id ON contract.preparation_draft IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__source_quote_response_id
    FOREIGN KEY (tenant_id, source_quote_response_id)
    REFERENCES opportunity.quote_response (tenant_id, quote_response_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__source_quote_response_id ON contract.preparation_draft IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_draft
    ADD CONSTRAINT fk_preparation_draft__source_direct_decision_id
    FOREIGN KEY (tenant_id, source_direct_decision_id)
    REFERENCES contract.preparation_decision (tenant_id, preparation_decision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_draft__source_direct_decision_id ON contract.preparation_draft IS '同租户准确事实引用。';
CREATE TRIGGER trg_preparation_draft__mutation_guard BEFORE UPDATE OR DELETE ON contract.preparation_draft FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_preparation_draft__mutation_guard ON contract.preparation_draft IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.preparation_draft FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.preparation_draft TO ${app_command_role};
GRANT SELECT ON contract.preparation_draft TO ${app_query_role};
ALTER TABLE contract.template_version
    ADD CONSTRAINT fk_template_version__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_version__tenant ON contract.template_version IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.template_version
    ADD CONSTRAINT fk_template_version__evidence_version_id
    FOREIGN KEY (tenant_id, evidence_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_version__evidence_version_id ON contract.template_version IS '同租户准确事实引用。';
ALTER TABLE contract.template_version
    ADD CONSTRAINT fk_template_version__approved_by_appointment_id
    FOREIGN KEY (tenant_id, approved_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_template_version__approved_by_appointment_id ON contract.template_version IS '同租户准确事实引用。';
CREATE TRIGGER trg_template_version__mutation_guard BEFORE UPDATE OR DELETE ON contract.template_version FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_template_version__mutation_guard ON contract.template_version IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.template_version FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.template_version TO ${app_command_role};
GRANT SELECT ON contract.template_version TO ${app_query_role};
ALTER TABLE contract.clause_version
    ADD CONSTRAINT fk_clause_version__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_clause_version__tenant ON contract.clause_version IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.clause_version
    ADD CONSTRAINT fk_clause_version__evidence_version_id
    FOREIGN KEY (tenant_id, evidence_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_clause_version__evidence_version_id ON contract.clause_version IS '同租户准确事实引用。';
ALTER TABLE contract.clause_version
    ADD CONSTRAINT fk_clause_version__approved_by_appointment_id
    FOREIGN KEY (tenant_id, approved_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_clause_version__approved_by_appointment_id ON contract.clause_version IS '同租户准确事实引用。';
CREATE TRIGGER trg_clause_version__mutation_guard BEFORE UPDATE OR DELETE ON contract.clause_version FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_clause_version__mutation_guard ON contract.clause_version IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.clause_version FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.clause_version TO ${app_command_role};
GRANT SELECT ON contract.clause_version TO ${app_query_role};
ALTER TABLE contract.revision_clause
    ADD CONSTRAINT fk_revision_clause__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_clause__tenant ON contract.revision_clause IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_clause
    ADD CONSTRAINT fk_revision_clause__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_clause__contract_revision_id ON contract.revision_clause IS '同租户准确事实引用。';
ALTER TABLE contract.revision_clause
    ADD CONSTRAINT fk_revision_clause__clause_version_id
    FOREIGN KEY (tenant_id, clause_version_id)
    REFERENCES contract.clause_version (tenant_id, clause_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_clause__clause_version_id ON contract.revision_clause IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_clause__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_clause FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_clause__mutation_guard ON contract.revision_clause IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_clause FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_clause TO ${app_command_role};
GRANT SELECT ON contract.revision_clause TO ${app_query_role};
ALTER TABLE contract.revision_review_request
    ADD CONSTRAINT fk_revision_review_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_request__tenant ON contract.revision_review_request IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_review_request
    ADD CONSTRAINT fk_revision_review_request__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_request__contract_revision_id ON contract.revision_review_request IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_request
    ADD CONSTRAINT fk_revision_review_request__requested_by_appointment_id
    FOREIGN KEY (tenant_id, requested_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_request__requested_by_appointment_id ON contract.revision_review_request IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_request
    ADD CONSTRAINT fk_revision_review_request__previous_request_id
    FOREIGN KEY (tenant_id, previous_request_id)
    REFERENCES contract.revision_review_request (tenant_id, revision_review_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_request__previous_request_id ON contract.revision_review_request IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_review_request__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_review_request FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_review_request__mutation_guard ON contract.revision_review_request IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_review_request FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_review_request TO ${app_command_role};
GRANT SELECT ON contract.revision_review_request TO ${app_query_role};
ALTER TABLE contract.revision_review_decision
    ADD CONSTRAINT fk_revision_review_decision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_decision__tenant ON contract.revision_review_decision IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_review_decision
    ADD CONSTRAINT fk_revision_review_decision__request_id
    FOREIGN KEY (tenant_id, request_id)
    REFERENCES contract.revision_review_request (tenant_id, revision_review_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_decision__request_id ON contract.revision_review_decision IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_decision
    ADD CONSTRAINT fk_revision_review_decision__conflict_review_id
    FOREIGN KEY (tenant_id, conflict_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_decision__conflict_review_id ON contract.revision_review_decision IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_decision
    ADD CONSTRAINT fk_revision_review_decision__decided_by_appointment_id
    FOREIGN KEY (tenant_id, decided_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_decision__decided_by_appointment_id ON contract.revision_review_decision IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_review_decision__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_review_decision FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_review_decision__mutation_guard ON contract.revision_review_decision IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_review_decision FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_review_decision TO ${app_command_role};
GRANT SELECT ON contract.revision_review_decision TO ${app_query_role};
ALTER TABLE contract.revision_review_binding
    ADD CONSTRAINT fk_revision_review_binding__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_binding__tenant ON contract.revision_review_binding IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_review_binding
    ADD CONSTRAINT fk_revision_review_binding__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_binding__contract_revision_id ON contract.revision_review_binding IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_binding
    ADD CONSTRAINT fk_revision_review_binding__review_decision_id
    FOREIGN KEY (tenant_id, review_decision_id)
    REFERENCES contract.revision_review_decision (tenant_id, revision_review_decision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_binding__review_decision_id ON contract.revision_review_binding IS '同租户准确事实引用。';
ALTER TABLE contract.revision_review_binding
    ADD CONSTRAINT fk_revision_review_binding__conflict_review_id
    FOREIGN KEY (tenant_id, conflict_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_review_binding__conflict_review_id ON contract.revision_review_binding IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_review_binding__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_review_binding FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_review_binding__mutation_guard ON contract.revision_review_binding IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_review_binding FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_review_binding TO ${app_command_role};
GRANT SELECT ON contract.revision_review_binding TO ${app_query_role};
ALTER TABLE contract.revision_approval_requirement
    ADD CONSTRAINT fk_revision_approval_requirement__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_requirement__tenant ON contract.revision_approval_requirement IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_approval_requirement
    ADD CONSTRAINT fk_revision_approval_requirement__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_requirement__contract_revision_id ON contract.revision_approval_requirement IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_requirement
    ADD CONSTRAINT fk_revision_approval_requirement__approver_appointment_id
    FOREIGN KEY (tenant_id, approver_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_requirement__approver_appointment_id ON contract.revision_approval_requirement IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_requirement
    ADD CONSTRAINT fk_revision_approval_requirement__policy_id
    FOREIGN KEY (tenant_id, policy_id)
    REFERENCES contract.approval_policy (tenant_id, approval_policy_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_requirement__policy_id ON contract.revision_approval_requirement IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_approval_requirement__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_approval_requirement FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_approval_requirement__mutation_guard ON contract.revision_approval_requirement IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_approval_requirement FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_approval_requirement TO ${app_command_role};
GRANT SELECT ON contract.revision_approval_requirement TO ${app_query_role};
ALTER TABLE contract.revision_approval_decision
    ADD CONSTRAINT fk_revision_approval_decision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_decision__tenant ON contract.revision_approval_decision IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.revision_approval_decision
    ADD CONSTRAINT fk_revision_approval_decision__requirement_id
    FOREIGN KEY (tenant_id, requirement_id)
    REFERENCES contract.revision_approval_requirement (tenant_id, revision_approval_requirement_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_decision__requirement_id ON contract.revision_approval_decision IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_decision
    ADD CONSTRAINT fk_revision_approval_decision__review_binding_id
    FOREIGN KEY (tenant_id, review_binding_id)
    REFERENCES contract.revision_review_binding (tenant_id, revision_review_binding_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_decision__review_binding_id ON contract.revision_approval_decision IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_decision
    ADD CONSTRAINT fk_revision_approval_decision__decided_by_appointment_id
    FOREIGN KEY (tenant_id, decided_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_decision__decided_by_appointment_id ON contract.revision_approval_decision IS '同租户准确事实引用。';
ALTER TABLE contract.revision_approval_decision
    ADD CONSTRAINT fk_revision_approval_decision__approval_request_id
    FOREIGN KEY (tenant_id, approval_request_id)
    REFERENCES contract.revision_approval_request (tenant_id, revision_approval_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_revision_approval_decision__approval_request_id ON contract.revision_approval_decision IS '同租户准确事实引用。';
CREATE TRIGGER trg_revision_approval_decision__mutation_guard BEFORE UPDATE OR DELETE ON contract.revision_approval_decision FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_revision_approval_decision__mutation_guard ON contract.revision_approval_decision IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.revision_approval_decision FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.revision_approval_decision TO ${app_command_role};
GRANT SELECT ON contract.revision_approval_decision TO ${app_query_role};
ALTER TABLE contract.signature_readiness
    ADD CONSTRAINT fk_signature_readiness__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_readiness__tenant ON contract.signature_readiness IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.signature_readiness
    ADD CONSTRAINT fk_signature_readiness__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_readiness__contract_revision_id ON contract.signature_readiness IS '同租户准确事实引用。';
ALTER TABLE contract.signature_readiness
    ADD CONSTRAINT fk_signature_readiness__review_binding_id
    FOREIGN KEY (tenant_id, review_binding_id)
    REFERENCES contract.revision_review_binding (tenant_id, revision_review_binding_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_readiness__review_binding_id ON contract.signature_readiness IS '同租户准确事实引用。';
CREATE TRIGGER trg_signature_readiness__mutation_guard BEFORE UPDATE OR DELETE ON contract.signature_readiness FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_readiness__mutation_guard ON contract.signature_readiness IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.signature_readiness FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.signature_readiness TO ${app_command_role};
GRANT SELECT ON contract.signature_readiness TO ${app_query_role};
CREATE UNIQUE INDEX uq_preparation_draft__root ON contract.preparation_draft (tenant_id,opportunity_id) WHERE previous_draft_id IS NULL;
COMMENT ON INDEX contract.uq_preparation_draft__root IS '商机准备草稿单根。';
CREATE UNIQUE INDEX uq_revision_review_request__root ON contract.revision_review_request (tenant_id,contract_revision_id) WHERE previous_request_id IS NULL;
COMMENT ON INDEX contract.uq_revision_review_request__root IS '合同版本审查单根。';
CREATE UNIQUE INDEX uq_preparation_workflow__root ON contract.preparation_workflow (tenant_id,opportunity_id) WHERE previous_workflow_id IS NULL;
COMMENT ON INDEX contract.uq_preparation_workflow__root IS '商机合同流程单根。';
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
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND subject_type='opportunity.opportunity' AND subject_id=NEW.opportunity_id AND business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT') AND state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'closure retains active task' USING ERRCODE='23514'; END IF;
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
 IF NEW.revision<>0 OR (NEW.state='OPEN' OR (NEW.state='WAITING' AND NEW.business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY','REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW') AND NEW.responsibility_basis_type='opportunity.responsibility_handoff' AND NEW.responsibility_basis_revision=0 AND NEW.handoff_predecessor_task_occurrence_id IS NOT NULL)) IS NOT TRUE THEN RAISE EXCEPTION 'invalid task initial state' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;

CREATE FUNCTION contract.fn_reject_r2_preparation_execution() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF EXISTS(SELECT 1 FROM contract.contract_revision r WHERE r.tenant_id=NEW.tenant_id AND r.contract_revision_id=NEW.contract_revision_id AND r.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN RAISE EXCEPTION 'R2 preparation requires a separately activated signing protocol' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_reject_r2_preparation_execution() IS '准备版本不能利用尚未形成旧签署计划的空集合直接执行；后续协议须具名激活。';
REVOKE ALL ON FUNCTION contract.fn_reject_r2_preparation_execution() FROM PUBLIC;
CREATE TRIGGER trg_contract_execution__r2_protocol BEFORE INSERT ON contract.contract_execution FOR EACH ROW EXECUTE FUNCTION contract.fn_reject_r2_preparation_execution();
COMMENT ON TRIGGER trg_contract_execution__r2_protocol ON contract.contract_execution IS 'T08只达到签署准备边界，不激活执行。';
CREATE FUNCTION contract.fn_assert_r2_material(t uuid,v uuid,sha bytea,o uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.material_version m JOIN evidence.received_source_object src ON src.tenant_id=m.tenant_id AND src.received_source_object_id=m.received_source_object_id JOIN evidence.evidence_binding b ON b.tenant_id=m.tenant_id AND b.evidence_binding_id=m.evidence_binding_id
 WHERE m.tenant_id=t AND m.material_version_id=v AND (o IS NULL OR m.opportunity_id=o) AND src.server_sha256=sha AND src.scan_result='PASSED' AND b.revoked_at IS NULL FOR SHARE OF b;
 IF NOT FOUND THEN RAISE EXCEPTION 'contract exact document unavailable' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_material(uuid,uuid,bytea,uuid) IS '真实准确文件、扫描和未撤回证据；原件字节不能由示例替代。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_material(uuid,uuid,bytea,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_source(t uuid,o uuid,q uuid,d uuid,c uuid,h bytea) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE source_customer uuid; req contract.preparation_request%ROWTYPE; dec contract.preparation_decision%ROWTYPE; opp opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO opp FROM opportunity.opportunity WHERE tenant_id=t AND opportunity_id=o FOR UPDATE;
 IF opp.opportunity_id IS NULL OR opp.closed_at IS NOT NULL OR num_nonnulls(q,d)<>1 THEN RAISE EXCEPTION 'contract source unavailable' USING ERRCODE='23514'; END IF;
 IF d IS NOT NULL THEN
  SELECT * INTO dec FROM contract.preparation_decision WHERE tenant_id=t AND preparation_decision_id=d;
  SELECT * INTO req FROM contract.preparation_request WHERE tenant_id=t AND preparation_request_id=dec.preparation_request_id;
  IF req.preparation_request_id IS NULL OR req.opportunity_id<>o OR dec.decision_code<>'APPROVED' OR req.opportunity_revision<>opp.revision OR dec.effective_from>clock_timestamp() OR (dec.effective_until IS NOT NULL AND dec.effective_until<=clock_timestamp()) OR (h IS NOT NULL AND req.commercial_digest<>h) OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=t AND n.previous_request_id=req.preparation_request_id) THEN RAISE EXCEPTION 'contract direct authorization changed' USING ERRCODE='23514'; END IF;
  IF req.responsibility_type='opportunity.opportunity' THEN
   IF req.responsibility_id<>o OR req.responsibility_revision<>opp.revision OR req.owner_appointment_id<>opp.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff x WHERE x.tenant_id=t AND x.opportunity_id=o) THEN RAISE EXCEPTION 'contract direct responsibility changed' USING ERRCODE='23514'; END IF;
  ELSIF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff x WHERE x.tenant_id=t AND x.responsibility_handoff_id=req.responsibility_id AND x.opportunity_id=o AND x.to_appointment_id=req.owner_appointment_id AND x.revision=req.responsibility_revision AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=t AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=x.responsibility_handoff_id)) THEN RAISE EXCEPTION 'contract direct responsibility changed' USING ERRCODE='23514'; END IF;
  source_customer=req.customer_confirmation_id;
 ELSE
  SELECT pb.customer_confirmation_id INTO source_customer
  FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id
  JOIN opportunity.quote_revision v ON v.tenant_id=i.tenant_id AND v.quote_revision_id=i.quote_revision_id
  JOIN opportunity.quote_package_basis pb ON pb.tenant_id=v.tenant_id AND pb.quote_revision_id=v.quote_revision_id
  JOIN opportunity.contract_preparation_source s ON s.tenant_id=r.tenant_id AND s.quote_response_id=r.quote_response_id
  JOIN opportunity.quote_manual_delivery md ON md.tenant_id=i.tenant_id AND md.quote_manual_delivery_id=i.delivery_fact_id AND i.delivery_fact_type='opportunity.quote_manual_delivery'
  JOIN opportunity.material_version dm ON dm.tenant_id=md.tenant_id AND dm.material_version_id=md.material_version_id
  JOIN evidence.evidence_binding db ON db.tenant_id=dm.tenant_id AND db.evidence_binding_id=dm.evidence_binding_id
  JOIN opportunity.quote_response_basis rb ON rb.tenant_id=r.tenant_id AND rb.quote_response_id=r.quote_response_id
  JOIN opportunity.material_version rm ON rm.tenant_id=rb.tenant_id AND rm.material_version_id=rb.material_version_id
  JOIN evidence.evidence_binding eb ON eb.tenant_id=rm.tenant_id AND eb.evidence_binding_id=rm.evidence_binding_id
  WHERE r.tenant_id=t AND r.quote_response_id=q AND r.response_code='ACCEPTED' AND i.issue_status_code='ACTIVE' AND v.opportunity_id=o AND s.opportunity_id=o AND s.source_kind='ACCEPTED_QUOTE' AND v.package_contract_code='R2_QUOTE_PACKAGE_V1' AND v.package_contract_version=1 AND md.quote_revision_id=v.quote_revision_id AND md.occurred_at=i.issued_at AND i.delivery_fact_revision=0 AND dm.opportunity_id=o AND rm.opportunity_id=o AND rm.evidence_submission_id=r.evidence_submission_id
    AND r.received_at>=md.occurred_at AND r.created_at>=r.received_at AND r.created_at<=clock_timestamp() AND (v.valid_until IS NULL OR r.received_at<v.valid_until) AND db.revoked_at IS NULL AND eb.revoked_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_revision n WHERE n.tenant_id=t AND n.predecessor_quote_revision_id=v.quote_revision_id)
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_issue n WHERE n.tenant_id=t AND n.replaces_quote_issue_id=i.quote_issue_id)
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response n WHERE n.tenant_id=t AND n.quote_issue_id=i.quote_issue_id AND n.response_no>r.response_no)
  FOR UPDATE OF i,db,eb;
  IF source_customer IS NULL THEN RAISE EXCEPTION 'contract accepted source changed' USING ERRCODE='23514'; END IF;
 END IF;
 IF c IS NOT NULL AND c<>source_customer THEN RAISE EXCEPTION 'contract customer differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation x WHERE x.tenant_id=t AND x.customer_requirement_confirmation_id=source_customer AND x.opportunity_id=o AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=t AND n.previous_confirmation_id=x.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'contract customer changed' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM party.party p JOIN opportunity.customer_requirement_participant x ON x.tenant_id=p.tenant_id AND x.party_id=p.party_id WHERE x.tenant_id=t AND x.confirmation_id=source_customer ORDER BY p.party_id FOR UPDATE OF p;
 IF EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant x JOIN party.party p ON p.tenant_id=x.tenant_id AND p.party_id=x.party_id WHERE x.tenant_id=t AND x.confirmation_id=source_customer AND (p.revision<>x.party_revision OR p.status<>'ACTIVE')) THEN RAISE EXCEPTION 'contract party changed' USING ERRCODE='23514'; END IF;
 IF d IS NOT NULL AND dec.effective_until IS NOT NULL AND dec.effective_until<=clock_timestamp() THEN RAISE EXCEPTION 'contract authorization expired after lock' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea) IS '同商机双入口准确来源；合法历史接受不因当前自然到期失效。商业明文摘要仍由受信任Owner解密复验。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_version(t uuid,v uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE; root contract.contract%ROWTYPE; d contract.preparation_draft%ROWTYPE; tpl contract.template_version%ROWTYPE; cl record;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=v;
 SELECT * INTO root FROM contract.contract WHERE tenant_id=t AND contract_id=r.contract_id FOR UPDATE;
 IF r.contract_revision_id IS NULL OR root.current_revision_id IS DISTINCT FROM v OR root.preparation_contract_code IS DISTINCT FROM 'R2_CONTRACT_PREPARATION_V1' OR r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract preparation version must become current atomically' USING ERRCODE='23514'; END IF;
 SELECT * INTO d FROM contract.preparation_draft WHERE tenant_id=t AND preparation_draft_id=r.preparation_draft_id;
 IF d.preparation_draft_id IS NULL OR d.opportunity_id<>root.opportunity_id OR d.customer_confirmation_id<>r.customer_confirmation_id OR d.commercial_digest<>r.commercial_digest OR d.source_quote_response_id IS DISTINCT FROM r.source_quote_response_id OR d.source_direct_decision_id IS DISTINCT FROM r.source_direct_decision_id OR EXISTS(SELECT 1 FROM contract.preparation_draft n WHERE n.tenant_id=t AND n.previous_draft_id=d.preparation_draft_id) THEN RAISE EXCEPTION 'contract preparation draft differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.opportunity o WHERE o.tenant_id=t AND o.opportunity_id=root.opportunity_id AND o.revision=d.opportunity_revision AND ((d.responsibility_type='opportunity.opportunity' AND d.responsibility_id=o.opportunity_id AND d.responsibility_revision=o.revision AND d.owner_appointment_id=o.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=t AND h.opportunity_id=o.opportunity_id)) OR (d.responsibility_type='opportunity.responsibility_handoff' AND EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=t AND h.responsibility_handoff_id=d.responsibility_id AND h.opportunity_id=o.opportunity_id AND h.revision=d.responsibility_revision AND h.to_appointment_id=d.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=t AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id))))) THEN RAISE EXCEPTION 'contract draft responsibility changed' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_r2_source(t,root.opportunity_id,r.source_quote_response_id,r.source_direct_decision_id,r.customer_confirmation_id,r.commercial_digest);
 IF (SELECT count(*) FROM contract.contract_participation p WHERE p.tenant_id=t AND p.contract_revision_id=v)<>(SELECT count(*) FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=t AND p.confirmation_id=r.customer_confirmation_id) OR NOT EXISTS(SELECT 1 FROM contract.contract_participation p WHERE p.tenant_id=t AND p.contract_revision_id=v AND p.context_role_code='CLIENT') THEN RAISE EXCEPTION 'contract preparation participants differ' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM contract.contract c WHERE c.tenant_id=t AND c.contract_id<>root.contract_id AND ((r.source_quote_response_id IS NOT NULL AND c.accepted_quote_response_id=r.source_quote_response_id) OR (r.source_direct_decision_id IS NOT NULL AND c.direct_preparation_decision_id=r.source_direct_decision_id))) OR EXISTS(SELECT 1 FROM contract.contract_revision other WHERE other.tenant_id=t AND other.contract_id<>root.contract_id AND ((r.source_quote_response_id IS NOT NULL AND other.source_quote_response_id=r.source_quote_response_id) OR (r.source_direct_decision_id IS NOT NULL AND other.source_direct_decision_id=r.source_direct_decision_id))) THEN RAISE EXCEPTION 'contract source already consumed by another contract' USING ERRCODE='23514'; END IF;
 IF r.source_quote_response_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response q JOIN opportunity.quote_issue i ON i.tenant_id=q.tenant_id AND i.quote_issue_id=q.quote_issue_id WHERE q.tenant_id=t AND q.quote_response_id=r.source_quote_response_id AND i.quote_revision_id=r.source_quote_revision_id) THEN RAISE EXCEPTION 'contract quote version differs' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_r2_material(t,r.body_evidence_version_id,r.body_sha256,root.opportunity_id);
 IF r.body_evidence_submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.material_version m WHERE m.tenant_id=t AND m.material_version_id=r.body_evidence_version_id AND m.evidence_submission_id=r.body_evidence_submission_id) THEN RAISE EXCEPTION 'contract body evidence differs' USING ERRCODE='23514'; END IF;
 SELECT * INTO tpl FROM contract.template_version WHERE tenant_id=t AND template_version_id=r.template_version_id;
 PERFORM contract.fn_assert_r2_material(t,tpl.evidence_version_id,tpl.body_sha256,NULL);
 FOR cl IN SELECT c.* FROM contract.revision_clause rc JOIN contract.clause_version c ON c.tenant_id=rc.tenant_id AND c.clause_version_id=rc.clause_version_id WHERE rc.tenant_id=t AND rc.contract_revision_id=v LOOP
  PERFORM contract.fn_assert_r2_material(t,cl.evidence_version_id,cl.body_sha256,NULL);
 END LOOP;
 IF EXISTS(SELECT 1 FROM contract.revision_approval_requirement req JOIN contract.approval_policy pol ON pol.tenant_id=req.tenant_id AND pol.approval_policy_id=req.policy_id JOIN identity.appointment a ON a.tenant_id=d.tenant_id AND a.appointment_id=d.owner_appointment_id WHERE req.tenant_id=t AND req.contract_revision_id=v AND pol.organization_unit_id<>a.organization_unit_id) THEN RAISE EXCEPTION 'contract approval organization differs' USING ERRCODE='23514'; END IF;
 IF (SELECT count(DISTINCT policy_id) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>1 OR EXISTS(SELECT 1 FROM contract.revision_approval_requirement req JOIN contract.approval_policy_member pm ON pm.tenant_id=req.tenant_id AND pm.policy_id=req.policy_id WHERE req.tenant_id=t AND req.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_approval_requirement rr WHERE rr.tenant_id=t AND rr.contract_revision_id=v AND rr.requirement_code=pm.requirement_code AND rr.approver_appointment_id=pm.appointment_id)) THEN RAISE EXCEPTION 'contract policy requirements incomplete' USING ERRCODE='23514'; END IF;
 IF (SELECT count(*) FROM contract.revision_clause WHERE tenant_id=t AND contract_revision_id=v)<>r.clause_count OR (SELECT count(*) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>r.approval_requirement_count THEN RAISE EXCEPTION 'contract sealed requirements incomplete' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_version(uuid,uuid) IS '准确合同准备包、真实审核文档与同事务封存审批集合。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_version(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_approval(t uuid,c uuid,v uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE; b contract.revision_review_binding%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=v AND contract_id=c;
 SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=t AND contract_revision_id=v;
 IF r.contract_revision_id IS NULL OR r.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' OR b.revision_review_binding_id IS NULL OR (SELECT count(*) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>r.approval_requirement_count OR EXISTS(SELECT 1 FROM contract.revision_approval_requirement req WHERE req.tenant_id=t AND req.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_approval_decision d JOIN contract.revision_approval_request a ON a.tenant_id=d.tenant_id AND a.revision_approval_request_id=d.approval_request_id WHERE d.tenant_id=t AND d.requirement_id=req.revision_approval_requirement_id AND d.decision_code='APPROVED' AND d.review_binding_id=b.revision_review_binding_id AND a.contract_revision_id=v AND a.review_binding_id=b.revision_review_binding_id)) THEN RAISE EXCEPTION 'current contract approval incomplete' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_approval(uuid,uuid,uuid) IS '本版准确冲突放行及完整审批集合；旧版批准不可复用。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_approval(uuid,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_check_r2_fact() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE v uuid; root contract.contract%ROWTYPE; r contract.contract_revision%ROWTYPE; prior uuid; req contract.revision_review_request%ROWTYPE; dec contract.revision_review_decision%ROWTYPE; review conflict.conflict_review%ROWTYPE; b contract.revision_review_binding%ROWTYPE; requirement contract.revision_approval_requirement%ROWTYPE; ar contract.revision_approval_request%ROWTYPE;
BEGIN
 NEW.created_in_transaction=pg_current_xact_id();
 IF TG_TABLE_NAME='preparation_decision' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='contract' THEN
  IF NEW.preparation_contract_code IS NULL THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='contract_revision' THEN
  IF NEW.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' THEN RETURN NEW; END IF;
 END IF;
 NEW.created_at=clock_timestamp();
 IF TG_TABLE_NAME='approval_policy' THEN
  PERFORM 1 FROM identity.organization_unit WHERE tenant_id=NEW.tenant_id AND organization_unit_id=NEW.organization_unit_id FOR UPDATE;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='approval_policy_member' THEN
  IF NOT EXISTS(SELECT 1 FROM contract.approval_policy p WHERE p.tenant_id=NEW.tenant_id AND p.approval_policy_id=NEW.policy_id AND p.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'contract approval policy sealed' USING ERRCODE='23514'; END IF;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME IN ('contract_revision','contract') THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='preparation_workflow' THEN
  PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'contract workflow opportunity differs' USING ERRCODE='23514'; END IF;
  SELECT preparation_workflow_id INTO prior FROM contract.preparation_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.preparation_workflow_id);
  IF prior IS DISTINCT FROM NEW.previous_workflow_id OR (NEW.contract_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.contract c WHERE c.tenant_id=NEW.tenant_id AND c.contract_id=NEW.contract_id AND c.opportunity_id=NEW.opportunity_id)) THEN RAISE EXCEPTION 'contract workflow predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.task_id AND task.owner_appointment_id=NEW.owner_appointment_id AND ((task.subject_type='opportunity.opportunity' AND task.subject_id=NEW.opportunity_id) OR (task.subject_type='contract.contract' AND task.subject_id=NEW.contract_id))) THEN RAISE EXCEPTION 'contract workflow task differs' USING ERRCODE='23514'; END IF;
  IF NEW.prior_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND ((task.subject_type='opportunity.opportunity' AND task.subject_id=NEW.opportunity_id) OR (task.subject_type='contract.contract' AND task.subject_id=NEW.contract_id))) THEN RAISE EXCEPTION 'contract workflow prior task differs' USING ERRCODE='23514'; END IF;
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND task.state='CANCELLED' AND task.cancellation_reason_code='CONTRACT_AUTHORITY_MISSING' AND ((NEW.recovery_resume_stage='DIRECT_RETURNED' AND task.business_purpose_code='DECIDE_CONTRACT_PREPARATION') OR (NEW.recovery_resume_stage='RETURNED' AND task.business_purpose_code IN ('REVIEW_CONTRACT','APPROVE_CONTRACT')))) THEN RAISE EXCEPTION 'contract authority recovery basis differs' USING ERRCODE='23514'; END IF;
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow p WHERE p.tenant_id=NEW.tenant_id AND p.preparation_workflow_id=NEW.previous_workflow_id AND ((p.task_id=NEW.prior_task_id AND ((p.stage_code='DIRECT_REVIEW' AND NEW.recovery_resume_stage='DIRECT_RETURNED') OR (p.stage_code IN ('AWAIT_REVIEW','AWAIT_APPROVAL') AND NEW.recovery_resume_stage='RETURNED'))) OR (p.stage_code='OWNER_EXCEPTION' AND p.recovery_resume_stage=NEW.recovery_resume_stage AND p.prior_task_id=NEW.prior_task_id))) THEN RAISE EXCEPTION 'contract authority recovery predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.stage_code IN ('READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE') AND NOT EXISTS(SELECT 1 FROM contract.contract c JOIN contract.signature_readiness sr ON sr.tenant_id=c.tenant_id AND sr.contract_revision_id=c.current_revision_id WHERE c.tenant_id=NEW.tenant_id AND c.contract_id=NEW.contract_id) THEN RAISE EXCEPTION 'contract workflow readiness missing' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME IN ('template_version','clause_version') THEN
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.evidence_version_id,NEW.body_sha256,NULL);RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='preparation_draft' THEN
  PERFORM contract.fn_assert_r2_source(NEW.tenant_id,NEW.opportunity_id,NEW.source_quote_response_id,NEW.source_direct_decision_id,NEW.customer_confirmation_id,NEW.commercial_digest);
  IF NOT EXISTS(SELECT 1 FROM opportunity.opportunity o WHERE o.tenant_id=NEW.tenant_id AND o.opportunity_id=NEW.opportunity_id AND o.revision=NEW.opportunity_revision AND ((NEW.responsibility_type='opportunity.opportunity' AND NEW.responsibility_id=o.opportunity_id AND NEW.responsibility_revision=o.revision AND NEW.owner_appointment_id=o.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=o.tenant_id AND h.opportunity_id=o.opportunity_id)) OR (NEW.responsibility_type='opportunity.responsibility_handoff' AND EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=o.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.opportunity_id=o.opportunity_id AND h.revision=NEW.responsibility_revision AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id))))) THEN RAISE EXCEPTION 'contract draft responsibility changed' USING ERRCODE='23514'; END IF;
  SELECT preparation_draft_id INTO prior FROM contract.preparation_draft d WHERE d.tenant_id=NEW.tenant_id AND d.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_draft n WHERE n.tenant_id=d.tenant_id AND n.previous_draft_id=d.preparation_draft_id);
  IF prior IS DISTINCT FROM NEW.previous_draft_id THEN RAISE EXCEPTION 'contract draft predecessor differs' USING ERRCODE='23514'; END IF;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='revision_review_decision' THEN
  SELECT * INTO req FROM contract.revision_review_request WHERE tenant_id=NEW.tenant_id AND revision_review_request_id=NEW.request_id;v=req.contract_revision_id;
 ELSIF TG_TABLE_NAME='revision_approval_decision' THEN
  SELECT * INTO requirement FROM contract.revision_approval_requirement WHERE tenant_id=NEW.tenant_id AND revision_approval_requirement_id=NEW.requirement_id;v=requirement.contract_revision_id;
 ELSE v=NEW.contract_revision_id;
 END IF;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=v;
 SELECT * INTO root FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=r.contract_id FOR UPDATE;
 IF r.contract_revision_id IS NULL OR r.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' OR root.contract_termination_id IS NOT NULL OR root.contract_execution_id IS NOT NULL THEN RAISE EXCEPTION 'contract version unavailable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME IN ('revision_clause','revision_approval_requirement') THEN
  IF r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract version collection sealed' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='revision_approval_requirement' THEN
   IF NOT EXISTS(SELECT 1 FROM contract.approval_policy p JOIN contract.approval_policy_member m ON m.tenant_id=p.tenant_id AND m.policy_id=p.approval_policy_id WHERE p.tenant_id=NEW.tenant_id AND p.approval_policy_id=NEW.policy_id AND p.policy_digest=NEW.policy_digest AND m.requirement_code=NEW.requirement_code AND m.appointment_id=NEW.approver_appointment_id AND NOT EXISTS(SELECT 1 FROM contract.approval_policy n WHERE n.tenant_id=p.tenant_id AND n.organization_unit_id=p.organization_unit_id AND n.policy_version>p.policy_version)) THEN RAISE EXCEPTION 'contract approval policy differs' USING ERRCODE='23514'; END IF;
  END IF;RETURN NEW;
 END IF;
 IF root.current_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract version is not current' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='revision_review_request' THEN
  SELECT revision_review_request_id INTO prior FROM contract.revision_review_request x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_review_request n WHERE n.tenant_id=x.tenant_id AND n.previous_request_id=x.revision_review_request_id);
  IF prior IS DISTINCT FROM NEW.previous_request_id OR (prior IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.revision_review_decision d WHERE d.tenant_id=NEW.tenant_id AND d.request_id=prior AND d.decision_code='NEED_INFO')) OR EXISTS(SELECT 1 FROM contract.revision_review_binding x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=v) THEN RAISE EXCEPTION 'contract review request predecessor differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revision_review_decision' THEN
  IF EXISTS(SELECT 1 FROM contract.revision_review_request n WHERE n.tenant_id=NEW.tenant_id AND n.previous_request_id=NEW.request_id) THEN RAISE EXCEPTION 'contract review request superseded' USING ERRCODE='23514'; END IF;
  SELECT * INTO review FROM conflict.conflict_review WHERE tenant_id=NEW.tenant_id AND conflict_review_id=NEW.conflict_review_id FOR UPDATE;
  IF review.conflict_review_id IS NULL OR review.review_type_code<>'PRE_CONTRACT' OR review.trigger_fact_type<>'contract.contract_revision' OR review.trigger_fact_id<>v OR review.trigger_fact_hash IS DISTINCT FROM r.content_digest OR review.trigger_fact_revision IS NOT NULL OR review.scope_hash=decode(repeat('00',32),'hex') OR review.rule_set_hash=decode(repeat('00',32),'hex') OR review.corpus_hash=decode(repeat('00',32),'hex') OR review.scope_hash<>req.scope_hash OR NEW.scope_hash<>req.scope_hash OR (CASE WHEN review.initial_conclusion_code='CLEAR' THEN 'CLEAR' WHEN review.initial_conclusion_code='NEED_INFO' THEN 'NEED_INFO' ELSE review.resolution_code END) IS DISTINCT FROM NEW.decision_code OR (NEW.decision_code='WAIVED' AND NEW.resolution_digest IS DISTINCT FROM review.resolution_digest) OR (NEW.decision_code='CLEAR' AND NEW.resolution_digest IS DISTINCT FROM sha256(convert_to('R2_CONTRACT_REVIEW_CLEAR_V1|'||review.conflict_review_id::text||'|'||encode(review.scope_hash,'hex')||'|'||encode(review.rule_set_hash,'hex')||'|'||encode(review.corpus_hash,'hex')||'|'||encode(r.content_digest,'hex'),'UTF8'))) THEN RAISE EXCEPTION 'contract exact review decision differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revision_review_binding' THEN
  SELECT * INTO dec FROM contract.revision_review_decision WHERE tenant_id=NEW.tenant_id AND revision_review_decision_id=NEW.review_decision_id;
  SELECT * INTO req FROM contract.revision_review_request WHERE tenant_id=NEW.tenant_id AND revision_review_request_id=dec.request_id;
  IF req.contract_revision_id IS DISTINCT FROM v OR dec.decision_code NOT IN ('CLEAR','WAIVED') OR dec.conflict_review_id IS DISTINCT FROM NEW.conflict_review_id OR dec.scope_hash IS DISTINCT FROM NEW.scope_hash OR dec.resolution_digest IS DISTINCT FROM NEW.resolution_digest THEN RAISE EXCEPTION 'contract passing review binding differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME IN ('revision_approval_request','revision_approval_decision','signature_readiness') THEN
  SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=NEW.tenant_id AND revision_review_binding_id=NEW.review_binding_id;
  IF b.contract_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract approval review differs' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='revision_approval_decision' THEN
   SELECT * INTO ar FROM contract.revision_approval_request WHERE tenant_id=NEW.tenant_id AND revision_approval_request_id=NEW.approval_request_id;
   IF requirement.approver_appointment_id IS DISTINCT FROM NEW.decided_by_appointment_id OR ar.contract_revision_id IS DISTINCT FROM v OR ar.review_binding_id IS DISTINCT FROM NEW.review_binding_id THEN RAISE EXCEPTION 'contract approval requirement differs' USING ERRCODE='23514'; END IF;
  ELSIF TG_TABLE_NAME='signature_readiness' THEN
   PERFORM contract.fn_assert_r2_approval(NEW.tenant_id,root.contract_id,v);
   IF root.approved_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract readiness requires current approval pointer' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_r2_fact() IS 'T08准确当前版本、事务封存、后置审查和审批，不伪造签署。';
REVOKE ALL ON FUNCTION contract.fn_check_r2_fact() FROM PUBLIC;

CREATE FUNCTION contract.fn_check_r2_participation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=NEW.contract_revision_id;
 IF r.package_contract_code='R2_CONTRACT_PREPARATION_V1' THEN
  IF r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract preparation participants sealed' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=NEW.tenant_id AND p.confirmation_id=r.customer_confirmation_id AND p.party_id=NEW.party_id AND p.party_revision=NEW.party_revision AND p.role=NEW.context_role_code AND sha256(convert_to(p.profile_version_id::text,'UTF8'))=NEW.party_snapshot_digest) OR NEW.signature_required OR NEW.source_opportunity_participation_id IS NOT NULL THEN RAISE EXCEPTION 'contract preparation participants differ' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_r2_participation() IS '准备版本冻结准确确认参与方；签署计划另行激活。';
REVOKE ALL ON FUNCTION contract.fn_check_r2_participation() FROM PUBLIC;
CREATE TRIGGER trg_contract_participation__r2_basis BEFORE INSERT ON contract.contract_participation FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_participation();
COMMENT ON TRIGGER trg_contract_participation__r2_basis ON contract.contract_participation IS '准确确认参与方与版本形成事务。';
CREATE TRIGGER trg_approval_policy__r2_basis BEFORE INSERT ON contract.approval_policy FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_approval_policy__r2_basis ON contract.approval_policy IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_approval_policy_member__r2_basis BEFORE INSERT ON contract.approval_policy_member FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_approval_policy_member__r2_basis ON contract.approval_policy_member IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_preparation_workflow__r2_basis BEFORE INSERT ON contract.preparation_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_preparation_workflow__r2_basis ON contract.preparation_workflow IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_approval_request__r2_basis BEFORE INSERT ON contract.revision_approval_request FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_approval_request__r2_basis ON contract.revision_approval_request IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_preparation_draft__r2_basis BEFORE INSERT ON contract.preparation_draft FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_preparation_draft__r2_basis ON contract.preparation_draft IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_template_version__r2_basis BEFORE INSERT ON contract.template_version FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_template_version__r2_basis ON contract.template_version IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_clause_version__r2_basis BEFORE INSERT ON contract.clause_version FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_clause_version__r2_basis ON contract.clause_version IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_clause__r2_basis BEFORE INSERT ON contract.revision_clause FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_clause__r2_basis ON contract.revision_clause IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_review_request__r2_basis BEFORE INSERT ON contract.revision_review_request FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_review_request__r2_basis ON contract.revision_review_request IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_review_decision__r2_basis BEFORE INSERT ON contract.revision_review_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_review_decision__r2_basis ON contract.revision_review_decision IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_review_binding__r2_basis BEFORE INSERT ON contract.revision_review_binding FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_review_binding__r2_basis ON contract.revision_review_binding IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_approval_requirement__r2_basis BEFORE INSERT ON contract.revision_approval_requirement FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_approval_requirement__r2_basis ON contract.revision_approval_requirement IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_revision_approval_decision__r2_basis BEFORE INSERT ON contract.revision_approval_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_revision_approval_decision__r2_basis ON contract.revision_approval_decision IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_signature_readiness__r2_basis BEFORE INSERT ON contract.signature_readiness FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_signature_readiness__r2_basis ON contract.signature_readiness IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_preparation_decision__r2_basis BEFORE INSERT ON contract.preparation_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_preparation_decision__r2_basis ON contract.preparation_decision IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_contract_revision__r2_basis BEFORE INSERT ON contract.contract_revision FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_contract_revision__r2_basis ON contract.contract_revision IS '准确版本和形成事务守卫。';
CREATE TRIGGER trg_contract__r2_basis BEFORE INSERT ON contract.contract FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();
COMMENT ON TRIGGER trg_contract__r2_basis ON contract.contract IS '准确版本和形成事务守卫。';
GRANT EXECUTE ON FUNCTION contract.fn_assert_r2_material(uuid,uuid,bytea,uuid) TO ${app_command_role};
GRANT EXECUTE ON FUNCTION contract.fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea) TO ${app_command_role};
GRANT EXECUTE ON FUNCTION contract.fn_assert_r2_version(uuid,uuid) TO ${app_command_role};
GRANT EXECUTE ON FUNCTION contract.fn_assert_r2_approval(uuid,uuid,uuid) TO ${app_command_role};
CREATE OR REPLACE FUNCTION contract.fn_check_preparation_decision() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.preparation_request%ROWTYPE; o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.preparation_request WHERE tenant_id=NEW.tenant_id AND preparation_request_id=NEW.preparation_request_id;
 IF r.preparation_request_id IS NULL THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=r.tenant_id AND opportunity_id=r.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=r.tenant_id AND n.previous_request_id=r.preparation_request_id) THEN RAISE EXCEPTION 'pending preparation request is not current' USING ERRCODE='23514'; END IF;
 IF NEW.decision_code='RETURNED' THEN NEW.created_at=clock_timestamp();NEW.effective_from=NULL;RETURN NEW;END IF;
 IF o.closed_at IS NOT NULL OR o.revision<>r.opportunity_revision OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=r.tenant_id AND n.previous_request_id=r.preparation_request_id) OR EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=r.tenant_id AND n.previous_confirmation_id=r.customer_confirmation_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 IF r.responsibility_type='opportunity.opportunity' THEN
  IF o.owner_appointment_id<>r.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=r.tenant_id AND h.opportunity_id=r.opportunity_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=r.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=r.responsibility_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.created_at=clock_timestamp();
 IF NEW.decision_code='APPROVED' THEN NEW.effective_from=NEW.created_at; ELSE NEW.effective_from=NULL; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_preparation_decision() IS 'V980允许准确最新申请的非通过退回；批准仍复验全部当前依据。';
CREATE OR REPLACE FUNCTION platform_meta.fn_assert_contract_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.preparation_contract_code='R2_CONTRACT_PREPARATION_V1' THEN
            PERFORM contract.fn_assert_r2_source(NEW.tenant_id,NEW.opportunity_id,NEW.accepted_quote_response_id,NEW.direct_preparation_decision_id,NULL,NULL);
            IF EXISTS(SELECT 1 FROM contract.contract_revision v WHERE v.tenant_id=NEW.tenant_id AND v.contract_id<>NEW.contract_id AND ((NEW.accepted_quote_response_id IS NOT NULL AND v.source_quote_response_id=NEW.accepted_quote_response_id) OR (NEW.direct_preparation_decision_id IS NOT NULL AND v.source_direct_decision_id=NEW.direct_preparation_decision_id))) THEN RAISE EXCEPTION 'contract source already consumed by another contract' USING ERRCODE='23514'; END IF;
        ELSE
        PERFORM 1
        FROM opportunity.quote_response response
        JOIN opportunity.quote_issue issue
          ON issue.tenant_id = response.tenant_id
         AND issue.quote_issue_id = response.quote_issue_id
        WHERE response.tenant_id = NEW.tenant_id
          AND response.quote_response_id = NEW.accepted_quote_response_id
        FOR UPDATE OF issue;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'contract requires its exact issued quote response' USING ERRCODE = '23503';
        END IF;
        SELECT EXISTS (
            SELECT 1
            FROM opportunity.quote_response response
            JOIN opportunity.quote_issue issue
              ON issue.tenant_id = response.tenant_id
             AND issue.quote_issue_id = response.quote_issue_id
            JOIN opportunity.quote_revision quote_revision
              ON quote_revision.tenant_id = issue.tenant_id
             AND quote_revision.quote_revision_id = issue.quote_revision_id
            WHERE response.tenant_id = NEW.tenant_id
              AND response.quote_response_id = NEW.accepted_quote_response_id
              AND response.response_code = 'ACCEPTED'
              AND issue.issue_status_code = 'ACTIVE'
              AND quote_revision.opportunity_id = NEW.opportunity_id
              AND (quote_revision.valid_until IS NULL
                   OR quote_revision.valid_until > pg_catalog.clock_timestamp())
              AND NOT EXISTS (
                  SELECT 1 FROM opportunity.quote_issue replacement
                  WHERE replacement.tenant_id = issue.tenant_id
                    AND replacement.replaces_quote_issue_id = issue.quote_issue_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM opportunity.quote_response later_response
                  WHERE later_response.tenant_id = response.tenant_id
                    AND later_response.quote_issue_id = response.quote_issue_id
                    AND later_response.response_no > response.response_no
              )
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'contract requires an active accepted quote response from the same opportunity' USING ERRCODE = '23514';
        END IF;
        END IF;
        IF NEW.current_revision_id IS NOT NULL
           OR NEW.approved_revision_id IS NOT NULL
           OR NEW.contract_execution_id IS NOT NULL
           OR NEW.deal_activated_at IS NOT NULL
           OR NEW.contract_termination_id IS NOT NULL THEN
            RAISE EXCEPTION 'contract lifecycle slots must be empty on anchor insert' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.contract_termination_id IS NOT NULL THEN
        RAISE EXCEPTION 'terminated contract is sealed' USING ERRCODE = '55000';
    END IF;

    IF NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN
        IF OLD.contract_execution_id IS NOT NULL THEN
            RAISE EXCEPTION 'executed contract cannot advance revision' USING ERRCODE = '55000';
        END IF;
        SELECT EXISTS (
            SELECT 1
            FROM contract.contract_revision next_revision
            LEFT JOIN contract.contract_revision old_revision
              ON old_revision.tenant_id = NEW.tenant_id
             AND old_revision.contract_revision_id = OLD.current_revision_id
            WHERE next_revision.tenant_id = NEW.tenant_id
              AND next_revision.contract_revision_id = NEW.current_revision_id
              AND next_revision.contract_id = NEW.contract_id
              AND ((OLD.current_revision_id IS NULL
                    AND next_revision.revision_no = 1
                    AND next_revision.predecessor_revision_id IS NULL)
                OR (OLD.current_revision_id IS NOT NULL
                    AND next_revision.predecessor_revision_id = OLD.current_revision_id
                    AND next_revision.revision_no = old_revision.revision_no + 1))
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'current contract revision must advance by one direct successor' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.approved_revision_id IS NOT NULL
          AND NEW.approved_revision_id IS DISTINCT FROM OLD.approved_revision_id THEN
        RAISE EXCEPTION 'approved revision can change only while advancing the current revision' USING ERRCODE = '55000';
    END IF;

    IF NEW.approved_revision_id IS NOT NULL
       AND NEW.approved_revision_id IS DISTINCT FROM NEW.current_revision_id THEN
        RAISE EXCEPTION 'approved revision must equal current revision' USING ERRCODE = '23514';
    END IF;

    IF NEW.approved_revision_id IS NOT NULL AND NEW.approved_revision_id IS DISTINCT FROM OLD.approved_revision_id AND NEW.preparation_contract_code='R2_CONTRACT_PREPARATION_V1' THEN
        PERFORM contract.fn_assert_r2_approval(NEW.tenant_id,NEW.contract_id,NEW.approved_revision_id);
    END IF;
    IF NEW.contract_execution_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM contract.contract_execution execution
            WHERE execution.tenant_id = NEW.tenant_id
              AND execution.contract_execution_id = NEW.contract_execution_id
              AND execution.contract_id = NEW.contract_id
              AND execution.contract_revision_id = NEW.current_revision_id
              AND execution.contract_revision_id = NEW.approved_revision_id
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'execution must bind the current approved contract revision' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.deal_activated_at IS NOT NULL AND NEW.contract_execution_id IS NULL THEN
        RAISE EXCEPTION 'deal activation requires contract execution' USING ERRCODE = '23514';
    END IF;
    IF OLD.deal_activated_at IS NULL
       AND NEW.deal_activated_at IS NOT NULL
       AND NEW.contract_termination_id IS NOT NULL THEN
        RAISE EXCEPTION 'deal activation cannot be formed together with cancellation or termination' USING ERRCODE = '23514';
    END IF;

    IF NEW.contract_termination_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM contract.contract_termination termination
            WHERE termination.tenant_id = NEW.tenant_id
              AND termination.contract_termination_id = NEW.contract_termination_id
              AND termination.contract_id = NEW.contract_id
              AND termination.contract_revision_id = NEW.current_revision_id
              AND termination.contract_execution_id IS NOT DISTINCT FROM NEW.contract_execution_id
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'termination must bind the current contract lifecycle' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION platform_meta.fn_assert_contract_revision_package()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    source_matches boolean;
BEGIN
    IF NEW.package_contract_code='R2_CONTRACT_PREPARATION_V1' THEN
        PERFORM contract.fn_assert_r2_version(NEW.tenant_id,NEW.contract_revision_id);
        RETURN NEW;
    END IF;
    IF NEW.pre_contract_review_id IS NULL OR NEW.pre_contract_scope_hash IS NULL OR NEW.pre_contract_resolution_digest IS NULL THEN RAISE EXCEPTION 'legacy contract revision requires complete review' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM contract.contract contract_root
    WHERE contract_root.tenant_id = NEW.tenant_id
      AND contract_root.contract_id = NEW.contract_id
      AND contract_root.current_revision_id = NEW.contract_revision_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract revision must become current in the same transaction' USING ERRCODE = '23514';
    END IF;
    PERFORM 1
    FROM opportunity.quote_response response
    JOIN opportunity.quote_issue issue
      ON issue.tenant_id = response.tenant_id
     AND issue.quote_issue_id = response.quote_issue_id
    WHERE response.tenant_id = NEW.tenant_id
      AND response.quote_response_id = NEW.source_quote_response_id
    FOR UPDATE OF issue;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract revision requires its exact issued quote response' USING ERRCODE = '23503';
    END IF;
    SELECT EXISTS (
        SELECT 1
        FROM contract.contract contract_root
        JOIN opportunity.quote_response response
          ON response.tenant_id = contract_root.tenant_id
         AND response.quote_response_id = contract_root.accepted_quote_response_id
         AND response.quote_response_id = NEW.source_quote_response_id
        JOIN opportunity.quote_issue issue
          ON issue.tenant_id = response.tenant_id
         AND issue.quote_issue_id = response.quote_issue_id
        JOIN opportunity.quote_revision quote_revision
          ON quote_revision.tenant_id = issue.tenant_id
         AND quote_revision.quote_revision_id = issue.quote_revision_id
         AND quote_revision.quote_revision_id = NEW.source_quote_revision_id
        WHERE contract_root.tenant_id = NEW.tenant_id
          AND contract_root.contract_id = NEW.contract_id
          AND response.response_code = 'ACCEPTED'
          AND quote_revision.opportunity_id = contract_root.opportunity_id
    ) INTO source_matches;
    IF NOT source_matches THEN
        RAISE EXCEPTION 'contract revision quote and accepted response must be the anchor consumed source chain' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
DO $v980$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v12',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v11';
 IF NOT FOUND THEN RAISE EXCEPTION 'V980 requires 52-plus-2-r2-v11' USING ERRCODE='55000'; END IF;
END;
$v980$;
