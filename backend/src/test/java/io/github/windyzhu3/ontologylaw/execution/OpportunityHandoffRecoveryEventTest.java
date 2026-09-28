package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpportunityHandoffRecoveryEventTest {
    @ParameterizedTest @ValueSource(strings={"R2_OPPORTUNITY_FOLLOWUP_V1","R2_OPPORTUNITY_HANDOFF_WAIT_V1"})
    void acceptsOnlyExactRegisteredWaitAndPreservesTask(String profile)throws Exception {
        verify(profile,false);
        assertThrows(java.sql.SQLException.class,()->verify(profile,true));
    }
    @org.junit.jupiter.api.Test void refusesUnregisteredWaitProfile() {
        assertThrows(java.sql.SQLException.class,()->verify("CONTACT_RETRY_V1",false));
    }
    private void verify(String profile,boolean staleWait)throws Exception {
        var tenant=UUID.randomUUID();var task=UUID.randomUUID();var owner=UUID.randomUUID();
        var subject=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
        var hash=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var wait=new Subject("responsibility.wait_receipt",UUID.randomUUID(),null,hash);
        var progress=new Subject("opportunity.opportunity_progress",UUID.randomUUID(),null,hash);
        var before=new R1EventFacts.Task(new Subject("responsibility.task_occurrence",task,0L,null),subject,owner,"PROGRESS_OPPORTUNITY","RECORD_OPPORTUNITY_PROGRESS","WAITING","R2_BUSINESS_4H_V1",14400,Instant.EPOCH,null,null);
        var after=new R1EventFacts.Task(new Subject("responsibility.task_occurrence",task,1L,null),subject,owner,before.purpose(),before.primaryCommand(),"OPEN",before.slaCode(),before.slaSeconds(),before.slaDue(),null,null);
        var receipt=new R1EventFacts.Wait(wait,task,staleWait?1:0,profile,1,Instant.EPOCH);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var facts=(R1EventFacts)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{R1EventFacts.class},(proxy,method,args)->switch(method.getName()) {
            case "task"->calls.getAndIncrement()==0?before:after;
            case "latestWait"->receipt;
            default->throw new AssertionError(method.getName());
        });
        var actor=new Actor(tenant,UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE);
        var envelope=new CommandEnvelope(CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of());
        var context=new CommandHandler.Context(CommandScope.opportunityRecovery(tenant,task,subject,wait,progress),
            new Request(actor,before.selector(),UUID.randomUUID(),new Requirement("OPPORTUNITY_TASK_RECOVER","R2_OPPORTUNITY_SYSTEM",Path.SYSTEM,UUID.randomUUID())),
            new CommandAuthorizationBinding.Recovery(task,subject,0,wait.id(),hash));
        var policy=new R1EventPolicy(facts);policy.beforeWork(null,envelope,context);
        policy.validate(null,envelope,context,CommandHandler.Result.succeeded(after.selector(),CommandHandler.Event.OpportunityTaskReopenedV1));
    }
}
