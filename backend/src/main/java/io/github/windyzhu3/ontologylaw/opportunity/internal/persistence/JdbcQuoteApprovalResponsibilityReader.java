package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.QuoteApprovalResponsibilityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
public final class JdbcQuoteApprovalResponsibilityReader implements QuoteApprovalResponsibilityReader {
 public Approval current(Connection c,UUID tenant,Subject opportunity)throws SQLException{
  var facts=new LinkedHashSet<Subject>();var members=new ArrayList<Member>();UUID workflow=null;
  try(var p=c.prepareStatement("""
   select w.quote_workflow_id,q.quote_revision_id,q.content_digest,r.quote_approval_request_id,
          m.quote_approval_member_id,m.task_id,m.appointment_id,d.quote_approval_decision_id,d.decision
   from opportunity.opportunity o
   join opportunity.quote_revision q on q.tenant_id=o.tenant_id and q.quote_revision_id=o.current_quote_revision_id and q.opportunity_id=o.opportunity_id
   join opportunity.quote_workflow w on w.tenant_id=o.tenant_id and w.opportunity_id=o.opportunity_id and w.quote_revision_id=q.quote_revision_id
   join opportunity.quote_approval_request r on r.tenant_id=q.tenant_id and r.quote_revision_id=q.quote_revision_id
   join opportunity.quote_approval_member m on m.tenant_id=r.tenant_id and m.request_id=r.quote_approval_request_id
   left join opportunity.quote_approval_decision d on d.tenant_id=m.tenant_id and d.member_id=m.quote_approval_member_id
   where o.tenant_id=? and o.opportunity_id=? and o.revision=? and o.closed_at is null and w.stage='AWAIT_APPROVAL'
     and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)
   """)){
   p.setObject(1,tenant);p.setObject(2,opportunity.id());p.setObject(3,opportunity.revision());
   try(var r=p.executeQuery()){while(r.next()){
    var id=r.getObject(1,UUID.class);if(workflow!=null&&!workflow.equals(id))throw new SQLException("Ambiguous quote approval","40001");workflow=id;
    facts.add(exact("opportunity.quote_workflow",id));facts.add(new Subject("opportunity.quote_revision",r.getObject(2,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(3))));
    facts.add(exact("opportunity.quote_approval_request",r.getObject(4,UUID.class)));
    var member=exact("opportunity.quote_approval_member",r.getObject(5,UUID.class));facts.add(member);
    var decision=r.getObject(8)==null?null:exact("opportunity.quote_approval_decision",r.getObject(8,UUID.class));
    if(decision!=null){if(!"APPROVED".equals(r.getString(9)))throw new SQLException("Returned approval is no longer pending","40001");facts.add(decision);}
    members.add(new Member(member,r.getObject(6,UUID.class),r.getObject(7,UUID.class),decision));
   }}
  }
  return workflow==null?null:new Approval(List.copyOf(facts),members);
 }
 private static Subject exact(String type,UUID id){return new Subject(type,id,0L,null);}
}
