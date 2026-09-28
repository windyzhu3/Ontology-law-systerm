package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
import org.junit.jupiter.api.Test;
class R2QuoteDraftIT extends R2CustomerRequirementsIT {
 private QuoteDraftService quotes(){return QuoteDraftService.databaseBacked(cipher,new QuoteDraftService.Codec(){
  public String encode(Map<String,Object> body){return io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(body);}
  public Map<String,Object> decode(String body){return tools.jackson.databind.json.JsonMapper.builder().build().readValue(body,Map.class);}
 });}
 private Subject confirmation()throws Exception {var saved=save(doc());var confirmed=execute(command(true,saved.resultFact(),null,null));assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,confirmed.status());return confirmed.resultFact();}
 @Test void quote_draft_preserves_waiting_and_is_encrypted_and_tenant_scoped()throws Exception{
  setup(true,true);var confirmed=confirmation();var q=quotes();
  String taskBefore=scalar("select task_occurrence_id::text||':'||state||':'||revision from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id());
  QuoteDraftService.Version saved;
  try(var c=database.apiConnection()){saved=inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of("scope","保密收费方案"))));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertEquals("保密收费方案",q.read(x,seed.tenant(),saved.selector().id()).document().get("scope"));assertNull(q.read(x,UUID.randomUUID(),saved.selector().id()));return null;});}
  assertFalse(saved.toString().contains("保密收费方案"));
  assertEquals(taskBefore,scalar("select task_occurrence_id::text||':'||state||':'||revision from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
  try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of("scope","过期写入")))));}
  try(var c=database.apiConnection()){var next=inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,saved.selector(),Map.of("scope","第二版草稿"))));assertNotEquals(saved.selector(),next.selector());}
  assertEquals("2",scalar("select count(*) from opportunity.quote_draft where tenant_id=?",seed.tenant()));
 }
 @Test void quote_draft_rolls_back_and_cannot_be_mutated()throws Exception{
  setup(true,false);var confirmed=confirmation();var q=quotes();
  try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of()));throw new IllegalStateException("simulate later command failure");}));}
  assertEquals("0",scalar("select count(*) from opportunity.quote_draft where tenant_id=?",seed.tenant()));
  try(var c=database.apiConnection()){var saved=inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of())));
   assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{try(var p=x.prepareStatement("update opportunity.quote_draft set body_digest=? where tenant_id=? and quote_draft_id=?")){p.setBytes(1,new byte[32]);p.setObject(2,seed.tenant());p.setObject(3,saved.selector().id());p.executeUpdate();}return null;}));
  }
 }
 @Test void changed_customer_confirmation_rejects_old_basis()throws Exception{
  setup(true,false);var old=confirmation();var q=quotes();
  Subject oldDraft;try(var c=database.apiConnection()){oldDraft=inTransaction(c,Capability.QUERY,x->R2CustomerRequirementsServices.create(cipher).latestDraft(x,seed.tenant(),opportunity.id(),opportunity,seed.appointment()).selector());}
  var next=execute(command(false,oldDraft,old,doc()));assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,next.status());
  var newer=execute(command(true,next.resultFact(),old,null));assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,newer.status());
  try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),old,null,Map.of()))));}
  try(var c=database.apiConnection()){assertNotNull(inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),newer.resultFact(),null,Map.of()))));}
 }
 private static void sql(java.sql.Connection c,String query,Object... args)throws java.sql.SQLException {try(var p=c.prepareStatement(query)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
 @Test void formed_quote_children_cannot_be_extended_in_later_transaction()throws Exception{
  setup(true,false);var confirmed=confirmation();var q=quotes();UUID quote=UUID.randomUUID();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   var draft=q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of()));
   UUID action;try(var p=x.prepareStatement("select action_draft_id from responsibility.action_draft where tenant_id=? and state='CONFIRMED' limit 1")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){assertTrue(r.next());action=r.getObject(1,UUID.class);}}
   var digest=io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest("{}");
   sql(x,"insert into opportunity.quote_revision(tenant_id,quote_revision_id,opportunity_id,quote_revision_no,confirmed_action_draft_id,participation_set_revision,participation_set_digest,package_contract_code,package_contract_version,currency_code,total_minor,content_digest,valid_until,created_by_appointment_id,created_at) values(?,?,?,1,?,1,?,'R2_QUOTE_PACKAGE_V1',1,'CNY',100,?,clock_timestamp()+interval '1 day',?,clock_timestamp())",seed.tenant(),quote,opportunity.id(),action,new byte[32],digest,seed.appointment());
   sql(x,"insert into opportunity.quote_package_basis(tenant_id,quote_package_basis_id,revision,quote_revision_id,quote_draft_id,customer_confirmation_id,body_ciphertext,body_digest,created_at) values(?,?,0,?,?,?,?,?,clock_timestamp())",seed.tenant(),quote,quote,draft.selector().id(),confirmed.id(),cipher.encryptQuote(seed.tenant(),opportunity.id(),quote,false,"{}"),digest);
   sql(x,"insert into opportunity.quote_service_scope(tenant_id,quote_service_scope_id,quote_revision_id,scope_no,service_code,scope_summary,included,scope_hash,created_at) values(?,?,?,1,'SERVICE','范围',true,?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),quote,digest);
   sql(x,"insert into opportunity.quote_line(tenant_id,quote_line_id,quote_revision_id,line_no,line_type_code,line_summary,amount_minor,currency_code,created_at) values(?,?,?,1,'FIXED','费用',100,'CNY',clock_timestamp())",seed.tenant(),UUID.randomUUID(),quote);
   sql(x,"insert into opportunity.quote_payment_term(tenant_id,quote_payment_term_id,quote_revision_id,term_no,due_basis_code,due_offset_days,amount_minor,currency_code,created_at) values(?,?,?,1,'SIGNATURE',0,100,'CNY',clock_timestamp())",seed.tenant(),UUID.randomUUID(),quote);
   sql(x,"insert into opportunity.opportunity_participation(tenant_id,opportunity_participation_id,opportunity_id,participation_set_revision,participation_no,participation_set_size,participation_set_digest,party_id,party_revision,party_snapshot_digest,context_role_code,created_at) select tenant_id,uuidv7(),?,1,1,1,?,party_id,party_revision,?,'CLIENT',clock_timestamp() from opportunity.customer_requirement_participant where tenant_id=? and confirmation_id=? and role='CLIENT'",opportunity.id(),new byte[32],new byte[32],seed.tenant(),confirmed.id());
   sql(x,"update opportunity.opportunity set current_quote_revision_id=?,revision=revision+1 where tenant_id=? and opportunity_id=?",quote,seed.tenant(),opportunity.id());
   return null;
  });}
  for(String statement:List.of(
   "insert into opportunity.quote_line(tenant_id,quote_line_id,quote_revision_id,line_no,line_type_code,line_summary,amount_minor,currency_code,created_at) values(?,?,?,2,'FIXED','追加',1,'CNY',clock_timestamp())",
   "insert into opportunity.quote_service_scope(tenant_id,quote_service_scope_id,quote_revision_id,scope_no,service_code,scope_summary,included,scope_hash,created_at) values(?,?,?,2,'SERVICE','追加',true,decode(repeat('00',32),'hex'),clock_timestamp())",
   "insert into opportunity.quote_payment_term(tenant_id,quote_payment_term_id,quote_revision_id,term_no,due_basis_code,due_offset_days,amount_minor,currency_code,created_at) values(?,?,?,2,'SIGNATURE',0,1,'CNY',clock_timestamp())")){
   try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{sql(x,statement,seed.tenant(),UUID.randomUUID(),quote);return null;}));}
  }
  try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
   sql(x,"insert into opportunity.opportunity_participation(tenant_id,opportunity_participation_id,opportunity_id,participation_set_revision,participation_no,participation_set_size,participation_set_digest,party_id,party_revision,party_snapshot_digest,context_role_code,created_at) select p.tenant_id,uuidv7(),p.opportunity_id,p.participation_set_revision,2,2,p.participation_set_digest,p.party_id,p.party_revision,p.party_snapshot_digest,'COUNTERPARTY',clock_timestamp() from opportunity.opportunity_participation p join opportunity.quote_revision q on q.tenant_id=p.tenant_id and q.opportunity_id=p.opportunity_id and q.participation_set_revision=p.participation_set_revision where q.tenant_id=? and q.quote_revision_id=? and p.participation_no=1",seed.tenant(),quote);return null;
  }));}
 }
 @Test void draft_head_uses_chain_even_when_clock_moves_backwards()throws Exception{
  setup(true,false);var confirmed=confirmation();var q=quotes();QuoteDraftService.Version first,second;
  try(var c=database.apiConnection()){first=inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,null,Map.of())));}
  try(var c=database.apiConnection()){second=inTransaction(c,Capability.COMMAND,x->q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,first.selector(),Map.of())));}
  // Only the disposable integration database simulates a backwards wall clock.
  try(var c=database.adminConnection()){c.setAutoCommit(false);try{
   sql(c,"alter table opportunity.quote_draft disable trigger trg_quote_draft__mutation_guard");
   sql(c,"update opportunity.quote_draft set created_at=created_at-interval '1 day' where tenant_id=? and quote_draft_id=?",seed.tenant(),second.selector().id());
   sql(c,"alter table opportunity.quote_draft enable trigger trg_quote_draft__mutation_guard");c.commit();
  }catch(Exception e){c.rollback();throw e;}}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{assertEquals(second.selector(),q.latest(x,seed.tenant(),opportunity,opportunity,seed.appointment()).selector());return q.save(x,seed.tenant(),new QuoteDraftService.Input(opportunity,opportunity,seed.appointment(),confirmed,second.selector(),Map.of()));});}
 }
}
