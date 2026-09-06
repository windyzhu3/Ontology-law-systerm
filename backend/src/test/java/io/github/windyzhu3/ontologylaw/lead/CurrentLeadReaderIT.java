package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurrentLeadReaderIT extends LeadBusinessFixture {
    // Catches using the captured channel only, the wrong AAD, or collapsing absence and bad ciphertext.
    @Test void query_reads_ingress_contact_without_changing_original_capture_semantics() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, true);
        run(capture("effective",false));var t=task("COMPLETE_LEAD_INGRESS");
        run(command(t,Map.of("phone","+12025550124","email","supplement@example.com","sourceCode","OWNER_CONFIRMED","sourceSummary","Synthetic source")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection());
            var original=reader.read(x,seed.tenant(),t.lead().id());
            assertNull(original.capturedPhone());assertNull(original.capturedEmail());
            var effective=reader.effectiveContact(x,seed.tenant(),t.lead().id());
            assertNotNull(effective);assertEquals("+12025550124",effective.phone());assertEquals("supplement@example.com",effective.email());
            assertEquals(1L,effective.selector().revision());return null;
        });}
    }
    // Catches missing any original/ingress cross-origin comparison, or a QUERY role escalation.
    @Test void query_duplicate_all_channel_origins_agrees_with_real_command() throws Exception {
        for(boolean phone:List.of(false,true))for(boolean candidateIngress:List.of(false,true))for(boolean currentIngress:List.of(false,true)) {
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
            var values=captureValues("candidate",!candidateIngress&&phone);if(!candidateIngress&&!phone)values.put("email","same@example.com");
            var candidate=resolvedCandidate("candidate",values);
            if(candidateIngress)completeFixtureIngress(candidate.lead(),phone);
            var input=captureValues("current",!currentIngress&&phone);if(!currentIngress&&!phone)input.put("email","same@example.com");
            var outcome=run(new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),input));
            if(currentIngress)completeFixtureIngress(outcome.resultFact().id(),phone);
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
                var reader=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection());
                var result=reader.duplicate(x,seed.tenant(),outcome.resultFact().id(),java.time.Instant.now().plusSeconds(1));
                assertNotNull(result);assertEquals(candidate.lead(),result.lead().id());assertEquals(candidate.party(),result.party().id());
                assertEquals(candidateIngress?2L:1L,result.lead().revision());return null;
            });}
            // The actual command Owner recomputes the same fixed candidate under COMMAND.
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                var owner=LeadIngressService.databaseBacked(LeadProtectionTest.protection());
                var current=owner.read(x,seed.tenant(),outcome.resultFact().id());
                assertEquals(candidate.lead(),owner.duplicate(x,seed.tenant(),current,java.time.Instant.now().plusSeconds(1)).lead().id());return null;
            });}
            TaskFactory.Task duplicateTask;
            if(currentIngress) {
                mutate("update responsibility.task_occurrence set state='CANCELLED',cancelled_at=clock_timestamp(),cancellation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and subject_id=? and state='OPEN'",seed.tenant(),outcome.resultFact().id());
                try(var c=database.apiConnection()){duplicateTask=inTransaction(c,Capability.COMMAND,x->{var owner=LeadIngressService.databaseBacked(LeadProtectionTest.protection());var current=owner.read(x,seed.tenant(),outcome.resultFact().id());return TaskFactory.databaseBacked().create(x,seed.tenant(),TaskFactory.Type.RESOLVE_LEAD_DUPLICATE,seed.appointment(),current.selector(),java.time.ZoneId.of("Asia/Shanghai"),owner.now(x));});}
            } else duplicateTask=task("RESOLVE_LEAD_DUPLICATE");
            var accepted=new ImportedCandidate(candidate.lead(),candidateIngress?2L:1L,candidate.party());
            completed(duplicateTask,run(command(duplicateTask,duplicateValues(accepted,"KEEP_SEPARATE"))));
        }
    }
    @Test void effective_contact_distinguishes_absence_preserves_original_precedence_and_rejects_wrong_aad()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var noContact=run(capture("absent",false)).resultFact();var original=run(capture("original",true)).resultFact();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection());
            var empty=reader.effectiveContact(x,seed.tenant(),noContact.id());assertNull(empty.phone());assertNull(empty.email());assertNull(reader.effectiveContact(x,UUID.randomUUID(),noContact.id()));
            assertEquals("+12025550123",reader.effectiveContact(x,seed.tenant(),original.id()).phone());return null;});}
        completeFixtureIngress(noContact.id());
        // Import a wrong-field ciphertext while keeping the tenant/field corruption explicit and test-only.
        try(var c=database.adminConnection();var s=c.createStatement()) {s.execute("set session_replication_role=replica");try{
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update lead.lead set ingress_completion_phone_ciphertext=? where tenant_id=? and lead_id=?",LeadProtectionTest.protection().encrypt(seed.tenant(),LeadProtection.Field.CAPTURED_PHONE,"+12025550123"),seed.tenant(),noContact.id());
            // V850 forbids captured and ingress slots coexisting; original precedence uses the legal original-only row.
        }finally{s.execute("set session_replication_role=origin");}}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection());assertThrows(IllegalArgumentException.class,()->reader.effectiveContact(x,seed.tenant(),noContact.id()));assertEquals("+12025550123",reader.effectiveContact(x,seed.tenant(),original.id()).phone());return null;});}
    }
    @Test void query_ranking_uses_both_then_phone_then_email_active_party_and_task_created_cutoff()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
        var emailValues=captureValues("email",false);emailValues.put("email","rank@example.com");var email=resolvedCandidate("email",emailValues);
        var phone=resolvedCandidate("phone",captureValues("phone",true));
        var bothValues=captureValues("both",true);bothValues.put("email","rank@example.com");var both=resolvedCandidate("both",bothValues);
        var input=captureValues("current",true);input.put("email","rank@example.com");run(new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),input));
        var task=task("RESOLVE_LEAD_DUPLICATE");
        var lateValues=new TreeMap<String,Object>(bothValues);lateValues.put("sourceRecordKey","late");lateValues.put("capturedAt","2026-01-01T00:00:00.000000Z");resolvedCandidate("late",lateValues);
        for(var expected:List.of(both,phone,email)) {
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var result=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).duplicate(x,seed.tenant(),task.lead().id(),task.createdAt());assertNotNull(result);assertEquals(expected.lead(),result.lead().id());return null;});}
            UUID survivor=UUID.randomUUID();mutate("insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','Synthetic survivor','ACTIVE')",seed.tenant(),survivor);
            mutate("update party.party set status='MERGED',merged_into_party_id=?,merged_at=clock_timestamp(),revision=revision+1 where tenant_id=? and party_id=?",survivor,seed.tenant(),expected.party());
        }
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertNull(CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).duplicate(x,seed.tenant(),task.lead().id(),task.createdAt()));return null;});}
    }
    private void completeFixtureIngress(UUID id)throws Exception {
        completeFixtureIngress(id,null);
    }
    private void completeFixtureIngress(UUID id,Boolean phone)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var owner=LeadIngressService.databaseBacked(LeadProtectionTest.protection());
            var values=new TreeMap<String,Object>();values.put("sourceCode","OWNER_CONFIRMED");values.put("sourceSummary","Synthetic source");
            if(phone==null||phone)values.put("phone","+12025550123");if(phone==null||!phone)values.put("email","same@example.com");
            owner.update(x,seed.tenant(),owner.read(x,seed.tenant(),id),null,null,
                values,seed.appointment(),null,owner.now(x));return null;
        });}
    }

    @Test void query_candidate_capture_ties_and_same_tenant_are_exact()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
        var first=resolvedCandidate("first",captureValues("first",true));var tied=resolvedCandidate("tied",captureValues("tied",true));
        run(capture("current",true));var current=task("RESOLVE_LEAD_DUPLICATE");
        UUID expected=first.lead().toString().compareTo(tied.lead().toString())<0?first.lead():tied.lead();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection());
            assertEquals(expected,reader.duplicate(x,seed.tenant(),current.lead().id(),current.createdAt()).lead().id());return null;
        });}
        // Earlier capturedAt wins before the UUID tie breaker, with both candidates already created.
        UUID other=expected.equals(first.lead())?tied.lead():first.lead();
        try(var c=database.adminConnection();var s=c.createStatement()){s.execute("set session_replication_role=replica");try{
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update lead.lead set captured_at='2026-09-03T09:00:00Z' where tenant_id=? and lead_id=?",seed.tenant(),other);
        }finally{s.execute("set session_replication_role=origin");}}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertEquals(other,CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).duplicate(x,seed.tenant(),current.lead().id(),current.createdAt()).lead().id());return null;});}
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var isolated=run(capture("isolated",true)).resultFact();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertNull(CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).duplicate(x,seed.tenant(),isolated.id(),java.time.Instant.now().plusSeconds(1)));return null;});}
    }
}
