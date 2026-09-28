package io.github.windyzhu3.ontologylaw.payment.internal.persistence;

import io.github.windyzhu3.ontologylaw.payment.PaymentLedgerReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

public final class JdbcPaymentLedgerReader implements PaymentLedgerReader {
 public ProtectedReview review(Connection c,UUID tenant,Subject exact)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Query transaction required","25000");
  if(exact==null||!"contract.payment_review".equals(exact.type())||exact.hash()!=null||exact.revision()==null)return null;
  try(var p=c.prepareStatement("select revision,opportunity_id,recorded_by,created_at,decision_code,body_ciphertext,body_digest from contract.payment_review where tenant_id=? and payment_review_id=?")){
   p.setObject(1,tenant);p.setObject(2,exact.id());try(var row=p.executeQuery()){
    if(!row.next()||row.getLong(1)!=exact.revision())return null;
    return new ProtectedReview(exact,row.getObject(2,UUID.class),row.getObject(3,UUID.class),row.getTimestamp(4).toInstant(),row.getString(5),row.getBytes(6),row.getBytes(7));
   }
  }
 }
 public Detail detail(Connection c,UUID tenant,UUID request)throws SQLException {
  Row row;Subject version;boolean prepay;Long required;
  try(var p=c.prepareStatement("select r.payment_request_id,r.revision request_revision,w.payment_workflow_id,w.revision workflow_revision,r.opportunity_id,r.contract_revision_id,w.stage_code,w.owner_appointment_id,w.task_id,w.due_at,v.content_digest,v.receipt_required_before_transfer,v.required_amount_minor from contract.payment_request r join contract.payment_workflow w on w.tenant_id=r.tenant_id and w.request_id=r.payment_request_id join contract.contract_revision v on v.tenant_id=r.tenant_id and v.contract_revision_id=r.contract_revision_id where r.tenant_id=? and r.payment_request_id=? and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)")){
   p.setObject(1,tenant);p.setObject(2,request);try(var r=p.executeQuery()){if(!r.next())return null;row=map(r);version=new Subject("contract.contract_revision",row.contractRevisionId(),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes("content_digest")));prepay=r.getBoolean("receipt_required_before_transfer");required=prepay?r.getObject("required_amount_minor",Long.class):null;if(r.next())throw new SQLException("Ambiguous payment responsibility","21000");}
  }
  var facts=new LinkedHashSet<Subject>(List.of(row.request(),row.workflow(),version));var receipts=new ArrayList<Receipt>();var history=new ArrayList<Review>();
  try(var p=c.prepareStatement("select p.payment_confirmation_id,p.amount_minor,p.currency_code,p.confirmation_type,p.attribution_digest,p.confirmed_at,exists(select 1 from contract.payment_review d join contract.payment_workflow w on w.tenant_id=d.tenant_id and w.payment_workflow_id=d.workflow_id where d.tenant_id=p.tenant_id and d.confirmation_id=p.payment_confirmation_id and w.request_id=?) own_request from contract.payment_confirmation p where p.tenant_id=? and p.contract_revision_id=? order by p.confirmation_no")){
   p.setObject(1,request);p.setObject(2,tenant);p.setObject(3,row.contractRevisionId());try(var r=p.executeQuery()){while(r.next()){var fact=new Subject("contract.payment_confirmation",r.getObject(1,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(5)));facts.add(fact);receipts.add(new Receipt(fact,r.getLong(2),r.getString(3),r.getString(4),r.getBoolean(7),r.getTimestamp(6).toInstant()));}}
  }
  try(var p=c.prepareStatement("select d.payment_review_id,d.revision,d.decision_code,d.created_at from contract.payment_review d join contract.payment_workflow w on w.tenant_id=d.tenant_id and w.payment_workflow_id=d.workflow_id where d.tenant_id=? and w.request_id=? order by d.created_at,d.payment_review_id")){
   p.setObject(1,tenant);p.setObject(2,request);try(var r=p.executeQuery()){while(r.next()){var fact=new Subject("contract.payment_review",r.getObject(1,UUID.class),r.getLong(2),null);facts.add(fact);history.add(new Review(fact,r.getString(3),r.getTimestamp(4).toInstant()));}}
  }
  return new Detail(row,version,prepay,required,List.copyOf(receipts),List.copyOf(history),List.copyOf(facts));
 }
 private static Row map(ResultSet r)throws SQLException{return new Row(new Subject("contract.payment_request",r.getObject("payment_request_id",UUID.class),r.getLong("request_revision"),null),new Subject("contract.payment_workflow",r.getObject("payment_workflow_id",UUID.class),r.getLong("workflow_revision"),null),r.getObject("opportunity_id",UUID.class),r.getObject("contract_revision_id",UUID.class),r.getString("stage_code"),r.getObject("owner_appointment_id",UUID.class),r.getObject("task_id",UUID.class),r.getTimestamp("due_at").toInstant());}
 public List<Row> scan(Connection c,UUID tenant,UUID after,String stage,int limit)throws SQLException {
  Objects.requireNonNull(tenant);
  if(limit<1||limit>100||stage!=null&&!Set.of("CHECK_RECEIPT","SUPPLEMENT_RECEIPT","COMPLETE","OWNER_EXCEPTION").contains(stage))throw new IllegalArgumentException("Bounded payment query required");
  String sql="select r.payment_request_id,r.revision request_revision,w.payment_workflow_id,w.revision workflow_revision,r.opportunity_id,r.contract_revision_id,w.stage_code,w.owner_appointment_id,w.task_id,w.due_at from contract.payment_request r join contract.payment_workflow w on w.tenant_id=r.tenant_id and w.request_id=r.payment_request_id where r.tenant_id=? and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)"+(after==null?"":" and r.payment_request_id>?")+(stage==null?"":" and w.stage_code=?")+" order by r.payment_request_id limit ?";
  try(var p=c.prepareStatement(sql)){
   int i=1;p.setObject(i++,tenant);if(after!=null)p.setObject(i++,after);if(stage!=null)p.setString(i++,stage);p.setInt(i,limit);
   try(var r=p.executeQuery()){
    var result=new ArrayList<Row>();while(r.next())result.add(new Row(new Subject("contract.payment_request",r.getObject("payment_request_id",UUID.class),r.getLong("request_revision"),null),new Subject("contract.payment_workflow",r.getObject("payment_workflow_id",UUID.class),r.getLong("workflow_revision"),null),r.getObject("opportunity_id",UUID.class),r.getObject("contract_revision_id",UUID.class),r.getString("stage_code"),r.getObject("owner_appointment_id",UUID.class),r.getObject("task_id",UUID.class),r.getTimestamp("due_at").toInstant()));
    return List.copyOf(result);
   }
  }
 }
}
