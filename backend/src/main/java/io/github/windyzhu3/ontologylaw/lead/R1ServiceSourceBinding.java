package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;

/** R1_TRUSTED_SERVICE_SOURCE_BINDING_V1. Deployment configuration only, never request data. */
public final class R1ServiceSourceBinding {
    public record Entry(String issuer,String audience,String identityProviderCode,UUID tenantId,UUID principalId,
            UUID appointmentId,Set<String> sourceAccountCodes) {
        public Entry {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(principalId);Objects.requireNonNull(appointmentId);
            if(issuer==null || !issuer.matches("[A-Za-z][A-Za-z0-9+.-]*:[^\\s]+") || audience==null || audience.isBlank()
                    || identityProviderCode==null || identityProviderCode.isBlank())throw new IllegalArgumentException("Trusted issuer, audience and provider required");
            sourceAccountCodes=Set.copyOf(sourceAccountCodes);
            if(sourceAccountCodes.isEmpty() || sourceAccountCodes.stream().anyMatch(code->!code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")))
                throw new IllegalArgumentException("Nonempty registered source accounts required");
        }
    }
    private record Key(UUID tenant,UUID principal,UUID appointment) {}
    private final Map<Key,Entry> bindings;
    private final List<Entry> entries;
    private R1ServiceSourceBinding(Map<Key,Entry> bindings,List<Entry> entries){this.bindings=Map.copyOf(bindings);this.entries=List.copyOf(entries);}
    /** Startup validation uses the actual identity Owner reader on the assembly's QUERY transaction. */
    public static R1ServiceSourceBinding validate(Connection c,Collection<Entry> registrations,R1SourcePolicyRegistry sources)throws SQLException {
        if(c.getAutoCommit() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Binding validation requires READ COMMITTED transaction","25001");
        var identities=AuthorizationIdentityReader.databaseBacked();var bindings=new HashMap<Key,Entry>();var entries=List.copyOf(registrations);
        for(var entry:entries) {
            var actual=identities.registration(c,entry.tenantId(),entry.appointmentId());
            if(actual==null || !actual.principalId().equals(entry.principalId()) || actual.principalKind()!=PrincipalKind.SERVICE
                    || !actual.identityProviderCode().equals(entry.identityProviderCode()) || !entry.sourceAccountCodes().stream().allMatch(sources::contains))
                throw new IllegalArgumentException("Trusted SERVICE registration does not match Owner identity and source policies");
            // Actor intentionally carries no token material. One Actor cannot merge permissions across credential entries.
            if(bindings.put(new Key(entry.tenantId(),entry.principalId(),entry.appointmentId()),entry)!=null)
                throw new IllegalArgumentException("Ambiguous SERVICE Actor registration");
        }
        return new R1ServiceSourceBinding(bindings,entries);
    }
    public List<Entry> entries(){return entries;}
    public boolean allows(Connection c,Actor actor,String sourceAccountCode)throws SQLException {
        if(actor.principalKind()!=PrincipalKind.SERVICE || actor.onBehalfPrincipalId()!=null)return false;
        var entry=bindings.get(new Key(actor.tenantId(),actor.principalId(),actor.appointmentId()));
        if(entry==null || !entry.sourceAccountCodes().contains(sourceAccountCode))return false;
        var actual=AuthorizationIdentityReader.databaseBacked().registration(c,actor.tenantId(),actor.appointmentId());
        return actual!=null && actual.principalId().equals(entry.principalId()) && actual.principalKind()==PrincipalKind.SERVICE
                && actual.identityProviderCode().equals(entry.identityProviderCode());
    }
}
