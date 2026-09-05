package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.execution.CommandHandler.Event.*;

/** Per-invocation verifier. Reads exact Owner facts on the runtime transaction, never caller outcome labels. */
public final class R1EventPolicy {
    public record Branch(String id,CommandEnvelope.Type command,String outcome,Set<CommandHandler.Event> events) {
        public Branch { events=Set.copyOf(events); }
    }
    private static Branch branch(String id,CommandEnvelope.Type command,String outcome,CommandHandler.Event... events){return new Branch(id,command,outcome,Set.of(events));}
    private static final List<Branch> BRANCHES=List.of(
        branch("CAPTURE_LEAD_CREATED",CommandEnvelope.Type.CAPTURE_LEAD,"CREATED",LeadCapturedV1),
        branch("SAVE_ACTION_DRAFT_CHANGED",CommandEnvelope.Type.SAVE_ACTION_DRAFT,"CREATED_OR_CHANGED",ActionDraftSavedV1),
        branch("REOPEN_DUE_CONTACT_TASKS_REOPENED",CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"WAITING_TO_OPEN",ContactTaskReopenedV1),
        branch("REOPEN_DUE_ROUTING_REVIEW_TASKS_REOPENED",CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS,"WAITING_TO_OPEN",RoutingReviewTaskReopenedV1),
        branch("P0_01_LINK_EXISTING",CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,"LINK_EXISTING_PARTY",LeadDuplicateResolutionRecordedV1),
        branch("P0_01_KEEP_SEPARATE",CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,"KEEP_SEPARATE",LeadDuplicateResolutionRecordedV1),
        branch("P0_02_COMPLETE",CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,"INGRESS_COMPLETED",LeadIngressCompletedV1),
        branch("P0_03_ASSIGN",CommandEnvelope.Type.ASSIGN_LEAD,"ASSIGNED",LeadAssignedV1),
        branch("P0_04_SCHEDULE_ROUTING_REVIEW",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"SCHEDULE_ROUTING_REVIEW",LeadRoutingDispositionRecordedV1),
        branch("P0_04_RETRY_ASSIGNMENT_NOW",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"RETRY_ASSIGNMENT_NOW",LeadRoutingDispositionRecordedV1),
        branch("P0_04_REQUEST_SOURCE_INTAKE_STOP",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"REQUEST_SOURCE_INTAKE_STOP",SourceIntakeStopRequestedV1),
        branch("ACK_SOURCE_INTAKE_STOP_REQUEST",CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,"SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED",SourceIntakeStopRequestAcknowledgedV1),
        branch("CONTACT_CONNECTED_VALID",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"CONNECTED_VALID",LeadContactResultRecordedV1,OpportunityOpened),
        branch("CONTACT_NOT_CONNECTED_RETRY",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"NOT_CONNECTED_RETRY",LeadContactResultRecordedV1),
        branch("CONTACT_NOT_CONNECTED_EXHAUSTED",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"NOT_CONNECTED_EXHAUSTED",LeadContactRetryExhaustedV1),
        branch("CONTACT_SUSPECT_INVALID",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"SUSPECT_INVALID",LeadContactResultRecordedV1),
        branch("REVIEW_CONFIRM_INVALID",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"CONFIRM_INVALID",LeadValidityReviewedV1),
        branch("REVIEW_CLOSE_UNREACHED",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"CLOSE_UNREACHED",LeadValidityReviewedV1),
        branch("REVIEW_REOPEN_CONTACT",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"REOPEN_CONTACT",LeadValidityReviewedV1));
    public static List<Branch> branches(){return BRANCHES;}
    private final R1EventFacts facts;
    private R1EventFacts.Task beforeTask;
    private R1EventFacts.Wait beforeWait;
    private Subject beforeCapture;
    private boolean beforeContactExists=true; // Absence must be observed for the locked Task.
    private boolean prepared;
    public R1EventPolicy(R1EventFacts facts){this.facts=facts;}

    /** Runtime calls once under root locks, before any Handler writes. Instance never crosses invocations. */
    public void beforeWork(Connection c,CommandEnvelope e,CommandHandler.Context context)throws SQLException {
        require(!prepared);prepared=true;
        if(facts==null)return; // A successful result still fails closed in validate.
        if(context.scope().taskId()!=null)beforeTask=facts.task(c,e.actor().tenantId(),context.scope().taskId());
        if(e.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT && beforeTask!=null)beforeContactExists=facts.contactExistsForTask(c,e.actor().tenantId(),beforeTask.selector().id());
        if(e.type().recovery())beforeWait=facts.latestWait(c,e.actor().tenantId(),context.scope().taskId());
        if(context.binding() instanceof CommandAuthorizationBinding.Capture b)beforeCapture=facts.capturedLead(c,e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest());
    }
    public void validate(Connection c,CommandEnvelope e,CommandHandler.Context context,CommandHandler.Result result)throws SQLException {
        String expectedType=switch(e.type()) {
            case CAPTURE_LEAD,COMPLETE_LEAD_INGRESS -> "lead.lead";
            case SAVE_ACTION_DRAFT -> "responsibility.action_draft";
            case REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS -> "responsibility.task_occurrence";
            case ASSIGN_LEAD -> "lead.lead_assignment";
            case RECORD_CONTACT_RESULT -> "lead.lead_contact_result";
            default -> "responsibility.decision_record";
        };
        require(expectedType.equals(result.fact().type()));
        if(result.status()!=CommandOutcome.Status.SUCCEEDED){require(result.notifications().isEmpty());return;}
        require(prepared && facts!=null);
        var tenant=e.actor().tenantId();var receipt=result.fact();
        var task=context.scope().taskId()==null?null:facts.task(c,tenant,context.scope().taskId());
        if(!R1CommandPolicy.dedicated(e.type()))require(task!=null && task.lead().equals(context.authorization().subject()));
        var expectedSources=new EnumMap<CommandHandler.Event,Subject>(CommandHandler.Event.class);
        String outcome;
        switch(e.type()) {
            case CAPTURE_LEAD -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Capture);
                var b=(CommandAuthorizationBinding.Capture)context.binding();
                require(beforeCapture==null && receipt.equals(facts.capturedLead(c,tenant,b.sourceAccountCode(),b.sourceRecordKeyDigest())));
                outcome="CREATED";
            }
            case SAVE_ACTION_DRAFT -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Draft && beforeTask!=null && task!=null);
                var b=(CommandAuthorizationBinding.Draft)context.binding();var draft=task.draft();
                require(beforeTask.selector().revision()==b.taskRevision());
                require(b.draftId()==null?beforeTask.draft()==null:beforeTask.draft()!=null && beforeTask.draft().selector().equals(new Subject("responsibility.action_draft",b.draftId(),b.draftRevision(),null)));
                require("OPEN".equals(beforeTask.state()) && sameTask(beforeTask,task) && beforeTask.selector().equals(task.selector()) && "OPEN".equals(task.state()) && task.completion()==null);
                require(draft!=null && receipt.equals(draft.selector()) && "DRAFT".equals(draft.state()) && draft.taskId().equals(b.taskId()) && draft.action().equals(b.actionCode().name()) && draft.version()==b.schemaVersion());
                require(draft.selector().revision()==(b.draftRevision()==null?0:CommandHandler.nextRevision(b.draftRevision())) && (b.draftId()==null || b.draftId().equals(draft.selector().id())));
                require(beforeTask.draft()==null || "DRAFT".equals(beforeTask.draft().state()) && beforeTask.draft().schema().equals(draft.schema()));
                outcome="CREATED_OR_CHANGED";
            }
            case REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Recovery && beforeTask!=null && task!=null && beforeWait!=null);
                var b=(CommandAuthorizationBinding.Recovery)context.binding();
                boolean contact=e.type()==CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS;
                require("WAITING".equals(beforeTask.state()) && "OPEN".equals(task.state()) && sameTask(beforeTask,task) && task.completion()==null && Objects.equals(beforeTask.draft(),task.draft()));
                require(receipt.equals(task.selector()) && task.selector().revision()==CommandHandler.nextRevision(beforeTask.selector().revision()) && beforeTask.selector().revision()==b.taskRevision());
                require(task.purpose().equals(contact?"CONTACT_LEAD":"RESOLVE_LEAD_ROUTING_GAP") && beforeWait.profile().equals(contact?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1") && beforeWait.version()==1);
                require(beforeWait.selector().equals(new Subject("responsibility.wait_receipt",b.waitReceiptId(),null,b.waitReceiptHash())) && beforeWait.taskRevision()==b.taskRevision() && beforeWait.equals(facts.latestWait(c,tenant,b.taskId())));
                outcome="WAITING_TO_OPEN";
            }
            case COMPLETE_LEAD_INGRESS -> {
                completed(task,beforeTask,e,receipt);
                require(receipt.equals(facts.lead(c,tenant,task.lead().id())) && receipt.revision()==CommandHandler.nextRevision(task.lead().revision()));
                outcome="INGRESS_COMPLETED";
            }
            case ASSIGN_LEAD -> {
                completed(task,beforeTask,e,receipt);var assignment=facts.assignment(c,tenant,receipt.id());
                require(assignment!=null && receipt.equals(assignment.selector()) && receipt.revision()==0 && assignment.leadId().equals(task.lead().id()));
                require(context.scope().canonical().equals(CommandScope.task(tenant,e.type(),task.selector().id(),task.lead(),Map.of("selectedOwnerAppointmentId",assignment.owner())).canonical()));
                outcome="ASSIGNED";
            }
            case RECORD_CONTACT_RESULT -> {
                completed(task,beforeTask,e,receipt);var contact=facts.contact(c,tenant,receipt.id());
                require(contact!=null && receipt.equals(contact.selector()) && contact.taskId().equals(task.selector().id()) && contact.leadId().equals(task.lead().id()) && contact.contactNo()>=1 && contact.contactNo()<=3);
                var assignment=facts.assignment(c,tenant,contact.assignmentId());
                require(assignment!=null && assignment.leadId().equals(contact.leadId()) && assignment.owner().equals(task.owner()));
                require(context.scope().canonical().equals(CommandScope.task(tenant,e.type(),task.selector().id(),task.lead(),Map.of("leadAssignmentId",assignment.selector().id(),"leadAssignmentRevision",assignment.selector().revision())).canonical()));
                var opportunity=facts.opportunityForContact(c,tenant,contact.selector().id());
                outcome=contact.code();
                if("CONNECTED_VALID".equals(outcome)) {
                    require(!beforeContactExists);
                    require(opportunity!=null && opportunity.selector().revision()==0 && opportunity.leadId().equals(contact.leadId()) && opportunity.assignmentId().equals(contact.assignmentId()) && opportunity.contactId().equals(contact.selector().id()) && opportunity.owner().equals(assignment.owner()));
                    var beforeDraft=beforeTask.draft();var draft=task.draft();
                    require(beforeDraft!=null && "DRAFT".equals(beforeDraft.state()) && draft!=null && "CONFIRMED".equals(draft.state()));
                    require(beforeDraft.selector().id().equals(draft.selector().id()) && beforeDraft.selector().revision()!=null && draft.selector().revision()==CommandHandler.nextRevision(beforeDraft.selector().revision()));
                    expectedSources.put(OpportunityOpened,opportunity.selector());
                } else {
                    require(opportunity==null);
                    if("NOT_CONNECTED".equals(outcome))outcome=contact.contactNo()==3?"NOT_CONNECTED_EXHAUSTED":"NOT_CONNECTED_RETRY";
                }
            }
            default -> {
                completed(task,beforeTask,e,receipt);var decision=facts.decision(c,tenant,receipt.id());
                require(decision!=null && receipt.equals(decision.selector()) && decision.taskId().equals(task.selector().id()) && decision.subject().equals(task.lead()) && decision.version()==1);
                String contract=switch(e.type()) {
                    case RESOLVE_DUPLICATE_LEAD -> "LEAD_DUPLICATE_RESOLUTION";
                    case RECORD_ROUTING_DISPOSITION -> "LEAD_ROUTING_DISPOSITION";
                    case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED";
                    case REVIEW_LEAD_VALIDITY -> "LEAD_VALIDITY_REVIEW";
                    default -> throw violation();
                };
                require(contract.equals(decision.contract()));outcome=decision.code();
            }
        }
        String branchOutcome=outcome;
        var branch=BRANCHES.stream().filter(b->b.command()==e.type() && b.outcome().equals(branchOutcome)).findFirst().orElseThrow(R1EventPolicy::violation);
        for(var event:branch.events())expectedSources.putIfAbsent(event,receipt);
        require(result.notifications().size()==expectedSources.size());
        var seen=EnumSet.noneOf(CommandHandler.Event.class);
        for(var n:result.notifications()) {
            require(seen.add(n.event()) && n.sourceFact().equals(expectedSources.get(n.event())) && n.event().sourceFactType().equals(n.sourceFact().type()));
            require(n.event().sourceSelector().startsWith("hash:")?n.sourceFact().hash()!=null:n.sourceFact().revision()!=null);
        }
        require(seen.equals(branch.events()));
    }
    private static void completed(R1EventFacts.Task task,R1EventFacts.Task before,CommandEnvelope e,Subject receipt)throws SQLException {
        require(task!=null && before!=null && "OPEN".equals(before.state()) && "DONE".equals(task.state()) && e.type().name().equals(task.primaryCommand()) && receipt.equals(task.completion()) && sameTask(before,task) && task.selector().revision()==CommandHandler.nextRevision(before.selector().revision()));
        require(task.owner().equals(e.actor().onBehalfAppointmentId()==null?e.actor().appointmentId():e.actor().onBehalfAppointmentId()));
    }
    private static boolean sameTask(R1EventFacts.Task before,R1EventFacts.Task after) {
        return before.selector().id().equals(after.selector().id()) && before.lead().equals(after.lead()) && before.owner().equals(after.owner()) && before.purpose().equals(after.purpose()) && before.primaryCommand().equals(after.primaryCommand()) && before.slaCode().equals(after.slaCode()) && before.slaSeconds()==after.slaSeconds() && before.slaDue().equals(after.slaDue());
    }
    private static void require(boolean condition)throws SQLException {if(!condition)throw violation();}
    private static SQLException violation(){return new SQLException("R1 persisted event contract violation","22000");}
}
