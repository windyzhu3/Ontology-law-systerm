package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.R2OpportunityCommandsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
/** Typed transport only; the shared command runtime owns business writes and recovery. */
@RestController
public class R2OpportunityApiDelegate implements R2OpportunityCommandsApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OpportunityApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private Actor actor(){var actor=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    public ResponseEntity<OpportunityProgressDraftWriteResultV1> saveOpportunityProgressDraft(UUID task,UUID key,SaveOpportunityProgressDraftV1 body,String match,String none,UUID appointment,UUID onBehalf){
        return command(new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,key,UUID.randomUUID(),actor(),R1WireModels.payload(body),null,new CommandEnvelope.DraftPrecondition(task,match,none)),OpportunityProgressDraftWriteResultV1.class);
    }
    public ResponseEntity<OpportunityProgressCommandReceiptV1> recordOpportunityProgress(UUID task,UUID key,String match,RecordOpportunityProgressV1 body,UUID appointment,UUID onBehalf){
        return command(new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,key,UUID.randomUUID(),actor(),R1WireModels.payload(body),new CommandEnvelope.TaskPrecondition(task,match)),OpportunityProgressCommandReceiptV1.class);
    }
    private <T> ResponseEntity<T> command(CommandEnvelope command,Class<T> type){
        var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        var value=service.opportunityCommand(command);if(value.errorCode()!=null)throw new R1HttpFailure(value.errorCode(),value.receiptRef(),value.currentETag(),null);
        var response=ResponseEntity.status(value.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+command.commandId()+"/receipt");
        if(value.etag()!=null)response.header("ETag",value.etag());return response.body(R1WireModels.model(value.body(),type));
    }
}
