CREATE TABLE contract.execution_verification (
    tenant_id uuid NOT NULL,
    execution_verification_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    handoff_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_execution_verification PRIMARY KEY (tenant_id, execution_verification_id),
    CONSTRAINT ck_execution_verification__revision CHECK (revision=0),
    CONSTRAINT ck_execution_verification__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_execution_verification__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.execution_verification IS 'Fact Owner：ContractRuntime；执行条件办理不可变事实；不代替到账或合同执行。';
COMMENT ON CONSTRAINT pk_execution_verification ON contract.execution_verification IS '主键：在租户内唯一标识一条execution_verification记录。';
COMMENT ON INDEX contract.pk_execution_verification IS '主键：在租户内唯一标识一条execution_verification记录。';
COMMENT ON COLUMN contract.execution_verification.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.execution_verification.execution_verification_id IS '执行条件办理不可变事实；不代替到账或合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.execution_verification.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.execution_verification.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.execution_verification.opportunity_id IS '准确销售主线。';
COMMENT ON COLUMN contract.execution_verification.handoff_id IS '准确签署归档交接。';
COMMENT ON COLUMN contract.execution_verification.contract_revision_id IS '批准合同版本。';
COMMENT ON COLUMN contract.execution_verification.recorded_by IS '人工核验任职。';
COMMENT ON COLUMN contract.execution_verification.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.execution_verification.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.execution_verification.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_execution_verification__revision ON contract.execution_verification IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_execution_verification__body ON contract.execution_verification IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_execution_verification__body_digest_length ON contract.execution_verification IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.execution_workflow (
    tenant_id uuid NOT NULL,
    execution_workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    handoff_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    previous_workflow_id uuid,
    stage_code varchar(64) NOT NULL,
    owner_appointment_id uuid,
    task_id uuid,
    verification_id uuid,
    execution_id uuid,
    recorded_by uuid NOT NULL,
    due_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_execution_workflow PRIMARY KEY (tenant_id, execution_workflow_id),
    CONSTRAINT ck_execution_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_execution_workflow__previous UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_execution_workflow__stage CHECK (stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT','READY_TRANSFER','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT') AND owner_appointment_id IS NOT NULL AND task_id IS NOT NULL) OR (stage_code IN ('READY_TRANSFER','OWNER_EXCEPTION') AND task_id IS NULL)) AND (stage_code='READY_TRANSFER')=(execution_id IS NOT NULL) AND (stage_code<>'READY_TRANSFER' OR verification_id IS NOT NULL))
);

COMMENT ON TABLE contract.execution_workflow IS 'Fact Owner：ContractRuntime；执行条件办理不可变事实；不代替到账或合同执行。';
COMMENT ON CONSTRAINT pk_execution_workflow ON contract.execution_workflow IS '主键：在租户内唯一标识一条execution_workflow记录。';
COMMENT ON INDEX contract.pk_execution_workflow IS '主键：在租户内唯一标识一条execution_workflow记录。';
COMMENT ON COLUMN contract.execution_workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.execution_workflow.execution_workflow_id IS '执行条件办理不可变事实；不代替到账或合同执行。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.execution_workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.execution_workflow.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.execution_workflow.opportunity_id IS '准确销售主线。';
COMMENT ON COLUMN contract.execution_workflow.handoff_id IS '唯一归档交接来源。';
COMMENT ON COLUMN contract.execution_workflow.contract_revision_id IS '当前批准合同版本。';
COMMENT ON COLUMN contract.execution_workflow.previous_workflow_id IS '直接前序责任；根为空。';
COMMENT ON COLUMN contract.execution_workflow.stage_code IS '执行条件办理阶段。';
COMMENT ON COLUMN contract.execution_workflow.owner_appointment_id IS '合格责任人；异常为空。';
COMMENT ON COLUMN contract.execution_workflow.task_id IS '可办理责任；异常或完成为空。';
COMMENT ON COLUMN contract.execution_workflow.verification_id IS '已人工核验条件。';
COMMENT ON COLUMN contract.execution_workflow.execution_id IS '满足门禁后的合同执行事实。';
COMMENT ON COLUMN contract.execution_workflow.recorded_by IS '办理者或恢复服务任职。';
COMMENT ON COLUMN contract.execution_workflow.due_at IS '首次交接确定的原期限，恢复不延长。';
COMMENT ON COLUMN contract.execution_workflow.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_execution_workflow__revision ON contract.execution_workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_execution_workflow__previous ON contract.execution_workflow IS '只允许一个后继。';
COMMENT ON INDEX contract.uq_execution_workflow__previous IS '只允许一个后继。';
COMMENT ON CONSTRAINT ck_execution_workflow__stage ON contract.execution_workflow IS '阶段与可办理责任一致。';
ALTER TABLE contract.execution_verification
    ADD CONSTRAINT fk_execution_verification__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_verification__tenant ON contract.execution_verification IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.execution_verification
    ADD CONSTRAINT fk_execution_verification__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_verification__opportunity_id ON contract.execution_verification IS '同租户准确事实引用。';
ALTER TABLE contract.execution_verification
    ADD CONSTRAINT fk_execution_verification__handoff_id
    FOREIGN KEY (tenant_id, handoff_id)
    REFERENCES contract.signature_handoff (tenant_id, signature_handoff_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_verification__handoff_id ON contract.execution_verification IS '同租户准确事实引用。';
ALTER TABLE contract.execution_verification
    ADD CONSTRAINT fk_execution_verification__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_verification__contract_revision_id ON contract.execution_verification IS '同租户准确事实引用。';
ALTER TABLE contract.execution_verification
    ADD CONSTRAINT fk_execution_verification__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_verification__recorded_by ON contract.execution_verification IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__tenant ON contract.execution_workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__opportunity_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__handoff_id
    FOREIGN KEY (tenant_id, handoff_id)
    REFERENCES contract.signature_handoff (tenant_id, signature_handoff_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__handoff_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__contract_revision_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES contract.execution_workflow (tenant_id, execution_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__previous_workflow_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__owner_appointment_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__task_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__verification_id
    FOREIGN KEY (tenant_id, verification_id)
    REFERENCES contract.execution_verification (tenant_id, execution_verification_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__verification_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__execution_id
    FOREIGN KEY (tenant_id, execution_id)
    REFERENCES contract.contract_execution (tenant_id, contract_execution_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__execution_id ON contract.execution_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.execution_workflow
    ADD CONSTRAINT fk_execution_workflow__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_execution_workflow__recorded_by ON contract.execution_workflow IS '同租户准确事实引用。';
CREATE TRIGGER trg_execution_verification__immutable BEFORE UPDATE OR DELETE ON contract.execution_verification FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_execution_verification__transaction BEFORE INSERT ON contract.execution_verification FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.execution_verification FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.execution_verification TO ${app_command_role};
GRANT SELECT ON contract.execution_verification TO ${app_query_role};
CREATE TRIGGER trg_execution_workflow__immutable BEFORE UPDATE OR DELETE ON contract.execution_workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_execution_workflow__transaction BEFORE INSERT ON contract.execution_workflow FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.execution_workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.execution_workflow TO ${app_command_role};
GRANT SELECT ON contract.execution_workflow TO ${app_query_role};

CREATE FUNCTION contract.fn_execution_basis_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE h contract.signature_handoff%ROWTYPE; r contract.signature_readiness%ROWTYPE; k contract.contract%ROWTYPE;
BEGIN
 PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id);
 SELECT * INTO h FROM contract.signature_handoff WHERE tenant_id=NEW.tenant_id AND signature_handoff_id=NEW.handoff_id;
 SELECT * INTO r FROM contract.signature_readiness WHERE tenant_id=NEW.tenant_id AND signature_readiness_id=h.readiness_id;
 SELECT c.* INTO k FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id FOR UPDATE OF c;
 IF h.signature_handoff_id IS NULL OR h.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.current_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.approved_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.opportunity_id IS DISTINCT FROM NEW.opportunity_id THEN RAISE EXCEPTION 'execution handoff differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_execution_basis_guard() FROM PUBLIC;
CREATE TRIGGER trg_execution_workflow__basis BEFORE INSERT ON contract.execution_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_execution_basis_guard();
CREATE TRIGGER trg_execution_verification__basis BEFORE INSERT ON contract.execution_verification FOR EACH ROW EXECUTE FUNCTION contract.fn_execution_basis_guard();
CREATE FUNCTION contract.fn_execution_workflow_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE p contract.execution_workflow%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; v contract.execution_verification%ROWTYPE; e contract.contract_execution%ROWTYPE;
BEGIN
 IF NEW.previous_workflow_id IS NOT NULL THEN
  SELECT * INTO p FROM contract.execution_workflow WHERE tenant_id=NEW.tenant_id AND execution_workflow_id=NEW.previous_workflow_id;
  IF p.execution_workflow_id IS NULL OR p.handoff_id IS DISTINCT FROM NEW.handoff_id OR p.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR p.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id OR p.stage_code='READY_TRANSFER' THEN RAISE EXCEPTION 'execution predecessor differs' USING ERRCODE='23514'; END IF;
  IF p.due_at IS DISTINCT FROM NEW.due_at THEN RAISE EXCEPTION 'original execution deadline changed' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.task_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id;
  IF t.task_occurrence_id IS NULL OR t.business_purpose_code<>'CHECK_CONTRACT_EXECUTION' OR t.subject_type<>'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR t.original_sla_due_at IS DISTINCT FROM NEW.due_at OR t.state IS DISTINCT FROM (CASE WHEN NEW.stage_code='WAIT_RECEIPT' THEN 'WAITING' ELSE 'OPEN' END) THEN RAISE EXCEPTION 'execution task differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.verification_id IS NOT NULL THEN
  SELECT * INTO v FROM contract.execution_verification WHERE tenant_id=NEW.tenant_id AND execution_verification_id=NEW.verification_id;
  IF v.handoff_id IS DISTINCT FROM NEW.handoff_id OR v.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id THEN RAISE EXCEPTION 'execution verification differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.execution_id IS NOT NULL THEN
  SELECT * INTO e FROM contract.contract_execution WHERE tenant_id=NEW.tenant_id AND contract_execution_id=NEW.execution_id;
  IF e.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id THEN RAISE EXCEPTION 'execution fact differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_execution_workflow_guard() FROM PUBLIC;
CREATE TRIGGER trg_execution_workflow__exact BEFORE INSERT ON contract.execution_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_execution_workflow_guard();
DO $v1040$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v18',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v17';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1040 requires 52-plus-2-r2-v17' USING ERRCODE='55000'; END IF;
END;
$v1040$;
ALTER TABLE contract.contract_execution ALTER COLUMN archive_evidence_submission_id DROP NOT NULL;
ALTER TABLE contract.contract_execution ADD COLUMN execution_verification_id uuid;
COMMENT ON COLUMN contract.contract_execution.execution_verification_id IS 'R2准确归档后的人工执行条件核验；与旧归档证据互斥。';
ALTER TABLE contract.contract_execution ADD CONSTRAINT ck_contract_execution__manual CHECK (num_nonnulls(archive_evidence_submission_id,execution_verification_id)=1);
COMMENT ON CONSTRAINT ck_contract_execution__manual ON contract.contract_execution IS '保留完整旧执行依据；具名R2执行必须引用人工核验。';
ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__execution_verification_id
    FOREIGN KEY (tenant_id, execution_verification_id)
    REFERENCES contract.execution_verification (tenant_id, execution_verification_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__execution_verification_id ON contract.contract_execution IS '同租户准确事实引用。';

CREATE FUNCTION contract.fn_assert_manual_execution(e contract.contract_execution) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE v contract.execution_verification%ROWTYPE; h contract.signature_handoff%ROWTYPE; r contract.contract_revision%ROWTYPE; b contract.revision_review_binding%ROWTYPE;
BEGIN
 SELECT * INTO v FROM contract.execution_verification WHERE tenant_id=e.tenant_id AND execution_verification_id=e.execution_verification_id;
 SELECT * INTO h FROM contract.signature_handoff WHERE tenant_id=e.tenant_id AND signature_handoff_id=v.handoff_id;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=e.tenant_id AND contract_revision_id=e.contract_revision_id;
 SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=e.tenant_id AND contract_revision_id=e.contract_revision_id;
 IF v.execution_verification_id IS NULL OR v.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR v.contract_revision_id IS DISTINCT FROM e.contract_revision_id OR v.recorded_by IS DISTINCT FROM e.executed_by_appointment_id OR h.signature_handoff_id IS NULL OR r.contract_id IS DISTINCT FROM e.contract_id OR r.package_contract_code IS DISTINCT FROM 'R2_CONTRACT_PREPARATION_V1' THEN RAISE EXCEPTION 'exact current human execution verification required' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_require_negotiation_active(e.tenant_id,v.opportunity_id);
 PERFORM contract.fn_assert_r2_approval(e.tenant_id,e.contract_id,e.contract_revision_id);
 IF b.revision_review_binding_id IS NULL OR e.review_scope_hash IS DISTINCT FROM b.scope_hash OR e.review_resolution_digest IS DISTINCT FROM b.resolution_digest THEN RAISE EXCEPTION 'execution review differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM contract.signature_archive a JOIN contract.signature_readiness s ON s.tenant_id=a.tenant_id AND s.signature_readiness_id=h.readiness_id WHERE a.tenant_id=e.tenant_id AND a.signature_archive_id=h.archive_id AND a.arrangement_id=h.arrangement_id AND s.contract_revision_id=e.contract_revision_id)
 OR NOT EXISTS(SELECT 1 FROM contract.signature_workflow w WHERE w.tenant_id=e.tenant_id AND w.readiness_id=h.readiness_id AND w.arrangement_id=h.arrangement_id AND w.stage_code='SIGNATURE_COMPLETE' AND NOT EXISTS(SELECT 1 FROM contract.signature_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.signature_workflow_id))
 OR NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=e.tenant_id AND p.arrangement_id=h.arrangement_id AND p.required)
 OR EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=e.tenant_id AND p.arrangement_id=h.arrangement_id AND p.required AND NOT EXISTS(SELECT 1 FROM contract.contract_signature s JOIN contract.signature_verification x ON x.tenant_id=s.tenant_id AND x.signature_verification_id=s.verification_id WHERE s.tenant_id=p.tenant_id AND s.signature_plan_id=p.signature_plan_id AND s.contract_revision_id=e.contract_revision_id AND s.revoked_at IS NULL AND s.signed_content_digest=r.body_sha256 AND x.decision_code='VERIFIED')) THEN RAISE EXCEPTION 'exact archived signature package required' USING ERRCODE='23514'; END IF;
 IF r.receipt_required_before_transfer AND EXISTS(SELECT 1 FROM contract.payment_confirmation p WHERE p.tenant_id=e.tenant_id AND p.contract_id=e.contract_id AND p.contract_revision_id=e.contract_revision_id AND (p.confirmation_type<>'RECEIPT' OR p.currency_code<>'CNY')) THEN RAISE EXCEPTION 'receipt disposition outside R2 execution scope' USING ERRCODE='23514'; END IF;
 IF r.receipt_required_before_transfer AND COALESCE((SELECT sum(p.amount_minor) FROM contract.payment_confirmation p WHERE p.tenant_id=e.tenant_id AND p.contract_id=e.contract_id AND p.contract_revision_id=e.contract_revision_id AND p.confirmation_type='RECEIPT' AND p.currency_code='CNY'),0)<r.required_amount_minor THEN RAISE EXCEPTION 'required contract receipt not satisfied' USING ERRCODE='23514'; END IF;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_assert_manual_execution(contract.contract_execution) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_assert_manual_execution(contract.contract_execution) TO ${app_command_role};
CREATE OR REPLACE FUNCTION contract.fn_reject_r2_preparation_execution() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF EXISTS(SELECT 1 FROM contract.contract_revision r WHERE r.tenant_id=NEW.tenant_id AND r.contract_revision_id=NEW.contract_revision_id AND r.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN
  IF NEW.execution_verification_id IS NULL THEN RAISE EXCEPTION 'R2 preparation requires exact manual execution verification' USING ERRCODE='55000'; END IF;
  PERFORM contract.fn_assert_manual_execution(NEW);
 ELSIF NEW.execution_verification_id IS NOT NULL THEN RAISE EXCEPTION 'manual execution requires R2 approved version' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_reject_r2_preparation_execution() IS 'V1040具名人工执行：准确批准、归档、同事务人工核验及约定首款；旧执行包守卫保留。';
CREATE OR REPLACE FUNCTION platform_meta.fn_assert_contract_execution_package()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1 FROM contract.contract contract_root
    WHERE contract_root.tenant_id = NEW.tenant_id
      AND contract_root.contract_id = NEW.contract_id
      AND contract_root.current_revision_id = NEW.contract_revision_id
      AND contract_root.approved_revision_id = NEW.contract_revision_id
      AND contract_root.contract_execution_id = NEW.contract_execution_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract execution must fill the exact current approved anchor slot in the same transaction' USING ERRCODE = '23514';
    END IF;
    IF NEW.execution_verification_id IS NOT NULL THEN
        PERFORM contract.fn_assert_manual_execution(NEW);
        RETURN NEW;
    END IF;
    IF EXISTS (
        SELECT 1 FROM contract.signature_plan plan
        WHERE plan.tenant_id = NEW.tenant_id
          AND plan.contract_revision_id = NEW.contract_revision_id
          AND plan.required
          AND NOT EXISTS (
              SELECT 1
              FROM contract.contract_signature signature
              JOIN contract.contract_revision contract_revision
                ON contract_revision.tenant_id = signature.tenant_id
               AND contract_revision.contract_revision_id = signature.contract_revision_id
              WHERE signature.tenant_id = plan.tenant_id
                AND signature.signature_plan_id = plan.signature_plan_id
                AND signature.contract_revision_id = plan.contract_revision_id
                AND signature.revoked_at IS NULL
                AND signature.signed_content_digest = contract_revision.content_digest
          )
    ) THEN
        RAISE EXCEPTION 'contract execution requires one active exact-content signature for every required plan' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TABLE contract.payment_request (
    tenant_id uuid NOT NULL,
    payment_request_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    handoff_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    material_version_id uuid,
    recorded_by uuid NOT NULL,
    due_at timestamptz(6) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_payment_request PRIMARY KEY (tenant_id, payment_request_id),
    CONSTRAINT ck_payment_request__revision CHECK (revision=0),
    CONSTRAINT ck_payment_request__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_payment_request__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.payment_request IS 'Fact Owner：ContractRuntime；收款办理不可变事实；独立于销售执行责任。';
COMMENT ON CONSTRAINT pk_payment_request ON contract.payment_request IS '主键：在租户内唯一标识一条payment_request记录。';
COMMENT ON INDEX contract.pk_payment_request IS '主键：在租户内唯一标识一条payment_request记录。';
COMMENT ON COLUMN contract.payment_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.payment_request.payment_request_id IS '收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.payment_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.payment_request.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.payment_request.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.payment_request.handoff_id IS '准确签署归档交接。';
COMMENT ON COLUMN contract.payment_request.contract_revision_id IS '准确批准合同版本。';
COMMENT ON COLUMN contract.payment_request.material_version_id IS '后续逐笔核对的明确凭证；首项可空。';
COMMENT ON COLUMN contract.payment_request.recorded_by IS '发起任职。';
COMMENT ON COLUMN contract.payment_request.due_at IS '本次责任原期限。';
COMMENT ON COLUMN contract.payment_request.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.payment_request.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.payment_request.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_payment_request__revision ON contract.payment_request IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_payment_request__body ON contract.payment_request IS '有界受保护正文。';
COMMENT ON CONSTRAINT ck_payment_request__body_digest_length ON contract.payment_request IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.payment_workflow (
    tenant_id uuid NOT NULL,
    payment_workflow_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    request_id uuid NOT NULL,
    previous_workflow_id uuid,
    stage_code varchar(64) NOT NULL,
    target_stage_code varchar(64) NOT NULL,
    owner_appointment_id uuid,
    task_id uuid,
    review_id uuid,
    recorded_by uuid NOT NULL,
    due_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_payment_workflow PRIMARY KEY (tenant_id, payment_workflow_id),
    CONSTRAINT ck_payment_workflow__revision CHECK (revision=0),
    CONSTRAINT uq_payment_workflow__previous UNIQUE (tenant_id, previous_workflow_id),
    CONSTRAINT ck_payment_workflow__stage CHECK (target_stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE') AND (stage_code=target_stage_code OR (stage_code='OWNER_EXCEPTION' AND target_stage_code<>'COMPLETE')) AND stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL) OR (stage_code IN ('COMPLETE','OWNER_EXCEPTION') AND task_id IS NULL)))
);

COMMENT ON TABLE contract.payment_workflow IS 'Fact Owner：ContractRuntime；收款办理不可变事实；独立于销售执行责任。';
COMMENT ON CONSTRAINT pk_payment_workflow ON contract.payment_workflow IS '主键：在租户内唯一标识一条payment_workflow记录。';
COMMENT ON INDEX contract.pk_payment_workflow IS '主键：在租户内唯一标识一条payment_workflow记录。';
COMMENT ON COLUMN contract.payment_workflow.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.payment_workflow.payment_workflow_id IS '收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.payment_workflow.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.payment_workflow.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.payment_workflow.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.payment_workflow.request_id IS '本次核对请求。';
COMMENT ON COLUMN contract.payment_workflow.previous_workflow_id IS '直接前序。';
COMMENT ON COLUMN contract.payment_workflow.stage_code IS '财务核对、销售补正、结束或责任异常。';
COMMENT ON COLUMN contract.payment_workflow.target_stage_code IS '责任异常时仍保留原办理环节。';
COMMENT ON COLUMN contract.payment_workflow.owner_appointment_id IS '责任任职。';
COMMENT ON COLUMN contract.payment_workflow.task_id IS '办理事项。';
COMMENT ON COLUMN contract.payment_workflow.review_id IS '触发本次变化的核对事实。';
COMMENT ON COLUMN contract.payment_workflow.recorded_by IS '记录任职。';
COMMENT ON COLUMN contract.payment_workflow.due_at IS '原请求期限，不随补正重置。';
COMMENT ON COLUMN contract.payment_workflow.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_payment_workflow__revision ON contract.payment_workflow IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_payment_workflow__previous ON contract.payment_workflow IS '前序唯一后继。';
COMMENT ON INDEX contract.uq_payment_workflow__previous IS '前序唯一后继。';
COMMENT ON CONSTRAINT ck_payment_workflow__stage ON contract.payment_workflow IS '办理责任与阶段一致。';
CREATE TABLE contract.payment_review (
    tenant_id uuid NOT NULL,
    payment_review_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    decision_code varchar(64) NOT NULL,
    material_version_id uuid,
    confirmation_id uuid,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_payment_review PRIMARY KEY (tenant_id, payment_review_id),
    CONSTRAINT ck_payment_review__revision CHECK (revision=0),
    CONSTRAINT ck_payment_review__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_payment_review__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT uq_payment_review__confirmation UNIQUE (tenant_id, confirmation_id),
    CONSTRAINT ck_payment_review__decision CHECK (decision_code IN ('CONFIRMED','RETURNED','SUPPLEMENTED') AND (decision_code='CONFIRMED')=(confirmation_id IS NOT NULL) AND (decision_code='RETURNED' OR material_version_id IS NOT NULL)),
    CONSTRAINT ck_payment_review__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.payment_review IS 'Fact Owner：ContractRuntime；收款办理不可变事实；独立于销售执行责任。';
COMMENT ON CONSTRAINT pk_payment_review ON contract.payment_review IS '主键：在租户内唯一标识一条payment_review记录。';
COMMENT ON INDEX contract.pk_payment_review IS '主键：在租户内唯一标识一条payment_review记录。';
COMMENT ON COLUMN contract.payment_review.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.payment_review.payment_review_id IS '收款办理不可变事实；独立于销售执行责任。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.payment_review.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.payment_review.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN contract.payment_review.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.payment_review.workflow_id IS '核对的准确当前责任。';
COMMENT ON COLUMN contract.payment_review.decision_code IS '确认到账、退回或补正。';
COMMENT ON COLUMN contract.payment_review.material_version_id IS '准确材料；退回不要求。';
COMMENT ON COLUMN contract.payment_review.confirmation_id IS '本笔到账；退回及补正不得填写。';
COMMENT ON COLUMN contract.payment_review.recorded_by IS '实际办理任职。';
COMMENT ON COLUMN contract.payment_review.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.payment_review.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.payment_review.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_payment_review__revision ON contract.payment_review IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_payment_review__body ON contract.payment_review IS '有界受保护正文。';
COMMENT ON CONSTRAINT uq_payment_review__workflow ON contract.payment_review IS '同一责任版本只记录一次结果。';
COMMENT ON INDEX contract.uq_payment_review__workflow IS '同一责任版本只记录一次结果。';
COMMENT ON CONSTRAINT uq_payment_review__confirmation ON contract.payment_review IS '同一到账只归入一次核对。';
COMMENT ON INDEX contract.uq_payment_review__confirmation IS '同一到账只归入一次核对。';
COMMENT ON CONSTRAINT ck_payment_review__decision ON contract.payment_review IS '退回补正不能伪造到账。';
COMMENT ON CONSTRAINT ck_payment_review__body_digest_length ON contract.payment_review IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__tenant ON contract.payment_request IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__opportunity_id ON contract.payment_request IS '同租户准确事实引用。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__handoff_id
    FOREIGN KEY (tenant_id, handoff_id)
    REFERENCES contract.signature_handoff (tenant_id, signature_handoff_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__handoff_id ON contract.payment_request IS '同租户准确事实引用。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__contract_revision_id
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__contract_revision_id ON contract.payment_request IS '同租户准确事实引用。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__material_version_id ON contract.payment_request IS '同租户准确事实引用。';
ALTER TABLE contract.payment_request
    ADD CONSTRAINT fk_payment_request__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_request__recorded_by ON contract.payment_request IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__tenant ON contract.payment_workflow IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__opportunity_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__request_id
    FOREIGN KEY (tenant_id, request_id)
    REFERENCES contract.payment_request (tenant_id, payment_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__request_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__previous_workflow_id
    FOREIGN KEY (tenant_id, previous_workflow_id)
    REFERENCES contract.payment_workflow (tenant_id, payment_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__previous_workflow_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__owner_appointment_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__task_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__review_id
    FOREIGN KEY (tenant_id, review_id)
    REFERENCES contract.payment_review (tenant_id, payment_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__review_id ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_workflow
    ADD CONSTRAINT fk_payment_workflow__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_workflow__recorded_by ON contract.payment_workflow IS '同租户准确事实引用。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__tenant ON contract.payment_review IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__opportunity_id ON contract.payment_review IS '同租户准确事实引用。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES contract.payment_workflow (tenant_id, payment_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__workflow_id ON contract.payment_review IS '同租户准确事实引用。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__material_version_id
    FOREIGN KEY (tenant_id, material_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__material_version_id ON contract.payment_review IS '同租户准确事实引用。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__confirmation_id
    FOREIGN KEY (tenant_id, confirmation_id)
    REFERENCES contract.payment_confirmation (tenant_id, payment_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__confirmation_id ON contract.payment_review IS '同租户准确事实引用。';
ALTER TABLE contract.payment_review
    ADD CONSTRAINT fk_payment_review__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_review__recorded_by ON contract.payment_review IS '同租户准确事实引用。';
CREATE TRIGGER trg_payment_request__immutable BEFORE UPDATE OR DELETE ON contract.payment_request FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_payment_request__transaction BEFORE INSERT ON contract.payment_request FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.payment_request FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.payment_request TO ${app_command_role};
GRANT SELECT ON contract.payment_request TO ${app_query_role};
CREATE TRIGGER trg_payment_workflow__immutable BEFORE UPDATE OR DELETE ON contract.payment_workflow FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_payment_workflow__transaction BEFORE INSERT ON contract.payment_workflow FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.payment_workflow FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.payment_workflow TO ${app_command_role};
GRANT SELECT ON contract.payment_workflow TO ${app_query_role};
CREATE TRIGGER trg_payment_review__immutable BEFORE UPDATE OR DELETE ON contract.payment_review FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_payment_review__transaction BEFORE INSERT ON contract.payment_review FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON contract.payment_review FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON contract.payment_review TO ${app_command_role};
GRANT SELECT ON contract.payment_review TO ${app_query_role};

CREATE FUNCTION contract.fn_payment_request_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE h contract.signature_handoff%ROWTYPE; r contract.signature_readiness%ROWTYPE; k contract.contract%ROWTYPE;
BEGIN
 SELECT * INTO h FROM contract.signature_handoff WHERE tenant_id=NEW.tenant_id AND signature_handoff_id=NEW.handoff_id;
 SELECT * INTO r FROM contract.signature_readiness WHERE tenant_id=NEW.tenant_id AND signature_readiness_id=h.readiness_id;
 SELECT c.* INTO k FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id FOR UPDATE OF c;
 IF h.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.current_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.approved_revision_id IS DISTINCT FROM NEW.contract_revision_id THEN RAISE EXCEPTION 'payment request basis differs' USING ERRCODE='23514'; END IF;
 IF NEW.material_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.material_version m WHERE m.tenant_id=NEW.tenant_id AND m.material_version_id=NEW.material_version_id AND m.opportunity_id=NEW.opportunity_id AND m.evidence_submission_id IS NOT NULL) THEN RAISE EXCEPTION 'payment evidence differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION contract.fn_payment_workflow_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.payment_request%ROWTYPE; p contract.payment_workflow%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; d contract.payment_review%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.payment_request WHERE tenant_id=NEW.tenant_id AND payment_request_id=NEW.request_id;
 IF r.opportunity_id IS DISTINCT FROM NEW.opportunity_id THEN RAISE EXCEPTION 'payment workflow request differs' USING ERRCODE='23514'; END IF;
 IF r.due_at IS DISTINCT FROM NEW.due_at THEN RAISE EXCEPTION 'payment original deadline changed' USING ERRCODE='23514'; END IF;
 IF NEW.previous_workflow_id IS NOT NULL THEN
  SELECT * INTO p FROM contract.payment_workflow WHERE tenant_id=NEW.tenant_id AND payment_workflow_id=NEW.previous_workflow_id;
  IF p.request_id IS DISTINCT FROM NEW.request_id OR p.stage_code='COMPLETE' THEN RAISE EXCEPTION 'payment workflow predecessor differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.review_id IS NOT NULL THEN
  SELECT * INTO d FROM contract.payment_review WHERE tenant_id=NEW.tenant_id AND payment_review_id=NEW.review_id;
  IF d.workflow_id IS DISTINCT FROM NEW.previous_workflow_id OR d.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'payment workflow review differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.stage_code='COMPLETE' AND (NEW.review_id IS NULL OR d.decision_code<>'CONFIRMED') THEN RAISE EXCEPTION 'payment completion review missing' USING ERRCODE='23514'; END IF;
 IF NEW.task_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id;
  IF t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR t.state IS DISTINCT FROM 'OPEN' OR t.original_sla_due_at IS DISTINCT FROM NEW.due_at OR t.business_purpose_code IS DISTINCT FROM (CASE WHEN NEW.stage_code='CHECK_RECEIPT' THEN 'CHECK_CONTRACT_RECEIPT' ELSE 'SUPPLEMENT_CONTRACT_RECEIPT' END) THEN RAISE EXCEPTION 'payment workflow task differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION contract.fn_payment_review_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w contract.payment_workflow%ROWTYPE; r contract.payment_request%ROWTYPE; p contract.payment_confirmation%ROWTYPE; m opportunity.material_version%ROWTYPE; t responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO w FROM contract.payment_workflow WHERE tenant_id=NEW.tenant_id AND payment_workflow_id=NEW.workflow_id;
 SELECT * INTO r FROM contract.payment_request WHERE tenant_id=NEW.tenant_id AND payment_request_id=w.request_id;
 SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=w.task_id;
 IF w.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.state IS DISTINCT FROM 'OPEN' OR EXISTS(SELECT 1 FROM contract.payment_workflow n WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=w.payment_workflow_id) OR (NEW.decision_code='SUPPLEMENTED' AND w.stage_code<>'SUPPLEMENT_RECEIPT') OR (NEW.decision_code<>'SUPPLEMENTED' AND w.stage_code<>'CHECK_RECEIPT') THEN RAISE EXCEPTION 'payment review responsibility differs' USING ERRCODE='23514'; END IF;
 IF NEW.material_version_id IS NOT NULL THEN
  SELECT * INTO m FROM opportunity.material_version WHERE tenant_id=NEW.tenant_id AND material_version_id=NEW.material_version_id;
  IF m.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR m.evidence_submission_id IS NULL THEN RAISE EXCEPTION 'payment evidence differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.confirmation_id IS NOT NULL THEN
  SELECT * INTO p FROM contract.payment_confirmation WHERE tenant_id=NEW.tenant_id AND payment_confirmation_id=NEW.confirmation_id;
  IF p.contract_revision_id IS DISTINCT FROM r.contract_revision_id OR p.confirmation_type IS DISTINCT FROM 'RECEIPT' OR p.evidence_submission_id IS DISTINCT FROM m.evidence_submission_id OR p.recorded_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR p.confirmed_at IS DISTINCT FROM NEW.created_at THEN RAISE EXCEPTION 'payment review confirmation differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION contract.fn_payment_request_guard(),contract.fn_payment_workflow_guard(),contract.fn_payment_review_guard() FROM PUBLIC;
CREATE TRIGGER trg_payment_request__exact BEFORE INSERT ON contract.payment_request FOR EACH ROW EXECUTE FUNCTION contract.fn_payment_request_guard();
CREATE TRIGGER trg_payment_workflow__exact BEFORE INSERT ON contract.payment_workflow FOR EACH ROW EXECUTE FUNCTION contract.fn_payment_workflow_guard();
CREATE TRIGGER trg_payment_review__exact BEFORE INSERT ON contract.payment_review FOR EACH ROW EXECUTE FUNCTION contract.fn_payment_review_guard();
