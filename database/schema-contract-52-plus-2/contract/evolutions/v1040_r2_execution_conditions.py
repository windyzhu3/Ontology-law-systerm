"""F10 durable execution-condition responsibility, independent of signature completion."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key
from .v920_r2_customer_requirements import body, checks, fk
from .v950_r2_quote_runtime import txn
from .v980_r2_contract_versions import protected
from . import execution_activation, payment_review

def ref(n,c,t,s='contract'):return fk(n,c,s,t)
def fact(n,cols,cs=(),fks=(),indexes=()):
 return tenant_table('contract',n,n+'_id','执行条件办理不可变事实；不代替到账或合同执行。',(revision_col(),txn(),uuid_col('opportunity_id','准确销售主线。'),*cols,time_col('created_at','数据库记录时间。')),constraints=(*checks(n),*cs),foreign_keys=(ref(n,'opportunity_id','opportunity','opportunity'),*fks),indexes=indexes)

VERIFICATION=fact('execution_verification',(
 uuid_col('handoff_id','准确签署归档交接。'),uuid_col('contract_revision_id','批准合同版本。'),uuid_col('recorded_by','人工核验任职。'),*body()),
 (*protected('execution_verification'),),
 (ref('execution_verification','handoff_id','signature_handoff'),ref('execution_verification','contract_revision_id','contract_revision'),ref('execution_verification','recorded_by','appointment','identity')))
WORKFLOW=fact('execution_workflow',(
 uuid_col('handoff_id','唯一归档交接来源。'),uuid_col('contract_revision_id','当前批准合同版本。'),
 uuid_col('previous_workflow_id','直接前序责任；根为空。',nullable=True),code_col('stage_code','执行条件办理阶段。'),
 uuid_col('owner_appointment_id','合格责任人；异常为空。',nullable=True),uuid_col('task_id','可办理责任；异常或完成为空。',nullable=True),
 uuid_col('verification_id','已人工核验条件。',nullable=True),uuid_col('execution_id','满足门禁后的合同执行事实。',nullable=True),
 uuid_col('recorded_by','办理者或恢复服务任职。'),time_col('due_at','首次交接确定的原期限，恢复不延长。')),
 (unique('uq_execution_workflow__previous',('tenant_id','previous_workflow_id'),'只允许一个后继。'),
  check('ck_execution_workflow__stage',"stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT','READY_TRANSFER','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_CONDITIONS','WAIT_RECEIPT') AND owner_appointment_id IS NOT NULL AND task_id IS NOT NULL) OR (stage_code IN ('READY_TRANSFER','OWNER_EXCEPTION') AND task_id IS NULL)) AND (stage_code='READY_TRANSFER')=(execution_id IS NOT NULL) AND (stage_code<>'READY_TRANSFER' OR verification_id IS NOT NULL)",'阶段与可办理责任一致。')),
 tuple(ref('execution_workflow',c,t,s) for c,t,s in (('handoff_id','signature_handoff','contract'),('contract_revision_id','contract_revision','contract'),('previous_workflow_id','execution_workflow','contract'),('owner_appointment_id','appointment','identity'),('task_id','task_occurrence','responsibility'),('verification_id','execution_verification','contract'),('execution_id','contract_execution','contract'),('recorded_by','appointment','identity'))),
 (index('uq_execution_workflow__root',('tenant_id','handoff_id'),'交接只形成一个责任链根。',unique_=True,where='previous_workflow_id IS NULL'),))
TABLES=(VERIFICATION,WORKFLOW)
def apply_evolution(schemas):return execution_activation.extend(tuple(replace(s,tables=(*s.tables,*TABLES,*payment_review.TABLES)) if s.name=='contract' else s for s in schemas))
def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'+'\n'.join(_render_foreign_key(t,f) for t in TABLES for f in t.foreign_keys)+'\n'
 for t in TABLES:
  n='contract.'+t.name
  sql+=f"CREATE TRIGGER trg_{t.name}__immutable BEFORE UPDATE OR DELETE ON {n} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();\n"
  sql+=f"CREATE TRIGGER trg_{t.name}__transaction BEFORE INSERT ON {n} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();\n"
  sql+=f"REVOKE ALL ON {n} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};\nGRANT SELECT,INSERT ON {n} TO ${{app_command_role}};\nGRANT SELECT ON {n} TO ${{app_query_role}};\n"
 return sql+GUARDS+execution_activation.render(before,after)+payment_review.render()

GUARDS=r'''
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
'''
EVOLUTION=ContractEvolution(version=1040,migration_name='V1040__r2_execution_conditions.sql',contract_version='52-plus-2-r2-v18',apply=apply_evolution,render_sql=render_sql)
