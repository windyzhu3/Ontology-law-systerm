"""Named R2 preparation successor; legacy execution and immutable identities remain intact."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _constraint_sql, _render_update_guards
from .v920_r2_customer_requirements import basis, body, document_checks, document_fks, fk, checks
from .v950_r2_quote_runtime import txn

FORMAT='R2_CONTRACT_PREPARATION_V1'
SALES_TASKS=('REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW')
ALL_TASKS=(*SALES_TASKS,'DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT')
def sql_list(values):return '('+','.join("'"+v+"'" for v in values)+')'
def fact(name,columns,cs=(),fks=()):
    return tenant_table('contract',name,name+'_id','T08准确不可变事实；不是签署或执行事实。',(revision_col(),txn(),*columns,time_col('created_at','数据库形成时间。')),constraints=(*checks(name),*cs),foreign_keys=fks)
def ref(name,column,target,schema='contract'):
    return fk(name,column,schema,target)
def uq(name,key):return unique('uq_'+name+'__'+key,('tenant_id',key),'准确事实唯一。')
def protected(name):return (check('ck_'+name+'__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','有界受保护正文。'),)

DRAFT=tenant_table('contract','preparation_draft','preparation_draft_id','准备草稿；保存不等于形成版本或完成责任。',(*basis(),uuid_col('customer_confirmation_id','当前客户需求确认。'),uuid_col('previous_draft_id','同商机直接前稿；换负责人也沿用单链。',nullable=True),uuid_col('source_quote_response_id','准确接受报价。',nullable=True),uuid_col('source_direct_decision_id','准确直接授权。',nullable=True),digest_col('commercial_digest','准确商业摘要。'),*body(),time_col('created_at','保存时间。')),constraints=(*document_checks('preparation_draft'),uq('preparation_draft','previous_draft_id'),check('ck_preparation_draft__source','num_nonnulls(source_quote_response_id,source_direct_decision_id)=1','双入口互斥。')),foreign_keys=(*document_fks('preparation_draft'),ref('preparation_draft','customer_confirmation_id','customer_requirement_confirmation','opportunity'),ref('preparation_draft','previous_draft_id','preparation_draft'),ref('preparation_draft','source_quote_response_id','quote_response','opportunity'),ref('preparation_draft','source_direct_decision_id','preparation_decision')))

def approved_document(name):
    return fact(name,(code_col('document_code','受控文档代码。'),int_col('version_no','已审核文档版本。'),uuid_col('evidence_version_id','真实T06原件版本。'),digest_col('body_sha256','真实文档字节摘要。'),uuid_col('approved_by_appointment_id','有权审核者。'),time_col('approved_at','真实审核时间。')),
        (unique('uq_'+name+'__code_version',('tenant_id','document_code','version_no'),'已审核文档版本唯一。'),check('ck_'+name+'__version','version_no>0 AND approved_at<=created_at','真实审核版本和时间。')),
        (ref(name,'evidence_version_id','material_version','opportunity'),ref(name,'approved_by_appointment_id','appointment','identity')))
TEMPLATE=approved_document('template_version')
CLAUSE=approved_document('clause_version')
REVISION_CLAUSE=fact('revision_clause',(uuid_col('contract_revision_id','准确合同版本。'),uuid_col('clause_version_id','准确已审核条款。'),int_col('clause_no','版本内顺序。')),(unique('uq_revision_clause__number',('tenant_id','contract_revision_id','clause_no'),'序号唯一。'),unique('uq_revision_clause__clause',('tenant_id','contract_revision_id','clause_version_id'),'条款唯一。'),check('ck_revision_clause__no','clause_no BETWEEN 1 AND 200','有界条款集合。')),(ref('revision_clause','contract_revision_id','contract_revision'),ref('revision_clause','clause_version_id','clause_version')))
REVIEW_REQUEST=fact('revision_review_request',(uuid_col('contract_revision_id','请求审查的准确版本。'),digest_col('scope_hash','本次准确审查范围。'),uuid_col('requested_by_appointment_id','提交任职。')),(uq('revision_review_request','contract_revision_id'),),(ref('revision_review_request','contract_revision_id','contract_revision'),ref('revision_review_request','requested_by_appointment_id','appointment','identity')))
REVIEW_DECISION=fact('revision_review_decision',(uuid_col('request_id','准确审查申请。'),uuid_col('conflict_review_id','真实独立冲突审查。'),code_col('decision_code','审查结果CLEAR/WAIVED/NEED_INFO/BLOCKED。'),digest_col('scope_hash','准确范围。'),digest_col('resolution_digest','准确结论摘要；补正阻断可空。',nullable=True),uuid_col('decided_by_appointment_id','实际审查任职。'),*body()),(uq('revision_review_decision','request_id'),*protected('revision_review_decision'),check('ck_revision_review_decision__code',"decision_code IN ('CLEAR','WAIVED','NEED_INFO','BLOCKED') AND (decision_code NOT IN ('CLEAR','WAIVED') OR resolution_digest IS NOT NULL)",'通过必须有准确结论依据。')),(ref('revision_review_decision','request_id','revision_review_request'),ref('revision_review_decision','conflict_review_id','conflict_review','conflict'),ref('revision_review_decision','decided_by_appointment_id','appointment','identity')))
BINDING=fact('revision_review_binding',(uuid_col('contract_revision_id','被放行版本。'),uuid_col('review_decision_id','真实通过决定。'),uuid_col('conflict_review_id','准确审查。'),digest_col('scope_hash','准确审查范围。'),digest_col('resolution_digest','准确可用结论。')),(uq('revision_review_binding','contract_revision_id'),uq('revision_review_binding','review_decision_id')),(ref('revision_review_binding','contract_revision_id','contract_revision'),ref('revision_review_binding','review_decision_id','revision_review_decision'),ref('revision_review_binding','conflict_review_id','conflict_review','conflict')))
REQUIREMENT=fact('revision_approval_requirement',(uuid_col('contract_revision_id','准确版本。'),code_col('requirement_code','明确审批要求。'),uuid_col('approver_appointment_id','明确审批任职。'),digest_col('policy_digest','本版审批策略摘要。')),(unique('uq_revision_approval_requirement__code',('tenant_id','contract_revision_id','requirement_code'),'审批要求唯一。'),),(ref('revision_approval_requirement','contract_revision_id','contract_revision'),ref('revision_approval_requirement','approver_appointment_id','appointment','identity')))
APPROVAL=fact('revision_approval_decision',(uuid_col('requirement_id','准确审批要求。'),uuid_col('review_binding_id','本版通过审查。'),code_col('decision_code','批准或退回。'),uuid_col('decided_by_appointment_id','实际审批任职。'),*body()),(uq('revision_approval_decision','requirement_id'),*protected('revision_approval_decision'),check('ck_revision_approval_decision__code',"decision_code IN ('APPROVED','RETURNED')",'明确审批结果。')),(ref('revision_approval_decision','requirement_id','revision_approval_requirement'),ref('revision_approval_decision','review_binding_id','revision_review_binding'),ref('revision_approval_decision','decided_by_appointment_id','appointment','identity')))
READY=fact('signature_readiness',(uuid_col('contract_revision_id','已完成审查与审批的准确当前版本。'),uuid_col('review_binding_id','本版通过审查依据。'),code_col('state_code','明确下一阶段交接边界；不创建签署待办。')),(uq('signature_readiness','contract_revision_id'),check('ck_signature_readiness__state',"state_code='READY_FOR_SIGNATURE'",'等待下一阶段，不是已签署。')),(ref('signature_readiness','contract_revision_id','contract_revision'),ref('signature_readiness','review_binding_id','revision_review_binding')))
APPROVAL_REQUEST=fact('revision_approval_request',(uuid_col('contract_revision_id','准确版本。'),uuid_col('review_binding_id','本版通过审查。'),uuid_col('requested_by_appointment_id','提交任职。')),(uq('revision_approval_request','contract_revision_id'),uq('revision_approval_request','review_binding_id')),(ref('revision_approval_request','contract_revision_id','contract_revision'),ref('revision_approval_request','review_binding_id','revision_review_binding'),ref('revision_approval_request','requested_by_appointment_id','appointment','identity')))
APPROVAL=replace(APPROVAL,columns=(*APPROVAL.columns,uuid_col('approval_request_id','正式提交的准确审批申请。')),foreign_keys=(*APPROVAL.foreign_keys,ref('revision_approval_decision','approval_request_id','revision_approval_request')))
DRAFT=replace(DRAFT,indexes=(index('uq_preparation_draft__root',('tenant_id','opportunity_id'),'每个商机只有一个草稿根。',unique_=True,where='previous_draft_id IS NULL'),))
REVIEW_REQUEST=replace(REVIEW_REQUEST,columns=(*REVIEW_REQUEST.columns,uuid_col('previous_request_id','前次补正审查申请。',nullable=True)),constraints=(*(c for c in REVIEW_REQUEST.constraints if c.name!='uq_revision_review_request__contract_revision_id'),uq('revision_review_request','previous_request_id')),foreign_keys=(*REVIEW_REQUEST.foreign_keys,ref('revision_review_request','previous_request_id','revision_review_request')),indexes=(index('uq_revision_review_request__root',('tenant_id','contract_revision_id'),'每版只有一个审查申请根。',unique_=True,where='previous_request_id IS NULL'),))
WORKFLOW=fact('preparation_workflow',(uuid_col('opportunity_id','准确商机。'),uuid_col('contract_id','形成后的唯一合同身份。',nullable=True),uuid_col('previous_workflow_id','直接前一流程事实。',nullable=True),code_col('stage_code','明确合同准备责任阶段。'),uuid_col('owner_appointment_id','实际后继负责人。'),uuid_col('task_id','可办理的后继待办。',nullable=True),uuid_col('prior_task_id','移交的准确前序待办。',nullable=True)),(uq('preparation_workflow','previous_workflow_id'),check('ck_preparation_workflow__stage',"stage_code IN ('DIRECT_REQUEST','DIRECT_REVIEW','DIRECT_RETURNED','PREPARE','RETURNED','SUBMIT_REVIEW','AWAIT_REVIEW','REVIEW_SUPPLEMENT','REVIEW_BLOCKED','SUBMIT_APPROVAL','AWAIT_APPROVAL','READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE','OWNER_EXCEPTION') AND (stage_code NOT IN ('READY_FOR_SIGNATURE','AWAITING_NEXT_STAGE') OR task_id IS NULL)",'明确责任阶段；下阶段边界不创建占位待办。')),(ref('preparation_workflow','opportunity_id','opportunity','opportunity'),ref('preparation_workflow','contract_id','contract'),ref('preparation_workflow','previous_workflow_id','preparation_workflow'),ref('preparation_workflow','owner_appointment_id','appointment','identity'),ref('preparation_workflow','task_id','task_occurrence','responsibility'),ref('preparation_workflow','prior_task_id','task_occurrence','responsibility')))
WORKFLOW=replace(WORKFLOW,indexes=(index('uq_preparation_workflow__root',('tenant_id','opportunity_id'),'每商机只有一个流程根。',unique_=True,where='previous_workflow_id IS NULL'),))
WORKFLOW=replace(WORKFLOW,columns=(*WORKFLOW.columns,uuid_col('created_by_appointment_id','真实流程写入任职；与后继责任人分离。')),foreign_keys=(*WORKFLOW.foreign_keys,ref('preparation_workflow','created_by_appointment_id','appointment','identity')))
WORKFLOW=replace(WORKFLOW,columns=(*WORKFLOW.columns,code_col('recovery_resume_stage','失效独立责任归还销售时的准确恢复阶段；旧报价承接异常为空。',nullable=True)),constraints=(*WORKFLOW.constraints,check('ck_preparation_workflow__recovery_resume',"recovery_resume_stage IS NULL OR (stage_code='OWNER_EXCEPTION' AND recovery_resume_stage IN ('DIRECT_RETURNED','RETURNED') AND task_id IS NULL AND prior_task_id IS NOT NULL)",'仅无可办理任务的负责人异常保留恢复目标；不伪造退回决定。')))
POLICY=fact('approval_policy',(uuid_col('organization_unit_id','明确适用组织。'),code_col('policy_code','具名合同审批策略。'),bigint_col('policy_version','明确策略版本。'),code_col('mode','明确要求批准；不复用报价自授权。'),digest_col('policy_digest','策略和审批成员规范摘要。')),(unique('uq_approval_policy__version',('tenant_id','organization_unit_id','policy_code','policy_version'),'组织策略版本唯一。'),check('ck_approval_policy__values',"policy_code='R2_CONTRACT_APPROVAL_V1' AND policy_version BETWEEN 1 AND 9007199254740991 AND mode='REQUIRE_APPROVAL'",'明确合同审批策略。')),(ref('approval_policy','organization_unit_id','organization_unit','identity'),))
POLICY_MEMBER=fact('approval_policy_member',(uuid_col('policy_id','准确策略版本。'),code_col('requirement_code','明确审批要求。'),uuid_col('appointment_id','明确有权审批任职。')),(unique('uq_approval_policy_member__requirement',('tenant_id','policy_id','requirement_code'),'每个策略要求唯一。'),),(ref('approval_policy_member','policy_id','approval_policy'),ref('approval_policy_member','appointment_id','appointment','identity')))
REQUIREMENT=replace(REQUIREMENT,columns=(*REQUIREMENT.columns,uuid_col('policy_id','本版明确合同审批策略。')),foreign_keys=(*REQUIREMENT.foreign_keys,ref('revision_approval_requirement','policy_id','approval_policy')))
REVIEW_REQUEST=replace(REVIEW_REQUEST,columns=(*REVIEW_REQUEST.columns,*body()),constraints=(*REVIEW_REQUEST.constraints,*protected('revision_review_request')))
TABLES=(POLICY,POLICY_MEMBER,WORKFLOW,APPROVAL_REQUEST,DRAFT,TEMPLATE,CLAUSE,REVISION_CLAUSE,REVIEW_REQUEST,REVIEW_DECISION,BINDING,REQUIREMENT,APPROVAL,READY)
ROOT_COLUMNS=(txn(),uuid_col('created_by_appointment_id','R2合同创建者；旧合同可空。',nullable=True),uuid_col('direct_preparation_decision_id','最初消费的直接准备授权；后续版本独立记录来源。',nullable=True),code_col('preparation_contract_code','具名R2双入口锚点；旧合同为空。',nullable=True))
REVISION_COLUMNS=(uuid_col('preparation_draft_id','真实准备草稿。',nullable=True),uuid_col('source_direct_decision_id','本版准确直接授权。',nullable=True),uuid_col('customer_confirmation_id','准确客户确认。',nullable=True),digest_col('commercial_digest','准确商业摘要。',nullable=True),uuid_col('body_evidence_version_id','准确T06正文版本。',nullable=True),uuid_col('template_version_id','已审核真实模板版本。',nullable=True),digest_col('party_snapshot_digest','准确参与方快照。',nullable=True),encrypted_col('package_ciphertext','本版完整规范包受保护密文。',nullable=True),bool_col('receipt_required_before_transfer','仅明确付款约定阻断转案。',nullable=True),bigint_col('required_amount_minor','明确先到账金额。',nullable=True),int_col('approval_requirement_count','在版本形成事务冻结的必要审批数量。',nullable=True),int_col('clause_count','本版条款集合数量。',nullable=True),txn())
RELAXED=('confirmed_action_draft_id','source_quote_revision_id','source_quote_response_id','body_evidence_submission_id','pre_contract_review_id','pre_contract_scope_hash','pre_contract_resolution_digest')
ROOT_CONSTRAINTS=(uq('contract','direct_preparation_decision_id'),check('ck_contract__r2_source',"(preparation_contract_code IS NULL AND accepted_quote_response_id IS NOT NULL AND direct_preparation_decision_id IS NULL) OR (preparation_contract_code IS NOT NULL AND preparation_contract_code='R2_CONTRACT_PREPARATION_V1' AND created_by_appointment_id IS NOT NULL AND num_nonnulls(accepted_quote_response_id,direct_preparation_decision_id)=1)",'具名双入口；旧结构不放宽。'))
ROOT_CONSTRAINTS=(replace(ROOT_CONSTRAINTS[0],name='uq_contract__direct_source'),ROOT_CONSTRAINTS[1])
R2_FIELDS=('preparation_draft_id','customer_confirmation_id','commercial_digest','body_evidence_version_id','template_version_id','party_snapshot_digest','package_ciphertext','receipt_required_before_transfer','approval_requirement_count','clause_count')
R2_SHAPE="package_contract_code='R2_CONTRACT_PREPARATION_V1' AND package_contract_version=1 AND "+' AND '.join(n+' IS NOT NULL' for n in R2_FIELDS)+" AND pre_contract_review_id IS NULL AND pre_contract_scope_hash IS NULL AND pre_contract_resolution_digest IS NULL AND confirmed_action_draft_id IS NULL AND num_nonnulls(source_quote_response_id,source_direct_decision_id)=1 AND ((source_quote_response_id IS NOT NULL AND source_quote_revision_id IS NOT NULL) OR (source_direct_decision_id IS NOT NULL AND source_quote_revision_id IS NULL)) AND octet_length(package_ciphertext) BETWEEN 29 AND 1048576 AND approval_requirement_count BETWEEN 1 AND 100 AND clause_count BETWEEN 0 AND 200 AND ((receipt_required_before_transfer AND required_amount_minor IS NOT NULL AND required_amount_minor BETWEEN 1 AND 9007199254740991) OR (NOT receipt_required_before_transfer AND required_amount_minor IS NULL))"
LEGACY_SHAPE="package_contract_code<>'R2_CONTRACT_PREPARATION_V1' AND "+' AND '.join(n+' IS NOT NULL' for n in RELAXED)+ ' AND '+' AND '.join(n+' IS NULL' for n in ('source_direct_decision_id',*R2_FIELDS,'required_amount_minor'))
REVISION_CONSTRAINTS=(*digest_checks('contract_revision',REVISION_COLUMNS),check('ck_contract_revision__r2_shape','('+R2_SHAPE+') OR ('+LEGACY_SHAPE+')','具名准备版本与完整旧版本分离；不伪造审查。'),uq('contract_revision','preparation_draft_id'))

def apply_evolution(schemas):
    result=[]
    for s in schemas:
        ts=[]
        for t in s.tables:
            if s.name=='platform_meta' and t.name=='r2_opportunity_checkpoint':t=replace(t,columns=tuple(replace(c,sql_type='varchar(32)') if c.name=='scan_kind' else c for c in t.columns),constraints=tuple(replace(c,expression="scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION','CONTRACT_PREPARATION')") if c.name=='ck_r2_opportunity_checkpoint__kind' else c for c in t.constraints))
            if s.name=='responsibility' and t.name=='task_occurrence':t=replace(t,constraints=tuple(replace(c,expression=c.expression.replace("'RECORD_QUOTE_REPLY')","'RECORD_QUOTE_REPLY',"+','.join("'"+x+"'" for x in SALES_TASKS)+')')) if c.name=='ck_task_occurrence__handoff_predecessor' else c for c in t.constraints))
            if s.name=='contract' and t.name=='contract':t=replace(t,columns=tuple(replace(c,nullable=True) if c.name=='accepted_quote_response_id' else c for c in t.columns)+ROOT_COLUMNS,constraints=(*t.constraints,*ROOT_CONSTRAINTS),foreign_keys=(*t.foreign_keys,ref('contract','direct_preparation_decision_id','preparation_decision'),ref('contract','created_by_appointment_id','appointment','identity')))
            if s.name=='contract' and t.name=='contract_revision':t=replace(t,columns=tuple(replace(c,nullable=True) if c.name in RELAXED else c for c in t.columns)+REVISION_COLUMNS,constraints=(*t.constraints,*REVISION_CONSTRAINTS),foreign_keys=(*t.foreign_keys,*(ref('contract_revision',a,b,c) for a,b,c in [('preparation_draft_id','preparation_draft','contract'),('source_direct_decision_id','preparation_decision','contract'),('customer_confirmation_id','customer_requirement_confirmation','opportunity'),('body_evidence_version_id','material_version','opportunity'),('template_version_id','template_version','contract')])) )
            if s.name=='contract' and t.name=='preparation_decision':t=replace(t,columns=(*t.columns,txn()))
            ts.append(t)
        result.append(replace(s,tables=(*ts,*(TABLES if s.name=='contract' else ()))))
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
                elif c.sql_type!=oldcols[c.name].sql_type:sql+=f'ALTER TABLE {t.schema}.{t.name} ALTER COLUMN {c.name} TYPE {c.sql_type};\n'
            for c in t.constraints:
                if c not in prior.constraints:
                    if any(old.name==c.name for old in prior.constraints):sql+=f'ALTER TABLE {t.schema}.{t.name} DROP CONSTRAINT {c.name};\n'
                    sql+=f'ALTER TABLE {t.schema}.{t.name} ADD {_constraint_sql(c)};\n'+f"COMMENT ON CONSTRAINT {c.name} ON {t.schema}.{t.name} IS '{c.comment}';\n"
                    if c.kind=='UNIQUE':sql+=f"COMMENT ON INDEX {t.schema}.{c.name} IS '{c.comment}';\n"
            for f in t.foreign_keys:
                if f not in prior.foreign_keys:sql+=_render_foreign_key(t,f)+'\n'
    for t in TABLES:
        sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
        sql+=f'''CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON contract.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON contract.{t.name} IS '不可变准确事实禁止改写。';
REVOKE ALL ON contract.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON contract.{t.name} TO ${{app_command_role}};
GRANT SELECT ON contract.{t.name} TO ${{app_query_role}};
'''
    sql+="CREATE UNIQUE INDEX uq_preparation_draft__root ON contract.preparation_draft (tenant_id,opportunity_id) WHERE previous_draft_id IS NULL;\nCOMMENT ON INDEX contract.uq_preparation_draft__root IS '商机准备草稿单根。';\n"
    sql+="CREATE UNIQUE INDEX uq_revision_review_request__root ON contract.revision_review_request (tenant_id,contract_revision_id) WHERE previous_request_id IS NULL;\nCOMMENT ON INDEX contract.uq_revision_review_request__root IS '合同版本审查单根。';\nCREATE UNIQUE INDEX uq_preparation_workflow__root ON contract.preparation_workflow (tenant_id,opportunity_id) WHERE previous_workflow_id IS NULL;\nCOMMENT ON INDEX contract.uq_preparation_workflow__root IS '商机合同流程单根。';\n"
    from .v950_r2_quote_runtime import _closure
    from .v900_r2_owner_exception import TASK_GUARDS
    old_purposes="('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY')"
    closure=_closure.replace(old_purposes,old_purposes[:-1]+','+','.join("'"+v+"'" for v in ALL_TASKS)+')')
    initial=TASK_GUARDS.split('CREATE TRIGGER')[0].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION').replace("NEW.business_purpose_code='PROGRESS_OPPORTUNITY'",'NEW.business_purpose_code IN '+sql_list(('PROGRESS_OPPORTUNITY','RECORD_QUOTE_REPLY',*SALES_TASKS)))
    sql+=closure+initial+GUARDS
    from .v970_r2_contract_preparation import SQL as preparation_sql
    decision='CREATE OR REPLACE FUNCTION contract.fn_check_preparation_decision()'+preparation_sql.split('CREATE FUNCTION contract.fn_check_preparation_decision()',1)[1].split('COMMENT ON FUNCTION',1)[0]
    lock=' SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=r.tenant_id AND opportunity_id=r.opportunity_id FOR UPDATE;'
    decision=decision.replace(lock,lock+"\n IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=r.tenant_id AND n.previous_request_id=r.preparation_request_id) THEN RAISE EXCEPTION 'pending preparation request is not current' USING ERRCODE='23514'; END IF;\n IF NEW.decision_code='RETURNED' THEN NEW.created_at=clock_timestamp();NEW.effective_from=NULL;RETURN NEW;END IF;")
    sql+=decision+"COMMENT ON FUNCTION contract.fn_check_preparation_decision() IS 'V980允许准确最新申请的非通过退回；批准仍复验全部当前依据。';\n"
    # Keep every legacy lifecycle condition, specializing only the named R2 insert path.
    base=_render_update_guards(before)
    a=base.index('CREATE FUNCTION platform_meta.fn_assert_contract_lifecycle()')
    b=base.index('COMMENT ON FUNCTION platform_meta.fn_assert_contract_lifecycle()',a)
    lifecycle=base[a:b].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION',1)
    start=lifecycle.index("        PERFORM 1",lifecycle.index("IF TG_OP = 'INSERT' THEN"))
    end=lifecycle.index('        IF NEW.current_revision_id',start)
    legacy=lifecycle[start:end]
    lifecycle=lifecycle[:start]+"        IF NEW.preparation_contract_code='R2_CONTRACT_PREPARATION_V1' THEN\n            PERFORM contract.fn_assert_r2_source(NEW.tenant_id,NEW.opportunity_id,NEW.accepted_quote_response_id,NEW.direct_preparation_decision_id,NULL,NULL);\n            IF EXISTS(SELECT 1 FROM contract.contract_revision v WHERE v.tenant_id=NEW.tenant_id AND v.contract_id<>NEW.contract_id AND ((NEW.accepted_quote_response_id IS NOT NULL AND v.source_quote_response_id=NEW.accepted_quote_response_id) OR (NEW.direct_preparation_decision_id IS NOT NULL AND v.source_direct_decision_id=NEW.direct_preparation_decision_id))) THEN RAISE EXCEPTION 'contract source already consumed by another contract' USING ERRCODE='23514'; END IF;\n        ELSE\n"+legacy+'        END IF;\n'+lifecycle[end:]
    lifecycle=lifecycle.replace('    IF NEW.contract_execution_id IS NOT NULL THEN',"    IF NEW.approved_revision_id IS NOT NULL AND NEW.approved_revision_id IS DISTINCT FROM OLD.approved_revision_id AND NEW.preparation_contract_code='R2_CONTRACT_PREPARATION_V1' THEN\n        PERFORM contract.fn_assert_r2_approval(NEW.tenant_id,NEW.contract_id,NEW.approved_revision_id);\n    END IF;\n    IF NEW.contract_execution_id IS NOT NULL THEN")
    a=base.index('CREATE FUNCTION platform_meta.fn_assert_contract_revision_package()')
    b=base.index('COMMENT ON FUNCTION platform_meta.fn_assert_contract_revision_package()',a)
    revision=base[a:b].replace('CREATE FUNCTION','CREATE OR REPLACE FUNCTION',1)
    revision=revision.replace('BEGIN\n',"BEGIN\n    IF NEW.package_contract_code='R2_CONTRACT_PREPARATION_V1' THEN\n        PERFORM contract.fn_assert_r2_version(NEW.tenant_id,NEW.contract_revision_id);\n        RETURN NEW;\n    END IF;\n    IF NEW.pre_contract_review_id IS NULL OR NEW.pre_contract_scope_hash IS NULL OR NEW.pre_contract_resolution_digest IS NULL THEN RAISE EXCEPTION 'legacy contract revision requires complete review' USING ERRCODE='23514'; END IF;\n",1)
    sql+=lifecycle+revision
    sql+='''DO $v980$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v12',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v11';
 IF NOT FOUND THEN RAISE EXCEPTION 'V980 requires 52-plus-2-r2-v11' USING ERRCODE='55000'; END IF;
END;
$v980$;
'''
    return sql

GUARDS=r'''
CREATE FUNCTION contract.fn_reject_r2_preparation_execution() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF EXISTS(SELECT 1 FROM contract.contract_revision r WHERE r.tenant_id=NEW.tenant_id AND r.contract_revision_id=NEW.contract_revision_id AND r.package_contract_code='R2_CONTRACT_PREPARATION_V1') THEN RAISE EXCEPTION 'R2 preparation requires a separately activated signing protocol' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_reject_r2_preparation_execution() IS '准备版本不能利用尚未形成旧签署计划的空集合直接执行；后续协议须具名激活。';
REVOKE ALL ON FUNCTION contract.fn_reject_r2_preparation_execution() FROM PUBLIC;
CREATE TRIGGER trg_contract_execution__r2_protocol BEFORE INSERT ON contract.contract_execution FOR EACH ROW EXECUTE FUNCTION contract.fn_reject_r2_preparation_execution();
COMMENT ON TRIGGER trg_contract_execution__r2_protocol ON contract.contract_execution IS 'T08只达到签署准备边界，不激活执行。';
CREATE FUNCTION contract.fn_assert_r2_material(t uuid,v uuid,sha bytea,o uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.material_version m JOIN evidence.received_source_object src ON src.tenant_id=m.tenant_id AND src.received_source_object_id=m.received_source_object_id JOIN evidence.evidence_binding b ON b.tenant_id=m.tenant_id AND b.evidence_binding_id=m.evidence_binding_id
 WHERE m.tenant_id=t AND m.material_version_id=v AND (o IS NULL OR m.opportunity_id=o) AND src.server_sha256=sha AND src.scan_result='PASSED' AND b.revoked_at IS NULL FOR SHARE OF b;
 IF NOT FOUND THEN RAISE EXCEPTION 'contract exact document unavailable' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_material(uuid,uuid,bytea,uuid) IS '真实准确文件、扫描和未撤回证据；原件字节不能由示例替代。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_material(uuid,uuid,bytea,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_source(t uuid,o uuid,q uuid,d uuid,c uuid,h bytea) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE source_customer uuid; req contract.preparation_request%ROWTYPE; dec contract.preparation_decision%ROWTYPE; opp opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO opp FROM opportunity.opportunity WHERE tenant_id=t AND opportunity_id=o FOR UPDATE;
 IF opp.opportunity_id IS NULL OR opp.closed_at IS NOT NULL OR num_nonnulls(q,d)<>1 THEN RAISE EXCEPTION 'contract source unavailable' USING ERRCODE='23514'; END IF;
 IF d IS NOT NULL THEN
  SELECT * INTO dec FROM contract.preparation_decision WHERE tenant_id=t AND preparation_decision_id=d;
  SELECT * INTO req FROM contract.preparation_request WHERE tenant_id=t AND preparation_request_id=dec.preparation_request_id;
  IF req.preparation_request_id IS NULL OR req.opportunity_id<>o OR dec.decision_code<>'APPROVED' OR req.opportunity_revision<>opp.revision OR dec.effective_from>clock_timestamp() OR (dec.effective_until IS NOT NULL AND dec.effective_until<=clock_timestamp()) OR (h IS NOT NULL AND req.commercial_digest<>h) OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=t AND n.previous_request_id=req.preparation_request_id) THEN RAISE EXCEPTION 'contract direct authorization changed' USING ERRCODE='23514'; END IF;
  IF req.responsibility_type='opportunity.opportunity' THEN
   IF req.responsibility_id<>o OR req.responsibility_revision<>opp.revision OR req.owner_appointment_id<>opp.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff x WHERE x.tenant_id=t AND x.opportunity_id=o) THEN RAISE EXCEPTION 'contract direct responsibility changed' USING ERRCODE='23514'; END IF;
  ELSIF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff x WHERE x.tenant_id=t AND x.responsibility_handoff_id=req.responsibility_id AND x.opportunity_id=o AND x.to_appointment_id=req.owner_appointment_id AND x.revision=req.responsibility_revision AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=t AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=x.responsibility_handoff_id)) THEN RAISE EXCEPTION 'contract direct responsibility changed' USING ERRCODE='23514'; END IF;
  source_customer=req.customer_confirmation_id;
 ELSE
  SELECT pb.customer_confirmation_id INTO source_customer
  FROM opportunity.quote_response r JOIN opportunity.quote_issue i ON i.tenant_id=r.tenant_id AND i.quote_issue_id=r.quote_issue_id
  JOIN opportunity.quote_revision v ON v.tenant_id=i.tenant_id AND v.quote_revision_id=i.quote_revision_id
  JOIN opportunity.quote_package_basis pb ON pb.tenant_id=v.tenant_id AND pb.quote_revision_id=v.quote_revision_id
  JOIN opportunity.contract_preparation_source s ON s.tenant_id=r.tenant_id AND s.quote_response_id=r.quote_response_id
  JOIN opportunity.quote_manual_delivery md ON md.tenant_id=i.tenant_id AND md.quote_manual_delivery_id=i.delivery_fact_id AND i.delivery_fact_type='opportunity.quote_manual_delivery'
  JOIN opportunity.material_version dm ON dm.tenant_id=md.tenant_id AND dm.material_version_id=md.material_version_id
  JOIN evidence.evidence_binding db ON db.tenant_id=dm.tenant_id AND db.evidence_binding_id=dm.evidence_binding_id
  JOIN opportunity.quote_response_basis rb ON rb.tenant_id=r.tenant_id AND rb.quote_response_id=r.quote_response_id
  JOIN opportunity.material_version rm ON rm.tenant_id=rb.tenant_id AND rm.material_version_id=rb.material_version_id
  JOIN evidence.evidence_binding eb ON eb.tenant_id=rm.tenant_id AND eb.evidence_binding_id=rm.evidence_binding_id
  WHERE r.tenant_id=t AND r.quote_response_id=q AND r.response_code='ACCEPTED' AND i.issue_status_code='ACTIVE' AND v.opportunity_id=o AND s.opportunity_id=o AND s.source_kind='ACCEPTED_QUOTE' AND v.package_contract_code='R2_QUOTE_PACKAGE_V1' AND v.package_contract_version=1 AND md.quote_revision_id=v.quote_revision_id AND md.occurred_at=i.issued_at AND i.delivery_fact_revision=0 AND dm.opportunity_id=o AND rm.opportunity_id=o AND rm.evidence_submission_id=r.evidence_submission_id
    AND r.received_at>=md.occurred_at AND r.created_at>=r.received_at AND r.created_at<=clock_timestamp() AND (v.valid_until IS NULL OR r.received_at<v.valid_until) AND db.revoked_at IS NULL AND eb.revoked_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_revision n WHERE n.tenant_id=t AND n.predecessor_quote_revision_id=v.quote_revision_id)
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_issue n WHERE n.tenant_id=t AND n.replaces_quote_issue_id=i.quote_issue_id)
    AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response n WHERE n.tenant_id=t AND n.quote_issue_id=i.quote_issue_id AND n.response_no>r.response_no)
  FOR UPDATE OF i,db,eb;
  IF source_customer IS NULL THEN RAISE EXCEPTION 'contract accepted source changed' USING ERRCODE='23514'; END IF;
 END IF;
 IF c IS NOT NULL AND c<>source_customer THEN RAISE EXCEPTION 'contract customer differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation x WHERE x.tenant_id=t AND x.customer_requirement_confirmation_id=source_customer AND x.opportunity_id=o AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=t AND n.previous_confirmation_id=x.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'contract customer changed' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM party.party p JOIN opportunity.customer_requirement_participant x ON x.tenant_id=p.tenant_id AND x.party_id=p.party_id WHERE x.tenant_id=t AND x.confirmation_id=source_customer ORDER BY p.party_id FOR UPDATE OF p;
 IF EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant x JOIN party.party p ON p.tenant_id=x.tenant_id AND p.party_id=x.party_id WHERE x.tenant_id=t AND x.confirmation_id=source_customer AND (p.revision<>x.party_revision OR p.status<>'ACTIVE')) THEN RAISE EXCEPTION 'contract party changed' USING ERRCODE='23514'; END IF;
 IF d IS NOT NULL AND dec.effective_until IS NOT NULL AND dec.effective_until<=clock_timestamp() THEN RAISE EXCEPTION 'contract authorization expired after lock' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea) IS '同商机双入口准确来源；合法历史接受不因当前自然到期失效。商业明文摘要仍由受信任Owner解密复验。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_version(t uuid,v uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE; root contract.contract%ROWTYPE; d contract.preparation_draft%ROWTYPE; tpl contract.template_version%ROWTYPE; cl record;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=v;
 SELECT * INTO root FROM contract.contract WHERE tenant_id=t AND contract_id=r.contract_id FOR UPDATE;
 IF r.contract_revision_id IS NULL OR root.current_revision_id IS DISTINCT FROM v OR root.preparation_contract_code IS DISTINCT FROM 'R2_CONTRACT_PREPARATION_V1' OR r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract preparation version must become current atomically' USING ERRCODE='23514'; END IF;
 SELECT * INTO d FROM contract.preparation_draft WHERE tenant_id=t AND preparation_draft_id=r.preparation_draft_id;
 IF d.preparation_draft_id IS NULL OR d.opportunity_id<>root.opportunity_id OR d.customer_confirmation_id<>r.customer_confirmation_id OR d.commercial_digest<>r.commercial_digest OR d.source_quote_response_id IS DISTINCT FROM r.source_quote_response_id OR d.source_direct_decision_id IS DISTINCT FROM r.source_direct_decision_id OR EXISTS(SELECT 1 FROM contract.preparation_draft n WHERE n.tenant_id=t AND n.previous_draft_id=d.preparation_draft_id) THEN RAISE EXCEPTION 'contract preparation draft differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.opportunity o WHERE o.tenant_id=t AND o.opportunity_id=root.opportunity_id AND o.revision=d.opportunity_revision AND ((d.responsibility_type='opportunity.opportunity' AND d.responsibility_id=o.opportunity_id AND d.responsibility_revision=o.revision AND d.owner_appointment_id=o.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=t AND h.opportunity_id=o.opportunity_id)) OR (d.responsibility_type='opportunity.responsibility_handoff' AND EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=t AND h.responsibility_handoff_id=d.responsibility_id AND h.opportunity_id=o.opportunity_id AND h.revision=d.responsibility_revision AND h.to_appointment_id=d.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=t AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id))))) THEN RAISE EXCEPTION 'contract draft responsibility changed' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_r2_source(t,root.opportunity_id,r.source_quote_response_id,r.source_direct_decision_id,r.customer_confirmation_id,r.commercial_digest);
 IF (SELECT count(*) FROM contract.contract_participation p WHERE p.tenant_id=t AND p.contract_revision_id=v)<>(SELECT count(*) FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=t AND p.confirmation_id=r.customer_confirmation_id) OR NOT EXISTS(SELECT 1 FROM contract.contract_participation p WHERE p.tenant_id=t AND p.contract_revision_id=v AND p.context_role_code='CLIENT') THEN RAISE EXCEPTION 'contract preparation participants differ' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM contract.contract c WHERE c.tenant_id=t AND c.contract_id<>root.contract_id AND ((r.source_quote_response_id IS NOT NULL AND c.accepted_quote_response_id=r.source_quote_response_id) OR (r.source_direct_decision_id IS NOT NULL AND c.direct_preparation_decision_id=r.source_direct_decision_id))) OR EXISTS(SELECT 1 FROM contract.contract_revision other WHERE other.tenant_id=t AND other.contract_id<>root.contract_id AND ((r.source_quote_response_id IS NOT NULL AND other.source_quote_response_id=r.source_quote_response_id) OR (r.source_direct_decision_id IS NOT NULL AND other.source_direct_decision_id=r.source_direct_decision_id))) THEN RAISE EXCEPTION 'contract source already consumed by another contract' USING ERRCODE='23514'; END IF;
 IF r.source_quote_response_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.quote_response q JOIN opportunity.quote_issue i ON i.tenant_id=q.tenant_id AND i.quote_issue_id=q.quote_issue_id WHERE q.tenant_id=t AND q.quote_response_id=r.source_quote_response_id AND i.quote_revision_id=r.source_quote_revision_id) THEN RAISE EXCEPTION 'contract quote version differs' USING ERRCODE='23514'; END IF;
 PERFORM contract.fn_assert_r2_material(t,r.body_evidence_version_id,r.body_sha256,root.opportunity_id);
 IF r.body_evidence_submission_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.material_version m WHERE m.tenant_id=t AND m.material_version_id=r.body_evidence_version_id AND m.evidence_submission_id=r.body_evidence_submission_id) THEN RAISE EXCEPTION 'contract body evidence differs' USING ERRCODE='23514'; END IF;
 SELECT * INTO tpl FROM contract.template_version WHERE tenant_id=t AND template_version_id=r.template_version_id;
 PERFORM contract.fn_assert_r2_material(t,tpl.evidence_version_id,tpl.body_sha256,NULL);
 FOR cl IN SELECT c.* FROM contract.revision_clause rc JOIN contract.clause_version c ON c.tenant_id=rc.tenant_id AND c.clause_version_id=rc.clause_version_id WHERE rc.tenant_id=t AND rc.contract_revision_id=v LOOP
  PERFORM contract.fn_assert_r2_material(t,cl.evidence_version_id,cl.body_sha256,NULL);
 END LOOP;
 IF EXISTS(SELECT 1 FROM contract.revision_approval_requirement req JOIN contract.approval_policy pol ON pol.tenant_id=req.tenant_id AND pol.approval_policy_id=req.policy_id JOIN identity.appointment a ON a.tenant_id=d.tenant_id AND a.appointment_id=d.owner_appointment_id WHERE req.tenant_id=t AND req.contract_revision_id=v AND pol.organization_unit_id<>a.organization_unit_id) THEN RAISE EXCEPTION 'contract approval organization differs' USING ERRCODE='23514'; END IF;
 IF (SELECT count(DISTINCT policy_id) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>1 OR EXISTS(SELECT 1 FROM contract.revision_approval_requirement req JOIN contract.approval_policy_member pm ON pm.tenant_id=req.tenant_id AND pm.policy_id=req.policy_id WHERE req.tenant_id=t AND req.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_approval_requirement rr WHERE rr.tenant_id=t AND rr.contract_revision_id=v AND rr.requirement_code=pm.requirement_code AND rr.approver_appointment_id=pm.appointment_id)) THEN RAISE EXCEPTION 'contract policy requirements incomplete' USING ERRCODE='23514'; END IF;
 IF (SELECT count(*) FROM contract.revision_clause WHERE tenant_id=t AND contract_revision_id=v)<>r.clause_count OR (SELECT count(*) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>r.approval_requirement_count THEN RAISE EXCEPTION 'contract sealed requirements incomplete' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_version(uuid,uuid) IS '准确合同准备包、真实审核文档与同事务封存审批集合。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_version(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_assert_r2_approval(t uuid,c uuid,v uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE; b contract.revision_review_binding%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=t AND contract_revision_id=v AND contract_id=c;
 SELECT * INTO b FROM contract.revision_review_binding WHERE tenant_id=t AND contract_revision_id=v;
 IF r.contract_revision_id IS NULL OR r.package_contract_code<>'R2_CONTRACT_PREPARATION_V1' OR b.revision_review_binding_id IS NULL OR (SELECT count(*) FROM contract.revision_approval_requirement WHERE tenant_id=t AND contract_revision_id=v)<>r.approval_requirement_count OR EXISTS(SELECT 1 FROM contract.revision_approval_requirement req WHERE req.tenant_id=t AND req.contract_revision_id=v AND NOT EXISTS(SELECT 1 FROM contract.revision_approval_decision d JOIN contract.revision_approval_request a ON a.tenant_id=d.tenant_id AND a.revision_approval_request_id=d.approval_request_id WHERE d.tenant_id=t AND d.requirement_id=req.revision_approval_requirement_id AND d.decision_code='APPROVED' AND d.review_binding_id=b.revision_review_binding_id AND a.contract_revision_id=v AND a.review_binding_id=b.revision_review_binding_id)) THEN RAISE EXCEPTION 'current contract approval incomplete' USING ERRCODE='23514'; END IF;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_assert_r2_approval(uuid,uuid,uuid) IS '本版准确冲突放行及完整审批集合；旧版批准不可复用。';
REVOKE ALL ON FUNCTION contract.fn_assert_r2_approval(uuid,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION contract.fn_check_r2_fact() RETURNS trigger
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
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence task WHERE task.tenant_id=NEW.tenant_id AND task.task_occurrence_id=NEW.prior_task_id AND task.state='CANCELLED' AND task.cancellation_reason_code='CONTRACT_AUTHORITY_MISSING' AND ((NEW.recovery_resume_stage='DIRECT_RETURNED' AND task.business_purpose_code='DECIDE_CONTRACT_PREPARATION') OR (NEW.recovery_resume_stage='RETURNED' AND task.business_purpose_code IN ('REVIEW_CONTRACT','APPROVE_CONTRACT')))) THEN RAISE EXCEPTION 'contract authority recovery basis differs' USING ERRCODE='23514'; END IF;
  IF NEW.recovery_resume_stage IS NOT NULL AND NOT EXISTS(SELECT 1 FROM contract.preparation_workflow p WHERE p.tenant_id=NEW.tenant_id AND p.preparation_workflow_id=NEW.previous_workflow_id AND ((p.task_id=NEW.prior_task_id AND ((p.stage_code='DIRECT_REVIEW' AND NEW.recovery_resume_stage='DIRECT_RETURNED') OR (p.stage_code IN ('AWAIT_REVIEW','AWAIT_APPROVAL') AND NEW.recovery_resume_stage='RETURNED'))) OR (p.stage_code='OWNER_EXCEPTION' AND p.recovery_resume_stage=NEW.recovery_resume_stage AND p.prior_task_id=NEW.prior_task_id))) THEN RAISE EXCEPTION 'contract authority recovery predecessor differs' USING ERRCODE='23514'; END IF;
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
COMMENT ON FUNCTION contract.fn_check_r2_fact() IS 'T08准确当前版本、事务封存、后置审查和审批，不伪造签署。';
REVOKE ALL ON FUNCTION contract.fn_check_r2_fact() FROM PUBLIC;

CREATE FUNCTION contract.fn_check_r2_participation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.contract_revision%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.contract_revision WHERE tenant_id=NEW.tenant_id AND contract_revision_id=NEW.contract_revision_id;
 IF r.package_contract_code='R2_CONTRACT_PREPARATION_V1' THEN
  IF r.created_in_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'contract preparation participants sealed' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=NEW.tenant_id AND p.confirmation_id=r.customer_confirmation_id AND p.party_id=NEW.party_id AND p.party_revision=NEW.party_revision AND p.role=NEW.context_role_code AND sha256(convert_to(p.profile_version_id::text,'UTF8'))=NEW.party_snapshot_digest) OR NEW.signature_required OR NEW.source_opportunity_participation_id IS NOT NULL THEN RAISE EXCEPTION 'contract preparation participants differ' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_r2_participation() IS '准备版本冻结准确确认参与方；签署计划另行激活。';
REVOKE ALL ON FUNCTION contract.fn_check_r2_participation() FROM PUBLIC;
CREATE TRIGGER trg_contract_participation__r2_basis BEFORE INSERT ON contract.contract_participation FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_participation();
COMMENT ON TRIGGER trg_contract_participation__r2_basis ON contract.contract_participation IS '准确确认参与方与版本形成事务。';
'''
for _name in (*[t.name for t in TABLES], 'preparation_decision', 'contract_revision', 'contract'):
    GUARDS+=f"CREATE TRIGGER trg_{_name}__r2_basis BEFORE INSERT ON contract.{_name} FOR EACH ROW EXECUTE FUNCTION contract.fn_check_r2_fact();\nCOMMENT ON TRIGGER trg_{_name}__r2_basis ON contract.{_name} IS '准确版本和形成事务守卫。';\n"
for _signature in ('fn_assert_r2_material(uuid,uuid,bytea,uuid)','fn_assert_r2_source(uuid,uuid,uuid,uuid,uuid,bytea)','fn_assert_r2_version(uuid,uuid)','fn_assert_r2_approval(uuid,uuid,uuid)'):
    GUARDS+=f'GRANT EXECUTE ON FUNCTION contract.{_signature} TO ${{app_command_role}};\n'
EVOLUTION=ContractEvolution(version=980,migration_name='V980__r2_contract_versions.sql',contract_version='52-plus-2-r2-v12',apply=apply_evolution,render_sql=render_sql)
