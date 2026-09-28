"""Explicit immutable top-level transaction provenance survives command savepoints."""
from dataclasses import replace
from ..model import Column, ContractEvolution
from ..render import _column_sql

TARGETS=('quote_approval_decision','quote_issue','quote_response')
MARKER=Column('created_in_transaction','xid8',False,'事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。',default='pg_current_xact_id()')

def apply_evolution(schemas):
    return tuple(replace(s,tables=tuple(replace(t,columns=(*t.columns,MARKER)) if s.name=='opportunity' and t.name in TARGETS else t for t in s.tables)) for s in schemas)

def render_sql(before,after):
    lines=['-- V960: immutable top-level transaction markers; existing facts receive the migration transaction.']
    for name in TARGETS:
        lines += [f'ALTER TABLE opportunity.{name} ADD COLUMN {_column_sql(MARKER)};',
            f"COMMENT ON COLUMN opportunity.{name}.created_in_transaction IS '{MARKER.comment}';"]
    lines += [r"""CREATE FUNCTION opportunity.fn_guard_quote_transaction_marker() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.created_in_transaction IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION 'quote transaction marker differs' USING ERRCODE='23514'; END IF;
 ELSIF NEW.created_in_transaction IS DISTINCT FROM OLD.created_in_transaction THEN
  RAISE EXCEPTION 'quote transaction marker immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_guard_quote_transaction_marker() IS '报价准确事实的顶层事务来源不可伪造或改写。';
REVOKE ALL ON FUNCTION opportunity.fn_guard_quote_transaction_marker() FROM PUBLIC;"""]
    for name in TARGETS:
        lines += [f'GRANT SELECT (created_in_transaction) ON opportunity.{name} TO ${{app_query_role}};',
            f'CREATE TRIGGER trg_{name}__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.{name} FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();',
            f"COMMENT ON TRIGGER trg_{name}__transaction_marker ON opportunity.{name} IS '报价事务来源写入及不可变校验。';"]
    lines += ["""DO $v960$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v10',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v9';
 IF NOT FOUND THEN RAISE EXCEPTION 'V960 requires 52-plus-2-r2-v9' USING ERRCODE='55000'; END IF;
END;
$v960$;"""]
    return '\n'.join(lines)

EVOLUTION=ContractEvolution(version=960,migration_name='V960__r2_quote_transaction.sql',contract_version='52-plus-2-r2-v10',apply=apply_evolution,render_sql=render_sql)
