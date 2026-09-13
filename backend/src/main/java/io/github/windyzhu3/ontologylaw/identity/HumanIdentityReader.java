package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;

/** Principal-only credential mapping and bounded authenticated-self identity reads. */
public interface HumanIdentityReader {
    record VerifiedHumanIdentity(UUID tenantId,UUID principalId,String provider) {
        public VerifiedHumanIdentity {Objects.requireNonNull(tenantId);Objects.requireNonNull(principalId);Objects.requireNonNull(provider);}
        public String toString(){return "VerifiedHumanIdentity[restricted]";}
    }
    record Choice(Subject appointment,Subject organization,String label) {}
    record DelegatedChoice(Choice choice,UUID principalId) {}
    record Self(VerifiedHumanIdentity identity,Subject principal,String displayName,List<Choice> choices) {
        public Self{choices=List.copyOf(choices);}
        public Actor select(UUID selector) {
            UUID selected=selector;
            if(selected==null&&choices.size()==1)selected=choices.getFirst().appointment().id();
            if(selected==null)return null;
            UUID exact=selected;
            if(choices.stream().filter(c->c.appointment().id().equals(exact)).count()!=1)throw new Failure("NOT_AUTHORIZED");
            return new Actor(identity.tenantId(),identity.principalId(),exact,null,null,PrincipalKind.HUMAN);
        }
    }
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code){super(code,null,false,false);this.code=code;}
        public String code(){return code;}
    }
    VerifiedHumanIdentity unique(Connection connection,UUID tenant,String provider,byte[] subjectHmac)throws SQLException;
    boolean tenantActive(Connection connection,UUID tenant)throws SQLException;
    Subject rootOrganization(Connection connection,UUID tenant)throws SQLException;
    Self self(Connection connection,VerifiedHumanIdentity identity)throws SQLException;
    List<DelegatedChoice> delegated(Connection connection,VerifiedHumanIdentity identity,UUID ownAppointment)throws SQLException;
    default Actor selectDelegated(Self self,Actor own,UUID behalf,List<DelegatedChoice> candidates) {
        if(behalf==null)return own;
        if(own==null)throw new Failure("NOT_AUTHORIZED");
        var selected=candidates.stream().filter(candidate->candidate.choice().appointment().id().equals(behalf)).findFirst().orElseThrow(()->new Failure("NOT_AUTHORIZED"));
        return new Actor(self.identity().tenantId(),self.identity().principalId(),own.appointmentId(),selected.principalId(),behalf,PrincipalKind.HUMAN);
    }
    static HumanIdentityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqHumanIdentityReader();}
}
