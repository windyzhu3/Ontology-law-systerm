package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.OpportunityLedgerAuthorityReader;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.util.*;
/** Read-only projection into the existing contract history; disclosure remains inside its audited fence. */
final class R2TransferHistoryProjection {
 private static final TransferWorkflowReader READER=TransferWorkflowReader.databaseBacked();
 static String append(Connection c,Actor actor,UUID contract,Map<String,Object> context,List<AuditAppender.ContractDisclosureEntry> disclosures)throws SQLException{return append(c,actor,contract,context,disclosures,true);}
 static String append(Connection c,Actor actor,UUID contract,Map<String,Object> context,List<AuditAppender.ContractDisclosureEntry> disclosures,boolean includeHistory)throws SQLException{
  var state=READER.forContract(c,actor.tenantId(),contract);if(state==null)return null;
  var facts=new LinkedHashSet<Subject>(READER.facts(c,actor.tenantId(),state.request().id()));facts.add(state.request());
  var authority=OpportunityLedgerAuthorityReader.databaseBacked();var pending=new ArrayList<AuditAppender.ContractDisclosureEntry>();
  var ordered=List.copyOf(facts);
  var batch=io.github.windyzhu3.ontologylaw.identity.R1AuthorityReader.databaseBacked().authorizeAll(c,actor,ordered,state.fromOrganization(),"OPPORTUNITY_OWNER","CONTRACT_READ");
  if(batch.size()==ordered.size()){
   for(int i=0;i<ordered.size();i++)pending.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),ordered.get(i),batch.get(i),"HISTORY"));
  }else{
   // A transfer may disclose different exact sources through either organization's
   // complete grant. Keep that fallback; do not turn a partial batch into permission.
   for(var fact:facts){var evidence=authority.evidence(c,actor,state.fromOrganization(),fact,"CONTRACT_READ");if(evidence==null||!evidence.allowed())evidence=authority.evidence(c,actor,state.toOrganization(),fact,"CONTRACT_READ");if(evidence==null||!evidence.allowed())return null;pending.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,evidence,"HISTORY"));}
  }
  if(context!=null){
   var entry=new LinkedHashMap<String,Object>();entry.put("stage",state.stage());entry.put("task",null);entry.put("canHandle",false);
   // Only the detail view exposes correction. The ledger returns status and the
   // exact pending task, so it must not build an unused classification command graph.
   boolean canCorrect=false;
   if(includeHistory&&"COMPLETE".equals(state.stage())&&state.matterId()!=null&&actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null){
    var correctionFacts=new LinkedHashSet<Subject>(R2ContractServices.facts(c,actor.tenantId(),state.opportunity().id()));correctionFacts.addAll(facts);correctionFacts.add(state.workflow());
    canCorrect=authority.permitted(c,actor,state.toOrganization(),List.copyOf(correctionFacts),"MATTER_CLASSIFY")&&authority.permitted(c,actor,state.toOrganization(),List.copyOf(correctionFacts),"CONTRACT_READ");
   }
   entry.put("canCorrectClassification",canCorrect);
   if(state.taskId()!=null&&actor.appointmentId().equals(state.owner())){
    var task=io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),state.taskId());
    UUID org=Set.of("PREPARE","SUPPLEMENT").contains(state.stage())?state.fromOrganization():state.toOrganization();
    if(task!=null&&task.type().isTransfer()&&task.owner().equals(actor.appointmentId())&&Set.of("OPEN","CLAIMED").contains(task.state())){
     var read=authority.evidence(c,actor,org,task.selector(),"CONTRACT_READ");var businessFacts=new LinkedHashSet<Subject>(R2ContractServices.facts(c,actor.tenantId(),state.opportunity().id()));businessFacts.addAll(facts);businessFacts.add(task.selector());
     String code=io.github.windyzhu3.ontologylaw.execution.R1CommandPolicy.transferAuthority(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.valueOf(task.type().command));
     if(read!=null&&read.allowed()&&authority.permitted(c,actor,org,List.copyOf(businessFacts),code)){entry.put("task",Map.of("id",task.selector().id().toString(),"revision",task.selector().revision()));entry.put("canHandle",true);pending.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),task.selector(),read,"HISTORY"));}
    }
   }
   context.put("transfer",Collections.unmodifiableMap(entry));
  }
  if(context!=null&&includeHistory){var history=new ArrayList<Object>((List<?>)context.getOrDefault("history",List.of()));for(var item:READER.history(c,actor.tenantId(),state.request().id())){
   String label=switch(item.kind()){case "submission"->"转案提交";case "review"->"转案前独立审查";case "intake"->"转案接收";case "classification"->"案件分类及承接";default->throw new IllegalStateException();};
   String summary=switch(item.kind()){case "submission"->"本次资料已提交，等待独立审查和案管接收";case "review"->switch(item.outcome()){case "CLEAR"->"本次审查通过";case "BLOCKED"->"存在待处理冲突，已交销售处理";default->"已退回补充主体或材料";};case "intake"->"ACCEPT".equals(item.outcome())?"案管已接收，案件编号："+state.matterNumber():"案管已退回补正，本次未生成案件";case "classification"->category(item.outcome())+" · 分类及承接已确认；案件编号："+state.matterNumber();default->throw new IllegalStateException();};
   history.add(Map.of("id",item.selector().id().toString(),"label",label,"summary",summary,"occurredAt",item.occurredAt().toString()));
  }context.put("history",List.copyOf(history));}
  disclosures.addAll(pending);return switch(state.stage()){case "PREPARE"->"待准备转案资料";case "SUPPLEMENT"->"转案待补正";case "REVIEW_TRANSFER"->"待转案前独立审查";case "INTAKE"->"待案管接收";case "CLASSIFY"->"已接收，待案件分类";case "COMPLETE"->"案件分类及承接已确认";case "OWNER_EXCEPTION"->"转案责任待安排";default->null;};
 }
 private static String category(String code){return switch(code){case "GENERAL"->"综法业务";case "ENFORCEMENT"->"执行业务";default->"其他业务";};}
}
