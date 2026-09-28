"""T07 protected quote drafts and exact basis; existing quote tables remain authoritative."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution, Column
from ..render import _render_table, _render_foreign_key
from .v920_r2_customer_requirements import fk, checks, body, basis, document_checks, document_fks

DRAFT=tenant_table('opportunity','quote_draft','quote_draft_id','不可变报价草稿；保存不完成任何待办。',(
 *basis(),uuid_col('customer_confirmation_id','准确已确认客户需求。'),uuid_col('previous_draft_id','同一责任人的前次草稿。',nullable=True),*body(),time_col('created_at','保存时间。')),
 constraints=(*document_checks('quote_draft'),unique('uq_quote_draft__previous',('tenant_id','previous_draft_id'),'草稿不分叉。')),
 foreign_keys=(*document_fks('quote_draft'),fk('quote_draft','customer_confirmation_id','opportunity','customer_requirement_confirmation'),fk('quote_draft','previous_draft_id','opportunity','quote_draft')))
PACKAGE=tenant_table('opportunity','quote_package_basis','quote_package_basis_id','既有报价版本的唯一受保护依据；不建立第二报价身份。',(
 revision_col(),Column('created_in_transaction','xid8',False,'由守卫写入报价包形成事务，防止后来追加子项。',default='pg_current_xact_id()'),uuid_col('quote_revision_id','唯一既有报价版本。'),uuid_col('quote_draft_id','准确被确认草稿。'),uuid_col('customer_confirmation_id','准确客户确认。'),*body(),time_col('created_at','形成时间。')),
 constraints=(*checks('quote_package_basis'),unique('uq_quote_package_basis__quote',('tenant_id','quote_revision_id'),'每个报价一个依据。'),unique('uq_quote_package_basis__draft',('tenant_id','quote_draft_id'),'草稿只能形成一个报价。'),check('ck_quote_package_basis__identity','quote_package_basis_id=quote_revision_id','同一报价身份。'),check('ck_quote_package_basis__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','受保护正文有界。')),
 foreign_keys=(fk('quote_package_basis','quote_revision_id','opportunity','quote_revision'),fk('quote_package_basis','quote_draft_id','opportunity','quote_draft'),fk('quote_package_basis','customer_confirmation_id','opportunity','customer_requirement_confirmation')))
TABLES=(DRAFT,PACKAGE)
def apply_evolution(schemas):
 return tuple(replace(s,tables=(*s.tables,*(t for t in TABLES if t.schema==s.name))) for s in schemas)
def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
 for t in TABLES:
  sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
  sql+=f"""CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON {t.schema}.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON {t.schema}.{t.name} IS '受保护报价事实不可修改或删除。';
REVOKE ALL ON {t.schema}.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON {t.schema}.{t.name} TO ${{app_command_role}};
GRANT SELECT ON {t.schema}.{t.name} TO ${{app_query_role}};
"""
 return sql+CONSISTENCY_SQL
CONSISTENCY_SQL=r'''
CREATE FUNCTION opportunity.fn_check_quote_draft() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; prior uuid;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision<>NEW.opportunity_revision THEN RAISE EXCEPTION 'quote opportunity differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>o.opportunity_id OR NEW.responsibility_revision<>o.revision OR NEW.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=o.opportunity_id) THEN RAISE EXCEPTION 'quote responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=o.opportunity_id AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'quote responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'quote customer confirmation differs' USING ERRCODE='23514'; END IF;
 SELECT d.quote_draft_id INTO prior FROM opportunity.quote_draft d WHERE d.tenant_id=NEW.tenant_id AND d.opportunity_id=o.opportunity_id AND d.responsibility_type=NEW.responsibility_type AND d.responsibility_id=NEW.responsibility_id AND d.responsibility_revision=NEW.responsibility_revision AND d.owner_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.quote_draft successor WHERE successor.tenant_id=d.tenant_id AND successor.previous_draft_id=d.quote_draft_id);
 IF prior IS DISTINCT FROM NEW.previous_draft_id THEN RAISE EXCEPTION 'quote draft predecessor differs' USING ERRCODE='23514'; END IF;
 NEW.created_at=clock_timestamp(); NEW.created_in_transaction=pg_current_xact_id(); RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_draft() IS '锁商机重验当前责任、客户确认和唯一草稿后继。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_draft() FROM PUBLIC;
CREATE TRIGGER trg_quote_draft__source BEFORE INSERT ON opportunity.quote_draft FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_draft();
COMMENT ON TRIGGER trg_quote_draft__source ON opportunity.quote_draft IS '准确草稿依据。';
CREATE FUNCTION opportunity.fn_check_quote_package_basis() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.quote_draft%ROWTYPE; o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO d FROM opportunity.quote_draft WHERE tenant_id=NEW.tenant_id AND quote_draft_id=NEW.quote_draft_id;
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=d.tenant_id AND opportunity_id=d.opportunity_id FOR UPDATE;
 IF d.quote_draft_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM d.opportunity_revision OR EXISTS(SELECT 1 FROM opportunity.quote_draft n WHERE n.tenant_id=d.tenant_id AND n.previous_draft_id=d.quote_draft_id) THEN RAISE EXCEPTION 'quote package source changed' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=d.tenant_id AND n.previous_confirmation_id=d.customer_confirmation_id) THEN RAISE EXCEPTION 'quote customer confirmation differs' USING ERRCODE='23514'; END IF;
 IF d.responsibility_type='opportunity.opportunity' THEN
  IF d.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=d.tenant_id AND h.opportunity_id=d.opportunity_id) THEN RAISE EXCEPTION 'quote responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=d.tenant_id AND h.responsibility_handoff_id=d.responsibility_id AND h.revision=d.responsibility_revision AND h.to_appointment_id=d.owner_appointment_id AND h.opportunity_id=d.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'quote responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.quote_revision q JOIN opportunity.quote_draft src ON src.tenant_id=q.tenant_id AND src.opportunity_id=q.opportunity_id WHERE q.tenant_id=NEW.tenant_id AND q.quote_revision_id=NEW.quote_revision_id AND src.quote_draft_id=NEW.quote_draft_id AND src.customer_confirmation_id=NEW.customer_confirmation_id AND q.created_by_appointment_id=src.owner_appointment_id AND q.package_contract_code='R2_QUOTE_PACKAGE_V1' AND q.package_contract_version=1 AND q.content_digest=NEW.body_digest) THEN RAISE EXCEPTION 'quote package differs' USING ERRCODE='23514'; END IF;
 NEW.created_in_transaction=pg_current_xact_id(); RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_quote_package_basis() IS '报价正文摘要及客户确认必须与准确草稿和原报价一致。';
REVOKE ALL ON FUNCTION opportunity.fn_check_quote_package_basis() FROM PUBLIC;
CREATE TRIGGER trg_quote_package_basis__source BEFORE INSERT ON opportunity.quote_package_basis FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_quote_package_basis();
COMMENT ON TRIGGER trg_quote_package_basis__source ON opportunity.quote_package_basis IS '准确报价依据守卫。';
CREATE FUNCTION opportunity.fn_require_r2_quote_package() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.package_contract_code='R2_QUOTE_PACKAGE_V1' AND (NOT EXISTS(SELECT 1 FROM opportunity.quote_package_basis b WHERE b.tenant_id=NEW.tenant_id AND b.quote_revision_id=NEW.quote_revision_id) OR NOT EXISTS(SELECT 1 FROM opportunity.quote_service_scope s WHERE s.tenant_id=NEW.tenant_id AND s.quote_revision_id=NEW.quote_revision_id) OR (SELECT sum(l.amount_minor) FROM opportunity.quote_line l WHERE l.tenant_id=NEW.tenant_id AND l.quote_revision_id=NEW.quote_revision_id) IS DISTINCT FROM NEW.total_minor OR NOT EXISTS(SELECT 1 FROM opportunity.quote_payment_term p WHERE p.tenant_id=NEW.tenant_id AND p.quote_revision_id=NEW.quote_revision_id)) THEN RAISE EXCEPTION 'incomplete R2 quote package' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_require_r2_quote_package() IS 'R2报价头、范围、明细、付款及受保护依据必须在同一事务完整写入。';
REVOKE ALL ON FUNCTION opportunity.fn_require_r2_quote_package() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_quote_revision__r2_package AFTER INSERT ON opportunity.quote_revision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_require_r2_quote_package();
COMMENT ON TRIGGER trg_quote_revision__r2_package ON opportunity.quote_revision IS 'R2报价提交前完整包守卫，旧报价不受影响。';
CREATE FUNCTION opportunity.fn_freeze_r2_quote_child() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE q opportunity.quote_revision%ROWTYPE; formed xid8;
BEGIN
 SELECT * INTO q FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND quote_revision_id=NEW.quote_revision_id;
 IF q.package_contract_code='R2_QUOTE_PACKAGE_V1' THEN
  SELECT created_in_transaction INTO formed FROM opportunity.quote_package_basis WHERE tenant_id=q.tenant_id AND quote_revision_id=q.quote_revision_id;
  IF formed IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'R2 quote child set frozen' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_freeze_r2_quote_child() IS 'R2报价子项只能在依据形成的顶层事务内追加，保存点不改变该边界。';
REVOKE ALL ON FUNCTION opportunity.fn_freeze_r2_quote_child() FROM PUBLIC;
CREATE TRIGGER trg_quote_service_scope__r2_frozen BEFORE INSERT ON opportunity.quote_service_scope FOR EACH ROW EXECUTE FUNCTION opportunity.fn_freeze_r2_quote_child();
COMMENT ON TRIGGER trg_quote_service_scope__r2_frozen ON opportunity.quote_service_scope IS '报价范围集合冻结。';
CREATE TRIGGER trg_quote_line__r2_frozen BEFORE INSERT ON opportunity.quote_line FOR EACH ROW EXECUTE FUNCTION opportunity.fn_freeze_r2_quote_child();
COMMENT ON TRIGGER trg_quote_line__r2_frozen ON opportunity.quote_line IS '报价计价集合冻结。';
CREATE TRIGGER trg_quote_payment_term__r2_frozen BEFORE INSERT ON opportunity.quote_payment_term FOR EACH ROW EXECUTE FUNCTION opportunity.fn_freeze_r2_quote_child();
COMMENT ON TRIGGER trg_quote_payment_term__r2_frozen ON opportunity.quote_payment_term IS '报价付款集合冻结。';
CREATE FUNCTION opportunity.fn_freeze_r2_quote_participant() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF EXISTS(SELECT 1 FROM opportunity.quote_revision q LEFT JOIN opportunity.quote_package_basis b ON b.tenant_id=q.tenant_id AND b.quote_revision_id=q.quote_revision_id WHERE q.tenant_id=NEW.tenant_id AND q.opportunity_id=NEW.opportunity_id AND q.participation_set_revision=NEW.participation_set_revision AND q.package_contract_code='R2_QUOTE_PACKAGE_V1' AND b.created_in_transaction IS DISTINCT FROM pg_current_xact_id()) THEN RAISE EXCEPTION 'R2 quote participant set frozen' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_freeze_r2_quote_participant() IS '已形成R2报价的参与方集合不可在后续事务追加成员。';
REVOKE ALL ON FUNCTION opportunity.fn_freeze_r2_quote_participant() FROM PUBLIC;
CREATE TRIGGER trg_opportunity_participation__r2_frozen BEFORE INSERT ON opportunity.opportunity_participation FOR EACH ROW EXECUTE FUNCTION opportunity.fn_freeze_r2_quote_participant();
COMMENT ON TRIGGER trg_opportunity_participation__r2_frozen ON opportunity.opportunity_participation IS '冻结报价准确参与方集合。';
DO $v940$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v8',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v7';
 IF NOT FOUND THEN RAISE EXCEPTION 'V940 requires 52-plus-2-r2-v7' USING ERRCODE='55000'; END IF;
END;
$v940$;
'''
EVOLUTION=ContractEvolution(version=940,migration_name='V940__r2_quotes.sql',contract_version='52-plus-2-r2-v8',apply=apply_evolution,render_sql=render_sql)
