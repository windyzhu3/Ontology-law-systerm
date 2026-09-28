package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;

/** Does not own a connection: each loader invocation must acquire, audit, commit and close its own. */
public final class R25AiCandidateService {
    @FunctionalInterface public interface SourceLoader {R25AiSourceReadService.Snapshot read(Actor actor,UUID opportunity,Task task)throws SQLException;}
    public record Candidate(Task task,List<Item> items,List<Source> sources,String sourceToken,Instant generatedAt) {
        public Candidate {items=List.copyOf(items);sources=List.copyOf(sources);}
        @Override public String toString(){return "AiCandidate[protected]";}
    }
    private final R2ManagementCursor tokens;
    private final SourceLoader loader;
    private final R25AiModel model;
    private final Clock clock;
    public R25AiCandidateService(byte[] key,SourceLoader loader,R25AiModel model,Clock clock){tokens=new R2ManagementCursor(key);this.loader=Objects.requireNonNull(loader);this.model=Objects.requireNonNull(model);this.clock=Objects.requireNonNull(clock);}
    public Candidate generate(Actor actor,UUID opportunity,Task task)throws SQLException {
        own(actor);var before=loader.read(actor,opportunity,task);
        // Re-validate even an alternate adapter's result, not just the built-in HTTP adapter.
        var result=parse(task,before.sources(),JSON.writeValueAsString(model.generate(task,before.sources())));
        var after=loader.read(actor,opportunity,task);
        if(!before.equals(after))throw new Failure("AI_SOURCE_CHANGED");
        var now=clock.instant();return new Candidate(task,result.items(),after.sources(),tokens.encode(opportunity,scope(actor,opportunity,task,after.fingerprint()),now),now);
    }
    public void recheck(Actor actor,UUID opportunity,Task task,String token)throws SQLException {
        own(actor);if(token==null||token.isBlank())throw new Failure("AI_SOURCE_CHANGED");
        var current=loader.read(actor,opportunity,task);
        try{if(!opportunity.equals(tokens.decode(token,scope(actor,opportunity,task,current.fingerprint()),clock.instant())))throw new Failure("AI_SOURCE_CHANGED");}
        catch(R1ServiceReadRuntime.Failure ignored){throw new Failure("AI_SOURCE_CHANGED");}
    }
    private static String scope(Actor actor,UUID opportunity,Task task,String fingerprint){return CanonicalJson.encode(Map.of("profile","R25_AI_CANDIDATES_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"opportunity",opportunity.toString(),"task",task.name(),"fingerprint",fingerprint));}
    private static void own(Actor actor){if(actor==null||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}
