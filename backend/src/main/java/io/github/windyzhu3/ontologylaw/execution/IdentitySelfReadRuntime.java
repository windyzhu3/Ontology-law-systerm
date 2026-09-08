package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.HumanIdentityReader.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;

/** Narrow SELF exception. All preparation and audit complete under shared fences before any response escapes. */
public final class IdentitySelfReadRuntime {
    public record EntryRights(boolean workbench,boolean administration) {}
    @FunctionalInterface public interface Rights {EntryRights evaluate(Connection c,Actor actor,Choice own)throws SQLException;}
    private final AuditAppender audit;private final ActorScopeProtection scopes;
    public IdentitySelfReadRuntime(AuditAppender audit,ActorScopeProtection scopes){this.audit=Objects.requireNonNull(audit);this.scopes=Objects.requireNonNull(scopes);}
    public Map<String,Object> read(Connection connection,VerifiedHumanIdentity identity,UUID own,UUID behalf,UUID correlation,Rights rights) {
        try {
            return inTransaction(connection,Capability.QUERY,c->{
                R1BusinessFence.databaseBacked().shared(c,identity.tenantId());AuthorizationService.databaseBacked().lockForEvaluation(c,identity.tenantId());
                var reader=HumanIdentityReader.databaseBacked();var self=reader.self(c,identity);var selectedOwn=self.select(own);
                var delegated=selectedOwn==null?List.<DelegatedChoice>of():reader.delegated(c,identity,selectedOwn.appointmentId());
                var actor=reader.selectDelegated(self,selectedOwn,behalf,delegated);
                var choices=self.choices();
                Choice selectedChoice=actor==null?null:behalf==null?choices.stream().filter(choice->choice.appointment().id().equals(actor.appointmentId())).findFirst().orElseThrow():delegated.stream().filter(choice->choice.choice().appointment().id().equals(behalf)).findFirst().orElseThrow().choice();
                EntryRights access=actor==null?new EntryRights(false,false):rights.evaluate(c,actor,selectedChoice);
                var body=new LinkedHashMap<String,Object>();body.put("displayName",self.displayName());body.put("state",actor!=null?"READY":choices.isEmpty()?"NO_APPOINTMENT":"APPOINTMENT_SELECTION_REQUIRED");
                body.put("appointmentChoices",choices.stream().map(choice->Map.of("id",choice.appointment().id().toString(),"label",choice.label())).toList());
                body.put("selectedAppointmentId",actor==null?null:actor.appointmentId().toString());body.put("actorScopeKey",actor==null?null:scopes.key(actor));
                body.put("canEnterWorkbench",access.workbench());body.put("canEnterIdentityAdmin",behalf==null&&access.administration());body.put("delegatedAppointmentChoices",delegated.stream().map(candidate->Map.of("id",candidate.choice().appointment().id().toString(),"label",candidate.choice().label())).toList());body.put("selectedOnBehalfAppointmentId",behalf==null?null:behalf.toString());
                var sources=new ArrayList<Subject>();sources.add(self.principal());choices.forEach(choice->sources.add(choice.appointment()));
                delegated.forEach(candidate->{if(!sources.contains(candidate.choice().appointment()))sources.add(candidate.choice().appointment());});
                var entry=new AuditAppender.SelfDisclosureEntry(UUID.randomUUID(),correlation,identity.tenantId(),self.principal(),actor==null?null:actor.appointmentId(),SensitiveReadClock.now(c),sources);
                setLocalRole(c,Capability.AUDIT);audit.append(c,entry);
                return Collections.unmodifiableMap(body);
            });
        }catch(Failure refused){throw refused;}
        catch(SQLException|RuntimeException unavailable){throw new Failure("SERVICE_UNAVAILABLE");}
    }
}
