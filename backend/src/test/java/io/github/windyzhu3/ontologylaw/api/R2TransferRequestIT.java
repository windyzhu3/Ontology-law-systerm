package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.TransferSubmissionService;
import io.github.windyzhu3.ontologylaw.contract.ContractTransferSourceReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2TransferRequestIT extends R2ContractExecutionHandoffIT {
 boolean authorized=true;UUID destination;
 TransferSubmissionService transfer(){return TransferSubmissionService.databaseBacked(new TransferSubmissionService.Ports(){
  public void lockAuthority(Connection c,Actor actor){}
  public void authorizePreparation(Connection c,Actor actor,Subject opportunity){if(!authorized)throw new TransferSubmissionService.Blocked("NOT_AUTHORIZED");}
  public TransferSubmissionService.Source source(Connection c,UUID tenant,UUID contract)throws SQLException{
   try(var p=c.prepareStatement("select contract_id from contract.contract where tenant_id=? and contract_id=? for update")){p.setObject(1,tenant);p.setObject(2,contract);p.executeQuery().close();}
   return ContractTransferSourceReader.databaseBacked().find(c,tenant,contract).map(s->new TransferSubmissionService.Source(s.opportunityId(),s.contractId(),s.executionId(),s.activatedAt(),s.activationDigest())).orElse(null);
  }
  public TransferSubmissionService.Route route(Connection c,Actor actor,Subject opportunity){return new TransferSubmissionService.Route(seed.org(),destination);}
 });}
 void prepareExecutedSource()throws Exception{
  authorized=true;var trigger=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),trigger);command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",executionVerificationPayload());
  destination=UUID.randomUUID();
  mutate("insert into identity.organization_unit(tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values(?,?,?,'F11_INTAKE','Synthetic intake','ACTIVE',clock_timestamp())",seed.tenant(),destination,seed.org());
 }
 Subject prepare()throws Exception{var worker=service("TRANSFER_TASK_RECOVER");try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->transfer().prepare(x,worker,opportunity,anchor.id()));}}
 @Test void transfer_request_uses_exact_execution_and_neutral_classification_without_creating_case()throws Exception{
  prepareExecutedSource();var request=prepare();assertEquals("transfer.transfer_request",request.type());
  assertEquals("UNCLASSIFIED",scalar("select proposed_matter_type_code from transfer.transfer_request where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_request r join contract.contract c on c.tenant_id=r.tenant_id and c.contract_id=r.contract_id and c.contract_execution_id=r.contract_execution_id and c.activation_source_hash=r.deal_activation_digest and c.deal_activated_at=r.deal_activated_at where r.tenant_id=? and r.matter_id is null and r.accepted_snapshot_id is null",seed.tenant()));
  assertThrows(TransferSubmissionService.Blocked.class,()->prepare());
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }
 @Test void transfer_request_denied_or_rolled_back_never_leaves_partial_request()throws Exception{
  prepareExecutedSource();authorized=false;assertThrows(TransferSubmissionService.Blocked.class,()->prepare());authorized=true;
  var worker=service("TRANSFER_TASK_RECOVER");try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{transfer().prepare(x,worker,opportunity,anchor.id());throw new IllegalStateException("audit failed");}));}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }
 @Test void transfer_request_concurrent_recovery_has_one_root_and_no_case()throws Exception{
  prepareExecutedSource();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var operation=(java.util.concurrent.Callable<Boolean>)()->{try{prepare();return true;}catch(TransferSubmissionService.Blocked rejected){assertEquals("STALE_SUBJECT",rejected.code());return false;}};
   var first=pool.submit(operation);var second=pool.submit(operation);assertNotEquals(first.get(),second.get());
  }
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is null",seed.tenant()));
 }
 @Test void transfer_request_requires_execution_and_cannot_be_created_by_a_human_recovery_call()throws Exception{
  executionSource();assertEquals("STALE_SUBJECT",assertThrows(TransferSubmissionService.Blocked.class,()->prepare()).code());
  try(var c=database.apiConnection()){assertEquals("NOT_AUTHORIZED",assertThrows(TransferSubmissionService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->transfer().prepare(x,actor(),opportunity,anchor.id()))).code());}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }
}
