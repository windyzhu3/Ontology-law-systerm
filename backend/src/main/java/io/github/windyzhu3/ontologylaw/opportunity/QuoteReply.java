package io.github.windyzhu3.ontologylaw.opportunity;

import java.time.Instant;
import java.util.UUID;

/** Validated reply intent. Persistence must additionally verify evidence access and ownership. */
public record QuoteReply(Kind kind, UUID quoteRevisionId, UUID issueId, UUID evidenceVersionId,
                         String statement, Instant occurredAt, Instant nextCheckAt) {
    public enum Kind { ACCEPTED, NOT_ACCEPTED, REJECTED, AMBIGUOUS }
    public enum Next { CONTRACT_PREPARATION_SOURCE, FOLLOW_UP, SALES_DISPOSITION, CLARIFY_REPLY }
    public QuoteReply {
        if(kind==null || quoteRevisionId==null || issueId==null || evidenceVersionId==null)
            throw new IllegalArgumentException("Exact quote, delivery and reply evidence required");
        statement=QuotePackage.text(statement,4000);
        QuotePackage.exactTime(occurredAt);
        if(kind==Kind.ACCEPTED){
            if(nextCheckAt!=null)throw new IllegalArgumentException("Acceptance does not schedule a reply follow-up");
        }else{
            QuotePackage.exactTime(nextCheckAt);
            if(!nextCheckAt.isAfter(occurredAt))throw new IllegalArgumentException("Future follow-up required");
        }
    }
    public Next next(){return switch(kind){
        case ACCEPTED->Next.CONTRACT_PREPARATION_SOURCE;
        case NOT_ACCEPTED->Next.FOLLOW_UP;
        case REJECTED->Next.SALES_DISPOSITION;
        case AMBIGUOUS->Next.CLARIFY_REPLY;
    };}
    public void validate(UUID exactQuote,UUID exactIssue,Instant deliveredAt,Instant validUntil,Instant recordedAt){
        QuotePackage.exactTime(deliveredAt);QuotePackage.exactTime(validUntil);QuotePackage.exactTime(recordedAt);
        if(!quoteRevisionId.equals(exactQuote)||!issueId.equals(exactIssue))throw new IllegalArgumentException("Reply basis changed");
        if(occurredAt.isBefore(deliveredAt)||occurredAt.isAfter(recordedAt))throw new IllegalArgumentException("Reply occurrence must follow delivery and not be future");
        if(kind==Kind.ACCEPTED&&!occurredAt.isBefore(validUntil))throw new IllegalArgumentException("Cannot accept an expired quote");
        if(nextCheckAt!=null&&!nextCheckAt.isAfter(recordedAt))throw new IllegalArgumentException("Follow-up must remain in the future");
    }
    @Override public String toString(){return "QuoteReply[protected]";}
}
