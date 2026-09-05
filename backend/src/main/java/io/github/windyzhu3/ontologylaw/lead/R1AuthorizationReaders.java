package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.R1AuthorizationFacts;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import io.github.windyzhu3.ontologylaw.responsibility.AuthorizationTaskReader;

/** Trusted application assembly of concrete Owner ports; execution has no downstream dependency. */
public final class R1AuthorizationReaders {
    private R1AuthorizationReaders() {}
    public static R1AuthorizationFacts databaseBacked(R1SourcePolicyRegistry sources) {
        return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqR1AuthorizationFacts(
                sources, AuthorizationTaskReader.databaseBacked(), AuthorizationIdentityReader.databaseBacked());
    }
}
