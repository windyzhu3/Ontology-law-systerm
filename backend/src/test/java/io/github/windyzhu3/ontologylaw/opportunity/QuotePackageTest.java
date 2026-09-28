package io.github.windyzhu3.ontologylaw.opportunity;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotePackageTest {
    private static final Instant NOW=Instant.parse("2026-09-20T10:00:00Z");
    private QuotePackage pack(List<QuotePackage.Line> lines) {
        return new QuotePackage("CNY","协商服务",lines,"签署后分期付款",NOW.plusSeconds(86400),null);
    }
    @Test void exact_money_and_explicit_discount() {
        assertEquals(10,QuotePackage.minor("0.10"));
        assertEquals(123456,QuotePackage.minor("1234.56"));
        for(String bad:List.of("1.001","1e3","NaN","-0.01","", "9223372036854775807"))
            assertThrows(IllegalArgumentException.class,()->QuotePackage.minor(bad));
        var p=pack(List.of(new QuotePackage.Line("服务费",2000000,false),new QuotePackage.Line("约定折扣",-100000,true)));
        assertEquals(1900000,p.totalMinor());
        assertThrows(IllegalArgumentException.class,()->new QuotePackage.Line("隐形折扣",-1,false));
        assertThrows(IllegalArgumentException.class,()->pack(List.of(new QuotePackage.Line("折扣",-1,true))));
    }
    @Test void bounded_sum_and_immutable_lines() {
        var lines=new ArrayList<>(List.of(new QuotePackage.Line("服务",100,false)));
        var p=pack(lines);lines.clear();assertEquals(100,p.totalMinor());
        assertThrows(UnsupportedOperationException.class,()->p.lines().clear());
        assertThrows(IllegalArgumentException.class,()->pack(List.of(new QuotePackage.Line("服务",9007199254740991L,false),new QuotePackage.Line("另一项",1,false))));
    }
    @Test void conditions_are_separate_from_fixed_total_and_validity_is_strict() {
        var p=new QuotePackage("CNY","范围",List.of(new QuotePackage.Line("基础",100,false)),"付款条件",NOW.plusSeconds(1),new QuotePackage.ConditionalFee("实际回款",500,50000));
        assertEquals(100,p.totalMinor());assertDoesNotThrow(()->p.validateAt(NOW));
        assertThrows(IllegalArgumentException.class,()->p.validateAt(NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new QuotePackage.ConditionalFee("",500,50000));
        assertThrows(IllegalArgumentException.class,()->new QuotePackage.ConditionalFee("回款",10001,50000));
    }
    @Test void digest_binds_exact_customer_and_commercial_terms_without_logging_contents() {
        var p=pack(List.of(new QuotePackage.Line("服务",100,false)));
        UUID tenant=UUID.randomUUID(),opportunity=UUID.randomUUID(),confirmation=UUID.randomUUID();
        byte[] digest=p.digest(tenant,opportunity,confirmation,1,null);
        assertArrayEquals(digest,p.digest(tenant,opportunity,confirmation,1,null));
        assertFalse(Arrays.equals(digest,p.digest(tenant,opportunity,UUID.randomUUID(),1,null)));
        assertFalse(Arrays.equals(digest,pack(List.of(new QuotePackage.Line("服务",101,false))).digest(tenant,opportunity,confirmation,1,null)));
        assertThrows(IllegalArgumentException.class,()->p.digest(tenant,opportunity,confirmation,2,null));
        assertFalse(p.toString().contains("协商服务"));
        assertThrows(IllegalArgumentException.class,()->new QuotePackage.Line("\u0000",10,false));
    }
    @Test void final_total_is_independent_of_discount_position() {
        var base=new QuotePackage.Line("服务",9007199254740991L,false);
        var extra=new QuotePackage.Line("附加",1,false);
        var discount=new QuotePackage.Line("折扣",-1,true);
        assertEquals(9007199254740991L,pack(List.of(base,extra,discount)).totalMinor());
        assertEquals(9007199254740991L,pack(List.of(base,discount,extra)).totalMinor());
    }
    @Test void digest_changes_for_every_commercial_or_identity_basis() {
        UUID t=UUID.randomUUID(),o=UUID.randomUUID(),c=UUID.randomUUID();
        var lines=List.of(new QuotePackage.Line("服务",100,false));
        var p=pack(lines);byte[] original=p.digest(t,o,c,1,null);
        for(var changed:List.of(new QuotePackage("CNY","另一个范围",lines,p.paymentTerms(),p.validUntil(),null),
            new QuotePackage("CNY",p.scope(),lines,"另一付款安排",p.validUntil(),null),
            new QuotePackage("CNY",p.scope(),lines,p.paymentTerms(),p.validUntil().plusSeconds(1),null),
            new QuotePackage("CNY",p.scope(),lines,p.paymentTerms(),p.validUntil(),new QuotePackage.ConditionalFee("回款",500,10000)),
            pack(List.of(new QuotePackage.Line("另一服务",100,false)))))
            assertFalse(Arrays.equals(original,changed.digest(t,o,c,1,null)));
        assertFalse(Arrays.equals(original,p.digest(UUID.randomUUID(),o,c,1,null)));
        assertFalse(Arrays.equals(original,p.digest(t,UUID.randomUUID(),c,1,null)));
        assertFalse(Arrays.equals(original,p.digest(t,o,c,2,UUID.randomUUID())));
        var n1=new QuotePackage("CNY","Café\n服务",lines,"付款",p.validUntil(),null);
        var n2=new QuotePackage("CNY"," Cafe\u0301\r\n服务 ",lines,"付款",p.validUntil(),null);
        assertArrayEquals(n1.digest(t,o,c,1,null),n2.digest(t,o,c,1,null));
    }
}
