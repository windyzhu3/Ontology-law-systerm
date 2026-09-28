package io.github.windyzhu3.ontologylaw.execution;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
class OpportunityOwnerExceptionEventTest {
 private final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();private final Subject opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
 private final Actor actor=new Actor(tenant,UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE);
 private final CommandEnvelope command=new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",0L));
 private R1EventFacts.OwnerException snapshot(long revision){return new R1EventFacts.OwnerException(new Subject("opportunity.owner_exception",id,revision,null),opportunity,opportunity,actor.appointmentId(),null,null,"ACTIVE",null,null,null,null,null,null,null,null);}
 private CommandHandler.Context context(){return new CommandHandler.Context(CommandScope.ownerException(command,opportunity,null,null,null,null),new Request(actor,opportunity,UUID.randomUUID(),new Requirement("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER","OPPORTUNITY_OWNER",Path.SYSTEM,UUID.randomUUID())),new CommandAuthorizationBinding.OwnerException(opportunity,null,null,null,null));}
 private R1EventPolicy policy(R1EventFacts.OwnerException before,R1EventFacts.OwnerException after){var facts=(R1EventFacts)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{R1EventFacts.class},(p,m,a)->switch(m.getName()){case "activeOwnerException"->before;case "ownerException"->after;default->throw new AssertionError(m.getName());});return new R1EventPolicy(facts);}
 @Test void observed_event_requires_a_new_persisted_revision()throws Exception{var context=context();var before=snapshot(2);var policy=policy(before,before);policy.beforeWork(null,command,context);assertThrows(java.sql.SQLException.class,()->policy.validate(null,command,context,CommandHandler.Result.succeeded(before.selector(),CommandHandler.Event.OpportunityOwnerExceptionObservedV1)));}
 @Test void exact_next_revision_accepts_registered_observed_event()throws Exception{var context=context();var after=snapshot(3);var policy=policy(snapshot(2),after);policy.beforeWork(null,command,context);assertDoesNotThrow(()->policy.validate(null,command,context,CommandHandler.Result.succeeded(after.selector(),CommandHandler.Event.OpportunityOwnerExceptionObservedV1)));}
 @Test void no_change_cannot_hide_an_active_exception_cycle()throws Exception{var context=context();var policy=policy(snapshot(2),null);policy.beforeWork(null,command,context);var audit=new Subject("audit.audit_entry",UUID.randomUUID(),null,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");assertThrows(java.sql.SQLException.class,()->policy.validate(null,command,context,CommandHandler.Result.noChange(audit)));}
}
