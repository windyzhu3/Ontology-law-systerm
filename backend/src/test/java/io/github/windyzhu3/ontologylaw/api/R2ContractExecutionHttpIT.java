package io.github.windyzhu3.ontologylaw.api;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;

/** Extends the same imported sales record through real HTTP execution confirmation and receipt recovery. */
class R2ContractExecutionHttpIT extends R2SalesChainClosureIT {
 @Override void continueContractPreparation(HttpHarness http)throws Exception {
  super.continueContractPreparation(http);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_EXECUTION_VERIFY");return null;});}
  var owner=R2ContractServices.create(contractProtection,opportunityProtection,materialStore);var worker=service("CONTRACT_TASK_RECOVER");
  Map<String,Object> input;
  try(var c=database.apiConnection()){input=inTransaction(c,Capability.QUERY,x->{var candidates=owner.recoveryPage(x,worker,100,null).candidates();assertEquals(1,candidates.size());var candidate=candidates.getFirst();assertEquals("contract.signature_handoff",candidate.source().type());
   var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",candidate.opportunity().revision());p.put("responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()));p.put("sourceKind","EXECUTION_HANDOFF");p.put("source",Map.of("id",candidate.source().id().toString(),"revision",0L));p.put("expectedWorkflow",null);return p;});}
  var recovery=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,input);
  var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"F10_HTTP_EXECUTION",owner);
  try(var c=database.apiConnection()){var result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,recovery));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());}
  afterExecutionHandoff(http);
 }
 void afterExecutionHandoff(HttpHarness http)throws Exception{verifyExecution(http);}
 void verifyExecution(HttpHarness http)throws Exception{
  var sales=new RoleHttp(http,SUBJECT);var ctx=context(sales);var execution=(Map<?,?>)ctx.get("execution");assertNotNull(execution);var workflow=(Map<?,?>)execution.get("workflow");assertEquals("CHECK_CONDITIONS",workflow.get("stage"));
  var task=(Map<?,?>)workflow.get("task");var card=sales.request("GET","/api/v1/workcards/current?taskId="+task.get("id"),null,Map.of());assertEquals(200,card.statusCode(),card.body());assertEquals("CHECK_CONTRACT_EXECUTION",((Map<?,?>)sales.body(card).get("currentCard")).get("taskType"));
  var command=contractBody(ctx,Map.of("expectedExecutionWorkflow",workflow.get("selector"),"approvedConditionsChecked",true,"archiveAndConditionsComplete",true));String key=UUID.randomUUID().toString();
  var result=sales.request("POST",base+"/execution-verifications",command,Map.of("Idempotency-Key",key));assertEquals(200,result.statusCode(),result.body());assertEquals("CONTRACT_EXECUTION_VERIFICATION",((Map<?,?>)sales.body(result).get("resultFact")).get("factType"));
  var replay=sales.request("POST",base+"/execution-verifications",command,Map.of("Idempotency-Key",key));assertEquals(200,replay.statusCode(),replay.body());assertSameReceipt(sales.body(result),sales.body(replay));
  var receipt=sales.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertSameReceipt(sales.body(result),sales.body(receipt));
  var finalWorkflow=(Map<?,?>)((Map<?,?>)context(sales).get("execution")).get("workflow");assertEquals("READY_TRANSFER",finalWorkflow.get("stage"));assertNull(finalWorkflow.get("task"));
  assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
 }
}
