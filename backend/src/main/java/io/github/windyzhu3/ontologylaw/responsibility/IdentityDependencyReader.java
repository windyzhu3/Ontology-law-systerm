package io.github.windyzhu3.ontologylaw.responsibility;

import java.sql.*;
import java.util.UUID;

/** Exact Appointment dependencies under the caller's exclusive business fence; no Task locks. */
public interface IdentityDependencyReader {
    boolean open(Connection connection,UUID tenant,UUID appointment)throws SQLException;
    static IdentityDependencyReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqIdentityDependencyReader();}
}
