CREATE TABLE contract.negotiation_disposition (
    tenant_id uuid NOT NULL,
    negotiation_disposition_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    previous_disposition_id uuid,
    request_disposition_id uuid,
    kind varchar(64) NOT NULL,
    contract_id uuid,
    contract_revision bigint,
    contract_version_id uuid,
    preparation_workflow_id uuid NOT NULL,
    signature_workflow_id uuid,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_negotiation_disposition PRIMARY KEY (tenant_id, negotiation_disposition_id),
    CONSTRAINT ck_negotiation_disposition__revision CHECK (revision=0),
    CONSTRAINT uq_negotiation_disposition__previous_disposition_id UNIQUE (tenant_id, previous_disposition_id),
    CONSTRAINT ck_negotiation_disposition__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_negotiation_disposition__shape CHECK (opportunity_revision BETWEEN 0 AND 9007199254740991 AND ((contract_id IS NULL AND contract_revision IS NULL AND contract_version_id IS NULL) OR (contract_id IS NOT NULL AND contract_revision BETWEEN 0 AND 9007199254740991)) AND ((kind IN ('STOP_UNSIGNED','REQUEST_REVIEW') AND request_disposition_id IS NULL) OR (kind IN ('STOP_REVIEWED','CONTINUE') AND request_disposition_id=previous_disposition_id AND request_disposition_id IS NOT NULL))),
    CONSTRAINT ck_negotiation_disposition__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.negotiation_disposition IS 'Fact Owner：ContractRuntime；销售办理处置的不可变准确事实；不解除合同或撤销执行。';
COMMENT ON CONSTRAINT pk_negotiation_disposition ON contract.negotiation_disposition IS '主键：在租户内唯一标识一条negotiation_disposition记录。';
COMMENT ON INDEX contract.pk_negotiation_disposition IS '主键：在租户内唯一标识一条negotiation_disposition记录。';
COMMENT ON COLUMN contract.negotiation_disposition.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.negotiation_disposition.negotiation_disposition_id IS '销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.negotiation_disposition.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.negotiation_disposition.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.negotiation_disposition.opportunity_id IS '销售主线。';
COMMENT ON COLUMN contract.negotiation_disposition.opportunity_revision IS '准确商机修订。';
COMMENT ON COLUMN contract.negotiation_disposition.previous_disposition_id IS '直接前序处置，单链。';
COMMENT ON COLUMN contract.negotiation_disposition.request_disposition_id IS '主管处置所核对的准确请求。';
COMMENT ON COLUMN contract.negotiation_disposition.kind IS 'STOP_UNSIGNED/REQUEST_REVIEW/STOP_REVIEWED/CONTINUE。';
COMMENT ON COLUMN contract.negotiation_disposition.contract_id IS '当前合同身份；直接授权申请阶段可空。';
COMMENT ON COLUMN contract.negotiation_disposition.contract_revision IS '合同身份准确修订。';
COMMENT ON COLUMN contract.negotiation_disposition.contract_version_id IS '准确合同正文版本。';
COMMENT ON COLUMN contract.negotiation_disposition.preparation_workflow_id IS '准确准备办理依据。';
COMMENT ON COLUMN contract.negotiation_disposition.signature_workflow_id IS '当前签署办理依据。';
COMMENT ON COLUMN contract.negotiation_disposition.recorded_by IS '实际申请或核对任职。';
COMMENT ON COLUMN contract.negotiation_disposition.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.negotiation_disposition.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.negotiation_disposition.created_at IS '数据库可信记录时间。';
COMMENT ON CONSTRAINT ck_negotiation_disposition__revision ON contract.negotiation_disposition IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_negotiation_disposition__previous_disposition_id ON contract.negotiation_disposition IS '引用只能接续一次。';
COMMENT ON INDEX contract.uq_negotiation_disposition__previous_disposition_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT ck_negotiation_disposition__body ON contract.negotiation_disposition IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_negotiation_disposition__shape ON contract.negotiation_disposition IS '明确形状与准确版本。';
COMMENT ON CONSTRAINT ck_negotiation_disposition__body_digest_length ON contract.negotiation_disposition IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.termination_review_assignment (
    tenant_id uuid NOT NULL,
    termination_review_assignment_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    request_id uuid NOT NULL,
    previous_assignment_id uuid,
    owner_appointment_id uuid,
    task_id uuid,
    due_at timestamptz(6) NOT NULL,
    recorded_by uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_termination_review_assignment PRIMARY KEY (tenant_id, termination_review_assignment_id),
    CONSTRAINT ck_termination_review_assignment__revision CHECK (revision=0),
    CONSTRAINT uq_termination_review_assignment__previous_assignment_id UNIQUE (tenant_id, previous_assignment_id),
    CONSTRAINT uq_termination_review_assignment__task_id UNIQUE (tenant_id, task_id),
    CONSTRAINT ck_termination_review_assignment__owner CHECK ((owner_appointment_id IS NULL)=(task_id IS NULL))
);

COMMENT ON TABLE contract.termination_review_assignment IS 'Fact Owner：ContractRuntime；销售办理处置的不可变准确事实；不解除合同或撤销执行。';
COMMENT ON CONSTRAINT pk_termination_review_assignment ON contract.termination_review_assignment IS '主键：在租户内唯一标识一条termination_review_assignment记录。';
COMMENT ON INDEX contract.pk_termination_review_assignment IS '主键：在租户内唯一标识一条termination_review_assignment记录。';
COMMENT ON COLUMN contract.termination_review_assignment.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.termination_review_assignment.termination_review_assignment_id IS '销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.termination_review_assignment.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.termination_review_assignment.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.termination_review_assignment.request_id IS '准确终止核对请求。';
COMMENT ON COLUMN contract.termination_review_assignment.previous_assignment_id IS '直接前序责任安排。';
COMMENT ON COLUMN contract.termination_review_assignment.owner_appointment_id IS '有权主管；未配置时为空。';
COMMENT ON COLUMN contract.termination_review_assignment.task_id IS '实际主管任务；未配置时为空。';
COMMENT ON COLUMN contract.termination_review_assignment.due_at IS '原核对期限，重新分配不延长。';
COMMENT ON COLUMN contract.termination_review_assignment.recorded_by IS '申请者或恢复服务任职。';
COMMENT ON COLUMN contract.termination_review_assignment.created_at IS '数据库可信记录时间。';
COMMENT ON CONSTRAINT ck_termination_review_assignment__revision ON contract.termination_review_assignment IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_termination_review_assignment__previous_assignment_id ON contract.termination_review_assignment IS '引用只能接续一次。';
COMMENT ON INDEX contract.uq_termination_review_assignment__previous_assignment_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT uq_termination_review_assignment__task_id ON contract.termination_review_assignment IS '引用只能接续一次。';
COMMENT ON INDEX contract.uq_termination_review_assignment__task_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT ck_termination_review_assignment__owner ON contract.termination_review_assignment IS '无主管不伪造可办理任务。';
CREATE TABLE contract.negotiation_cancelled_task (
    tenant_id uuid NOT NULL,
    negotiation_cancelled_task_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    disposition_id uuid NOT NULL,
    task_id uuid NOT NULL,
    task_revision bigint NOT NULL,
    prior_state varchar(64) NOT NULL,
    wait_id uuid,
    wait_hash bytea,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_negotiation_cancelled_task PRIMARY KEY (tenant_id, negotiation_cancelled_task_id),
    CONSTRAINT ck_negotiation_cancelled_task__revision CHECK (revision=0),
    CONSTRAINT uq_negotiation_cancelled_task__task_id UNIQUE (tenant_id, task_id),
    CONSTRAINT ck_negotiation_cancelled_task__state CHECK (task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))),
    CONSTRAINT ck_negotiation_cancelled_task__wait_hash_length CHECK (octet_length(wait_hash) = 32)
);

COMMENT ON TABLE contract.negotiation_cancelled_task IS 'Fact Owner：ContractRuntime；销售办理处置的不可变准确事实；不解除合同或撤销执行。';
COMMENT ON CONSTRAINT pk_negotiation_cancelled_task ON contract.negotiation_cancelled_task IS '主键：在租户内唯一标识一条negotiation_cancelled_task记录。';
COMMENT ON INDEX contract.pk_negotiation_cancelled_task IS '主键：在租户内唯一标识一条negotiation_cancelled_task记录。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.negotiation_cancelled_task_id IS '销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.disposition_id IS '停止或暂停的准确依据。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.task_id IS '原未完成责任。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.task_revision IS '取消前准确修订。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.prior_state IS '取消前OPEN/WAITING。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.wait_id IS 'WAITING的末端等待。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.wait_hash IS '准确等待摘要。';
COMMENT ON COLUMN contract.negotiation_cancelled_task.created_at IS '数据库可信记录时间。';
COMMENT ON CONSTRAINT ck_negotiation_cancelled_task__revision ON contract.negotiation_cancelled_task IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_negotiation_cancelled_task__task_id ON contract.negotiation_cancelled_task IS '引用只能接续一次。';
COMMENT ON INDEX contract.uq_negotiation_cancelled_task__task_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT ck_negotiation_cancelled_task__state ON contract.negotiation_cancelled_task IS '准确取消集合。';
COMMENT ON CONSTRAINT ck_negotiation_cancelled_task__wait_hash_length ON contract.negotiation_cancelled_task IS '摘要格式：wait_hash必须保存32字节的规范二进制值。';
CREATE TABLE responsibility.contract_task_resumption (
    tenant_id uuid NOT NULL,
    contract_task_resumption_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    disposition_id uuid NOT NULL,
    cancelled_task_id uuid NOT NULL,
    prior_task_id uuid NOT NULL,
    next_task_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_task_resumption PRIMARY KEY (tenant_id, contract_task_resumption_id),
    CONSTRAINT ck_contract_task_resumption__revision CHECK (revision=0),
    CONSTRAINT uq_contract_task_resumption__cancelled_task_id UNIQUE (tenant_id, cancelled_task_id),
    CONSTRAINT uq_contract_task_resumption__prior_task_id UNIQUE (tenant_id, prior_task_id),
    CONSTRAINT uq_contract_task_resumption__next_task_id UNIQUE (tenant_id, next_task_id)
);

COMMENT ON TABLE responsibility.contract_task_resumption IS 'Fact Owner：ResponsibilityRuntime；销售办理处置的不可变准确事实；不解除合同或撤销执行。';
COMMENT ON CONSTRAINT pk_contract_task_resumption ON responsibility.contract_task_resumption IS '主键：在租户内唯一标识一条contract_task_resumption记录。';
COMMENT ON INDEX responsibility.pk_contract_task_resumption IS '主键：在租户内唯一标识一条contract_task_resumption记录。';
COMMENT ON COLUMN responsibility.contract_task_resumption.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN responsibility.contract_task_resumption.contract_task_resumption_id IS '销售办理处置的不可变准确事实；不解除合同或撤销执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN responsibility.contract_task_resumption.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN responsibility.contract_task_resumption.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN responsibility.contract_task_resumption.disposition_id IS '主管CONTINUE决定。';
COMMENT ON COLUMN responsibility.contract_task_resumption.cancelled_task_id IS '准确取消明细。';
COMMENT ON COLUMN responsibility.contract_task_resumption.prior_task_id IS '被暂停的原责任。';
COMMENT ON COLUMN responsibility.contract_task_resumption.next_task_id IS '新建的同目的责任。';
COMMENT ON COLUMN responsibility.contract_task_resumption.created_at IS '数据库可信记录时间。';
COMMENT ON CONSTRAINT ck_contract_task_resumption__revision ON responsibility.contract_task_resumption IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_contract_task_resumption__cancelled_task_id ON responsibility.contract_task_resumption IS '引用只能接续一次。';
COMMENT ON INDEX responsibility.uq_contract_task_resumption__cancelled_task_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT uq_contract_task_resumption__prior_task_id ON responsibility.contract_task_resumption IS '引用只能接续一次。';
COMMENT ON INDEX responsibility.uq_contract_task_resumption__prior_task_id IS '引用只能接续一次。';
COMMENT ON CONSTRAINT uq_contract_task_resumption__next_task_id ON responsibility.contract_task_resumption IS '引用只能接续一次。';
COMMENT ON INDEX responsibility.uq_contract_task_resumption__next_task_id IS '引用只能接续一次。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__tenant ON contract.negotiation_disposition IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__opportunity_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__previous_disposition_id
    FOREIGN KEY (tenant_id, previous_disposition_id)
    REFERENCES contract.negotiation_disposition (tenant_id, negotiation_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__previous_disposition_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__request_disposition_id
    FOREIGN KEY (tenant_id, request_disposition_id)
    REFERENCES contract.negotiation_disposition (tenant_id, negotiation_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__request_disposition_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__contract_id
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__contract_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__contract_version_id
    FOREIGN KEY (tenant_id, contract_version_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__contract_version_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__preparation_workflow_id
    FOREIGN KEY (tenant_id, preparation_workflow_id)
    REFERENCES contract.preparation_workflow (tenant_id, preparation_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__preparation_workflow_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__signature_workflow_id
    FOREIGN KEY (tenant_id, signature_workflow_id)
    REFERENCES contract.signature_workflow (tenant_id, signature_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__signature_workflow_id ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_disposition
    ADD CONSTRAINT fk_negotiation_disposition__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_disposition__recorded_by ON contract.negotiation_disposition IS '同租户准确事实引用。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__tenant ON contract.termination_review_assignment IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__request_id
    FOREIGN KEY (tenant_id, request_id)
    REFERENCES contract.negotiation_disposition (tenant_id, negotiation_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__request_id ON contract.termination_review_assignment IS '同租户准确事实引用。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__previous_assignment_id
    FOREIGN KEY (tenant_id, previous_assignment_id)
    REFERENCES contract.termination_review_assignment (tenant_id, termination_review_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__previous_assignment_id ON contract.termination_review_assignment IS '同租户准确事实引用。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__owner_appointment_id ON contract.termination_review_assignment IS '同租户准确事实引用。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__task_id ON contract.termination_review_assignment IS '同租户准确事实引用。';
ALTER TABLE contract.termination_review_assignment
    ADD CONSTRAINT fk_termination_review_assignment__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_termination_review_assignment__recorded_by ON contract.termination_review_assignment IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_cancelled_task
    ADD CONSTRAINT fk_negotiation_cancelled_task__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_cancelled_task__tenant ON contract.negotiation_cancelled_task IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.negotiation_cancelled_task
    ADD CONSTRAINT fk_negotiation_cancelled_task__disposition_id
    FOREIGN KEY (tenant_id, disposition_id)
    REFERENCES contract.negotiation_disposition (tenant_id, negotiation_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_cancelled_task__disposition_id ON contract.negotiation_cancelled_task IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_cancelled_task
    ADD CONSTRAINT fk_negotiation_cancelled_task__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_cancelled_task__task_id ON contract.negotiation_cancelled_task IS '同租户准确事实引用。';
ALTER TABLE contract.negotiation_cancelled_task
    ADD CONSTRAINT fk_negotiation_cancelled_task__wait_id
    FOREIGN KEY (tenant_id, wait_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_negotiation_cancelled_task__wait_id ON contract.negotiation_cancelled_task IS '同租户准确事实引用。';
ALTER TABLE responsibility.contract_task_resumption
    ADD CONSTRAINT fk_contract_task_resumption__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_task_resumption__tenant ON responsibility.contract_task_resumption IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE responsibility.contract_task_resumption
    ADD CONSTRAINT fk_contract_task_resumption__disposition_id
    FOREIGN KEY (tenant_id, disposition_id)
    REFERENCES contract.negotiation_disposition (tenant_id, negotiation_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_task_resumption__disposition_id ON responsibility.contract_task_resumption IS '同租户准确事实引用。';
ALTER TABLE responsibility.contract_task_resumption
    ADD CONSTRAINT fk_contract_task_resumption__cancelled_task_id
    FOREIGN KEY (tenant_id, cancelled_task_id)
    REFERENCES contract.negotiation_cancelled_task (tenant_id, negotiation_cancelled_task_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_task_resumption__cancelled_task_id ON responsibility.contract_task_resumption IS '同租户准确事实引用。';
ALTER TABLE responsibility.contract_task_resumption
    ADD CONSTRAINT fk_contract_task_resumption__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_task_resumption__prior_task_id ON responsibility.contract_task_resumption IS '同租户准确事实引用。';
ALTER TABLE responsibility.contract_task_resumption
    ADD CONSTRAINT fk_contract_task_resumption__next_task_id
    FOREIGN KEY (tenant_id, next_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_task_resumption__next_task_id ON responsibility.contract_task_resumption IS '同租户准确事实引用。';
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_cancellation;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK ((((cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure')))) OR (state='CANCELLED' AND cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND cancellation_fact_type='opportunity.followup_attempt' AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL)) OR (state='CANCELLED' AND cancellation_reason_code='R2_QUOTE_TERMINATION_V1' AND cancellation_fact_type='opportunity.quote_termination' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL)) OR (state='CANCELLED' AND cancellation_reason_code='R2_CONTRACT_NEGOTIATION_V1' AND cancellation_fact_type='contract.negotiation_disposition' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL));
CREATE TRIGGER trg_negotiation_disposition__immutable BEFORE UPDATE OR DELETE ON contract.negotiation_disposition FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_negotiation_disposition__transaction BEFORE INSERT ON contract.negotiation_disposition FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.negotiation_disposition FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.negotiation_disposition TO ${app_command_role};
GRANT SELECT ON contract.negotiation_disposition TO ${app_query_role};
CREATE TRIGGER trg_termination_review_assignment__immutable BEFORE UPDATE OR DELETE ON contract.termination_review_assignment FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_termination_review_assignment__transaction BEFORE INSERT ON contract.termination_review_assignment FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.termination_review_assignment FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.termination_review_assignment TO ${app_command_role};
GRANT SELECT ON contract.termination_review_assignment TO ${app_query_role};
CREATE TRIGGER trg_negotiation_cancelled_task__immutable BEFORE UPDATE OR DELETE ON contract.negotiation_cancelled_task FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_negotiation_cancelled_task__transaction BEFORE INSERT ON contract.negotiation_cancelled_task FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.negotiation_cancelled_task FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.negotiation_cancelled_task TO ${app_command_role};
GRANT SELECT ON contract.negotiation_cancelled_task TO ${app_query_role};
CREATE TRIGGER trg_contract_task_resumption__immutable BEFORE UPDATE OR DELETE ON responsibility.contract_task_resumption FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_contract_task_resumption__transaction BEFORE INSERT ON responsibility.contract_task_resumption FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON responsibility.contract_task_resumption FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON responsibility.contract_task_resumption TO ${app_command_role};
GRANT SELECT ON responsibility.contract_task_resumption TO ${app_query_role};

CREATE FUNCTION contract.fn_negotiation_has_evidence(t uuid,o uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
 SELECT EXISTS(SELECT 1 FROM contract.signature_submission s WHERE s.tenant_id=t AND s.opportunity_id=o)
 OR EXISTS(SELECT 1 FROM contract.contract_signature s JOIN contract.contract_revision v ON v.tenant_id=s.tenant_id AND v.contract_revision_id=s.contract_revision_id JOIN contract.contract c ON c.tenant_id=v.tenant_id AND c.contract_id=v.contract_id WHERE c.tenant_id=t AND c.opportunity_id=o)
 OR EXISTS(SELECT 1 FROM contract.contract_execution e JOIN contract.contract c ON c.tenant_id=e.tenant_id AND c.contract_id=e.contract_id WHERE c.tenant_id=t AND c.opportunity_id=o)
 OR EXISTS(SELECT 1 FROM contract.payment_confirmation p JOIN contract.contract c ON c.tenant_id=p.tenant_id AND c.contract_id=p.contract_id WHERE c.tenant_id=t AND c.opportunity_id=o)
 OR EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=t AND r.opportunity_id=o);
$fn$;
COMMENT ON FUNCTION contract.fn_negotiation_has_evidence(uuid,uuid) IS '任一签字提交、已签、执行或转案事实均须主管核对；不凭缺少核验误判未签。';
REVOKE ALL ON FUNCTION contract.fn_negotiation_has_evidence(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_negotiation_has_evidence(uuid,uuid) TO ${app_command_role},${app_query_role};

CREATE FUNCTION contract.fn_check_negotiation_disposition() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; p contract.negotiation_disposition%ROWTYPE; c contract.contract%ROWTYPE; a contract.termination_review_assignment%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM NEW.opportunity_revision OR NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'negotiation root differs' USING ERRCODE='23514'; END IF;
 SELECT * INTO p FROM contract.negotiation_disposition d WHERE d.tenant_id=NEW.tenant_id AND d.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.negotiation_disposition n WHERE n.tenant_id=d.tenant_id AND n.previous_disposition_id=d.negotiation_disposition_id);
 IF p.negotiation_disposition_id IS DISTINCT FROM NEW.previous_disposition_id THEN RAISE EXCEPTION 'negotiation predecessor differs' USING ERRCODE='23514'; END IF;
 IF NEW.kind IN ('STOP_UNSIGNED','REQUEST_REVIEW') AND p.kind IS NOT NULL AND p.kind<>'CONTINUE' THEN RAISE EXCEPTION 'negotiation already pending or stopped' USING ERRCODE='23514'; END IF;
 IF NEW.kind IN ('STOP_REVIEWED','CONTINUE') THEN
  SELECT * INTO a FROM contract.termination_review_assignment x WHERE x.tenant_id=NEW.tenant_id AND x.request_id=NEW.request_disposition_id AND NOT EXISTS(SELECT 1 FROM contract.termination_review_assignment n WHERE n.tenant_id=x.tenant_id AND n.previous_assignment_id=x.termination_review_assignment_id);
  IF p.kind IS DISTINCT FROM 'REQUEST_REVIEW' OR a.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR NEW.recorded_by=p.recorded_by OR NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=a.task_id AND t.state='OPEN' AND t.owner_appointment_id=NEW.recorded_by AND t.business_purpose_code='REVIEW_CONTRACT_TERMINATION') THEN RAISE EXCEPTION 'supervisor responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT * INTO c FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id AND preparation_contract_code='R2_CONTRACT_PREPARATION_V1';
 IF c.contract_id IS DISTINCT FROM NEW.contract_id OR c.revision IS DISTINCT FROM NEW.contract_revision OR c.current_revision_id IS DISTINCT FROM NEW.contract_version_id THEN RAISE EXCEPTION 'negotiation contract differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM contract.preparation_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.preparation_workflow_id=NEW.preparation_workflow_id AND w.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.preparation_workflow_id)) THEN RAISE EXCEPTION 'negotiation preparation differs' USING ERRCODE='23514'; END IF;
 IF (SELECT w.signature_workflow_id FROM contract.signature_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND w.contract_revision_id=NEW.contract_version_id AND NOT EXISTS(SELECT 1 FROM contract.signature_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.signature_workflow_id)) IS DISTINCT FROM NEW.signature_workflow_id THEN RAISE EXCEPTION 'negotiation signature differs' USING ERRCODE='23514'; END IF;
 IF (NEW.kind='STOP_UNSIGNED' AND contract.fn_negotiation_has_evidence(NEW.tenant_id,NEW.opportunity_id)) OR (NEW.kind='REQUEST_REVIEW' AND NOT contract.fn_negotiation_has_evidence(NEW.tenant_id,NEW.opportunity_id)) THEN RAISE EXCEPTION 'negotiation evidence requires a different disposition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_check_negotiation_disposition() FROM PUBLIC;
CREATE TRIGGER trg_negotiation_disposition__current BEFORE INSERT ON contract.negotiation_disposition FOR EACH ROW EXECUTE FUNCTION contract.fn_check_negotiation_disposition();

CREATE FUNCTION contract.fn_check_negotiation_member() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d contract.negotiation_disposition%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; a contract.termination_review_assignment%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='negotiation_cancelled_task' THEN
  SELECT * INTO d FROM contract.negotiation_disposition WHERE tenant_id=NEW.tenant_id AND negotiation_disposition_id=NEW.disposition_id;
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id FOR UPDATE;
  IF d.kind NOT IN ('STOP_UNSIGNED','REQUEST_REVIEW') OR d.negotiation_disposition_id IS NULL OR d.created_in_transaction<>pg_current_xact_id() OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM d.opportunity_id OR t.subject_revision IS DISTINCT FROM d.opportunity_revision OR t.business_purpose_code NOT IN ('REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE','VERIFY_CONTRACT_SIGNATURE','ARCHIVE_CONTRACT_SIGNATURE') OR t.revision IS DISTINCT FROM NEW.task_revision OR t.state IS DISTINCT FROM NEW.prior_state OR NEW.created_at IS DISTINCT FROM d.created_at THEN RAISE EXCEPTION 'negotiation cancellation member differs' USING ERRCODE='23514'; END IF;
  IF NEW.prior_state='WAITING' AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w WHERE w.tenant_id=NEW.tenant_id AND w.wait_receipt_id=NEW.wait_id AND w.task_occurrence_id=NEW.task_id AND w.task_revision=NEW.task_revision) THEN RAISE EXCEPTION 'negotiation waiting basis differs' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO d FROM contract.negotiation_disposition WHERE tenant_id=NEW.tenant_id AND negotiation_disposition_id=NEW.request_id;
  PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=d.opportunity_id FOR UPDATE;
  IF d.kind IS DISTINCT FROM 'REQUEST_REVIEW' OR EXISTS(SELECT 1 FROM contract.negotiation_disposition n WHERE n.tenant_id=d.tenant_id AND n.previous_disposition_id=d.negotiation_disposition_id) OR NEW.owner_appointment_id=d.recorded_by THEN RAISE EXCEPTION 'negotiation review request differs' USING ERRCODE='23514'; END IF;
  SELECT * INTO a FROM contract.termination_review_assignment x WHERE x.tenant_id=NEW.tenant_id AND x.request_id=NEW.request_id AND NOT EXISTS(SELECT 1 FROM contract.termination_review_assignment n WHERE n.tenant_id=x.tenant_id AND n.previous_assignment_id=x.termination_review_assignment_id);
  IF a.termination_review_assignment_id IS DISTINCT FROM NEW.previous_assignment_id OR (a.termination_review_assignment_id IS NOT NULL AND a.due_at IS DISTINCT FROM NEW.due_at) THEN RAISE EXCEPTION 'negotiation review predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence reviewed WHERE reviewed.tenant_id=NEW.tenant_id AND reviewed.task_occurrence_id=NEW.task_id AND reviewed.state='OPEN' AND reviewed.revision=0 AND reviewed.owner_appointment_id=NEW.owner_appointment_id AND reviewed.subject_id=d.opportunity_id AND reviewed.subject_revision=d.opportunity_revision AND reviewed.business_purpose_code='REVIEW_CONTRACT_TERMINATION' AND reviewed.original_sla_due_at=NEW.due_at AND reviewed.created_at=NEW.created_at) THEN RAISE EXCEPTION 'negotiation review task differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_check_negotiation_member() FROM PUBLIC;
CREATE TRIGGER trg_negotiation_cancelled_task__current BEFORE INSERT ON contract.negotiation_cancelled_task FOR EACH ROW EXECUTE FUNCTION contract.fn_check_negotiation_member();
CREATE TRIGGER trg_termination_review_assignment__current BEFORE INSERT ON contract.termination_review_assignment FOR EACH ROW EXECUTE FUNCTION contract.fn_check_negotiation_member();

CREATE FUNCTION contract.fn_check_negotiation_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d contract.negotiation_disposition%ROWTYPE; a contract.termination_review_assignment%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='task_occurrence' THEN
  IF NEW.cancellation_reason_code IS DISTINCT FROM 'R2_CONTRACT_NEGOTIATION_V1' OR (TG_OP='UPDATE' AND NEW.revision=OLD.revision) THEN RETURN NEW; END IF;
  IF NOT EXISTS(SELECT 1 FROM contract.negotiation_cancelled_task m JOIN contract.negotiation_disposition z ON z.tenant_id=m.tenant_id AND z.negotiation_disposition_id=m.disposition_id WHERE m.tenant_id=NEW.tenant_id AND m.task_id=NEW.task_occurrence_id AND m.task_revision+1=NEW.revision AND z.negotiation_disposition_id=NEW.cancellation_fact_id AND z.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'orphan negotiation cancellation' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO d FROM contract.negotiation_disposition WHERE tenant_id=NEW.tenant_id AND negotiation_disposition_id=NEW.negotiation_disposition_id;
 IF d.kind IN ('STOP_UNSIGNED','REQUEST_REVIEW') THEN
  IF EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=d.tenant_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=d.opportunity_id AND t.business_purpose_code IN ('REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE','VERIFY_CONTRACT_SIGNATURE','ARCHIVE_CONTRACT_SIGNATURE') AND t.state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'contract handling responsibility was left active' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM contract.negotiation_cancelled_task m JOIN responsibility.task_occurrence t ON t.tenant_id=m.tenant_id AND t.task_occurrence_id=m.task_id WHERE m.tenant_id=d.tenant_id AND m.disposition_id=d.negotiation_disposition_id AND (t.state<>'CANCELLED' OR t.revision<>m.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'contract.negotiation_disposition' OR t.cancellation_fact_id IS DISTINCT FROM d.negotiation_disposition_id OR t.cancellation_fact_revision IS DISTINCT FROM 0 OR t.cancellation_reason_code IS DISTINCT FROM 'R2_CONTRACT_NEGOTIATION_V1' OR t.cancelled_at IS DISTINCT FROM d.created_at OR t.completion_fact_type IS NOT NULL)) THEN RAISE EXCEPTION 'contract cancellation result differs' USING ERRCODE='23514'; END IF;
  IF d.kind='REQUEST_REVIEW' AND NOT EXISTS(SELECT 1 FROM contract.termination_review_assignment x WHERE x.tenant_id=d.tenant_id AND x.request_id=d.negotiation_disposition_id AND x.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'contract review responsibility missing' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO a FROM contract.termination_review_assignment x WHERE x.tenant_id=d.tenant_id AND x.request_id=d.request_disposition_id AND NOT EXISTS(SELECT 1 FROM contract.termination_review_assignment n WHERE n.tenant_id=x.tenant_id AND n.previous_assignment_id=x.termination_review_assignment_id);
  IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=d.tenant_id AND t.task_occurrence_id=a.task_id AND t.state='DONE' AND t.completion_fact_type='contract.negotiation_disposition' AND t.completion_fact_id=d.negotiation_disposition_id AND t.completion_fact_revision=0) THEN RAISE EXCEPTION 'contract supervisor task not completed' USING ERRCODE='23514'; END IF;
  IF d.kind='CONTINUE' AND EXISTS(SELECT 1 FROM contract.negotiation_cancelled_task m WHERE m.tenant_id=d.tenant_id AND m.disposition_id=d.request_disposition_id AND NOT EXISTS(SELECT 1 FROM responsibility.contract_task_resumption r WHERE r.tenant_id=m.tenant_id AND r.cancelled_task_id=m.negotiation_cancelled_task_id AND r.disposition_id=d.negotiation_disposition_id)) THEN RAISE EXCEPTION 'contract resume responsibility missing' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_check_negotiation_complete() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_negotiation_disposition__complete AFTER INSERT ON contract.negotiation_disposition DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_negotiation_complete();
CREATE CONSTRAINT TRIGGER trg_task__negotiation_complete AFTER INSERT OR UPDATE ON responsibility.task_occurrence DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_negotiation_complete();

CREATE FUNCTION responsibility.fn_check_contract_task_resumption() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d contract.negotiation_disposition%ROWTYPE; m contract.negotiation_cancelled_task%ROWTYPE; p responsibility.task_occurrence%ROWTYPE; n responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO d FROM contract.negotiation_disposition WHERE tenant_id=NEW.tenant_id AND negotiation_disposition_id=NEW.disposition_id;
 SELECT * INTO m FROM contract.negotiation_cancelled_task WHERE tenant_id=NEW.tenant_id AND negotiation_cancelled_task_id=NEW.cancelled_task_id;
 SELECT * INTO p FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.prior_task_id;
 SELECT * INTO n FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.next_task_id;
 IF d.kind IS DISTINCT FROM 'CONTINUE' OR d.created_in_transaction<>pg_current_xact_id() OR m.disposition_id IS DISTINCT FROM d.request_disposition_id OR m.task_id IS DISTINCT FROM p.task_occurrence_id OR p.task_occurrence_id IS NULL OR p.state<>'CANCELLED' OR p.cancellation_fact_id IS DISTINCT FROM d.request_disposition_id OR p.revision<>m.task_revision+1 OR n.task_occurrence_id IS NULL OR n.state<>'OPEN' OR n.revision<>0 OR n.created_at IS DISTINCT FROM d.created_at THEN RAISE EXCEPTION 'contract resumption provenance differs' USING ERRCODE='23514'; END IF;
 IF ROW(n.subject_type,n.subject_id,n.subject_revision,n.subject_hash,n.business_purpose_code,n.primary_command_code,n.expected_completion_fact_type,n.original_sla_code,n.original_sla_seconds,n.original_sla_due_at,n.owner_appointment_id) IS DISTINCT FROM ROW(p.subject_type,p.subject_id,p.subject_revision,p.subject_hash,p.business_purpose_code,p.primary_command_code,p.expected_completion_fact_type,p.original_sla_code,p.original_sla_seconds,p.original_sla_due_at,p.owner_appointment_id) THEN RAISE EXCEPTION 'contract resumption changed purpose owner or deadline' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION responsibility.fn_check_contract_task_resumption() FROM PUBLIC;
CREATE TRIGGER trg_contract_task_resumption__exact BEFORE INSERT ON responsibility.contract_task_resumption FOR EACH ROW EXECUTE FUNCTION responsibility.fn_check_contract_task_resumption();

DO $v1030$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v17',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v16';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1030 requires 52-plus-2-r2-v16' USING ERRCODE='55000'; END IF;
END;
$v1030$;

CREATE FUNCTION contract.fn_require_negotiation_active(t uuid,o uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE k varchar(64);
BEGIN
 IF o IS NULL THEN RETURN; END IF;
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=t AND opportunity_id=o FOR UPDATE;
 SELECT d.kind INTO k FROM contract.negotiation_disposition d WHERE d.tenant_id=t AND d.opportunity_id=o AND NOT EXISTS(SELECT 1 FROM contract.negotiation_disposition n WHERE n.tenant_id=d.tenant_id AND n.previous_disposition_id=d.negotiation_disposition_id);
 IF k IS NOT NULL AND k<>'CONTINUE' THEN RAISE EXCEPTION 'contract negotiation is paused or stopped' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_require_negotiation_active(uuid,uuid) IS '在销售主线锁内阻断暂停或停止之后的新准备、签署、执行和转案事实；不删除已有事实。';
REVOKE ALL ON FUNCTION contract.fn_require_negotiation_active(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_require_negotiation_active(uuid,uuid) TO ${app_command_role};
CREATE FUNCTION contract.fn_contract_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract__negotiation_guard BEFORE INSERT ON contract.contract FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_negotiation_guard();
CREATE FUNCTION contract.fn_contract_revision_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT opportunity_id FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=NEW.contract_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_revision_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract_revision__negotiation_guard BEFORE INSERT ON contract.contract_revision FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_revision_negotiation_guard();
CREATE FUNCTION contract.fn_contract_participation_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_participation_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract_participation__negotiation_guard BEFORE INSERT ON contract.contract_participation FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_participation_negotiation_guard();
CREATE FUNCTION contract.fn_contract_fee_term_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_fee_term_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract_fee_term__negotiation_guard BEFORE INSERT ON contract.contract_fee_term FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_fee_term_negotiation_guard();
CREATE FUNCTION contract.fn_payment_gate_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_payment_gate_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_payment_gate__negotiation_guard BEFORE INSERT ON contract.payment_gate FOR EACH ROW EXECUTE FUNCTION contract.fn_payment_gate_negotiation_guard();
CREATE FUNCTION contract.fn_signature_plan_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_plan_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_plan__negotiation_guard BEFORE INSERT ON contract.signature_plan FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_plan_negotiation_guard();
CREATE FUNCTION contract.fn_contract_signature_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_signature_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract_signature__negotiation_guard BEFORE INSERT ON contract.contract_signature FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_signature_negotiation_guard();
CREATE FUNCTION contract.fn_contract_execution_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT opportunity_id FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=NEW.contract_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_contract_execution_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_contract_execution__negotiation_guard BEFORE INSERT ON contract.contract_execution FOR EACH ROW EXECUTE FUNCTION contract.fn_contract_execution_negotiation_guard();
CREATE FUNCTION contract.fn_preparation_request_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_preparation_request_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_preparation_request__negotiation_guard BEFORE INSERT ON contract.preparation_request FOR EACH ROW EXECUTE FUNCTION contract.fn_preparation_request_negotiation_guard();
CREATE FUNCTION contract.fn_preparation_decision_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT opportunity_id FROM contract.preparation_request WHERE tenant_id=NEW.tenant_id AND preparation_request_id=NEW.preparation_request_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_preparation_decision_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_preparation_decision__negotiation_guard BEFORE INSERT ON contract.preparation_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_preparation_decision_negotiation_guard();
CREATE FUNCTION contract.fn_preparation_workflow_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_preparation_workflow_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_preparation_workflow__negotiation_guard BEFORE INSERT ON contract.preparation_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_preparation_workflow_negotiation_guard();
CREATE FUNCTION contract.fn_revision_approval_request_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_approval_request_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_approval_request__negotiation_guard BEFORE INSERT ON contract.revision_approval_request FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_approval_request_negotiation_guard();
CREATE FUNCTION contract.fn_preparation_draft_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_preparation_draft_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_preparation_draft__negotiation_guard BEFORE INSERT ON contract.preparation_draft FOR EACH ROW EXECUTE FUNCTION contract.fn_preparation_draft_negotiation_guard();
CREATE FUNCTION contract.fn_revision_clause_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_clause_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_clause__negotiation_guard BEFORE INSERT ON contract.revision_clause FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_clause_negotiation_guard();
CREATE FUNCTION contract.fn_revision_review_request_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_review_request_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_review_request__negotiation_guard BEFORE INSERT ON contract.revision_review_request FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_review_request_negotiation_guard();
CREATE FUNCTION contract.fn_revision_review_decision_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id JOIN contract.revision_review_request p ON p.tenant_id=v.tenant_id AND p.contract_revision_id=v.contract_revision_id WHERE p.tenant_id=NEW.tenant_id AND p.revision_review_request_id=NEW.request_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_review_decision_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_review_decision__negotiation_guard BEFORE INSERT ON contract.revision_review_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_review_decision_negotiation_guard();
CREATE FUNCTION contract.fn_revision_review_binding_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_review_binding_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_review_binding__negotiation_guard BEFORE INSERT ON contract.revision_review_binding FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_review_binding_negotiation_guard();
CREATE FUNCTION contract.fn_revision_approval_requirement_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_approval_requirement_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_approval_requirement__negotiation_guard BEFORE INSERT ON contract.revision_approval_requirement FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_approval_requirement_negotiation_guard();
CREATE FUNCTION contract.fn_revision_approval_decision_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id JOIN contract.revision_approval_requirement p ON p.tenant_id=v.tenant_id AND p.contract_revision_id=v.contract_revision_id WHERE p.tenant_id=NEW.tenant_id AND p.revision_approval_requirement_id=NEW.requirement_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_revision_approval_decision_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_revision_approval_decision__negotiation_guard BEFORE INSERT ON contract.revision_approval_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_revision_approval_decision_negotiation_guard();
CREATE FUNCTION contract.fn_signature_readiness_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_readiness_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_readiness__negotiation_guard BEFORE INSERT ON contract.signature_readiness FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_readiness_negotiation_guard();
CREATE FUNCTION contract.fn_signature_arrangement_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_arrangement_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_arrangement__negotiation_guard BEFORE INSERT ON contract.signature_arrangement FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_arrangement_negotiation_guard();
CREATE FUNCTION contract.fn_signature_draft_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_draft_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_draft__negotiation_guard BEFORE INSERT ON contract.signature_draft FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_draft_negotiation_guard();
CREATE FUNCTION contract.fn_signature_submission_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_submission_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_submission__negotiation_guard BEFORE INSERT ON contract.signature_submission FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_submission_negotiation_guard();
CREATE FUNCTION contract.fn_signature_verification_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_verification_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_verification__negotiation_guard BEFORE INSERT ON contract.signature_verification FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_verification_negotiation_guard();
CREATE FUNCTION contract.fn_signature_archive_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_archive_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_archive__negotiation_guard BEFORE INSERT ON contract.signature_archive FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_archive_negotiation_guard();
CREATE FUNCTION contract.fn_signature_revision_return_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_revision_return_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_revision_return__negotiation_guard BEFORE INSERT ON contract.signature_revision_return FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_revision_return_negotiation_guard();
CREATE FUNCTION contract.fn_signature_workflow_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_workflow_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_workflow__negotiation_guard BEFORE INSERT ON contract.signature_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_workflow_negotiation_guard();
CREATE FUNCTION contract.fn_signature_handoff_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_signature_handoff_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_signature_handoff__negotiation_guard BEFORE INSERT ON contract.signature_handoff FOR EACH ROW EXECUTE FUNCTION contract.fn_signature_handoff_negotiation_guard();
CREATE FUNCTION contract.fn_transfer_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION contract.fn_transfer_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer__negotiation_guard BEFORE INSERT ON transfer.transfer_request FOR EACH ROW EXECUTE FUNCTION contract.fn_transfer_negotiation_guard();
CREATE FUNCTION contract.fn_transfer_negotiation_lock() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=OLD.tenant_id AND opportunity_id=OLD.opportunity_id FOR UPDATE;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_transfer_negotiation_lock() IS 'Serialize existing transfer processing with sales disposition without stopping independent processing.';
REVOKE ALL ON FUNCTION contract.fn_transfer_negotiation_lock() FROM PUBLIC;
CREATE TRIGGER trg_transfer__negotiation_lock BEFORE UPDATE ON transfer.transfer_request FOR EACH ROW EXECUTE FUNCTION contract.fn_transfer_negotiation_lock();


CREATE FUNCTION contract.fn_payment_negotiation_lock() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.opportunity o JOIN contract.contract c ON c.tenant_id=o.tenant_id AND c.opportunity_id=o.opportunity_id WHERE c.tenant_id=NEW.tenant_id AND c.contract_id=NEW.contract_id FOR UPDATE OF o;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_payment_negotiation_lock() IS 'Serialize independent receipts with sales disposition without blocking receipts after sales handling stops.';
REVOKE ALL ON FUNCTION contract.fn_payment_negotiation_lock() FROM PUBLIC;
CREATE TRIGGER trg_payment__negotiation_lock BEFORE INSERT ON contract.payment_confirmation FOR EACH ROW EXECUTE FUNCTION contract.fn_payment_negotiation_lock();

CREATE FUNCTION responsibility.fn_contract_task_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
 BEGIN
  IF NEW.subject_type='opportunity.opportunity' AND NEW.business_purpose_code IN ('REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW','DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT','ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE','VERIFY_CONTRACT_SIGNATURE','ARCHIVE_CONTRACT_SIGNATURE') THEN
   PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.subject_id);
  END IF;
  RETURN NEW;
 END; $fn$;
REVOKE ALL ON FUNCTION responsibility.fn_contract_task_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_task__contract_negotiation_guard BEFORE INSERT ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION responsibility.fn_contract_task_negotiation_guard();
