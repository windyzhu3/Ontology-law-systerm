-- V960: immutable top-level transaction markers; existing facts receive the migration transaction.
ALTER TABLE opportunity.quote_approval_decision ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN opportunity.quote_approval_decision.created_in_transaction IS '事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。';
ALTER TABLE opportunity.quote_issue ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN opportunity.quote_issue.created_in_transaction IS '事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。';
ALTER TABLE opportunity.quote_response ADD COLUMN created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL;
COMMENT ON COLUMN opportunity.quote_response.created_in_transaction IS '事实形成的顶层事务；迁移前事实回填迁移事务，不冒充后续命令。';
CREATE FUNCTION opportunity.fn_guard_quote_transaction_marker() RETURNS trigger
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
REVOKE ALL ON FUNCTION opportunity.fn_guard_quote_transaction_marker() FROM PUBLIC;
GRANT SELECT (created_in_transaction) ON opportunity.quote_approval_decision TO ${app_query_role};
CREATE TRIGGER trg_quote_approval_decision__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.quote_approval_decision FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_quote_approval_decision__transaction_marker ON opportunity.quote_approval_decision IS '报价事务来源写入及不可变校验。';
GRANT SELECT (created_in_transaction) ON opportunity.quote_issue TO ${app_query_role};
CREATE TRIGGER trg_quote_issue__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.quote_issue FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_quote_issue__transaction_marker ON opportunity.quote_issue IS '报价事务来源写入及不可变校验。';
GRANT SELECT (created_in_transaction) ON opportunity.quote_response TO ${app_query_role};
CREATE TRIGGER trg_quote_response__transaction_marker BEFORE INSERT OR UPDATE ON opportunity.quote_response FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_quote_transaction_marker();
COMMENT ON TRIGGER trg_quote_response__transaction_marker ON opportunity.quote_response IS '报价事务来源写入及不可变校验。';
DO $v960$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v10',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v9';
 IF NOT FOUND THEN RAISE EXCEPTION 'V960 requires 52-plus-2-r2-v9' USING ERRCODE='55000'; END IF;
END;
$v960$;