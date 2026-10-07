package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import io.github.windyzhu3.ontologylaw.identity.R1AuthorityReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Trusted intake presentation metadata filtered by the exact existing capture authority. */
public final class LeadIntakeSources {
    public record Source(String sourceAccountCode, String displayName, String sourceChannelCode,
                         String serviceCategoryCode, String jurisdictionCode, String urgencyCode) {
        public Source {
            if (sourceAccountCode == null || !sourceAccountCode.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Registered source account required");
            code(sourceChannelCode); code(serviceCategoryCode); code(jurisdictionCode); code(urgencyCode);
            if (displayName == null || displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 200
                    || displayName.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid source display name");
        }
    }
    private final R1SourcePolicyRegistry policies;
    private final List<Source> configured;
    private final R1HumanSourceBinding bindings;
    private final AuthorizationService authorization = AuthorizationService.databaseBacked();
    private final AuthorizationIdentityReader identity = AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authorities = R1AuthorityReader.databaseBacked();

    /** No source configuration is accepted from a user command or browser request. */
    public LeadIntakeSources(R1SourcePolicyRegistry policies, List<Source> configured) {
        this(policies,configured,new R1HumanSourceBinding(List.of(),policies));
    }
    public LeadIntakeSources(R1SourcePolicyRegistry policies,List<Source> configured,R1HumanSourceBinding bindings) {
        this.policies = Objects.requireNonNull(policies);
        this.bindings=Objects.requireNonNull(bindings);
        Objects.requireNonNull(configured);
        if (configured.size() > 50) throw new IllegalArgumentException("Intake catalog is limited to 50 sources");
        var accounts = new HashSet<String>();
        var names = new HashSet<String>();
        for (var source : configured) {
            Objects.requireNonNull(source);
            if (!policies.contains(source.sourceAccountCode()) || !accounts.add(source.sourceAccountCode()) || !names.add(source.displayName()))
                throw new IllegalArgumentException("Sources must be registered and unambiguous");
        }
        this.configured = configured.stream().sorted(Comparator.comparing(Source::displayName).thenComparing(Source::sourceAccountCode)).toList();
    }

    /** Caller retains the transaction/identity lock through disclosure. Listing never grants permission to write. */
    public List<Source> read(Connection connection, Actor actor) throws SQLException {
        Objects.requireNonNull(actor);
        if (actor.principalKind() != PrincipalKind.HUMAN || actor.onBehalfAppointmentId() != null)
            throw new IllegalArgumentException("Intake requires the current human appointment");
        if (connection.getAutoCommit()) throw new SQLException("Intake source reading requires an active transaction", "25000");
        authorization.lockForEvaluation(connection, actor.tenantId());
        var result = new ArrayList<Source>();
        for (var source : configured) {
            if(!bindings.permits(actor,source.sourceAccountCode()))continue;
            var root = identity.organization(connection, actor.tenantId(), policies.find(source.sourceAccountCode()).sourceIntakeRootCode());
            if (root == null) continue;
            var request = authorities.select(connection, actor, root, root.id(), "SOURCE_INTAKE_OWNER", "LEAD_CAPTURE");
            if (request != null && authorization.evaluate(connection, request, true).allowed()) result.add(source);
        }
        return List.copyOf(result);
    }
    private static void code(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Registered Code64 required");
    }
}
