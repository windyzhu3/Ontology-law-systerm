package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.OpportunityMaintenanceTasks;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityOwnerExceptionChecksIT extends ContactFlowFixture {
    private Subject opening()throws Exception {
        setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));
        assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector());}
    }
    private OpportunityTaskActivationService.OpeningSourceReader sources(){return (c,t,a,k)->{
        var facts=R1EventReaders.databaseBacked();var contact=facts.contact(c,t,k);var assignment=facts.assignment(c,t,a);var task=contact==null?null:facts.task(c,t,contact.taskId());
        return new OpportunityTaskActivationService.OpeningSources(
                contact==null?null:new OpportunityTaskActivationService.Contact(contact.selector(),contact.leadId(),contact.assignmentId(),contact.taskId(),contact.code()),
                assignment==null?null:new OpportunityTaskActivationService.Assignment(assignment.selector(),assignment.leadId(),assignment.owner()),
                task==null?null:new OpportunityTaskActivationService.ContactTask(task.selector(),task.lead(),task.owner(),task.purpose(),task.primaryCommand(),task.state(),task.completion()));
    };}
    private OpportunityOwnerExceptionChecks checks(){return checks(sources());}
    private OpportunityOwnerExceptionChecks checks(OpportunityTaskActivationService.OpeningSourceReader sources){return OpportunityOwnerExceptionChecks.databaseBacked(sources,
            (c,t,o,r)->new OpportunityOwnerExceptionChecks.TaskState(null,null,null,null,List.of(),!OpportunityMaintenanceTasks.databaseBacked().initialExists(c,t,o.id())),
            (c,t,o,r,e,now)->null);}
    private Observation inspect(Subject o)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->checks().inspect(x,seed.tenant(),o,OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),o),Instant.now().minusSeconds(1)));}
    }
    private void ownerGrant()throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return null;});}}
    private void supervisorGrant()throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");return null;});}}
    private OpportunityOwnerExceptionService exceptions(){return OpportunityOwnerExceptionService.databaseBacked(checks(),checks(),(c,t,h,o,r,task,waitReceipt,receiver,newTask,actor,zone)->{throw new SQLException("No task mutation in discovery fixture");});}
    @Test void current_database_checks_distinguish_missing_authority_deny_and_inactive_owner()throws Exception {
        var o=opening();assertTrue(inspect(o).reasons().contains(Reason.OWNER_AUTHORITY_MISSING));
        ownerGrant();assertEquals(Set.of(Reason.SUPERVISOR_UNRESOLVED),inspect(o).reasons());
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'SALES_OPPORTUNITY_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),o.id());
        assertTrue(inspect(o).reasons().contains(Reason.OWNER_DENIED));
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());
        assertTrue(inspect(o).reasons().containsAll(Set.of(Reason.OWNER_INACTIVE,Reason.OWNER_DENIED,Reason.SUPERVISOR_UNRESOLVED)));
    }
    @Test void inactive_owner_is_discovered_through_historical_organization_without_read_side_effects()throws Exception {
        var o=opening();var scanner=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var page=OpportunityOwnerExceptionCandidates.databaseBacked(checks()).scan(x,scanner,Instant.now().minusMillis(1),null,100);
            assertEquals(1,page.scanned());assertEquals(o,page.candidates().getFirst().opportunity());assertTrue(page.candidates().getFirst().reasons().contains(Reason.OWNER_INACTIVE));return null;});}
        assertEquals("0",scalar("select count(*) from opportunity.owner_exception where tenant_id=?",seed.tenant()));
    }
    @Test void denied_rows_advance_scan_position_and_do_not_expose_candidate()throws Exception {
        var o=opening();var scanner=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_OWNER_EXCEPTION_DISCOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,0)",seed.tenant(),UUID.randomUUID(),scanner.principalId(),seed.appointment(),o.id());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var candidates=OpportunityOwnerExceptionCandidates.databaseBacked(checks());var at=Instant.now().minusMillis(1);var first=candidates.scan(x,scanner,at,null,1);
            assertEquals(1,first.scanned());assertTrue(first.candidates().isEmpty());assertNotNull(first.lastScanned());
            assertEquals(0,candidates.scan(x,scanner,at,first.lastScanned(),1).scanned());return null;});}
    }
    @Test void active_cycles_are_scanned_when_owner_recovers_and_when_opportunity_closes()throws Exception {
        var o=opening();var scanner=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{assertTrue(exceptions().observe(x,seed.tenant(),o).isPresent());return null;});}
        ownerGrant();supervisorGrant();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var page=OpportunityOwnerExceptionCandidates.databaseBacked(checks()).scan(x,scanner,Instant.now().minusMillis(1),null,100);
            assertEquals(1,page.candidates().size());assertTrue(page.candidates().getFirst().reasons().isEmpty());return null;});}
        // No immutable validation/audit fact has been supplied, so no invented OWNER_VALIDATED resolution.
        try(var c=database.apiConnection()){assertThrows(IllegalArgumentException.class,()->inTransaction(c,Capability.COMMAND,x->exceptions().observe(x,seed.tenant(),o)));}
        mutate("update opportunity.opportunity set close_outcome_code='LOST',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),o.id());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var page=OpportunityOwnerExceptionCandidates.databaseBacked(checks()).scan(x,scanner,Instant.now().minusMillis(1),null,100);
            assertEquals(1,page.candidates().size());assertEquals(1L,page.candidates().getFirst().opportunity().revision());return null;});}
    }
    @Test void missing_one_source_still_protects_other_known_exact_facts()throws Exception {
        var o=opening();var scanner=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_OWNER_EXCEPTION_DISCOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",seed.tenant(),UUID.randomUUID(),scanner.principalId(),seed.appointment(),secondaryFact.type(),secondaryFact.id(),secondaryFact.revision());
        var partial=checks((c,t,a,k)->{var full=sources().read(c,t,a,k);return new OpportunityTaskActivationService.OpeningSources(null,full.assignment(),full.task());});
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertFalse(partial.canDiscover(x,scanner,o,Set.of(seed.org())));return null;});}
    }
    @Test void opportunity_due_scope_includes_cross_organization_grantees_only_when_grant_coverage_intersects()throws Exception {
        opening();var scanner=service("OPPORTUNITY_TASK_RECOVER");UUID org=UUID.randomUUID(),principal=UUID.randomUUID(),receiver=UUID.randomUUID();
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'OTHER_ROOT','Other organization','ACTIVE',clock_timestamp())",seed.tenant(),org);
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'Cross organization receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),receiver,principal,org);
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertFalse(io.github.windyzhu3.ontologylaw.identity.R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(x,scanner,"OPPORTUNITY_TASK_RECOVER",Instant.now()).ownerAppointments().contains(receiver));return null;});}
        UUID grant=UUID.randomUUID();mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_OPPORTUNITY_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),grant,receiver,seed.appointment(),seed.org());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertTrue(io.github.windyzhu3.ontologylaw.identity.R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(x,scanner,"OPPORTUNITY_TASK_RECOVER",Instant.now()).ownerAppointments().contains(receiver));return null;});}
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),grant);
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertFalse(io.github.windyzhu3.ontologylaw.identity.R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(x,scanner,"OPPORTUNITY_TASK_RECOVER",Instant.now()).ownerAppointments().contains(receiver));return null;});}
    }
}
