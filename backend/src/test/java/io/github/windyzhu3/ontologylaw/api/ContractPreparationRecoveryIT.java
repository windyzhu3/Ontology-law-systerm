package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ContractPreparationRecoveryIT extends R2ContractQuoteSourceIT {
    ContractWorkflowService contracts;
    Actor worker;
    void initializeRecovery(boolean qualified)throws Exception {
        reply("ACCEPTED");
        if(qualified)qualify();
        worker=service("CONTRACT_TASK_RECOVER");
        contracts=R2ContractServices.create(ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES")),cipher,null);
        runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"CONTRACT_RECOVERY_IT",contracts);
    }
    void qualify()throws Exception{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_PREPARE");return null;});}}
    List<?> candidates()throws Exception{try(var c=database.apiConnection()){return (List<?>)new ContractPreparationDiscovery(contracts,new byte[32]).list(c,worker,50,null).get("candidates");}}
    CommandEnvelope next()throws Exception{
        var rows=candidates();assertEquals(1,rows.size());var body=new LinkedHashMap<String,Object>();
        ((Map<?,?>)rows.getFirst()).forEach((k,v)->body.put((String)k,v));body.remove("kind");
        UUID key=UUID.fromString((String)body.remove("idempotencyKey"));
        return new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,body);
    }
    @Test void production_runtime_recovery_replays_without_duplicate_source_workflow_or_task()throws Exception{
        initializeRecovery(true);var envelope=next();var first=execute(envelope);
        assertEquals(CommandOutcome.Status.SUCCEEDED,first.status(),first.rejectionCode());
        assertEquals("contract.preparation_workflow",first.resultFact().type());assertEquals(first,execute(envelope));
        assertTrue(candidates().isEmpty());assertCounts("1","1");
        assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and command_id=? and command_type='RECONCILE_CONTRACT_PREPARATION' and result_code='SUCCEEDED'",seed.tenant(),envelope.commandId()));
    }
    @Test void background_recovery_reuses_locked_identity_rows_instead_of_blocking_foreground_reads()throws Exception{
        initializeRecovery(true);var envelope=next();
        try(var c=database.apiConnection()){
            var probe=new ReadConnectionProbe(c);var result=runtime.execute(probe.connection(),envelope);
            var outcome=assertInstanceOf(CommandOutcome.class,result);assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status(),outcome.rejectionCode());
            int businessFence=probe.statements.indexOf("select pg_advisory_xact_lock(?)");assertTrue(businessFence>=0);
            // Preliminary authorization runs before the business fence; this bound
            // measures only reads while foreground shared reads are blocked.
            long principalReads=probe.statements.stream().skip(businessFence+1).filter(sql->sql.startsWith("select")&&sql.contains("\"identity\".\"principal\"")).count();
            assertTrue(principalReads<=10,"One maintenance command must reuse identity rows under its shared lock; principal reads="+principalReads);
        }
        assertCounts("1","1");
    }
    @Test void missing_owner_authority_has_no_fake_task_and_recovers_after_real_grant()throws Exception{
        initializeRecovery(false);var first=execute(next());assertEquals(CommandOutcome.Status.SUCCEEDED,first.status(),first.rejectionCode());
        assertCounts("1","0");assertTrue(candidates().isEmpty());
        assertEquals("OWNER_EXCEPTION",scalar("select stage_code from contract.preparation_workflow where tenant_id=?",seed.tenant()));
        qualify();var second=execute(next());assertEquals(CommandOutcome.Status.SUCCEEDED,second.status(),second.rejectionCode());
        assertCounts("2","1");assertTrue(candidates().isEmpty());
        assertEquals(first.resultFact().id().toString(),scalar("select previous_workflow_id::text from contract.preparation_workflow where tenant_id=? and stage_code='PREPARE'",seed.tenant()));
    }
    @Test void owner_transaction_failure_rolls_back_created_task_and_workflow()throws Exception{
        initializeRecovery(true);var envelope=next();
        try(var c=database.apiConnection()){
            assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{
                R1BusinessFence.databaseBacked().exclusive(x,seed.tenant());
                contracts.reconcile(x,worker,(Map<String,Object>)envelope.payload());throw new IllegalStateException("injected rollback");
            }));
        }
        assertCounts("0","0");assertEquals(1,candidates().size());
    }
    @Test void recovery_raw_cursor_advances_filtered_rows_and_rescans_changed_heads()throws Exception {
        initializeRecovery(false);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(next()).status());
        var discovery=new ContractPreparationDiscovery(contracts,new byte[32]);Map<String,Object> filtered;
        try(var c=database.apiConnection()){filtered=discovery.list(c,worker,1,null);}
        assertTrue(((List<?>)filtered.get("candidates")).isEmpty());assertNotNull(filtered.get("nextCursor"));
        try(var c=database.apiConnection()){var tail=discovery.list(c,worker,1,(String)filtered.get("nextCursor"));assertTrue(((List<?>)tail.get("candidates")).isEmpty());assertNull(tail.get("nextCursor"));}
        qualify();assertEquals(1,candidates().size());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(next()).status());
        try(var c=database.apiConnection()){var done=discovery.list(c,worker,1,null);assertTrue(((List<?>)done.get("candidates")).isEmpty());assertNull(done.get("nextCursor"),"a historical OWNER_EXCEPTION must not rescan a current PREPARE head");}
    }
    void assertCounts(String workflow,String task)throws Exception{
        assertEquals(workflow,scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant()));
        assertEquals(task,scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from opportunity.contract_preparation_source where tenant_id=?",seed.tenant()));
    }
    @Test void accepted_quote_start_context_uses_exact_response_digest_selector()throws Exception{
        initializeRecovery(true);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(next()).status());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_READ");return null;});}
        Map<String,Object> before;try(var c=database.apiConnection()){before=inTransaction(c,Capability.QUERY,x->contracts.context(x,seed.request().actor(),opportunity.id()));}
        var payload=new LinkedHashMap<String,Object>();payload.put("opportunityId",opportunity.id().toString());payload.put("expectedOpportunityRevision",((Map<?,?>)before.get("opportunity")).get("revision"));payload.put("responsibilityBasis",before.get("responsibilityBasis"));payload.put("customerConfirmation",before.get("customerConfirmation"));
        for(var pair:List.of(new String[]{"contract","expectedContract"},new String[]{"draft","expectedDraft"},new String[]{"version","expectedVersion"},new String[]{"workflow","expectedWorkflow"})){var value=(Map<?,?>)before.get(pair[0]);payload.put(pair[1],value==null?null:value.get("selector"));}payload.put("values",Map.of());
        var result=execute(new CommandEnvelope(CommandEnvelope.Type.START_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
        Map<String,Object> after;try(var c=database.apiConnection()){after=inTransaction(c,Capability.QUERY,x->contracts.context(x,seed.request().actor(),opportunity.id()));}
        var source=(Map<?,?>)((Map<?,?>)after.get("contract")).get("source");var selector=(Map<?,?>)source.get("selector");assertNotNull(selector.get("hash"));assertFalse(selector.containsKey("revision"));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(scalar("select encode(response_content_digest,'hex') from opportunity.quote_response where tenant_id=? and quote_response_id=?",seed.tenant(),UUID.fromString((String)selector.get("id"))))),selector.get("hash"));
    }
}
