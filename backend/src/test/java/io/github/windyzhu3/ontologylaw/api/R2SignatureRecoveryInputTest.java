package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.worker.InternalApiClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class R2SignatureRecoveryInputTest {
    @Test void readiness_and_authority_return_keep_distinct_exact_scope_types(){
        for(String kind:List.of("SIGNATURE_READINESS","SIGNATURE_AUTHORITY_RETURN")){
            UUID opportunity=UUID.randomUUID(),source=UUID.randomUUID();
            var payload=new LinkedHashMap<String,Object>();
            payload.put("opportunityId",opportunity.toString());payload.put("expectedOpportunityRevision",0L);
            payload.put("responsibilityBasis",Map.of("id",opportunity.toString(),"revision",0L));
            payload.put("sourceKind",kind);payload.put("source",Map.of("id",source.toString(),"revision",0L));
            payload.put("expectedWorkflow",kind.endsWith("RETURN")?Map.of("id",source.toString(),"revision",0L):null);
            var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE);
            var envelope=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),actor,payload);
            var binding=R2ContractRecoveryCommand.input(envelope);
            assertEquals(kind,binding.sourceKind());
            assertEquals(kind.endsWith("RETURN")?"contract.signature_workflow":"contract.signature_readiness",binding.source().type());
            assertDoesNotThrow(()->new InternalApiClient.ContractPreparationCandidate(ContractPreparationDiscovery.commandId(actor.tenantId(),binding),opportunity,0,opportunity,0,source,kind.endsWith("RETURN")?source:null,kind));
            if(kind.endsWith("RETURN")){
                payload.put("expectedWorkflow",Map.of("id",UUID.randomUUID().toString(),"revision",0L));
                assertThrows(CommandHandler.Rejected.class,()->R2ContractRecoveryCommand.input(new CommandEnvelope(envelope.type(),UUID.randomUUID(),UUID.randomUUID(),actor,payload)));
            }
        }
    }
}
