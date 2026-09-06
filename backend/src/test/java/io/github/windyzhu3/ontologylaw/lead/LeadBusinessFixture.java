package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.Seed;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.util.*;

abstract class LeadBusinessFixture extends PostgresIntegrationTest {
    Seed seed;
    CommandRuntime runtime;
    R1SourcePolicyRegistry sources;
    void setup(R1SourcePolicyRegistry.AssignmentMode mode,boolean sales)throws Exception {
        seed=AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(String code:List.of("LEAD_INGRESS_RESOLVE","LEAD_INGRESS_COMPLETE","LEAD_ASSIGN","LEAD_ROUTING_DECIDE","SOURCE_INTAKE_REQUEST_ACK")) grant(x,code);
            if(sales)grant(x,"SALES_CONTACT_OWNER");return null;
        });}
        sources=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(mode,List.of("ROOT"),"ROOT","ROOT","Asia/Shanghai")));
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection()).handlers(),AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("LEAD_IT"),R1AuthorizationReaders.databaseBacked(sources),R1EventReaders.databaseBacked());
    }
    void grant(Connection c,String code)throws SQLException {sql(c,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),code);}
    Map<String,Object> captureValues(String key,boolean contact) {
        var p=new TreeMap<String,Object>();p.put("sourceChannelCode","TEST");p.put("sourceAccountCode","FIXTURE");p.put("sourceRecordKey",key);p.put("capturedAt","2026-09-04T09:00:00.000000Z");
        p.put("capturedName","Synthetic contact");p.put("serviceCategoryCode","CONSULTATION");p.put("jurisdictionCode","CN");p.put("urgencyCode","NORMAL");p.put("legalNeedSummary","Synthetic legal enquiry");if(contact)p.put("phone","+12025550123");return p;
    }
    CommandEnvelope capture(String key,boolean contact) {return new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),captureValues(key,contact));}
    CommandOutcome run(CommandEnvelope e)throws Exception {try(var c=database.apiConnection()){return assertInstanceOf(CommandOutcome.class,assertDoesNotThrow(()->runtime.execute(c,e)));}}
    List<Long> counts()throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var result=new ArrayList<Long>();
            for(String table:List.of("lead.lead","lead.lead_assignment","responsibility.decision_record","responsibility.wait_receipt","responsibility.task_occurrence","execution.command_execution_slot","execution.command_receipt","execution.domain_event","execution.domain_event_outbox","audit.audit_entry_classified_v"))
                try(var p=x.prepareStatement("select count(*) from "+table+" where tenant_id=?")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){r.next();result.add(r.getLong(1));}}
            return result;
        });}
    }
    TaskFactory.Task task(String type)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            try(var p=x.prepareStatement("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and business_purpose_code=? and state in ('OPEN','WAITING') order by created_at desc,task_occurrence_id desc limit 1")){p.setObject(1,seed.tenant());p.setString(2,type);try(var r=p.executeQuery()){assertTrue(r.next());return TaskFactory.databaseBacked().read(x,seed.tenant(),r.getObject(1,UUID.class));}}
        });}
    }
    CommandEnvelope command(TaskFactory.Task task,Map<String,Object> values)throws Exception {
        UUID id=UUID.randomUUID();String json=CanonicalJson.encode(values);byte[] digest=CanonicalJson.digest(json);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into responsibility.action_draft (tenant_id,action_draft_id,task_occurrence_id,action_code,payload_schema_code,payload_schema_version,candidate_payload,candidate_payload_digest,state,created_by_appointment_id,created_at,last_edited_at) values (?,?,?,?,?,1,?::jsonb,?,'DRAFT',?,clock_timestamp(),clock_timestamp())",seed.tenant(),id,task.selector().id(),task.type().command,task.type().schema,json,digest,seed.appointment());return null;
        });}
        var payload=new TreeMap<String,Object>(values);payload.put("draftId",id.toString());payload.put("expectedDraftRevision",0L);payload.put("draftDigest",Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        return new CommandEnvelope(CommandEnvelope.Type.valueOf(task.type().command),UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload,new CommandEnvelope.TaskPrecondition(task.selector().id(),R1ResourceTags.task(seed.request().actor(),task.selector(),task.state())));
    }
    void delta(List<Long> before,List<Long> expected)throws Exception {var after=counts();var actual=new ArrayList<Long>();for(int i=0;i<after.size();i++)actual.add(after.get(i)-before.get(i));assertEquals(expected,actual);}
    void completed(TaskFactory.Task original,CommandOutcome receipt)throws Exception {
        assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var after=TaskFactory.databaseBacked().read(x,seed.tenant(),original.selector().id());assertEquals("DONE",after.state());assertEquals(original.selector().revision()+1,after.selector().revision());assertEquals(receipt.resultFact(),after.completion());return null;});}
    }
    String scalar(String sql,Object... args)throws Exception {try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{try(var p=x.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}});}}
    void mutate(String statement,Object... args)throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,statement,args);return null;});}}
    record ImportedCandidate(UUID lead,long revision,UUID party) {}
    ImportedCandidate resolvedCandidate(String key,Map<String,Object> input)throws Exception {
        var e=new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),input);var outcome=run(e);UUID party=UUID.randomUUID();
        mutate("insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','Synthetic imported Party','ACTIVE')",seed.tenant(),party);
        mutate("update lead.lead set parsed_party_id=?,party_resolution_code='RESOLVED',revision=revision+1 where tenant_id=? and lead_id=?",party,seed.tenant(),outcome.resultFact().id());
        return new ImportedCandidate(outcome.resultFact().id(),outcome.resultFact().revision()+1,party);
    }
    Map<String,Object> duplicateValues(ImportedCandidate candidate,String decision){return Map.of("decisionCode",decision,"candidateLeadId",candidate.lead().toString(),"candidateLeadRevision",candidate.revision(),"partyId",candidate.party().toString(),"partyRevision",0L,"rationaleSummary","Synthetic duplicate reason");}
    void emitted(CommandEnvelope e,CommandOutcome receipt,String event)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            try(var p=x.prepareStatement("select d.event_type,d.source_fact_type,d.source_fact_id,d.source_fact_revision,d.source_fact_hash,d.event_payload::text,o.queue_owner from execution.domain_event d join execution.domain_event_outbox o using(tenant_id,domain_event_id) where d.tenant_id=? and d.command_id=?")){p.setObject(1,seed.tenant());p.setObject(2,e.commandId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(event,r.getString(1));assertEquals(receipt.resultFact().type(),r.getString(2));assertEquals(receipt.resultFact().id(),r.getObject(3,UUID.class));assertEquals(receipt.resultFact().revision(),r.getObject(4,Long.class));assertArrayEquals(receipt.resultFact().hash()==null?null:Base64.getUrlDecoder().decode(receipt.resultFact().hash()),r.getBytes(5));assertEquals("{}",r.getString(6));assertEquals("R1_PROJECTION",r.getString(7));assertFalse(r.next());}}
            try(var p=x.prepareStatement("select result_code from audit.audit_entry_classified_v where tenant_id=? and command_id=?")){p.setObject(1,seed.tenant());p.setObject(2,e.commandId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SUCCEEDED",r.getString(1));assertFalse(r.next());}}
            if(e.taskPrecondition()!=null)try(var p=x.prepareStatement("select state,revision,confirmed_payload_digest=candidate_payload_digest from responsibility.action_draft where tenant_id=? and task_occurrence_id=?")){p.setObject(1,seed.tenant());p.setObject(2,e.taskPrecondition().taskId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("CONFIRMED",r.getString(1));assertEquals(1,r.getLong(2));assertTrue(r.getBoolean(3));}}
            return null;
        });}
    }
    static void awaitBlocked(Connection observer,int pid)throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){try(var p=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")){p.setInt(1,pid);try(var r=p.executeQuery()){r.next();if(r.getBoolean(1))return;}}Thread.onSpinWait();}fail("Expected blocked command");}
    AuthorizationService.Actor actor(String kind,String authority)throws Exception {
        return actorAt(kind,authority,seed.org(),java.time.Instant.now().minusSeconds(86400));
    }
    AuthorizationService.Actor actorAt(String kind,String authority,UUID organization,java.time.Instant startsAt)throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,'FIXTURE',?,'Synthetic actor','ACTIVE',clock_timestamp())",seed.tenant(),principal,kind,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',?,'ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,organization,startsAt.atOffset(java.time.ZoneOffset.UTC));
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);
        return new AuthorizationService.Actor(seed.tenant(),principal,appointment,null,null,AuthorizationService.PrincipalKind.valueOf(kind));
    }
    void rejectedBefore(CommandEnvelope e,String code)throws Exception {
        var before=counts();try(var c=database.apiConnection()){assertEquals(code,assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e)).code());}assertEquals(before,counts());
    }
    void terminal(CommandEnvelope e,String code)throws Exception {
        var before=counts();var outcome=run(e);assertEquals(CommandOutcome.Status.REJECTED,outcome.status());assertEquals(code,outcome.rejectionCode());delta(before,List.of(0L,0L,0L,0L,0L,1L,1L,0L,0L,1L));var after=counts();assertEquals(outcome,run(e));assertEquals(after,counts());
    }
    CommandEnvelope payload(CommandEnvelope e,Map<String,Object> values){return new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),values,e.taskPrecondition());}
    List<String> businessSnapshot()throws Exception {
        var result=new ArrayList<String>();for(String table:List.of("lead.lead","lead.lead_assignment","responsibility.task_occurrence","responsibility.action_draft","responsibility.decision_record","responsibility.wait_receipt"))result.add(scalar("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where tenant_id=?",seed.tenant()));return result;
    }
    /** Fault is in real database storage; the production Handler, Owner ports and runtime stay intact. */
    void storageFailureRollsBack(CommandEnvelope e,String table)throws Exception {
        assertTrue(Set.of("lead.lead","responsibility.task_occurrence","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry").contains(table));
        var before=counts();var business=businessSnapshot();
        try(var admin=database.adminConnection();var s=admin.createStatement()){
            s.execute("create function public.task3_test_fail() returns trigger language plpgsql as 'begin raise exception ''Synthetic storage failure'' using errcode=''XX000''; end'");
            try {
                s.execute("create trigger task3_test_failure before insert or update on "+table+" for each row execute function public.task3_test_fail()");
                try(var c=database.apiConnection()){var failure=assertThrows(Exception.class,()->runtime.execute(c,e));boolean injected=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().contains("Synthetic storage failure"))injected=true;assertTrue(injected,"The selected storage boundary must be reached");}
            }finally{s.execute("drop trigger if exists task3_test_failure on "+table);s.execute("drop function public.task3_test_fail()");}
        }
        assertEquals(before,counts());assertEquals(business,businessSnapshot());
    }
}
