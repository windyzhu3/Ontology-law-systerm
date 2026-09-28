package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.OpportunityOwnerExceptionsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
/** Typed management surface; exact command inputs retain meaningful explicit null preconditions. */
@RestController
public class R2OpportunityOwnerExceptionApiDelegate implements OpportunityOwnerExceptionsApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OpportunityOwnerExceptionApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(UUID behalf){var value=SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(behalf!=null||!(value instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    private <T>ResponseEntity<T> read(String operation,UUID id,Long revision,Integer limit,String cursor,UUID behalf,Class<T> type){
        var value=service().ownerExceptions(actor(behalf),operation,id,revision,limit==null?20:limit,cursor);var response=ResponseEntity.ok().header("Cache-Control","no-store");if(value.get("etag") instanceof String etag)response.header("ETag",etag);return response.body(R1WireModels.model(value,type));
    }
    public ResponseEntity<OwnerExceptionPageV1> listOpportunityOwnerExceptions(UUID own,UUID behalf,Integer limit,String cursor){return read("list",null,null,limit,cursor,behalf,OwnerExceptionPageV1.class);}
    public ResponseEntity<OwnerExceptionOperationsPageV1> listOpportunityOwnerExceptionOperations(UUID own,UUID behalf,Integer limit,String cursor){return read("operations",null,null,limit,cursor,behalf,OwnerExceptionOperationsPageV1.class);}
    public ResponseEntity<OwnerExceptionDetailV1> getOpportunityOwnerException(UUID id,UUID own,UUID behalf){return read("detail",id,null,null,null,behalf,OwnerExceptionDetailV1.class);}
    public ResponseEntity<OwnerExceptionReceiverPageV1> listOpportunityOwnerExceptionReceivers(UUID id,Long revision,UUID own,UUID behalf,Integer limit,String cursor){return read("candidates",id,revision,limit,cursor,behalf,OwnerExceptionReceiverPageV1.class);}
    public ResponseEntity<OwnerExceptionCommandReceiptV1> transferOpportunityResponsibility(UUID key,TransferOpportunityResponsibilityV1 body,UUID own,UUID behalf){return command(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,key,body,behalf);}
    public ResponseEntity<OwnerExceptionCommandReceiptV1> recordOpportunityOwnerCoordination(UUID key,RecordOpportunityOwnerCoordinationV1 body,UUID own,UUID behalf){return command(CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION,key,body,behalf);}
    private ResponseEntity<OwnerExceptionCommandReceiptV1> command(CommandEnvelope.Type type,UUID key,Object body,UUID behalf){
        var payload=new TreeMap<String,Object>((Map<String,Object>)R1WireModels.payload(body));payload.putIfAbsent("expectedTask",null);payload.putIfAbsent("expectedWait",null);
        var envelope=new CommandEnvelope(type,key,UUID.randomUUID(),actor(behalf),payload);var response=service().opportunityCommand(envelope);
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode(),response.receiptRef(),response.currentETag(),null);
        return ResponseEntity.status(response.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+key+"/receipt").body(R1WireModels.model(response.body(),OwnerExceptionCommandReceiptV1.class));
    }
}
