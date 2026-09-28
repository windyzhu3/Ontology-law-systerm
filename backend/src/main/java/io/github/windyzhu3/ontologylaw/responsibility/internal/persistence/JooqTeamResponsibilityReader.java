package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.TASK_OCCURRENCE;
/** UUID keyset over exact task occurrences; no owner impersonation or unbounded hydration. */
public final class JooqTeamResponsibilityReader implements TeamResponsibilityReader {
 public List<CurrentTaskReader.Task> currentForLead(Connection c,UUID tenant,UUID lead)throws SQLException{return leadTasks(c,tenant,lead,true);}
 public CurrentTaskReader.Task lastDecisionForLead(Connection c,UUID tenant,UUID lead)throws SQLException{var rows=leadTasks(c,tenant,lead,false);return rows.isEmpty()?null:rows.getFirst();}
 private List<CurrentTaskReader.Task> leadTasks(Connection c,UUID tenant,UUID lead,boolean active)throws SQLException{
  if(c.getAutoCommit())throw new SQLException("Lead responsibility read requires a transaction","25000");
  var t=TASK_OCCURRENCE;var condition=t.TENANT_ID.eq(tenant).and(t.SUBJECT_TYPE.eq("lead.lead")).and(t.SUBJECT_ID.eq(lead));
  condition=active?condition.and(t.STATE.in("OPEN","WAITING")):condition.and(t.STATE.eq("DONE")).and(t.COMPLETION_FACT_TYPE.eq("responsibility.decision_record"));
  // Two current rows are enough to flag an inconsistent early-lead responsibility; do not hide it as one owner.
  return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(DSL.asterisk()).from(t).where(condition).orderBy(t.CREATED_AT.desc(),t.TASK_OCCURRENCE_ID.desc()).limit(active?2:1).fetch().stream().map(JooqCurrentTaskReader::task).toList();
 }
 public List<CurrentTaskReader.Task> scan(Connection c,UUID tenant,View view,UUID after,int limit)throws SQLException{
  if(tenant==null||view==null||limit<1||limit>100)throw new IllegalArgumentException("Bounded team scan required");
  var t=TASK_OCCURRENCE;var states=switch(view){case TASKS->List.of("OPEN");case WAITING->List.of("WAITING");case HISTORY->List.of("DONE","CANCELLED");};
  var condition=t.TENANT_ID.eq(tenant).and(t.STATE.in(states));if(after!=null)condition=condition.and(t.TASK_OCCURRENCE_ID.gt(after));
  return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(DSL.asterisk()).from(t).where(condition).orderBy(t.TASK_OCCURRENCE_ID).limit(limit).fetch().stream().map(JooqCurrentTaskReader::task).toList();
 }
}
