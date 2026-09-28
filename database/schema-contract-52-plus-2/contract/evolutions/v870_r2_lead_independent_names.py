"""R2 first schema successor: independent immutable captured customer and contact names."""
from dataclasses import replace
from ..helpers import encrypted_col
from ..model import ContractEvolution

NAMES = ("customer_name_ciphertext", "contact_name_ciphertext")
def apply_evolution(schemas):
    return tuple(replace(schema, tables=tuple(replace(table, columns=(*table.columns,
        encrypted_col(NAMES[0], "客户名称密文：独立可选原始接入事实，创建后不可变。", nullable=True),
        encrypted_col(NAMES[1], "联系人名称密文：独立可选原始接入事实，创建后不可变。", nullable=True)))
        if schema.name == "lead" and table.name == "lead" else table for table in schema.tables)) for schema in schemas)

def render_sql(base_schemas, evolved_schemas):
    return """-- R2 approved independent names; historical V001-V860 remain immutable.
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
"""

EVOLUTION = ContractEvolution(version=870, migration_name="V870__r2_lead_independent_names.sql",
    contract_version="52-plus-2-r2-v1", apply=apply_evolution, render_sql=render_sql)
