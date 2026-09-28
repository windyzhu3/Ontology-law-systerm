ALTER TABLE contract.preparation_workflow DROP CONSTRAINT ck_preparation_workflow__recovery_resume;
ALTER TABLE contract.preparation_workflow ADD CONSTRAINT ck_preparation_workflow__recovery_resume CHECK (recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('DIRECT_RETURNED','PREPARE','RETURNED','REVIEW_BLOCKED','SUBMIT_REVIEW','SUBMIT_APPROVAL','REVIEW_SUPPLEMENT') AND task_id IS NULL AND prior_task_id IS NOT NULL));
COMMENT ON COLUMN contract.preparation_workflow.recovery_resume_stage IS '失权时保留的准确恢复阶段；原任务类型和期限不变。';
COMMENT ON CONSTRAINT ck_preparation_workflow__recovery_resume ON contract.preparation_workflow IS '仅无可办理任务的负责人异常保留恢复目标；不伪造退回决定。';
CREATE OR REPLACE FUNCTION contract.fn_check_r2_fact() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE v uuid; root contract.contract%ROWTYPE; r contract.contract_revision%ROWTYPE; prior uuid; req contract.revision_review_request%ROWTYPE; dec contract.revision_review_decision%ROWTYPE; review conflict.conflict_review%ROWTYPE; b contract.revision_review_binding%ROWTYPE; requirement contract.revision_approval_requirement%ROWTYPE; ar contract.revision_approval_request%ROWTYPE;
BEGIN
 NEW.created_in_transaction=pg_current_xact_id();
 IF TG_TABLE_NAME='preparation_decision' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='contract' THEN
  IF NEW.preparation_contract_code IS NULL THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='contract_revision' THEN
  IF NEW.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' THEN RETURN NEW; END IF;
 END IF;
 NEW.created_at=clock_timestamp();
 IF TG_TABLE_NAME='approval_policy' THEN
  PERFORM 1 FROM identity.organization_unit WHERE tenant_id=NEW.tenant_id AND organization_unit_id=NEW.organization_unit_id FOR UPDATE;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='approval_policy_member' THEN
  IF NOT EXISTS(SELECT 1 FROM contract.approval_policy p WHERE p.tenant_id=NEW.tenant_id AND p.approval_policy_id=NEW.policy_id AND p.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'contract approval policy sealed' USING ERRCODE='23514'; END IF;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME IN ('contract_revision','contract') THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='preparation_workflow' THEN
  PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'contract workflow opportunity differs' USING ERRCODE='23514'; END IF;
  SELECT preparation_workflow_id INTO prior FROM contract.preparation_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.preparation_workflow_id);
  IF prior IS DISTINCT FROM NEW.previous_workflow_id OR (NEW.contract_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.contract c WHERE c.tenant_id=NEW.tenant_id AND c.contract_id=NEW.contract_id AND c.opportunity_id=NEW.opportunity_id)) THEN RAISE EXCEPTION 'contract workflow predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.task_id AND task.owner_appointment_id=NEW.owner_appointment_id AND ((task.subject_type='opportunity.opportunity' AND task.subject_id=NEW.opportunity_id) OR (task.subject_type='contract.contract' AND task.subject_id=NEW.contract_id))) THEN RAISE EXCEPTION 'contract workflow task differs' USING ERRCODE='23514'; END IF;
  IF NEW.prior_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND ((task.subject_type='opportunity.opportunity' AND task.subject_id=NEW.opportunity_id) OR (task.subject_type='contract.contract' AND task.subject_id=NEW.contract_id))) THEN RAISE EXCEPTION 'contract workflow prior task differs' USING ERRCODE='23514'; END IF;
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND task.state='CANCELLED' AND task.cancellation_reason_code='CONTRACT_AUTHORITY_MISSING' AND ((NEW.recovery_resume_stage='DIRECT_RETURNED' AND task.business_purpose_code='REQUEST_CONTRACT_PREPARATION') OR (NEW.recovery_resume_stage='PREPARE' AND task.business_purpose_code='PREPARE_CONTRACT') OR (NEW.recovery_resume_stage='RETURNED' AND task.business_purpose_code='PREPARE_CONTRACT') OR (NEW.recovery_resume_stage='REVIEW_BLOCKED' AND task.business_purpose_code='PREPARE_CONTRACT') OR (NEW.recovery_resume_stage='SUBMIT_REVIEW' AND task.business_purpose_code='SUBMIT_CONTRACT_REVIEW') OR (NEW.recovery_resume_stage='SUBMIT_APPROVAL' AND task.business_purpose_code='SUBMIT_CONTRACT_APPROVAL') OR (NEW.recovery_resume_stage='REVIEW_SUPPLEMENT' AND task.business_purpose_code='SUPPLEMENT_CONTRACT_REVIEW') OR (NEW.recovery_resume_stage='DIRECT_RETURNED' AND task.business_purpose_code='DECIDE_CONTRACT_PREPARATION') OR (NEW.recovery_resume_stage='RETURNED' AND task.business_purpose_code IN ('REVIEW_CONTRACT','APPROVE_CONTRACT')))) THEN RAISE EXCEPTION 'contract authority recovery basis differs' USING ERRCODE='23514'; END IF;
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow p WHERE p.tenant_id=NEW.tenant_id AND p.preparation_workflow_id=NEW.previous_workflow_id AND ((p.task_id=NEW.prior_task_id AND (p.stage_code=NEW.recovery_resume_stage OR (p.stage_code='DIRECT_REVIEW' AND NEW.recovery_resume_stage='DIRECT_RETURNED') OR (p.stage_code IN ('AWAIT_REVIEW','AWAIT_APPROVAL') AND NEW.recovery_resume_stage='RETURNED'))) OR (p.stage_code='OWNER_EXCEPTION' AND p.recovery_resume_stage=NEW.recovery_resume_stage AND p.prior_task_id=NEW.prior_task_id))) THEN RAISE EXCEPTION 'contract authority recovery predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.stage_code IN ('READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE') AND NOT EXISTS(SELECT 1 FROM contract.contract c JOIN contract.signature_readiness sr ON sr.tenant_id=c.tenant_id AND sr.contract_revision_id=c.current_revision_id WHERE c.tenant_id=NEW.tenant_id AND c.contract_id=NEW.contract_id) THEN RAISE EXCEPTION 'contract workflow readiness missing' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME IN ('template_version','clause_version') THEN
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.evidence_version_id,NEW.body_sha256,NULL);RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='preparation_draft' THEN
  PERFORM contract.fn_assert_r2_source(NEW.tenant_id,NEW.opportunity_id,NEW.source_quote_response_id,NEW.source_direct_decision_id,NEW.customer_confirmation_id,NEW.commercial_digest);
  IF NOT EXISTS(SELECT 1 FROM opportunity.opportunity o WHERE o.tenant_id=NEW.tenant_id AND o.opportunity_id=NEW.opportunity_id AND o.revision=NEW.opportunity_revision AND ((NEW.responsibility_type='opportunity.opportunity' AND NEW.responsibility_id=o.opportunity_id AND NEW.responsibility_revision=o.revision AND NEW.owner_appointment_id=o.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=o.tenant_id AND h.opportunity_id=o.opportunity_id)) OR (NEW.responsibility_type='opportunity.responsibility_handoff' AND EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=o.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.opportunity_id=o.opportunity_id AND h.revision=NEW.responsibility_revision AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id))))) THEN RAISE EXCEPTION 'contract draft responsibility changed' USING ERRCODE='23514'; END IF;
  SELECT preparation_draft_id INTO prior FROM contract.preparation_draft d WHERE d.tenant_id=NEW.tenant_id AND d.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_draft n WHERE n.tenant_id=d.tenant_id AND n.previous_draft_id=d.preparation_draft_id);
  IF prior IS DISTINCT FROM NEW.previous_draft_id THEN RAISE EXCEPTION 'contract draft predecessor differs' USING ERRCODE='23514'; END IF;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='revision_review_decision' THEN
  SELECT * INTO req FROM contract.revision_review_request WHERE tenant_id=NEW.tenant_id AND revision_review_request_id=NEW.request_id;v=req.contract_revision_id;
 ELSIF TG_TABLE_NAME='revision_approval_decision' THEN
  SELECT * INTO requirement FROM contract.revision_approval_requirement WHERE tenant_id=NEW.tenant_id AND revision_approval_requirement_id=NEW.requirement_id;v=requirement.contract_revision_id;
 ELSE v=NEW.contract_revision_id;
 END IF;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=v;
 SELECT * INTO root FROM contract.contract WHERE tenant_id=NEW.tenant_id AND contract_id=r.contract_id FOR UPDATE;
 IF r.contract_revision_id IS NULL OR r.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' OR root.contract_termination_id IS NOT NULL OR root.contract_execution_id IS NOT NULL THEN RAISE EXCEPTION 'contract version unavailable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME IN ('revision_clause','revision_approval_requirement') THEN
  IF r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract version collection sealed' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='revision_approval_requirement' THEN
   IF NOT EXISTS(SELECT 1 FROM contract.approval_policy p JOIN contract.approval_policy_member m ON m.tenant_id=p.tenant_id AND m.policy_id=p.approval_policy_id WHERE p.tenant_id=NEW.tenant_id AND p.approval_policy_id=NEW.policy_id AND p.policy_digest=NEW.policy_digest AND m.requirement_code=NEW.requirement_code AND m.appointment_id=NEW.approver_appointment_id AND NOT EXISTS(SELECT 1 FROM contract.approval_policy n WHERE n.tenant_id=p.tenant_id AND n.organization_unit_id=p.organization_unit_id AND n.policy_version>p.policy_version)) THEN RAISE EXCEPTION 'contract approval policy differs' USING ERRCODE='23514'; END IF;
  END IF;RETURN NEW;
 END IF;
 IF root.current_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract version is not current' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='revision_review_request' THEN
  SELECT revision_review_request_id INTO prior FROM contract.revision_review_request x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_review_request n WHERE n.tenant_id=x.tenant_id AND n.previous_request_id=x.revision_review_request_id);
  IF prior IS DISTINCT FROM NEW.previous_request_id OR (prior IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.revision_review_decision d WHERE d.tenant_id=NEW.tenant_id AND d.request_id=prior AND d.decision_code='NEED_INFO')) OR EXISTS(SELECT 1 FROM contract.revision_review_binding x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=v) THEN RAISE EXCEPTION 'contract review request predecessor differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revision_review_decision' THEN
  IF EXISTS(SELECT 1 FROM contract.revision_review_request n WHERE n.tenant_id=NEW.tenant_id AND n.previous_request_id=NEW.request_id) THEN RAISE EXCEPTION 'contract review request superseded' USING ERRCODE='23514'; END IF;
  SELECT * INTO review FROM conflict.conflict_review WHERE tenant_id=NEW.tenant_id AND conflict_review_id=NEW.conflict_review_id FOR UPDATE;
  IF review.conflict_review_id IS NULL OR review.review_type_code<>'PRE_CONTRACT' OR review.trigger_fact_type<>'contract.contract_revision' OR review.trigger_fact_id<>v OR review.trigger_fact_hash IS DISTINCT FROM r.content_digest OR review.trigger_fact_revision IS NOT NULL OR review.scope_hash=decode(repeat('00',32),'hex') OR review.rule_set_hash=decode(repeat('00',32),'hex') OR review.corpus_hash=decode(repeat('00',32),'hex') OR review.scope_hash<>req.scope_hash OR NEW.scope_hash<>req.scope_hash OR (CASE WHEN review.initial_conclusion_code='CLEAR' THEN 'CLEAR' WHEN review.initial_conclusion_code='NEED_INFO' THEN 'NEED_INFO' ELSE review.resolution_code END) IS DISTINCT FROM NEW.decision_code OR (NEW.decision_code='WAIVED' AND NEW.resolution_digest IS DISTINCT FROM review.resolution_digest) OR (NEW.decision_code='CLEAR' AND NEW.resolution_digest IS DISTINCT FROM sha256(convert_to('R2_CONTRACT_REVIEW_CLEAR_V1|'||review.conflict_review_id::text||'|'||encode(review.scope_hash,'hex')||'|'||encode(review.rule_set_hash,'hex')||'|'||encode(review.corpus_hash,'hex')||'|'||encode(r.content_digest,'hex'),'UTF8'))) THEN RAISE EXCEPTION 'contract exact review decision differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revision_review_binding' THEN
  SELECT * INTO dec FROM contract.revision_review_decision WHERE tenant_id=NEW.tenant_id AND revision_review_decision_id=NEW.review_decision_id;
  SELECT * INTO req FROM contract.revision_review_request WHERE tenant_id=NEW.tenant_id AND revision_review_request_id=dec.request_id;
  IF req.contract_revision_id IS DISTINCT FROM v OR dec.decision_code NOT IN ('CLEAR','WAIVED') OR dec.conflict_review_id IS DISTINCT FROM NEW.conflict_review_id OR dec.scope_hash IS DISTINCT FROM NEW.scope_hash OR dec.resolution_digest IS DISTINCT FROM NEW.resolution_digest THEN RAISE EXCEPTION 'contract passing review binding differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME IN ('revision_approval_request','revision_approval_decision','signature_readiness') THEN
  SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=NEW.tenant_id AND revision_review_binding_id=NEW.review_binding_id;
  IF b.contract_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract approval review differs' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='revision_approval_decision' THEN
   SELECT * INTO ar FROM contract.revision_approval_request WHERE tenant_id=NEW.tenant_id AND revision_approval_request_id=NEW.approval_request_id;
   IF requirement.approver_appointment_id IS DISTINCT FROM NEW.decided_by_appointment_id OR ar.contract_revision_id IS DISTINCT FROM v OR ar.review_binding_id IS DISTINCT FROM NEW.review_binding_id THEN RAISE EXCEPTION 'contract approval requirement differs' USING ERRCODE='23514'; END IF;
  ELSIF TG_TABLE_NAME='signature_readiness' THEN
   PERFORM contract.fn_assert_r2_approval(NEW.tenant_id,root.contract_id,v);
   IF root.approved_revision_id IS DISTINCT FROM v THEN RAISE EXCEPTION 'contract readiness requires current approval pointer' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_check_r2_fact() FROM PUBLIC;
DO $v1060$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v20',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v19';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1060 requires 52-plus-2-r2-v19' USING ERRCODE='55000'; END IF;
END;$v1060$;
