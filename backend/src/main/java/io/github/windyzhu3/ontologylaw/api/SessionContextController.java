package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.SessionApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.SessionContextV1;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.IdentitySelfReadRuntime;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;

@RestController
public class SessionContextController implements SessionApi {
    public static final class Services {
        private final ActorContextResolver.Connections connections;private final IdentitySelfReadRuntime runtime;
        public Services(ActorContextResolver.Connections connections,AuditAppender audit,ActorScopeProtection scopes){this.connections=connections;runtime=new IdentitySelfReadRuntime(audit,scopes);}
        public Map<String,Object> read(HumanIdentityReader.VerifiedHumanIdentity identity,UUID own,UUID behalf) {
            try(var c=connections.open()) {
                return runtime.read(c,identity,own,behalf,UUID.randomUUID(),(connection,actor,choice)->{
                    var authorities=R1AuthorityReader.databaseBacked();boolean workbench=false,admin=false;
                    for(var task:TaskFactory.Type.values())if(authorities.select(connection,actor,choice.organization(),choice.organization().id(),task.slot,task.authority)!=null){workbench=true;break;}
                    if(actor.onBehalfAppointmentId()==null) {
                        var root=HumanIdentityReader.databaseBacked().rootOrganization(connection,actor.tenantId());
                        for(String code:List.of("IDENTITY_PRINCIPAL_MANAGE","IDENTITY_ORGANIZATION_MANAGE","IDENTITY_APPOINTMENT_MANAGE","IDENTITY_AUTHORITY_MANAGE")) {
                            var target=code.equals("IDENTITY_PRINCIPAL_MANAGE")?root:choice.organization();
                            if(authorities.select(connection,actor,target,target.id(),"IDENTITY_ADMIN",code)!=null){admin=true;break;}
                        }
                    }
                    boolean exceptions=false;
                    if(actor.onBehalfAppointmentId()==null){var exceptionRights=OpportunityOwnerExceptionAuthorityReader.databaseBacked();var now=io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.databaseTime(connection);exceptions=exceptionRights.hasAuthority(connection,actor,"OPPORTUNITY_OWNER_EXCEPTION_READ",now)||exceptionRights.hasAuthority(connection,actor,"OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ",now);}
                    boolean ledger=actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&OpportunityLedgerAuthorityReader.databaseBacked().hasAuthority(connection,actor,io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.databaseTime(connection));
                    var named=OpportunityOwnerExceptionAuthorityReader.databaseBacked();var now=io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.databaseTime(connection);
                    boolean management=actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&(named.hasAuthority(connection,actor,"PAYMENT_LEDGER_READ",now)||named.hasAuthority(connection,actor,"TRANSFER_LEDGER_READ",now));
                    var views=new ArrayList<String>();if(actor.onBehalfAppointmentId()==null){for(var entry:Map.of("contracts","CONTRACT_READ","payments","PAYMENT_LEDGER_READ","transfer","TRANSFER_LEDGER_READ").entrySet())if(named.hasAuthority(connection,actor,entry.getValue(),now))views.add(entry.getKey());}views.sort(String::compareTo);
                    boolean overview=false;if(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null)for(var code:List.of("LEAD_MANAGEMENT_READ","OPPORTUNITY_LEDGER_READ","SALES_OPPORTUNITY_OWNER","CONTRACT_READ","TRANSFER_LEDGER_READ","TEAM_TASK_READ"))if(named.hasAuthority(connection,actor,code,now)){overview=true;break;}
                    return new IdentitySelfReadRuntime.EntryRights(workbench,admin,exceptions,ledger,management,List.copyOf(views),actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&named.hasAuthority(connection,actor,"TEAM_TASK_READ",now),actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&named.hasAuthority(connection,actor,"LEAD_MANAGEMENT_READ",now),overview,actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&named.hasAuthority(connection,actor,"AUDIT_READ",now));
                });
            }catch(HumanIdentityReader.Failure refused){throw new R1HttpFailure(refused.code());}
            catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
        }
    }
    private final ObjectProvider<Services> services;
    public SessionContextController(ObjectProvider<Services> services){this.services=services;}
    public ResponseEntity<SessionContextV1> getSessionContext(UUID own,UUID behalf) {
        Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(!(principal instanceof HumanIdentityReader.VerifiedHumanIdentity identity))throw new R1HttpFailure("NOT_AUTHORIZED");
        var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(service.read(identity,own,behalf),SessionContextV1.class));
    }
}
