package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class R2QuoteOwnerBoundaryIT extends R2QuoteWorkflowIT {
 private void pendingApproval()throws Exception {
  setup(true,true);confirmed();policy("REQUIRE_APPROVAL",List.of(seed.appointment()));quoteCommand("FORM_QUOTE",commercial());quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());
 }
 @Test void independent_quote_approval_is_not_a_broken_sales_task()throws Exception {
  pendingApproval();
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   var current=EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id()).selector();
   var owner=OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),current);
   var state=R2OpportunityOwnerExceptionAssembly.taskState(x,seed.tenant(),current,owner);
   assertTrue(state.lineageValid(),"Approval belongs to its designated reviewer, not the ordinary sales task");assertNull(state.task());
   assertTrue(state.protectedSources().stream().anyMatch(s->s.type().equals("opportunity.quote_approval_member")));
   assertFalse(R2SalesStageGuards.initialFollowupAllowed(x,seed.tenant(),opportunity.id()));return null;
  });}
 }
 @Test void unmatched_approval_task_and_revoked_sales_authority_are_not_hidden()throws Exception {
  pendingApproval();
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   var current=EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id()).selector();
   var owner=OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),current);
   var observed=R2OpportunityOwnerExceptionAssembly.checks().inspect(x,seed.tenant(),current,owner,java.time.Instant.now());
   assertTrue(observed.reasons().contains(OpportunityOwnerExceptionService.Reason.OWNER_AUTHORITY_MISSING));
   assertFalse(observed.reasons().contains(OpportunityOwnerExceptionService.Reason.SOURCE_INCONSISTENT));
   io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().create(x,seed.tenant(),io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.APPROVE_QUOTE,seed.appointment(),current,java.time.ZoneId.of("Asia/Shanghai"),java.time.Instant.now());
   assertFalse(R2OpportunityOwnerExceptionAssembly.taskState(x,seed.tenant(),current,owner).lineageValid(),"An unassigned extra approval task must remain inconsistent");return null;
  });}
 }
}
