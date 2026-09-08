package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurrentWorkCardCausalIT extends WorkcardTestFixture {
    // Each malformed imported source isolates one causal predicate; no production writer or constraint is weakened.
    @Test void review_requires_completed_contact_task_exact_hash_same_lead_allowed_result_and_created_cutoff()throws Exception {
        for(String fault:List.of("FUTURE","WRONG_LEAD","WRONG_TASK","WRONG_TYPE","WRONG_HASH","WRONG_RESULT")) {
            setupCard(TaskFactory.Type.REVIEW_LEAD_VALIDITY);UUID sourceTask;
            try(var c=database.apiConnection()){sourceTask=inTransaction(c,Capability.QUERY,x->CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),secondaryFact.id()).taskId());}
            try(var c=database.adminConnection();var s=c.createStatement()) {
                s.execute("set session_replication_role=replica");
                try {
                    switch(fault) {
                        case "FUTURE" -> sql(c,"update lead.lead_contact_result set resulted_at=? where tenant_id=? and lead_contact_result_id=?",current.createdAt().plusSeconds(1).atOffset(ZoneOffset.UTC),seed.tenant(),secondaryFact.id());
                        case "WRONG_LEAD" -> sql(c,"update lead.lead_contact_result set lead_id=? where tenant_id=? and lead_contact_result_id=?",UUID.randomUUID(),seed.tenant(),secondaryFact.id());
                        case "WRONG_TASK" -> sql(c,"update lead.lead_contact_result set contact_task_id=? where tenant_id=? and lead_contact_result_id=?",UUID.randomUUID(),seed.tenant(),secondaryFact.id());
                        case "WRONG_TYPE" -> sql(c,"update responsibility.task_occurrence set business_purpose_code='RESOLVE_LEAD_ROUTING_GAP' where tenant_id=? and task_occurrence_id=?",seed.tenant(),sourceTask);
                        case "WRONG_HASH" -> sql(c,"update responsibility.task_occurrence set completion_fact_hash=decode(repeat('ab',32),'hex') where tenant_id=? and task_occurrence_id=?",seed.tenant(),sourceTask);
                        case "WRONG_RESULT" -> sql(c,"update lead.lead_contact_result set result_code='NOT_CONNECTED',contact_no=2 where tenant_id=? and lead_contact_result_id=?",seed.tenant(),secondaryFact.id());
                    }
                    if(!fault.equals("WRONG_HASH")){var exact=CurrentLeadReader.databaseBacked(protection).contactResult(c,seed.tenant(),secondaryFact.id()).selector();sql(c,"update responsibility.task_occurrence set completion_fact_hash=? where tenant_id=? and task_occurrence_id=?",Base64.getUrlDecoder().decode(exact.hash()),seed.tenant(),sourceTask);}
                } finally{s.execute("set session_replication_role=origin");}
            }
            var response=readCard(null);assertEquals(200,response.status());assertNull(response.body().get("currentCard"));assertEquals(0,auditCount());
        }
    }
    @Test void review_selects_greatest_eligible_time_then_unsigned_uuid_and_ignores_uncompleted_newer_result()throws Exception {
        setupCard(TaskFactory.Type.REVIEW_LEAD_VALIDITY);
        UUID high=UUID.fromString("90000000-0000-4000-8000-000000000002"),low=UUID.fromString("10000000-0000-4000-8000-000000000002");
        appendResult(high,current.createdAt(),2,true);appendResult(low,current.createdAt(),3,true);
        var incomplete=appendResult(UUID.randomUUID(),current.createdAt().plusSeconds(1),4,false);deny(incomplete.selector(),"SALES_CONTACT_OWNER");
        var response=readCard(null);assertEquals(200,response.status());var values=(Map<?,?>)((Map<?,?>)((Map<?,?>)response.body().get("currentCard")).get("commandForm")).get("values");assertEquals(high.toString(),values.get("triggeringContactResultId"));assertEquals(6,auditCount());
    }
    private TaskFactory.Task appendResult(UUID id,Instant time,long number,boolean completed)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
            var tasks=TaskFactory.databaseBacked();var lead=CurrentLeadReader.databaseBacked(protection).read(x,seed.tenant(),current.lead().id());
            var task=tasks.create(x,seed.tenant(),TaskFactory.Type.CONTACT_LEAD,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),time);
            sql(x,"insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values (?,?,?,?,?,?,'PHONE','SUSPECT_INVALID',?,?)",seed.tenant(),id,lead.selector().id(),lead.currentAssignmentId(),number,task.selector().id(),time.atOffset(ZoneOffset.UTC),time.atOffset(ZoneOffset.UTC));
            if(completed)tasks.complete(x,seed.tenant(),task,CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),id).selector(),time);return task;
        });}
    }
    @Test void ack_requires_full_routing_completion_proof_and_exact_lead_revision_not_arbitrary_latest_decision()throws Exception {
        for(String fault:List.of("WRONG_HASH","WRONG_CODE","WRONG_REVISION","FUTURE","WRONG_PRIMARY")) {
            setupCard(TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST);UUID sourceTask;
            try(var c=database.apiConnection()){sourceTask=inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().decision(x,seed.tenant(),secondaryFact.id()).taskId());}
            try(var c=database.adminConnection();var s=c.createStatement()) {
                s.execute("set session_replication_role=replica");try {
                    switch(fault) {
                        case "WRONG_HASH" -> sql(c,"update responsibility.task_occurrence set completion_fact_hash=decode(repeat('ab',32),'hex') where tenant_id=? and task_occurrence_id=?",seed.tenant(),sourceTask);
                        case "WRONG_PRIMARY" -> sql(c,"update responsibility.task_occurrence set primary_command_code='WRONG_COMMAND' where tenant_id=? and task_occurrence_id=?",seed.tenant(),sourceTask);
                        case "WRONG_CODE" -> sql(c,"update responsibility.decision_record set decision_code='SCHEDULE_ROUTING_REVIEW' where tenant_id=? and decision_record_id=?",seed.tenant(),secondaryFact.id());
                        case "WRONG_REVISION" -> sql(c,"update responsibility.decision_record set decision_subject_revision=1 where tenant_id=? and decision_record_id=?",seed.tenant(),secondaryFact.id());
                        case "FUTURE" -> sql(c,"update responsibility.decision_record set decided_at=? where tenant_id=? and decision_record_id=?",current.createdAt().plusSeconds(1).atOffset(ZoneOffset.UTC),seed.tenant(),secondaryFact.id());
                    }
                }finally{s.execute("set session_replication_role=origin");}
            }
            var response=readCard(null);assertEquals(200,response.status());assertNull(response.body().get("currentCard"));assertEquals(0,auditCount());
        }
    }
}
