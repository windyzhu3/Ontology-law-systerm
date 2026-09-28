package io.github.windyzhu3.ontologylaw.payment.internal.persistence;
import io.github.windyzhu3.ontologylaw.payment.*;
import io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.*;import java.util.*;import java.security.*;import java.nio.charset.StandardCharsets;

public final class JdbcPaymentWorkflowService implements PaymentWorkflowService {
 private final PaymentTransactionProtection protection;private final Ports ports;
 public JdbcPaymentWorkflowService(PaymentTransactionProtection protection,Ports ports){this.protection=Objects.requireNonNull(protection);this.ports=Objects.requireNonNull(ports);}
 private static void transaction(Connection c)throws SQLException{if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Payment command transaction required");}
 private static UUID id(Object v){return v==null?null:v instanceof UUID x?x:UUID.fromString(v.toString());}
 private static Instant instant(Object v){return v instanceof OffsetDateTime t?t.toInstant():v instanceof java.sql.Timestamp t?t.toInstant():(Instant)v;}
 private static Subject fact(String table,Object id){return new Subject("contract."+table,id(id),0L,null);}
 private static Blocked stale(){return new Blocked("STALE_SUBJECT");}
 private static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i] instanceof Instant t?OffsetDateTime.ofInstant(t,ZoneOffset.UTC):args[i]);}
 private static Map<String,Object> row(Connection c,String sql,Object...args)throws SQLException{try(var s=c.prepareStatement(sql)){bind(s,args);try(var r=s.executeQuery()){if(!r.next())return null;var out=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)out.put(r.getMetaData().getColumnLabel(i),r.getObject(i));return out;}}}
 private static void write(Connection c,String sql,Object...args)throws SQLException{try(var s=c.prepareStatement(sql)){bind(s,args);if(s.executeUpdate()!=1)throw stale();}}
 private static UUID fresh(Connection c)throws SQLException{return id(row(c,"select uuidv7() id").get("id"));}
 private Subject lock(Connection c,UUID tenant,UUID opportunity)throws SQLException{var o=row(c,"select revision from opportunity.opportunity where tenant_id=? and opportunity_id=? for update",tenant,opportunity);if(o==null)throw stale();return new Subject("opportunity.opportunity",opportunity,((Number)o.get("revision")).longValue(),null);}
 private Map<String,Object> basis(Connection c,UUID tenant,UUID handoff,UUID opportunity)throws SQLException{
  var b=row(c,"select v.*,h.created_at as handed_at from contract.signature_handoff h join contract.signature_readiness s on s.tenant_id=h.tenant_id and s.signature_readiness_id=h.readiness_id join contract.contract_revision v on v.tenant_id=s.tenant_id and v.contract_revision_id=s.contract_revision_id join contract.contract k on k.tenant_id=v.tenant_id and k.contract_id=v.contract_id where h.tenant_id=? and h.signature_handoff_id=? and h.opportunity_id=? and k.opportunity_id=? and k.current_revision_id=v.contract_revision_id and k.approved_revision_id=v.contract_revision_id for update of k",tenant,handoff,opportunity,opportunity);
  if(b==null)throw stale();return b;
 }
 private static List<Map<String,Object>> rows(Connection c,String sql,Object...args)throws SQLException{try(var statement=c.prepareStatement(sql)){bind(statement,args);try(var r=statement.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var value=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)value.put(r.getMetaData().getColumnLabel(i),r.getObject(i));out.add(value);}return List.copyOf(out);}}}
 private static Map<String,Object> selector(Subject s){return Map.of("id",s.id().toString(),"revision",s.revision());}
 public List<Map<String,Object>> context(Connection c,Actor actor,Subject opportunity)throws SQLException{
  ports.lockAuthority(c,actor);ports.authorize(c,actor,opportunity,"PAYMENT_READ",List.of());var out=new ArrayList<Map<String,Object>>();
  for(var w:rows(c,"select w.*,r.contract_revision_id,r.handoff_id from contract.payment_workflow w join contract.payment_request r on r.tenant_id=w.tenant_id and r.payment_request_id=w.request_id where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id) order by r.created_at desc,r.payment_request_id",actor.tenantId(),opportunity.id())){
   var value=new LinkedHashMap<String,Object>();var exact=fact("payment_workflow",w.get("payment_workflow_id"));String stage=(String)w.get("stage_code");
   var basis=row(c,"select receipt_required_before_transfer,required_amount_minor from contract.contract_revision where tenant_id=? and contract_revision_id=?",actor.tenantId(),w.get("contract_revision_id"));
   var total=row(c,"select coalesce(sum(amount_minor),0) total,count(*) filter(where confirmation_type<>'RECEIPT' or currency_code<>'CNY') invalid from contract.payment_confirmation where tenant_id=? and contract_revision_id=?",actor.tenantId(),w.get("contract_revision_id"));
   if(((Number)total.get("invalid")).longValue()!=0)throw stale();long confirmed=((java.math.BigDecimal)total.get("total")).longValueExact();if(confirmed>9007199254740991L)throw stale();
   Long required=Boolean.TRUE.equals(basis.get("receipt_required_before_transfer"))&&basis.get("required_amount_minor")!=null?((Number)basis.get("required_amount_minor")).longValue():null;
   value.put("requiredMinor",required);value.put("confirmedMinor",confirmed);value.put("remainingMinor",required==null?null:Math.max(0,required-confirmed));
   value.put("selector",selector(exact));value.put("request",selector(fact("payment_request",w.get("request_id"))));value.put("stage",stage);value.put("targetStage",w.get("target_stage_code"));value.put("ownerAppointmentId",w.get("owner_appointment_id")==null?null:w.get("owner_appointment_id").toString());value.put("ownerLabel",stage.equals("SUPPLEMENT_RECEIPT")?"当前销售责任人":"财务核对责任人");value.put("dueAt",instant(w.get("due_at")).toString());
   Task task=w.get("task_id")==null?null:ports.task(c,actor.tenantId(),id(w.get("task_id")));value.put("task",task==null?null:selector(task.selector()));
   value.put("taskIds",rows(c,"select task_id from contract.payment_workflow where tenant_id=? and request_id=? and task_id is not null",actor.tenantId(),w.get("request_id")).stream().map(r->r.get("task_id").toString()).distinct().toList());
   List<String> actions=List.of();if(actor.principalKind()==PrincipalKind.HUMAN&&task!=null&&task.state().equals("OPEN")&&actor.appointmentId().equals(task.owner())&&Set.of("CHECK_RECEIPT","SUPPLEMENT_RECEIPT").contains(stage)){try{ports.authorize(c,actor,opportunity,stage.equals("CHECK_RECEIPT")?"PAYMENT_CONFIRM":"PAYMENT_SUBMIT",List.of(exact,task.selector()));actions=List.of(stage.equals("CHECK_RECEIPT")?"RECORD_CONTRACT_RECEIPT_REVIEW":"SUPPLEMENT_CONTRACT_RECEIPT");}catch(Blocked denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}}
   if(actor.principalKind()==PrincipalKind.HUMAN&&stage.equals("COMPLETE")&&row(c,"select 1 from contract.payment_workflow p join contract.payment_request r on r.tenant_id=p.tenant_id and r.payment_request_id=p.request_id where p.tenant_id=? and r.handoff_id=? and p.stage_code<>'COMPLETE' and not exists(select 1 from contract.payment_workflow n where n.tenant_id=p.tenant_id and n.previous_workflow_id=p.payment_workflow_id) limit 1",actor.tenantId(),w.get("handoff_id"))==null){try{ports.authorize(c,actor,opportunity,"PAYMENT_SUBMIT",List.of(exact));if(actor.appointmentId().equals(ports.owner(c,actor.tenantId(),opportunity,"SUPPLEMENT_RECEIPT")))actions=List.of("REQUEST_CONTRACT_RECEIPT_REVIEW");}catch(Blocked denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}}
   value.put("allowedActions",actions);try{value.put("accountLabel",ports.account(actor.tenantId()).label());}catch(Blocked absent){if(!"PAYMENT_ACCOUNT_NOT_CONFIGURED".equals(absent.code()))throw absent;value.put("accountLabel",null);}
   String explanation=null;var review=row(c,"select d.* from contract.payment_review d join contract.payment_workflow p on p.tenant_id=d.tenant_id and p.payment_workflow_id=d.workflow_id where p.tenant_id=? and p.request_id=? and d.decision_code in ('RETURNED','SUPPLEMENTED') order by d.created_at desc,d.payment_review_id desc limit 1",actor.tenantId(),w.get("request_id"));
   if(review!=null){var clear=ports.open(actor.tenantId(),opportunity.id(),id(review.get("payment_review_id")),(byte[])review.get("body_ciphertext"));if(!MessageDigest.isEqual(digest(ports.encode(clear)),(byte[])review.get("body_digest")))throw new IllegalStateException("Payment review integrity failure");explanation=(String)clear.get("explanation");}
   if(explanation==null){var request=row(c,"select * from contract.payment_request where tenant_id=? and payment_request_id=?",actor.tenantId(),w.get("request_id"));var clear=ports.open(actor.tenantId(),opportunity.id(),id(w.get("request_id")),(byte[])request.get("body_ciphertext"));if(!MessageDigest.isEqual(digest(ports.encode(clear)),(byte[])request.get("body_digest")))throw new IllegalStateException("Payment request integrity failure");explanation=(String)clear.get("explanation");}
   value.put("explanation",explanation);out.add(Collections.unmodifiableMap(value));
  }
  return List.copyOf(out);
 }
 public Recovery recovery(Connection c,Actor actor,Subject opportunity)throws SQLException{
  ports.lockAuthority(c,actor);if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");ports.authorize(c,actor,opportunity,"PAYMENT_TASK_RECOVER",List.of());
  for(var w:rows(c,"select w.* from contract.payment_workflow w where tenant_id=? and opportunity_id=? and stage_code<>'COMPLETE' and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id) order by created_at,payment_workflow_id",actor.tenantId(),opportunity.id())){
   var old=w.get("task_id")==null?null:ports.task(c,actor.tenantId(),id(w.get("task_id")));UUID owner=ports.owner(c,actor.tenantId(),opportunity,(String)w.get("target_stage_code"),old==null?null:old.owner());
   if(old==null&&owner!=null||old!=null&&"OPEN".equals(old.state())&&!old.owner().equals(owner))return new Recovery(fact("payment_request",w.get("request_id")),fact("payment_workflow",w.get("payment_workflow_id")));
  }
  var h=row(c,"select h.archive_id from contract.signature_handoff h join contract.signature_readiness s on s.tenant_id=h.tenant_id and s.signature_readiness_id=h.readiness_id join contract.contract_revision v on v.tenant_id=s.tenant_id and v.contract_revision_id=s.contract_revision_id join contract.contract k on k.tenant_id=v.tenant_id and k.contract_id=v.contract_id where h.tenant_id=? and h.opportunity_id=? and k.current_revision_id=v.contract_revision_id and k.approved_revision_id=v.contract_revision_id and not exists(select 1 from contract.payment_request r where r.tenant_id=h.tenant_id and r.handoff_id=h.signature_handoff_id and r.material_version_id is null) order by h.created_at desc limit 1",actor.tenantId(),opportunity.id());
  return h==null?null:new Recovery(fact("signature_archive",h.get("archive_id")),null);
 }
 public Subject reconcile(Connection c,Actor actor,Subject opportunity,Recovery expected)throws SQLException{
  transaction(c);ports.lockAuthority(c,actor);if(actor.principalKind()!=PrincipalKind.SERVICE||expected==null||expected.source()==null||!Long.valueOf(0).equals(expected.source().revision()))throw new Blocked("NOT_AUTHORIZED");
  var o=lock(c,actor.tenantId(),opportunity.id());if(!o.equals(opportunity))throw stale();ports.authorize(c,actor,o,"PAYMENT_TASK_RECOVER",List.of(expected.source()));
  if(expected.source().type().equals("contract.signature_archive")&&expected.workflow()==null){var h=row(c,"select signature_handoff_id from contract.signature_handoff where tenant_id=? and opportunity_id=? and archive_id=?",actor.tenantId(),o.id(),expected.source().id());if(h==null)throw stale();return start(c,actor,o,id(h.get("signature_handoff_id")));}
  if(!expected.source().type().equals("contract.payment_request")||expected.workflow()==null)throw stale();
  var w=row(c,"select request_id,opportunity_id from contract.payment_workflow where tenant_id=? and payment_workflow_id=?",actor.tenantId(),expected.workflow().id());if(w==null||!o.id().equals(w.get("opportunity_id"))||!expected.source().id().equals(w.get("request_id")))throw stale();return recover(c,actor,expected.workflow());
 }
 public Subject start(Connection c,Actor actor,Subject expected,UUID handoff)throws SQLException{
  transaction(c);ports.lockAuthority(c,actor);if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");var o=lock(c,actor.tenantId(),expected.id());if(!o.equals(expected))throw stale();
  ports.authorize(c,actor,o,"PAYMENT_TASK_RECOVER",List.of(fact("signature_handoff",handoff)));var b=basis(c,actor.tenantId(),handoff,o.id());
  if(row(c,"select payment_request_id from contract.payment_request where tenant_id=? and handoff_id=? and material_version_id is null",actor.tenantId(),handoff)!=null)throw stale();
  UUID request=fresh(c);Instant now=ports.now(c),due=ports.due(instant(b.get("handed_at")));
  String clear=ports.encode(Map.of("explanation","签署归档交接后的收款核对"));
  write(c,"insert into contract.payment_request(tenant_id,payment_request_id,opportunity_id,handoff_id,contract_revision_id,recorded_by,due_at,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),request,o.id(),handoff,b.get("contract_revision_id"),actor.appointmentId(),due,ports.seal(actor.tenantId(),o.id(),request,clear),digest(clear),now);
  return advance(c,actor,o,request,null,null,"CHECK_RECEIPT",null,now,due);
 }
 public Subject recover(Connection c,Actor actor,Subject expected)throws SQLException{
  transaction(c);ports.lockAuthority(c,actor);if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");
  if(expected==null||!expected.type().equals("contract.payment_workflow")||!Long.valueOf(0).equals(expected.revision()))throw stale();
  var first=row(c,"select opportunity_id from contract.payment_workflow where tenant_id=? and payment_workflow_id=?",actor.tenantId(),expected.id());if(first==null)throw stale();var o=lock(c,actor.tenantId(),id(first.get("opportunity_id")));
  var w=row(c,"select w.* from contract.payment_workflow w where tenant_id=? and payment_workflow_id=? and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)",actor.tenantId(),expected.id());if(w==null||"COMPLETE".equals(w.get("stage_code")))throw stale();
  ports.authorize(c,actor,o,"PAYMENT_TASK_RECOVER",List.of(expected));String target=(String)w.get("target_stage_code");
  Task old=w.get("task_id")==null?null:ports.task(c,actor.tenantId(),id(w.get("task_id")));UUID nextOwner=ports.owner(c,actor.tenantId(),o,target,old==null?null:old.owner());
  if(old==null&&nextOwner==null||old!=null&&"OPEN".equals(old.state())&&old.owner().equals(nextOwner))throw stale();
  if(old!=null&&!"OPEN".equals(old.state()))throw stale();Instant now=ports.now(c);if(old!=null)ports.cancel(c,actor.tenantId(),old,now);
  return advance(c,actor,o,id(w.get("request_id")),w,null,target,null,now,instant(w.get("due_at")));
 }
 private record Current(Subject opportunity,Map<String,Object> workflow,Map<String,Object> request,Map<String,Object> version,Task task){}
 private Current current(Connection c,Actor actor,Subject expected,String stage,String authority)throws SQLException{
  transaction(c);ports.lockAuthority(c,actor);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");if(expected==null||!expected.type().equals("contract.payment_workflow")||!Long.valueOf(0).equals(expected.revision()))throw stale();
  var first=row(c,"select opportunity_id from contract.payment_workflow where tenant_id=? and payment_workflow_id=?",actor.tenantId(),expected.id());if(first==null)throw stale();var o=lock(c,actor.tenantId(),id(first.get("opportunity_id")));
  var w=row(c,"select w.* from contract.payment_workflow w where tenant_id=? and payment_workflow_id=? and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)",actor.tenantId(),expected.id());
  if(w==null||!stage.equals(w.get("stage_code"))||!actor.appointmentId().equals(w.get("owner_appointment_id")))throw stale();
  var task=ports.task(c,actor.tenantId(),id(w.get("task_id")));if(task==null||!"OPEN".equals(task.state())||!actor.appointmentId().equals(task.owner()))throw stale();
  ports.authorize(c,actor,o,authority,List.of(expected,task.selector()));
  var r=row(c,"select * from contract.payment_request where tenant_id=? and payment_request_id=?",actor.tenantId(),w.get("request_id"));
  var b=basis(c,actor.tenantId(),id(r.get("handoff_id")),o.id());if(!Objects.equals(r.get("contract_revision_id"),b.get("contract_revision_id")))throw stale();return new Current(o,w,r,b,task);
 }
 private void accepted(Connection c,Actor actor,Current x,UUID material,String sha,String authority)throws SQLException{
  ports.material(c,actor,x.opportunity(),material,sha,authority);
  if(row(c,"select evidence_submission_id from opportunity.material_version where tenant_id=? and opportunity_id=? and material_version_id=? and evidence_submission_id is not null",actor.tenantId(),x.opportunity().id(),material)==null)throw new Blocked("STALE_EVIDENCE");
 }
 public Subject request(Connection c,Actor actor,Subject expected,UUID material,String sha,String reason)throws SQLException{
  transaction(c);ports.lockAuthority(c,actor);explanation(reason);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");if(expected==null||!"contract.payment_workflow".equals(expected.type())||!Long.valueOf(0).equals(expected.revision()))throw stale();
  var first=row(c,"select opportunity_id from contract.payment_workflow where tenant_id=? and payment_workflow_id=?",actor.tenantId(),expected.id());if(first==null)throw stale();var o=lock(c,actor.tenantId(),id(first.get("opportunity_id")));
  var w=row(c,"select w.* from contract.payment_workflow w where tenant_id=? and payment_workflow_id=? and stage_code='COMPLETE' and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)",actor.tenantId(),expected.id());if(w==null)throw stale();ports.authorize(c,actor,o,"PAYMENT_SUBMIT",List.of(expected));if(!actor.appointmentId().equals(ports.owner(c,actor.tenantId(),o,"SUPPLEMENT_RECEIPT")))throw new Blocked("NOT_AUTHORIZED");
  var previous=row(c,"select * from contract.payment_request where tenant_id=? and payment_request_id=?",actor.tenantId(),w.get("request_id"));var v=basis(c,actor.tenantId(),id(previous.get("handoff_id")),o.id());
  if(row(c,"select w.payment_workflow_id from contract.payment_workflow w join contract.payment_request r on r.tenant_id=w.tenant_id and r.payment_request_id=w.request_id where w.tenant_id=? and r.handoff_id=? and w.stage_code<>'COMPLETE' and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)",actor.tenantId(),previous.get("handoff_id"))!=null)throw new Blocked("PAYMENT_REVIEW_ALREADY_ACTIVE");
  if(row(c,"select payment_request_id from contract.payment_request where tenant_id=? and handoff_id=? and material_version_id=?",actor.tenantId(),previous.get("handoff_id"),material)!=null)throw new Blocked("PAYMENT_EVIDENCE_ALREADY_SUBMITTED");
  accepted(c,actor,new Current(o,w,previous,v,null),material,sha,"PAYMENT_SUBMIT");UUID request=fresh(c);Instant now=ports.now(c),due=ports.due(now);String clear=ports.encode(Map.of("explanation",reason.strip(),"materialVersionId",material.toString(),"materialSha256",sha));
  write(c,"insert into contract.payment_request(tenant_id,payment_request_id,opportunity_id,handoff_id,contract_revision_id,material_version_id,recorded_by,due_at,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),request,o.id(),previous.get("handoff_id"),v.get("contract_revision_id"),material,actor.appointmentId(),due,ports.seal(actor.tenantId(),o.id(),request,clear),digest(clear),now);
  advance(c,actor,o,request,null,null,"CHECK_RECEIPT",null,now,due);return fact("payment_request",request);
 }
 public Subject confirm(Connection c,Actor actor,Subject workflow,PaymentReceiptInput input)throws SQLException{
  Objects.requireNonNull(input);var x=current(c,actor,workflow,"CHECK_RECEIPT","PAYMENT_CONFIRM");var v=x.version();
  if(!input.contractId().equals(v.get("contract_id"))||!input.contractRevisionId().equals(v.get("contract_revision_id")))throw stale();
  accepted(c,actor,x,input.materialVersionId(),input.materialSha256(),"PAYMENT_CONFIRM");Instant now=ports.now(c);input.validateAt(now);var account=Objects.requireNonNull(ports.account(actor.tenantId()));
  byte[] reference=protection.digest(actor.tenantId(),account.code(),input.transactionReference());
  if(row(c,"select payment_confirmation_id from contract.payment_confirmation where tenant_id=? and provider_account_code=? and provider_transaction_key_hmac=? and confirmation_type='RECEIPT'",actor.tenantId(),account.code(),reference)!=null)throw new Blocked("PAYMENT_ALREADY_RECORDED");
  var totals=row(c,"select coalesce(sum(amount_minor),0) total,count(*) filter(where confirmation_type<>'RECEIPT' or currency_code<>?) invalid,coalesce(max(confirmation_no),0) sequence from contract.payment_confirmation where tenant_id=? and contract_id=? and contract_revision_id=?",input.currency(),actor.tenantId(),input.contractId(),input.contractRevisionId());
  if(((Number)totals.get("invalid")).longValue()!=0)throw stale();long previous=((java.math.BigDecimal)totals.get("total")).longValueExact();boolean prepay=Boolean.TRUE.equals(v.get("receipt_required_before_transfer"));Long required=v.get("required_amount_minor")==null?null:((Number)v.get("required_amount_minor")).longValue();var outcome=PaymentReviewOutcome.confirmed(prepay,required,previous,input.amountMinor());
  UUID confirmation=fresh(c);var clear=new LinkedHashMap<String,Object>();clear.put("decision","CONFIRMED");clear.put("contractId",input.contractId().toString());clear.put("contractRevisionId",input.contractRevisionId().toString());clear.put("materialVersionId",input.materialVersionId().toString());clear.put("materialSha256",input.materialSha256());clear.put("accountCode",account.code());clear.put("transactionReference",input.transactionReference());clear.put("amountMinor",input.amountMinor());clear.put("currency",input.currency());clear.put("receivedAt",input.receivedAt().toString());clear.put("explanation",input.explanation());clear.put("attributionChecked",true);clear.put("remainingMinor",outcome.remainingMinor());clear.put("resumeExecution",outcome.resumeExecution());
  var evidence=row(c,"select evidence_submission_id from opportunity.material_version where tenant_id=? and material_version_id=?",actor.tenantId(),input.materialVersionId());
  var sequence=row(c,"select coalesce(max(confirmation_no),0)+1 n from contract.payment_confirmation where tenant_id=? and contract_id=?",actor.tenantId(),input.contractId());
  write(c,"insert into contract.payment_confirmation(tenant_id,payment_confirmation_id,contract_id,contract_revision_id,confirmation_no,confirmation_type,amount_minor,currency_code,provider_account_code,provider_transaction_key_hmac,evidence_submission_id,attribution_digest,effective_at,confirmed_at,recorded_by_appointment_id) values(?,?,?,?,?,'RECEIPT',?,?,?,?,?,?,?,?,?)",actor.tenantId(),confirmation,input.contractId(),input.contractRevisionId(),sequence.get("n"),input.amountMinor(),input.currency(),account.code(),reference,evidence.get("evidence_submission_id"),digest(ports.encode(clear)),input.receivedAt(),now,actor.appointmentId());
  var result=review(c,actor,x,"CONFIRMED",input.materialVersionId(),confirmation,clear,now);
  if(outcome.reviewComplete())ports.complete(c,actor.tenantId(),x.task(),result,now);
  advance(c,actor,x.opportunity(),id(x.request().get("payment_request_id")),x.workflow(),result,outcome.reviewComplete()?"COMPLETE":"CHECK_RECEIPT",outcome.reviewComplete()?null:x.task(),now,instant(x.request().get("due_at")));return result;
 }
 public Subject returnForCorrection(Connection c,Actor actor,Subject workflow,String reason)throws SQLException{
  explanation(reason);var x=current(c,actor,workflow,"CHECK_RECEIPT","PAYMENT_CONFIRM");Instant now=ports.now(c);var result=review(c,actor,x,"RETURNED",null,null,Map.of("decision","RETURNED","explanation",reason.strip()),now);ports.complete(c,actor.tenantId(),x.task(),result,now);advance(c,actor,x.opportunity(),id(x.request().get("payment_request_id")),x.workflow(),result,"SUPPLEMENT_RECEIPT",null,now,instant(x.request().get("due_at")));return result;
 }
 public Subject supplement(Connection c,Actor actor,Subject workflow,UUID material,String sha,String reason)throws SQLException{
  explanation(reason);var x=current(c,actor,workflow,"SUPPLEMENT_RECEIPT","PAYMENT_SUBMIT");accepted(c,actor,x,material,sha,"PAYMENT_SUBMIT");Instant now=ports.now(c);var result=review(c,actor,x,"SUPPLEMENTED",material,null,Map.of("decision","SUPPLEMENTED","materialVersionId",material.toString(),"materialSha256",sha,"explanation",reason.strip()),now);ports.complete(c,actor.tenantId(),x.task(),result,now);advance(c,actor,x.opportunity(),id(x.request().get("payment_request_id")),x.workflow(),result,"CHECK_RECEIPT",null,now,instant(x.request().get("due_at")));return result;
 }
 private static void explanation(String text){if(text==null||text.isBlank()||text.codePointCount(0,text.length())>2000)throw new Blocked("VALIDATION_FAILED");}
 private static byte[] digest(String clear){try{return MessageDigest.getInstance("SHA-256").digest(clear.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private Subject review(Connection c,Actor actor,Current x,String decision,UUID material,UUID confirmation,Map<String,Object> body,Instant now)throws SQLException{
  UUID id=fresh(c);String clear=ports.encode(body);write(c,"insert into contract.payment_review(tenant_id,payment_review_id,opportunity_id,workflow_id,decision_code,material_version_id,confirmation_id,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),id,x.opportunity().id(),x.workflow().get("payment_workflow_id"),decision,material,confirmation,actor.appointmentId(),ports.seal(actor.tenantId(),x.opportunity().id(),id,clear),digest(clear),now);return fact("payment_review",id);
 }
 private Subject advance(Connection c,Actor actor,Subject opportunity,UUID request,Map<String,Object> previous,Subject result,String target,Task retained,Instant now,Instant due)throws SQLException{
  UUID nextOwner=target.equals("COMPLETE")?null:retained==null?ports.owner(c,actor.tenantId(),opportunity,target):retained.owner();
  Task task=retained!=null?retained:nextOwner==null?null:ports.create(c,actor.tenantId(),opportunity,nextOwner,target,now,due);String stage=target.equals("COMPLETE")?target:nextOwner==null?"OWNER_EXCEPTION":target;
  UUID id=fresh(c);write(c,"insert into contract.payment_workflow(tenant_id,payment_workflow_id,opportunity_id,request_id,previous_workflow_id,stage_code,target_stage_code,owner_appointment_id,task_id,review_id,recorded_by,due_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),id,opportunity.id(),request,previous==null?null:previous.get("payment_workflow_id"),stage,target,nextOwner,task==null?null:task.selector().id(),result==null?null:result.id(),actor.appointmentId(),due,now);return fact("payment_workflow",id);
 }
}
