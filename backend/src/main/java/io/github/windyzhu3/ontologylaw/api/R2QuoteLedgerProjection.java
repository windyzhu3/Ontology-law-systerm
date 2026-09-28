package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;import java.time.*;import java.util.*;
/** Metadata only: quote documents are never decrypted for the opportunity ledger. */
final class R2QuoteLedgerProjection {
 record State(UUID workflow,String stage,CurrentTaskReader.Task task,Instant due,List<Subject> facts,boolean visible){}
 private static final OpportunityLedgerAuthorityReader authority=OpportunityLedgerAuthorityReader.databaseBacked();
 static State read(Connection c,Actor a,UUID opportunity,UUID organization)throws SQLException{
  try(var p=c.prepareStatement("select w.quote_workflow_id,w.stage,w.task_id,w.quote_revision_id,w.next_check_at from opportunity.quote_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)")){
   p.setObject(1,a.tenantId());p.setObject(2,opportunity);try(var r=p.executeQuery()){if(!r.next())return null;
    UUID workflow=r.getObject(1,UUID.class),taskId=r.getObject(3,UUID.class),quote=r.getObject(4,UUID.class);String stage=r.getString(2);Instant due=r.getTimestamp(5)==null?null:r.getTimestamp(5).toInstant();
    var facts=R2QuoteServices.facts(c,a.tenantId(),opportunity);
    if(!authority.permitted(c,a,organization,facts,"QUOTE_READ"))return new State(workflow,null,null,null,List.of(),false);
    if("AWAIT_APPROVAL".equals(stage))try(var members=c.prepareStatement("select m.task_id from opportunity.quote_approval_member m join opportunity.quote_approval_request q on q.tenant_id=m.tenant_id and q.quote_approval_request_id=m.request_id join responsibility.task_occurrence t on t.tenant_id=m.tenant_id and t.task_occurrence_id=m.task_id where q.tenant_id=? and q.quote_revision_id=? and t.state='OPEN' order by (m.appointment_id=?) desc,t.original_sla_due_at,m.task_id")){members.setObject(1,a.tenantId());members.setObject(2,quote);members.setObject(3,a.appointmentId());try(var m=members.executeQuery()){if(m.next())taskId=m.getObject(1,UUID.class);}}
    var task=taskId==null?null:CurrentTaskReader.databaseBacked().read(c,a.tenantId(),taskId);
    if(task!=null){if(!task.subject().id().equals(opportunity))throw new SQLException("Invalid quote task lineage","22000");if(due==null)due=task.slaDueAt();}
    var exact=new LinkedHashSet<Subject>(facts);if(task!=null)exact.add(task.selector());
    if(!authority.permitted(c,a,organization,List.copyOf(exact),"QUOTE_READ"))return new State(workflow,null,null,null,List.of(),false);
    return new State(workflow,stage,task,due,List.copyOf(exact),true);
   }
  }
 }
 static String label(String stage){return switch(stage){case "PREPARE"->"准备收费方案";case "RETURNED"->"修订退回的报价";case "SUBMIT_APPROVAL"->"提交报价审批";case "AWAIT_APPROVAL"->"审批报价";case "DELIVER"->"人工送达报价并记录凭据";case "AWAIT_REPLY"->"记录客户报价回复";case "FOLLOW_UP"->"跟进客户报价回复";case "CLARIFY_REPLY"->"澄清客户报价回复";case "SALES_DISPOSITION"->"决定后续洽谈安排";case "OWNER_EXCEPTION"->"处理报价授权问题";case "ACCEPTED"->"报价已接受，待衔接合同准备";default->"核对当前报价事项";};}
 static void apply(Connection c,Actor a,UUID org,State q,boolean detail,Map<String,Object> out,List<AuditAppender.QuoteDisclosureEntry> audits)throws SQLException{
  out.remove("dueAt");out.remove("nextActionLabel");out.remove("task");out.put("taskState","NONE");if(detail)out.put("canHandle",false);
  if(!q.visible())return;
  for(var f:q.facts()){var e=authority.evidence(c,a,org,f,"QUOTE_READ");if(e==null||!e.allowed())throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");audits.add(new AuditAppender.QuoteDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),f,e,"BODY"));}
  out.put("nextActionLabel",label(q.stage()));var t=q.task();if("ACCEPTED".equals(q.stage()))return;
  if(t==null||!Set.of("OPEN","WAITING").contains(t.state()))return;
  out.put("taskState",t.state());if(q.due()!=null)out.put("dueAt",q.due().toString());
  var owner=WorkcardOwnerReader.databaseBacked().read(c,a.tenantId(),t.owner());if(owner!=null){var fs=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());if(q.facts().containsAll(fs))out.put("ownerLabel",owner.principal().displayName());}
  if(detail&&"OPEN".equals(t.state())&&a.appointmentId().equals(t.owner())){
   String code=R1CommandPolicy.quoteAuthority(CommandEnvelope.Type.valueOf(t.type().command));
   if(authority.permitted(c,a,org,q.facts(),code)){out.put("canHandle",true);out.put("task",Map.of("id",t.selector().id().toString(),"revision",t.selector().revision(),"etag",R1ResourceTags.task(a,t.selector(),t.state(),t.responsibilityBasis())));}
  }
 }
}
