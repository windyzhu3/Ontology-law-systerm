package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Exercise Owner-created responsibilities, including the authority repair and revision loop. */
class TeamQuoteCoverageIT extends R2QuoteWorkflowIT {
 @Test void preparation_authority_repair_return_delivery_and_reply_keep_exact_team_sources()throws Exception {
  setup(true,true);confirmed();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   for(var code:List.of("QUOTE_PREPARE","QUOTE_READ","TEAM_TASK_READ"))grant(x,code);return null;
  });}
  var evidence=material();
  quoteCommand("START_QUOTE_PREPARATION",Map.of());assertTeamSources();
  quoteCommand("FORM_QUOTE",commercial());assertTeamSources();
  assertEquals("OWNER_EXCEPTION",((Map<?,?>)context().get("workflow")).get("stage"));
  policy("REQUIRE_APPROVAL",List.of(seed.appointment()));
  quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());assertTeamSources();
  quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","RETURNED","reason","原审批退回本版"));assertTeamSources();
  quoteCommand("FORM_QUOTE",commercial());assertTeamSources();
  quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());assertTeamSources();
  quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","APPROVED","reason","修订版经人工同意"));assertTeamSources();
  quoteCommand("RECORD_QUOTE_DELIVERY",Map.of("recipient","客户联系人","recipientParticipation",((Map<?,?>)((List<?>)context().get("recipients")).getFirst()).get("selector"),"channel","EMAIL","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));assertTeamSources();
  quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","客户确认接受修订版","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
  var completed=assertTeamSources();
  assertTrue(completed.containsAll(Set.of("PREPARE_QUOTE","RESOLVE_QUOTE_AUTHORITY","SUBMIT_QUOTE_APPROVAL","APPROVE_QUOTE","DELIVER_QUOTE","RECORD_QUOTE_REPLY")),completed.toString());
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/r25-team-quote-covered-types.txt"),String.join("\n",completed)+"\n");
 }
 private Set<String> assertTeamSources()throws Exception {
  var service=new R2TeamManagementReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("TEAM_QUOTE_COVERAGE"));
  var completed=new TreeSet<String>();
  record Entry(UUID id,String type,String state){}
  List<Entry> entries=new ArrayList<>();
  try(var c=database.adminConnection();var p=c.prepareStatement("select task_occurrence_id,business_purpose_code,state from responsibility.task_occurrence where tenant_id=? order by task_occurrence_id")){
   p.setObject(1,seed.tenant());try(var rows=p.executeQuery()){while(rows.next())entries.add(new Entry(rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3)));}
  }
  for(var entry:entries){
   String view=switch(entry.state()){case "OPEN"->"tasks";case "WAITING"->"waiting";default->"history";};
   try(var c=database.apiConnection()){
    var detail=assertDoesNotThrow(()->service.detail(c,seed.request().actor(),view,entry.id()),entry.type()+" "+entry.state());
    assertEquals(entry.id().toString(),detail.get("id"));assertFalse(((List<?>)detail.get("facts")).isEmpty());
    if(view.equals("history")){assertNull(detail.get("action"));assertNull(detail.get("taskId"));}
    if(entry.state().equals("DONE"))completed.add(entry.type());
   }
  }
  return completed;
 }
}
