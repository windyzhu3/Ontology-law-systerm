package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** One fenced observation across five metrics, retaining each existing disclosure authority. */
public final class BusinessOverviewReadRuntime {
 private BusinessOverviewReadRuntime(){}
 public record Prepared<T>(T value,List<AuditAppender.ManagementDisclosureEntry> management,
   List<AuditAppender.OpportunityLedgerDisclosureEntry> opportunities,List<AuditAppender.ContractDisclosureEntry> contracts){
  public Prepared{management=List.copyOf(management);opportunities=List.copyOf(opportunities);contracts=List.copyOf(contracts);}
 }
 @FunctionalInterface public interface Work<T>{Prepared<T> read(Connection c,Instant now)throws SQLException;}
 public static <T>T read(Connection connection,Actor actor,AuditAppender audit,Work<T> work)throws SQLException{
  if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
  return inTransaction(connection,Capability.QUERY,c->{
   R1BusinessFence.databaseBacked().shared(c,actor.tenantId());Prepared<T> result;
   try(var scope=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())){
    result=work.read(c,R1ServiceReadRuntime.databaseTime(c));
    var requests=new ArrayList<Request>();
    result.management().forEach(d->requests.add(d.authorization().request()));
    result.opportunities().forEach(d->requests.add(d.authorization().request()));
    result.contracts().forEach(d->requests.add(d.authorization().request()));
    var decisions=AuthorizationService.databaseBacked().evaluateAll(c,requests,true);
    if(decisions.size()!=requests.size()||decisions.stream().anyMatch(d->!d.allowed()))throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
   }
   if(!result.management().isEmpty()||!result.opportunities().isEmpty()||!result.contracts().isEmpty()){
    setLocalRole(c,Capability.AUDIT);
    if(!result.management().isEmpty())audit.appendManagement(c,result.management());
    if(!result.opportunities().isEmpty())audit.appendOpportunityLedger(c,result.opportunities(),List.of());
    if(!result.contracts().isEmpty())audit.appendContracts(c,result.contracts());
   }
   return result.value();
  });
 }
}
