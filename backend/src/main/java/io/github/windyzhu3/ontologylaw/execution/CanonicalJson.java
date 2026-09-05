package io.github.windyzhu3.ontologylaw.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** RFC 8785 serialization for the R1 JSON subset: integers only, no floating point coercion.
 * HTTP adapters must reject duplicate object names while parsing, before constructing this tree.
 */
public final class CanonicalJson {
    private CanonicalJson() {}
    public static Object freeze(Object value) {
        encode(value); // Validate before recursion, including nesting depth and numeric/Unicode bounds.
        return copy(value);
    }
    private static Object copy(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> result=new TreeMap<>();map.forEach((k,v)->result.put((String)k,copy(v)));return Collections.unmodifiableMap(result);}
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();list.forEach(v->result.add(copy(v)));return Collections.unmodifiableList(result);}
        return value;
    }
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }
    public static byte[] digest(String canonical) {
        try { return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting exceeds R1 limit");
        if (value == null) out.append("null");
        else if (value instanceof String string) quote(out, string);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number < -9007199254740991L || number > 9007199254740991L) throw new IllegalArgumentException("Unsafe JSON integer");
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, element) -> {
                if (!(key instanceof String)) throw new IllegalArgumentException("JSON key must be a string");
                sorted.put((String) key, element);
            });
            out.append('{');
            boolean comma = false;
            for (var entry : sorted.entrySet()) {
                if (comma) out.append(',');
                quote(out, entry.getKey()); out.append(':'); append(out, entry.getValue(), depth + 1); comma = true;
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) { if (i > 0) out.append(','); append(out, list.get(i), depth + 1); }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported R1 JSON value");
    }
    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new IllegalArgumentException("Invalid Unicode");
                out.append(c).append(value.charAt(++i)); continue;
            }
            if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }
}
