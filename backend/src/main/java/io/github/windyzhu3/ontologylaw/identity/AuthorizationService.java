package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.util.*;

public interface AuthorizationService {
    enum Path { DIRECT, DELEGATED, OBJECT, SYSTEM }
    record Actor(UUID tenantId, UUID principalId, UUID appointmentId, UUID onBehalfPrincipalId, UUID onBehalfAppointmentId) {
        public Actor {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(principalId); Objects.requireNonNull(appointmentId);
            if ((onBehalfPrincipalId == null) != (onBehalfAppointmentId == null)) throw new IllegalArgumentException("Incomplete represented actor");
        }
    }
    record Subject(String type, UUID id, Long revision, String hash) {
        public Subject {
            Objects.requireNonNull(type); Objects.requireNonNull(id);
            if ((revision == null) == (hash == null)) throw new IllegalArgumentException("Exact selector required");
            if (revision != null && (revision < 0 || revision > 9007199254740991L)) throw new IllegalArgumentException("Unsafe revision");
            if (hash != null && (hash.length() != 43 || Base64.getUrlDecoder().decode(hash).length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(hash)).equals(hash))) throw new IllegalArgumentException("Invalid hash");
        }
    }
    record Requirement(String authorityCode, String slot, Path path, UUID authorityFactId) {
        public Requirement {
            if (!authorityCode.matches("[A-Z][A-Z0-9_]{0,63}") || !slot.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid authority code");
            Objects.requireNonNull(path); Objects.requireNonNull(authorityFactId);
        }
    }
    record Request(Actor actor, Subject subject, UUID scopeOrganizationId, Requirement requirement) {
        public Request { Objects.requireNonNull(actor); Objects.requireNonNull(subject); Objects.requireNonNull(scopeOrganizationId); Objects.requireNonNull(requirement); }
    }
    AuthorizationSnapshot evaluate(Connection connection, Request request, boolean finalCheck) throws SQLException;
    /** All identity writers call before any mutation, and never acquire business locks afterwards. */
    void lockForMutation(Connection connection, UUID tenantId) throws SQLException;
    static AuthorizationService databaseBacked() { return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqAuthorizationService(); }
}
