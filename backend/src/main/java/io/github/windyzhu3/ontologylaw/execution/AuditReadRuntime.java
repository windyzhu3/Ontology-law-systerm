package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.audit.AuditRecordReader.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Synchronous audited disclosures. No BODY can leave until commit is confirmed. */
public final class AuditReadRuntime {
 private final AuditAppender audit;private final AuditRecordProtection protection;
 private final AuditRecordReader reader=AuditRecordReader.databaseBacked();private final AuditReadAuthorization authorization=AuditReadAuthorization.databaseBacked();
 public AuditReadRuntime(AuditAppender audit,AuditRecordProtection protection){this.audit=audit;this.protection=protection;}
 public Map<String,Object> read(Connection c,Actor actor,Query query)throws SQLException{return disclose(c,actor,query,null,null);}
 public Map<String,Object> detail(Connection c,Actor actor,UUID id)throws SQLException{return disclose(c,actor,new Query(null,null,null,null,null,20,null),id,null);}
 public Map<String,Object> related(Connection c,Actor actor,UUID id,Relation relation,Query query)throws SQLException{if(relation==null)throw new Failure("VALIDATION_FAILED");return disclose(c,actor,query,id,relation);}
 private Map<String,Object> disclose(Connection connection,Actor actor,Query input,UUID id,Relation relation)throws SQLException{
  return inTransaction(connection,Capability.QUERY,c->{
   R1BusinessFence.databaseBacked().shared(c,actor.tenantId());
   try(var lock=AuthorizationService.databaseBacked().lockedReadScope(c,actor.tenantId())){
    var access=authorization.scopes(c,actor);Instant now=SensitiveReadClock.now(c);
    if(input.limit()<1||input.limit()>50||input.scope()!=null&&!Set.of("TENANT","ORGANIZATION","OBJECT","SECURITY").contains(input.scope())||input.result()!=null&&!Set.of("SUCCEEDED","NO_CHANGE","REJECTED","FAILED").contains(input.result())||input.search()!=null&&(input.search().codePointCount(0,input.search().length())>100||input.search().codePoints().anyMatch(ch->Character.isISOControl(ch)||Character.getType(ch)==Character.FORMAT)))throw new Failure("VALIDATION_FAILED");
    SafeRecord seed=id==null?null:reader.find(c,actor,id);if(id!=null&&seed==null)throw new Failure("NOT_AUTHORIZED");if(seed!=null)authorization.authorize(c,actor,seed.fact(),seed.source(),seed.organization());
    var bindings=new TreeMap<String,Object>();bindings.put("profile","ADM07_QUERY_V1");bindings.put("actor",IdentityResourceProtection.actor(actor));bindings.put("authorization",access.binding());bindings.put("start",input.start()==null?null:input.start().toString());bindings.put("end",input.end()==null?null:input.end().toString());bindings.put("scope",input.scope());bindings.put("result",input.result());bindings.put("search",input.search());bindings.put("limit",input.limit());bindings.put("seed",seed==null?null:seed.fact().toString());bindings.put("relation",relation==null?null:relation.name());
    String binding=Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(bindings)));var position=protection.position(input.cursor(),binding);if(position==null)position=new Position(null,null,now);
    Instant end=input.end()==null?position.watermark():input.end(),start=input.start()==null?end.minus(Duration.ofDays(7)):input.start();if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(31))>0||end.isAfter(now)||position.watermark().isAfter(now))throw new Failure("VALIDATION_FAILED");
    var query=new Query(start,end,input.scope(),input.result(),input.search()==null||input.search().isBlank()?null:input.search(),input.limit(),input.cursor());
    try(var statement=c.createStatement()){statement.execute("SET LOCAL statement_timeout='5s'");}
    Page page=id==null?reader.list(c,actor,query,position):relation==null?new Page(List.of(seed),false):reader.related(c,actor,seed,relation,query,position);
    var selected=new ArrayList<>(page.items());if(seed!=null&&!selected.contains(seed))selected.add(seed);
    AuthorizationSnapshot snapshot=access.authorization();
    if(!access.binding().equals(authorization.scopes(c,actor).binding()))throw new Failure("NOT_AUTHORIZED");
    for(var row:selected){var current=reader.find(c,actor,row.fact().id());if(current==null||!current.fact().equals(row.fact())||!current.source().equals(row.source())||!Objects.equals(current.organization(),row.organization()))throw new Failure("NOT_AUTHORIZED");snapshot=authorization.authorize(c,actor,current.fact(),current.source(),current.organization());}
    var body=new LinkedHashMap<String,Object>();if(id!=null&&relation==null)body.putAll(seed.values());else{body.put("items",page.items().stream().map(SafeRecord::values).toList());if(page.hasMore()){var last=page.items().getLast();body.put("nextCursor",protection.cursor(binding,new Position(last.trustedAt(),last.fact().id(),position.watermark())));}}
    String operation=id==null?"LIST_AUDIT_RECORDS":relation==null?"GET_AUDIT_RECORD":"LIST_RELATED_AUDIT_RECORDS";
    setLocalRole(c,Capability.AUDIT);audit.append(c,new AuditAppender.AuditRecordDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),operation,snapshot,page.items().stream().map(SafeRecord::fact).toList(),query.scope()==null?"ALL":query.scope(),query.result()==null?"ALL":query.result()));
    // Audit work may cross an expiry boundary. Recheck immediately before commit too.
    setLocalRole(c,Capability.QUERY);if(!access.binding().equals(authorization.scopes(c,actor).binding()))throw new Failure("NOT_AUTHORIZED");for(var row:selected)authorization.authorize(c,actor,row.fact(),row.source(),row.organization());
    return Collections.unmodifiableMap(body);
   }
  });
 }
}
