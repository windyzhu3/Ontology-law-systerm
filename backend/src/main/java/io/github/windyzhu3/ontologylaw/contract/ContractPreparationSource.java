package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractInputValidation.*;

/** Exact source values, never proof of database existence, an approved decision or actor authority. */
public sealed interface ContractPreparationSource {
    record Basis(UUID tenantId,UUID opportunityId,UUID customerConfirmationId,String commercialDigest) {
        public Basis {required(tenantId);required(opportunityId);required(customerConfirmationId);digest(commercialDigest);}
        public Map<String,Object> canonical(){return Map.of("tenantId",tenantId.toString(),"opportunityId",opportunityId.toString(),"customerConfirmationId",customerConfirmationId.toString(),"commercialDigest",commercialDigest);}
        @Override public String toString(){return "ContractPreparationBasis[protected]";}
    }
    Basis basis();
    String kind();
    Map<String,Object> canonical();
    void validateAt(Basis expected,Instant now);
    private static void basisAt(Basis actual,Basis expected,Instant now){time(now);if(!actual.equals(expected))throw new IllegalArgumentException("Contract preparation basis changed");}
    record AcceptedQuote(Basis basis,UUID quoteRevisionId,UUID issueId,UUID responseId,UUID evidenceVersionId,
                         Instant deliveredAt,Instant acceptedAt,Instant quoteValidUntil,Instant recordedAt) implements ContractPreparationSource {
        public AcceptedQuote {
            required(basis);required(quoteRevisionId);required(issueId);required(responseId);required(evidenceVersionId);
            time(deliveredAt);time(acceptedAt);time(quoteValidUntil);time(recordedAt);
            if(acceptedAt.isBefore(deliveredAt)||!acceptedAt.isBefore(quoteValidUntil)||acceptedAt.isAfter(recordedAt))throw new IllegalArgumentException("Invalid historical quote acceptance");
        }
        public String kind(){return "ACCEPTED_QUOTE";}
        public void validateAt(Basis expected,Instant now){basisAt(basis,expected,now);if(recordedAt.isAfter(now))throw new IllegalArgumentException("Future quote source");}
        public Map<String,Object> canonical(){var map=new TreeMap<String,Object>(basis.canonical());map.put("kind",kind());map.put("quoteRevisionId",quoteRevisionId.toString());map.put("issueId",issueId.toString());map.put("responseId",responseId.toString());map.put("evidenceVersionId",evidenceVersionId.toString());map.put("deliveredAt",deliveredAt.toString());map.put("acceptedAt",acceptedAt.toString());map.put("quoteValidUntil",quoteValidUntil.toString());map.put("recordedAt",recordedAt.toString());return Collections.unmodifiableMap(map);}
        @Override public String toString(){return "AcceptedQuotePreparationSource[protected]";}
    }
    record DirectAuthorization(Basis basis,UUID requestId,UUID decisionId,Instant effectiveFrom,Instant effectiveUntil) implements ContractPreparationSource {
        public DirectAuthorization {
            required(basis);required(requestId);required(decisionId);time(effectiveFrom);
            if(effectiveUntil!=null){time(effectiveUntil);if(!effectiveUntil.isAfter(effectiveFrom))throw new IllegalArgumentException("Empty authorization interval");}
        }
        public String kind(){return "DIRECT_AUTHORIZATION";}
        public void validateAt(Basis expected,Instant now){basisAt(basis,expected,now);if(now.isBefore(effectiveFrom)||effectiveUntil!=null&&!now.isBefore(effectiveUntil))throw new IllegalArgumentException("Direct preparation authorization not effective");}
        public Map<String,Object> canonical(){var map=new TreeMap<String,Object>(basis.canonical());map.put("kind",kind());map.put("requestId",requestId.toString());map.put("decisionId",decisionId.toString());map.put("effectiveFrom",effectiveFrom.toString());map.put("effectiveUntil",effectiveUntil==null?null:effectiveUntil.toString());return Collections.unmodifiableMap(map);}
        @Override public String toString(){return "DirectContractPreparationSource[protected]";}
    }
}
