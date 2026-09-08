package io.github.windyzhu3.ontologylaw.worker;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.R1ProductionFixture;
import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeRoleIT extends R1ProductionFixture {
    @TempDir Path directory;
    @Test void production_worker_is_not_ready_before_all_remote_loops_have_succeeded()throws Exception {
        setupContact();var deployment=deployment(directory);int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}deployment.worker().put("ols.worker.api-origin","https://localhost:"+port);
        try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
            assertFalse(context.getBean(WorkerRuntimeHealth.class).healthy());assertEquals(org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC,context.getBean(org.springframework.boot.availability.ApplicationAvailability.class).getReadinessState());
        }
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","UNKNOWN","CONFLICT","API_NONE","WORKER_SERVLET","API_MISSING_TRUST","WORKER_MISSING_TRUST"})
    void same_jar_rejects_ambiguous_roles_and_incomplete_production_configuration(String defect)throws Exception {
        var settings=new HashMap<String,Object>();settings.put("spring.main.banner-mode","off");settings.put("logging.level.root","OFF");settings.put("server.port","0");
        if(!defect.equals("MISSING"))settings.put("ols.runtime-role",defect.equals("UNKNOWN")?"both":defect.startsWith("WORKER")?"worker":"api");
        if(defect.equals("API_NONE"))settings.put("spring.main.web-application-type","none");if(defect.equals("WORKER_SERVLET"))settings.put("spring.main.web-application-type","servlet");
        var log=directory.resolve("negative-"+defect+".log");var configuration=config("negative-"+defect,settings);var builder=processBuilder(configuration,log);builder.environment().remove("OLS_RUNTIME_ROLE");if(defect.equals("CONFLICT"))builder.environment().put("OLS_RUNTIME_ROLE","worker");
        var process=builder.start();try{assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Invalid same-Jar role/configuration did not fail closed: "+defect);assertNotEquals(0,process.exitValue(),defect);String output=Files.readString(log);assertFalse(output.contains("R1_API_READY"));assertFalse(output.contains("R1_WORKER_READY"));}finally{if(process.isAlive())stop(process);}
    }
    @Test void real_worker_context_close_stops_owned_schedulers_without_an_http_shutdown_protocol()throws Exception {
        setupContact();var deployment=deployment(directory);int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}String origin="https://localhost:"+port;
        deployment.api().put("server.port",Integer.toString(port));deployment.worker().put("ols.worker.api-origin",origin);
        var evidence=Path.of("target","runtime-role-evidence",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(evidence);var api=process(config("api-close-proof",deployment.api()),evidence.resolve("api.log"));
        try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).connectTimeout(Duration.ofSeconds(2)).build()) {
            boolean ready=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<until&&api.isAlive()){try{if(client.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/workcards/current")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200){ready=true;break;}}catch(java.io.IOException unavailable){}Thread.sleep(100);}assertTrue(ready,"API child unavailable; evidence="+evidence);
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
                var health=context.getBean(WorkerRuntimeHealth.class);until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until&&!health.healthy())Thread.sleep(100);assertTrue(health.healthy());assertTrue(health.snapshot().projection());assertTrue(health.snapshot().contactRecovery());assertTrue(health.snapshot().routingRecovery());
                assertReadiness(context,org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
                assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.api.R1ApiServices.class).isEmpty());assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.class).isEmpty());assertFalse(context.getClass().getName().contains("WebServerApplicationContext"));
                try(var c=context.getBean(R1WorkerDeployment.class).database.open();var statement=c.createStatement();var row=statement.executeQuery("select current_user,session_user")){assertTrue(row.next());assertEquals("law_worker_login",row.getString(1));assertEquals(row.getString(1),row.getString(2));}
                var gatedCounts=counts();try(var c=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update platform_meta.deployment_state set operating_mode='BLOCKED',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}assertFalse(health.snapshot().database());assertFalse(health.healthy());Thread.sleep(1200);assertEquals(gatedCounts,counts());
                assertReadiness(context,org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
                try(var c=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update platform_meta.deployment_state set operating_mode='ACTIVE',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until&&!health.healthy())Thread.sleep(100);assertTrue(health.healthy());assertTrue(health.snapshot().projection());assertTrue(health.snapshot().contactRecovery());assertTrue(health.snapshot().routingRecovery());
                assertReadiness(context,org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
                var availability=context.getBean(org.springframework.boot.availability.ApplicationAvailability.class);
                var before=counts();assertTimeout(Duration.ofSeconds(10),context::close);assertFalse(health.isRunning());assertFalse(health.healthy());assertEquals(org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC,availability.getReadinessState());until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until&&ownedThreads())Thread.sleep(25);assertFalse(ownedThreads(),"Owned Worker lifecycle threads survived Context.close");Thread.sleep(1100);assertEquals(before,counts());
            }
        }finally{stop(api);System.out.println("R1_ROLE_CLOSE_EVIDENCE="+evidence);}
    }
    private static boolean ownedThreads(){return Thread.getAllStackTraces().keySet().stream().anyMatch(thread->thread.isAlive()&&(thread.getName().equals("r1-worker-health")||thread.getName().startsWith("r1-projection-")||thread.getName().startsWith("r1-due-")));}
    private static void assertReadiness(org.springframework.context.ApplicationContext context,org.springframework.boot.availability.ReadinessState expected)throws Exception {
        var availability=context.getBean(org.springframework.boot.availability.ApplicationAvailability.class);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until&&availability.getReadinessState()!=expected)Thread.sleep(25);
        assertEquals(expected,availability.getReadinessState());
    }
    @Test void same_exact_jar_runs_api_and_worker_and_worker_starts_all_required_loops()throws Exception {
        setupContact();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());selectTask(TaskFactory.Type.CONTACT_LEAD);UUID contactTask=current.selector().id();
        var routing=addTask(UUID.randomUUID(),businessAt.minusSeconds(60),businessAt.plusSeconds(3600),"WAITING");
        var deployment=deployment(directory);int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}String origin="https://localhost:"+port;
        deployment.api().put("server.port",Integer.toString(port));deployment.worker().put("ols.worker.api-origin",origin);deployment.worker().put("server.port","0");
        deployment.worker().put("logging.level.root","ERROR");deployment.worker().put("logging.level.io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth","INFO");deployment.api().put("logging.level.io.github.windyzhu3.ontologylaw.api.ApiRuntimeHealth","INFO");
        var evidence=Path.of("target","runtime-role-evidence",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(evidence);
        var apiConfig=config("api",deployment.api());var workerConfig=config("worker",deployment.worker());
        try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).connectTimeout(Duration.ofSeconds(2)).build()) {
            var api=process(apiConfig,evidence.resolve("api.log"));Process worker=null;
            try {
                boolean apiReady=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(System.nanoTime()<until&&api.isAlive()) {try{var response=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/workcards/current")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()==200){apiReady=true;break;}}catch(java.io.IOException unavailable){}Thread.sleep(100);}
                assertTrue(apiReady,"API Jar did not become ready; evidence="+evidence);
                worker=process(workerConfig,evidence.resolve("worker.log"));boolean completed=false;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(System.nanoTime()<until&&worker.isAlive()) {
                    if("2".equals(scalar("select count(*)::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id in (?,?) and state='OPEN'",seed.tenant(),contactTask,routing.selector().id()))&&"3".equals(scalar("select count(*)::text from execution.domain_event_outbox where tenant_id=? and status='DELIVERED'",seed.tenant()))&&Files.readString(evidence.resolve("worker.log")).contains("R1_WORKER_READY")){completed=true;break;}Thread.sleep(100);
                }
                assertTrue(completed,"Same-Jar Worker did not run all three production loops; exit="+(worker.isAlive()?"RUNNING":worker.exitValue())+"; evidence="+evidence);
                assertTrue(api.isAlive());assertTrue(worker.isAlive());
                assertEquals("3",scalar("select count(*)::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*)::text from execution.domain_event_outbox where tenant_id=? and status<>'DELIVERED'",seed.tenant()));
                assertTrue(Files.readString(evidence.resolve("api.log")).contains("R1_API_ASSEMBLY_ISOLATED"));assertTrue(Files.readString(evidence.resolve("worker.log")).contains("R1_WORKER_ASSEMBLY_ISOLATED"));
                var stable=counts();Thread.sleep(1200);assertTrue(worker.isAlive());assertEquals(stable,counts());
            }finally{if(worker!=null)stop(worker);stop(api);System.out.println("R1_ROLE_PROCESS_EVIDENCE="+evidence);}
        }
    }
    private Path config(String name,Map<String,Object> settings)throws Exception {var result=directory.resolve(name+".properties");var properties=new Properties();settings.forEach((key,value)->properties.setProperty(key,value.toString()));try(var output=Files.newOutputStream(result)){properties.store(output,"test-only deployment");}return result;}
    private Process process(Path configuration,Path log)throws Exception {return processBuilder(configuration,log).start();}
    private ProcessBuilder processBuilder(Path configuration,Path log){var executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");var jar=Path.of("target","ontology-law-system-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar));return new ProcessBuilder(executable.toString(),"-jar",jar.toString(),"--spring.config.location="+configuration.toUri()).redirectErrorStream(true).redirectOutput(log.toFile());}
    private static void stop(Process process)throws Exception {var descendants=process.descendants().toList();process.destroy();boolean stopped=process.waitFor(10,TimeUnit.SECONDS);if(!stopped){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}assertTrue(stopped,"Normal runtime process shutdown timed out; forced cleanup is not successful lifecycle evidence");assertFalse(process.isAlive());assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive));}
    @ParameterizedTest @ValueSource(strings={"api","worker"})
    void production_assembly_cannot_start_without_deployment_trust_registry_and_expected_gate(String role) {
        assertThrows(Exception.class,()->{
            try(var unexpected=new SpringApplicationBuilder(OntologyLawApplication.class).properties("ols.runtime-role="+role,"server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run()) {
                // Scope includes resource cleanup even when the baseline incorrectly starts.
            }
        });
    }
}
