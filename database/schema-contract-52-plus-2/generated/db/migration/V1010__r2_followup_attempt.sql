CREATE TABLE opportunity.followup_attempt (
    tenant_id uuid NOT NULL,
    followup_attempt_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    context_code varchar(64) NOT NULL,
    attempt_code varchar(64) NOT NULL,
    prior_task_id uuid NOT NULL,
    prior_task_revision bigint NOT NULL,
    prior_wait_id uuid,
    prior_wait_hash bytea,
    task_id uuid NOT NULL,
    quote_workflow_id uuid,
    next_quote_workflow_id uuid,
    recorded_by uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    next_check_at timestamptz(6) NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_followup_attempt PRIMARY KEY (tenant_id, followup_attempt_id),
    CONSTRAINT ck_followup_attempt__revision CHECK (revision=0),
    CONSTRAINT uq_followup_attempt__prior_task UNIQUE (tenant_id, prior_task_id),
    CONSTRAINT uq_followup_attempt__task UNIQUE (tenant_id, task_id),
    CONSTRAINT ck_followup_attempt__values CHECK (context_code IN ('OPPORTUNITY','QUOTE') AND attempt_code IN ('NOT_CONNECTED','NO_REPLY','NO_EFFECTIVE_PROGRESS') AND prior_task_id<>task_id AND opportunity_revision BETWEEN 0 AND 9007199254740991 AND prior_task_revision BETWEEN 0 AND 9007199254740990 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND occurred_at<=created_at AND next_check_at>created_at AND (prior_wait_id IS NULL)=(prior_wait_hash IS NULL) AND ((context_code='QUOTE' AND quote_workflow_id IS NOT NULL AND next_quote_workflow_id IS NOT NULL) OR (context_code='OPPORTUNITY' AND quote_workflow_id IS NULL AND next_quote_workflow_id IS NULL))),
    CONSTRAINT ck_followup_attempt__prior_wait_hash_length CHECK (octet_length(prior_wait_hash) = 32),
    CONSTRAINT ck_followup_attempt__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE opportunity.followup_attempt IS 'Fact Owner：OpportunityRuntime；报价闭环不可变准确事实；不构成合同签署。';
COMMENT ON CONSTRAINT pk_followup_attempt ON opportunity.followup_attempt IS '主键：在租户内唯一标识一条followup_attempt记录。';
COMMENT ON INDEX opportunity.pk_followup_attempt IS '主键：在租户内唯一标识一条followup_attempt记录。';
COMMENT ON COLUMN opportunity.followup_attempt.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.followup_attempt.followup_attempt_id IS '报价闭环不可变准确事实；不构成合同签署。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.followup_attempt.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.followup_attempt.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.followup_attempt.opportunity_revision IS '提交时版本。';
COMMENT ON COLUMN opportunity.followup_attempt.responsibility_type IS '准确责任来源。';
COMMENT ON COLUMN opportunity.followup_attempt.responsibility_id IS '准确责任来源身份。';
COMMENT ON COLUMN opportunity.followup_attempt.responsibility_revision IS '准确责任来源版本。';
COMMENT ON COLUMN opportunity.followup_attempt.context_code IS '普通跟进或报价回复。';
COMMENT ON COLUMN opportunity.followup_attempt.attempt_code IS '本次真实情况。';
COMMENT ON COLUMN opportunity.followup_attempt.prior_task_id IS '被本次安排接管的任务。';
COMMENT ON COLUMN opportunity.followup_attempt.prior_task_revision IS '原任务准确版本。';
COMMENT ON COLUMN opportunity.followup_attempt.prior_wait_id IS '原等待。';
COMMENT ON COLUMN opportunity.followup_attempt.prior_wait_hash IS '原等待摘要。';
COMMENT ON COLUMN opportunity.followup_attempt.task_id IS '唯一后继任务。';
COMMENT ON COLUMN opportunity.followup_attempt.quote_workflow_id IS '本次报价前序工作流。';
COMMENT ON COLUMN opportunity.followup_attempt.next_quote_workflow_id IS '本次报价等待工作流。';
COMMENT ON COLUMN opportunity.followup_attempt.recorded_by IS '实际记录任职。';
COMMENT ON COLUMN opportunity.followup_attempt.body_ciphertext IS '联系尝试说明密文。';
COMMENT ON COLUMN opportunity.followup_attempt.body_digest IS '规范尝试正文与来源摘要。';
COMMENT ON COLUMN opportunity.followup_attempt.occurred_at IS '实际联系时间。';
COMMENT ON COLUMN opportunity.followup_attempt.next_check_at IS '约定下一次联系。';
COMMENT ON COLUMN opportunity.followup_attempt.created_in_transaction IS '冻结配置或审批成员集合的形成事务。';
COMMENT ON COLUMN opportunity.followup_attempt.created_at IS '数据库记录时间。';
COMMENT ON CONSTRAINT ck_followup_attempt__revision ON opportunity.followup_attempt IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_followup_attempt__prior_task ON opportunity.followup_attempt IS '每项原责任仅一次安排。';
COMMENT ON INDEX opportunity.uq_followup_attempt__prior_task IS '每项原责任仅一次安排。';
COMMENT ON CONSTRAINT uq_followup_attempt__task ON opportunity.followup_attempt IS '后继只由一次尝试形成。';
COMMENT ON INDEX opportunity.uq_followup_attempt__task IS '后继只由一次尝试形成。';
COMMENT ON CONSTRAINT ck_followup_attempt__values ON opportunity.followup_attempt IS '准确来源及过去尝试、未来安排。';
COMMENT ON CONSTRAINT ck_followup_attempt__prior_wait_hash_length ON opportunity.followup_attempt IS '摘要格式：prior_wait_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_followup_attempt__body_digest_length ON opportunity.followup_attempt IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__tenant ON opportunity.followup_attempt IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__opportunity_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__prior_task_id
    FOREIGN KEY (tenant_id, prior_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__prior_task_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__task_id
    FOREIGN KEY (tenant_id, task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__task_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__prior_wait_id
    FOREIGN KEY (tenant_id, prior_wait_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__prior_wait_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__quote_workflow_id
    FOREIGN KEY (tenant_id, quote_workflow_id)
    REFERENCES opportunity.quote_workflow (tenant_id, quote_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__quote_workflow_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__next_quote_workflow_id
    FOREIGN KEY (tenant_id, next_quote_workflow_id)
    REFERENCES opportunity.quote_workflow (tenant_id, quote_workflow_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__next_quote_workflow_id ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE opportunity.followup_attempt
    ADD CONSTRAINT fk_followup_attempt__recorded_by
    FOREIGN KEY (tenant_id, recorded_by)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_followup_attempt__recorded_by ON opportunity.followup_attempt IS '同租户准确事实引用。';
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__progress_predecessor;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__progress_predecessor CHECK (predecessor_task_occurrence_id IS NULL OR (business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY') AND predecessor_task_occurrence_id <> task_occurrence_id));
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_cancellation;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK ((cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure')))) OR (state='CANCELLED' AND cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND cancellation_fact_type='opportunity.followup_attempt' AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__positive_task_revision;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__positive_task_revision CHECK (task_revision > 0 OR (wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND task_revision=0));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__resume_after_entry;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__resume_after_entry CHECK (resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1'));
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__handoff_shape;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__handoff_shape CHECK ((wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL) OR (wait_contract_code='R2_QUOTE_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.quote_response' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL) OR (wait_contract_code='R2_ATTEMPT_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.followup_attempt' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL));
CREATE OR REPLACE FUNCTION responsibility.fn_guard_r2_handoff_task_initial() RETURNS trigger
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
 IF NEW.original_wait_receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w JOIN responsibility.wait_receipt original ON original.tenant_id=w.tenant_id AND original.wait_receipt_id=NEW.original_wait_receipt_id WHERE w.tenant_id=NEW.tenant_id AND w.task_occurrence_id=NEW.new_task_occurrence_id AND w.wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND (w.wait_contract_code<>'R2_QUOTE_HANDOFF_WAIT_V1' OR (original.wait_contract_code IN ('R2_QUOTE_FOLLOWUP_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND w.awaited_fact_type=original.awaited_fact_type AND w.awaited_fact_id=original.awaited_fact_id AND w.awaited_fact_hash=original.awaited_fact_hash AND original.awaited_fact_revision IS NULL)) AND (w.wait_contract_code<>'R2_ATTEMPT_HANDOFF_WAIT_V1' OR (original.wait_contract_code IN ('R2_SALES_ATTEMPT_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND w.awaited_fact_type='opportunity.followup_attempt' AND w.awaited_fact_type=original.awaited_fact_type AND w.awaited_fact_id=original.awaited_fact_id AND w.awaited_fact_hash=original.awaited_fact_hash AND original.awaited_fact_revision IS NULL)) AND w.handoff_fact_id=NEW.responsibility_handoff_id AND w.handoff_fact_revision=0 AND w.inherited_wait_receipt_id=NEW.original_wait_receipt_id AND w.inherited_wait_hash=NEW.original_wait_hash AND w.resume_due_at IS NOT DISTINCT FROM original.resume_due_at AND w.original_sla_due_at=NEW.original_due_at AND original.task_occurrence_id=NEW.old_task_occurrence_id) THEN RAISE EXCEPTION 'handoff wait inheritance mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence old_task JOIN responsibility.task_occurrence new_task ON new_task.tenant_id=old_task.tenant_id AND new_task.task_occurrence_id=NEW.new_task_occurrence_id WHERE old_task.tenant_id=NEW.tenant_id AND old_task.task_occurrence_id=NEW.old_task_occurrence_id AND old_task.business_purpose_code=new_task.business_purpose_code AND old_task.primary_command_code=new_task.primary_command_code AND old_task.expected_completion_fact_type=new_task.expected_completion_fact_type AND old_task.original_sla_code=new_task.original_sla_code AND old_task.original_sla_seconds=new_task.original_sla_seconds) THEN RAISE EXCEPTION 'handoff successor purpose differs' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;

CREATE TRIGGER trg_followup_attempt__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.followup_attempt FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_followup_attempt__mutation_guard ON opportunity.followup_attempt IS '尝试与来源不可改写。';
CREATE TRIGGER trg_followup_attempt__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.followup_attempt FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_followup_attempt__transaction_marker ON opportunity.followup_attempt IS '尝试由本次事务形成。';
REVOKE ALL ON opportunity.followup_attempt FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON opportunity.followup_attempt TO ${app_command_role};
GRANT SELECT ON opportunity.followup_attempt TO ${app_query_role};
CREATE FUNCTION opportunity.fn_check_followup_attempt() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; p responsibility.task_occurrence%ROWTYPE; n responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 SELECT * INTO p FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.prior_task_id;
 SELECT * INTO n FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision<>NEW.opportunity_revision OR NEW.occurred_at<o.created_at OR NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp() OR NEW.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'attempt root or time differs' USING ERRCODE='23514'; END IF;
 IF p.state IS DISTINCT FROM 'CANCELLED' OR p.completion_fact_id IS NOT NULL OR p.revision IS DISTINCT FROM NEW.prior_task_revision+1 OR p.cancellation_reason_code IS DISTINCT FROM 'R2_FOLLOWUP_ATTEMPT_V1' OR p.cancellation_fact_type IS DISTINCT FROM 'opportunity.followup_attempt' OR p.cancellation_fact_id IS DISTINCT FROM NEW.followup_attempt_id OR p.cancellation_fact_hash IS DISTINCT FROM NEW.body_digest OR p.cancelled_at IS DISTINCT FROM NEW.created_at OR p.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR p.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR p.subject_id IS DISTINCT FROM NEW.opportunity_id OR p.subject_revision IS DISTINCT FROM NEW.opportunity_revision THEN RAISE EXCEPTION 'attempt predecessor differs' USING ERRCODE='23514'; END IF;
 IF n.state IS DISTINCT FROM 'WAITING' OR n.revision IS DISTINCT FROM 1 OR n.owner_appointment_id IS DISTINCT FROM p.owner_appointment_id OR n.subject_type IS DISTINCT FROM p.subject_type OR n.subject_id IS DISTINCT FROM p.subject_id OR n.subject_revision IS DISTINCT FROM p.subject_revision OR n.business_purpose_code IS DISTINCT FROM p.business_purpose_code OR n.primary_command_code IS DISTINCT FROM p.primary_command_code OR n.expected_completion_fact_type IS DISTINCT FROM p.expected_completion_fact_type OR n.predecessor_task_occurrence_id IS DISTINCT FROM p.task_occurrence_id OR n.created_at IS DISTINCT FROM NEW.created_at THEN RAISE EXCEPTION 'attempt successor differs' USING ERRCODE='23514'; END IF;
 IF (NEW.context_code='OPPORTUNITY')<>(p.business_purpose_code='PROGRESS_OPPORTUNITY') OR (NEW.context_code='QUOTE')<>(p.business_purpose_code='RECORD_QUOTE_REPLY') THEN RAISE EXCEPTION 'attempt purpose differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w WHERE w.tenant_id=NEW.tenant_id AND w.task_occurrence_id=NEW.task_id AND w.task_revision=1 AND w.wait_contract_code='R2_SALES_ATTEMPT_WAIT_V1' AND w.wait_contract_version=1 AND w.awaited_fact_type='opportunity.followup_attempt' AND w.awaited_fact_id=NEW.followup_attempt_id AND w.awaited_fact_hash=NEW.body_digest AND w.entered_waiting_at=NEW.created_at AND w.resume_due_at=NEW.next_check_at AND w.recorded_by_appointment_id=NEW.recorded_by) THEN RAISE EXCEPTION 'attempt wait differs' USING ERRCODE='23514'; END IF;
 IF NEW.prior_wait_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w WHERE w.tenant_id=NEW.tenant_id AND w.wait_receipt_id=NEW.prior_wait_id AND w.task_occurrence_id=NEW.prior_task_id AND w.task_revision=NEW.prior_task_revision) THEN RAISE EXCEPTION 'attempt prior wait differs' USING ERRCODE='23514'; END IF;
 IF NEW.context_code='OPPORTUNITY' AND EXISTS(SELECT 1 FROM opportunity.quote_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'quote already owns responsibility' USING ERRCODE='23514'; END IF;
 IF NEW.context_code='QUOTE' AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow w JOIN opportunity.quote_workflow prior ON prior.tenant_id=w.tenant_id AND prior.quote_workflow_id=w.previous_workflow_id WHERE w.tenant_id=NEW.tenant_id AND w.quote_workflow_id=NEW.next_quote_workflow_id AND prior.quote_workflow_id=NEW.quote_workflow_id AND w.stage='AWAIT_REPLY' AND prior.stage IN ('AWAIT_REPLY','FOLLOW_UP','CLARIFY_REPLY') AND w.opportunity_id=NEW.opportunity_id AND w.quote_revision_id=prior.quote_revision_id AND w.task_id=NEW.task_id AND w.prior_task_id=NEW.prior_task_id AND w.next_check_at=NEW.next_check_at AND w.created_at=NEW.created_at) THEN RAISE EXCEPTION 'attempt quote continuation differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_followup_attempt() IS '尝试、原责任收口、唯一后继与等待提交前一致。';
REVOKE ALL ON FUNCTION opportunity.fn_check_followup_attempt() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_followup_attempt__source AFTER INSERT ON opportunity.followup_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_followup_attempt();
COMMENT ON TRIGGER trg_followup_attempt__source ON opportunity.followup_attempt IS '无虚构进展或客户回复的安排。';
CREATE FUNCTION opportunity.fn_check_attempt_responsibility() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF TG_TABLE_NAME='task_occurrence' THEN
  IF NEW.cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND NOT EXISTS(SELECT 1 FROM opportunity.followup_attempt a WHERE a.tenant_id=NEW.tenant_id AND a.followup_attempt_id=NEW.cancellation_fact_id AND a.body_digest=NEW.cancellation_fact_hash AND a.prior_task_id=NEW.task_occurrence_id AND a.prior_task_revision+1=NEW.revision AND a.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'missing exact attempt for cancelled responsibility' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.wait_contract_code='R2_SALES_ATTEMPT_WAIT_V1' AND NOT EXISTS(SELECT 1 FROM opportunity.followup_attempt a WHERE a.tenant_id=NEW.tenant_id AND a.followup_attempt_id=NEW.awaited_fact_id AND a.body_digest=NEW.awaited_fact_hash AND NEW.awaited_fact_type='opportunity.followup_attempt' AND NEW.awaited_fact_revision IS NULL AND a.task_id=NEW.task_occurrence_id AND a.next_check_at=NEW.resume_due_at AND a.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'missing exact attempt for waiting responsibility' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_attempt_responsibility() IS '责任取消与初始等待必须存在本事务准确尝试事实。';
REVOKE ALL ON FUNCTION opportunity.fn_check_attempt_responsibility() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_task_occurrence__followup_attempt AFTER UPDATE ON responsibility.task_occurrence DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_attempt_responsibility();
COMMENT ON TRIGGER trg_task_occurrence__followup_attempt ON responsibility.task_occurrence IS '禁止孤立取消。';
CREATE CONSTRAINT TRIGGER trg_wait_receipt__followup_attempt AFTER INSERT ON responsibility.wait_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_attempt_responsibility();
COMMENT ON TRIGGER trg_wait_receipt__followup_attempt ON responsibility.wait_receipt IS '禁止孤立联系尝试等待。';
DO $v1010$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v15',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v14';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1010 requires 52-plus-2-r2-v14' USING ERRCODE='55000'; END IF;
END;
$v1010$;
