package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Real Owner workflow and protected facts; setup authority ports remain the existing explicit fixture. */
class TeamContractRemainderCoverageIT extends R2ContractWorkflowPersistenceIT {
 @Test void returned_request_preparation_and_review_supplement_keep_their_original_completed_sources()throws Exception {
  versionFixture();
  command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","重新申请准确准备范围")));
  command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","RETURNED","reason","请核对本次范围")));
  command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","已经核对本次范围")));
  command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","APPROVED","reason","批准准确准备范围")));
  formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));scopeComplete=false;
  command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","NEED_INFO","reason","请补齐本次审查资料")));scopeComplete=true;
  command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of("reason","按原审查范围逐项补正")));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});}
  var reader=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_CONTRACT_REMAINDER"));
  var completed=new TreeSet<String>();
  for(var type:List.of("REQUEST_CONTRACT_PREPARATION","PREPARE_CONTRACT","SUPPLEMENT_CONTRACT_REVIEW")){
   UUID task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and business_purpose_code=? and state='DONE'",seed.tenant(),type));
   try(var c=database.apiConnection()){
    var detail=reader.detail(c,actor(),"history",task);var fields=(List<?>)detail.get("facts");assertNull(detail.get("action"));assertNull(detail.get("taskId"));
    for(var label:List.of("当时确认人","处理时间"))assertTrue(fields.stream().anyMatch(v->((List<?>)v).get(0).equals(label)&&!((List<?>)v).get(1).toString().contains("未取得")),type+" "+label);
    if(type.equals("SUPPLEMENT_CONTRACT_REVIEW"))assertTrue(fields.contains(List.of("原因或说明","按原审查范围逐项补正")));
    completed.add(type);
   }
  }
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/r25-team-contract-remainder-covered-types.txt"),String.join("\n",completed)+"\n");
 }
}
