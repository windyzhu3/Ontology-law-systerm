package io.github.windyzhu3.ontologylaw.opportunity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Confirmed effective interaction, distinct from drafts, attempts and future commitments. */
public record OpportunityProgressInput(String type,String summary,Instant occurredAt,Instant nextCheckAt) {
    public static final String CONTRACT="R2_OPPORTUNITY_PROGRESS_V1";
    public OpportunityProgressInput {
        if(type==null||!Set.of("PHONE_CONNECTED","CLIENT_VISIT","MEETING","SITE_VISIT","WECHAT_CONNECTED").contains(type))
            throw new IllegalArgumentException("Effective progress type required");
        summary=normalize(summary);
        if(summary==null||summary.isEmpty()||summary.codePointCount(0,summary.length())>2000)
            throw new IllegalArgumentException("Progress summary required");
        exactTime(occurredAt);exactTime(nextCheckAt);
    }
    public void validateAt(Instant recordedAt){
        exactTime(recordedAt);
        if(occurredAt.isAfter(recordedAt)||!nextCheckAt.isAfter(recordedAt))
            throw new IllegalArgumentException("Past interaction and future follow-up required");
    }
    public Map<String,Object> bodyValues(UUID tenant,UUID opportunity,long opportunityRevision,UUID progress,UUID task,long taskRevision,UUID actor,Instant recordedAt){
        validateAt(recordedAt);
        if(opportunityRevision<0||taskRevision<0||opportunityRevision>9007199254740991L||taskRevision>9007199254740991L)
            throw new IllegalArgumentException("Exact safe revisions required");
        return Map.of("profile",CONTRACT,"tenantId",tenant.toString(),"opportunityId",opportunity.toString(),
            "progressId",progress.toString(),"taskId",task.toString(),"recordedBy",actor.toString(),"recordedAt",recordedAt.toString(),
            "opportunityRevision",opportunityRevision,"taskRevision",taskRevision,
            "values",Map.of("type",type,"summary",summary,"occurredAt",occurredAt.toString(),"nextCheckAt",nextCheckAt.toString()));
    }
    private static String normalize(String text){
        if(text==null)return null;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException("Invalid progress text");}
            else if(Character.isLowSurrogate(c)||c<32&&c!=9&&c!=10&&c!=13||c>=127&&c<=159)throw new IllegalArgumentException("Invalid progress text");
        }
        text=Normalizer.normalize(text.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFC);
        int start=0,end=text.length();
        while(start<end&&(Character.isWhitespace(text.codePointAt(start))||Character.isSpaceChar(text.codePointAt(start))))start+=Character.charCount(text.codePointAt(start));
        while(end>start&&(Character.isWhitespace(text.codePointBefore(end))||Character.isSpaceChar(text.codePointBefore(end))))end-=Character.charCount(text.codePointBefore(end));
        return text.substring(start,end);
    }
    private static void exactTime(Instant time){
        if(time==null||time.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond instant required");
    }
    @Override public String toString(){return "OpportunityProgressInput[protected]";}
}
