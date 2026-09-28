package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;
class R25AiCandidateServiceTest {
    final Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
    final UUID opportunity=UUID.randomUUID();
    final List<Source> sources=List.of(new Source("s1","线索","TEXT","联系人林悦"));
    final R25AiSourceReadService.Snapshot snapshot=new R25AiSourceReadService.Snapshot("a".repeat(64),sources);
    final Result answer=new Result(List.of(new Item("contactName","CANDIDATE","林悦",List.of(new Citation("s1","联系人林悦")))));
    @Test void sourceIsReadBeforeAndAfterModelAndAgainBeforeAdoption() throws Exception {
        var reads=new AtomicInteger();var calls=new AtomicInteger();
        var service=new R25AiCandidateService(new byte[32],(a,o,t)->{assertEquals(actor,a);assertEquals(opportunity,o);reads.incrementAndGet();return snapshot;},(t,s)->{assertEquals(1,reads.get());calls.incrementAndGet();return answer;},Clock.systemUTC());
        var candidate=service.generate(actor,opportunity,Task.FIELDS);assertEquals(2,reads.get());assertEquals(1,calls.get());
        service.recheck(actor,opportunity,Task.FIELDS,candidate.sourceToken());assertEquals(3,reads.get());assertEquals(1,calls.get());
        assertFalse(candidate.toString().contains("林悦"));
    }
    @Test void changedSourceOrRevokedAuthorityDiscardsResult() {
        var reads=new AtomicInteger();
        var changed=new R25AiCandidateService(new byte[32],(a,o,t)->reads.incrementAndGet()==1?snapshot:new R25AiSourceReadService.Snapshot("b".repeat(64),sources),(t,s)->answer,Clock.systemUTC());
        assertEquals("AI_SOURCE_CHANGED",assertThrows(Failure.class,()->changed.generate(actor,opportunity,Task.FIELDS)).code());
        reads.set(0);
        var revoked=new R25AiCandidateService(new byte[32],(a,o,t)->{if(reads.incrementAndGet()>1)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");return snapshot;},(t,s)->answer,Clock.systemUTC());
        assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->revoked.generate(actor,opportunity,Task.FIELDS)).status());
    }
    @Test void adoptionTokenCannotCrossActorTaskOrTimeAndDelegationCannotCallModel() throws Exception {
        Instant now=Instant.parse("2026-09-28T10:00:00Z");var calls=new AtomicInteger();
        R25AiModel model=(t,s)->{calls.incrementAndGet();return answer;};
        var service=new R25AiCandidateService(new byte[32],(a,o,t)->snapshot,model,Clock.fixed(now,ZoneOffset.UTC));
        var candidate=service.generate(actor,opportunity,Task.FIELDS);
        var other=new Actor(actor.tenantId(),actor.principalId(),UUID.randomUUID(),null,null);
        assertThrows(Failure.class,()->service.recheck(other,opportunity,Task.FIELDS,candidate.sourceToken()));
        assertThrows(Failure.class,()->service.recheck(actor,opportunity,Task.SUMMARY,candidate.sourceToken()));
        var late=new R25AiCandidateService(new byte[32],(a,o,t)->snapshot,model,Clock.fixed(now.plusSeconds(600),ZoneOffset.UTC));
        assertThrows(Failure.class,()->late.recheck(actor,opportunity,Task.FIELDS,candidate.sourceToken()));
        var delegated=new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),UUID.randomUUID(),UUID.randomUUID());
        assertThrows(R1ServiceReadRuntime.Failure.class,()->service.generate(delegated,opportunity,Task.FIELDS));assertEquals(1,calls.get());
    }
}
