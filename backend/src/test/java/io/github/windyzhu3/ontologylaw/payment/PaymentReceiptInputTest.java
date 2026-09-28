package io.github.windyzhu3.ontologylaw.payment;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentReceiptInputTest {
 private final UUID contract=UUID.randomUUID(),revision=UUID.randomUUID(),material=UUID.randomUUID();
 private final Instant now=Instant.parse("2026-09-26T12:00:00Z");
 private PaymentReceiptInput input(String reference,long amount,String currency,Instant received,boolean checked){return new PaymentReceiptInput(contract,revision,material,"ab".repeat(32),reference,amount,currency,received,"已人工核对合同归属与账户凭证",checked);}
 @Test void exact_positive_receipt_preserves_identifiers_and_trims_outer_reference_space(){var v=input("  SYNTHETIC-0926-01  ",800000,"CNY",now.minusSeconds(60),true);v.validateAt(now);assertEquals("SYNTHETIC-0926-01",v.transactionReference());assertEquals(contract,v.contractId());assertEquals(revision,v.contractRevisionId());assertEquals(material,v.materialVersionId());assertEquals(800000,v.amountMinor());}
 @Test void receipt_cannot_be_zero_negative_unsafe_or_wrong_currency(){for(long value:new long[]{0,-1,9007199254740992L})assertThrows(IllegalArgumentException.class,()->input("T1",value,"CNY",now,true));assertThrows(IllegalArgumentException.class,()->input("T1",100,"USD",now,true));}
 @Test void future_receipt_or_missing_human_verification_is_rejected(){assertThrows(IllegalArgumentException.class,()->input("T1",100,"CNY",now.plusSeconds(1),true).validateAt(now));assertThrows(IllegalArgumentException.class,()->input("T1",100,"CNY",now,false));}
 @Test void missing_or_control_character_reference_is_not_a_confirmable_transaction(){for(String value:List.of("","   ","BANK\n123","BANK\t123","\uD800","x".repeat(201)))assertThrows(IllegalArgumentException.class,()->input(value,100,"CNY",now,true));}
 @Test void exact_accepted_material_digest_and_explanation_are_required(){assertThrows(IllegalArgumentException.class,()->new PaymentReceiptInput(contract,revision,material,"bad","T1",100,"CNY",now,"人工核对",true));assertThrows(IllegalArgumentException.class,()->new PaymentReceiptInput(contract,revision,null,"ab".repeat(32),"T1",100,"CNY",now,"人工核对",true));assertThrows(IllegalArgumentException.class,()->new PaymentReceiptInput(contract,revision,material,"ab".repeat(32),"T1",100,"CNY",now," ",true));}
 @Test void default_string_does_not_disclose_bank_reference_or_explanation(){var v=input("PRIVATE-BANK-REFERENCE",100,"CNY",now,true);assertFalse(v.toString().contains("PRIVATE-BANK"));assertFalse(v.toString().contains("账户凭证"));}
}
