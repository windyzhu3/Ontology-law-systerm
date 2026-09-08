package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContactSafetyIT extends ContactFlowFixture {
    @ParameterizedTest @ValueSource(strings={"EVENT2","OUTBOX2","RECEIPT","AUDIT"})
    void technical_terminal_write_fault_rolls_back_complete_contact_branch_and_cross_tenant_sentinel(String fault)throws Exception{
        setupContact();UUID foreign=seed.tenant();String sentinel=tenantSnapshot(foreign);setupContact();var command=prepare(contact("CONNECTED_VALID"));var before=counts();
        String table=switch(fault){case "EVENT2"->"execution.domain_event";case "OUTBOX2"->"execution.domain_event_outbox";case "RECEIPT"->"execution.command_receipt";default->"audit.audit_entry";};
        String extra=switch(fault){case "EVENT2"->" and NEW.event_type=''OpportunityOpened''";case "OUTBOX2"->" and (select count(*) from execution.domain_event_outbox where tenant_id=NEW.tenant_id)=1";default->"";};
        try(var admin=database.migratorConnection();var sql=admin.createStatement()){
            sql.execute("create function public.task6_fail() returns trigger language plpgsql as 'begin if NEW.tenant_id=''"+seed.tenant()+"''::uuid"+extra+" then raise exception ''Synthetic Task6 storage fault'' using errcode=''XX000''; end if; return NEW; end'");
            try{sql.execute("create trigger task6_fail before insert on "+table+" for each row execute function public.task6_fail()");
                try(var c=database.apiConnection()){var thrown=assertThrows(Exception.class,()->runtime.execute(c,command));assertFalse(thrown instanceof CommandHandler.Rejected);Throwable cause=thrown;while(cause!=null&&!(cause instanceof SQLException))cause=cause.getCause();assertInstanceOf(SQLException.class,cause);assertEquals("XX000",((SQLException)cause).getSQLState());}
                assertEquals(before,counts());assertEquals(sentinel,tenantSnapshot(foreign));assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
                assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
            }finally{sql.execute("drop trigger if exists task6_fail on "+table);sql.execute("drop function public.task6_fail()");}
        }
    }
    private String tenantSnapshot(UUID tenant)throws Exception{return scalar("select jsonb_build_array((select count(*) from responsibility.task_occurrence where tenant_id=?),(select count(*) from lead.lead_contact_result where tenant_id=?),(select count(*) from execution.command_receipt where tenant_id=?),(select count(*) from audit.audit_entry_classified_v where tenant_id=?))::text",tenant,tenant,tenant,tenant);}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void competing_submissions_append_one_immutable_result_and_one_opportunity(boolean sameKey)throws Exception{
        setupContact();var command=prepare(contact("CONNECTED_VALID"));var other=sameKey?command:new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),command.actor(),command.payload(),command.taskPrecondition());var before=counts();
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return execute(command);});var b=pool.submit(()->{gate.await();return execute(other);});gate.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            if(sameKey){assertEquals(first,second);delta(before,1,1,0,0,0,0,1,1,1,2,2);}else{assertEquals(1,List.of(first,second).stream().filter(o->o.status()==CommandOutcome.Status.SUCCEEDED).count());delta(before,1,1,0,0,0,0,2,2,2,2,2);}
        }
        assertEquals("1",scalar("select contact_no::text from lead.lead_contact_result where tenant_id=?",seed.tenant()));
        assertThrows(Exception.class,()->mutate("update lead.lead_contact_result set result_summary='changed' where tenant_id=?",seed.tenant()));
    }
    @ParameterizedTest @ValueSource(strings={"TASK","DRAFT","ASSIGNMENT"})
    void stale_exact_preconditions_keep_business_facts_unchanged(String stale)throws Exception{
        setupContact();var values=contact("CONNECTED_VALID");if(stale.equals("ASSIGNMENT"))values.put("leadAssignmentRevision",1L);var command=prepare(values);
        if(stale.equals("TASK"))command=new CommandEnvelope(command.type(),command.commandId(),command.correlationId(),command.actor(),command.payload(),new CommandEnvelope.TaskPrecondition(current.selector().id(),"\"task."+"A".repeat(43)+"\""));
        if(stale.equals("DRAFT")){try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var drafts=ActionDraftService.databaseBacked();var changed=new TreeMap<String,Object>(values);changed.put("legalNeed","Changed confirmed need");drafts.save(x,seed.tenant(),current,drafts.read(x,seed.tenant(),current.selector().id()),CurrentLeadReader.validatedDraftValues(current.type().command,changed),seed.appointment(),businessAt.plusSeconds(1));return null;});}}
        var before=counts();var outcome=execute(command);assertEquals(CommandOutcome.Status.REJECTED,outcome.status());delta(before,0,0,0,0,0,0,1,1,1,0,0);
    }
    @Test void maximum_global_ordinal_cannot_increment_and_is_technical_zero_delta()throws Exception{
        setupContact();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var tasks=TaskFactory.databaseBacked();var source=tasks.create(x,seed.tenant(),TaskFactory.Type.CONTACT_LEAD,seed.appointment(),current.lead(),ZoneId.of("Asia/Shanghai"),businessAt.minusSeconds(10));UUID id=UUID.randomUUID();io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values (?,?,?,?,9007199254740991,?,'PHONE','NOT_CONNECTED',?,?)",seed.tenant(),id,current.lead().id(),secondaryFact.id(),source.selector().id(),businessAt.minusSeconds(9).atOffset(ZoneOffset.UTC),businessAt.minusSeconds(9).atOffset(ZoneOffset.UTC));var fact=CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),id).selector();tasks.complete(x,seed.tenant(),source,fact,businessAt.minusSeconds(9));return null;});}
        var command=prepare(contact("CONNECTED_VALID"));var before=counts();try(var c=database.apiConnection()){var failure=assertThrows(SQLException.class,()->runtime.execute(c,command));assertEquals("22003",failure.getSQLState());}assertEquals(before,counts());
    }
}
