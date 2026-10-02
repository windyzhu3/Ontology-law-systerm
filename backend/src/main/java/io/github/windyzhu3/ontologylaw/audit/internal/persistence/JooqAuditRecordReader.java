package io.github.windyzhu3.ontologylaw.audit.internal.persistence;

import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.audit.internal.AuditRecordSummary;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;

public final class JooqAuditRecordReader implements AuditRecordReader {
 private static void budget(long deadline)throws SQLException{if(System.nanoTime()>deadline)throw new SQLException("Audit query time budget exceeded","57014");}
 private final AuditReadAuthorization authorization=AuditReadAuthorization.databaseBacked();
 private static final String SELECT="select a.audit_entry_id,a.trusted_at,a.audit_scope_code,a.action_code,a.result_code,a.authorization_scope_organization_unit_id,a.authorization_path_code,a.correlation_id,a.summary_schema_code,a.summary_schema_version,a.change_summary::text as summary_text,a.change_summary_digest,a.subject_type,a.subject_id,a.subject_revision,a.subject_hash,a.correction_target_type,a.correction_target_id,a.correction_target_revision,a.correction_target_hash,a.on_behalf_of_principal_id,pb.display_name as represented_name,os.display_name as record_organization_name,p.display_name as actor_name,r.display_name as role_name,o.display_name as organization_name from audit.audit_entry_classified_v a left join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.actor_principal_id left join identity.appointment ap on ap.tenant_id=a.tenant_id and ap.appointment_id=a.actor_appointment_id left join identity.appointment_role r on r.tenant_id=ap.tenant_id and r.role_code=ap.role_code left join identity.organization_unit o on o.tenant_id=ap.tenant_id and o.organization_unit_id=ap.organization_unit_id left join identity.principal pb on pb.tenant_id=a.tenant_id and pb.principal_id=a.on_behalf_of_principal_id left join identity.organization_unit os on os.tenant_id=a.tenant_id and os.organization_unit_id=a.authorization_scope_organization_unit_id where a.tenant_id=?";
 private org.jooq.DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
 public SafeRecord find(Connection c,Actor actor,UUID id)throws SQLException{var row=db(c).fetchOne(SELECT+" and a.audit_entry_id=?",actor.tenantId(),id);return row==null?null:authorizedProjection(c,actor,row);}
 private UUID scope(Record row){return Set.of("TENANT","SECURITY").contains(row.get("audit_scope_code",String.class))||"SYSTEM".equals(row.get("authorization_path_code",String.class))?null:row.get("authorization_scope_organization_unit_id",UUID.class);}
 private SafeRecord authorizedProjection(Connection c,Actor actor,Record row)throws SQLException{
  if(!AuditRecordSummary.classified(row.get("audit_scope_code",String.class),row.get("authorization_path_code",String.class),row.get("subject_type",String.class)))return null;
  var fact=new Subject("audit.audit_entry",row.get("audit_entry_id",UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(row.get("change_summary_digest",byte[].class)));
  try{authorization.authorize(c,actor,fact,subject(row,"subject_"),scope(row));return project(row);}catch(Failure denied){if(denied.code().equals("NOT_AUTHORIZED"))return null;throw denied;}
 }
 private static Subject subject(Record row,String prefix){String type=row.get(prefix+"type",String.class);UUID id=row.get(prefix+"id",UUID.class);Long revision=row.get(prefix+"revision",Long.class);byte[] hash=row.get(prefix+"hash",byte[].class);return type==null?null:new Subject(type,id,revision,hash==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(hash));}
 private SafeRecord project(Record row)throws SQLException{
  try{
   UUID id=row.get("audit_entry_id",UUID.class);byte[] hash=row.get("change_summary_digest",byte[].class);var source=subject(row,"subject_");if(source==null)throw new IllegalArgumentException();
   String summary=AuditRecordSummary.project(row.get("summary_schema_code",String.class),row.get("summary_schema_version",Integer.class),row.get("summary_text",String.class),hash);
   var correction=subject(row,"correction_target_");Instant at=row.get("trusted_at",OffsetDateTime.class).toInstant();UUID correlation=row.get("correlation_id",UUID.class);
   var values=new LinkedHashMap<String,Object>();values.put("id",id.toString());values.put("trustedAt",at.toString());values.put("actorLabel",AuditRecordSummary.name(row.get("actor_name",String.class)));values.put("appointmentLabel",AuditRecordSummary.name(row.get("organization_name",String.class))+" · "+AuditRecordSummary.name(row.get("role_name",String.class)));values.put("authorizationPathLabel",switch(row.get("authorization_path_code",String.class)){case "DIRECT"->"直接授权";case "DELEGATED"->"委托授权";case "SYSTEM"->"系统办理";case "OBJECT"->"对象授权";default->"未识别授权路径";});values.put("recordOrganizationLabel",scope(row)==null?"事务所根范围":AuditRecordSummary.name(row.get("record_organization_name",String.class)));values.put("onBehalfLabel",row.get("on_behalf_of_principal_id")==null?"本人办理":"代办 · "+AuditRecordSummary.name(row.get("represented_name",String.class)));values.put("objectLabel",AuditRecordSummary.objectLabel(source.type()));values.put("actionLabel",AuditRecordSummary.actionLabel(row.get("action_code",String.class)));values.put("scopeLabel",AuditRecordSummary.scopeLabel(row.get("audit_scope_code",String.class)));values.put("resultLabel",AuditRecordSummary.resultLabel(row.get("result_code",String.class)));values.put("summary",summary);values.put("verificationLabel","摘要完整性已核验；查询时重新核验当前权限。");values.put("hasCorrelation",correlation!=null);values.put("hasCorrection",correction!=null);
   if(((String)values.get("appointmentLabel")).codePointCount(0,((String)values.get("appointmentLabel")).length())>200)values.put("appointmentLabel","任职显示名称过长");
   if(((String)values.get("onBehalfLabel")).codePointCount(0,((String)values.get("onBehalfLabel")).length())>200)values.put("onBehalfLabel","代办 · 未提供显示名称");
   return new SafeRecord(new Subject("audit.audit_entry",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(hash)),source,scope(row),at,correlation,correction,values);
  }catch(IllegalArgumentException unsafe){throw new SQLException("Unsafe audit record projection","22000");}
 }
 private boolean allowed(Connection c,Actor actor,SafeRecord row)throws SQLException{try{authorization.authorize(c,actor,row.fact(),row.source(),row.organization());return true;}catch(Failure denied){if(!denied.code().equals("NOT_AUTHORIZED"))throw denied;return false;}}
 public Page list(Connection c,Actor actor,Query query,Position position)throws SQLException{return scan(c,actor,query,position,null);}
 private Page scan(Connection c,Actor actor,Query q,Position position,UUID correlation)throws SQLException{
  var access=authorization.scopes(c,actor);var result=new ArrayList<SafeRecord>();var args=new ArrayList<Object>();args.add(actor.tenantId());args.add(q.start().toString());args.add(q.end().toString());args.add(position.watermark().toString());
  String sql=SELECT+" and a.trusted_at>=?::timestamptz and a.trusted_at<=?::timestamptz and a.trusted_at<=?::timestamptz";
  // Candidate restriction only; exact audit/source/organization DENY checks still follow.
  String roots=String.join(",",Collections.nCopies(access.scopes().size(),"?::uuid"));
  sql+=" and exists (with recursive visible(organization_unit_id) as (select organization_unit_id from identity.organization_unit where tenant_id=? and organization_unit_id in ("+roots+") union select o.organization_unit_id from identity.organization_unit o join visible v on o.parent_organization_unit_id=v.organization_unit_id where o.tenant_id=?) select 1 from visible where organization_unit_id=coalesce(case when a.audit_scope_code in ('TENANT','SECURITY') or a.authorization_path_code='SYSTEM' then null else a.authorization_scope_organization_unit_id end,?::uuid))";
  args.add(actor.tenantId());args.addAll(access.scopes());args.add(actor.tenantId());args.add(HumanIdentityReader.databaseBacked().rootOrganization(c,actor.tenantId()).id());
  if(q.scope()!=null){sql+=" and a.audit_scope_code=?";args.add(q.scope());}if(q.result()!=null){sql+=" and a.result_code=?";args.add(q.result());}if(correlation!=null){sql+=" and a.correlation_id=?";args.add(correlation);}
  if(q.search()!=null)sql+=" and "+searchCandidates(q.search(),args);
  Position after=position;
  long deadline=System.nanoTime()+5_000_000_000L;
  while(result.size()<=q.limit()){
   budget(deadline);
   var params=new ArrayList<>(args);String chunk=sql;
   if(after.trustedAt()!=null){chunk+=" and (a.trusted_at,a.audit_entry_id)<(?::timestamptz,?::uuid)";params.add(after.trustedAt().toString());params.add(after.id());}
   chunk+=" order by a.trusted_at desc,a.audit_entry_id desc limit 100";var rows=db(c).fetch(chunk,params.toArray());if(rows.isEmpty())break;
   for(var row:rows){after=new Position(row.get("trusted_at",OffsetDateTime.class).toInstant(),row.get("audit_entry_id",UUID.class),position.watermark());
    budget(deadline);
    // Scope and exact DENY decisions precede projection, including summary integrity failures.
    var safe=authorizedProjection(c,actor,row);if(safe==null)continue;if(matches(safe,q)){result.add(safe);if(result.size()>q.limit())break;}
   }
   if(rows.size()<100)break;
  }
  SafeRecord lookahead=result.size()>q.limit()?result.removeLast():null;return new Page(result,lookahead);
 }
 // Conservative filtering on allowed display fields only. Exact authorization and Java safe-label matching still follow.
 private static String searchCandidates(String search,List<Object> args){
  String term=search.toLowerCase(Locale.ROOT),pattern="%"+term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  var clauses=new ArrayList<String>();
  clauses.add("coalesce(p.display_name,'未提供显示名称') ilike ?");args.add(pattern);
  clauses.add("(coalesce(o.display_name,'未提供显示名称')||' · '||coalesce(r.display_name,'未提供显示名称')) ilike ?");args.add(pattern);
  // Unsafe, long and non-basic Unicode names may have a Java fallback/case expansion; keep those candidates rather than omit them.
  for(String column:List.of("p.display_name","o.display_name","r.display_name")){clauses.add("(char_length("+column+")>200 or btrim("+column+")='' or "+column+" ~ ?)");args.add("[^ -~一-龿]");}
  clauses.add("char_length(coalesce(o.display_name,'未提供显示名称')||' · '||coalesce(r.display_name,'未提供显示名称'))>200");
  codes(clauses,args,"a.action_code",AuditRecordSummary.registeredActions().entrySet().stream().filter(e->e.getValue().toLowerCase(Locale.ROOT).contains(term)).map(Map.Entry::getKey).sorted().toList(),false);
  if("执行操作".contains(term))codes(clauses,args,"a.action_code",AuditRecordSummary.registeredActions().keySet().stream().sorted().toList(),true);
  codes(clauses,args,"a.subject_type",AuditRecordSummary.registeredSources().stream().filter(t->AuditRecordSummary.objectLabel(t).contains(term)).sorted().toList(),false);
  codes(clauses,args,"a.audit_scope_code",List.of("TENANT","ORGANIZATION","OBJECT","SECURITY").stream().filter(t->AuditRecordSummary.scopeLabel(t).contains(term)).toList(),false);
  var results=List.of("SUCCEEDED","COMPLETED","NO_CHANGE","REJECTED","FAILED");codes(clauses,args,"a.result_code",results.stream().filter(t->AuditRecordSummary.resultLabel(t).contains(term)).toList(),false);
  if("未识别结果".contains(term))codes(clauses,args,"a.result_code",results,true);
  return "("+String.join(" or ",clauses)+")";
 }
 private static void codes(List<String> clauses,List<Object> args,String column,List<String> codes,boolean exclude){
  if(codes.isEmpty())return;clauses.add(column+(exclude?" not":"")+" in ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")");args.addAll(codes);
 }
 private boolean matches(SafeRecord row,Query q){return (q.scope()==null||AuditRecordSummary.scopeLabel(q.scope()).equals(row.values().get("scopeLabel")))&&(q.result()==null||AuditRecordSummary.resultLabel(q.result()).equals(row.values().get("resultLabel")))&&(q.search()==null||List.of("actorLabel","appointmentLabel","objectLabel","actionLabel","scopeLabel","resultLabel").stream().anyMatch(k->((String)row.values().get(k)).toLowerCase(Locale.ROOT).contains(q.search().toLowerCase(Locale.ROOT))));}
 public Page related(Connection c,Actor actor,SafeRecord seed,Relation relation,Query q,Position position)throws SQLException{
  if(!allowed(c,actor,seed))throw new Failure("NOT_AUTHORIZED");
  if(relation==Relation.CORRELATION)return seed.correlation()==null?new Page(List.of(),null):scan(c,actor,q,position,seed.correlation());
  var visited=new HashSet<UUID>();var queue=new ArrayDeque<SafeRecord>();var found=new ArrayList<SafeRecord>();queue.add(seed);
  long deadline=System.nanoTime()+5_000_000_000L;
  while(!queue.isEmpty()){budget(deadline);var current=queue.removeFirst();if(current.trustedAt().isAfter(position.watermark())||visited.contains(current.fact().id()))continue;if(visited.size()>=1000)throw new SQLException("Audit correction traversal budget exceeded","57014");visited.add(current.fact().id());found.add(current);
   if(current.correction()!=null&&current.correction().type().equals("audit.audit_entry")&&!visited.contains(current.correction().id())){var parent=find(c,actor,current.correction().id());if(parent!=null&&!parent.trustedAt().isAfter(position.watermark())&&parent.fact().equals(current.correction()))queue.add(parent);}
   // The frozen correction-target unique index permits one exact successor.
   var children=db(c).fetch(SELECT+" and a.correction_target_type='audit.audit_entry' and a.correction_target_id=? and a.correction_target_hash=? and a.trusted_at<=?::timestamptz",actor.tenantId(),current.fact().id(),Base64.getUrlDecoder().decode(current.fact().hash()),position.watermark().toString());
   for(var child:children){var safe=authorizedProjection(c,actor,child);if(safe!=null)queue.add(safe);}
  }
  var order=Comparator.comparing(SafeRecord::trustedAt).thenComparing(r->r.fact().id().toString());var visible=found.stream().filter(r->!r.trustedAt().isBefore(q.start())&&!r.trustedAt().isAfter(q.end())&&!r.trustedAt().isAfter(position.watermark())).filter(r->position.trustedAt()==null||r.trustedAt().isBefore(position.trustedAt())||r.trustedAt().equals(position.trustedAt())&&r.fact().id().toString().compareTo(position.id().toString())<0).filter(r->matches(r,q)).sorted(order.reversed()).toList();
  return new Page(visible.stream().limit(q.limit()).toList(),visible.size()>q.limit()?visible.get(q.limit()):null);
 }
}

