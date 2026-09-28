package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Composition of exact Owner sources; query permission never supplies another person's command authority. */
final class R25LeadManagementReadService {
 static final String AUTHORITY="LEAD_MANAGEMENT_READ",SLOT="SOURCE_INTAKE_OWNER";
 static final Set<String> STATES=Set.of("INCOMPLETE","DUPLICATE","ASSIGNMENT","CONTACT","VALIDITY_REVIEW","SOURCE_REVIEW","ROUTING","WAITING","INVALID","CLOSED","OPPORTUNITY","NEEDS_REVIEW");
 private final R2ManagementCursor cursor;
 private final LeadManagementReader leads;
 private final CurrentLeadReader leadFacts;
 private final R1SourcePolicyRegistry policies;
 private final Map<String,LeadIntakeSources.Source> sources;
 private final AuditAppender audit;
 private final OpportunityOwnerExceptionAuthorityReader identity=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 R25LeadManagementReadService(byte[] key,LeadProtection protection,R1SourcePolicyRegistry policies,List<LeadIntakeSources.Source> sources,AuditAppender audit){
  cursor=new R2ManagementCursor(key);leads=LeadManagementReader.databaseBacked(protection);leadFacts=CurrentLeadReader.databaseBacked(protection);this.policies=Objects.requireNonNull(policies);this.audit=Objects.requireNonNull(audit);
  new LeadIntakeSources(policies,sources);var indexed=new LinkedHashMap<String,LeadIntakeSources.Source>();for(var source:sources)indexed.put(source.sourceAccountCode(),source);this.sources=Map.copyOf(indexed);
 }
 Map<String,Object> list(Connection c,Actor actor,int limit,String encoded,String search,String source,String owner,String state)throws SQLException{
  String q=normalize(search),s=normalize(source),o=normalize(owner),filter=normalize(state);
  if(limit<1||limit>100||q.length()>200||q.chars().anyMatch(Character::isISOControl)||!s.isEmpty()&&!s.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||!filter.isEmpty()&&!STATES.contains(filter))throw fail(400,"VALIDATION_FAILED");
  UUID selectedOwner=null;if(!o.isEmpty())try{selectedOwner=UUID.fromString(o);}catch(IllegalArgumentException invalid){throw fail(400,"VALIDATION_FAILED");}final UUID ownerId=selectedOwner;
  String scope=CanonicalJson.encode(Map.of("purpose","R25_LEAD_MANAGEMENT_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"limit",limit,"search",q,"source",s,"owner",o,"state",filter));
  return ManagementReadRuntime.read(c,actor,audit,(tx,now)->{
   requireRead(tx,actor,now);UUID after=cursor.decode(encoded,scope,now),last=after;int consumed=0;
   var candidates=leads.scan(tx,actor.tenantId(),after,100);var items=new ArrayList<Map<String,Object>>();var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();
   for(var candidate:candidates){consumed++;last=candidate.selector().id();if(!s.isEmpty()&&!s.equals(candidate.sourceAccount()))continue;
    var projection=project(tx,actor,candidate,ds,false);if(projection==null)continue;
    if(!filter.isEmpty()&&!filter.equals(projection.state())||ownerId!=null&&!ownerId.equals(projection.owner()))continue;
    if(!projection.search().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))continue;
    items.add(projection.value());if(items.size()==limit)break;
   }
   requireRead(tx,actor,R1ServiceReadRuntime.databaseTime(tx));var result=new LinkedHashMap<String,Object>();result.put("items",List.copyOf(items));result.put("nextCursor",last!=null&&(consumed<candidates.size()||candidates.size()==100)?cursor.encode(last,scope,now):null);
   return new ManagementReadRuntime.Prepared<>(Collections.unmodifiableMap(result),List.copyOf(ds));
  });
 }
 Map<String,Object> detail(Connection c,Actor actor,UUID id)throws SQLException{
  return ManagementReadRuntime.read(c,actor,audit,(tx,now)->{
   requireRead(tx,actor,now);var source=leads.metadata(tx,actor.tenantId(),id);if(source==null)throw fail(404,"NOT_FOUND");
   var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();var projection=project(tx,actor,source,ds,true);if(projection==null)throw fail(403,"NOT_AUTHORIZED");
   requireRead(tx,actor,R1ServiceReadRuntime.databaseTime(tx));return new ManagementReadRuntime.Prepared<>(projection.value(),List.copyOf(ds));
  });
 }
 private record Projection(Map<String,Object> value,String state,UUID owner,String search){}
 Map<String,Object> sources(Connection c,Actor actor)throws SQLException{
  return ManagementReadRuntime.read(c,actor,audit,(tx,now)->{
   requireRead(tx,actor,now);var ds=new ArrayList<AuditAppender.ManagementDisclosureEntry>();var items=new ArrayList<Map<String,Object>>();
   for(var source:sources.values().stream().sorted(Comparator.comparing(LeadIntakeSources.Source::displayName).thenComparing(LeadIntakeSources.Source::sourceAccountCode)).toList()){
    var policy=policies.find(source.sourceAccountCode());var root=AuthorizationIdentityReader.databaseBacked().organization(tx,actor.tenantId(),policy.sourceIntakeRootCode());
    if(root==null||!authorize(tx,actor,List.of(root),root.id(),ds))continue;
    var intake=IdentityAdminReader.databaseBacked().find(tx,actor.tenantId(),IdentityAdminReader.Kind.ORGANIZATION,root.id());
    if(intake==null||!intake.fact().equals(root))throw fail(412,"STALE_SUBJECT");
    String supervisorLabel="责任范围信息不可见";
    var supervisor=AuthorizationIdentityReader.databaseBacked().organization(tx,actor.tenantId(),policy.routingSupervisorRootCode());
    if(supervisor!=null&&authorize(tx,actor,List.of(supervisor),supervisor.id(),ds)){
     var resource=IdentityAdminReader.databaseBacked().find(tx,actor.tenantId(),IdentityAdminReader.Kind.ORGANIZATION,supervisor.id());
     if(resource==null||!resource.fact().equals(supervisor))throw fail(412,"STALE_SUBJECT");supervisorLabel=(String)resource.values().get("displayName");
    }
    items.add(Map.of("code",source.sourceAccountCode(),"label",source.displayName(),"channel",source.sourceChannelCode(),"assignmentMode",policy.assignmentMode().name(),"intakeLabel",(String)intake.values().get("displayName"),"supervisorLabel",supervisorLabel));
   }
   requireRead(tx,actor,R1ServiceReadRuntime.databaseTime(tx));return new ManagementReadRuntime.Prepared<>(Map.of("items",List.copyOf(items)),List.copyOf(ds));
  });
 }
 private Projection project(Connection c,Actor actor,LeadManagementReader.Metadata lead,List<AuditAppender.ManagementDisclosureEntry> ds,boolean detail)throws SQLException{
  UUID tenant=actor.tenantId();var policy=policies.find(lead.sourceAccount());if(policy==null)return null;
  var root=AuthorizationIdentityReader.databaseBacked().organization(c,tenant,policy.sourceIntakeRootCode());if(root==null)return null;
  var assignment=lead.assignment()==null?null:leadFacts.assignment(c,tenant,lead.assignment());if(lead.assignment()!=null&&(assignment==null||!assignment.leadId().equals(lead.selector().id())))return null;
  UUID org=assignment==null?root.id():identity.historicalOrganization(c,tenant,assignment.owner());if(org==null)return null;
  var facts=new LinkedHashSet<Subject>();facts.add(lead.selector());if(assignment!=null)facts.add(assignment.selector());
  // Refuse the lead before opening any encrypted label or contact field.
  if(!authorize(c,actor,List.copyOf(facts),org,ds))return null;
  var responsibility=TeamResponsibilityReader.databaseBacked();var current=responsibility.currentForLead(c,tenant,lead.selector().id());
  for(var task:current){facts.add(task.selector());facts.add(task.subject());if(task.responsibilityBasis()!=null)facts.add(task.responsibilityBasis());}
  var last=current.isEmpty()?responsibility.lastDecisionForLead(c,tenant,lead.selector().id()):null;
  if(last!=null){facts.add(last.selector());facts.add(last.subject());facts.add(last.completion());}
  var contact=leads.latestContact(c,tenant,lead.selector().id());if(contact!=null)facts.add(contact);
  var opportunity=contact==null?null:EventOpportunityReader.databaseBacked().forContact(c,tenant,contact.id());if(opportunity!=null)facts.add(opportunity.selector());
  var party=lead.party()==null?null:R1PartyReader.databaseBacked().active(c,tenant,lead.party());if(party!=null)facts.add(new Subject("party.party",party.id(),party.revision(),null));
  if(!authorize(c,actor,List.copyOf(facts),org,ds))return null;
  String reason=null,state;var selected=current.size()==1?current.getFirst():null;
  if(opportunity!=null)state="OPPORTUNITY";
  else if(current.size()>1||selected!=null&&!selected.subject().equals(lead.selector()))state="NEEDS_REVIEW";
  else if(selected!=null)state=selected.state().equals("WAITING")?"WAITING":switch(selected.type()){
   case COMPLETE_LEAD_INGRESS->"INCOMPLETE";case RESOLVE_LEAD_DUPLICATE->"DUPLICATE";case ASSIGN_LEAD->"ASSIGNMENT";case CONTACT_LEAD->"CONTACT";case REVIEW_LEAD_VALIDITY->"VALIDITY_REVIEW";case ACK_SOURCE_INTAKE_STOP_REQUEST,RESOLVE_SOURCE_REQUEST->"SOURCE_REVIEW";case RESOLVE_LEAD_ROUTING_GAP->"ROUTING";default->"NEEDS_REVIEW";
  };
  else if(last!=null){var decision=CurrentTaskReader.databaseBacked().decision(c,tenant,last.completion().id());if(decision==null||!decision.selector().equals(last.completion())||!decision.taskId().equals(last.selector().id()))throw fail(412,"STALE_SUBJECT");reason=decision.rationale();state=switch(decision.code()){case "CONFIRM_INVALID"->"INVALID";case "CLOSE_UNREACHED","END_LEAD"->"CLOSED";default->"NEEDS_REVIEW";};}
  else state="NEEDS_REVIEW";
  var text=leads.summary(c,tenant,lead.selector());String customer=first(text.customerName(),text.capturedName(),"未填写客户名称");
  if(party!=null){var named=R1PartyReader.databaseBacked().named(c,tenant,party.id());if(named==null||named.revision()!=party.revision())throw fail(412,"STALE_SUBJECT");customer=named.canonicalName();}
  UUID owner=selected!=null?selected.owner():assignment==null?null:assignment.owner();String ownerLabel="尚未分配";boolean ownerVisible=false;
  if(owner!=null){var person=WorkcardOwnerReader.databaseBacked().read(c,tenant,owner);ownerLabel="责任人信息不可见";
   if(person!=null&&authorize(c,actor,List.of(person.appointment().selector(),person.principal().selector(),person.organization().selector()),org,ds)){ownerVisible=true;ownerLabel=person.principal().displayName()+" · "+person.organization().displayName();}
  }
  String sourceLabel="原接入来源";var configured=sources.get(lead.sourceAccount());if(configured!=null&&authorize(c,actor,List.of(root),root.id(),ds))sourceLabel=configured.displayName();
  var result=new LinkedHashMap<String,Object>();result.put("id",lead.selector().id().toString());result.put("customerLabel",customer);result.put("contactLabel",first(text.contactName(),text.capturedName(),"未填写联系人"));result.put("sourceLabel",sourceLabel);result.put("state",state);result.put("stateLabel",label(state));result.put("ownerId",ownerVisible?owner.toString():null);result.put("ownerLabel",ownerLabel);result.put("capturedAt",lead.capturedAt().toString());
  if(detail){
   var values=new ArrayList<List<String>>();values.add(List.of("当前状态",label(state)));values.add(List.of("接入来源",sourceLabel));values.add(List.of("当前责任人",ownerLabel));values.add(List.of("录入时间",lead.capturedAt().toString()));values.add(List.of("联系电话",first(text.phone(),"未提供")));values.add(List.of("联系邮箱",first(text.email(),"未提供")));
   if(contact!=null){var recorded=leadFacts.contactResult(c,tenant,contact.id());if(recorded==null||!recorded.selector().equals(contact)||!recorded.leadId().equals(lead.selector().id()))throw fail(412,"STALE_SUBJECT");values.add(List.of("最近联系时间",recorded.resultedAt().toString()));values.add(List.of("最近联系说明",first(recorded.summary(),"原记录未包含说明")));}
   if(reason!=null)values.add(List.of("原处置说明",reason));
   boolean can=selected!=null&&!"NEEDS_REVIEW".equals(state)&&R2TeamManagementReadService.canHandle(c,actor,selected,List.copyOf(facts),org);
   result.put("facts",List.copyOf(values));result.put("taskId",can?selected.selector().id().toString():null);result.put("action",can?Map.of("kind","task","label","前往原工作卡"):null);result.put("opportunityId",opportunity==null?null:opportunity.selector().id().toString());result.put("nextAction",can?"回到原责任核对并办理":state.equals("NEEDS_REVIEW")?"原责任依据需要核对；本次查询不判定为衔接异常":"按原责任和授权范围继续；此处不代办");
  }
  return new Projection(Collections.unmodifiableMap(result),state,owner,customer+" "+first(text.phone(),"")+" "+first(text.email(),""));
 }
 private boolean authorize(Connection c,Actor actor,List<Subject> facts,UUID org,List<AuditAppender.ManagementDisclosureEntry> ds)throws SQLException{
  var exact=facts.stream().distinct().toList();var decisions=R1AuthorityReader.databaseBacked().authorizeAll(c,actor,exact,org,SLOT,AUTHORITY);if(decisions.size()!=exact.size())return false;
  for(int i=0;i<exact.size();i++)ds.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),exact.get(i),decisions.get(i)));return true;
 }
 private void requireRead(Connection c,Actor actor,Instant now)throws SQLException{if(!identity.hasAuthority(c,actor,AUTHORITY,now))throw fail(403,"NOT_AUTHORIZED");}
 private static String normalize(String value){return value==null?"":value.strip();}
 private static String first(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return "";}
 static String label(String state){return switch(state){case "INCOMPLETE"->"待补齐资料";case "DUPLICATE"->"重复待核";case "ASSIGNMENT"->"待分配";case "CONTACT"->"待联系";case "VALIDITY_REVIEW"->"有效性待复核";case "SOURCE_REVIEW"->"来源请求待处置";case "ROUTING"->"分配安排待核对";case "WAITING"->"等待约定核对";case "INVALID"->"已判无效";case "CLOSED"->"本次线索已终止";case "OPPORTUNITY"->"已转商机";default->"责任依据待核对";};}
 private static R1ServiceReadRuntime.Failure fail(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
