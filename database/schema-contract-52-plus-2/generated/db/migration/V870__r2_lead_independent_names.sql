-- R2 approved independent names; historical V001-V860 remain immutable.
ALTER TABLE lead.lead ADD COLUMN customer_name_ciphertext bytea;
ALTER TABLE lead.lead ADD COLUMN contact_name_ciphertext bytea;
COMMENT ON COLUMN lead.lead.customer_name_ciphertext IS '客户名称密文：独立可选原始接入事实，创建后不可变。';
COMMENT ON COLUMN lead.lead.contact_name_ciphertext IS '联系人名称密文：独立可选原始接入事实，创建后不可变。';
GRANT SELECT (customer_name_ciphertext, contact_name_ciphertext) ON lead.lead TO ${app_query_role};
DO $v870_contract_version$
BEGIN
    UPDATE platform_meta.deployment_state
    SET schema_contract_version = '52-plus-2-r2-v1', revision = revision + 1, changed_at = clock_timestamp()
    WHERE deployment_state_key = 'PRIMARY' AND schema_contract_version = '52-plus-2-v1.2';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V870 requires schema contract 52-plus-2-v1.2' USING ERRCODE = '55000';
    END IF;
END;
$v870_contract_version$;
