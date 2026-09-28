package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.TaskConfirmationReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class JooqTaskConfirmationReader implements TaskConfirmationReader {
 public Confirmation forTask(Connection c,UUID tenant,UUID task)throws SQLException {
  try(var p=c.prepareStatement("select action_draft_id,revision,action_code,payload_schema_code,payload_schema_version,confirmed_at,confirmed_payload_digest from responsibility.action_draft where tenant_id=? and task_occurrence_id=? and state='CONFIRMED' and confirmed_payload_digest=candidate_payload_digest")){
   p.setObject(1,tenant);p.setObject(2,task);
   try(var r=p.executeQuery()){
    if(!r.next())return null;
    var result=new Confirmation(new Subject("responsibility.action_draft",r.getObject(1,UUID.class),r.getLong(2),null),task,r.getString(3),r.getString(4),r.getInt(5),r.getObject(6,OffsetDateTime.class).toInstant(),Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(7)));
    if(r.next())throw new SQLException("Ambiguous original confirmation","22000");return result;
   }
  }
 }
}
