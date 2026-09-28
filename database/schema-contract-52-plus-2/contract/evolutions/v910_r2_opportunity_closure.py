"""T04 explicit Opportunity closure; DecisionRecord retains its Task-only meaning."""
from dataclasses import replace
from ..helpers import *
from ..model import Column, ContractEvolution
from ..render import _render_table, _render_foreign_key

def ck(name,expression): return check('ck_closure__'+name,expression,'商机终结具名一致性。')
def ef(column,schema,table): return entity_fk('closure',column,schema,table,table+'_id','同租户准确来源。',suffix=column)

CLOSURE=tenant_table('opportunity','closure','closure_id','不可变商机终结事实；无待办也保留明确依据；说明加密且仅授权后解密。',(
    revision_col(),uuid_col('opportunity_id','终结的准确商机。'),bigint_col('opportunity_revision','终结前准确商机版本。'),
    code_col('responsibility_type','有效责任依据类型。'),uuid_col('responsibility_id','有效责任依据身份。'),bigint_col('responsibility_revision','有效责任依据版本。'),
    uuid_col('task_occurrence_id','实际取消的当前普通任务；未建卡时为空。',nullable=True),bigint_col('task_revision','取消前任务版本。',nullable=True),
    uuid_col('wait_receipt_id','取消前准确等待事实。',nullable=True),digest_col('wait_hash','取消前准确等待摘要。',nullable=True),
    uuid_col('closed_by_appointment_id','实际终结操作者。'),code_col('reason_code','明确终结原因。'),
    Column('closure_summary_ciphertext','bytea',False,'受保护简短终结说明；独立AAD绑定租户商机终结事实。'),
    digest_col('summary_digest','规范说明摘要，只用于完整性复验。'),time_col('closed_at','数据库终结时间。'),
),constraints=(
    unique('uq_closure__opportunity',('tenant_id','opportunity_id'),'每个商机至多一个终结事实。'),
    ck('revision','revision=0'),ck('opportunity_revision','opportunity_revision BETWEEN 0 AND 9007199254740990'),
    ck('responsibility_revision','responsibility_revision BETWEEN 0 AND 9007199254740991'),
    ck('responsibility_type',"responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')"),
    ck('reason',"reason_code IN ('CLIENT_DECLINED','NEED_CANCELLED','OTHER')"),
    ck('task','(task_occurrence_id IS NULL) = (task_revision IS NULL) AND (task_revision IS NULL OR task_revision BETWEEN 0 AND 9007199254740990)'),
    ck('wait','(wait_receipt_id IS NULL) = (wait_hash IS NULL) AND (wait_receipt_id IS NULL OR task_occurrence_id IS NOT NULL)'),
    ck('protected_body','octet_length(closure_summary_ciphertext) BETWEEN 29 AND 16384'),
),foreign_keys=(ef('opportunity_id','opportunity','opportunity'),ef('task_occurrence_id','responsibility','task_occurrence'),ef('wait_receipt_id','responsibility','wait_receipt'),ef('closed_by_appointment_id','identity','appointment')))

CANCEL="cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure')))"
def apply_evolution(schemas):
    return tuple(replace(s,tables=(*s.tables,CLOSURE)) if s.name=='opportunity' else replace(s,tables=tuple(replace(t,constraints=tuple(replace(c,expression=CANCEL) if c.name=='ck_task_occurrence__handoff_cancellation' else c for c in t.constraints)) if t.name=='task_occurrence' else t for t in s.tables)) if s.name=='responsibility' else s for s in schemas)

def render_sql(before,after):
    return _render_table(CLOSURE)+'\n'+'\n'.join(_render_foreign_key(CLOSURE,f) for f in CLOSURE.foreign_keys)+r'''
CREATE TRIGGER trg_closure__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.closure
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_closure__mutation_guard ON opportunity.closure IS '终结事实禁止修改或删除。';
REVOKE ALL ON opportunity.closure FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.closure TO ${app_command_role};
GRANT SELECT ON opportunity.closure TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_cancellation;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK ('''+CANCEL+r''');

CREATE FUNCTION opportunity.fn_check_closure() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; t responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id;
 IF o.revision IS DISTINCT FROM NEW.opportunity_revision+1 OR o.closed_at IS DISTINCT FROM NEW.closed_at OR o.close_outcome_code IS DISTINCT FROM NEW.reason_code THEN RAISE EXCEPTION 'closure root differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>NEW.opportunity_id OR NEW.responsibility_revision<>NEW.opportunity_revision OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'closure handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure has downstream facts' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND subject_type='opportunity.opportunity' AND subject_id=NEW.opportunity_id AND business_purpose_code='PROGRESS_OPPORTUNITY' AND state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'closure retains active task' USING ERRCODE='23514'; END IF;
 IF NEW.task_occurrence_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_occurrence_id;
  IF t.state IS DISTINCT FROM 'CANCELLED' OR t.revision IS DISTINCT FROM NEW.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'opportunity.closure' OR t.cancellation_fact_id IS DISTINCT FROM NEW.closure_id OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.cancelled_at IS DISTINCT FROM NEW.closed_at THEN RAISE EXCEPTION 'closure cancellation differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_closure() IS '终结事务提交前检查准确商机、责任、取消及后续事实；不扩展查询角色权限。';
REVOKE ALL ON FUNCTION opportunity.fn_check_closure() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_closure__consistency AFTER INSERT ON opportunity.closure DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_closure();
COMMENT ON TRIGGER trg_closure__consistency ON opportunity.closure IS '终结事实与商机关闭和任务取消在同一事务完整提交。';
DO $v910$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v5',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v4';
 IF NOT FOUND THEN RAISE EXCEPTION 'V910 requires 52-plus-2-r2-v4' USING ERRCODE='55000'; END IF;
END;
$v910$;
'''

EVOLUTION=ContractEvolution(version=910,migration_name='V910__r2_opportunity_closure.sql',contract_version='52-plus-2-r2-v5',apply=apply_evolution,render_sql=render_sql)
