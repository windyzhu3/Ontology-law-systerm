package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.time.Instant;import java.util.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
/** Material reads and transport state changes use the same business/identity fence and fail-closed audit. */
public final class MaterialReadRuntime {
 public record Prepared<T>(T value,List<AuditAppender.MaterialDisclosureEntry> disclosures){}
 @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
 public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException{return run(connection,actor,audit,false,work);}
 public static <T>T transport(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException{return run(connection,actor,audit,true,work);}
 private static <T>T run(Connection connection,Actor actor,AuditAppender audit,boolean write,Work<T> work)throws SQLException{if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");return inTransaction(connection,write?Capability.COMMAND:Capability.QUERY,c->{R1BusinessFence.databaseBacked().shared(c,actor.tenantId());AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());var result=work.read(c,R1ServiceReadRuntime.databaseTime(c));for(var d:result.disclosures())if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");setLocalRole(c,Capability.AUDIT);for(var d:result.disclosures())audit.append(c,d);return result.value();});}
}
