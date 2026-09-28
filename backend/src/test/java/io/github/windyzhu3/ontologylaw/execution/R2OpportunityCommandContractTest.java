package io.github.windyzhu3.ontologylaw.execution;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class R2OpportunityCommandContractTest {
    @Test void opportunity_command_has_its_own_subject_scope_and_primary_draft_contract(){
        var type=CommandEnvelope.Type.valueOf("RECORD_OPPORTUNITY_PROGRESS");
        assertEquals("OPPORTUNITY_OWNER:SALES_OPPORTUNITY_OWNER",R1CommandPolicy.primaryPolicy(type));
        assertEquals("RecordOpportunityProgressV1",R1CommandPolicy.primarySchema(type));
        var scope=CommandScope.opportunity(UUID.randomUUID(),UUID.randomUUID(),new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null));
        assertEquals(type,scope.type());assertTrue(scope.canonical().contains("R2_OPPORTUNITY_COMMAND_SCOPE_V1"));
        assertThrows(IllegalArgumentException.class,()->CommandScope.opportunity(UUID.randomUUID(),UUID.randomUUID(),new Subject("lead.lead",UUID.randomUUID(),0L,null)));
    }
    @Test void r2_progress_event_does_not_enter_the_frozen_r1_queue(){
        var event=CommandHandler.Event.valueOf("OpportunityProgressRecordedV1");
        assertEquals("opportunity.opportunity_progress",event.sourceFactType());
        assertEquals(Set.of(CommandHandler.QueueOwner.valueOf("R2_PROJECTION")),event.queueOwners());
        assertEquals(Set.of(CommandHandler.QueueOwner.R1_PROJECTION),CommandHandler.Event.OpportunityOpened.queueOwners());
    }

    @Test void receipt_recovery_preserves_opportunity_and_rejects_cross_domain_or_extra_bindings(){
        var tenant=UUID.randomUUID();var task=UUID.randomUUID();var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),2L,null);
        var scope=CommandScope.opportunity(tenant,task,opportunity);
        var binding=new HashMap<String,Object>();binding.put("kind","TASK");binding.put("evidence",null);
        var root=Map.<String,Object>of("profile","R1_COMMAND_RECEIPT_RECOVERY_V1","scope",scope.fields(),"binding",binding);
        var metadata=new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata("RECORD_OPPORTUNITY_PROGRESS",root);
        assertEquals(opportunity,metadata.lead());assertEquals(task,metadata.taskId());assertArrayEquals(scope.digest(),metadata.scopeDigest());
        for(var replacement:List.of("lead.lead","R1_COMMAND_SCOPE_V1")){
            String raw=CanonicalJson.encode(root).replace(replacement.equals("lead.lead")?"opportunity.opportunity":"R2_OPPORTUNITY_COMMAND_SCOPE_V1",replacement);
            assertThrows(IllegalArgumentException.class,()->new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata("RECORD_OPPORTUNITY_PROGRESS",io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(raw)));
        }
        binding.put("evidence",Map.of());
        assertThrows(IllegalArgumentException.class,()->new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata("RECORD_OPPORTUNITY_PROGRESS",root));
    }
    @Test void draft_recovery_accepts_opportunity_only_for_its_named_action(){
        var binding=new HashMap<String,Object>();binding.put("kind","DRAFT");binding.put("lead",Map.of("type","opportunity.opportunity","id",UUID.randomUUID().toString(),"revision",0L));binding.put("taskRevision",0L);binding.put("draft",null);
        var scope=CommandScope.draft(UUID.randomUUID(),UUID.randomUUID(),CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS);
        var root=Map.<String,Object>of("profile","R1_COMMAND_RECEIPT_RECOVERY_V1","scope",scope.fields(),"binding",binding);
        assertEquals("opportunity.opportunity",new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata("SAVE_ACTION_DRAFT",root).lead().type());
        String raw=CanonicalJson.encode(root).replace("RECORD_OPPORTUNITY_PROGRESS","RECORD_CONTACT_RESULT");
        assertThrows(IllegalArgumentException.class,()->new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata("SAVE_ACTION_DRAFT",io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(raw)));
    }
    @Test void event_requires_matching_persisted_progress_completed_task_and_confirmed_draft()throws Exception{
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
        var taskId=UUID.randomUUID();var draftId=UUID.randomUUID();
        var progress=new Subject("opportunity.opportunity_progress",UUID.randomUUID(),null,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        var beforeDraft=new R1EventFacts.Draft(new Subject("responsibility.action_draft",draftId,0L,null),taskId,"RECORD_OPPORTUNITY_PROGRESS","RecordOpportunityProgressV1",1,"DRAFT");
        var afterDraft=new R1EventFacts.Draft(new Subject("responsibility.action_draft",draftId,1L,null),taskId,beforeDraft.action(),beforeDraft.schema(),1,"CONFIRMED");
        var due=java.time.Instant.parse("2026-09-14T08:00:00Z");
        var before=new R1EventFacts.Task(new Subject("responsibility.task_occurrence",taskId,0L,null),opportunity,actor.appointmentId(),"PROGRESS_OPPORTUNITY","RECORD_OPPORTUNITY_PROGRESS","OPEN","OPPORTUNITY_PROGRESS_V1",14400,due,null,beforeDraft);
        var after=new R1EventFacts.Task(new Subject("responsibility.task_occurrence",taskId,1L,null),opportunity,actor.appointmentId(),before.purpose(),before.primaryCommand(),"DONE",before.slaCode(),14400,due,progress,afterDraft);
        var envelope=new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of());
        var context=new CommandHandler.Context(CommandScope.opportunity(actor.tenantId(),taskId,opportunity),new Request(actor,opportunity,UUID.randomUUID(),new Requirement("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT,UUID.randomUUID())));
        for(int scenario=0;scenario<4;scenario++){
            final int mode=scenario;var calls=new java.util.concurrent.atomic.AtomicInteger();
            var facts=(R1EventFacts)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{R1EventFacts.class},(proxy,method,args)->switch(method.getName()){
                case "task"->calls.getAndIncrement()==0?before:(mode==2?before:after);
                case "progress"->mode==1?null:new R1EventFacts.Progress(progress,opportunity.id(),mode==3?UUID.randomUUID():taskId);
                default->throw new AssertionError(method.getName());
            });
            var policy=new R1EventPolicy(facts);policy.beforeWork(null,envelope,context);
            var result=CommandHandler.Result.succeeded(progress,CommandHandler.Event.OpportunityProgressRecordedV1);
            if(mode==0)policy.validate(null,envelope,context,result);
            else assertThrows(java.sql.SQLException.class,()->policy.validate(null,envelope,context,result));
        }
        assertEquals(62,R1EventPolicy.r2Branches().size());
        assertEquals(62,R1EventPolicy.r2Branches().stream().map(R1EventPolicy.Branch::id).distinct().count());
        var frozenSigningEvents=Set.of(CommandHandler.Event.ContractSignatureDraftSavedV1,CommandHandler.Event.ContractSignatureArrangementConfirmedV1,CommandHandler.Event.ContractSignatureSubmittedV1,CommandHandler.Event.ContractSignatureVerificationRecordedV1,CommandHandler.Event.ContractSignatureArchivedV1,CommandHandler.Event.ContractReturnedForRevisionV1,CommandHandler.Event.ContractSignatureVerificationReturnedV1,CommandHandler.Event.ContractSignatureReconciledV1,CommandHandler.Event.ContractPreparationReconciledV1,CommandHandler.Event.ContractPreparationRequestedV1,CommandHandler.Event.ContractPreparationDecisionRecordedV1,CommandHandler.Event.ContractPreparationStartedV1,CommandHandler.Event.ContractDraftSavedV1,CommandHandler.Event.ContractFormedV1,CommandHandler.Event.ContractReviewRequestedV1,CommandHandler.Event.ContractReviewRecordedV1,CommandHandler.Event.ContractApprovalRequestedV1,CommandHandler.Event.ContractDecisionRecordedV1,CommandHandler.Event.QuoteDraftSavedV1,CommandHandler.Event.QuoteFormedV1,CommandHandler.Event.QuoteApprovalRequestedV1,CommandHandler.Event.QuoteDecisionRecordedV1,CommandHandler.Event.QuoteDeliveredV1,CommandHandler.Event.QuoteResponseRecordedV1,CommandHandler.Event.OpportunityMaterialUploadOpenedV1,CommandHandler.Event.OpportunityMaterialAcceptedV1,CommandHandler.Event.OpportunityCustomerDraftSavedV1,CommandHandler.Event.OpportunityCustomerRequirementsConfirmedV1,CommandHandler.Event.OpportunityClosedV1,CommandHandler.Event.OpportunityOwnerExceptionObservedV1,CommandHandler.Event.OpportunityOwnerCoordinationRecordedV1,CommandHandler.Event.OpportunityResponsibilityTransferredV1,CommandHandler.Event.OpportunityActionDraftSavedV1,CommandHandler.Event.OpportunityProgressRecordedV1,CommandHandler.Event.OpportunityInitialTaskActivatedV1,CommandHandler.Event.OpportunityTaskReopenedV1);
        var approvedAdditions=Set.of(CommandHandler.Event.TransferWorkflowReconciledV1,CommandHandler.Event.TransferSubmittedV1,CommandHandler.Event.TransferResubmittedV1,CommandHandler.Event.TransferConflictReviewRecordedV1,CommandHandler.Event.TransferIntakeRecordedV1,CommandHandler.Event.MatterClassifiedV1,CommandHandler.Event.ContractReceiptReviewRequestedV1,CommandHandler.Event.ContractReceiptReviewRecordedV1,CommandHandler.Event.ContractReceiptSupplementedV1,CommandHandler.Event.ContractPaymentReconciledV1,CommandHandler.Event.ContractExecutionConditionsVerifiedV1,CommandHandler.Event.ContractExecutionReconciledV1,CommandHandler.Event.ContractNegotiationEndedV1,CommandHandler.Event.ContractTerminationReviewRequestedV1,CommandHandler.Event.ContractTerminationReviewRecordedV1,CommandHandler.Event.ContractTerminationReviewReconciledV1,CommandHandler.Event.QuotePreparationStartedV1,CommandHandler.Event.QuoteNegotiationEndedV1,CommandHandler.Event.OpportunitySupersededTaskRepairedV1);
        var expectedEvents=new HashSet<>(frozenSigningEvents);expectedEvents.addAll(approvedAdditions);
        assertEquals(expectedEvents,Arrays.stream(CommandHandler.Event.values()).filter(event->event.queueOwners().contains(CommandHandler.QueueOwner.R2_PROJECTION)).collect(java.util.stream.Collectors.toSet()));
        assertEquals(expectedEvents,R1EventPolicy.r2Branches().stream().flatMap(b->b.events().stream()).filter(event->event.queueOwners().contains(CommandHandler.QueueOwner.R2_PROJECTION)).collect(java.util.stream.Collectors.toSet()));
        assertTrue(R1EventPolicy.branches().stream().flatMap(b->b.events().stream()).allMatch(e->e.queueOwners().equals(Set.of(CommandHandler.QueueOwner.R1_PROJECTION))));
    }
    @Test void t08_commands_have_one_exact_named_event_and_only_r2_projection() {
        var expected=Map.ofEntries(
            Map.entry(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,CommandHandler.Event.ContractPreparationReconciledV1),
            Map.entry(CommandEnvelope.Type.REQUEST_CONTRACT_PREPARATION,CommandHandler.Event.ContractPreparationRequestedV1),
            Map.entry(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,CommandHandler.Event.ContractPreparationDecisionRecordedV1),
            Map.entry(CommandEnvelope.Type.START_CONTRACT_PREPARATION,CommandHandler.Event.ContractPreparationStartedV1),
            Map.entry(CommandEnvelope.Type.SAVE_CONTRACT_DRAFT,CommandHandler.Event.ContractDraftSavedV1),
            Map.entry(CommandEnvelope.Type.FORM_CONTRACT,CommandHandler.Event.ContractFormedV1),
            Map.entry(CommandEnvelope.Type.REQUEST_CONTRACT_REVIEW,CommandHandler.Event.ContractReviewRequestedV1),
            Map.entry(CommandEnvelope.Type.RECORD_CONTRACT_REVIEW,CommandHandler.Event.ContractReviewRecordedV1),
            Map.entry(CommandEnvelope.Type.REQUEST_CONTRACT_APPROVAL,CommandHandler.Event.ContractApprovalRequestedV1),
            Map.entry(CommandEnvelope.Type.RECORD_CONTRACT_DECISION,CommandHandler.Event.ContractDecisionRecordedV1));
        for(var entry:expected.entrySet()){var branches=R1EventPolicy.r2Branches().stream().filter(b->b.command()==entry.getKey()).toList();var expectedEvents=entry.getKey()==CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION?Set.of(entry.getValue(),CommandHandler.Event.ContractSignatureReconciledV1,CommandHandler.Event.ContractTerminationReviewReconciledV1,CommandHandler.Event.ContractPaymentReconciledV1,CommandHandler.Event.ContractExecutionReconciledV1,CommandHandler.Event.TransferWorkflowReconciledV1):Set.of(entry.getValue());assertEquals(expectedEvents.size(),branches.size());assertEquals(expectedEvents,branches.stream().flatMap(b->b.events().stream()).collect(java.util.stream.Collectors.toSet()));for(var branch:branches)assertEquals(branch.events().contains(CommandHandler.Event.ContractSignatureReconciledV1)?"SIGNATURE":branch.events().contains(CommandHandler.Event.ContractTerminationReviewReconciledV1)?"TERMINATION_REVIEW":branch.events().contains(CommandHandler.Event.ContractPaymentReconciledV1)?"PAYMENT":branch.events().contains(CommandHandler.Event.TransferWorkflowReconciledV1)?"TRANSFER":branch.events().contains(CommandHandler.Event.ContractExecutionReconciledV1)?"EXECUTION":"RECORDED",branch.outcome());assertEquals(Set.of(CommandHandler.QueueOwner.R2_PROJECTION),entry.getValue().queueOwners());if(entry.getKey().contracts())assertEquals(entry.getValue(),R1EventPolicy.contractEvent(entry.getKey()));}
    }
}
