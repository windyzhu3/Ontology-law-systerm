package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.contract.ContractTeamExceptionReader;
import io.github.windyzhu3.ontologylaw.transfer.TransferTeamExceptionReader;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;import java.time.*;import java.util.*;
/** Read-only composition of current exceptions. Recovery remains with the original Owner. */
final class R2TeamExceptionReadService {
 private static final String SLOT="OPPORTUNITY_OWNER";
 private final OpportunityTeamExceptionReader opportunities=OpportunityTeamExceptionReader.databaseBacked();
 private final ContractTeamExceptionReader contracts=ContractTeamExceptionReader.databaseBacked();
 private final TransferTeamExceptionReader transfers=TransferTeamExceptionReader.databaseBacked();
 private record Row(Subject selector,UUID opportunity,UUID owner,UUID originalTask,Instant observedAt,
                    Instant dueAt,String kind,String state,String resumeStage,UUID organization,List<Subject> sources){
  private Row{sources=List.copyOf(sources);}
 }
 private final R2ManagementCursor cursor;private final OpportunityLedgerLeadReader leads;private final AuditAppender audit;
 private final OpportunityOwnerExceptionAuthorityReader identity=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 private record Basis(UUID organization,Subject lead,List<Subject> facts,CurrentTaskReader.Task original){}
 R2TeamExceptionReadService(byte[] key,LeadProtection protection,AuditAppender audit){cursor=new R2ManagementCursor(key);leads=OpportunityLedgerLeadReader.databaseBacked(protection);this.audit=audit;}
 Map<String,Object> list(Connection connection,Actor actor,int limit,String encoded,String search,String state)throws SQLException{
  String query=search==null?"":search.strip().toLowerCase(Locale.ROOT);
  if(limit<1||limit>100||query.length()>200||query.chars().anyMatch(Character::isISOControl)||state!=null&&!Set.of("OWNER_INVALID","OWNER_EXCEPTION","COORDINATING","CONTINUATION_GAP").contains(state))throw fail(400,"VALIDATION_FAILED");
  String scope=CanonicalJson.encode(Map.of("purpose","R25_TEAM_EXCEPTION_CURSOR_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"search",query,"state",state==null?"":state,"limit",limit));
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   requireTeam(c,actor,now);UUID after=cursor.decode(encoded,scope,now),last=after;
   var candidates=new ArrayList<Row>();
   opportunities.scan(c,actor.tenantId(),after,100).stream().map(R2TeamExceptionReadService::row).forEach(candidates::add);
   contracts.scan(c,actor.tenantId(),after,100).stream().map(R2TeamExceptionReadService::row).forEach(candidates::add);
   transfers.scan(c,actor.tenantId(),after,100).stream().map(R2TeamExceptionReadService::row).forEach(candidates::add);
   candidates.sort(Comparator.comparing(v->v.selector().id().toString()));
   var disclosures=new ArrayList<AuditAppender.ManagementDisclosureEntry>();var items=new ArrayList<Map<String,Object>>();int consumed=0;
   for(var row:candidates){if(consumed==100)break;consumed++;last=row.selector().id();if(state!=null&&!state.equals(row.state()))continue;
    var basis=basis(c,actor,row);if(basis==null||!disclose(c,actor,basis,disclosures))continue;
    String customer=leads.label(c,actor.tenantId(),basis.lead());String owner=owner(c,actor,row,basis,disclosures);
    if(!(customer+" "+owner).toLowerCase(Locale.ROOT).contains(query))continue;
    items.add(Map.of("id",last.toString(),"customerLabel",customer,"purposeLabel",purpose(row),"stateLabel",stateLabel(row.state()),"ownerLabel",owner,"timeLabel",timeLabel(row,basis)));
    if(items.size()==limit)break;
   }
   requireTeam(c,actor,R1ServiceReadRuntime.databaseTime(c));
   var result=new LinkedHashMap<String,Object>();result.put("items",List.copyOf(items));result.put("nextCursor",last!=null&&(consumed<candidates.size()||consumed==100)?cursor.encode(last,scope,now):null);
   return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(disclosures));
  });
 }
 Map<String,Object> detail(Connection connection,Actor actor,UUID id)throws SQLException{
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   requireTeam(c,actor,now);var row=current(c,actor.tenantId(),id);if(row==null)throw fail(404,"NOT_FOUND");
   var basis=basis(c,actor,row);var disclosures=new ArrayList<AuditAppender.ManagementDisclosureEntry>();
   if(basis==null||!disclose(c,actor,basis,disclosures))throw fail(403,"NOT_AUTHORIZED");
   var values=new ArrayList<List<String>>();values.add(List.of("当前事项",purpose(row)));values.add(List.of("状态",stateLabel(row.state())));values.add(List.of("原责任人",owner(c,actor,row,basis,disclosures)));values.add(List.of("时间依据",timeLabel(row,basis)));
   values.add(List.of("发现时间",row.observedAt().toString()));
   if(row.resumeStage()!=null)values.add(List.of("原阶段",stageLabel(row.resumeStage())));
   boolean handle=false;boolean taskHandle=false;
   if("OPPORTUNITY".equals(row.kind())){
    var snapshot=OpportunityOwnerExceptionReader.databaseBacked().current(c,actor.tenantId(),id);
    if(snapshot==null||!snapshot.selector().equals(row.selector()))throw fail(412,"STALE_SUBJECT");
    values.add(List.of("异常依据",snapshot.reasons().stream().sorted().map(R2TeamExceptionReadService::reasonLabel).collect(java.util.stream.Collectors.joining("；"))));
    handle=identity.permitted(c,actor,basis.organization(),basis.facts(),"OPPORTUNITY_OWNER_EXCEPTION_READ")&&identity.permitted(c,actor,basis.organization(),basis.facts(),"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
   }else values.add(List.of("异常依据","原工作流已记录办理资格异常；资格恢复后由对应领域核对接续"));
   if("QUOTE".equals(row.kind())&&basis.original()!=null&&basis.original().type()==TaskFactory.Type.RESOLVE_QUOTE_AUTHORITY){
    taskHandle=R2TeamManagementReadService.canHandle(c,actor,basis.original(),basis.facts(),basis.organization());
   }
   if(!row.equals(current(c,actor.tenantId(),id)))throw fail(412,"STALE_SUBJECT");
   requireTeam(c,actor,R1ServiceReadRuntime.databaseTime(c));
   var result=new LinkedHashMap<String,Object>();result.put("id",id.toString());result.put("customerLabel",leads.label(c,actor.tenantId(),basis.lead()));result.put("nextAction",taskHandle?"进入原报价责任卡，核对修复后的审批依据":handle?"进入原异常处置，核对依据后办理":"核对原责任人的办理资格；恢复由原业务流程接续，本页不代办");
   result.put("facts",List.copyOf(values));result.put("history",List.of(Map.of("id",id.toString(),"label",row.observedAt()+" · "+purpose(row)+" · "+stateLabel(row.state()))));result.put("action",taskHandle?Map.of("kind","task","label","前往办理"):handle?Map.of("kind","exception","label","查看原异常处置"):null);result.put("taskId",taskHandle?basis.original().selector().id().toString():null);result.put("exceptionId",handle?id.toString():null);
   return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(disclosures));
  });
 }
 private Row current(Connection c,UUID tenant,UUID id)throws SQLException{
  Row found=null;
  for(var row:new Row[]{row(opportunities.current(c,tenant,id)),row(contracts.current(c,tenant,id)),row(transfers.current(c,tenant,id))})
   if(row!=null){if(found!=null)throw new SQLException("Ambiguous exception identity","21000");found=row;}
  return found;
 }
 private static Row row(OpportunityTeamExceptionReader.Row r){return r==null?null:new Row(r.selector(),r.opportunity(),r.owner(),r.originalTask(),r.observedAt(),r.dueAt(),r.kind(),r.state(),r.resumeStage(),r.organization(),r.sources());}
 private static Row row(ContractTeamExceptionReader.Row r){return r==null?null:new Row(r.selector(),r.opportunity(),r.owner(),r.originalTask(),r.observedAt(),r.dueAt(),r.kind(),r.state(),r.resumeStage(),r.organization(),r.sources());}
 private static Row row(TransferTeamExceptionReader.Row r){return r==null?null:new Row(r.selector(),r.opportunity(),r.owner(),r.originalTask(),r.observedAt(),r.dueAt(),r.kind(),r.state(),r.resumeStage(),r.organization(),r.sources());}
 private Basis basis(Connection c,Actor actor,Row row)throws SQLException{
  var opening=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),row.opportunity());if(opening==null)return null;
  UUID organization=row.organization()==null?identity.historicalOrganization(c,actor.tenantId(),opening.owner()):row.organization();if(organization==null)return null;
  var lead=leads.metadata(c,actor.tenantId(),opening.leadId());if(lead==null)return null;
  var facts=new LinkedHashSet<>(row.sources());facts.add(opening.selector());facts.add(lead.selector());
  var original=row.originalTask()==null?null:CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),row.originalTask());
  if(original!=null){if(!original.subject().equals(opening.selector()))throw new SQLException("Exception original responsibility differs","22000");facts.add(original.selector());if(original.responsibilityBasis()!=null)facts.add(original.responsibilityBasis());var cancellation=CurrentTaskReader.databaseBacked().cancellation(c,actor.tenantId(),original.selector().id());if(cancellation!=null)facts.add(cancellation);}
  if("OPPORTUNITY".equals(row.kind())){
   var s=OpportunityOwnerExceptionReader.databaseBacked().current(c,actor.tenantId(),row.selector().id());if(s==null||!s.selector().equals(row.selector()))return null;
   facts.addAll(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,actor.tenantId(),opening.selector()));facts.add(s.opportunity());facts.add(s.responsibility().basis());if(s.task()!=null)facts.add(s.task());if(s.waitReceipt()!=null)facts.add(s.waitReceipt());if(s.lastDispositionId()!=null)facts.add(new Subject("opportunity.owner_exception_disposition",s.lastDispositionId(),0L,null));
   organization=identity.historicalOrganization(c,actor.tenantId(),s.frozenOwner());if(organization==null)return null;
  }
  return new Basis(organization,lead.selector(),List.copyOf(facts),original);
 }
 private static boolean disclose(Connection c,Actor actor,Basis basis,List<AuditAppender.ManagementDisclosureEntry> ds)throws SQLException{return R2TeamManagementReadService.disclose(c,actor,basis.facts(),basis.organization(),SLOT,ds);}
 private static String owner(Connection c,Actor actor,Row row,Basis basis,List<AuditAppender.ManagementDisclosureEntry> ds)throws SQLException{return row.owner()==null?"原责任安排待核对":R2TeamManagementReadService.appointmentLabel(c,actor,row.owner(),basis.organization(),SLOT,ds);}
 private void requireTeam(Connection c,Actor actor,Instant now)throws SQLException{if(!identity.hasAuthority(c,actor,R2TeamManagementReadService.AUTHORITY,now))throw fail(403,"NOT_AUTHORIZED");}
 private static String timeLabel(Row row,Basis basis){if(basis.original()!=null)return basis.original().slaDueAt()+"（原期限）";return row.dueAt()!=null?row.dueAt()+"（原流程时间）":row.observedAt()+"（发现时间）";}
 private static String purpose(Row row){return switch(row.kind()){case "CONTRACT_PREPARATION"->"合同准备责任异常";case "CONTRACT_SIGNATURE"->"合同签署责任异常";case "CONTRACT_EXECUTION"->"合同执行责任异常";case "CONTRACT_PAYMENT"->"收款核对责任异常";case "TRANSFER"->"转案与分类责任异常";case "QUOTE"->"报价责任异常";case "OPPORTUNITY"->"商机负责人异常";default->throw new IllegalArgumentException("Unregistered exception kind");};}
 private static String stateLabel(String state){return switch(state){case "OWNER_INVALID"->"负责人资格异常";case "COORDINATING"->"协调处理中";case "OWNER_EXCEPTION"->"责任资格待恢复";default->throw new IllegalArgumentException("Unregistered exception state");};}
 private static String stageLabel(String stage){return switch(stage){case "PREPARE"->"准备";case "DIRECT_RETURNED"->"直接授权退回";case "RETURNED"->"合同退回补正";case "REVIEW_BLOCKED"->"审查待补正";case "SUBMIT_REVIEW"->"提交审查";case "SUBMIT_APPROVAL"->"提交审批";case "REVIEW_SUPPLEMENT"->"补充审查";case "ARRANGE"->"安排签署";case "COLLECT"->"收集签署材料";case "VERIFY"->"核验签署";case "ARCHIVE"->"归档签署";case "CHECK_EXECUTION"->"核对执行条件";case "CHECK_RECEIPT"->"收款核对";case "SUPPLEMENT_RECEIPT"->"补充收款依据";case "SUPPLEMENT"->"转案补正";case "REVIEW_TRANSFER"->"转案审查";case "INTAKE"->"案管接收";case "CLASSIFY"->"案件分类";default->"按原业务流程核对";};}
 private static String reasonLabel(OpportunityOwnerExceptionService.Reason reason){return switch(reason){case OWNER_INACTIVE->"原负责人任职失效";case OWNER_AUTHORITY_MISSING->"原负责人缺少办理资格";case OWNER_DENIED->"原负责人受到明确拒绝";case SUPERVISOR_UNRESOLVED->"主管责任尚未确定";case SOURCE_INCONSISTENT->"业务来源需要核对";};}
 private static R1ServiceReadRuntime.Failure fail(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
