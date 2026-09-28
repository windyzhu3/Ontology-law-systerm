"""Named R2 manual execution branch for V1040; legacy execution guards stay intact."""
from dataclasses import replace
from ..helpers import uuid_col, check
from ..render import _constraint_sql, _render_foreign_key, _render_update_guards
from .v920_r2_customer_requirements import fk

def extend(schemas):
 result=[]
 for schema in schemas:
  tables=[]
  for table in schema.tables:
   if schema.name=='contract' and table.name=='contract_execution':
    table=replace(table,columns=tuple(replace(c,nullable=True) if c.name=='archive_evidence_submission_id' else c for c in table.columns)+(uuid_col('execution_verification_id','R2准确归档后的人工执行条件核验；与旧归档证据互斥。',nullable=True),),constraints=(*table.constraints,check('ck_contract_execution__manual',"num_nonnulls(archive_evidence_submission_id,execution_verification_id)=1",'保留完整旧执行依据；具名R2执行必须引用人工核验。')),foreign_keys=(*table.foreign_keys,fk('contract_execution','execution_verification_id','contract','execution_verification')))
   tables.append(table)
  result.append(replace(schema,tables=tuple(tables)))
 return tuple(result)

def render(before,after):
 table=next(t for s in after for t in s.tables if s.name=='contract' and t.name=='contract_execution')
 sql="ALTER TABLE contract.contract_execution ALTER COLUMN archive_evidence_submission_id DROP NOT NULL;\nALTER TABLE contract.contract_execution ADD COLUMN execution_verification_id uuid;\nCOMMENT ON COLUMN contract.contract_execution.execution_verification_id IS 'R2准确归档后的人工执行条件核验；与旧归档证据互斥。';\n"
 sql+='ALTER TABLE contract.contract_execution ADD '+_constraint_sql(table.constraints[-1])+';\n'
 sql+="COMMENT ON CONSTRAINT ck_contract_execution__manual ON contract.contract_execution IS '保留完整旧执行依据；具名R2执行必须引用人工核验。';\n"
 sql+=_render_foreign_key(table,table.foreign_keys[-1])+'\n'+GUARD
 base=_render_update_guards(before)
 start=base.index('CREATE FUNCTION platform_meta.fn_assert_contract_execution_package()')
 end=base.index('COMMENT ON FUNCTION platform_meta.fn_assert_contract_execution_package()',start)
 legacy=base[start:end].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION',1)
 legacy=legacy.replace('    IF EXISTS (\n',"    IF NEW.execution_verification_id IS NOT NULL THEN\n        PERFORM contract.fn_assert_manual_execution(NEW);\n        RETURN NEW;\n    END IF;\n    IF EXISTS (\n",1)
 return sql+legacy

GUARD=r'''
CREATE FUNCTION contract.fn_assert_manual_execution(e contract.contract_execution) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE v contract.execution_verification%ROWTYPE; h contract.signature_handoff%ROWTYPE; r contract.contract_revision%ROWTYPE; b contract.revision_review_binding%ROWTYPE;
BEGIN
 SELECT * INTO v FROM contract.execution_verification WHERE tenant_id=e.tenant_id AND execution_verification_id=e.execution_verification_id;
 SELECT * INTO h FROM contract.signature_handoff WHERE tenant_id=e.tenant_id AND signature_handoff_id=v.handoff_id;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=e.tenant_id AND contract_revision_id=e.contract_revision_id;
 SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=e.tenant_id AND contract_revision_id=e.contract_revision_id;
 IF v.execution_verification_id IS NULL OR v.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR v.contract_revision_id IS DISTINCT FROM e.contract_revision_id OR v.recorded_by IS DISTINCT FROM e.executed_by_appointment_id OR h.signature_handoff_id IS NULL OR r.contract_id IS DISTINCT FROM e.contract_id OR r.package_contract_code IS DISTINCT FROM 'R2_CONTRACT_PREPARATION_V1' THEN RAISE EXCEPTION 'exact current human execution verification required' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_require_negotiation_active(e.tenant_id,v.opportunity_id);
 PERFORM contract.fn_assert_r2_approval(e.tenant_id,e.contract_id,e.contract_revision_id);
 IF b.revision_review_binding_id IS NULL OR e.review_scope_hash IS DISTINCT FROM b.scope_hash OR e.review_resolution_digest IS DISTINCT FROM b.resolution_digest THEN RAISE EXCEPTION 'execution review differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM contract.signature_archive a JOIN contract.signature_readiness s ON s.tenant_id=a.tenant_id AND s.signature_readiness_id=h.readiness_id WHERE a.tenant_id=e.tenant_id AND a.signature_archive_id=h.archive_id AND a.arrangement_id=h.arrangement_id AND s.contract_revision_id=e.contract_revision_id)
 OR NOT EXISTS(SELECT 1 FROM contract.signature_workflow w WHERE w.tenant_id=e.tenant_id AND w.readiness_id=h.readiness_id AND w.arrangement_id=h.arrangement_id AND w.stage_code='SIGNATURE_COMPLETE' AND NOT EXISTS(SELECT 1 FROM contract.signature_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.signature_workflow_id))
 OR NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=e.tenant_id AND p.arrangement_id=h.arrangement_id AND p.required)
 OR EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=e.tenant_id AND p.arrangement_id=h.arrangement_id AND p.required AND NOT EXISTS(SELECT 1 FROM contract.contract_signature s JOIN contract.signature_verification x ON x.tenant_id=s.tenant_id AND x.signature_verification_id=s.verification_id WHERE s.tenant_id=p.tenant_id AND s.signature_plan_id=p.signature_plan_id AND s.contract_revision_id=e.contract_revision_id AND s.revoked_at IS NULL AND s.signed_content_digest=r.body_sha256 AND x.decision_code='VERIFIED')) THEN RAISE EXCEPTION 'exact archived signature package required' USING ERRCODE='23514'; END IF;
 IF r.receipt_required_before_transfer AND EXISTS(SELECT 1 FROM contract.payment_confirmation p WHERE p.tenant_id=e.tenant_id AND p.contract_id=e.contract_id AND p.contract_revision_id=e.contract_revision_id AND (p.confirmation_type<>'RECEIPT' OR p.currency_code<>'CNY')) THEN RAISE EXCEPTION 'receipt disposition outside R2 execution scope' USING ERRCODE='23514'; END IF;
 IF r.receipt_required_before_transfer AND COALESCE((SELECT sum(p.amount_minor) FROM contract.payment_confirmation p WHERE p.tenant_id=e.tenant_id AND p.contract_id=e.contract_id AND p.contract_revision_id=e.contract_revision_id AND p.confirmation_type='RECEIPT' AND p.currency_code='CNY'),0)<r.required_amount_minor THEN RAISE EXCEPTION 'required contract receipt not satisfied' USING ERRCODE='23514'; END IF;
END;
$fn$;
REVOKE ALL ON FUNCTION contract.fn_assert_manual_execution(contract.contract_execution) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_assert_manual_execution(contract.contract_execution) TO ${app_command_role};
CREATE OR REPLACE FUNCTION contract.fn_reject_r2_preparation_execution() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF EXISTS(SELECT 1 FROM contract.contract_revision r WHERE r.tenant_id=NEW.tenant_id AND r.contract_revision_id=NEW.contract_revision_id AND r.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN
  IF NEW.execution_verification_id IS NULL THEN RAISE EXCEPTION 'R2 preparation requires exact manual execution verification' USING ERRCODE='55000'; END IF;
  PERFORM contract.fn_assert_manual_execution(NEW);
 ELSIF NEW.execution_verification_id IS NOT NULL THEN RAISE EXCEPTION 'manual execution requires R2 approved version' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_reject_r2_preparation_execution() IS 'V1040具名人工执行：准确批准、归档、同事务人工核验及约定首款；旧执行包守卫保留。';
'''
