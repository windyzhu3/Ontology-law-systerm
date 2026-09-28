CREATE TABLE opportunity.closure (
    tenant_id uuid NOT NULL,
    closure_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    task_occurrence_id uuid,
    task_revision bigint,
    wait_receipt_id uuid,
    wait_hash bytea,
    closed_by_appointment_id uuid NOT NULL,
    reason_code varchar(64) NOT NULL,
    closure_summary_ciphertext bytea NOT NULL,
    summary_digest bytea NOT NULL,
    closed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_closure PRIMARY KEY (tenant_id, closure_id),
    CONSTRAINT uq_closure__opportunity UNIQUE (tenant_id, opportunity_id),
    CONSTRAINT ck_closure__revision CHECK (revision=0),
    CONSTRAINT ck_closure__opportunity_revision CHECK (opportunity_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_closure__responsibility_revision CHECK (responsibility_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_closure__responsibility_type CHECK (responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')),
    CONSTRAINT ck_closure__reason CHECK (reason_code IN ('CLIENT_DECLINED','NEED_CANCELLED','OTHER')),
    CONSTRAINT ck_closure__task CHECK ((task_occurrence_id IS NULL) = (task_revision IS NULL) AND (task_revision IS NULL OR task_revision BETWEEN 0 AND 9007199254740990)),
    CONSTRAINT ck_closure__wait CHECK ((wait_receipt_id IS NULL) = (wait_hash IS NULL) AND (wait_receipt_id IS NULL OR task_occurrence_id IS NOT NULL)),
    CONSTRAINT ck_closure__protected_body CHECK (octet_length(closure_summary_ciphertext) BETWEEN 29 AND 16384),
    CONSTRAINT ck_closure__wait_hash_length CHECK (octet_length(wait_hash) = 32),
    CONSTRAINT ck_closure__summary_digest_length CHECK (octet_length(summary_digest) = 32)
);

COMMENT ON TABLE opportunity.closure IS 'Fact Owner：OpportunityRuntime；不可变商机终结事实；无待办也保留明确依据；说明加密且仅授权后解密。';
COMMENT ON CONSTRAINT pk_closure ON opportunity.closure IS '主键：在租户内唯一标识一条closure记录。';
COMMENT ON INDEX opportunity.pk_closure IS '主键：在租户内唯一标识一条closure记录。';
COMMENT ON COLUMN opportunity.closure.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.closure.closure_id IS '不可变商机终结事实；无待办也保留明确依据；说明加密且仅授权后解密。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.closure.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.closure.opportunity_id IS '终结的准确商机。';
COMMENT ON COLUMN opportunity.closure.opportunity_revision IS '终结前准确商机版本。';
COMMENT ON COLUMN opportunity.closure.responsibility_type IS '有效责任依据类型。';
COMMENT ON COLUMN opportunity.closure.responsibility_id IS '有效责任依据身份。';
COMMENT ON COLUMN opportunity.closure.responsibility_revision IS '有效责任依据版本。';
COMMENT ON COLUMN opportunity.closure.task_occurrence_id IS '实际取消的当前普通任务；未建卡时为空。';
COMMENT ON COLUMN opportunity.closure.task_revision IS '取消前任务版本。';
COMMENT ON COLUMN opportunity.closure.wait_receipt_id IS '取消前准确等待事实。';
COMMENT ON COLUMN opportunity.closure.wait_hash IS '取消前准确等待摘要。';
COMMENT ON COLUMN opportunity.closure.closed_by_appointment_id IS '实际终结操作者。';
COMMENT ON COLUMN opportunity.closure.reason_code IS '明确终结原因。';
COMMENT ON COLUMN opportunity.closure.closure_summary_ciphertext IS '受保护简短终结说明；独立AAD绑定租户商机终结事实。';
COMMENT ON COLUMN opportunity.closure.summary_digest IS '规范说明摘要，只用于完整性复验。';
COMMENT ON COLUMN opportunity.closure.closed_at IS '数据库终结时间。';
COMMENT ON CONSTRAINT uq_closure__opportunity ON opportunity.closure IS '每个商机至多一个终结事实。';
COMMENT ON INDEX opportunity.uq_closure__opportunity IS '每个商机至多一个终结事实。';
COMMENT ON CONSTRAINT ck_closure__revision ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__opportunity_revision ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__responsibility_revision ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__responsibility_type ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__reason ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__task ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__wait ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__protected_body ON opportunity.closure IS '商机终结具名一致性。';
COMMENT ON CONSTRAINT ck_closure__wait_hash_length ON opportunity.closure IS '摘要格式：wait_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_closure__summary_digest_length ON opportunity.closure IS '摘要格式：summary_digest必须保存32字节的规范二进制值。';
ALTER TABLE opportunity.closure
    ADD CONSTRAINT fk_closure__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_closure__tenant ON opportunity.closure IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.closure
    ADD CONSTRAINT fk_closure__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_closure__opportunity_id ON opportunity.closure IS '同租户准确来源。';
ALTER TABLE opportunity.closure
    ADD CONSTRAINT fk_closure__task_occurrence_id
    FOREIGN KEY (tenant_id, task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_closure__task_occurrence_id ON opportunity.closure IS '同租户准确来源。';
ALTER TABLE opportunity.closure
    ADD CONSTRAINT fk_closure__wait_receipt_id
    FOREIGN KEY (tenant_id, wait_receipt_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_closure__wait_receipt_id ON opportunity.closure IS '同租户准确来源。';
ALTER TABLE opportunity.closure
    ADD CONSTRAINT fk_closure__closed_by_appointment_id
    FOREIGN KEY (tenant_id, closed_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_closure__closed_by_appointment_id ON opportunity.closure IS '同租户准确来源。';
CREATE TRIGGER trg_closure__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.closure
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_closure__mutation_guard ON opportunity.closure IS '终结事实禁止修改或删除。';
REVOKE ALL ON opportunity.closure FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.closure TO ${app_command_role};
GRANT SELECT ON opportunity.closure TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence DROP CONSTRAINT ck_task_occurrence__handoff_cancellation;
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK (cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0 AND cancellation_fact_hash IS NULL AND ((cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff') OR (cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1' AND cancellation_fact_type='opportunity.closure'))));

CREATE FUNCTION opportunity.fn_check_closure() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; t responsibility.task_occurrence%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id;
 IF o.revision IS DISTINCT FROM NEW.opportunity_revision+1 OR o.closed_at IS DISTINCT FROM NEW.closed_at OR o.close_outcome_code IS DISTINCT FROM NEW.reason_code THEN RAISE EXCEPTION 'closure root differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>NEW.opportunity_id OR NEW.responsibility_revision<>NEW.opportunity_revision OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=NEW.opportunity_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'closure handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM opportunity.quote_revision WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM contract.contract WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) OR EXISTS(SELECT 1 FROM transfer.transfer_request WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'closure has downstream facts' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND subject_type='opportunity.opportunity' AND subject_id=NEW.opportunity_id AND business_purpose_code='PROGRESS_OPPORTUNITY' AND state IN ('OPEN','WAITING')) THEN RAISE EXCEPTION 'closure retains active task' USING ERRCODE='23514'; END IF;
 IF NEW.task_occurrence_id IS NOT NULL THEN
  SELECT * INTO t FROM responsibility.task_occurrence WHERE tenant_id=NEW.tenant_id AND task_occurrence_id=NEW.task_occurrence_id;
  IF t.state IS DISTINCT FROM 'CANCELLED' OR t.revision IS DISTINCT FROM NEW.task_revision+1 OR t.cancellation_fact_type IS DISTINCT FROM 'opportunity.closure' OR t.cancellation_fact_id IS DISTINCT FROM NEW.closure_id OR t.subject_id IS DISTINCT FROM NEW.opportunity_id OR t.subject_type IS DISTINCT FROM 'opportunity.opportunity' OR t.cancelled_at IS DISTINCT FROM NEW.closed_at THEN RAISE EXCEPTION 'closure cancellation differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_closure() IS '终结事务提交前检查准确商机、责任、取消及后续事实；不扩展查询角色权限。';
REVOKE ALL ON FUNCTION opportunity.fn_check_closure() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_closure__consistency AFTER INSERT ON opportunity.closure DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_closure();
COMMENT ON TRIGGER trg_closure__consistency ON opportunity.closure IS '终结事实与商机关闭和任务取消在同一事务完整提交。';
DO $v910$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v5',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v4';
 IF NOT FOUND THEN RAISE EXCEPTION 'V910 requires 52-plus-2-r2-v4' USING ERRCODE='55000'; END IF;
END;
$v910$;
