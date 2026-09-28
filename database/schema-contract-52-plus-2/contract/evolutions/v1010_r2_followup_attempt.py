"""F05 factual contact attempts, distinct from effective progress and client replies."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v950_r2_quote_runtime import table, fk, txn, render_handoff

ATTEMPT=table('followup_attempt',(
    uuid_col('opportunity_id','准确商机。'),bigint_col('opportunity_revision','提交时版本。'),
    code_col('responsibility_type','准确责任来源。'),uuid_col('responsibility_id','准确责任来源身份。'),bigint_col('responsibility_revision','准确责任来源版本。'),
    code_col('context_code','普通跟进或报价回复。'),code_col('attempt_code','本次真实情况。'),
    uuid_col('prior_task_id','被本次安排接管的任务。'),bigint_col('prior_task_revision','原任务准确版本。'),
    uuid_col('prior_wait_id','原等待。',nullable=True),digest_col('prior_wait_hash','原等待摘要。',nullable=True),
    uuid_col('task_id','唯一后继任务。'),uuid_col('quote_workflow_id','本次报价前序工作流。',nullable=True),uuid_col('next_quote_workflow_id','本次报价等待工作流。',nullable=True),
    uuid_col('recorded_by','实际记录任职。'),encrypted_col('body_ciphertext','联系尝试说明密文。'),digest_col('body_digest','规范尝试正文与来源摘要。'),
    time_col('occurred_at','实际联系时间。'),time_col('next_check_at','约定下一次联系。'),txn(),time_col('created_at','数据库记录时间。')),
    (unique('uq_followup_attempt__prior_task',('tenant_id','prior_task_id'),'每项原责任仅一次安排。'),
     unique('uq_followup_attempt__task',('tenant_id','task_id'),'后继只由一次尝试形成。'),
     check('ck_followup_attempt__values',"context_code IN ('OPPORTUNITY','QUOTE') AND attempt_code IN ('NOT_CONNECTED','NO_REPLY','NO_EFFECTIVE_PROGRESS') AND prior_task_id<>task_id AND opportunity_revision BETWEEN 0 AND 9007199254740991 AND prior_task_revision BETWEEN 0 AND 9007199254740990 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND occurred_at<=created_at AND next_check_at>created_at AND (prior_wait_id IS NULL)=(prior_wait_hash IS NULL) AND ((context_code='QUOTE' AND quote_workflow_id IS NOT NULL AND next_quote_workflow_id IS NOT NULL) OR (context_code='OPPORTUNITY' AND quote_workflow_id IS NULL AND next_quote_workflow_id IS NULL))",'准确来源及过去尝试、未来安排。')),
    tuple(fk('followup_attempt',col,schema,target) for col,schema,target in (
        ('opportunity_id','opportunity','opportunity'),('prior_task_id','responsibility','task_occurrence'),('task_id','responsibility','task_occurrence'),
        ('prior_wait_id','responsibility','wait_receipt'),('quote_workflow_id','opportunity','quote_workflow'),('next_quote_workflow_id','opportunity','quote_workflow'),('recorded_by','identity','appointment'))))

def constraint(c):
    if c.name=='ck_task_occurrence__handoff_cancellation':
        return replace(c,expression='('+c.expression+") OR (state='CANCELLED' AND cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1' AND cancellation_fact_type='opportunity.followup_attempt' AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL)")
    if c.name=='ck_task_occurrence__progress_predecessor':
        return replace(c,expression=c.expression.replace("business_purpose_code = 'PROGRESS_OPPORTUNITY'","business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY')"))
    if c.name in ('ck_wait_receipt__resume_after_entry','ck_wait_receipt__positive_task_revision'):
        return replace(c,expression=c.expression.replace("'R2_QUOTE_HANDOFF_WAIT_V1'","'R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1'"))
    if c.name=='ck_wait_receipt__handoff_shape':
        e=c.expression.replace("NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1')","NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1')")
        e+=" OR (wait_contract_code='R2_ATTEMPT_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.followup_attempt' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL)"
        return replace(c,expression=e)
    return c

def apply_evolution(schemas):
    return tuple(replace(s,tables=tuple(replace(t,constraints=tuple(constraint(c) for c in t.constraints)) if t.schema=='responsibility' and t.name in ('task_occurrence','wait_receipt') else t for t in s.tables)+((ATTEMPT,) if s.name=='opportunity' else ())) for s in schemas)

def render_sql(before,after):
    sql=_render_table(ATTEMPT)+'\n'+'\n'.join(_render_foreign_key(ATTEMPT,f) for f in ATTEMPT.foreign_keys)+'\n'
    prior={t.schema+'.'+t.name:t for s in before for t in s.tables}
    for s in after:
        for t in s.tables:
            if t.schema!='responsibility' or t.name not in ('task_occurrence','wait_receipt'):continue
            old={c.name:c for c in prior[t.schema+'.'+t.name].constraints}
            for c in t.constraints:
                if c!=old[c.name]:sql+=f'ALTER TABLE {t.schema}.{t.name} DROP CONSTRAINT {c.name};\nALTER TABLE {t.schema}.{t.name} ADD {_constraint_sql(c)};\n'
    # Keep all V950 handoff guards. Only register the exact new inherited wait profile.
    guard=render_handoff(before,before)
    guard=guard.replace("w.wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1')","w.wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1')")
    guard=guard.replace('AND w.handoff_fact_id=NEW.responsibility_handoff_id',"AND (w.wait_contract_code<>'R2_ATTEMPT_HANDOFF_WAIT_V1' OR (original.wait_contract_code IN ('R2_SALES_ATTEMPT_WAIT_V1','R2_ATTEMPT_HANDOFF_WAIT_V1') AND w.awaited_fact_type='opportunity.followup_attempt' AND w.awaited_fact_type=original.awaited_fact_type AND w.awaited_fact_id=original.awaited_fact_id AND w.awaited_fact_hash=original.awaited_fact_hash AND original.awaited_fact_revision IS NULL)) AND w.handoff_fact_id=NEW.responsibility_handoff_id")
    return sql+guard+GUARDS

GUARDS=r'''
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
'''
EVOLUTION=ContractEvolution(version=1010,migration_name='V1010__r2_followup_attempt.sql',contract_version='52-plus-2-r2-v15',apply=apply_evolution,render_sql=render_sql)
