package io.github.windyzhu3.ontologylaw.execution;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Tenant transaction fence: acquire before Lead, Task, command slot and final identity locks. */
public interface R1BusinessFence {
    void shared(Connection connection,UUID tenantId)throws SQLException;
    void exclusive(Connection connection,UUID tenantId)throws SQLException;
    static R1BusinessFence databaseBacked(){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqR1BusinessFence();}
}
