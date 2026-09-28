package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2OpportunityClosureCommandIT extends ContactFlowFixture {
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private Subject opportunity;
    private void setup(boolean closeGrant,boolean task)throws Exception{
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{if(closeGrant)grant(x,"OPPORTUNITY_CLOSE");grant(x,"SALES_OPPORTUNITY_OWNER");var o=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();if(task)TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),o,ZoneId.of("Asia/Shanghai"),businessAt);return o;});}
        runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"T04_CLOSE_IT");
    }
    private CommandEnvelope command()throws Exception{
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var s=io.github.windyzhu3.ontologylaw.api.R2OpportunityClosureServices.create(cipher).inspect(x,seed.tenant(),opportunity.id());var p=new TreeMap<String,Object>();p.put("opportunityId",s.opportunity().id().toString());p.put("expectedOpportunityRevision",s.opportunity().revision());p.put("expectedResponsibility",CommandScope.selector(s.responsibility().basis()));p.put("expectedTask",CommandScope.selector(s.task()));p.put("expectedWait",CommandScope.selector(s.waitReceipt()));p.put("reasonCode","NEED_CANCELLED");p.put("summary","客户明确取消本次法律需求");return new CommandEnvelope(CommandEnvelope.Type.CLOSE_OPPORTUNITY,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p);});}
    }
    private void deny(Subject fact)throws Exception{mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_CLOSE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),fact.type(),fact.id(),fact.revision());}
    @Test void close_grant_is_not_implied_by_followup_grant()throws Exception{
        setup(false,true);var e=command();try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void successful_close_replays_and_recovers_exact_receipt_without_sensitive_audit_text()throws Exception{
        setup(true,true);var e=command();var result=execute(e);assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());assertEquals("opportunity.closure",result.resultFact().type());assertEquals(0L,result.resultFact().revision());var counts=counts();assertEquals(result,execute(e));assertEquals(counts,counts());
        try(var c=database.apiConnection()){var receipt=new CommandReceiptRecoveryService(policies,protection,null,"T04_RECEIPT_IT").read(c,seed.request().actor(),e.commandId(),UUID.randomUUID());assertEquals(200,receipt.status(),receipt.errorCode());assertEquals("OPPORTUNITY_CLOSURE",((Map<?,?>)receipt.body().get("resultFact")).get("factType"));}
        assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='OpportunityClosedV1'",seed.tenant()));
        assertEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and change_summary::text like '%客户明确取消%'",seed.tenant()));
    }
    @Test void exact_source_task_and_result_denials_fail_closed()throws Exception{
        setup(true,true);var e=command();var in=R2OpportunityClosureInput.parse(e);deny(in.task());try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void new_revision_deny_rolls_back_the_terminal_fact_and_cancellation()throws Exception{
        setup(true,true);var e=command();deny(new Subject(opportunity.type(),opportunity.id(),opportunity.revision()+1,null));var result=execute(e);assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),R2OpportunityClosureInput.parse(e).task().id()));
    }
    @Test void exact_owner_identity_deny_blocks_direct_command()throws Exception{
        setup(true,true);var e=command();try(var c=database.apiConnection()){var owner=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.databaseBacked().read(x,seed.tenant(),seed.appointment()));deny(owner.appointment().selector());}
        try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void exact_opening_assignment_deny_blocks_direct_command()throws Exception{
        setup(true,false);var e=command();try(var c=database.apiConnection()){var source=inTransaction(c,Capability.QUERY,x->{var opening=EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id());return io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked().assignment(x,seed.tenant(),opening.assignmentId()).selector();});deny(source);}
        try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void exact_draft_deny_blocks_direct_command_without_discarding_draft()throws Exception{
        setup(true,true);var e=command();var task=R2OpportunityClosureInput.parse(e).task();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var values=Map.<String,Object>of("progressTypeCode","MEETING","progressSummary","准备沟通","occurredAt",now.minusSeconds(1).toString(),"nextCheckAt",now.plusSeconds(86400).toString());
        try(var c=database.apiConnection()){var draft=inTransaction(c,Capability.COMMAND,x->ActionDraftService.databaseBacked().save(x,seed.tenant(),TaskFactory.databaseBacked().read(x,seed.tenant(),task.id()),null,values,seed.appointment(),now).draft());deny(draft.selector());}
        try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void linked_party_deny_blocks_direct_command()throws Exception{
        setup(true,true);UUID party=UUID.randomUUID();mutate("insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'ORGANIZATION','Closure linked party','ACTIVE')",seed.tenant(),party);
        mutate("update lead.lead set parsed_party_id=?,party_resolution_code='RESOLVED',revision=revision+1 where tenant_id=? and lead_id=(select source_lead_id from opportunity.opportunity where tenant_id=? and opportunity_id=?)",party,seed.tenant(),seed.tenant(),opportunity.id());deny(new Subject("party.party",party,0L,null));var e=command();
        try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
    @Test void terminal_fact_deny_blocks_receipt_and_replay()throws Exception{
        setup(true,false);var e=command();var closed=execute(e);deny(closed.resultFact());try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}
        try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"T04_RESULT_DENY_IT").read(c,seed.request().actor(),e.commandId(),UUID.randomUUID()).status());}
    }
    @Test void revoked_grant_blocks_both_replay_and_receipt()throws Exception{
        setup(true,false);var e=command();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(e).status());mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant(),seed.appointment());
        try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e));}
        try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"T04_REVOKED_IT").read(c,seed.request().actor(),e.commandId(),UUID.randomUUID()).status());}
    }
    @Test void two_distinct_close_keys_serialize_to_one_terminal_fact()throws Exception{
        setup(true,true);var first=command();var second=command();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var attempts=List.<java.util.concurrent.Callable<CommandOutcome>>of(()->execute(first),()->execute(second));var outcomes=new ArrayList<CommandOutcome>();for(var future:pool.invokeAll(attempts))outcomes.add(future.get());assertEquals(1,outcomes.stream().filter(o->o.status()==CommandOutcome.Status.SUCCEEDED).count());assertEquals(1,outcomes.stream().filter(o->o.status()==CommandOutcome.Status.REJECTED&&"STALE_SUBJECT".equals(o.rejectionCode())).count());}
        assertEquals("1",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
}
