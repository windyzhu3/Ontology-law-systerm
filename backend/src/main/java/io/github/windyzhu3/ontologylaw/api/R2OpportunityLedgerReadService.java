package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
/** Exact-authorized ledger projection. No business writes and no draft/progress search. */
public final class R2OpportunityLedgerReadService {
 private final byte[] key;
 private final OpportunityLedgerLeadReader leads;
 private final OpportunityLedgerReader ledger;
 private final AuditAppender audit;
 private final OpportunityLedgerAuthorityReader authority=OpportunityLedgerAuthorityReader.databaseBacked();
 private record Cursor(Instant observed,OpportunityLedgerReader.Position after,String stamp){}
 record Context(OpportunityLedgerReader.Header header,OpportunityResponsibilityReader.Responsibility owner,UUID organization,List<Subject> facts,Subject lead,OpportunityOwnerExceptionChecks.TaskState tasks,String code,R2QuoteLedgerProjection.State quote){}
 public R2OpportunityLedgerReadService(byte[] key,LeadProtection protection,OpportunityProgressProtection progressProtection,AuditAppender audit){if(key==null||key.length<32)throw new IllegalArgumentException("Cursor key required");this.key=key.clone();leads=OpportunityLedgerLeadReader.databaseBacked(protection);ledger=OpportunityLedgerReader.databaseBacked(progressProtection);this.audit=Objects.requireNonNull(audit);}
 public Map<String,Object> read(Connection connection,Actor actor,String operation,UUID id,int limit,String encoded,String search,String state)throws SQLException {
  if(limit<1||limit>100||!Set.of("list","detail","task").contains(operation)||!"list".equals(operation)&&id==null)throw failure(400,"VALIDATION_FAILED");
  final String query=search==null?"":search.strip().toLowerCase(Locale.ROOT), filter=state==null?"ALL":state;
  if(query.length()>200||state!=null&&!Set.of("OPEN","WAITING","NONE","CLOSED").contains(state))throw failure(400,"VALIDATION_FAILED");
  return OpportunityLedgerReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!authority.hasAuthority(c,actor,now))throw failure(403,"NOT_AUTHORIZED");
   var disclosures=new ArrayList<AuditAppender.OpportunityLedgerDisclosureEntry>();
   var quoteDisclosures=new ArrayList<AuditAppender.QuoteDisclosureEntry>();
   var contractDisclosures=new ArrayList<AuditAppender.ContractDisclosureEntry>();
   Map<String,Object> result;
   if("task".equals(operation)){
    var task=TaskFactory.databaseBacked().read(c,actor.tenantId(),id);
    if(task==null||task.type()!=TaskFactory.Type.PROGRESS_OPPORTUNITY||!task.owner().equals(actor.appointmentId())||!Set.of("OPEN","WAITING").contains(task.state())||!"opportunity.opportunity".equals(task.subject().type()))throw failure(403,"NOT_AUTHORIZED");
    var context=context(c,actor,task.subject().id());if(context==null)throw failure(403,"NOT_AUTHORIZED");result=project(c,actor,context,true,disclosures,quoteDisclosures,contractDisclosures);
   }else if("detail".equals(operation)){
    var context=context(c,actor,id);if(context==null)throw failure(403,"NOT_AUTHORIZED");result=project(c,actor,context,true,disclosures,quoteDisclosures,contractDisclosures);
   }else{
    var cursor=encoded==null?new Cursor(now,null,null):decode(actor,query,filter,encoded,now);
    if(cursor.after()!=null&&!Objects.equals(cursor.stamp(),stamp(c,actor,cursor.after().id())))throw failure(409,"STALE_SUBJECT");
    var rows=ledger.scan(c,actor.tenantId(),cursor.observed(),cursor.after(),limit);
    var items=new ArrayList<Map<String,Object>>();
    for(var row:rows){var context=context(c,actor,row.id());if(context==null)continue;
     // Search only after exact source authorization. Audits cover examined protected labels too.
     var item=project(c,actor,context,false,disclosures,quoteDisclosures,contractDisclosures);
     if((filter.equals("ALL")||filter.equals(item.get("taskState")))&&((String)item.get("customerLabel")).toLowerCase(Locale.ROOT).contains(query))items.add(item);
    }
    result=new LinkedHashMap<>();result.put("items",items);if(rows.size()==limit)result.put("nextCursor",encode(actor,query,filter,new Cursor(cursor.observed(),rows.getLast(),stamp(c,actor,rows.getLast().id()))));
   }
   // OpportunityLedgerReadRuntime acquired the tenant business fence and identity lock
   // before the first source read and retains both through audit commit. Every command
   // writer takes the exclusive business fence: rebuilding all contexts here cannot
   // discover another committed business version. Keep fresh-time authorization checks
   // below, since validity windows can change without a write.

   if(!authority.hasAuthority(c,actor,R1ServiceReadRuntime.databaseTime(c)))throw failure(403,"NOT_AUTHORIZED");
   for(var d:disclosures)if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw failure(403,"NOT_AUTHORIZED");
   for(var d:quoteDisclosures)if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw failure(403,"NOT_AUTHORIZED");
   for(var d:contractDisclosures)if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw failure(403,"NOT_AUTHORIZED");
   return new OpportunityLedgerReadRuntime.Prepared<>(result,List.copyOf(disclosures),List.copyOf(quoteDisclosures),List.copyOf(contractDisclosures));
  });
 }
 Context context(Connection c,Actor a,UUID id)throws SQLException {
  var header=ledger.header(c,a.tenantId(),id);var opening=EventOpportunityReader.databaseBacked().byId(c,a.tenantId(),id);if(header==null||opening==null)return null;
  var origin=R2OpportunityOwnerExceptionAssembly.sources().read(c,a.tenantId(),opening.assignmentId(),opening.contactId());
  if(origin==null||origin.assignment()==null||origin.contact()==null||origin.task()==null)return null;
  var contact=origin.contact();var assignment=origin.assignment();var sourceTask=origin.task();
  if(!opening.leadId().equals(contact.leadId())||!opening.assignmentId().equals(contact.assignmentId())||!opening.leadId().equals(assignment.leadId())||!opening.owner().equals(assignment.owner())||!opening.owner().equals(sourceTask.owner())||!contact.taskId().equals(sourceTask.selector().id())||!opening.leadId().equals(sourceTask.subject().id())||!"lead.lead".equals(sourceTask.subject().type())||!"CONTACT_LEAD".equals(sourceTask.purpose())||!"RECORD_CONTACT_RESULT".equals(sourceTask.command())||!"CONNECTED_VALID".equals(contact.resultCode())||!"DONE".equals(sourceTask.state())||!contact.selector().equals(sourceTask.completion()))return null;
  var owner=OpportunityResponsibilityReader.databaseBacked().current(c,a.tenantId(),header.selector());
  if(owner==null)return null;
  UUID org=authority.historicalOrganization(c,a.tenantId(),opening.owner());if(org==null)return null;
  var metadata=leads.metadata(c,a.tenantId(),opening.leadId());if(metadata==null)return null;var lead=metadata.selector();
  var facts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,a.tenantId(),header.selector()));
  // Contract takeover facts use their own authorization and typed disclosure below.
   facts.removeIf(f->f.type().startsWith("contract.")||"opportunity.opportunity_progress".equals(f.type())||Set.of("opportunity.quote_draft","opportunity.quote_revision","opportunity.quote_workflow","opportunity.quote_approval_member","opportunity.quote_approval_request","opportunity.quote_approval_decision","opportunity.quote_issue","opportunity.quote_response").contains(f.type()));facts.add(header.selector());facts.add(owner.basis());facts.add(lead);
  if(metadata.partyId()!=null){var party=io.github.windyzhu3.ontologylaw.party.R1PartyReader.databaseBacked().active(c,a.tenantId(),metadata.partyId());if(party==null)return null;facts.add(new Subject("party.party",party.id(),party.revision(),null));}
  var tasks=R2OpportunityOwnerExceptionAssembly.taskState(c,a.tenantId(),header.selector(),owner);
  String code=authority.code(c,a,owner.appointmentId(),org,List.copyOf(facts),R1ServiceReadRuntime.databaseTime(c));if(code==null)return null;
  return new Context(header,owner,org,List.copyOf(facts),lead,tasks,code,R2QuoteLedgerProjection.read(c,a,id,org));
 }
 private Map<String,Object> project(Connection c,Actor a,Context x,boolean detail,List<AuditAppender.OpportunityLedgerDisclosureEntry> disclosures,List<AuditAppender.QuoteDisclosureEntry> quoteDisclosures,List<AuditAppender.ContractDisclosureEntry> contractDisclosures)throws SQLException {
  var result=new LinkedHashMap<String,Object>();result.put("opportunity",Map.of("id",x.header().selector().id().toString(),"revision",x.header().selector().revision()));
  result.put("customerLabel",leads.label(c,a.tenantId(),x.lead()));
  for(var fact:x.facts())disclose(c,a,x,fact,disclosures);
  var owner=WorkcardOwnerReader.databaseBacked().read(c,a.tenantId(),x.owner().appointmentId());result.put("ownerLabel","负责人信息受限");
  if(owner!=null){var facts=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());if(authority.permitted(c,a,x.organization(),facts,x.code())){result.put("ownerLabel",owner.principal().displayName());for(var f:facts)disclose(c,a,x,f,disclosures);}}
  String state=x.header().closed()?"CLOSED":x.tasks().task()==null?"NONE":x.tasks().state();
  if(!Set.of("OPEN","WAITING","NONE","CLOSED").contains(state))state="NONE";
  result.put("taskState",state);
  CurrentTaskReader.Task task=null;
  if(x.tasks().task()!=null){task=CurrentTaskReader.databaseBacked().read(c,a.tenantId(),x.tasks().task().id());if(task==null||!task.selector().equals(x.tasks().task()))throw failure(409,"STALE_SUBJECT");}
  if(state.equals("OPEN")){result.put("nextActionLabel","联系客户并记录进展");if(task!=null)result.put("dueAt",task.slaDueAt().toString());}
  if(state.equals("WAITING")){result.put("nextActionLabel","等待约定时间后继续跟进");var wait=EventResponsibilityReader.databaseBacked().latestWait(c,a.tenantId(),task.selector().id());if(wait==null||!wait.selector().equals(x.tasks().waitReceipt()))throw failure(409,"STALE_SUBJECT");result.put("dueAt",wait.resumeDue().toString());}
  if(detail){
   boolean can=x.quote()==null&&state.equals("OPEN")&&x.tasks().lineageValid()&&task!=null&&a.appointmentId().equals(task.owner())&&a.appointmentId().equals(x.owner().appointmentId())&&x.owner().basis().equals(task.responsibilityBasis())&&authority.permitted(c,a,x.organization(),x.facts(),OpportunityLedgerAuthorityReader.OWN);
   var ownIdentity=owner==null?List.<Subject>of():List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());
   can=can&&!ownIdentity.isEmpty()&&authority.permitted(c,a,x.organization(),ownIdentity,OpportunityLedgerAuthorityReader.OWN);
   if(can){var authTask=AuthorizationTaskReader.databaseBacked().read(c,a.tenantId(),task.selector().id());if(authTask==null||!authTask.selector().equals(task.selector()))can=false;else if(authTask.draft()!=null){var d=authTask.draft();can=d.schemaVersion()==1&&task.type().schema.equals(d.schemaCode())&&task.type().command.equals(d.actionCode())&&authority.permitted(c,a,x.organization(),List.of(d.selector()),OpportunityLedgerAuthorityReader.OWN);}}
   if(can){var ownContext=new Context(x.header(),x.owner(),x.organization(),x.facts(),x.lead(),x.tasks(),OpportunityLedgerAuthorityReader.OWN,x.quote());for(var f:x.facts())disclose(c,a,ownContext,f,disclosures);for(var f:ownIdentity)disclose(c,a,ownContext,f,disclosures);var d=AuthorizationTaskReader.databaseBacked().read(c,a.tenantId(),task.selector().id()).draft();if(d!=null)disclose(c,a,ownContext,d.selector(),disclosures);}
   result.put("canHandle",can);if(can)result.put("task",Map.of("id",task.selector().id().toString(),"revision",task.selector().revision(),"etag",R1ResourceTags.task(a,task.selector(),task.state(),task.responsibilityBasis())));
   var progress=ledger.latestProgress(c,a.tenantId(),x.header().selector().id());
   if(progress!=null&&authority.permitted(c,a,x.organization(),List.of(progress.selector()),x.code())){
    String body=ledger.progressBody(c,a.tenantId(),x.header().selector().id(),progress.selector());
    try{var tree=tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);var values=tree.path("values");if(!values.path("summary").isTextual()||!values.path("occurredAt").isTextual()||!OpportunityProgressInput.CONTRACT.equals(tree.path("profile").asText())||!a.tenantId().toString().equals(tree.path("tenantId").asText())||!x.header().selector().id().toString().equals(tree.path("opportunityId").asText())||!progress.selector().id().toString().equals(tree.path("progressId").asText())||!progress.occurredAt().equals(Instant.parse(values.path("occurredAt").asText()))||values.path("summary").asText().isBlank()||values.path("summary").asText().codePointCount(0,values.path("summary").asText().length())>2000)throw new IllegalArgumentException();result.put("lastProgress",Map.of("summary",values.path("summary").asText(),"occurredAt",values.path("occurredAt").asText()));}catch(RuntimeException invalid){throw new SQLException("Invalid progress projection","22000");}
    disclose(c,a,x,progress.selector(),disclosures);
    if(!progress.equals(ledger.latestProgress(c,a.tenantId(),x.header().selector().id())))throw failure(409,"STALE_SUBJECT");
   }
  }
  if(x.quote()!=null&&!x.header().closed())R2QuoteLedgerProjection.apply(c,a,x.organization(),x.quote(),detail,result,quoteDisclosures);
  if(!x.header().closed())R2ContractTaskProjection.apply(c,a,x.header().selector(),x.organization(),detail,result,contractDisclosures);
  return result;
 }
 private void disclose(Connection c,Actor a,Context x,Subject fact,List<AuditAppender.OpportunityLedgerDisclosureEntry> out)throws SQLException{var evidence=authority.evidence(c,a,x.organization(),fact,x.code());if(evidence==null||!evidence.allowed())throw failure(403,"NOT_AUTHORIZED");out.add(new AuditAppender.OpportunityLedgerDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,evidence));}
 private String stamp(Connection c,Actor a,UUID id)throws SQLException {
  var x=context(c,a,id);if(x==null)return "unavailable";
  var dependencies=new ArrayList<String>();dependencies.add(x.toString());
  for(var f:x.facts()){var e=authority.evidence(c,a,x.organization(),f,x.code());if(e==null||!e.allowed())return "unavailable";dependencies.add(String.valueOf(e.stableDependencies()));}
  return Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(dependencies)));
 }
 private String encode(Actor a,String q,String filter,Cursor cursor){String raw=String.join("\n","R2_LEDGER_CURSOR_V1",a.tenantId().toString(),a.principalId().toString(),a.appointmentId().toString(),Base64.getUrlEncoder().withoutPadding().encodeToString(q.getBytes(StandardCharsets.UTF_8)),filter,cursor.observed().toString(),cursor.after().at().toString(),cursor.after().id().toString(),cursor.stamp());byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);return seal(bytes);}
 private Cursor decode(Actor a,String q,String filter,String value,Instant now){try{if(value.length()>2048)throw new IllegalArgumentException();byte[] bytes=openCursor(value);var f=new String(bytes,StandardCharsets.UTF_8).split("\n",-1);if(f.length!=10||!f[0].equals("R2_LEDGER_CURSOR_V1")||!f[1].equals(a.tenantId().toString())||!f[2].equals(a.principalId().toString())||!f[3].equals(a.appointmentId().toString())||!f[4].equals(Base64.getUrlEncoder().withoutPadding().encodeToString(q.getBytes(StandardCharsets.UTF_8)))||!f[5].equals(filter))throw new IllegalArgumentException();var observed=Instant.parse(f[6]);var at=Instant.parse(f[7]);if(observed.isAfter(now)||!now.isBefore(observed.plusSeconds(300))||at.isAfter(observed))throw new IllegalArgumentException();return new Cursor(observed,new OpportunityLedgerReader.Position(at,UUID.fromString(f[8])),f[9]);}catch(IllegalArgumentException|DateTimeException invalid){throw failure(400,"VALIDATION_FAILED");}}
 private String seal(byte[] clear){try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(mac("R2_LEDGER_CURSOR_ENCRYPTION_V1".getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,nonce));byte[] encrypted=cipher.doFinal(clear);return Base64.getUrlEncoder().withoutPadding().encodeToString(java.nio.ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
 private byte[] openCursor(String value){try{byte[] bytes=Base64.getUrlDecoder().decode(value);if(bytes.length<29||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(mac("R2_LEDGER_CURSOR_ENCRYPTION_V1".getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));return cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length));}catch(GeneralSecurityException invalid){throw new IllegalArgumentException("Invalid cursor");}}
 private byte[] mac(byte[] input){try{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(input);}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
 private static R1ServiceReadRuntime.Failure failure(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
