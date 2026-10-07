package io.github.windyzhu3.ontologylaw.identity;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class AuditReadAuthorizationIT extends PostgresIntegrationTest {
 @Test void sales_and_identity_management_permissions_do_not_allow_audit_disclosure()throws Exception{
  for(String authority:List.of("LEAD_CAPTURE","IDENTITY_READ")){
   var s=seed(database,"HUMAN",authority);var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
   try(var c=database.apiConnection()){
    inTransaction(c,Capability.QUERY,x->{AuthorizationService.databaseBacked().lockForEvaluation(x,s.tenant());assertThrows(IdentityCommands.Failure.class,()->AuditReadAuthorization.databaseBacked().scopes(x,actor));return null;});
   }
  }
 }
 @Test void audit_permission_is_independent_current_and_self_only()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);
  var reader=AuditReadAuthorization.databaseBacked();var audit=new Subject("audit.audit_entry",UUID.randomUUID(),null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.QUERY,x->{AuthorizationService.databaseBacked().lockForEvaluation(x,s.tenant());assertEquals(List.of(s.org()),reader.scopes(x,actor).scopes());assertTrue(reader.authorize(x,actor,audit,s.request().subject(),s.org()).allowed());assertThrows(IdentityCommands.Failure.class,()->reader.scopes(x,new Actor(s.tenant(),s.principal(),s.appointment(),null,null,PrincipalKind.SERVICE)));assertThrows(IdentityCommands.Failure.class,()->reader.scopes(x,new Actor(s.tenant(),s.principal(),s.appointment(),s.principal(),s.appointment())));return null;});
   inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",s.tenant(),s.grant());return null;});
   inTransaction(c,Capability.QUERY,x->{assertThrows(IdentityCommands.Failure.class,()->reader.scopes(x,actor));return null;});
  }
 }
 @Test void original_object_deny_wins()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);var audit=new Subject("audit.audit_entry",UUID.randomUUID(),null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,object_subject_type,object_subject_id,object_subject_revision,access_code,effect_code,valid_from,state,created_at) values (?,?,?,?,?,?,0,'AUDIT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),s.request().subject().type(),s.subject());return null;});
   inTransaction(c,Capability.QUERY,x->{assertThrows(IdentityCommands.Failure.class,()->AuditReadAuthorization.databaseBacked().authorize(x,actor,audit,s.request().subject(),s.org()));return null;});
  }
 }
 @Test void exact_audit_hash_deny_wins()throws Exception{
  var s=seed(database,"HUMAN","AUDIT_READ");var actor=new Actor(s.tenant(),s.principal(),s.appointment(),null,null);UUID id=UUID.randomUUID();byte[] hash=new byte[32];var audit=new Subject("audit.audit_entry",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{sql(x,"insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_hash) values(?,?,?,?,'AUDIT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'audit.audit_entry',?,?)",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),id,hash);return null;});
   inTransaction(c,Capability.QUERY,x->{assertThrows(IdentityCommands.Failure.class,()->AuditReadAuthorization.databaseBacked().authorize(x,actor,audit,s.request().subject(),s.org()));return null;});
  }
 }
}
