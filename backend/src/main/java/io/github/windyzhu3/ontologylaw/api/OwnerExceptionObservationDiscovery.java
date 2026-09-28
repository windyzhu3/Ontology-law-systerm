package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Read-only bounded exception discovery; observations remain formal commands. */
public final class OwnerExceptionObservationDiscovery {
    public record Candidate(UUID idempotencyKey,UUID opportunityId,long expectedOpportunityRevision) {}
    public record Page(List<Candidate> candidates,String nextCursor,int diagnostics){public Page{candidates=List.copyOf(candidates);if(diagnostics<0||diagnostics>100)throw new IllegalArgumentException("Invalid diagnostics count");}}
    public record Response(int status,Page page,String errorCode) {}
    record Cursor(Instant observed,OpportunityOwnerExceptionCandidates.Position after) {}
    private final byte[] key;
    public OwnerExceptionObservationDiscovery(byte[] key){if(key==null||key.length<32)throw new IllegalArgumentException("Cursor key required");this.key=key.clone();}
    public Response list(Connection connection,Actor actor,int limit,String encoded) {
        try {
            if(limit<1||limit>100)throw invalid();
            return R1ServiceReadRuntime.read(connection,actor,(c,now)->{
                if(!R2OpportunityServiceScopeReader.databaseBacked().ownerExceptionScope(c,actor,now).authorized())throw forbidden();
                var cursor=encoded==null?new Cursor(now,null):decode(actor,encoded,now);
                var checks=R2OpportunityOwnerExceptionAssembly.checks();
                var page=OpportunityOwnerExceptionCandidates.databaseBacked(checks).scan(c,actor,cursor.observed(),cursor.after(),limit);
                var scope=R2OpportunityServiceScopeReader.databaseBacked().ownerExceptionScope(c,actor,R1ServiceReadRuntime.databaseTime(c));
                if(!scope.authorized())throw forbidden();
                var result=new ArrayList<Candidate>();
                for(var candidate:page.candidates()) {
                    if(!checks.canDiscover(c,actor,candidate.opportunity(),scope.organizations()))throw forbidden();
                    result.add(new Candidate(commandId(actor,cursor.observed(),candidate.opportunity()),candidate.opportunity().id(),candidate.opportunity().revision()));
                }
                String next=page.exhausted()?null:encode(actor,new Cursor(cursor.observed(),page.lastScanned()));
                return new Response(200,new Page(result,next,page.restrictedDiagnostics()),null);
            });
        }catch(R1ServiceReadRuntime.Failure failure){return new Response(failure.status(),null,failure.code());}
    }
    static UUID commandId(Actor actor,Instant observed,Subject opportunity) {
        var body=Map.of("profile","R2_OWNER_EXCEPTION_OBSERVATION_KEY_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"observed",observed.toString(),"opportunityId",opportunity.id().toString(),"revision",opportunity.revision());
        try {
            var sha=MessageDigest.getInstance("SHA-1");var ns=UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            sha.update(ByteBuffer.allocate(16).putLong(ns.getMostSignificantBits()).putLong(ns.getLeastSignificantBits()).array());
            var hash=sha.digest(CanonicalJson.encode(body).getBytes(StandardCharsets.UTF_8));hash[6]=(byte)((hash[6]&15)|0x50);hash[8]=(byte)((hash[8]&63)|0x80);var bytes=ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(),bytes.getLong());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    String encode(Actor actor,Cursor cursor) {
        Objects.requireNonNull(cursor.after());
        var raw="R2_OWNER_EXCEPTION_CURSOR_V1\n"+actor.tenantId()+"\n"+actor.principalId()+"\n"+actor.appointmentId()+"\n"+cursor.observed()+"\n"+cursor.after().at()+"\n"+cursor.after().id();
        var bytes=raw.getBytes(StandardCharsets.UTF_8);return base64(bytes)+"."+base64(mac(bytes));
    }
    Cursor decode(Actor actor,String encoded,Instant now) {
        try {
            if(encoded==null||encoded.length()>2048)throw invalid();var parts=encoded.split("\\.",-1);if(parts.length!=2)throw invalid();
            var bytes=Base64.getUrlDecoder().decode(parts[0]);var signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!base64(bytes).equals(parts[0])||!base64(signature).equals(parts[1])||!MessageDigest.isEqual(mac(bytes),signature))throw invalid();
            var fields=new String(bytes,StandardCharsets.UTF_8).split("\n",-1);
            if(fields.length!=7||!fields[0].equals("R2_OWNER_EXCEPTION_CURSOR_V1")||!fields[1].equals(actor.tenantId().toString())||!fields[2].equals(actor.principalId().toString())||!fields[3].equals(actor.appointmentId().toString()))throw invalid();
            var observed=Instant.parse(fields[4]);var at=Instant.parse(fields[5]);
            if(observed.isAfter(now)||!now.isBefore(observed.plusSeconds(300))||at.isAfter(observed))throw invalid();
            return new Cursor(observed,new OpportunityOwnerExceptionCandidates.Position(at,UUID.fromString(fields[6])));
        }catch(IllegalArgumentException|DateTimeException failure){throw invalid();}
    }
    private static String base64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private byte[] mac(byte[] value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value);}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    private static R1ServiceReadRuntime.Failure invalid(){return new R1ServiceReadRuntime.Failure(400,"VALIDATION_FAILED");}
    private static R1ServiceReadRuntime.Failure forbidden(){return new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}
