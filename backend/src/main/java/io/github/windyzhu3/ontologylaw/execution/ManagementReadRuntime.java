package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.Instant;import java.util.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
/** Separately audited management disclosure; never grants contract-body access. */
public final class ManagementReadRuntime {
 private ManagementReadRuntime(){}
 public record Prepared<T>(T value,List<AuditAppender.ManagementDisclosureEntry> disclosures){}
 @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
 public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException{
  if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
  try(var metrics=new io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadMetrics(UUID.randomUUID())){
  return inTransaction(metrics.connection(connection),Capability.QUERY,c->{
   R1BusinessFence.databaseBacked().shared(c,actor.tenantId());metrics.mark("managementBusinessLock");Prepared<T> result;
   try(var scope=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())){
    metrics.mark("identityLock");result=work.read(c,R1ServiceReadRuntime.databaseTime(c));metrics.mark("readAndAuthorize");
    for(var decision:AuthorizationService.databaseBacked().evaluateAll(c,result.disclosures().stream().map(d->d.authorization().request()).toList(),true))if(!decision.allowed())throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
   }
   setLocalRole(c,Capability.AUDIT);audit.appendManagement(c,result.disclosures());metrics.mark("managementAudit");return result.value();
  });
  }
 }
}
