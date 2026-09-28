package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
/** QUERY capability, tenant fence and identity lock; returns only after commit acknowledgement. */
public final class OpportunityLedgerReadRuntime {
    public record Prepared<T>(T value,List<AuditAppender.OpportunityLedgerDisclosureEntry> disclosures,List<AuditAppender.QuoteDisclosureEntry> quotes,List<AuditAppender.ContractDisclosureEntry> contracts){public Prepared(T value,List<AuditAppender.OpportunityLedgerDisclosureEntry> disclosures){this(value,disclosures,List.of(),List.of());}public Prepared(T value,List<AuditAppender.OpportunityLedgerDisclosureEntry> disclosures,List<AuditAppender.QuoteDisclosureEntry> quotes){this(value,disclosures,quotes,List.of());}}
    @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
    public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException {
        if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
        try(var metrics=new io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadMetrics(UUID.randomUUID())) {
            return inTransaction(metrics.connection(connection),Capability.QUERY,c->{
                R1BusinessFence.databaseBacked().shared(c,actor.tenantId());metrics.mark("opportunityBusinessLock");
                Prepared<T> result;
                try(var identityRead=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())) {
                    metrics.mark("identityLock");result=work.read(c,R1ServiceReadRuntime.databaseTime(c));metrics.mark("readAndAuthorize");
                }
                if(!result.disclosures().isEmpty()||!result.quotes().isEmpty()||!result.contracts().isEmpty()) {
                    setLocalRole(c,Capability.AUDIT);
                    audit.appendOpportunityLedger(c,result.disclosures(),result.quotes());
                    if(!result.contracts().isEmpty())audit.appendContracts(c,result.contracts());
                }
                metrics.mark("audit");return result.value();
            });
        }
    }
}
