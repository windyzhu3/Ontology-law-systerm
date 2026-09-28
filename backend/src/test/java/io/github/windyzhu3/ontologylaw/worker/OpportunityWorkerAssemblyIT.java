package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.api.R1ProductionFixture;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Actual packaged API, TLS, PostgreSQL and Keycloak; synthetic grants exist only in this fixture. */
class OpportunityWorkerAssemblyIT extends R1ProductionFixture {
    @TempDir Path directory;

    @Test @Timeout(150)
    void normal_cycles_activate_recover_and_restart_without_duplicates_or_exception_observation() throws Exception {
        setupContact();
        var completed=execute(prepare(contact("CONNECTED_VALID")));
        assertEquals(CommandOutcome.Status.SUCCEEDED,completed.status());
        UUID opportunity;
        try(var c=database.apiConnection()) {
            opportunity=inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector().id());
        }
        var originalOpportunity=opportunityFact(opportunity);
        R1EventFacts.Assignment originalAssignment;
        try(var c=database.apiConnection()){originalAssignment=inTransaction(c,Capability.QUERY,x->R1EventReaders.databaseBacked().assignment(x,seed.tenant(),originalOpportunity.assignmentId()));}
        assertEquals(seed.appointment(),originalOpportunity.owner());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return null;});}
        assertEquals("NONE",ledger(opportunity).get("taskState"));assertEquals(false,ledger(opportunity).get("canHandle"));
        var deployment=deployment(directory);
        UUID recoveryGrant=UUID.randomUUID();
        for(String authority:List.of("OPPORTUNITY_TASK_ACTIVATE","OPPORTUNITY_TASK_RECOVER"))
            mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),authority.endsWith("RECOVER")?recoveryGrant:UUID.randomUUID(),deployment.service().appointmentId(),seed.appointment(),seed.org(),authority);
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        String origin="https://localhost:"+port;
        deployment.api().put("server.port",Integer.toString(port));
        deployment.worker().put("ols.worker.api-origin",origin);
        deployment.worker().put("ols.worker.opportunity-task-scheduling-enabled","true");
        var evidence=Path.of("target","opportunity-worker-evidence",UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(evidence);
        var config=directory.resolve("api.properties");var properties=new Properties();
        deployment.api().forEach((key,value)->properties.setProperty(key,value.toString()));
        try(var output=Files.newOutputStream(config)){properties.store(output,"test-only deployment");}
        var executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var jar=Path.of("target","ontology-law-system-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        var api=new ProcessBuilder(executable.toString(),"-Xms32m","-Xmx384m","-XX:ActiveProcessorCount=2","-XX:+UseSerialGC","-jar",jar.toString(),"--spring.config.location="+config.toUri()).redirectErrorStream(true).redirectOutput(evidence.resolve("api.log").toFile()).start();
        try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).connectTimeout(Duration.ofSeconds(2)).build()) {
            await(30,()->{
                if(!api.isAlive())return false;
                try{return client.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/workcards/current")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                catch(java.io.IOException unavailable){return false;}
            },"Production API unavailable; evidence="+evidence);
            UUID initial;
            UUID successor;
            CurrentTaskReader.Task waiting;
            R1EventFacts.Wait wait;
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
                var health=context.getBean(WorkerRuntimeHealth.class);
                await(30,()->"1".equals(taskCount(opportunity))&&health.healthy(),"Initial task was not automatically activated");
                assertTrue(health.snapshot().opportunityTaskSchedulingEnabled());
                assertTrue(health.snapshot().opportunityInitial());assertTrue(health.snapshot().opportunityDue());
                assertFalse(health.snapshot().ownerExceptionEnabled());
                assertCheckpoints(deployment.service().appointmentId());
                initial=UUID.fromString(scalar("select task_occurrence_id::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity));
                try(var c=database.apiConnection()){current=inTransaction(c,Capability.QUERY,x->TaskFactory.databaseBacked().read(x,seed.tenant(),initial));}
                assertEquals("OPEN",current.state());
                var initialLedger=ledger(opportunity);assertEquals("OPEN",initialLedger.get("taskState"));assertEquals(true,initialLedger.get("canHandle"));assertEquals(initial.toString(),((Map<?,?>)initialLedger.get("task")).get("id"));
                // Exercise draft and confirmation through the formal command runtime. The key matches the packaged API fixture.
                byte[] key=new byte[32];Arrays.fill(key,(byte)0x51);
                runtime=io.github.windyzhu3.ontologylaw.api.R2OpportunityCommandRuntime.create(policies,protection,OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(key,"AES")),"T02_ASSEMBLY_IT",ZoneId.of("Asia/Shanghai"));
                var nextCheck=Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                Map<String,Object> values=Map.of("progressTypeCode","PHONE_CONNECTED","progressSummary","Confirmed next follow-up in the production assembly fixture","occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"nextCheckAt",nextCheck.toString());
                var save=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(initial,null,"*"));
                assertEquals(CommandOutcome.Status.SUCCEEDED,execute(save).status());
                CommandEnvelope confirm;
                try(var c=database.apiConnection()){confirm=inTransaction(c,Capability.QUERY,x->{
                    var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),initial);var body=new TreeMap<String,Object>(values);
                    body.put("draftId",draft.selector().id().toString());body.put("expectedDraftRevision",draft.selector().revision());body.put("draftDigest",draft.digest());
                    var taskView=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),initial);
                    return new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,new CommandEnvelope.TaskPrecondition(initial,R1ResourceTags.task(seed.request().actor(),current.selector(),current.state(),taskView.responsibilityBasis())));
                });}
                var progress=execute(confirm);assertEquals(CommandOutcome.Status.SUCCEEDED,progress.status());
                successor=UUID.fromString(scalar("select task_occurrence_id::text from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=?",seed.tenant(),initial));
                assertNotEquals(initial,successor);
                waiting=task(successor);assertEquals("WAITING",waiting.state());assertEquals(seed.appointment(),waiting.owner());
                var progressLedger=ledger(opportunity);assertEquals("WAITING",progressLedger.get("taskState"));assertEquals(false,progressLedger.get("canHandle"));assertEquals(values.get("progressSummary"),((Map<?,?>)progressLedger.get("lastProgress")).get("summary"));
                wait=waitReceipt(successor);assertEquals(nextCheck,wait.resumeDue());
                assertNotNull(wait.selector());assertEquals("R2_OPPORTUNITY_FOLLOWUP_V1",wait.profile());
                await(30,()->"OPEN".equals(task(successor).state())&&health.healthy(),"Due waiting task was not automatically reopened");
                assertRetained(waiting,wait,successor);
                var reopenedLedger=ledger(opportunity);assertEquals("OPEN",reopenedLedger.get("taskState"));assertEquals(true,reopenedLedger.get("canHandle"));assertEquals(successor.toString(),((Map<?,?>)reopenedLedger.get("task")).get("id"));
                assertEquals("DONE",task(initial).state());assertEquals(progress.resultFact(),task(initial).completion());
                assertEquals("2",taskCount(opportunity));assertEquals("1",progressCount(opportunity));
                assertCheckpoints(deployment.service().appointmentId());
                assertTimeout(Duration.ofSeconds(10),context::close);assertFalse(health.isRunning());
            }
            // Same durable checkpoint namespace, a new worker context, and no synthetic manual polling.
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
                var health=context.getBean(WorkerRuntimeHealth.class);
                await(30,health::healthy,"Restarted worker did not resume its persisted checkpoints");
                assertEquals("2",taskCount(opportunity));assertEquals("1",progressCount(opportunity));
                assertRetained(waiting,wait,successor);assertCheckpoints(deployment.service().appointmentId());
                assertEquals(originalOpportunity,opportunityFact(opportunity));
                try(var c=database.apiConnection()){assertEquals(originalAssignment,inTransaction(c,Capability.QUERY,x->R1EventReaders.databaseBacked().assignment(x,seed.tenant(),originalOpportunity.assignmentId())));}
                mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),recoveryGrant);
                await(20,()->!health.snapshot().opportunityDue(),"Recovery revocation did not invalidate readiness");
                assertTrue(health.snapshot().opportunityTaskSchedulingEnabled());assertFalse(health.healthy());
                assertEquals("2",taskCount(opportunity));assertEquals("1",progressCount(opportunity));
                assertCheckpoints(deployment.service().appointmentId());
            }
        } finally {
            api.destroy();boolean stopped=api.waitFor(10,TimeUnit.SECONDS);
            if(!stopped){api.destroyForcibly();api.waitFor(5,TimeUnit.SECONDS);}
            assertTrue(stopped,"API did not stop normally; evidence="+evidence);
            System.out.println("T02_WORKER_ASSEMBLY_EVIDENCE="+evidence);
        }
    }
    private Map<String,Object> ledger(UUID opportunity)throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)0x51);
        try(var c=database.apiConnection()){return new io.github.windyzhu3.ontologylaw.api.R2OpportunityLedgerReadService(new byte[32],protection,OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(key,"AES")),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("T03_T02_ASSEMBLY_IT")).read(c,seed.request().actor(),"detail",opportunity,20,null,null,null);}
    }
    private void assertRetained(CurrentTaskReader.Task before,R1EventFacts.Wait wait,UUID id)throws Exception {
        var after=task(id);assertEquals(id,after.selector().id());assertEquals("OPEN",after.state());
        assertEquals(before.owner(),after.owner());assertEquals(before.slaDueAt(),after.slaDueAt());
        assertEquals(before.slaCode(),after.slaCode());assertEquals(before.slaSeconds(),after.slaSeconds());
        assertEquals(wait,waitReceipt(id));
    }
    private CurrentTaskReader.Task task(UUID id)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),id));}
    }
    private EventOpportunityReader.Opportunity opportunityFact(UUID id)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),id));}
    }
    private R1EventFacts.Wait waitReceipt(UUID id)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),id));}
    }
    private String taskCount(UUID opportunity)throws Exception {
        return scalar("select count(*)::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity);
    }
    private String progressCount(UUID opportunity)throws Exception {
        return scalar("select count(*)::text from opportunity.opportunity_progress where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity);
    }
    private void assertCheckpoints(UUID appointment)throws Exception {
        assertEquals("1",checkpoints(appointment,"INITIAL"));assertEquals("1",checkpoints(appointment,"DUE"));assertEquals("0",checkpoints(appointment,"OWNER_EXCEPTION"));
    }
    private String checkpoints(UUID appointment,String kind)throws Exception {
        try(var c=database.workerConnection()) {
            return inTransaction(c,Capability.WORKER,x->{
                try(var q=x.prepareStatement("select count(*)::text from platform_meta.r2_opportunity_checkpoint where tenant_id=? and appointment_id=? and scan_kind=?")) {
                    q.setObject(1,seed.tenant());q.setObject(2,appointment);q.setString(3,kind);
                    try(var row=q.executeQuery()){assertTrue(row.next());return row.getString(1);}
                }
            });
        }
    }
    @FunctionalInterface private interface Condition {boolean met()throws Exception;}
    private static void await(int seconds,Condition condition,String failure)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<until){if(condition.met())return;Thread.sleep(100);}
        fail(failure);
    }
}
