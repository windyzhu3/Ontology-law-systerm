package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.sql.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Organization coverage only; no fabricated business Subject and no event DENY substitution. */
public interface R1ServiceAuthorityReader {
    record DueScope(boolean authorized,Set<UUID> ownerAppointments) {public DueScope{ownerAppointments=Set.copyOf(ownerAppointments);}}
    DueScope dueScope(Connection connection,Actor actor,String authorityCode,Instant checkedAt)throws SQLException;
    boolean projectionCoverage(Connection connection,Actor actor,Set<UUID> organizations,Instant checkedAt)throws SQLException;
    static R1ServiceAuthorityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqR1ServiceAuthorityReader();}
}
