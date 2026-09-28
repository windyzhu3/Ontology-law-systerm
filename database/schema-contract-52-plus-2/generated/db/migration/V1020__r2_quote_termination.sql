CREATE TABLE opportunity.quote_termination (
    tenant_id uuid NOT NULL,
    quote_termination_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    quote_workflow_id uuid NOT NULL,
    quote_revision_id uuid,
    closure_id uuid NOT NULL,
    recorded_by uuid NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_termination PRIMARY KEY (tenant_id, quote_termination_id),
    CONSTRAINT ck_quote_termination__revision CHECK (revision=0),
    CONSTRAINT uq_quote_termination__opportunity UNIQUE (tenant_id, opportunity_id),
    CONSTRAINT uq_quote_termination__closure UNIQUE (tenant_id, closure_id),
    CONSTRAINT ck_quote_termination__opportunity_revision CHECK (opportunity_revision BETWEEN 0 AND 9007199254740990)
);

COMMENT ON TABLE opportunity.quote_termination IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_termination ON opportunity.quote_termination IS '主键：在租户内唯一标识一条quote_termination记录。';
COMMENT ON INDEX opportunity.pk_quote_termination IS '主键：在租户内唯一标识一条quote_termination记录。';
COMMENT ON COLUMN opportunity.quote_termination.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_termination.quote_termination_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_termination.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_termination.opportunity_id IS '本次商机。';
COMMENT ON COLUMN opportunity.quote_termination.opportunity_revision IS '结束前准确商机版本。';
COMMENT ON COLUMN opportunity.quote_termination.quote_workflow_id IS '准确末端报价工作流。';
COMMENT ON COLUMN opportunity.quote_termination.quote_revision_id IS '准确当前报价；准备阶段可空。';
COMMENT ON COLUMN opportunity.quote_termination.closure_id IS '本次主线终点及受保护原因。';
COMMENT ON COLUMN opportunity.quote_termination.recorded_by IS '现任销售。';
COMMENT ON COLUMN opportunity.quote_termination.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.quote_termination.created_at IS '可信记录时间。';
COMMENT ON CONSTRAINT ck_quote_termination__revision ON opportunity.quote_termination IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_termination__opportunity ON opportunity.quote_termination IS '每个报价主线仅一个终点。';
COMMENT ON INDEX opportunity.uq_quote_termination__opportunity IS '每个报价主线仅一个终点。';
COMMENT ON CONSTRAINT uq_quote_termination__closure ON opportunity.quote_termination IS '准确终点唯一。';
COMMENT ON INDEX opportunity.uq_quote_termination__closure IS '准确终点唯一。';
COMMENT ON CONSTRAINT ck_quote_termination__opportunity_revision ON opportunity.quote_termination IS '版本可安全递增。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__tenant ON opportunity.quote_termination IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__opportunity_id ON opportunity.quote_termination IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__quote_workflow_id
    FOREIGN KEY (tenant_id, quote_workflow_id)
    REFERENCES opportunity.quote_workflow (tenant_id, quote_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__quote_workflow_id ON opportunity.quote_termination IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__quote_revision_id
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__quote_revision_id ON opportunity.quote_termination IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__closure_id
    FOREIGN KEY (tenant_id, closure_id)
    REFERENCES opportunity.closure (tenant_id, closure_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__closure_id ON opportunity.quote_termination IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination
    ADD CONSTRAINT fk_quote_termination__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination__recorded_by ON opportunity.quote_termination IS '同租户准确事实引用。';
CREATE TABLE opportunity.quote_termination_task (
    tenant_id uuid NOT NULL,
    quote_termination_task_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    quote_termination_id uuid NOT NULL,
    task_id uuid NOT NULL,
    task_revision bigint NOT NULL,
    prior_state varchar(64) NOT NULL,
    wait_id uuid,
    wait_hash bytea,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_termination_task PRIMARY KEY (tenant_id, quote_termination_task_id),
    CONSTRAINT ck_quote_termination_task__revision CHECK (revision=0),
    CONSTRAINT uq_quote_termination_task__task UNIQUE (tenant_id, task_id),
    CONSTRAINT ck_quote_termination_task__state CHECK (task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))),
    CONSTRAINT ck_quote_termination_task__wait_hash_length CHECK (octet_length(wait_hash) = 32)
);

COMMENT ON TABLE opportunity.quote_termination_task IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_quote_termination_task ON opportunity.quote_termination_task IS '主键：在租户内唯一标识一条quote_termination_task记录。';
COMMENT ON INDEX opportunity.pk_quote_termination_task IS '主键：在租户内唯一标识一条quote_termination_task记录。';
COMMENT ON COLUMN opportunity.quote_termination_task.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_termination_task.quote_termination_task_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_termination_task.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_termination_task.quote_termination_id IS '准确终止依据。';
COMMENT ON COLUMN opportunity.quote_termination_task.task_id IS '取消的未完成任务。';
COMMENT ON COLUMN opportunity.quote_termination_task.task_revision IS '取消前准确版本。';
COMMENT ON COLUMN opportunity.quote_termination_task.prior_state IS '取消前状态。';
COMMENT ON COLUMN opportunity.quote_termination_task.wait_id IS '准确末端等待。';
COMMENT ON COLUMN opportunity.quote_termination_task.wait_hash IS '准确末端等待摘要。';
COMMENT ON COLUMN opportunity.quote_termination_task.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.quote_termination_task.created_at IS '可信记录时间。';
COMMENT ON CONSTRAINT ck_quote_termination_task__revision ON opportunity.quote_termination_task IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_quote_termination_task__task ON opportunity.quote_termination_task IS '未完成责任仅取消一次。';
COMMENT ON INDEX opportunity.uq_quote_termination_task__task IS '未完成责任仅取消一次。';
COMMENT ON CONSTRAINT ck_quote_termination_task__state ON opportunity.quote_termination_task IS '准确取消前状态和等待。';
COMMENT ON CONSTRAINT ck_quote_termination_task__wait_hash_length ON opportunity.quote_termination_task IS '摘要格式：wait_hash必须保存32字节的规范二进制值。';
ALTER TABLE opportunity.quote_termination_task
    ADD CONSTRAINT fk_quote_termination_task__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination_task__tenant ON opportunity.quote_termination_task IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.quote_termination_task
    ADD CONSTRAINT fk_quote_termination_task__quote_termination_id
    FOREIGN KEY (tenant_id, quote_termination_id)
    REFERENCES opportunity.quote_termination (tenant_id, quote_termination_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination_task__quote_termination_id ON opportunity.quote_termination_task IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination_task
    ADD CONSTRAINT fk_quote_termination_task__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination_task__task_id ON opportunity.quote_termination_task IS '同租户准确事实引用。';
ALTER TABLE opportunity.quote_termination_task
    ADD CONSTRAINT fk_quote_termination_task__wait_id
    FOREIGN KEY (tenant_id, wait_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_termination_task__wait_id ON opportunity.quote_termination_task IS '同租户准确事实引用。';
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_cancellation;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK (((cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure')))) OR (state='CANCELLED' AND cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND cancellation_fact_type='opportunity.followup_attempt' AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL)) OR (state='CANCELLED' AND cancellation_reason_code='R2_QUOTE_TERMINATION_V1' AND cancellation_fact_type='opportunity.quote_termination' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL));
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
 IF ((EXISTS(SELECT 1 FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) AND NOT EXISTS(
 SELECT 1 FROM opportunity.quote_workflow w JOIN opportunity.quote_revision q ON q.tenant_id=w.tenant_id AND q.quote_revision_id=w.quote_revision_id
 WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND q.quote_revision_id=o.current_quote_revision_id
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id)
 AND (w.stage='SALES_DISPOSITION' OR (w.stage IN ('DELIVER','AWAIT_REPLY') AND q.valid_until<=NEW.closed_at
      AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id WHERE i.tenant_id=q.tenant_id AND i.quote_revision_id=q.quote_revision_id)))
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id JOIN opportunity.quote_revision accepted ON accepted.tenant_id=i.tenant_id AND accepted.quote_revision_id=i.quote_revision_id WHERE accepted.tenant_id=NEW.tenant_id AND accepted.opportunity_id=NEW.opportunity_id AND r.response_code='ACCEPTED'))) AND NOT EXISTS(SELECT 1 FROM opportunity.quote_termination z WHERE z.tenant_id=NEW.tenant_id AND z.closure_id=NEW.closure_id AND z.opportunity_id=NEW.opportunity_id AND z.created_in_transaction=pg_current_xact_id())) OR EXISTS(SELECT 1 FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure has downstream facts' USING ERRCODE='23514'; END IF;
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

CREATE TRIGGER trg_quote_termination__immutable BEFORE UPDATE OR DELETE ON opportunity.quote_termination FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_quote_termination_task__immutable BEFORE UPDATE OR DELETE ON opportunity.quote_termination_task FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
CREATE TRIGGER trg_quote_termination__transaction BEFORE INSERT OR UPDATE ON opportunity.quote_termination FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
CREATE TRIGGER trg_quote_termination_task__transaction BEFORE INSERT OR UPDATE ON opportunity.quote_termination_task FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
REVOKE ALL ON opportunity.quote_termination,opportunity.quote_termination_task FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON opportunity.quote_termination,opportunity.quote_termination_task TO ${app_command_role};
GRANT SELECT ON opportunity.quote_termination,opportunity.quote_termination_task TO ${app_query_role};
CREATE FUNCTION opportunity.fn_check_quote_termination_current() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; z opportunity.quote_termination%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='quote_termination' THEN
  SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
  IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM NEW.opportunity_revision OR o.current_quote_revision_id IS DISTINCT FROM NEW.quote_revision_id OR NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'quote termination root differs' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.quote_workflow_id=NEW.quote_workflow_id AND w.opportunity_id=NEW.opportunity_id AND w.quote_revision_id IS NOT DISTINCT FROM NEW.quote_revision_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id)) THEN RAISE EXCEPTION 'quote termination workflow differs' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM contract.preparation_workflow WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM contract.preparation_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'contract owner already took over' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO z FROM opportunity.quote_termination WHERE tenant_id=NEW.tenant_id AND quote_termination_id=NEW.quote_termination_id;
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id FOR UPDATE;
  IF z.quote_termination_id IS NULL OR z.created_in_transaction<>pg_current_xact_id() OR NEW.created_at IS DISTINCT FROM z.created_at OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM z.opportunity_id OR t.revision IS DISTINCT FROM NEW.task_revision OR t.state IS DISTINCT FROM NEW.prior_state OR t.business_purpose_code NOT IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY','PREPARE_CONTRACT') THEN RAISE EXCEPTION 'termination responsibility differs' USING ERRCODE='23514'; END IF;
  IF t.business_purpose_code='PREPARE_CONTRACT' AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow w JOIN opportunity.contract_preparation_source s ON s.tenant_id=w.tenant_id AND s.opportunity_id=w.opportunity_id WHERE w.tenant_id=NEW.tenant_id AND w.quote_workflow_id=z.quote_workflow_id AND w.stage='ACCEPTED' AND w.task_id=t.task_occurrence_id AND s.source_kind='ACCEPTED_QUOTE') THEN RAISE EXCEPTION 'contract task does not belong to accepted quote' USING ERRCODE='23514'; END IF;
  IF NEW.prior_state='WAITING' AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w WHERE w.tenant_id=NEW.tenant_id AND w.wait_receipt_id=NEW.wait_id AND w.task_occurrence_id=NEW.task_id AND w.task_revision=NEW.task_revision) THEN RAISE EXCEPTION 'termination wait differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_termination_current() FROM PUBLIC;
CREATE TRIGGER trg_quote_termination__current BEFORE INSERT ON opportunity.quote_termination FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_termination_current();
CREATE TRIGGER trg_quote_termination_task__current BEFORE INSERT ON opportunity.quote_termination_task FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_termination_current();
CREATE FUNCTION opportunity.fn_check_quote_termination() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE z opportunity.quote_termination%ROWTYPE; f opportunity.closure%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='task_occurrence' THEN
  IF NEW.cancellation_reason_code IS DISTINCT FROM 'R2_QUOTE_TERMINATION_V1' THEN RETURN NEW; END IF;
  SELECT * INTO z FROM opportunity.quote_termination WHERE tenant_id=NEW.tenant_id AND quote_termination_id=NEW.cancellation_fact_id;
  IF z.quote_termination_id IS NULL OR NOT EXISTS(SELECT 1 FROM opportunity.quote_termination_task m WHERE m.tenant_id=NEW.tenant_id AND m.quote_termination_id=z.quote_termination_id AND m.task_id=NEW.task_occurrence_id AND m.task_revision+1=NEW.revision) THEN RAISE EXCEPTION 'orphan quote cancellation' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='quote_termination_task' THEN
  SELECT * INTO z FROM opportunity.quote_termination WHERE tenant_id=NEW.tenant_id AND quote_termination_id=NEW.quote_termination_id;
 ELSE z=NEW;
 END IF;
 SELECT * INTO f FROM opportunity.closure WHERE tenant_id=z.tenant_id AND closure_id=z.closure_id;
 IF z.quote_termination_id IS NULL OR z.created_in_transaction<>pg_current_xact_id() OR f.opportunity_id IS DISTINCT FROM z.opportunity_id OR f.opportunity_revision IS DISTINCT FROM z.opportunity_revision OR f.closed_by_appointment_id IS DISTINCT FROM z.recorded_by OR f.closed_at IS DISTINCT FROM z.created_at OR f.task_occurrence_id IS NOT NULL THEN RAISE EXCEPTION 'quote termination closure differs' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=z.tenant_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=z.opportunity_id AND t.state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'quote termination retains active responsibility' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM opportunity.quote_termination_task m LEFT JOIN responsibility.task_occurrence t ON t.tenant_id=m.tenant_id AND t.task_occurrence_id=m.task_id WHERE m.tenant_id=z.tenant_id AND m.quote_termination_id=z.quote_termination_id AND (t.state IS DISTINCT FROM 'CANCELLED' OR t.revision IS DISTINCT FROM m.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'opportunity.quote_termination' OR t.cancellation_fact_id IS DISTINCT FROM z.quote_termination_id OR t.cancellation_reason_code IS DISTINCT FROM 'R2_QUOTE_TERMINATION_V1' OR t.cancelled_at IS DISTINCT FROM z.created_at OR t.completion_fact_id IS NOT NULL)) THEN RAISE EXCEPTION 'quote termination cancellation differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_termination() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_termination__complete AFTER INSERT ON opportunity.quote_termination DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_termination();
CREATE CONSTRAINT TRIGGER trg_quote_termination_task__complete AFTER INSERT ON opportunity.quote_termination_task DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_termination();
CREATE CONSTRAINT TRIGGER trg_task__quote_termination AFTER INSERT OR UPDATE ON responsibility.task_occurrence DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_termination();
DO $v1020$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v16',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v15';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1020 requires 52-plus-2-r2-v15' USING ERRCODE='55000'; END IF;
END;
$v1020$;
