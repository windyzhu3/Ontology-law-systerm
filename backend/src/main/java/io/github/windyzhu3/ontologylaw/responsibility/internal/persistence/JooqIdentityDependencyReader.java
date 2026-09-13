package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.IdentityDependencyReader;
import java.sql.*;
import java.util.UUID;
import org.jooq.*;
import org.jooq.impl.DSL;

public final class JooqIdentityDependencyReader implements IdentityDependencyReader {
    public boolean open(Connection c,UUID tenant,UUID appointment)throws SQLException {
        if(c.getAutoCommit())throw new SQLException("Dependency read requires transaction","25001");
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).fetchExists(DSL.selectOne().from("responsibility.task_occurrence").where("tenant_id=? and owner_appointment_id=? and state in ('OPEN','WAITING')",tenant,appointment));
    }
}
