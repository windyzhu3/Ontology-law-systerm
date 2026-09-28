package io.github.windyzhu3.ontologylaw.opportunity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** A real attempt and future arrangement; never effective progress or a client response. */
public record OpportunityFollowupAttempt(String type,String summary,Instant occurredAt,Instant nextCheckAt) {
    public OpportunityFollowupAttempt {
        if(type==null||!Set.of("NOT_CONNECTED","NO_REPLY","NO_EFFECTIVE_PROGRESS").contains(type))throw new IllegalArgumentException("Attempt kind required");
        if(summary==null)throw new IllegalArgumentException("Attempt summary required");
        summary=Normalizer.normalize(summary.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFC).strip();
        if(summary.isBlank()||summary.codePointCount(0,summary.length())>2000||summary.codePoints().anyMatch(c->c<32&&c!=9&&c!=10||c>=127&&c<=159||c>=0xD800&&c<=0xDFFF))throw new IllegalArgumentException("Attempt summary required");
        exact(occurredAt);exact(nextCheckAt);
    }
    public void validateAt(Instant createdAt,Instant openedAt){exact(createdAt);if(occurredAt.isAfter(createdAt)||occurredAt.isBefore(openedAt)||!nextCheckAt.isAfter(createdAt))throw new IllegalArgumentException("Past attempt and future arrangement required");}
    private static void exact(Instant at){if(at==null||at.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond time required");}
    public Map<String,Object> values(){return Map.of("type",type,"summary",summary,"occurredAt",occurredAt.toString(),"nextCheckAt",nextCheckAt.toString());}
    @Override public String toString(){return "OpportunityFollowupAttempt[protected]";}
}
