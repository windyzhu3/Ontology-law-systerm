"""Q transfer pending submissions and original-deadline responsibility chain."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key
from .v920_r2_customer_requirements import fk, checks, body
from .v950_r2_quote_runtime import txn
from .v980_r2_contract_versions import protected

def fact(name,cols,cs=(),refs=(),indexes=()):
 return tenant_table('transfer',name,name+'_id','转案办理不可变事实；接收之前不生成案件。',(revision_col(),txn(),uuid_col('transfer_request_id','准确转案来源。'),uuid_col('opportunity_id','准确商机。'),*cols,time_col('created_at','数据库记录时间。')),constraints=(*checks(name),*cs),foreign_keys=(fk(name,'transfer_request_id','transfer','transfer_request'),fk(name,'opportunity_id','opportunity','opportunity'),*(fk(name,c,s,t) for c,s,t in refs)),indexes=indexes)
WORKFLOW=fact('workflow',(
 uuid_col('previous_workflow_id','直接前序责任。',nullable=True),code_col('stage_code','当前可办理阶段或责任异常。'),code_col('target_stage_code','责任异常仍保留原阶段。'),uuid_col('owner_appointment_id','当前责任任职。',nullable=True),uuid_col('task_id','当前待办。',nullable=True),uuid_col('submission_id','准确待审提交。',nullable=True),uuid_col('review_id','本次独立审查。',nullable=True),uuid_col('intake_id','准确案管接收或退回。',nullable=True),uuid_col('classification_id','本案分类及承接事实。',nullable=True),uuid_col('recorded_by','记录任职。'),time_col('due_at','原责任期限，恢复和补正不重置。')),
 cs=(unique('uq_transfer_workflow__previous',('tenant_id','previous_workflow_id'),'同一责任只有一个后继。'),check('ck_transfer_workflow__stage',"target_stage_code IN ('PREPARE','REVIEW_TRANSFER','INTAKE','SUPPLEMENT','CLASSIFY','COMPLETE') AND (stage_code=target_stage_code OR stage_code='OWNER_EXCEPTION') AND ((stage_code IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NULL AND owner_appointment_id IS NULL) OR (stage_code NOT IN ('OWNER_EXCEPTION','COMPLETE') AND task_id IS NOT NULL AND owner_appointment_id IS NOT NULL)) AND (target_stage_code='PREPARE')=(submission_id IS NULL)",'阶段、待审提交与责任一致。')),
 refs=(('previous_workflow_id','transfer','workflow'),('owner_appointment_id','identity','appointment'),('task_id','responsibility','task_occurrence'),('submission_id','transfer','submission'),('review_id','transfer','review'),('intake_id','transfer','intake'),('classification_id','transfer','classification'),('recorded_by','identity','appointment')),
 indexes=(index('uq_transfer_workflow__root',('tenant_id','transfer_request_id'),'一请求仅有一个责任链根。',unique_=True,where='previous_workflow_id IS NULL'),))
SUBMISSION=fact('submission',(
 col('evidence_submission_ids','uuid[]','本次完整已接收证据集合，包含补正证据。'), uuid_col('previous_submission_id','补正对应的上一提交。',nullable=True),uuid_col('previous_review_id','补正对应的准确退回审查。',nullable=True),uuid_col('previous_intake_id','案管退回的准确决定。',nullable=True), uuid_col('workflow_id','本次销售办理的准确责任。'),uuid_col('contract_revision_id','批准合同版本。'),uuid_col('customer_confirmation_id','当前客户及需求确认。'),uuid_col('client_identity_material_id','已接收主体证明。'),uuid_col('signature_archive_material_id','已接收完整签署归档。'),uuid_col('confirmed_action_draft_id','人工确认的准确输入草案。'),digest_col('action_draft_digest','准确确认草案摘要。'),digest_col('contract_context_digest','执行来源与合同上下文摘要。'),digest_col('legal_need_context_digest','提交时法律需求摘要。'),uuid_col('recorded_by','实际销售提交任职。'),*body()),
 cs=(*protected('submission'),unique('uq_transfer_submission__workflow',('tenant_id','workflow_id'),'一销售责任只提交一次。'),unique('uq_transfer_submission__draft',('tenant_id','confirmed_action_draft_id'),'一确认草案只形成一次提交。')),
 refs=(('previous_submission_id','transfer','submission'),('previous_review_id','transfer','review'),('previous_intake_id','transfer','intake'),('workflow_id','transfer','workflow'),('contract_revision_id','contract','contract_revision'),('customer_confirmation_id','opportunity','customer_requirement_confirmation'),('client_identity_material_id','opportunity','material_version'),('signature_archive_material_id','opportunity','material_version'),('confirmed_action_draft_id','responsibility','action_draft'),('recorded_by','identity','appointment')))
REVIEW=fact('review',(
 uuid_col('workflow_id','被办理的独立审查责任。'),uuid_col('submission_id','准确销售提交。'),uuid_col('conflict_review_id','独立PRE_TRANSFER事实。'),code_col('outcome_code','人工审查结果。'),uuid_col('confirmed_action_draft_id','准确确认草案。'),digest_col('action_draft_digest','准确草案摘要。'),digest_col('scope_digest','范围摘要。'),digest_col('corpus_digest','语料摘要。'),uuid_col('recorded_by','实际独立审查人。'),*body()),
 cs=(*protected('review'),unique('uq_transfer_review__workflow',('tenant_id','workflow_id'),'一次责任一个审查结果。'),unique('uq_transfer_review__conflict',('tenant_id','conflict_review_id'),'审查事实专属本提交。'),check('ck_transfer_review__outcome',"outcome_code IN ('CLEAR','NEED_INFO','BLOCKED')",'不自动豁免。')),
 refs=(('workflow_id','transfer','workflow'),('submission_id','transfer','submission'),('conflict_review_id','conflict','conflict_review'),('confirmed_action_draft_id','responsibility','action_draft'),('recorded_by','identity','appointment')))
REVIEW_RETURN_ITEM=fact('review_return_item',(
 uuid_col('review_id','准确非通过审查。'),code_col('requirement_code','准确补正要求。'),*body()),
 cs=(*protected('review_return_item'),unique('uq_transfer_review_return_item__requirement',('tenant_id','review_id','requirement_code'),'一次审查一个具名要求。')),
 refs=(('review_id','transfer','review'),))
INTAKE=fact('intake',(
 uuid_col('workflow_id','准确案管待办。'),uuid_col('submission_id','接收的提交。'),uuid_col('review_id','准确独立审查。'),uuid_col('snapshot_id','案管核对的完整冻结快照。'),uuid_col('decision_record_id','独立案管决定。'),code_col('outcome_code','接收或退回。'),code_col('requirement_code','退回项目。',nullable=True),uuid_col('matter_id','接收后唯一案件身份。',nullable=True),code_col('matter_no','唯一案件编号。',nullable=True),uuid_col('confirmed_action_draft_id','接收核对草案。'),digest_col('action_draft_digest','草案摘要。'),uuid_col('recorded_by','案管接收任职。'),*body()),
 cs=(*protected('intake'),unique('uq_transfer_intake__workflow',('tenant_id','workflow_id'),'一责任一决定。'),unique('uq_transfer_intake__snapshot',('tenant_id','snapshot_id'),'一快照一决定。'),check('ck_transfer_intake__outcome',"(outcome_code='ACCEPT' AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND requirement_code IS NULL) OR (outcome_code='RETURN' AND matter_id IS NULL AND matter_no IS NULL AND requirement_code IS NOT NULL)",'未接收不得生成案件。')),
 refs=(('workflow_id','transfer','workflow'),('submission_id','transfer','submission'),('review_id','transfer','review'),('snapshot_id','transfer','transfer_snapshot'),('decision_record_id','responsibility','decision_record'),('confirmed_action_draft_id','responsibility','action_draft'),('recorded_by','identity','appointment')))
CLASSIFICATION=fact('classification',(
 uuid_col('previous_classification_id','明确更正时保留前次分类。',nullable=True), uuid_col('workflow_id','已接收案件的分类责任。'),uuid_col('intake_id','准确接收事实。'),uuid_col('matter_id','保持已接收案件身份。'),code_col('category_code','综法、执行或其他。'),uuid_col('recipient_appointment_id','有权承接任职。'),uuid_col('confirmed_action_draft_id','人工分类草案。'),digest_col('action_draft_digest','分类草案摘要。'),uuid_col('recorded_by','分类确认人。'),*body()),
 cs=(*protected('classification'),unique('uq_transfer_classification__previous',('tenant_id','previous_classification_id'),'前次分类只允许一个更正后继。'),unique('uq_transfer_classification__workflow',('tenant_id','workflow_id'),'一个分类责任一个结果。'),check('ck_transfer_classification__category',"category_code IN ('GENERAL','ENFORCEMENT','OTHER')",'限定MVP分类。')),
 refs=(('previous_classification_id','transfer','classification'),('workflow_id','transfer','workflow'),('intake_id','transfer','intake'),('recipient_appointment_id','identity','appointment'),('confirmed_action_draft_id','responsibility','action_draft'),('recorded_by','identity','appointment')))
TABLES=(WORKFLOW,SUBMISSION,REVIEW,REVIEW_RETURN_ITEM,INTAKE,CLASSIFICATION)
def apply_evolution(schemas):return tuple(replace(s,tables=(*s.tables,*TABLES)) if s.name=='transfer' else s for s in schemas)
def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'+'\n'.join(_render_foreign_key(t,f) for t in TABLES for f in t.foreign_keys)+'\n'
 for t in TABLES:
  n='transfer.'+t.name
  sql+=f"CREATE TRIGGER trg_transfer_{t.name}__immutable BEFORE UPDATE OR DELETE ON {n} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();\nCREATE TRIGGER trg_transfer_{t.name}__transaction BEFORE INSERT ON {n} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();\nREVOKE ALL ON {n} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};\nGRANT SELECT,INSERT ON {n} TO ${{app_command_role}};\nGRANT SELECT ON {n} TO ${{app_query_role}};\n"
 return sql+GUARDS
GUARDS=r'''
CREATE FUNCTION transfer.fn_workflow_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r transfer.transfer_request%ROWTYPE; p transfer.workflow%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; s transfer.submission%ROWTYPE; rv transfer.review%ROWTYPE; it transfer.intake%ROWTYPE; cl transfer.classification%ROWTYPE;
BEGIN
 SELECT * INTO r FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 IF r.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR (r.accepted_snapshot_id IS NOT NULL AND (NEW.target_stage_code NOT IN ('CLASSIFY','COMPLETE') OR NOT EXISTS(SELECT 1 FROM transfer.intake a WHERE a.tenant_id=NEW.tenant_id AND a.intake_id=NEW.intake_id AND a.snapshot_id=r.accepted_snapshot_id AND a.matter_id=r.matter_id AND a.outcome_code='ACCEPT'))) THEN RAISE EXCEPTION 'transfer workflow request differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_workflow_id IS NULL THEN
  IF NEW.target_stage_code<>'PREPARE' THEN RAISE EXCEPTION 'transfer initial stage differs' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO p FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.previous_workflow_id;
  IF p.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR p.opportunity_id IS DISTINCT FROM NEW.opportunity_id THEN RAISE EXCEPTION 'transfer workflow predecessor differs' USING ERRCODE='23514'; END IF;
  IF p.due_at IS DISTINCT FROM NEW.due_at THEN RAISE EXCEPTION 'transfer original deadline changed' USING ERRCODE='23514'; END IF;
  IF p.task_id IS NOT NULL THEN
   SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=p.task_id;
   IF NEW.submission_id IS DISTINCT FROM p.submission_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.submission' OR t.completion_fact_id IS DISTINCT FROM NEW.submission_id OR t.completion_fact_revision IS DISTINCT FROM 0 THEN RAISE EXCEPTION 'transfer predecessor completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.review_id IS DISTINCT FROM p.review_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.review' OR t.completion_fact_id IS DISTINCT FROM NEW.review_id THEN RAISE EXCEPTION 'transfer review completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.intake_id IS DISTINCT FROM p.intake_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.intake' OR t.completion_fact_id IS DISTINCT FROM NEW.intake_id THEN RAISE EXCEPTION 'transfer intake completion differs' USING ERRCODE='23514'; END IF;
   ELSIF NEW.classification_id IS DISTINCT FROM p.classification_id THEN
    IF t.state IS DISTINCT FROM 'DONE' OR t.completion_fact_type IS DISTINCT FROM 'transfer.classification' OR t.completion_fact_id IS DISTINCT FROM NEW.classification_id THEN RAISE EXCEPTION 'transfer classification completion differs' USING ERRCODE='23514'; END IF;
   ELSIF t.state IS DISTINCT FROM 'CANCELLED' THEN RAISE EXCEPTION 'transfer reassignment requires cancelled predecessor' USING ERRCODE='23514'; END IF;
  END IF;
  IF NEW.submission_id IS DISTINCT FROM p.submission_id THEN
   SELECT * INTO s FROM transfer.submission WHERE tenant_id=NEW.tenant_id AND submission_id=NEW.submission_id;
   IF p.stage_code NOT IN ('PREPARE','SUPPLEMENT') OR NEW.review_id IS NOT NULL OR NEW.intake_id IS NOT NULL OR NEW.target_stage_code<>'REVIEW_TRANSFER' OR s.workflow_id IS DISTINCT FROM p.workflow_id OR s.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'transfer submitted successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.review_id IS DISTINCT FROM p.review_id THEN
   SELECT * INTO rv FROM transfer.review WHERE tenant_id=NEW.tenant_id AND review_id=NEW.review_id;
   IF p.stage_code<>'REVIEW_TRANSFER' OR rv.workflow_id IS DISTINCT FROM p.workflow_id OR rv.submission_id IS DISTINCT FROM NEW.submission_id OR rv.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NEW.target_stage_code IS DISTINCT FROM (CASE WHEN rv.outcome_code='CLEAR' THEN 'INTAKE' ELSE 'SUPPLEMENT' END) THEN RAISE EXCEPTION 'transfer reviewed successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.intake_id IS DISTINCT FROM p.intake_id THEN
   SELECT * INTO it FROM transfer.intake WHERE tenant_id=NEW.tenant_id AND intake_id=NEW.intake_id;
   IF p.stage_code<>'INTAKE' OR it.workflow_id IS DISTINCT FROM p.workflow_id OR it.review_id IS DISTINCT FROM NEW.review_id OR it.submission_id IS DISTINCT FROM NEW.submission_id OR it.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NEW.target_stage_code IS DISTINCT FROM (CASE WHEN it.outcome_code='ACCEPT' THEN 'CLASSIFY' ELSE 'SUPPLEMENT' END) THEN RAISE EXCEPTION 'transfer intake successor differs' USING ERRCODE='23514'; END IF;
  ELSIF NEW.classification_id IS DISTINCT FROM p.classification_id THEN
   SELECT * INTO cl FROM transfer.classification WHERE tenant_id=NEW.tenant_id AND classification_id=NEW.classification_id;
   IF p.stage_code<>'CLASSIFY' OR NEW.target_stage_code<>'COMPLETE' OR cl.workflow_id IS DISTINCT FROM p.workflow_id OR cl.intake_id IS DISTINCT FROM NEW.intake_id OR cl.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'transfer classification successor differs' USING ERRCODE='23514'; END IF;
  ELSIF p.stage_code='COMPLETE' AND NEW.stage_code='CLASSIFY' AND NEW.classification_id IS NOT DISTINCT FROM p.classification_id AND NEW.intake_id IS NOT DISTINCT FROM p.intake_id THEN NULL;
  ELSIF NEW.target_stage_code IS DISTINCT FROM p.target_stage_code THEN RAISE EXCEPTION 'transfer responsibility cannot skip stages' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.stage_code IN ('REVIEW_TRANSFER','INTAKE') AND EXISTS(
  SELECT 1 FROM transfer.submission submitted
  JOIN identity.appointment sales ON sales.tenant_id=submitted.tenant_id AND sales.appointment_id=submitted.recorded_by
  JOIN identity.appointment reviewer ON reviewer.tenant_id=sales.tenant_id AND reviewer.appointment_id=NEW.owner_appointment_id
  WHERE submitted.tenant_id=NEW.tenant_id AND submitted.submission_id=NEW.submission_id AND sales.principal_id=reviewer.principal_id
 ) THEN RAISE EXCEPTION 'transfer review must be independent of submitter' USING ERRCODE='23514'; END IF;
 IF NEW.task_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_id;
  IF t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR t.original_sla_due_at IS DISTINCT FROM NEW.due_at OR t.state IS DISTINCT FROM 'OPEN' OR t.business_purpose_code IS DISTINCT FROM (CASE WHEN NEW.stage_code='PREPARE' THEN 'PREPARE_TRANSFER' WHEN NEW.stage_code='INTAKE' THEN 'ACCEPT_TRANSFER' WHEN NEW.stage_code='CLASSIFY' THEN 'CLASSIFY_MATTER' WHEN NEW.stage_code='SUPPLEMENT' THEN 'SUPPLEMENT_TRANSFER' ELSE 'REVIEW_TRANSFER' END) THEN RAISE EXCEPTION 'transfer task differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_submission_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; r transfer.transfer_request%ROWTYPE; k contract.contract%ROWTYPE; t responsibility.task_occurrence%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO r FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 SELECT * INTO k FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=r.contract_id;
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=w.task_id;
 IF r.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.accepted_snapshot_id IS NOT NULL OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.stage_code NOT IN ('PREPARE','SUPPLEMENT') OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR t.state IS DISTINCT FROM 'OPEN' OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer submission responsibility differs' USING ERRCODE='23514'; END IF;
 IF (w.stage_code='PREPARE' AND (NEW.previous_submission_id IS NOT NULL OR NEW.previous_review_id IS NOT NULL OR NEW.previous_intake_id IS NOT NULL)) OR (w.stage_code='SUPPLEMENT' AND (NEW.previous_submission_id IS DISTINCT FROM w.submission_id OR NEW.previous_review_id IS DISTINCT FROM w.review_id OR NEW.previous_intake_id IS DISTINCT FROM w.intake_id OR NOT ((w.intake_id IS NULL AND EXISTS(SELECT 1 FROM transfer.review rv WHERE rv.tenant_id=NEW.tenant_id AND rv.review_id=NEW.previous_review_id AND rv.submission_id=NEW.previous_submission_id AND rv.outcome_code<>'CLEAR')) OR (w.intake_id IS NOT NULL AND EXISTS(SELECT 1 FROM transfer.intake i WHERE i.tenant_id=NEW.tenant_id AND i.intake_id=w.intake_id AND i.submission_id=NEW.previous_submission_id AND i.outcome_code='RETURN'))))) THEN RAISE EXCEPTION 'transfer correction predecessor differs' USING ERRCODE='23514'; END IF;
 IF k.current_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.approved_revision_id IS DISTINCT FROM NEW.contract_revision_id OR k.contract_execution_id IS DISTINCT FROM r.contract_execution_id OR k.activation_source_hash IS DISTINCT FROM r.deal_activation_digest OR k.deal_activated_at IS DISTINCT FROM r.deal_activated_at OR k.contract_termination_id IS NOT NULL THEN RAISE EXCEPTION 'transfer executed basis differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'transfer customer basis differs' USING ERRCODE='23514'; END IF;
 IF cardinality(NEW.evidence_submission_ids)<1 OR EXISTS(SELECT 1 FROM unnest(NEW.evidence_submission_ids) e(id) WHERE NOT EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.opportunity_id=NEW.opportunity_id AND v.evidence_submission_id=e.id)) THEN RAISE EXCEPTION 'transfer frozen evidence differs' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM unnest(ARRAY[NEW.client_identity_material_id,NEW.signature_archive_material_id]) material(id) WHERE NOT EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=material.id AND v.opportunity_id=NEW.opportunity_id AND v.evidence_submission_id IS NOT NULL)) THEN RAISE EXCEPTION 'transfer material basis differs' USING ERRCODE='23514'; END IF;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest OR d.action_code IS DISTINCT FROM (CASE WHEN w.stage_code='SUPPLEMENT' THEN 'RESUBMIT_TRANSFER' ELSE 'SUBMIT_TRANSFER' END) THEN RAISE EXCEPTION 'transfer submission exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_submission_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.submission' AND t.completion_fact_id=NEW.submission_id AND t.completion_fact_revision=0) THEN RAISE EXCEPTION 'transfer submission completion differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=NEW.workflow_id AND n.submission_id=NEW.submission_id AND n.target_stage_code='REVIEW_TRANSFER' AND n.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer submission successor missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_review_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; s transfer.submission%ROWTYPE; r conflict.conflict_review%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO s FROM transfer.submission WHERE tenant_id=NEW.tenant_id AND submission_id=NEW.submission_id;
 SELECT * INTO r FROM conflict.conflict_review WHERE tenant_id=NEW.tenant_id AND conflict_review_id=NEW.conflict_review_id;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF w.stage_code IS DISTINCT FROM 'REVIEW_TRANSFER' OR w.submission_id IS DISTINCT FROM NEW.submission_id OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR s.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR s.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer review responsibility differs' USING ERRCODE='23514'; END IF;
 IF r.review_type_code IS DISTINCT FROM 'PRE_TRANSFER' OR r.trigger_fact_type IS DISTINCT FROM 'transfer.submission' OR r.trigger_fact_id IS DISTINCT FROM NEW.submission_id OR r.trigger_fact_hash IS DISTINCT FROM s.body_digest OR r.scope_hash IS DISTINCT FROM NEW.scope_digest OR r.corpus_hash IS DISTINCT FROM NEW.corpus_digest OR (NEW.outcome_code='CLEAR' AND r.initial_conclusion_code IS DISTINCT FROM 'CLEAR') OR (NEW.outcome_code='NEED_INFO' AND r.initial_conclusion_code IS DISTINCT FROM 'NEED_INFO') OR (NEW.outcome_code='BLOCKED' AND r.resolution_code IS DISTINCT FROM 'BLOCKED') THEN RAISE EXCEPTION 'transfer independent conflict result differs' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.action_code IS DISTINCT FROM 'RECORD_TRANSFER_CONFLICT_REVIEW' OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'transfer review exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_review_return_item_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.review rv WHERE rv.tenant_id=NEW.tenant_id AND rv.review_id=NEW.review_id AND rv.transfer_request_id=NEW.transfer_request_id AND rv.opportunity_id=NEW.opportunity_id AND rv.outcome_code<>'CLEAR' AND rv.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer return item requires exact nonclear review' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_review_return_item_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_review_return_item__exact BEFORE INSERT ON transfer.review_return_item FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_return_item_guard();
CREATE FUNCTION transfer.fn_review_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF (NEW.outcome_code<>'CLEAR') IS DISTINCT FROM EXISTS(SELECT 1 FROM transfer.review_return_item i WHERE i.tenant_id=NEW.tenant_id AND i.review_id=NEW.review_id) THEN RAISE EXCEPTION 'transfer nonclear review requires correction items' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.review' AND t.completion_fact_id=NEW.review_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.review_id=NEW.review_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer review successor missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_review_guard(),transfer.fn_review_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_review__exact BEFORE INSERT ON transfer.review FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_review__completion AFTER INSERT ON transfer.review DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_review_completion_guard();
CREATE OR REPLACE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $fn$
DECLARE accepted_snapshot uuid;
BEGIN
 SELECT r.accepted_snapshot_id INTO accepted_snapshot FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'transfer snapshot requires its exact request' USING ERRCODE='23503'; END IF;
 IF accepted_snapshot IS NOT NULL AND (accepted_snapshot IS DISTINCT FROM NEW.transfer_snapshot_id OR NOT EXISTS(
  SELECT 1 FROM transfer.intake i JOIN transfer.transfer_request r ON r.tenant_id=i.tenant_id AND r.transfer_request_id=i.transfer_request_id
  WHERE i.tenant_id=NEW.tenant_id AND i.transfer_request_id=NEW.transfer_request_id AND i.snapshot_id=NEW.transfer_snapshot_id AND i.outcome_code='ACCEPT' AND i.created_in_transaction=pg_current_xact_id()
   AND r.accepted_snapshot_id=i.snapshot_id AND r.accept_decision_record_id=i.decision_record_id AND r.matter_id=i.matter_id AND r.matter_no=i.matter_no
 )) THEN RAISE EXCEPTION 'accepted transfer rejects new snapshots' USING ERRCODE='55000'; END IF;
 IF NEW.snapshot_no>1 AND NOT EXISTS(SELECT 1 FROM transfer.transfer_snapshot p WHERE p.tenant_id=NEW.tenant_id AND p.transfer_snapshot_id=NEW.predecessor_snapshot_id AND p.transfer_request_id=NEW.transfer_request_id AND p.snapshot_no+1=NEW.snapshot_no) THEN RAISE EXCEPTION 'transfer snapshot must follow the direct predecessor of the same request' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_intake_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; rv transfer.review%ROWTYPE; snap transfer.transfer_snapshot%ROWTYPE; d responsibility.decision_record%ROWTYPE; draft responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO rv FROM transfer.review WHERE tenant_id=NEW.tenant_id AND review_id=NEW.review_id;
 SELECT * INTO snap FROM transfer.transfer_snapshot WHERE tenant_id=NEW.tenant_id AND transfer_snapshot_id=NEW.snapshot_id;
 SELECT * INTO d FROM responsibility.decision_record WHERE tenant_id=NEW.tenant_id AND decision_record_id=NEW.decision_record_id;
 SELECT * INTO draft FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF w.stage_code IS DISTINCT FROM 'INTAKE' OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR w.submission_id IS DISTINCT FROM NEW.submission_id OR w.review_id IS DISTINCT FROM NEW.review_id OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR rv.outcome_code IS DISTINCT FROM 'CLEAR' OR snap.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR snap.pre_transfer_review_id IS DISTINCT FROM rv.conflict_review_id OR snap.pre_transfer_scope_hash IS DISTINCT FROM rv.scope_digest OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'transfer intake exact cleared basis required' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.decided_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.decision_code IS DISTINCT FROM NEW.outcome_code OR d.decision_contract_code IS DISTINCT FROM 'R2_TRANSFER_INTAKE_V1' OR d.decision_subject_type IS DISTINCT FROM 'transfer.transfer_snapshot' OR d.decision_subject_id IS DISTINCT FROM NEW.snapshot_id OR d.decision_subject_hash IS DISTINCT FROM snap.snapshot_digest THEN RAISE EXCEPTION 'transfer intake decision differs' USING ERRCODE='23514'; END IF;
 IF draft.task_occurrence_id IS DISTINCT FROM w.task_id OR draft.action_code IS DISTINCT FROM 'RECORD_TRANSFER_INTAKE' OR draft.state IS DISTINCT FROM 'CONFIRMED' OR draft.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR draft.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'transfer intake exact draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_intake_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.intake' AND t.completion_fact_id=NEW.intake_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.intake_id=NEW.intake_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'transfer intake successor missing' USING ERRCODE='23514'; END IF;
 IF NEW.outcome_code='RETURN' AND (EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.accepted_snapshot_id IS NOT NULL) OR NOT EXISTS(SELECT 1 FROM transfer.transfer_return_item i WHERE i.tenant_id=NEW.tenant_id AND i.reviewed_snapshot_id=NEW.snapshot_id AND i.return_decision_record_id=NEW.decision_record_id AND i.requirement_code=NEW.requirement_code)) THEN RAISE EXCEPTION 'transfer intake return requires exact correction items' USING ERRCODE='23514'; END IF;
 IF NEW.outcome_code='ACCEPT' AND NOT EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.accepted_snapshot_id=NEW.snapshot_id AND r.accept_decision_record_id=NEW.decision_record_id AND r.matter_id=NEW.matter_id AND r.matter_no=NEW.matter_no) THEN RAISE EXCEPTION 'transfer accepted case identity differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_intake_guard(),transfer.fn_intake_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_intake__exact BEFORE INSERT ON transfer.intake FOR EACH ROW EXECUTE FUNCTION transfer.fn_intake_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_intake__completion AFTER INSERT ON transfer.intake DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_intake_completion_guard();
CREATE FUNCTION transfer.fn_classification_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w transfer.workflow%ROWTYPE; i transfer.intake%ROWTYPE; d responsibility.action_draft%ROWTYPE;
BEGIN
 SELECT * INTO w FROM transfer.workflow WHERE tenant_id=NEW.tenant_id AND workflow_id=NEW.workflow_id;
 SELECT * INTO i FROM transfer.intake WHERE tenant_id=NEW.tenant_id AND intake_id=NEW.intake_id;
 SELECT * INTO d FROM responsibility.action_draft WHERE tenant_id=NEW.tenant_id AND action_draft_id=NEW.confirmed_action_draft_id;
 IF NEW.previous_classification_id IS DISTINCT FROM w.classification_id OR w.stage_code IS DISTINCT FROM 'CLASSIFY' OR w.owner_appointment_id IS DISTINCT FROM NEW.recorded_by OR w.intake_id IS DISTINCT FROM NEW.intake_id OR w.transfer_request_id IS DISTINCT FROM NEW.transfer_request_id OR w.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR i.outcome_code IS DISTINCT FROM 'ACCEPT' OR i.matter_id IS DISTINCT FROM NEW.matter_id OR NOT EXISTS(SELECT 1 FROM transfer.transfer_request r WHERE r.tenant_id=NEW.tenant_id AND r.transfer_request_id=NEW.transfer_request_id AND r.matter_id=NEW.matter_id AND r.accepted_snapshot_id=i.snapshot_id) OR EXISTS(SELECT 1 FROM transfer.workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.workflow_id) THEN RAISE EXCEPTION 'classification requires accepted exact case' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM identity.appointment a JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE a.tenant_id=NEW.tenant_id AND a.appointment_id=NEW.recipient_appointment_id AND a.state='ACTIVE' AND a.effective_from<=NEW.created_at AND (a.effective_until IS NULL OR a.effective_until>NEW.created_at) AND p.state='ACTIVE' AND p.principal_kind='HUMAN') THEN RAISE EXCEPTION 'classification recipient unavailable' USING ERRCODE='23514'; END IF;
 IF d.task_occurrence_id IS DISTINCT FROM w.task_id OR d.action_code IS DISTINCT FROM 'CLASSIFY_MATTER' OR d.state IS DISTINCT FROM 'CONFIRMED' OR d.confirmed_by_appointment_id IS DISTINCT FROM NEW.recorded_by OR d.confirmed_payload_digest IS DISTINCT FROM NEW.action_draft_digest THEN RAISE EXCEPTION 'classification exact confirmed draft required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
CREATE FUNCTION transfer.fn_classification_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM transfer.workflow w JOIN responsibility.task_occurrence t ON t.tenant_id=w.tenant_id AND t.task_occurrence_id=w.task_id WHERE w.tenant_id=NEW.tenant_id AND w.workflow_id=NEW.workflow_id AND t.state='DONE' AND t.completion_fact_type='transfer.classification' AND t.completion_fact_id=NEW.classification_id) OR NOT EXISTS(SELECT 1 FROM transfer.workflow w WHERE w.tenant_id=NEW.tenant_id AND w.previous_workflow_id=NEW.workflow_id AND w.stage_code='COMPLETE' AND w.classification_id=NEW.classification_id AND w.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'classification completion missing' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_classification_guard(),transfer.fn_classification_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_classification__exact BEFORE INSERT ON transfer.classification FOR EACH ROW EXECUTE FUNCTION transfer.fn_classification_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_classification__completion AFTER INSERT ON transfer.classification DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_classification_completion_guard();
CREATE FUNCTION transfer.fn_reclassification_completion_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.stage_code='CLASSIFY' AND EXISTS(SELECT 1 FROM transfer.workflow p WHERE p.tenant_id=NEW.tenant_id AND p.workflow_id=NEW.previous_workflow_id AND p.stage_code='COMPLETE') AND NOT EXISTS(SELECT 1 FROM transfer.workflow n JOIN transfer.classification cl ON cl.tenant_id=n.tenant_id AND cl.classification_id=n.classification_id WHERE n.tenant_id=NEW.tenant_id AND n.previous_workflow_id=NEW.workflow_id AND n.stage_code='COMPLETE' AND n.created_in_transaction=pg_current_xact_id() AND cl.previous_classification_id=NEW.classification_id AND cl.workflow_id=NEW.workflow_id) THEN RAISE EXCEPTION 'explicit reclassification must close atomically' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;$fn$;
REVOKE ALL ON FUNCTION transfer.fn_reclassification_completion_guard() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_transfer_workflow__reclassification AFTER INSERT ON transfer.workflow DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_reclassification_completion_guard();
REVOKE ALL ON FUNCTION transfer.fn_workflow_guard(),transfer.fn_submission_guard(),transfer.fn_submission_completion_guard() FROM PUBLIC;
CREATE TRIGGER trg_transfer_workflow__exact BEFORE INSERT ON transfer.workflow FOR EACH ROW EXECUTE FUNCTION transfer.fn_workflow_guard();
CREATE TRIGGER trg_transfer_submission__exact BEFORE INSERT ON transfer.submission FOR EACH ROW EXECUTE FUNCTION transfer.fn_submission_guard();
CREATE CONSTRAINT TRIGGER trg_transfer_submission__completion AFTER INSERT ON transfer.submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer.fn_submission_completion_guard();
DO $v1050$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v19',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v18';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1050 requires 52-plus-2-r2-v18' USING ERRCODE='55000'; END IF;
END;$v1050$;
'''
EVOLUTION=ContractEvolution(version=1050,migration_name='V1050__r2_transfer_workflow.sql',contract_version='52-plus-2-r2-v19',apply=apply_evolution,render_sql=render_sql)
