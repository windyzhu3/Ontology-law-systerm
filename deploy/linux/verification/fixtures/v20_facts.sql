-- Verification ONLY: synthetic existing facts in a newly created v20 database.
DO $fixture$
DECLARE t uuid:=uuidv7(); p uuid:=uuidv7(); o uuid:=uuidv7(); a uuid:=uuidv7(); l uuid:=uuidv7();
BEGIN
 INSERT INTO identity.tenant(tenant_id,tenant_code,display_name,state,created_at)
 VALUES(t,'LINUX_V20_FIXTURE','Migration preservation fixture','ACTIVE',clock_timestamp());
 INSERT INTO identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at)
 VALUES(t,p,'HUMAN','FIXTURE',decode(repeat('11',32),'hex'),'Synthetic owner','ACTIVE',clock_timestamp());
 INSERT INTO identity.organization_unit(tenant_id,organization_unit_id,unit_code,display_name,state,created_at)
 VALUES(t,o,'ROOT','Synthetic root','ACTIVE',clock_timestamp());
 INSERT INTO identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at)
 VALUES(t,a,p,o,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp());
 INSERT INTO identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at)
 VALUES(t,uuidv7(),a,a,o,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp());
 INSERT INTO lead.lead(tenant_id,lead_id,source_channel_code,source_account_code,source_record_key_digest,captured_at,service_category_code,jurisdiction_code,urgency_code,legal_need_summary_ciphertext,captured_content_digest,party_resolution_code,disposition_code,created_at)
 VALUES(t,l,'FIXTURE','FIXTURE',decode(repeat('22',32),'hex'),clock_timestamp(),'FIXTURE','FIXTURE','FIXTURE',decode('01','hex'),decode(repeat('33',32),'hex'),'UNRESOLVED','CAPTURED',clock_timestamp());
 INSERT INTO responsibility.task_occurrence(tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision)
 VALUES(t,uuidv7(),a,'COMPLETE_LEAD_INGRESS','COMPLETE_LEAD_INGRESS','lead.lead','R1_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'lead.lead',l,0);
 INSERT INTO audit.audit_entry(tenant_id,audit_entry_id,entry_type,audit_scope_code,trusted_at,action_code,result_code,actor_principal_id,actor_appointment_id,correlation_id,authorization_slot_code,authorization_path_code,authorization_scope_organization_unit_id,authorization_snapshot_digest,trace_id,service_role_code,execution_node_code,summary_schema_code,summary_schema_version,change_summary,change_summary_digest,subject_type,subject_id,subject_revision)
 VALUES(t,uuidv7(),'EVENT','OBJECT',clock_timestamp(),'FIXTURE','SUCCEEDED',p,a,uuidv7(),'FIXTURE','DIRECT',o,decode(repeat('44',32),'hex'),uuidv7(),'API','LINUX_FIXTURE','FIXTURE',1,'{}',decode(repeat('55',32),'hex'),'lead.lead',l,0);
END;$fixture$;
