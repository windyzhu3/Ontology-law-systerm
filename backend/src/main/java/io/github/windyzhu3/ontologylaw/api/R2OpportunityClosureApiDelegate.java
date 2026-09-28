package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.OpportunityClosureApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Separate typed terminal surface; exact null task/wait observations are command preconditions. */
@RestController
public class R2OpportunityClosureApiDelegate implements OpportunityClosureApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OpportunityClosureApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(UUID behalf){var authentication=SecurityContextHolder.getContext().getAuthentication();if(authentication==null||behalf!=null||!(authentication.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    @Override public ResponseEntity<OpportunityCloseContextV1> getOpportunityClosure(UUID id,UUID own,UUID behalf){
        var value=service().opportunityClosure(actor(behalf),id);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(value,OpportunityCloseContextV1.class));
    }
    @Override public ResponseEntity<OpportunityCloseCommandReceiptV1> closeOpportunity(UUID id,UUID key,CloseOpportunityV1 body,UUID own,UUID behalf){
        var payload=new TreeMap<String,Object>((Map<String,Object>)R1WireModels.payload(body));payload.put("opportunityId",id.toString());payload.putIfAbsent("expectedTask",null);payload.putIfAbsent("expectedWait",null);
        var response=service().opportunityCommand(new CommandEnvelope(CommandEnvelope.Type.CLOSE_OPPORTUNITY,key,UUID.randomUUID(),actor(behalf),payload));
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode(),response.receiptRef(),null,null);
        return ResponseEntity.status(response.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+key+"/receipt").body(R1WireModels.model(response.body(),OpportunityCloseCommandReceiptV1.class));
    }
}
