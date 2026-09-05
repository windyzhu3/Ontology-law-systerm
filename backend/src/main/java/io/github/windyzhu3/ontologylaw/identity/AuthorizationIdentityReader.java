package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

public interface AuthorizationIdentityReader {
    record Owner(UUID appointmentId, UUID principalId, UUID organizationId, boolean active, String evidence) {}
    record Registration(UUID tenantId,UUID principalId,UUID appointmentId,AuthorizationService.PrincipalKind principalKind,String identityProviderCode) {}
    /** Exact database identity behind a trusted static credential registration; no credential material. */
    Registration registration(Connection connection,UUID tenant,UUID appointment)throws SQLException;
    AuthorizationService.Subject organization(Connection connection, UUID tenant, String code) throws SQLException;
    Owner owner(Connection connection, UUID tenant, UUID appointment, Instant checkedAt) throws SQLException;
    static AuthorizationIdentityReader databaseBacked() {
        return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqAuthorizationIdentityReader();
    }
}
