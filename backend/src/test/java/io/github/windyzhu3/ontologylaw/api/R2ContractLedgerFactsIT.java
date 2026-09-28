package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2ContractLedgerFactsIT extends R2ContractWorkflowPersistenceIT {
 @Test void repeated_finance_contract_metadata_is_reused_only_in_the_ledger_scope()throws Exception{
  initialize();try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().shared(x,seed.tenant());List<?> first;
   try(var identity=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked().lockedReadScope(x,seed.tenant());var scope=R2LedgerSourceFacts.open(x,seed.tenant())){
    first=R2ContractServices.facts(x,seed.tenant(),opportunity.id());assertFalse(first.isEmpty());assertSame(first,R2ContractServices.facts(x,seed.tenant(),opportunity.id()));
    try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,y->{assertNotSame(first,R2ContractServices.facts(y,seed.tenant(),opportunity.id()));return null;});}
   }assertNotSame(first,R2ContractServices.facts(x,seed.tenant(),opportunity.id()));return null;
  });}
 }
 @Test void quote_and_customer_source_graphs_are_reused_only_in_the_exact_ledger_scope()throws Exception{
  initialize();
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().shared(x,seed.tenant());
   List<?> quote;List<?> customer;
   try(var identity=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked().lockedReadScope(x,seed.tenant());var scope=R2LedgerSourceFacts.open(x,seed.tenant())){
    quote=R2QuoteServices.facts(x,seed.tenant(),opportunity.id());customer=R2CustomerRequirementsServices.sourceFacts(x,seed.tenant(),opportunity,null);
    assertFalse(quote.isEmpty());assertFalse(customer.isEmpty());
    assertSame(quote,R2QuoteServices.facts(x,seed.tenant(),opportunity.id()));
    assertSame(customer,R2CustomerRequirementsServices.sourceFacts(x,seed.tenant(),opportunity,null));
    assertNull(R2CustomerRequirementsServices.sourceFacts(x,seed.tenant(),opportunity,new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("opportunity.customer_requirement_confirmation",UUID.randomUUID(),0L,null)),"Exact confirmation belongs in the source key");
    try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,y->{assertNotSame(quote,R2QuoteServices.facts(y,seed.tenant(),opportunity.id()));assertNotSame(customer,R2CustomerRequirementsServices.sourceFacts(y,seed.tenant(),opportunity,null));return null;});}
   }
   assertNotSame(quote,R2QuoteServices.facts(x,seed.tenant(),opportunity.id()));assertNotSame(customer,R2CustomerRequirementsServices.sourceFacts(x,seed.tenant(),opportunity,null));return null;
  });}
 }
 @Test void repeated_material_sources_reuse_the_exact_graph_only_inside_the_ledger_scope()throws Exception {
  initialize();
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().shared(x,seed.tenant());
   List<?> first;
   try(var identity=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked().lockedReadScope(x,seed.tenant());var scope=R2LedgerSourceFacts.open(x,seed.tenant())){
    first=R2OpportunityClosureServices.protectedFacts(x,seed.tenant(),opportunity);
    assertNotNull(first);assertFalse(first.isEmpty());
    assertSame(first,R2OpportunityClosureServices.protectedFacts(x,seed.tenant(),opportunity));
    try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,y->{assertNotSame(first,R2OpportunityClosureServices.protectedFacts(y,seed.tenant(),opportunity));return null;});}
   }
   assertNotSame(first,R2OpportunityClosureServices.protectedFacts(x,seed.tenant(),opportunity));return null;
  });}
 }
 @Test void ledger_reuses_only_fenced_transaction_facts_and_clears_them_on_close()throws Exception{
  initialize();var service=service();
  try(var c=database.apiConnection()) {inTransaction(c,Capability.QUERY,x->{
   io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().shared(x,seed.tenant());
   List<?> first;
   try(var scope=service.lockedLedgerFacts(x,seed.tenant())) {
    first=service.protectedFacts(x,seed.tenant(),opportunity.id());
    assertFalse(first.isEmpty());
    assertThrows(java.sql.SQLException.class,()->service.execute(x,"FORM_CONTRACT",actor(),Map.of()));
    assertThrows(java.sql.SQLException.class,()->service.reconcile(x,actor(),Map.of()));
    assertThrows(java.sql.SQLException.class,()->service.lockedLedgerFacts(x,seed.tenant()));
    try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,y->{assertNotSame(first,service.protectedFacts(y,seed.tenant(),opportunity.id()),"Different connections cannot share facts");return null;});}
    assertSame(first,service.protectedFacts(x,seed.tenant(),opportunity.id()),"The same fenced graph must not be rebuilt");
   }
   assertNotSame(first,service.protectedFacts(x,seed.tenant(),opportunity.id()),"No cross-scope reuse");
   return null;
  });}
 }
}
