"""V990 manual evidence signing; frozen preparation participation is never rewritten."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql
from .v920_r2_customer_requirements import body, checks
from .v980_r2_contract_versions import ref, uq, protected, sql_list
from .v950_r2_quote_runtime import txn

def fact(name,columns,cs=(),fks=(),indexes=()):
 return tenant_table('contract',name,name+'_id','人工签署不可变准确事实；不是合同执行。',(revision_col(),txn(),uuid_col('opportunity_id','准确商机。'),*columns,time_col('created_at','数据库记录时间。')),constraints=(*checks(name),*cs),foreign_keys=(ref(name,'opportunity_id','opportunity','opportunity'),*fks),indexes=indexes)
def chain(name,owner,prev):
 return index('uq_'+name+'__root',('tenant_id',owner),'同一准确依据唯一链根。',unique_=True,where=prev+' IS NULL')
def actor(name,column):return ref(name,column,'appointment','identity')
ARRANGEMENT=fact('signature_arrangement',(uuid_col('readiness_id','准确签署准备依据。'),uuid_col('contract_revision_id','批准正文版本。'),uuid_col('previous_arrangement_id','同正文安排补正前序。',nullable=True),uuid_col('registered_by_appointment_id','登记任职。'),int_col('slot_count','完整明确槽数量。'),*body()),(uq('signature_arrangement','previous_arrangement_id'),*protected('signature_arrangement'),check('ck_signature_arrangement__count','slot_count BETWEEN 1 AND 100','有限非空安排。')),(ref('signature_arrangement','readiness_id','signature_readiness'),ref('signature_arrangement','contract_revision_id','contract_revision'),ref('signature_arrangement','previous_arrangement_id','signature_arrangement'),actor('signature_arrangement','registered_by_appointment_id')),(chain('signature_arrangement','readiness_id','previous_arrangement_id'),))
DRAFT=fact('signature_draft',(uuid_col('readiness_id','准确签署准备依据。'),uuid_col('arrangement_id','已登记安排；登记前草稿为空。',nullable=True),uuid_col('previous_draft_id','上一不可变草稿。',nullable=True),uuid_col('saved_by_appointment_id','保存任职。'),*body()),(uq('signature_draft','previous_draft_id'),*protected('signature_draft')),(ref('signature_draft','readiness_id','signature_readiness'),ref('signature_draft','arrangement_id','signature_arrangement'),ref('signature_draft','previous_draft_id','signature_draft'),actor('signature_draft','saved_by_appointment_id')),(chain('signature_draft','readiness_id','previous_draft_id'),))
SUBMISSION=fact('signature_submission',(uuid_col('arrangement_id','准确安排。'),uuid_col('signature_plan_id','准确计划槽。'),uuid_col('previous_submission_id','同槽前次补证。',nullable=True),uuid_col('submitted_by_appointment_id','提交任职。'),uuid_col('material_version_id','正式已接收签字件。'),uuid_col('authority_material_version_id','正式权限材料。'),digest_col('material_sha256','签字文件字节摘要。'),digest_col('authority_material_sha256','权限文件字节摘要。'),digest_col('approved_body_sha256','批准正文摘要；无需等于签字文件。'),time_col('signed_at','实际签署业务时间。'),*body()),(uq('signature_submission','previous_submission_id'),*protected('signature_submission'),check('ck_signature_submission__time','signed_at<=created_at','业务时间不晚于系统记录。')),(ref('signature_submission','arrangement_id','signature_arrangement'),ref('signature_submission','signature_plan_id','signature_plan'),ref('signature_submission','previous_submission_id','signature_submission'),actor('signature_submission','submitted_by_appointment_id'),ref('signature_submission','material_version_id','material_version','opportunity'),ref('signature_submission','authority_material_version_id','material_version','opportunity')),(chain('signature_submission','signature_plan_id','previous_submission_id'),))
VERIFICATION=fact('signature_verification',(uuid_col('submission_id','准确不可变提交。'),uuid_col('verified_by_appointment_id','有权核验任职。'),code_col('decision_code','准确核验结论。'),*body()),(uq('signature_verification','submission_id'),*protected('signature_verification'),check('ck_signature_verification__decision',"decision_code IN ('VERIFIED','NEED_INFO','REVISION_REQUIRED','ARRANGEMENT_CORRECTION')",'明确结果，不直接等于全合同完成。')),(ref('signature_verification','submission_id','signature_submission'),actor('signature_verification','verified_by_appointment_id')))
ARCHIVE=fact('signature_archive',(uuid_col('arrangement_id','全部必要签署的准确安排。'),uuid_col('material_version_id','完整正式归档文件。'),uuid_col('archived_by_appointment_id','归档任职。'),*body()),(uq('signature_archive','arrangement_id'),*protected('signature_archive')),(ref('signature_archive','arrangement_id','signature_arrangement'),ref('signature_archive','material_version_id','material_version','opportunity'),actor('signature_archive','archived_by_appointment_id')))
RETURN=fact('signature_revision_return',(uuid_col('readiness_id','失效前准确准备依据。'),uuid_col('contract_revision_id','需要真实修订的正文。'),uuid_col('created_by_appointment_id','退回任职。'),*body()),(uq('signature_revision_return','readiness_id'),*protected('signature_revision_return')),(ref('signature_revision_return','readiness_id','signature_readiness'),ref('signature_revision_return','contract_revision_id','contract_revision'),actor('signature_revision_return','created_by_appointment_id')))
STAGES=('ARRANGE','COLLECT','AWAIT_VERIFICATION','SUPPLEMENT','PARTIAL','ARCHIVE','SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION')
WORKFLOW=fact('signature_workflow',(uuid_col('readiness_id','唯一消费准确准备依据。'),uuid_col('contract_revision_id','准确批准正文。'),uuid_col('previous_workflow_id','CAS直接前序流程。',nullable=True),code_col('stage_code','真实办理阶段。'),uuid_col('owner_appointment_id','实际责任任职；异常可空。',nullable=True),uuid_col('task_id','可办理待办；终态无占位任务。',nullable=True),uuid_col('prior_task_id','前序待办。',nullable=True),uuid_col('arrangement_id','准确当前安排。',nullable=True),uuid_col('submission_id','等待核验的准确提交。',nullable=True),uuid_col('created_by_appointment_id','实际写入任职。'),time_col('due_at','原责任期限，恢复不延长。'),code_col('recovery_resume_stage','异常恢复原阶段。',nullable=True)),(uq('signature_workflow','previous_workflow_id'),check('ck_signature_workflow__stage','stage_code IN '+sql_list(STAGES)+" AND (stage_code NOT IN ('SIGNATURE_COMPLETE','REVISION_REQUIRED','OWNER_EXCEPTION') OR task_id IS NULL) AND (stage_code IN ('OWNER_EXCEPTION','SIGNATURE_COMPLETE','REVISION_REQUIRED') OR owner_appointment_id IS NOT NULL)",'终态不创建不可办理占位任务。'),check('ck_signature_workflow__recovery',"recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN "+sql_list(STAGES[:-1])+')','异常保留准确恢复阶段。')),(ref('signature_workflow','readiness_id','signature_readiness'),ref('signature_workflow','contract_revision_id','contract_revision'),ref('signature_workflow','previous_workflow_id','signature_workflow'),actor('signature_workflow','owner_appointment_id'),actor('signature_workflow','created_by_appointment_id'),ref('signature_workflow','task_id','task_occurrence','responsibility'),ref('signature_workflow','prior_task_id','task_occurrence','responsibility'),ref('signature_workflow','arrangement_id','signature_arrangement'),ref('signature_workflow','submission_id','signature_submission')),(chain('signature_workflow','readiness_id','previous_workflow_id'),))
HANDOFF=fact('signature_handoff',(uuid_col('readiness_id','已消费准备依据。'),uuid_col('arrangement_id','已全部核验的安排。'),uuid_col('archive_id','准确完整归档。'),code_col('state_code','持久化后续等待边界。'),*body()),(uq('signature_handoff','readiness_id'),uq('signature_handoff','arrangement_id'),uq('signature_handoff','archive_id'),*protected('signature_handoff'),check('ck_signature_handoff__state',"state_code='AWAITING_EXECUTION_CONDITIONS'",'不等于合同执行、到账或建案。')),(ref('signature_handoff','readiness_id','signature_readiness'),ref('signature_handoff','arrangement_id','signature_arrangement'),ref('signature_handoff','archive_id','signature_archive')))
TEMPLATE_SIGNER=tenant_table('contract','template_signing_party','template_signing_party_id','审核模板同事务冻结的律所签约主体；不得为旧模板补造绑定。',(revision_col(),txn(),uuid_col('template_version_id','准确审核模板。'),uuid_col('party_id','真实律所主体。'),bigint_col('party_revision','批准时主体版本。'),uuid_col('profile_version_id','准确不可变主体资料。'),digest_col('party_snapshot_digest','资料身份规范摘要。'),code_col('role_code','明确模板签署角色FIRM。'),uuid_col('created_by_appointment_id','与模板审核者相同的任职。'),time_col('created_at','数据库冻结时间。')),constraints=(*checks('template_signing_party'),unique('uq_template_signing_party__template_role',('tenant_id','template_version_id','role_code'),'模板内律所角色唯一。'),unique('uq_template_signing_party__template_party',('tenant_id','template_version_id','party_id'),'模板主体唯一。'),check('ck_template_signing_party__values',"role_code='FIRM' AND party_revision BETWEEN 0 AND 9007199254740991",'明确律所绑定及准确资料版本。')),foreign_keys=(ref('template_signing_party','template_version_id','template_version'),ref('template_signing_party','party_id','party','party'),ref('template_signing_party','profile_version_id','profile_version','party'),actor('template_signing_party','created_by_appointment_id')))
TABLES=(TEMPLATE_SIGNER,ARRANGEMENT,DRAFT,SUBMISSION,VERIFICATION,ARCHIVE,RETURN,WORKFLOW,HANDOFF)
PLAN_UQS=('uk_signature_plan__revision_slot_no','uk_signature_plan__revision_authority_slot')
SALES_TASKS=('ARRANGE_CONTRACT_SIGNATURE','COLLECT_CONTRACT_SIGNATURE')
ALL_TASKS=(*SALES_TASKS,'VERIFY_CONTRACT_SIGNATURE','ARCHIVE_CONTRACT_SIGNATURE')

def apply_evolution(schemas):
 result=[]
 for s in schemas:
  ts=[]
  for t in s.tables:
   if s.name=='contract' and t.name=='signature_plan':
    t=replace(t,columns=tuple(replace(c,nullable=True) if c.name=='contract_participation_id' else c for c in t.columns)+(uuid_col('arrangement_id','具名人工签署安排；历史计划为空。',nullable=True),bool_col('signature_required','本槽是否必须签字。',default='true'),uuid_col('template_signing_party_id','审核模板冻结的律所绑定；与合同参与项互斥。',nullable=True)),constraints=(*(c for c in t.constraints if c.name not in PLAN_UQS),check('ck_signature_plan__manual',"(arrangement_id IS NULL AND contract_participation_id IS NOT NULL AND template_signing_party_id IS NULL) OR (arrangement_id IS NOT NULL AND signature_method_code='MANUAL' AND num_nonnulls(contract_participation_id,template_signing_party_id)=1 AND (signature_required OR seal_required))",'历史参与引用不放宽；人工安排独立登记。')),foreign_keys=(*t.foreign_keys,ref('signature_plan','arrangement_id','signature_arrangement'),ref('signature_plan','template_signing_party_id','template_signing_party')),indexes=(*t.indexes,*(index(n,('tenant_id','contract_revision_id',c),'历史计划槽唯一。',unique_=True,where='arrangement_id IS NULL') for n,c in zip(PLAN_UQS,('slot_no','authority_slot_code'))),index('uq_signature_plan__arrangement_slot',('tenant_id','arrangement_id','slot_no'),'同安排槽唯一。',unique_=True,where='arrangement_id IS NOT NULL'),index('uq_signature_plan__arrangement_authority',('tenant_id','arrangement_id','authority_slot_code'),'同安排权限唯一。',unique_=True,where='arrangement_id IS NOT NULL'),index('uq_signature_plan__arrangement_party',('tenant_id','arrangement_id','signer_party_id'),'同安排主体唯一。',unique_=True,where='arrangement_id IS NOT NULL')))
   if s.name=='contract' and t.name=='contract_signature':
    t=replace(t,columns=tuple(replace(c,nullable=True) if c.name=='evidence_submission_id' else c for c in t.columns)+(uuid_col('verification_id','人工证据核验通过事实；历史签署为空。',nullable=True),),constraints=(*t.constraints,uq('contract_signature','verification_id'),check('ck_contract_signature__manual',"(verification_id IS NULL AND evidence_submission_id IS NOT NULL) OR (verification_id IS NOT NULL AND evidence_submission_id IS NULL AND external_action_id IS NULL AND provider_inbox_id IS NULL AND verification_method_code='MANUAL')",'人工核验与旧外部证据互斥。')),foreign_keys=(*t.foreign_keys,ref('contract_signature','verification_id','signature_verification')))
   if s.name=='responsibility' and t.name=='task_occurrence':
    t=replace(t,constraints=tuple(replace(c,expression=c.expression.replace("'SUPPLEMENT_CONTRACT_REVIEW')","'SUPPLEMENT_CONTRACT_REVIEW',"+','.join("'"+v+"'" for v in SALES_TASKS)+')')) if c.name=='ck_task_occurrence__handoff_predecessor' else c for c in t.constraints))
   ts.append(t)
  result.append(replace(s,tables=(*ts,*TABLES) if s.name=='contract' else tuple(ts)))
 return tuple(result)

def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
 old={t.schema+'.'+t.name:t for s in before for t in s.tables}
 for s in after:
  for t in s.tables:
   prior=old.get(t.schema+'.'+t.name)
   if prior is None:continue
   oldcols={c.name:c for c in prior.columns}
   for c in t.columns:
    if c.name not in oldcols:
     sql+=f'ALTER TABLE {t.schema}.{t.name} ADD COLUMN {c.name} {c.sql_type}'+(' DEFAULT '+c.default if c.default else '')+(' NOT NULL' if not c.nullable else '')+';\n'
     sql+=f"COMMENT ON COLUMN {t.schema}.{t.name}.{c.name} IS '{c.comment}';\n"
    elif c.nullable and not oldcols[c.name].nullable:sql+=f'ALTER TABLE {t.schema}.{t.name} ALTER COLUMN {c.name} DROP NOT NULL;\n'
   for c in prior.constraints:
    if c not in t.constraints:sql+=f'ALTER TABLE {t.schema}.{t.name} DROP CONSTRAINT {c.name};\n'
   for c in t.constraints:
    if c not in prior.constraints:
     sql+=f'ALTER TABLE {t.schema}.{t.name} ADD {_constraint_sql(c)};\n'+f"COMMENT ON CONSTRAINT {c.name} ON {t.schema}.{t.name} IS '{c.comment}';\n"
     if c.kind=='UNIQUE':sql+=f"COMMENT ON INDEX {t.schema}.{c.name} IS '{c.comment}';\n"
   for f in t.foreign_keys:
    if f not in prior.foreign_keys:sql+=_render_foreign_key(t,f)+'\n'
 for s in after:
  for t in s.tables:
   prior=old.get(t.schema+'.'+t.name)
   for i in t.indexes:
    if prior is not None and i in prior.indexes:continue
    sql+='CREATE '+('UNIQUE ' if i.unique else '')+f'INDEX {i.name} ON {t.schema}.{t.name} ('+', '.join(i.columns)+')'+(' WHERE '+i.where if i.where else '')+';\n'+f"COMMENT ON INDEX {t.schema}.{i.name} IS '{i.comment}';\n"
 for t in TABLES:
  sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
  sql+=f'''CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON contract.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON contract.{t.name} IS '人工签署准确事实不可变。';
REVOKE ALL ON contract.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON contract.{t.name} TO ${{app_command_role}};
GRANT SELECT ON contract.{t.name} TO ${{app_query_role}};
'''
 from .v950_r2_quote_runtime import _closure
 from .v980_r2_contract_versions import ALL_TASKS as PREPARATION_TASKS, SALES_TASKS as PREPARATION_SALES
 from .v900_r2_owner_exception import TASK_GUARDS
 old_purposes="('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY')"
 sql+=_closure.replace(old_purposes,old_purposes[:-1]+','+','.join("'"+v+"'" for v in (*PREPARATION_TASKS,*ALL_TASKS))+')')
 sql+=TASK_GUARDS.split('CREATE TRIGGER')[0].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION').replace("NEW.business_purpose_code='PROGRESS_OPPORTUNITY'",'NEW.business_purpose_code IN '+sql_list(('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY',*PREPARATION_SALES,*SALES_TASKS)))
 sql+=GUARDS
 for t in TABLES:
  sql+=f"CREATE TRIGGER trg_{t.name}__manual_basis BEFORE INSERT ON contract.{t.name} FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();\nCOMMENT ON TRIGGER trg_{t.name}__manual_basis ON contract.{t.name} IS '准确准备、安排、材料和核验依据。';\n"
 for name in ('signature_plan','contract_signature'):
  sql+=f"CREATE TRIGGER trg_{name}__manual_basis BEFORE INSERT ON contract.{name} FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature();\nCOMMENT ON TRIGGER trg_{name}__manual_basis ON contract.{name} IS '人工证据具名路径守卫。';\n"
 sql+='''DO $v990$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v13',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v12';
 IF NOT FOUND THEN RAISE EXCEPTION 'V990 requires 52-plus-2-r2-v12' USING ERRCODE='55000'; END IF;
END;
$v990$;
'''
 return sql

GUARDS=r'''
CREATE FUNCTION contract.fn_assert_manual_readiness(t uuid, ready uuid, version uuid, opp uuid, passing boolean DEFAULT true) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE c contract.contract%ROWTYPE; version_row contract.contract_revision%ROWTYPE;
BEGIN
 SELECT r.* INTO c FROM contract.signature_readiness s JOIN contract.contract_revision v ON v.tenant_id=s.tenant_id AND v.contract_revision_id=s.contract_revision_id JOIN contract.contract r ON r.tenant_id=v.tenant_id AND r.contract_id=v.contract_id WHERE s.tenant_id=t AND s.signature_readiness_id=ready AND s.contract_revision_id=version AND r.opportunity_id=opp AND r.current_revision_id=version AND (NOT passing OR r.approved_revision_id=version) AND r.contract_execution_id IS NULL AND r.contract_termination_id IS NULL FOR UPDATE OF r;
 IF NOT FOUND THEN RAISE EXCEPTION 'manual signature readiness is not current' USING ERRCODE='23514'; END IF;
 IF NOT passing THEN RETURN; END IF;
 PERFORM contract.fn_assert_r2_approval(t,c.contract_id,version);
 SELECT * INTO version_row FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=version;
 PERFORM contract.fn_assert_r2_source(t,opp,version_row.source_quote_response_id,version_row.source_direct_decision_id,version_row.customer_confirmation_id,version_row.commercial_digest);
 PERFORM contract.fn_assert_r2_material(t,version_row.body_evidence_version_id,version_row.body_sha256,opp);
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) IS '锁定准确当前批准版本并复验有效审查和全部审批。';
REVOKE ALL ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION contract.fn_assert_manual_readiness(uuid,uuid,uuid,uuid,boolean) TO ${app_command_role};

CREATE FUNCTION contract.fn_check_manual_signature() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE a contract.signature_arrangement%ROWTYPE; p contract.signature_plan%ROWTYPE; s contract.signature_submission%ROWTYPE; v contract.signature_verification%ROWTYPE; w contract.signature_workflow%ROWTYPE; r contract.contract_revision%ROWTYPE; archive contract.signature_archive%ROWTYPE; material_sha bytea; proof record; passing boolean := true; template contract.template_version%ROWTYPE; signer contract.template_signing_party%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='template_signing_party' THEN
  SELECT * INTO template FROM contract.template_version WHERE tenant_id=NEW.tenant_id AND template_version_id=NEW.template_version_id;
  IF template.template_version_id IS NULL OR template.created_in_transaction<>pg_current_xact_id() OR NEW.created_by_appointment_id<>template.approved_by_appointment_id THEN RAISE EXCEPTION 'manual template signer binding sealed' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM party.profile_version profile JOIN party.party party ON party.tenant_id=profile.tenant_id AND party.party_id=profile.party_id WHERE profile.tenant_id=NEW.tenant_id AND profile.profile_version_id=NEW.profile_version_id AND profile.party_id=NEW.party_id AND profile.party_revision=NEW.party_revision AND party.revision=NEW.party_revision AND profile.party_type='ORGANIZATION' AND sha256(convert_to(profile.profile_version_id::text,'UTF8'))=NEW.party_snapshot_digest) THEN RAISE EXCEPTION 'manual template signer profile differs' USING ERRCODE='23514'; END IF;
  NEW.created_in_transaction=pg_current_xact_id(); NEW.created_at=clock_timestamp(); RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='signature_plan' THEN IF NEW.arrangement_id IS NULL THEN
  IF EXISTS(SELECT 1 FROM contract.contract_revision x WHERE x.tenant_id=NEW.tenant_id AND x.contract_revision_id=NEW.contract_revision_id AND x.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN RAISE EXCEPTION 'R2 signature plan requires manual arrangement' USING ERRCODE='23514'; END IF;
  RETURN NEW; END IF; END IF;
 IF TG_TABLE_NAME='contract_signature' THEN IF NEW.verification_id IS NULL THEN
  IF EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.signature_plan_id=NEW.signature_plan_id AND (x.arrangement_id IS NOT NULL OR EXISTS(SELECT 1 FROM contract.contract_revision cr WHERE cr.tenant_id=x.tenant_id AND cr.contract_revision_id=x.contract_revision_id AND cr.package_contract_code='R2_CONTRACT_PREPARATION_V1'))) THEN RAISE EXCEPTION 'manual signature requires verification' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF; END IF;
 IF TG_TABLE_NAME='signature_revision_return' THEN passing=false; END IF;
 IF TG_TABLE_NAME='signature_workflow' THEN passing=NEW.stage_code<>'REVISION_REQUIRED'; END IF;
 IF TG_TABLE_NAME='signature_verification' THEN passing=NEW.decision_code<>'REVISION_REQUIRED'; END IF;
 IF TG_TABLE_NAME NOT IN ('signature_plan','contract_signature') THEN NEW.created_in_transaction=pg_current_xact_id(); NEW.created_at=clock_timestamp(); END IF;
 IF TG_TABLE_NAME IN ('signature_arrangement','signature_workflow','signature_revision_return') THEN
  PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,NEW.readiness_id,NEW.contract_revision_id,NEW.opportunity_id,passing);
 END IF;
 IF TG_TABLE_NAME='signature_arrangement' THEN
  IF NEW.previous_arrangement_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_arrangement x WHERE x.tenant_id=NEW.tenant_id AND x.signature_arrangement_id=NEW.previous_arrangement_id AND x.readiness_id=NEW.readiness_id AND x.contract_revision_id=NEW.contract_revision_id AND x.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'manual signature arrangement predecessor differs' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM contract.signature_handoff x WHERE x.tenant_id=NEW.tenant_id AND x.readiness_id=NEW.readiness_id) THEN RAISE EXCEPTION 'manual signature already archived' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='signature_revision_return' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='signature_workflow' THEN
  IF NEW.previous_workflow_id IS NOT NULL THEN
   SELECT * INTO w FROM contract.signature_workflow WHERE tenant_id=NEW.tenant_id AND signature_workflow_id=NEW.previous_workflow_id;
   IF w.signature_workflow_id IS NULL OR w.readiness_id<>NEW.readiness_id OR w.contract_revision_id<>NEW.contract_revision_id OR w.opportunity_id<>NEW.opportunity_id OR w.due_at<>NEW.due_at THEN RAISE EXCEPTION 'manual signature workflow predecessor differs' USING ERRCODE='23514'; END IF;
  END IF;
  IF NEW.submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_submission x WHERE x.tenant_id=NEW.tenant_id AND x.signature_submission_id=NEW.submission_id AND x.arrangement_id=NEW.arrangement_id) THEN RAISE EXCEPTION 'manual signature workflow submission differs' USING ERRCODE='23514'; END IF;
  IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.task_id AND task.owner_appointment_id=NEW.owner_appointment_id AND task.subject_id=NEW.opportunity_id AND task.original_sla_due_at=NEW.due_at AND task.state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'manual signature workflow task differs' USING ERRCODE='23514'; END IF;
  IF NEW.arrangement_id IS NULL THEN RETURN NEW; END IF;
 END IF;
 IF TG_TABLE_NAME='signature_draft' THEN
  IF NEW.previous_draft_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_draft x WHERE x.tenant_id=NEW.tenant_id AND x.signature_draft_id=NEW.previous_draft_id AND x.readiness_id=NEW.readiness_id AND x.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'manual signature draft predecessor differs' USING ERRCODE='23514'; END IF;
  IF NEW.arrangement_id IS NULL THEN
  SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=(SELECT contract_revision_id FROM contract.signature_readiness WHERE tenant_id=NEW.tenant_id AND signature_readiness_id=NEW.readiness_id);
  PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,NEW.readiness_id,r.contract_revision_id,NEW.opportunity_id);
  RETURN NEW;
 END IF; END IF;
 IF TG_TABLE_NAME IN ('signature_verification','contract_signature') THEN
  IF TG_TABLE_NAME='contract_signature' THEN
   SELECT * INTO v FROM contract.signature_verification WHERE tenant_id=NEW.tenant_id AND signature_verification_id=NEW.verification_id;
   SELECT * INTO s FROM contract.signature_submission WHERE tenant_id=NEW.tenant_id AND signature_submission_id=v.submission_id;
  ELSE SELECT * INTO s FROM contract.signature_submission WHERE tenant_id=NEW.tenant_id AND signature_submission_id=NEW.submission_id;
  END IF;
  SELECT * INTO a FROM contract.signature_arrangement WHERE tenant_id=NEW.tenant_id AND signature_arrangement_id=s.arrangement_id;
 ELSE SELECT * INTO a FROM contract.signature_arrangement WHERE tenant_id=NEW.tenant_id AND signature_arrangement_id=NEW.arrangement_id;
 END IF;
 IF a.signature_arrangement_id IS NULL THEN RAISE EXCEPTION 'manual signature arrangement missing' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_manual_readiness(NEW.tenant_id,a.readiness_id,a.contract_revision_id,a.opportunity_id,passing);
 IF EXISTS(SELECT 1 FROM contract.signature_arrangement x WHERE x.tenant_id=NEW.tenant_id AND x.previous_arrangement_id=a.signature_arrangement_id) THEN RAISE EXCEPTION 'manual signature arrangement superseded' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME NOT IN ('signature_plan','contract_signature') THEN IF NEW.opportunity_id<>a.opportunity_id THEN RAISE EXCEPTION 'manual signature opportunity differs' USING ERRCODE='23514'; END IF; END IF;
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=a.contract_revision_id;
 IF TG_TABLE_NAME IN ('signature_draft','signature_workflow') THEN
  IF NEW.readiness_id<>a.readiness_id THEN RAISE EXCEPTION 'manual signature readiness differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='signature_plan' THEN
  IF NEW.template_signing_party_id IS NOT NULL THEN
   SELECT * INTO signer FROM contract.template_signing_party WHERE tenant_id=NEW.tenant_id AND template_signing_party_id=NEW.template_signing_party_id;
   IF signer.template_signing_party_id IS NULL OR signer.template_version_id<>r.template_version_id OR signer.party_id<>NEW.signer_party_id OR NOT NEW.required OR NEW.contract_participation_id IS NOT NULL THEN RAISE EXCEPTION 'manual signature template signer differs' USING ERRCODE='23514'; END IF;
  END IF;
  IF a.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'manual signature collection sealed' USING ERRCODE='23514'; END IF;
  IF NEW.contract_revision_id<>a.contract_revision_id OR NEW.plan_digest<>a.body_digest THEN RAISE EXCEPTION 'manual signature plan basis differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='signature_submission' THEN
  SELECT * INTO p FROM contract.signature_plan WHERE tenant_id=NEW.tenant_id AND signature_plan_id=NEW.signature_plan_id;
  IF p.arrangement_id IS DISTINCT FROM a.signature_arrangement_id OR NEW.approved_body_sha256<>r.body_sha256 THEN RAISE EXCEPTION 'manual signature submission basis differs' USING ERRCODE='23514'; END IF;
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.material_version_id,NEW.material_sha256,a.opportunity_id);
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.authority_material_version_id,NEW.authority_material_sha256,a.opportunity_id);
  IF NEW.previous_submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.signature_submission x JOIN contract.signature_verification d ON d.tenant_id=x.tenant_id AND d.submission_id=x.signature_submission_id WHERE x.tenant_id=NEW.tenant_id AND x.signature_submission_id=NEW.previous_submission_id AND x.signature_plan_id=NEW.signature_plan_id AND x.arrangement_id=NEW.arrangement_id AND d.decision_code='NEED_INFO') THEN RAISE EXCEPTION 'manual signature supplement predecessor differs' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='signature_verification' THEN
  IF s.signature_submission_id IS NULL OR EXISTS(SELECT 1 FROM contract.signature_submission x WHERE x.tenant_id=NEW.tenant_id AND x.previous_submission_id=s.signature_submission_id) THEN RAISE EXCEPTION 'manual signature verification basis differs' USING ERRCODE='23514'; END IF;
  IF NEW.decision_code='VERIFIED' THEN
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,s.material_version_id,s.material_sha256,a.opportunity_id);
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,s.authority_material_version_id,s.authority_material_sha256,a.opportunity_id);
  END IF;
 ELSIF TG_TABLE_NAME='contract_signature' THEN
  SELECT * INTO p FROM contract.signature_plan WHERE tenant_id=NEW.tenant_id AND signature_plan_id=s.signature_plan_id;
  IF v.decision_code IS DISTINCT FROM 'VERIFIED' OR v.created_in_transaction<>pg_current_xact_id() OR NEW.signature_plan_id<>s.signature_plan_id OR NEW.contract_revision_id<>a.contract_revision_id OR NEW.signer_party_id<>p.signer_party_id OR NEW.signed_content_digest<>s.approved_body_sha256 OR NEW.signed_at<>s.signed_at THEN RAISE EXCEPTION 'manual signature verification basis differs' USING ERRCODE='23514'; END IF;
  NEW.verified_at=clock_timestamp(); NEW.created_at=NEW.verified_at; NEW.changed_at=NEW.verified_at;
 ELSIF TG_TABLE_NAME='signature_archive' THEN
  IF (SELECT count(*) FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id)<>a.slot_count OR NOT EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id AND x.required) OR EXISTS(SELECT 1 FROM contract.signature_plan x WHERE x.tenant_id=NEW.tenant_id AND x.arrangement_id=a.signature_arrangement_id AND x.required AND NOT EXISTS(SELECT 1 FROM contract.contract_signature cs WHERE cs.tenant_id=x.tenant_id AND cs.signature_plan_id=x.signature_plan_id AND cs.verification_id IS NOT NULL AND cs.revoked_at IS NULL)) THEN RAISE EXCEPTION 'manual signature archive incomplete' USING ERRCODE='23514'; END IF;
  FOR proof IN SELECT u.* FROM contract.signature_plan plan JOIN contract.contract_signature cs ON cs.tenant_id=plan.tenant_id AND cs.signature_plan_id=plan.signature_plan_id JOIN contract.signature_verification d ON d.tenant_id=cs.tenant_id AND d.signature_verification_id=cs.verification_id JOIN contract.signature_submission u ON u.tenant_id=d.tenant_id AND u.signature_submission_id=d.submission_id WHERE plan.tenant_id=NEW.tenant_id AND plan.arrangement_id=a.signature_arrangement_id AND plan.required AND cs.revoked_at IS NULL LOOP
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,proof.material_version_id,proof.material_sha256,a.opportunity_id);
   PERFORM contract.fn_assert_r2_material(NEW.tenant_id,proof.authority_material_version_id,proof.authority_material_sha256,a.opportunity_id);
  END LOOP;
  SELECT src.server_sha256 INTO material_sha FROM opportunity.material_version m JOIN evidence.received_source_object src ON src.tenant_id=m.tenant_id AND src.received_source_object_id=m.received_source_object_id WHERE m.tenant_id=NEW.tenant_id AND m.material_version_id=NEW.material_version_id;
  PERFORM contract.fn_assert_r2_material(NEW.tenant_id,NEW.material_version_id,material_sha,a.opportunity_id);
 ELSIF TG_TABLE_NAME='signature_handoff' THEN
  SELECT * INTO archive FROM contract.signature_archive WHERE tenant_id=NEW.tenant_id AND signature_archive_id=NEW.archive_id;
  IF NEW.readiness_id<>a.readiness_id OR archive.arrangement_id IS DISTINCT FROM a.signature_arrangement_id OR archive.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'manual signature handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_manual_signature() IS '准确人工签署输入、不可变补证和完整归档守卫。';
REVOKE ALL ON FUNCTION contract.fn_check_manual_signature() FROM PUBLIC;
'''
GUARDS+=r'''
CREATE FUNCTION contract.fn_check_manual_signature_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF TG_TABLE_NAME='signature_arrangement' THEN
  IF (SELECT count(*) FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id)<>NEW.slot_count OR NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id AND p.required) OR NOT EXISTS(SELECT 1 FROM contract.template_signing_party binding JOIN contract.contract_revision revision ON revision.tenant_id=binding.tenant_id AND revision.template_version_id=binding.template_version_id WHERE revision.tenant_id=NEW.tenant_id AND revision.contract_revision_id=NEW.contract_revision_id) OR EXISTS(SELECT 1 FROM contract.template_signing_party binding JOIN contract.contract_revision revision ON revision.tenant_id=binding.tenant_id AND revision.template_version_id=binding.template_version_id WHERE revision.tenant_id=NEW.tenant_id AND revision.contract_revision_id=NEW.contract_revision_id AND NOT EXISTS(SELECT 1 FROM contract.signature_plan p WHERE p.tenant_id=NEW.tenant_id AND p.arrangement_id=NEW.signature_arrangement_id AND p.template_signing_party_id=binding.template_signing_party_id AND p.required)) THEN RAISE EXCEPTION 'manual signature arrangement set incomplete' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.decision_code='VERIFIED' AND NOT EXISTS(SELECT 1 FROM contract.contract_signature cs WHERE cs.tenant_id=NEW.tenant_id AND cs.verification_id=NEW.signature_verification_id) THEN RAISE EXCEPTION 'manual signature verified fact missing' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_manual_signature_complete() IS '安排完整集合及核验通过签署同事务封存。';
REVOKE ALL ON FUNCTION contract.fn_check_manual_signature_complete() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER ctrg_signature_arrangement__complete AFTER INSERT ON contract.signature_arrangement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature_complete();
COMMENT ON TRIGGER ctrg_signature_arrangement__complete ON contract.signature_arrangement IS '完整非空安排封存。';
CREATE CONSTRAINT TRIGGER ctrg_signature_verification__complete AFTER INSERT ON contract.signature_verification DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION contract.fn_check_manual_signature_complete();
COMMENT ON TRIGGER ctrg_signature_verification__complete ON contract.signature_verification IS '核验通过必须同事务形成签署。';
'''
EVOLUTION=ContractEvolution(version=990,migration_name='V990__r2_manual_signature.sql',contract_version='52-plus-2-r2-v13',apply=apply_evolution,render_sql=render_sql)
