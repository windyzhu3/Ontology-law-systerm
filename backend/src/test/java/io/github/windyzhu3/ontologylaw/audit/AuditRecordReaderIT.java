package io.github.windyzhu3.ontologylaw.audit;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.*;
public class AuditRecordReaderIT extends PostgresIntegrationTest {
 @Test void safe_search_matches_display_categories_and_unicode_fallbacks_without_searching_raw_summary()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=AuditRecordReader.databaseBacked();var now=Instant.now().plusSeconds(1);for(String search:List.of("FIxTURE","线索","对象","执行操作","成功"))assertEquals(1,reader.list(x,actor,new AuditRecordReader.Query(now.minusSeconds(86400),now,null,null,search,20,null),new AuditRecordReader.Position(null,null,now)).items().size(),search);assertTrue(reader.list(x,actor,new AuditRecordReader.Query(now.minusSeconds(86400),now,null,null,"HMAC_TOKEN_CONTACT",20,null),new AuditRecordReader.Position(null,null,now)).items().isEmpty());return null;});}
  for(String name:List.of("private-name\u200B","İpek","A%B_C\\D")){
   try(var c=database.migratorConnection()){sql(c,"update identity.principal set display_name=?,revision=revision+1 where tenant_id=? and principal_id=?",name,s.tenant(),s.principal());}
   String search=name.startsWith("private")?"未提供显示名称":name.equals("İpek")?"i\u0307pek":name;
   try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var now=Instant.now().plusSeconds(1);assertEquals(1,AuditRecordReader.databaseBacked().list(x,actor,new AuditRecordReader.Query(now.minusSeconds(86400),now,null,null,search,20,null),new AuditRecordReader.Position(null,null,now)).items().size());return null;});}
  }
 }
 @Test void original_workcard_disclosure_writer_is_readable_without_rewriting_its_digest()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID id=UUID.randomUUID();var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.AUDIT,x->{var snapshot=new AuthorizationSnapshot(s.request(),Instant.now(),true,null,new Subject("identity.authority_grant",s.grant(),0L,null),"fixture",ReceiptAuditJson.digest("fixture"));AuditAppender.databaseBacked("AUDIT_IT").append(x,new AuditAppender.ReadDisclosureEntry(id,UUID.randomUUID(),s.request().subject(),s.request().subject(),snapshot,AuditAppender.ResponseMode.BODY));return null;});}
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var record=AuditRecordReader.databaseBacked().find(x,actor,id);assertNotNull(record);assertTrue(((String)record.values().get("summary")).contains("读取"));return null;});}
 }
 @Test void unknown_scope_path_and_source_types_fail_closed_before_any_projection()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID original=append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  for(String kind:List.of("SCOPE","PATH","SUBJECT")){
   UUID id=UUID.randomUUID();copy(s,original,id,null,kind);
   try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertNull(AuditRecordReader.databaseBacked().find(x,actor,id),kind+" must not disclose unclassified record metadata");return null;});}
  }
 }
 @Test void a_long_correction_chain_continues_after_the_first_fifty_nodes()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID original=append(database,s,UUID.randomUUID()),parent=original;
  for(int i=0;i<50;i++){UUID id=UUID.randomUUID();copy(s,parent,id,parent,null);parent=id;}
  var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=AuditRecordReader.databaseBacked();var seed=reader.find(x,actor,original);Instant now=Instant.now().plusSeconds(1);var query=new AuditRecordReader.Query(now.minusSeconds(86400),now,null,null,null,20,null);var position=new AuditRecordReader.Position(null,null,now);var ids=new HashSet<UUID>();int pages=0;
   while(true){var page=reader.related(x,actor,seed,AuditRecordReader.Relation.CORRECTION,query,position);assertTrue(page.items().size()<=20);for(var item:page.items())assertTrue(ids.add(item.fact().id()));pages++;if(!page.hasMore())break;assertTrue(pages<4);var last=page.items().getLast();position=new AuditRecordReader.Position(last.trustedAt(),last.fact().id(),now);}
   assertEquals(51,ids.size());assertEquals(3,pages);return null;});}
 }
 private void copy(AuthorizationServiceIT.Seed s,UUID original,UUID id,UUID parent,String unknown)throws Exception{
  String scope="SCOPE".equals(unknown)?"'UNREGISTERED_SCOPE'":"audit_scope_code",path="PATH".equals(unknown)?"'UNREGISTERED_PATH'":"authorization_path_code",source="SUBJECT".equals(unknown)?"'future.private_source'":"subject_type";
  try(var c=database.migratorConnection()){sql(c,"insert into audit.audit_entry (tenant_id,audit_entry_id,entry_type,audit_scope_code,trusted_at,action_code,result_code,actor_principal_id,actor_appointment_id,correlation_id,authorization_slot_code,authorization_path_code,authorization_scope_organization_unit_id,authorization_snapshot_digest,trace_id,service_role_code,execution_node_code,summary_schema_code,summary_schema_version,change_summary,change_summary_digest,subject_type,subject_id,subject_revision,subject_hash,correction_target_type,correction_target_id,correction_target_hash) select tenant_id,?,? ,"+scope+",clock_timestamp(),action_code,result_code,actor_principal_id,actor_appointment_id,correlation_id,authorization_slot_code,"+path+",authorization_scope_organization_unit_id,authorization_snapshot_digest,trace_id,service_role_code,execution_node_code,summary_schema_code,summary_schema_version,change_summary,change_summary_digest,"+source+",subject_id,subject_revision,subject_hash,?,?,? from audit.audit_entry where tenant_id=? and audit_entry_id=?",id,parent==null?"EVENT":"CORRECTION",parent==null?null:"audit.audit_entry",parent,parent==null?null:ReceiptAuditJson.digest("{\"secret\":\"HMAC_TOKEN_CONTACT\"}"),s.tenant(),original);}
 }
 @Test void correction_chain_only_follows_existing_exact_audit_hash_edges()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID original=append(database,s,UUID.randomUUID()),correction=UUID.randomUUID();
  try(var c=database.migratorConnection()){sql(c,"insert into audit.audit_entry (tenant_id,audit_entry_id,entry_type,audit_scope_code,trusted_at,action_code,result_code,actor_principal_id,actor_appointment_id,correlation_id,authorization_slot_code,authorization_path_code,authorization_scope_organization_unit_id,authorization_snapshot_digest,trace_id,service_role_code,execution_node_code,summary_schema_code,summary_schema_version,change_summary,change_summary_digest,subject_type,subject_id,subject_revision,subject_hash,correction_target_type,correction_target_id,correction_target_hash) select tenant_id,?,'CORRECTION',audit_scope_code,clock_timestamp(),action_code,result_code,actor_principal_id,actor_appointment_id,correlation_id,authorization_slot_code,authorization_path_code,authorization_scope_organization_unit_id,authorization_snapshot_digest,trace_id,service_role_code,execution_node_code,summary_schema_code,summary_schema_version,change_summary,change_summary_digest,subject_type,subject_id,subject_revision,subject_hash,'audit.audit_entry',audit_entry_id,change_summary_digest from audit.audit_entry where tenant_id=? and audit_entry_id=?",correction,s.tenant(),original);}
  var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=AuditRecordReader.databaseBacked();var seed=reader.find(x,actor,original);Instant now=Instant.now();var query=new AuditRecordReader.Query(now.minusSeconds(86400),now.plusSeconds(1),null,null,null,20,null);var page=reader.related(x,actor,seed,AuditRecordReader.Relation.CORRECTION,query,new AuditRecordReader.Position(null,null,now.plusSeconds(1)));assertEquals(Set.of(original,correction),new HashSet<>(page.items().stream().map(r->r.fact().id()).toList()));return null;});}
 }
 @Test void organization_scope_uses_record_scope_instead_of_actor_department_and_chains_filter_each_node()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID first=UUID.randomUUID(),other=UUID.randomUUID(),grant=UUID.randomUUID(),correlation=UUID.randomUUID();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(var org:List.of(first,other))sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,?,'Sales scope','ACTIVE',clock_timestamp())",s.tenant(),org,s.org(),org.toString());sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",s.tenant(),s.grant());sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'AUDIT_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant(),grant,s.appointment(),s.appointment(),first);return null;});}
  var a=new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),first,grant,s.subject());var b=new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),other,grant,s.subject());UUID visible=append(database,a,correlation),hidden=append(database,b,correlation);UUID root=append(database,s,correlation);var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var reader=AuditRecordReader.databaseBacked();var seed=reader.find(x,actor,visible);assertNotNull(seed);assertNull(reader.find(x,actor,hidden));assertNull(reader.find(x,actor,root));Instant now=Instant.now();var query=new AuditRecordReader.Query(now.minusSeconds(86400),now.plusSeconds(1),null,null,null,20,null);var page=reader.related(x,actor,seed,AuditRecordReader.Relation.CORRELATION,query,new AuditRecordReader.Position(null,null,now.plusSeconds(1)));assertEquals(List.of(visible),page.items().stream().map(r->r.fact().id()).toList());return null;});}
 }
 public static UUID append(Database db,AuthorizationServiceIT.Seed s,UUID correlation)throws Exception{
  UUID id=UUID.randomUUID();String summary="{\"secret\":\"HMAC_TOKEN_CONTACT\"}";
  try(var c=db.apiConnection()){inTransaction(c,Capability.AUDIT,x->{var request=new Request(new Actor(s.tenant(),s.principal(),s.appointment(),null,null),s.request().subject(),s.org(),new Requirement("AUDIT_READ","AUDIT_READ",Path.DIRECT,s.grant()));var snapshot=new AuthorizationSnapshot(request,Instant.now(),true,null,new Subject("identity.authority_grant",s.grant(),0L,null),"fixture",ReceiptAuditJson.digest("fixture"));AuditAppender.databaseBacked("AUDIT_IT").append(x,new AuditAppender.Entry(id,UUID.randomUUID(),"SYNTHETIC_COMMAND",correlation,"SUCCEEDED",snapshot,summary,ReceiptAuditJson.digest(summary)));return null;});}return id;
 }
 @Test void classified_read_hides_raw_summary_and_cross_tenant_and_basetable()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");UUID id=append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.QUERY,x->{var row=AuditRecordReader.databaseBacked().find(x,actor,id);assertNotNull(row);assertFalse(row.values().toString().contains("HMAC_TOKEN"));assertNull(AuditRecordReader.databaseBacked().find(x,new Actor(UUID.randomUUID(),s.principal(),s.appointment(),null,null),id));assertThrows(java.sql.SQLException.class,()->{try(var p=x.prepareStatement("select audit_entry_id from audit.audit_entry")){p.executeQuery();}});return null;});
  }
 }
 @Test void paging_and_safe_search_ignore_secret_metadata()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");append(database,s,UUID.randomUUID());var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var now=Instant.now();var query=new AuditRecordReader.Query(now.minusSeconds(86400),now.plusSeconds(1),null,null,"HMAC_TOKEN_CONTACT",20,null);assertTrue(AuditRecordReader.databaseBacked().list(x,actor,query,new AuditRecordReader.Position(null,null,now.plusSeconds(1))).items().isEmpty());return null;});}
 }
}
