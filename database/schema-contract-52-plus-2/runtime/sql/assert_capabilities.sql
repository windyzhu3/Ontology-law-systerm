BEGIN;

CREATE FUNCTION pg_temp.expect_sqlstate(expected_state text, statement_text text, assertion_name text)
RETURNS void
LANGUAGE plpgsql
AS $expect_sqlstate$
DECLARE
    actual_state text;
BEGIN
    BEGIN
        EXECUTE statement_text;
        RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=success', assertion_name, expected_state;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state <> expected_state THEN
            RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=%', assertion_name, expected_state, actual_state;
        END IF;
    END;
END;
$expect_sqlstate$;

-- Three transactional guard probes use real writes and exact SQLSTATE checks, then ROLLBACK below.
INSERT INTO identity.tenant (tenant_id, tenant_code, display_name, state, created_at, revision)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'runtime-probe-a', 'runtime probe A', 'ACTIVE', clock_timestamp(), 0),
    ('20000000-0000-0000-0000-000000000002', 'runtime-probe-b', 'runtime probe B', 'ACTIVE', clock_timestamp(), 0);
INSERT INTO identity.organization_unit (
    tenant_id, organization_unit_id, unit_code, display_name,
    parent_organization_unit_id, state, created_at, revision
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000003',
    'runtime-parent', 'runtime parent', NULL, 'ACTIVE', clock_timestamp(), 0
);

DO $cross_tenant_fk$
DECLARE
    actual_state text;
BEGIN
    BEGIN
        INSERT INTO identity.organization_unit (
            tenant_id, organization_unit_id, unit_code, display_name,
            parent_organization_unit_id, state, created_at, revision
        )
        VALUES (
            '20000000-0000-0000-0000-000000000002',
            '40000000-0000-0000-0000-000000000004',
            'runtime-child', 'runtime child',
            '30000000-0000-0000-0000-000000000003',
            'ACTIVE', clock_timestamp(), 0
        );
        SET CONSTRAINTS identity.fk_organization_unit__parent_organization_unit IMMEDIATE;
        RAISE EXCEPTION 'assertion=cross-tenant organization parent expected SQLSTATE=23503 actual=success';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state <> '23503' THEN
            RAISE EXCEPTION 'assertion=cross-tenant organization parent expected SQLSTATE=23503 actual=%', actual_state;
        END IF;
    END;
END;
$cross_tenant_fk$;

SELECT pg_temp.expect_sqlstate(
    '55000',
    'UPDATE platform_meta.deployment_state SET operating_mode = operating_mode, revision = revision WHERE deployment_state_key = ''PRIMARY''',
    'deployment no-op update'
);
SELECT pg_temp.expect_sqlstate(
    '40001',
    'UPDATE platform_meta.deployment_state SET operating_mode = ''ACTIVE'', revision = revision + 2 WHERE deployment_state_key = ''PRIMARY''',
    'deployment revision must increment exactly once'
);

SET LOCAL ROLE law_app_query;
SELECT 1 FROM lead.lead LIMIT 0;
SELECT pg_temp.expect_sqlstate('42501', 'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', 'query role INSERT');
SELECT pg_temp.expect_sqlstate('42501', 'UPDATE lead.lead SET revision = revision + 1 WHERE false', 'query role UPDATE');
SELECT pg_temp.expect_sqlstate('42501', 'DELETE FROM lead.lead WHERE false', 'query role DELETE');
SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1 FROM audit.audit_entry LIMIT 0', 'query role direct audit read');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE SCHEMA runtime_query_forbidden', 'query role CREATE SCHEMA');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE TABLE public.runtime_query_forbidden(id integer)', 'query role CREATE TABLE');
DO $$ BEGIN
    IF pg_catalog.pg_has_role(current_user, 'law_schema_migrator', 'SET') THEN
        RAISE EXCEPTION 'assertion=query role migration owner expected=false actual=true';
    END IF;
END $$;
RESET ROLE;

SET LOCAL ROLE law_audit_append;
INSERT INTO audit.audit_entry (tenant_id) SELECT NULL::uuid WHERE false;
SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1 FROM audit.audit_entry LIMIT 0', 'audit append role SELECT');
SELECT pg_temp.expect_sqlstate('42501', 'UPDATE audit.audit_entry SET tenant_id = tenant_id WHERE false', 'audit append role UPDATE');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE SCHEMA runtime_audit_forbidden', 'audit append role CREATE SCHEMA');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE TABLE public.runtime_audit_forbidden(id integer)', 'audit append role CREATE TABLE');
DO $$ BEGIN
    IF pg_catalog.pg_has_role(current_user, 'law_schema_migrator', 'SET') THEN
        RAISE EXCEPTION 'assertion=audit append role migration owner expected=false actual=true';
    END IF;
END $$;
RESET ROLE;

SET LOCAL ROLE law_app_worker;
SELECT 1 FROM execution.domain_event_outbox LIMIT 0;
SELECT 1 FROM external_action.external_action_outbox LIMIT 0;
UPDATE execution.domain_event_outbox SET status = status WHERE false;
UPDATE external_action.external_action_outbox SET status = status WHERE false;
SELECT pg_temp.expect_sqlstate('42501', 'UPDATE execution.domain_event_outbox SET tenant_id = tenant_id WHERE false', 'worker frozen outbox column');
SELECT pg_temp.expect_sqlstate('42501', 'INSERT INTO execution.domain_event_outbox (tenant_id) SELECT NULL::uuid WHERE false', 'worker outbox INSERT');
SELECT pg_temp.expect_sqlstate('42501', 'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', 'worker domain write');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE SCHEMA runtime_worker_forbidden', 'worker role CREATE SCHEMA');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE TABLE public.runtime_worker_forbidden(id integer)', 'worker role CREATE TABLE');
DO $$ BEGIN
    IF pg_catalog.pg_has_role(current_user, 'law_schema_migrator', 'SET') THEN
        RAISE EXCEPTION 'assertion=worker role migration owner expected=false actual=true';
    END IF;
END $$;
RESET ROLE;

SET LOCAL ROLE law_app_command;
SELECT 1 FROM lead.lead LIMIT 0;
INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false;
UPDATE lead.lead SET revision = revision + 1 WHERE false;
SELECT pg_temp.expect_sqlstate('42501', 'DELETE FROM lead.lead WHERE false', 'command role DELETE');
SELECT pg_temp.expect_sqlstate('42501', 'TRUNCATE lead.lead', 'command role TRUNCATE');
SELECT pg_temp.expect_sqlstate('42501', 'UPDATE lead.lead SET tenant_id = tenant_id WHERE false', 'command role frozen column');
SELECT pg_temp.expect_sqlstate('42501', 'UPDATE platform_meta.deployment_state SET revision = revision + 1', 'command role platform_meta write');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE SCHEMA runtime_command_forbidden', 'command role CREATE SCHEMA');
SELECT pg_temp.expect_sqlstate('42501', 'CREATE TABLE public.runtime_command_forbidden(id integer)', 'command role CREATE TABLE');
DO $$ BEGIN
    IF pg_catalog.pg_has_role(current_user, 'law_schema_migrator', 'SET') THEN
        RAISE EXCEPTION 'assertion=command role migration owner expected=false actual=true';
    END IF;
END $$;
RESET ROLE;

ROLLBACK;
