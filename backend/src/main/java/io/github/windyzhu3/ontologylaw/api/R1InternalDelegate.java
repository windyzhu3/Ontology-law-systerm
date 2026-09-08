package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.InternalTaskCommandsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Only the mTLS filter may supply this SERVICE Actor; no request body can select Tenant or authority. */
@RestController
public class R1InternalDelegate implements InternalTaskCommandsApi {
    private final ObjectProvider<R1ApiServices> services;
    public R1InternalDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(){var value=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(value.principalKind()!=PrincipalKind.SERVICE||value.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return value;}
    // Generated produces only lists error media for bodyless204; do not negotiate a nonexistent success representation.
    @org.springframework.web.bind.annotation.RequestMapping(method=org.springframework.web.bind.annotation.RequestMethod.GET,value=PATH_CHECK_R1_PROJECTION_READINESS)
    public ResponseEntity<Void> checkR1ProjectionReadiness(){var result=service().readiness(actor());if(result.errorCode()!=null)throw new R1HttpFailure(result.errorCode());return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    @org.springframework.web.bind.annotation.RequestMapping(method=org.springframework.web.bind.annotation.RequestMethod.POST,value=PATH_CONSUME_R1_PROJECTION,consumes="application/json")
    public ResponseEntity<Void> consumeR1Projection(ConsumeR1ProjectionV1 body){var result=service().consume(actor(),body);if(result.errorCode()!=null)throw new R1HttpFailure(result.errorCode());return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    public ResponseEntity<DueR1TaskPageV1> listDueR1Tasks(RecoveryTypeV1 type,Integer limit,String cursor){var result=service().due(actor(),type,limit,cursor);if(result.errorCode()!=null)throw new R1HttpFailure(result.errorCode());return ResponseEntity.ok().header("Cache-Control","no-store").body(result.page());}
    public ResponseEntity<TaskOccurrenceCommandReceipt> reopenDueContactTasks(UUID key,ReopenDueContactTaskV1 body){return recover(CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,key,body);}
    public ResponseEntity<TaskOccurrenceCommandReceipt> reopenDueRoutingReviewTasks(UUID key,ReopenDueRoutingReviewTaskV1 body){return recover(CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS,key,body);}
    private ResponseEntity<TaskOccurrenceCommandReceipt> recover(CommandEnvelope.Type type,UUID key,Object body){
        var result=service().command(new CommandEnvelope(type,key,UUID.randomUUID(),actor(),R1WireModels.payload(body)));if(result.errorCode()!=null)throw new R1HttpFailure(result.errorCode(),result.receiptRef());
        return ResponseEntity.ok().header("Cache-Control","no-store").header("ETag",result.etag()).header("Location","/api/v1/commands/"+key+"/receipt").body(R1WireModels.model(result.body(),TaskOccurrenceCommandReceipt.class));
    }
}
