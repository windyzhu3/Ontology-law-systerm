package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import java.util.*;

/** Server-built exact source/authorization bindings. Caller input cannot select these entries. */
public record DisclosurePlan(List<Entry> entries,List<AuthorizationSnapshot> dependencies) {
    public record Entry(Subject disclosedSource,Subject authorizationAnchor,AuthorizationSnapshot authorization) {
        public Entry {
            Objects.requireNonNull(disclosedSource);Objects.requireNonNull(authorizationAnchor);Objects.requireNonNull(authorization);
            if(!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject())||authorization.stableDependencies()==null)
                throw new IllegalArgumentException("Complete source authorization required");
        }
    }
    public DisclosurePlan {
        entries=List.copyOf(entries);dependencies=List.copyOf(dependencies);
        var seen=new HashSet<Subject>();for(var e:entries)if(!seen.add(e.disclosedSource()))throw new IllegalArgumentException("Duplicate disclosed source");
    }
}
