package io.github.windyzhu3.ontologylaw.contract;

import java.util.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractInputValidation.*;

/** Immutable preparation input; not a persisted version, approval, execution or authority proof. */
public record ContractVersionInput(UUID contractId,long revision,UUID predecessorId,
        ContractPreparationSource source,CommercialTerms commercial,Document document,Signing signing,PaymentGate paymentGate) {
    public static final String FORMAT="R2_CONTRACT_PREPARATION_V1";
    private static final long MAX=9007199254740991L;
    public record FeeLine(String description,long amountMinor,boolean discount) {
        public FeeLine {description=text(description,500);if(amountMinor < -MAX||amountMinor>MAX||(discount?amountMinor>=0:amountMinor<0))throw new IllegalArgumentException("Invalid exact contract fee");}
        Map<String,Object> canonical(){return Map.of("description",description,"amountMinor",amountMinor,"discount",discount);}
        @Override public String toString(){return "ContractFeeLine[protected]";}
    }
    public record ConditionalFee(String basis,int rateBasisPoints,long capMinor) {
        public ConditionalFee {basis=text(basis,2000);if(rateBasisPoints<=0||rateBasisPoints>10000||capMinor<=0||capMinor>MAX)throw new IllegalArgumentException("Explicit bounded conditional fee required");}
        Map<String,Object> canonical(){return Map.of("basis",basis,"rateBasisPoints",rateBasisPoints,"capMinor",capMinor);}
        @Override public String toString(){return "ContractConditionalFee[protected]";}
    }
    public record CommercialTerms(String currency,String scope,List<FeeLine> lines,ConditionalFee conditionalFee,String paymentTerms) {
        public CommercialTerms {
            if(!"CNY".equals(currency))throw new IllegalArgumentException("Unsupported contract currency");
            scope=text(scope,4000);paymentTerms=text(paymentTerms,4000);
            if(lines==null||lines.isEmpty()||lines.size()>100||lines.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Bounded contract fee lines required");
            lines=List.copyOf(lines);total(lines);
        }
        public long totalMinor(){return total(lines);}
        private static long total(List<FeeLine> lines){long sum=0;for(var line:lines){try{sum=Math.addExact(sum,line.amountMinor());}catch(ArithmeticException ex){throw new IllegalArgumentException("Contract fee overflow");}}if(sum<0||sum>MAX)throw new IllegalArgumentException("Unsafe contract total");return sum;}
        public Map<String,Object> canonical(){var map=new TreeMap<String,Object>();map.put("currency",currency);map.put("scope",scope);map.put("lines",lines.stream().map(FeeLine::canonical).toList());map.put("conditionalFee",conditionalFee==null?null:conditionalFee.canonical());map.put("paymentTerms",paymentTerms);map.put("totalMinor",totalMinor());return Collections.unmodifiableMap(map);}
        public String digest(){return hash(Map.of("format",FORMAT+"_COMMERCIAL", "terms",canonical()));}
        @Override public String toString(){return "ContractCommercialTerms[protected]";}
    }
    public record Document(UUID evidenceVersionId,String bodySha256,UUID templateVersionId,List<UUID> clauseVersionIds) {
        public Document {required(evidenceVersionId);ContractInputValidation.digest(bodySha256);required(templateVersionId);if(clauseVersionIds==null||clauseVersionIds.size()>200||clauseVersionIds.stream().anyMatch(Objects::isNull)||new HashSet<>(clauseVersionIds).size()!=clauseVersionIds.size())throw new IllegalArgumentException("Unique bounded clause versions required");clauseVersionIds=List.copyOf(clauseVersionIds);}
        Map<String,Object> canonical(){return Map.of("evidenceVersionId",evidenceVersionId.toString(),"bodySha256",bodySha256,"templateVersionId",templateVersionId.toString(),"clauseVersionIds",clauseVersionIds.stream().map(UUID::toString).toList());}
        @Override public String toString(){return "ContractDocument[protected]";}
    }
    public record Signing(String partySnapshotDigest,String requirements) {
        public Signing {ContractInputValidation.digest(partySnapshotDigest);requirements=text(requirements,4000);}
        Map<String,Object> canonical(){return Map.of("partySnapshotDigest",partySnapshotDigest,"requirements",requirements);}
        @Override public String toString(){return "ContractSigning[protected]";}
    }
    /** Amount is in the commercial currency; this value is never evidence of an actual payment. */
    public record PaymentGate(boolean receiptRequiredBeforeTransfer,Long requiredMinor) {
        public PaymentGate {if(receiptRequiredBeforeTransfer?(requiredMinor==null||requiredMinor<=0||requiredMinor>MAX):requiredMinor!=null)throw new IllegalArgumentException("Explicit contract receipt gate required");}
        Map<String,Object> canonical(){var map=new TreeMap<String,Object>();map.put("receiptRequiredBeforeTransfer",receiptRequiredBeforeTransfer);map.put("requiredMinor",requiredMinor);return Collections.unmodifiableMap(map);}
        @Override public String toString(){return "ContractPaymentGate[protected]";}
    }
    public ContractVersionInput {
        required(contractId);required(source);required(commercial);required(document);required(signing);required(paymentGate);
        if(revision<1||revision>Integer.MAX_VALUE||(revision==1)!=(predecessorId==null))throw new IllegalArgumentException("Exact contract version chain required");
        if(!source.basis().commercialDigest().equals(commercial.digest()))throw new IllegalArgumentException("Commercial preparation basis changed");
    }
    public Map<String,Object> canonical(){var map=new TreeMap<String,Object>();map.put("format",FORMAT);map.put("contractId",contractId.toString());map.put("revision",revision);map.put("predecessorId",predecessorId==null?null:predecessorId.toString());map.put("source",source.canonical());map.put("commercial",commercial.canonical());map.put("document",document.canonical());map.put("signing",signing.canonical());map.put("paymentGate",paymentGate.canonical());return Collections.unmodifiableMap(map);}
    public String digest(){return hash(canonical());}
    private static String hash(Map<String,Object> body){return HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(body)));}
    @Override public String toString(){return "ContractVersionInput[protected]";}
}
