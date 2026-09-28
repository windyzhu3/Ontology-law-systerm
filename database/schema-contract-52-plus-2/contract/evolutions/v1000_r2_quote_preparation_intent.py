"""Explicit preparation intent; page reads and drafts never transfer responsibility."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v950_r2_quote_runtime import table, fk, txn, CONSISTENCY_SQL

INTENT=table('quote_preparation_intent',(
    uuid_col('opportunity_id','准确商机。'),bigint_col('opportunity_revision','提交时商机版本。'),
    uuid_col('customer_confirmation_id','人工确认的准确客户资料。'),
    code_col('responsibility_type','准确责任来源类型。'),uuid_col('responsibility_id','准确责任来源。'),bigint_col('responsibility_revision','准确责任版本。'),
    uuid_col('workflow_id','本次报价准备工作流。'),uuid_col('task_id','接管后的报价准备待办。'),
    uuid_col('prior_task_id','被接管的普通跟进待办。',nullable=True),
    uuid_col('requested_by','明确开始准备的任职。'),txn(),time_col('created_at','可信提交时间。')),
    (unique('uq_quote_preparation_intent__opportunity',('tenant_id','opportunity_id'),'首次准备意图唯一。'),
     unique('uq_quote_preparation_intent__workflow',('tenant_id','workflow_id'),'准备工作流唯一来源。'),
     check('ck_quote_preparation_intent__versions',"opportunity_revision>=0 AND responsibility_revision>=0 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')",'准确责任及版本。')),
    tuple(fk('quote_preparation_intent',col,schema,target) for col,schema,target in (
        ('opportunity_id','opportunity','opportunity'),('customer_confirmation_id','opportunity','customer_requirement_confirmation'),
        ('workflow_id','opportunity','quote_workflow'),('task_id','responsibility','task_occurrence'),
        ('prior_task_id','responsibility','task_occurrence'),('requested_by','identity','appointment'))))
INITIAL=check('ck_quote_workflow__initial_preparation',"quote_revision_id IS NOT NULL OR (stage='PREPARE' AND previous_workflow_id IS NULL AND task_id IS NOT NULL AND next_check_at IS NULL)",'正式报价前仅允许具名准备责任。')

def apply_evolution(schemas):
    return tuple(replace(s,tables=tuple(replace(t,columns=tuple(replace(c,nullable=True) if c.name=='quote_revision_id' else c for c in t.columns),constraints=(*t.constraints,INITIAL)) if t.name=='quote_workflow' else t for t in s.tables)+(INTENT,)) if s.name=='opportunity' else s for s in schemas)

def render_sql(before,after):
    sql=_render_table(INTENT)+'\n'+'\n'.join(_render_foreign_key(INTENT,f) for f in INTENT.foreign_keys)+'\n'
    sql+='ALTER TABLE opportunity.quote_workflow ALTER COLUMN quote_revision_id DROP NOT NULL;\n'
    sql+='ALTER TABLE opportunity.quote_workflow ADD '+_constraint_sql(INITIAL)+';\n'
    sql+="COMMENT ON CONSTRAINT ck_quote_workflow__initial_preparation ON opportunity.quote_workflow IS '正式报价前仅允许具名准备责任。';\n"
    # Preserve the frozen runtime guard in full, with one explicitly bounded initial branch.
    end=CONSISTENCY_SQL.index('CREATE TRIGGER trg_quote_approval_policy_signer__source')
    guard=CONSISTENCY_SQL[:end].replace('CREATE FUNCTION opportunity.fn_check_quote_runtime()', 'CREATE OR REPLACE FUNCTION opportunity.fn_check_quote_runtime()')
    guard=guard.replace(" IF TG_TABLE_NAME='quote_approval_policy_signer' THEN", """ IF TG_TABLE_NAME='quote_workflow' THEN
 IF NEW.quote_revision_id IS NULL THEN
  SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
  IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.current_quote_revision_id IS NOT NULL OR EXISTS(SELECT 1 FROM opportunity.quote_workflow WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'initial quote preparation source differs' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 END IF;
 IF TG_TABLE_NAME='quote_approval_policy_signer' THEN""",1)
    sql+=guard+GUARDS
    return sql

GUARDS=r'''
CREATE TRIGGER trg_quote_preparation_intent__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_preparation_intent__mutation_guard ON opportunity.quote_preparation_intent IS '明确准备意图不可改写。';
CREATE TRIGGER trg_quote_preparation_intent__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_quote_preparation_intent__transaction_marker ON opportunity.quote_preparation_intent IS '本次命令顶层事务来源。';
REVOKE ALL ON opportunity.quote_preparation_intent FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.quote_preparation_intent TO ${app_command_role};
GRANT SELECT ON opportunity.quote_preparation_intent TO ${app_query_role};
CREATE FUNCTION opportunity.fn_check_preparation_intent_source() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision<>NEW.opportunity_revision OR o.current_quote_revision_id IS NOT NULL THEN RAISE EXCEPTION 'preparation opportunity differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>o.opportunity_id OR NEW.responsibility_revision<>o.revision OR NEW.requested_by<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=o.opportunity_id) THEN RAISE EXCEPTION 'preparation responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=o.opportunity_id AND h.to_appointment_id=NEW.requested_by AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'preparation responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'preparation customer confirmation differs' USING ERRCODE='23514'; END IF;
 IF NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'preparation trusted time differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_preparation_intent_source() IS '锁定商机后验证准确版本、现任责任和本次客户确认。';
REVOKE ALL ON FUNCTION opportunity.fn_check_preparation_intent_source() FROM PUBLIC;
CREATE TRIGGER trg_quote_preparation_intent__current BEFORE INSERT ON opportunity.quote_preparation_intent FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent_source();
COMMENT ON TRIGGER trg_quote_preparation_intent__current ON opportunity.quote_preparation_intent IS '禁止旧资料及失效责任发起准备。';
CREATE FUNCTION opportunity.fn_check_preparation_intent() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE w opportunity.quote_workflow%ROWTYPE; i opportunity.quote_preparation_intent%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='quote_workflow' THEN
  IF NEW.quote_revision_id IS NOT NULL THEN RETURN NEW; END IF;
  w=NEW;
  SELECT * INTO i FROM opportunity.quote_preparation_intent WHERE tenant_id=w.tenant_id AND workflow_id=w.quote_workflow_id;
 ELSE
  i=NEW;
  SELECT * INTO w FROM opportunity.quote_workflow WHERE tenant_id=i.tenant_id AND quote_workflow_id=i.workflow_id;
 END IF;
 IF i.quote_preparation_intent_id IS NULL OR w.quote_workflow_id IS NULL OR i.created_in_transaction<>pg_current_xact_id() OR w.quote_revision_id IS NOT NULL OR w.stage<>'PREPARE' OR w.opportunity_id<>i.opportunity_id OR w.task_id<>i.task_id OR w.owner_appointment_id<>i.requested_by OR w.prior_task_id IS DISTINCT FROM i.prior_task_id OR w.created_at<>i.created_at THEN RAISE EXCEPTION 'exact preparation intent required' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=i.tenant_id AND c.customer_requirement_confirmation_id=i.customer_confirmation_id AND c.opportunity_id=i.opportunity_id) OR NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=i.tenant_id AND t.task_occurrence_id=i.task_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=i.opportunity_id AND t.subject_revision=i.opportunity_revision AND t.owner_appointment_id=i.requested_by AND t.business_purpose_code='PREPARE_QUOTE') THEN RAISE EXCEPTION 'preparation customer or task differs' USING ERRCODE='23514'; END IF;
 IF i.prior_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=i.tenant_id AND t.task_occurrence_id=i.prior_task_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=i.opportunity_id AND t.business_purpose_code='PROGRESS_OPPORTUNITY' AND t.state='CANCELLED' AND t.cancellation_reason_code='QUOTE_WORKFLOW_TAKEOVER') THEN RAISE EXCEPTION 'preparation prior task not handed off' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_preparation_intent() IS '准备事实和任务接管必须同事务形成；不生成报价。';
REVOKE ALL ON FUNCTION opportunity.fn_check_preparation_intent() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_preparation_intent__source AFTER INSERT ON opportunity.quote_preparation_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent();
COMMENT ON TRIGGER trg_quote_preparation_intent__source ON opportunity.quote_preparation_intent IS '准确准备来源提交前校验。';
CREATE CONSTRAINT TRIGGER trg_quote_workflow__preparation_intent AFTER INSERT ON opportunity.quote_workflow DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_preparation_intent();
COMMENT ON TRIGGER trg_quote_workflow__preparation_intent ON opportunity.quote_workflow IS '正式报价前工作流必须有本次明确意图。';
DO $v1000$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v14',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v13';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1000 requires 52-plus-2-r2-v13' USING ERRCODE='55000'; END IF;
END;
$v1000$;
'''
EVOLUTION=ContractEvolution(version=1000,migration_name='V1000__r2_quote_preparation_intent.sql',contract_version='52-plus-2-r2-v14',apply=apply_evolution,render_sql=render_sql)
