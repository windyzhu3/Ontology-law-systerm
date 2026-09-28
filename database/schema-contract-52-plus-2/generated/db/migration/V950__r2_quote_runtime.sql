CREATE TABLE opportunity.quote_approval_policy (
    tenant_id uuid NOT NULL,
    quote_approval_policy_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    organization_unit_id uuid NOT NULL,
    policy_code varchar(64) NOT NULL,
    policy_version bigint NOT NULL,
    mode varchar(64) NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_approval_policy PRIMARY KEY (tenant_id, quote_approval_policy_id),
    CONSTRAINT ck_quote_approval_policy__revision CHECK (revision=0),
    CONSTRAINT ck_quote_approval_policy__values CHECK (policy_code='R2_QUOTE_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode IN ('REQUIRE_APPROVAL','SELF_AUTHORIZED')),
    CONSTRAINT uq_quote_approval_policy__version UNIQUE (tenant_id, organization_unit_id, policy_code, policy_version)
);

COMMENT ON TABLE opportunity.quote_approval_policy IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_approval_policy ON opportunity.quote_approval_policy IS '主键：在租户内唯一标识一条quote_approval_policy记录。';
COMMENT ON INDEX opportunity.pk_quote_approval_policy IS '主键：在租户内唯一标识一条quote_approval_policy记录。';
COMMENT ON COLUMN opportunity.quote_approval_policy.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_approval_policy.quote_approval_policy_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_approval_policy.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_approval_policy.organization_unit_id IS '明确适用组织。';
COMMENT ON COLUMN opportunity.quote_approval_policy.policy_code IS '具名审批策略。';
COMMENT ON COLUMN opportunity.quote_approval_policy.policy_version IS '策略版本。';
COMMENT ON COLUMN opportunity.quote_approval_policy.mode IS '审批方式。';
COMMENT ON COLUMN opportunity.quote_approval_policy.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.quote_approval_policy.created_at IS '配置时间。';
COMMENT ON CONSTRAINT ck_quote_approval_policy__revision ON opportunity.quote_approval_policy IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_quote_approval_policy__values ON opportunity.quote_approval_policy IS '明确策略及授权模式。';
COMMENT ON CONSTRAINT uq_quote_approval_policy__version ON opportunity.quote_approval_policy IS '同组织策略版本唯一。';
COMMENT ON INDEX opportunity.uq_quote_approval_policy__version IS '同组织策略版本唯一。';
CREATE TABLE opportunity.quote_approval_policy_signer (
    tenant_id uuid NOT NULL,
    quote_approval_policy_signer_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    policy_id uuid NOT NULL,
    appointment_id uuid NOT NULL,
    CONSTRAINT pk_quote_approval_policy_signer PRIMARY KEY (tenant_id, quote_approval_policy_signer_id),
    CONSTRAINT ck_quote_approval_policy_signer__revision CHECK (revision=0),
    CONSTRAINT uq_quote_approval_policy_signer__member UNIQUE (tenant_id, policy_id, appointment_id)
);

COMMENT ON TABLE opportunity.quote_approval_policy_signer IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_approval_policy_signer ON opportunity.quote_approval_policy_signer IS '主键：在租户内唯一标识一条quote_approval_policy_signer记录。';
COMMENT ON INDEX opportunity.pk_quote_approval_policy_signer IS '主键：在租户内唯一标识一条quote_approval_policy_signer记录。';
COMMENT ON COLUMN opportunity.quote_approval_policy_signer.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_approval_policy_signer.quote_approval_policy_signer_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_approval_policy_signer.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_approval_policy_signer.policy_id IS '准确配置策略。';
COMMENT ON COLUMN opportunity.quote_approval_policy_signer.appointment_id IS '明确获授权的审批或权限内任职。';
COMMENT ON CONSTRAINT ck_quote_approval_policy_signer__revision ON opportunity.quote_approval_policy_signer IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_approval_policy_signer__member ON opportunity.quote_approval_policy_signer IS '配置成员不重复。';
COMMENT ON INDEX opportunity.uq_quote_approval_policy_signer__member IS '配置成员不重复。';
CREATE TABLE opportunity.quote_approval_request (
    tenant_id uuid NOT NULL,
    quote_approval_request_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    quote_revision_id uuid NOT NULL,
    policy_id uuid NOT NULL,
    policy_code varchar(64) NOT NULL,
    policy_version bigint NOT NULL,
    requested_by uuid NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_approval_request PRIMARY KEY (tenant_id, quote_approval_request_id),
    CONSTRAINT ck_quote_approval_request__revision CHECK (revision=0),
    CONSTRAINT uq_quote_approval_request__quote UNIQUE (tenant_id, quote_revision_id)
);

COMMENT ON TABLE opportunity.quote_approval_request IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_approval_request ON opportunity.quote_approval_request IS '主键：在租户内唯一标识一条quote_approval_request记录。';
COMMENT ON INDEX opportunity.pk_quote_approval_request IS '主键：在租户内唯一标识一条quote_approval_request记录。';
COMMENT ON COLUMN opportunity.quote_approval_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_approval_request.quote_approval_request_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_approval_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_approval_request.quote_revision_id IS '准确报价。';
COMMENT ON COLUMN opportunity.quote_approval_request.policy_id IS '准确策略版本。';
COMMENT ON COLUMN opportunity.quote_approval_request.policy_code IS '策略代码。';
COMMENT ON COLUMN opportunity.quote_approval_request.policy_version IS '策略版本。';
COMMENT ON COLUMN opportunity.quote_approval_request.requested_by IS '提交任职。';
COMMENT ON COLUMN opportunity.quote_approval_request.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.quote_approval_request.created_at IS '提交时间。';
COMMENT ON CONSTRAINT ck_quote_approval_request__revision ON opportunity.quote_approval_request IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_approval_request__quote ON opportunity.quote_approval_request IS '准确报价仅一次审批请求。';
COMMENT ON INDEX opportunity.uq_quote_approval_request__quote IS '准确报价仅一次审批请求。';
CREATE TABLE opportunity.quote_approval_member (
    tenant_id uuid NOT NULL,
    quote_approval_member_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    request_id uuid NOT NULL,
    appointment_id uuid NOT NULL,
    task_id uuid NOT NULL,
    CONSTRAINT pk_quote_approval_member PRIMARY KEY (tenant_id, quote_approval_member_id),
    CONSTRAINT ck_quote_approval_member__revision CHECK (revision=0),
    CONSTRAINT uq_quote_approval_member__appointment UNIQUE (tenant_id, request_id, appointment_id),
    CONSTRAINT uq_quote_approval_member__task UNIQUE (tenant_id, task_id)
);

COMMENT ON TABLE opportunity.quote_approval_member IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_approval_member ON opportunity.quote_approval_member IS '主键：在租户内唯一标识一条quote_approval_member记录。';
COMMENT ON INDEX opportunity.pk_quote_approval_member IS '主键：在租户内唯一标识一条quote_approval_member记录。';
COMMENT ON COLUMN opportunity.quote_approval_member.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_approval_member.quote_approval_member_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_approval_member.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_approval_member.request_id IS '准确审批请求。';
COMMENT ON COLUMN opportunity.quote_approval_member.appointment_id IS '明确审批人。';
COMMENT ON COLUMN opportunity.quote_approval_member.task_id IS '准确审批待办。';
COMMENT ON CONSTRAINT ck_quote_approval_member__revision ON opportunity.quote_approval_member IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_approval_member__appointment ON opportunity.quote_approval_member IS '请求成员唯一。';
COMMENT ON INDEX opportunity.uq_quote_approval_member__appointment IS '请求成员唯一。';
COMMENT ON CONSTRAINT uq_quote_approval_member__task ON opportunity.quote_approval_member IS '待办只属于一个成员。';
COMMENT ON INDEX opportunity.uq_quote_approval_member__task IS '待办只属于一个成员。';
CREATE TABLE opportunity.quote_approval_decision (
    tenant_id uuid NOT NULL,
    quote_approval_decision_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    member_id uuid NOT NULL,
    decision varchar(64) NOT NULL,
    reason_ciphertext bytea NOT NULL,
    reason_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_approval_decision PRIMARY KEY (tenant_id, quote_approval_decision_id),
    CONSTRAINT ck_quote_approval_decision__revision CHECK (revision=0),
    CONSTRAINT uq_quote_approval_decision__member UNIQUE (tenant_id, member_id),
    CONSTRAINT ck_quote_approval_decision__values CHECK (decision IN ('APPROVED','RETURNED') AND octet_length(reason_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_quote_approval_decision__reason_digest_length CHECK (octet_length(reason_digest) = 32)
);

COMMENT ON TABLE opportunity.quote_approval_decision IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_approval_decision ON opportunity.quote_approval_decision IS '主键：在租户内唯一标识一条quote_approval_decision记录。';
COMMENT ON INDEX opportunity.pk_quote_approval_decision IS '主键：在租户内唯一标识一条quote_approval_decision记录。';
COMMENT ON COLUMN opportunity.quote_approval_decision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_approval_decision.quote_approval_decision_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_approval_decision.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_approval_decision.member_id IS '准确审批成员。';
COMMENT ON COLUMN opportunity.quote_approval_decision.decision IS '批准或退回。';
COMMENT ON COLUMN opportunity.quote_approval_decision.reason_ciphertext IS '审批说明密文。';
COMMENT ON COLUMN opportunity.quote_approval_decision.reason_digest IS '审批说明摘要。';
COMMENT ON COLUMN opportunity.quote_approval_decision.created_at IS '决定时间。';
COMMENT ON CONSTRAINT ck_quote_approval_decision__revision ON opportunity.quote_approval_decision IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_approval_decision__member ON opportunity.quote_approval_decision IS '每个审批成员一次决定。';
COMMENT ON INDEX opportunity.uq_quote_approval_decision__member IS '每个审批成员一次决定。';
COMMENT ON CONSTRAINT ck_quote_approval_decision__values ON opportunity.quote_approval_decision IS '决定及有界密文。';
COMMENT ON CONSTRAINT ck_quote_approval_decision__reason_digest_length ON opportunity.quote_approval_decision IS '摘要格式：reason_digest必须保存32字节的规范二进制值。';
CREATE TABLE opportunity.quote_manual_delivery (
    tenant_id uuid NOT NULL,
    quote_manual_delivery_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    quote_revision_id uuid NOT NULL,
    material_version_id uuid NOT NULL,
    recipient_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    channel varchar(64) NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    recorded_by uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_manual_delivery PRIMARY KEY (tenant_id, quote_manual_delivery_id),
    CONSTRAINT ck_quote_manual_delivery__revision CHECK (revision=0),
    CONSTRAINT ck_quote_manual_delivery__values CHECK (octet_length(recipient_ciphertext) BETWEEN 29 AND 131072 AND channel ~ '^[A-Z][A-Z0-9_]{0,63}$' AND occurred_at<=created_at),
    CONSTRAINT ck_quote_manual_delivery__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE opportunity.quote_manual_delivery IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_manual_delivery ON opportunity.quote_manual_delivery IS '主键：在租户内唯一标识一条quote_manual_delivery记录。';
COMMENT ON INDEX opportunity.pk_quote_manual_delivery IS '主键：在租户内唯一标识一条quote_manual_delivery记录。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.quote_manual_delivery_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.quote_revision_id IS '准确报价。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.material_version_id IS 'T06准确证据版本。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.recipient_ciphertext IS '实际接收人受保护内容。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.body_digest IS '交付内容摘要。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.channel IS '实际交付方式。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.occurred_at IS '实际发生时间。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.recorded_by IS '确认交付任职。';
COMMENT ON COLUMN opportunity.quote_manual_delivery.created_at IS '记录时间。';
COMMENT ON CONSTRAINT ck_quote_manual_delivery__revision ON opportunity.quote_manual_delivery IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_quote_manual_delivery__values ON opportunity.quote_manual_delivery IS '必要交付信息及时间。';
COMMENT ON CONSTRAINT ck_quote_manual_delivery__body_digest_length ON opportunity.quote_manual_delivery IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE opportunity.quote_response_basis (
    tenant_id uuid NOT NULL,
    quote_response_basis_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    quote_response_id uuid NOT NULL,
    material_version_id uuid NOT NULL,
    next_check_at timestamptz(6),
    CONSTRAINT pk_quote_response_basis PRIMARY KEY (tenant_id, quote_response_basis_id),
    CONSTRAINT ck_quote_response_basis__revision CHECK (revision=0),
    CONSTRAINT uq_quote_response_basis__response UNIQUE (tenant_id, quote_response_id),
    CONSTRAINT ck_quote_response_basis__identity CHECK (quote_response_basis_id=quote_response_id)
);

COMMENT ON TABLE opportunity.quote_response_basis IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_response_basis ON opportunity.quote_response_basis IS '主键：在租户内唯一标识一条quote_response_basis记录。';
COMMENT ON INDEX opportunity.pk_quote_response_basis IS '主键：在租户内唯一标识一条quote_response_basis记录。';
COMMENT ON COLUMN opportunity.quote_response_basis.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_response_basis.quote_response_basis_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_response_basis.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_response_basis.quote_response_id IS '既有准确客户回复。';
COMMENT ON COLUMN opportunity.quote_response_basis.material_version_id IS 'T06准确证明版本。';
COMMENT ON COLUMN opportunity.quote_response_basis.next_check_at IS '暂不接受或不明确时下一行动。';
COMMENT ON CONSTRAINT ck_quote_response_basis__revision ON opportunity.quote_response_basis IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_response_basis__response ON opportunity.quote_response_basis IS '回复唯一补充依据。';
COMMENT ON INDEX opportunity.uq_quote_response_basis__response IS '回复唯一补充依据。';
COMMENT ON CONSTRAINT ck_quote_response_basis__identity ON opportunity.quote_response_basis IS '与既有回复身份一致。';
CREATE TABLE opportunity.contract_preparation_source (
    tenant_id uuid NOT NULL,
    contract_preparation_source_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    quote_response_id uuid NOT NULL,
    source_kind varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_preparation_source PRIMARY KEY (tenant_id, contract_preparation_source_id),
    CONSTRAINT ck_contract_preparation_source__revision CHECK (revision=0),
    CONSTRAINT uq_contract_preparation_source__response UNIQUE (tenant_id, quote_response_id),
    CONSTRAINT ck_contract_preparation_source__kind CHECK (source_kind='ACCEPTED_QUOTE')
);

COMMENT ON TABLE opportunity.contract_preparation_source IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_contract_preparation_source ON opportunity.contract_preparation_source IS '主键：在租户内唯一标识一条contract_preparation_source记录。';
COMMENT ON INDEX opportunity.pk_contract_preparation_source IS '主键：在租户内唯一标识一条contract_preparation_source记录。';
COMMENT ON COLUMN opportunity.contract_preparation_source.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.contract_preparation_source.contract_preparation_source_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.contract_preparation_source.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.contract_preparation_source.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.contract_preparation_source.quote_response_id IS '准确接受回复。';
COMMENT ON COLUMN opportunity.contract_preparation_source.source_kind IS '本阶段只允许报价接受来源。';
COMMENT ON COLUMN opportunity.contract_preparation_source.created_at IS '形成时间。';
COMMENT ON CONSTRAINT ck_contract_preparation_source__revision ON opportunity.contract_preparation_source IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_contract_preparation_source__response ON opportunity.contract_preparation_source IS '接受来源唯一。';
COMMENT ON INDEX opportunity.uq_contract_preparation_source__response IS '接受来源唯一。';
COMMENT ON CONSTRAINT ck_contract_preparation_source__kind ON opportunity.contract_preparation_source IS '不伪造直接授权来源。';
CREATE TABLE opportunity.quote_workflow (
    tenant_id uuid NOT NULL,
    quote_workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    previous_workflow_id uuid,
    stage varchar(64) NOT NULL,
    owner_appointment_id uuid NOT NULL,
    task_id uuid,
    prior_task_id uuid,
    next_check_at timestamptz(6),
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_workflow PRIMARY KEY (tenant_id, quote_workflow_id),
    CONSTRAINT ck_quote_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_quote_workflow__previous UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_quote_workflow__stage CHECK (stage IN ('PREPARE','SUBMIT_APPROVAL','AWAIT_APPROVAL','DELIVER','AWAIT_REPLY','FOLLOW_UP','CLARIFY_REPLY','SALES_DISPOSITION','ACCEPTED','RETURNED','OWNER_EXCEPTION'))
);

COMMENT ON TABLE opportunity.quote_workflow IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_workflow ON opportunity.quote_workflow IS '主键：在租户内唯一标识一条quote_workflow记录。';
COMMENT ON INDEX opportunity.pk_quote_workflow IS '主键：在租户内唯一标识一条quote_workflow记录。';
COMMENT ON COLUMN opportunity.quote_workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_workflow.quote_workflow_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_workflow.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.quote_workflow.quote_revision_id IS '准确报价。';
COMMENT ON COLUMN opportunity.quote_workflow.previous_workflow_id IS '直接前一工作流事实。';
COMMENT ON COLUMN opportunity.quote_workflow.stage IS '有界业务阶段。';
COMMENT ON COLUMN opportunity.quote_workflow.owner_appointment_id IS '下一责任任职。';
COMMENT ON COLUMN opportunity.quote_workflow.task_id IS '对应已有待办。';
COMMENT ON COLUMN opportunity.quote_workflow.prior_task_id IS '移交的原跟进待办。';
COMMENT ON COLUMN opportunity.quote_workflow.next_check_at IS '约定下一行动。';
COMMENT ON COLUMN opportunity.quote_workflow.created_at IS '形成时间。';
COMMENT ON CONSTRAINT ck_quote_workflow__revision ON opportunity.quote_workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_workflow__previous ON opportunity.quote_workflow IS '工作流事实不分叉。';
COMMENT ON INDEX opportunity.uq_quote_workflow__previous IS '工作流事实不分叉。';
COMMENT ON CONSTRAINT ck_quote_workflow__stage ON opportunity.quote_workflow IS '报价业务阶段域。';
ALTER TABLE opportunity.quote_approval_policy
    ADD CONSTRAINT fk_quote_approval_policy__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_policy__tenant ON opportunity.quote_approval_policy IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_approval_policy
    ADD CONSTRAINT fk_quote_approval_policy__organization_unit_id
    FOREIGN KEY (tenant_id, organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_policy__organization_unit_id ON opportunity.quote_approval_policy IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_approval_policy__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_approval_policy FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_approval_policy__mutation_guard ON opportunity.quote_approval_policy IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_approval_policy FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT ON opportunity.quote_approval_policy TO ${app_command_role};
GRANT SELECT ON opportunity.quote_approval_policy TO ${app_query_role};
ALTER TABLE opportunity.quote_approval_policy_signer
    ADD CONSTRAINT fk_quote_approval_policy_signer__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_policy_signer__tenant ON opportunity.quote_approval_policy_signer IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_approval_policy_signer
    ADD CONSTRAINT fk_quote_approval_policy_signer__policy_id
    FOREIGN KEY (tenant_id, policy_id)
    REFERENCES opportunity.quote_approval_policy (tenant_id, quote_approval_policy_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_policy_signer__policy_id ON opportunity.quote_approval_policy_signer IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_approval_policy_signer
    ADD CONSTRAINT fk_quote_approval_policy_signer__appointment_id
    FOREIGN KEY (tenant_id, appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_policy_signer__appointment_id ON opportunity.quote_approval_policy_signer IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_approval_policy_signer__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_approval_policy_signer FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_approval_policy_signer__mutation_guard ON opportunity.quote_approval_policy_signer IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_approval_policy_signer FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT ON opportunity.quote_approval_policy_signer TO ${app_command_role};
GRANT SELECT ON opportunity.quote_approval_policy_signer TO ${app_query_role};
ALTER TABLE opportunity.quote_approval_request
    ADD CONSTRAINT fk_quote_approval_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_request__tenant ON opportunity.quote_approval_request IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_approval_request
    ADD CONSTRAINT fk_quote_approval_request__quote_revision_id
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_request__quote_revision_id ON opportunity.quote_approval_request IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_approval_request
    ADD CONSTRAINT fk_quote_approval_request__policy_id
    FOREIGN KEY (tenant_id, policy_id)
    REFERENCES opportunity.quote_approval_policy (tenant_id, quote_approval_policy_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_request__policy_id ON opportunity.quote_approval_request IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_approval_request
    ADD CONSTRAINT fk_quote_approval_request__requested_by
    FOREIGN KEY (tenant_id, requested_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_request__requested_by ON opportunity.quote_approval_request IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_approval_request__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_approval_request FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_approval_request__mutation_guard ON opportunity.quote_approval_request IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_approval_request FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_approval_request TO ${app_command_role};
GRANT SELECT ON opportunity.quote_approval_request TO ${app_query_role};
ALTER TABLE opportunity.quote_approval_member
    ADD CONSTRAINT fk_quote_approval_member__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_member__tenant ON opportunity.quote_approval_member IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_approval_member
    ADD CONSTRAINT fk_quote_approval_member__request_id
    FOREIGN KEY (tenant_id, request_id)
    REFERENCES opportunity.quote_approval_request (tenant_id, quote_approval_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_member__request_id ON opportunity.quote_approval_member IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_approval_member
    ADD CONSTRAINT fk_quote_approval_member__appointment_id
    FOREIGN KEY (tenant_id, appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_member__appointment_id ON opportunity.quote_approval_member IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_approval_member
    ADD CONSTRAINT fk_quote_approval_member__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_member__task_id ON opportunity.quote_approval_member IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_approval_member__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_approval_member FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_approval_member__mutation_guard ON opportunity.quote_approval_member IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_approval_member FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_approval_member TO ${app_command_role};
GRANT SELECT ON opportunity.quote_approval_member TO ${app_query_role};
ALTER TABLE opportunity.quote_approval_decision
    ADD CONSTRAINT fk_quote_approval_decision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_decision__tenant ON opportunity.quote_approval_decision IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_approval_decision
    ADD CONSTRAINT fk_quote_approval_decision__member_id
    FOREIGN KEY (tenant_id, member_id)
    REFERENCES opportunity.quote_approval_member (tenant_id, quote_approval_member_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_approval_decision__member_id ON opportunity.quote_approval_decision IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_approval_decision__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_approval_decision FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_approval_decision__mutation_guard ON opportunity.quote_approval_decision IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_approval_decision FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_approval_decision TO ${app_command_role};
GRANT SELECT ON opportunity.quote_approval_decision TO ${app_query_role};
ALTER TABLE opportunity.quote_manual_delivery
    ADD CONSTRAINT fk_quote_manual_delivery__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_manual_delivery__tenant ON opportunity.quote_manual_delivery IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_manual_delivery
    ADD CONSTRAINT fk_quote_manual_delivery__quote_revision_id
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_manual_delivery__quote_revision_id ON opportunity.quote_manual_delivery IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_manual_delivery
    ADD CONSTRAINT fk_quote_manual_delivery__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_manual_delivery__material_version_id ON opportunity.quote_manual_delivery IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_manual_delivery
    ADD CONSTRAINT fk_quote_manual_delivery__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_manual_delivery__recorded_by ON opportunity.quote_manual_delivery IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_manual_delivery__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_manual_delivery FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_manual_delivery__mutation_guard ON opportunity.quote_manual_delivery IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_manual_delivery FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_manual_delivery TO ${app_command_role};
GRANT SELECT ON opportunity.quote_manual_delivery TO ${app_query_role};
ALTER TABLE opportunity.quote_response_basis
    ADD CONSTRAINT fk_quote_response_basis__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response_basis__tenant ON opportunity.quote_response_basis IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_response_basis
    ADD CONSTRAINT fk_quote_response_basis__quote_response_id
    FOREIGN KEY (tenant_id, quote_response_id)
    REFERENCES opportunity.quote_response (tenant_id, quote_response_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response_basis__quote_response_id ON opportunity.quote_response_basis IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_response_basis
    ADD CONSTRAINT fk_quote_response_basis__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response_basis__material_version_id ON opportunity.quote_response_basis IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_response_basis__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_response_basis FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_response_basis__mutation_guard ON opportunity.quote_response_basis IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_response_basis FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_response_basis TO ${app_command_role};
GRANT SELECT ON opportunity.quote_response_basis TO ${app_query_role};
ALTER TABLE opportunity.contract_preparation_source
    ADD CONSTRAINT fk_contract_preparation_source__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_preparation_source__tenant ON opportunity.contract_preparation_source IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.contract_preparation_source
    ADD CONSTRAINT fk_contract_preparation_source__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_preparation_source__opportunity_id ON opportunity.contract_preparation_source IS '同租户准确事实引用。';
ALTER TABLE opportunity.contract_preparation_source
    ADD CONSTRAINT fk_contract_preparation_source__quote_response_id
    FOREIGN KEY (tenant_id, quote_response_id)
    REFERENCES opportunity.quote_response (tenant_id, quote_response_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_preparation_source__quote_response_id ON opportunity.contract_preparation_source IS '同租户准确事实引用。';
CREATE TRIGGER trg_contract_preparation_source__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.contract_preparation_source FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_contract_preparation_source__mutation_guard ON opportunity.contract_preparation_source IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.contract_preparation_source FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.contract_preparation_source TO ${app_command_role};
GRANT SELECT ON opportunity.contract_preparation_source TO ${app_query_role};
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__tenant ON opportunity.quote_workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__opportunity_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__quote_revision_id
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__quote_revision_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES opportunity.quote_workflow (tenant_id, quote_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__previous_workflow_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__owner_appointment_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__task_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow
    ADD CONSTRAINT fk_quote_workflow__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_workflow__prior_task_id ON opportunity.quote_workflow IS '同租户准确事实引用。';
CREATE TRIGGER trg_quote_workflow__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_workflow__mutation_guard ON opportunity.quote_workflow IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.quote_workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_workflow TO ${app_command_role};
GRANT SELECT ON opportunity.quote_workflow TO ${app_query_role};

CREATE FUNCTION opportunity.fn_check_quote_runtime() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE q opportunity.quote_revision%ROWTYPE; o opportunity.opportunity%ROWTYPE; p opportunity.quote_approval_policy%ROWTYPE; r opportunity.quote_approval_request%ROWTYPE; m opportunity.quote_approval_member%ROWTYPE; prior uuid;
BEGIN
 IF TG_TABLE_NAME='quote_approval_policy_signer' THEN
  SELECT * INTO p FROM opportunity.quote_approval_policy WHERE tenant_id=NEW.tenant_id AND quote_approval_policy_id=NEW.policy_id;
  IF p.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'quote policy signer set frozen' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_TABLE_NAME='quote_approval_member' THEN
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_approval_request_id=NEW.request_id;
  IF r.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer s WHERE s.tenant_id=NEW.tenant_id AND s.policy_id=r.policy_id AND s.appointment_id=NEW.appointment_id) THEN RAISE EXCEPTION 'quote approval member not configured or frozen' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t JOIN opportunity.quote_revision v ON v.tenant_id=t.tenant_id AND v.opportunity_id=t.subject_id WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.task_id AND t.subject_type='opportunity.opportunity' AND v.quote_revision_id=r.quote_revision_id AND t.owner_appointment_id=NEW.appointment_id) THEN RAISE EXCEPTION 'quote approval task differs' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_TABLE_NAME='quote_approval_decision' THEN
  SELECT * INTO m FROM opportunity.quote_approval_member WHERE tenant_id=NEW.tenant_id AND quote_approval_member_id=NEW.member_id;
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_approval_request_id=m.request_id;
  SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND quote_revision_id=r.quote_revision_id;
 ELSE
  SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND quote_revision_id=NEW.quote_revision_id;
 END IF;
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=q.tenant_id AND opportunity_id=q.opportunity_id FOR UPDATE;
 IF q.quote_revision_id IS NULL OR o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.current_quote_revision_id IS DISTINCT FROM q.quote_revision_id OR (TG_TABLE_NAME<>'quote_workflow' AND q.valid_until<=clock_timestamp()) THEN RAISE EXCEPTION 'quote runtime current source differs' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='quote_approval_request' THEN
  SELECT * INTO p FROM opportunity.quote_approval_policy WHERE tenant_id=NEW.tenant_id AND quote_approval_policy_id=NEW.policy_id;
  IF p.quote_approval_policy_id IS NULL OR p.policy_code<>NEW.policy_code OR p.policy_version<>NEW.policy_version OR NOT EXISTS(SELECT 1 FROM identity.appointment a WHERE a.tenant_id=NEW.tenant_id AND a.appointment_id=q.created_by_appointment_id AND a.organization_unit_id=p.organization_unit_id) THEN RAISE EXCEPTION 'quote approval policy differs' USING ERRCODE='23514'; END IF;
  NEW.created_in_transaction=pg_current_xact_id();
 ELSIF TG_TABLE_NAME='quote_manual_delivery' THEN
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.evidence_binding b ON b.tenant_id=v.tenant_id AND b.evidence_binding_id=v.evidence_binding_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.material_version_id AND v.opportunity_id=o.opportunity_id AND b.revoked_at IS NULL) THEN RAISE EXCEPTION 'quote manual delivery evidence differs' USING ERRCODE='23514'; END IF;
  IF NEW.occurred_at<q.created_at OR NEW.occurred_at>=q.valid_until THEN RAISE EXCEPTION 'quote delivery time differs' USING ERRCODE='23514'; END IF;
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_revision_id=q.quote_revision_id;
  IF r.quote_approval_request_id IS NOT NULL THEN
   IF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_member WHERE tenant_id=NEW.tenant_id AND request_id=r.quote_approval_request_id) OR EXISTS(SELECT 1 FROM opportunity.quote_approval_member member_row WHERE member_row.tenant_id=NEW.tenant_id AND member_row.request_id=r.quote_approval_request_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_decision d WHERE d.tenant_id=member_row.tenant_id AND d.member_id=member_row.quote_approval_member_id AND d.decision='APPROVED')) THEN RAISE EXCEPTION 'quote approval set incomplete' USING ERRCODE='23514'; END IF;
  ELSIF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy policy_row JOIN opportunity.quote_approval_policy_signer s ON s.tenant_id=policy_row.tenant_id AND s.policy_id=policy_row.quote_approval_policy_id JOIN identity.appointment a ON a.tenant_id=s.tenant_id AND a.appointment_id=s.appointment_id WHERE policy_row.tenant_id=NEW.tenant_id AND s.appointment_id=NEW.recorded_by AND policy_row.organization_unit_id=a.organization_unit_id AND policy_row.mode='SELF_AUTHORIZED' AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy n WHERE n.tenant_id=policy_row.tenant_id AND n.organization_unit_id=policy_row.organization_unit_id AND n.policy_code=policy_row.policy_code AND n.policy_version>policy_row.policy_version)) THEN RAISE EXCEPTION 'explicit quote authorization required' USING ERRCODE='23514';
  END IF;
 ELSIF TG_TABLE_NAME='quote_workflow' THEN
  SELECT w.quote_workflow_id INTO prior FROM opportunity.quote_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id);
  IF NEW.opportunity_id<>o.opportunity_id OR NEW.previous_workflow_id IS DISTINCT FROM prior THEN RAISE EXCEPTION 'quote workflow predecessor differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_runtime() IS '锁定商机并核对不可变审批、交付和工作流依据。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_runtime() FROM PUBLIC;
CREATE TRIGGER trg_quote_approval_policy_signer__source BEFORE INSERT ON opportunity.quote_approval_policy_signer FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_approval_policy_signer__source ON opportunity.quote_approval_policy_signer IS '准确报价闭环来源守卫。';
CREATE TRIGGER trg_quote_approval_request__source BEFORE INSERT ON opportunity.quote_approval_request FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_approval_request__source ON opportunity.quote_approval_request IS '准确报价闭环来源守卫。';
CREATE TRIGGER trg_quote_approval_member__source BEFORE INSERT ON opportunity.quote_approval_member FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_approval_member__source ON opportunity.quote_approval_member IS '准确报价闭环来源守卫。';
CREATE TRIGGER trg_quote_approval_decision__source BEFORE INSERT ON opportunity.quote_approval_decision FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_approval_decision__source ON opportunity.quote_approval_decision IS '准确报价闭环来源守卫。';
CREATE TRIGGER trg_quote_manual_delivery__source BEFORE INSERT ON opportunity.quote_manual_delivery FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_manual_delivery__source ON opportunity.quote_manual_delivery IS '准确报价闭环来源守卫。';
CREATE TRIGGER trg_quote_workflow__source BEFORE INSERT ON opportunity.quote_workflow FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_quote_workflow__source ON opportunity.quote_workflow IS '准确报价闭环来源守卫。';

CREATE FUNCTION opportunity.fn_check_quote_response_source() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r opportunity.quote_response%ROWTYPE; i opportunity.quote_issue%ROWTYPE; q opportunity.quote_revision%ROWTYPE;
BEGIN
 SELECT * INTO r FROM opportunity.quote_response WHERE tenant_id=NEW.tenant_id AND quote_response_id=NEW.quote_response_id;
 SELECT * INTO i FROM opportunity.quote_issue WHERE tenant_id=r.tenant_id AND quote_issue_id=r.quote_issue_id;
 SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=i.tenant_id AND quote_revision_id=i.quote_revision_id;
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=q.tenant_id AND opportunity_id=q.opportunity_id FOR UPDATE;
 IF q.quote_revision_id IS NULL OR i.issue_status_code<>'ACTIVE' OR r.received_at<i.issued_at OR r.received_at>r.created_at OR (r.response_code='ACCEPTED' AND r.received_at>=q.valid_until) THEN RAISE EXCEPTION 'quote response exact issue or time differs' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='quote_response_basis' THEN
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.evidence_binding b ON b.tenant_id=v.tenant_id AND b.evidence_binding_id=v.evidence_binding_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.material_version_id AND v.opportunity_id=q.opportunity_id AND v.evidence_submission_id=r.evidence_submission_id AND b.revoked_at IS NULL) THEN RAISE EXCEPTION 'quote response evidence differs' USING ERRCODE='23514'; END IF;
  IF (r.response_code<>'ACCEPTED') IS DISTINCT FROM (NEW.next_check_at IS NOT NULL) OR NEW.next_check_at<=r.created_at THEN RAISE EXCEPTION 'quote response followup differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF q.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.response_code IS DISTINCT FROM 'ACCEPTED' OR NOT EXISTS(SELECT 1 FROM opportunity.quote_response_basis b WHERE b.tenant_id=r.tenant_id AND b.quote_response_id=r.quote_response_id) THEN RAISE EXCEPTION 'contract preparation requires exact accepted quote' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_response_source() IS '准确材料回复与接受来源核对；不创建合同或签署事实。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_response_source() FROM PUBLIC;
CREATE TRIGGER trg_quote_response_basis__source BEFORE INSERT ON opportunity.quote_response_basis FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_response_source();
COMMENT ON TRIGGER trg_quote_response_basis__source ON opportunity.quote_response_basis IS '客户回复准确证据及下一行动。';
CREATE TRIGGER trg_contract_preparation_source__source BEFORE INSERT ON opportunity.contract_preparation_source FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_response_source();
COMMENT ON TRIGGER trg_contract_preparation_source__source ON opportunity.contract_preparation_source IS '准确接受来源。';
CREATE FUNCTION opportunity.fn_check_manual_quote_issue() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.delivery_fact_type='opportunity.quote_manual_delivery' THEN
  IF NEW.external_action_id IS NOT NULL OR NEW.provider_inbox_id IS NOT NULL OR NEW.delivery_fact_revision IS DISTINCT FROM 0 OR NEW.delivery_fact_hash IS NOT NULL OR NOT EXISTS(SELECT 1 FROM opportunity.quote_manual_delivery d WHERE d.tenant_id=NEW.tenant_id AND d.quote_manual_delivery_id=NEW.delivery_fact_id AND d.quote_revision_id=NEW.quote_revision_id AND d.occurred_at=NEW.issued_at AND d.channel=NEW.delivery_channel_code) THEN RAISE EXCEPTION 'manual quote issue exact source differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_manual_quote_issue() IS '经确认人工交付引用准确事实，不冒充供应商回调。';
REVOKE ALL ON FUNCTION opportunity.fn_check_manual_quote_issue() FROM PUBLIC;
CREATE TRIGGER trg_quote_issue__manual_source BEFORE INSERT ON opportunity.quote_issue FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_manual_quote_issue();
COMMENT ON TRIGGER trg_quote_issue__manual_source ON opportunity.quote_issue IS '具名人工交付来源扩展；原报价守卫保留。';
CREATE FUNCTION opportunity.fn_seal_quote_approval_members() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer WHERE tenant_id=NEW.tenant_id AND policy_id=NEW.policy_id) OR EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer s WHERE s.tenant_id=NEW.tenant_id AND s.policy_id=NEW.policy_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_member m WHERE m.tenant_id=s.tenant_id AND m.request_id=NEW.quote_approval_request_id AND m.appointment_id=s.appointment_id)) THEN RAISE EXCEPTION 'complete configured quote approval members required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_seal_quote_approval_members() IS '审批请求必须原子冻结全部具名配置成员，缺配置不得默认通过。';
REVOKE ALL ON FUNCTION opportunity.fn_seal_quote_approval_members() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_approval_request__members AFTER INSERT ON opportunity.quote_approval_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_seal_quote_approval_members();
COMMENT ON TRIGGER trg_quote_approval_request__members ON opportunity.quote_approval_request IS '完整审批成员提交前验证。';
DO $v950$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v9',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v8';
 IF NOT FOUND THEN RAISE EXCEPTION 'V950 requires 52-plus-2-r2-v8' USING ERRCODE='55000'; END IF;
END;
$v950$;

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
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND subject_type='opportunity.opportunity' AND subject_id=NEW.opportunity_id AND business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY') AND state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'closure retains active task' USING ERRCODE='23514'; END IF;
 IF NEW.task_occurrence_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_occurrence_id;
  IF t.state IS DISTINCT FROM 'CANCELLED' OR t.revision IS DISTINCT FROM NEW.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'opportunity.closure' OR t.cancellation_fact_id IS DISTINCT FROM NEW.closure_id OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.cancelled_at IS DISTINCT FROM NEW.closed_at THEN RAISE EXCEPTION 'closure cancellation differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_closure() IS '终结事务提交前检查准确商机、责任、取消及后续事实；不扩展查询角色权限。';
REVOKE ALL ON FUNCTION opportunity.fn_check_closure() FROM PUBLIC;
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_predecessor;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_predecessor CHECK (handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','DELIVER_QUOTE','RECORD_QUOTE_REPLY') AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff'));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__positive_task_revision;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__positive_task_revision CHECK (task_revision > 0 OR (wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND task_revision=0));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__resume_after_entry;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__resume_after_entry CHECK (resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1'));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__handoff_shape;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__handoff_shape CHECK ((wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL) OR (wait_contract_code='R2_QUOTE_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.quote_response' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL));CREATE OR REPLACE FUNCTION responsibility.fn_guard_r2_handoff_task_initial() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.revision<>0 OR (NEW.state='OPEN' OR (NEW.state='WAITING' AND NEW.business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY') AND NEW.responsibility_basis_type='opportunity.responsibility_handoff' AND NEW.responsibility_basis_revision=0 AND NEW.handoff_predecessor_task_occurrence_id IS NOT NULL)) IS NOT TRUE THEN RAISE EXCEPTION 'invalid task initial state' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
CREATE OR REPLACE FUNCTION opportunity.fn_check_responsibility_handoff() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.owner_exception_disposition%ROWTYPE; predecessor opportunity.responsibility_handoff%ROWTYPE; original_owner uuid;
BEGIN
 SELECT owner_appointment_id INTO original_owner FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NEW.prior_basis_type='opportunity.opportunity' THEN
  IF NEW.prior_basis_id<>NEW.opportunity_id OR NEW.prior_basis_revision<>NEW.opportunity_revision OR NEW.from_appointment_id IS DISTINCT FROM original_owner THEN RAISE EXCEPTION 'invalid initial handoff basis' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO predecessor FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND responsibility_handoff_id=NEW.prior_basis_id;
  IF NOT FOUND OR predecessor.opportunity_id<>NEW.opportunity_id OR predecessor.revision<>NEW.prior_basis_revision OR predecessor.to_appointment_id<>NEW.from_appointment_id OR predecessor.handed_off_at>NEW.handed_off_at THEN RAISE EXCEPTION 'invalid prior handoff basis' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT * INTO d FROM opportunity.owner_exception_disposition WHERE tenant_id=NEW.tenant_id AND owner_exception_disposition_id=NEW.owner_exception_disposition_id;
 IF NOT FOUND OR d.kind<>'TRANSFER' OR d.responsibility_handoff_id<>NEW.responsibility_handoff_id OR d.receiver_appointment_id<>NEW.to_appointment_id OR d.actor_appointment_id<>NEW.actor_appointment_id THEN RAISE EXCEPTION 'handoff decision mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.owner_exception e WHERE e.tenant_id=NEW.tenant_id AND e.owner_exception_id=d.owner_exception_id AND e.revision=d.owner_exception_revision AND e.opportunity_id=NEW.opportunity_id AND e.state IN ('ACTIVE','COORDINATING') AND e.basis_type=NEW.prior_basis_type AND e.basis_id=NEW.prior_basis_id AND e.basis_revision=NEW.prior_basis_revision) THEN RAISE EXCEPTION 'handoff exception basis mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.new_task_occurrence_id AND t.revision=NEW.new_task_revision AND t.owner_appointment_id=NEW.to_appointment_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=NEW.opportunity_id AND t.responsibility_basis_type='opportunity.responsibility_handoff' AND t.responsibility_basis_id=NEW.responsibility_handoff_id AND t.responsibility_basis_revision=0 AND t.original_sla_due_at=NEW.original_due_at AND t.handoff_predecessor_task_occurrence_id IS NOT DISTINCT FROM NEW.old_task_occurrence_id AND t.state=CASE WHEN NEW.original_wait_receipt_id IS NULL THEN 'OPEN' ELSE 'WAITING' END) THEN RAISE EXCEPTION 'handoff successor task mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.old_task_occurrence_id AND t.revision=NEW.old_task_revision+1 AND t.state='CANCELLED' AND t.owner_appointment_id=NEW.from_appointment_id AND t.original_sla_due_at=NEW.original_due_at AND t.cancellation_fact_type='opportunity.responsibility_handoff' AND t.cancellation_fact_id=NEW.responsibility_handoff_id AND t.cancellation_fact_revision=0 AND t.completion_fact_type IS NULL) THEN RAISE EXCEPTION 'handoff cancellation mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.original_wait_receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w JOIN responsibility.wait_receipt original ON original.tenant_id=w.tenant_id AND original.wait_receipt_id=NEW.original_wait_receipt_id WHERE w.tenant_id=NEW.tenant_id AND w.task_occurrence_id=NEW.new_task_occurrence_id AND w.wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND (w.wait_contract_code<>'R2_QUOTE_HANDOFF_WAIT_V1' OR (original.wait_contract_code IN ('R2_QUOTE_FOLLOWUP_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND w.awaited_fact_type=original.awaited_fact_type AND w.awaited_fact_id=original.awaited_fact_id AND w.awaited_fact_hash=original.awaited_fact_hash AND original.awaited_fact_revision IS NULL)) AND w.handoff_fact_id=NEW.responsibility_handoff_id AND w.handoff_fact_revision=0 AND w.inherited_wait_receipt_id=NEW.original_wait_receipt_id AND w.inherited_wait_hash=NEW.original_wait_hash AND w.resume_due_at IS NOT DISTINCT FROM original.resume_due_at AND w.original_sla_due_at=NEW.original_due_at AND original.task_occurrence_id=NEW.old_task_occurrence_id) THEN RAISE EXCEPTION 'handoff wait inheritance mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence old_task JOIN responsibility.task_occurrence new_task ON new_task.tenant_id=old_task.tenant_id AND new_task.task_occurrence_id=NEW.new_task_occurrence_id WHERE old_task.tenant_id=NEW.tenant_id AND old_task.task_occurrence_id=NEW.old_task_occurrence_id AND old_task.business_purpose_code=new_task.business_purpose_code AND old_task.primary_command_code=new_task.primary_command_code AND old_task.expected_completion_fact_type=new_task.expected_completion_fact_type AND old_task.original_sla_code=new_task.original_sla_code AND old_task.original_sla_seconds=new_task.original_sla_seconds) THEN RAISE EXCEPTION 'handoff successor purpose differs' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
