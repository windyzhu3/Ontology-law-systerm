package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.opportunity.QuoteCanonicalJson;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Immutable commercial input; creating this value does not approve or issue a quote. */
public record QuotePackage(String currency, String scope, List<Line> lines,
                           String paymentTerms, Instant validUntil, ConditionalFee conditionalFee) {
    public static final String CONTRACT="R2_QUOTE_PACKAGE_V1";
    private static final long MAX=9007199254740991L;
    public record Line(String description,long amountMinor,boolean discount) {
        public Line {
            description=text(description,500);
            if(amountMinor < -MAX || amountMinor > MAX || (discount ? amountMinor>=0 : amountMinor<0))
                throw new IllegalArgumentException("Invalid quote line amount or discount");
        }
        @Override public String toString(){return "QuoteLine[protected]";}
    }
    public record ConditionalFee(String basis,int rateBasisPoints,long capMinor) {
        public ConditionalFee {
            basis=text(basis,2000);
            if(rateBasisPoints<=0 || rateBasisPoints>10000 || capMinor<=0 || capMinor>MAX)
                throw new IllegalArgumentException("Explicit conditional fee rate and cap required");
        }
        @Override public String toString(){return "ConditionalFee[protected]";}
    }
    public QuotePackage {
        if(!"CNY".equals(currency))throw new IllegalArgumentException("Unsupported quote currency");
        scope=text(scope,4000);paymentTerms=text(paymentTerms,4000);
        if(lines==null || lines.isEmpty() || lines.size()>100)throw new IllegalArgumentException("Quote lines required");
        lines=List.copyOf(lines);
        sum(lines);exactTime(validUntil);
    }
    public long totalMinor(){return sum(lines);}
    private static long sum(List<Line> lines) {
        long total=0;
        for(Line line:lines){
            try{total=Math.addExact(total,line.amountMinor());}
            catch(ArithmeticException e){throw new IllegalArgumentException("Quote total overflow");}
        }
        if(total>MAX)throw new IllegalArgumentException("Unsafe quote total");
        if(total<0)throw new IllegalArgumentException("Negative quote total");
        return total;
    }
    /** Decimal text from the UI; never accept rounding, exponent notation or a float. */
    public static long minor(String value) {
        if(value==null || value.length()>20 || !value.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Exact decimal amount required");
        try {
            long minor=new BigDecimal(value).movePointRight(2).longValueExact();
            if(minor>MAX)throw new IllegalArgumentException("Unsafe quote amount");
            return minor;
        } catch(ArithmeticException e){throw new IllegalArgumentException("Quote amount overflow");}
    }
    public void validateAt(Instant now){
        exactTime(now);
        if(!validUntil.isAfter(now))throw new IllegalArgumentException("Quote expired");
    }
    /** Binds a package to its exact customer confirmation and version chain. */
    public byte[] digest(UUID tenant,UUID opportunity,UUID customerConfirmation,long revision,UUID predecessor) {
        return QuoteCanonicalJson.digest(QuoteCanonicalJson.encode(body(tenant,opportunity,customerConfirmation,revision,predecessor)));
    }
    public Map<String,Object> body(UUID tenant,UUID opportunity,UUID customerConfirmation,long revision,UUID predecessor) {
        if(tenant==null || opportunity==null || customerConfirmation==null || revision<1 || revision>MAX
            || (revision==1)!=(predecessor==null))throw new IllegalArgumentException("Exact quote basis required");
        Map<String,Object> body=new TreeMap<>();
        body.put("contract",CONTRACT);body.put("tenantId",tenant.toString());body.put("opportunityId",opportunity.toString());
        body.put("customerConfirmationId",customerConfirmation.toString());body.put("revision",revision);
        body.put("predecessor",predecessor==null?null:predecessor.toString());
        body.put("currency",currency);body.put("scope",scope);body.put("paymentTerms",paymentTerms);
        body.put("validUntil",validUntil.toString());body.put("totalMinor",totalMinor());
        body.put("lines",lines.stream().map(l->Map.of("description",l.description(),"amountMinor",l.amountMinor(),"discount",l.discount())).toList());
        body.put("conditionalFee",conditionalFee==null?null:Map.of("basis",conditionalFee.basis(),"rateBasisPoints",conditionalFee.rateBasisPoints(),"capMinor",conditionalFee.capMinor()));
        return Collections.unmodifiableMap(body);
    }
    static void exactTime(Instant time){
        if(time==null || time.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond instant required");
    }
    static String text(String value,int limit){
        if(value==null)throw new IllegalArgumentException("Quote text required");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException("Invalid quote text");}
            else if(Character.isLowSurrogate(c)||c<32&&c!=9&&c!=10&&c!=13||c>=127&&c<=159)throw new IllegalArgumentException("Invalid quote text");
        }
        value=Normalizer.normalize(value.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFC).strip();
        if(value.isBlank() || value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)) || value.codePointCount(0,value.length())>limit)
            throw new IllegalArgumentException("Quote text required or too long");
        return value;
    }
    @Override public String toString(){return "QuotePackage[protected]";}
}
