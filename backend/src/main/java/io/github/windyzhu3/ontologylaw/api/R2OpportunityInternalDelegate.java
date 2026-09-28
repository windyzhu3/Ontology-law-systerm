package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.InternalOpportunityTasksApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Named internal operations; only verified mTLS supplies the SERVICE identity. */
@RestController
public class R2OpportunityInternalDelegate implements InternalOpportunityTasksApi {
    private final ObjectProvider<R1ApiServices> services;
    public R2OpportunityInternalDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return service;}
    private Actor actor(){var actor=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    public ResponseEntity<R2OpportunityTaskPageV1> listR2OpportunityTaskCandidates(R2OpportunityTaskKindV1 kind,Integer limit,String cursor){
        var response=service().opportunityCandidates(actor(),R2OpportunityDiscoveryService.Kind.valueOf(kind.getValue()),limit==null?50:limit,cursor);
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode());
        var page=new R2OpportunityTaskPageV1();
        for(var candidate:response.page().candidates()){
            var row=new TreeMap<String,Object>();row.put("kind",candidate.kind().name());row.put("idempotencyKey",candidate.commandId().toString());row.put("opportunityId",candidate.opportunity().id().toString());row.put("expectedOpportunityRevision",candidate.opportunity().revision());
            if(candidate.kind()==R2OpportunityDiscoveryService.Kind.INITIAL)page.addCandidatesItem(R1WireModels.model(row,R2InitialOpportunityTaskCandidateV1.class));
            else {
                row.put("taskId",candidate.task().id().toString());row.put("expectedTaskRevision",candidate.task().revision());row.put("waitReceiptId",candidate.waitReceipt().id().toString());row.put("waitReceiptHash",candidate.waitReceipt().hash());row.put("progressId",candidate.progress().id().toString());row.put("progressHash",candidate.progress().hash());row.put("dueCutoff",candidate.due().toString());
                page.addCandidatesItem(R1WireModels.model(row,R2DueOpportunityTaskCandidateV1.class));
            }
        }
        page.setNextCursor(response.page().nextCursor());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(page);
    }
    public ResponseEntity<TaskOccurrenceCommandReceipt> activateInitialOpportunityTask(UUID key,ActivateInitialOpportunityTaskV1 body){return command(CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,key,body);}
    public ResponseEntity<TaskOccurrenceCommandReceipt> reopenDueOpportunityTask(UUID key,ReopenDueOpportunityTaskV1 body){return command(CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,key,body);}
    public ResponseEntity<ContractPreparationCandidatePageV1> listContractPreparationCandidates(Integer limit,String cursor){return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(service().contractCandidates(actor(),limit==null?50:limit,cursor),ContractPreparationCandidatePageV1.class));}
    public ResponseEntity<ContractPreparationReconcileReceiptV1> reconcileContractPreparation(UUID key,ReconcileContractPreparationV1 body){var p=new TreeMap<String,Object>((Map<String,Object>)R1WireModels.payload(body));p.putIfAbsent("expectedWorkflow",null);var response=service().contractCommand(new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),actor(),p));if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode());return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(response.body(),ContractPreparationReconcileReceiptV1.class));}
    private ResponseEntity<TaskOccurrenceCommandReceipt> command(CommandEnvelope.Type type,UUID key,Object body){
        var response=service().opportunityCommand(new CommandEnvelope(type,key,UUID.randomUUID(),actor(),R1WireModels.payload(body)));
        // These service receipts are recovered by replay, never through the human receipt GET route.
        if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(response.body(),TaskOccurrenceCommandReceipt.class));
    }
}
