"""T07 exact runtime facts; original quote identities and guards remain authoritative."""
from dataclasses import replace
from ..helpers import *
from ..model import Column, ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v920_r2_customer_requirements import fk, checks

def txn():
    return Column('created_in_transaction','xid8',False,'冻结配置或审批成员集合的形成事务。',default='pg_current_xact_id()')

def table(name, columns, constraints=(), foreign_keys=()):
    return tenant_table('opportunity',name,name+'_id','报价闭环不可变准确事实；不构成合同签署。',
        (revision_col(),*columns),constraints=(*checks(name),*constraints),foreign_keys=foreign_keys)

POLICY=table('quote_approval_policy',(
    uuid_col('organization_unit_id','明确适用组织。'),code_col('policy_code','具名审批策略。'),
    bigint_col('policy_version','策略版本。'),code_col('mode','审批方式。'),txn(),time_col('created_at','配置时间。')),
    (check('ck_quote_approval_policy__values',"policy_code='R2_QUOTE_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode IN ('REQUIRE_APPROVAL','SELF_AUTHORIZED')",'明确策略及授权模式。'),
     unique('uq_quote_approval_policy__version',('tenant_id','organization_unit_id','policy_code','policy_version'),'同组织策略版本唯一。')),
    (fk('quote_approval_policy','organization_unit_id','identity','organization_unit'),))
SIGNER=table('quote_approval_policy_signer',(
    uuid_col('policy_id','准确配置策略。'),uuid_col('appointment_id','明确获授权的审批或权限内任职。')),
    (unique('uq_quote_approval_policy_signer__member',('tenant_id','policy_id','appointment_id'),'配置成员不重复。'),),
    (fk('quote_approval_policy_signer','policy_id','opportunity','quote_approval_policy'),fk('quote_approval_policy_signer','appointment_id','identity','appointment')))
REQUEST=table('quote_approval_request',(
    uuid_col('quote_revision_id','准确报价。'),uuid_col('policy_id','准确策略版本。'),code_col('policy_code','策略代码。'),
    bigint_col('policy_version','策略版本。'),uuid_col('requested_by','提交任职。'),txn(),time_col('created_at','提交时间。')),
    (unique('uq_quote_approval_request__quote',('tenant_id','quote_revision_id'),'准确报价仅一次审批请求。'),),
    (fk('quote_approval_request','quote_revision_id','opportunity','quote_revision'),fk('quote_approval_request','policy_id','opportunity','quote_approval_policy'),fk('quote_approval_request','requested_by','identity','appointment')))
MEMBER=table('quote_approval_member',(
    uuid_col('request_id','准确审批请求。'),uuid_col('appointment_id','明确审批人。'),uuid_col('task_id','准确审批待办。')),
    (unique('uq_quote_approval_member__appointment',('tenant_id','request_id','appointment_id'),'请求成员唯一。'),unique('uq_quote_approval_member__task',('tenant_id','task_id'),'待办只属于一个成员。')),
    (fk('quote_approval_member','request_id','opportunity','quote_approval_request'),fk('quote_approval_member','appointment_id','identity','appointment'),fk('quote_approval_member','task_id','responsibility','task_occurrence')))
DECISION=table('quote_approval_decision',(
    uuid_col('member_id','准确审批成员。'),code_col('decision','批准或退回。'),encrypted_col('reason_ciphertext','审批说明密文。'),
    digest_col('reason_digest','审批说明摘要。'),time_col('created_at','决定时间。')),
    (unique('uq_quote_approval_decision__member',('tenant_id','member_id'),'每个审批成员一次决定。'),
     check('ck_quote_approval_decision__values',"decision IN ('APPROVED','RETURNED') AND octet_length(reason_ciphertext) BETWEEN 29 AND 131072",'决定及有界密文。')),
    (fk('quote_approval_decision','member_id','opportunity','quote_approval_member'),))
DELIVERY=table('quote_manual_delivery',(
    uuid_col('quote_revision_id','准确报价。'),uuid_col('material_version_id','T06准确证据版本。'),
    encrypted_col('recipient_ciphertext','实际接收人受保护内容。'),digest_col('body_digest','交付内容摘要。'),code_col('channel','实际交付方式。'),
    time_col('occurred_at','实际发生时间。'),uuid_col('recorded_by','确认交付任职。'),time_col('created_at','记录时间。')),
    (check('ck_quote_manual_delivery__values',"octet_length(recipient_ciphertext) BETWEEN 29 AND 131072 AND channel ~ '^[A-Z][A-Z0-9_]{0,63}$' AND occurred_at<=created_at",'必要交付信息及时间。'),),
    (fk('quote_manual_delivery','quote_revision_id','opportunity','quote_revision'),fk('quote_manual_delivery','material_version_id','opportunity','material_version'),fk('quote_manual_delivery','recorded_by','identity','appointment')))
RESPONSE=table('quote_response_basis',(
    uuid_col('quote_response_id','既有准确客户回复。'),uuid_col('material_version_id','T06准确证明版本。'),time_col('next_check_at','暂不接受或不明确时下一行动。',nullable=True)),
    (unique('uq_quote_response_basis__response',('tenant_id','quote_response_id'),'回复唯一补充依据。'),check('ck_quote_response_basis__identity','quote_response_basis_id=quote_response_id','与既有回复身份一致。')),
    (fk('quote_response_basis','quote_response_id','opportunity','quote_response'),fk('quote_response_basis','material_version_id','opportunity','material_version')))
SOURCE=table('contract_preparation_source',(
    uuid_col('opportunity_id','准确商机。'),uuid_col('quote_response_id','准确接受回复。'),code_col('source_kind','本阶段只允许报价接受来源。'),time_col('created_at','形成时间。')),
    (unique('uq_contract_preparation_source__response',('tenant_id','quote_response_id'),'接受来源唯一。'),check('ck_contract_preparation_source__kind',"source_kind='ACCEPTED_QUOTE'",'不伪造直接授权来源。')),
    (fk('contract_preparation_source','opportunity_id','opportunity','opportunity'),fk('contract_preparation_source','quote_response_id','opportunity','quote_response')))
WORKFLOW=table('quote_workflow',(
    uuid_col('opportunity_id','准确商机。'),uuid_col('quote_revision_id','准确报价。'),uuid_col('previous_workflow_id','直接前一工作流事实。',nullable=True),
    code_col('stage','有界业务阶段。'),uuid_col('owner_appointment_id','下一责任任职。'),uuid_col('task_id','对应已有待办。',nullable=True),
    uuid_col('prior_task_id','移交的原跟进待办。',nullable=True),time_col('next_check_at','约定下一行动。',nullable=True),time_col('created_at','形成时间。')),
    (unique('uq_quote_workflow__previous',('tenant_id','previous_workflow_id'),'工作流事实不分叉。'),
     check('ck_quote_workflow__stage',"stage IN ('PREPARE','SUBMIT_APPROVAL','AWAIT_APPROVAL','DELIVER','AWAIT_REPLY','FOLLOW_UP','CLARIFY_REPLY','SALES_DISPOSITION','ACCEPTED','RETURNED','OWNER_EXCEPTION')",'报价业务阶段域。')),
    (fk('quote_workflow','opportunity_id','opportunity','opportunity'),fk('quote_workflow','quote_revision_id','opportunity','quote_revision'),fk('quote_workflow','previous_workflow_id','opportunity','quote_workflow'),fk('quote_workflow','owner_appointment_id','identity','appointment'),fk('quote_workflow','task_id','responsibility','task_occurrence'),fk('quote_workflow','prior_task_id','responsibility','task_occurrence')))
TABLES=(POLICY,SIGNER,REQUEST,MEMBER,DECISION,DELIVERY,RESPONSE,SOURCE,WORKFLOW)

QUOTE_HANDOFF_TYPES="('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','DELIVER_QUOTE','RECORD_QUOTE_REPLY')"
def handoff_constraint(c):
    e=c.expression
    if c.name=='ck_task_occurrence__handoff_predecessor':
        e=e.replace("business_purpose_code='PROGRESS_OPPORTUNITY'",'business_purpose_code IN '+QUOTE_HANDOFF_TYPES)
    elif c.name in ('ck_wait_receipt__resume_after_entry','ck_wait_receipt__positive_task_revision'):
        e=e.replace("wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1'","wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1')")
    elif c.name=='ck_wait_receipt__handoff_shape':
        e=e.replace("wait_contract_code<>'R2_OPPORTUNITY_HANDOFF_WAIT_V1'","wait_contract_code NOT IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1')")
        e += " OR (wait_contract_code='R2_QUOTE_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NOT NULL AND awaited_fact_type='opportunity.quote_response' AND awaited_fact_id IS NOT NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL)"
    return replace(c,expression=e)

def apply_evolution(schemas):
    return tuple(replace(s,tables=(*(replace(t,constraints=tuple(handoff_constraint(c) for c in t.constraints)) if t.schema=='responsibility' and t.name in ('task_occurrence','wait_receipt') else t for t in s.tables),*(t for t in TABLES if t.schema==s.name))) for s in schemas)

def render_handoff(before,after):
    old={t.schema+'.'+t.name:t for s in before for t in s.tables}
    lines=[]
    for schema in after:
        for t in schema.tables:
            if t.schema!='responsibility' or t.name not in ('task_occurrence','wait_receipt'): continue
            prior={c.name:c for c in old[t.schema+'.'+t.name].constraints}
            for c in t.constraints:
                if c!=prior[c.name]:
                    lines += [f'ALTER TABLE {t.schema}.{t.name} DROP CONSTRAINT {c.name};', f'ALTER TABLE {t.schema}.{t.name} ADD {_constraint_sql(c)};']
    from .v900_r2_owner_exception import TASK_GUARDS, HANDOFF_GUARDS
    initial=TASK_GUARDS.split('CREATE TRIGGER')[0].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION').replace("NEW.business_purpose_code='PROGRESS_OPPORTUNITY'","NEW.business_purpose_code IN ('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY')")
    guard=HANDOFF_GUARDS[HANDOFF_GUARDS.index('CREATE FUNCTION opportunity.fn_check_responsibility_handoff()'):].split('CREATE CONSTRAINT TRIGGER')[0].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION')
    guard=guard.replace("w.wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1'", "w.wait_contract_code IN ('R2_OPPORTUNITY_HANDOFF_WAIT_V1','R2_QUOTE_HANDOFF_WAIT_V1')")
    guard=guard.replace("AND w.handoff_fact_id=NEW.responsibility_handoff_id", "AND (w.wait_contract_code<>'R2_QUOTE_HANDOFF_WAIT_V1' OR (original.wait_contract_code IN ('R2_QUOTE_FOLLOWUP_V1','R2_QUOTE_HANDOFF_WAIT_V1') AND w.awaited_fact_type=original.awaited_fact_type AND w.awaited_fact_id=original.awaited_fact_id AND w.awaited_fact_hash=original.awaited_fact_hash AND original.awaited_fact_revision IS NULL)) AND w.handoff_fact_id=NEW.responsibility_handoff_id")
    guard=guard.replace(' RETURN NULL;', " IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence old_task JOIN responsibility.task_occurrence new_task ON new_task.tenant_id=old_task.tenant_id AND new_task.task_occurrence_id=NEW.new_task_occurrence_id WHERE old_task.tenant_id=NEW.tenant_id AND old_task.task_occurrence_id=NEW.old_task_occurrence_id AND old_task.business_purpose_code=new_task.business_purpose_code AND old_task.primary_command_code=new_task.primary_command_code AND old_task.expected_completion_fact_type=new_task.expected_completion_fact_type AND old_task.original_sla_code=new_task.original_sla_code AND old_task.original_sla_seconds=new_task.original_sla_seconds) THEN RAISE EXCEPTION 'handoff successor purpose differs' USING ERRCODE='23514'; END IF;\n RETURN NULL;")
    return '\n'.join(lines)+initial+guard


def render_sql(before,after):
    sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
    for t in TABLES:
        sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
        sql+=f'''CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON opportunity.{t.name} IS '报价业务事实不可改写或删除。';
REVOKE ALL ON opportunity.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT{'' if t in (POLICY,SIGNER) else ', INSERT'} ON opportunity.{t.name} TO ${{app_command_role}};
GRANT SELECT ON opportunity.{t.name} TO ${{app_query_role}};
'''
    return sql+CONSISTENCY_SQL+render_handoff(before,after)

CONSISTENCY_SQL=r'''
CREATE FUNCTION opportunity.fn_check_quote_runtime() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE q opportunity.quote_revision%ROWTYPE; o opportunity.opportunity%ROWTYPE; p opportunity.quote_approval_policy%ROWTYPE; r opportunity.quote_approval_request%ROWTYPE; m opportunity.quote_approval_member%ROWTYPE; prior uuid;
BEGIN
 IF TG_TABLE_NAME='quote_approval_policy_signer' THEN
  SELECT * INTO p FROM opportunity.quote_approval_policy WHERE tenant_id=NEW.tenant_id AND quote_approval_policy_id=NEW.policy_id;
  IF p.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'quote policy signer set frozen' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_TABLE_NAME='quote_approval_member' THEN
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_approval_request_id=NEW.request_id;
  IF r.created_in_transaction IS DISTINCT FROM pg_current_xact_id() OR NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer s WHERE s.tenant_id=NEW.tenant_id AND s.policy_id=r.policy_id AND s.appointment_id=NEW.appointment_id) THEN RAISE EXCEPTION 'quote approval member not configured or frozen' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t JOIN opportunity.quote_revision v ON v.tenant_id=t.tenant_id AND v.opportunity_id=t.subject_id WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.task_id AND t.subject_type='opportunity.opportunity' AND v.quote_revision_id=r.quote_revision_id AND t.owner_appointment_id=NEW.appointment_id) THEN RAISE EXCEPTION 'quote approval task differs' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 ELSIF TG_TABLE_NAME='quote_approval_decision' THEN
  SELECT * INTO m FROM opportunity.quote_approval_member WHERE tenant_id=NEW.tenant_id AND quote_approval_member_id=NEW.member_id;
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_approval_request_id=m.request_id;
  SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND quote_revision_id=r.quote_revision_id;
 ELSE
  SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND quote_revision_id=NEW.quote_revision_id;
 END IF;
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=q.tenant_id AND opportunity_id=q.opportunity_id FOR UPDATE;
 IF q.quote_revision_id IS NULL OR o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.current_quote_revision_id IS DISTINCT FROM q.quote_revision_id OR (TG_TABLE_NAME<>'quote_workflow' AND q.valid_until<=clock_timestamp()) THEN RAISE EXCEPTION 'quote runtime current source differs' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='quote_approval_request' THEN
  SELECT * INTO p FROM opportunity.quote_approval_policy WHERE tenant_id=NEW.tenant_id AND quote_approval_policy_id=NEW.policy_id;
  IF p.quote_approval_policy_id IS NULL OR p.policy_code<>NEW.policy_code OR p.policy_version<>NEW.policy_version OR NOT EXISTS(SELECT 1 FROM identity.appointment a WHERE a.tenant_id=NEW.tenant_id AND a.appointment_id=q.created_by_appointment_id AND a.organization_unit_id=p.organization_unit_id) THEN RAISE EXCEPTION 'quote approval policy differs' USING ERRCODE='23514'; END IF;
  NEW.created_in_transaction=pg_current_xact_id();
 ELSIF TG_TABLE_NAME='quote_manual_delivery' THEN
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.evidence_binding b ON b.tenant_id=v.tenant_id AND b.evidence_binding_id=v.evidence_binding_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.material_version_id AND v.opportunity_id=o.opportunity_id AND b.revoked_at IS NULL) THEN RAISE EXCEPTION 'quote manual delivery evidence differs' USING ERRCODE='23514'; END IF;
  IF NEW.occurred_at<q.created_at OR NEW.occurred_at>=q.valid_until THEN RAISE EXCEPTION 'quote delivery time differs' USING ERRCODE='23514'; END IF;
  SELECT * INTO r FROM opportunity.quote_approval_request WHERE tenant_id=NEW.tenant_id AND quote_revision_id=q.quote_revision_id;
  IF r.quote_approval_request_id IS NOT NULL THEN
   IF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_member WHERE tenant_id=NEW.tenant_id AND request_id=r.quote_approval_request_id) OR EXISTS(SELECT 1 FROM opportunity.quote_approval_member member_row WHERE member_row.tenant_id=NEW.tenant_id AND member_row.request_id=r.quote_approval_request_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_decision d WHERE d.tenant_id=member_row.tenant_id AND d.member_id=member_row.quote_approval_member_id AND d.decision='APPROVED')) THEN RAISE EXCEPTION 'quote approval set incomplete' USING ERRCODE='23514'; END IF;
  ELSIF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy policy_row JOIN opportunity.quote_approval_policy_signer s ON s.tenant_id=policy_row.tenant_id AND s.policy_id=policy_row.quote_approval_policy_id JOIN identity.appointment a ON a.tenant_id=s.tenant_id AND a.appointment_id=s.appointment_id WHERE policy_row.tenant_id=NEW.tenant_id AND s.appointment_id=NEW.recorded_by AND policy_row.organization_unit_id=a.organization_unit_id AND policy_row.mode='SELF_AUTHORIZED' AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy n WHERE n.tenant_id=policy_row.tenant_id AND n.organization_unit_id=policy_row.organization_unit_id AND n.policy_code=policy_row.policy_code AND n.policy_version>policy_row.policy_version)) THEN RAISE EXCEPTION 'explicit quote authorization required' USING ERRCODE='23514';
  END IF;
 ELSIF TG_TABLE_NAME='quote_workflow' THEN
  SELECT w.quote_workflow_id INTO prior FROM opportunity.quote_workflow w WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id);
  IF NEW.opportunity_id<>o.opportunity_id OR NEW.previous_workflow_id IS DISTINCT FROM prior THEN RAISE EXCEPTION 'quote workflow predecessor differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_runtime() IS '锁定商机并核对不可变审批、交付和工作流依据。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_runtime() FROM PUBLIC;
'''

for _name in ('quote_approval_policy_signer','quote_approval_request','quote_approval_member','quote_approval_decision','quote_manual_delivery','quote_workflow'):
    CONSISTENCY_SQL+=f'''CREATE TRIGGER trg_{_name}__source BEFORE INSERT ON opportunity.{_name} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_runtime();
COMMENT ON TRIGGER trg_{_name}__source ON opportunity.{_name} IS '准确报价闭环来源守卫。';
'''

CONSISTENCY_SQL+=r'''
CREATE FUNCTION opportunity.fn_check_quote_response_source() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r opportunity.quote_response%ROWTYPE; i opportunity.quote_issue%ROWTYPE; q opportunity.quote_revision%ROWTYPE;
BEGIN
 SELECT * INTO r FROM opportunity.quote_response WHERE tenant_id=NEW.tenant_id AND quote_response_id=NEW.quote_response_id;
 SELECT * INTO i FROM opportunity.quote_issue WHERE tenant_id=r.tenant_id AND quote_issue_id=r.quote_issue_id;
 SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=i.tenant_id AND quote_revision_id=i.quote_revision_id;
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=q.tenant_id AND opportunity_id=q.opportunity_id FOR UPDATE;
 IF q.quote_revision_id IS NULL OR i.issue_status_code<>'ACTIVE' OR r.received_at<i.issued_at OR r.received_at>r.created_at OR (r.response_code='ACCEPTED' AND r.received_at>=q.valid_until) THEN RAISE EXCEPTION 'quote response exact issue or time differs' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='quote_response_basis' THEN
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.evidence_binding b ON b.tenant_id=v.tenant_id AND b.evidence_binding_id=v.evidence_binding_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.material_version_id AND v.opportunity_id=q.opportunity_id AND v.evidence_submission_id=r.evidence_submission_id AND b.revoked_at IS NULL) THEN RAISE EXCEPTION 'quote response evidence differs' USING ERRCODE='23514'; END IF;
  IF (r.response_code<>'ACCEPTED') IS DISTINCT FROM (NEW.next_check_at IS NOT NULL) OR NEW.next_check_at<=r.created_at THEN RAISE EXCEPTION 'quote response followup differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF q.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR r.response_code IS DISTINCT FROM 'ACCEPTED' OR NOT EXISTS(SELECT 1 FROM opportunity.quote_response_basis b WHERE b.tenant_id=r.tenant_id AND b.quote_response_id=r.quote_response_id) THEN RAISE EXCEPTION 'contract preparation requires exact accepted quote' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_response_source() IS '准确材料回复与接受来源核对；不创建合同或签署事实。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_response_source() FROM PUBLIC;
CREATE TRIGGER trg_quote_response_basis__source BEFORE INSERT ON opportunity.quote_response_basis FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_response_source();
COMMENT ON TRIGGER trg_quote_response_basis__source ON opportunity.quote_response_basis IS '客户回复准确证据及下一行动。';
CREATE TRIGGER trg_contract_preparation_source__source BEFORE INSERT ON opportunity.contract_preparation_source FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_response_source();
COMMENT ON TRIGGER trg_contract_preparation_source__source ON opportunity.contract_preparation_source IS '准确接受来源。';
CREATE FUNCTION opportunity.fn_check_manual_quote_issue() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.delivery_fact_type='opportunity.quote_manual_delivery' THEN
  IF NEW.external_action_id IS NOT NULL OR NEW.provider_inbox_id IS NOT NULL OR NEW.delivery_fact_revision IS DISTINCT FROM 0 OR NEW.delivery_fact_hash IS NOT NULL OR NOT EXISTS(SELECT 1 FROM opportunity.quote_manual_delivery d WHERE d.tenant_id=NEW.tenant_id AND d.quote_manual_delivery_id=NEW.delivery_fact_id AND d.quote_revision_id=NEW.quote_revision_id AND d.occurred_at=NEW.issued_at AND d.channel=NEW.delivery_channel_code) THEN RAISE EXCEPTION 'manual quote issue exact source differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_manual_quote_issue() IS '经确认人工交付引用准确事实，不冒充供应商回调。';
REVOKE ALL ON FUNCTION opportunity.fn_check_manual_quote_issue() FROM PUBLIC;
CREATE TRIGGER trg_quote_issue__manual_source BEFORE INSERT ON opportunity.quote_issue FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_manual_quote_issue();
COMMENT ON TRIGGER trg_quote_issue__manual_source ON opportunity.quote_issue IS '具名人工交付来源扩展；原报价守卫保留。';
CREATE FUNCTION opportunity.fn_seal_quote_approval_members() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer WHERE tenant_id=NEW.tenant_id AND policy_id=NEW.policy_id) OR EXISTS(SELECT 1 FROM opportunity.quote_approval_policy_signer s WHERE s.tenant_id=NEW.tenant_id AND s.policy_id=NEW.policy_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_approval_member m WHERE m.tenant_id=s.tenant_id AND m.request_id=NEW.quote_approval_request_id AND m.appointment_id=s.appointment_id)) THEN RAISE EXCEPTION 'complete configured quote approval members required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_seal_quote_approval_members() IS '审批请求必须原子冻结全部具名配置成员，缺配置不得默认通过。';
REVOKE ALL ON FUNCTION opportunity.fn_seal_quote_approval_members() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_approval_request__members AFTER INSERT ON opportunity.quote_approval_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_seal_quote_approval_members();
COMMENT ON TRIGGER trg_quote_approval_request__members ON opportunity.quote_approval_request IS '完整审批成员提交前验证。';
DO $v950$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v9',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v8';
 IF NOT FOUND THEN RAISE EXCEPTION 'V950 requires 52-plus-2-r2-v8' USING ERRCODE='55000'; END IF;
END;
$v950$;
'''
# Preserve every V910 root/responsibility/cancellation invariant; extend only the named quote branch.
from .v910_r2_opportunity_closure import render_sql as _closure_sql
_historical_closure=_closure_sql((),())
_start=_historical_closure.index('CREATE FUNCTION opportunity.fn_check_closure()')
_end=_historical_closure.index('CREATE CONSTRAINT TRIGGER trg_closure__consistency',_start)
_closure=_historical_closure[_start:_end].replace('CREATE FUNCTION opportunity.fn_check_closure()', 'CREATE OR REPLACE FUNCTION opportunity.fn_check_closure()')
_old_quote="EXISTS(SELECT 1 FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id)"
_allowed_quote=r'''EXISTS(
 SELECT 1 FROM opportunity.quote_workflow w JOIN opportunity.quote_revision q ON q.tenant_id=w.tenant_id AND q.quote_revision_id=w.quote_revision_id
 WHERE w.tenant_id=NEW.tenant_id AND w.opportunity_id=NEW.opportunity_id AND q.quote_revision_id=o.current_quote_revision_id
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_workflow n WHERE n.tenant_id=w.tenant_id AND n.previous_workflow_id=w.quote_workflow_id)
 AND (w.stage='SALES_DISPOSITION' OR (w.stage IN ('DELIVER','AWAIT_REPLY') AND q.valid_until<=NEW.closed_at
      AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id WHERE i.tenant_id=q.tenant_id AND i.quote_revision_id=q.quote_revision_id)))
 AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id JOIN opportunity.quote_revision accepted ON accepted.tenant_id=i.tenant_id AND accepted.quote_revision_id=i.quote_revision_id WHERE accepted.tenant_id=NEW.tenant_id AND accepted.opportunity_id=NEW.opportunity_id AND r.response_code='ACCEPTED'))'''
if _closure.count(_old_quote)!=1:
    raise ValueError('Frozen V910 quote closure predicate differs')
_closure=_closure.replace(_old_quote,'('+_old_quote+' AND NOT '+_allowed_quote+')')
_closure=_closure.replace("business_purpose_code='PROGRESS_OPPORTUNITY'", "business_purpose_code IN ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY')")
CONSISTENCY_SQL+='\n'+_closure
EVOLUTION=ContractEvolution(version=950,migration_name='V950__r2_quote_runtime.sql',contract_version='52-plus-2-r2-v9',apply=apply_evolution,render_sql=render_sql)
