"""F10 independent finance facts; no accounting/refund expansion."""
from ..helpers import *
from ..render import _render_table, _render_foreign_key
from .v920_r2_customer_requirements import fk, checks, body
from .v950_r2_quote_runtime import txn
from .v980_r2_contract_versions import protected

def fact(name,cols,cs=(),refs=(),indexes=()):
 return tenant_table('contract',name,name+'_id','收款办理不可变事实；独立于销售执行责任。',(revision_col(),txn(),uuid_col('opportunity_id','准确商机。'),*cols,time_col('created_at','数据库记录时间。')),constraints=(*checks(name),*cs),foreign_keys=(fk(name,'opportunity_id','opportunity','opportunity'),*(fk(name,c,s,t) for c,s,t in refs)),indexes=indexes)
REQUEST=fact('payment_request',(
 uuid_col('handoff_id','准确签署归档交接。'),uuid_col('contract_revision_id','准确批准合同版本。'),uuid_col('material_version_id','后续逐笔核对的明确凭证；首项可空。',nullable=True),uuid_col('recorded_by','发起任职。'),time_col('due_at','本次责任原期限。'),*body()),
 cs=(*protected('payment_request'),),
 refs=(('handoff_id','contract','signature_handoff'),('contract_revision_id','contract','contract_revision'),('material_version_id','opportunity','material_version'),('recorded_by','identity','appointment')),
 indexes=(index('uq_payment_request__initial',('tenant_id','handoff_id'),'交接首项唯一。',unique_=True,where='material_version_id IS NULL'),index('uq_payment_request__material',('tenant_id','handoff_id','material_version_id'),'后续同版凭证不可重复起项。',unique_=True,where='material_version_id IS NOT NULL')))
WORKFLOW=fact('payment_workflow',(
 uuid_col('request_id','本次核对请求。'),uuid_col('previous_workflow_id','直接前序。',nullable=True),code_col('stage_code','财务核对、销售补正、结束或责任异常。'),code_col('target_stage_code','责任异常时仍保留原办理环节。'),uuid_col('owner_appointment_id','责任任职。',nullable=True),uuid_col('task_id','办理事项。',nullable=True),uuid_col('review_id','触发本次变化的核对事实。',nullable=True),uuid_col('recorded_by','记录任职。'),time_col('due_at','原请求期限，不随补正重置。')),
 cs=(unique('uq_payment_workflow__previous',('tenant_id','previous_workflow_id'),'前序唯一后继。'),check('ck_payment_workflow__stage',"target_stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE') AND (stage_code=target_stage_code OR (stage_code='OWNER_EXCEPTION' AND target_stage_code<>'COMPLETE')) AND stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT','COMPLETE','OWNER_EXCEPTION') AND ((stage_code IN ('CHECK_RECEIPT','SUPPLEMENT_RECEIPT') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL) OR (stage_code IN ('COMPLETE','OWNER_EXCEPTION') AND task_id IS NULL))",'办理责任与阶段一致。')),
 refs=(('request_id','contract','payment_request'),('previous_workflow_id','contract','payment_workflow'),('owner_appointment_id','identity','appointment'),('task_id','responsibility','task_occurrence'),('review_id','contract','payment_review'),('recorded_by','identity','appointment')),
 indexes=(index('uq_payment_workflow__root',('tenant_id','request_id'),'一请求一个责任链根。',unique_=True,where='previous_workflow_id IS NULL'),))
REVIEW=fact('payment_review',(
 uuid_col('workflow_id','核对的准确当前责任。'),code_col('decision_code','确认到账、退回或补正。'),uuid_col('material_version_id','准确材料；退回不要求。',nullable=True),uuid_col('confirmation_id','本笔到账；退回及补正不得填写。',nullable=True),uuid_col('recorded_by','实际办理任职。'),*body()),
 cs=(*protected('payment_review'),unique('uq_payment_review__workflow',('tenant_id','workflow_id'),'同一责任版本只记录一次结果。'),unique('uq_payment_review__confirmation',('tenant_id','confirmation_id'),'同一到账只归入一次核对。'),check('ck_payment_review__decision',"decision_code IN ('CONFIRMED','RETURNED','SUPPLEMENTED') AND (decision_code='CONFIRMED')=(confirmation_id IS NOT NULL) AND (decision_code='RETURNED' OR material_version_id IS NOT NULL)",'退回补正不能伪造到账。')),
 refs=(('workflow_id','contract','payment_workflow'),('material_version_id','opportunity','material_version'),('confirmation_id','contract','payment_confirmation'),('recorded_by','identity','appointment')))
TABLES=(REQUEST,WORKFLOW,REVIEW)
def render():
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'+'\n'.join(_render_foreign_key(t,f) for t in TABLES for f in t.foreign_keys)+'\n'
 for t in TABLES:
  n='contract.'+t.name
  sql+=f"CREATE TRIGGER trg_{t.name}__immutable BEFORE UPDATE OR DELETE ON {n} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();\nCREATE TRIGGER trg_{t.name}__transaction BEFORE INSERT ON {n} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();\nREVOKE ALL ON {n} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};\nGRANT SELECT,INSERT ON {n} TO ${{app_command_role}};\nGRANT SELECT ON {n} TO ${{app_query_role}};\n"
 return sql+GUARDS
GUARDS=r'''
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
'''
