package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;

/** Owner selection of independently complete authority paths; no SQL or generated types escape. */
public interface R1AuthorityReader {
    record Candidate(UUID appointmentId, UUID principalId, UUID organizationId, Instant startsAt, Request authorization) {}
    Request select(Connection connection, Actor actor, Subject subject, UUID organization, String slot, String authorityCode) throws SQLException;
    List<Candidate> candidates(Connection connection, UUID tenant, Subject subject, UUID organization, String slot, String authorityCode) throws SQLException;
    static R1AuthorityReader databaseBacked() {return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqR1AuthorityReader();}
}
