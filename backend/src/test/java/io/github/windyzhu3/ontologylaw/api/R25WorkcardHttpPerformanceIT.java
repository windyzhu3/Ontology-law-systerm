package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.lead.LeadIngressService;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Controlled HTTPS workload on this host; separate from browser and review-environment acceptance. */
@EnabledIfSystemProperty(named="ols.performance.http",matches="true")
class R25WorkcardHttpPerformanceIT extends R1ProductionFixture {
 @TempDir Path directory;
 @Test @Timeout(300) void twenty_tasks_five_concurrent_one_hundred_http_reads_with_live_worker()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()) {inTransaction(c,Capability.COMMAND,x->{
   var leads=LeadIngressService.databaseBacked(protection);var tasks=TaskFactory.databaseBacked();var now=leads.now(x);
   for(int n=1;n<20;n++) {
    var lead=leads.capture(x,seed.tenant(),input(false),CanonicalJson.digest(UUID.randomUUID().toString()),now);
    tasks.create(x,seed.tenant(),TaskFactory.Type.COMPLETE_LEAD_INGRESS,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now);
   }return null;
  });}
  var deployment=deployment(directory);
  int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
  String origin="https://localhost:"+port;
  deployment.api().put("server.port",Integer.toString(port));deployment.worker().put("ols.worker.api-origin",origin);
  var config=directory.resolve("api.properties");var properties=new Properties();
  deployment.api().forEach((key,value)->properties.setProperty(key,value.toString()));
  try(var out=Files.newOutputStream(config)){properties.store(out,"private synthetic performance fixture");}
  var executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
  var jar=Path.of("target","ontology-law-system-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar));
  var evidence=Path.of("target","r25-workcard-http-evidence",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(evidence);
  var api=new ProcessBuilder(executable.toString(),"-Xms32m","-Xmx384m","-XX:ActiveProcessorCount=2","-XX:+UseSerialGC","-jar",jar.toString(),"--spring.config.location="+config.toUri()).redirectErrorStream(true).redirectOutput(evidence.resolve("api.log").toFile()).start();
  try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).build()) {
   var request=HttpRequest.newBuilder(URI.create(origin+"/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).timeout(Duration.ofSeconds(30)).GET().build();
   boolean ready=false;long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
   while(System.nanoTime()<readyUntil&&api.isAlive()) {
    try {if(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200){ready=true;break;}}
    catch(java.io.IOException unavailable) { }
    Thread.sleep(100);
   }
   assertTrue(ready,"Separate API process unavailable; evidence="+evidence);
   try(var worker=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.worker()).run()) {
    var health=worker.getBean(WorkerRuntimeHealth.class);
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
    while(!health.healthy()&&System.nanoTime()<deadline)Thread.sleep(100);
    assertTrue(health.healthy(),"The real worker must remain enabled during the workload");
    for(int n=0;n<5;n++)check(client.send(request,HttpResponse.BodyHandlers.ofString()));
    var durations=new CopyOnWriteArrayList<Long>();
    try(var executor=Executors.newFixedThreadPool(5)) {
     var futures=new ArrayList<Future<?>>();
     for(int n=0;n<100;n++)futures.add(executor.submit(()->{
      long start=System.nanoTime();
      try {var response=client.send(request,HttpResponse.BodyHandlers.ofString());durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));check(response);}
      catch(Exception failure){throw new IllegalStateException("Controlled HTTPS workload failed",failure);}
     }));
     for(var future:futures)future.get(2,TimeUnit.MINUTES);
    }
    var sorted=durations.stream().sorted().toList();assertEquals(100,sorted.size());
    var report=Map.of("tasks",20,"concurrency",5,"samples",100,"p50Ms",sorted.get(49),"p95Ms",sorted.get(94),"maxMs",sorted.getLast(),"workerHealthy",health.healthy(),"environment","isolated fixture on local host; real HTTPS API and Keycloak; separate fixture business database; no browser timing");
    Files.writeString(Path.of("target/r25-workcard-http-performance.json"),CanonicalJson.encode(report));
    System.out.println("R25_WORKCARD_HTTP_PERFORMANCE "+CanonicalJson.encode(report));
    assertTrue(health.healthy());assertTrue(sorted.get(94)<=2000,"Current responsibility HTTPS P95 exceeds 2 seconds");
   }
  } finally {
   api.destroy();if(!api.waitFor(10,TimeUnit.SECONDS)){api.destroyForcibly();assertTrue(api.waitFor(5,TimeUnit.SECONDS));}
  }
 }
 private void check(HttpResponse<String> response){
  assertEquals(200,response.statusCode());assertEquals(20,mapper.readTree(response.body()).path("myTasks").size());
 }
}
