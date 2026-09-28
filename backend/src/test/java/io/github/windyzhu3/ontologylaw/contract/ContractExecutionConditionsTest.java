package io.github.windyzhu3.ontologylaw.contract;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractExecutionConditions.*;

class ContractExecutionConditionsTest {
    final Basis basis=new Basis(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    final ContractVersionInput.PaymentGate prepay=new ContractVersionInput.PaymentGate(true,20000L);
    Receipt receipt(long amount){return new Receipt(UUID.randomUUID(),basis.tenantId(),basis.contractId(),basis.revisionId(),"CNY",amount);}
    @Test void non_prepay_does_not_require_a_fabricated_receipt(){
        var result=evaluate(basis,new ContractVersionInput.PaymentGate(false,null),"CNY",true,List.of());
        assertEquals(Status.READY_FOR_TRANSFER_PREPARATION,result.status());assertEquals(0,result.confirmedMinor());assertTrue(result.receiptIds().isEmpty());
    }
    @Test void paid_money_never_substitutes_for_human_condition_verification(){
        assertEquals(Status.AWAIT_CONDITION_VERIFICATION,evaluate(basis,prepay,"CNY",false,List.of(receipt(20000))).status());
        assertEquals(Status.AWAIT_CONDITION_VERIFICATION,evaluate(basis,new ContractVersionInput.PaymentGate(false,null),"CNY",false,List.of()).status());
    }
    @Test void unpaid_prepay_is_reported_before_human_conditions_in_the_confirmed_P_order(){
        assertEquals(Status.AWAIT_REQUIRED_RECEIPT,evaluate(basis,prepay,"CNY",false,List.of()).status());
        assertEquals(Status.AWAIT_REQUIRED_RECEIPT,evaluate(basis,prepay,"CNY",false,List.of(receipt(8000))).status());
    }
    @Test void partial_receipts_keep_original_gate_until_exact_threshold(){
        var first=receipt(8000);var partial=evaluate(basis,prepay,"CNY",true,List.of(first));
        assertEquals(Status.AWAIT_REQUIRED_RECEIPT,partial.status());assertEquals(12000,partial.remainingMinor());
        var second=receipt(12000);var complete=evaluate(basis,prepay,"CNY",true,List.of(first,second));
        assertEquals(Status.READY_FOR_TRANSFER_PREPARATION,complete.status());assertEquals(20000,complete.confirmedMinor());assertEquals(0,complete.remainingMinor());
        assertEquals(Set.of(first.id(),second.id()),new HashSet<>(complete.receiptIds()));
    }
    @Test void overpayment_does_not_create_negative_remaining(){assertEquals(0,evaluate(basis,prepay,"CNY",true,List.of(receipt(25000))).remainingMinor());}
    @Test void duplicate_fact_cannot_count_twice(){var one=receipt(10000);assertThrows(IllegalArgumentException.class,()->evaluate(basis,prepay,"CNY",true,List.of(one,one)));}
    @Test void wrong_tenant_contract_revision_or_currency_is_rejected_even_without_payment_gate(){
        for(var wrong:List.of(new Receipt(UUID.randomUUID(),UUID.randomUUID(),basis.contractId(),basis.revisionId(),"CNY",20000),new Receipt(UUID.randomUUID(),basis.tenantId(),UUID.randomUUID(),basis.revisionId(),"CNY",20000),new Receipt(UUID.randomUUID(),basis.tenantId(),basis.contractId(),UUID.randomUUID(),"CNY",20000),new Receipt(UUID.randomUUID(),basis.tenantId(),basis.contractId(),basis.revisionId(),"USD",20000))){
            assertThrows(IllegalArgumentException.class,()->evaluate(basis,prepay,"CNY",true,List.of(wrong)));
            assertThrows(IllegalArgumentException.class,()->evaluate(basis,new ContractVersionInput.PaymentGate(false,null),"CNY",true,List.of(wrong)));
        }
    }
    @Test void exact_safe_minor_amounts_only(){
        for(long amount:new long[]{0,-1,9007199254740992L})assertThrows(IllegalArgumentException.class,()->receipt(amount));
        assertThrows(IllegalArgumentException.class,()->evaluate(basis,prepay,"CNY",true,List.of(receipt(9007199254740991L),receipt(1))));
    }
    @Test void receipts_are_immutable_and_input_order_does_not_change_result(){
        var a=receipt(8000);var b=receipt(12000);
        var result=evaluate(basis,prepay,"CNY",true,List.of(a,b));
        assertEquals(result,evaluate(basis,prepay,"CNY",true,List.of(b,a)));
        assertThrows(UnsupportedOperationException.class,()->result.receiptIds().clear());
    }
}
