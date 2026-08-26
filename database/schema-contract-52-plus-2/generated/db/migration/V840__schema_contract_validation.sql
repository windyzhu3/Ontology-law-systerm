-- 安装时结构合同断言；任一偏离都会令迁移失败。
DO $$
DECLARE
    actual_count integer;
    missing_count integer;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_namespace namespace
        WHERE namespace.nspname !~ '^pg_'
          AND namespace.nspname <> 'information_schema'
          AND namespace.nspname NOT IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta', 'public')
    ) THEN
        RAISE EXCEPTION 'unexpected user schema violates the dedicated database contract';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
    ) THEN
        RAISE EXCEPTION 'unexpected user table exists outside the 52 plus 2 ledger';
    END IF;

    SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables WHERE schemaname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer');
    IF actual_count <> 52 THEN
        RAISE EXCEPTION 'expected 52 application tables, found %', actual_count;
    END IF;

    WITH expected(schema_name, table_name) AS (VALUES
            ('identity', 'tenant'),
            ('identity', 'principal'),
            ('identity', 'organization_unit'),
            ('identity', 'appointment'),
            ('identity', 'authority_grant'),
            ('identity', 'delegation_grant'),
            ('identity', 'object_access_grant'),
            ('audit', 'audit_entry'),
            ('responsibility', 'task_occurrence'),
            ('responsibility', 'decision_record'),
            ('responsibility', 'wait_receipt'),
            ('responsibility', 'action_draft'),
            ('execution', 'command_execution_slot'),
            ('execution', 'command_receipt'),
            ('execution', 'domain_event'),
            ('execution', 'domain_event_outbox'),
            ('external_action', 'external_action'),
            ('external_action', 'external_action_outbox'),
            ('external_action', 'provider_inbox'),
            ('evidence', 'upload_session'),
            ('evidence', 'received_source_object'),
            ('evidence', 'evidence_submission'),
            ('evidence', 'evidence_binding'),
            ('party', 'party'),
            ('lead', 'lead'),
            ('lead', 'lead_assignment'),
            ('lead', 'lead_contact_result'),
            ('opportunity', 'opportunity'),
            ('opportunity', 'opportunity_participation'),
            ('opportunity', 'opportunity_progress'),
            ('opportunity', 'quote_revision'),
            ('opportunity', 'quote_service_scope'),
            ('opportunity', 'quote_line'),
            ('opportunity', 'quote_payment_term'),
            ('opportunity', 'quote_issue'),
            ('opportunity', 'quote_response'),
            ('conflict', 'conflict_review'),
            ('conflict', 'conflict_review_party'),
            ('conflict', 'conflict_finding'),
            ('contract', 'contract'),
            ('contract', 'contract_revision'),
            ('contract', 'contract_participation'),
            ('contract', 'contract_fee_term'),
            ('contract', 'payment_gate'),
            ('contract', 'signature_plan'),
            ('contract', 'contract_signature'),
            ('contract', 'contract_execution'),
            ('contract', 'payment_confirmation'),
            ('contract', 'contract_termination'),
            ('transfer', 'transfer_request'),
            ('transfer', 'transfer_snapshot'),
            ('transfer', 'transfer_return_item')
    )
    SELECT count(*) INTO missing_count
    FROM expected e
    LEFT JOIN pg_catalog.pg_tables t ON t.schemaname = e.schema_name AND t.tablename = e.table_name
    WHERE t.tablename IS NULL;
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'frozen application table ledger is incomplete: % missing', missing_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname = 'platform_meta';
    IF actual_count <> 2 OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_tables
        WHERE schemaname = 'platform_meta'
          AND tablename NOT IN ('deployment_state', 'flyway_schema_history')
    ) THEN
        RAISE EXCEPTION 'expected 2 platform_meta tables (deployment_state and flyway_schema_history), found %', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta');
    IF actual_count <> 54 THEN
        RAISE EXCEPTION 'expected 54 managed tables, found %', actual_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_namespace n
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND coalesce(pg_catalog.obj_description(n.oid, 'pg_namespace'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'schema comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND c.relkind IN ('r', 'p')
      AND coalesce(pg_catalog.obj_description(c.oid, 'pg_class'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'table comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped
      AND coalesce(pg_catalog.col_description(c.oid, a.attnum), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'column comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_constraint con
    JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND c.relname <> 'flyway_schema_history'
      AND con.contype IN ('c', 'f', 'p', 'u', 'x')
      AND coalesce(pg_catalog.obj_description(con.oid, 'pg_constraint'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'constraint comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_class i
    JOIN pg_catalog.pg_namespace n ON n.oid = i.relnamespace
    JOIN pg_catalog.pg_index ix ON ix.indexrelid = i.oid
    JOIN pg_catalog.pg_class c ON c.oid = ix.indrelid
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND c.relname <> 'flyway_schema_history'
      AND coalesce(pg_catalog.obj_description(i.oid, 'pg_class'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'index comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_proc p
    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'platform_meta' AND p.proname LIKE 'fn_%'
      AND coalesce(pg_catalog.obj_description(p.oid, 'pg_proc'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'function comments missing: %', missing_count;
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_trigger t
    JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND NOT t.tgisinternal
      AND coalesce(pg_catalog.obj_description(t.oid, 'pg_trigger'), '') = '';
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'trigger comments missing: %', missing_count;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer') AND con.contype = 'f'
          AND (con.confupdtype <> 'a' OR con.confdeltype <> 'a')
    ) THEN
        RAISE EXCEPTION 'all application foreign keys must use NO ACTION';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class child ON child.oid = con.conrelid
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = child.relnamespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer')
          AND con.contype = 'f'
          AND (NOT con.convalidated OR con.confmatchtype <> 's')
    ) THEN
        RAISE EXCEPTION 'foreign key must be validated with MATCH SIMPLE';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        JOIN pg_catalog.pg_roles role ON role.rolname = application_role.role_name
        WHERE role.rolcanlogin
    ) THEN
        RAISE EXCEPTION 'application capability role must be NOLOGIN';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class child_table ON child_table.oid = con.conrelid
        JOIN pg_catalog.pg_namespace child_ns ON child_ns.oid = child_table.relnamespace
        JOIN pg_catalog.pg_attribute child_column
          ON child_column.attrelid = child_table.oid AND child_column.attnum = con.conkey[1]
        JOIN pg_catalog.pg_class parent_table ON parent_table.oid = con.confrelid
        JOIN pg_catalog.pg_attribute parent_column
          ON parent_column.attrelid = parent_table.oid AND parent_column.attnum = con.confkey[1]
        WHERE child_ns.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer')
          AND con.contype = 'f'
          AND (child_column.attname <> 'tenant_id' OR parent_column.attname <> 'tenant_id')
    ) THEN
        RAISE EXCEPTION 'tenant_id must be the first column of every tenant foreign key';
    END IF;

    WITH expected(
        child_schema, child_table, constraint_name, child_columns,
        parent_schema, parent_table, parent_columns, is_deferrable, is_deferred
    ) AS (VALUES
            ('identity', 'principal', 'fk_principal__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'organization_unit', 'fk_organization_unit__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'organization_unit', 'fk_organization_unit__parent_organization_unit', ARRAY['tenant_id', 'parent_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], true, true),
            ('identity', 'appointment', 'fk_appointment__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'appointment', 'fk_appointment__principal', ARRAY['tenant_id', 'principal_id']::text[], 'identity', 'principal', ARRAY['tenant_id', 'principal_id']::text[], false, false),
            ('identity', 'appointment', 'fk_appointment__organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('identity', 'authority_grant', 'fk_authority_grant__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'authority_grant', 'fk_authority_grant__grantee_appointment', ARRAY['tenant_id', 'grantee_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('identity', 'authority_grant', 'fk_authority_grant__granted_by_appointment', ARRAY['tenant_id', 'granted_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('identity', 'authority_grant', 'fk_authority_grant__scope_org', ARRAY['tenant_id', 'scope_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('identity', 'delegation_grant', 'fk_delegation_grant__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'delegation_grant', 'fk_delegation_grant__source_grantee', ARRAY['tenant_id', 'source_authority_grant_id', 'delegator_appointment_id']::text[], 'identity', 'authority_grant', ARRAY['tenant_id', 'authority_grant_id', 'grantee_appointment_id']::text[], false, false),
            ('identity', 'delegation_grant', 'fk_delegation_grant__delegator_appointment', ARRAY['tenant_id', 'delegator_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('identity', 'delegation_grant', 'fk_delegation_grant__delegate_appointment', ARRAY['tenant_id', 'delegate_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('identity', 'delegation_grant', 'fk_delegation_grant__scope_org', ARRAY['tenant_id', 'scope_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('identity', 'object_access_grant', 'fk_object_access_grant__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('identity', 'object_access_grant', 'fk_object_access_grant__grantee_principal', ARRAY['tenant_id', 'grantee_principal_id']::text[], 'identity', 'principal', ARRAY['tenant_id', 'principal_id']::text[], false, false),
            ('identity', 'object_access_grant', 'fk_object_access_grant__granted_by_appointment', ARRAY['tenant_id', 'granted_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__actor_principal', ARRAY['tenant_id', 'actor_principal_id']::text[], 'identity', 'principal', ARRAY['tenant_id', 'principal_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__actor_appointment', ARRAY['tenant_id', 'actor_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__on_behalf_principal', ARRAY['tenant_id', 'on_behalf_of_principal_id']::text[], 'identity', 'principal', ARRAY['tenant_id', 'principal_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__on_behalf_of_appointment', ARRAY['tenant_id', 'on_behalf_of_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__actor_appointment_principal', ARRAY['tenant_id', 'actor_appointment_id', 'actor_principal_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id', 'principal_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__on_behalf_appointment_principal', ARRAY['tenant_id', 'on_behalf_of_appointment_id', 'on_behalf_of_principal_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id', 'principal_id']::text[], false, false),
            ('audit', 'audit_entry', 'fk_audit_entry__authorization_scope_org', ARRAY['tenant_id', 'authorization_scope_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('responsibility', 'task_occurrence', 'fk_task_occurrence__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('responsibility', 'task_occurrence', 'fk_task_occurrence__owner_appointment', ARRAY['tenant_id', 'owner_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('responsibility', 'decision_record', 'fk_decision_record__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('responsibility', 'decision_record', 'fk_decision_record__task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], 'responsibility', 'task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], false, false),
            ('responsibility', 'decision_record', 'fk_decision_record__predecessor', ARRAY['tenant_id', 'predecessor_decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false),
            ('responsibility', 'decision_record', 'fk_decision_record__decided_by_appointment', ARRAY['tenant_id', 'decided_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('responsibility', 'wait_receipt', 'fk_wait_receipt__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('responsibility', 'wait_receipt', 'fk_wait_receipt__task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], 'responsibility', 'task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], false, false),
            ('responsibility', 'wait_receipt', 'fk_wait_receipt__recorded_by_appointment', ARRAY['tenant_id', 'recorded_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('responsibility', 'action_draft', 'fk_action_draft__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('responsibility', 'action_draft', 'fk_action_draft__task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], 'responsibility', 'task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], false, false),
            ('responsibility', 'action_draft', 'fk_action_draft__created_by_appointment', ARRAY['tenant_id', 'created_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('responsibility', 'action_draft', 'fk_action_draft__confirmed_by_appointment', ARRAY['tenant_id', 'confirmed_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('execution', 'command_execution_slot', 'fk_command_execution_slot__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('execution', 'command_receipt', 'fk_command_receipt__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('execution', 'command_receipt', 'fk_command_receipt__command_execution_slot', ARRAY['tenant_id', 'command_execution_slot_id']::text[], 'execution', 'command_execution_slot', ARRAY['tenant_id', 'command_execution_slot_id']::text[], false, false),
            ('execution', 'domain_event', 'fk_domain_event__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('execution', 'domain_event_outbox', 'fk_domain_event_outbox__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('execution', 'domain_event_outbox', 'fk_domain_event_outbox__domain_event', ARRAY['tenant_id', 'domain_event_id']::text[], 'execution', 'domain_event', ARRAY['tenant_id', 'domain_event_id']::text[], false, false),
            ('external_action', 'external_action', 'fk_external_action__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('external_action', 'external_action_outbox', 'fk_external_action_outbox__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('external_action', 'external_action_outbox', 'fk_external_action_outbox__external_action', ARRAY['tenant_id', 'external_action_id']::text[], 'external_action', 'external_action', ARRAY['tenant_id', 'external_action_id']::text[], false, false),
            ('external_action', 'provider_inbox', 'fk_provider_inbox__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('external_action', 'provider_inbox', 'fk_provider_inbox__external_action', ARRAY['tenant_id', 'external_action_id']::text[], 'external_action', 'external_action', ARRAY['tenant_id', 'external_action_id']::text[], false, false),
            ('evidence', 'upload_session', 'fk_upload_session__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('evidence', 'upload_session', 'fk_upload_session__creator', ARRAY['tenant_id', 'created_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('evidence', 'received_source_object', 'fk_received_source_object__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('evidence', 'received_source_object', 'fk_received_source_object__upload_session', ARRAY['tenant_id', 'upload_session_id']::text[], 'evidence', 'upload_session', ARRAY['tenant_id', 'upload_session_id']::text[], false, false),
            ('evidence', 'evidence_submission', 'fk_evidence_submission__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('evidence', 'evidence_submission', 'fk_evidence_submission__received_source_object', ARRAY['tenant_id', 'received_source_object_id']::text[], 'evidence', 'received_source_object', ARRAY['tenant_id', 'received_source_object_id']::text[], false, false),
            ('evidence', 'evidence_submission', 'fk_evidence_submission__submitter', ARRAY['tenant_id', 'submitted_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('evidence', 'evidence_binding', 'fk_evidence_binding__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('evidence', 'evidence_binding', 'fk_evidence_binding__evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('evidence', 'evidence_binding', 'fk_evidence_binding__binder', ARRAY['tenant_id', 'bound_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('evidence', 'evidence_binding', 'fk_evidence_binding__revoker', ARRAY['tenant_id', 'revoked_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('party', 'party', 'fk_party__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('party', 'party', 'fk_party__merged_into_party', ARRAY['tenant_id', 'merged_into_party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('lead', 'lead', 'fk_lead__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('lead', 'lead', 'fk_lead__parsed_party', ARRAY['tenant_id', 'parsed_party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('lead', 'lead', 'fk_lead__current_assignment', ARRAY['tenant_id', 'current_assignment_id']::text[], 'lead', 'lead_assignment', ARRAY['tenant_id', 'lead_assignment_id']::text[], false, false),
            ('lead', 'lead_assignment', 'fk_lead_assignment__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('lead', 'lead_assignment', 'fk_lead_assignment__lead', ARRAY['tenant_id', 'lead_id']::text[], 'lead', 'lead', ARRAY['tenant_id', 'lead_id']::text[], false, false),
            ('lead', 'lead_assignment', 'fk_lead_assignment__previous_assignment', ARRAY['tenant_id', 'previous_assignment_id']::text[], 'lead', 'lead_assignment', ARRAY['tenant_id', 'lead_assignment_id']::text[], false, false),
            ('lead', 'lead_assignment', 'fk_lead_assignment__owner_appointment', ARRAY['tenant_id', 'owner_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('lead', 'lead_contact_result', 'fk_lead_contact_result__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('lead', 'lead_contact_result', 'fk_lead_contact_result__lead', ARRAY['tenant_id', 'lead_id']::text[], 'lead', 'lead', ARRAY['tenant_id', 'lead_id']::text[], false, false),
            ('lead', 'lead_contact_result', 'fk_lead_contact_result__lead_assignment', ARRAY['tenant_id', 'lead_assignment_id']::text[], 'lead', 'lead_assignment', ARRAY['tenant_id', 'lead_assignment_id']::text[], false, false),
            ('lead', 'lead_contact_result', 'fk_lead_contact_result__contact_task', ARRAY['tenant_id', 'contact_task_id']::text[], 'responsibility', 'task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], false, false),
            ('lead', 'lead_contact_result', 'fk_lead_contact_result__evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__source_lead', ARRAY['tenant_id', 'source_lead_id']::text[], 'lead', 'lead', ARRAY['tenant_id', 'lead_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__source_assignment', ARRAY['tenant_id', 'source_assignment_id']::text[], 'lead', 'lead_assignment', ARRAY['tenant_id', 'lead_assignment_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__owner_appointment', ARRAY['tenant_id', 'owner_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__source_contact_result', ARRAY['tenant_id', 'source_contact_result_id']::text[], 'lead', 'lead_contact_result', ARRAY['tenant_id', 'lead_contact_result_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__assignment_path', ARRAY['tenant_id', 'source_assignment_id', 'source_lead_id', 'owner_appointment_id']::text[], 'lead', 'lead_assignment', ARRAY['tenant_id', 'lead_assignment_id', 'lead_id', 'owner_appointment_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__contact_path', ARRAY['tenant_id', 'source_contact_result_id', 'source_lead_id', 'source_assignment_id']::text[], 'lead', 'lead_contact_result', ARRAY['tenant_id', 'lead_contact_result_id', 'lead_id', 'lead_assignment_id']::text[], false, false),
            ('opportunity', 'opportunity', 'fk_opportunity__current_quote_revision', ARRAY['tenant_id', 'current_quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'opportunity_participation', 'fk_opportunity_participation__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'opportunity_participation', 'fk_opportunity_participation__opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], 'opportunity', 'opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], false, false),
            ('opportunity', 'opportunity_participation', 'fk_opportunity_participation__party', ARRAY['tenant_id', 'party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('opportunity', 'opportunity_progress', 'fk_opportunity_progress__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'opportunity_progress', 'fk_opportunity_progress__opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], 'opportunity', 'opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], false, false),
            ('opportunity', 'quote_revision', 'fk_quote_revision__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_revision', 'fk_quote_revision__opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], 'opportunity', 'opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], false, false),
            ('opportunity', 'quote_revision', 'fk_quote_revision__predecessor', ARRAY['tenant_id', 'predecessor_quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'quote_revision', 'fk_quote_revision__action_draft', ARRAY['tenant_id', 'confirmed_action_draft_id']::text[], 'responsibility', 'action_draft', ARRAY['tenant_id', 'action_draft_id']::text[], false, false),
            ('opportunity', 'quote_revision', 'fk_quote_revision__creator', ARRAY['tenant_id', 'created_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('opportunity', 'quote_service_scope', 'fk_quote_service_scope__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_service_scope', 'fk_quote_service_scope__quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'quote_line', 'fk_quote_line__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_line', 'fk_quote_line__quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'quote_line', 'fk_quote_line__service_scope', ARRAY['tenant_id', 'quote_service_scope_id']::text[], 'opportunity', 'quote_service_scope', ARRAY['tenant_id', 'quote_service_scope_id']::text[], false, false),
            ('opportunity', 'quote_payment_term', 'fk_quote_payment_term__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_payment_term', 'fk_quote_payment_term__quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__recipient_participation', ARRAY['tenant_id', 'recipient_participation_id']::text[], 'opportunity', 'opportunity_participation', ARRAY['tenant_id', 'opportunity_participation_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__external_action', ARRAY['tenant_id', 'external_action_id']::text[], 'external_action', 'external_action', ARRAY['tenant_id', 'external_action_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], 'external_action', 'provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], false, false),
            ('opportunity', 'quote_issue', 'fk_quote_issue__replaces', ARRAY['tenant_id', 'replaces_quote_issue_id']::text[], 'opportunity', 'quote_issue', ARRAY['tenant_id', 'quote_issue_id']::text[], false, false),
            ('opportunity', 'quote_response', 'fk_quote_response__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('opportunity', 'quote_response', 'fk_quote_response__issue', ARRAY['tenant_id', 'quote_issue_id']::text[], 'opportunity', 'quote_issue', ARRAY['tenant_id', 'quote_issue_id']::text[], false, false),
            ('opportunity', 'quote_response', 'fk_quote_response__provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], 'external_action', 'provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], false, false),
            ('opportunity', 'quote_response', 'fk_quote_response__evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('opportunity', 'quote_response', 'fk_quote_response__recorder', ARRAY['tenant_id', 'recorded_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('conflict', 'conflict_review', 'fk_conflict_review__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('conflict', 'conflict_review_party', 'fk_conflict_review_party__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('conflict', 'conflict_review_party', 'fk_conflict_review_party__conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], 'conflict', 'conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], false, false),
            ('conflict', 'conflict_review_party', 'fk_conflict_review_party__party', ARRAY['tenant_id', 'party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('conflict', 'conflict_finding', 'fk_conflict_finding__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('conflict', 'conflict_finding', 'fk_conflict_finding__conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], 'conflict', 'conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], false, false),
            ('conflict', 'conflict_finding', 'fk_conflict_finding__review_party', ARRAY['tenant_id', 'conflict_review_party_id']::text[], 'conflict', 'conflict_review_party', ARRAY['tenant_id', 'conflict_review_party_id']::text[], false, false),
            ('conflict', 'conflict_finding', 'fk_conflict_finding__evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'contract', 'fk_contract__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract', 'fk_contract__opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], 'opportunity', 'opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], false, false),
            ('contract', 'contract', 'fk_contract__accepted_quote_response', ARRAY['tenant_id', 'accepted_quote_response_id']::text[], 'opportunity', 'quote_response', ARRAY['tenant_id', 'quote_response_id']::text[], false, false),
            ('contract', 'contract', 'fk_contract__current_revision', ARRAY['tenant_id', 'current_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], true, true),
            ('contract', 'contract', 'fk_contract__approved_revision', ARRAY['tenant_id', 'approved_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], true, true),
            ('contract', 'contract', 'fk_contract__execution', ARRAY['tenant_id', 'contract_execution_id']::text[], 'contract', 'contract_execution', ARRAY['tenant_id', 'contract_execution_id']::text[], true, true),
            ('contract', 'contract', 'fk_contract__termination', ARRAY['tenant_id', 'contract_termination_id']::text[], 'contract', 'contract_termination', ARRAY['tenant_id', 'contract_termination_id']::text[], true, true),
            ('contract', 'contract_revision', 'fk_contract_revision__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__contract', ARRAY['tenant_id', 'contract_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__predecessor', ARRAY['tenant_id', 'predecessor_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__action_draft', ARRAY['tenant_id', 'confirmed_action_draft_id']::text[], 'responsibility', 'action_draft', ARRAY['tenant_id', 'action_draft_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__quote_revision', ARRAY['tenant_id', 'source_quote_revision_id']::text[], 'opportunity', 'quote_revision', ARRAY['tenant_id', 'quote_revision_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__quote_response', ARRAY['tenant_id', 'source_quote_response_id']::text[], 'opportunity', 'quote_response', ARRAY['tenant_id', 'quote_response_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__body_evidence', ARRAY['tenant_id', 'body_evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__pre_contract_review', ARRAY['tenant_id', 'pre_contract_review_id']::text[], 'conflict', 'conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], false, false),
            ('contract', 'contract_revision', 'fk_contract_revision__creator', ARRAY['tenant_id', 'created_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('contract', 'contract_participation', 'fk_contract_participation__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_participation', 'fk_contract_participation__contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_participation', 'fk_contract_participation__party', ARRAY['tenant_id', 'party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('contract', 'contract_participation', 'fk_contract_participation__source_opportunity_participation', ARRAY['tenant_id', 'source_opportunity_participation_id']::text[], 'opportunity', 'opportunity_participation', ARRAY['tenant_id', 'opportunity_participation_id']::text[], false, false),
            ('contract', 'contract_fee_term', 'fk_contract_fee_term__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_fee_term', 'fk_contract_fee_term__contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_fee_term', 'fk_contract_fee_term__source_quote_line', ARRAY['tenant_id', 'source_quote_line_id']::text[], 'opportunity', 'quote_line', ARRAY['tenant_id', 'quote_line_id']::text[], false, false),
            ('contract', 'payment_gate', 'fk_payment_gate__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'payment_gate', 'fk_payment_gate__contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'payment_gate', 'fk_payment_gate__risk_decision', ARRAY['tenant_id', 'risk_decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false),
            ('contract', 'signature_plan', 'fk_signature_plan__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'signature_plan', 'fk_signature_plan__contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'signature_plan', 'fk_signature_plan__participation', ARRAY['tenant_id', 'contract_participation_id']::text[], 'contract', 'contract_participation', ARRAY['tenant_id', 'contract_participation_id']::text[], false, false),
            ('contract', 'signature_plan', 'fk_signature_plan__signer_party', ARRAY['tenant_id', 'signer_party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('contract', 'signature_plan', 'fk_signature_plan__participation_path', ARRAY['tenant_id', 'contract_participation_id', 'contract_revision_id', 'signer_party_id']::text[], 'contract', 'contract_participation', ARRAY['tenant_id', 'contract_participation_id', 'contract_revision_id', 'party_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__plan', ARRAY['tenant_id', 'signature_plan_id']::text[], 'contract', 'signature_plan', ARRAY['tenant_id', 'signature_plan_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__evidence', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__external_action', ARRAY['tenant_id', 'external_action_id']::text[], 'external_action', 'external_action', ARRAY['tenant_id', 'external_action_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], 'external_action', 'provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__signer_party', ARRAY['tenant_id', 'signer_party_id']::text[], 'party', 'party', ARRAY['tenant_id', 'party_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__revoker', ARRAY['tenant_id', 'revoked_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('contract', 'contract_signature', 'fk_contract_signature__plan_path', ARRAY['tenant_id', 'signature_plan_id', 'contract_revision_id', 'signer_party_id']::text[], 'contract', 'signature_plan', ARRAY['tenant_id', 'signature_plan_id', 'contract_revision_id', 'signer_party_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__contract', ARRAY['tenant_id', 'contract_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__revision_contract', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__archive_evidence', ARRAY['tenant_id', 'archive_evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'contract_execution', 'fk_contract_execution__executor', ARRAY['tenant_id', 'executed_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__contract', ARRAY['tenant_id', 'contract_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__revision_contract', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__external_action', ARRAY['tenant_id', 'external_action_id']::text[], 'external_action', 'external_action', ARRAY['tenant_id', 'external_action_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], 'external_action', 'provider_inbox', ARRAY['tenant_id', 'provider_inbox_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__evidence', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__reverses', ARRAY['tenant_id', 'reverses_payment_confirmation_id']::text[], 'contract', 'payment_confirmation', ARRAY['tenant_id', 'payment_confirmation_id']::text[], false, false),
            ('contract', 'payment_confirmation', 'fk_payment_confirmation__recorder', ARRAY['tenant_id', 'recorded_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__contract', ARRAY['tenant_id', 'contract_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__revision', ARRAY['tenant_id', 'contract_revision_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__execution', ARRAY['tenant_id', 'contract_execution_id']::text[], 'contract', 'contract_execution', ARRAY['tenant_id', 'contract_execution_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__revision_contract', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], 'contract', 'contract_revision', ARRAY['tenant_id', 'contract_revision_id', 'contract_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__execution_path', ARRAY['tenant_id', 'contract_execution_id', 'contract_id', 'contract_revision_id']::text[], 'contract', 'contract_execution', ARRAY['tenant_id', 'contract_execution_id', 'contract_id', 'contract_revision_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__decision', ARRAY['tenant_id', 'decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__evidence', ARRAY['tenant_id', 'evidence_submission_id']::text[], 'evidence', 'evidence_submission', ARRAY['tenant_id', 'evidence_submission_id']::text[], false, false),
            ('contract', 'contract_termination', 'fk_contract_termination__terminator', ARRAY['tenant_id', 'terminated_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], 'opportunity', 'opportunity', ARRAY['tenant_id', 'opportunity_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__contract', ARRAY['tenant_id', 'contract_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__contract_execution', ARRAY['tenant_id', 'contract_execution_id']::text[], 'contract', 'contract_execution', ARRAY['tenant_id', 'contract_execution_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__from_org', ARRAY['tenant_id', 'from_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__to_org', ARRAY['tenant_id', 'to_organization_unit_id']::text[], 'identity', 'organization_unit', ARRAY['tenant_id', 'organization_unit_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__accepted_snapshot', ARRAY['tenant_id', 'accepted_snapshot_id']::text[], 'transfer', 'transfer_snapshot', ARRAY['tenant_id', 'transfer_snapshot_id']::text[], true, true),
            ('transfer', 'transfer_request', 'fk_transfer_request__accept_decision', ARRAY['tenant_id', 'accept_decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__creator', ARRAY['tenant_id', 'created_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('transfer', 'transfer_request', 'fk_transfer_request__contract_path', ARRAY['tenant_id', 'contract_id', 'opportunity_id', 'contract_execution_id']::text[], 'contract', 'contract', ARRAY['tenant_id', 'contract_id', 'opportunity_id', 'contract_execution_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__transfer_request', ARRAY['tenant_id', 'transfer_request_id']::text[], 'transfer', 'transfer_request', ARRAY['tenant_id', 'transfer_request_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__predecessor', ARRAY['tenant_id', 'predecessor_snapshot_id']::text[], 'transfer', 'transfer_snapshot', ARRAY['tenant_id', 'transfer_snapshot_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__submission_task', ARRAY['tenant_id', 'submission_task_occurrence_id']::text[], 'responsibility', 'task_occurrence', ARRAY['tenant_id', 'task_occurrence_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__action_draft', ARRAY['tenant_id', 'confirmed_action_draft_id']::text[], 'responsibility', 'action_draft', ARRAY['tenant_id', 'action_draft_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__pre_transfer_review', ARRAY['tenant_id', 'pre_transfer_review_id']::text[], 'conflict', 'conflict_review', ARRAY['tenant_id', 'conflict_review_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__previous_return_decision', ARRAY['tenant_id', 'previous_return_decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false),
            ('transfer', 'transfer_snapshot', 'fk_transfer_snapshot__submitter', ARRAY['tenant_id', 'submitted_by_appointment_id']::text[], 'identity', 'appointment', ARRAY['tenant_id', 'appointment_id']::text[], false, false),
            ('transfer', 'transfer_return_item', 'fk_transfer_return_item__tenant', ARRAY['tenant_id']::text[], 'identity', 'tenant', ARRAY['tenant_id']::text[], false, false),
            ('transfer', 'transfer_return_item', 'fk_transfer_return_item__transfer_request', ARRAY['tenant_id', 'transfer_request_id']::text[], 'transfer', 'transfer_request', ARRAY['tenant_id', 'transfer_request_id']::text[], false, false),
            ('transfer', 'transfer_return_item', 'fk_transfer_return_item__reviewed_snapshot', ARRAY['tenant_id', 'reviewed_snapshot_id']::text[], 'transfer', 'transfer_snapshot', ARRAY['tenant_id', 'transfer_snapshot_id']::text[], false, false),
            ('transfer', 'transfer_return_item', 'fk_transfer_return_item__snapshot_request', ARRAY['tenant_id', 'reviewed_snapshot_id', 'transfer_request_id']::text[], 'transfer', 'transfer_snapshot', ARRAY['tenant_id', 'transfer_snapshot_id', 'transfer_request_id']::text[], false, false),
            ('transfer', 'transfer_return_item', 'fk_transfer_return_item__return_decision', ARRAY['tenant_id', 'return_decision_record_id']::text[], 'responsibility', 'decision_record', ARRAY['tenant_id', 'decision_record_id']::text[], false, false)
    ), actual AS (
        SELECT child_ns.nspname::text, child.relname::text, con.conname::text,
               ARRAY(
                   SELECT attribute.attname::text
                   FROM pg_catalog.unnest(con.conkey) WITH ORDINALITY AS key(attnum, ordinal_no)
                   JOIN pg_catalog.pg_attribute attribute
                     ON attribute.attrelid = child.oid AND attribute.attnum = key.attnum
                   ORDER BY key.ordinal_no
               ),
               parent_ns.nspname::text, parent.relname::text,
               ARRAY(
                   SELECT attribute.attname::text
                   FROM pg_catalog.unnest(con.confkey) WITH ORDINALITY AS key(attnum, ordinal_no)
                   JOIN pg_catalog.pg_attribute attribute
                     ON attribute.attrelid = parent.oid AND attribute.attnum = key.attnum
                   ORDER BY key.ordinal_no
               ),
               con.condeferrable, con.condeferred
        FROM pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class child ON child.oid = con.conrelid
        JOIN pg_catalog.pg_namespace child_ns ON child_ns.oid = child.relnamespace
        JOIN pg_catalog.pg_class parent ON parent.oid = con.confrelid
        JOIN pg_catalog.pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
        WHERE con.contype = 'f' AND child_ns.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer')
    ), missing_expected AS (
        SELECT * FROM expected EXCEPT SELECT * FROM actual
    ), unexpected_actual AS (
        SELECT * FROM actual EXCEPT SELECT * FROM expected
    )
    SELECT count(*) INTO missing_count FROM (
        SELECT * FROM missing_expected
        UNION ALL
        SELECT * FROM unexpected_actual
    ) AS foreign_key_drift;
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'physical foreign key whitelist mismatch: % differences', missing_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        JOIN pg_catalog.pg_roles member_role
          ON member_role.rolname = application_role.role_name
        JOIN pg_catalog.pg_auth_members membership
          ON membership.member = member_role.oid
    ) THEN
        RAISE EXCEPTION 'application role has a forbidden parent role membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_roles privileged_role
        WHERE (privileged_role.rolsuper OR privileged_role.rolcreaterole
            OR privileged_role.rolcreatedb OR privileged_role.rolreplication
            OR privileged_role.rolbypassrls)
          AND pg_catalog.pg_has_role(application_role.role_name, privileged_role.oid, 'MEMBER')
    ) THEN
        RAISE EXCEPTION 'application role inherits a forbidden cluster capability';
    END IF;

    WITH expected(role_name, schema_name) AS (VALUES
            ('${app_command_role}', 'conflict'),
            ('${app_command_role}', 'contract'),
            ('${app_command_role}', 'evidence'),
            ('${app_command_role}', 'execution'),
            ('${app_command_role}', 'external_action'),
            ('${app_command_role}', 'identity'),
            ('${app_command_role}', 'lead'),
            ('${app_command_role}', 'opportunity'),
            ('${app_command_role}', 'party'),
            ('${app_command_role}', 'platform_meta'),
            ('${app_command_role}', 'responsibility'),
            ('${app_command_role}', 'transfer'),
            ('${app_query_role}', 'audit'),
            ('${app_query_role}', 'conflict'),
            ('${app_query_role}', 'contract'),
            ('${app_query_role}', 'evidence'),
            ('${app_query_role}', 'execution'),
            ('${app_query_role}', 'external_action'),
            ('${app_query_role}', 'identity'),
            ('${app_query_role}', 'lead'),
            ('${app_query_role}', 'opportunity'),
            ('${app_query_role}', 'party'),
            ('${app_query_role}', 'platform_meta'),
            ('${app_query_role}', 'responsibility'),
            ('${app_query_role}', 'transfer'),
            ('${app_worker_role}', 'execution'),
            ('${app_worker_role}', 'external_action'),
            ('${app_worker_role}', 'platform_meta'),
            ('${audit_append_role}', 'audit')
    ), actual AS (
        SELECT application_role.role_name, namespace.nspname::text
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_namespace namespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'USAGE')
    ), missing_expected AS (
        SELECT * FROM expected EXCEPT SELECT * FROM actual
    ), unexpected_actual AS (
        SELECT * FROM actual EXCEPT SELECT * FROM expected
    )
    SELECT count(*) INTO missing_count FROM (
        SELECT * FROM missing_expected
        UNION ALL
        SELECT * FROM unexpected_actual
    ) AS schema_usage_drift;
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'schema USAGE privilege matrix mismatch: % differences', missing_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_namespace namespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta', 'public')
          AND (pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'CREATE')
               OR (namespace.nspname = 'public'
                   AND pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'USAGE')))
    ) OR EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        WHERE pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'CONNECT')
           OR pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'CREATE')
           OR pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'TEMPORARY')
    ) THEN
        RAISE EXCEPTION 'application capability role has forbidden database CONNECT/CREATE/TEMPORARY, schema CREATE, or public USAGE';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN (
            SELECT object.relowner AS owner_oid
            FROM pg_catalog.pg_class object
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
            WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
              AND object.relkind IN ('r', 'p', 'v', 'm', 'S', 'i')
            UNION
            SELECT namespace.nspowner
            FROM pg_catalog.pg_namespace namespace
            WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
            UNION
            SELECT routine.proowner
            FROM pg_catalog.pg_proc routine
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace
            WHERE namespace.nspname = 'platform_meta'
            UNION
            SELECT database.datdba
            FROM pg_catalog.pg_database database
            WHERE database.datname = current_database()
        ) AS managed_owner
        WHERE pg_catalog.pg_has_role(application_role.role_name, managed_owner.owner_oid, 'MEMBER')
    ) THEN
        RAISE EXCEPTION 'application role must not own or inherit managed owner';
    END IF;

    WITH allowed(role_name, schema_name, object_name, can_select, can_insert) AS (VALUES
            ('${app_command_role}', 'conflict', 'conflict_finding', true, true),
            ('${app_command_role}', 'conflict', 'conflict_review', true, true),
            ('${app_command_role}', 'conflict', 'conflict_review_party', true, true),
            ('${app_command_role}', 'contract', 'contract', true, true),
            ('${app_command_role}', 'contract', 'contract_execution', true, true),
            ('${app_command_role}', 'contract', 'contract_fee_term', true, true),
            ('${app_command_role}', 'contract', 'contract_participation', true, true),
            ('${app_command_role}', 'contract', 'contract_revision', true, true),
            ('${app_command_role}', 'contract', 'contract_signature', true, true),
            ('${app_command_role}', 'contract', 'contract_termination', true, true),
            ('${app_command_role}', 'contract', 'payment_confirmation', true, true),
            ('${app_command_role}', 'contract', 'payment_gate', true, true),
            ('${app_command_role}', 'contract', 'signature_plan', true, true),
            ('${app_command_role}', 'evidence', 'evidence_binding', true, true),
            ('${app_command_role}', 'evidence', 'evidence_submission', true, true),
            ('${app_command_role}', 'evidence', 'received_source_object', true, true),
            ('${app_command_role}', 'evidence', 'upload_session', true, true),
            ('${app_command_role}', 'execution', 'command_execution_slot', true, true),
            ('${app_command_role}', 'execution', 'command_receipt', true, true),
            ('${app_command_role}', 'execution', 'domain_event', true, true),
            ('${app_command_role}', 'execution', 'domain_event_outbox', true, true),
            ('${app_command_role}', 'external_action', 'external_action', true, true),
            ('${app_command_role}', 'external_action', 'external_action_outbox', true, true),
            ('${app_command_role}', 'external_action', 'provider_inbox', true, true),
            ('${app_command_role}', 'identity', 'appointment', true, true),
            ('${app_command_role}', 'identity', 'authority_grant', true, true),
            ('${app_command_role}', 'identity', 'delegation_grant', true, true),
            ('${app_command_role}', 'identity', 'object_access_grant', true, true),
            ('${app_command_role}', 'identity', 'organization_unit', true, true),
            ('${app_command_role}', 'identity', 'principal', true, true),
            ('${app_command_role}', 'identity', 'tenant', true, true),
            ('${app_command_role}', 'lead', 'lead', true, true),
            ('${app_command_role}', 'lead', 'lead_assignment', true, true),
            ('${app_command_role}', 'lead', 'lead_contact_result', true, true),
            ('${app_command_role}', 'opportunity', 'opportunity', true, true),
            ('${app_command_role}', 'opportunity', 'opportunity_participation', true, true),
            ('${app_command_role}', 'opportunity', 'opportunity_progress', true, true),
            ('${app_command_role}', 'opportunity', 'quote_issue', true, true),
            ('${app_command_role}', 'opportunity', 'quote_line', true, true),
            ('${app_command_role}', 'opportunity', 'quote_payment_term', true, true),
            ('${app_command_role}', 'opportunity', 'quote_response', true, true),
            ('${app_command_role}', 'opportunity', 'quote_revision', true, true),
            ('${app_command_role}', 'opportunity', 'quote_service_scope', true, true),
            ('${app_command_role}', 'party', 'party', true, true),
            ('${app_command_role}', 'platform_meta', 'deployment_state', true, false),
            ('${app_command_role}', 'responsibility', 'action_draft', true, true),
            ('${app_command_role}', 'responsibility', 'decision_record', true, true),
            ('${app_command_role}', 'responsibility', 'task_occurrence', true, true),
            ('${app_command_role}', 'responsibility', 'wait_receipt', true, true),
            ('${app_command_role}', 'transfer', 'transfer_request', true, true),
            ('${app_command_role}', 'transfer', 'transfer_return_item', true, true),
            ('${app_command_role}', 'transfer', 'transfer_snapshot', true, true),
            ('${app_query_role}', 'audit', 'audit_entry_classified_v', true, false),
            ('${app_query_role}', 'conflict', 'conflict_finding', true, false),
            ('${app_query_role}', 'conflict', 'conflict_review', true, false),
            ('${app_query_role}', 'conflict', 'conflict_review_party', true, false),
            ('${app_query_role}', 'contract', 'contract', true, false),
            ('${app_query_role}', 'contract', 'contract_execution', true, false),
            ('${app_query_role}', 'contract', 'contract_fee_term', true, false),
            ('${app_query_role}', 'contract', 'contract_participation', true, false),
            ('${app_query_role}', 'contract', 'contract_revision', true, false),
            ('${app_query_role}', 'contract', 'contract_signature', true, false),
            ('${app_query_role}', 'contract', 'contract_termination', true, false),
            ('${app_query_role}', 'contract', 'payment_confirmation', true, false),
            ('${app_query_role}', 'contract', 'payment_gate', true, false),
            ('${app_query_role}', 'contract', 'signature_plan', true, false),
            ('${app_query_role}', 'evidence', 'evidence_binding', true, false),
            ('${app_query_role}', 'evidence', 'evidence_submission', true, false),
            ('${app_query_role}', 'evidence', 'received_source_object', true, false),
            ('${app_query_role}', 'evidence', 'upload_session', true, false),
            ('${app_query_role}', 'execution', 'command_execution_slot', true, false),
            ('${app_query_role}', 'execution', 'command_receipt', true, false),
            ('${app_query_role}', 'execution', 'domain_event', true, false),
            ('${app_query_role}', 'execution', 'domain_event_outbox', true, false),
            ('${app_query_role}', 'external_action', 'external_action', true, false),
            ('${app_query_role}', 'external_action', 'external_action_outbox', true, false),
            ('${app_query_role}', 'external_action', 'provider_inbox', true, false),
            ('${app_query_role}', 'identity', 'appointment', true, false),
            ('${app_query_role}', 'identity', 'authority_grant', true, false),
            ('${app_query_role}', 'identity', 'delegation_grant', true, false),
            ('${app_query_role}', 'identity', 'object_access_grant', true, false),
            ('${app_query_role}', 'identity', 'organization_unit', true, false),
            ('${app_query_role}', 'identity', 'principal', true, false),
            ('${app_query_role}', 'identity', 'tenant', true, false),
            ('${app_query_role}', 'lead', 'lead', true, false),
            ('${app_query_role}', 'lead', 'lead_assignment', true, false),
            ('${app_query_role}', 'lead', 'lead_contact_result', true, false),
            ('${app_query_role}', 'opportunity', 'opportunity', true, false),
            ('${app_query_role}', 'opportunity', 'opportunity_participation', true, false),
            ('${app_query_role}', 'opportunity', 'opportunity_progress', true, false),
            ('${app_query_role}', 'opportunity', 'quote_issue', true, false),
            ('${app_query_role}', 'opportunity', 'quote_line', true, false),
            ('${app_query_role}', 'opportunity', 'quote_payment_term', true, false),
            ('${app_query_role}', 'opportunity', 'quote_response', true, false),
            ('${app_query_role}', 'opportunity', 'quote_revision', true, false),
            ('${app_query_role}', 'opportunity', 'quote_service_scope', true, false),
            ('${app_query_role}', 'party', 'party', true, false),
            ('${app_query_role}', 'platform_meta', 'deployment_state', true, false),
            ('${app_query_role}', 'responsibility', 'action_draft', true, false),
            ('${app_query_role}', 'responsibility', 'decision_record', true, false),
            ('${app_query_role}', 'responsibility', 'task_occurrence', true, false),
            ('${app_query_role}', 'responsibility', 'wait_receipt', true, false),
            ('${app_query_role}', 'transfer', 'transfer_request', true, false),
            ('${app_query_role}', 'transfer', 'transfer_return_item', true, false),
            ('${app_query_role}', 'transfer', 'transfer_snapshot', true, false),
            ('${app_worker_role}', 'execution', 'domain_event', true, false),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', true, false),
            ('${app_worker_role}', 'external_action', 'external_action', true, false),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', true, false),
            ('${app_worker_role}', 'platform_meta', 'deployment_state', true, false),
            ('${audit_append_role}', 'audit', 'audit_entry', false, true)
    ), candidates AS (
        SELECT application_role.role_name, namespace.nspname::text AS schema_name,
               object.relname::text AS object_name, object.oid AS object_oid
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_class object
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND object.relkind IN ('r', 'p', 'v')
    )
    SELECT count(*) INTO missing_count
    FROM candidates candidate
    LEFT JOIN allowed
      ON allowed.role_name = candidate.role_name
     AND allowed.schema_name = candidate.schema_name
     AND allowed.object_name = candidate.object_name
    WHERE pg_catalog.has_table_privilege(candidate.role_name, candidate.object_oid, 'SELECT')
              IS DISTINCT FROM coalesce(allowed.can_select, false)
       OR pg_catalog.has_any_column_privilege(candidate.role_name, candidate.object_oid, 'SELECT')
              IS DISTINCT FROM coalesce(allowed.can_select, false)
       OR pg_catalog.has_table_privilege(candidate.role_name, candidate.object_oid, 'INSERT')
              IS DISTINCT FROM coalesce(allowed.can_insert, false)
       OR pg_catalog.has_any_column_privilege(candidate.role_name, candidate.object_oid, 'INSERT')
              IS DISTINCT FROM coalesce(allowed.can_insert, false);
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'table SELECT/INSERT privilege matrix mismatch: % objects', missing_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_class object
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND object.relkind IN ('r', 'p')
          AND (pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'DELETE')
            OR pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'TRUNCATE'))
    ) THEN
        RAISE EXCEPTION 'application role has forbidden DELETE or TRUNCATE';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_class object
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND object.relkind IN ('r', 'p')
          AND (pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'TRIGGER')
            OR pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'REFERENCES')
            OR pg_catalog.has_any_column_privilege(application_role.role_name, object.oid, 'REFERENCES'))
    ) THEN
        RAISE EXCEPTION 'application role has forbidden TRIGGER or REFERENCES';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_class object
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND object.relkind IN ('r', 'p')
          AND pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'UPDATE')
    ) THEN
        RAISE EXCEPTION 'application role must use only column-level UPDATE grants';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_proc routine
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace
        WHERE namespace.nspname = 'platform_meta'
          AND routine.proname LIKE 'fn_%'
          AND pg_catalog.has_function_privilege(application_role.role_name, routine.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'application role has forbidden direct function EXECUTE';
    END IF;

    WITH expected(role_name, schema_name, table_name, column_name) AS (VALUES
            ('${app_command_role}', 'conflict', 'conflict_review', 'resolution_code'),
            ('${app_command_role}', 'conflict', 'conflict_review', 'resolution_digest'),
            ('${app_command_role}', 'conflict', 'conflict_review', 'resolved_at'),
            ('${app_command_role}', 'conflict', 'conflict_review', 'revision'),
            ('${app_command_role}', 'contract', 'contract', 'activation_source_hash'),
            ('${app_command_role}', 'contract', 'contract', 'activation_source_id'),
            ('${app_command_role}', 'contract', 'contract', 'activation_source_revision'),
            ('${app_command_role}', 'contract', 'contract', 'activation_source_type'),
            ('${app_command_role}', 'contract', 'contract', 'approved_revision_id'),
            ('${app_command_role}', 'contract', 'contract', 'changed_at'),
            ('${app_command_role}', 'contract', 'contract', 'contract_execution_id'),
            ('${app_command_role}', 'contract', 'contract', 'contract_termination_id'),
            ('${app_command_role}', 'contract', 'contract', 'current_revision_id'),
            ('${app_command_role}', 'contract', 'contract', 'deal_activated_at'),
            ('${app_command_role}', 'contract', 'contract', 'revision'),
            ('${app_command_role}', 'contract', 'contract_signature', 'changed_at'),
            ('${app_command_role}', 'contract', 'contract_signature', 'revision'),
            ('${app_command_role}', 'contract', 'contract_signature', 'revocation_authorization_digest'),
            ('${app_command_role}', 'contract', 'contract_signature', 'revocation_reason_code'),
            ('${app_command_role}', 'contract', 'contract_signature', 'revoked_at'),
            ('${app_command_role}', 'contract', 'contract_signature', 'revoked_by_appointment_id'),
            ('${app_command_role}', 'contract', 'contract_termination', 'changed_at'),
            ('${app_command_role}', 'contract', 'contract_termination', 'refund_calculated_at'),
            ('${app_command_role}', 'contract', 'contract_termination', 'refund_calculation_digest'),
            ('${app_command_role}', 'contract', 'contract_termination', 'refund_calculation_minor'),
            ('${app_command_role}', 'contract', 'contract_termination', 'refund_currency_code'),
            ('${app_command_role}', 'contract', 'contract_termination', 'revision'),
            ('${app_command_role}', 'contract', 'payment_gate', 'changed_at'),
            ('${app_command_role}', 'contract', 'payment_gate', 'confirmation_set_digest'),
            ('${app_command_role}', 'contract', 'payment_gate', 'gate_state'),
            ('${app_command_role}', 'contract', 'payment_gate', 'payment_confirmation_ids'),
            ('${app_command_role}', 'contract', 'payment_gate', 'revision'),
            ('${app_command_role}', 'contract', 'payment_gate', 'risk_decision_record_id'),
            ('${app_command_role}', 'contract', 'payment_gate', 'satisfaction_digest'),
            ('${app_command_role}', 'contract', 'payment_gate', 'satisfied_at'),
            ('${app_command_role}', 'evidence', 'evidence_binding', 'revision'),
            ('${app_command_role}', 'evidence', 'evidence_binding', 'revocation_authorization_digest'),
            ('${app_command_role}', 'evidence', 'evidence_binding', 'revocation_reason_code'),
            ('${app_command_role}', 'evidence', 'evidence_binding', 'revoked_at'),
            ('${app_command_role}', 'evidence', 'evidence_binding', 'revoked_by_appointment_id'),
            ('${app_command_role}', 'evidence', 'upload_session', 'finalized_at'),
            ('${app_command_role}', 'evidence', 'upload_session', 'received_at'),
            ('${app_command_role}', 'evidence', 'upload_session', 'revision'),
            ('${app_command_role}', 'evidence', 'upload_session', 'status'),
            ('${app_command_role}', 'execution', 'domain_event_outbox', 'available_at'),
            ('${app_command_role}', 'execution', 'domain_event_outbox', 'revision'),
            ('${app_command_role}', 'execution', 'domain_event_outbox', 'status'),
            ('${app_command_role}', 'external_action', 'external_action', 'completed_at'),
            ('${app_command_role}', 'external_action', 'external_action', 'dispatched_at'),
            ('${app_command_role}', 'external_action', 'external_action', 'last_error_code'),
            ('${app_command_role}', 'external_action', 'external_action', 'provider_action_id'),
            ('${app_command_role}', 'external_action', 'external_action', 'resolution_method_code'),
            ('${app_command_role}', 'external_action', 'external_action', 'resolution_source_hash'),
            ('${app_command_role}', 'external_action', 'external_action', 'resolution_source_id'),
            ('${app_command_role}', 'external_action', 'external_action', 'resolution_source_revision'),
            ('${app_command_role}', 'external_action', 'external_action', 'resolution_source_type'),
            ('${app_command_role}', 'external_action', 'external_action', 'result_code'),
            ('${app_command_role}', 'external_action', 'external_action', 'result_digest'),
            ('${app_command_role}', 'external_action', 'external_action', 'revision'),
            ('${app_command_role}', 'external_action', 'external_action', 'status'),
            ('${app_command_role}', 'identity', 'appointment', 'ended_at'),
            ('${app_command_role}', 'identity', 'appointment', 'revision'),
            ('${app_command_role}', 'identity', 'appointment', 'state'),
            ('${app_command_role}', 'identity', 'authority_grant', 'revision'),
            ('${app_command_role}', 'identity', 'authority_grant', 'revocation_reason_code'),
            ('${app_command_role}', 'identity', 'authority_grant', 'revoked_at'),
            ('${app_command_role}', 'identity', 'authority_grant', 'state'),
            ('${app_command_role}', 'identity', 'delegation_grant', 'revision'),
            ('${app_command_role}', 'identity', 'delegation_grant', 'revocation_reason_code'),
            ('${app_command_role}', 'identity', 'delegation_grant', 'revoked_at'),
            ('${app_command_role}', 'identity', 'delegation_grant', 'state'),
            ('${app_command_role}', 'identity', 'object_access_grant', 'revision'),
            ('${app_command_role}', 'identity', 'object_access_grant', 'revocation_reason_code'),
            ('${app_command_role}', 'identity', 'object_access_grant', 'revoked_at'),
            ('${app_command_role}', 'identity', 'object_access_grant', 'state'),
            ('${app_command_role}', 'identity', 'organization_unit', 'closed_at'),
            ('${app_command_role}', 'identity', 'organization_unit', 'display_name'),
            ('${app_command_role}', 'identity', 'organization_unit', 'parent_organization_unit_id'),
            ('${app_command_role}', 'identity', 'organization_unit', 'revision'),
            ('${app_command_role}', 'identity', 'organization_unit', 'state'),
            ('${app_command_role}', 'identity', 'principal', 'disabled_at'),
            ('${app_command_role}', 'identity', 'principal', 'display_name'),
            ('${app_command_role}', 'identity', 'principal', 'revision'),
            ('${app_command_role}', 'identity', 'principal', 'state'),
            ('${app_command_role}', 'identity', 'tenant', 'closed_at'),
            ('${app_command_role}', 'identity', 'tenant', 'display_name'),
            ('${app_command_role}', 'identity', 'tenant', 'revision'),
            ('${app_command_role}', 'identity', 'tenant', 'state'),
            ('${app_command_role}', 'lead', 'lead', 'current_assignment_id'),
            ('${app_command_role}', 'lead', 'lead', 'disposition_code'),
            ('${app_command_role}', 'lead', 'lead', 'parsed_party_id'),
            ('${app_command_role}', 'lead', 'lead', 'party_resolution_code'),
            ('${app_command_role}', 'lead', 'lead', 'revision'),
            ('${app_command_role}', 'lead', 'lead_assignment', 'assignment_status_code'),
            ('${app_command_role}', 'lead', 'lead_assignment', 'close_reason_code'),
            ('${app_command_role}', 'lead', 'lead_assignment', 'closed_at'),
            ('${app_command_role}', 'lead', 'lead_assignment', 'revision'),
            ('${app_command_role}', 'opportunity', 'opportunity', 'close_outcome_code'),
            ('${app_command_role}', 'opportunity', 'opportunity', 'closed_at'),
            ('${app_command_role}', 'opportunity', 'opportunity', 'current_quote_revision_id'),
            ('${app_command_role}', 'opportunity', 'opportunity', 'revision'),
            ('${app_command_role}', 'opportunity', 'quote_issue', 'issue_status_code'),
            ('${app_command_role}', 'opportunity', 'quote_issue', 'revision'),
            ('${app_command_role}', 'opportunity', 'quote_issue', 'revocation_reason_code'),
            ('${app_command_role}', 'opportunity', 'quote_issue', 'revoked_at'),
            ('${app_command_role}', 'party', 'party', 'canonical_name'),
            ('${app_command_role}', 'party', 'party', 'merged_at'),
            ('${app_command_role}', 'party', 'party', 'merged_into_party_id'),
            ('${app_command_role}', 'party', 'party', 'primary_identifier_ciphertext'),
            ('${app_command_role}', 'party', 'party', 'primary_identifier_hmac'),
            ('${app_command_role}', 'party', 'party', 'primary_identifier_type'),
            ('${app_command_role}', 'party', 'party', 'revision'),
            ('${app_command_role}', 'party', 'party', 'status'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'candidate_payload'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'candidate_payload_digest'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'confirmed_at'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'confirmed_by_appointment_id'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'confirmed_payload_digest'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'last_edited_at'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'revision'),
            ('${app_command_role}', 'responsibility', 'action_draft', 'state'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'cancellation_reason_code'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'cancelled_at'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'completed_at'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'completion_fact_hash'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'completion_fact_id'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'completion_fact_revision'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'completion_fact_type'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'revision'),
            ('${app_command_role}', 'responsibility', 'task_occurrence', 'state'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'accept_decision_record_id'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'accepted_snapshot_id'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'changed_at'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_capability_pack_code'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_capability_pack_version'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_created_at'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_id'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_no'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'matter_type_code'),
            ('${app_command_role}', 'transfer', 'transfer_request', 'revision'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'attempt_count'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'available_at'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'delivered_at'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'fencing_token'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'last_error_code'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'lease_owner'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'lease_until'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'revision'),
            ('${app_worker_role}', 'execution', 'domain_event_outbox', 'status'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'attempt_count'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'available_at'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'delivered_at'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'fencing_token'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'last_error_code'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'lease_owner'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'lease_until'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'revision'),
            ('${app_worker_role}', 'external_action', 'external_action_outbox', 'status')
    ), candidates AS (
        SELECT application_role.role_name, namespace.nspname::text, object.relname::text, attribute.attname::text
        FROM (VALUES
            ('${app_command_role}'), ('${app_worker_role}'),
            ('${app_query_role}'), ('${audit_append_role}')
        ) AS application_role(role_name)
        CROSS JOIN pg_catalog.pg_class object
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
        JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = object.oid
        WHERE namespace.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
          AND object.relkind IN ('r', 'p')
          AND attribute.attnum > 0 AND NOT attribute.attisdropped
    ), actual AS (
        SELECT candidate.* FROM candidates candidate
        WHERE pg_catalog.has_column_privilege(
            candidate.role_name,
            pg_catalog.format('%I.%I', candidate.nspname, candidate.relname),
            candidate.attname, 'UPDATE'
        )
    ), missing_expected AS (
        SELECT * FROM expected EXCEPT SELECT * FROM actual
    ), unexpected_actual AS (
        SELECT * FROM actual EXCEPT SELECT * FROM expected
    )
    SELECT count(*) INTO missing_count FROM (
        SELECT * FROM missing_expected
        UNION ALL
        SELECT * FROM unexpected_actual
    ) AS update_drift;
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'column UPDATE whitelist mismatch: % differences', missing_count;
    END IF;

    IF pg_catalog.has_table_privilege('${app_query_role}', 'audit.audit_entry', 'SELECT')
       OR NOT pg_catalog.has_table_privilege('${app_query_role}', 'audit.audit_entry_classified_v', 'SELECT') THEN
        RAISE EXCEPTION 'audit query role must use only the classified audit view';
    END IF;

    SELECT count(*) INTO missing_count
    FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta')
      AND c.relkind IN ('r', 'p')
      AND c.relname <> 'flyway_schema_history'
      AND NOT EXISTS (
          SELECT 1 FROM pg_catalog.pg_trigger t
          WHERE t.tgrelid = c.oid AND NOT t.tgisinternal
            AND t.tgname = 'trg_' || c.relname || '__mutation_guard'
      );
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'mutation guard coverage mismatch: % tables missing', missing_count;
    END IF;
END;
$$;
