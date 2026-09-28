package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.api.R1ProductionFixture;
import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Production configuration, actual TLS transport and database; opt-in is confined to this test. */
class OwnerExceptionWorkerAssemblyIT extends R1ProductionFixture {
    @TempDir Path directory;

    @Test @Timeout(150)
    void enabled_production_loop_observes_owner_exception_and_loses_readiness_on_scope_revocation() throws Exception {
        setupContact();
        var completed=execute(prepare(contact("CONNECTED_VALID")));
        assertEquals(CommandOutcome.Status.SUCCEEDED,completed.status());
        UUID opportunity;
        try(var c=database.apiConnection()) {
            opportunity=inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector().id());
        }
        assertEquals("0",scalar("select count(*)::text from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER' and state='ACTIVE'",seed.tenant(),seed.appointment()));
        var deployment=deployment(directory);
        UUID discoveryGrant=UUID.randomUUID();
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'OPPORTUNITY_OWNER_EXCEPTION_DISCOVER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),discoveryGrant,deployment.service().appointmentId(),seed.appointment(),seed.org());
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        String origin="https://localhost:"+port;
        deployment.api().put("server.port",Integer.toString(port));
        deployment.worker().put("ols.worker.api-origin",origin);
        deployment.worker().put("ols.worker.owner-exception-observation-enabled","true");
        var evidence=Path.of("target","owner-exception-worker-evidence",UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(evidence);
        var config=directory.resolve("api.properties");var properties=new Properties();
        deployment.api().forEach((key,value)->properties.setProperty(key,value.toString()));
        try(var output=Files.newOutputStream(config)){properties.store(output,"test-only deployment");}
        var executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var jar=Path.of("target","ontology-law-system-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        var api=new ProcessBuilder(executable.toString(),"-Xms32m","-Xmx384m","-XX:ActiveProcessorCount=2","-XX:+UseSerialGC","-jar",jar.toString(),"--spring.config.location="+config.toUri()).redirectErrorStream(true).redirectOutput(evidence.resolve("api.log").toFile()).start();
        try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).connectTimeout(Duration.ofSeconds(2)).build()) {
            boolean ready=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<until&&api.isAlive()) {
                try {if(client.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/workcards/current")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200){ready=true;break;}}
                catch(java.io.IOException unavailable) { }
                Thread.sleep(100);
            }
            assertTrue(ready,"Production API unavailable; evidence="+evidence);
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
                var health=context.getBean(WorkerRuntimeHealth.class);
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                boolean observed=false;
                while(System.nanoTime()<until) {
                    if("1".equals(activeExceptions(opportunity))&&"1".equals(checkpoints(deployment.service().appointmentId(),"OWNER_EXCEPTION"))&&health.healthy()){observed=true;break;}
                    Thread.sleep(100);
                }
                assertTrue(observed,"Real observation loop did not complete: "+health.snapshot()+"; evidence="+evidence);
                assertTrue(health.snapshot().ownerExceptionEnabled());assertTrue(health.snapshot().ownerExceptionObservation());
                assertEquals("0",checkpoints(deployment.service().appointmentId(),"INITIAL"));assertEquals("0",checkpoints(deployment.service().appointmentId(),"DUE"));
                assertFalse(context.getClass().getName().contains("WebServerApplicationContext"));
                assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.api.R1ApiServices.class).isEmpty());
                try(var c=context.getBean(R1WorkerDeployment.class).database.open();var q=c.createStatement();var row=q.executeQuery("select current_user,session_user")){assertTrue(row.next());assertEquals("law_worker_login",row.getString(1));assertEquals(row.getString(1),row.getString(2));}
                mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),discoveryGrant);
                // A completed owner-exception scan deliberately cools down for60s.
                // Observe the next real production scan; do not disable that throttle.
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);
                while(System.nanoTime()<until&&health.snapshot().ownerExceptionObservation())Thread.sleep(100);
                assertTrue(health.snapshot().ownerExceptionEnabled());assertFalse(health.snapshot().ownerExceptionObservation());assertFalse(health.healthy());
                assertEquals("1",activeExceptions(opportunity));
                assertEquals("0",checkpoints(deployment.service().appointmentId(),"INITIAL"));assertEquals("0",checkpoints(deployment.service().appointmentId(),"DUE"));
                assertTimeout(Duration.ofSeconds(10),context::close);assertFalse(health.isRunning());
            }
        } finally {
            api.destroy();boolean stopped=api.waitFor(10,TimeUnit.SECONDS);
            if(!stopped){api.destroyForcibly();api.waitFor(5,TimeUnit.SECONDS);}
            assertTrue(stopped,"API did not stop normally; evidence="+evidence);
            System.out.println("T01_WORKER_ASSEMBLY_EVIDENCE="+evidence);
        }
    }
    private String activeExceptions(UUID opportunity)throws Exception {
        return scalar("select count(*)::text from opportunity.owner_exception where tenant_id=? and opportunity_id=? and is_current and state='ACTIVE'",seed.tenant(),opportunity);
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
}
