CREATE TABLE opportunity.quote_preparation_intent (
    tenant_id uuid NOT NULL,
    quote_preparation_intent_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    customer_confirmation_id uuid NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    workflow_id uuid NOT NULL,
    task_id uuid NOT NULL,
    prior_task_id uuid,
    requested_by uuid NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_preparation_intent PRIMARY KEY (tenant_id, quote_preparation_intent_id),
    CONSTRAINT ck_quote_preparation_intent__revision CHECK (revision=0),
    CONSTRAINT uq_quote_preparation_intent__opportunity UNIQUE (tenant_id, opportunity_id),
    CONSTRAINT uq_quote_preparation_intent__workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT ck_quote_preparation_intent__versions CHECK (opportunity_revision>=0 AND responsibility_revision>=0 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff'))
);

COMMENT ON TABLE opportunity.quote_preparation_intent IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_preparation_intent ON opportunity.quote_preparation_intent IS '主键：在租户内唯一标识一条quote_preparation_intent记录。';
COMMENT ON INDEX opportunity.pk_quote_preparation_intent IS '主键：在租户内唯一标识一条quote_preparation_intent记录。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.quote_preparation_intent_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.opportunity_revision IS '提交时商机版本。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.customer_confirmation_id IS '人工确认的准确客户资料。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.responsibility_type IS '准确责任来源类型。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.responsibility_id IS '准确责任来源。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.responsibility_revision IS '准确责任版本。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.workflow_id IS '本次报价准备工作流。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.task_id IS '接管后的报价准备待办。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.prior_task_id IS '被接管的普通跟进待办。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.requested_by IS '明确开始准备的任职。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.quote_preparation_intent.created_at IS '可信提交时间。';
COMMENT ON CONSTRAINT ck_quote_preparation_intent__revision ON opportunity.quote_preparation_intent IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_preparation_intent__opportunity ON opportunity.quote_preparation_intent IS '首次准备意图唯一。';
COMMENT ON INDEX opportunity.uq_quote_preparation_intent__opportunity IS '首次准备意图唯一。';
COMMENT ON CONSTRAINT uq_quote_preparation_intent__workflow ON opportunity.quote_preparation_intent IS '准备工作流唯一来源。';
COMMENT ON INDEX opportunity.uq_quote_preparation_intent__workflow IS '准备工作流唯一来源。';
COMMENT ON CONSTRAINT ck_quote_preparation_intent__versions ON opportunity.quote_preparation_intent IS '准确责任及版本。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__tenant ON opportunity.quote_preparation_intent IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__opportunity_id ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__customer_confirmation_id ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__workflow_id
    FOREIGN KEY (tenant_id, workflow_id)
    REFERENCES opportunity.quote_workflow (tenant_id, quote_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__workflow_id ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__task_id ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__prior_task_id ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_preparation_intent
    ADD CONSTRAINT fk_quote_preparation_intent__requested_by
    FOREIGN KEY (tenant_id, requested_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_preparation_intent__requested_by ON opportunity.quote_preparation_intent IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_workflow ALTER COLUMN quote_revision_id DROP NOT NULL;
ALTER TABLE opportunity.quote_workflow ADD CONSTRAINT ck_quote_workflow__initial_preparation CHECK (quote_revision_id IS NOT NULL OR (stage='PREPARE' AND previous_workflow_id IS NULL AND task_id IS NOT NULL AND next_check_at IS NULL));
COMMENT ON CONSTRAINT ck_quote_workflow__initial_preparation ON opportunity.quote_workflow IS '正式报价前仅允许具名准备责任。';

CREATE OR REPLACE FUNCTION opportunity.fn_check_quote_runtime() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE q opportunity.quote_revision%ROWTYPE; o opportunity.opportunity%ROWTYPE; p opportunity.quote_approval_policy%ROWTYPE; r opportunity.quote_approval_request%ROWTYPE; m opportunity.quote_approval_member%ROWTYPE; prior uuid;
BEGIN
 IF TG_TABLE_NAME='quote_workflow' THEN
 IF NEW.quote_revision_id IS NULL THEN
  SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
  IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.current_quote_revision_id IS NOT NULL OR EXISTS(SELECT 1 FROM opportunity.quote_workflow WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'initial quote preparation source differs' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 END IF;
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

CREATE TRIGGER trg_quote_preparation_intent__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_preparation_intent__mutation_guard ON opportunity.quote_preparation_intent IS '明确准备意图不可改写。';
CREATE TRIGGER trg_quote_preparation_intent__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_quote_preparation_intent__transaction_marker ON opportunity.quote_preparation_intent IS '本次命令顶层事务来源。';
REVOKE ALL ON opportunity.quote_preparation_intent FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_preparation_intent TO ${app_command_role};
GRANT SELECT ON opportunity.quote_preparation_intent TO ${app_query_role};
CREATE FUNCTION opportunity.fn_check_preparation_intent_source() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision<>NEW.opportunity_revision OR o.current_quote_revision_id IS NOT NULL THEN RAISE EXCEPTION 'preparation opportunity differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>o.opportunity_id OR NEW.responsibility_revision<>o.revision OR NEW.requested_by<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=o.opportunity_id) THEN RAISE EXCEPTION 'preparation responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=o.opportunity_id AND h.to_appointment_id=NEW.requested_by AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'preparation responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'preparation customer confirmation differs' USING ERRCODE='23514'; END IF;
 IF NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'preparation trusted time differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_preparation_intent_source() IS '锁定商机后验证准确版本、现任责任和本次客户确认。';
REVOKE ALL ON FUNCTION opportunity.fn_check_preparation_intent_source() FROM PUBLIC;
CREATE TRIGGER trg_quote_preparation_intent__current BEFORE INSERT ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent_source();
COMMENT ON TRIGGER trg_quote_preparation_intent__current ON opportunity.quote_preparation_intent IS '禁止旧资料及失效责任发起准备。';
CREATE FUNCTION opportunity.fn_check_preparation_intent() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w opportunity.quote_workflow%ROWTYPE; i opportunity.quote_preparation_intent%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='quote_workflow' THEN
  IF NEW.quote_revision_id IS NOT NULL THEN RETURN NEW; END IF;
  w=NEW;
  SELECT * INTO i FROM opportunity.quote_preparation_intent WHERE tenant_id=w.tenant_id AND workflow_id=w.quote_workflow_id;
 ELSE
  i=NEW;
  SELECT * INTO w FROM opportunity.quote_workflow WHERE tenant_id=i.tenant_id AND quote_workflow_id=i.workflow_id;
 END IF;
 IF i.quote_preparation_intent_id IS NULL OR w.quote_workflow_id IS NULL OR i.created_in_transaction<>pg_current_xact_id() OR w.quote_revision_id IS NOT NULL OR w.stage<>'PREPARE' OR w.opportunity_id<>i.opportunity_id OR w.task_id<>i.task_id OR w.owner_appointment_id<>i.requested_by OR w.prior_task_id IS DISTINCT FROM i.prior_task_id OR w.created_at<>i.created_at THEN RAISE EXCEPTION 'exact preparation intent required' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=i.tenant_id AND c.customer_requirement_confirmation_id=i.customer_confirmation_id AND c.opportunity_id=i.opportunity_id) OR NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=i.tenant_id AND t.task_occurrence_id=i.task_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=i.opportunity_id AND t.subject_revision=i.opportunity_revision AND t.owner_appointment_id=i.requested_by AND t.business_purpose_code='PREPARE_QUOTE') THEN RAISE EXCEPTION 'preparation customer or task differs' USING ERRCODE='23514'; END IF;
 IF i.prior_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=i.tenant_id AND t.task_occurrence_id=i.prior_task_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=i.opportunity_id AND t.business_purpose_code='PROGRESS_OPPORTUNITY' AND t.state='CANCELLED' AND t.cancellation_reason_code='QUOTE_WORKFLOW_TAKEOVER') THEN RAISE EXCEPTION 'preparation prior task not handed off' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_preparation_intent() IS '准备事实和任务接管必须同事务形成；不生成报价。';
REVOKE ALL ON FUNCTION opportunity.fn_check_preparation_intent() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_preparation_intent__source AFTER INSERT ON opportunity.quote_preparation_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent();
COMMENT ON TRIGGER trg_quote_preparation_intent__source ON opportunity.quote_preparation_intent IS '准确准备来源提交前校验。';
CREATE CONSTRAINT TRIGGER trg_quote_workflow__preparation_intent AFTER INSERT ON opportunity.quote_workflow DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent();
COMMENT ON TRIGGER trg_quote_workflow__preparation_intent ON opportunity.quote_workflow IS '正式报价前工作流必须有本次明确意图。';
DO $v1000$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v14',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v13';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1000 requires 52-plus-2-r2-v13' USING ERRCODE='55000'; END IF;
END;
$v1000$;
