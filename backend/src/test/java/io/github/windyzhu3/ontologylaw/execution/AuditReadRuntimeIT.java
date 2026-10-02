package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.sql.*;
import java.lang.reflect.*;
class AuditReadRuntimeIT extends PostgresIntegrationTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"LIST","CORRELATION"})
 void pagination_existence_is_reauthorized_after_a_future_object_deny_becomes_effective(String operation)throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID correlation=UUID.randomUUID(),older=AuditRecordReaderIT.append(database,s,correlation),newer=AuditRecordReaderIT.append(database,s,correlation);var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){
   io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,object_subject_type,object_subject_id,object_subject_hash,access_code,effect_code,valid_from,state,created_at) values (?,?,?,?,'audit.audit_entry',?,?,'AUDIT_READ','DENY',clock_timestamp()+interval '3 seconds','ACTIVE',clock_timestamp())",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),older,io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest("{\"secret\":\"HMAC_TOKEN_CONTACT\"}"));return null;});
   var delegate=AuditAppender.databaseBacked("AUDIT_IT");AuditAppender delayed=new AuditAppender(){public void append(Connection x,Entry e)throws SQLException{delegate.append(x,e);}public void append(Connection x,AuditRecordDisclosureEntry e)throws SQLException{delegate.append(x,e);try{Thread.sleep(3200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}}};
   var query=new AuditRecordReader.Query(null,null,null,null,null,1,null);assertThrows(IdentityCommands.Failure.class,()->{if(operation.equals("LIST"))runtime(delayed).read(c,actor,query);else runtime(delayed).related(c,actor,newer,AuditRecordReader.Relation.CORRELATION,query);},"A next cursor must not disclose a now-denied lookahead node");
  }
 }
 @Test void cursors_bind_filters_actor_and_watermark_and_time_bounds_are_closed()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");for(int i=0;i<3;i++)AuditRecordReaderIT.append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);var runtime=runtime(AuditAppender.databaseBacked("AUDIT_IT"));
  try(var c=database.apiConnection()){
   var page=runtime.read(c,actor,new AuditRecordReader.Query(null,null,null,null,null,1,null));String cursor=(String)page.get("nextCursor");assertNotNull(cursor);
   var next=runtime.read(c,actor,new AuditRecordReader.Query(null,null,null,null,null,1,cursor));assertNotEquals(((List<?>)page.get("items")).getFirst(),((List<?>)next.get("items")).getFirst());
   assertThrows(IdentityCommands.Failure.class,()->runtime.read(c,actor,new AuditRecordReader.Query(null,null,null,null,"changed",1,cursor)));
   assertThrows(IdentityCommands.Failure.class,()->runtime.read(c,actor,new AuditRecordReader.Query(java.time.Instant.now().minusSeconds(32*86400),java.time.Instant.now(),null,null,null,20,null)));
  }
 }
 @Test void expiry_during_audit_append_rolls_back_and_cannot_disclose()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID id=AuditRecordReaderIT.append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){
   io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",s.tenant(),s.grant());sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'AUDIT_READ',clock_timestamp()-interval '1 day',clock_timestamp()+interval '3 seconds','ACTIVE',clock_timestamp())",s.tenant(),UUID.randomUUID(),s.appointment(),s.appointment(),s.org());return null;});
   var delegate=AuditAppender.databaseBacked("AUDIT_IT");AuditAppender delayed=new AuditAppender(){public void append(Connection x,Entry e)throws SQLException{delegate.append(x,e);}public void append(Connection x,AuditRecordDisclosureEntry e)throws SQLException{delegate.append(x,e);try{Thread.sleep(3200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}}};
   assertThrows(IdentityCommands.Failure.class,()->runtime(delayed).detail(c,actor,id));
  }
  try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=? and summary_schema_code='ADM07_AUDIT_DISCLOSURE_V1'")){p.setObject(1,s.tenant());try(var r=p.executeQuery()){r.next();assertEquals(0,r.getInt(1));}}
 }
 private AuditReadRuntime runtime(AuditAppender appender){byte[] key=new byte[32];Arrays.fill(key,(byte)42);return new AuditReadRuntime(appender,new AuditRecordProtection(key));}
 private AuditRecordReader.Query query(){return new AuditRecordReader.Query(null,null,null,null,null,20,null);}
 @Test void query_detail_and_empty_page_commit_exactly_one_disclosure_each()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID id=AuditRecordReaderIT.append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  var before=businessCounts(s.tenant());
  try(var c=database.apiConnection()){
   var runtime=runtime(AuditAppender.databaseBacked("AUDIT_IT"));var page=runtime.read(c,actor,query());assertEquals(1,((List<?>)page.get("items")).size());assertEquals(id.toString(),runtime.detail(c,actor,id).get("id"));assertTrue(((List<?>)runtime.read(c,actor,new AuditRecordReader.Query(null,null,null,null,"unmatched",20,null)).get("items")).isEmpty());
  }
  try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=? and summary_schema_code='ADM07_AUDIT_DISCLOSURE_V1'")){p.setObject(1,s.tenant());try(var r=p.executeQuery()){r.next();assertEquals(3,r.getInt(1));}}
  assertEquals(before,businessCounts(s.tenant()),"Audit reads cannot create command, receipt, event, outbox or task rows");
  try(var c=database.migratorConnection();var p=c.prepareStatement("select change_summary::text from audit.audit_entry where tenant_id=? and summary_schema_code='ADM07_AUDIT_DISCLOSURE_V1'")){
   p.setObject(1,s.tenant());try(var rows=p.executeQuery()){while(rows.next()){String summary=rows.getString(1);assertTrue(summary.contains("scopeCategory"));assertTrue(summary.contains("resultCategory"));assertFalse(summary.contains("unmatched"));}}
  }
 }
 private List<Long> businessCounts(UUID tenant)throws Exception{
  var result=new ArrayList<Long>();
  try(var c=database.migratorConnection()){
   for(String table:List.of("execution.command_execution_slot","execution.command_receipt","execution.domain_event","execution.domain_event_outbox","responsibility.task_occurrence"))try(var p=c.prepareStatement("select count(*) from "+table+" where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();result.add(r.getLong(1));}}
  }
  return result;
 }
 @Test void append_failure_or_unknown_commit_never_returns_the_body()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID id=AuditRecordReaderIT.append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  AuditAppender fail=new AuditAppender(){public void append(Connection c,Entry e)throws SQLException{throw new SQLException("synthetic");}public void append(Connection c,AuditRecordDisclosureEntry e)throws SQLException{throw new SQLException("synthetic");}};
  try(var c=database.apiConnection()){assertThrows(SQLException.class,()->runtime(fail).detail(c,actor,id));}
  try(var c=database.apiConnection()){
   var unknown=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{try{if(method.getName().equals("commit")){c.commit();throw new SQLException("commit confirmation lost");}return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}});
   assertThrows(SQLException.class,()->runtime(AuditAppender.databaseBacked("AUDIT_IT")).read(unknown,actor,query()));
  }
 }
}
