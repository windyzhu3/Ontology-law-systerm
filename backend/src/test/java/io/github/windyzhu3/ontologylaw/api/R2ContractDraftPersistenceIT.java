package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2ContractDraftPersistenceIT extends R2ContractPreparationPersistenceIT {
    final ContractProtection protectedBodies=ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    final ContractVersionInput.CommercialTerms terms=new ContractVersionInput.CommercialTerms("CNY","诉前协商服务",List.of(new ContractVersionInput.FeeLine("服务费",20000,false)),null,"签署后按约支付");
    ContractPreparationRepository repository() {
        return ContractPreparationRepository.databaseBacked(protectedBodies,R2ContractPreparationSources.create(cipher),new ContractPreparationRepository.Codec() {
            public String encode(Map<String,Object> value){return ContractCanonicalJson.encode(value);}
            @SuppressWarnings("unchecked") public Map<String,Object> decode(String body){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(body,Map.class);}
        });
    }
    ContractPreparationRepository.Basis currentBasis(){return new ContractPreparationRepository.Basis(opportunity,opportunity,seed.appointment(),confirmation);}
    Subject newRequest(UUID previous)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->repository().request(x,seed.tenant(),currentBasis(),previous,terms,"客户要求直接准备合同"));}
    }
    Subject approve(Subject request)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->repository().decide(x,seed.tenant(),request.id(),seed.appointment(),true,null,"授权本次范围和收费"));}
    }
    Subject saveDraft(Subject authorization,UUID previous)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->repository().saveDraft(x,seed.tenant(),currentBasis(),new ContractPreparationSources.DirectDecision(authorization.id()),previous,terms,Map.of("signingRequirements","双方盖章")));}
    }
    @Test void request_decision_and_draft_are_protected_and_recoverable_without_completing_work()throws Exception {
        initialize();var request=newRequest(null);var authorization=approve(request);var draft=saveDraft(authorization,null);
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var saved=repository().readDraft(x,seed.tenant(),draft.id());assertNotNull(saved);
            assertEquals("双方盖章",saved.preparation().get("signingRequirements"));assertEquals(terms.digest(),saved.commercialDigest());
            assertFalse(saved.toString().contains("盖章"));return null;
        });}
        assertEquals("0",scalar("select count(*) from contract.contract where tenant_id=?",seed.tenant()));
        assertThrows(Exception.class,()->mutate("update contract.preparation_draft set body_digest=decode(repeat('00',32),'hex') where tenant_id=? and preparation_draft_id=?",seed.tenant(),draft.id()));
    }
    @Test void draft_successor_is_exact_and_stale_authorization_does_not_save()throws Exception {
        initialize();var first=newRequest(null);var authorization=approve(first);var draft=saveDraft(authorization,null);
        var next=saveDraft(authorization,draft.id());assertNotEquals(draft.id(),next.id());
        assertThrows(Exception.class,()->saveDraft(authorization,draft.id()));
        newRequest(first.id());assertThrows(ContractPreparationSources.Unavailable.class,()->saveDraft(authorization,next.id()));
    }
    @Test void draft_write_rolls_back_with_the_caller_transaction()throws Exception {
        initialize();var authorization=approve(newRequest(null));
        try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{repository().saveDraft(x,seed.tenant(),currentBasis(),new ContractPreparationSources.DirectDecision(authorization.id()),null,terms,Map.of());throw new IllegalStateException("audit failed");}));}
        assertEquals("0",scalar("select count(*) from contract.preparation_draft where tenant_id=?",seed.tenant()));
    }
    @Test void metadata_proves_exact_creator_and_top_transaction()throws Exception {
        initialize();Subject result;
        try(var c=database.apiConnection()){result=inTransaction(c,Capability.COMMAND,x->{
            var created=repository().request(x,seed.tenant(),currentBasis(),null,terms,"直接准备申请");
            var metadata=ContractWorkflowService.metadata(x,seed.tenant(),created);
            assertNotNull(metadata);assertTrue(metadata.createdInCurrentTransaction());assertEquals(seed.appointment(),metadata.actorAppointmentId());
            assertEquals(opportunity.id(),metadata.opportunityId());
            assertNull(ContractWorkflowService.metadata(x,seed.tenant(),new Subject(created.type(),created.id(),1L,null)));
            return created;
        });}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertFalse(ContractWorkflowService.metadata(x,seed.tenant(),result).createdInCurrentTransaction());return null;});}
    }
}
