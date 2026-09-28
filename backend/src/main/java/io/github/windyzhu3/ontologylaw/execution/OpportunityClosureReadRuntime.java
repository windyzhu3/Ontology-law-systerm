package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
/** QUERY capability, tenant fence and identity lock; returns only after commit acknowledgement. */
public final class OpportunityClosureReadRuntime {
    public record Prepared<T>(T value,List<AuditAppender.OpportunityClosureDisclosureEntry> disclosures){}
    @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
    public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException {
        if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
        return inTransaction(connection,Capability.QUERY,c->{R1BusinessFence.databaseBacked().shared(c,actor.tenantId());AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());var result=work.read(c,R1ServiceReadRuntime.databaseTime(c));if(!result.disclosures().isEmpty()){setLocalRole(c,Capability.AUDIT);for(var disclosure:result.disclosures())audit.append(c,disclosure);}return result.value();});
    }
}
