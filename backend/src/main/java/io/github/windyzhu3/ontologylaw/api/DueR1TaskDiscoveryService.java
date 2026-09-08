package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import java.sql.Connection;
import java.util.*;
import java.time.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.responsibility.*;

public final class DueR1TaskDiscoveryService {
    public record Response(int status,DueR1TaskPageV1 page,String errorCode) {}
    private final byte[] cursorKey;
    public DueR1TaskDiscoveryService(byte[] cursorKey) {if(cursorKey==null||cursorKey.length<32)throw new IllegalArgumentException("Cursor key required");this.cursorKey=cursorKey.clone();}
    public Response list(Connection c,Actor actor,RecoveryTypeV1 type,Integer requested,String cursor){
        try {
            int limit=requested==null?50:requested;
            if(type==null||limit<1||limit>100)throw badCursor();
            return R1ServiceReadRuntime.read(c,actor,(connection,now)->{
                var position=cursor==null?new Cursor(now,null):decode(actor,type,cursor,now);
                boolean contact=type==RecoveryTypeV1.CONTACT_TASK;String code=contact?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER";
                var scope=R1ServiceAuthorityReader.databaseBacked().dueScope(connection,actor,code,now);
                if(!scope.authorized())throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
                var rows=DueR1TaskReader.databaseBacked().scan(connection,actor.tenantId(),contact?TaskFactory.Type.CONTACT_LEAD:TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,scope.ownerAppointments(),position.observed(),position.after(),limit);
                var candidates=new ArrayList<DueR1TaskCandidateV1>();var facts=R1EventReaders.databaseBacked();var identity=AuthorizationIdentityReader.databaseBacked();
                for(var row:rows) {
                    try {
                        var task=CurrentTaskReader.databaseBacked().read(connection,actor.tenantId(),row.taskId());
                        var wait=facts.latestWait(connection,actor.tenantId(),row.taskId());
                        if(task==null||wait==null||!"WAITING".equals(task.state())||task.selector().revision()>=9007199254740991L||wait.taskRevision()!=task.selector().revision()
                            ||wait.version()!=1||!wait.profile().equals(contact?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1")||!row.dueAt().equals(wait.resumeDue())){warn();continue;}
                        var lead=facts.lead(connection,actor.tenantId(),task.lead().id());var owner=identity.owner(connection,actor.tenantId(),task.owner(),now);
                        if(lead==null||owner==null||!owner.active()){warn();continue;}
                        var authorization=R1AuthorityReader.databaseBacked().select(connection,actor,task.selector(),owner.organizationId(),"SYSTEM_RECOVERY",code);
                        if(authorization==null||!AuthorizationService.databaseBacked().evaluate(connection,new Request(actor,lead,owner.organizationId(),authorization.requirement()),true).allowed()){warn();continue;}
                        candidates.add(new DueR1TaskCandidateV1(type,row.taskId(),task.selector().revision(),wait.selector().id(),wait.selector().hash(),wait.resumeDue().atOffset(ZoneOffset.UTC),recoveryKey(actor.tenantId(),type,row.taskId(),wait.selector().id(),wait.selector().hash())));
                    } catch(IllegalArgumentException invalid){warn();}
                }
                var page=new DueR1TaskPageV1(candidates);
                if(rows.size()==limit)page.setNextCursor(encode(actor,type,new Cursor(position.observed(),rows.getLast())));
                return new Response(200,page,null);
            });
        } catch(R1ServiceReadRuntime.Failure failure){return new Response(failure.status(),null,failure.code());}
    }
    private static void warn(){org.slf4j.LoggerFactory.getLogger(DueR1TaskDiscoveryService.class).warn("R1_DUE_CANDIDATE_INVALID");}
    private record Cursor(Instant observed,DueR1TaskReader.Position after) {}
    private static R1ServiceReadRuntime.Failure badCursor(){return new R1ServiceReadRuntime.Failure(400,"VALIDATION_FAILED");}
    private String encode(Actor actor,RecoveryTypeV1 type,Cursor cursor){
        String value="R1_DUE_CURSOR_V1\n"+actor.tenantId()+"\n"+actor.principalId()+"\n"+actor.appointmentId()+"\n"+type+"\n"+cursor.observed()+"\n"+cursor.after().dueAt()+"\n"+cursor.after().taskId();
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(bytes));
    }
    private Cursor decode(Actor actor,RecoveryTypeV1 type,String encoded,Instant now){
        try {
            if(encoded.length()>2048)throw badCursor();var parts=encoded.split("\\.",-1);if(parts.length!=2)throw badCursor();
            byte[] value=Base64.getUrlDecoder().decode(parts[0]),signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1])||!MessageDigest.isEqual(mac(value),signature))throw badCursor();
            var fields=new String(value,StandardCharsets.UTF_8).split("\n",-1);
            if(fields.length!=8||!fields[0].equals("R1_DUE_CURSOR_V1")||!fields[1].equals(actor.tenantId().toString())||!fields[2].equals(actor.principalId().toString())||!fields[3].equals(actor.appointmentId().toString())||!fields[4].equals(type.toString()))throw badCursor();
            var observed=Instant.parse(fields[5]);var due=Instant.parse(fields[6]);
            if(observed.isAfter(now)||!now.isBefore(observed.plusSeconds(300))||due.isAfter(observed))throw badCursor();
            return new Cursor(observed,new DueR1TaskReader.Position(due,UUID.fromString(fields[7])));
        } catch(IllegalArgumentException|DateTimeException failure){throw badCursor();}
    }
    private byte[] mac(byte[] input){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));return mac.doFinal(input);}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    public static UUID recoveryKey(UUID tenant,RecoveryTypeV1 type,UUID task,UUID wait,String digest){
        Objects.requireNonNull(type);var namespace=UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        String command=type==RecoveryTypeV1.CONTACT_TASK?"REOPEN_DUE_CONTACT_TASKS":"REOPEN_DUE_ROUTING_REVIEW_TASKS";
        String name="ontology-law:R1_DUE_RECOVERY_COMMAND_V1:"+tenant+":"+command+":"+task+":"+wait+":"+digest;
        try {var sha=MessageDigest.getInstance("SHA-1");sha.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            byte[] hash=sha.digest(name.getBytes(StandardCharsets.UTF_8));hash[6]=(byte)((hash[6]&15)|0x50);hash[8]=(byte)((hash[8]&63)|0x80);var buffer=ByteBuffer.wrap(hash);return new UUID(buffer.getLong(),buffer.getLong());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
