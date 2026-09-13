package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;

/** Current identity prerequisite only; it never grants business visibility. */
public interface ActorIdentityReader {
    boolean active(Connection connection, AuthorizationService.Actor actor) throws SQLException;
    static ActorIdentityReader databaseBacked() {return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqActorIdentityReader();}
}
