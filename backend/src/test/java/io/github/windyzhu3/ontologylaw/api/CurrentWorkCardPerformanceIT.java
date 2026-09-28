package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CurrentWorkCardPerformanceIT extends WorkcardTestFixture {
 @Test void source_authorization_uses_one_fresh_clock_per_selected_path()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  var sourceClocks=new java.util.concurrent.atomic.AtomicInteger();var pathClocks=new java.util.concurrent.atomic.AtomicInteger();
  try(var c=database.apiConnection()) {
   var observed=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,args)->{
    if(m.getName().equals("prepareStatement")&&"select clock_timestamp()".equals(args[0])) {
     var stack=StackWalker.getInstance().walk(s->s.toList());
     if(stack.stream().anyMatch(f->f.getClassName().equals(CurrentWorkCardSources.class.getName())&&f.getMethodName().equals("authorized"))) {
      sourceClocks.incrementAndGet();
      if(stack.stream().anyMatch(f->f.getClassName().endsWith("JooqR1AuthorityReader")&&f.getMethodName().equals("choose")))pathClocks.incrementAndGet();
     }
    }
    return ReadConnectionProbe.call(c,m,args);
   });
   var response=new CurrentWorkCardDisclosureService(protection,policies,"ONE_PATH_IT").read(observed,seed.request().actor(),UUID.randomUUID(),null);
   assertEquals(200,response.status());assertTrue(pathClocks.get()>0);
   assertEquals(pathClocks.get(),sourceClocks.get(),"Selecting an allowed path must not immediately repeat the same authorization");
  }
 }
 @Test void twenty_tasks_keep_all_exact_audits_in_one_bounded_write()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);var now=java.time.Instant.now();
  for(int i=0;i<19;i++)addTask(UUID.randomUUID(),now.plusSeconds(i),now.plusSeconds(3600+i),"OPEN");
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);
   var response=new CurrentWorkCardDisclosureService(protection,policies,"READ_BATCH_IT").read(probe.connection(),seed.request().actor(),UUID.randomUUID(),null);
   assertEquals(200,response.status());assertTrue(auditCount()>20,"Every disclosed task and source retains its own persisted audit");
   assertEquals(auditCount(),probe.auditRows.get());
   assertEquals(1,probe.statements.stream().filter(q->q.startsWith("insert into \"audit\".\"audit_entry\"")).count(),"The small workbench disclosure fits one synchronous batch");
  }
 }
 @Test void denial_catalog_is_exact_and_loaded_once_per_locked_request()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  var base=seed.request();var denied=base.subject();
  try(var c=database.apiConnection()) {
   io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,
    io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{
     try(var p=x.prepareStatement("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)")) {
      p.setObject(1,seed.tenant());p.setObject(2,UUID.randomUUID());p.setObject(3,seed.principal());p.setObject(4,seed.appointment());p.setString(5,base.requirement().authorityCode());p.setString(6,denied.type());p.setObject(7,denied.id());p.setObject(8,denied.revision());p.executeUpdate();
     }return null;
    });
   var requests=new ArrayList<AuthorizationService.Request>();requests.add(base);
   requests.add(new AuthorizationService.Request(base.actor(),new AuthorizationService.Subject(denied.type(),denied.id(),1L,null),base.scopeOrganizationId(),base.requirement()));
   for(int n=0;n<20;n++)requests.add(new AuthorizationService.Request(base.actor(),new AuthorizationService.Subject(denied.type(),UUID.randomUUID(),0L,null),base.scopeOrganizationId(),base.requirement()));
   var expected=new ArrayList<AuthorizationSnapshot>();
   io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,
    io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->{for(var r:requests)expected.add(AuthorizationService.databaseBacked().evaluate(x,r,true));return null;});
   assertFalse(expected.getFirst().allowed());assertTrue(expected.stream().skip(1).allMatch(AuthorizationSnapshot::allowed));
   var probe=new ReadConnectionProbe(c);
   for(int run=0;run<2;run++)QuoteReadRuntime.read(probe.connection(),base.actor(),AuditAppender.databaseBacked("DENIAL_BATCH_IT"),(x,now)->{
    for(int i=0;i<requests.size();i++) {
     var actual=AuthorizationService.databaseBacked().evaluate(x,requests.get(i),true);
     assertEquals(expected.get(i).allowed(),actual.allowed());assertEquals(expected.get(i).stableDependencies(),actual.stableDependencies());
    }
    return new QuoteReadRuntime.Prepared<>("ok",List.of());
   });
   assertEquals(2,probe.statements.stream().filter(q->q.contains("from \"identity\".\"object_access_grant\"")).count(),"one raw denial catalog per request, including exact revision separation");
  }
 }
 @Test void complete_projection_is_built_once_under_identity_lock() throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()) {
   var count=new java.util.concurrent.atomic.AtomicInteger();
   var runtime=new SensitiveReadRuntime(AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("PERFORMANCE_IT"));
   var result=runtime.read(c,seed.request().actor(),UUID.randomUUID(),null,(x,now)->{
    count.incrementAndGet();return new SensitiveReadRuntime.Prepared(Map.of(),new DisclosurePlan(List.of(),List.of()));
   });
   assertEquals(200,result.status());assertEquals(1,count.get());
  }
 }
 @Test void locked_identity_facts_are_reused_without_reusing_authorization_decisions() throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);long start=System.nanoTime();
   var result=new CurrentWorkCardDisclosureService(protection,policies,"PERFORMANCE_IT").read(probe.connection(),seed.request().actor(),UUID.randomUUID(),null);
   long tenantReads=probe.statements.stream().filter(s->s.contains("from \"identity\".\"tenant\"")).count();
   System.out.printf("READ_BASELINE totalMs=%.2f sqlMs=%.2f sqlCount=%d tenantReads=%d%n",(System.nanoTime()-start)/1e6,probe.sqlNanos.sum()/1e6,probe.statements.size(),tenantReads);
   assertEquals(200,result.status());assertTrue(tenantReads<=1,"repeated tenant reads: "+tenantReads);
   assertTrue(probe.statements.size()<=60,"repeated grant/denial fact reads: "+probe.statements.size());
  }
 }
 @Test void quote_read_reuses_locked_identity_facts()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);
   QuoteReadRuntime.read(probe.connection(),seed.request().actor(),AuditAppender.databaseBacked("QUOTE_PERFORMANCE_IT"),(x,now)->{
    var auth=AuthorizationService.databaseBacked();assertTrue(auth.evaluate(x,seed.request(),false).allowed());assertTrue(auth.evaluate(x,seed.request(),true).allowed());
    return new QuoteReadRuntime.Prepared<>("ok",List.of());
   });
   assertEquals(1,probe.statements.stream().filter(sql->sql.contains("from \"identity\".\"tenant\"")).count());
  }
 }
 @Test void owner_metadata_reuses_raw_rows_only_within_each_locked_request()throws Exception {
  setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);var connection=probe.connection();
   for(int request=0;request<2;request++)QuoteReadRuntime.read(connection,seed.request().actor(),AuditAppender.databaseBacked("OWNER_READ_PERF"),(x,now)->{
    var first=WorkcardOwnerReader.databaseBacked().read(x,seed.tenant(),seed.appointment());assertNotNull(first);
    assertEquals(first,WorkcardOwnerReader.databaseBacked().read(x,seed.tenant(),seed.appointment()));
    return new QuoteReadRuntime.Prepared<>("ok",List.of());
   });
   assertEquals(2,probe.statements.stream().filter(q->q.contains("from \"identity\".\"appointment\"")).count(),"one Owner fetch in each request, never cross-request reuse");
  }
 }
 @Test void optional_isolated_load_matrix()throws Exception {
  org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("ols.performance.matrix"));
  int samples=Integer.getInteger("ols.performance.samples",100);
  var reports=new ArrayList<Map<String,Object>>();
  for(int count:new int[]{0,20,100}) {
   if(count==0)seed=seedFor(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
   else {
    setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
    try(var c=database.apiConnection()) {
     io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,
      io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{
       var leads=LeadIngressService.databaseBacked(protection);var tasks=TaskFactory.databaseBacked();var now=leads.now(x);
       for(int i=1;i<count;i++) {
        var lead=leads.capture(x,seed.tenant(),input(false),CanonicalJson.digest(UUID.randomUUID().toString()),now);
        tasks.create(x,seed.tenant(),TaskFactory.Type.COMPLETE_LEAD_INGRESS,seed.appointment(),lead.selector(),java.time.ZoneId.of("Asia/Shanghai"),now);
       }return null;
      });
    }
   }
   try(var pool=RuntimeDatabase.jdbc(new RuntimeDatabase.JdbcLogin(database.jdbcUrl(),"law_api_login",database.apiPassword().toCharArray()),12)) {
    var service=new CurrentWorkCardDisclosureService(protection,policies,"LOAD_IT");
    String etag;
    try(var c=pool.open()){var warm=service.read(c,seed.request().actor(),UUID.randomUUID(),null);assertEquals(count,((List<?>)warm.body().get("myTasks")).size());etag=warm.etag();}
    for(int concurrency:new int[]{1,5,10}) {
     var durations=new java.util.concurrent.CopyOnWriteArrayList<Long>();var sqlCounts=new java.util.concurrent.CopyOnWriteArrayList<Integer>();
     long started=System.nanoTime();
     try(var executor=java.util.concurrent.Executors.newFixedThreadPool(concurrency)) {
      var futures=new ArrayList<java.util.concurrent.Future<?>>();
      for(int i=0;i<samples;i++){final int iteration=i;futures.add(executor.submit(()->{
       long begin=System.nanoTime();
       try(var c=pool.open()) {
        var probe=new ReadConnectionProbe(c);
        var result=service.read(probe.connection(),seed.request().actor(),UUID.randomUUID(),iteration%2==0?null:etag);
        assertTrue(result.status()==200||result.status()==304,"status "+result.status());
        durations.add((System.nanoTime()-begin)/1000000);sqlCounts.add(probe.statements.size());
       }catch(Exception e){throw new RuntimeException(e);}
      }));}
      for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.MINUTES);
     }
     var sorted=durations.stream().sorted().toList();
     var report=Map.<String,Object>of("tasks",count,"concurrency",concurrency,"samples",samples,"p50Ms",sorted.get((samples-1)/2),"p95Ms",sorted.get((int)Math.ceil(samples*0.95)-1),"maxMs",sorted.getLast(),"meanSql",Math.round(sqlCounts.stream().mapToInt(Integer::intValue).average().orElse(0)),"elapsedMs",(System.nanoTime()-started)/1000000);
     reports.add(report);System.out.println("PERFORMANCE_MATRIX "+CanonicalJson.encode(report));
     java.nio.file.Files.writeString(java.nio.file.Path.of("target/current-workcard-performance-matrix.json"),CanonicalJson.encode(reports));
    }
   }
  }
 }

}
