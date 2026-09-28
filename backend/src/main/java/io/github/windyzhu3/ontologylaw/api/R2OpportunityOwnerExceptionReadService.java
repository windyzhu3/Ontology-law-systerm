package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independently authorized supervisor reads and a separately constructed operations allowlist. */
public final class R2OpportunityOwnerExceptionReadService {
    private static final String READ="OPPORTUNITY_OWNER_EXCEPTION_READ",RESOLVE="OPPORTUNITY_OWNER_EXCEPTION_RESOLVE",OPS="OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ";
    private final byte[] key;
    private final CurrentLeadReader leads;
    private final AuditAppender audit;
    private final OpportunityOwnerExceptionReader ledger=OpportunityOwnerExceptionReader.databaseBacked();
    private final OpportunityOwnerExceptionAuthorityReader authority=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
    private record Cursor(Instant observed,Instant at,UUID id){}
    private record Context(Snapshot snapshot,UUID organization,List<Subject> facts,Subject lead){}
    public R2OpportunityOwnerExceptionReadService(byte[] key,LeadProtection protection,AuditAppender audit){if(key==null||key.length<32)throw new IllegalArgumentException("Cursor key required");this.key=key.clone();this.leads=CurrentLeadReader.databaseBacked(protection);this.audit=Objects.requireNonNull(audit);}
    public Map<String,Object> read(Connection connection,Actor actor,String operation,UUID id,Long revision,int limit,String encoded)throws SQLException {
        if(limit<1||limit>100)throw failure(400,"VALIDATION_FAILED");
        return OpportunityOwnerExceptionReadRuntime.read(connection,actor,audit,(c,now)->{
            var disclosures=new ArrayList<AuditAppender.OwnerExceptionDisclosureEntry>();
            String code="operations".equals(operation)?OPS:READ;
            if(!authority.hasAuthority(c,actor,code,now))throw failure(403,"NOT_AUTHORIZED");
            Map<String,Object> result;
            if("detail".equals(operation)||"candidates".equals(operation)){
                var snapshot=ledger.current(c,actor.tenantId(),id);
                var context=snapshot==null?null:context(c,actor,snapshot);
                if(context==null||!allowed(c,actor,context,READ))throw failure(403,"NOT_AUTHORIZED");
                if("candidates".equals(operation)){
                    if(revision==null||revision<0||revision>9007199254740991L)throw failure(400,"VALIDATION_FAILED");
                    if(snapshot.selector().revision().longValue()!=revision)throw failure(409,"STALE_SUBJECT");
                    if(!allowed(c,actor,context,RESOLVE))throw failure(403,"NOT_AUTHORIZED");
                    var detail=detail(c,actor,context,disclosures);
                    var actions=(List<?>)detail.get("allowedActions");
                    var items=new ArrayList<Map<String,Object>>();
                    String next=null;
                    if(actions.contains("TRANSFER")){
                        String kind="candidates:"+id+":"+revision+":"+detail.get("etag");
                        var cursor=encoded==null?new Cursor(now,null,null):decode(actor,kind,encoded,now);
                        var rows=authority.receiverAppointments(c,actor.tenantId(),cursor.id(),limit);
                        for(var receiver:rows){
                            if(receiver.equals(snapshot.responsibility().appointmentId()))continue;
                            var assessment=authority.receiver(c,actor.tenantId(),receiver,context.organization(),context.facts(),R1ServiceReadRuntime.databaseTime(c),R2OpportunityOwnerExceptionAssembly.taskState(c,actor.tenantId(),snapshot.opportunity(),snapshot.responsibility()).authorityCode());
                            if(!assessment.active()||!assessment.authorized()||assessment.denied())continue;
                            var label=owner(c,actor,context,receiver,disclosures);
                            if(label==null)continue;
                            items.add(Map.of("appointmentId",receiver.toString(),"displayName",label.principal().displayName(),"organizationLabel",label.organization().displayName()));
                        }
                        if(rows.size()==limit)next=encode(actor,kind,new Cursor(cursor.observed(),null,rows.getLast()));
                    }else if(encoded!=null)throw failure(409,"STALE_SUBJECT");
                    result=new LinkedHashMap<>();result.put("items",items);result.put("exception",selector(snapshot.selector()));result.put("etag",detail.get("etag"));if(next!=null)result.put("nextCursor",next);
                }else result=detail(c,actor,context,disclosures);
            }else{
                if(!Set.of("list","operations").contains(operation))throw failure(400,"VALIDATION_FAILED");
                var cursor=encoded==null?new Cursor(now,null,null):decode(actor,operation,encoded,now);
                var rows=ledger.scan(c,actor.tenantId(),cursor.observed(),cursor.id()==null?null:new OpportunityOwnerExceptionReader.Position(cursor.at(),cursor.id()),limit);
                var items=new ArrayList<Map<String,Object>>();
                for(var row:rows){
                    var s=ledger.current(c,actor.tenantId(),row.id());if(s==null)continue;
                    if("operations".equals(operation)){
                        UUID organization=authority.historicalOrganization(c,actor.tenantId(),s.frozenOwner());
                        // Summary authority is checked on the exact exception projection only; never infer full fact rights.
                        if(organization==null||!authority.permitted(c,actor,organization,List.of(s.selector()),OPS))continue;
                        String label=authority.restrictedOrganizationLabel(c,actor.tenantId(),organization);if(label!=null)items.add(operations(s,label));
                    }else{
                        var context=context(c,actor,s);
                        if(context!=null&&allowed(c,actor,context,READ))items.add(detail(c,actor,context,disclosures));
                    }
                }
                result=new LinkedHashMap<>();result.put("items",items);
                if(rows.size()==limit){var last=rows.getLast();result.put("nextCursor",encode(actor,operation,new Cursor(cursor.observed(),last.at(),last.id())));}
            }
            if(!authority.hasAuthority(c,actor,code,R1ServiceReadRuntime.databaseTime(c)))throw failure(403,"NOT_AUTHORIZED");
            return new OpportunityOwnerExceptionReadRuntime.Prepared<>(result,List.copyOf(disclosures));
        });
    }
    private Context context(Connection c,Actor actor,Snapshot s)throws SQLException {
        UUID organization=authority.historicalOrganization(c,actor.tenantId(),s.frozenOwner());if(organization==null)return null;
        var opportunity=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),s.opportunity().id());if(opportunity==null)return null;
        var facts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,actor.tenantId(),opportunity.selector()));
        facts.add(s.selector());facts.add(s.opportunity());facts.add(s.responsibility().basis());if(s.task()!=null)facts.add(s.task());if(s.waitReceipt()!=null)facts.add(s.waitReceipt());if(s.resolution()!=null)facts.add(s.resolution());
        if(s.lastDispositionId()!=null)facts.add(new Subject("opportunity.owner_exception_disposition",s.lastDispositionId(),0L,null));
        Subject lead=leads.selector(c,actor.tenantId(),opportunity.leadId());if(lead==null)return null;facts.add(lead);
        return new Context(s,organization,List.copyOf(facts),lead);
    }
    private boolean allowed(Connection c,Actor actor,Context context,String code)throws SQLException{return authority.permitted(c,actor,context.organization(),context.facts(),code);}
    private Map<String,Object> detail(Connection c,Actor actor,Context context,List<AuditAppender.OwnerExceptionDisclosureEntry> disclosures)throws SQLException {
        var s=context.snapshot();
        var result=new LinkedHashMap<String,Object>();result.put("exception",selector(s.selector()));result.put("opportunity",selector(s.opportunity()));result.put("basis",selector(s.responsibility().basis()));
        if(s.task()!=null)result.put("task",selector(s.task()));if(s.waitReceipt()!=null)result.put("wait",selector(s.waitReceipt()));
        result.put("currentOwnerAppointmentId",s.responsibility().appointmentId().toString());result.put("frozenOwnerAppointmentId",s.frozenOwner().toString());result.put("state",s.state().name());result.put("reasonCodes",s.reasons().stream().sorted().map(Enum::name).toList());result.put("firstObservedAt",s.firstObservedAt().toString());result.put("lastObservedAt",s.lastObservedAt().toString());if(s.reviewDueAt()!=null)result.put("reviewDueAt",s.reviewDueAt().toString());
        var lead=leads.read(c,actor.tenantId(),context.lead().id());if(lead==null||!lead.selector().equals(context.lead()))throw failure(409,"STALE_SUBJECT");
        result.put("opportunityLabel",lead.customerName()!=null?lead.customerName():lead.capturedName()!=null?lead.capturedName():"客户委托");disclose(c,actor,context,context.lead(),disclosures);
        var current=owner(c,actor,context,s.responsibility().appointmentId(),disclosures);var frozen=owner(c,actor,context,s.frozenOwner(),disclosures);
        if(current!=null){result.put("currentOwnerLabel",current.principal().displayName());result.put("organizationLabel",current.organization().displayName());}if(frozen!=null)result.put("frozenOwnerLabel",frozen.principal().displayName());
        boolean exact=true;
        if(s.task()!=null){var task=EventResponsibilityReader.databaseBacked().task(c,actor.tenantId(),s.task().id());exact=task!=null&&task.selector().equals(s.task());if(exact){result.put("taskState",task.state());result.put("taskDueAt",task.slaDue().toString());}}
        if(s.waitReceipt()!=null){var wait=EventResponsibilityReader.databaseBacked().latestWait(c,actor.tenantId(),s.task().id());exact&=wait!=null&&wait.selector().equals(s.waitReceipt());if(exact)result.put("resumeDueAt",wait.resumeDue().toString());}
        var op=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),s.opportunity().id());exact&=op!=null&&op.selector().equals(s.opportunity());
        if(exact){var currentResponsibility=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),s.opportunity());exact=currentResponsibility.equals(s.responsibility());if(exact){var currentTask=R2OpportunityOwnerExceptionAssembly.taskState(c,actor.tenantId(),s.opportunity(),currentResponsibility);exact=Objects.equals(currentTask.task(),s.task())&&Objects.equals(currentTask.waitReceipt(),s.waitReceipt());}}
        exact&=OpportunityActivationCandidates.databaseBacked().currentOpen(c,actor.tenantId(),s.opportunity());
        var actions=new ArrayList<String>();
        if(exact&&(s.state()==State.ACTIVE||s.state()==State.COORDINATING)&&allowed(c,actor,context,RESOLVE)){
            actions.add("COORDINATE");if(!s.reasons().contains(Reason.SOURCE_INCONSISTENT))actions.add("TRANSFER");
        }
        result.put("allowedActions",actions);
        // Re-read all current policies after protected field materialization; no partial response can escape.
        if(!allowed(c,actor,context,READ))throw failure(403,"NOT_AUTHORIZED");
        result.put("etag",tag(actor,result));return result;
    }
    private WorkcardOwnerReader.Owner owner(Connection c,Actor actor,Context context,UUID appointment,List<AuditAppender.OwnerExceptionDisclosureEntry> disclosures)throws SQLException {
        var owner=WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),appointment);if(owner==null)return null;
        var facts=List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());
        if(!authority.permitted(c,actor,context.organization(),facts,READ))return null;
        for(var fact:facts)disclose(c,actor,context,fact,disclosures);return owner;
    }
    private void disclose(Connection c,Actor actor,Context context,Subject source,List<AuditAppender.OwnerExceptionDisclosureEntry> disclosures)throws SQLException {
        var request=R1AuthorityReader.databaseBacked().select(c,actor,context.lead(),context.organization(),"OPPORTUNITY_OWNER",READ);
        if(request==null)throw failure(403,"NOT_AUTHORIZED");var evidence=AuthorizationService.databaseBacked().evaluate(c,request,true);if(!evidence.allowed())throw failure(403,"NOT_AUTHORIZED");
        disclosures.add(new AuditAppender.OwnerExceptionDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),source,context.lead(),evidence));
    }
    static Map<String,Object> operations(Snapshot s,String organization){
        return Map.of("exceptionId",s.selector().id().toString(),"reasonCodes",s.reasons().stream().sorted().map(Enum::name).toList(),"state",s.state().name(),"firstObservedAt",s.firstObservedAt().toString(),"lastObservedAt",s.lastObservedAt().toString(),"organizationLabel",organization,"repairGuidance","请通过既有受控流程修复主管任职、组织范围或授权，然后重新检查异常。");
    }
    static Map<String,Object> selector(Subject s){var value=new LinkedHashMap<String,Object>();value.put("type",s.type());value.put("id",s.id().toString());if(s.revision()!=null)value.put("revision",s.revision());if(s.hash()!=null)value.put("hash",s.hash());return value;}
    private String tag(Actor actor,Map<String,Object> body){return "\"oe."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac((actor.tenantId()+":"+actor.principalId()+":"+actor.appointmentId()+":"+CanonicalJson.encode(body)).getBytes(StandardCharsets.UTF_8)))+"\"";}
    private String encode(Actor actor,String kind,Cursor cursor){String raw="R2_OWNER_EXCEPTION_READ_CURSOR_V1\n"+actor.tenantId()+"\n"+actor.principalId()+"\n"+actor.appointmentId()+"\n"+kind+"\n"+cursor.observed()+"\n"+(cursor.at()==null?"":cursor.at())+"\n"+cursor.id();var bytes=raw.getBytes(StandardCharsets.UTF_8);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(bytes));}
    private Cursor decode(Actor actor,String kind,String encoded,Instant now){try{
        if(encoded.length()>2048)throw failure(400,"VALIDATION_FAILED");var parts=encoded.split("\\.",-1);if(parts.length!=2)throw failure(400,"VALIDATION_FAILED");var bytes=Base64.getUrlDecoder().decode(parts[0]);var signature=Base64.getUrlDecoder().decode(parts[1]);if(!MessageDigest.isEqual(mac(bytes),signature)||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(parts[0])||!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1]))throw failure(400,"VALIDATION_FAILED");
        var fields=new String(bytes,StandardCharsets.UTF_8).split("\n",-1);if(fields.length!=8||!fields[0].equals("R2_OWNER_EXCEPTION_READ_CURSOR_V1")||!fields[1].equals(actor.tenantId().toString())||!fields[2].equals(actor.principalId().toString())||!fields[3].equals(actor.appointmentId().toString())||!fields[4].equals(kind))throw failure(400,"VALIDATION_FAILED");var observed=Instant.parse(fields[5]);if(observed.isAfter(now)||!now.isBefore(observed.plusSeconds(300)))throw failure(400,"VALIDATION_FAILED");var at=fields[6].isEmpty()?null:Instant.parse(fields[6]);if(at!=null&&at.isAfter(observed))throw failure(400,"VALIDATION_FAILED");return new Cursor(observed,at,UUID.fromString(fields[7]));
    }catch(IllegalArgumentException|DateTimeException invalid){throw failure(400,"VALIDATION_FAILED");}}
    private byte[] mac(byte[] input){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(input);}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    private static R1ServiceReadRuntime.Failure failure(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
