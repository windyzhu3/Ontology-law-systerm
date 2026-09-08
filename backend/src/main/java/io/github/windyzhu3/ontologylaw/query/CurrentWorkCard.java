package io.github.windyzhu3.ontologylaw.query;

import java.util.*;

/** Immutable plain wire tree; only the disclosure service may release it after commit. */
public record CurrentWorkCard(Map<String,Object> values) {
    public CurrentWorkCard { values=freezeMap(values); }
    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> m)return freezeMap((Map<String,Object>)m);
        if(value instanceof List<?> l)return l.stream().map(CurrentWorkCard::freeze).toList();
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long)return value;
        throw new IllegalArgumentException("Non-wire value");
    }
    private static Map<String,Object> freezeMap(Map<String,Object> input) {
        var out=new LinkedHashMap<String,Object>();input.forEach((k,v)->out.put(k,freeze(v)));return Collections.unmodifiableMap(out);
    }
    static Map<String,Object> object(Object... pairs) {
        var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;
    }
}
