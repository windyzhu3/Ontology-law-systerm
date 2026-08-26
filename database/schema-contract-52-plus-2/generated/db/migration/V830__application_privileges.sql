-- Flyway placeholders必须映射到由IaC预创建、不可作为对象Owner的数据库角色。

DO $role_contract$
DECLARE
    configured_roles text[] := ARRAY[
        '${app_command_role}', '${app_worker_role}',
        '${app_query_role}', '${audit_append_role}'
    ];
    role_name text;
BEGIN
    IF (SELECT count(DISTINCT item) FROM pg_catalog.unnest(configured_roles) AS item) <> 4 THEN
        RAISE EXCEPTION 'application database roles must be four distinct roles';
    END IF;
    FOREACH role_name IN ARRAY configured_roles LOOP
        IF role_name !~ '^[a-z][a-z0-9_]*$' THEN
            RAISE EXCEPTION 'application database role is not unquoted snake_case: %', role_name;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = role_name) THEN
            RAISE EXCEPTION 'configured application database role does not exist: %', role_name;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_catalog.pg_roles
            WHERE rolname = role_name
              AND (rolcanlogin OR rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
        ) THEN
            RAISE EXCEPTION 'application database capability role has LOGIN or forbidden cluster capability: %', role_name;
        END IF;
    END LOOP;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_auth_members membership
        JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
        WHERE member_role.rolname = ANY(configured_roles)
    ) THEN
        RAISE EXCEPTION 'application database roles must not be members of any parent role';
    END IF;
END;
$role_contract$;

DO $dedicated_database_contract$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_namespace namespace
        WHERE namespace.nspname !~ '^pg_'
          AND namespace.nspname <> 'information_schema'
          AND namespace.nspname NOT IN ('identity', 'audit', 'responsibility', 'execution', 'external_action', 'evidence', 'party', 'lead', 'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta', 'public')
    ) THEN
        RAISE EXCEPTION 'schema contract requires a dedicated database without unexpected user schemas';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
    ) THEN
        RAISE EXCEPTION 'schema contract requires no user tables in public schema';
    END IF;
END;
$dedicated_database_contract$;

DO $database_privileges$
DECLARE
    role_name text;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        '${app_command_role}', '${app_worker_role}',
        '${app_query_role}', '${audit_append_role}'
    ] LOOP
        EXECUTE pg_catalog.format(
            'REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I',
            current_database(), role_name
        );
    END LOOP;
    EXECUTE pg_catalog.format(
        'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC',
        current_database()
    );
END;
$database_privileges$;
REVOKE ALL ON SCHEMA public FROM PUBLIC, ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};

REVOKE ALL ON SCHEMA identity, audit, responsibility, execution, external_action, evidence, party, lead, opportunity, conflict, contract, transfer, platform_meta FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA identity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA identity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA identity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA audit FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA audit FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA audit FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA responsibility FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA responsibility FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA responsibility FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA responsibility REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA responsibility REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA execution FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA execution FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA execution FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA execution REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA execution REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA external_action FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA external_action FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA external_action FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA external_action REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA external_action REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA evidence FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA evidence FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA evidence FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA evidence REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA evidence REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA party FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA party FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA party FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA party REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA party REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA lead FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA lead FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA lead FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA lead REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA lead REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA opportunity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA opportunity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA opportunity FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA opportunity REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA opportunity REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA conflict FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA conflict FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA conflict FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA conflict REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA conflict REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA contract FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA contract FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA contract FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA contract REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA contract REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA transfer FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA transfer FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA transfer FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA transfer REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA transfer REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA platform_meta FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA platform_meta FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA platform_meta FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_meta REVOKE ALL ON TABLES FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_meta REVOKE ALL ON FUNCTIONS FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}, PUBLIC;
GRANT USAGE ON SCHEMA identity TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA audit TO ${app_query_role}, ${audit_append_role};
GRANT USAGE ON SCHEMA responsibility TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA execution TO ${app_command_role}, ${app_query_role}, ${app_worker_role};
GRANT USAGE ON SCHEMA external_action TO ${app_command_role}, ${app_query_role}, ${app_worker_role};
GRANT USAGE ON SCHEMA evidence TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA party TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA lead TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA opportunity TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA conflict TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA contract TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA transfer TO ${app_command_role}, ${app_query_role};
GRANT USAGE ON SCHEMA platform_meta TO ${app_command_role}, ${app_worker_role}, ${app_query_role};

GRANT SELECT, INSERT ON identity.tenant TO ${app_command_role};
GRANT SELECT ON identity.tenant TO ${app_query_role};
GRANT UPDATE (display_name, state, closed_at, revision) ON identity.tenant TO ${app_command_role};
GRANT SELECT, INSERT ON identity.principal TO ${app_command_role};
GRANT SELECT ON identity.principal TO ${app_query_role};
GRANT UPDATE (display_name, state, disabled_at, revision) ON identity.principal TO ${app_command_role};
GRANT SELECT, INSERT ON identity.organization_unit TO ${app_command_role};
GRANT SELECT ON identity.organization_unit TO ${app_query_role};
GRANT UPDATE (display_name, parent_organization_unit_id, state, closed_at, revision) ON identity.organization_unit TO ${app_command_role};
GRANT SELECT, INSERT ON identity.appointment TO ${app_command_role};
GRANT SELECT ON identity.appointment TO ${app_query_role};
GRANT UPDATE (state, ended_at, revision) ON identity.appointment TO ${app_command_role};
GRANT SELECT, INSERT ON identity.authority_grant TO ${app_command_role};
GRANT SELECT ON identity.authority_grant TO ${app_query_role};
GRANT UPDATE (state, revoked_at, revocation_reason_code, revision) ON identity.authority_grant TO ${app_command_role};
GRANT SELECT, INSERT ON identity.delegation_grant TO ${app_command_role};
GRANT SELECT ON identity.delegation_grant TO ${app_query_role};
GRANT UPDATE (state, revoked_at, revocation_reason_code, revision) ON identity.delegation_grant TO ${app_command_role};
GRANT SELECT, INSERT ON identity.object_access_grant TO ${app_command_role};
GRANT SELECT ON identity.object_access_grant TO ${app_query_role};
GRANT UPDATE (state, revoked_at, revocation_reason_code, revision) ON identity.object_access_grant TO ${app_command_role};
GRANT INSERT ON audit.audit_entry TO ${audit_append_role};
GRANT SELECT, INSERT ON responsibility.task_occurrence TO ${app_command_role};
GRANT SELECT ON responsibility.task_occurrence TO ${app_query_role};
GRANT UPDATE (state, completed_at, cancelled_at, cancellation_reason_code, completion_fact_type, completion_fact_id, completion_fact_revision, completion_fact_hash, revision) ON responsibility.task_occurrence TO ${app_command_role};
GRANT SELECT, INSERT ON responsibility.decision_record TO ${app_command_role};
GRANT SELECT ON responsibility.decision_record TO ${app_query_role};
GRANT SELECT, INSERT ON responsibility.wait_receipt TO ${app_command_role};
GRANT SELECT ON responsibility.wait_receipt TO ${app_query_role};
GRANT SELECT, INSERT ON responsibility.action_draft TO ${app_command_role};
GRANT SELECT ON responsibility.action_draft TO ${app_query_role};
GRANT UPDATE (candidate_payload, candidate_payload_digest, last_edited_at, state, confirmed_by_appointment_id, confirmed_at, confirmed_payload_digest, revision) ON responsibility.action_draft TO ${app_command_role};
GRANT SELECT, INSERT ON execution.command_execution_slot TO ${app_command_role};
GRANT SELECT ON execution.command_execution_slot TO ${app_query_role};
GRANT SELECT, INSERT ON execution.command_receipt TO ${app_command_role};
GRANT SELECT ON execution.command_receipt TO ${app_query_role};
GRANT SELECT, INSERT ON execution.domain_event TO ${app_command_role};
GRANT SELECT ON execution.domain_event TO ${app_query_role};
GRANT SELECT, INSERT ON execution.domain_event_outbox TO ${app_command_role};
GRANT SELECT ON execution.domain_event_outbox TO ${app_query_role};
GRANT UPDATE (status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision) ON execution.domain_event_outbox TO ${app_worker_role};
GRANT SELECT ON execution.domain_event_outbox TO ${app_worker_role};
GRANT SELECT, INSERT ON external_action.external_action TO ${app_command_role};
GRANT SELECT ON external_action.external_action TO ${app_query_role};
GRANT UPDATE (status, dispatched_at, provider_action_id, completed_at, result_code, result_digest, resolution_method_code, resolution_source_type, resolution_source_id, resolution_source_revision, resolution_source_hash, last_error_code, revision) ON external_action.external_action TO ${app_command_role};
GRANT SELECT, INSERT ON external_action.external_action_outbox TO ${app_command_role};
GRANT SELECT ON external_action.external_action_outbox TO ${app_query_role};
GRANT UPDATE (status, available_at, lease_owner, lease_until, fencing_token, attempt_count, delivered_at, last_error_code, revision) ON external_action.external_action_outbox TO ${app_worker_role};
GRANT SELECT ON external_action.external_action_outbox TO ${app_worker_role};
GRANT SELECT, INSERT ON external_action.provider_inbox TO ${app_command_role};
GRANT SELECT ON external_action.provider_inbox TO ${app_query_role};
GRANT SELECT, INSERT ON evidence.upload_session TO ${app_command_role};
GRANT SELECT ON evidence.upload_session TO ${app_query_role};
GRANT UPDATE (status, received_at, finalized_at, revision) ON evidence.upload_session TO ${app_command_role};
GRANT SELECT, INSERT ON evidence.received_source_object TO ${app_command_role};
GRANT SELECT ON evidence.received_source_object TO ${app_query_role};
GRANT SELECT, INSERT ON evidence.evidence_submission TO ${app_command_role};
GRANT SELECT ON evidence.evidence_submission TO ${app_query_role};
GRANT SELECT, INSERT ON evidence.evidence_binding TO ${app_command_role};
GRANT SELECT ON evidence.evidence_binding TO ${app_query_role};
GRANT UPDATE (revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision) ON evidence.evidence_binding TO ${app_command_role};
GRANT SELECT, INSERT ON party.party TO ${app_command_role};
GRANT SELECT ON party.party TO ${app_query_role};
GRANT UPDATE (canonical_name, primary_identifier_type, primary_identifier_ciphertext, primary_identifier_hmac, status, merged_into_party_id, merged_at, revision) ON party.party TO ${app_command_role};
GRANT SELECT, INSERT ON lead.lead TO ${app_command_role};
GRANT SELECT ON lead.lead TO ${app_query_role};
GRANT UPDATE (parsed_party_id, party_resolution_code, disposition_code, current_assignment_id, revision) ON lead.lead TO ${app_command_role};
GRANT SELECT, INSERT ON lead.lead_assignment TO ${app_command_role};
GRANT SELECT ON lead.lead_assignment TO ${app_query_role};
GRANT UPDATE (assignment_status_code, closed_at, close_reason_code, revision) ON lead.lead_assignment TO ${app_command_role};
GRANT SELECT, INSERT ON lead.lead_contact_result TO ${app_command_role};
GRANT SELECT ON lead.lead_contact_result TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.opportunity TO ${app_command_role};
GRANT SELECT ON opportunity.opportunity TO ${app_query_role};
GRANT UPDATE (current_quote_revision_id, close_outcome_code, closed_at, revision) ON opportunity.opportunity TO ${app_command_role};
GRANT SELECT, INSERT ON opportunity.opportunity_participation TO ${app_command_role};
GRANT SELECT ON opportunity.opportunity_participation TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.opportunity_progress TO ${app_command_role};
GRANT SELECT ON opportunity.opportunity_progress TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.quote_revision TO ${app_command_role};
GRANT SELECT ON opportunity.quote_revision TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.quote_service_scope TO ${app_command_role};
GRANT SELECT ON opportunity.quote_service_scope TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.quote_line TO ${app_command_role};
GRANT SELECT ON opportunity.quote_line TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.quote_payment_term TO ${app_command_role};
GRANT SELECT ON opportunity.quote_payment_term TO ${app_query_role};
GRANT SELECT, INSERT ON opportunity.quote_issue TO ${app_command_role};
GRANT SELECT ON opportunity.quote_issue TO ${app_query_role};
GRANT UPDATE (issue_status_code, revoked_at, revocation_reason_code, revision) ON opportunity.quote_issue TO ${app_command_role};
GRANT SELECT, INSERT ON opportunity.quote_response TO ${app_command_role};
GRANT SELECT ON opportunity.quote_response TO ${app_query_role};
GRANT SELECT, INSERT ON conflict.conflict_review TO ${app_command_role};
GRANT SELECT ON conflict.conflict_review TO ${app_query_role};
GRANT UPDATE (resolution_code, resolution_digest, resolved_at, revision) ON conflict.conflict_review TO ${app_command_role};
GRANT SELECT, INSERT ON conflict.conflict_review_party TO ${app_command_role};
GRANT SELECT ON conflict.conflict_review_party TO ${app_query_role};
GRANT SELECT, INSERT ON conflict.conflict_finding TO ${app_command_role};
GRANT SELECT ON conflict.conflict_finding TO ${app_query_role};
GRANT SELECT, INSERT ON contract.contract TO ${app_command_role};
GRANT SELECT ON contract.contract TO ${app_query_role};
GRANT UPDATE (current_revision_id, approved_revision_id, contract_execution_id, deal_activated_at, activation_source_type, activation_source_id, activation_source_revision, activation_source_hash, contract_termination_id, revision, changed_at) ON contract.contract TO ${app_command_role};
GRANT SELECT, INSERT ON contract.contract_revision TO ${app_command_role};
GRANT SELECT ON contract.contract_revision TO ${app_query_role};
GRANT SELECT, INSERT ON contract.contract_participation TO ${app_command_role};
GRANT SELECT ON contract.contract_participation TO ${app_query_role};
GRANT SELECT, INSERT ON contract.contract_fee_term TO ${app_command_role};
GRANT SELECT ON contract.contract_fee_term TO ${app_query_role};
GRANT SELECT, INSERT ON contract.payment_gate TO ${app_command_role};
GRANT SELECT ON contract.payment_gate TO ${app_query_role};
GRANT UPDATE (gate_state, satisfied_at, satisfaction_digest, payment_confirmation_ids, confirmation_set_digest, risk_decision_record_id, revision, changed_at) ON contract.payment_gate TO ${app_command_role};
GRANT SELECT, INSERT ON contract.signature_plan TO ${app_command_role};
GRANT SELECT ON contract.signature_plan TO ${app_query_role};
GRANT SELECT, INSERT ON contract.contract_signature TO ${app_command_role};
GRANT SELECT ON contract.contract_signature TO ${app_query_role};
GRANT UPDATE (revoked_at, revoked_by_appointment_id, revocation_authorization_digest, revocation_reason_code, revision, changed_at) ON contract.contract_signature TO ${app_command_role};
GRANT SELECT, INSERT ON contract.contract_execution TO ${app_command_role};
GRANT SELECT ON contract.contract_execution TO ${app_query_role};
GRANT SELECT, INSERT ON contract.payment_confirmation TO ${app_command_role};
GRANT SELECT ON contract.payment_confirmation TO ${app_query_role};
GRANT SELECT, INSERT ON contract.contract_termination TO ${app_command_role};
GRANT SELECT ON contract.contract_termination TO ${app_query_role};
GRANT UPDATE (refund_calculation_minor, refund_currency_code, refund_calculation_digest, refund_calculated_at, revision, changed_at) ON contract.contract_termination TO ${app_command_role};
GRANT SELECT, INSERT ON transfer.transfer_request TO ${app_command_role};
GRANT SELECT ON transfer.transfer_request TO ${app_query_role};
GRANT UPDATE (accepted_snapshot_id, accept_decision_record_id, matter_id, matter_no, matter_type_code, matter_capability_pack_code, matter_capability_pack_version, matter_created_at, revision, changed_at) ON transfer.transfer_request TO ${app_command_role};
GRANT SELECT, INSERT ON transfer.transfer_snapshot TO ${app_command_role};
GRANT SELECT ON transfer.transfer_snapshot TO ${app_query_role};
GRANT SELECT, INSERT ON transfer.transfer_return_item TO ${app_command_role};
GRANT SELECT ON transfer.transfer_return_item TO ${app_query_role};
GRANT SELECT ON execution.domain_event, external_action.external_action TO ${app_worker_role};
GRANT UPDATE (status, available_at, revision)
    ON execution.domain_event_outbox TO ${app_command_role};

CREATE VIEW audit.audit_entry_classified_v
WITH (security_barrier = true)
AS
SELECT
    tenant_id,
    audit_entry_id,
    entry_type,
    audit_scope_code,
    trusted_at,
    action_code,
    result_code,
    actor_principal_id,
    actor_appointment_id,
    on_behalf_of_principal_id,
    on_behalf_of_appointment_id,
    command_id,
    command_type,
    correlation_id,
    causation_id,
    authorization_slot_code,
    authorization_path_code,
    authorization_scope_organization_unit_id,
    authorization_snapshot_digest,
    trace_id,
    service_role_code,
    summary_schema_code,
    summary_schema_version,
    change_summary,
    change_summary_digest,
    subject_type,
    subject_id,
    subject_revision,
    subject_hash,
    correction_target_type,
    correction_target_id,
    correction_target_revision,
    correction_target_hash,
    authorization_fact_type,
    authorization_fact_id,
    authorization_fact_revision,
    authorization_fact_hash
FROM audit.audit_entry;
COMMENT ON VIEW audit.audit_entry_classified_v IS
    '受控审计分类视图：仅供Query Facade在实时四轴重鉴权且先写查询审计后读取；排除会话HMAC、客户端IP密文和执行节点。';
COMMENT ON COLUMN audit.audit_entry_classified_v.tenant_id IS '分类审计字段：租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN audit.audit_entry_classified_v.audit_entry_id IS '分类审计字段：审计条目标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN audit.audit_entry_classified_v.entry_type IS '分类审计字段：条目类型：EVENT表示原始审计事实，CORRECTION表示对一条原记录的单链修正。';
COMMENT ON COLUMN audit.audit_entry_classified_v.audit_scope_code IS '分类审计字段：审计Scope：静态分类的租户、组织、对象或安全管理范围。';
COMMENT ON COLUMN audit.audit_entry_classified_v.trusted_at IS '分类审计字段：可信时间：被审计写入、拒绝或披露提交审计事务的服务端时间。';
COMMENT ON COLUMN audit.audit_entry_classified_v.action_code IS '分类审计字段：动作代码：来自静态审计动作注册表，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry_classified_v.result_code IS '分类审计字段：结果代码：SUCCEEDED、NO_CHANGE、REJECTED或FAILED，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry_classified_v.actor_principal_id IS '分类审计字段：实际发起身份主体标识：以同租户复合外键关联身份主体，创建后不可修改。';
COMMENT ON COLUMN audit.audit_entry_classified_v.actor_appointment_id IS '分类审计字段：实际采用的任职标识：以同租户复合外键关联任职；不适用时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.on_behalf_of_principal_id IS '分类审计字段：被代表Principal标识：非代办时为空，存在时与被代表任职一起冻结。';
COMMENT ON COLUMN audit.audit_entry_classified_v.on_behalf_of_appointment_id IS '分类审计字段：被代表任职标识：非代办时为空，存在时与被代表Principal一起冻结。';
COMMENT ON COLUMN audit.audit_entry_classified_v.command_id IS '分类审计字段：命令标识：由CommandRuntime产生的事件准确关联命令；非命令事件为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.command_type IS '分类审计字段：命令类型：与command_id同时存在；非命令事件为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.correlation_id IS '分类审计字段：关联标识：贯穿一次用户或服务请求的稳定UUID。';
COMMENT ON COLUMN audit.audit_entry_classified_v.causation_id IS '分类审计字段：因果标识：存在直接上游命令或事件时记录其稳定UUID。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_slot_code IS '分类审计字段：授权槽：本动作实际满足的唯一静态authoritySlot。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_path_code IS '分类审计字段：授权路径：DIRECT、DELEGATED、OBJECT或SYSTEM等静态单路径类型。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_scope_organization_unit_id IS '分类审计字段：授权组织Scope根：按提交时当前组织树解释；全租户系统路径时可为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_snapshot_digest IS '分类审计字段：授权依据快照摘要：冻结实际Actor、Appointment、路径、范围、限制和决定依据。';
COMMENT ON COLUMN audit.audit_entry_classified_v.trace_id IS '分类审计字段：追踪标识：把同一请求链上的审计事实关联起来，不是业务对象外键。';
COMMENT ON COLUMN audit.audit_entry_classified_v.service_role_code IS '分类审计字段：后端执行角色：API、WORKER或受控管理角色等静态代码。';
COMMENT ON COLUMN audit.audit_entry_classified_v.summary_schema_code IS '分类审计字段：变更摘要Schema：静态允许列表定义可出现的字段。';
COMMENT ON COLUMN audit.audit_entry_classified_v.summary_schema_version IS '分类审计字段：变更摘要Schema版本：解释允许列表化JSON结构的正整数版本。';
COMMENT ON COLUMN audit.audit_entry_classified_v.change_summary IS '分类审计字段：允许列表化变更摘要：仅保存必要字段变化，不得复制完整领域事实、请求响应、密码、Token、Secret或正文。';
COMMENT ON COLUMN audit.audit_entry_classified_v.change_summary_digest IS '分类审计字段：变更摘要摘要：规范化允许列表JSON的32字节SHA-256。';
COMMENT ON COLUMN audit.audit_entry_classified_v.subject_type IS '分类审计字段：本条审计所针对的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry_classified_v.subject_id IS '分类审计字段：本条审计所针对的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry_classified_v.subject_revision IS '分类审计字段：本条审计所针对的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.subject_hash IS '分类审计字段：本条审计所针对的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.correction_target_type IS '分类审计字段：本条更正所指向的原审计事实的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry_classified_v.correction_target_id IS '分类审计字段：本条更正所指向的原审计事实在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry_classified_v.correction_target_revision IS '分类审计字段：本条更正所指向的原审计事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.correction_target_hash IS '分类审计字段：本条更正所指向的原审计事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_fact_type IS '分类审计字段：执行被审计动作时实际采用的授权或委托Fact的静态注册类型。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_fact_id IS '分类审计字段：执行被审计动作时实际采用的授权或委托Fact在所属租户内的准确标识。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_fact_revision IS '分类审计字段：执行被审计动作时实际采用的授权或委托Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN audit.audit_entry_classified_v.authorization_fact_hash IS '分类审计字段：执行被审计动作时实际采用的授权或委托Fact的准确规范摘要；按修订冻结时为空。';
REVOKE ALL ON audit.audit_entry_classified_v FROM PUBLIC, ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};
GRANT SELECT ON audit.audit_entry_classified_v TO ${app_query_role};

CREATE FUNCTION platform_meta.fn_guard_domain_event_redrive()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $redrive$
DECLARE
    is_command_path boolean;
BEGIN
    is_command_path := pg_catalog.pg_has_role(current_user, TG_ARGV[0], 'MEMBER');
    IF OLD.status = 'EXHAUSTED' AND NEW.status = 'PENDING' THEN
        IF NOT is_command_path THEN
            RAISE EXCEPTION 'only the command role may redrive an exhausted domain outbox' USING ERRCODE = '42501';
        END IF;
        IF (to_jsonb(NEW) - ARRAY['status', 'available_at', 'revision']::text[])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['status', 'available_at', 'revision']::text[]) THEN
            RAISE EXCEPTION 'redrive may change only status, available_at and revision' USING ERRCODE = '55000';
        END IF;
    ELSIF is_command_path THEN
        RAISE EXCEPTION 'command role may update this outbox only for exhausted redrive' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$redrive$;
COMMENT ON FUNCTION platform_meta.fn_guard_domain_event_redrive() IS
    '授权重驱门禁：普通Worker不能把EXHAUSTED领域事件投递恢复为PENDING，只有CommandRuntime角色可在审计命令中原位重驱。';
CREATE TRIGGER trg_domain_event_outbox__authorized_redrive
BEFORE UPDATE ON execution.domain_event_outbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_domain_event_redrive('${app_command_role}');
COMMENT ON TRIGGER trg_domain_event_outbox__authorized_redrive ON execution.domain_event_outbox IS
    '重驱权限保护：EXHAUSTED到PENDING只接受CommandRuntime数据库角色。';
REVOKE ALL ON FUNCTION platform_meta.fn_guard_domain_event_redrive() FROM PUBLIC, ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};

GRANT SELECT ON platform_meta.deployment_state TO ${app_command_role}, ${app_worker_role}, ${app_query_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform_meta.deployment_state FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};
