package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;import org.junit.jupiter.api.Test;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
class LeadIngressIT extends LeadBusinessFixture {
    @Test void fresh_manual_capture_creates_one_assignment_responsibility_and_natural_key_is_no_change()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var e=capture("fresh",true);var receipt=run(e);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
        assertEquals(List.of(1L,0L,0L,0L,1L,1L,1L,1L,1L,1L),counts());assertEquals("OPEN",task("ASSIGN_LEAD").state());
        emitted(e,receipt,"LeadCapturedV1");
        assertEquals(receipt,run(e));assertEquals(CommandOutcome.Status.NO_CHANGE,run(capture("fresh",true)).status());
        assertEquals(List.of(1L,0L,0L,0L,1L,2L,2L,1L,1L,2L),counts());
    }
    @Test void automatic_capture_freezes_assignment_owner_and_final_lead_revision()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);var receipt=run(capture("auto",true));assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
        assertEquals(List.of(1L,1L,0L,0L,1L,1L,1L,1L,1L,1L),counts());var task=task("CONTACT_LEAD");assertEquals(seed.appointment(),task.owner());assertEquals(1L,task.lead().revision());assertEquals(receipt.resultFact(),task.lead());
    }
    @Test void both_duplicate_outcomes_follow_manual_automatic_and_empty_policy_with_resolution_only_digest()throws Exception {
        for(String decision:List.of("LINK_EXISTING_PARTY","KEEP_SEPARATE"))for(int policyCase=0;policyCase<3;policyCase++){
            var mode=policyCase==0?R1SourcePolicyRegistry.AssignmentMode.MANUAL:R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC;boolean sales=policyCase!=2;setup(mode,sales);
            var candidate=resolvedCandidate("import",captureValues("import",true));
            String candidateBefore=scalar("select to_jsonb(l)::text from lead.lead l where tenant_id=? and lead_id=?",seed.tenant(),candidate.lead());String partyBefore=scalar("select to_jsonb(p)::text from party.party p where tenant_id=? and party_id=?",seed.tenant(),candidate.party());
            run(capture("duplicate",true));var original=task("RESOLVE_LEAD_DUPLICATE");var e=command(original,duplicateValues(candidate,decision));var before=counts();var receipt=run(e);completed(original,receipt);
            boolean automatic=policyCase==1;delta(before,List.of(0L,automatic?1L:0L,1L,0L,1L,1L,1L,1L,1L,1L));emitted(e,receipt,"LeadDuplicateResolutionRecordedV1");
            String successorType=policyCase==0?"ASSIGN_LEAD":automatic?"CONTACT_LEAD":"RESOLVE_LEAD_ROUTING_GAP";var successor=task(successorType);assertEquals(original.lead().id(),successor.lead().id());assertEquals(1L,successor.lead().revision());assertEquals(seed.appointment(),successor.owner());assertEquals("OPEN",successor.state());
            assertEquals(decision+":1:"+(decision.equals("LINK_EXISTING_PARTY")?candidate.party()+":RESOLVED":"NULL:UNRESOLVED"),scalar("select disposition_code||':'||revision||':'||coalesce(parsed_party_id::text,'NULL')||':'||party_resolution_code from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id()));
            assertEquals(candidateBefore,scalar("select to_jsonb(l)::text from lead.lead l where tenant_id=? and lead_id=?",seed.tenant(),candidate.lead()));assertEquals(partyBefore,scalar("select to_jsonb(p)::text from party.party p where tenant_id=? and party_id=?",seed.tenant(),candidate.party()));
            var expected=new TreeMap<String,Object>();expected.put("tenantId",seed.tenant().toString());expected.put("subject",Map.of("type","lead.lead","id",original.lead().id().toString(),"revision",0L));expected.put("authoritySlot","SOURCE_INTAKE_OWNER");expected.putAll(duplicateValues(candidate,decision));expected.put("newRevision",1L);
            var changed=new TreeMap<String,Object>();changed.put("disposition_code",decision);if(decision.equals("LINK_EXISTING_PARTY")){changed.put("parsed_party_id",candidate.party().toString());changed.put("party_resolution_code","RESOLVED");}expected.put("newValues",changed);
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(expected))),receipt.resultFact().hash());
            var after=counts();assertEquals(receipt,run(e));assertEquals(after,counts());
        }
    }
    @Test void duplicate_ranking_uses_phone_and_email_then_capture_time_and_unsigned_uuid()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
        var phone=resolvedCandidate("phone",captureValues("phone",true));
        var bothInput=captureValues("both",true);bothInput.put("email","MATCH@EXAMPLE.COM");bothInput.put("capturedAt","2026-09-04T08:00:00.000000Z");var both=resolvedCandidate("both",bothInput);
        var laterInput=captureValues("later",true);laterInput.put("email","match@example.com");var later=resolvedCandidate("later",laterInput);
        var current=captureValues("current",true);current.put("email","match@example.com");run(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),current));
        var original=task("RESOLVE_LEAD_DUPLICATE");var e=command(original,duplicateValues(both,"KEEP_SEPARATE"));completed(original,run(e));
        assertNotEquals(phone.lead(),both.lead());assertNotEquals(later.lead(),both.lead());
    }
    @Test void stale_candidate_revision_and_inactive_party_are_terminal_rejections_with_no_confirmation()throws Exception {
        for(boolean partyChanged:List.of(false,true)){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var candidate=resolvedCandidate("original",captureValues("original",true));run(capture("current",true));var original=task("RESOLVE_LEAD_DUPLICATE");var e=command(original,duplicateValues(candidate,"LINK_EXISTING_PARTY"));
            if(partyChanged)mutate("update party.party set canonical_name='Changed synthetic name',revision=revision+1 where tenant_id=? and party_id=?",seed.tenant(),candidate.party());
            else mutate("update lead.lead set disposition_code='KEEP_SEPARATE',revision=revision+1 where tenant_id=? and lead_id=?",seed.tenant(),candidate.lead());
            var before=counts();var rejection=run(e);assertEquals("STALE_SUBJECT",rejection.rejectionCode());delta(before,List.of(0L,0L,0L,0L,0L,1L,1L,0L,0L,1L));assertEquals("DRAFT:0",scalar("select state||':'||revision from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),original.selector().id()));assertEquals(rejection,run(e));
        }
    }
    @Test void trusted_service_capture_requires_registered_source_binding_and_assigns_human_intake_owner()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var actor=actor("SERVICE","LEAD_CAPTURE");var e=new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),actor,captureValues("service",false));
        rejectedBefore(e,"NOT_AUTHORIZED");
        R1ServiceSourceBinding binding;try(var c=database.apiConnection()){binding=inTransaction(c,Capability.QUERY,x->R1ServiceSourceBinding.validate(x,List.of(new R1ServiceSourceBinding.Entry("https://synthetic.example","law-api","FIXTURE",seed.tenant(),actor.principalId(),actor.appointmentId(),Set.of("FIXTURE"))),sources));}
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection()).handlers(),AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("LEAD_IT"),R1AuthorizationReaders.databaseBacked(sources,binding),R1EventReaders.databaseBacked());
        var receipt=run(e);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());emitted(e,receipt,"LeadCapturedV1");assertEquals(seed.appointment(),task("COMPLETE_LEAD_INGRESS").owner());assertNotEquals(actor.appointmentId(),task("COMPLETE_LEAD_INGRESS").owner());var after=counts();assertEquals(receipt,run(e));assertEquals(after,counts());
        var unbound=new TreeMap<String,Object>(captureValues("unbound",false));unbound.put("sourceAccountCode","UNREGISTERED");rejectedBefore(payload(e,unbound),"VALIDATION_FAILED");
    }
    @Test void capture_rejects_injected_authority_fields_unknown_sources_and_unresolved_intake_or_supervisor()throws Exception {
        for(String injected:List.of("tenantId","sourceIntakeRootCode","ownerAppointmentId","authoritySlotCode")){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var e=capture("injected",false);var values=new TreeMap<String,Object>((Map<String,Object>)e.payload());values.put(injected,"FORGED");rejectedBefore(payload(e,values),"VALIDATION_FAILED");
        }
        for(String code:List.of("LEAD_INGRESS_COMPLETE","LEAD_ROUTING_DECIDE"))for(boolean ambiguous:List.of(false,true)){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);
            if(ambiguous)actor("HUMAN",code);else mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code=?",seed.tenant(),code);
            terminal(capture("unresolved",code.equals("LEAD_ROUTING_DECIDE")),"SUPERVISOR_UNRESOLVED");
        }
    }
    @Test void malformed_capture_timestamp_is_controlled_validation_failure_before_slot()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var e=capture("invalid-time",false);var values=new TreeMap<String,Object>((Map<String,Object>)e.payload());values.put("capturedAt","not-a-time");rejectedBefore(payload(e,values),"VALIDATION_FAILED");
    }
    @Test void capture_protected_fields_are_normalized_encrypted_and_digested_independently()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var values=captureValues("protected",true);values.put("capturedName","  Cafe\u0301\r\nSynthetic  ");values.put("email","SYNTHETIC@BÜCHER.DE");var e=new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),values);var receipt=run(e);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{try(var p=x.prepareStatement("select captured_name_ciphertext,captured_phone_ciphertext,captured_email_ciphertext,captured_phone_hmac,captured_email_hmac,source_record_key_digest,captured_content_digest from lead.lead where tenant_id=? and lead_id=?")){p.setObject(1,seed.tenant());p.setObject(2,receipt.resultFact().id());try(var r=p.executeQuery()){assertTrue(r.next());var protection=LeadProtectionTest.protection();assertEquals("Café\nSynthetic",protection.decrypt(seed.tenant(),LeadProtection.Field.CAPTURED_NAME,r.getBytes(1)));assertEquals("+12025550123",protection.decrypt(seed.tenant(),LeadProtection.Field.CAPTURED_PHONE,r.getBytes(2)));assertEquals("synthetic@xn--bcher-kva.de",protection.decrypt(seed.tenant(),LeadProtection.Field.CAPTURED_EMAIL,r.getBytes(3)));assertArrayEquals(protection.hmac(seed.tenant(),LeadProtection.Purpose.LEAD_PHONE_EXACT,null,"+12025550123"),r.getBytes(4));assertArrayEquals(protection.hmac(seed.tenant(),LeadProtection.Purpose.LEAD_EMAIL_EXACT,null,"synthetic@xn--bcher-kva.de"),r.getBytes(5));
            var digest=new TreeMap<String,Object>();digest.putAll(values);digest.remove("sourceRecordKey");digest.put("sourceRecordKeyDigest",Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(6)));digest.put("capturedName","Café\nSynthetic");digest.put("email","synthetic@xn--bcher-kva.de");digest.put("cityCode",null);assertArrayEquals(CanonicalJson.digest(CanonicalJson.encode(digest)),r.getBytes(7));}}
            return null;});}
        var before=businessSnapshot();var changed=captureValues("protected",true);changed.put("legalNeedSummary","Different synthetic natural-key retry");var retry=run(new CommandEnvelope(e.type(),UUID.randomUUID(),UUID.randomUUID(),e.actor(),changed));assertEquals(CommandOutcome.Status.NO_CHANGE,retry.status());assertEquals(before,businessSnapshot());
    }
    @Test void duplicate_snapshot_excludes_later_candidate_and_uuid_breaks_exact_tie()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var first=resolvedCandidate("first",captureValues("first",true));var tied=resolvedCandidate("tied",captureValues("tied",true));run(capture("current",true));var original=task("RESOLVE_LEAD_DUPLICATE");
        var laterValues=captureValues("later",true);laterValues.put("capturedAt","2026-09-01T00:00:00.000000Z");resolvedCandidate("later",laterValues);
        var winner=first.lead().toString().compareTo(tied.lead().toString())<0?first:tied;completed(original,run(command(original,duplicateValues(winner,"KEEP_SEPARATE"))));
    }
    @Test void capture_and_both_duplicate_outcomes_roll_back_at_every_storage_boundary()throws Exception {
        for(String decision:List.of("CAPTURE","LINK_EXISTING_PARTY","KEEP_SEPARATE")){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);CommandEnvelope e;
            if(decision.equals("CAPTURE"))e=capture("rollback",true);else{var candidate=resolvedCandidate("existing",captureValues("existing",true));run(capture("current",true));e=command(task("RESOLVE_LEAD_DUPLICATE"),duplicateValues(candidate,decision));}
            for(String table:List.of("lead.lead","responsibility.task_occurrence","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(e,table);
            assertEquals(CommandOutcome.Status.SUCCEEDED,run(e).status());
        }
    }
    @Test void merged_party_and_existing_open_assignment_cannot_be_reused_by_duplicate_resolution()throws Exception {
        for(String fault:List.of("MERGED","EXISTING_ASSIGNMENT")){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);var candidate=resolvedCandidate("existing",captureValues("existing",true));run(capture("current",true));var task=task("RESOLVE_LEAD_DUPLICATE");var e=command(task,duplicateValues(candidate,"LINK_EXISTING_PARTY"));
            if(fault.equals("MERGED")){UUID party=UUID.randomUUID();mutate("insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','Synthetic survivor','ACTIVE')",seed.tenant(),party);mutate("update party.party set status='MERGED',merged_into_party_id=?,merged_at=clock_timestamp(),revision=revision+1 where tenant_id=? and party_id=?",party,seed.tenant(),candidate.party());}
            else try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{UUID assignment=UUID.randomUUID();
                io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"insert into lead.lead_assignment (tenant_id,lead_assignment_id,lead_id,assignment_no,owner_appointment_id,assignment_reason_code,assigned_at,assignment_status_code,created_at) values (?,?,?,1,?,'MANUAL_SELECTION',clock_timestamp(),'OPEN',clock_timestamp())",seed.tenant(),assignment,task.lead().id(),seed.appointment());
                io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"update lead.lead set current_assignment_id=?,revision=revision+1 where tenant_id=? and lead_id=?",assignment,seed.tenant(),task.lead().id());return null;
            });}
            var business=businessSnapshot();terminal(e,"STALE_SUBJECT");assertEquals(business,businessSnapshot());
        }
    }
    @Test void matching_contact_in_another_tenant_never_enters_duplicate_candidates()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var foreign=resolvedCandidate("same-source-key",captureValues("same-source-key",true));var foreignTenant=seed.tenant();String before=scalar("select to_jsonb(l)::text from lead.lead l where tenant_id=? and lead_id=?",foreignTenant,foreign.lead());
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var receipt=run(capture("same-source-key",true));assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());assertEquals(receipt.resultFact(),task("ASSIGN_LEAD").lead());assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='RESOLVE_LEAD_DUPLICATE'",seed.tenant()));assertEquals(before,scalar("select to_jsonb(l)::text from lead.lead l where tenant_id=? and lead_id=?",foreignTenant,foreign.lead()));
    }
}
