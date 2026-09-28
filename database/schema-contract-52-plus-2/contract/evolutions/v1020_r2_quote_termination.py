"""F06 exact quotation termination and the complete cancelled responsibility set."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v950_r2_quote_runtime import table, fk, txn, _closure, _old_quote, _allowed_quote

TERMINATION=table('quote_termination',(
 uuid_col('opportunity_id','本次商机。'),bigint_col('opportunity_revision','结束前准确商机版本。'),
 uuid_col('quote_workflow_id','准确末端报价工作流。'),uuid_col('quote_revision_id','准确当前报价；准备阶段可空。',nullable=True),
 uuid_col('closure_id','本次主线终点及受保护原因。'),uuid_col('recorded_by','现任销售。'),txn(),time_col('created_at','可信记录时间。')),
 (unique('uq_quote_termination__opportunity',('tenant_id','opportunity_id'),'每个报价主线仅一个终点。'),unique('uq_quote_termination__closure',('tenant_id','closure_id'),'准确终点唯一。'),check('ck_quote_termination__opportunity_revision','opportunity_revision BETWEEN 0 AND 9007199254740990','版本可安全递增。')),
 tuple(fk('quote_termination',col,schema,target) for col,schema,target in (
 ('opportunity_id','opportunity','opportunity'),('quote_workflow_id','opportunity','quote_workflow'),('quote_revision_id','opportunity','quote_revision'),('closure_id','opportunity','closure'),('recorded_by','identity','appointment'))))
MEMBER=table('quote_termination_task',(
 uuid_col('quote_termination_id','准确终止依据。'),uuid_col('task_id','取消的未完成任务。'),bigint_col('task_revision','取消前准确版本。'),
 code_col('prior_state','取消前状态。'),uuid_col('wait_id','准确末端等待。',nullable=True),digest_col('wait_hash','准确末端等待摘要。',nullable=True),txn(),time_col('created_at','可信记录时间。')),
 (unique('uq_quote_termination_task__task',('tenant_id','task_id'),'未完成责任仅取消一次。'),check('ck_quote_termination_task__state',"task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))",'准确取消前状态和等待。')),
 tuple(fk('quote_termination_task',col,schema,target) for col,schema,target in (('quote_termination_id','opportunity','quote_termination'),('task_id','responsibility','task_occurrence'),('wait_id','responsibility','wait_receipt'))))

def constraint(c):
 if c.name=='ck_task_occurrence__handoff_cancellation':return replace(c,expression='('+c.expression+") OR (state='CANCELLED' AND cancellation_reason_code='R2_QUOTE_TERMINATION_V1' AND cancellation_fact_type='opportunity.quote_termination' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL)")
 return c

def apply_evolution(schemas):
 return tuple(replace(s,tables=tuple(replace(t,constraints=tuple(constraint(c) for c in t.constraints)) if t.schema=='responsibility' and t.name=='task_occurrence' else t for t in s.tables)+((TERMINATION,MEMBER) if s.name=='opportunity' else ())) for s in schemas)

def render_sql(before,after):
 sql='\n'.join(_render_table(t)+'\n'+'\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys) for t in (TERMINATION,MEMBER))+'\n'
 t=next(t for s in after for t in s.tables if t.schema=='responsibility' and t.name=='task_occurrence');c=next(c for c in t.constraints if c.name=='ck_task_occurrence__handoff_cancellation')
 sql+='ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT '+c.name+';\nALTER TABLE responsibility.task_occurrence ADD '+_constraint_sql(c)+';\n'
 # Retain V950's historical close path; the new exception needs a same-transaction named termination.
 needle='('+_old_quote+' AND NOT '+_allowed_quote+')'
 assert _closure.count(needle)==1
 extended=_closure.replace(needle,'('+needle+" AND NOT EXISTS(SELECT 1 FROM opportunity.quote_termination z WHERE z.tenant_id=NEW.tenant_id AND z.closure_id=NEW.closure_id AND z.opportunity_id=NEW.opportunity_id AND z.created_in_transaction=pg_current_xact_id()))")
 return sql+extended+GUARDS

GUARDS=r'''
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
'''
EVOLUTION=ContractEvolution(version=1020,migration_name='V1020__r2_quote_termination.sql',contract_version='52-plus-2-r2-v16',apply=apply_evolution,render_sql=render_sql)
