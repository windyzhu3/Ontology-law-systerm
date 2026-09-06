package io.github.windyzhu3.ontologylaw.lead;

import com.ibm.icu.text.IDNA;
import java.text.Normalizer;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;

public final class LeadCanonicalization {
    private LeadCanonicalization() {}
    private static final IDNA IDNA2008=IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII | IDNA.NONTRANSITIONAL_TO_UNICODE
            | IDNA.USE_STD3_RULES | IDNA.CHECK_BIDI | IDNA.CHECK_CONTEXTJ | IDNA.CHECK_CONTEXTO);
    private static final List<int[]> VALID=load();
    private static List<int[]> load() {
        try(var input=LeadCanonicalization.class.getResourceAsStream("/lead/idna/Idna2008-17.0.0.txt")) {
            if(input==null)throw new IllegalStateException("IDNA data unavailable");
            byte[] bytes=input.readAllBytes();
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals("e4a7526a8a37539c0defa4da25f5dbf77d0212a14d4762d455cadea608a8921c"))throw new IllegalStateException("IDNA data mismatch");
            var ranges=new ArrayList<int[]>();
            for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")) {
                line=line.split("#",2)[0].trim();if(line.isEmpty())continue;
                var fields=line.split(";");String category=fields[1].trim();
                if(!Set.of("PVALID","CONTEXTJ","CONTEXTO").contains(category))continue;
                var bounds=fields[0].trim().split("\\.\\.");int first=Integer.parseInt(bounds[0],16);
                ranges.add(new int[]{first,bounds.length==1?first:Integer.parseInt(bounds[1],16)});
            }
            return List.copyOf(ranges);
        } catch(Exception failed) {throw new ExceptionInInitializerError(failed);}
    }
    private static boolean valid(int cp) {
        int low=0,high=VALID.size()-1;
        while(low<=high) {int mid=(low+high)>>>1;int[] range=VALID.get(mid);
            if(cp<range[0])high=mid-1;else if(cp>range[1])low=mid+1;else return true;
        }
        return false;
    }
    private static boolean whitespace(int c) {return c>=9&&c<=13 || c==0x20 || c==0x85 || c==0xa0 || c==0x1680 || c>=0x2000&&c<=0x200a || c==0x2028 || c==0x2029 || c==0x202f || c==0x205f || c==0x3000;}
    private static String trim(String value) {
        int start=0,end=value.length();while(start<end&&whitespace(value.codePointAt(start)))start+=Character.charCount(value.codePointAt(start));
        while(end>start&&whitespace(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));return value.substring(start,end);
    }
    public static String email(String value) {
        if(value==null)return null;CanonicalJson.encode(value);value=trim(Normalizer.normalize(value,Normalizer.Form.NFC));if(value.isEmpty())return null;
        int at=value.indexOf('@');if(at<1||at!=value.lastIndexOf('@')||at==value.length()-1)throw new IllegalArgumentException("Invalid email");
        String local=value.substring(0,at).toLowerCase(Locale.ROOT);
        if(local.codePoints().anyMatch(c->whitespace(c)||c<32||c>=127&&c<=159))throw new IllegalArgumentException("Invalid email");
        String domain=Normalizer.normalize(value.substring(at+1).toLowerCase(Locale.ROOT),Normalizer.Form.NFC);
        for(String label:domain.split("\\.",-1)) {
            if(label.isEmpty()||!label.codePoints().allMatch(LeadCanonicalization::valid))throw new IllegalArgumentException("Invalid IDNA domain");
            var unicode=new StringBuilder();var info=new IDNA.Info();IDNA2008.labelToUnicode(label,unicode,info);
            if(info.hasErrors()||!unicode.codePoints().allMatch(LeadCanonicalization::valid))throw new IllegalArgumentException("Invalid IDNA label");
        }
        var ascii=new StringBuilder();var info=new IDNA.Info();IDNA2008.nameToASCII(domain,ascii,info);
        if(info.hasErrors())throw new IllegalArgumentException("Invalid IDNA domain");
        return local+"@"+ascii.toString().toLowerCase(Locale.ROOT);
    }
    public static String phone(String value) {
        if(value==null||value.isEmpty())return null;
        if(!value.matches("\\+[1-9][0-9]{0,14}"))throw new IllegalArgumentException("Invalid phone");return value;
    }
    public static String text(String value) {
        if(value==null)return null;CanonicalJson.encode(value);
        value=trim(Normalizer.normalize(value.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFC));
        if(value.codePoints().anyMatch(c->c<32&&c!=9&&c!=10||c>=127&&c<=159))throw new IllegalArgumentException("Invalid text");return value;
    }
}
