"""V860: the approved four-column QUERY read exception (ADR-0010)."""

from ..model import ContractEvolution


def apply_evolution(schemas):
    return schemas


def render_sql(base_schemas, evolved_schemas):
    return """-- V860：只追加四列QUERY读取能力；V001至V850及业务模型保持不变。
GRANT SELECT (
    ingress_completion_phone_hmac,
    ingress_completion_email_hmac,
    ingress_completion_phone_ciphertext,
    ingress_completion_email_ciphertext
) ON TABLE lead.lead TO "${app_query_role}";

DO $v860_contract_version$
BEGIN
    UPDATE platform_meta.deployment_state
    SET schema_contract_version = '52-plus-2-v1.2',
        revision = revision + 1, changed_at = clock_timestamp()
    WHERE deployment_state_key = 'PRIMARY'
      AND schema_contract_version = '52-plus-2-v1.1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V860 requires deployment_state at schema contract 52-plus-2-v1.1'
            USING ERRCODE = '55000';
    END IF;
END;
$v860_contract_version$;

DO $v860_validation$
DECLARE
    column_name text;
    role_name text;
    expected_select boolean;
BEGIN
    FOR column_name IN
        SELECT attname FROM pg_catalog.pg_attribute
        WHERE attrelid = 'lead.lead'::pg_catalog.regclass AND attnum > 0 AND NOT attisdropped
    LOOP
        expected_select := column_name NOT IN (
            'ingress_completion_source_code', 'ingress_completion_source_summary_ciphertext',
            'ingress_completed_by_appointment_id', 'ingress_completed_at', 'ingress_completion_digest'
        );
        IF pg_catalog.has_column_privilege('${app_query_role}', 'lead.lead', column_name, 'SELECT')
           IS DISTINCT FROM expected_select
           OR pg_catalog.has_column_privilege('${app_query_role}', 'lead.lead', column_name, 'SELECT WITH GRANT OPTION')
           OR pg_catalog.has_column_privilege('${app_query_role}', 'lead.lead', column_name, 'INSERT, UPDATE, REFERENCES') THEN
            RAISE EXCEPTION 'V860 QUERY column capability mismatch on %', column_name USING ERRCODE = '55000';
        END IF;
        IF column_name LIKE 'ingress_completion_%' OR column_name IN ('ingress_completed_by_appointment_id', 'ingress_completed_at') THEN
            IF NOT pg_catalog.has_column_privilege('${app_command_role}', 'lead.lead', column_name, 'UPDATE') THEN
                RAISE EXCEPTION 'V860 COMMAND ingress UPDATE missing on %', column_name USING ERRCODE = '55000';
            END IF;
        END IF;
    END LOOP;
    IF pg_catalog.has_table_privilege('${app_query_role}', 'lead.lead', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') THEN
        RAISE EXCEPTION 'V860 QUERY table capability expanded' USING ERRCODE = '55000';
    END IF;
    IF NOT pg_catalog.has_table_privilege('${app_command_role}', 'lead.lead', 'SELECT')
       OR NOT pg_catalog.has_table_privilege('${app_command_role}', 'lead.lead', 'INSERT') THEN
        RAISE EXCEPTION 'V860 COMMAND Lead capability missing' USING ERRCODE = '55000';
    END IF;
    FOREACH role_name IN ARRAY ARRAY['${app_worker_role}', '${audit_append_role}'] LOOP
        IF pg_catalog.has_any_column_privilege(role_name, 'lead.lead', 'SELECT, INSERT, UPDATE, REFERENCES')
           OR pg_catalog.has_table_privilege(role_name, 'lead.lead', 'DELETE, TRUNCATE, TRIGGER') THEN
            RAISE EXCEPTION 'V860 non-QUERY Lead capability expanded for %', role_name USING ERRCODE = '55000';
        END IF;
    END LOOP;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_class r CROSS JOIN LATERAL pg_catalog.aclexplode(r.relacl) a
        WHERE r.oid = 'lead.lead'::pg_catalog.regclass AND a.grantee = 0
        UNION ALL
        SELECT 1 FROM pg_catalog.pg_attribute c CROSS JOIN LATERAL pg_catalog.aclexplode(c.attacl) a
        WHERE c.attrelid = 'lead.lead'::pg_catalog.regclass AND a.grantee = 0
    ) THEN
        RAISE EXCEPTION 'V860 PUBLIC Lead capability expanded' USING ERRCODE = '55000';
    END IF;
END;
$v860_validation$;
"""


EVOLUTION = ContractEvolution(
    version=860,
    migration_name="V860__lead_ingress_query_read_capability.sql",
    contract_version="52-plus-2-v1.2",
    apply=apply_evolution,
    render_sql=render_sql,
)
