package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.contract.ContractOverviewReader;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.transfer.TransferOverviewReader;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only, source-scoped counting. Counts and drilldown use the same exact candidate authorization. */
final class R25BusinessOverviewReadService {
 enum Metric {
  LEADS("leads","新增线索","LEAD_MANAGEMENT_READ"),OPPORTUNITIES("opportunities","有效商机","OPPORTUNITY_LEDGER_READ"),
  SIGNED("signedContracts","签署归档合同","CONTRACT_READ"),MATTERS("acceptedMatters","已接收案件","TRANSFER_LEDGER_READ"),OVERDUE("overdueTasks","当前逾期待办","TEAM_TASK_READ");
  final String key,label,authority;Metric(String key,String label,String authority){this.key=key;this.label=label;this.authority=authority;}
  static Metric parse(String key){return Arrays.stream(values()).filter(m->m.key.equals(key)).findFirst().orElseThrow(()->fail(400,"VALIDATION_FAILED"));}
 }
 private record Period(String month,Instant from,Instant until,Instant observed){}
 private record Candidate(UUID id,Instant at,Subject lead,Subject party,UUID owner,UUID organization,UUID alternative,String slot,String state,List<Subject> facts){}
 private record Raw(UUID id,Instant at,Object source){}
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private static final int PAGE=100,BUDGET=5000;
 private final R2ManagementCursor cursor;
 private final LeadManagementReader leads;
 private final CurrentLeadReader leadFacts;
 private final OpportunityLedgerLeadReader labels;
 private final R1SourcePolicyRegistry policies;
 private final AuditAppender audit;
 private final OpportunityOwnerExceptionAuthorityReader identity=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 R25BusinessOverviewReadService(byte[] key,LeadProtection protection,R1SourcePolicyRegistry policies,AuditAppender audit){cursor=new R2ManagementCursor(key);leads=LeadManagementReader.databaseBacked(protection);leadFacts=CurrentLeadReader.databaseBacked(protection);labels=OpportunityLedgerLeadReader.databaseBacked(protection);this.policies=Objects.requireNonNull(policies);this.audit=Objects.requireNonNull(audit);}
 private static final class Disclosures {
  final List<AuditAppender.ManagementDisclosureEntry> management=new ArrayList<>();
  final List<AuditAppender.OpportunityLedgerDisclosureEntry> opportunities=new ArrayList<>();
  final List<AuditAppender.ContractDisclosureEntry> contracts=new ArrayList<>();
  <T>BusinessOverviewReadRuntime.Prepared<T> prepared(T value){return new BusinessOverviewReadRuntime.Prepared<>(value,management,opportunities,contracts);}
 }
 Map<String,Object> summary(Connection connection,Actor actor,String month)throws SQLException{
  return BusinessOverviewReadRuntime.read(connection,actor,audit,(c,now)->{
   var period=period(month,now);var disclosed=new Disclosures();var metrics=new ArrayList<Map<String,Object>>();var available=EnumSet.noneOf(Metric.class);int scanned=0;
   for(var metric:Metric.values()){
    boolean permitted=qualified(c,actor,metric,now);Long count=null;
    if(permitted){available.add(metric);count=0L;UUID after=null;
     while(true){var rows=scan(c,actor,metric,period,after);scanned+=rows.size();if(scanned>BUDGET)throw fail(503,"SERVICE_UNAVAILABLE");
      for(var raw:rows){var candidate=candidate(c,actor,metric,raw);if(candidate!=null&&authorize(c,actor,metric,candidate,disclosed))count++;}
      if(rows.size()<PAGE)break;after=rows.getLast().id();
     }
    }
    var item=new LinkedHashMap<String,Object>();item.put("key",metric.key);item.put("label",metric.label);item.put("status",permitted?"AVAILABLE":"FORBIDDEN");item.put("count",count);metrics.add(Collections.unmodifiableMap(item));
   }
   if(available.isEmpty())throw fail(403,"NOT_AUTHORIZED");
   freshQualification(c,actor,available);
   return disclosed.prepared(Map.of("month",period.month(),"asOf",now.toString(),"metrics",List.copyOf(metrics)));
  });
 }
 Map<String,Object> details(Connection connection,Actor actor,String key,String month,int limit,String encoded)throws SQLException{
  var metric=Metric.parse(key);if(limit<1||limit>100)throw fail(400,"VALIDATION_FAILED");
  return BusinessOverviewReadRuntime.read(connection,actor,audit,(c,now)->{
   if(!qualified(c,actor,metric,now))throw fail(403,"NOT_AUTHORIZED");var period=period(month,now);
   String scope=CanonicalJson.encode(Map.of("purpose","R25_BUSINESS_OVERVIEW_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"metric",key,"month",period.month(),"limit",limit));
   UUID after=cursor.decode(encoded,scope,now),last=after;var rows=scan(c,actor,metric,period,after);var disclosed=new Disclosures();var items=new ArrayList<Map<String,Object>>();int consumed=0;
   for(var raw:rows){last=raw.id();consumed++;var candidate=candidate(c,actor,metric,raw);if(candidate==null||!authorize(c,actor,metric,candidate,disclosed))continue;
    String name=labels.label(c,actor.tenantId(),candidate.lead());
    if(candidate.party()!=null){var named=R1PartyReader.databaseBacked().named(c,actor.tenantId(),candidate.party().id());if(named==null||named.revision()!=candidate.party().revision())throw fail(412,"STALE_SUBJECT");name=named.canonicalName();}
    items.add(Map.of("id",candidate.id().toString(),"customerLabel",name,"occurredAt",candidate.at().toString(),"stateLabel",candidate.state()));if(items.size()==limit)break;
   }
   freshQualification(c,actor,EnumSet.of(metric));var result=new LinkedHashMap<String,Object>();result.put("month",period.month());result.put("asOf",now.toString());result.put("metric",key);result.put("items",List.copyOf(items));result.put("nextCursor",last!=null&&(consumed<rows.size()||rows.size()==PAGE)?cursor.encode(last,scope,now):null);return disclosed.prepared(Collections.unmodifiableMap(result));
  });
 }
 private static Period period(String value,Instant now){
  try{var current=YearMonth.from(now.atZone(ZONE));var month=value==null?current:YearMonth.parse(value);if(month.getYear()<1970||month.isAfter(current)||value!=null&&!value.equals(month.toString()))throw fail(400,"VALIDATION_FAILED");var from=month.atDay(1).atStartOfDay(ZONE).toInstant();var end=month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();return new Period(month.toString(),from,end.isAfter(now)?now:end,now);}
  catch(java.time.DateTimeException invalid){throw fail(400,"VALIDATION_FAILED");}
 }
 private boolean qualified(Connection c,Actor actor,Metric metric,Instant now)throws SQLException{return identity.hasAuthority(c,actor,metric.authority,now)||metric==Metric.OPPORTUNITIES&&identity.hasAuthority(c,actor,"SALES_OPPORTUNITY_OWNER",now);}
 private void freshQualification(Connection c,Actor actor,Set<Metric> metrics)throws SQLException{var now=R1ServiceReadRuntime.databaseTime(c);for(var metric:metrics)if(!qualified(c,actor,metric,now))throw fail(403,"NOT_AUTHORIZED");}
 private List<Raw> scan(Connection c,Actor actor,Metric metric,Period p,UUID after)throws SQLException{
  if(metric!=Metric.OVERDUE&&!p.from().isBefore(p.until()))return List.of();UUID tenant=actor.tenantId();
  return switch(metric){
   case LEADS->LeadOverviewReader.databaseBacked().scan(c,tenant,p.from(),p.until(),after,PAGE).stream().map(r->new Raw(r.lead().id(),r.occurredAt(),r)).toList();
   case OPPORTUNITIES->OpportunityOverviewReader.databaseBacked().scan(c,tenant,p.from(),p.until(),after,PAGE).stream().map(r->new Raw(r.opportunity().id(),r.occurredAt(),r)).toList();
   case SIGNED->ContractOverviewReader.databaseBacked().scan(c,tenant,p.from(),p.until(),after,PAGE).stream().map(r->new Raw(r.id(),r.occurredAt(),r)).toList();
   case MATTERS->TransferOverviewReader.databaseBacked().scan(c,tenant,p.from(),p.until(),after,PAGE).stream().map(r->new Raw(r.id(),r.occurredAt(),r)).toList();
   case OVERDUE->ResponsibilityOverviewReader.databaseBacked().overdue(c,tenant,p.observed(),after,PAGE).stream().map(r->new Raw(r.selector().id(),r.slaDueAt(),r)).toList();
  };
 }
 private Candidate candidate(Connection c,Actor actor,Metric metric,Raw raw)throws SQLException{
  UUID tenant=actor.tenantId(),leadId,org,alternative=null,owner=null;String slot="OPPORTUNITY_OWNER",state=metric.label;var facts=new LinkedHashSet<Subject>();
  if(metric==Metric.LEADS){
   var row=(LeadOverviewReader.Candidate)raw.source();var lead=leads.metadata(c,tenant,row.lead().id());if(lead==null||!lead.selector().equals(row.lead()))throw fail(412,"STALE_SUBJECT");
   var policy=policies.find(lead.sourceAccount());if(policy==null)return null;var root=AuthorizationIdentityReader.databaseBacked().organization(c,tenant,policy.sourceIntakeRootCode());if(root==null)return null;
   var assignment=lead.assignment()==null?null:leadFacts.assignment(c,tenant,lead.assignment());if(lead.assignment()!=null&&(assignment==null||!assignment.leadId().equals(row.lead().id())))return null;
   owner=assignment==null?null:assignment.owner();org=assignment==null?root.id():identity.historicalOrganization(c,tenant,owner);if(assignment!=null)facts.add(assignment.selector());leadId=lead.selector().id();slot="SOURCE_INTAKE_OWNER";state="首次接入";
  }else if(metric==Metric.OVERDUE){
   var task=(CurrentTaskReader.Task)raw.source();var basis=R2TeamTaskResolver.basis(c,tenant,task);if(basis==null)return null;facts.addAll(basis.facts());leadId=basis.lead();org=basis.organization();owner=task.owner();slot=task.type().slot;
   if("WAITING".equals(task.state())){var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id());if(wait==null||wait.taskRevision()!=task.selector().revision())throw fail(412,"STALE_SUBJECT");facts.add(wait.selector());}
   state=io.github.windyzhu3.ontologylaw.query.CurrentWorkCardQuery.label(task.type())+("WAITING".equals(task.state())?" · 等待中，原期限已过":" · 当前逾期");
  }else{
   UUID opportunity;
   if(metric==Metric.OPPORTUNITIES){var row=(OpportunityOverviewReader.Candidate)raw.source();opportunity=row.opportunity().id();facts.add(row.opportunity());state="有效联系形成";}
   else if(metric==Metric.SIGNED){var row=(ContractOverviewReader.Candidate)raw.source();opportunity=row.opportunityId();facts.addAll(row.facts());state="首次签署归档";}
   else{var row=(TransferOverviewReader.Candidate)raw.source();opportunity=row.opportunityId();facts.addAll(row.facts());alternative=row.fromOrganization();state="首次接收案件";}
   var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity);if(opening==null)return null;facts.add(opening.selector());leadId=opening.leadId();
   var assignment=leadFacts.assignment(c,tenant,opening.assignmentId());var contact=leadFacts.contactResult(c,tenant,opening.contactId());var responsibility=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
   if(assignment==null||contact==null||responsibility==null||!assignment.leadId().equals(leadId)||!contact.leadId().equals(leadId)||!contact.assignmentId().equals(assignment.selector().id()))throw fail(412,"STALE_SUBJECT");
   facts.add(assignment.selector());facts.add(contact.selector());facts.add(responsibility.basis());owner=responsibility.appointmentId();
   // A handoff changes responsibility, not the original business authorization organization.
   org=metric==Metric.MATTERS?((TransferOverviewReader.Candidate)raw.source()).toOrganization():identity.historicalOrganization(c,tenant,opening.owner());
  }
  if(org==null)return null;var lead=labels.metadata(c,tenant,leadId);if(lead==null)return null;facts.add(lead.selector());Subject party=null;
  if(lead.partyId()!=null){var active=R1PartyReader.databaseBacked().active(c,tenant,lead.partyId());if(active!=null){party=new Subject("party.party",active.id(),active.revision(),null);facts.add(party);}}
  return new Candidate(raw.id(),raw.at(),lead.selector(),party,owner,org,alternative,slot,state,List.copyOf(facts));
 }
 private boolean authorize(Connection c,Actor actor,Metric metric,Candidate candidate,Disclosures disclosed)throws SQLException{
  String code=metric.authority;var reader=R1AuthorityReader.databaseBacked();var decisions=reader.authorizeAll(c,actor,candidate.facts(),candidate.organization(),candidate.slot(),code);
  if(decisions.size()!=candidate.facts().size()&&candidate.alternative()!=null)decisions=reader.authorizeAll(c,actor,candidate.facts(),candidate.alternative(),candidate.slot(),code);
  if(decisions.size()!=candidate.facts().size()&&metric==Metric.OPPORTUNITIES&&actor.appointmentId().equals(candidate.owner())){code="SALES_OPPORTUNITY_OWNER";decisions=reader.authorizeAll(c,actor,candidate.facts(),candidate.organization(),candidate.slot(),code);}
  if(decisions.size()!=candidate.facts().size())return false;
  for(int i=0;i<decisions.size();i++){var fact=candidate.facts().get(i);var auth=decisions.get(i);
   if(metric==Metric.OPPORTUNITIES)disclosed.opportunities.add(new AuditAppender.OpportunityLedgerDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,auth));
   else if(metric==Metric.SIGNED)disclosed.contracts.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,auth,"HISTORY"));
   else disclosed.management.add(new AuditAppender.ManagementDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,auth));
  }
  return true;
 }
 private static R1ServiceReadRuntime.Failure fail(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
