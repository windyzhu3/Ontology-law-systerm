package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.lead.R1SourcePolicyRegistry;
import java.sql.Connection;
import java.util.*;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader;

/** API application boundary; HTTP and trusted actor resolution belong to the adapter. */
public final class R1ProjectionReadinessService {
    public record Response(int status,String errorCode,String cacheControl) {}
    private final R1SourcePolicyRegistry policies;
    public R1ProjectionReadinessService(R1SourcePolicyRegistry policies) {this.policies=Objects.requireNonNull(policies);}
    public Response check(Connection connection,Actor actor) {
        try {
            return R1ServiceReadRuntime.read(connection,actor,(c,now)->{
                var identity=AuthorizationIdentityReader.databaseBacked();var organizations=new HashSet<UUID>();
                for(var code:policies.intakeRootCodes()) {
                    var org=identity.organization(c,actor.tenantId(),code);
                    if(org==null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
                    organizations.add(org.id());
                }
                var owners=new HashSet<>(EventResponsibilityReader.databaseBacked().retainedR1OwnerAppointments(c,actor.tenantId()));
                owners.addAll(R1EventReaders.databaseBacked().retainedAssignmentOwners(c,actor.tenantId()));
                for(var id:owners) {
                    var owner=identity.owner(c,actor.tenantId(),id,now);
                    // Retained historical appointments remain scope anchors after a staff departure.
                    if(owner==null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
                    organizations.add(owner.organizationId());
                }
                var evaluatedAt=R1ServiceReadRuntime.databaseTime(c);
                if(!R1ServiceAuthorityReader.databaseBacked().projectionCoverage(c,actor,organizations,evaluatedAt))throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
                return new Response(204,null,"no-store");
            });
        } catch(R1ServiceReadRuntime.Failure failure){return new Response(failure.status(),failure.code(),"no-store");}
    }
}
