CREATE TABLE evidence.material_upload_basis (
    tenant_id uuid NOT NULL,
    material_upload_basis_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    upload_session_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    owner_appointment_id uuid NOT NULL,
    customer_confirmation_id uuid,
    original_task_id uuid,
    original_task_revision bigint,
    expected_previous_version_id uuid,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_material_upload_basis PRIMARY KEY (tenant_id, material_upload_basis_id),
    CONSTRAINT ck_material_upload_basis__revision CHECK (revision=0),
    CONSTRAINT uq_material_upload_basis__session UNIQUE (tenant_id, upload_session_id),
    CONSTRAINT ck_material_upload_basis__identity CHECK (material_upload_basis_id=upload_session_id),
    CONSTRAINT ck_material_upload_basis__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_material_upload_basis__selectors CHECK (opportunity_revision BETWEEN 0 AND 9007199254740991 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND ((original_task_id IS NULL AND original_task_revision IS NULL) OR (original_task_id IS NOT NULL AND original_task_revision IS NOT NULL AND original_task_revision BETWEEN 0 AND 9007199254740991))),
    CONSTRAINT ck_material_upload_basis__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE evidence.material_upload_basis IS 'Fact Owner：EvidenceRuntime；材料上传准确责任及受保护元数据；不改变既有证据链。';
COMMENT ON CONSTRAINT pk_material_upload_basis ON evidence.material_upload_basis IS '主键：在租户内唯一标识一条material_upload_basis记录。';
COMMENT ON INDEX evidence.pk_material_upload_basis IS '主键：在租户内唯一标识一条material_upload_basis记录。';
COMMENT ON COLUMN evidence.material_upload_basis.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.material_upload_basis.material_upload_basis_id IS '材料上传准确责任及受保护元数据；不改变既有证据链。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.material_upload_basis.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN evidence.material_upload_basis.upload_session_id IS '唯一既有上传会话。';
COMMENT ON COLUMN evidence.material_upload_basis.opportunity_id IS '准确商机。';
COMMENT ON COLUMN evidence.material_upload_basis.opportunity_revision IS '准确商机版本。';
COMMENT ON COLUMN evidence.material_upload_basis.responsibility_type IS '准确责任类型。';
COMMENT ON COLUMN evidence.material_upload_basis.responsibility_id IS '准确责任身份。';
COMMENT ON COLUMN evidence.material_upload_basis.responsibility_revision IS '准确责任版本。';
COMMENT ON COLUMN evidence.material_upload_basis.owner_appointment_id IS '当前责任任职。';
COMMENT ON COLUMN evidence.material_upload_basis.customer_confirmation_id IS '可选准确客户确认不可变版本。';
COMMENT ON COLUMN evidence.material_upload_basis.original_task_id IS '原跟进事项。';
COMMENT ON COLUMN evidence.material_upload_basis.original_task_revision IS '原事项准确版本。';
COMMENT ON COLUMN evidence.material_upload_basis.expected_previous_version_id IS '补交所期望的准确前版。';
COMMENT ON COLUMN evidence.material_upload_basis.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN evidence.material_upload_basis.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN evidence.material_upload_basis.created_at IS '冻结时间。';
COMMENT ON CONSTRAINT ck_material_upload_basis__revision ON evidence.material_upload_basis IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_material_upload_basis__session ON evidence.material_upload_basis IS '一会话唯一依据。';
COMMENT ON INDEX evidence.uq_material_upload_basis__session IS '一会话唯一依据。';
COMMENT ON CONSTRAINT ck_material_upload_basis__identity ON evidence.material_upload_basis IS '会话与不可变依据共享身份但版本语义独立。';
COMMENT ON CONSTRAINT ck_material_upload_basis__body ON evidence.material_upload_basis IS '受保护文件名及说明大小。';
COMMENT ON CONSTRAINT ck_material_upload_basis__selectors ON evidence.material_upload_basis IS '完整准确选择器。';
COMMENT ON CONSTRAINT ck_material_upload_basis__body_digest_length ON evidence.material_upload_basis IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE evidence.material_upload_check (
    tenant_id uuid NOT NULL,
    material_upload_check_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    upload_basis_id uuid NOT NULL,
    previous_check_id uuid,
    status varchar(32) NOT NULL,
    result_code varchar(64),
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_material_upload_check PRIMARY KEY (tenant_id, material_upload_check_id),
    CONSTRAINT ck_material_upload_check__revision CHECK (revision=0),
    CONSTRAINT uq_material_upload_check__previous UNIQUE (tenant_id, previous_check_id),
    CONSTRAINT ck_material_upload_check__status CHECK (status IN ('CHECKING','UNKNOWN','SCAN_UNAVAILABLE','PASSED','REJECTED')),
    CONSTRAINT ck_material_upload_check__code CHECK (result_code IS NULL OR result_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

COMMENT ON TABLE evidence.material_upload_check IS 'Fact Owner：EvidenceRuntime；技术处理状态不可变历史；未知禁止静默重传。';
COMMENT ON CONSTRAINT pk_material_upload_check ON evidence.material_upload_check IS '主键：在租户内唯一标识一条material_upload_check记录。';
COMMENT ON INDEX evidence.pk_material_upload_check IS '主键：在租户内唯一标识一条material_upload_check记录。';
COMMENT ON COLUMN evidence.material_upload_check.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN evidence.material_upload_check.material_upload_check_id IS '技术处理状态不可变历史；未知禁止静默重传。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN evidence.material_upload_check.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN evidence.material_upload_check.upload_basis_id IS '准确上传依据。';
COMMENT ON COLUMN evidence.material_upload_check.previous_check_id IS '准确前次检查状态。';
COMMENT ON COLUMN evidence.material_upload_check.status IS '技术状态。';
COMMENT ON COLUMN evidence.material_upload_check.result_code IS '静态安全结果码，不保存正文。';
COMMENT ON COLUMN evidence.material_upload_check.created_at IS '状态形成时间。';
COMMENT ON CONSTRAINT ck_material_upload_check__revision ON evidence.material_upload_check IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_material_upload_check__previous ON evidence.material_upload_check IS '检查状态单一后继。';
COMMENT ON INDEX evidence.uq_material_upload_check__previous IS '检查状态单一后继。';
COMMENT ON CONSTRAINT ck_material_upload_check__status ON evidence.material_upload_check IS '技术状态域。';
COMMENT ON CONSTRAINT ck_material_upload_check__code ON evidence.material_upload_check IS '有界安全结果代码。';
CREATE TABLE opportunity.material_version (
    tenant_id uuid NOT NULL,
    material_version_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    material_item_id uuid NOT NULL,
    previous_version_id uuid,
    upload_basis_id uuid NOT NULL,
    upload_session_id uuid NOT NULL,
    received_source_object_id uuid NOT NULL,
    evidence_submission_id uuid NOT NULL,
    evidence_binding_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    purpose_code varchar(64) NOT NULL,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    received_by_appointment_id uuid NOT NULL,
    received_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_material_version PRIMARY KEY (tenant_id, material_version_id),
    CONSTRAINT ck_material_version__revision CHECK (revision=0),
    CONSTRAINT uq_material_version__previous_version_id UNIQUE (tenant_id, previous_version_id),
    CONSTRAINT uq_material_version__upload_basis_id UNIQUE (tenant_id, upload_basis_id),
    CONSTRAINT uq_material_version__upload_session_id UNIQUE (tenant_id, upload_session_id),
    CONSTRAINT uq_material_version__evidence_submission_id UNIQUE (tenant_id, evidence_submission_id),
    CONSTRAINT uq_material_version__evidence_binding_id UNIQUE (tenant_id, evidence_binding_id),
    CONSTRAINT ck_material_version__purpose CHECK (purpose_code IN ('CONTRACT_BUSINESS','CORRESPONDENCE','OTHER')),
    CONSTRAINT ck_material_version__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT ck_material_version__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE opportunity.material_version IS 'Fact Owner：OpportunityRuntime；材料条目不可变版本；当前版以无后继派生，历史引用保留。';
COMMENT ON CONSTRAINT pk_material_version ON opportunity.material_version IS '主键：在租户内唯一标识一条material_version记录。';
COMMENT ON INDEX opportunity.pk_material_version IS '主键：在租户内唯一标识一条material_version记录。';
COMMENT ON COLUMN opportunity.material_version.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.material_version.material_version_id IS '材料条目不可变版本；当前版以无后继派生，历史引用保留。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.material_version.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.material_version.material_item_id IS '首版本身份作为稳定条目身份。';
COMMENT ON COLUMN opportunity.material_version.previous_version_id IS '准确前版。';
COMMENT ON COLUMN opportunity.material_version.upload_basis_id IS '准确上传依据。';
COMMENT ON COLUMN opportunity.material_version.upload_session_id IS '既有上传会话。';
COMMENT ON COLUMN opportunity.material_version.received_source_object_id IS '既有扫描来源。';
COMMENT ON COLUMN opportunity.material_version.evidence_submission_id IS '既有不可变提交。';
COMMENT ON COLUMN opportunity.material_version.evidence_binding_id IS '既有准确绑定。';
COMMENT ON COLUMN opportunity.material_version.opportunity_id IS '所属商机。';
COMMENT ON COLUMN opportunity.material_version.purpose_code IS '静态材料用途。';
COMMENT ON COLUMN opportunity.material_version.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN opportunity.material_version.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN opportunity.material_version.received_by_appointment_id IS '实际接收任职。';
COMMENT ON COLUMN opportunity.material_version.received_at IS '人工接收时间。';
COMMENT ON CONSTRAINT ck_material_version__revision ON opportunity.material_version IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_material_version__previous_version_id ON opportunity.material_version IS '准确链节点只能接收一次。';
COMMENT ON INDEX opportunity.uq_material_version__previous_version_id IS '准确链节点只能接收一次。';
COMMENT ON CONSTRAINT uq_material_version__upload_basis_id ON opportunity.material_version IS '准确链节点只能接收一次。';
COMMENT ON INDEX opportunity.uq_material_version__upload_basis_id IS '准确链节点只能接收一次。';
COMMENT ON CONSTRAINT uq_material_version__upload_session_id ON opportunity.material_version IS '准确链节点只能接收一次。';
COMMENT ON INDEX opportunity.uq_material_version__upload_session_id IS '准确链节点只能接收一次。';
COMMENT ON CONSTRAINT uq_material_version__evidence_submission_id ON opportunity.material_version IS '准确链节点只能接收一次。';
COMMENT ON INDEX opportunity.uq_material_version__evidence_submission_id IS '准确链节点只能接收一次。';
COMMENT ON CONSTRAINT uq_material_version__evidence_binding_id ON opportunity.material_version IS '准确链节点只能接收一次。';
COMMENT ON INDEX opportunity.uq_material_version__evidence_binding_id IS '准确链节点只能接收一次。';
COMMENT ON CONSTRAINT ck_material_version__purpose ON opportunity.material_version IS '用途不是案件分类。';
COMMENT ON CONSTRAINT ck_material_version__body ON opportunity.material_version IS '加密材料元数据有界。';
COMMENT ON CONSTRAINT ck_material_version__body_digest_length ON opportunity.material_version IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__tenant ON evidence.material_upload_basis IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__upload_session_id
    FOREIGN KEY (tenant_id, upload_session_id)
    REFERENCES evidence.upload_session (tenant_id, upload_session_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__upload_session_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__opportunity_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__owner_appointment_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__customer_confirmation_id
    FOREIGN KEY (tenant_id, customer_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__customer_confirmation_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__original_task_id
    FOREIGN KEY (tenant_id, original_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__original_task_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_basis
    ADD CONSTRAINT fk_material_upload_basis__expected_previous_version_id
    FOREIGN KEY (tenant_id, expected_previous_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_basis__expected_previous_version_id ON evidence.material_upload_basis IS '同租户准确事实引用。';
CREATE TRIGGER trg_material_upload_basis__mutation_guard BEFORE UPDATE OR DELETE ON evidence.material_upload_basis FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_material_upload_basis__mutation_guard ON evidence.material_upload_basis IS '不可变材料事实禁止改写或删除。';
REVOKE ALL ON evidence.material_upload_basis FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON evidence.material_upload_basis TO ${app_command_role};
GRANT SELECT ON evidence.material_upload_basis TO ${app_query_role};
ALTER TABLE evidence.material_upload_check
    ADD CONSTRAINT fk_material_upload_check__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_check__tenant ON evidence.material_upload_check IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE evidence.material_upload_check
    ADD CONSTRAINT fk_material_upload_check__upload_basis_id
    FOREIGN KEY (tenant_id, upload_basis_id)
    REFERENCES evidence.material_upload_basis (tenant_id, material_upload_basis_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_check__upload_basis_id ON evidence.material_upload_check IS '同租户准确事实引用。';
ALTER TABLE evidence.material_upload_check
    ADD CONSTRAINT fk_material_upload_check__previous_check_id
    FOREIGN KEY (tenant_id, previous_check_id)
    REFERENCES evidence.material_upload_check (tenant_id, material_upload_check_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_upload_check__previous_check_id ON evidence.material_upload_check IS '同租户准确事实引用。';
CREATE TRIGGER trg_material_upload_check__mutation_guard BEFORE UPDATE OR DELETE ON evidence.material_upload_check FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_material_upload_check__mutation_guard ON evidence.material_upload_check IS '不可变材料事实禁止改写或删除。';
REVOKE ALL ON evidence.material_upload_check FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON evidence.material_upload_check TO ${app_command_role};
GRANT SELECT ON evidence.material_upload_check TO ${app_query_role};
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__tenant ON opportunity.material_version IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__previous_version_id
    FOREIGN KEY (tenant_id, previous_version_id)
    REFERENCES opportunity.material_version (tenant_id, material_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__previous_version_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__upload_basis_id
    FOREIGN KEY (tenant_id, upload_basis_id)
    REFERENCES evidence.material_upload_basis (tenant_id, material_upload_basis_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__upload_basis_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__upload_session_id
    FOREIGN KEY (tenant_id, upload_session_id)
    REFERENCES evidence.upload_session (tenant_id, upload_session_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__upload_session_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__received_source_object_id
    FOREIGN KEY (tenant_id, received_source_object_id)
    REFERENCES evidence.received_source_object (tenant_id, received_source_object_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__received_source_object_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__evidence_submission_id
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__evidence_submission_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__evidence_binding_id
    FOREIGN KEY (tenant_id, evidence_binding_id)
    REFERENCES evidence.evidence_binding (tenant_id, evidence_binding_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__evidence_binding_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__opportunity_id ON opportunity.material_version IS '同租户准确事实引用。';
ALTER TABLE opportunity.material_version
    ADD CONSTRAINT fk_material_version__received_by_appointment_id
    FOREIGN KEY (tenant_id, received_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_material_version__received_by_appointment_id ON opportunity.material_version IS '同租户准确事实引用。';
CREATE TRIGGER trg_material_version__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.material_version FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_material_version__mutation_guard ON opportunity.material_version IS '不可变材料事实禁止改写或删除。';
REVOKE ALL ON opportunity.material_version FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.material_version TO ${app_command_role};
GRANT SELECT ON opportunity.material_version TO ${app_query_role};

CREATE FUNCTION evidence.fn_check_material_basis_current(b evidence.material_upload_basis) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=b.tenant_id AND opportunity_id=b.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM b.opportunity_revision THEN RAISE EXCEPTION 'material opportunity differs' USING ERRCODE='23514'; END IF;
 IF b.responsibility_type='opportunity.opportunity' THEN
  IF b.responsibility_id<>b.opportunity_id OR b.responsibility_revision<>b.opportunity_revision OR b.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=b.tenant_id AND opportunity_id=b.opportunity_id) THEN RAISE EXCEPTION 'material responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=b.tenant_id AND h.responsibility_handoff_id=b.responsibility_id AND h.revision=b.responsibility_revision AND h.opportunity_id=b.opportunity_id AND h.to_appointment_id=b.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'material responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) IS '上传及接收均锁商机重验准确当前责任。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) TO ${app_command_role};
CREATE FUNCTION evidence.fn_check_material_basis() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM evidence.fn_check_material_basis_current(NEW);
 IF NOT EXISTS(SELECT 1 FROM evidence.upload_session u WHERE u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id AND u.target_type='opportunity.opportunity' AND u.target_id=NEW.opportunity_id AND u.target_revision=NEW.opportunity_revision AND u.target_hash IS NULL AND u.created_by_appointment_id=NEW.owner_appointment_id AND u.status='OPEN' AND u.expires_at>clock_timestamp() AND u.purpose_code IN ('CONTRACT_BUSINESS','CORRESPONDENCE','OTHER')) THEN RAISE EXCEPTION 'material session differs' USING ERRCODE='23514'; END IF;
 IF NEW.customer_confirmation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'material confirmation differs' USING ERRCODE='23514'; END IF;
 IF NEW.original_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.original_task_id AND t.revision=NEW.original_task_revision AND t.subject_type='opportunity.opportunity' AND t.subject_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'material original task differs' USING ERRCODE='23514'; END IF;
 IF NEW.expected_previous_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.upload_session u ON u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.expected_previous_version_id AND v.opportunity_id=NEW.opportunity_id AND v.purpose_code=u.purpose_code AND NOT EXISTS(SELECT 1 FROM opportunity.material_version n WHERE n.tenant_id=v.tenant_id AND n.previous_version_id=v.material_version_id)) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_basis() IS '会话依据冻结准确归属、原事项和可选确认版本。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_basis() FROM PUBLIC;
CREATE TRIGGER trg_material_upload_basis__source BEFORE INSERT ON evidence.material_upload_basis FOR EACH ROW EXECUTE FUNCTION evidence.fn_check_material_basis();
COMMENT ON TRIGGER trg_material_upload_basis__source ON evidence.material_upload_basis IS '上传前准确依据守卫。';
CREATE FUNCTION evidence.fn_check_material_check() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE p evidence.material_upload_check%ROWTYPE;
BEGIN
 PERFORM 1 FROM evidence.upload_session u JOIN evidence.material_upload_basis b ON b.tenant_id=u.tenant_id AND b.upload_session_id=u.upload_session_id WHERE b.tenant_id=NEW.tenant_id AND b.material_upload_basis_id=NEW.upload_basis_id FOR UPDATE OF u;
 IF NEW.previous_check_id IS NULL THEN
  IF NEW.status<>'CHECKING' OR EXISTS(SELECT 1 FROM evidence.material_upload_check WHERE tenant_id=NEW.tenant_id AND upload_basis_id=NEW.upload_basis_id) THEN RAISE EXCEPTION 'material check transition differs' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO p FROM evidence.material_upload_check WHERE tenant_id=NEW.tenant_id AND material_upload_check_id=NEW.previous_check_id;
  IF p.upload_basis_id IS DISTINCT FROM NEW.upload_basis_id OR p.status IN ('PASSED','REJECTED') OR NEW.status='CHECKING' OR NEW.created_at<p.created_at THEN RAISE EXCEPTION 'material check transition differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_check() IS '技术检查历史单一后继，不允许未知后重发字节。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_check() FROM PUBLIC;
CREATE TRIGGER trg_material_upload_check__source BEFORE INSERT ON evidence.material_upload_check FOR EACH ROW EXECUTE FUNCTION evidence.fn_check_material_check();
COMMENT ON TRIGGER trg_material_upload_check__source ON evidence.material_upload_check IS '检查结果不可倒退。';
CREATE FUNCTION opportunity.fn_check_material_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE b evidence.material_upload_basis%ROWTYPE;
BEGIN
 SELECT * INTO b FROM evidence.material_upload_basis WHERE tenant_id=NEW.tenant_id AND material_upload_basis_id=NEW.upload_basis_id;
 IF b.material_upload_basis_id IS NULL THEN RAISE EXCEPTION 'material basis absent' USING ERRCODE='23514'; END IF;
 PERFORM evidence.fn_check_material_basis_current(b);
 IF b.opportunity_id<>NEW.opportunity_id OR b.upload_session_id<>NEW.upload_session_id OR b.owner_appointment_id<>NEW.received_by_appointment_id OR b.expected_previous_version_id IS DISTINCT FROM NEW.previous_version_id OR b.body_ciphertext<>NEW.body_ciphertext OR b.body_digest<>NEW.body_digest THEN RAISE EXCEPTION 'material basis differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_version_id IS NULL THEN
  IF NEW.material_item_id<>NEW.material_version_id OR EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.material_item_id=NEW.material_item_id) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version p WHERE p.tenant_id=NEW.tenant_id AND p.material_version_id=NEW.previous_version_id AND p.material_item_id=NEW.material_item_id AND p.opportunity_id=NEW.opportunity_id AND p.purpose_code=NEW.purpose_code) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM evidence.upload_session u JOIN evidence.received_source_object r ON r.tenant_id=u.tenant_id AND r.upload_session_id=u.upload_session_id JOIN evidence.evidence_submission s ON s.tenant_id=r.tenant_id AND s.received_source_object_id=r.received_source_object_id JOIN evidence.evidence_binding e ON e.tenant_id=s.tenant_id AND e.evidence_submission_id=s.evidence_submission_id WHERE u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id AND u.status='FINALIZED' AND u.purpose_code=NEW.purpose_code AND u.target_type='opportunity.opportunity' AND u.target_id=NEW.opportunity_id AND u.target_revision=b.opportunity_revision AND u.expires_at>clock_timestamp() AND r.received_source_object_id=NEW.received_source_object_id AND r.scan_result='PASSED' AND r.size_bytes BETWEEN 1 AND 20971520 AND r.detected_media_type IN ('application/pdf','image/jpeg','image/png') AND s.evidence_submission_id=NEW.evidence_submission_id AND s.submitted_by_appointment_id=NEW.received_by_appointment_id AND e.evidence_binding_id=NEW.evidence_binding_id AND e.purpose_code=NEW.purpose_code AND e.bound_by_appointment_id=NEW.received_by_appointment_id AND e.target_type=u.target_type AND e.target_id=u.target_id AND e.target_revision=u.target_revision AND e.target_hash IS NULL AND e.revoked_at IS NULL) THEN RAISE EXCEPTION 'material evidence chain differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM evidence.material_upload_check c WHERE c.tenant_id=NEW.tenant_id AND c.upload_basis_id=NEW.upload_basis_id AND c.status='PASSED' AND NOT EXISTS(SELECT 1 FROM evidence.material_upload_check n WHERE n.tenant_id=c.tenant_id AND n.previous_check_id=c.material_upload_check_id)) THEN RAISE EXCEPTION 'material check not passed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_material_version() IS '接收原子引用原证据链、重验责任与单一材料后继。';
REVOKE ALL ON FUNCTION opportunity.fn_check_material_version() FROM PUBLIC;
CREATE TRIGGER trg_material_version__source BEFORE INSERT ON opportunity.material_version FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_material_version();
COMMENT ON TRIGGER trg_material_version__source ON opportunity.material_version IS '接收版本准确链守卫。';
DO $v930$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v7',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v6';
 IF NOT FOUND THEN RAISE EXCEPTION 'V930 requires 52-plus-2-r2-v6' USING ERRCODE='55000'; END IF;
END;
$v930$;
