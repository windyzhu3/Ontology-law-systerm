CREATE TABLE transfer.workflow (
    tenant_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    previous_workflow_id uuid,
    stage_code varchar(64) NOT NULL,
    target_stage_code varchar(64) NOT NULL,
    owner_appointment_id uuid,
    task_id uuid,
    submission_id uuid,
    review_id uuid,
    intake_id uuid,
    classification_id uuid,
    recorded_by uuid NOT NULL,
    due_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_workflow PRIMARY KEY (tenant_id, workflow_id),
    CONSTRAINT ck_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_transfer_workflow__previous UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_transfer_workflow__stage CHECK (target_stage_code IN ('PREPARE','REVIEW_TRANSFER','INTAKE','SUPPLEMENT','CLASSIFY','COMPLETE') AND (stage_code=target_stage_code OR stage_code='OWNER_EXCEPTION') AND ((stage_code IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NULL AND owner_appointment_id IS NULL) OR (stage_code NOT IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL)) AND (target_stage_code='PREPARE')=(submission_id IS NULL))
);

COMMENT ON TABLE transfer.workflow IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_workflow ON transfer.workflow IS '主键：在租户内唯一标识一条workflow记录。';
COMMENT ON INDEX transfer.pk_workflow IS '主键：在租户内唯一标识一条workflow记录。';
COMMENT ON COLUMN transfer.workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.workflow.workflow_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.workflow.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.workflow.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.workflow.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.workflow.previous_workflow_id IS '直接前序责任。';
COMMENT ON COLUMN transfer.workflow.stage_code IS '当前可办理阶段或责任异常。';
COMMENT ON COLUMN transfer.workflow.target_stage_code IS '责任异常仍保留原阶段。';
COMMENT ON COLUMN transfer.workflow.owner_appointment_id IS '当前责任任职。';
COMMENT ON COLUMN transfer.workflow.task_id IS '当前待办。';
COMMENT ON COLUMN transfer.workflow.submission_id IS '准确待审提交。';
COMMENT ON COLUMN transfer.workflow.review_id IS '本次独立审查。';
COMMENT ON COLUMN transfer.workflow.intake_id IS '准确案管接收或退回。';
COMMENT ON COLUMN transfer.workflow.classification_id IS '本案分类及承接事实。';
COMMENT ON COLUMN transfer.workflow.recorded_by IS '记录任职。';
COMMENT ON COLUMN transfer.workflow.due_at IS '原责任期限，恢复和补正不重置。';
COMMENT ON COLUMN transfer.workflow.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_workflow__revision ON transfer.workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_transfer_workflow__previous ON transfer.workflow IS '同一责任只有一个后继。';
COMMENT ON INDEX transfer.uq_transfer_workflow__previous IS '同一责任只有一个后继。';
COMMENT ON CONSTRAINT ck_transfer_workflow__stage ON transfer.workflow IS '阶段、待审提交与责任一致。';
CREATE TABLE transfer.submission (
    tenant_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    evidence_submission_ids uuid[] NOT NULL,
    previous_submission_id uuid,
    previous_review_id uuid,
    previous_intake_id uuid,
    workflow_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    customer_confirmation_id uuid NOT NULL,
    client_identity_material_id uuid NOT NULL,
    signature_archive_material_id uuid NOT NULL,
    confirmed_action_draft_id uuid NOT NULL,
    action_draft_digest bytea NOT NULL,
    contract_context_digest bytea NOT NULL,
    legal_need_context_digest bytea NOT NULL,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_submission PRIMARY KEY (tenant_id, submission_id),
    CONSTRAINT ck_submission__revision CHECK (revision=0),
    CONSTRAINT ck_submission__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_transfer_submission__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT uq_transfer_submission__draft UNIQUE (tenant_id, confirmed_action_draft_id),
    CONSTRAINT ck_submission__action_draft_digest_length CHECK (octet_length(action_draft_digest) = 32),
    CONSTRAINT ck_submission__contract_context_digest_length CHECK (octet_length(contract_context_digest) = 32),
    CONSTRAINT ck_submission__legal_need_context_digest_length CHECK (octet_length(legal_need_context_digest) = 32),
    CONSTRAINT ck_submission__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE transfer.submission IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_submission ON transfer.submission IS '主键：在租户内唯一标识一条submission记录。';
COMMENT ON INDEX transfer.pk_submission IS '主键：在租户内唯一标识一条submission记录。';
COMMENT ON COLUMN transfer.submission.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.submission.submission_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.submission.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.submission.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.submission.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.submission.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.submission.evidence_submission_ids IS '本次完整已接收证据集合，包含补正证据。';
COMMENT ON COLUMN transfer.submission.previous_submission_id IS '补正对应的上一提交。';
COMMENT ON COLUMN transfer.submission.previous_review_id IS '补正对应的准确退回审查。';
COMMENT ON COLUMN transfer.submission.previous_intake_id IS '案管退回的准确决定。';
COMMENT ON COLUMN transfer.submission.workflow_id IS '本次销售办理的准确责任。';
COMMENT ON COLUMN transfer.submission.contract_revision_id IS '批准合同版本。';
COMMENT ON COLUMN transfer.submission.customer_confirmation_id IS '当前客户及需求确认。';
COMMENT ON COLUMN transfer.submission.client_identity_material_id IS '已接收主体证明。';
COMMENT ON COLUMN transfer.submission.signature_archive_material_id IS '已接收完整签署归档。';
COMMENT ON COLUMN transfer.submission.confirmed_action_draft_id IS '人工确认的准确输入草案。';
COMMENT ON COLUMN transfer.submission.action_draft_digest IS '准确确认草案摘要。';
COMMENT ON COLUMN transfer.submission.contract_context_digest IS '执行来源与合同上下文摘要。';
COMMENT ON COLUMN transfer.submission.legal_need_context_digest IS '提交时法律需求摘要。';
COMMENT ON COLUMN transfer.submission.recorded_by IS '实际销售提交任职。';
COMMENT ON COLUMN transfer.submission.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN transfer.submission.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN transfer.submission.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_submission__revision ON transfer.submission IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_submission__body ON transfer.submission IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_transfer_submission__workflow ON transfer.submission IS '一销售责任只提交一次。';
COMMENT ON INDEX transfer.uq_transfer_submission__workflow IS '一销售责任只提交一次。';
COMMENT ON CONSTRAINT uq_transfer_submission__draft ON transfer.submission IS '一确认草案只形成一次提交。';
COMMENT ON INDEX transfer.uq_transfer_submission__draft IS '一确认草案只形成一次提交。';
COMMENT ON CONSTRAINT ck_submission__action_draft_digest_length ON transfer.submission IS '摘要格式：action_draft_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_submission__contract_context_digest_length ON transfer.submission IS '摘要格式：contract_context_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_submission__legal_need_context_digest_length ON transfer.submission IS '摘要格式：legal_need_context_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_submission__body_digest_length ON transfer.submission IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE transfer.review (
    tenant_id uuid NOT NULL,
    review_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    outcome_code varchar(64) NOT NULL,
    confirmed_action_draft_id uuid NOT NULL,
    action_draft_digest bytea NOT NULL,
    scope_digest bytea NOT NULL,
    corpus_digest bytea NOT NULL,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_review PRIMARY KEY (tenant_id, review_id),
    CONSTRAINT ck_review__revision CHECK (revision=0),
    CONSTRAINT ck_review__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_transfer_review__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT uq_transfer_review__conflict UNIQUE (tenant_id, conflict_review_id),
    CONSTRAINT ck_transfer_review__outcome CHECK (outcome_code IN ('CLEAR','NEED_INFO','BLOCKED')),
    CONSTRAINT ck_review__action_draft_digest_length CHECK (octet_length(action_draft_digest) = 32),
    CONSTRAINT ck_review__scope_digest_length CHECK (octet_length(scope_digest) = 32),
    CONSTRAINT ck_review__corpus_digest_length CHECK (octet_length(corpus_digest) = 32),
    CONSTRAINT ck_review__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE transfer.review IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_review ON transfer.review IS '主键：在租户内唯一标识一条review记录。';
COMMENT ON INDEX transfer.pk_review IS '主键：在租户内唯一标识一条review记录。';
COMMENT ON COLUMN transfer.review.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.review.review_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.review.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.review.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.review.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.review.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.review.workflow_id IS '被办理的独立审查责任。';
COMMENT ON COLUMN transfer.review.submission_id IS '准确销售提交。';
COMMENT ON COLUMN transfer.review.conflict_review_id IS '独立PRE_TRANSFER事实。';
COMMENT ON COLUMN transfer.review.outcome_code IS '人工审查结果。';
COMMENT ON COLUMN transfer.review.confirmed_action_draft_id IS '准确确认草案。';
COMMENT ON COLUMN transfer.review.action_draft_digest IS '准确草案摘要。';
COMMENT ON COLUMN transfer.review.scope_digest IS '范围摘要。';
COMMENT ON COLUMN transfer.review.corpus_digest IS '语料摘要。';
COMMENT ON COLUMN transfer.review.recorded_by IS '实际独立审查人。';
COMMENT ON COLUMN transfer.review.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN transfer.review.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN transfer.review.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_review__revision ON transfer.review IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_review__body ON transfer.review IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_transfer_review__workflow ON transfer.review IS '一次责任一个审查结果。';
COMMENT ON INDEX transfer.uq_transfer_review__workflow IS '一次责任一个审查结果。';
COMMENT ON CONSTRAINT uq_transfer_review__conflict ON transfer.review IS '审查事实专属本提交。';
COMMENT ON INDEX transfer.uq_transfer_review__conflict IS '审查事实专属本提交。';
COMMENT ON CONSTRAINT ck_transfer_review__outcome ON transfer.review IS '不自动豁免。';
COMMENT ON CONSTRAINT ck_review__action_draft_digest_length ON transfer.review IS '摘要格式：action_draft_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_review__scope_digest_length ON transfer.review IS '摘要格式：scope_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_review__corpus_digest_length ON transfer.review IS '摘要格式：corpus_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_review__body_digest_length ON transfer.review IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE transfer.review_return_item (
    tenant_id uuid NOT NULL,
    review_return_item_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    review_id uuid NOT NULL,
    requirement_code varchar(64) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_review_return_item PRIMARY KEY (tenant_id, review_return_item_id),
    CONSTRAINT ck_review_return_item__revision CHECK (revision=0),
    CONSTRAINT ck_review_return_item__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_transfer_review_return_item__requirement UNIQUE (tenant_id, review_id, requirement_code),
    CONSTRAINT ck_review_return_item__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE transfer.review_return_item IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_review_return_item ON transfer.review_return_item IS '主键：在租户内唯一标识一条review_return_item记录。';
COMMENT ON INDEX transfer.pk_review_return_item IS '主键：在租户内唯一标识一条review_return_item记录。';
COMMENT ON COLUMN transfer.review_return_item.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.review_return_item.review_return_item_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.review_return_item.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.review_return_item.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.review_return_item.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.review_return_item.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.review_return_item.review_id IS '准确非通过审查。';
COMMENT ON COLUMN transfer.review_return_item.requirement_code IS '准确补正要求。';
COMMENT ON COLUMN transfer.review_return_item.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN transfer.review_return_item.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN transfer.review_return_item.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_review_return_item__revision ON transfer.review_return_item IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_review_return_item__body ON transfer.review_return_item IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_transfer_review_return_item__requirement ON transfer.review_return_item IS '一次审查一个具名要求。';
COMMENT ON INDEX transfer.uq_transfer_review_return_item__requirement IS '一次审查一个具名要求。';
COMMENT ON CONSTRAINT ck_review_return_item__body_digest_length ON transfer.review_return_item IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE transfer.intake (
    tenant_id uuid NOT NULL,
    intake_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    review_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    decision_record_id uuid NOT NULL,
    outcome_code varchar(64) NOT NULL,
    requirement_code varchar(64),
    matter_id uuid,
    matter_no varchar(64),
    confirmed_action_draft_id uuid NOT NULL,
    action_draft_digest bytea NOT NULL,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_intake PRIMARY KEY (tenant_id, intake_id),
    CONSTRAINT ck_intake__revision CHECK (revision=0),
    CONSTRAINT ck_intake__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_transfer_intake__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT uq_transfer_intake__snapshot UNIQUE (tenant_id, snapshot_id),
    CONSTRAINT ck_transfer_intake__outcome CHECK ((outcome_code='ACCEPT' AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND requirement_code IS NULL) OR (outcome_code='RETURN' AND matter_id IS NULL AND matter_no IS NULL AND requirement_code IS NOT NULL)),
    CONSTRAINT ck_intake__action_draft_digest_length CHECK (octet_length(action_draft_digest) = 32),
    CONSTRAINT ck_intake__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE transfer.intake IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_intake ON transfer.intake IS '主键：在租户内唯一标识一条intake记录。';
COMMENT ON INDEX transfer.pk_intake IS '主键：在租户内唯一标识一条intake记录。';
COMMENT ON COLUMN transfer.intake.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.intake.intake_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.intake.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.intake.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.intake.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.intake.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.intake.workflow_id IS '准确案管待办。';
COMMENT ON COLUMN transfer.intake.submission_id IS '接收的提交。';
COMMENT ON COLUMN transfer.intake.review_id IS '准确独立审查。';
COMMENT ON COLUMN transfer.intake.snapshot_id IS '案管核对的完整冻结快照。';
COMMENT ON COLUMN transfer.intake.decision_record_id IS '独立案管决定。';
COMMENT ON COLUMN transfer.intake.outcome_code IS '接收或退回。';
COMMENT ON COLUMN transfer.intake.requirement_code IS '退回项目。';
COMMENT ON COLUMN transfer.intake.matter_id IS '接收后唯一案件身份。';
COMMENT ON COLUMN transfer.intake.matter_no IS '唯一案件编号。';
COMMENT ON COLUMN transfer.intake.confirmed_action_draft_id IS '接收核对草案。';
COMMENT ON COLUMN transfer.intake.action_draft_digest IS '草案摘要。';
COMMENT ON COLUMN transfer.intake.recorded_by IS '案管接收任职。';
COMMENT ON COLUMN transfer.intake.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN transfer.intake.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN transfer.intake.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_intake__revision ON transfer.intake IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_intake__body ON transfer.intake IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_transfer_intake__workflow ON transfer.intake IS '一责任一决定。';
COMMENT ON INDEX transfer.uq_transfer_intake__workflow IS '一责任一决定。';
COMMENT ON CONSTRAINT uq_transfer_intake__snapshot ON transfer.intake IS '一快照一决定。';
COMMENT ON INDEX transfer.uq_transfer_intake__snapshot IS '一快照一决定。';
COMMENT ON CONSTRAINT ck_transfer_intake__outcome ON transfer.intake IS '未接收不得生成案件。';
COMMENT ON CONSTRAINT ck_intake__action_draft_digest_length ON transfer.intake IS '摘要格式：action_draft_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_intake__body_digest_length ON transfer.intake IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE transfer.classification (
    tenant_id uuid NOT NULL,
    classification_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    previous_classification_id uuid,
    workflow_id uuid NOT NULL,
    intake_id uuid NOT NULL,
    matter_id uuid NOT NULL,
    category_code varchar(64) NOT NULL,
    recipient_appointment_id uuid NOT NULL,
    confirmed_action_draft_id uuid NOT NULL,
    action_draft_digest bytea NOT NULL,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_classification PRIMARY KEY (tenant_id, classification_id),
    CONSTRAINT ck_classification__revision CHECK (revision=0),
    CONSTRAINT ck_classification__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_transfer_classification__previous UNIQUE (tenant_id, previous_classification_id),
    CONSTRAINT uq_transfer_classification__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT ck_transfer_classification__category CHECK (category_code IN ('GENERAL','ENFORCEMENT','OTHER')),
    CONSTRAINT ck_classification__action_draft_digest_length CHECK (octet_length(action_draft_digest) = 32),
    CONSTRAINT ck_classification__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE transfer.classification IS 'Fact Owner：TransferRuntime；转案办理不可变事实；接收之前不生成案件。';
COMMENT ON CONSTRAINT pk_classification ON transfer.classification IS '主键：在租户内唯一标识一条classification记录。';
COMMENT ON INDEX transfer.pk_classification IS '主键：在租户内唯一标识一条classification记录。';
COMMENT ON COLUMN transfer.classification.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.classification.classification_id IS '转案办理不可变事实；接收之前不生成案件。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.classification.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.classification.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN transfer.classification.transfer_request_id IS '准确转案来源。';
COMMENT ON COLUMN transfer.classification.opportunity_id IS '准确商机。';
COMMENT ON COLUMN transfer.classification.previous_classification_id IS '明确更正时保留前次分类。';
COMMENT ON COLUMN transfer.classification.workflow_id IS '已接收案件的分类责任。';
COMMENT ON COLUMN transfer.classification.intake_id IS '准确接收事实。';
COMMENT ON COLUMN transfer.classification.matter_id IS '保持已接收案件身份。';
COMMENT ON COLUMN transfer.classification.category_code IS '综法、执行或其他。';
COMMENT ON COLUMN transfer.classification.recipient_appointment_id IS '有权承接任职。';
COMMENT ON COLUMN transfer.classification.confirmed_action_draft_id IS '人工分类草案。';
COMMENT ON COLUMN transfer.classification.action_draft_digest IS '分类草案摘要。';
COMMENT ON COLUMN transfer.classification.recorded_by IS '分类确认人。';
COMMENT ON COLUMN transfer.classification.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN transfer.classification.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN transfer.classification.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_classification__revision ON transfer.classification IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_classification__body ON transfer.classification IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_transfer_classification__previous ON transfer.classification IS '前次分类只允许一个更正后继。';
COMMENT ON INDEX transfer.uq_transfer_classification__previous IS '前次分类只允许一个更正后继。';
COMMENT ON CONSTRAINT uq_transfer_classification__workflow ON transfer.classification IS '一个分类责任一个结果。';
COMMENT ON INDEX transfer.uq_transfer_classification__workflow IS '一个分类责任一个结果。';
COMMENT ON CONSTRAINT ck_transfer_classification__category ON transfer.classification IS '限定MVP分类。';
COMMENT ON CONSTRAINT ck_classification__action_draft_digest_length ON transfer.classification IS '摘要格式：action_draft_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_classification__body_digest_length ON transfer.classification IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__tenant ON transfer.workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__transfer_request_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__opportunity_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES transfer.workflow (tenant_id, workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__previous_workflow_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__owner_appointment_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__task_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__submission_id
    FOREIGN KEY (tenant_id, submission_id)
    REFERENCES transfer.submission (tenant_id, submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__submission_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__review_id
    FOREIGN KEY (tenant_id, review_id)
    REFERENCES transfer.review (tenant_id, review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__review_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__intake_id
    FOREIGN KEY (tenant_id, intake_id)
    REFERENCES transfer.intake (tenant_id, intake_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__intake_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__classification_id
    FOREIGN KEY (tenant_id, classification_id)
    REFERENCES transfer.classification (tenant_id, classification_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__classification_id ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.workflow
    ADD CONSTRAINT fk_workflow__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_workflow__recorded_by ON transfer.workflow IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__tenant ON transfer.submission IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__transfer_request_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__opportunity_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__previous_submission_id
    FOREIGN KEY (tenant_id, previous_submission_id)
    REFERENCES transfer.submission (tenant_id, submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__previous_submission_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__previous_review_id
    FOREIGN KEY (tenant_id, previous_review_id)
    REFERENCES transfer.review (tenant_id, review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__previous_review_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__previous_intake_id
    FOREIGN KEY (tenant_id, previous_intake_id)
    REFERENCES transfer.intake (tenant_id, intake_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__previous_intake_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES transfer.workflow (tenant_id, workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__workflow_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__contract_revision_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__customer_confirmation_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__client_identity_material_id
    FOREIGN KEY (tenant_id, client_identity_material_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__client_identity_material_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__signature_archive_material_id
    FOREIGN KEY (tenant_id, signature_archive_material_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__signature_archive_material_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__confirmed_action_draft_id
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__confirmed_action_draft_id ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.submission
    ADD CONSTRAINT fk_submission__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_submission__recorded_by ON transfer.submission IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__tenant ON transfer.review IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__transfer_request_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__opportunity_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES transfer.workflow (tenant_id, workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__workflow_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__submission_id
    FOREIGN KEY (tenant_id, submission_id)
    REFERENCES transfer.submission (tenant_id, submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__submission_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__conflict_review_id
    FOREIGN KEY (tenant_id, conflict_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__conflict_review_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__confirmed_action_draft_id
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__confirmed_action_draft_id ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review
    ADD CONSTRAINT fk_review__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review__recorded_by ON transfer.review IS '同租户准确事实引用。';
ALTER TABLE transfer.review_return_item
    ADD CONSTRAINT fk_review_return_item__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review_return_item__tenant ON transfer.review_return_item IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.review_return_item
    ADD CONSTRAINT fk_review_return_item__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review_return_item__transfer_request_id ON transfer.review_return_item IS '同租户准确事实引用。';
ALTER TABLE transfer.review_return_item
    ADD CONSTRAINT fk_review_return_item__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review_return_item__opportunity_id ON transfer.review_return_item IS '同租户准确事实引用。';
ALTER TABLE transfer.review_return_item
    ADD CONSTRAINT fk_review_return_item__review_id
    FOREIGN KEY (tenant_id, review_id)
    REFERENCES transfer.review (tenant_id, review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_review_return_item__review_id ON transfer.review_return_item IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__tenant ON transfer.intake IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__transfer_request_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__opportunity_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES transfer.workflow (tenant_id, workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__workflow_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__submission_id
    FOREIGN KEY (tenant_id, submission_id)
    REFERENCES transfer.submission (tenant_id, submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__submission_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__review_id
    FOREIGN KEY (tenant_id, review_id)
    REFERENCES transfer.review (tenant_id, review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__review_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__snapshot_id
    FOREIGN KEY (tenant_id, snapshot_id)
    REFERENCES transfer.transfer_snapshot (tenant_id, transfer_snapshot_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__snapshot_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__decision_record_id
    FOREIGN KEY (tenant_id, decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__decision_record_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__confirmed_action_draft_id
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__confirmed_action_draft_id ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.intake
    ADD CONSTRAINT fk_intake__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_intake__recorded_by ON transfer.intake IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__tenant ON transfer.classification IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__transfer_request_id
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__transfer_request_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__opportunity_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__previous_classification_id
    FOREIGN KEY (tenant_id, previous_classification_id)
    REFERENCES transfer.classification (tenant_id, classification_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__previous_classification_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES transfer.workflow (tenant_id, workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__workflow_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__intake_id
    FOREIGN KEY (tenant_id, intake_id)
    REFERENCES transfer.intake (tenant_id, intake_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__intake_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__recipient_appointment_id
    FOREIGN KEY (tenant_id, recipient_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__recipient_appointment_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__confirmed_action_draft_id
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__confirmed_action_draft_id ON transfer.classification IS '同租户准确事实引用。';
ALTER TABLE transfer.classification
    ADD CONSTRAINT fk_classification__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_classification__recorded_by ON transfer.classification IS '同租户准确事实引用。';
CREATE TRIGGER trg_transfer_workflow__immutable BEFORE UPDATE OR DELETE ON transfer.workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_workflow__transaction BEFORE INSERT ON transfer.workflow FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.workflow TO ${app_command_role};
GRANT SELECT ON transfer.workflow TO ${app_query_role};
CREATE TRIGGER trg_transfer_submission__immutable BEFORE UPDATE OR DELETE ON transfer.submission FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_submission__transaction BEFORE INSERT ON transfer.submission FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.submission FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.submission TO ${app_command_role};
GRANT SELECT ON transfer.submission TO ${app_query_role};
CREATE TRIGGER trg_transfer_review__immutable BEFORE UPDATE OR DELETE ON transfer.review FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_review__transaction BEFORE INSERT ON transfer.review FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.review FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.review TO ${app_command_role};
GRANT SELECT ON transfer.review TO ${app_query_role};
CREATE TRIGGER trg_transfer_review_return_item__immutable BEFORE UPDATE OR DELETE ON transfer.review_return_item FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_review_return_item__transaction BEFORE INSERT ON transfer.review_return_item FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.review_return_item FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.review_return_item TO ${app_command_role};
GRANT SELECT ON transfer.review_return_item TO ${app_query_role};
CREATE TRIGGER trg_transfer_intake__immutable BEFORE UPDATE OR DELETE ON transfer.intake FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_intake__transaction BEFORE INSERT ON transfer.intake FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.intake FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.intake TO ${app_command_role};
GRANT SELECT ON transfer.intake TO ${app_query_role};
CREATE TRIGGER trg_transfer_classification__immutable BEFORE UPDATE OR DELETE ON transfer.classification FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_transfer_classification__transaction BEFORE INSERT ON transfer.classification FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON transfer.classification FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON transfer.classification TO ${app_command_role};
GRANT SELECT ON transfer.classification TO ${app_query_role};

CREATE FUNCTION transfer.fn_workflow_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r transfer.transfer_request%ROWTYPE; p transfer.workflow%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; s transfer.submission%ROWTYPE; rv transfer.review%ROWTYPE; it transfer.intake%ROWTYPE; cl transfer.classification%ROWTYPE;
BEGIN
 SELECT * INTO r FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 IF r.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR (r.accepted_snapshot_id IS NOT NULL AND (NEW.target_stage_code NOT IN ('CLASSIFY','COMPLETE') OR NOT EXISTS(SELECT 1 FROM transfer.intake a WHERE a.tenant_id=NEW.tenant_id AND a.intake_id=NEW.intake_id AND a.snapshot_id=r.accepted_snapshot_id AND a.matter_id=r.matter_id AND a.outcome_code='ACCEPT'))) THEN RAISE EXCEPTION 'transfer workflow request differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_workflow_id IS NULL THEN
  IF NEW.target_stage_code<>'PREPARE' THEN RAISE EXCEPTION 'transfer initial stage differs' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO p FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.previous_workflow_id;
  IF p.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR p.opportunity_id IS DISTINCT FROM NEW.opportunity_id THEN RAISE EXCEPTION 'transfer workflow predecessor differs' USING ERRCODE='23514'; END IF;
  IF p.due_at IS DISTINCT FROM NEW.due_at THEN RAISE EXCEPTION 'transfer original deadline changed' USING ERRCODE='23514'; END IF;
  IF p.task_id IS NOT NULL THEN
   SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=p.task_id;
   IF NEW.submission_id IS DISTINCT FROM p.submission_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.submission' OR t.completion_fact_id IS DISTINCT FROM NEW.submission_id OR t.completion_fact_revision IS DISTINCT FROM 0 THEN RAISE EXCEPTION 'transfer predecessor completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.review_id IS DISTINCT FROM p.review_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.review' OR t.completion_fact_id IS DISTINCT FROM NEW.review_id THEN RAISE EXCEPTION 'transfer review completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.intake_id IS DISTINCT FROM p.intake_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.intake' OR t.completion_fact_id IS DISTINCT FROM NEW.intake_id THEN RAISE EXCEPTION 'transfer intake completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.classification_id IS DISTINCT FROM p.classification_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.classification' OR t.completion_fact_id IS DISTINCT FROM NEW.classification_id THEN RAISE EXCEPTION 'transfer classification completion differs' USING ERRCODE='23514'; END IF;
   ELSIF t.state IS DISTINCT FROM 'CANCELLED' THEN RAISE EXCEPTION 'transfer reassignment requires cancelled predecessor' USING ERRCODE='23514'; END IF;
  END IF;
  IF NEW.submission_id IS DISTINCT FROM p.submission_id THEN
   SELECT * INTO s FROM transfer.submission WHERE tenant_id=NEW.tenant_id AND submission_id=NEW.submission_id;
   IF p.stage_code NOT IN ('PREPARE','SUPPLEMENT') OR NEW.review_id IS NOT NULL OR NEW.intake_id IS NOT NULL OR NEW.target_stage_code<>'REVIEW_TRANSFER' OR s.workflow_id IS DISTINCT FROM p.workflow_id OR s.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'transfer submitted successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.review_id IS DISTINCT FROM p.review_id THEN
   SELECT * INTO rv FROM transfer.review WHERE tenant_id=NEW.tenant_id AND review_id=NEW.review_id;
   IF p.stage_code<>'REVIEW_TRANSFER' OR rv.workflow_id IS DISTINCT FROM p.workflow_id OR rv.submission_id IS DISTINCT FROM NEW.submission_id OR rv.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NEW.target_stage_code IS DISTINCT FROM (CASE WHEN rv.outcome_code='CLEAR' THEN 'INTAKE' ELSE 'SUPPLEMENT' END) THEN RAISE EXCEPTION 'transfer reviewed successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.intake_id IS DISTINCT FROM p.intake_id THEN
   SELECT * INTO it FROM transfer.intake WHERE tenant_id=NEW.tenant_id AND intake_id=NEW.intake_id;
   IF p.stage_code<>'INTAKE' OR it.workflow_id IS DISTINCT FROM p.workflow_id OR it.review_id IS DISTINCT FROM NEW.review_id OR it.submission_id IS DISTINCT FROM NEW.submission_id OR it.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NEW.target_stage_code IS DISTINCT FROM (CASE WHEN it.outcome_code='ACCEPT' THEN 'CLASSIFY' ELSE 'SUPPLEMENT' END) THEN RAISE EXCEPTION 'transfer intake successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.classification_id IS DISTINCT FROM p.classification_id THEN
   SELECT * INTO cl FROM transfer.classification WHERE tenant_id=NEW.tenant_id AND classification_id=NEW.classification_id;
   IF p.stage_code<>'CLASSIFY' OR NEW.target_stage_code<>'COMPLETE' OR cl.workflow_id IS DISTINCT FROM p.workflow_id OR cl.intake_id IS DISTINCT FROM NEW.intake_id OR cl.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'transfer classification successor differs' USING ERRCODE='23514'; END IF;
  ELSIF p.stage_code='COMPLETE' AND NEW.stage_code='CLASSIFY' AND NEW.classification_id IS NOT DISTINCT FROM p.classification_id AND NEW.intake_id IS NOT DISTINCT FROM p.intake_id THEN NULL;
  ELSIF NEW.target_stage_code IS DISTINCT FROM p.target_stage_code THEN RAISE EXCEPTION 'transfer responsibility cannot skip stages' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.stage_code IN ('REVIEW_TRANSFER','INTAKE') AND EXISTS(
  SELECT 1 FROM transfer.submission submitted
  JOIN identity.appointment sales ON sales.tenant_id=submitted.tenant_id AND sales.appointment_id=submitted.recorded_by
  JOIN identity.appointment reviewer ON reviewer.tenant_id=sales.tenant_id AND reviewer.appointment_id=NEW.owner_appointment_id
  WHERE submitted.tenant_id=NEW.tenant_id AND submitted.submission_id=NEW.submission_id AND sales.principal_id=reviewer.principal_id
 ) THEN RAISE EXCEPTION 'transfer review must be independent of submitter' USING ERRCODE='23514'; END IF;
 IF NEW.task_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id;
  IF t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR t.original_sla_due_at IS DISTINCT FROM NEW.due_at OR t.state IS DISTINCT FROM 'OPEN' OR t.business_purpose_code IS DISTINCT FROM (CASE WHEN NEW.stage_code='PREPARE' THEN 'PREPARE_TRANSFER' WHEN NEW.stage_code='INTAKE' THEN 'ACCEPT_TRANSFER' WHEN NEW.stage_code='CLASSIFY' THEN 'CLASSIFY_MATTER' WHEN NEW.stage_code='SUPPLEMENT' THEN 'SUPPLEMENT_TRANSFER' ELSE 'REVIEW_TRANSFER' END) THEN RAISE EXCEPTION 'transfer task differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_submission_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; r transfer.transfer_request%ROWTYPE; k contract.contract%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO r FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 SELECT * INTO k FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=r.contract_id;
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=w.task_id;
 IF r.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.accepted_snapshot_id IS NOT NULL OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.stage_code NOT IN ('PREPARE','SUPPLEMENT') OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.state IS DISTINCT FROM 'OPEN' OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer submission responsibility differs' USING ERRCODE='23514'; END IF;
 IF (w.stage_code='PREPARE' AND (NEW.previous_submission_id IS NOT NULL OR NEW.previous_review_id IS NOT NULL OR NEW.previous_intake_id IS NOT NULL)) OR (w.stage_code='SUPPLEMENT' AND (NEW.previous_submission_id IS DISTINCT FROM w.submission_id OR NEW.previous_review_id IS DISTINCT FROM w.review_id OR NEW.previous_intake_id IS DISTINCT FROM w.intake_id OR NOT ((w.intake_id IS NULL AND EXISTS(SELECT 1 FROM transfer.review rv WHERE rv.tenant_id=NEW.tenant_id AND rv.review_id=NEW.previous_review_id AND rv.submission_id=NEW.previous_submission_id AND rv.outcome_code<>'CLEAR')) OR (w.intake_id IS NOT NULL AND EXISTS(SELECT 1 FROM transfer.intake i WHERE i.tenant_id=NEW.tenant_id AND i.intake_id=w.intake_id AND i.submission_id=NEW.previous_submission_id AND i.outcome_code='RETURN'))))) THEN RAISE EXCEPTION 'transfer correction predecessor differs' USING ERRCODE='23514'; END IF;
 IF k.current_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.approved_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.contract_execution_id IS DISTINCT FROM r.contract_execution_id OR k.activation_source_hash IS DISTINCT FROM r.deal_activation_digest OR k.deal_activated_at IS DISTINCT FROM r.deal_activated_at OR k.contract_termination_id IS NOT NULL THEN RAISE EXCEPTION 'transfer executed basis differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'transfer customer basis differs' USING ERRCODE='23514'; END IF;
 IF cardinality(NEW.evidence_submission_ids)<1 OR EXISTS(SELECT 1 FROM unnest(NEW.evidence_submission_ids) e(id) WHERE NOT EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.opportunity_id=NEW.opportunity_id AND v.evidence_submission_id=e.id)) THEN RAISE EXCEPTION 'transfer frozen evidence differs' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM unnest(ARRAY[NEW.client_identity_material_id,NEW.signature_archive_material_id]) material(id) WHERE NOT EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=material.id AND v.opportunity_id=NEW.opportunity_id AND v.evidence_submission_id IS NOT NULL)) THEN RAISE EXCEPTION 'transfer material basis differs' USING ERRCODE='23514'; END IF;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest OR d.action_code IS DISTINCT FROM (CASE WHEN w.stage_code='SUPPLEMENT' THEN 'RESUBMIT_TRANSFER' ELSE 'SUBMIT_TRANSFER' END) THEN RAISE EXCEPTION 'transfer submission exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_submission_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.submission' AND t.completion_fact_id=NEW.submission_id AND t.completion_fact_revision=0) THEN RAISE EXCEPTION 'transfer submission completion differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=NEW.workflow_id AND n.submission_id=NEW.submission_id AND n.target_stage_code='REVIEW_TRANSFER' AND n.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer submission successor missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_review_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; s transfer.submission%ROWTYPE; r conflict.conflict_review%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO s FROM transfer.submission WHERE tenant_id=NEW.tenant_id AND submission_id=NEW.submission_id;
 SELECT * INTO r FROM conflict.conflict_review WHERE tenant_id=NEW.tenant_id AND conflict_review_id=NEW.conflict_review_id;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF w.stage_code IS DISTINCT FROM 'REVIEW_TRANSFER' OR w.submission_id IS DISTINCT FROM NEW.submission_id OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR s.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR s.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer review responsibility differs' USING ERRCODE='23514'; END IF;
 IF r.review_type_code IS DISTINCT FROM 'PRE_TRANSFER' OR r.trigger_fact_type IS DISTINCT FROM 'transfer.submission' OR r.trigger_fact_id IS DISTINCT FROM NEW.submission_id OR r.trigger_fact_hash IS DISTINCT FROM s.body_digest OR r.scope_hash IS DISTINCT FROM NEW.scope_digest OR r.corpus_hash IS DISTINCT FROM NEW.corpus_digest OR (NEW.outcome_code='CLEAR' AND r.initial_conclusion_code IS DISTINCT FROM 'CLEAR') OR (NEW.outcome_code='NEED_INFO' AND r.initial_conclusion_code IS DISTINCT FROM 'NEED_INFO') OR (NEW.outcome_code='BLOCKED' AND r.resolution_code IS DISTINCT FROM 'BLOCKED') THEN RAISE EXCEPTION 'transfer independent conflict result differs' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.action_code IS DISTINCT FROM 'RECORD_TRANSFER_CONFLICT_REVIEW' OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'transfer review exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_review_return_item_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.review rv WHERE rv.tenant_id=NEW.tenant_id AND rv.review_id=NEW.review_id AND rv.transfer_request_id=NEW.transfer_request_id AND rv.opportunity_id=NEW.opportunity_id AND rv.outcome_code<>'CLEAR' AND rv.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer return item requires exact nonclear review' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_review_return_item_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_review_return_item__exact BEFORE INSERT ON transfer.review_return_item FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_return_item_guard();
CREATE FUNCTION transfer.fn_review_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF (NEW.outcome_code<>'CLEAR') IS DISTINCT FROM EXISTS(SELECT 1 FROM transfer.review_return_item i WHERE i.tenant_id=NEW.tenant_id AND i.review_id=NEW.review_id) THEN RAISE EXCEPTION 'transfer nonclear review requires correction items' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.review' AND t.completion_fact_id=NEW.review_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.review_id=NEW.review_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer review successor missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_review_guard(),transfer.fn_review_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_review__exact BEFORE INSERT ON transfer.review FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_review__completion AFTER INSERT ON transfer.review DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_completion_guard();
CREATE OR REPLACE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $fn$
DECLARE accepted_snapshot uuid;
BEGIN
 SELECT r.accepted_snapshot_id INTO accepted_snapshot FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'transfer snapshot requires its exact request' USING ERRCODE='23503'; END IF;
 IF accepted_snapshot IS NOT NULL AND (accepted_snapshot IS DISTINCT FROM NEW.transfer_snapshot_id OR NOT EXISTS(
  SELECT 1 FROM transfer.intake i JOIN transfer.transfer_request r ON r.tenant_id=i.tenant_id AND r.transfer_request_id=i.transfer_request_id
  WHERE i.tenant_id=NEW.tenant_id AND i.transfer_request_id=NEW.transfer_request_id AND i.snapshot_id=NEW.transfer_snapshot_id AND i.outcome_code='ACCEPT' AND i.created_in_transaction=pg_current_xact_id()
   AND r.accepted_snapshot_id=i.snapshot_id AND r.accept_decision_record_id=i.decision_record_id AND r.matter_id=i.matter_id AND r.matter_no=i.matter_no
 )) THEN RAISE EXCEPTION 'accepted transfer rejects new snapshots' USING ERRCODE='55000'; END IF;
 IF NEW.snapshot_no>1 AND NOT EXISTS(SELECT 1 FROM transfer.transfer_snapshot p WHERE p.tenant_id=NEW.tenant_id AND p.transfer_snapshot_id=NEW.predecessor_snapshot_id AND p.transfer_request_id=NEW.transfer_request_id AND p.snapshot_no+1=NEW.snapshot_no) THEN RAISE EXCEPTION 'transfer snapshot must follow the direct predecessor of the same request' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_intake_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; rv transfer.review%ROWTYPE; snap transfer.transfer_snapshot%ROWTYPE; d responsibility.decision_record%ROWTYPE; draft responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO rv FROM transfer.review WHERE tenant_id=NEW.tenant_id AND review_id=NEW.review_id;
 SELECT * INTO snap FROM transfer.transfer_snapshot WHERE tenant_id=NEW.tenant_id AND transfer_snapshot_id=NEW.snapshot_id;
 SELECT * INTO d FROM responsibility.decision_record WHERE tenant_id=NEW.tenant_id AND decision_record_id=NEW.decision_record_id;
 SELECT * INTO draft FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF w.stage_code IS DISTINCT FROM 'INTAKE' OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR w.submission_id IS DISTINCT FROM NEW.submission_id OR w.review_id IS DISTINCT FROM NEW.review_id OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR rv.outcome_code IS DISTINCT FROM 'CLEAR' OR snap.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR snap.pre_transfer_review_id IS DISTINCT FROM rv.conflict_review_id OR snap.pre_transfer_scope_hash IS DISTINCT FROM rv.scope_digest OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer intake exact cleared basis required' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.decided_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.decision_code IS DISTINCT FROM NEW.outcome_code OR d.decision_contract_code IS DISTINCT FROM 'R2_TRANSFER_INTAKE_V1' OR d.decision_subject_type IS DISTINCT FROM 'transfer.transfer_snapshot' OR d.decision_subject_id IS DISTINCT FROM NEW.snapshot_id OR d.decision_subject_hash IS DISTINCT FROM snap.snapshot_digest THEN RAISE EXCEPTION 'transfer intake decision differs' USING ERRCODE='23514'; END IF;
 IF draft.task_occurrence_id IS DISTINCT FROM w.task_id OR draft.action_code IS DISTINCT FROM 'RECORD_TRANSFER_INTAKE' OR draft.state IS DISTINCT FROM 'CONFIRMED' OR draft.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR draft.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'transfer intake exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_intake_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.intake' AND t.completion_fact_id=NEW.intake_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.intake_id=NEW.intake_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer intake successor missing' USING ERRCODE='23514'; END IF;
 IF NEW.outcome_code='RETURN' AND (EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.accepted_snapshot_id IS NOT NULL) OR NOT EXISTS(SELECT 1 FROM transfer.transfer_return_item i WHERE i.tenant_id=NEW.tenant_id AND i.reviewed_snapshot_id=NEW.snapshot_id AND i.return_decision_record_id=NEW.decision_record_id AND i.requirement_code=NEW.requirement_code)) THEN RAISE EXCEPTION 'transfer intake return requires exact correction items' USING ERRCODE='23514'; END IF;
 IF NEW.outcome_code='ACCEPT' AND NOT EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.accepted_snapshot_id=NEW.snapshot_id AND r.accept_decision_record_id=NEW.decision_record_id AND r.matter_id=NEW.matter_id AND r.matter_no=NEW.matter_no) THEN RAISE EXCEPTION 'transfer accepted case identity differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_intake_guard(),transfer.fn_intake_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_intake__exact BEFORE INSERT ON transfer.intake FOR EACH ROW EXECUTE FUNCTION transfer.fn_intake_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_intake__completion AFTER INSERT ON transfer.intake DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_intake_completion_guard();
CREATE FUNCTION transfer.fn_classification_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; i transfer.intake%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO i FROM transfer.intake WHERE tenant_id=NEW.tenant_id AND intake_id=NEW.intake_id;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF NEW.previous_classification_id IS DISTINCT FROM w.classification_id OR w.stage_code IS DISTINCT FROM 'CLASSIFY' OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR w.intake_id IS DISTINCT FROM NEW.intake_id OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR i.outcome_code IS DISTINCT FROM 'ACCEPT' OR i.matter_id IS DISTINCT FROM NEW.matter_id OR NOT EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.matter_id=NEW.matter_id AND r.accepted_snapshot_id=i.snapshot_id) OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'classification requires accepted exact case' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM identity.appointment a JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE a.tenant_id=NEW.tenant_id AND a.appointment_id=NEW.recipient_appointment_id AND a.state='ACTIVE' AND a.effective_from<=NEW.created_at AND (a.effective_until IS NULL OR a.effective_until>NEW.created_at) AND p.state='ACTIVE' AND p.principal_kind='HUMAN') THEN RAISE EXCEPTION 'classification recipient unavailable' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.action_code IS DISTINCT FROM 'CLASSIFY_MATTER' OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'classification exact confirmed draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_classification_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.classification' AND t.completion_fact_id=NEW.classification_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.stage_code='COMPLETE' AND w.classification_id=NEW.classification_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'classification completion missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_classification_guard(),transfer.fn_classification_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_classification__exact BEFORE INSERT ON transfer.classification FOR EACH ROW EXECUTE FUNCTION transfer.fn_classification_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_classification__completion AFTER INSERT ON transfer.classification DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_classification_completion_guard();
CREATE FUNCTION transfer.fn_reclassification_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.stage_code='CLASSIFY' AND EXISTS(SELECT 1 FROM transfer.workflow p WHERE p.tenant_id=NEW.tenant_id AND p.workflow_id=NEW.previous_workflow_id AND p.stage_code='COMPLETE') AND NOT EXISTS(SELECT 1 FROM transfer.workflow n JOIN transfer.classification cl ON cl.tenant_id=n.tenant_id AND cl.classification_id=n.classification_id WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=NEW.workflow_id AND n.stage_code='COMPLETE' AND n.created_in_transaction=pg_current_xact_id() AND cl.previous_classification_id=NEW.classification_id AND cl.workflow_id=NEW.workflow_id) THEN RAISE EXCEPTION 'explicit reclassification must close atomically' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_reclassification_completion_guard() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_transfer_workflow__reclassification AFTER INSERT ON transfer.workflow DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_reclassification_completion_guard();
REVOKE ALL ON FUNCTION transfer.fn_workflow_guard(),transfer.fn_submission_guard(),transfer.fn_submission_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_workflow__exact BEFORE INSERT ON transfer.workflow FOR EACH ROW EXECUTE FUNCTION transfer.fn_workflow_guard();
CREATE TRIGGER trg_transfer_submission__exact BEFORE INSERT ON transfer.submission FOR EACH ROW EXECUTE FUNCTION transfer.fn_submission_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_submission__completion AFTER INSERT ON transfer.submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_submission_completion_guard();
DO $v1050$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v19',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v18';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1050 requires 52-plus-2-r2-v18' USING ERRCODE='55000'; END IF;
END;$v1050$;
