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
    WHERE success
      AND version IS NOT NULL
      AND type = 'SQL';
    IF actual_count <> 20 THEN
        RAISE EXCEPTION 'assertion=20 successful migrations expected=20 actual=%', actual_count;
    END IF;
    IF EXISTS (SELECT 1 FROM platform_meta.flyway_schema_history WHERE NOT success) THEN
        SELECT count(*) INTO actual_count
        FROM platform_meta.flyway_schema_history WHERE NOT success;
        RAISE EXCEPTION 'assertion=all migrations successful expected=0 failed actual=%', actual_count;
    END IF;
    SELECT max(version::integer)::text INTO actual_text
    FROM platform_meta.flyway_schema_history WHERE success;
    IF actual_text IS DISTINCT FROM '850' THEN
        RAISE EXCEPTION 'assertion=maximum migration version expected=850 actual=%', coalesce(actual_text, 'NULL');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform_meta.flyway_schema_history
        WHERE version::integer = 850 AND success
    ) THEN
        RAISE EXCEPTION 'assertion=V850 successful expected=true actual=false';
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname = ANY (application_schemas)
      AND constraint_record.contype = 'f';
    IF actual_count <> 207 THEN
        RAISE EXCEPTION 'assertion=207 composite foreign keys expected=207 actual=%', actual_count;
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
    FROM pg_catalog.pg_constraint constraint_record
    WHERE constraint_record.conname = 'fk_lead__ingress_completed_by_appointment'
      AND constraint_record.contype = 'f'
      AND constraint_record.conrelid = 'lead.lead'::pg_catalog.regclass
      AND constraint_record.confrelid = 'identity.appointment'::pg_catalog.regclass
      AND ARRAY(
          SELECT attribute_record.attname::text
          FROM pg_catalog.unnest(constraint_record.conkey)
               WITH ORDINALITY AS key_column(attnum, ordinal_no)
          JOIN pg_catalog.pg_attribute attribute_record
            ON attribute_record.attrelid = constraint_record.conrelid
           AND attribute_record.attnum = key_column.attnum
          ORDER BY key_column.ordinal_no
      ) = ARRAY['tenant_id', 'ingress_completed_by_appointment_id']::text[]
      AND ARRAY(
          SELECT attribute_record.attname::text
          FROM pg_catalog.unnest(constraint_record.confkey)
               WITH ORDINALITY AS key_column(attnum, ordinal_no)
          JOIN pg_catalog.pg_attribute attribute_record
            ON attribute_record.attrelid = constraint_record.confrelid
           AND attribute_record.attnum = key_column.attnum
          ORDER BY key_column.ordinal_no
      ) = ARRAY['tenant_id', 'appointment_id']::text[]
      AND constraint_record.confupdtype = 'a'
      AND constraint_record.confdeltype = 'a'
      AND constraint_record.confmatchtype = 's'
      AND constraint_record.convalidated
      AND NOT constraint_record.condeferrable
      AND NOT constraint_record.condeferred;
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'assertion=V850 ingress completion Appointment FK expected=1 exact catalog row actual=%', actual_count;
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

    WITH expected(
        trigger_name, trigger_type, function_oid, argument_count, argument_bytes
    ) AS (
        VALUES
        (
            'trg_lead__mutation_guard'::name,
            27::smallint,
            'platform_meta.fn_guard_controlled_update()'::pg_catalog.regprocedure,
            5::smallint,
            pg_catalog.convert_to(
                'parsed_party_id,party_resolution_code,disposition_code,current_assignment_id,revision,ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest',
                'UTF8'
            ) || pg_catalog.decode('00', 'hex') ||
            pg_catalog.convert_to(
                'ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest',
                'UTF8'
            ) || pg_catalog.decode('000000', 'hex') ||
            pg_catalog.convert_to('CONTROLLED', 'UTF8') ||
            pg_catalog.decode('00', 'hex')
        ),
        (
            'trg_lead__initial_unassigned'::name,
            7::smallint,
            'platform_meta.fn_guard_initial_nulls()'::pg_catalog.regprocedure,
            1::smallint,
            pg_catalog.convert_to(
                'current_assignment_id,ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest',
                'UTF8'
            ) || pg_catalog.decode('00', 'hex')
        )
    ), mismatches AS (
        SELECT expected.trigger_name
        FROM expected
        LEFT JOIN pg_catalog.pg_trigger trigger_record
          ON trigger_record.tgrelid = 'lead.lead'::pg_catalog.regclass
         AND trigger_record.tgname = expected.trigger_name
        WHERE trigger_record.oid IS NULL
           OR trigger_record.tgtype <> expected.trigger_type
           OR trigger_record.tgfoid <> expected.function_oid
           OR trigger_record.tgnargs <> expected.argument_count
           OR trigger_record.tgargs <> expected.argument_bytes
           OR trigger_record.tgenabled <> 'O'
           OR trigger_record.tgisinternal
           OR trigger_record.tgconstraint <> 0::oid
    )
    SELECT count(*) INTO actual_count FROM mismatches;
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=V850 Lead trigger catalog contract expected=0 mismatches actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_trigger trigger_record
    WHERE trigger_record.tgrelid = 'lead.lead'::pg_catalog.regclass
      AND trigger_record.tgname = 'trg_lead__ingress_completion_slot'
      AND trigger_record.tgtype = 19
      AND trigger_record.tgfoid =
          'platform_meta.fn_guard_lead_ingress_completion_slot()'::pg_catalog.regprocedure
      AND trigger_record.tgnargs = 0
      AND trigger_record.tgargs = pg_catalog.decode('', 'hex')
      AND trigger_record.tgenabled = 'O'
      AND NOT trigger_record.tgisinternal
      AND trigger_record.tgconstraint = 0::oid;
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'assertion=V850 ingress completion slot trigger expected=1 exact catalog row actual=%', actual_count;
    END IF;

    SELECT count(DISTINCT role_record.oid) INTO actual_count
    FROM pg_catalog.pg_roles role_record
    WHERE role_record.rolname = ANY (capability_roles);
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'assertion=four distinct capability roles expected=4 actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_proc function_record
    CROSS JOIN LATERAL pg_catalog.aclexplode(
        COALESCE(
            function_record.proacl,
            pg_catalog.acldefault('f', function_record.proowner)
        )
    ) AS privilege_record
    WHERE function_record.oid =
          'platform_meta.fn_guard_lead_ingress_completion_slot()'::pg_catalog.regprocedure
      AND privilege_record.grantee = 0
      AND privilege_record.privilege_type = 'EXECUTE';
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=V850 ingress completion guard PUBLIC EXECUTE expected=0 grants actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.unnest(capability_roles) AS capability(role_name)
    WHERE pg_catalog.has_function_privilege(
        capability.role_name,
        'platform_meta.fn_guard_lead_ingress_completion_slot()'::pg_catalog.regprocedure,
        'EXECUTE'
    ) IS TRUE;
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=V850 ingress completion guard capability EXECUTE expected=0 roles actual=%', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.unnest(ARRAY[
        'ingress_completion_phone_ciphertext',
        'ingress_completion_phone_hmac',
        'ingress_completion_email_ciphertext',
        'ingress_completion_email_hmac',
        'ingress_completion_source_code',
        'ingress_completion_source_summary_ciphertext',
        'ingress_completed_by_appointment_id',
        'ingress_completed_at',
        'ingress_completion_digest'
    ]::text[]) AS completion_column(column_name)
    WHERE pg_catalog.has_column_privilege(
        '${app_query_role}',
        'lead.lead',
        completion_column.column_name,
        'SELECT'
    ) IS TRUE;
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'assertion=V850 query role completion SELECT expected=0 columns actual=%', actual_count;
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
      AND schema_contract_version = '52-plus-2-v1.1'
      AND revision = 1
      AND active_release_digest = pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex')
      AND active_manifest_hash = pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex')
      AND pg_catalog.octet_length(active_release_digest) = 32
      AND pg_catalog.octet_length(active_manifest_hash) = 32;
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'assertion=deployment_state PRIMARY/BLOCKED/52-plus-2-v1.1/revision=1 with 32 zero bytes expected=1 actual=%', actual_count;
    END IF;
END;
$schema_contract$;
