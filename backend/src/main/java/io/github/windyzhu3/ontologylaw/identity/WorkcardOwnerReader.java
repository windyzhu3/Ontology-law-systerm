package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Three independent disclosed sources. Callers authorize their binding to the actual Task/Lead. */
public interface WorkcardOwnerReader {
    record Appointment(Subject selector,UUID principalId,UUID organizationId,String role,String state,Instant from,Instant until) {}
    record Principal(Subject selector,String displayName,String kind,String state) {}
    record Organization(Subject selector,String code,String displayName,String state) {}
    record Owner(Appointment appointment,Principal principal,Organization organization) {}
    Owner read(Connection c,UUID tenant,UUID appointment)throws SQLException;
    static WorkcardOwnerReader databaseBacked() { return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqWorkcardOwnerReader(); }
}
