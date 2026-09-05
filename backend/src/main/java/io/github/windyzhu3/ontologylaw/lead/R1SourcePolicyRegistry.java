package io.github.windyzhu3.ontologylaw.lead;

import java.time.ZoneId;
import java.util.*;

/** R1SourcePolicyRegistryV1: immutable values supplied by trusted application assembly. */
public final class R1SourcePolicyRegistry {
    public enum AssignmentMode { MANUAL, AUTOMATIC }
    public record SourcePolicy(AssignmentMode assignmentMode, List<String> routingOrganizationRootCodes,
            String routingSupervisorRootCode, String sourceIntakeRootCode, String businessTimezone) {
        public SourcePolicy {
            Objects.requireNonNull(assignmentMode);
            routingOrganizationRootCodes=List.copyOf(routingOrganizationRootCodes);
            if(routingOrganizationRootCodes.isEmpty() || new HashSet<>(routingOrganizationRootCodes).size()!=routingOrganizationRootCodes.size())
                throw new IllegalArgumentException("Routing roots must be nonempty and unique");
            routingOrganizationRootCodes.forEach(R1SourcePolicyRegistry::code);
            code(routingSupervisorRootCode);code(sourceIntakeRootCode);
            if(!ZoneId.getAvailableZoneIds().contains(businessTimezone) || ZoneId.of(businessTimezone).getRules().isFixedOffset())
                throw new IllegalArgumentException("IANA business timezone required");
        }
    }
    private final Map<String,SourcePolicy> sources;
    public R1SourcePolicyRegistry(Map<String,SourcePolicy> sources) {
        sources.keySet().forEach(R1SourcePolicyRegistry::code);
        this.sources=Map.copyOf(sources);
    }
    public SourcePolicy find(String sourceAccountCode) { return sources.get(sourceAccountCode); }
    private static void code(String code) {
        if(code==null || !code.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("Registered ASCII code required");
    }
}
