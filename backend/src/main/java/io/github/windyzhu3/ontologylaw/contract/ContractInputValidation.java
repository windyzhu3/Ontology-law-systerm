package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.text.Normalizer;

/** Structural input checks only; Owner reads and authorization remain required. */
final class ContractInputValidation {
    private ContractInputValidation() {}
    static <T>T required(T value){if(value==null)throw new IllegalArgumentException("Exact contract input required");return value;}
    static Instant time(Instant value){required(value);if(value.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond contract time required");return value;}
    static String digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Canonical SHA256 required");return value;}
    static String text(String value,int limit){
        required(value);
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException("Invalid contract text");}
            else if(Character.isLowSurrogate(c)||c<32&&c!=9&&c!=10&&c!=13||c>=127&&c<=159)throw new IllegalArgumentException("Invalid contract text");
        }
        value=Normalizer.normalize(value.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFC).strip();
        if(value.isEmpty()||value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))||value.codePointCount(0,value.length())>limit)throw new IllegalArgumentException("Contract text required or too long");
        return value;
    }
}
