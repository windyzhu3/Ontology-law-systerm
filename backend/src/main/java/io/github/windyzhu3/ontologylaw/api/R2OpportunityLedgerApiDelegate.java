package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.OpportunitiesApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Read-only normal ledger. Handling remains the existing selected-task work card. */
@RestController
public class R2OpportunityLedgerApiDelegate implements OpportunitiesApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OpportunityLedgerApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(UUID behalf){
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||behalf!=null||!(authentication.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");
        return actor;
    }
    private <T> ResponseEntity<T> read(String operation,UUID id,Integer limit,String cursor,String search,String state,UUID behalf,Class<T> type){
        var actor=actor(behalf);
        var value=service().opportunities(actor,operation,id,limit==null?20:limit,cursor,search,state);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(value,type));
    }
    @Override public ResponseEntity<OpportunityLedgerDetailV1> getOpportunityTaskContinuation(UUID id,UUID own,UUID behalf){return read("task",id,null,null,null,null,behalf,OpportunityLedgerDetailV1.class);}
    @Override public ResponseEntity<OpportunityLedgerPageV1> listOpportunities(UUID own,UUID behalf,Integer limit,String cursor,String search,String state){return read("list",null,limit,cursor,search,state,behalf,OpportunityLedgerPageV1.class);}
    @Override public ResponseEntity<OpportunityLedgerDetailV1> getOpportunity(UUID id,UUID own,UUID behalf){return read("detail",id,null,null,null,null,behalf,OpportunityLedgerDetailV1.class);}
}
