package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import java.sql.Connection;
import java.time.*;
import org.jooq.*;
import org.jooq.impl.DSL;

public final class SensitiveReadClock {
    private SensitiveReadClock() {}
    public static Instant now(Connection c) {
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
            .select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class).toInstant();
    }
}
