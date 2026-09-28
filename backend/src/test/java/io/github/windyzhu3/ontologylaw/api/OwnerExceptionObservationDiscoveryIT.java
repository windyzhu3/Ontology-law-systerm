package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerExceptionObservationDiscoveryIT extends ContactFlowFixture {
    private final OwnerExceptionObservationDiscovery discovery=new OwnerExceptionObservationDiscovery(new byte[32]);
    private Subject opening()throws Exception {
        setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector());}
    }
    private OwnerExceptionObservationDiscovery.Response list(Actor actor,int limit,String cursor)throws Exception {
        try(var c=database.apiConnection()){return discovery.list(c,actor,limit,cursor);}
    }
    @Test void unchanged_active_exception_is_not_republished_but_changed_authority_is_discovered()throws Exception {
        var opportunity=opening();var actor=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->R2OpportunityOwnerExceptionAssembly.service().observe(x,seed.tenant(),opportunity).orElseThrow());}
        var before=counts();var stable=list(actor,50,null);assertEquals(200,stable.status());
        assertTrue(stable.page().candidates().isEmpty(),"Unchanged active exceptions must not produce another observation command each scan");
        assertEquals(before,counts());
        var paged=list(actor,1,null);assertTrue(paged.page().candidates().isEmpty());assertNotNull(paged.page().nextCursor());
        var end=list(actor,1,paged.page().nextCursor());assertTrue(end.page().candidates().isEmpty());assertNull(end.page().nextCursor());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return null;});}
        var changed=list(actor,50,null);assertEquals(200,changed.status());assertEquals(1,changed.page().candidates().size());
        assertEquals(opportunity.id(),changed.page().candidates().getFirst().opportunityId());
    }
    @Test void coordination_is_quiet_until_the_review_deadline_then_discovered_again()throws Exception {
        var opportunity=opening();var actor=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        OpportunityOwnerExceptionService.Snapshot snapshot;
        try(var c=database.apiConnection()){snapshot=inTransaction(c,Capability.COMMAND,x->R2OpportunityOwnerExceptionAssembly.service().observe(x,seed.tenant(),opportunity).orElseThrow());}
        var decision=new OpportunityOwnerExceptionService.Decision(snapshot.selector(),opportunity,snapshot.responsibility().basis(),snapshot.task(),snapshot.waitReceipt(),seed.appointment(),"约定复查");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->R2OpportunityOwnerExceptionAssembly.service().coordinate(x,seed.tenant(),decision,java.time.Instant.now().plusSeconds(5)));}
        var frozen=java.time.Instant.now();
        var cursor=discovery.encode(actor,new OwnerExceptionObservationDiscovery.Cursor(frozen,new io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionCandidates.Position(java.time.Instant.EPOCH,UUID.randomUUID())));
        assertTrue(list(actor,50,null).page().candidates().isEmpty(),"Coordination must not rewrite itself on every poll");
        try(var c=database.apiConnection();var p=c.prepareStatement("select pg_sleep(5.1)")){p.execute();}
        var due=list(actor,50,null);assertEquals(200,due.status());assertEquals(1,due.page().candidates().size());
        var continued=list(actor,50,cursor);assertEquals(200,continued.status());assertEquals(1,continued.page().candidates().size(),"Frozen page time must not hide a review that is now due");
    }
    @Test void missing_owner_grant_is_discoverable_without_business_mutation_and_requires_named_service_authority()throws Exception {
        var opportunity=opening();var actor=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");var before=counts();
        var result=list(actor,50,null);assertEquals(200,result.status(),result.errorCode());
        assertEquals(1,result.page().candidates().size());assertEquals(opportunity.id(),result.page().candidates().getFirst().opportunityId());
        assertEquals(5,result.page().candidates().getFirst().idempotencyKey().version());assertEquals(before,counts());
        assertEquals("0",scalar("select count(*) from opportunity.owner_exception where tenant_id=?",seed.tenant()));
        assertEquals(403,list(seed.request().actor(),50,null).status());
        assertEquals(403,list(service("OPPORTUNITY_TASK_ACTIVATE"),50,null).status());
    }
    @Test void denied_candidate_does_not_stall_page_and_cursor_cannot_cross_selected_identity()throws Exception {
        var opportunity=opening();var actor=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_OWNER_EXCEPTION_DISCOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),opportunity.id(),opportunity.revision());
        var page=list(actor,1,null);assertEquals(200,page.status(),page.errorCode());assertTrue(page.page().candidates().isEmpty());assertNotNull(page.page().nextCursor());
        var end=list(actor,1,page.page().nextCursor());assertEquals(200,end.status());assertTrue(end.page().candidates().isEmpty());assertNull(end.page().nextCursor());
        assertEquals(400,list(service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER"),1,page.page().nextCursor()).status());
        assertEquals(400,list(actor,1,page.page().nextCursor()+"x").status());
    }
}
