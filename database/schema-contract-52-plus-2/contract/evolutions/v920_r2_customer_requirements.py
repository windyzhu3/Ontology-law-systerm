"""T05 immutable Party snapshots and owner-bound customer requirements versions."""
from dataclasses import replace
from ..helpers import *
from ..model import Column, ContractEvolution
from ..render import _render_table, _render_foreign_key

def fk(table, column, schema, target, target_id=None):
    return entity_fk(table,column,schema,target,target_id or target+'_id','同租户准确事实引用。',suffix=column)

def checks(name):
    return (check('ck_'+name+'__revision','revision=0','不可变保存版本。'),)

PROFILE=tenant_table('party','profile_version','profile_version_id','主体资料不可变版本；复用Party身份，不建立第二主体库。',(
    revision_col(),uuid_col('party_id','准确Party主体。'),bigint_col('party_revision','保存时准确主体版本。'),
    code_col('party_type','主体种类。',length=32),text_col('canonical_name','当时规范名称。'),
    uuid_col('created_by_appointment_id','实际维护者。'),time_col('created_at','资料版本形成时间。'),
),constraints=(*checks('profile_version'),unique('uq_profile_version__party_revision',('tenant_id','party_id','party_revision'),'主体准确版本唯一。'),
    check('ck_profile_version__party_revision','party_revision BETWEEN 0 AND 9007199254740991','准确主体版本。'),
    check('ck_profile_version__type',"party_type IN ('PERSON','ORGANIZATION')",'主体类型域。'),
    check('ck_profile_version__name','length(btrim(canonical_name)) BETWEEN 1 AND 300','必要名称范围。')),
    foreign_keys=(fk('profile_version','party_id','party','party'),fk('profile_version','created_by_appointment_id','identity','appointment')))

def basis():
    return (revision_col(),Column('created_in_transaction','xid8',False,'由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。',default='pg_current_xact_id()'),uuid_col('opportunity_id','准确商机。'),bigint_col('opportunity_revision','读取的商机版本。'),
        code_col('responsibility_type','责任依据类型。'),uuid_col('responsibility_id','责任依据身份。'),bigint_col('responsibility_revision','责任依据版本。'),
        uuid_col('owner_appointment_id','保存/确认的当前负责人。'))

def body():
    return (Column('body_ciphertext','bytea',False,'联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。'),digest_col('body_digest','受保护规范正文完整性摘要。'))

def document_checks(name):
    return (*checks(name),check('ck_'+name+'__basis',"responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991",'准确责任和版本。'),
        check('ck_'+name+'__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','有界加密正文。'))

def document_fks(name):
    return (fk(name,'opportunity_id','opportunity','opportunity'),fk(name,'owner_appointment_id','identity','appointment'))

DRAFT=tenant_table('opportunity','customer_requirement_draft','customer_requirement_draft_id','责任人独立不可变草稿；不依赖普通任务且不会成为确认事实。',(
    *basis(),uuid_col('previous_draft_id','准确前一保存；首次为空。',nullable=True),*body(),time_col('created_at','保存时间。'),
),constraints=(*document_checks('customer_requirement_draft'),
    unique('uq_customer_requirement_draft__previous',('tenant_id','previous_draft_id'),'同一保存版本只能有一个后继。')),
    foreign_keys=(*document_fks('customer_requirement_draft'),fk('customer_requirement_draft','previous_draft_id','opportunity','customer_requirement_draft')))

CONFIRMATION=tenant_table('opportunity','customer_requirement_confirmation','customer_requirement_confirmation_id','客户参与方及服务需求的不可变完整确认版本；不完成普通任务。',(
    *basis(),uuid_col('draft_id','准确不可变草稿。'),uuid_col('previous_confirmation_id','前一完整确认；首次为空。',nullable=True),
    *body(),time_col('confirmed_at','确认时间。'),
),constraints=(*document_checks('customer_requirement_confirmation'),
    unique('uq_customer_requirement_confirmation__draft',('tenant_id','draft_id'),'一份草稿至多确认一次。'),
    unique('uq_customer_requirement_confirmation__previous',('tenant_id','previous_confirmation_id'),'完整确认版本不允许分叉。')),
    foreign_keys=(*document_fks('customer_requirement_confirmation'),fk('customer_requirement_confirmation','draft_id','opportunity','customer_requirement_draft'),fk('customer_requirement_confirmation','previous_confirmation_id','opportunity','customer_requirement_confirmation')))

PARTICIPANT=tenant_table('opportunity','customer_requirement_participant','customer_requirement_participant_id','完整确认中的准确参与方；未知对方只存正文状态，不创建占位主体。',(
    revision_col(),uuid_col('confirmation_id','完整确认版本。'),uuid_col('party_id','准确主体。'),bigint_col('party_revision','当时主体版本。'),
    code_col('role','参与角色。',length=32),uuid_col('profile_version_id','准确不可变主体显示快照。'),
),constraints=(*checks('customer_requirement_participant'),
    unique('uq_customer_requirement_participant__role',('tenant_id','confirmation_id','party_id','role'),'集合中主体角色唯一。'),
    check('ck_customer_requirement_participant__role',"role IN ('CLIENT','OPPONENT','OTHER')",'具名参与方角色。'),
    check('ck_customer_requirement_participant__party_revision','party_revision BETWEEN 0 AND 9007199254740991','准确主体版本。')),
    foreign_keys=(fk('customer_requirement_participant','confirmation_id','opportunity','customer_requirement_confirmation'),fk('customer_requirement_participant','party_id','party','party'),fk('customer_requirement_participant','profile_version_id','party','profile_version')))

DRAFT_PARTY=tenant_table('opportunity','customer_requirement_draft_party','customer_requirement_draft_party_id','草稿准确主体来源集合；回执逐事实授权无需解密正文。',(
    revision_col(),uuid_col('draft_id','准确不可变草稿。'),uuid_col('party_id','人工选定的准确主体。'),bigint_col('party_revision','保存时准确主体版本。'),
),constraints=(*checks('customer_requirement_draft_party'),
    unique('uq_customer_requirement_draft_party__party',('tenant_id','draft_id','party_id'),'一份草稿每个主体来源唯一。'),
    check('ck_customer_requirement_draft_party__party_revision','party_revision BETWEEN 0 AND 9007199254740991','准确主体版本。')),
    foreign_keys=(fk('customer_requirement_draft_party','draft_id','opportunity','customer_requirement_draft'),fk('customer_requirement_draft_party','party_id','party','party')))

TABLES=(PROFILE,DRAFT,CONFIRMATION,PARTICIPANT,DRAFT_PARTY)
def apply_evolution(schemas):
    return tuple(replace(s,tables=(*s.tables,*(t for t in TABLES if t.schema==s.name))) for s in schemas)

def render_sql(before,after):
    sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
    for t in TABLES:
        sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
        sql+=f'''CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON {t.schema}.{t.name}
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON {t.schema}.{t.name} IS '不可变版本禁止改写或删除。';
REVOKE ALL ON {t.schema}.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON {t.schema}.{t.name} TO ${{app_command_role}};
GRANT SELECT ON {t.schema}.{t.name} TO ${{app_query_role}};
'''
    return sql+CONSISTENCY_SQL

CONSISTENCY_SQL=r'''
CREATE FUNCTION opportunity.fn_check_requirement_draft_party() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft d WHERE d.tenant_id=NEW.tenant_id AND d.customer_requirement_draft_id=NEW.draft_id AND d.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'draft party set already frozen' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.status='ACTIVE' FOR SHARE) THEN RAISE EXCEPTION 'draft party changed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_draft_party() IS '草稿来源在形成事务完整冻结，逐事实授权不解密正文。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_draft_party() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_draft_party__source BEFORE INSERT ON opportunity.customer_requirement_draft_party FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_draft_party();
COMMENT ON TRIGGER trg_customer_requirement_draft_party__source ON opportunity.customer_requirement_draft_party IS '准确主体版本与草稿同事务冻结。';

CREATE FUNCTION opportunity.fn_check_requirement_basis() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; d opportunity.customer_requirement_draft%ROWTYPE;
BEGIN
 NEW.created_in_transaction := pg_current_xact_id();
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM NEW.opportunity_revision THEN RAISE EXCEPTION 'requirement opportunity differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>NEW.opportunity_id OR NEW.responsibility_revision<>NEW.opportunity_revision OR NEW.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'requirement responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=NEW.opportunity_id AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'requirement handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='customer_requirement_draft' THEN
  IF NEW.previous_draft_id IS NOT NULL THEN
   SELECT * INTO d FROM opportunity.customer_requirement_draft WHERE tenant_id=NEW.tenant_id AND customer_requirement_draft_id=NEW.previous_draft_id;
   IF d.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR d.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR d.responsibility_type IS DISTINCT FROM NEW.responsibility_type OR d.responsibility_id IS DISTINCT FROM NEW.responsibility_id OR d.responsibility_revision IS DISTINCT FROM NEW.responsibility_revision THEN RAISE EXCEPTION 'draft predecessor differs' USING ERRCODE='23514'; END IF;
  ELSIF EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft x WHERE x.tenant_id=NEW.tenant_id AND x.opportunity_id=NEW.opportunity_id AND x.owner_appointment_id=NEW.owner_appointment_id AND x.responsibility_type=NEW.responsibility_type AND x.responsibility_id=NEW.responsibility_id AND x.responsibility_revision=NEW.responsibility_revision) THEN RAISE EXCEPTION 'draft initial version exists' USING ERRCODE='23514'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft x WHERE x.tenant_id=NEW.tenant_id AND x.previous_draft_id=NEW.draft_id) THEN RAISE EXCEPTION 'confirmation draft superseded' USING ERRCODE='23514'; END IF;
  IF NEW.previous_confirmation_id IS NULL AND EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'confirmation initial version exists' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_basis() IS '同商机锁核验当前责任、未关闭状态和不分叉草稿。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_basis() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_draft__basis BEFORE INSERT ON opportunity.customer_requirement_draft FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_basis();
COMMENT ON TRIGGER trg_customer_requirement_draft__basis ON opportunity.customer_requirement_draft IS '草稿必须属于当前有效责任。';
CREATE TRIGGER trg_customer_requirement_confirmation__basis BEFORE INSERT ON opportunity.customer_requirement_confirmation FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_basis();
COMMENT ON TRIGGER trg_customer_requirement_confirmation__basis ON opportunity.customer_requirement_confirmation IS '确认必须属于当前有效责任。';

CREATE FUNCTION party.fn_check_profile_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.party_type=NEW.party_type AND p.canonical_name=NEW.canonical_name AND p.status='ACTIVE') THEN RAISE EXCEPTION 'profile source differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION party.fn_check_profile_version() IS '形成时核验主体准确版本；历史不随当前资料改写。';
REVOKE ALL ON FUNCTION party.fn_check_profile_version() FROM PUBLIC;
CREATE TRIGGER trg_profile_version__source BEFORE INSERT ON party.profile_version FOR EACH ROW EXECUTE FUNCTION party.fn_check_profile_version();
COMMENT ON TRIGGER trg_profile_version__source ON party.profile_version IS '主体版本快照准确性。';

CREATE FUNCTION opportunity.fn_check_requirement_participant() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM party.profile_version p WHERE p.tenant_id=NEW.tenant_id AND p.profile_version_id=NEW.profile_version_id AND p.party_id=NEW.party_id AND p.party_revision=NEW.party_revision) THEN RAISE EXCEPTION 'participant profile differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.status='ACTIVE' FOR SHARE) THEN RAISE EXCEPTION 'participant party changed' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.confirmation_id AND c.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'participant set already frozen' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_participant() IS '参与方必须引用准确同租户主体快照。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_participant() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_participant__source BEFORE INSERT ON opportunity.customer_requirement_participant FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_participant();
COMMENT ON TRIGGER trg_customer_requirement_participant__source ON opportunity.customer_requirement_participant IS '准确主体版本核验。';

CREATE FUNCTION opportunity.fn_check_requirement_confirmation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.customer_requirement_draft%ROWTYPE;
BEGIN
 SELECT * INTO d FROM opportunity.customer_requirement_draft WHERE tenant_id=NEW.tenant_id AND customer_requirement_draft_id=NEW.draft_id;
 IF d.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR d.opportunity_revision IS DISTINCT FROM NEW.opportunity_revision OR d.responsibility_type IS DISTINCT FROM NEW.responsibility_type OR d.responsibility_id IS DISTINCT FROM NEW.responsibility_id OR d.responsibility_revision IS DISTINCT FROM NEW.responsibility_revision OR d.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id THEN RAISE EXCEPTION 'confirmation draft differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_confirmation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.previous_confirmation_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'confirmation predecessor differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=NEW.tenant_id AND p.confirmation_id=NEW.customer_requirement_confirmation_id AND p.role='CLIENT') THEN RAISE EXCEPTION 'confirmation requires client' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_confirmation() IS '同事务确认完整集合、准确草稿及前一版本。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_confirmation() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_customer_requirement_confirmation__complete AFTER INSERT ON opportunity.customer_requirement_confirmation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_confirmation();
COMMENT ON TRIGGER trg_customer_requirement_confirmation__complete ON opportunity.customer_requirement_confirmation IS '至少一个委托方与准确草稿同事务。';
DO $v920$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v6',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v5';
 IF NOT FOUND THEN RAISE EXCEPTION 'V920 requires 52-plus-2-r2-v5' USING ERRCODE='55000'; END IF;
END;
$v920$;
'''

EVOLUTION=ContractEvolution(version=920,migration_name='V920__r2_customer_requirements.sql',contract_version='52-plus-2-r2-v6',apply=apply_evolution,render_sql=render_sql)
