package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Short QUERY transaction for credential mapping; no authority is cached or granted here. */
public final class CredentialIdentityRuntime {
    public Actor certificate(Connection connection,Actor actor,String provider)throws SQLException {
        return inTransaction(connection,Capability.QUERY,c->{
            var registration=AuthorizationIdentityReader.databaseBacked().registration(c,actor.tenantId(),actor.appointmentId());
            return registration!=null&&actor.principalKind()==AuthorizationService.PrincipalKind.SERVICE
                    &&registration.principalKind()==actor.principalKind()&&registration.principalId().equals(actor.principalId())
                    &&registration.identityProviderCode().equals(provider)?actor:null;
        });
    }
    public record Candidate(Actor actor,String provider,byte[] hmac) {
        public Candidate{Objects.requireNonNull(actor);Objects.requireNonNull(provider);hmac=hmac.clone();}
        public byte[] hmac(){return hmac.clone();}
        public String toString(){return "CredentialCandidate[restricted]";}
    }
    public Actor unique(Connection connection,List<Candidate> candidates)throws SQLException {
        return inTransaction(connection,Capability.QUERY,c->{
            var reader=CredentialIdentityReader.databaseBacked();Actor match=null;
            for(var candidate:candidates)if(reader.matches(c,candidate.actor(),candidate.provider(),candidate.hmac())) {
                if(match!=null)return null;match=candidate.actor();
            }
            return match;
        });
    }
}
