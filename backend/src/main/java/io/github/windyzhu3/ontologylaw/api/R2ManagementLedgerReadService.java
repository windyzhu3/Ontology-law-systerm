package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.payment.PaymentLedgerReader;
import io.github.windyzhu3.ontologylaw.transfer.TransferLedgerReader;
import java.sql.*;import java.util.*;

final class R2ManagementLedgerReadService {
 private final R2ManagementCursor cursor;private final OpportunityLedgerLeadReader leads;private final AuditAppender audit;
 private final OpportunityOwnerExceptionAuthorityReader identity=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 record Candidate(Subject request,Subject workflow,UUID opportunity,String stage,UUID owner,UUID task,UUID contractBasis){}
 R2ManagementLedgerReadService(byte[] key,LeadProtection protection,AuditAppender audit){cursor=new R2ManagementCursor(key);leads=OpportunityLedgerLeadReader.databaseBacked(protection);this.audit=audit;}
 Map<String,Object> detail(Connection connection,Actor actor,String view,UUID id)throws SQLException {
  if(!Set.of("payments","transfer").contains(view))throw fail(400,"VALIDATION_FAILED");String code=view.equals("payments")?"PAYMENT_LEDGER_READ":"TRANSFER_LEDGER_READ";
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!identity.hasAuthority(c,actor,code,now))throw fail(403,"NOT_AUTHORIZED");
   var payment=view.equals("payments")?PaymentLedgerReader.databaseBacked().detail(c,actor.tenantId(),id):null;
   var transfer=view.equals("transfer")?io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked().current(c,actor.tenantId(),id):null;
   if(payment==null&&transfer==null)throw fail(404,"NOT_FOUND");
   UUID opportunity=payment!=null?payment.row().opportunityId():transfer.opportunity().id();UUID taskId=payment!=null?payment.row().taskId():transfer.taskId();String stage=payment!=null?payment.row().stage():transfer.stage();
   var opening=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),opportunity);if(opening==null)throw fail(404,"NOT_FOUND");
   var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),opening.selector());if(owner==null)throw fail(403,"NOT_AUTHORIZED");
   UUID org=transfer!=null?Set.of("PREPARE","SUPPLEMENT").contains(stage)?transfer.fromOrganization():transfer.toOrganization():identity.historicalOrganization(c,actor.tenantId(),owner.appointmentId());
   var lead=leads.metadata(c,actor.tenantId(),opening.leadId());if(lead==null||org==null)throw fail(403,"NOT_AUTHORIZED");
   var contractBasis=payment!=null?io.github.windyzhu3.ontologylaw.contract.ContractLedgerBasisReader.databaseBacked().revision(c,actor.tenantId(),payment.row().contractRevisionId()):io.github.windyzhu3.ontologylaw.contract.ContractLedgerBasisReader.databaseBacked().executed(c,actor.tenantId(),transfer.contractId());if(contractBasis==null)throw fail(404,"NOT_FOUND");
   var facts=new LinkedHashSet<Subject>(List.of(opening.selector(),owner.basis(),lead.selector()));facts.addAll(contractBasis.facts());
   if(payment!=null)facts.addAll(payment.facts());else{facts.add(transfer.request());facts.add(transfer.workflow());facts.addAll(io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked().facts(c,actor.tenantId(),id));}
   var task=taskId==null?null:io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),taskId);if(task!=null)facts.add(task.selector());
   var missingItems=transfer==null?List.<io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.ReturnItem>of():io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked().returnItems(c,actor.tenantId(),transfer);
   missingItems.forEach(item->facts.add(item.selector()));
   var exact=List.copyOf(facts);var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,exact,org,"OPPORTUNITY_OWNER",code);
   if(decisions.size()!=exact.size()&&transfer!=null){org=org.equals(transfer.fromOrganization())?transfer.toOrganization():transfer.fromOrganization();decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,exact,org,"OPPORTUNITY_OWNER",code);}
   if(decisions.size()!=exact.size())throw fail(403,"NOT_AUTHORIZED");
   var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();for(int i=0;i<exact.size();i++)ds.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),exact.get(i),decisions.get(i)));
   var values=new ArrayList<List<String>>();values.add(List.of("合同依据","第 "+contractBasis.version()+" 版"));values.add(List.of("负责人",ownerLabel(c,actor,payment!=null?payment.row().ownerAppointmentId():transfer.owner(),org,code,ds)));var history=new ArrayList<Map<String,String>>();values.add(List.of("当前事项",label(view,stage)));values.add(List.of("办理期限",(payment!=null?payment.row().dueAt():transfer.dueAt()).toString()));
   if(payment!=null){
    var totals=new TreeMap<String,java.math.BigInteger>();var current=new TreeMap<String,java.math.BigInteger>();boolean adjustment=false;
    for(var r:payment.receipts()){if(!"RECEIPT".equals(r.kind())){adjustment=true;continue;}totals.merge(r.currency(),java.math.BigInteger.valueOf(r.amountMinor()),java.math.BigInteger::add);if(r.thisRequest())current.merge(r.currency(),java.math.BigInteger.valueOf(r.amountMinor()),java.math.BigInteger::add);}
    values.add(List.of("本次已核实",money(current)));values.add(List.of("合同累计到账",adjustment?"存在撤销或退款记录，需对照原记录核对":money(totals)));
    if(payment.prepay()){values.add(List.of("约定首款",payment.requiredMinor()==null?"约定金额待核对":minor(java.math.BigInteger.valueOf(payment.requiredMinor()),"CNY")));if(payment.requiredMinor()!=null&&!adjustment)values.add(List.of("首款尚差",minor(java.math.BigInteger.valueOf(payment.requiredMinor()).subtract(totals.getOrDefault("CNY",java.math.BigInteger.ZERO)).max(java.math.BigInteger.ZERO),"CNY")));}
    values.add(List.of("转案条件",payment.prepay()?"按批准合同的先款约定核对；足额后仍须核对其他执行条件":"不要求先到账；本笔核对独立闭环，不阻断已满足其他条件的转案"));
    for(var h:payment.history())history.add(Map.of("id",h.selector().id().toString(),"label",h.occurredAt()+" · "+switch(h.outcome()){case "CONFIRMED"->"本笔到账已核对";case "RETURNED"->"退回补充凭证或归属依据";case "SUPPLEMENTED"->"凭证已补充，交财务核对";default->"收款处理记录";}));
   }else{
    values.add(List.of("案件身份",transfer.matterNumber()==null?"尚未生成案件":transfer.matterNumber()));values.add(List.of("业务分类",transfer.category()==null?transfer.matterId()==null?"接收后再分类":"待确认分类":switch(transfer.category()){case "GENERAL"->"综法业务";case "ENFORCEMENT"->"执行业务";default->"其他业务";}));
    var reader=io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked();
    for(var h:reader.history(c,actor.tenantId(),id))history.add(Map.of("id",h.selector().id().toString(),"label",h.occurredAt()+" · "+switch(h.kind()){case "submission"->"转案资料已提交";case "review"->"CLEAR".equals(h.outcome())?"独立冲突审查已通过":"独立审查要求补正或处理冲突";case "intake"->"ACCEPT".equals(h.outcome())?"案管已接收并生成案件":"案管已退回补正";case "classification"->"案件分类及承接已确认";default->"处理记录";}));
    for(var missing:missingItems)values.add(List.of("补正项目",switch(missing.requirement()){case "CLIENT_IDENTITY"->"委托主体证明";case "SIGNATURE_ARCHIVE"->"完整签署归档";case "HANDOVER_EXPLANATION"->"交接说明";case "RESOLVE_CONFLICT"->"冲突处理依据";default->"主体或补充材料";}));
   }
   boolean can=false;
   if(task!=null&&(payment!=null?Set.of(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CHECK_CONTRACT_RECEIPT,io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.SUPPLEMENT_CONTRACT_RECEIPT).contains(task.type()):task.type().isTransfer())&&task.owner().equals(actor.appointmentId())&&Set.of("OPEN","CLAIMED").contains(task.state())&&task.subject().id().equals(opportunity)&&"opportunity.opportunity".equals(task.subject().type())){
    var business=new LinkedHashSet<>(R2ContractServices.facts(c,actor.tenantId(),opportunity));business.addAll(facts);
    String action=io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.valueOf(task.type().command).name();String authority=task.type().isTransfer()?R1CommandPolicy.transferAuthority(CommandEnvelope.Type.valueOf(action)):R1CommandPolicy.contractAuthority(CommandEnvelope.Type.valueOf(action));
    UUID commandOrg=transfer!=null?Set.of("PREPARE","SUPPLEMENT").contains(stage)?transfer.fromOrganization():transfer.toOrganization():org;
    var checker=OpportunityLedgerAuthorityReader.databaseBacked();can=checker.permitted(c,actor,commandOrg,List.copyOf(business),"CONTRACT_READ")&&checker.permitted(c,actor,commandOrg,List.copyOf(business),authority);
   }
   var result=new LinkedHashMap<String,Object>();result.put("id",id.toString());result.put("opportunityId",opportunity.toString());result.put("customerLabel",leads.label(c,actor.tenantId(),lead.selector()));result.put("facts",List.copyOf(values));result.put("history",List.copyOf(history));result.put("canHandle",can);result.put("taskId",can?taskId.toString():null);return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(ds));
  });
 }
 private static String ownerLabel(Connection c,Actor actor,UUID appointment,UUID org,String code,List<AuditAppender.ManagementDisclosureEntry> audit)throws SQLException{
  if(appointment==null)return "无当前办理责任人";
  var owner=WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),appointment);if(owner==null)return "责任安排待核对";
  var facts=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());
  var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,facts,org,"OPPORTUNITY_OWNER",code);
  if(decisions.size()!=facts.size())return "当前责任人信息不可见";
  for(int i=0;i<facts.size();i++)audit.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),facts.get(i),decisions.get(i)));
  return owner.principal().displayName()+" · "+owner.organization().displayName();
 }
 private static String money(Map<String,java.math.BigInteger> values){return values.isEmpty()?"尚无已核实到账":values.entrySet().stream().map(e->minor(e.getValue(),e.getKey())).collect(java.util.stream.Collectors.joining("；"));}
 private static String minor(java.math.BigInteger value,String currency){return "CNY".equals(currency)?"人民币 "+new java.math.BigDecimal(value,2).toPlainString()+" 元":currency+" "+value+"（最小货币单位）";}
 Map<String,Object> list(Connection connection,Actor actor,String view,int limit,String encoded,String search,String state)throws SQLException {
  if(!Set.of("payments","transfer").contains(view)||limit<1||limit>100)throw fail(400,"VALIDATION_FAILED");
  String query=search==null?"":search.strip().toLowerCase(Locale.ROOT);if(query.length()>200||query.chars().anyMatch(Character::isISOControl))throw fail(400,"VALIDATION_FAILED");
  String code=view.equals("payments")?"PAYMENT_LEDGER_READ":"TRANSFER_LEDGER_READ";
  String scope=io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(Map.of("purpose","R2_MANAGEMENT_CURSOR_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"view",view,"search",query,"state",state==null?"":state,"limit",limit));
  return ManagementReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!identity.hasAuthority(c,actor,code,now))throw fail(403,"NOT_AUTHORIZED");var after=cursor.decode(encoded,scope,now);
   List<Candidate> candidates;
   try{candidates=view.equals("payments")?PaymentLedgerReader.databaseBacked().scan(c,actor.tenantId(),after,state,100).stream().map(r->new Candidate(r.request(),r.workflow(),r.opportunityId(),r.stage(),r.ownerAppointmentId(),r.taskId(),r.contractRevisionId())).toList():TransferLedgerReader.databaseBacked().scan(c,actor.tenantId(),after,state,100).stream().map(r->new Candidate(r.request(),r.workflow(),r.opportunityId(),r.stage(),r.ownerAppointmentId(),r.taskId(),r.contractId())).toList();}catch(IllegalArgumentException invalid){throw fail(400,"VALIDATION_FAILED");}
   var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();var items=new ArrayList<Map<String,Object>>();UUID last=after;int consumed=0;
   for(var candidate:candidates){consumed++;last=candidate.request().id();var opening=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),candidate.opportunity());if(opening==null)continue;
    var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),opening.selector());if(owner==null)continue;UUID org=identity.historicalOrganization(c,actor.tenantId(),owner.appointmentId());if(org==null)continue;
    var lead=leads.metadata(c,actor.tenantId(),opening.leadId());if(lead==null)continue;
    var contractBasis=view.equals("payments")?io.github.windyzhu3.ontologylaw.contract.ContractLedgerBasisReader.databaseBacked().revision(c,actor.tenantId(),candidate.contractBasis()):io.github.windyzhu3.ontologylaw.contract.ContractLedgerBasisReader.databaseBacked().executed(c,actor.tenantId(),candidate.contractBasis());if(contractBasis==null)continue;
    var facts=new ArrayList<>(List.of(opening.selector(),owner.basis(),lead.selector(),candidate.request(),candidate.workflow()));facts.addAll(contractBasis.facts());
    var transferState=view.equals("transfer")?io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked().current(c,actor.tenantId(),candidate.request().id()):null;
    if(transferState!=null)org=Set.of("PREPARE","SUPPLEMENT").contains(candidate.stage())?transferState.fromOrganization():transferState.toOrganization();
    var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,facts,org,"OPPORTUNITY_OWNER",code);
    if(decisions.size()!=facts.size()&&transferState!=null){org=org.equals(transferState.fromOrganization())?transferState.toOrganization():transferState.fromOrganization();decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,facts,org,"OPPORTUNITY_OWNER",code);}
    if(decisions.size()!=facts.size())continue;
    // The label is decrypted only after all its exact sources have been authorized.
    String label=leads.label(c,actor.tenantId(),lead.selector());
    for(int i=0;i<facts.size();i++)ds.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),facts.get(i),decisions.get(i)));
    if(!label.toLowerCase(Locale.ROOT).contains(query))continue;
    var item=new LinkedHashMap<String,Object>();item.put("id",candidate.request().id().toString());item.put("opportunityId",candidate.opportunity().toString());item.put("customerLabel",label);item.put("basisLabel","合同第 "+contractBasis.version()+" 版 · "+(view.equals("payments")?"本次收款":"本次转案"));item.put("stateLabel",label(view,candidate.stage()));item.put("ownerLabel",ownerLabel(c,actor,candidate.owner(),org,code,ds));items.add(Collections.unmodifiableMap(item));if(items.size()==limit)break;
   }
   if(!identity.hasAuthority(c,actor,code,R1ServiceReadRuntime.databaseTime(c)))throw fail(403,"NOT_AUTHORIZED");
   var result=new LinkedHashMap<String,Object>();result.put("items",List.copyOf(items));result.put("nextCursor",last!=null&&(consumed<candidates.size()||candidates.size()==100)?cursor.encode(last,scope,now):null);
   return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(ds));
  });
 }
 static String label(String view,String stage){return switch(stage){case "CHECK_RECEIPT"->"待核对本笔收款";case "SUPPLEMENT_RECEIPT"->"待补充收款凭证";case "COMPLETE"->view.equals("payments")?"本笔收款已核对":"已分类及承接";case "OWNER_EXCEPTION"->"责任安排待处理";case "PREPARE"->"待提交转案";case "REVIEW_TRANSFER"->"待独立冲突审查";case "INTAKE"->"待案管接收";case "SUPPLEMENT"->"退回补正";case "CLASSIFY"->"已接收，待分类";default->throw new IllegalArgumentException("Unknown stage");};}
 private static R1ServiceReadRuntime.Failure fail(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
