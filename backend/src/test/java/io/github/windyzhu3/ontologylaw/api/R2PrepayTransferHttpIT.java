package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import static org.junit.jupiter.api.Assertions.*;
/** F12: the same imported paths explicitly require a first receipt before execution and transfer. */
class R2PrepayTransferHttpIT extends R2TransferHttpIT {
 @Override Map<String,Object> paymentGate(){return Map.of("receiptRequiredBeforeTransfer",true,"requiredMinor",100L);}
 @Override void afterExecutionHandoff(HttpHarness http)throws Exception{
  var sales=new RoleHttp(http,SUBJECT);var context=context(sales);var workflow=(Map<?,?>)((Map<?,?>)context.get("execution")).get("workflow");assertEquals("WAIT_RECEIPT",workflow.get("stage"));
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
  var response=sales.request("POST",base+"/execution-verifications",contractBody(context,Map.of("expectedExecutionWorkflow",workflow.get("selector"),"approvedConditionsChecked",true,"archiveAndConditionsComplete",true)),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
  assertTrue(response.statusCode()>=400&&response.statusCode()<500,response.body());
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
 }
 @Override void afterInitialPayment(HttpHarness http)throws Exception{
  // Receipt confirmation wakes eligibility; the production worker reconciles the waiting execution duty.
  var services=http.context.getBean(R1ApiServices.class);var worker=service("CONTRACT_TASK_RECOVER");Map<String,Object> candidate=null;String cursor=null;
  for(int page=0;page<20;page++){var result=services.contractCandidates(worker,20,cursor);for(var raw:(List<?>)result.get("candidates")){var row=(Map<String,Object>)raw;if("EXECUTION_HANDOFF".equals(row.get("sourceKind")))candidate=row;}cursor=(String)result.get("nextCursor");if(cursor==null)break;}
  assertNotNull(candidate,"Confirmed prepay must make the waiting execution duty recoverable");var payload=new LinkedHashMap<String,Object>(candidate);var key=UUID.fromString((String)payload.remove("idempotencyKey"));payload.remove("kind");
  var command=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,payload);var result=services.contractCommand(command);assertEquals(200,result.status(),result.errorCode());assertEquals(result.body(),services.contractCommand(command).body());
  verifyExecution(http);
 }
}
