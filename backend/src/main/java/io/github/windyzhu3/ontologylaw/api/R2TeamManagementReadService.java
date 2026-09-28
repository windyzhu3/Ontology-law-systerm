package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;import java.time.*;import java.util.*;
/** Team task metadata only. No acting as another owner and no business state mutations. */
final class R2TeamManagementReadService {
 static final String AUTHORITY="TEAM_TASK_READ";
 private final io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection;private final OpportunityProgressProtection progressProtection;private final R2TeamExceptionReadService exceptions;private final R2ManagementCursor cursor;private final OpportunityLedgerLeadReader leads;private final CurrentLeadReader leadFacts;private final AuditAppender audit;
 private final OpportunityOwnerExceptionAuthorityReader identity=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 R2TeamManagementReadService(byte[] key,LeadProtection protection,AuditAppender audit){this(key,protection,null,audit);}
 R2TeamManagementReadService(byte[] key,LeadProtection protection,OpportunityProgressProtection progressProtection,AuditAppender audit){this(key,protection,progressProtection,null,audit);}
 R2TeamManagementReadService(byte[] key,LeadProtection protection,OpportunityProgressProtection progressProtection,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection,AuditAppender audit){this.contractProtection=contractProtection;this.progressProtection=progressProtection;exceptions=new R2TeamExceptionReadService(key,protection,audit);cursor=new R2ManagementCursor(key);leads=OpportunityLedgerLeadReader.databaseBacked(protection);leadFacts=CurrentLeadReader.databaseBacked(protection);this.audit=audit;}
 Map<String,Object> detail(Connection connection,Actor actor,String view,UUID id)throws SQLException{
  if("exceptions".equals(view))return exceptions.detail(connection,actor,id);
  if(!Set.of("tasks","waiting","history").contains(view))throw fail(400,"VALIDATION_FAILED");
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!identity.hasAuthority(c,actor,AUTHORITY,now))throw fail(403,"NOT_AUTHORIZED");
   var task=CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),id);
   if(task==null)throw fail(404,"NOT_FOUND");
   boolean matches=switch(view){case "tasks"->"OPEN".equals(task.state());case "waiting"->"WAITING".equals(task.state());default->Set.of("DONE","CANCELLED").contains(task.state());};
   if(!matches)throw fail(412,"STALE_SUBJECT");
   var basis=R2TeamTaskResolver.basis(c,actor.tenantId(),task);if(basis==null)throw fail(403,"NOT_AUTHORIZED");
   var lead=leads.metadata(c,actor.tenantId(),basis.lead());if(lead==null)throw fail(404,"NOT_FOUND");
   var facts=new LinkedHashSet<>(basis.facts());facts.add(lead.selector());
   var confirmedInput=R2TeamTaskResolver.originalInput(c,actor.tenantId(),task);if(confirmedInput!=null)facts.add(confirmedInput.selector());
   var wait="WAITING".equals(task.state())?EventResponsibilityReader.databaseBacked().latestWait(c,actor.tenantId(),id):null;
   if("WAITING".equals(task.state())&&(wait==null||wait.taskRevision()!=task.selector().revision()))throw fail(412,"STALE_SUBJECT");
   if(wait!=null)facts.add(wait.selector());
   var exact=List.copyOf(facts);var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();
   if(!disclose(c,actor,exact,basis.organization(),task.type().slot,ds))throw fail(403,"NOT_AUTHORIZED");
   String status=wait!=null?wait.resumeDue()==null?"WAIT_CONDITION":wait.resumeDue().isAfter(now)?"WAIT_FUTURE":"WAIT_DUE":"OPEN".equals(task.state())&&!task.slaDueAt().isAfter(now)?"OVERDUE":task.state();
   String purpose=io.github.windyzhu3.ontologylaw.query.CurrentWorkCardQuery.label(task.type());
   var values=new ArrayList<List<String>>();values.add(List.of("当前事项",purpose));values.add(List.of("状态",label(status)));
   values.add(List.of("原责任人",ownerLabel(c,actor,task,basis.organization(),ds)));values.add(List.of("原办理期限",task.slaDueAt().toString()));
   if(wait!=null)values.add(List.of("等待依据",wait.resumeDue()==null?"待原业务条件满足后核对":wait.resumeDue()+"（约定核对时间）"));
   var history=new ArrayList<Map<String,String>>();history.add(Map.of("id",id.toString(),"label",task.createdAt()+" · 形成责任："+purpose));
   if(task.completion()!=null){
    var completion=R2TeamTaskResolver.confirmedInput(c,actor.tenantId(),task,confirmedInput,R2TeamTaskResolver.completion(c,actor.tenantId(),task,leadFacts,progressProtection,contractProtection));
    String person=completion.actor()==null?"本次查询未取得原确认人":appointmentLabel(c,actor,completion.actor(),basis.organization(),task.type().slot,ds);
    String at=completion.at()==null?"本次查询未取得原处理时间":completion.at().toString();
    values.add(List.of("当时确认人",person));values.add(List.of("处理时间",at));
    values.add(List.of("原记录依据",task.completion().hash()!=null||Long.valueOf(0).equals(task.completion().revision())?"已绑定不可变原记录":"原记录修订号 "+task.completion().revision()));
    values.add(List.of("原因或说明",completion.reason()==null||completion.reason().isBlank()?"本视图未展开原因，请核对原业务记录":completion.reason()));
    history.add(Map.of("id",task.completion().id().toString(),"label",at+" · 已处理："+purpose+" · "+person));
   }else if("CANCELLED".equals(task.state())){
    String reason=CurrentTaskReader.databaseBacked().cancellationReason(c,actor.tenantId(),id);
    values.add(List.of("取消依据",reason==null?"原记录未包含原因":cancellationLabel(reason)));
    var cancellation=CurrentTaskReader.databaseBacked().cancellation(c,actor.tenantId(),id);
    history.add(Map.of("id",cancellation==null?id.toString():cancellation.id().toString(),"label","原责任已取消；保留原期限与业务依据"));
   }
   boolean can=canHandle(c,actor,task,List.copyOf(facts),basis.organization());
   var fresh=CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),id);if(!task.equals(fresh))throw fail(412,"STALE_SUBJECT");
   var result=new LinkedHashMap<String,Object>();result.put("id",id.toString());result.put("customerLabel",leads.label(c,actor.tenantId(),lead.selector()));
   result.put("nextAction",can?"进入原工作卡，按当前依据办理":wait!=null?"按原等待条件接续；到期本身不表示衔接异常":"本事项仅供查询，保留原责任与处理记录");
   result.put("facts",List.copyOf(values));result.put("history",List.copyOf(history));result.put("action",can?Map.of("kind","task","label","前往办理"):null);result.put("taskId",can?id.toString():null);result.put("exceptionId",null);
   return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(ds));
  });
 }
 static boolean canHandle(Connection c,Actor actor,CurrentTaskReader.Task task,List<Subject> facts,UUID organization)throws SQLException{
  boolean can="OPEN".equals(task.state())&&task.owner().equals(actor.appointmentId());
   if(can){
    var commandFacts=new LinkedHashSet<>(facts);var family=R2TeamTaskResolver.family(task.type());
    if(family==R2TeamTaskResolver.Family.QUOTE)commandFacts.addAll(R2QuoteServices.facts(c,actor.tenantId(),task.subject().id()));
    if(family==R2TeamTaskResolver.Family.CONTRACT||family==R2TeamTaskResolver.Family.TRANSFER)commandFacts.addAll(R2ContractServices.facts(c,actor.tenantId(),task.subject().id()));
    var authorities=R1AuthorityReader.databaseBacked();var decisions=authorities.authorizeAll(c,actor,List.copyOf(commandFacts),organization,task.type().slot,task.type().authority);
    can=decisions.size()==commandFacts.size();
    if(can&&(family==R2TeamTaskResolver.Family.CONTRACT||family==R2TeamTaskResolver.Family.TRANSFER||family==R2TeamTaskResolver.Family.QUOTE)){
     var read=authorities.authorizeAll(c,actor,List.copyOf(commandFacts),organization,task.type().slot,family==R2TeamTaskResolver.Family.QUOTE?"QUOTE_READ":"CONTRACT_READ");can=read.size()==commandFacts.size();
     var both=new ArrayList<>(decisions);both.addAll(read);decisions=both;
    }
    if(can)can=AuthorizationService.databaseBacked().evaluateAll(c,decisions.stream().map(AuthorizationSnapshot::request).toList(),true).stream().allMatch(AuthorizationSnapshot::allowed);
   }
  return can;
 }
 static boolean disclose(Connection c,Actor actor,List<Subject> facts,UUID org,String slot,List<AuditAppender.ManagementDisclosureEntry> audit)throws SQLException{
  var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,facts,org,slot,AUTHORITY);if(decisions.size()!=facts.size())return false;
  for(int i=0;i<facts.size();i++)audit.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),facts.get(i),decisions.get(i)));return true;
 }
 static String appointmentLabel(Connection c,Actor actor,UUID appointment,UUID org,String slot,List<AuditAppender.ManagementDisclosureEntry> audit)throws SQLException{
  var owner=WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),appointment);if(owner==null)return "原任职信息不可见";
  var facts=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());return disclose(c,actor,facts,org,slot,audit)?owner.principal().displayName()+" · "+owner.organization().displayName():"原确认人信息不可见";
 }
 private static String cancellationLabel(String reason){return switch(reason){case "CONTRACT_AUTHORITY_MISSING"->"合同办理资格失效，等待原领域恢复";case "CONTRACT_TAKEOVER"->"责任已由合同阶段接续";case "QUOTE_TAKEOVER"->"责任已由报价阶段接续";case "OPPORTUNITY_OWNER_CHANGED"->"负责人交接，原责任已结束";default->"原业务规则取消；请核对原业务记录";};}
 Map<String,Object> list(Connection connection,Actor actor,String view,int limit,String encoded,String search,String state)throws SQLException{
  if("exceptions".equals(view))return exceptions.list(connection,actor,limit,encoded,search,state);
  var kind=switch(view){case "tasks"->TeamResponsibilityReader.View.TASKS;case "waiting"->TeamResponsibilityReader.View.WAITING;case "history"->TeamResponsibilityReader.View.HISTORY;default->throw fail(400,"VALIDATION_FAILED");};
  String query=search==null?"":search.strip().toLowerCase(Locale.ROOT);
  var allowed=switch(kind){case TASKS->Set.of("OPEN","OVERDUE");case WAITING->Set.of("WAIT_FUTURE","WAIT_CONDITION","WAIT_DUE");case HISTORY->Set.of("DONE","CANCELLED");};
  if(limit<1||limit>100||query.length()>200||query.chars().anyMatch(Character::isISOControl)||state!=null&&!allowed.contains(state))throw fail(400,"VALIDATION_FAILED");
  String scope=CanonicalJson.encode(Map.of("purpose","R2_TEAM_CURSOR_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"view",view,"search",query,"state",state==null?"":state,"limit",limit));
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!identity.hasAuthority(c,actor,AUTHORITY,now))throw fail(403,"NOT_AUTHORIZED");
   UUID after=cursor.decode(encoded,scope,now),last=after;var candidates=TeamResponsibilityReader.databaseBacked().scan(c,actor.tenantId(),kind,after,100);
   var disclosures=new ArrayList<AuditAppender.ManagementDisclosureEntry>();var items=new ArrayList<Map<String,Object>>();int consumed=0;
   for(var task:candidates){consumed++;last=task.selector().id();var basis=R2TeamTaskResolver.basis(c,actor.tenantId(),task);if(basis==null)continue;var facts=new LinkedHashSet<>(basis.facts());
    UUID organization=basis.organization(),leadId=basis.lead();
    if(organization==null)continue;var lead=leads.metadata(c,actor.tenantId(),leadId);if(lead==null)continue;facts.add(lead.selector());
    var wait=kind==TeamResponsibilityReader.View.WAITING?EventResponsibilityReader.databaseBacked().latestWait(c,actor.tenantId(),task.selector().id()):null;
    if(kind==TeamResponsibilityReader.View.WAITING&&(wait==null||wait.taskRevision()!=task.selector().revision()))continue;
    if(wait!=null)facts.add(wait.selector());
    var exact=List.copyOf(facts);var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,exact,organization,task.type().slot,AUTHORITY);if(decisions.size()!=exact.size())continue;
    for(int i=0;i<exact.size();i++)disclosures.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),exact.get(i),decisions.get(i)));
    String status=kind==TeamResponsibilityReader.View.WAITING?wait.resumeDue()==null?"WAIT_CONDITION":wait.resumeDue().isAfter(now)?"WAIT_FUTURE":"WAIT_DUE":"OPEN".equals(task.state())&&!task.slaDueAt().isAfter(now)?"OVERDUE":task.state();
    if(state!=null&&!state.equals(status))continue;
    String customer=leads.label(c,actor.tenantId(),lead.selector()),ownerLabel=ownerLabel(c,actor,task,organization,disclosures);
    if(!(customer+" "+ownerLabel).toLowerCase(Locale.ROOT).contains(query))continue;
    var row=new LinkedHashMap<String,Object>();row.put("id",task.selector().id().toString());row.put("customerLabel",customer);row.put("purposeLabel",io.github.windyzhu3.ontologylaw.query.CurrentWorkCardQuery.label(task.type()));row.put("stateLabel",label(status));row.put("ownerLabel",ownerLabel);row.put("timeLabel",kind==TeamResponsibilityReader.View.WAITING&&wait.resumeDue()!=null?wait.resumeDue().toString()+"（约定时间）":task.slaDueAt().toString()+"（原期限）");items.add(Collections.unmodifiableMap(row));if(items.size()==limit)break;
   }
   if(!identity.hasAuthority(c,actor,AUTHORITY,R1ServiceReadRuntime.databaseTime(c)))throw fail(403,"NOT_AUTHORIZED");
   var result=new LinkedHashMap<String,Object>();result.put("items",List.copyOf(items));result.put("nextCursor",last!=null&&(consumed<candidates.size()||candidates.size()==100)?cursor.encode(last,scope,now):null);return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(disclosures));
  });
 }
 private static String ownerLabel(Connection c,Actor actor,CurrentTaskReader.Task task,UUID org,List<AuditAppender.ManagementDisclosureEntry> audit)throws SQLException{
  var owner=WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),task.owner());if(owner==null)return "责任安排待核对";var facts=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,facts,org,task.type().slot,AUTHORITY);if(decisions.size()!=facts.size())return "责任人信息不可见";for(int i=0;i<facts.size();i++)audit.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),facts.get(i),decisions.get(i)));return owner.principal().displayName()+" · "+owner.organization().displayName();
 }
 private static String label(String state){return switch(state){case "OPEN"->"待办理";case "OVERDUE"->"已逾期";case "WAIT_FUTURE"->"未到约定时间";case "WAIT_CONDITION"->"等待条件满足";case "WAIT_DUE"->"已到核对时间";case "DONE"->"已处理";case "CANCELLED"->"已取消";default->throw new IllegalArgumentException("Unsupported task state");};}
 private static R1ServiceReadRuntime.Failure fail(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
