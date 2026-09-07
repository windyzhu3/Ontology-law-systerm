package io.github.windyzhu3.ontologylaw.worker;
import java.util.*;
public final class R1WorkerTenantBindings {
    public record Binding(UUID tenantId,UUID principalId,UUID appointmentId,String credentialAlias,String certificateSha256) {}
    private final List<Binding> bindings;
    public R1WorkerTenantBindings(String release,List<Binding> bindings) {
        if(!"MVP-2026-09-07.1".equals(release)||bindings==null||bindings.isEmpty())throw invalid();
        var tenants=new HashSet<UUID>();var identities=new HashSet<String>();var aliases=new HashSet<String>();var actors=new HashSet<String>();
        for(var b:bindings)if(b==null||b.tenantId()==null||b.principalId()==null||b.appointmentId()==null||b.credentialAlias()==null||!b.credentialAlias().matches("[A-Za-z][A-Za-z0-9_]{0,63}")||b.certificateSha256()==null||!b.certificateSha256().matches("[0-9a-f]{64}")||!tenants.add(b.tenantId())||!identities.add(b.certificateSha256())||!aliases.add(b.credentialAlias().toLowerCase(Locale.ROOT))||!actors.add(b.principalId()+":"+b.appointmentId()))throw invalid();
        this.bindings=List.copyOf(bindings);
    }
    public List<Binding> bindings(){return bindings;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("R1_WORKER_BINDING_INVALID");}
}
