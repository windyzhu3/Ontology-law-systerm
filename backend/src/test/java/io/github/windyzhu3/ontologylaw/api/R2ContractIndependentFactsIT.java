package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import java.sql.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
/** Synthetic independent facts: real root-lock races, without implementing F10/F11 business commands. */
class R2ContractIndependentFactsIT extends R2SalesTerminationIT {
 private static void write(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);assertEquals(1,p.executeUpdate());}}
 private static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(25,TimeUnit.SECONDS))throw new SQLException("Barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
 private static int pid(Connection c)throws SQLException{try(var p=c.prepareStatement("select pg_backend_pid()");var r=p.executeQuery()){r.next();return r.getInt(1);}}
 private void blocked(int pid)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(!"0".equals(scalar("select cardinality(pg_blocking_pids(?))",pid)))return;Thread.sleep(20);}fail("Independent write did not participate in the opportunity lock");}
 private UUID version()throws Exception{return UUID.fromString((String)((Map<?,?>)((Map<?,?>)context().get("contract")).get("currentRevision")).get("id"));}
 private void payment(Connection c,UUID version)throws SQLException{write(c,"insert into contract.payment_confirmation(tenant_id,payment_confirmation_id,contract_id,contract_revision_id,confirmation_no,confirmation_type,amount_minor,currency_code,provider_account_code,provider_transaction_key_hmac,evidence_submission_id,attribution_digest,effective_at,confirmed_at,recorded_by_appointment_id) select tenant_id,uuidv7(),?,?,1,'RECEIPT',100,'CNY','F06_RACE',sha256(convert_to(cast(? as text),'UTF8')),evidence_submission_id,decode(repeat('31',32),'hex'),clock_timestamp(),clock_timestamp(),? from opportunity.material_version where tenant_id=? and material_version_id=?",anchor.id(),version,UUID.randomUUID().toString(),seed.appointment(),seed.tenant(),material);}
 private void paymentRace(boolean paymentFirst,boolean unsigned)throws Exception{
  start();var who=actor();if(!unsigned){arrange();submit();who=supervisor();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));}var reviewer=who;
  var version=version();String action=unsigned?"END_CONTRACT_NEGOTIATION":"RECORD_CONTRACT_TERMINATION_REVIEW";var body=dispositionPayload(unsigned?Map.of():Map.of("decision","STOP"));
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{if(paymentFirst)payment(x,version);else service().execute(x,action,reviewer,body);held.countDown();await(release);return true;});}});
   Future<String> two;
   try{await(held);two=pool.submit(()->{try(var c=database.apiConnection()){waitingPid.set(pid(c));started.countDown();if(paymentFirst)return assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->service().execute(x,action,reviewer,body))).code();return inTransaction(c,Capability.COMMAND,x->{payment(x,version);return "RECORDED";});}});await(started);blocked(waitingPid.get());}finally{release.countDown();}
   assertTrue(one.get(25,TimeUnit.SECONDS));assertEquals(paymentFirst?(unsigned?"NOT_AUTHORIZED":"STALE_SUBJECT"):"RECORDED",two.get(25,TimeUnit.SECONDS));
  }
  assertEquals("1",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));
  assertEquals(paymentFirst?(unsigned?"0":"1"):(unsigned?"1":"2"),scalar("select count(*) from contract.negotiation_disposition where tenant_id=?",seed.tenant()));
 }
 @Test void independent_payment_first_requires_supervisor_instead_of_unsigned_stop()throws Exception{paymentRace(true,true);}
 @Test void independent_unsigned_stop_first_allows_later_payment()throws Exception{paymentRace(false,true);}
 @Test void independent_payment_first_invalidates_old_supervisor_basis()throws Exception{paymentRace(true,false);}
 @Test void independent_supervisor_stop_first_preserves_later_payment()throws Exception{paymentRace(false,false);}
 private List<UUID> transferFacts()throws Exception{
  start();arrange();submit();command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));
  command("SUBMIT_CONTRACT_SIGNATURE",signaturePayload(Map.of("slotNumber",2,"materialVersionId",material.toString(),"materialSha256",bodySha,"authorityMaterialVersionId",material.toString(),"authorityMaterialSha256",bodySha,"signerName","合成律所代表","signedAt","2026-01-01T00:00:00Z")));command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));command("ARCHIVE_CONTRACT_SIGNATURE",signaturePayload(Map.of("materialVersionId",material.toString(),"materialSha256",bodySha,"reason","合成归档已核对","archiveComplete",true)));
  UUID execution=UUID.randomUUID(),dest=UUID.randomUUID(),version=version();var ids=List.of(UUID.fromString("00000000-0000-4000-8000-000000000001"),UUID.fromString("00000000-0000-4000-8000-000000000002"));
  mutate("insert into identity.organization_unit(tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values(?,?,?,'F06_DEST','合成承接组织','ACTIVE',clock_timestamp())",seed.tenant(),dest,seed.org());
  // Isolated database fixture only: model already-persisted downstream facts, not F10 execution.
  // R2 and legacy signature digests deliberately differ. This synthetic snapshot bypasses only
  // execution creation gates while seeding; it does not assert a valid executed business package.
  // FK and update constraints stay enabled. Restore both gates before any exercised command.
  try(var x=database.adminConnection()){x.setAutoCommit(false);try(var gate=x.createStatement()){
   gate.execute("alter table contract.contract_execution disable trigger trg_contract_execution__r2_protocol");
   gate.execute("alter table contract.contract_execution disable trigger ctrg_contract_execution__complete_package");
   write(x,"insert into contract.contract_execution(tenant_id,contract_execution_id,contract_id,contract_revision_id,approval_set_digest,signature_set_digest,review_scope_hash,review_resolution_digest,archive_evidence_submission_id,execution_digest,executed_by_appointment_id,executed_at) select tenant_id,?,?,?,decode(repeat('31',32),'hex'),decode(repeat('31',32),'hex'),decode(repeat('31',32),'hex'),decode(repeat('31',32),'hex'),evidence_submission_id,decode(repeat('31',32),'hex'),?,clock_timestamp() from opportunity.material_version where tenant_id=? and material_version_id=?",execution,anchor.id(),version,seed.appointment(),seed.tenant(),material);
   write(x,"update contract.contract set contract_execution_id=?,deal_activated_at=clock_timestamp(),activation_source_type='contract.contract_execution',activation_source_id=?,activation_source_hash=decode(repeat('31',32),'hex'),revision=revision+1,changed_at=clock_timestamp() where tenant_id=? and contract_id=?",execution,execution,seed.tenant(),anchor.id());
   for(int i=0;i<ids.size();i++)write(x,"insert into transfer.transfer_request(tenant_id,transfer_request_id,opportunity_id,contract_id,contract_execution_id,deal_activated_at,deal_activation_digest,from_organization_unit_id,to_organization_unit_id,transfer_purpose_code,proposed_matter_type_code,proposed_capability_pack_code,proposed_capability_pack_version,created_by_appointment_id,created_at,changed_at) select tenant_id,?,opportunity_id,contract_id,contract_execution_id,deal_activated_at,sha256(convert_to(cast(? as text),'UTF8')),?,?,'SYNTHETIC_EXISTING','UNCLASSIFIED','SYNTHETIC',1,?,clock_timestamp(),clock_timestamp() from contract.contract where tenant_id=? and contract_id=?",ids.get(i),Integer.toString(i),seed.org(),dest,seed.appointment(),seed.tenant(),anchor.id());
   gate.execute("set constraints all immediate");
   gate.execute("alter table contract.contract_execution enable trigger trg_contract_execution__r2_protocol");
   gate.execute("alter table contract.contract_execution enable trigger ctrg_contract_execution__complete_package");
   x.commit();
  }}
  assertEquals("O",scalar("select tgenabled from pg_trigger where tgrelid='contract.contract_execution'::regclass and tgname='trg_contract_execution__r2_protocol'"));
  assertEquals("O",scalar("select tgenabled from pg_trigger where tgrelid='contract.contract_execution'::regclass and tgname='ctrg_contract_execution__complete_package'"));
  return ids;
 }
 @Test void independent_all_existing_transfer_requests_are_review_basis()throws Exception{
  var ids=transferFacts();try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var facts=service().protectedFacts(x,seed.tenant(),opportunity.id()).stream().filter(f->f.type().equals("transfer.transfer_request")).toList();assertEquals(ids,facts.stream().map(f->f.id()).toList());return null;});}
 }
 @Test void independent_existing_transfer_update_waits_while_supervisor_decides()throws Exception{
  var ids=transferFacts();var reviewer=supervisor();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));var body=dispositionPayload(Map.of("decision","STOP"));
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{service().execute(x,"RECORD_CONTRACT_TERMINATION_REVIEW",reviewer,body);held.countDown();await(release);return true;});}});
   Future<String> two;
   try{await(held);two=pool.submit(()->{try(var c=database.apiConnection()){waitingPid.set(pid(c));started.countDown();return assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
    // This F06 lock probe deliberately has no acceptance package: F11 owns that business command.
    write(x,"update transfer.transfer_request set accepted_snapshot_id=?,accept_decision_record_id=?,matter_id=?,matter_no='F06_UNCOMMITTED',matter_type_code='UNCLASSIFIED',matter_capability_pack_code='SYNTHETIC',matter_capability_pack_version=1,matter_created_at=clock_timestamp(),revision=revision+1,changed_at=clock_timestamp() where tenant_id=? and transfer_request_id=?",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),seed.tenant(),ids.getLast());return true;
   })).getSQLState();}});await(started);blocked(waitingPid.get());}finally{release.countDown();}
   assertTrue(one.get(25,TimeUnit.SECONDS));assertEquals("23503",two.get(25,TimeUnit.SECONDS),"Independent processing reaches its own fact validation, not the sales pause barrier");
  }
  assertEquals("0",scalar("select revision from transfer.transfer_request where tenant_id=? and transfer_request_id=?",seed.tenant(),ids.getLast()));assertEquals("STOPPED",((Map<?,?>)context().get("termination")).get("state"));
 }
}
