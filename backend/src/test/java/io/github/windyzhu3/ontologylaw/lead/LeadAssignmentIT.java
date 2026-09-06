package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.concurrent.*;
import java.sql.*;
import java.lang.reflect.*;
class LeadAssignmentIT extends LeadBusinessFixture {
    @Test void manual_assignment_completes_with_exact_assignment_and_one_contact_successor()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("manual",true));var original=task("ASSIGN_LEAD");var before=counts();
        var e=command(original,Map.of("ownerAppointmentId",seed.appointment().toString()));var receipt=run(e);completed(original,receipt);
        delta(before,List.of(0L,1L,0L,0L,1L,1L,1L,1L,1L,1L));assertEquals("lead.lead_assignment",receipt.resultFact().type());assertEquals(0L,receipt.resultFact().revision());assertEquals(seed.appointment(),task("CONTACT_LEAD").owner());assertEquals(receipt,run(e));
        emitted(e,receipt,"LeadAssignedV1");
    }
    @Test void task_deny_committed_while_replay_waits_is_rechecked_without_any_writes()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("replay-deny",true));var original=task("ASSIGN_LEAD");var e=command(original,Map.of("ownerAppointmentId",seed.appointment().toString()));run(e);var before=counts();
        try(var reader=database.apiConnection();var writer=database.apiConnection();var observer=database.apiConnection();var pool=Executors.newSingleThreadExecutor()){
            reader.setAutoCommit(false);R1BusinessFence.databaseBacked().shared(reader,seed.tenant());int pid;try(var p=writer.prepareStatement("select pg_backend_pid()");var r=p.executeQuery()){r.next();pid=r.getInt(1);}
            var started=new CountDownLatch(1);var result=pool.submit(()->{started.countDown();return runtime.execute(writer,e);});
            try{assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlocked(observer,pid);
                mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_ASSIGN','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,1)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),original.selector().id());
            }finally{reader.rollback();}
            assertInstanceOf(CommandHandler.Rejected.class,assertThrows(ExecutionException.class,()->result.get(15,TimeUnit.SECONDS)).getCause());
        }
        assertEquals(before,counts());
    }
    @Test void concurrent_same_key_and_competing_keys_leave_one_assignment_and_one_contact_task()throws Exception {
        for(boolean sameKey:List.of(false,true)){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("race",true));var original=task("ASSIGN_LEAD");var first=command(original,Map.of("ownerAppointmentId",seed.appointment().toString()));var second=sameKey?first:new CommandEnvelope(first.type(),UUID.randomUUID(),UUID.randomUUID(),first.actor(),first.payload(),first.taskPrecondition());var before=counts();
            try(var pool=Executors.newFixedThreadPool(2)){
                var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var results=new ArrayList<Future<CommandOutcome>>();
                for(var e:List.of(first,second))results.add(pool.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));try(var c=database.apiConnection()){return (CommandOutcome)runtime.execute(c,e);}}));
                assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var a=results.get(0).get(20,TimeUnit.SECONDS);var b=results.get(1).get(20,TimeUnit.SECONDS);
                if(sameKey)assertEquals(a,b);else{assertEquals(1,List.of(a,b).stream().filter(r->r.status()==CommandOutcome.Status.SUCCEEDED).count());assertEquals("TASK_ALREADY_COMPLETED",List.of(a,b).stream().filter(r->r.status()==CommandOutcome.Status.REJECTED).findFirst().orElseThrow().rejectionCode());}
            }
            long commands=sameKey?1:2;delta(before,List.of(0L,1L,0L,0L,1L,commands,commands,1L,1L,commands));assertEquals(1L,task("CONTACT_LEAD").lead().revision());
        }
    }
    @Test void candidate_revocation_inactivity_and_exact_deny_reject_without_assignment()throws Exception {
        for(String fault:List.of("REVOKED","INACTIVE","DENY","UNKNOWN")){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var candidate=actor("HUMAN","SALES_CONTACT_OWNER");run(capture("candidate",true));var task=task("ASSIGN_LEAD");var e=command(task,Map.of("ownerAppointmentId",fault.equals("UNKNOWN")?UUID.randomUUID().toString():candidate.appointmentId().toString()));
            if(fault.equals("REVOKED"))mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),candidate.appointmentId());
            if(fault.equals("INACTIVE"))mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),candidate.appointmentId());
            if(fault.equals("DENY"))mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'SALES_CONTACT_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",seed.tenant(),UUID.randomUUID(),candidate.principalId(),seed.appointment(),task.lead().id());
            terminal(e,"STALE_SUBJECT");
        }
    }
    @Test void same_key_conflicts_and_advanced_tag_replay_have_zero_extra_delta()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("conflict",true));var task=task("ASSIGN_LEAD");var e=command(task,Map.of("ownerAppointmentId",seed.appointment().toString()));var receipt=run(e);var before=counts();
        var staleTag=new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),e.payload(),new CommandEnvelope.TaskPrecondition(task.selector().id(),"\"task."+"A".repeat(43)+"\""));assertEquals(receipt,run(staleTag));
        for(String key:List.of("draftDigest","ownerAppointmentId")){var values=new TreeMap<String,Object>((Map<String,Object>)e.payload());values.put(key,key.equals("draftDigest")?"A".repeat(43):UUID.randomUUID().toString());try(var c=database.apiConnection()){var conflict=assertInstanceOf(CommandResult.Conflict.class,runtime.execute(c,payload(e,values)));assertEquals(receipt.receiptId(),conflict.receiptId());}}
        assertEquals(before,counts());
    }
    @Test void foreign_task_and_unrepresented_owner_are_hidden_before_slot()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("tenant",true));var task=task("ASSIGN_LEAD");var e=command(task,Map.of("ownerAppointmentId",seed.appointment().toString()));var other=actor("HUMAN","LEAD_ASSIGN");rejectedBefore(new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),other,e.payload(),e.taskPrecondition()),"NOT_AUTHORIZED");
        var foreign=io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.seed(database);rejectedBefore(new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),foreign.request().actor(),e.payload(),e.taskPrecondition()),"NOT_FOUND");
        assertEquals("0",scalar("select count(*) from execution.command_receipt where tenant_id=?",foreign.tenant()));
    }
    @Test void assignment_rolls_back_all_storage_failures()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);run(capture("rollback",true));var task=task("ASSIGN_LEAD");var e=command(task,Map.of("ownerAppointmentId",seed.appointment().toString()));
        for(String table:List.of("lead.lead","responsibility.task_occurrence","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(e,table);
        completed(task,run(e));
    }
    @Test void identity_change_after_real_fact_writes_rolls_back_before_commit()throws Exception {
        for(String change:List.of("CANDIDATE_GRANT","TASK_DENY")){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var candidate=actor("HUMAN","SALES_CONTACT_OWNER");run(capture("final-check",true));var task=task("ASSIGN_LEAD");var e=command(task,Map.of("ownerAppointmentId",candidate.appointmentId().toString()));var before=counts();var business=businessSnapshot();UUID denyId=UUID.randomUUID();
            try(var c=database.apiConnection();var pool=Executors.newSingleThreadExecutor()){
                var afterFacts=new CountDownLatch(1);var resume=new CountDownLatch(1);var proxy=blockBeforeFinalQuery(c,afterFacts,resume);var future=pool.submit(()->runtime.execute(proxy,e));
                try{assertTrue(afterFacts.await(15,TimeUnit.SECONDS));
                    if(change.equals("CANDIDATE_GRANT"))mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),candidate.appointmentId());
                    else mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_ASSIGN','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,1)",seed.tenant(),denyId,seed.principal(),seed.appointment(),task.selector().id());
                }finally{resume.countDown();}
                var result=assertInstanceOf(CommandOutcome.class,future.get(15,TimeUnit.SECONDS));assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals(change.equals("CANDIDATE_GRANT")?"STALE_SUBJECT":"NOT_AUTHORIZED",result.rejectionCode());
            }
            delta(before,List.of(0L,0L,0L,0L,0L,1L,1L,0L,0L,1L));assertEquals(business,businessSnapshot());
            if(change.equals("TASK_DENY"))try(var c=database.apiConnection()){
                io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->{
                    try(var p=x.prepareStatement("select change_summary::text from audit.audit_entry_classified_v where tenant_id=? and command_id=?")){p.setObject(1,seed.tenant());p.setObject(2,e.commandId());try(var r=p.executeQuery()){assertTrue(r.next());String evidence=r.getString(1);assertTrue(evidence.contains(denyId.toString()),"Audit must retain the exact DENY observed on advanced Task revision");assertTrue(evidence.contains("revision=1"));}}return null;
                });
            }
        }
    }
    /** Observes only the existing role boundary after execute; never substitutes a Handler or Owner. */
    private static Connection blockBeforeFinalQuery(Connection actual,CountDownLatch facts,CountDownLatch resume) {
        int[] queries={0};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{
                Object value=method.invoke(actual,args);
                if(method.getName().equals("createStatement")){Statement statement=(Statement)value;return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                    if(m.getName().equals("execute")&&a!=null&&"SET LOCAL ROLE law_app_query".equals(a[0])&&++queries[0]==3){facts.countDown();if(!resume.await(15,TimeUnit.SECONDS))throw new SQLException("Synthetic final-check timeout");}
                    try{return m.invoke(statement,a);}catch(InvocationTargetException ex){throw ex.getCause();}
                });}
                return value;
            }catch(InvocationTargetException ex){throw ex.getCause();}
        });
    }
    @Test void automatic_owner_order_uses_policy_priority_before_start_then_unsigned_uuid()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);UUID priority=UUID.randomUUID();
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'PRIORITY','Synthetic priority','ACTIVE',clock_timestamp())",seed.tenant(),priority,seed.org());
        var first=actorAt("HUMAN","SALES_CONTACT_OWNER",priority,java.time.Instant.parse("2026-01-01T00:00:00Z"));var tied=actorAt("HUMAN","SALES_CONTACT_OWNER",priority,java.time.Instant.parse("2026-01-01T00:00:00Z"));actorAt("HUMAN","SALES_CONTACT_OWNER",seed.org(),java.time.Instant.parse("2020-01-01T00:00:00Z"));
        sources=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,List.of("PRIORITY","ROOT"),"ROOT","ROOT","Asia/Shanghai")));
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection()).handlers(),io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("LEAD_IT"),R1AuthorizationReaders.databaseBacked(sources),R1EventReaders.databaseBacked());
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(capture("priority",true)).status());UUID expected=first.appointmentId().toString().compareTo(tied.appointmentId().toString())<0?first.appointmentId():tied.appointmentId();assertEquals(expected,task("CONTACT_LEAD").owner());
    }
}
