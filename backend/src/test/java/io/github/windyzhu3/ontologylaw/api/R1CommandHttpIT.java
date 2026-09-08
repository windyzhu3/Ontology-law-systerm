package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class R1CommandHttpIT extends R1HttpFixture {
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=TaskFactory.Type.class,names={"ASSIGN_LEAD","RESOLVE_LEAD_DUPLICATE"})
    void rejected_inactive_assignee_or_stale_candidate_receipt_is_recovered_without_new_command_eligibility(TaskFactory.Type type)throws Exception {
        setupFlow(type);provisionSuccessors();var command=prepare(candidate(type));
        if(type==TaskFactory.Type.ASSIGN_LEAD)mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),secondaryAppointment);
        else mutate("update party.party set canonical_name='Changed candidate fixture',revision=revision+1 where tenant_id=? and party_id=?",seed.tenant(),secondaryParty);
        try(var http=new HttpHarness()) {
            var before=counts();var rejected=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/"+path(type),command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",command.taskPrecondition().ifMatch()));assertEquals(412,rejected.statusCode(),rejected.body());assertEquals("STALE_SUBJECT",http.body(rejected).get("code"));delta(before,0,0,0,0,0,0,1,1,1,0,0);
            before=counts();var receipt=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertEquals("REJECTED",http.body(receipt).get("outcome"));assertEquals("STALE_SUBJECT",http.body(receipt).get("rejectionCode"));delta(before,0,0,0,0,0,0,0,0,1,0,0);
        }
    }
    void provisionSuccessors()throws Exception {
        var codes=new HashSet<String>();for(var type:TaskFactory.Type.values())codes.add(type.authority);codes.add("LEAD_CAPTURE");
        for(var code:codes)if("0".equals(scalar("select count(*)::text from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code=?",seed.tenant(),seed.appointment(),code)))
            mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),code);
    }
    Map<String,Object> candidate(TaskFactory.Type type){return switch(type){
        case COMPLETE_LEAD_INGRESS->Map.of("phone","+12025550999","sourceCode","OWNER_CONFIRMED","sourceSummary","HTTP verified input");
        case RESOLVE_LEAD_DUPLICATE->Map.of("decisionCode","KEEP_SEPARATE","candidateLeadId",secondaryLead.toString(),"candidateLeadRevision",1L,"partyId",secondaryParty.toString(),"partyRevision",0L,"rationaleSummary","Verified separate matter");
        case ASSIGN_LEAD->Map.of("ownerAppointmentId",secondaryAppointment.toString());
        case RESOLVE_LEAD_ROUTING_GAP->Map.of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary","Review the routing later");
        case ACK_SOURCE_INTAKE_STOP_REQUEST->Map.of("causalDecisionId",secondaryFact.id().toString(),"causalDecisionHash",secondaryFact.hash(),"rationaleSummary","Source owner acknowledged request");
        case CONTACT_LEAD->contact("CONNECTED_VALID");
        case REVIEW_LEAD_VALIDITY->Map.of("triggeringContactResultId",secondaryFact.id().toString(),"triggeringContactResultHash",secondaryFact.hash(),"decisionCode","CONFIRM_INVALID","rationaleSummary","Supervisor reviewed current evidence");
    };}
    static String path(TaskFactory.Type type){return type.command.toLowerCase(Locale.ROOT).replace('_','-');}
    @ParameterizedTest @CsvSource({"RESOLVE_LEAD_DUPLICATE,DECISION_RECORD","COMPLETE_LEAD_INGRESS,LEAD","ASSIGN_LEAD,LEAD_ASSIGNMENT","RESOLVE_LEAD_ROUTING_GAP,DECISION_RECORD","ACK_SOURCE_INTAKE_STOP_REQUEST,DECISION_RECORD","CONTACT_LEAD,LEAD_CONTACT_RESULT","REVIEW_LEAD_VALIDITY,DECISION_RECORD"})
    void seven_generated_primary_operations_commit_exact_facts_and_original_receipt_projection(TaskFactory.Type type,String factType)throws Exception {
        setupFlow(type);provisionSuccessors();var command=prepare(candidate(type));
        try(var http=new HttpHarness()) {
            var response=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/"+path(type),command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",command.taskPrecondition().ifMatch()));
            assertEquals(200,response.statusCode(),response.body());var body=http.body(response);assertEquals("SUCCEEDED",body.get("outcome"));assertEquals(command.commandId().toString(),body.get("commandId"));assertEquals(7,UUID.fromString((String)body.get("receiptId")).version());
            var fact=(Map<?,?>)body.get("resultFact");assertEquals(factType,fact.get("factType"));assertEquals(3,fact.size());assertFalse(response.body().contains(seed.tenant().toString()));assertFalse(response.body().contains(current.lead().id().toString()));assertEquals("/api/v1/commands/"+command.commandId()+"/receipt",response.headers().firstValue("Location").orElseThrow());
            assertEquals("2",scalar("select summary_schema_version::text from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
            var committed=counts();var replay=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/"+path(type),command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",command.taskPrecondition().ifMatch()));assertEquals(200,replay.statusCode(),replay.body());assertEquals(body,http.body(replay));assertEquals(committed,counts());
            var recovered=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(200,recovered.statusCode(),recovered.body());assertEquals(body,http.body(recovered));
        }
    }
    @Test void capture_generated_operation_uses_inputless_authority_context_and_same_key_original_receipt()throws Exception {
        setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);provisionSuccessors();var payload=new TreeMap<String,Object>(input(false));payload.put("sourceRecordKey","HTTP-capture-exact-Case");UUID command=UUID.randomUUID();
        try(var http=new HttpHarness()) {
            var response=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",command.toString()));assertEquals(201,response.statusCode(),response.body());var body=http.body(response);assertEquals("LEAD",((Map<?,?>)body.get("resultFact")).get("factType"));assertEquals("SUCCEEDED",body.get("outcome"));
            var before=counts();var replay=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",command.toString()));assertEquals(201,replay.statusCode());assertEquals(body,http.body(replay));assertEquals(before,counts());
            var read=http.request("GET","/api/v1/commands/"+command+"/receipt",null,Map.of());assertEquals(200,read.statusCode());assertEquals(body,http.body(read));
        }
    }
    @Test void draft_save_update_and_old_key_replay_keep_original_receipt_but_current_authorized_projection()throws Exception {
        setupContact();provisionSuccessors();String draftPath="/api/v1/tasks/"+current.selector().id()+"/draft";UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var valuesA=contact("CONNECTED_VALID");valuesA.put("legalNeed","Original A draft need");var valuesB=new TreeMap<>(valuesA);valuesB.put("legalNeed","Current B draft need");
        var payloadA=Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",valuesA);var payloadB=Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",valuesB);
        try(var http=new HttpHarness()) {
            var first=http.request("PUT",draftPath,payloadA,Map.of("Idempotency-Key",a.toString(),"If-None-Match","*"));assertEquals(201,first.statusCode(),first.body());var firstBody=http.body(first);
            var second=http.request("PUT",draftPath,payloadB,Map.of("Idempotency-Key",b.toString(),"If-Match",first.headers().firstValue("ETag").orElseThrow()));assertEquals(200,second.statusCode(),second.body());var secondBody=http.body(second);
            var before=counts();var replay=http.request("PUT",draftPath,payloadA,Map.of("Idempotency-Key",a.toString(),"If-None-Match","*"));assertEquals(201,replay.statusCode(),replay.body());var replayBody=http.body(replay);assertEquals(firstBody.get("receipt"),replayBody.get("receipt"));assertEquals(secondBody.get("draft"),replayBody.get("draft"));assertEquals(secondBody.get("preconditions"),replayBody.get("preconditions"));assertEquals(second.headers().firstValue("ETag"),replay.headers().firstValue("ETag"));assertEquals(before,counts());
            var draft=(Map<?,?>)secondBody.get("draft");var confirmation=new TreeMap<String,Object>(valuesB);confirmation.put("draftId",draft.get("draftId"));confirmation.put("expectedDraftRevision",draft.get("draftRevision"));confirmation.put("draftDigest",draft.get("digest"));
            var preconditions=(Map<?,?>)secondBody.get("preconditions");var completed=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",confirmation,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",(String)preconditions.get("taskETag")));assertEquals(200,completed.statusCode(),completed.body());
            before=counts();var confirmedReplay=http.request("PUT",draftPath,payloadA,Map.of("Idempotency-Key",a.toString(),"If-None-Match","*"));assertEquals(201,confirmedReplay.statusCode(),confirmedReplay.body());assertEquals(false,((Map<?,?>)http.body(confirmedReplay).get("draft")).get("editable"));assertEquals(firstBody.get("receipt"),http.body(confirmedReplay).get("receipt"));assertEquals(before,counts());
            var recovered=http.request("GET","/api/v1/commands/"+a+"/receipt",null,Map.of());assertEquals(200,recovered.statusCode(),recovered.body());assertEquals(firstBody.get("receipt"),http.body(recovered));delta(before,0,0,0,0,0,0,0,0,1,0,0);
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_CONTACT_OWNER'",seed.tenant());before=counts();assertEquals(403,http.request("GET","/api/v1/commands/"+a+"/receipt",null,Map.of()).statusCode());assertEquals(403,http.request("PUT",draftPath,payloadA,Map.of("Idempotency-Key",a.toString(),"If-None-Match","*")).statusCode());assertEquals(before,counts());
        }
    }
}
