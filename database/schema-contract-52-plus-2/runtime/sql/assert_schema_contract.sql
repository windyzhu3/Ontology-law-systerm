-- Runtime catalog assertions. Every exception names the assertion and prints expected/actual.
DO $schema_contract$
DECLARE
    actual_count integer;
    actual_text text;
    capability_roles constant text[] := ARRAY[
        'law_app_command', 'law_app_worker', 'law_app_query', 'law_audit_append'
    ];
    managed_schemas constant text[] := ARRAY[
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    ];
    application_schemas constant text[] := ARRAY[
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer'
    ];
BEGIN
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_namespace
    WHERE nspname !~ '^pg_'
      AND nspname NOT IN ('information_schema', 'public');
    IF actual_count <> 13 THEN
        RAISE EXCEPTION 'assertion=13 managed schemas expected=13 actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_namespace
    WHERE nspname !~ '^pg_'
      AND nspname NOT IN ('information_schema', 'public')
      AND nspname <> ALL (managed_schemas);
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=managed schema allowlist expected=0 unexpected actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname = ANY (application_schemas);
    IF actual_count <> 52 THEN
        RAISE EXCEPTION 'assertion=52 application tables expected=52 actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname = 'platform_meta';
    IF actual_count <> 2 OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_tables
        WHERE schemaname = 'platform_meta'
          AND tablename NOT IN ('deployment_state', 'flyway_schema_history')
    ) THEN
        RAISE EXCEPTION 'assertion=2 platform_meta tables expected=2 exact tables actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname = 'public';
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=public schema table count expected=0 actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM platform_meta.flyway_schema_history
    WHERE success;
    IF actual_count <> 19 THEN
        RAISE EXCEPTION 'assertion=19 successful migrations expected=19 actual=%', actual_count;
    END IF;
    IF EXISTS (SELECT 1 FROM platform_meta.flyway_schema_history WHERE NOT success) THEN
        SELECT count(*) INTO actual_count
        FROM platform_meta.flyway_schema_history WHERE NOT success;
        RAISE EXCEPTION 'assertion=all migrations successful expected=0 failed actual=%', actual_count;
    END IF;
    SELECT max(version::integer)::text INTO actual_text
    FROM platform_meta.flyway_schema_history WHERE success;
    IF actual_text IS DISTINCT FROM '840' THEN
        RAISE EXCEPTION 'assertion=maximum migration version expected=840 actual=%', coalesce(actual_text, 'NULL');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform_meta.flyway_schema_history
        WHERE version::integer = 840 AND success
    ) THEN
        RAISE EXCEPTION 'assertion=V840 successful expected=true actual=false';
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname = ANY (application_schemas)
      AND constraint_record.contype = 'f';
    IF actual_count <> 206 THEN
        RAISE EXCEPTION 'assertion=206 composite foreign keys expected=206 actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname = ANY (application_schemas)
      AND constraint_record.contype = 'f'
      AND (constraint_record.confupdtype <> 'a' OR constraint_record.confdeltype <> 'a');
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=application foreign keys NO ACTION expected=0 violations actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname = ANY (application_schemas)
      AND constraint_record.contype = 'f'
      AND (NOT constraint_record.convalidated OR constraint_record.confmatchtype <> 's');
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=validated MATCH SIMPLE foreign keys expected=0 violations actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class child_table ON child_table.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace child_schema ON child_schema.oid = child_table.relnamespace
    JOIN pg_catalog.pg_attribute child_column
      ON child_column.attrelid = child_table.oid AND child_column.attnum = constraint_record.conkey[1]
    JOIN pg_catalog.pg_class parent_table ON parent_table.oid = constraint_record.confrelid
    JOIN pg_catalog.pg_attribute parent_column
      ON parent_column.attrelid = parent_table.oid AND parent_column.attnum = constraint_record.confkey[1]
    WHERE child_schema.nspname = ANY (application_schemas)
      AND constraint_record.contype = 'f'
      AND (child_column.attname <> 'tenant_id' OR parent_column.attname <> 'tenant_id');
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=tenant_id first in tenant foreign keys expected=0 violations actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_class table_record
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname = ANY (managed_schemas)
      AND table_record.relkind IN ('r', 'p')
      AND table_record.relname <> 'flyway_schema_history'
      AND EXISTS (
          SELECT 1 FROM pg_catalog.pg_trigger trigger_record
          WHERE trigger_record.tgrelid = table_record.oid
            AND NOT trigger_record.tgisinternal
            AND trigger_record.tgname = 'trg_' || table_record.relname || '__mutation_guard'
      );
    IF actual_count <> 53 THEN
        RAISE EXCEPTION 'assertion=53 mutation guards expected=53 actual=%', actual_count;
    END IF;

    SELECT count(DISTINCT role_record.oid) INTO actual_count
    FROM pg_catalog.pg_roles role_record
    WHERE role_record.rolname = ANY (capability_roles);
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'assertion=four distinct capability roles expected=4 actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_roles role_record
    WHERE role_record.rolname = ANY (capability_roles) AND role_record.rolcanlogin;
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=capability roles NOLOGIN expected=0 LOGIN roles actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_roles role_record
    JOIN pg_catalog.pg_auth_members membership_record ON membership_record.member = role_record.oid
    WHERE role_record.rolname = ANY (capability_roles);
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=capability parent role memberships expected=0 actual=%', actual_count;
    END IF;
    SELECT count(*) INTO actual_count
    FROM pg_catalog.unnest(capability_roles) AS capability(role_name)
    WHERE pg_catalog.pg_has_role(capability.role_name, 'law_schema_migrator', 'MEMBER');
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=capability roles cannot obtain migration owner expected=0 actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM platform_meta.deployment_state
    WHERE deployment_state_key = 'PRIMARY'
      AND operating_mode = 'BLOCKED'
      AND schema_contract_version = '52-plus-2-v1'
      AND revision = 0
      AND active_release_digest = pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex')
      AND active_manifest_hash = pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex')
      AND pg_catalog.octet_length(active_release_digest) = 32
      AND pg_catalog.octet_length(active_manifest_hash) = 32;
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'assertion=deployment_state PRIMARY/BLOCKED/52-plus-2-v1/revision=0 with 32 zero bytes expected=1 actual=%', actual_count;
    END IF;
END;
$schema_contract$;
