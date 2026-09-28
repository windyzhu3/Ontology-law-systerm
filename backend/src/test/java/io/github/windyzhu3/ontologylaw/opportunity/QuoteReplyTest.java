package io.github.windyzhu3.ontologylaw.opportunity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class QuoteReplyTest {
    final Instant now=Instant.parse("2026-09-20T10:00:00Z");
    QuoteReply reply(QuoteReply.Kind kind,Instant next){return new QuoteReply(kind,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"客户原意",now,next);}
    @Test void acceptance_is_not_a_waiting_task_and_requires_evidence(){
        var r=reply(QuoteReply.Kind.ACCEPTED,null);
        assertEquals(QuoteReply.Next.CONTRACT_PREPARATION_SOURCE,r.next());
        assertThrows(IllegalArgumentException.class,()->new QuoteReply(QuoteReply.Kind.ACCEPTED,UUID.randomUUID(),UUID.randomUUID(),null,"接受",now,null));
        assertThrows(IllegalArgumentException.class,()->reply(QuoteReply.Kind.ACCEPTED,now.plusSeconds(60)));
    }
    @Test void non_acceptance_never_silently_closes_opportunity(){
        assertEquals(QuoteReply.Next.FOLLOW_UP,reply(QuoteReply.Kind.NOT_ACCEPTED,now.plusSeconds(60)).next());
        assertEquals(QuoteReply.Next.CLARIFY_REPLY,reply(QuoteReply.Kind.AMBIGUOUS,now.plusSeconds(60)).next());
        assertEquals(QuoteReply.Next.SALES_DISPOSITION,reply(QuoteReply.Kind.REJECTED,now.plusSeconds(60)).next());
        for(var k: new QuoteReply.Kind[]{QuoteReply.Kind.NOT_ACCEPTED,QuoteReply.Kind.AMBIGUOUS,QuoteReply.Kind.REJECTED}){
            assertThrows(IllegalArgumentException.class,()->reply(k,null));
            assertThrows(IllegalArgumentException.class,()->reply(k,now));
        }
    }
    @Test void reply_must_be_after_delivery_not_future_and_exact_version(){
        var r=reply(QuoteReply.Kind.ACCEPTED,null);
        assertDoesNotThrow(()->r.validate(r.quoteRevisionId(),r.issueId(),now.minusSeconds(1),now.plusSeconds(1),now));
        assertThrows(IllegalArgumentException.class,()->r.validate(UUID.randomUUID(),r.issueId(),now.minusSeconds(1),now.plusSeconds(1),now));
        assertThrows(IllegalArgumentException.class,()->r.validate(r.quoteRevisionId(),UUID.randomUUID(),now.minusSeconds(1),now.plusSeconds(1),now));
        assertThrows(IllegalArgumentException.class,()->r.validate(r.quoteRevisionId(),r.issueId(),now.plusSeconds(1),now.plusSeconds(2),now));
        assertThrows(IllegalArgumentException.class,()->r.validate(r.quoteRevisionId(),r.issueId(),now.minusSeconds(1),now,now));
        assertThrows(IllegalArgumentException.class,()->r.validate(r.quoteRevisionId(),r.issueId(),now.minusSeconds(2),now.plusSeconds(1),now.minusSeconds(1)));
        assertFalse(r.toString().contains("客户原意"));
    }
    @Test void late_recording_preserves_valid_historical_acceptance_but_not_past_followup(){
        var accepted=reply(QuoteReply.Kind.ACCEPTED,null);
        assertDoesNotThrow(()->accepted.validate(accepted.quoteRevisionId(),accepted.issueId(),now.minusSeconds(1),now.plusSeconds(1),now.plusSeconds(60)));
        var waiting=reply(QuoteReply.Kind.NOT_ACCEPTED,now.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,()->waiting.validate(waiting.quoteRevisionId(),waiting.issueId(),now.minusSeconds(1),now.plusSeconds(1),now.plusSeconds(60)));
    }
}
