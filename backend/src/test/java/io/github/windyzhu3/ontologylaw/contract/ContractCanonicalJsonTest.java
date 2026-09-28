package io.github.windyzhu3.ontologylaw.contract;

import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractCanonicalJsonTest {
    @Test void golden_key_order_escaping_and_sha256_vector() {
        var input=new LinkedHashMap<String,Object>();input.put("z",true);input.put("a","line\n\"\\");input.put("n",42L);
        assertEquals("{\"a\":\"line\\n\\\"\\\\\",\"n\":42,\"z\":true}",ContractCanonicalJson.encode(input));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",HexFormat.of().formatHex(ContractCanonicalJson.digest("")));
        assertEquals("[null,false,\"中文😀\"]",ContractCanonicalJson.encode(Arrays.asList(null,false,"中文😀")));
        assertEquals("{\"😀\":1,\"\uE000\":2}",ContractCanonicalJson.encode(Map.of("\uE000",2,"😀",1)));
    }
    @Test void rejects_unsafe_numbers_floating_point_and_invalid_unicode() {
        for(Object value:List.of(9007199254740992L,-9007199254740992L,Double.NaN,1.0,new BigDecimal("1.00"),"\uD800","\uDC00"))
            assertThrows(IllegalArgumentException.class,()->ContractCanonicalJson.encode(value));
        assertEquals("9007199254740991",ContractCanonicalJson.encode(9007199254740991L));
        assertThrows(IllegalArgumentException.class,()->ContractCanonicalJson.encode(Map.of(1,"not a string key")));
    }
    @Test void nested_snapshot_is_detached_and_read_only() {
        var list=new ArrayList<Object>(List.of("original"));var input=new HashMap<String,Object>();input.put("items",list);
        Object frozen=ContractCanonicalJson.freeze(input);list.set(0,"changed");input.clear();
        assertEquals("{\"items\":[\"original\"]}",ContractCanonicalJson.encode(frozen));
        assertThrows(UnsupportedOperationException.class,()->((Map<?,?>)frozen).clear());
        assertThrows(UnsupportedOperationException.class,()->((List<?>)((Map<?,?>)frozen).get("items")).clear());
    }
    @Test void excessive_depth_is_rejected_before_snapshot_recursion() {
        Object value="leaf";for(int i=0;i<100;i++)value=List.of(value);final Object deep=value;
        assertThrows(IllegalArgumentException.class,()->ContractCanonicalJson.freeze(deep));
    }
}
