package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.ContractAuthorityCandidates;
import java.sql.*;import java.util.*;
import org.junit.jupiter.api.Test;

class ContractAuthorityRecoveryIT extends R2ContractCommandIT {
    @Test void loss_of_contract_prepare_is_discovered_even_when_sales_authority_remains()throws Exception {
        initializeContract();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,Map.of("decision","APPROVED","reason","合成授权"))).status());
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.START_CONTRACT_PREPARATION,Map.of())).status());
        UUID prior=UUID.fromString(scalar("select task_id from contract.preparation_workflow where tenant_id=? and stage_code='PREPARE'",seed.tenant()));
        var deadline=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),prior);
        var worker=service("CONTRACT_TASK_RECOVER");var discovery=new ContractPreparationDiscovery(contracts,new byte[32]);
        try(var c=database.apiConnection()){assertTrue(((List<?>)discovery.list(c,worker,50,null).get("candidates")).isEmpty());}
        rejectForgedRecovery("PREPARE","contract authority recovery basis differs");
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_PREPARE'",seed.tenant());
        assertNotEquals("0",scalar("select count(*) from identity.authority_grant where tenant_id=? and authority_code='SALES_OPPORTUNITY_OWNER' and state='ACTIVE'",seed.tenant()));
        try(var c=database.apiConnection()){
            var rows=(List<?>)discovery.list(c,worker,50,null).get("candidates");
            assertEquals(1,rows.size(),"An active ordinary sales grant cannot hide a lost contract preparation responsibility");
            assertEquals("AUTHORITY_RETURN",((Map<?,?>)rows.getFirst()).get("sourceKind"));
            var command=recoveryCommand(worker,(Map<?,?>)rows.getFirst());var first=execute(command);
            assertEquals(CommandOutcome.Status.SUCCEEDED,first.status(),first.rejectionCode());assertEquals(first,execute(command));
            assertEquals("OWNER_EXCEPTION",scalar("select stage_code from contract.preparation_workflow where tenant_id=? and preparation_workflow_id=?",seed.tenant(),first.resultFact().id()));
            assertEquals("PREPARE",scalar("select recovery_resume_stage from contract.preparation_workflow where tenant_id=? and preparation_workflow_id=?",seed.tenant(),first.resultFact().id()));
            assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),prior));
            // RETURNED shares PREPARE_CONTRACT's purpose, but is not this workflow's
            // exact suspended stage; both purpose and immediate lineage are required.
            rejectForgedRecovery("RETURNED","contract authority recovery predecessor differs");
            rejectForgedRecovery("SUBMIT_APPROVAL","contract authority recovery basis differs");
            assertTrue(((List<?>)discovery.list(c,worker,50,null).get("candidates")).isEmpty(),"Unqualified owner does not create repeated recovery commands");
            inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
            var team=new R2TeamManagementReadService(new byte[32],protection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("R25_NO_TASK_EXCEPTION"));
            var exceptions=(List<?>)team.list(c,seed.request().actor(),"exceptions",20,null,"",null).get("items");
            assertEquals(1,exceptions.size(),"The original workflow exception is visible without a placeholder task");
            UUID exceptionId=UUID.fromString((String)((Map<?,?>)exceptions.getFirst()).get("id"));
            assertEquals(first.resultFact().id(),exceptionId);
            var detail=team.detail(c,seed.request().actor(),"exceptions",exceptionId);
            assertNull(detail.get("taskId"));assertNull(detail.get("exceptionId"));assertNull(detail.get("action"));
            assertTrue(((List<?>)detail.get("facts")).stream().anyMatch(v->v.toString().contains("合同准备")));
            var failing=new R2TeamManagementReadService(new byte[32],protection,(tx,entry)->{});
            assertThrows(SQLException.class,()->failing.detail(c,seed.request().actor(),"exceptions",exceptionId));
            inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_PREPARE");return null;});
            var restored=(List<?>)discovery.list(c,worker,50,null).get("candidates");assertEquals(1,restored.size());
            var result=execute(recoveryCommand(worker,(Map<?,?>)restored.getFirst()));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
            assertEquals("PREPARE",scalar("select stage_code from contract.preparation_workflow where tenant_id=? and preparation_workflow_id=?",seed.tenant(),result.resultFact().id()));
            assertTrue(((List<?>)team.list(c,seed.request().actor(),"exceptions",20,null,"",null).get("items")).isEmpty());
            assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->team.detail(c,seed.request().actor(),"exceptions",exceptionId));
            assertEquals(deadline,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='OPEN' and business_purpose_code='PREPARE_CONTRACT'",seed.tenant(),opportunity.id()));
            assertTrue(((List<?>)discovery.list(c,worker,50,null).get("candidates")).isEmpty());
        }
    }
    private void rejectForgedRecovery(String resume,String message)throws Exception {
        var before=scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant());
        try(var connection=database.apiConnection()) {
            var failure=assertThrows(SQLException.class,()->inTransaction(connection,Capability.COMMAND,c->{
                write(c,"insert into contract.preparation_workflow(tenant_id,preparation_workflow_id,revision,opportunity_id,contract_id,previous_workflow_id,stage_code,owner_appointment_id,task_id,prior_task_id,created_by_appointment_id,recovery_resume_stage,created_at) select w.tenant_id,?,0,w.opportunity_id,w.contract_id,w.preparation_workflow_id,'OWNER_EXCEPTION',w.owner_appointment_id,null,coalesce(w.task_id,w.prior_task_id),w.created_by_appointment_id,?,clock_timestamp() from contract.preparation_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from contract.preparation_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.preparation_workflow_id)",UUID.randomUUID(),resume,seed.tenant(),opportunity.id());return null;
            }));
            assertEquals("23514",failure.getSQLState());assertTrue(failure.getMessage().contains(message),failure.getMessage());
        }
        assertEquals(before,scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant()));
    }
    private CommandEnvelope recoveryCommand(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor worker,Map<?,?> row){
        var body=new LinkedHashMap<String,Object>();row.forEach((k,v)->body.put((String)k,v));body.remove("kind");UUID key=UUID.fromString((String)body.remove("idempotencyKey"));
        return new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,body);
    }
    @Test void independent_contract_candidates_page_past_one_hundred_without_sales_authority()throws Exception{
        initializeContract();var expected=new HashSet<UUID>();var excluded=new HashSet<UUID>();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(int i=0;i<104;i++){
                UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();boolean valid=i<101;
                String authority=i==101?"CONTRACT_REVIEW":"CONTRACT_APPROVE";
                write(x,"insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','CONTRACT_TEST',?,'Synthetic independent reviewer','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
                write(x,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTRACT_TEST',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
                write(x,"insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day',"+(i==102?"clock_timestamp()-interval '1 second'":"null")+",'ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);
                if(i==103)write(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),appointment);
                (valid?expected:excluded).add(appointment);
            }return null;
        });}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=ContractAuthorityCandidates.databaseBacked();var first=reader.appointments(x,seed.tenant(),"CONTRACT_APPROVE",null,100);assertEquals(100,first.size());var second=reader.appointments(x,seed.tenant(),"CONTRACT_APPROVE",first.getLast(),100);assertFalse(second.isEmpty());assertTrue(Collections.disjoint(first,second));
            var actual=new HashSet<>(new ContractWorkflowPorts(cipher,null).eligible(x,seed.tenant(),seed.org(),contracts.protectedFacts(x,seed.tenant(),opportunity.id()),"CONTRACT_APPROVE"));assertEquals(expected,actual);assertTrue(Collections.disjoint(actual,excluded));return null;
        });}
        assertEquals("0",scalar("select count(*) from identity.authority_grant where tenant_id=? and authority_code='SALES_OPPORTUNITY_OWNER' and grantee_appointment_id=?",seed.tenant(),expected.iterator().next()));
    }
    @Test void lost_independent_authority_returns_sales_task_with_runtime_receipt_replay_and_no_fake_decision()throws Exception{
        initializeContract();var requestResult=execute(request());assertEquals(CommandOutcome.Status.SUCCEEDED,requestResult.status());
        UUID priorTask=UUID.fromString(scalar("select task_id::text from contract.preparation_workflow where tenant_id=? and stage_code='DIRECT_REVIEW'",seed.tenant()));
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_PREPARATION_DECIDE'",seed.tenant());
        var worker=service("CONTRACT_TASK_RECOVER");var discovery=new ContractPreparationDiscovery(contracts,new byte[32]);Map<String,Object> page;
        try(var c=database.apiConnection()){page=discovery.list(c,worker,50,null);}var rows=(List<?>)page.get("candidates");assertEquals(1,rows.size());
        var body=new LinkedHashMap<String,Object>();((Map<?,?>)rows.getFirst()).forEach((k,v)->body.put((String)k,v));assertEquals("AUTHORITY_RETURN",body.get("sourceKind"));assertEquals(body.get("source"),body.get("expectedWorkflow"));body.remove("kind");UUID key=UUID.fromString((String)body.remove("idempotencyKey"));
        var command=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,body);var first=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,first.status(),first.rejectionCode());var countsBefore=counts();assertEquals(first,execute(command));assertEquals(countsBefore,counts());
        assertEquals("0",scalar("select count(*) from contract.preparation_decision where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
        assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),priorTask));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REQUEST_CONTRACT_PREPARATION' and state='OPEN' and owner_appointment_id=?",seed.tenant(),seed.appointment()));
        assertEquals("DIRECT_RETURNED",scalar("select stage_code from contract.preparation_workflow where tenant_id=? and preparation_workflow_id=?",seed.tenant(),first.resultFact().id()));
        try(var c=database.apiConnection()){assertTrue(((List<?>)discovery.list(c,worker,50,null).get("candidates")).isEmpty());}
    }
    private static void write(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
}
