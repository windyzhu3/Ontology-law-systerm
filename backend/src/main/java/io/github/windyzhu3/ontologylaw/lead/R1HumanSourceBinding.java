package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;

/** Deployment-only principal provenance; permission and appointment scope remain separate. */
public final class R1HumanSourceBinding {
    public record Entry(UUID tenantId,UUID principalId,String sourceAccountCode) {
        public Entry {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(principalId);
            if(sourceAccountCode==null||!sourceAccountCode.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("Registered source account required");
        }
    }
    private record Key(UUID tenantId,UUID principalId) {}
    private final Map<Key,String> accounts;
    private final Set<UUID> tenants;
    public R1HumanSourceBinding(List<Entry> entries,R1SourcePolicyRegistry sources) {
        Objects.requireNonNull(entries);Objects.requireNonNull(sources);
        var configured=new HashMap<Key,String>();var enabled=new HashSet<UUID>();var sourceOwners=new HashSet<String>();
        for(var entry:entries) {
            Objects.requireNonNull(entry);
            if(!sources.contains(entry.sourceAccountCode())||configured.putIfAbsent(new Key(entry.tenantId(),entry.principalId()),entry.sourceAccountCode())!=null
                ||!sourceOwners.add(entry.tenantId()+":"+entry.sourceAccountCode()))throw new IllegalArgumentException("Human sources must be registered and unambiguous");
            enabled.add(entry.tenantId());
        }
        accounts=Map.copyOf(configured);tenants=Set.copyOf(enabled);
    }
    public boolean enabled(UUID tenantId){return tenants.contains(Objects.requireNonNull(tenantId));}
    public boolean permits(Actor actor,String sourceAccountCode) {
        Objects.requireNonNull(actor);
        if(actor.principalKind()==PrincipalKind.SERVICE||!enabled(actor.tenantId()))return true;
        return actor.onBehalfAppointmentId()==null&&Objects.equals(accounts.get(new Key(actor.tenantId(),actor.principalId())),sourceAccountCode)&&sourceAccountCode!=null;
    }
}
