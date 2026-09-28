CREATE TABLE contract.preparation_request (
    tenant_id uuid NOT NULL,
    preparation_request_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    owner_appointment_id uuid NOT NULL,
    customer_confirmation_id uuid NOT NULL,
    commercial_digest bytea NOT NULL,
    previous_request_id uuid,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_preparation_request PRIMARY KEY (tenant_id, preparation_request_id),
    CONSTRAINT ck_preparation_request__revision CHECK (revision=0),
    CONSTRAINT ck_preparation_request__basis CHECK (responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_preparation_request__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_preparation_request__previous UNIQUE (tenant_id, previous_request_id),
    CONSTRAINT ck_preparation_request__commercial_digest_length CHECK (octet_length(commercial_digest) = 32),
    CONSTRAINT ck_preparation_request__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.preparation_request IS 'Fact Owner：ContractRuntime；直接准备合同的准确申请；不是报价接受、合同或批准。';
COMMENT ON CONSTRAINT pk_preparation_request ON contract.preparation_request IS '主键：在租户内唯一标识一条preparation_request记录。';
COMMENT ON INDEX contract.pk_preparation_request IS '主键：在租户内唯一标识一条preparation_request记录。';
COMMENT ON COLUMN contract.preparation_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.preparation_request.preparation_request_id IS '直接准备合同的准确申请；不是报价接受、合同或批准。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.preparation_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.preparation_request.created_in_transaction IS '由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。';
COMMENT ON COLUMN contract.preparation_request.opportunity_id IS '准确商机。';
COMMENT ON COLUMN contract.preparation_request.opportunity_revision IS '读取的商机版本。';
COMMENT ON COLUMN contract.preparation_request.responsibility_type IS '责任依据类型。';
COMMENT ON COLUMN contract.preparation_request.responsibility_id IS '责任依据身份。';
COMMENT ON COLUMN contract.preparation_request.responsibility_revision IS '责任依据版本。';
COMMENT ON COLUMN contract.preparation_request.owner_appointment_id IS '保存/确认的当前负责人。';
COMMENT ON COLUMN contract.preparation_request.customer_confirmation_id IS '准确客户需求确认。';
COMMENT ON COLUMN contract.preparation_request.commercial_digest IS '已申请服务范围及费用付款条款的规范摘要。';
COMMENT ON COLUMN contract.preparation_request.previous_request_id IS '本商机的准确前次申请；旧决定不适用于新申请。';
COMMENT ON COLUMN contract.preparation_request.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.preparation_request.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.preparation_request.created_at IS '服务端形成时间。';
COMMENT ON CONSTRAINT ck_preparation_request__revision ON contract.preparation_request IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_preparation_request__basis ON contract.preparation_request IS '准确责任和版本。';
COMMENT ON CONSTRAINT ck_preparation_request__body ON contract.preparation_request IS '有界加密正文。';
COMMENT ON CONSTRAINT uq_preparation_request__previous ON contract.preparation_request IS '申请单后继。';
COMMENT ON INDEX contract.uq_preparation_request__previous IS '申请单后继。';
COMMENT ON CONSTRAINT ck_preparation_request__commercial_digest_length ON contract.preparation_request IS '摘要格式：commercial_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_preparation_request__body_digest_length ON contract.preparation_request IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE contract.preparation_decision (
    tenant_id uuid NOT NULL,
    preparation_decision_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    preparation_request_id uuid NOT NULL,
    decision_code varchar(64) NOT NULL,
    decided_by_appointment_id uuid NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    effective_from timestamptz(6),
    effective_until timestamptz(6),
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_preparation_decision PRIMARY KEY (tenant_id, preparation_decision_id),
    CONSTRAINT ck_preparation_decision__revision CHECK (revision=0),
    CONSTRAINT uq_preparation_decision__request UNIQUE (tenant_id, preparation_request_id),
    CONSTRAINT ck_preparation_decision__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_preparation_decision__shape CHECK ((decision_code='APPROVED' AND effective_from IS NOT NULL AND (effective_until IS NULL OR effective_until>effective_from)) OR (decision_code='RETURNED' AND effective_from IS NULL AND effective_until IS NULL)),
    CONSTRAINT ck_preparation_decision__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE contract.preparation_decision IS 'Fact Owner：ContractRuntime；准确申请的不可变决定；仍需命令运行时授权，不代表合同审批。';
COMMENT ON CONSTRAINT pk_preparation_decision ON contract.preparation_decision IS '主键：在租户内唯一标识一条preparation_decision记录。';
COMMENT ON INDEX contract.pk_preparation_decision IS '主键：在租户内唯一标识一条preparation_decision记录。';
COMMENT ON COLUMN contract.preparation_decision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.preparation_decision.preparation_decision_id IS '准确申请的不可变决定；仍需命令运行时授权，不代表合同审批。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.preparation_decision.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.preparation_decision.preparation_request_id IS '被决定的唯一准确申请。';
COMMENT ON COLUMN contract.preparation_decision.decision_code IS '批准或退回。';
COMMENT ON COLUMN contract.preparation_decision.decided_by_appointment_id IS '实际有权决定任职。';
COMMENT ON COLUMN contract.preparation_decision.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN contract.preparation_decision.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN contract.preparation_decision.effective_from IS '批准生效起点；由服务端决定时间写入。';
COMMENT ON COLUMN contract.preparation_decision.effective_until IS '批准自然到期；空表示未设自然到期。';
COMMENT ON COLUMN contract.preparation_decision.created_at IS '服务端决定时间。';
COMMENT ON CONSTRAINT ck_preparation_decision__revision ON contract.preparation_decision IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_preparation_decision__request ON contract.preparation_decision IS '每份申请只有一个最终决定。';
COMMENT ON INDEX contract.uq_preparation_decision__request IS '每份申请只有一个最终决定。';
COMMENT ON CONSTRAINT ck_preparation_decision__body ON contract.preparation_decision IS '决定说明有界密文。';
COMMENT ON CONSTRAINT ck_preparation_decision__shape ON contract.preparation_decision IS '退回没有授权区间。';
COMMENT ON CONSTRAINT ck_preparation_decision__body_digest_length ON contract.preparation_decision IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE contract.preparation_request
    ADD CONSTRAINT fk_preparation_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_request__tenant ON contract.preparation_request IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.preparation_request
    ADD CONSTRAINT fk_preparation_request__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_request__opportunity_id ON contract.preparation_request IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_request
    ADD CONSTRAINT fk_preparation_request__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_request__owner_appointment_id ON contract.preparation_request IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_request
    ADD CONSTRAINT fk_preparation_request__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_request__customer_confirmation_id ON contract.preparation_request IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_request
    ADD CONSTRAINT fk_preparation_request__previous_request_id
    FOREIGN KEY (tenant_id, previous_request_id)
    REFERENCES contract.preparation_request (tenant_id, preparation_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_request__previous_request_id ON contract.preparation_request IS '同租户准确事实引用。';
CREATE TRIGGER trg_preparation_request__mutation_guard BEFORE UPDATE OR DELETE ON contract.preparation_request FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_preparation_request__mutation_guard ON contract.preparation_request IS '准确申请和决定不可改写。';
REVOKE ALL ON contract.preparation_request FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.preparation_request TO ${app_command_role};
GRANT SELECT ON contract.preparation_request TO ${app_query_role};
ALTER TABLE contract.preparation_decision
    ADD CONSTRAINT fk_preparation_decision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_decision__tenant ON contract.preparation_decision IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE contract.preparation_decision
    ADD CONSTRAINT fk_preparation_decision__preparation_request_id
    FOREIGN KEY (tenant_id, preparation_request_id)
    REFERENCES contract.preparation_request (tenant_id, preparation_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_decision__preparation_request_id ON contract.preparation_decision IS '同租户准确事实引用。';
ALTER TABLE contract.preparation_decision
    ADD CONSTRAINT fk_preparation_decision__decided_by_appointment_id
    FOREIGN KEY (tenant_id, decided_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_preparation_decision__decided_by_appointment_id ON contract.preparation_decision IS '同租户准确事实引用。';
CREATE TRIGGER trg_preparation_decision__mutation_guard BEFORE UPDATE OR DELETE ON contract.preparation_decision FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_preparation_decision__mutation_guard ON contract.preparation_decision IS '准确申请和决定不可改写。';
REVOKE ALL ON contract.preparation_decision FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON contract.preparation_decision TO ${app_command_role};
GRANT SELECT ON contract.preparation_decision TO ${app_query_role};
-- 查询、自然幂等和单当前事实索引。

CREATE UNIQUE INDEX uq_preparation_request__root ON contract.preparation_request (tenant_id, opportunity_id) WHERE previous_request_id IS NULL;
COMMENT ON INDEX contract.uq_preparation_request__root IS '每商机只有一个首申请；不依赖事务快照刷新。';

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
