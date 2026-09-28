package io.github.windyzhu3.ontologylaw.opportunity;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OpportunityClosureIT extends ContactFlowFixture {
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private final OpportunityClosureService service=io.github.windyzhu3.ontologylaw.api.R2OpportunityClosureServices.create(cipher);
    private Subject opportunity;
    private void opening()throws Exception{setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector());}}
    private OpportunityClosureService.Input input()throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var s=service.inspect(x,seed.tenant(),opportunity.id());return new OpportunityClosureService.Input(s.opportunity(),s.responsibility().basis(),s.task(),s.waitReceipt(),seed.appointment(),"CLIENT_DECLINED","客户明确拒绝继续洽谈");});}}
    private OpportunityClosureService.Closure close(OpportunityClosureService.Input in)throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->service.close(x,seed.tenant(),in));}}
    @Test void no_task_closure_keeps_a_protected_immutable_reason_without_creating_a_task()throws Exception{
        opening();var result=close(input());assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
        assertEquals("CLIENT_DECLINED",scalar("select close_outcome_code from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var metadata=service.inspect(x,seed.tenant(),opportunity.id());assertTrue(metadata.closed());assertNull(metadata.task());assertEquals(result,metadata.closure());assertEquals("客户明确拒绝继续洽谈",service.summary(x,seed.tenant(),result.selector()));return null;});}
        assertThrows(Exception.class,()->mutate("update opportunity.closure set reason_code='OTHER' where tenant_id=? and closure_id=?",seed.tenant(),result.selector().id()));
    }
    @Test void open_task_is_cancelled_not_completed_and_stale_close_cannot_rewrite_reason()throws Exception{
        opening();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt));}
        var in=input();var result=close(in);assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),in.task().id()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=? and completion_fact_type is not null",seed.tenant(),in.task().id()));
        assertEquals("STALE_SUBJECT",assertThrows(OpportunityClosureService.Blocked.class,()->close(in)).code());
        assertEquals(result.selector().id().toString(),scalar("select cancellation_fact_id from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),in.task().id()));
    }
    @Test void waiting_close_cancels_exact_wait_without_reopening_or_new_progress()throws Exception{
        opening();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("PHONE_CONNECTED","待再次确认",businessAt,Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS)),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));});}
        var in=input();assertNotNull(in.waitReceipt());var result=close(in);
        assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),in.task().id()));
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));assertEquals(in.waitReceipt(),result.waitReceipt());
    }
    @Test void stale_task_and_outer_failure_leave_all_terminal_facts_unchanged()throws Exception{
        opening();var stale=input();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt));}
        assertEquals("STALE_TASK",assertThrows(OpportunityClosureService.Blocked.class,()->close(stale)).code());var in=input();
        try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{service.close(x,seed.tenant(),in);throw new IllegalStateException("rollback");}));}
        assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),in.task().id()));
    }
    @Test void closure_ciphertext_cannot_be_reinterpreted_as_progress(){var tenant=UUID.randomUUID();var opportunity=UUID.randomUUID();var closure=UUID.randomUUID();var body=cipher.encryptClosure(tenant,opportunity,closure,"说明");assertEquals("说明",cipher.decryptClosure(tenant,opportunity,closure,body));assertThrows(IllegalArgumentException.class,()->cipher.decrypt(tenant,opportunity,closure,body));}
}
