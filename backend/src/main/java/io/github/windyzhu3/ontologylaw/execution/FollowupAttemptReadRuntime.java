package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.time.Instant;import java.util.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
public final class FollowupAttemptReadRuntime {
 public record Prepared<T>(T value,List<AuditAppender.FollowupAttemptDisclosureEntry> disclosures){}
 @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
 public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException{if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");return inTransaction(connection,Capability.QUERY,c->{R1BusinessFence.databaseBacked().shared(c,actor.tenantId());Prepared<T> result;try(var identityRead=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())){result=work.read(c,R1ServiceReadRuntime.databaseTime(c));}setLocalRole(c,Capability.AUDIT);for(var d:result.disclosures())audit.append(c,d);return result.value();});}
}
