package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.time.Instant;import java.util.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
public final class ContractReadRuntime {
 public record Prepared<T>(T value,List<AuditAppender.ContractDisclosureEntry> disclosures){}
 @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
 public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException {
  if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
  try(var metrics=new io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadMetrics(UUID.randomUUID())) {
   return inTransaction(metrics.connection(connection),Capability.QUERY,c->{
    R1BusinessFence.databaseBacked().shared(c,actor.tenantId());metrics.mark("contractBusinessLock");Prepared<T> result;
    try(var identityRead=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())) {
     metrics.mark("identityLock");result=work.read(c,R1ServiceReadRuntime.databaseTime(c));metrics.mark("readAndAuthorize");
    }
    setLocalRole(c,Capability.AUDIT);audit.appendContracts(c,result.disclosures());
    metrics.mark("audit");return result.value();
   });
  }
 }
}
