-- R2 protected progress; historical V001-V870 remain byte-identical.
ALTER TABLE opportunity.opportunity_progress ADD COLUMN progress_body_ciphertext bytea;
COMMENT ON COLUMN opportunity.opportunity_progress.progress_body_ciphertext IS 'R2进展正文密文：绑定租户、商机与进展身份；历史无正文记录为空，创建后不可变。';
GRANT SELECT (progress_body_ciphertext) ON opportunity.opportunity_progress TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN predecessor_task_occurrence_id uuid;
COMMENT ON COLUMN responsibility.task_occurrence.predecessor_task_occurrence_id IS '前序责任身份：R2商机后继跟进绑定已完成责任；初始责任与历史记录为空，创建后不可变。';
GRANT SELECT (predecessor_task_occurrence_id) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE opportunity.opportunity_progress ADD CONSTRAINT ck_opportunity_progress__protected_body CHECK ((progress_contract_code = 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_contract_version = 1 AND progress_body_ciphertext IS NOT NULL AND octet_length(progress_body_ciphertext) >= 29) OR (progress_contract_code <> 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_body_ciphertext IS NULL));
COMMENT ON CONSTRAINT ck_opportunity_progress__protected_body ON opportunity.opportunity_progress IS 'R2受保护正文仅由准确进展合同写入；历史合同不混用此正文。';
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT uq_task_occurrence__progress_successor UNIQUE (tenant_id, predecessor_task_occurrence_id);
COMMENT ON CONSTRAINT uq_task_occurrence__progress_successor ON responsibility.task_occurrence IS '商机跟进因果幂等：同一前序责任最多生成一个后继责任。';
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__progress_predecessor CHECK (predecessor_task_occurrence_id IS NULL OR (business_purpose_code = 'PROGRESS_OPPORTUNITY' AND predecessor_task_occurrence_id <> task_occurrence_id));
COMMENT ON CONSTRAINT ck_task_occurrence__progress_predecessor ON responsibility.task_occurrence IS '后继关系限定为商机推进且不得自指。';
COMMENT ON INDEX responsibility.uq_task_occurrence__progress_successor IS '商机跟进因果幂等：同一前序责任最多生成一个后继责任。';
ALTER TABLE responsibility.task_occurrence
    ADD CONSTRAINT fk_task_occurrence__progress_predecessor
    FOREIGN KEY (tenant_id, predecessor_task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_task_occurrence__progress_predecessor ON responsibility.task_occurrence IS '前序责任必须存在于同一租户。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_digest IS '进展事实摘要：历史合同覆盖类型与准确来源；R2_OPPORTUNITY_PROGRESS_V1覆盖规范受保护正文及准确业务身份。';
DO $v880_contract_version$
BEGIN
    UPDATE platform_meta.deployment_state
    SET schema_contract_version = '52-plus-2-r2-v2', revision = revision + 1, changed_at = clock_timestamp()
    WHERE deployment_state_key = 'PRIMARY' AND schema_contract_version = '52-plus-2-r2-v1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V880 requires schema contract 52-plus-2-r2-v1' USING ERRCODE = '55000';
    END IF;
END;
$v880_contract_version$;
