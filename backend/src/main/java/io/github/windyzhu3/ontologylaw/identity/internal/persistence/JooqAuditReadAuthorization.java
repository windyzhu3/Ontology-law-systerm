package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;

public final class JooqAuditReadAuthorization implements AuditReadAuthorization {
 private final AuthorizationService authorization=AuthorizationService.databaseBacked();
 private static void own(Actor actor){if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new Failure("NOT_AUTHORIZED");}
 private org.jooq.DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
 private List<org.jooq.Record> grants(Connection c,Actor actor){own(actor);var rows=db(c).fetch("select authority_grant_id,scope_organization_unit_id from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='AUDIT_READ' order by authority_grant_id limit 257",actor.tenantId(),actor.appointmentId());if(rows.size()>256)throw new Failure("SERVICE_UNAVAILABLE");return List.copyOf(rows);}
 private Subject organization(Connection c,Actor actor,UUID id){var row=db(c).fetchOne("select revision from identity.organization_unit where tenant_id=? and organization_unit_id=?",actor.tenantId(),id);if(row==null)throw new Failure("NOT_AUTHORIZED");return new Subject("identity.organization_unit",id,row.get(0,Long.class),null);}
 public Access scopes(Connection c,Actor actor)throws SQLException{
  var scopes=new TreeSet<UUID>();AuthorizationSnapshot first=null;var dependencies=new StringBuilder();
  for(var row:grants(c,actor)){
   UUID scope=row.get("scope_organization_unit_id",UUID.class);var snapshot=authorization.evaluate(c,new Request(actor,organization(c,actor,scope),scope,new Requirement("AUDIT_READ","AUDIT_READ",Path.DIRECT,row.get("authority_grant_id",UUID.class))),true);
   dependencies.append(snapshot.stableDependencies()).append('\n');if(snapshot.allowed()){scopes.add(scope);if(first==null)first=snapshot;}
  }
  if(first==null)throw new Failure("NOT_AUTHORIZED");
  return new Access(List.copyOf(scopes),first,hash(dependencies.toString()));
 }
 public AuthorizationSnapshot authorize(Connection c,Actor actor,Subject auditFact,Subject sourceFact,UUID recordScope)throws SQLException{
  own(actor);if(auditFact==null||sourceFact==null)throw new Failure("NOT_AUTHORIZED");
  UUID scope=recordScope==null?HumanIdentityReader.databaseBacked().rootOrganization(c,actor.tenantId()).id():recordScope;
  var dependencies=new StringBuilder();AuthorizationSnapshot selected=null;
  for(var row:grants(c,actor)){
   var requirement=new Requirement("AUDIT_READ","AUDIT_READ",Path.DIRECT,row.get("authority_grant_id",UUID.class));
   var snapshots=authorization.evaluateAll(c,List.of(new Request(actor,auditFact,scope,requirement),new Request(actor,sourceFact,scope,requirement),new Request(actor,organization(c,actor,scope),scope,requirement)),true);
   for(var snapshot:snapshots)dependencies.append(snapshot.stableDependencies()).append('\n');
   if(snapshots.stream().allMatch(AuthorizationSnapshot::allowed)&&selected==null)selected=snapshots.getFirst();
  }
  if(selected==null)throw new Failure("NOT_AUTHORIZED");String evidence=selected.evidence()+"\nADM07_EXACT_SOURCE_SET:"+hash(dependencies.toString());
  return new AuthorizationSnapshot(selected.request(),selected.checkedAt(),true,null,selected.authorityFact(),evidence,digest(evidence),dependencies.toString());
 }
 private static String hash(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(s));}
 private static byte[] digest(String s){try{return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
