package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractVersionInputTest {
    private static final String HASH="ab".repeat(32);
    private static final Instant NOW=Instant.parse("2026-09-21T10:00:00Z");
    private final UUID tenant=UUID.randomUUID(),opportunity=UUID.randomUUID(),customer=UUID.randomUUID(),contract=UUID.randomUUID();
    private ContractVersionInput.CommercialTerms terms(){return new ContractVersionInput.CommercialTerms("CNY","法律服务",List.of(new ContractVersionInput.FeeLine("固定费用",10000,false)),null,"签署后分期支付");}
    private ContractPreparationSource source(ContractVersionInput.CommercialTerms t){return new ContractPreparationSource.DirectAuthorization(new ContractPreparationSource.Basis(tenant,opportunity,customer,t.digest()),UUID.randomUUID(),UUID.randomUUID(),NOW,null);}
    private ContractVersionInput.Document document(){return new ContractVersionInput.Document(UUID.randomUUID(),HASH,UUID.randomUUID(),List.of(UUID.randomUUID()));}
    private ContractVersionInput.Signing signing(){return new ContractVersionInput.Signing(HASH,"双方签字并盖章，核对签字权限");}
    private ContractVersionInput make(ContractPreparationSource source,ContractVersionInput.CommercialTerms terms,ContractVersionInput.Document doc,ContractVersionInput.PaymentGate gate){return new ContractVersionInput(contract,1,null,source,terms,doc,signing(),gate);}
    @Test void exact_commercial_basis_is_required_for_either_entry() {
        var terms=terms();var source=source(terms);
        assertDoesNotThrow(()->make(source,terms,document(),new ContractVersionInput.PaymentGate(false,null)));
        var accepted=new ContractPreparationSource.AcceptedQuote(source.basis(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),NOW.minusSeconds(3),NOW.minusSeconds(2),NOW.minusSeconds(1),NOW);
        assertDoesNotThrow(()->make(accepted,terms,document(),new ContractVersionInput.PaymentGate(false,null)));
        var changed=new ContractVersionInput.CommercialTerms("CNY","其他范围",terms.lines(),null,terms.paymentTerms());
        assertThrows(IllegalArgumentException.class,()->make(source,changed,document(),new ContractVersionInput.PaymentGate(false,null)));
    }
    @Test void exact_amounts_explicit_discounts_and_conditional_fees() {
        var terms=new ContractVersionInput.CommercialTerms("CNY","范围",List.of(new ContractVersionInput.FeeLine("固定",100,false),new ContractVersionInput.FeeLine("折扣",-20,true)),new ContractVersionInput.ConditionalFee("实际回款",500,10000),"付款安排");
        assertEquals(80,terms.totalMinor());
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.FeeLine("隐形折扣",-1,false));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.CommercialTerms("CNY","范围",List.of(new ContractVersionInput.FeeLine("折扣",-1,true)),null,"付款"));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.ConditionalFee("回款",10001,10000));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.CommercialTerms("USD","范围",terms.lines(),null,"付款"));
        long max=9007199254740991L;
        assertDoesNotThrow(()->new ContractVersionInput.FeeLine("上限",max,false));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.FeeLine("超限",max+1,false));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.FeeLine("超限",Long.MIN_VALUE,true));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.CommercialTerms("CNY","范围",List.of(new ContractVersionInput.FeeLine("上限",max,false),new ContractVersionInput.FeeLine("额外",1,false)),null,"付款"));
    }
    @Test void first_payment_gate_is_explicit_and_cannot_hide_a_threshold() {
        assertDoesNotThrow(()->new ContractVersionInput.PaymentGate(false,null));
        assertDoesNotThrow(()->new ContractVersionInput.PaymentGate(true,100L));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.PaymentGate(false,100L));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.PaymentGate(true,null));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.PaymentGate(true,0L));
    }
    @Test void digest_binds_document_template_clauses_signing_payment_source_and_version() {
        var t=terms();var s=source(t);var d=document();var gate=new ContractVersionInput.PaymentGate(false,null);var v=make(s,t,d,gate);
        assertEquals(v.digest(),make(s,t,d,gate).digest());
        for(var changed:List.of(
            make(s,t,new ContractVersionInput.Document(d.evidenceVersionId(),"cd".repeat(32),d.templateVersionId(),d.clauseVersionIds()),gate),
            make(s,t,new ContractVersionInput.Document(d.evidenceVersionId(),d.bodySha256(),UUID.randomUUID(),d.clauseVersionIds()),gate),
            make(s,t,new ContractVersionInput.Document(d.evidenceVersionId(),d.bodySha256(),d.templateVersionId(),List.of(UUID.randomUUID())),gate),
            make(s,t,d,new ContractVersionInput.PaymentGate(true,100L)),
            make(source(t),t,d,gate),
            new ContractVersionInput(contract,2,UUID.randomUUID(),s,t,d,signing(),gate),
            new ContractVersionInput(contract,1,null,s,t,d,new ContractVersionInput.Signing("cd".repeat(32),"其他签署要求"),gate)))assertNotEquals(v.digest(),changed.digest());
    }
    @Test void version_chain_shape_immutability_and_sensitive_logging() {
        var t=terms();var d=document();var s=source(t);var gate=new ContractVersionInput.PaymentGate(false,null);
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput(contract,2,null,s,t,d,signing(),gate));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput(contract,1,UUID.randomUUID(),s,t,d,signing(),gate));
        var clauses=new ArrayList<>(d.clauseVersionIds());var copy=new ContractVersionInput.Document(d.evidenceVersionId(),HASH,d.templateVersionId(),clauses);clauses.clear();assertEquals(1,copy.clauseVersionIds().size());
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.Document(d.evidenceVersionId(),HASH,d.templateVersionId(),List.of(d.templateVersionId(),d.templateVersionId())));
        assertFalse(make(s,t,d,gate).toString().contains("法律服务"));
        assertFalse(t.toString().contains("法律服务"));
        assertThrows(IllegalArgumentException.class,()->new ContractVersionInput.Signing(HASH,"\u0000"));
    }
}
