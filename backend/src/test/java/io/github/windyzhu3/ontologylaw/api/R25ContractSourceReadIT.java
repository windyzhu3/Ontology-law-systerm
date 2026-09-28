package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R25ContractSourceReadIT extends R2ContractCommandIT {
 @Test void opportunity_responsibility_and_header_are_read_once_inside_the_same_owner_scope()throws Exception {
  initializeContract();var owners=io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked();var commands=io.github.windyzhu3.ontologylaw.opportunity.OpportunityCommandReader.databaseBacked(cipher);
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var identity=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant());var scope=io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.lockedLedgerFacts(tx,seed.tenant())){
     var first=owners.current(tx,seed.tenant(),opportunity);var header=commands.header(tx,seed.tenant(),opportunity.id());
     for(int i=0;i<20;i++){assertEquals(first,owners.current(tx,seed.tenant(),opportunity));assertEquals(header,commands.header(tx,seed.tenant(),opportunity.id()));}
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from opportunity.responsibility_handoff")).count());
     assertEquals(2,probe.statements.stream().filter(q->q.contains("from opportunity.opportunity")||q.contains("from \"opportunity\".\"opportunity\"")).count());
     assertThrows(java.sql.SQLException.class,()->owners.current(tx,java.util.UUID.randomUUID(),opportunity));
    }
    long before=probe.statements.size();owners.current(tx,seed.tenant(),opportunity);commands.header(tx,seed.tenant(),opportunity.id());assertTrue(probe.statements.size()>before);return null;
   });
  }
 }

 @Test void opportunity_customer_sources_are_reused_without_reusing_decryption_or_authority()throws Exception {
  initializeContract();var decryptions=new java.util.concurrent.atomic.AtomicInteger();
  var measuredCipher=(io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection.class},(proxy,method,args)->{
   if(method.getName().equals("decryptCustomerRequirements"))decryptions.incrementAndGet();
   try{return method.invoke(cipher,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });
  var customers=R2CustomerRequirementsServices.create(measuredCipher);var materials=io.github.windyzhu3.ontologylaw.opportunity.OpportunityMaterials.databaseBacked();
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var scope=io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.lockedLedgerFacts(tx,seed.tenant())){
     for(int i=0;i<3;i++){
      var versions=customers.history(tx,seed.tenant(),opportunity.id());assertEquals(1,versions.size());var exact=versions.getFirst().selector();
      assertEquals(versions.getFirst(),io.github.windyzhu3.ontologylaw.opportunity.OpportunityCustomerRequirementsService.metadata(tx,seed.tenant(),exact));
      assertEquals(exact,customers.read(tx,seed.tenant(),exact).metadata().selector());assertFalse(customers.participants(tx,seed.tenant(),exact).isEmpty());
      assertTrue(materials.history(tx,seed.tenant(),opportunity.id()).isEmpty());
     }
     assertEquals(3,decryptions.get(),"Only encrypted source rows are reused; protected data is decoded for each read");
     assertEquals(2,probe.statements.stream().filter(q->q.startsWith("select * from opportunity.customer_requirement_confirmation")).count(),"History and exact raw confirmation each load once");
     assertEquals(1,probe.statements.stream().filter(q->q.startsWith("select customer_requirement_participant_id")).count());
     assertEquals(1,probe.statements.stream().filter(q->q.startsWith("select * from opportunity.material_version")).count());
     assertTrue(customers.history(tx,java.util.UUID.randomUUID(),opportunity.id()).isEmpty());
     assertThrows(java.sql.SQLException.class,()->customers.save(tx,seed.tenant(),null,null,java.util.List.of()));
    }
    long before=probe.statements.size();assertEquals(1,customers.history(tx,seed.tenant(),opportunity.id()).size());assertTrue(probe.statements.size()>before);return null;
   });
  }
 }

 @Test void recovery_scan_bounds_raw_work_and_preserves_empty_page_continuation()throws Exception {
  initializeContract();var worker=service("CONTRACT_TASK_RECOVER");var last=java.util.UUID.randomUUID();var calls=new java.util.concurrent.atomic.AtomicInteger();
  var delegated=(io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.class},(proxy,method,args)->{
   if(method.getName().equals("recoveryPage")){
    assertEquals(10,args[2],"A client maximum does not enlarge one recovery transaction");
    if(calls.getAndIncrement()==0){assertNull(args[3]);return new io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.RecoveryPage(java.util.List.of(),last,false);}
    assertEquals(last,args[3],"Continue from the original raw position even without a candidate");return new io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.RecoveryPage(java.util.List.of(),last,true);
   }
   try{return method.invoke(contracts,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });
  try(var c=database.apiConnection()){
   var reader=new ContractPreparationDiscovery(delegated,new byte[32]);var first=reader.list(c,worker,50,null);assertTrue(((java.util.List<?>)first.get("candidates")).isEmpty());assertNotNull(first.get("nextCursor"));
   var second=reader.list(c,worker,50,(String)first.get("nextCursor"));assertNull(second.get("nextCursor"));assertEquals(2,calls.get());
  }
 }
 @Test void candidate_batches_require_exact_owner_connection_tenant_and_live_scope()throws Exception {
  initializeContract();var ids=java.util.List.of(opportunity.id());
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var scope=contracts.lockedLedgerFacts(tx,seed.tenant());var quote=io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.lockedLedgerFacts(tx,seed.tenant())){
     contracts.prepareLedgerCandidates(tx,seed.tenant(),ids,20);io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.prepareLedgerCandidates(tx,seed.tenant(),ids,20);
     assertThrows(java.sql.SQLException.class,()->R2QuoteServices.create(cipher).execute(tx,"FORM_QUOTE",seed.request().actor(),java.util.Map.of()));
     assertThrows(java.sql.SQLException.class,()->contracts.prepareLedgerCandidates(tx,java.util.UUID.randomUUID(),ids,20));
     assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.prepareLedgerCandidates(tx,java.util.UUID.randomUUID(),ids,20));
     assertThrows(IllegalArgumentException.class,()->contracts.prepareLedgerCandidates(tx,seed.tenant(),java.util.List.of(opportunity.id(),opportunity.id()),20));
     assertThrows(IllegalArgumentException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.prepareLedgerCandidates(tx,seed.tenant(),ids,21));
     try(var other=database.apiConnection()){
      assertThrows(java.sql.SQLException.class,()->contracts.prepareLedgerCandidates(other,seed.tenant(),ids,20));
      assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.prepareLedgerCandidates(other,seed.tenant(),ids,20));
     }
    }
    assertThrows(java.sql.SQLException.class,()->contracts.prepareLedgerCandidates(tx,seed.tenant(),ids,20));
    assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.prepareLedgerCandidates(tx,seed.tenant(),ids,20));return null;
   });
  }
 }

 @Test void recovery_scan_reuses_owner_rows_under_its_existing_business_fence()throws Exception {
  initializeContract();assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,execute(request()).status());
  assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,execute(nextContract(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,java.util.Map.of("decision","APPROVED","reason","合成授权"))).status());
  assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,execute(nextContract(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.START_CONTRACT_PREPARATION,java.util.Map.of())).status());
  var worker=service("CONTRACT_TASK_RECOVER");
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);var page=new ContractPreparationDiscovery(contracts,new byte[32]).list(probe.connection(),worker,50,null);assertTrue(((java.util.List<?>)page.get("candidates")).isEmpty());
   assertEquals(2,probe.statements.stream().filter(q->q.equals("select * from opportunity.opportunity where tenant_id=? and opportunity_id=?")).count(),"Contract and Quote each read their source row once per fenced recovery scan");
  }
 }
 @Test void creation_path_is_reused_only_inside_one_fenced_connection_and_tenant()throws Exception {
  initializeContract();
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);var reader=io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader.databaseBacked();
   inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var scope=reader.fencedReadScope(tx,seed.tenant())) {
     var first=reader.byId(tx,seed.tenant(),opportunity.id());assertNotNull(first);
     for(int i=0;i<20;i++)assertEquals(first,io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader.databaseBacked().byId(tx,seed.tenant(),opportunity.id()));
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from \"opportunity\".\"opportunity\"")).count());
     assertNull(reader.byId(tx,java.util.UUID.randomUUID(),opportunity.id()),"A tenant mismatch never uses another tenant's source row");
    }
    var before=probe.statements.size();assertNotNull(reader.byId(tx,seed.tenant(),opportunity.id()));assertTrue(probe.statements.size()>before);return null;
   });
  }
 }
 @Test void detail_builds_exact_business_source_graph_once_under_its_fence()throws Exception {
  initializeContract();assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,execute(request()).status());
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);
   var result=new R2ContractReadService(contracts,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("DETAIL_SOURCE_IT")).read(probe.connection(),seed.request().actor(),opportunity.id());
   assertNotNull(result.get("preparation"));
   assertEquals(1,probe.statements.stream().filter(q->q.startsWith("select 'contract.preparation_request' fact_type")).count(),"All source selectors are stable under the transaction fence; authorization still uses fresh time");
  }
 }
 @Test void responsibility_rows_are_reused_only_within_the_same_fenced_ledger()throws Exception {
  initializeContract();
  try(var c=database.apiConnection()) {
   var probe=new ReadConnectionProbe(c);var ports=new ContractWorkflowPorts(cipher,null);
   inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var identity=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant());var sources=R2LedgerSourceFacts.open(tx,seed.tenant())) {
     var expected=ports.responsibility(tx,seed.tenant(),opportunity);
     for(int i=0;i<20;i++)assertEquals(expected,new ContractWorkflowPorts(cipher,null).responsibility(tx,seed.tenant(),opportunity));
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from opportunity.responsibility_handoff")).count());
    }
    var count=probe.statements.size();ports.responsibility(tx,seed.tenant(),opportunity);
    assertTrue(probe.statements.size()>count,"Closing the scope removes its stored source rows");
    return null;
   });
  }
 }
}
