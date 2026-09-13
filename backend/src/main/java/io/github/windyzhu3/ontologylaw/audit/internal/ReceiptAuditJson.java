package io.github.windyzhu3.ontologylaw.audit.internal;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Restricted Audit protocol codec: JSON integers only, duplicate keys rejected, JCS encoding. */
public final class ReceiptAuditJson {
    private ReceiptAuditJson() {}
    public static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid receipt Audit metadata"); }
    public static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Object parse(String text) {
        if (text == null || text.length() > 1_048_576) throw invalid();
        var parser = new Parser(text); Object value = parser.value(0); parser.space();
        if (parser.at != text.length()) throw invalid();
        return value;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw invalid();
        return (Map<String,Object>) map;
    }
    public static void fields(Map<String,Object> value, String... names) {
        if (!value.keySet().equals(Set.of(names))) throw invalid();
    }
    public static String encode(Object value) { var result = new StringBuilder(); encode(value,result,0); return result.toString(); }
    private static void encode(Object value,StringBuilder out,int depth) {
        if (depth > 64) throw invalid();
        if (value == null) out.append("null");
        else if (value instanceof String text) quote(text,out);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number)value).longValue(); if (number < -9007199254740991L || number > 9007199254740991L) throw invalid(); out.append(number);
        } else if (value instanceof Map<?,?>) {
            out.append('{'); boolean comma = false;
            for (var entry : new TreeMap<>(object(value)).entrySet()) {
                if (comma) out.append(','); comma = true; quote(entry.getKey(),out); out.append(':'); encode(entry.getValue(),out,depth+1);
            } out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('['); for (int i=0;i<list.size();i++) { if(i>0)out.append(','); encode(list.get(i),out,depth+1); } out.append(']');
        } else throw invalid();
    }
    private static void quote(String text,StringBuilder out) {
        out.append('"');
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) { if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(i+1)))throw invalid(); out.append(c).append(text.charAt(++i)); continue; }
            if (Character.isLowSurrogate(c)) throw invalid();
            switch(c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); }
            }
        } out.append('"');
    }
    private static final class Parser {
        final String text; int at;
        Parser(String text) {this.text=text;}
        void space() {while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        boolean take(char c) {space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        Object value(int depth) {
            space();if(depth>64||at>=text.length())throw invalid();char c=text.charAt(at);
            if(c=='"')return string();
            if(c=='{') {
                at++;var values=new TreeMap<String,Object>();if(take('}'))return Collections.unmodifiableMap(values);
                do { space();if(at>=text.length()||text.charAt(at)!='"')throw invalid();String key=string();
                    if(values.containsKey(key)||!take(':'))throw invalid(); values.put(key,value(depth+1));
                }while(take(','));if(!take('}'))throw invalid();return Collections.unmodifiableMap(values);
            }
            if(c=='[') {
                at++;var values=new ArrayList<Object>();if(take(']'))return Collections.unmodifiableList(values);
                do {values.add(value(depth+1));}while(take(','));if(!take(']'))throw invalid();return Collections.unmodifiableList(values);
            }
            for(String literal:List.of("null","true","false"))if(text.startsWith(literal,at)){at+=literal.length();return literal.equals("null")?null:literal.equals("true");}
            int start=at;if(c=='-')at++;
            if(at>=text.length()||text.charAt(at)<'0'||text.charAt(at)>'9')throw invalid();
            if(text.charAt(at)=='0')at++;else while(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9')at++;
            try {long n=Long.parseLong(text.substring(start,at));if(n < -9007199254740991L||n>9007199254740991L)throw invalid();return n;}
            catch(NumberFormatException bad){throw invalid();}
        }
        String string() {
            at++;var value=new StringBuilder();boolean ended=false;
            while(at<text.length()) {
                char c=text.charAt(at++);if(c=='"'){ended=true;break;}if(c<32)throw invalid();
                if(c!='\\'){value.append(c);continue;}
                if(at>=text.length())throw invalid();char escaped=text.charAt(at++);
                switch(escaped) {
                    case '"','\\','/' -> value.append(escaped);case 'b' -> value.append('\b');case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');case 'r' -> value.append('\r');case 't' -> value.append('\t');
                    case 'u' -> {if(at+4>text.length())throw invalid();String digits=text.substring(at,at+4);if(!digits.matches("[0-9a-fA-F]{4}"))throw invalid();value.append((char)Integer.parseInt(digits,16));at+=4;}
                    default -> throw invalid();
                }
            }
            if(!ended)throw invalid();String result=value.toString();quote(result,new StringBuilder());return result;
        }
    }
}
