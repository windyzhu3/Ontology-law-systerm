WITH catalog_lines(section_name, object_key, definition) AS (
    SELECT '00-server', 'server_version', current_setting('server_version')
    UNION ALL
    SELECT '10-schema', namespace_record.nspname::text, coalesce(pg_catalog.obj_description(namespace_record.oid, 'pg_namespace'), '')
    FROM pg_catalog.pg_namespace namespace_record
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    )
    UNION ALL
    SELECT '20-table', namespace_record.nspname || '.' || table_record.relname,
           table_record.relkind::text || '|' || coalesce(pg_catalog.obj_description(table_record.oid, 'pg_class'), '')
    FROM pg_catalog.pg_class table_record
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    ) AND table_record.relkind IN ('r', 'p', 'v')
    UNION ALL
    SELECT '30-column', namespace_record.nspname || '.' || table_record.relname || '.' || column_record.attname,
           column_record.attnum::text || '|' || pg_catalog.format_type(column_record.atttypid, column_record.atttypmod)
             || '|' || column_record.attnotnull::text || '|'
             || coalesce(pg_catalog.pg_get_expr(default_record.adbin, default_record.adrelid), '') || '|'
             || coalesce(pg_catalog.col_description(table_record.oid, column_record.attnum), '')
    FROM pg_catalog.pg_class table_record
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    JOIN pg_catalog.pg_attribute column_record ON column_record.attrelid = table_record.oid
    LEFT JOIN pg_catalog.pg_attrdef default_record
      ON default_record.adrelid = table_record.oid AND default_record.adnum = column_record.attnum
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    ) AND table_record.relkind IN ('r', 'p', 'v')
      AND column_record.attnum > 0 AND NOT column_record.attisdropped
    UNION ALL
    SELECT '40-constraint', namespace_record.nspname || '.' || table_record.relname || '.' || constraint_record.conname,
           pg_catalog.pg_get_constraintdef(constraint_record.oid, true) || '|'
             || constraint_record.convalidated::text || '|' || constraint_record.condeferrable::text
             || '|' || constraint_record.condeferred::text
    FROM pg_catalog.pg_constraint constraint_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = constraint_record.conrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    )
    UNION ALL
    SELECT '50-index', namespace_record.nspname || '.' || index_record.relname,
           pg_catalog.pg_get_indexdef(index_record.oid)
    FROM pg_catalog.pg_class index_record
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = index_record.relnamespace
    JOIN pg_catalog.pg_index index_metadata ON index_metadata.indexrelid = index_record.oid
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    )
    UNION ALL
    SELECT '60-trigger', namespace_record.nspname || '.' || table_record.relname || '.' || trigger_record.tgname,
           pg_catalog.pg_get_triggerdef(trigger_record.oid, true)
    FROM pg_catalog.pg_trigger trigger_record
    JOIN pg_catalog.pg_class table_record ON table_record.oid = trigger_record.tgrelid
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
    WHERE namespace_record.nspname IN (
        'identity', 'audit', 'responsibility', 'execution', 'external_action',
        'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract',
        'transfer', 'platform_meta'
    ) AND NOT trigger_record.tgisinternal
    UNION ALL
    SELECT '70-function', namespace_record.nspname || '.' || function_record.proname
             || '(' || pg_catalog.pg_get_function_identity_arguments(function_record.oid) || ')',
           pg_catalog.pg_get_functiondef(function_record.oid)
    FROM pg_catalog.pg_proc function_record
    JOIN pg_catalog.pg_namespace namespace_record ON namespace_record.oid = function_record.pronamespace
    WHERE namespace_record.nspname = 'platform_meta'
    UNION ALL
    SELECT '80-flyway', installed_rank::text,
           coalesce(version, '') || '|' || description || '|' || type || '|' || script || '|'
             || coalesce(checksum::text, '') || '|' || success::text
    FROM platform_meta.flyway_schema_history
), stable_catalog AS (
    SELECT pg_catalog.string_agg(
        section_name || E'\x1f' || object_key || E'\x1f' || definition,
        E'\x1e' ORDER BY section_name, object_key, definition
    ) AS serialized
    FROM catalog_lines
)
SELECT pg_catalog.json_build_object(
    'fingerprint', pg_catalog.md5(serialized),
    'postgresVersion', pg_catalog.version(),
    'serverVersion', current_setting('server_version'),
    'status', 'PASSED'
)
FROM stable_catalog;
