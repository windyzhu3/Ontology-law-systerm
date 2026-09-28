"""T01 named owner-exception history and responsibility handoff successor."""
from dataclasses import replace
from ..helpers import *
from ..model import Column, ContractEvolution, Schema
from ..render import _render_table, _render_foreign_key, _render_indexes, _column_sql, _constraint_sql

MAX = 9007199254740991

def uid(n, optional=False): return uuid_col(n, 'T01同租户准确身份。', nullable=optional)
def rev(n, optional=False): return bigint_col(n, 'T01准确版本，JSON安全整数。', nullable=optional)
def tm(n, optional=False): return time_col(n, 'T01数据库业务时刻。', nullable=optional)
def ck(n,e): return check('ck_'+n,e,'T01具名事实一致性。')
def ef(t,c,s,p,pid=None): return entity_fk(t,c,s,p,pid or p+'_id','同租户身份存在性，准确版本由Owner复验。',suffix=c,deferrable=True,initially_deferred=True)

def bounded(table):
    return replace(table,constraints=(*table.constraints,*(ck(table.name+'__'+c.name+'_bound',f'{c.name} BETWEEN 0 AND {MAX}') for c in table.columns if c.name=='revision' or c.name.endswith('_revision'))))

EXCEPTION=bounded(tenant_table('opportunity','owner_exception','owner_exception_id','负责人异常版本：每次观察保留准确历史版本。',(
    revision_col(), bool_col('is_current','当前版本定位标记，仅允许退役。',default='true'),
    uid('opportunity_id'),rev('opportunity_revision'),Column('responsibility_slot','varchar(64)',False,'冻结责任槽。',"'OPPORTUNITY_OWNER'"),
    uid('frozen_owner_appointment_id'),uid('current_owner_appointment_id'),
    code_col('basis_type','当前有效责任依据类型。'),uid('basis_id'),rev('basis_revision'),
    uid('task_occurrence_id',True),rev('task_revision',True),uid('wait_receipt_id',True),digest_col('wait_hash','准确等待摘要。',nullable=True),
    Column('reason_codes','varchar(64)[]',False,'规范有序的异常原因集合。'),code_col('state','活动或终态。'),
    tm('first_observed_at'),tm('last_observed_at'),uid('last_disposition_id',True),tm('review_due_at',True),
    code_col('resolution_kind','解决类别。',nullable=True),code_col('resolution_type','准确解决事实类型。',nullable=True),uid('resolution_id',True),rev('resolution_revision',True),digest_col('resolution_hash','准确不可变验证审计摘要；与解决版本互斥。',nullable=True),
),constraints=(
    ck('owner_exception__slot',"responsibility_slot = 'OPPORTUNITY_OWNER'"),
    ck('owner_exception__state',"state IN ('ACTIVE','COORDINATING','RESOLVED','NO_LONGER_APPLICABLE')"),
    ck('owner_exception__basis',"basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')"),
    ck('owner_exception__times','last_observed_at >= first_observed_at'),
    ck('owner_exception__task','(task_occurrence_id IS NULL) = (task_revision IS NULL)'),
    ck('owner_exception__wait','(wait_receipt_id IS NULL) = (wait_hash IS NULL)'),
    ck('owner_exception__coordination',"state <> 'COORDINATING' OR (last_disposition_id IS NOT NULL AND review_due_at IS NOT NULL)"),
    ck('owner_exception__resolution',"(state IN ('ACTIVE','COORDINATING') AND resolution_kind IS NULL AND resolution_type IS NULL AND resolution_id IS NULL AND resolution_revision IS NULL AND resolution_hash IS NULL) OR (resolution_id IS NOT NULL AND resolution_kind IS NOT NULL AND resolution_type IS NOT NULL AND ((state='RESOLVED' AND resolution_kind='TRANSFER' AND resolution_type='opportunity.responsibility_handoff' AND resolution_revision IS NOT NULL AND resolution_revision=0 AND resolution_hash IS NULL) OR (state='RESOLVED' AND resolution_kind='OWNER_VALIDATED' AND resolution_type='audit.audit_entry' AND resolution_revision IS NULL AND resolution_hash IS NOT NULL) OR (state='NO_LONGER_APPLICABLE' AND resolution_kind='OPPORTUNITY_CLOSED' AND resolution_type='opportunity.opportunity' AND resolution_revision IS NOT NULL AND resolution_hash IS NULL)))"),
), indexes=(index('uq_owner_exception__current',('tenant_id','owner_exception_id'),'单当前版本。',unique_=True,where='is_current'),index('uq_owner_exception__active_slot',('tenant_id','opportunity_id','responsibility_slot'),'同商机责任槽至多一个活动异常周期。',unique_=True,where="is_current AND state IN ('ACTIVE','COORDINATING')")), foreign_keys=(
    ef('owner_exception','opportunity_id','opportunity','opportunity'),
    ef('owner_exception','frozen_owner_appointment_id','identity','appointment'),ef('owner_exception','current_owner_appointment_id','identity','appointment'),
    ef('owner_exception','task_occurrence_id','responsibility','task_occurrence'),ef('owner_exception','wait_receipt_id','responsibility','wait_receipt'),
    ef('owner_exception','last_disposition_id','opportunity','owner_exception_disposition'),
),update_policy='CONTROLLED',mutable_columns=('is_current',)))
EXCEPTION=replace(EXCEPTION,primary_key=('tenant_id','owner_exception_id','revision'))
DISPOSITION=bounded(tenant_table('opportunity','owner_exception_disposition','owner_exception_disposition_id','负责人异常处置：不可变人工决定。',(
    revision_col(),uid('owner_exception_id'),rev('owner_exception_revision'),code_col('kind','TRANSFER或COORDINATION。'),uid('actor_appointment_id'),text_col('reason','规范人工原因。'),tm('decided_at'),tm('review_due_at',True),uid('receiver_appointment_id',True),uid('responsibility_handoff_id',True),
),constraints=(ck('owner_exception_disposition__revision_zero','revision=0'),ck('owner_exception_disposition__reason','char_length(btrim(reason)) BETWEEN 1 AND 2000'),ck('owner_exception_disposition__kind',"(kind='COORDINATION' AND review_due_at IS NOT NULL AND review_due_at > decided_at AND receiver_appointment_id IS NULL AND responsibility_handoff_id IS NULL) OR (kind='TRANSFER' AND review_due_at IS NULL AND receiver_appointment_id IS NOT NULL AND responsibility_handoff_id IS NOT NULL)")),foreign_keys=(
    fk('fk_owner_exception_disposition__exact_exception',('tenant_id','owner_exception_id','owner_exception_revision'),'opportunity','owner_exception',('tenant_id','owner_exception_id','revision'),'准确异常历史版本。',deferrable=True,initially_deferred=True),
    ef('owner_exception_disposition','actor_appointment_id','identity','appointment'),ef('owner_exception_disposition','receiver_appointment_id','identity','appointment'),ef('owner_exception_disposition','responsibility_handoff_id','opportunity','responsibility_handoff'),
)))
HANDOFF=bounded(tenant_table('opportunity','responsibility_handoff','responsibility_handoff_id','商机责任交接：不可变唯一责任链。',(
    revision_col(),uid('opportunity_id'),rev('opportunity_revision'),code_col('prior_basis_type','准确前任责任类型。'),uid('prior_basis_id'),rev('prior_basis_revision'),uid('from_appointment_id'),uid('to_appointment_id'),uid('actor_appointment_id'),uid('owner_exception_disposition_id'),uid('old_task_occurrence_id',True),rev('old_task_revision',True),uid('new_task_occurrence_id'),rev('new_task_revision'),tm('original_due_at'),uid('original_wait_receipt_id',True),digest_col('original_wait_hash','准确原等待摘要。',nullable=True),tm('handed_off_at'),
),constraints=(ck('responsibility_handoff__revision_zero','revision=0'),unique('uq_responsibility_handoff__prior',('tenant_id','opportunity_id','prior_basis_type','prior_basis_id','prior_basis_revision'),'准确前任至多一个后继。'),unique('uq_responsibility_handoff__decision',('tenant_id','owner_exception_disposition_id'),'决定至多一个交接。'),ck('responsibility_handoff__basis',"prior_basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND prior_basis_id <> responsibility_handoff_id"),ck('responsibility_handoff__receiver','from_appointment_id <> to_appointment_id'),ck('responsibility_handoff__tasks','(old_task_occurrence_id IS NULL) = (old_task_revision IS NULL) AND (old_task_occurrence_id IS NULL OR old_task_occurrence_id <> new_task_occurrence_id)'),ck('responsibility_handoff__wait','(original_wait_receipt_id IS NULL) = (original_wait_hash IS NULL)')),
foreign_keys=(ef('responsibility_handoff','opportunity_id','opportunity','opportunity'),*(ef('responsibility_handoff',c,'identity','appointment') for c in ('from_appointment_id','to_appointment_id','actor_appointment_id')),ef('responsibility_handoff','owner_exception_disposition_id','opportunity','owner_exception_disposition'),ef('responsibility_handoff','old_task_occurrence_id','responsibility','task_occurrence'),ef('responsibility_handoff','new_task_occurrence_id','responsibility','task_occurrence'),ef('responsibility_handoff','original_wait_receipt_id','responsibility','wait_receipt'))))
HANDOFF=replace(HANDOFF,indexes=(index('uq_responsibility_handoff__initial',('tenant_id','opportunity_id'),'商机只能有一个首次交接。',unique_=True,where="prior_basis_type='opportunity.opportunity'"),),constraints=(*HANDOFF.constraints,unique('uq_responsibility_handoff__new_task',('tenant_id','new_task_occurrence_id'),'新任务只能由一个交接创建。')))
TABLES=(EXCEPTION,DISPOSITION,HANDOFF)

def apply_business_tables(schemas):
    return tuple(replace(s,tables=(*s.tables,*TABLES)) if s.name=='opportunity' else replace(s,tables=tuple(replace(t,constraints=tuple(replace(c,expression="scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION')") if c.name=='ck_r2_opportunity_checkpoint__kind' else c for c in t.constraints)) if t.name=='r2_opportunity_checkpoint' else t for t in s.tables)) if s.name=='platform_meta' else s for s in schemas)

def render_sql(base_schemas,evolved_schemas):
    lines=['-- V900: append-only exception versions and immutable human decisions; no grants to identities.']
    lines.extend(_render_table(t) for t in TABLES)
    for t in TABLES:
        lines.extend(_render_foreign_key(t,f) for f in t.foreign_keys)
    lines.append(_render_indexes((Schema('opportunity','T01',TABLES),)))
    for t in TABLES:
        q='opportunity.'+t.name
        lines += [f'REVOKE ALL ON {q} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};',f'GRANT SELECT, INSERT ON {q} TO ${{app_command_role}};',f'GRANT SELECT ({", ".join(c.name for c in t.columns)}) ON {q} TO ${{app_query_role}};']
        if t is not EXCEPTION:
            lines.append(f'CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON {q} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();')
    lines.append('GRANT UPDATE (is_current) ON opportunity.owner_exception TO ${app_command_role};')
    lines.append(render_task_extension(base_schemas,evolved_schemas))
    lines.append(HANDOFF_GUARDS)
    lines.append(GUARDS)
    sql='\n'.join(lines)
    import re
    for name in re.findall(r'CREATE FUNCTION ([a-z0-9_]+\.[a-z0-9_]+)\(',sql):
        sql += f"\nCOMMENT ON FUNCTION {name}() IS 'T01具名事实守卫。';"
    for name,table in re.findall(r'CREATE (?:CONSTRAINT )?TRIGGER ([a-z_]+).*? ON ([a-z_]+\.[a-z_]+)',sql):
        sql += f"\nCOMMENT ON TRIGGER {name} ON {table} IS 'T01具名事实一致性守卫。';"
    return sql

GUARDS=r'''
CREATE FUNCTION opportunity.fn_guard_owner_exception_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE prior opportunity.owner_exception%ROWTYPE; canonical varchar(64)[];
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'exception history deletion forbidden' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NOT OLD.is_current OR NEW.is_current OR (to_jsonb(NEW)-'is_current') IS DISTINCT FROM (to_jsonb(OLD)-'is_current') THEN
   RAISE EXCEPTION 'only current version retirement permitted' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NOT NEW.is_current THEN RAISE EXCEPTION 'new version must be current' USING ERRCODE='23514'; END IF;
 SELECT array_agg(x ORDER BY n) INTO canonical FROM unnest(ARRAY['OWNER_INACTIVE','OWNER_AUTHORITY_MISSING','OWNER_DENIED','SUPERVISOR_UNRESOLVED','SOURCE_INCONSISTENT']::varchar(64)[]) WITH ORDINALITY a(x,n) WHERE x=ANY(NEW.reason_codes);
 IF cardinality(NEW.reason_codes) NOT BETWEEN 1 AND 5 OR NEW.reason_codes IS DISTINCT FROM canonical THEN RAISE EXCEPTION 'noncanonical reason set' USING ERRCODE='23514'; END IF;
 SELECT * INTO prior FROM opportunity.owner_exception WHERE tenant_id=NEW.tenant_id AND owner_exception_id=NEW.owner_exception_id ORDER BY revision DESC LIMIT 1;
 IF FOUND THEN
  IF prior.is_current OR prior.state IN ('RESOLVED','NO_LONGER_APPLICABLE') OR prior.revision >= 9007199254740991 OR NEW.revision <> prior.revision+1 OR
   ROW(NEW.opportunity_id,NEW.opportunity_revision,NEW.responsibility_slot,NEW.frozen_owner_appointment_id,NEW.first_observed_at) IS DISTINCT FROM ROW(prior.opportunity_id,prior.opportunity_revision,prior.responsibility_slot,prior.frozen_owner_appointment_id,prior.first_observed_at) OR NEW.last_observed_at < prior.last_observed_at THEN
   RAISE EXCEPTION 'invalid exception version successor' USING ERRCODE='23514';
  END IF;
  IF prior.state='COORDINATING' AND NEW.state='ACTIVE' AND NEW.last_observed_at < prior.review_due_at THEN RAISE EXCEPTION 'coordination review not due' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.revision<>0 OR NEW.state<>'ACTIVE' THEN RAISE EXCEPTION 'exception initial version must be ACTIVE zero' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_owner_exception__mutation_guard BEFORE INSERT OR UPDATE OR DELETE ON opportunity.owner_exception FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_owner_exception_version();
CREATE FUNCTION opportunity.fn_check_owner_exception_current() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.owner_exception WHERE tenant_id=NEW.tenant_id AND owner_exception_id=NEW.owner_exception_id AND is_current) THEN RAISE EXCEPTION 'exception must retain one current version' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
CREATE CONSTRAINT TRIGGER trg_owner_exception__current_required AFTER INSERT OR UPDATE ON opportunity.owner_exception DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_owner_exception_current();
REVOKE ALL ON FUNCTION opportunity.fn_guard_owner_exception_version() FROM PUBLIC;
REVOKE ALL ON FUNCTION opportunity.fn_check_owner_exception_current() FROM PUBLIC;
ALTER TABLE platform_meta.r2_opportunity_checkpoint DROP CONSTRAINT ck_r2_opportunity_checkpoint__kind;
ALTER TABLE platform_meta.r2_opportunity_checkpoint ADD CONSTRAINT ck_r2_opportunity_checkpoint__kind CHECK (scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION'));
DO $v900$
DECLARE actual_count bigint;
BEGIN
 SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables WHERE schemaname IN ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta');
 IF actual_count<>58 THEN RAISE EXCEPTION 'V900 expected 58 tables, found %',actual_count; END IF;
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v4',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v3';
 IF NOT FOUND THEN RAISE EXCEPTION 'V900 requires schema contract 52-plus-2-r2-v3' USING ERRCODE='55000'; END IF;
END;
$v900$;
'''
BASIS=typed_ref('responsibility_basis','当前有效责任依据',optional=True)
CANCELLATION=typed_ref('cancellation_fact','交接取消依据',optional=True)
HANDOFF_PREDECESSOR=uid('handoff_predecessor_task_occurrence_id',True)
WAIT_COLUMNS=(uid('handoff_fact_id',True),rev('handoff_fact_revision',True),uid('inherited_wait_receipt_id',True),digest_col('inherited_wait_hash','准确原等待摘要。',nullable=True),uid('origin_progress_id',True),digest_col('origin_progress_hash','准确原进展摘要。',nullable=True),tm('original_sla_due_at',True))

def apply_evolution(schemas):
    def evolve(t):
        if (t.schema,t.name)==('responsibility','task_occurrence'):
            added=(*BASIS.columns,*CANCELLATION.columns,HANDOFF_PREDECESSOR)
            return replace(t,columns=(*t.columns,*added),typed_references=(*t.typed_references,BASIS,CANCELLATION),constraints=(*t.constraints,typed_ref_check(t.name,BASIS),typed_ref_check(t.name,CANCELLATION),*digest_checks(t.name,added),ck('task_occurrence__handoff_predecessor',"handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code='PROGRESS_OPPORTUNITY' AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff')"),ck('task_occurrence__handoff_cancellation',"cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0)")),foreign_keys=(*t.foreign_keys,ef(t.name,HANDOFF_PREDECESSOR.name,'responsibility','task_occurrence')),mutable_columns=(*t.mutable_columns,*(c.name for c in CANCELLATION.columns)),write_once_columns=(*t.write_once_columns,*(c.name for c in CANCELLATION.columns)))
        if (t.schema,t.name)==('responsibility','wait_receipt'):
            return replace(t,columns=(*t.columns,*WAIT_COLUMNS),constraints=(*(replace(c,expression="resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1'") if c.name=='ck_wait_receipt__resume_after_entry' else replace(c,expression="task_revision > 0 OR (wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND task_revision=0)") if c.name=='ck_wait_receipt__positive_task_revision' else c for c in t.constraints),*digest_checks(t.name,WAIT_COLUMNS),ck('wait_receipt__handoff_shape',"(wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code<>'R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL)")),foreign_keys=(*t.foreign_keys,ef(t.name,'handoff_fact_id','opportunity','responsibility_handoff'),ef(t.name,'inherited_wait_receipt_id','responsibility','wait_receipt'),ef(t.name,'origin_progress_id','opportunity','opportunity_progress')))
        return t
    return tuple(replace(s,tables=tuple(evolve(t) for t in s.tables)) for s in apply_business_tables(schemas))

def render_task_extension(old,new):
    before={f'{s.name}.{t.name}':t for s in old for t in s.tables};after={f'{s.name}.{t.name}':t for s in new for t in s.tables}
    lines=[]
    for q in ('responsibility.task_occurrence','responsibility.wait_receipt'):
        a,b=before[q],after[q]
        for c in b.columns[len(a.columns):]:
            lines.extend([f'ALTER TABLE {q} ADD COLUMN {_column_sql(c)};',f"COMMENT ON COLUMN {q}.{c.name} IS '{c.comment}';",f'GRANT SELECT ({c.name}) ON {q} TO ${{app_query_role}};'])
        lines.extend(f'ALTER TABLE {q} ADD {_constraint_sql(c)};' for c in b.constraints[len(a.constraints):])
        lines.extend(_render_foreign_key(b,f) for f in b.foreign_keys[len(a.foreign_keys):])
    lines.extend(["ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__resume_after_entry;","ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__resume_after_entry CHECK (resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1');"])
    lines.extend(["ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__positive_task_revision;","ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__positive_task_revision CHECK (task_revision > 0 OR (wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND task_revision=0));"])
    t=after['responsibility.task_occurrence']
    mutable=','.join(t.mutable_columns);once=','.join(t.write_once_columns);transitions=','.join(a+'>'+b for a,b in t.state_transitions)
    lines.extend(['DROP TRIGGER trg_task_occurrence__mutation_guard ON responsibility.task_occurrence;',f"CREATE TRIGGER trg_task_occurrence__mutation_guard BEFORE UPDATE OR DELETE ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('{mutable}','{once}','state','{transitions}','CONTROLLED');",'GRANT UPDATE (cancellation_fact_type,cancellation_fact_id,cancellation_fact_revision,cancellation_fact_hash) ON responsibility.task_occurrence TO ${app_command_role};','DROP TRIGGER trg_task_occurrence__initial_state ON responsibility.task_occurrence;',TASK_GUARDS])
    return '\n'.join(lines)

TASK_GUARDS=r'''CREATE FUNCTION responsibility.fn_guard_r2_handoff_task_initial() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.revision<>0 OR (NEW.state='OPEN' OR (NEW.state='WAITING' AND NEW.business_purpose_code='PROGRESS_OPPORTUNITY' AND NEW.responsibility_basis_type='opportunity.responsibility_handoff' AND NEW.responsibility_basis_revision=0 AND NEW.handoff_predecessor_task_occurrence_id IS NOT NULL)) IS NOT TRUE THEN RAISE EXCEPTION 'invalid task initial state' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_task_occurrence__initial_state BEFORE INSERT ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION responsibility.fn_guard_r2_handoff_task_initial();
REVOKE ALL ON FUNCTION responsibility.fn_guard_r2_handoff_task_initial() FROM PUBLIC;
'''

HANDOFF_GUARDS=r'''CREATE FUNCTION opportunity.fn_guard_responsibility_handoff_prior() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NEW.prior_basis_type='opportunity.responsibility_handoff' AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND responsibility_handoff_id=NEW.prior_basis_id AND opportunity_id=NEW.opportunity_id AND revision=NEW.prior_basis_revision) THEN RAISE EXCEPTION 'handoff predecessor must already exist' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_responsibility_handoff__prior BEFORE INSERT ON opportunity.responsibility_handoff FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_responsibility_handoff_prior();
REVOKE ALL ON FUNCTION opportunity.fn_guard_responsibility_handoff_prior() FROM PUBLIC;
CREATE FUNCTION opportunity.fn_check_responsibility_handoff() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.owner_exception_disposition%ROWTYPE; predecessor opportunity.responsibility_handoff%ROWTYPE; original_owner uuid;
BEGIN
 SELECT owner_appointment_id INTO original_owner FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NEW.prior_basis_type='opportunity.opportunity' THEN
  IF NEW.prior_basis_id<>NEW.opportunity_id OR NEW.prior_basis_revision<>NEW.opportunity_revision OR NEW.from_appointment_id IS DISTINCT FROM original_owner THEN RAISE EXCEPTION 'invalid initial handoff basis' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO predecessor FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND responsibility_handoff_id=NEW.prior_basis_id;
  IF NOT FOUND OR predecessor.opportunity_id<>NEW.opportunity_id OR predecessor.revision<>NEW.prior_basis_revision OR predecessor.to_appointment_id<>NEW.from_appointment_id OR predecessor.handed_off_at>NEW.handed_off_at THEN RAISE EXCEPTION 'invalid prior handoff basis' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT * INTO d FROM opportunity.owner_exception_disposition WHERE tenant_id=NEW.tenant_id AND owner_exception_disposition_id=NEW.owner_exception_disposition_id;
 IF NOT FOUND OR d.kind<>'TRANSFER' OR d.responsibility_handoff_id<>NEW.responsibility_handoff_id OR d.receiver_appointment_id<>NEW.to_appointment_id OR d.actor_appointment_id<>NEW.actor_appointment_id THEN RAISE EXCEPTION 'handoff decision mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.owner_exception e WHERE e.tenant_id=NEW.tenant_id AND e.owner_exception_id=d.owner_exception_id AND e.revision=d.owner_exception_revision AND e.opportunity_id=NEW.opportunity_id AND e.state IN ('ACTIVE','COORDINATING') AND e.basis_type=NEW.prior_basis_type AND e.basis_id=NEW.prior_basis_id AND e.basis_revision=NEW.prior_basis_revision) THEN RAISE EXCEPTION 'handoff exception basis mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.new_task_occurrence_id AND t.revision=NEW.new_task_revision AND t.owner_appointment_id=NEW.to_appointment_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=NEW.opportunity_id AND t.responsibility_basis_type='opportunity.responsibility_handoff' AND t.responsibility_basis_id=NEW.responsibility_handoff_id AND t.responsibility_basis_revision=0 AND t.original_sla_due_at=NEW.original_due_at AND t.handoff_predecessor_task_occurrence_id IS NOT DISTINCT FROM NEW.old_task_occurrence_id AND t.state=CASE WHEN NEW.original_wait_receipt_id IS NULL THEN 'OPEN' ELSE 'WAITING' END) THEN RAISE EXCEPTION 'handoff successor task mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.old_task_occurrence_id AND t.revision=NEW.old_task_revision+1 AND t.state='CANCELLED' AND t.owner_appointment_id=NEW.from_appointment_id AND t.original_sla_due_at=NEW.original_due_at AND t.cancellation_fact_type='opportunity.responsibility_handoff' AND t.cancellation_fact_id=NEW.responsibility_handoff_id AND t.cancellation_fact_revision=0 AND t.completion_fact_type IS NULL) THEN RAISE EXCEPTION 'handoff cancellation mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.original_wait_receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w JOIN responsibility.wait_receipt original ON original.tenant_id=w.tenant_id AND original.wait_receipt_id=NEW.original_wait_receipt_id WHERE w.tenant_id=NEW.tenant_id AND w.task_occurrence_id=NEW.new_task_occurrence_id AND w.wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND w.handoff_fact_id=NEW.responsibility_handoff_id AND w.handoff_fact_revision=0 AND w.inherited_wait_receipt_id=NEW.original_wait_receipt_id AND w.inherited_wait_hash=NEW.original_wait_hash AND w.resume_due_at IS NOT DISTINCT FROM original.resume_due_at AND w.original_sla_due_at=NEW.original_due_at AND original.task_occurrence_id=NEW.old_task_occurrence_id) THEN RAISE EXCEPTION 'handoff wait inheritance mismatch' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
CREATE CONSTRAINT TRIGGER trg_responsibility_handoff__facts AFTER INSERT ON opportunity.responsibility_handoff DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_responsibility_handoff();
REVOKE ALL ON FUNCTION opportunity.fn_check_responsibility_handoff() FROM PUBLIC;
'''
EVOLUTION=ContractEvolution(version=900,migration_name='V900__r2_owner_exception.sql',contract_version='52-plus-2-r2-v4',apply=apply_evolution,render_sql=render_sql)
