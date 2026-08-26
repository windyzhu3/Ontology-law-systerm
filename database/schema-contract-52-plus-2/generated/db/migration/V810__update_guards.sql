-- 不可变事实与受控CAS更新守卫。

CREATE FUNCTION platform_meta.fn_reject_fact_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'immutable fact %.% rejects %', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_reject_fact_mutation() IS
    '不可变事实守卫：拒绝对已追加事实执行UPDATE或DELETE。';

CREATE FUNCTION platform_meta.fn_guard_controlled_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    allowed_columns text[] := string_to_array(TG_ARGV[0], ',');
    once_columns text[] := CASE WHEN TG_ARGV[1] = '' THEN ARRAY[]::text[] ELSE string_to_array(TG_ARGV[1], ',') END;
    state_column text := TG_ARGV[2];
    allowed_transitions text[] := CASE WHEN TG_ARGV[3] = '' THEN ARRAY[]::text[] ELSE string_to_array(TG_ARGV[3], ',') END;
    queue_mode boolean := TG_ARGV[4] = 'QUEUE';
    old_row jsonb := to_jsonb(OLD);
    new_row jsonb := to_jsonb(NEW);
    changed_column text;
    once_column text;
    transition text;
    semantic_change boolean := false;
    state_has_exit boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'controlled table %.% rejects DELETE', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;

    FOR changed_column IN
        SELECT changed.key
        FROM pg_catalog.jsonb_object_keys(old_row || new_row) AS changed(key)
        WHERE old_row -> changed.key IS DISTINCT FROM new_row -> changed.key
    LOOP
        IF NOT changed_column = ANY(allowed_columns) THEN
            RAISE EXCEPTION 'column % is immutable on %.%', changed_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
        IF changed_column NOT IN ('revision', 'changed_at', 'last_edited_at') THEN
            semantic_change := true;
        END IF;
    END LOOP;

    IF NOT semantic_change THEN
        RAISE EXCEPTION 'controlled update requires a semantic column change on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;

    IF (new_row ->> 'revision')::bigint <> (old_row ->> 'revision')::bigint + 1 THEN
        RAISE EXCEPTION 'revision must increment exactly once on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '40001';
    END IF;

    FOREACH once_column IN ARRAY once_columns LOOP
        IF old_row -> once_column <> 'null'::jsonb AND old_row -> once_column IS DISTINCT FROM new_row -> once_column THEN
            RAISE EXCEPTION 'write-once column % cannot change on %.%', once_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
    END LOOP;

    IF state_column <> '' AND old_row -> state_column IS DISTINCT FROM new_row -> state_column THEN
        transition := (old_row ->> state_column) || '>' || (new_row ->> state_column);
        IF NOT transition = ANY(allowed_transitions) THEN
            RAISE EXCEPTION 'transition % is forbidden on %.%', transition, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
    END IF;

    IF state_column <> '' AND old_row -> state_column IS NOT DISTINCT FROM new_row -> state_column THEN
        SELECT EXISTS (
            SELECT 1 FROM pg_catalog.unnest(allowed_transitions) AS candidate(transition_text)
            WHERE candidate.transition_text LIKE (old_row ->> state_column) || '>%'
        ) INTO state_has_exit;
        IF NOT state_has_exit THEN
            RAISE EXCEPTION 'terminal state % rejects further updates on %.%', old_row ->> state_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
    END IF;

    IF queue_mode THEN
        IF old_row ->> state_column = 'EXHAUSTED'
           AND new_row ->> state_column = 'EXHAUSTED' THEN
            RAISE EXCEPTION 'exhausted queue row rejects in-place mutation without authorized redrive on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
        IF (new_row ->> 'fencing_token')::bigint < (old_row ->> 'fencing_token')::bigint
           OR (new_row ->> 'attempt_count')::bigint < (old_row ->> 'attempt_count')::bigint THEN
            RAISE EXCEPTION 'queue counters cannot decrease on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
        IF old_row ->> state_column <> 'CLAIMED' AND new_row ->> state_column = 'CLAIMED'
           AND ((new_row ->> 'fencing_token')::bigint <> (old_row ->> 'fencing_token')::bigint + 1
             OR (new_row ->> 'attempt_count')::bigint <> (old_row ->> 'attempt_count')::bigint + 1) THEN
            RAISE EXCEPTION 'queue fencing_token must increment on claim together with attempt_count on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '40001';
        END IF;
        IF NOT (old_row ->> state_column <> 'CLAIMED' AND new_row ->> state_column = 'CLAIMED')
           AND ((new_row ->> 'fencing_token')::bigint <> (old_row ->> 'fencing_token')::bigint
             OR (new_row ->> 'attempt_count')::bigint <> (old_row ->> 'attempt_count')::bigint) THEN
            RAISE EXCEPTION 'queue counters can change only on claim on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
        IF old_row ->> state_column = 'CLAIMED' AND new_row ->> state_column = 'CLAIMED'
           AND (old_row ->> 'lease_owner') IS DISTINCT FROM (new_row ->> 'lease_owner') THEN
            RAISE EXCEPTION 'claimed queue lease_owner cannot change in place on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_guard_controlled_update() IS
    '受控更新守卫：要求语义变化、CAS精确递增、write-once单向写入、终态封存、白名单状态转换及队列计数和围栏单调。';

CREATE FUNCTION platform_meta.fn_guard_initial_state()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF (to_jsonb(NEW) ->> 'revision')::bigint <> 0 THEN
        RAISE EXCEPTION 'initial revision must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) ->> TG_ARGV[0]) IS DISTINCT FROM TG_ARGV[1] THEN
        RAISE EXCEPTION 'initial state must be % on %.%', TG_ARGV[1], TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF TG_ARGV[2] = 'QUEUE'
       AND (((to_jsonb(NEW) ->> 'fencing_token')::bigint <> 0)
         OR ((to_jsonb(NEW) ->> 'attempt_count')::bigint <> 0)) THEN
        RAISE EXCEPTION 'initial queue counters must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_guard_initial_state() IS
    '初态守卫：受控状态机必须以revision零、静态唯一初态创建；队列表围栏与尝试计数也必须从零开始。';

CREATE FUNCTION platform_meta.fn_guard_initial_revision()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF (to_jsonb(NEW) ->> 'revision')::bigint <> 0 THEN
        RAISE EXCEPTION 'initial revision must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_guard_initial_revision() IS
    '初始CAS守卫：没有状态字段的当前态锚点和单向槽位表也必须以revision零创建。';

CREATE FUNCTION platform_meta.fn_guard_initial_nulls()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    required_null_columns text[] := string_to_array(TG_ARGV[0], ',');
    required_null_column text;
BEGIN
    FOREACH required_null_column IN ARRAY required_null_columns LOOP
        IF to_jsonb(NEW) -> required_null_column IS DISTINCT FROM 'null'::jsonb THEN
            RAISE EXCEPTION 'initial column % must be null on %.%', required_null_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_guard_initial_nulls() IS
    '初始空槽守卫：冻结的后续单向结论或撤回槽不得在锚点事实创建时预填。';

CREATE TRIGGER trg_tenant__mutation_guard
BEFORE UPDATE OR DELETE ON identity.tenant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('display_name,state,closed_at,revision', 'closed_at', 'state', 'ACTIVE>SUSPENDED,SUSPENDED>ACTIVE,ACTIVE>CLOSED,SUSPENDED>CLOSED', 'CONTROLLED');
COMMENT ON TRIGGER trg_tenant__mutation_guard ON identity.tenant IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_tenant__initial_state
BEFORE INSERT ON identity.tenant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_tenant__initial_state ON identity.tenant IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_principal__mutation_guard
BEFORE UPDATE OR DELETE ON identity.principal
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('display_name,state,disabled_at,revision', 'disabled_at', 'state', 'ACTIVE>SUSPENDED,SUSPENDED>ACTIVE,ACTIVE>DISABLED,SUSPENDED>DISABLED', 'CONTROLLED');
COMMENT ON TRIGGER trg_principal__mutation_guard ON identity.principal IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_principal__initial_state
BEFORE INSERT ON identity.principal
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_principal__initial_state ON identity.principal IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_organization_unit__mutation_guard
BEFORE UPDATE OR DELETE ON identity.organization_unit
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('display_name,parent_organization_unit_id,state,closed_at,revision', 'closed_at', 'state', 'ACTIVE>CLOSED', 'CONTROLLED');
COMMENT ON TRIGGER trg_organization_unit__mutation_guard ON identity.organization_unit IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_organization_unit__initial_state
BEFORE INSERT ON identity.organization_unit
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_organization_unit__initial_state ON identity.organization_unit IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_appointment__mutation_guard
BEFORE UPDATE OR DELETE ON identity.appointment
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,ended_at,revision', 'ended_at', 'state', 'ACTIVE>SUSPENDED,SUSPENDED>ACTIVE,ACTIVE>ENDED,SUSPENDED>ENDED', 'CONTROLLED');
COMMENT ON TRIGGER trg_appointment__mutation_guard ON identity.appointment IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_appointment__initial_state
BEFORE INSERT ON identity.appointment
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_appointment__initial_state ON identity.appointment IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_authority_grant__mutation_guard
BEFORE UPDATE OR DELETE ON identity.authority_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,revoked_at,revocation_reason_code,revision', 'revoked_at,revocation_reason_code', 'state', 'ACTIVE>REVOKED', 'CONTROLLED');
COMMENT ON TRIGGER trg_authority_grant__mutation_guard ON identity.authority_grant IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_authority_grant__initial_state
BEFORE INSERT ON identity.authority_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_authority_grant__initial_state ON identity.authority_grant IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_delegation_grant__mutation_guard
BEFORE UPDATE OR DELETE ON identity.delegation_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,revoked_at,revocation_reason_code,revision', 'revoked_at,revocation_reason_code', 'state', 'ACTIVE>REVOKED', 'CONTROLLED');
COMMENT ON TRIGGER trg_delegation_grant__mutation_guard ON identity.delegation_grant IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_delegation_grant__initial_state
BEFORE INSERT ON identity.delegation_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_delegation_grant__initial_state ON identity.delegation_grant IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_object_access_grant__mutation_guard
BEFORE UPDATE OR DELETE ON identity.object_access_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,revoked_at,revocation_reason_code,revision', 'revoked_at,revocation_reason_code', 'state', 'ACTIVE>REVOKED', 'CONTROLLED');
COMMENT ON TRIGGER trg_object_access_grant__mutation_guard ON identity.object_access_grant IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_object_access_grant__initial_state
BEFORE INSERT ON identity.object_access_grant
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_object_access_grant__initial_state ON identity.object_access_grant IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_audit_entry__mutation_guard
BEFORE UPDATE OR DELETE ON audit.audit_entry
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_audit_entry__mutation_guard ON audit.audit_entry IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_task_occurrence__mutation_guard
BEFORE UPDATE OR DELETE ON responsibility.task_occurrence
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,completed_at,cancelled_at,cancellation_reason_code,completion_fact_type,completion_fact_id,completion_fact_revision,completion_fact_hash,revision', 'completed_at,cancelled_at,cancellation_reason_code,completion_fact_type,completion_fact_id,completion_fact_revision,completion_fact_hash', 'state', 'OPEN>WAITING,WAITING>OPEN,OPEN>DONE,WAITING>DONE,OPEN>CANCELLED,WAITING>CANCELLED', 'CONTROLLED');
COMMENT ON TRIGGER trg_task_occurrence__mutation_guard ON responsibility.task_occurrence IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_task_occurrence__initial_state
BEFORE INSERT ON responsibility.task_occurrence
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'OPEN', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_task_occurrence__initial_state ON responsibility.task_occurrence IS '初态保护：新行必须以OPEN创建。';

CREATE TRIGGER trg_decision_record__mutation_guard
BEFORE UPDATE OR DELETE ON responsibility.decision_record
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_decision_record__mutation_guard ON responsibility.decision_record IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_wait_receipt__mutation_guard
BEFORE UPDATE OR DELETE ON responsibility.wait_receipt
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_wait_receipt__mutation_guard ON responsibility.wait_receipt IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_action_draft__mutation_guard
BEFORE UPDATE OR DELETE ON responsibility.action_draft
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('candidate_payload,candidate_payload_digest,last_edited_at,state,confirmed_by_appointment_id,confirmed_at,confirmed_payload_digest,revision', 'confirmed_by_appointment_id,confirmed_at,confirmed_payload_digest', 'state', 'DRAFT>CONFIRMED', 'CONTROLLED');
COMMENT ON TRIGGER trg_action_draft__mutation_guard ON responsibility.action_draft IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_action_draft__initial_state
BEFORE INSERT ON responsibility.action_draft
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'state', 'DRAFT', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_action_draft__initial_state ON responsibility.action_draft IS '初态保护：新行必须以DRAFT创建。';

CREATE TRIGGER trg_command_execution_slot__mutation_guard
BEFORE UPDATE OR DELETE ON execution.command_execution_slot
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_command_execution_slot__mutation_guard ON execution.command_execution_slot IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_command_receipt__mutation_guard
BEFORE UPDATE OR DELETE ON execution.command_receipt
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_command_receipt__mutation_guard ON execution.command_receipt IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_domain_event__mutation_guard
BEFORE UPDATE OR DELETE ON execution.domain_event
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_domain_event__mutation_guard ON execution.domain_event IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_domain_event_outbox__mutation_guard
BEFORE UPDATE OR DELETE ON execution.domain_event_outbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('status,available_at,lease_owner,lease_until,fencing_token,attempt_count,delivered_at,last_error_code,revision', 'delivered_at', 'status', 'PENDING>CLAIMED,CLAIMED>PENDING,CLAIMED>DELIVERED,CLAIMED>EXHAUSTED,EXHAUSTED>PENDING', 'QUEUE');
COMMENT ON TRIGGER trg_domain_event_outbox__mutation_guard ON execution.domain_event_outbox IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_domain_event_outbox__initial_state
BEFORE INSERT ON execution.domain_event_outbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'status', 'PENDING', 'QUEUE'
);
COMMENT ON TRIGGER trg_domain_event_outbox__initial_state ON execution.domain_event_outbox IS '初态保护：新行必须以PENDING创建。';

CREATE TRIGGER trg_external_action__mutation_guard
BEFORE UPDATE OR DELETE ON external_action.external_action
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('status,dispatched_at,provider_action_id,completed_at,result_code,result_digest,resolution_method_code,resolution_source_type,resolution_source_id,resolution_source_revision,resolution_source_hash,last_error_code,revision', 'dispatched_at,provider_action_id,completed_at,result_code,result_digest,resolution_method_code,resolution_source_type,resolution_source_id,resolution_source_revision,resolution_source_hash', 'status', 'PENDING>DISPATCHED,PENDING>UNKNOWN,DISPATCHED>SUCCEEDED,DISPATCHED>FAILED,DISPATCHED>UNKNOWN,UNKNOWN>SUCCEEDED,UNKNOWN>FAILED', 'CONTROLLED');
COMMENT ON TRIGGER trg_external_action__mutation_guard ON external_action.external_action IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_external_action__initial_state
BEFORE INSERT ON external_action.external_action
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'status', 'PENDING', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_external_action__initial_state ON external_action.external_action IS '初态保护：新行必须以PENDING创建。';

CREATE TRIGGER trg_external_action_outbox__mutation_guard
BEFORE UPDATE OR DELETE ON external_action.external_action_outbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('status,available_at,lease_owner,lease_until,fencing_token,attempt_count,delivered_at,last_error_code,revision', 'delivered_at', 'status', 'PENDING>CLAIMED,CLAIMED>PENDING,CLAIMED>DELIVERED,CLAIMED>EXHAUSTED', 'QUEUE');
COMMENT ON TRIGGER trg_external_action_outbox__mutation_guard ON external_action.external_action_outbox IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_external_action_outbox__initial_state
BEFORE INSERT ON external_action.external_action_outbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'status', 'PENDING', 'QUEUE'
);
COMMENT ON TRIGGER trg_external_action_outbox__initial_state ON external_action.external_action_outbox IS '初态保护：新行必须以PENDING创建。';

CREATE TRIGGER trg_provider_inbox__mutation_guard
BEFORE UPDATE OR DELETE ON external_action.provider_inbox
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_provider_inbox__mutation_guard ON external_action.provider_inbox IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_upload_session__mutation_guard
BEFORE UPDATE OR DELETE ON evidence.upload_session
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('status,received_at,finalized_at,revision', 'received_at,finalized_at', 'status', 'OPEN>OBJECT_RECEIVED,OBJECT_RECEIVED>FINALIZED,OPEN>EXPIRED,OPEN>CANCELLED', 'CONTROLLED');
COMMENT ON TRIGGER trg_upload_session__mutation_guard ON evidence.upload_session IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_upload_session__initial_state
BEFORE INSERT ON evidence.upload_session
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'status', 'OPEN', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_upload_session__initial_state ON evidence.upload_session IS '初态保护：新行必须以OPEN创建。';

CREATE TRIGGER trg_received_source_object__mutation_guard
BEFORE UPDATE OR DELETE ON evidence.received_source_object
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_received_source_object__mutation_guard ON evidence.received_source_object IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_evidence_submission__mutation_guard
BEFORE UPDATE OR DELETE ON evidence.evidence_submission
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_evidence_submission__mutation_guard ON evidence.evidence_submission IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_evidence_binding__mutation_guard
BEFORE UPDATE OR DELETE ON evidence.evidence_binding
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code,revision', 'revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_evidence_binding__mutation_guard ON evidence.evidence_binding IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_evidence_binding__initial_revision
BEFORE INSERT ON evidence.evidence_binding
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_evidence_binding__initial_revision ON evidence.evidence_binding IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_party__mutation_guard
BEFORE UPDATE OR DELETE ON party.party
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('canonical_name,primary_identifier_type,primary_identifier_ciphertext,primary_identifier_hmac,status,merged_into_party_id,merged_at,revision', 'merged_into_party_id,merged_at', 'status', 'ACTIVE>MERGED', 'CONTROLLED');
COMMENT ON TRIGGER trg_party__mutation_guard ON party.party IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_party__initial_state
BEFORE INSERT ON party.party
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'status', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_party__initial_state ON party.party IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_lead__mutation_guard
BEFORE UPDATE OR DELETE ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('parsed_party_id,party_resolution_code,disposition_code,current_assignment_id,revision', '', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_lead__mutation_guard ON lead.lead IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_lead__initial_revision
BEFORE INSERT ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_lead__initial_revision ON lead.lead IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_lead_assignment__mutation_guard
BEFORE UPDATE OR DELETE ON lead.lead_assignment
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('assignment_status_code,closed_at,close_reason_code,revision', 'closed_at,close_reason_code', 'assignment_status_code', 'OPEN>CLOSED', 'CONTROLLED');
COMMENT ON TRIGGER trg_lead_assignment__mutation_guard ON lead.lead_assignment IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_lead_assignment__initial_state
BEFORE INSERT ON lead.lead_assignment
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'assignment_status_code', 'OPEN', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_lead_assignment__initial_state ON lead.lead_assignment IS '初态保护：新行必须以OPEN创建。';

CREATE TRIGGER trg_lead_contact_result__mutation_guard
BEFORE UPDATE OR DELETE ON lead.lead_contact_result
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_lead_contact_result__mutation_guard ON lead.lead_contact_result IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_opportunity__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.opportunity
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('current_quote_revision_id,close_outcome_code,closed_at,revision', 'close_outcome_code,closed_at', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_opportunity__mutation_guard ON opportunity.opportunity IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_opportunity__initial_revision
BEFORE INSERT ON opportunity.opportunity
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_opportunity__initial_revision ON opportunity.opportunity IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_opportunity_participation__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.opportunity_participation
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_opportunity_participation__mutation_guard ON opportunity.opportunity_participation IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_opportunity_progress__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.opportunity_progress
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_opportunity_progress__mutation_guard ON opportunity.opportunity_progress IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_quote_revision__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_revision
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_revision__mutation_guard ON opportunity.quote_revision IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_quote_service_scope__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_service_scope
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_service_scope__mutation_guard ON opportunity.quote_service_scope IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_quote_line__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_line
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_line__mutation_guard ON opportunity.quote_line IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_quote_payment_term__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_payment_term
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_payment_term__mutation_guard ON opportunity.quote_payment_term IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_quote_issue__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_issue
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('issue_status_code,revoked_at,revocation_reason_code,revision', 'revoked_at,revocation_reason_code', 'issue_status_code', 'ACTIVE>REVOKED', 'CONTROLLED');
COMMENT ON TRIGGER trg_quote_issue__mutation_guard ON opportunity.quote_issue IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_quote_issue__initial_state
BEFORE INSERT ON opportunity.quote_issue
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'issue_status_code', 'ACTIVE', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_quote_issue__initial_state ON opportunity.quote_issue IS '初态保护：新行必须以ACTIVE创建。';

CREATE TRIGGER trg_quote_response__mutation_guard
BEFORE UPDATE OR DELETE ON opportunity.quote_response
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_quote_response__mutation_guard ON opportunity.quote_response IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_conflict_review__mutation_guard
BEFORE UPDATE OR DELETE ON conflict.conflict_review
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('resolution_code,resolution_digest,resolved_at,revision', 'resolution_code,resolution_digest,resolved_at', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_conflict_review__mutation_guard ON conflict.conflict_review IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_conflict_review__initial_revision
BEFORE INSERT ON conflict.conflict_review
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_conflict_review__initial_revision ON conflict.conflict_review IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_conflict_review_party__mutation_guard
BEFORE UPDATE OR DELETE ON conflict.conflict_review_party
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_conflict_review_party__mutation_guard ON conflict.conflict_review_party IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_conflict_finding__mutation_guard
BEFORE UPDATE OR DELETE ON conflict.conflict_finding
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_conflict_finding__mutation_guard ON conflict.conflict_finding IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_contract__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('current_revision_id,approved_revision_id,contract_execution_id,deal_activated_at,activation_source_type,activation_source_id,activation_source_revision,activation_source_hash,contract_termination_id,revision,changed_at', 'contract_execution_id,deal_activated_at,activation_source_type,activation_source_id,activation_source_revision,activation_source_hash,contract_termination_id', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_contract__mutation_guard ON contract.contract IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_contract__initial_revision
BEFORE INSERT ON contract.contract
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_contract__initial_revision ON contract.contract IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_contract_revision__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_revision
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_contract_revision__mutation_guard ON contract.contract_revision IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_contract_participation__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_participation
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_contract_participation__mutation_guard ON contract.contract_participation IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_contract_fee_term__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_fee_term
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_contract_fee_term__mutation_guard ON contract.contract_fee_term IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_payment_gate__mutation_guard
BEFORE UPDATE OR DELETE ON contract.payment_gate
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('gate_state,satisfied_at,satisfaction_digest,payment_confirmation_ids,confirmation_set_digest,risk_decision_record_id,revision,changed_at', 'satisfied_at,satisfaction_digest,payment_confirmation_ids,confirmation_set_digest,risk_decision_record_id', 'gate_state', 'PENDING>SATISFIED', 'CONTROLLED');
COMMENT ON TRIGGER trg_payment_gate__mutation_guard ON contract.payment_gate IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_payment_gate__initial_state
BEFORE INSERT ON contract.payment_gate
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_state(
    'gate_state', 'PENDING', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_payment_gate__initial_state ON contract.payment_gate IS '初态保护：新行必须以PENDING创建。';

CREATE TRIGGER trg_signature_plan__mutation_guard
BEFORE UPDATE OR DELETE ON contract.signature_plan
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_signature_plan__mutation_guard ON contract.signature_plan IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_contract_signature__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_signature
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code,revision,changed_at', 'revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_contract_signature__mutation_guard ON contract.contract_signature IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_contract_signature__initial_revision
BEFORE INSERT ON contract.contract_signature
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_contract_signature__initial_revision ON contract.contract_signature IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_contract_execution__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_execution
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_contract_execution__mutation_guard ON contract.contract_execution IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_payment_confirmation__mutation_guard
BEFORE UPDATE OR DELETE ON contract.payment_confirmation
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_payment_confirmation__mutation_guard ON contract.payment_confirmation IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_contract_termination__mutation_guard
BEFORE UPDATE OR DELETE ON contract.contract_termination
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('refund_calculation_minor,refund_currency_code,refund_calculation_digest,refund_calculated_at,revision,changed_at', 'refund_calculation_minor,refund_currency_code,refund_calculation_digest,refund_calculated_at', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_contract_termination__mutation_guard ON contract.contract_termination IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_contract_termination__initial_revision
BEFORE INSERT ON contract.contract_termination
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_contract_termination__initial_revision ON contract.contract_termination IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_transfer_request__mutation_guard
BEFORE UPDATE OR DELETE ON transfer.transfer_request
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('accepted_snapshot_id,accept_decision_record_id,matter_id,matter_no,matter_type_code,matter_capability_pack_code,matter_capability_pack_version,matter_created_at,revision,changed_at', 'accepted_snapshot_id,accept_decision_record_id,matter_id,matter_no,matter_type_code,matter_capability_pack_code,matter_capability_pack_version,matter_created_at', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_transfer_request__mutation_guard ON transfer.transfer_request IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_transfer_request__initial_revision
BEFORE INSERT ON transfer.transfer_request
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_transfer_request__initial_revision ON transfer.transfer_request IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_transfer_snapshot__mutation_guard
BEFORE UPDATE OR DELETE ON transfer.transfer_snapshot
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_transfer_snapshot__mutation_guard ON transfer.transfer_snapshot IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_transfer_return_item__mutation_guard
BEFORE UPDATE OR DELETE ON transfer.transfer_return_item
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_transfer_return_item__mutation_guard ON transfer.transfer_return_item IS '不可变性保护：拒绝更新或删除已写入事实。';

CREATE TRIGGER trg_deployment_state__mutation_guard
BEFORE UPDATE OR DELETE ON platform_meta.deployment_state
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('operating_mode,active_release_digest,active_manifest_hash,schema_contract_version,revision,changed_at', '', '', '', 'CONTROLLED');
COMMENT ON TRIGGER trg_deployment_state__mutation_guard ON platform_meta.deployment_state IS '受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。';

CREATE TRIGGER trg_deployment_state__initial_revision
BEFORE INSERT ON platform_meta.deployment_state
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();
COMMENT ON TRIGGER trg_deployment_state__initial_revision ON platform_meta.deployment_state IS '初始CAS保护：新行revision必须为零。';

CREATE TRIGGER trg_evidence_binding__initial_active
BEFORE INSERT ON evidence.evidence_binding
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code'
);
COMMENT ON TRIGGER trg_evidence_binding__initial_active ON evidence.evidence_binding IS 'Binding创建时必须有效，撤回槽只能由后续授权命令一次写入。';

CREATE TRIGGER trg_lead__initial_unassigned
BEFORE INSERT ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'current_assignment_id'
);
COMMENT ON TRIGGER trg_lead__initial_unassigned ON lead.lead IS 'Lead接入锚点创建时不得预填当前分派；首次Assignment必须在后续同一短事务追加并受控回填指针。';

CREATE TRIGGER trg_opportunity__initial_open
BEFORE INSERT ON opportunity.opportunity
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'current_quote_revision_id,close_outcome_code,closed_at'
);
COMMENT ON TRIGGER trg_opportunity__initial_open ON opportunity.opportunity IS 'Opportunity由CONNECTED_VALID结果创建时不得预填报价或关闭槽。';

CREATE TRIGGER trg_conflict_review__initial_unresolved
BEFORE INSERT ON conflict.conflict_review
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'resolution_code,resolution_digest,resolved_at'
);
COMMENT ON TRIGGER trg_conflict_review__initial_unresolved ON conflict.conflict_review IS 'ConflictReview封存初始结论时不得预填后续WAIVED或BLOCKED解决槽。';

CREATE TRIGGER trg_contract_signature__initial_active
BEFORE INSERT ON contract.contract_signature
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'revoked_at,revoked_by_appointment_id,revocation_authorization_digest,revocation_reason_code'
);
COMMENT ON TRIGGER trg_contract_signature__initial_active ON contract.contract_signature IS 'ContractSignature追加时必须有效，撤回只能由执行前后续授权命令单向写入。';

CREATE FUNCTION platform_meta.fn_assert_owned_pointer()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    pointer_value uuid;
    owner_value uuid;
    tenant_value uuid;
    relation_matches boolean;
BEGIN
    pointer_value := nullif(to_jsonb(NEW) ->> TG_ARGV[0], '')::uuid;
    IF pointer_value IS NULL THEN
        RETURN NEW;
    END IF;
    tenant_value := (to_jsonb(NEW) ->> 'tenant_id')::uuid;
    owner_value := (to_jsonb(NEW) ->> TG_ARGV[5])::uuid;
    EXECUTE pg_catalog.format(
        'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE tenant_id = $1 AND %I = $2 AND %I = $3)',
        TG_ARGV[1], TG_ARGV[2], TG_ARGV[3], TG_ARGV[4]
    ) INTO relation_matches USING tenant_value, pointer_value, owner_value;
    IF NOT relation_matches THEN
        RAISE EXCEPTION 'owned pointer % on %.% does not belong to its anchor', TG_ARGV[0], TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_owned_pointer() IS
    '延迟归属守卫：提交时证明冻结当前指针或单向槽位所指事实属于同租户同一业务锚点。';

CREATE FUNCTION platform_meta.fn_assert_evidence_finalization()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    matching_count integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'upload session must be created OPEN' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status = 'OBJECT_RECEIVED' THEN
        SELECT count(*) INTO matching_count
        FROM evidence.received_source_object source_object
        WHERE source_object.tenant_id = NEW.tenant_id
          AND source_object.upload_session_id = NEW.upload_session_id
          AND source_object.object_store_code = NEW.object_store_code
          AND source_object.object_key = NEW.object_key;
        IF matching_count <> 1 THEN
            RAISE EXCEPTION 'OBJECT_RECEIVED requires one exact source object' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status = 'FINALIZED' THEN
        SELECT count(*) INTO matching_count
        FROM evidence.received_source_object source_object
        JOIN evidence.evidence_submission submission
          ON submission.tenant_id = source_object.tenant_id
         AND submission.received_source_object_id = source_object.received_source_object_id
        JOIN evidence.evidence_binding binding
          ON binding.tenant_id = submission.tenant_id
         AND binding.evidence_submission_id = submission.evidence_submission_id
        WHERE source_object.tenant_id = NEW.tenant_id
          AND source_object.upload_session_id = NEW.upload_session_id
          AND source_object.object_store_code = NEW.object_store_code
          AND source_object.object_key = NEW.object_key
          AND source_object.scan_result = 'PASSED'
          AND binding.purpose_code = NEW.purpose_code
          AND binding.target_type = NEW.target_type
          AND binding.target_id = NEW.target_id
          AND binding.target_revision IS NOT DISTINCT FROM NEW.target_revision
          AND binding.target_hash IS NOT DISTINCT FROM NEW.target_hash
          AND binding.revoked_at IS NULL;
        IF matching_count <> 1 THEN
            RAISE EXCEPTION 'FINALIZED requires one passed source, immutable submission and exact active binding' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_evidence_finalization() IS
    '证据晋级守卫：会话创建必须为OPEN；接收与最终晋级在提交时分别证明准确来源对象及PASSED、Submission和同目标同用途有效Binding链。';

CREATE FUNCTION platform_meta.fn_assert_evidence_promotion_member()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    matching_count integer;
BEGIN
    IF TG_TABLE_NAME = 'received_source_object' THEN
        SELECT count(*) INTO matching_count
        FROM evidence.upload_session session
        WHERE session.tenant_id = NEW.tenant_id
          AND session.upload_session_id = NEW.upload_session_id
          AND session.object_store_code = NEW.object_store_code
          AND session.object_key = NEW.object_key
          AND session.status IN ('OBJECT_RECEIVED', 'FINALIZED');
        IF matching_count <> 1 THEN
            RAISE EXCEPTION 'source object requires its exact received upload session' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT count(*) INTO matching_count
    FROM evidence.received_source_object source_object
    JOIN evidence.upload_session session
      ON session.tenant_id = source_object.tenant_id
     AND session.upload_session_id = source_object.upload_session_id
     AND session.object_store_code = source_object.object_store_code
     AND session.object_key = source_object.object_key
    JOIN evidence.evidence_submission submission
      ON submission.tenant_id = source_object.tenant_id
     AND submission.received_source_object_id = source_object.received_source_object_id
    JOIN evidence.evidence_binding binding
      ON binding.tenant_id = submission.tenant_id
     AND binding.evidence_submission_id = submission.evidence_submission_id
    WHERE source_object.tenant_id = NEW.tenant_id
      AND source_object.scan_result = 'PASSED'
      AND session.status = 'FINALIZED'
      AND binding.revoked_at IS NULL
      AND binding.purpose_code = session.purpose_code
      AND binding.target_type = session.target_type
      AND binding.target_id = session.target_id
      AND binding.target_revision IS NOT DISTINCT FROM session.target_revision
      AND binding.target_hash IS NOT DISTINCT FROM session.target_hash
      AND submission.evidence_submission_id = NEW.evidence_submission_id;
    IF matching_count <> 1 THEN
        RAISE EXCEPTION 'submission and binding must be members of one exact finalized evidence promotion' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_evidence_promotion_member() IS
    '证据晋级成员守卫：反向证明SourceObject、Submission和Binding只能属于同一准确FINALIZED会话，防止绕过最终晋级命令单独插入。';

CREATE FUNCTION platform_meta.fn_assert_lead_assignment_chain()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
    current_assignment uuid;
BEGIN
    SELECT lead_root.current_assignment_id INTO current_assignment
    FROM lead.lead lead_root
    WHERE lead_root.tenant_id = NEW.tenant_id AND lead_root.lead_id = NEW.lead_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'lead assignment requires its exact lead root' USING ERRCODE = '23503';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.assignment_no > 1 THEN
        SELECT EXISTS (
            SELECT 1 FROM lead.lead_assignment predecessor
            WHERE predecessor.tenant_id = NEW.tenant_id
              AND predecessor.lead_assignment_id = NEW.previous_assignment_id
              AND predecessor.lead_id = NEW.lead_id
              AND predecessor.assignment_no + 1 = NEW.assignment_no
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'lead assignment must follow the direct predecessor of the same lead' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.assignment_status_code = 'OPEN' THEN
        IF current_assignment IS DISTINCT FROM NEW.lead_assignment_id THEN
            RAISE EXCEPTION 'open assignment must be the lead current assignment in the same transaction' USING ERRCODE = '23514';
        END IF;
    ELSIF current_assignment IS DISTINCT FROM NEW.lead_assignment_id
          AND NOT EXISTS (
        SELECT 1 FROM lead.lead_assignment successor
        WHERE successor.tenant_id = NEW.tenant_id
          AND successor.lead_assignment_id = current_assignment
          AND successor.lead_id = NEW.lead_id
          AND successor.previous_assignment_id = NEW.lead_assignment_id
          AND successor.assignment_no = NEW.assignment_no + 1
          AND successor.assignment_status_code = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'closed assignment may remain the terminal current leaf or require its direct open successor to be current' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_lead_assignment_chain() IS
    '销售分配链守卫：锁定Lead根，证明Assignment直接追加；OPEN项必须是当前指针，关闭项可作为终局当前叶，改派时必须由直接OPEN后继原子替代。';

CREATE FUNCTION platform_meta.fn_assert_lead_current_assignment()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_assignment_id IS NOT NULL THEN
            RAISE EXCEPTION 'lead must be inserted without a current assignment' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.current_assignment_id IS NOT DISTINCT FROM OLD.current_assignment_id THEN
        RETURN NEW;
    END IF;
    IF NEW.current_assignment_id IS NULL THEN
        RAISE EXCEPTION 'current assignment cannot be cleared or rolled back' USING ERRCODE = '23514';
    END IF;
    IF OLD.current_assignment_id IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM lead.lead_assignment first_assignment
            WHERE first_assignment.tenant_id = NEW.tenant_id
              AND first_assignment.lead_assignment_id = NEW.current_assignment_id
              AND first_assignment.lead_id = NEW.lead_id
              AND first_assignment.assignment_no = 1
              AND first_assignment.previous_assignment_id IS NULL
              AND first_assignment.assignment_status_code = 'OPEN'
        ) THEN
            RAISE EXCEPTION 'first current assignment must select the open chain head' USING ERRCODE = '23514';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM lead.lead_assignment successor
        JOIN lead.lead_assignment predecessor
          ON predecessor.tenant_id = successor.tenant_id
         AND predecessor.lead_assignment_id = successor.previous_assignment_id
        WHERE successor.tenant_id = NEW.tenant_id
          AND successor.lead_assignment_id = NEW.current_assignment_id
          AND successor.lead_id = NEW.lead_id
          AND successor.assignment_status_code = 'OPEN'
          AND predecessor.lead_assignment_id = OLD.current_assignment_id
          AND predecessor.lead_id = NEW.lead_id
          AND predecessor.assignment_status_code = 'CLOSED'
          AND successor.assignment_no = predecessor.assignment_no + 1
    ) THEN
        RAISE EXCEPTION 'current assignment must advance to the direct open successor' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_lead_current_assignment() IS
    'Lead当前分派守卫：初建无指针，首次只指OPEN链首，后续只能在旧Assignment已关闭时沿直接OPEN后继前移，禁止清空、回拨或跳段。';

CREATE FUNCTION platform_meta.fn_assert_opportunity_qualified_source()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM lead.lead_contact_result contact_result
        WHERE contact_result.tenant_id = NEW.tenant_id
          AND contact_result.lead_contact_result_id = NEW.source_contact_result_id
          AND contact_result.lead_id = NEW.source_lead_id
          AND contact_result.lead_assignment_id = NEW.source_assignment_id
          AND contact_result.result_code = 'CONNECTED_VALID'
    ) THEN
        RAISE EXCEPTION 'opportunity requires its exact CONNECTED_VALID contact result' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_opportunity_qualified_source() IS
    '商机来源守卫：Opportunity只能由同Lead、同Assignment的准确CONNECTED_VALID联系结果形成。';

CREATE FUNCTION platform_meta.fn_assert_quote_revision_package()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
BEGIN
    PERFORM 1 FROM opportunity.opportunity opportunity_root
    WHERE opportunity_root.tenant_id = NEW.tenant_id
      AND opportunity_root.opportunity_id = NEW.opportunity_id
      AND opportunity_root.closed_at IS NULL
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'quote revision requires its exact open opportunity root' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM opportunity.opportunity opportunity_root
        WHERE opportunity_root.tenant_id = NEW.tenant_id
          AND opportunity_root.opportunity_id = NEW.opportunity_id
          AND opportunity_root.current_quote_revision_id = NEW.quote_revision_id
    ) THEN
        RAISE EXCEPTION 'quote revision must become the current revision in the same transaction' USING ERRCODE = '23514';
    END IF;
    IF NEW.quote_revision_no > 1 THEN
        SELECT EXISTS (
            SELECT 1 FROM opportunity.quote_revision predecessor
            WHERE predecessor.tenant_id = NEW.tenant_id
              AND predecessor.quote_revision_id = NEW.predecessor_quote_revision_id
              AND predecessor.opportunity_id = NEW.opportunity_id
              AND predecessor.quote_revision_no + 1 = NEW.quote_revision_no
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'quote revision must follow the direct predecessor of the same opportunity' USING ERRCODE = '23514';
        END IF;
    END IF;
    SELECT EXISTS (
        SELECT 1
        FROM opportunity.opportunity_participation participation
        WHERE participation.tenant_id = NEW.tenant_id
          AND participation.opportunity_id = NEW.opportunity_id
          AND participation.participation_set_revision = NEW.participation_set_revision
        GROUP BY participation.opportunity_id, participation.participation_set_revision
        HAVING count(*) = max(participation.participation_set_size)
           AND min(participation.participation_set_size) = max(participation.participation_set_size)
           AND min(participation.participation_no) = 1
           AND max(participation.participation_no) = max(participation.participation_set_size)
           AND pg_catalog.bool_and(participation.participation_set_digest = NEW.participation_set_digest)
    ) INTO relation_matches;
    IF NOT relation_matches THEN
        RAISE EXCEPTION 'quote revision requires one complete frozen participation set' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_quote_revision_package() IS
    '报价版本包守卫：锁定Opportunity根，证明直接版本后继、同事务当前指针及完整连续Participation集合与摘要。';

CREATE FUNCTION platform_meta.fn_assert_participation_set_quoted()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM opportunity.quote_revision quote_revision
        WHERE quote_revision.tenant_id = NEW.tenant_id
          AND quote_revision.opportunity_id = NEW.opportunity_id
          AND quote_revision.participation_set_revision = NEW.participation_set_revision
          AND quote_revision.participation_set_digest = NEW.participation_set_digest
    ) THEN
        RAISE EXCEPTION 'participation set must be sealed by its quote revision in the same transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_participation_set_quoted() IS
    '参与集合成员守卫：每个不可变OpportunityParticipation必须被同版本同摘要QuoteRevision反向封存。';

CREATE FUNCTION platform_meta.fn_assert_opportunity_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'closed opportunity is sealed' USING ERRCODE = '55000';
    END IF;
    IF NEW.current_quote_revision_id IS DISTINCT FROM OLD.current_quote_revision_id THEN
        IF NEW.current_quote_revision_id IS NULL THEN
            RAISE EXCEPTION 'current quote revision cannot be cleared' USING ERRCODE = '23514';
        END IF;
        IF OLD.current_quote_revision_id IS NULL THEN
            IF NOT EXISTS (
                SELECT 1 FROM opportunity.quote_revision first_revision
                WHERE first_revision.tenant_id = NEW.tenant_id
                  AND first_revision.quote_revision_id = NEW.current_quote_revision_id
                  AND first_revision.opportunity_id = NEW.opportunity_id
                  AND first_revision.quote_revision_no = 1
                  AND first_revision.predecessor_quote_revision_id IS NULL
            ) THEN
                RAISE EXCEPTION 'first current quote pointer must select revision one' USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1
            FROM opportunity.quote_revision successor
            JOIN opportunity.quote_revision predecessor
              ON predecessor.tenant_id = successor.tenant_id
             AND predecessor.quote_revision_id = successor.predecessor_quote_revision_id
            WHERE successor.tenant_id = NEW.tenant_id
              AND successor.quote_revision_id = NEW.current_quote_revision_id
              AND successor.opportunity_id = NEW.opportunity_id
              AND predecessor.quote_revision_id = OLD.current_quote_revision_id
              AND predecessor.opportunity_id = NEW.opportunity_id
              AND successor.quote_revision_no = predecessor.quote_revision_no + 1
        ) THEN
            RAISE EXCEPTION 'current quote pointer must advance to the direct successor' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_opportunity_lifecycle() IS
    '商机生命周期守卫：当前Quote指针只能从空指向首版或沿直接后继前移；关闭事实形成后整行封存，不得回拨、跳版或重开。';

CREATE FUNCTION platform_meta.fn_assert_contract_revision_package()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    source_matches boolean;
BEGIN
    PERFORM 1 FROM contract.contract contract_root
    WHERE contract_root.tenant_id = NEW.tenant_id
      AND contract_root.contract_id = NEW.contract_id
      AND contract_root.current_revision_id = NEW.contract_revision_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract revision must become current in the same transaction' USING ERRCODE = '23514';
    END IF;
    PERFORM 1
    FROM opportunity.quote_response response
    JOIN opportunity.quote_issue issue
      ON issue.tenant_id = response.tenant_id
     AND issue.quote_issue_id = response.quote_issue_id
    WHERE response.tenant_id = NEW.tenant_id
      AND response.quote_response_id = NEW.source_quote_response_id
    FOR UPDATE OF issue;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract revision requires its exact issued quote response' USING ERRCODE = '23503';
    END IF;
    SELECT EXISTS (
        SELECT 1
        FROM contract.contract contract_root
        JOIN opportunity.quote_response response
          ON response.tenant_id = contract_root.tenant_id
         AND response.quote_response_id = contract_root.accepted_quote_response_id
         AND response.quote_response_id = NEW.source_quote_response_id
        JOIN opportunity.quote_issue issue
          ON issue.tenant_id = response.tenant_id
         AND issue.quote_issue_id = response.quote_issue_id
        JOIN opportunity.quote_revision quote_revision
          ON quote_revision.tenant_id = issue.tenant_id
         AND quote_revision.quote_revision_id = issue.quote_revision_id
         AND quote_revision.quote_revision_id = NEW.source_quote_revision_id
        WHERE contract_root.tenant_id = NEW.tenant_id
          AND contract_root.contract_id = NEW.contract_id
          AND response.response_code = 'ACCEPTED'
          AND quote_revision.opportunity_id = contract_root.opportunity_id
    ) INTO source_matches;
    IF NOT source_matches THEN
        RAISE EXCEPTION 'contract revision quote and accepted response must be the anchor consumed source chain' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_contract_revision_package() IS
    '合同版本来源守卫：锁定Contract和QuoteIssue，证明版本在同事务成为当前版本，且来源报价、Issue及ACCEPTED回应属于锚点已经消费的同一历史销售链；自然期限只在锚点创建时判断。';

CREATE FUNCTION platform_meta.fn_assert_contract_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1
        FROM opportunity.quote_response response
        JOIN opportunity.quote_issue issue
          ON issue.tenant_id = response.tenant_id
         AND issue.quote_issue_id = response.quote_issue_id
        WHERE response.tenant_id = NEW.tenant_id
          AND response.quote_response_id = NEW.accepted_quote_response_id
        FOR UPDATE OF issue;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'contract requires its exact issued quote response' USING ERRCODE = '23503';
        END IF;
        SELECT EXISTS (
            SELECT 1
            FROM opportunity.quote_response response
            JOIN opportunity.quote_issue issue
              ON issue.tenant_id = response.tenant_id
             AND issue.quote_issue_id = response.quote_issue_id
            JOIN opportunity.quote_revision quote_revision
              ON quote_revision.tenant_id = issue.tenant_id
             AND quote_revision.quote_revision_id = issue.quote_revision_id
            WHERE response.tenant_id = NEW.tenant_id
              AND response.quote_response_id = NEW.accepted_quote_response_id
              AND response.response_code = 'ACCEPTED'
              AND issue.issue_status_code = 'ACTIVE'
              AND quote_revision.opportunity_id = NEW.opportunity_id
              AND (quote_revision.valid_until IS NULL
                   OR quote_revision.valid_until > pg_catalog.clock_timestamp())
              AND NOT EXISTS (
                  SELECT 1 FROM opportunity.quote_issue replacement
                  WHERE replacement.tenant_id = issue.tenant_id
                    AND replacement.replaces_quote_issue_id = issue.quote_issue_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM opportunity.quote_response later_response
                  WHERE later_response.tenant_id = response.tenant_id
                    AND later_response.quote_issue_id = response.quote_issue_id
                    AND later_response.response_no > response.response_no
              )
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'contract requires an active accepted quote response from the same opportunity' USING ERRCODE = '23514';
        END IF;
        IF NEW.current_revision_id IS NOT NULL
           OR NEW.approved_revision_id IS NOT NULL
           OR NEW.contract_execution_id IS NOT NULL
           OR NEW.deal_activated_at IS NOT NULL
           OR NEW.contract_termination_id IS NOT NULL THEN
            RAISE EXCEPTION 'contract lifecycle slots must be empty on anchor insert' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.contract_termination_id IS NOT NULL THEN
        RAISE EXCEPTION 'terminated contract is sealed' USING ERRCODE = '55000';
    END IF;

    IF NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN
        IF OLD.contract_execution_id IS NOT NULL THEN
            RAISE EXCEPTION 'executed contract cannot advance revision' USING ERRCODE = '55000';
        END IF;
        SELECT EXISTS (
            SELECT 1
            FROM contract.contract_revision next_revision
            LEFT JOIN contract.contract_revision old_revision
              ON old_revision.tenant_id = NEW.tenant_id
             AND old_revision.contract_revision_id = OLD.current_revision_id
            WHERE next_revision.tenant_id = NEW.tenant_id
              AND next_revision.contract_revision_id = NEW.current_revision_id
              AND next_revision.contract_id = NEW.contract_id
              AND ((OLD.current_revision_id IS NULL
                    AND next_revision.revision_no = 1
                    AND next_revision.predecessor_revision_id IS NULL)
                OR (OLD.current_revision_id IS NOT NULL
                    AND next_revision.predecessor_revision_id = OLD.current_revision_id
                    AND next_revision.revision_no = old_revision.revision_no + 1))
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'current contract revision must advance by one direct successor' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.approved_revision_id IS NOT NULL
          AND NEW.approved_revision_id IS DISTINCT FROM OLD.approved_revision_id THEN
        RAISE EXCEPTION 'approved revision can change only while advancing the current revision' USING ERRCODE = '55000';
    END IF;

    IF NEW.approved_revision_id IS NOT NULL
       AND NEW.approved_revision_id IS DISTINCT FROM NEW.current_revision_id THEN
        RAISE EXCEPTION 'approved revision must equal current revision' USING ERRCODE = '23514';
    END IF;

    IF NEW.contract_execution_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM contract.contract_execution execution
            WHERE execution.tenant_id = NEW.tenant_id
              AND execution.contract_execution_id = NEW.contract_execution_id
              AND execution.contract_id = NEW.contract_id
              AND execution.contract_revision_id = NEW.current_revision_id
              AND execution.contract_revision_id = NEW.approved_revision_id
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'execution must bind the current approved contract revision' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.deal_activated_at IS NOT NULL AND NEW.contract_execution_id IS NULL THEN
        RAISE EXCEPTION 'deal activation requires contract execution' USING ERRCODE = '23514';
    END IF;
    IF OLD.deal_activated_at IS NULL
       AND NEW.deal_activated_at IS NOT NULL
       AND NEW.contract_termination_id IS NOT NULL THEN
        RAISE EXCEPTION 'deal activation cannot be formed together with cancellation or termination' USING ERRCODE = '23514';
    END IF;

    IF NEW.contract_termination_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM contract.contract_termination termination
            WHERE termination.tenant_id = NEW.tenant_id
              AND termination.contract_termination_id = NEW.contract_termination_id
              AND termination.contract_id = NEW.contract_id
              AND termination.contract_revision_id = NEW.current_revision_id
              AND termination.contract_execution_id IS NOT DISTINCT FROM NEW.contract_execution_id
        ) INTO relation_matches;
        IF NOT relation_matches THEN
            RAISE EXCEPTION 'termination must bind the current contract lifecycle' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_contract_lifecycle() IS
    '合同生命周期守卫：锚点只由当前有效销售接受链形成且初始槽为空，版本仅沿直接后继前移，批准必须等于当前版本，执行和终止必须绑定同一准确版本，执行后版本冻结且终止后整行封存。';

CREATE FUNCTION platform_meta.fn_guard_contract_signature_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1
    FROM contract.signature_plan plan
    JOIN contract.contract_revision contract_revision
      ON contract_revision.tenant_id = plan.tenant_id
     AND contract_revision.contract_revision_id = plan.contract_revision_id
    JOIN contract.contract contract_root
      ON contract_root.tenant_id = contract_revision.tenant_id
     AND contract_root.contract_id = contract_revision.contract_id
    WHERE plan.tenant_id = NEW.tenant_id
      AND plan.signature_plan_id = NEW.signature_plan_id
      AND plan.contract_revision_id = NEW.contract_revision_id
      AND contract_root.contract_execution_id IS NULL
      AND contract_root.contract_termination_id IS NULL
      AND (TG_OP = 'UPDATE' OR contract_root.current_revision_id = NEW.contract_revision_id)
    FOR UPDATE OF contract_root;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'signature creation or revocation requires the exact unexecuted and unterminated contract' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_guard_contract_signature_lifecycle() IS
    '签署生命周期守卫：新签署只能进入当前版本；签署新增或单向撤回都锁定Contract根并拒绝在执行、取消或终止后发生。';

CREATE FUNCTION platform_meta.fn_assert_contract_execution_package()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1 FROM contract.contract contract_root
    WHERE contract_root.tenant_id = NEW.tenant_id
      AND contract_root.contract_id = NEW.contract_id
      AND contract_root.current_revision_id = NEW.contract_revision_id
      AND contract_root.approved_revision_id = NEW.contract_revision_id
      AND contract_root.contract_execution_id = NEW.contract_execution_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract execution must fill the exact current approved anchor slot in the same transaction' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM contract.signature_plan plan
        WHERE plan.tenant_id = NEW.tenant_id
          AND plan.contract_revision_id = NEW.contract_revision_id
          AND plan.required
          AND NOT EXISTS (
              SELECT 1
              FROM contract.contract_signature signature
              JOIN contract.contract_revision contract_revision
                ON contract_revision.tenant_id = signature.tenant_id
               AND contract_revision.contract_revision_id = signature.contract_revision_id
              WHERE signature.tenant_id = plan.tenant_id
                AND signature.signature_plan_id = plan.signature_plan_id
                AND signature.contract_revision_id = plan.contract_revision_id
                AND signature.revoked_at IS NULL
                AND signature.signed_content_digest = contract_revision.content_digest
          )
    ) THEN
        RAISE EXCEPTION 'contract execution requires one active exact-content signature for every required plan' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_contract_execution_package() IS
    '合同执行反向守卫：Execution必须在同事务回填当前批准槽，并证明每个必需SignaturePlan存在未撤回且内容摘要准确的签署；审批、审查、印章与归档仍由ContractRuntime复验。';

CREATE FUNCTION platform_meta.fn_assert_contract_termination_anchor()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1 FROM contract.contract contract_root
    WHERE contract_root.tenant_id = NEW.tenant_id
      AND contract_root.contract_id = NEW.contract_id
      AND contract_root.current_revision_id = NEW.contract_revision_id
      AND contract_root.contract_execution_id IS NOT DISTINCT FROM NEW.contract_execution_id
      AND contract_root.contract_termination_id = NEW.contract_termination_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'contract termination must fill the exact current anchor slot in the same transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_contract_termination_anchor() IS
    '合同终止反向守卫：CANCELLED或TERMINATED事实必须在同事务回填准确Contract当前版本、Execution选择器和单向终止槽，禁止孤立终止事实。';

CREATE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
    accepted_snapshot uuid;
BEGIN
    SELECT request.accepted_snapshot_id INTO accepted_snapshot
    FROM transfer.transfer_request request
    WHERE request.tenant_id = NEW.tenant_id
      AND request.transfer_request_id = NEW.transfer_request_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transfer snapshot requires its exact request' USING ERRCODE = '23503';
    END IF;
    IF accepted_snapshot IS NOT NULL THEN
        RAISE EXCEPTION 'accepted transfer rejects new snapshots' USING ERRCODE = '55000';
    END IF;
    IF NEW.snapshot_no = 1 THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM transfer.transfer_snapshot predecessor
        WHERE predecessor.tenant_id = NEW.tenant_id
          AND predecessor.transfer_snapshot_id = NEW.predecessor_snapshot_id
          AND predecessor.transfer_request_id = NEW.transfer_request_id
          AND predecessor.snapshot_no + 1 = NEW.snapshot_no
    ) INTO relation_matches;
    IF NOT relation_matches THEN
        RAISE EXCEPTION 'transfer snapshot must follow the direct predecessor of the same request' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() IS
    '转案快照链守卫：锁定请求根后证明补正只接在同请求直接前序，串行化ACCEPT并禁止接收后新增Snapshot。';

CREATE FUNCTION platform_meta.fn_assert_transfer_return_open()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    accepted_snapshot uuid;
BEGIN
    SELECT request.accepted_snapshot_id INTO accepted_snapshot
    FROM transfer.transfer_request request
    WHERE request.tenant_id = NEW.tenant_id
      AND request.transfer_request_id = NEW.transfer_request_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'return item requires its exact transfer request' USING ERRCODE = '23503';
    END IF;
    IF accepted_snapshot IS NOT NULL THEN
        RAISE EXCEPTION 'accepted transfer rejects new return items' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_transfer_return_open() IS
    '转案退回项守卫：锁定同一请求根并禁止ACCEPT之后追加ReturnItem。';

CREATE FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    relation_matches boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.accepted_snapshot_id IS NOT NULL THEN
            RAISE EXCEPTION 'transfer acceptance slots must be empty on request insert' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.accepted_snapshot_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM 1 FROM contract.contract source_contract
    WHERE source_contract.tenant_id = NEW.tenant_id
      AND source_contract.contract_id = NEW.contract_id
      AND source_contract.contract_execution_id = NEW.contract_execution_id
      AND source_contract.deal_activated_at = NEW.deal_activated_at
      AND source_contract.contract_termination_id IS NULL
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transfer acceptance requires the exact executed active and unterminated contract' USING ERRCODE = '23514';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM transfer.transfer_snapshot accepted
        WHERE accepted.tenant_id = NEW.tenant_id
          AND accepted.transfer_snapshot_id = NEW.accepted_snapshot_id
          AND accepted.transfer_request_id = NEW.transfer_request_id
          AND NOT EXISTS (
              SELECT 1 FROM transfer.transfer_return_item returned_item
              WHERE returned_item.tenant_id = accepted.tenant_id
                AND returned_item.reviewed_snapshot_id = accepted.transfer_snapshot_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM transfer.transfer_snapshot successor
              WHERE successor.tenant_id = accepted.tenant_id
                AND successor.predecessor_snapshot_id = accepted.transfer_snapshot_id
          )
    ) INTO relation_matches;
    IF NOT relation_matches THEN
        RAISE EXCEPTION 'accepted snapshot must be the current leaf of its transfer request' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf() IS
    '转案接收守卫：接收槽只能在请求创建后一次写入；锁定来源Contract并要求仍已执行、已激活且未终止，acceptedSnapshot必须是同请求当前叶且未形成任何RETURN项。';

CREATE CONSTRAINT TRIGGER ctrg_lead__current_assignment_owner
AFTER INSERT OR UPDATE OF current_assignment_id ON lead.lead
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'current_assignment_id', 'lead', 'lead_assignment',
    'lead_assignment_id', 'lead_id', 'lead_id'
);
COMMENT ON TRIGGER ctrg_lead__current_assignment_owner ON lead.lead IS '当前Assignment必须属于同一Lead。';

CREATE CONSTRAINT TRIGGER ctrg_opportunity__current_quote_owner
AFTER INSERT OR UPDATE OF current_quote_revision_id ON opportunity.opportunity
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'current_quote_revision_id', 'opportunity', 'quote_revision',
    'quote_revision_id', 'opportunity_id', 'opportunity_id'
);
COMMENT ON TRIGGER ctrg_opportunity__current_quote_owner ON opportunity.opportunity IS '当前QuoteRevision必须属于同一Opportunity。';

CREATE CONSTRAINT TRIGGER ctrg_contract__current_revision_owner
AFTER INSERT OR UPDATE OF current_revision_id ON contract.contract
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'current_revision_id', 'contract', 'contract_revision',
    'contract_revision_id', 'contract_id', 'contract_id'
);
COMMENT ON TRIGGER ctrg_contract__current_revision_owner ON contract.contract IS '当前合同版本必须属于同一Contract。';

CREATE CONSTRAINT TRIGGER ctrg_contract__approved_revision_owner
AFTER INSERT OR UPDATE OF approved_revision_id ON contract.contract
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'approved_revision_id', 'contract', 'contract_revision',
    'contract_revision_id', 'contract_id', 'contract_id'
);
COMMENT ON TRIGGER ctrg_contract__approved_revision_owner ON contract.contract IS '批准合同版本必须属于同一Contract。';

CREATE CONSTRAINT TRIGGER ctrg_contract__execution_owner
AFTER INSERT OR UPDATE OF contract_execution_id ON contract.contract
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'contract_execution_id', 'contract', 'contract_execution',
    'contract_execution_id', 'contract_id', 'contract_id'
);
COMMENT ON TRIGGER ctrg_contract__execution_owner ON contract.contract IS '合同执行事实必须属于同一Contract。';

CREATE CONSTRAINT TRIGGER ctrg_contract__termination_owner
AFTER INSERT OR UPDATE OF contract_termination_id ON contract.contract
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'contract_termination_id', 'contract', 'contract_termination',
    'contract_termination_id', 'contract_id', 'contract_id'
);
COMMENT ON TRIGGER ctrg_contract__termination_owner ON contract.contract IS '合同终止事实必须属于同一Contract。';

CREATE CONSTRAINT TRIGGER ctrg_transfer_request__accepted_snapshot_owner
AFTER INSERT OR UPDATE OF accepted_snapshot_id ON transfer.transfer_request
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(
    'accepted_snapshot_id', 'transfer', 'transfer_snapshot',
    'transfer_snapshot_id', 'transfer_request_id', 'transfer_request_id'
);
COMMENT ON TRIGGER ctrg_transfer_request__accepted_snapshot_owner ON transfer.transfer_request IS '接收Snapshot必须属于同一TransferRequest。';

CREATE CONSTRAINT TRIGGER ctrg_upload_session__finalization
AFTER INSERT OR UPDATE OF status ON evidence.upload_session
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_evidence_finalization();
COMMENT ON TRIGGER ctrg_upload_session__finalization ON evidence.upload_session IS
    '证据晋级延迟守卫：在短事务提交时验证一会话一文件及Session、SourceObject、Submission、Binding准确晋级链。';

CREATE CONSTRAINT TRIGGER ctrg_received_source_object__session_member
AFTER INSERT ON evidence.received_source_object
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();
COMMENT ON TRIGGER ctrg_received_source_object__session_member ON evidence.received_source_object IS
    '来源对象反向守卫：只允许属于同租户、同Opaque Key且已进入接收态的准确UploadSession。';

CREATE CONSTRAINT TRIGGER ctrg_evidence_submission__promotion_member
AFTER INSERT ON evidence.evidence_submission
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();
COMMENT ON TRIGGER ctrg_evidence_submission__promotion_member ON evidence.evidence_submission IS
    '提交事实反向守卫：Submission只能与唯一Binding及FINALIZED会话在同一完整晋级链中存在。';

CREATE CONSTRAINT TRIGGER ctrg_evidence_binding__promotion_member
AFTER INSERT ON evidence.evidence_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();
COMMENT ON TRIGGER ctrg_evidence_binding__promotion_member ON evidence.evidence_binding IS
    '绑定事实反向守卫：Binding必须准确复现会话冻结目标与用途并属于同一完整晋级链。';

CREATE CONSTRAINT TRIGGER ctrg_lead_assignment__chain
AFTER INSERT OR UPDATE OF assignment_status_code ON lead.lead_assignment
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_lead_assignment_chain();
COMMENT ON TRIGGER ctrg_lead_assignment__chain ON lead.lead_assignment IS
    '销售分配延迟守卫：Assignment必须属于同一Lead、按直接前序追加，并与唯一当前OPEN指针原子一致。';

CREATE CONSTRAINT TRIGGER ctrg_lead__current_assignment
AFTER INSERT OR UPDATE OF current_assignment_id ON lead.lead
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_lead_current_assignment();
COMMENT ON TRIGGER ctrg_lead__current_assignment ON lead.lead IS
    'Lead当前指针延迟守卫：首次只回填OPEN链首，改派只沿已关闭前序的直接OPEN后继前移。';

CREATE CONSTRAINT TRIGGER ctrg_opportunity__qualified_source
AFTER INSERT ON opportunity.opportunity
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_opportunity_qualified_source();
COMMENT ON TRIGGER ctrg_opportunity__qualified_source ON opportunity.opportunity IS
    '商机来源延迟守卫：只接受准确CONNECTED_VALID联系结果形成Opportunity。';

CREATE CONSTRAINT TRIGGER ctrg_quote_revision__complete_package
AFTER INSERT ON opportunity.quote_revision
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_quote_revision_package();
COMMENT ON TRIGGER ctrg_quote_revision__complete_package ON opportunity.quote_revision IS
    '报价版本延迟守卫：提交时证明连续版本链、当前指针和完整Participation集合。';

CREATE CONSTRAINT TRIGGER ctrg_opportunity_participation__quoted_set
AFTER INSERT ON opportunity.opportunity_participation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_participation_set_quoted();
COMMENT ON TRIGGER ctrg_opportunity_participation__quoted_set ON opportunity.opportunity_participation IS
    '参与集合反向守卫：每一项都必须由同事务准确QuoteRevision封存。';

CREATE CONSTRAINT TRIGGER ctrg_opportunity__lifecycle
AFTER UPDATE ON opportunity.opportunity
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_opportunity_lifecycle();
COMMENT ON TRIGGER ctrg_opportunity__lifecycle ON opportunity.opportunity IS
    '商机生命周期延迟守卫：当前Quote指针仅沿直接后继前移，关闭后任何原位更新均被拒绝。';

CREATE CONSTRAINT TRIGGER ctrg_contract_revision__complete_package
AFTER INSERT ON contract.contract_revision
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_contract_revision_package();
COMMENT ON TRIGGER ctrg_contract_revision__complete_package ON contract.contract_revision IS
    '合同版本延迟守卫：提交时证明当前指针及报价Issue、QuoteRevision、ACCEPTED Response属于锚点已经消费的同一历史来源链。';

CREATE CONSTRAINT TRIGGER ctrg_contract__lifecycle
AFTER INSERT OR UPDATE ON contract.contract
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_contract_lifecycle();
COMMENT ON TRIGGER ctrg_contract__lifecycle ON contract.contract IS
    '合同生命周期延迟守卫：提交时验证版本直接后继、当前批准一致、准确执行和终止封存。';

CREATE TRIGGER trg_contract_signature__lifecycle
BEFORE INSERT OR UPDATE ON contract.contract_signature
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_contract_signature_lifecycle();
COMMENT ON TRIGGER trg_contract_signature__lifecycle ON contract.contract_signature IS
    '签署生命周期保护：新增签署及授权单向撤回均锁定准确Contract根，执行、取消或终止后拒绝任何签署变动。';

CREATE CONSTRAINT TRIGGER ctrg_contract_execution__complete_package
AFTER INSERT ON contract.contract_execution
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_contract_execution_package();
COMMENT ON TRIGGER ctrg_contract_execution__complete_package ON contract.contract_execution IS
    '合同执行反向延迟守卫：Execution必须原子回填锚点并保有全部必需、未撤回、准确内容签署。';

CREATE CONSTRAINT TRIGGER ctrg_contract_termination__anchor
AFTER INSERT ON contract.contract_termination
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_contract_termination_anchor();
COMMENT ON TRIGGER ctrg_contract_termination__anchor ON contract.contract_termination IS
    '合同终止反向延迟守卫：终止事实必须与锚点单向终止槽在同一短事务封存。';

CREATE CONSTRAINT TRIGGER ctrg_transfer_snapshot__chain
AFTER INSERT ON transfer.transfer_snapshot
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain();
COMMENT ON TRIGGER ctrg_transfer_snapshot__chain ON transfer.transfer_snapshot IS
    '转案快照延迟守卫：提交时验证同请求直接前序并拒绝接收后的新增快照。';

CREATE CONSTRAINT TRIGGER ctrg_transfer_return_item__unaccepted
AFTER INSERT ON transfer.transfer_return_item
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_transfer_return_open();
COMMENT ON TRIGGER ctrg_transfer_return_item__unaccepted ON transfer.transfer_return_item IS
    '转案退回项延迟守卫：与ACCEPT串行化并拒绝接收后追加任何ReturnItem。';

CREATE CONSTRAINT TRIGGER ctrg_transfer_request__acceptance_leaf
AFTER INSERT OR UPDATE ON transfer.transfer_request
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf();
COMMENT ON TRIGGER ctrg_transfer_request__acceptance_leaf ON transfer.transfer_request IS
    '转案接收延迟守卫：请求初建无接收槽，ACCEPT只能冻结同请求当前叶Snapshot。';

REVOKE ALL ON FUNCTION platform_meta.fn_reject_fact_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_guard_controlled_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_state() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_nulls() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_owned_pointer() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_evidence_finalization() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_evidence_promotion_member() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_lead_assignment_chain() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_lead_current_assignment() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_opportunity_qualified_source() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_quote_revision_package() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_participation_set_quoted() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_opportunity_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_revision_package() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_guard_contract_signature_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_execution_package() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_termination_anchor() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_return_open() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf() FROM PUBLIC;
