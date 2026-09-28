package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2ContractQuoteSourceIT extends R2QuoteWorkflowIT {
    boolean shortValidity;
    @Override Map<String,Object> commercial() {
        var values=new LinkedHashMap<String,Object>(super.commercial());
        if(shortValidity)values.put("validUntil",now().plusSeconds(5).toString());
        return values;
    }
    ContractPreparationSource.Basis basis()throws Exception {
        UUID confirmation=UUID.fromString((String)((Map<?,?>)context().get("customerConfirmation")).get("id"));
        var terms=new ContractVersionInput.CommercialTerms("CNY","保密服务范围",List.of(new ContractVersionInput.FeeLine("服务费",10000,false)),null,"先付全部服务费");
        return new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,terms.digest());
    }
    UUID reply(String code)throws Exception {
        var evidence=delivered();var values=new LinkedHashMap<String,Object>();values.put("kind",code);
        values.put("statement","客户准确原意");values.put("occurredAt",now().toString());values.put("evidence",R2CustomerRequirementsServices.selector(evidence));
        if(!"ACCEPTED".equals(code))values.put("nextCheckAt",now().plusSeconds(3600).toString());
        return quoteCommand("RECORD_QUOTE_RESPONSE",values).id();
    }
    ContractPreparationSource resolve(UUID response,ContractPreparationSource.Basis basis)throws Exception {
        try(var c=database.apiConnection()) {
            return inTransaction(c,Capability.COMMAND,x->R2ContractPreparationSources.create(cipher)
                    .resolve(x,basis,new ContractPreparationSources.AcceptedResponse(response)));
        }
    }
    @Test void real_t07_acceptance_resolves_exact_quote_evidence_and_commercial_terms()throws Exception {
        UUID response=reply("ACCEPTED");var expected=basis();
        var result=assertInstanceOf(ContractPreparationSource.AcceptedQuote.class,resolve(response,expected));
        assertEquals(response,result.responseId());assertEquals(expected,result.basis());
        assertEquals(result.quoteRevisionId().toString(),scalar("select i.quote_revision_id from opportunity.quote_issue i join opportunity.quote_response r on r.tenant_id=i.tenant_id and r.quote_issue_id=i.quote_issue_id where r.tenant_id=? and r.quote_response_id=?",seed.tenant(),response));
        assertEquals(result.evidenceVersionId().toString(),scalar("select material_version_id from opportunity.quote_response_basis where tenant_id=? and quote_response_id=?",seed.tenant(),response));
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(response,new ContractPreparationSource.Basis(expected.tenantId(),expected.opportunityId(),expected.customerConfirmationId(),"ab".repeat(32))));
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(UUID.randomUUID(),expected));
        assertEquals("0",scalar("select count(*) from contract.contract where tenant_id=?",seed.tenant()));
    }
    @Test void non_acceptance_never_becomes_contract_source()throws Exception {
        UUID response=reply("NOT_ACCEPTED");assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(response,basis()));
    }
    @Test void lawful_acceptance_survives_natural_quote_expiry()throws Exception {
        shortValidity=true;UUID response=reply("ACCEPTED");var expected=basis();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{
                try(var p=x.prepareStatement("select pg_sleep(greatest(0,extract(epoch from q.valid_until-clock_timestamp()))+0.02) from opportunity.quote_revision q where tenant_id=? and opportunity_id=?")) {
                    p.setObject(1,seed.tenant());p.setObject(2,opportunity.id());p.execute();
                }return null;
            });
        }
        assertEquals("true",scalar("select (valid_until<clock_timestamp())::text from opportunity.quote_revision where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        assertInstanceOf(ContractPreparationSource.AcceptedQuote.class,resolve(response,expected));
    }
    @Test void quote_body_integrity_is_checked_before_extracting_terms()throws Exception {
        UUID response=reply("ACCEPTED");var expected=basis();
        var altered=new io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection() {
            public byte[] encrypt(UUID t,UUID o,UUID f,String clear){throw new UnsupportedOperationException();}
            public String decrypt(UUID t,UUID o,UUID f,byte[] encrypted){throw new UnsupportedOperationException();}
            public String decryptQuote(UUID t,UUID o,UUID f,boolean draft,byte[] encrypted) {
                return cipher.decryptQuote(t,o,f,draft,encrypted).replace("10000","20000");
            }
        };
        try(var c=database.apiConnection()) {
            var error=assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->
                    R2ContractPreparationSources.create(altered).resolve(x,expected,new ContractPreparationSources.AcceptedResponse(response))));
            assertEquals("Contract quote source integrity failure",error.getMessage());
            assertFalse(error.getMessage().contains("服务"));
        }
    }
    @Test void revoked_acceptance_proof_cannot_start_contract_preparation()throws Exception {
        UUID response=acceptWithSeparateProof();var expected=basis();
        mutate("update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=decode(repeat('ab',32),'hex'),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id in (select v.evidence_binding_id from opportunity.material_version v join opportunity.quote_response_basis b on b.tenant_id=v.tenant_id and b.material_version_id=v.material_version_id where b.tenant_id=? and b.quote_response_id=?)",seed.appointment(),seed.tenant(),seed.tenant(),response);
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(response,expected));
    }
    UUID acceptWithSeparateProof()throws Exception {
        // The inherited upload fixture carries the initial opportunity revision. Forming the quote
        // advances that revision, so create both independent proof objects before the quote package.
        setup(true,true);confirmed();var delivery=material();var proof=material();
        assertNotEquals(delivery.id(),proof.id());
        policy("SELF_AUTHORIZED",List.of(seed.appointment()));
        quoteCommand("FORM_QUOTE",commercial());
        quoteCommand("RECORD_QUOTE_DELIVERY",Map.of("recipient","客户联系人",
                "recipientParticipation",((Map<?,?>)((List<?>)context().get("recipients")).getFirst()).get("selector"),
                "channel","EMAIL","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(delivery)));
        return quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","客户准确原意","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(proof))).id();
    }
    @Test void revoked_delivery_proof_cannot_start_contract_preparation()throws Exception {
        UUID response=acceptWithSeparateProof();var expected=basis();
        mutate("update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=decode(repeat('ab',32),'hex'),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id in (select v.evidence_binding_id from opportunity.material_version v join opportunity.quote_manual_delivery d on d.tenant_id=v.tenant_id and d.material_version_id=v.material_version_id where d.tenant_id=?)",seed.appointment(),seed.tenant(),seed.tenant());
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(response,expected));
    }
}
