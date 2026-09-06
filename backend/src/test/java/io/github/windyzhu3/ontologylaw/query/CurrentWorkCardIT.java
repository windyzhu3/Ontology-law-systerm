package io.github.windyzhu3.ontologylaw.query;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.CurrentWorkCardDisclosureService;
import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.api.OpenApiContractTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import java.time.Instant;

class CurrentWorkCardIT extends WorkcardTestFixture {
    // Catches a missing variant, wrong Owner/source selectors, leaked fields, and a missing per-source Audit.
    @Test void seven_cards_have_exact_sources_owner_and_frozen_shape()throws Exception {
        int[] counts={7,5,8,5,6,6,6};int index=0;
        for(var type:TaskFactory.Type.values()) {
            setupCard(type);
            try(var c=database.apiConnection()) {
                var response=new CurrentWorkCardDisclosureService(protection,policies,"WORKCARD_IT").read(c,seed.request().actor(),UUID.randomUUID(),null);
                assertNotNull(response);assertEquals(200,response.status());var body=response.body();assertNotNull(body);
                OpenApiContractTest.assertCurrentWorkcardWire(body);
                assertEquals(Set.of("todaySummary","currentCard","nextSummaries","waitingCount","chatComposer"),body.keySet());
                var card=(Map<?,?>)body.get("currentCard");assertNotNull(card);assertEquals(type.name(),card.get("taskType"));assertEquals(current.selector().id().toString(),card.get("taskId"));
                assertEquals(Set.of("taskId","taskType","taskRevision","subject","owner","businessPurpose","primaryCommand","expectedCompletionFact","sla","versionStatus","commandForm","actionDraft","preconditions"),card.keySet());
                assertEquals(Map.of("displayName","fixture","organizationLabel","fixture"),card.get("owner"));
                assertEquals(type.command,((Map<?,?>)card.get("primaryCommand")).get("code"));assertNull(card.get("actionDraft"));
                assertEquals(List.of(),body.get("nextSummaries"));assertEquals(0,body.get("waitingCount"));
                assertEquals("private, no-cache",response.cacheControl());assertEquals("Authorization",response.vary());
                assertTrue(response.etag().matches("\"wb\\.[A-Za-z0-9_-]{43}\""));
            }
            assertEquals(counts[index++],auditCount());
            assertExactInitialSources(type);
        }
    }
    private void assertExactInitialSources(TaskFactory.Type type)throws Exception {
        var expected=new LinkedHashMap<AuthorizationService.Subject,AuthorizationService.Subject>();
        expected.put(current.selector(),current.selector());expected.put(current.lead(),current.lead());
        expected.put(new AuthorizationService.Subject("identity.appointment",seed.appointment(),0L,null),current.selector());
        expected.put(new AuthorizationService.Subject("identity.principal",seed.principal(),0L,null),current.selector());
        expected.put(new AuthorizationService.Subject("identity.organization_unit",seed.org(),0L,null),current.selector());
        if(type==TaskFactory.Type.RESOLVE_LEAD_DUPLICATE) {
            var lead=new AuthorizationService.Subject("lead.lead",secondaryLead,1L,null);var party=new AuthorizationService.Subject("party.party",secondaryParty,0L,null);
            expected.put(lead,lead);expected.put(party,party);
        }
        if(type==TaskFactory.Type.ASSIGN_LEAD) {
            expected.put(new AuthorizationService.Subject("identity.appointment",secondaryAppointment,0L,null),current.lead());
            expected.put(new AuthorizationService.Subject("identity.principal",secondaryPrincipal,0L,null),current.lead());
            expected.put(new AuthorizationService.Subject("identity.organization_unit",secondaryOrganization,0L,null),current.lead());
        }
        if(Set.of(TaskFactory.Type.CONTACT_LEAD,TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST,TaskFactory.Type.REVIEW_LEAD_VALIDITY).contains(type))
            expected.put(secondaryFact,type==TaskFactory.Type.REVIEW_LEAD_VALIDITY?current.lead():secondaryFact);
        var actual=new LinkedHashMap<AuthorizationService.Subject,AuthorizationService.Subject>();
        try(var c=database.adminConnection();var p=c.prepareStatement("select subject_type,subject_id,subject_revision,subject_hash,change_summary->'authorizationAnchor'->>'type',change_summary->'authorizationAnchor'->>'id',change_summary->'authorizationAnchor'->>'revision',change_summary->'authorizationAnchor'->>'hash' from audit.audit_entry where tenant_id=?")) {
            p.setObject(1,seed.tenant());try(var r=p.executeQuery()){while(r.next()) {
                byte[] hash=r.getBytes(4);var source=new AuthorizationService.Subject(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,Long.class),hash==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
                var anchor=new AuthorizationService.Subject(r.getString(5),UUID.fromString(r.getString(6)),r.getString(7)==null?null:Long.valueOf(r.getString(7)),r.getString(8));
                assertNull(actual.put(source,anchor),"Each exact source must be audited once");
            }}
        }
        assertEquals(expected,actual);
    }
    @Test void contact_draft_restores_exact_values_and_conditional_form_without_inventing_legal_need()throws Exception {
        setupCard(TaskFactory.Type.CONTACT_LEAD);
        var values=Map.<String,Object>of("leadAssignmentId",secondaryFact.id().toString(),"leadAssignmentRevision",secondaryFact.revision(),"contactChannelCode","PHONE","resultCode","NOT_CONNECTED","resultSummary","未联系");
        var draft=saveDraft(values,false);var response=readCard(null);assertEquals(200,response.status());
        OpenApiContractTest.assertCurrentWorkcardWire(response.body());
        var card=(Map<?,?>)response.body().get("currentCard");var exposed=(Map<?,?>)card.get("actionDraft");
        assertEquals(Set.of("draftId","draftRevision","actionCode","schemaVersion","values","digest","updatedAt","editable"),exposed.keySet());
        assertEquals(values,exposed.get("values"));assertEquals(true,exposed.get("editable"));assertEquals(draft.digest(),exposed.get("digest"));assertEquals(7,auditCount());
        saveDraft(values,true);var confirmed=readCard(null);OpenApiContractTest.assertCurrentWorkcardWire(confirmed.body());
        assertEquals(false,((Map<?,?>)((Map<?,?>)confirmed.body().get("currentCard")).get("actionDraft")).get("editable"));
    }
    @Test void ordering_uses_sla_then_created_then_unsigned_uuid_and_authorizes_summaries_and_waiting_individually()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);cancelCurrent();
        Instant created=Instant.parse("2026-01-05T01:00:00Z"),due=Instant.parse("2026-01-05T05:00:00Z");
        UUID low=UUID.fromString("10000000-0000-4000-8000-000000000001"),high=UUID.fromString("90000000-0000-4000-8000-000000000001");
        var highTask=addTask(high,created,due,"OPEN");var lowTask=addTask(low,created,due,"OPEN");
        var laterCreated=addTask(UUID.randomUUID(),created.plusSeconds(1),due,"OPEN");var laterDue=addTask(UUID.randomUUID(),created.minusSeconds(1),due.plusSeconds(1),"OPEN");
        try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{grant(x,"LEAD_ROUTING_DECIDE");return null;});}
        var wait=addTask(UUID.randomUUID(),created,due,"WAITING");var hiddenWait=addTask(UUID.randomUUID(),created,due,"WAITING");deny(hiddenWait.selector(),"LEAD_ROUTING_DECIDE");
        var response=readCard(null);assertEquals(200,response.status());var body=response.body();OpenApiContractTest.assertCurrentWorkcardWire(body);
        assertEquals(low.toString(),((Map<?,?>)body.get("currentCard")).get("taskId"));assertEquals(1,body.get("waitingCount"));
        var next=(List<Map<String,Object>>)body.get("nextSummaries");assertEquals(List.of(high.toString(),laterCreated.selector().id().toString()),next.stream().map(m->m.get("taskId")).toList());
        assertEquals(Set.of("taskId","businessPurpose","priority","timeHint"),next.getFirst().keySet());
        deny(highTask.selector(),"LEAD_INGRESS_COMPLETE");var filtered=readCard(null);var summaries=(List<Map<String,Object>>)filtered.body().get("nextSummaries");
        assertEquals(List.of(laterCreated.selector().id().toString(),laterDue.selector().id().toString()),summaries.stream().map(m->m.get("taskId")).toList());
    }
    @Test void hidden_canonical_duplicate_is_never_replaced_by_another_candidate_and_falls_back_to_safe_task_or_zero()throws Exception {
        setupCard(TaskFactory.Type.RESOLVE_LEAD_DUPLICATE);
        try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{
            var spare=io.github.windyzhu3.ontologylaw.lead.LeadIngressService.databaseBacked(protection).capture(x,seed.tenant(),input(true),io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest("spare"),current.createdAt().minusNanos(1000));
            UUID spareParty=UUID.randomUUID();io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','可见第二候选','ACTIVE')",seed.tenant(),spareParty);
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"update lead.lead set parsed_party_id=?,party_resolution_code='RESOLVED',revision=revision+1 where tenant_id=? and lead_id=?",spareParty,seed.tenant(),spare.selector().id());return null;
        });}
        deny(new AuthorizationService.Subject("party.party",secondaryParty,0L,null),"LEAD_INGRESS_RESOLVE");
        var zero=readCard(null);assertEquals(200,zero.status());assertNull(zero.body().get("currentCard"));assertEquals(0,auditCount());
        assertFalse(zero.body().toString().contains(secondaryParty.toString()));assertFalse(zero.body().toString().contains("候选当事人"));assertFalse(zero.body().toString().contains("可见第二候选"));
        try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{grant(x,"LEAD_INGRESS_COMPLETE");return null;});}
        var next=addTask(UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(3600),"OPEN");
        var response=readCard(null);assertEquals(next.selector().id().toString(),((Map<?,?>)response.body().get("currentCard")).get("taskId"));assertFalse(response.body().toString().contains(secondaryParty.toString()));
    }
    @Test void zero_state_is_authorized_static_and_terminal_or_other_owner_tasks_never_become_full_card()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);cancelCurrent();var response=readCard(null);
        assertEquals(200,response.status());assertNull(response.body().get("currentCard"));assertEquals(List.of(),response.body().get("nextSummaries"));assertEquals(0,auditCount());
        var composer=(Map<?,?>)response.body().get("chatComposer");assertNull(composer.get("targetTaskId"));assertEquals(false,composer.get("enabled"));OpenApiContractTest.assertCurrentWorkcardWire(response.body());
    }
    @Test void every_variant_restores_and_seals_its_validated_draft_with_one_extra_exact_audit_source()throws Exception {
        int[] counts={8,6,9,6,7,7,7};int index=0;
        for(var type:TaskFactory.Type.values()) {
            setupCard(type);
            Map<String,Object> values=switch(type) {
                case RESOLVE_LEAD_DUPLICATE -> Map.of("decisionCode","KEEP_SEPARATE","candidateLeadId",secondaryLead.toString(),"candidateLeadRevision",1L,"partyId",secondaryParty.toString(),"partyRevision",0L,"rationaleSummary","归属说明");
                case COMPLETE_LEAD_INGRESS -> Map.of("phone","+12025550126","sourceCode","OWNER_CONFIRMED","sourceSummary","来源说明");
                case ASSIGN_LEAD -> Map.of("ownerAppointmentId",secondaryAppointment.toString());
                case RESOLVE_LEAD_ROUTING_GAP -> Map.of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary","安排说明");
                case ACK_SOURCE_INTAKE_STOP_REQUEST -> Map.of("causalDecisionId",secondaryFact.id().toString(),"causalDecisionHash",secondaryFact.hash(),"rationaleSummary","接收说明");
                case CONTACT_LEAD -> Map.of("leadAssignmentId",secondaryFact.id().toString(),"leadAssignmentRevision",secondaryFact.revision(),"contactChannelCode","PHONE","resultCode","CONNECTED_VALID","legalNeed","本次确认需求");
                case REVIEW_LEAD_VALIDITY -> Map.of("triggeringContactResultId",secondaryFact.id().toString(),"triggeringContactResultHash",secondaryFact.hash(),"decisionCode","CONFIRM_INVALID","rationaleSummary","复核说明");
            };
            saveDraft(values,false);var open=readCard(null);assertEquals(200,open.status());OpenApiContractTest.assertCurrentWorkcardWire(open.body());
            assertEquals(values,((Map<?,?>)((Map<?,?>)open.body().get("currentCard")).get("actionDraft")).get("values"));assertEquals(counts[index],auditCount());
            saveDraft(values,true);var sealed=readCard(open.etag());assertEquals(200,sealed.status());OpenApiContractTest.assertCurrentWorkcardWire(sealed.body());
            assertEquals(false,((Map<?,?>)((Map<?,?>)sealed.body().get("currentCard")).get("actionDraft")).get("editable"));assertEquals(counts[index++]*2L,auditCount());
        }
    }
}
