package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
/** Exact trusted registration proof, not a business authorization decision. */
public interface CredentialIdentityReader {
    boolean matches(Connection connection,Actor candidate,String provider,byte[] subjectHmac)throws SQLException;
    static CredentialIdentityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqCredentialIdentityReader();}
}
