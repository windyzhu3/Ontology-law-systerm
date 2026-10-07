package io.github.windyzhu3.ontologylaw.api;
import java.util.*;

/** Deployment-only targets for seven existing stages; neither authority nor approval policy. */
public final class BusinessResponsibilityRouting {
    private static final Set<String> STAGES=Set.of("AWAIT_REVIEW","AWAIT_VERIFICATION","ARCHIVE","CHECK_RECEIPT","REVIEW_TRANSFER","INTAKE","CLASSIFY");
    public record Entry(UUID tenantId,UUID sourceOrganizationId,String stageCode,UUID appointmentId){
        public Entry{Objects.requireNonNull(tenantId);Objects.requireNonNull(sourceOrganizationId);Objects.requireNonNull(appointmentId);if(!STAGES.contains(stageCode))throw new IllegalArgumentException("Existing responsibility stage required");}
    }
    private record Key(UUID tenant,UUID organization,String stage){}
    private final Map<Key,UUID> targets;private final Set<UUID> tenants;
    public BusinessResponsibilityRouting(List<Entry> entries){
        var targets=new HashMap<Key,UUID>();var tenants=new HashSet<UUID>();
        for(var entry:List.copyOf(entries)){if(targets.putIfAbsent(new Key(entry.tenantId(),entry.sourceOrganizationId(),entry.stageCode()),entry.appointmentId())!=null)throw new IllegalArgumentException("Duplicate responsibility target");tenants.add(entry.tenantId());}
        this.targets=Map.copyOf(targets);this.tenants=Set.copyOf(tenants);
    }
    public Optional<UUID> target(UUID tenantId,UUID sourceOrganizationId,String stageCode){if(!STAGES.contains(stageCode))throw new IllegalArgumentException("Existing responsibility stage required");return Optional.ofNullable(targets.get(new Key(tenantId,sourceOrganizationId,stageCode)));}
    public boolean enabled(UUID tenantId){return tenants.contains(tenantId);}
}
