package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.util.*;

public interface AuthorizationService {
    enum Path { DIRECT, DELEGATED, OBJECT, SYSTEM }
    enum PrincipalKind { HUMAN, SERVICE }
    record Actor(UUID tenantId, UUID principalId, UUID appointmentId, UUID onBehalfPrincipalId, UUID onBehalfAppointmentId, PrincipalKind principalKind) {
        /** Compatibility callers are HUMAN; SERVICE must be supplied by a trusted adapter. */
        public Actor(UUID tenantId,UUID principalId,UUID appointmentId,UUID onBehalfPrincipalId,UUID onBehalfAppointmentId) {
            this(tenantId,principalId,appointmentId,onBehalfPrincipalId,onBehalfAppointmentId,PrincipalKind.HUMAN);
        }
        public Actor {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(principalId); Objects.requireNonNull(appointmentId);
            Objects.requireNonNull(principalKind);
            if ((onBehalfPrincipalId == null) != (onBehalfAppointmentId == null)) throw new IllegalArgumentException("Incomplete represented actor");
            if (principalKind==PrincipalKind.SERVICE && onBehalfPrincipalId!=null) throw new IllegalArgumentException("SERVICE cannot represent another actor");
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
    /** Complete independent decisions; implementations may share a locked fact read, never an allow result. */
    default List<AuthorizationSnapshot> evaluateAll(Connection c,List<Request> requests,boolean finalCheck)throws SQLException {
        var result=new ArrayList<AuthorizationSnapshot>();for(var request:requests)result.add(evaluate(c,request,finalCheck));return List.copyOf(result);
    }
    /** Acquired before reading current Owner/organization facts, retained until transaction completion. */
    default void lockForEvaluation(Connection connection, UUID tenantId) throws SQLException {
        throw new SQLException("Identity shared lock support required", "0A000");
    }
    /** Explicit read-only scope. The identity lock is acquired before any reusable fact is read.
     * Close before leaving the transaction; callers must not mutate identity within this scope. */
    interface ReadScope extends AutoCloseable { void close(); }
    default ReadScope lockedReadScope(Connection connection,UUID tenantId)throws SQLException {
        lockForEvaluation(connection,tenantId);return ()->{};
    }
    /** All identity writers call before any mutation, and never acquire business locks afterwards. */
    void lockForMutation(Connection connection, UUID tenantId) throws SQLException;
    static AuthorizationService databaseBacked() { return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqAuthorizationService(); }
}
