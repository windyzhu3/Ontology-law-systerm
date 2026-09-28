package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Internal bounded SERVICE discovery. No writes, customer text, worker registration or public wire contract. */
public final class R2OpportunityDiscoveryService {
    public enum Kind {
        INITIAL("OPPORTUNITY_TASK_ACTIVATE"),DUE("OPPORTUNITY_TASK_RECOVER");
        public final String authority;Kind(String authority){this.authority=authority;}
    }
    public record Candidate(Kind kind,Subject opportunity,Subject task,Subject waitReceipt,Subject progress,Instant due,UUID commandId) {}
    public record Page(List<Candidate> candidates,String nextCursor){public Page{candidates=List.copyOf(candidates);}}
    public record Response(int status,Page page,String errorCode) {}
    private record Position(Instant at,UUID id) {}
    private record Cursor(Instant observed,Position after) {}
    private final byte[] cursorKey;
    public R2OpportunityDiscoveryService(byte[] key){if(key==null||key.length<32)throw new IllegalArgumentException("Cursor key required");cursorKey=key.clone();}
    public Response list(Connection c,Actor actor,Kind kind,int limit,String encoded){
        try {
            if(kind==null||limit<1||limit>100)throw invalid();
            return R1ServiceReadRuntime.read(c,actor,(connection,now)->{
                var cursor=encoded==null?new Cursor(now,null):decode(actor,kind,encoded,now);
                var scope=R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(connection,actor,kind.authority,now);
                if(!scope.authorized())throw forbidden();
                var responsibility=OpportunityMaintenanceTasks.databaseBacked();var events=EventOpportunityReader.databaseBacked();
                List<Position> rows;
                if(kind==Kind.INITIAL){
                    var after=cursor.after()==null?null:new OpportunityActivationCandidates.Position(cursor.after().at(),cursor.after().id());
                    rows=OpportunityActivationCandidates.databaseBacked().scan(connection,actor.tenantId(),scope.ownerAppointments(),cursor.observed(),after,limit).stream().map(r->new Position(r.at(),r.id())).toList();
                }else{
                    var after=cursor.after()==null?null:new OpportunityMaintenanceTasks.Position(cursor.after().at(),cursor.after().id());
                    rows=responsibility.due(connection,actor.tenantId(),scope.ownerAppointments(),cursor.observed(),after,limit).stream().map(r->new Position(r.at(),r.id())).toList();
                }
                var candidates=new ArrayList<Candidate>();
                for(var row:rows){
                    if(kind==Kind.INITIAL){
                        var opportunity=events.byId(connection,actor.tenantId(),row.id());
                        if(opportunity==null||!R2SalesStageGuards.initialFollowupAllowed(connection,actor.tenantId(),row.id())||responsibility.initialExists(connection,actor.tenantId(),row.id())||!visible(connection,actor,kind,opportunity,List.of()))continue;
                        candidates.add(candidate(kind,actor.tenantId(),opportunity.selector(),null,null,null,null));
                    }else{
                        var task=TaskFactory.databaseBacked().read(connection,actor.tenantId(),row.id());
                        var wait=EventResponsibilityReader.databaseBacked().latestWait(connection,actor.tenantId(),row.id());

                        if(task==null||!Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.RECORD_QUOTE_REPLY).contains(task.type())||!"WAITING".equals(task.state())||task.selector().revision()>=9007199254740991L
                                ||wait==null||wait.taskRevision()!=task.selector().revision()||wait.version()!=1||!(task.type()==TaskFactory.Type.RECORD_QUOTE_REPLY?Set.of("R2_QUOTE_FOLLOWUP_V1","R2_QUOTE_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile()):Set.of("R2_OPPORTUNITY_FOLLOWUP_V1","R2_OPPORTUNITY_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile()))||!row.at().equals(wait.resumeDue()))continue;
                        Subject origin;
                        try{origin=FollowupAttemptRecovery.source(connection,actor.tenantId(),row.id());}catch(CommandHandler.Rejected stale){continue;}
                        var opportunity=events.byId(connection,actor.tenantId(),task.subject().id());
                        if(opportunity==null||!opportunity.selector().equals(task.subject())||!OpportunityResponsibilityReader.databaseBacked().current(connection,actor.tenantId(),opportunity.selector()).appointmentId().equals(task.owner())
                                ||!visible(connection,actor,kind,opportunity,List.of(task.selector(),wait.selector(),origin)))continue;
                        candidates.add(candidate(kind,actor.tenantId(),opportunity.selector(),task.selector(),wait.selector(),origin,wait.resumeDue()));
                    }
                }
                // Advance by scanned rows, including denied/already-created rows, so they cannot starve later work.
                String next=rows.size()==limit?encode(actor,kind,new Cursor(cursor.observed(),rows.getLast())):null;
                if(!R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(connection,actor,kind.authority,R1ServiceReadRuntime.databaseTime(connection)).authorized())throw forbidden();
                return new Response(200,new Page(candidates,next),null);
            });
        }catch(R1ServiceReadRuntime.Failure failure){return new Response(failure.status(),null,failure.code());}
    }
    private static boolean visible(Connection c,Actor service,Kind kind,EventOpportunityReader.Opportunity opportunity,List<Subject> extra)throws SQLException {
        if(!OpportunityActivationCandidates.databaseBacked().currentOpen(c,service.tenantId(),opportunity.selector()))return false;
        var identities=AuthorizationIdentityReader.databaseBacked();var now=R1ServiceReadRuntime.databaseTime(c);
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,service.tenantId(),opportunity.selector());
        var owner=identities.owner(c,service.tenantId(),effective.appointmentId(),now);var registration=identities.registration(c,service.tenantId(),effective.appointmentId());
        if(owner==null||!owner.active()||registration==null||registration.principalKind()!=PrincipalKind.HUMAN)return false;
        var lead=R1EventReaders.databaseBacked().lead(c,service.tenantId(),opportunity.leadId());if(lead==null)return false;
        var subjects=new ArrayList<Subject>(List.of(opportunity.selector(),lead,effective.basis()));subjects.addAll(extra);
        var human=new Actor(service.tenantId(),owner.principalId(),owner.appointmentId(),null,null,PrincipalKind.HUMAN);
        var organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,service.tenantId(),opportunity.owner());if(organization==null)return false;
        var reader=R1AuthorityReader.databaseBacked();var auth=AuthorizationService.databaseBacked();
        var servicePath=reader.select(c,service,opportunity.selector(),organization,"R2_OPPORTUNITY_SYSTEM",kind.authority);
        var ownerPath=reader.select(c,human,opportunity.selector(),organization,"OPPORTUNITY_OWNER",quoteResponsibility(c,service.tenantId(),extra)?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER");
        if(servicePath==null||ownerPath==null)return false;
        for(var subject:subjects){
            if(!auth.evaluate(c,new Request(service,subject,organization,servicePath.requirement()),true).allowed()
                    ||!auth.evaluate(c,new Request(human,subject,organization,ownerPath.requirement()),true).allowed())return false;
        }
        // Discovery is only a hint. The mutating command must recheck closed state, lineage and exact selectors.
        return true;
    }
    private static boolean quoteResponsibility(Connection c,UUID tenant,List<Subject> facts)throws SQLException {
        for(var fact:facts){if("opportunity.quote_response".equals(fact.type()))return true;if(FollowupAttemptService.FACT.equals(fact.type())){var m=FollowupAttemptService.readMetadata(c,tenant,fact);if(m!=null&&"QUOTE".equals(m.context()))return true;}}return false;
    }
    private static Candidate candidate(Kind kind,UUID tenant,Subject opportunity,Subject task,Subject wait,Subject progress,Instant due){
        var body=new TreeMap<String,Object>();body.put("profile","R2_OPPORTUNITY_MAINTENANCE_KEY_V1");body.put("tenant",tenant.toString());body.put("kind",kind.name());
        body.put("opportunity",selector(opportunity));body.put("task",selector(task));body.put("wait",selector(wait));body.put("progress",selector(progress));body.put("due",due==null?null:due.toString());
        try{var sha=MessageDigest.getInstance("SHA-1");var namespace=UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");sha.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            var hash=sha.digest(CanonicalJson.encode(body).getBytes(StandardCharsets.UTF_8));hash[6]=(byte)((hash[6]&15)|0x50);hash[8]=(byte)((hash[8]&63)|0x80);var bytes=ByteBuffer.wrap(hash);
            return new Candidate(kind,opportunity,task,wait,progress,due,new UUID(bytes.getLong(),bytes.getLong()));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static Object selector(Subject value){if(value==null)return null;var result=new TreeMap<String,Object>();result.put("type",value.type());result.put("id",value.id().toString());result.put("revision",value.revision());result.put("hash",value.hash());return result;}
    private String encode(Actor actor,Kind kind,Cursor cursor){String raw="R2_OPPORTUNITY_CURSOR_V1\n"+actor.tenantId()+"\n"+actor.principalId()+"\n"+actor.appointmentId()+"\n"+kind+"\n"+cursor.observed()+"\n"+cursor.after().at()+"\n"+cursor.after().id();var bytes=raw.getBytes(StandardCharsets.UTF_8);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(bytes));}
    private Cursor decode(Actor actor,Kind kind,String encoded,Instant now){
        try{if(encoded.length()>2048)throw invalid();var parts=encoded.split("\\.",-1);if(parts.length!=2)throw invalid();var bytes=Base64.getUrlDecoder().decode(parts[0]);var signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(parts[0])||!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1])||!MessageDigest.isEqual(mac(bytes),signature))throw invalid();
            var fields=new String(bytes,StandardCharsets.UTF_8).split("\n",-1);
            if(fields.length!=8||!fields[0].equals("R2_OPPORTUNITY_CURSOR_V1")||!fields[1].equals(actor.tenantId().toString())||!fields[2].equals(actor.principalId().toString())||!fields[3].equals(actor.appointmentId().toString())||!fields[4].equals(kind.name()))throw invalid();
            var observed=Instant.parse(fields[5]);var at=Instant.parse(fields[6]);if(observed.isAfter(now)||!now.isBefore(observed.plusSeconds(300))||at.isAfter(observed))throw invalid();
            return new Cursor(observed,new Position(at,UUID.fromString(fields[7])));
        }catch(IllegalArgumentException|DateTimeException failure){throw invalid();}
    }
    private byte[] mac(byte[] input){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));return mac.doFinal(input);}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    private static R1ServiceReadRuntime.Failure invalid(){return new R1ServiceReadRuntime.Failure(400,"VALIDATION_FAILED");}
    private static R1ServiceReadRuntime.Failure forbidden(){return new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}

