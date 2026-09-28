package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowService;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuoteWorkflowServiceTest {
 private Map<String,Object> document(Object amount){return Map.of("currency","CNY","scope","服务范围","lines",List.of(Map.of("description","服务费","amountMinor",amount,"discount",false)),"paymentTerms","签约付款","validUntil","2030-01-01T00:00:00Z");}
 @Test void commercial_document_rejects_fractional_minor_units(){assertThrows(IllegalArgumentException.class,()->JdbcQuoteWorkflowService.commercial(document(1.5)));assertThrows(IllegalArgumentException.class,()->JdbcQuoteWorkflowService.commercial(document(1.0)));assertEquals(123,JdbcQuoteWorkflowService.commercial(document(123L)).totalMinor());}
 @Test void commercial_document_rejects_unsafe_integer_without_truncating(){assertThrows(IllegalArgumentException.class,()->JdbcQuoteWorkflowService.commercial(document(new java.math.BigInteger("9007199254740992"))));}
}
