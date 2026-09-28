package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractPreparationSourceTest {
    private static final Instant NOW=Instant.parse("2026-09-21T10:00:00Z");
    private static final String HASH="ab".repeat(32);
    private final ContractPreparationSource.Basis basis=new ContractPreparationSource.Basis(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),HASH);
    private ContractPreparationSource.AcceptedQuote quote(Instant accepted) {
        return new ContractPreparationSource.AcceptedQuote(basis,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),NOW.minusSeconds(100),accepted,NOW.minusSeconds(10),NOW.minusSeconds(5));
    }
    @Test void historical_legal_acceptance_remains_valid_after_quote_expiry() {
        var source=quote(NOW.minusSeconds(20));
        assertDoesNotThrow(()->source.validateAt(basis,NOW));
        assertEquals("ACCEPTED_QUOTE",source.kind());
        assertEquals(source.responseId().toString(),source.canonical().get("responseId"));
    }
    @Test void expiry_boundary_and_before_delivery_cannot_be_acceptance() {
        assertThrows(IllegalArgumentException.class,()->quote(NOW.minusSeconds(10)));
        assertThrows(IllegalArgumentException.class,()->quote(NOW.minusSeconds(101)));
        assertThrows(IllegalArgumentException.class,()->quote(NOW));
    }
    @Test void source_rejects_wrong_tenant_opportunity_customer_or_commercial_content() {
        var source=quote(NOW.minusSeconds(20));
        for(var other:new ContractPreparationSource.Basis[]{
            new ContractPreparationSource.Basis(UUID.randomUUID(),basis.opportunityId(),basis.customerConfirmationId(),HASH),
            new ContractPreparationSource.Basis(basis.tenantId(),UUID.randomUUID(),basis.customerConfirmationId(),HASH),
            new ContractPreparationSource.Basis(basis.tenantId(),basis.opportunityId(),UUID.randomUUID(),HASH),
            new ContractPreparationSource.Basis(basis.tenantId(),basis.opportunityId(),basis.customerConfirmationId(),"cd".repeat(32))})
            assertThrows(IllegalArgumentException.class,()->source.validateAt(other,NOW));
    }
    @Test void authorization_is_distinct_and_validity_uses_current_time() {
        var source=new ContractPreparationSource.DirectAuthorization(basis,UUID.randomUUID(),UUID.randomUUID(),NOW.minusSeconds(1),NOW.plusSeconds(1));
        assertDoesNotThrow(()->source.validateAt(basis,NOW));
        assertEquals("DIRECT_AUTHORIZATION",source.kind());
        assertFalse(source.canonical().containsKey("responseId"));
        assertThrows(IllegalArgumentException.class,()->source.validateAt(basis,NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->source.validateAt(basis,NOW.minusSeconds(2)));
    }
    @Test void exact_facts_time_precision_and_source_shape_are_required() {
        assertThrows(IllegalArgumentException.class,()->new ContractPreparationSource.Basis(null,UUID.randomUUID(),UUID.randomUUID(),HASH));
        assertThrows(IllegalArgumentException.class,()->new ContractPreparationSource.Basis(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"not-a-digest"));
        assertThrows(IllegalArgumentException.class,()->new ContractPreparationSource.DirectAuthorization(basis,null,UUID.randomUUID(),NOW,null));
        assertThrows(IllegalArgumentException.class,()->new ContractPreparationSource.DirectAuthorization(basis,UUID.randomUUID(),UUID.randomUUID(),NOW,NOW));
        assertThrows(IllegalArgumentException.class,()->new ContractPreparationSource.DirectAuthorization(basis,UUID.randomUUID(),UUID.randomUUID(),NOW.plusNanos(1),null));
        assertThrows(IllegalArgumentException.class,()->quote(NOW.minusSeconds(20)).validateAt(basis,NOW.minusSeconds(6)));
    }
    @Test void canonical_source_is_immutable_and_protected_in_logs() {
        var source=quote(NOW.minusSeconds(20));
        assertThrows(UnsupportedOperationException.class,()->source.canonical().put("kind","DIRECT_AUTHORIZATION"));
        assertFalse(source.toString().contains(source.responseId().toString()));
        assertFalse(basis.toString().contains(HASH));
    }
}
