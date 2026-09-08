package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;

/** Frozen generated wire interfaces; application transactions live in the composed services.
 * Task9.1 accepts the generated selection-header signature only. Trusted selection
 * validation remains security-resolver work for Task9.2; handlers never treat it as authority.
 */
@RestController
public class R1ApiDelegate implements CommandReceiptsApi,LeadsApi,TaskCommandsApi,TaskDraftsApi,WorkbenchApi {
    private final ObjectProvider<R1ApiServices> services;
    public R1ApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(){return (Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    public ResponseEntity<CommandReceipt> getCommandReceipt(UUID commandId,UUID xAppointmentId){
        var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        var actor=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var result=service.receipt(actor,commandId,UUID.randomUUID());
        if(result.status()!=200)throw new R1HttpFailure(result.errorCode());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.receipt(result.body()));
    }
    public ResponseEntity<LeadCommandReceipt> captureLead(UUID key,CaptureLeadV1 body,UUID xAppointmentId){
        return command(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,key,UUID.randomUUID(),actor(),R1WireModels.payload(body)),LeadCommandReceipt.class);
    }
    public ResponseEntity<CurrentWorkCardEnvelope> getCurrentWorkCard(String ifNoneMatch,UUID xAppointmentId){
        requireHuman();var value=service().card(actor(),UUID.randomUUID(),ifNoneMatch);if(value.errorCode()!=null)throw new R1HttpFailure(value.errorCode());
        var response=ResponseEntity.status(value.status()).header("Cache-Control",value.cacheControl()).header("Vary",value.vary()).header("ETag",value.etag());
        return value.status()==304?response.build():response.body(R1WireModels.model(value.body(),CurrentWorkCardEnvelope.class));
    }
    public ResponseEntity<ActionDraftWriteResult> saveActionDraft(UUID task,UUID key,SaveActionDraftV1 body,String match,String none,UUID xAppointmentId){
        requireHuman();return command(new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,key,UUID.randomUUID(),actor(),R1WireModels.payload(body),null,new CommandEnvelope.DraftPrecondition(task,match,none)),ActionDraftWriteResult.class);
    }
    public ResponseEntity<DecisionRecordCommandReceipt> acknowledgeSourceIntakeStopRequest(UUID task,UUID key,String match,AcknowledgeSourceIntakeStopRequestV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,task,key,match,body,DecisionRecordCommandReceipt.class);}
    public ResponseEntity<LeadAssignmentCommandReceipt> assignLead(UUID task,UUID key,String match,AssignLeadV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.ASSIGN_LEAD,task,key,match,body,LeadAssignmentCommandReceipt.class);}
    public ResponseEntity<LeadCommandReceipt> completeLeadIngress(UUID task,UUID key,String match,CompleteLeadIngressV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,task,key,match,body,LeadCommandReceipt.class);}
    public ResponseEntity<LeadContactResultCommandReceipt> recordContactResult(UUID task,UUID key,String match,RecordContactResultV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.RECORD_CONTACT_RESULT,task,key,match,body,LeadContactResultCommandReceipt.class);}
    public ResponseEntity<DecisionRecordCommandReceipt> recordRoutingDisposition(UUID task,UUID key,String match,RecordRoutingDispositionV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,task,key,match,body,DecisionRecordCommandReceipt.class);}
    public ResponseEntity<DecisionRecordCommandReceipt> resolveDuplicateLead(UUID task,UUID key,String match,ResolveDuplicateLeadV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,task,key,match,body,DecisionRecordCommandReceipt.class);}
    public ResponseEntity<DecisionRecordCommandReceipt> reviewLeadValidity(UUID task,UUID key,String match,ReviewLeadValidityV1 body,UUID xAppointmentId){return primary(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,task,key,match,body,DecisionRecordCommandReceipt.class);}
    private void requireHuman(){if(actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN)throw new R1HttpFailure("NOT_AUTHORIZED");}
    private <T> ResponseEntity<T> primary(CommandEnvelope.Type type,UUID task,UUID key,String match,Object body,Class<T> model){
        requireHuman();return command(new CommandEnvelope(type,key,UUID.randomUUID(),actor(),R1WireModels.payload(body),new CommandEnvelope.TaskPrecondition(task,match)),model);
    }
    private <T> ResponseEntity<T> command(CommandEnvelope envelope,Class<T> model){
        var value=service().command(envelope);if(value.errorCode()!=null)throw new R1HttpFailure(value.errorCode(),value.receiptRef(),value.currentETag(),null);
        var response=ResponseEntity.status(value.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+envelope.commandId()+"/receipt");
        if(value.etag()!=null)response.header("ETag",value.etag());
        return response.body(R1WireModels.model(value.body(),model));
    }
}
