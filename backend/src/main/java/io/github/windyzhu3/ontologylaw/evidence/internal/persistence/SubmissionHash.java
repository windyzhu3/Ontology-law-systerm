package io.github.windyzhu3.ontologylaw.evidence.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Closed seven-field R1_JSON_JCS_SHA256_V1 encoder; never an object-byte digest. */
final class SubmissionHash {
    private SubmissionHash(){}
    static String hash(UUID tenant,UUID id,UUID object,String code,int version,UUID submitter,Instant at){
        if(version<1||at.getNano()%1000!=0)throw new IllegalArgumentException("Invalid immutable Submission row");
        String time=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT).format(at);
        String json="{\"evidence_submission_id\":"+quote(id.toString())+",\"received_source_object_id\":"+quote(object.toString())
            +",\"submission_contract_code\":"+quote(code)+",\"submission_contract_version\":"+version+",\"submitted_at\":"+quote(time)
            +",\"submitted_by_appointment_id\":"+quote(submitter.toString())+",\"tenantId\":"+quote(tenant.toString())+"}";
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String quote(String value){
        var out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            switch(ch){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{
                if(ch<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)ch));
                else if(Character.isHighSurrogate(ch)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("Invalid Unicode");out.append(ch).append(value.charAt(++i));}
                else if(Character.isLowSurrogate(ch))throw new IllegalArgumentException("Invalid Unicode");else out.append(ch);
            }}
        }
        return out.append('"').toString();
    }
}
