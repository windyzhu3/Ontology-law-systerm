"""Immutable direct-contract applications and decisions; existing contract guards unchanged."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key, _render_indexes
from .v920_r2_customer_requirements import basis, body, document_checks, document_fks, fk, checks

REQUEST=tenant_table('contract','preparation_request','preparation_request_id','直接准备合同的准确申请；不是报价接受、合同或批准。',(
    *basis(),uuid_col('customer_confirmation_id','准确客户需求确认。'),digest_col('commercial_digest','已申请服务范围及费用付款条款的规范摘要。'),
    uuid_col('previous_request_id','本商机的准确前次申请；旧决定不适用于新申请。',nullable=True),*body(),time_col('created_at','服务端形成时间。')),
    constraints=(*document_checks('preparation_request'),unique('uq_preparation_request__previous',('tenant_id','previous_request_id'),'申请单后继。')),
    indexes=(index('uq_preparation_request__root',('tenant_id','opportunity_id'),'每商机只有一个首申请；不依赖事务快照刷新。',unique_=True,where='previous_request_id IS NULL'),),
    foreign_keys=(*document_fks('preparation_request'),fk('preparation_request','customer_confirmation_id','opportunity','customer_requirement_confirmation'),fk('preparation_request','previous_request_id','contract','preparation_request')))
DECISION=tenant_table('contract','preparation_decision','preparation_decision_id','准确申请的不可变决定；仍需命令运行时授权，不代表合同审批。',(
    revision_col(),uuid_col('preparation_request_id','被决定的唯一准确申请。'),code_col('decision_code','批准或退回。'),uuid_col('decided_by_appointment_id','实际有权决定任职。'),
    *body(),time_col('effective_from','批准生效起点；由服务端决定时间写入。',nullable=True),time_col('effective_until','批准自然到期；空表示未设自然到期。',nullable=True),time_col('created_at','服务端决定时间。')),
    constraints=(*checks('preparation_decision'),unique('uq_preparation_decision__request',('tenant_id','preparation_request_id'),'每份申请只有一个最终决定。'),
        check('ck_preparation_decision__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','决定说明有界密文。'),
        check('ck_preparation_decision__shape',"(decision_code='APPROVED' AND effective_from IS NOT NULL AND (effective_until IS NULL OR effective_until>effective_from)) OR (decision_code='RETURNED' AND effective_from IS NULL AND effective_until IS NULL)",'退回没有授权区间。')),
    foreign_keys=(fk('preparation_decision','preparation_request_id','contract','preparation_request'),fk('preparation_decision','decided_by_appointment_id','identity','appointment')))
TABLES=(REQUEST,DECISION)

def apply_evolution(schemas):
    return tuple(replace(s,tables=(*s.tables,*TABLES)) if s.name=='contract' else s for s in schemas)

def render_sql(before,after):
    sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
    for t in TABLES:
        sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
        sql+=f"""CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON contract.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON contract.{t.name} IS '准确申请和决定不可改写。';
REVOKE ALL ON contract.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON contract.{t.name} TO ${{app_command_role}};
GRANT SELECT ON contract.{t.name} TO ${{app_query_role}};
"""
    return sql+_render_indexes(tuple(replace(s,tables=TABLES) for s in after if s.name=='contract'))+SQL

SQL=r'''
CREATE FUNCTION contract.fn_check_preparation_request() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; prior uuid;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision<>NEW.opportunity_revision THEN RAISE EXCEPTION 'request basis changed' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>o.opportunity_id OR NEW.responsibility_revision<>o.revision OR NEW.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.opportunity_id=o.opportunity_id) THEN RAISE EXCEPTION 'request basis changed' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=o.opportunity_id AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'request basis changed' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=c.tenant_id AND n.previous_confirmation_id=c.customer_requirement_confirmation_id)) THEN RAISE EXCEPTION 'request customer confirmation changed' USING ERRCODE='23514'; END IF;
 SELECT r.preparation_request_id INTO prior FROM contract.preparation_request r WHERE r.tenant_id=NEW.tenant_id AND r.opportunity_id=o.opportunity_id AND NOT EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=r.tenant_id AND n.previous_request_id=r.preparation_request_id);
 IF prior IS DISTINCT FROM NEW.previous_request_id THEN RAISE EXCEPTION 'request predecessor differs' USING ERRCODE='23514'; END IF;
 NEW.created_at=clock_timestamp();NEW.created_in_transaction=pg_current_xact_id();RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_preparation_request() IS '锁商机并重验准确责任、当前客户确认和唯一申请后继；不代替命令授权。';
REVOKE ALL ON FUNCTION contract.fn_check_preparation_request() FROM PUBLIC;
CREATE TRIGGER trg_preparation_request__basis BEFORE INSERT ON contract.preparation_request FOR EACH ROW EXECUTE FUNCTION contract.fn_check_preparation_request();
COMMENT ON TRIGGER trg_preparation_request__basis ON contract.preparation_request IS '申请准确来源及唯一后继。';

CREATE FUNCTION contract.fn_check_preparation_decision() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE r contract.preparation_request%ROWTYPE; o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO r FROM contract.preparation_request WHERE tenant_id=NEW.tenant_id AND preparation_request_id=NEW.preparation_request_id;
 IF r.preparation_request_id IS NULL THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=r.tenant_id AND opportunity_id=r.opportunity_id FOR UPDATE;
 IF o.closed_at IS NOT NULL OR o.revision<>r.opportunity_revision OR EXISTS(SELECT 1 FROM contract.preparation_request n WHERE n.tenant_id=r.tenant_id AND n.previous_request_id=r.preparation_request_id) OR EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation n WHERE n.tenant_id=r.tenant_id AND n.previous_confirmation_id=r.customer_confirmation_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 IF r.responsibility_type='opportunity.opportunity' THEN
  IF o.owner_appointment_id<>r.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=r.tenant_id AND h.opportunity_id=r.opportunity_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=r.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=r.responsibility_id) THEN RAISE EXCEPTION 'decision source changed' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.created_at=clock_timestamp();
 IF NEW.decision_code='APPROVED' THEN NEW.effective_from=NEW.created_at; ELSE NEW.effective_from=NULL; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION contract.fn_check_preparation_decision() IS '对当前准确申请作决定；批准时间由数据库给出，退回不产生授权区间。';
REVOKE ALL ON FUNCTION contract.fn_check_preparation_decision() FROM PUBLIC;
CREATE TRIGGER trg_preparation_decision__basis BEFORE INSERT ON contract.preparation_decision FOR EACH ROW EXECUTE FUNCTION contract.fn_check_preparation_decision();
COMMENT ON TRIGGER trg_preparation_decision__basis ON contract.preparation_decision IS '准确申请的决定及自然有效期。';
DO $v970$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v11',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v10';
 IF NOT FOUND THEN RAISE EXCEPTION 'V970 requires 52-plus-2-r2-v10' USING ERRCODE='55000'; END IF;
END;
$v970$;
'''
EVOLUTION=ContractEvolution(version=970,migration_name='V970__r2_contract_preparation.sql',contract_version='52-plus-2-r2-v11',apply=apply_evolution,render_sql=render_sql)
