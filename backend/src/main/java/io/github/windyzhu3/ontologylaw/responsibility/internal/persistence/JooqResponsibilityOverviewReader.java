package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.TASK_OCCURRENCE;
public final class JooqResponsibilityOverviewReader implements ResponsibilityOverviewReader {
 public List<CurrentTaskReader.Task> overdue(Connection c,UUID tenant,Instant observed,UUID after,int limit)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Overview requires an active read transaction","25000");
  if(tenant==null||observed==null||limit<1||limit>100)throw new IllegalArgumentException("Bounded overview scan required");
  var t=TASK_OCCURRENCE;var condition=t.TENANT_ID.eq(tenant).and(t.STATE.in("OPEN","WAITING")).and(t.ORIGINAL_SLA_DUE_AT.lt(observed.atOffset(ZoneOffset.UTC)));if(after!=null)condition=condition.and(t.TASK_OCCURRENCE_ID.gt(after));
  return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(DSL.asterisk()).from(t).where(condition).orderBy(t.TASK_OCCURRENCE_ID).limit(limit).fetch().stream().map(JooqCurrentTaskReader::task).toList();
 }
}
