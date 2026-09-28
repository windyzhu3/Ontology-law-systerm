package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.InternalOpportunityOwnerExceptionsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Certificate-bound observation only; discovery never mutates business state. */
@RestController
public class R2OwnerExceptionInternalDelegate implements InternalOpportunityOwnerExceptionsApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OwnerExceptionInternalDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(){
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");
        return actor;
    }
    public ResponseEntity<OwnerExceptionObservationPageV1> listOpportunityOwnerExceptionCandidates(Integer limit,String cursor){
        var response=service().ownerExceptionCandidates(actor(),limit==null?20:limit,cursor);
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode());
        var page=new OwnerExceptionObservationPageV1();
        for(var candidate:response.page().candidates())page.addCandidatesItem(R1WireModels.model(Map.of(
            "kind","OWNER_EXCEPTION","idempotencyKey",candidate.idempotencyKey().toString(),"opportunityId",candidate.opportunityId().toString(),"expectedOpportunityRevision",candidate.expectedOpportunityRevision()),OwnerExceptionObservationCandidateV1.class));
        page.setNextCursor(response.page().nextCursor());
        if(response.page().diagnostics()>0)page.setDiagnostics(response.page().diagnostics());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(page);
    }
    public ResponseEntity<OwnerExceptionCommandReceiptV1> observeOpportunityOwnerException(UUID key,ObserveOpportunityOwnerExceptionV1 body){
        var response=service().opportunityCommand(new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,key,UUID.randomUUID(),actor(),R1WireModels.payload(body)));
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(response.body(),OwnerExceptionCommandReceiptV1.class));
    }
}
