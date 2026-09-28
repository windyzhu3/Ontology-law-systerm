"""R25 exact sales preparation recovery; preserve stage, task purpose and original SLA."""
from dataclasses import replace
from ..model import ContractEvolution
from ..render import _constraint_sql
from .v980_r2_contract_versions import GUARDS

STAGE_TASK = {
    'DIRECT_RETURNED': 'REQUEST_CONTRACT_PREPARATION',
    'PREPARE': 'PREPARE_CONTRACT',
    'RETURNED': 'PREPARE_CONTRACT',
    'REVIEW_BLOCKED': 'PREPARE_CONTRACT',
    'SUBMIT_REVIEW': 'SUBMIT_CONTRACT_REVIEW',
    'SUBMIT_APPROVAL': 'SUBMIT_CONTRACT_APPROVAL',
    'REVIEW_SUPPLEMENT': 'SUPPLEMENT_CONTRACT_REVIEW',
}
RESUME = "recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN (" + ','.join("'"+s+"'" for s in STAGE_TASK) + ") AND task_id IS NULL AND prior_task_id IS NOT NULL)"

def apply_evolution(schemas):
    def table(t):
        if t.schema!='contract' or t.name!='preparation_workflow':return t
        return replace(t,
            columns=tuple(replace(c,comment='失权时保留的准确恢复阶段；原任务类型和期限不变。') if c.name=='recovery_resume_stage' else c for c in t.columns),
            constraints=tuple(replace(c,expression=RESUME) if c.name=='ck_preparation_workflow__recovery_resume' else c for c in t.constraints))
    return tuple(replace(s,tables=tuple(table(t) for t in s.tables)) for s in schemas)

def render_sql(before,after):
    t=next(t for s in after for t in s.tables if t.schema=='contract' and t.name=='preparation_workflow')
    constraint=next(c for c in t.constraints if c.name=='ck_preparation_workflow__recovery_resume')
    sql='ALTER TABLE contract.preparation_workflow DROP CONSTRAINT ck_preparation_workflow__recovery_resume;\n'
    sql+='ALTER TABLE contract.preparation_workflow ADD '+_constraint_sql(constraint)+';\n'
    sql+="COMMENT ON COLUMN contract.preparation_workflow.recovery_resume_stage IS '失权时保留的准确恢复阶段；原任务类型和期限不变。';\n"
    sql+="COMMENT ON CONSTRAINT ck_preparation_workflow__recovery_resume ON contract.preparation_workflow IS '仅无可办理任务的负责人异常保留恢复目标；不伪造退回决定。';\n"
    # Re-emit the existing exact-fact guard with only the two recovery predicates
    # extended. Historical migration bytes and all other source checks are retained.
    guard='CREATE OR REPLACE FUNCTION contract.fn_check_r2_fact()'+GUARDS.split('CREATE FUNCTION contract.fn_check_r2_fact()',1)[1].split('COMMENT ON FUNCTION contract.fn_check_r2_fact()',1)[0]
    sales=' OR '.join("(NEW.recovery_resume_stage='"+stage+"' AND task.business_purpose_code='"+purpose+"')" for stage,purpose in STAGE_TASK.items())
    for message,replacement in (
        ('contract authority recovery basis differs',
         "  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND task.state='CANCELLED' AND task.cancellation_reason_code='CONTRACT_AUTHORITY_MISSING' AND ("+sales+" OR (NEW.recovery_resume_stage='DIRECT_RETURNED' AND task.business_purpose_code='DECIDE_CONTRACT_PREPARATION') OR (NEW.recovery_resume_stage='RETURNED' AND task.business_purpose_code IN ('REVIEW_CONTRACT','APPROVE_CONTRACT')))) THEN RAISE EXCEPTION 'contract authority recovery basis differs' USING ERRCODE='23514'; END IF;"),
        ('contract authority recovery predecessor differs',
         "  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow p WHERE p.tenant_id=NEW.tenant_id AND p.preparation_workflow_id=NEW.previous_workflow_id AND ((p.task_id=NEW.prior_task_id AND (p.stage_code=NEW.recovery_resume_stage OR (p.stage_code='DIRECT_REVIEW' AND NEW.recovery_resume_stage='DIRECT_RETURNED') OR (p.stage_code IN ('AWAIT_REVIEW','AWAIT_APPROVAL') AND NEW.recovery_resume_stage='RETURNED'))) OR (p.stage_code='OWNER_EXCEPTION' AND p.recovery_resume_stage=NEW.recovery_resume_stage AND p.prior_task_id=NEW.prior_task_id))) THEN RAISE EXCEPTION 'contract authority recovery predecessor differs' USING ERRCODE='23514'; END IF;")
    ):
        lines=guard.splitlines();matches=[i for i,line in enumerate(lines) if "RAISE EXCEPTION '"+message+"'" in line]
        assert len(matches)==1
        lines[matches[0]]=replacement;guard='\n'.join(lines)+'\n'
    sql+=guard+"REVOKE ALL ON FUNCTION contract.fn_check_r2_fact() FROM PUBLIC;\n"
    sql+="""DO $v1060$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v20',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v19';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1060 requires 52-plus-2-r2-v19' USING ERRCODE='55000'; END IF;
END;$v1060$;
"""
    return sql

EVOLUTION=ContractEvolution(version=1060,migration_name='V1060__r25_contract_responsibility_recovery.sql',contract_version='52-plus-2-r2-v20',apply=apply_evolution,render_sql=render_sql)
