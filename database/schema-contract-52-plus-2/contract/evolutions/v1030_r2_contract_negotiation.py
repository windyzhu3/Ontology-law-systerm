"""F06 sales handling disposition; never a legal contract termination or execution reversal."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v920_r2_customer_requirements import body, checks, fk
from .v950_r2_quote_runtime import txn
from .v980_r2_contract_versions import ALL_TASKS as PREPARATION_TASKS, protected, sql_list
from .v990_r2_manual_signature import ALL_TASKS as SIGNATURE_TASKS

TASKS=(*PREPARATION_TASKS,*SIGNATURE_TASKS)
def fact(name,columns,cs=(),fks=(),indexes=(),schema='contract'):
 return tenant_table(schema,name,name+'_id','销售办理处置的不可变准确事实；不解除合同或撤销执行。',(revision_col(),txn(),*columns,time_col('created_at','数据库可信记录时间。')),constraints=(*checks(name),*cs),foreign_keys=fks,indexes=indexes)
def ref(name,column,target,schema='contract'):return fk(name,column,schema,target)
def unique_ref(name,column):return unique('uq_'+name+'__'+column,('tenant_id',column),'引用只能接续一次。')

DISPOSITION=fact('negotiation_disposition',(
 uuid_col('opportunity_id','销售主线。'),bigint_col('opportunity_revision','准确商机修订。'),
 uuid_col('previous_disposition_id','直接前序处置，单链。',nullable=True),uuid_col('request_disposition_id','主管处置所核对的准确请求。',nullable=True),
 code_col('kind','STOP_UNSIGNED/REQUEST_REVIEW/STOP_REVIEWED/CONTINUE。'),
 uuid_col('contract_id','当前合同身份；直接授权申请阶段可空。',nullable=True),bigint_col('contract_revision','合同身份准确修订。',nullable=True),
 uuid_col('contract_version_id','准确合同正文版本。',nullable=True),uuid_col('preparation_workflow_id','准确准备办理依据。'),
 uuid_col('signature_workflow_id','当前签署办理依据。',nullable=True),uuid_col('recorded_by','实际申请或核对任职。'),*body()),
 (unique_ref('negotiation_disposition','previous_disposition_id'),*protected('negotiation_disposition'),
 check('ck_negotiation_disposition__shape',"opportunity_revision BETWEEN 0 AND 9007199254740991 AND ((contract_id IS NULL AND contract_revision IS NULL AND contract_version_id IS NULL) OR (contract_id IS NOT NULL AND contract_revision BETWEEN 0 AND 9007199254740991)) AND ((kind IN ('STOP_UNSIGNED','REQUEST_REVIEW') AND request_disposition_id IS NULL) OR (kind IN ('STOP_REVIEWED','CONTINUE') AND request_disposition_id=previous_disposition_id AND request_disposition_id IS NOT NULL))",'明确形状与准确版本。')),
 tuple(ref('negotiation_disposition',col,target,schema) for col,target,schema in (
 ('opportunity_id','opportunity','opportunity'),('previous_disposition_id','negotiation_disposition','contract'),('request_disposition_id','negotiation_disposition','contract'),('contract_id','contract','contract'),('contract_version_id','contract_revision','contract'),('preparation_workflow_id','preparation_workflow','contract'),('signature_workflow_id','signature_workflow','contract'),('recorded_by','appointment','identity'))),
 (index('uq_negotiation_disposition__root',('tenant_id','opportunity_id'),'每条销售主线仅一个处置链根。',unique_=True,where='previous_disposition_id IS NULL'),))

ASSIGNMENT=fact('termination_review_assignment',(
 uuid_col('request_id','准确终止核对请求。'),uuid_col('previous_assignment_id','直接前序责任安排。',nullable=True),
 uuid_col('owner_appointment_id','有权主管；未配置时为空。',nullable=True),uuid_col('task_id','实际主管任务；未配置时为空。',nullable=True),
 time_col('due_at','原核对期限，重新分配不延长。'),uuid_col('recorded_by','申请者或恢复服务任职。')),
 (unique_ref('termination_review_assignment','previous_assignment_id'),unique_ref('termination_review_assignment','task_id'),check('ck_termination_review_assignment__owner',"(owner_appointment_id IS NULL)=(task_id IS NULL)",'无主管不伪造可办理任务。')),
 tuple(ref('termination_review_assignment',col,target,schema) for col,target,schema in (('request_id','negotiation_disposition','contract'),('previous_assignment_id','termination_review_assignment','contract'),('owner_appointment_id','appointment','identity'),('task_id','task_occurrence','responsibility'),('recorded_by','appointment','identity'))),
 (index('uq_termination_review_assignment__root',('tenant_id','request_id'),'每个请求一个责任安排链根。',unique_=True,where='previous_assignment_id IS NULL'),))

CANCELLED=fact('negotiation_cancelled_task',(
 uuid_col('disposition_id','停止或暂停的准确依据。'),uuid_col('task_id','原未完成责任。'),bigint_col('task_revision','取消前准确修订。'),code_col('prior_state','取消前OPEN/WAITING。'),
 uuid_col('wait_id','WAITING的末端等待。',nullable=True),digest_col('wait_hash','准确等待摘要。',nullable=True)),
 (unique_ref('negotiation_cancelled_task','task_id'),check('ck_negotiation_cancelled_task__state',"task_revision BETWEEN 0 AND 9007199254740990 AND ((prior_state='OPEN' AND wait_id IS NULL AND wait_hash IS NULL) OR (prior_state='WAITING' AND wait_id IS NOT NULL AND wait_hash IS NOT NULL))",'准确取消集合。')),
 (ref('negotiation_cancelled_task','disposition_id','negotiation_disposition'),ref('negotiation_cancelled_task','task_id','task_occurrence','responsibility'),ref('negotiation_cancelled_task','wait_id','wait_receipt','responsibility')))

RESUMPTION=fact('contract_task_resumption',(
 uuid_col('disposition_id','主管CONTINUE决定。'),uuid_col('cancelled_task_id','准确取消明细。'),uuid_col('prior_task_id','被暂停的原责任。'),uuid_col('next_task_id','新建的同目的责任。')),
 (unique_ref('contract_task_resumption','cancelled_task_id'),unique_ref('contract_task_resumption','prior_task_id'),unique_ref('contract_task_resumption','next_task_id')),
 (ref('contract_task_resumption','disposition_id','negotiation_disposition'),ref('contract_task_resumption','cancelled_task_id','negotiation_cancelled_task'),ref('contract_task_resumption','prior_task_id','task_occurrence','responsibility'),ref('contract_task_resumption','next_task_id','task_occurrence','responsibility')),schema='responsibility')
TABLES=(DISPOSITION,ASSIGNMENT,CANCELLED,RESUMPTION)

def constraint(c):
 if c.name=='ck_task_occurrence__handoff_cancellation':return replace(c,expression='('+c.expression+") OR (state='CANCELLED' AND cancellation_reason_code='R2_CONTRACT_NEGOTIATION_V1' AND cancellation_fact_type='contract.negotiation_disposition' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL)")
 return c
def apply_evolution(schemas):
 return tuple(replace(s,tables=tuple(replace(t,constraints=tuple(constraint(c) for c in t.constraints)) if t.schema=='responsibility' and t.name=='task_occurrence' else t for t in s.tables)+tuple(t for t in TABLES if t.schema==s.name)) for s in schemas)

def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'+'\n'.join(_render_foreign_key(t,f) for t in TABLES for f in t.foreign_keys)+'\n'
 task=next(t for s in after for t in s.tables if t.schema=='responsibility' and t.name=='task_occurrence');c=next(c for c in task.constraints if c.name=='ck_task_occurrence__handoff_cancellation')
 sql+='ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT '+c.name+';\nALTER TABLE responsibility.task_occurrence ADD '+_constraint_sql(c)+';\n'
 for t in TABLES:
  name=t.schema+'.'+t.name
  sql+=f'CREATE TRIGGER trg_{t.name}__immutable BEFORE UPDATE OR DELETE ON {name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();\n'
  sql+=f'CREATE TRIGGER trg_{t.name}__transaction BEFORE INSERT ON {name} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();\n'
  sql+=f'REVOKE ALL ON {name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};\nGRANT SELECT,INSERT ON {name} TO ${{app_command_role}};\nGRANT SELECT ON {name} TO ${{app_query_role}};\n'
 return sql+GUARDS.replace('__CONTRACT_TASKS__',sql_list(TASKS))+barriers(after)

def barriers(schemas):
 """Cover every existing preparation/signing write, not just the public command adapter."""
 sql=r'''
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
'''
 excluded={t.name for t in TABLES}|{'contract_termination','payment_confirmation','template_version','clause_version','template_signing_party','revision_approval_policy','revision_approval_policy_member'}
 for schema in schemas:
  for table in schema.tables:
   if schema.name!='contract' or table.name in excluded:continue
   cols={c.name for c in table.columns};expression=None
   if 'opportunity_id' in cols:expression='NEW.opportunity_id'
   elif 'contract_id' in cols:expression='(SELECT opportunity_id FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=NEW.contract_id)'
   elif 'contract_revision_id' in cols:expression='(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id WHERE v.tenant_id=NEW.tenant_id AND v.contract_revision_id=NEW.contract_revision_id)'
   elif table.name=='preparation_decision':expression='(SELECT opportunity_id FROM contract.preparation_request WHERE tenant_id=NEW.tenant_id AND preparation_request_id=NEW.preparation_request_id)'
   elif table.name in ('revision_review_decision','revision_approval_decision'):
    parent,key=('revision_review_request','request_id') if table.name=='revision_review_decision' else ('revision_approval_requirement','requirement_id')
    expression=f'(SELECT c.opportunity_id FROM contract.contract c JOIN contract.contract_revision v ON v.tenant_id=c.tenant_id AND v.contract_id=c.contract_id JOIN contract.{parent} p ON p.tenant_id=v.tenant_id AND p.contract_revision_id=v.contract_revision_id WHERE p.tenant_id=NEW.tenant_id AND p.{parent}_id=NEW.{key})'
   if expression is None:continue
   name=table.name;function='contract.fn_'+name+'_negotiation_guard'
   sql+=f'''CREATE FUNCTION {function}() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,{expression}); RETURN NEW; END; $fn$;
REVOKE ALL ON FUNCTION {function}() FROM PUBLIC;
CREATE TRIGGER trg_{name}__negotiation_guard BEFORE INSERT ON contract.{name} FOR EACH ROW EXECUTE FUNCTION {function}();
'''
 sql+='''CREATE FUNCTION contract.fn_transfer_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$ BEGIN PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.opportunity_id); RETURN NEW; END; $fn$;
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

'''
 sql+='''CREATE FUNCTION responsibility.fn_contract_task_negotiation_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
 BEGIN
  IF NEW.subject_type='opportunity.opportunity' AND NEW.business_purpose_code IN __CONTRACT_TASKS__ THEN
   PERFORM contract.fn_require_negotiation_active(NEW.tenant_id,NEW.subject_id);
  END IF;
  RETURN NEW;
 END; $fn$;
REVOKE ALL ON FUNCTION responsibility.fn_contract_task_negotiation_guard() FROM PUBLIC;
CREATE TRIGGER trg_task__contract_negotiation_guard BEFORE INSERT ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION responsibility.fn_contract_task_negotiation_guard();
'''.replace('__CONTRACT_TASKS__',sql_list(TASKS))
 return sql

GUARDS=r'''
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
  IF d.kind NOT IN ('STOP_UNSIGNED','REQUEST_REVIEW') OR d.negotiation_disposition_id IS NULL OR d.created_in_transaction<>pg_current_xact_id() OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM d.opportunity_id OR t.subject_revision IS DISTINCT FROM d.opportunity_revision OR t.business_purpose_code NOT IN __CONTRACT_TASKS__ OR t.revision IS DISTINCT FROM NEW.task_revision OR t.state IS DISTINCT FROM NEW.prior_state OR NEW.created_at IS DISTINCT FROM d.created_at THEN RAISE EXCEPTION 'negotiation cancellation member differs' USING ERRCODE='23514'; END IF;
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
  IF EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=d.tenant_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=d.opportunity_id AND t.business_purpose_code IN __CONTRACT_TASKS__ AND t.state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'contract handling responsibility was left active' USING ERRCODE='23514'; END IF;
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
'''
EVOLUTION=ContractEvolution(version=1030,migration_name='V1030__r2_contract_negotiation.sql',contract_version='52-plus-2-r2-v17',apply=apply_evolution,render_sql=render_sql)
