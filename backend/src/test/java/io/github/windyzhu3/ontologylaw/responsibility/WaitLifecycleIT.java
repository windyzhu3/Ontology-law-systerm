package io.github.windyzhu3.ontologylaw.responsibility;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.time.*;

class WaitLifecycleIT extends ContactFlowFixture {
    @ParameterizedTest @CsvSource({"CONTACT_LEAD,FUTURE","RESOLVE_LEAD_ROUTING_GAP,FUTURE","CONTACT_LEAD,HASH","RESOLVE_LEAD_ROUTING_GAP,HASH","CONTACT_LEAD,REVISION","RESOLVE_LEAD_ROUTING_GAP,REVISION","CONTACT_LEAD,TYPE","RESOLVE_LEAD_ROUTING_GAP,TYPE","CONTACT_LEAD,TERMINAL","RESOLVE_LEAD_ROUTING_GAP,TERMINAL","CONTACT_LEAD,EXPIRED","RESOLVE_LEAD_ROUTING_GAP,EXPIRED","CONTACT_LEAD,OWNER","RESOLVE_LEAD_ROUTING_GAP,AUTHORITY","CONTACT_LEAD,OVERFLOW","CONTACT_LEAD,DATE","RESOLVE_LEAD_ROUTING_GAP,DATE"})
    void invalid_new_recovery_is_rejected_before_slot_with_zero_business_and_terminal_writes(TaskFactory.Type type,String defect)throws Exception{
        setupFlow(type);Instant due=businessAt.plusSeconds(3600);
        if(defect.equals("FUTURE")){try(var c=database.apiConnection()){due=inTransaction(c,Capability.QUERY,x->TaskFactory.databaseBacked().now(x).plusSeconds(3600));}}
        Instant resume=due;try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),resume,businessAt);return null;});}selectTask(type);
        String authority=defect.equals("TYPE")?(type==TaskFactory.Type.CONTACT_LEAD?"ROUTING_REVIEW_TASK_RECOVER":"CONTACT_TASK_RECOVER"):defect.equals("AUTHORITY")?"CONTACT_TASK_RECOVER":type==TaskFactory.Type.CONTACT_LEAD?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER";
        var actor=service(authority,defect.equals("EXPIRED"));var original=recovery(actor);var values=new TreeMap<String,Object>((Map<String,Object>)original.payload());
        if(defect.equals("HASH"))values.put("waitReceiptHash","A".repeat(43));if(defect.equals("REVISION"))values.put("expectedTaskRevision",0L);if(defect.equals("OVERFLOW"))values.put("expectedTaskRevision",9007199254740991L);if(defect.equals("DATE"))values.put("dueCutoff","2026-13-40T00:00:00Z");
        var command=new CommandEnvelope(defect.equals("TYPE")?(type==TaskFactory.Type.CONTACT_LEAD?CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS:CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS):original.type(),UUID.randomUUID(),UUID.randomUUID(),actor,values);
        if(defect.equals("TERMINAL"))cancelCurrent();if(defect.equals("OWNER"))mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());
        var before=counts();try(var c=database.apiConnection()){var rejection=assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command));assertTrue(Set.of("VALIDATION_FAILED","NOT_AUTHORIZED").contains(rejection.code()));}assertEquals(before,counts());
    }
    @ParameterizedTest @EnumSource(value=TaskFactory.Type.class,names={"CONTACT_LEAD","RESOLVE_LEAD_ROUTING_GAP"})
    void recovery_accepts_case_insensitive_uuid_and_rfc3339_offset_without_changing_exact_scope(TaskFactory.Type type)throws Exception{
        setupFlow(type);try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}selectTask(type);
        var actor=service(type==TaskFactory.Type.CONTACT_LEAD?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER");var original=recovery(actor);var p=new TreeMap<String,Object>((Map<String,Object>)original.payload());p.put("taskId",((String)p.get("taskId")).toUpperCase(Locale.ROOT));p.put("waitReceiptId",((String)p.get("waitReceiptId")).toUpperCase(Locale.ROOT));p.put("dueCutoff",Instant.parse((String)p.get("dueCutoff")).atOffset(ZoneOffset.ofHours(8)).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX")));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(new CommandEnvelope(original.type(),original.commandId(),original.correlationId(),actor,p)).status());
    }
    @Test void successful_recovery_replays_after_real_contact_completion_but_new_key_never_reopens_done_task()throws Exception{
        setupContact();execute(prepare(contact("NOT_CONNECTED")));selectTask(TaskFactory.Type.CONTACT_LEAD);var actor=service("CONTACT_TASK_RECOVER");var recovery=recovery(actor);var recovered=execute(recovery);selectTask(TaskFactory.Type.CONTACT_LEAD);businessAt=Instant.parse((String)((Map<?,?>)recovery.payload()).get("dueCutoff")).plusSeconds(1);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("CONNECTED_VALID"))).status());
        var before=counts();assertEquals(recovered,execute(recovery));assertEquals(before,counts());
        var another=new CommandEnvelope(recovery.type(),UUID.randomUUID(),UUID.randomUUID(),actor,recovery.payload());try(var c=database.apiConnection()){assertEquals("VALIDATION_FAILED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,another)).code());}assertEquals(before,counts());
    }
    @ParameterizedTest @EnumSource(value=TaskFactory.Type.class,names={"CONTACT_LEAD","RESOLVE_LEAD_ROUTING_GAP"})
    void due_recovery_only_cas_reopens_same_task_and_preserves_wait_sla_owner_and_replay(TaskFactory.Type type)throws Exception {
        setupFlow(type);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}
        selectTask(type);var actor=service(type==TaskFactory.Type.CONTACT_LEAD?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER");var command=recovery(actor);
        String immutable=scalar("select jsonb_build_array(owner_appointment_id,subject_type,subject_id,subject_revision,original_sla_code,original_sla_seconds,original_sla_due_at)::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());
        var before=counts();var receipt=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());delta(before,0,0,0,0,0,0,1,1,1,1,1);
        assertEquals("OPEN",selectTask(type).state());assertEquals(2L,current.selector().revision());
        assertEquals(immutable,scalar("select jsonb_build_array(owner_appointment_id,subject_type,subject_id,subject_revision,original_sla_code,original_sla_seconds,original_sla_due_at)::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        var after=counts();assertEquals(receipt,execute(command));assertEquals(after,counts());
        for(String conflict:List.of("PAYLOAD","SCOPE")){
            var changed=new TreeMap<String,Object>((Map<String,Object>)command.payload());if(conflict.equals("PAYLOAD"))changed.put("dueCutoff",businessAt.plusSeconds(3601).toString());else changed.put("waitReceiptId",UUID.randomUUID().toString());
            var retry=new CommandEnvelope(command.type(),command.commandId(),command.correlationId(),actor,changed);try(var c=database.apiConnection()){assertInstanceOf(CommandResult.Conflict.class,runtime.execute(c,retry));}assertEquals(after,counts());
        }
        var another=new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),actor,command.payload());
        assertEquals(CommandOutcome.Status.NO_CHANGE,execute(another).status());
        delta(after,0,0,0,0,0,0,1,1,1,0,0);
    }
}
