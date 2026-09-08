package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Short QUERY transaction for credential mapping; no authority is cached or granted here. */
public final class CredentialIdentityRuntime {
    public HumanIdentityReader.VerifiedHumanIdentity human(Connection connection,UUID tenant,String provider,byte[] hmac)throws SQLException {
        return inTransaction(connection,Capability.QUERY,c->{
            R1BusinessFence.databaseBacked().shared(c,tenant);AuthorizationService.databaseBacked().lockForEvaluation(c,tenant);
            var reader=HumanIdentityReader.databaseBacked();var identity=reader.unique(c,tenant,provider,hmac);reader.self(c,identity);return identity;
        });
    }
    public Actor selectHuman(Connection connection,HumanIdentityReader.VerifiedHumanIdentity identity,UUID selector)throws SQLException {
        return selectHuman(connection,identity,selector,null);
    }
    public Actor selectHuman(Connection connection,HumanIdentityReader.VerifiedHumanIdentity identity,UUID selector,UUID behalf)throws SQLException {
        return inTransaction(connection,Capability.QUERY,c->{
            R1BusinessFence.databaseBacked().shared(c,identity.tenantId());AuthorizationService.databaseBacked().lockForEvaluation(c,identity.tenantId());
            var reader=HumanIdentityReader.databaseBacked();var self=reader.self(c,identity);var actor=self.select(selector);
            if(actor==null)throw new HumanIdentityReader.Failure("NOT_AUTHORIZED");
            return reader.selectDelegated(self,actor,behalf,behalf==null?List.of():reader.delegated(c,identity,actor.appointmentId()));
        });
    }
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
