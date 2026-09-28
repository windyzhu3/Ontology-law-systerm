package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection;
import java.time.Instant;
import java.util.*;

/** Trusted Owner composition only; not registered as a web endpoint or command. */
public final class R2ContractPreparationSources {
    private R2ContractPreparationSources() {}
    public static ContractPreparationSources create(OpportunityProgressProtection protection) {
        Objects.requireNonNull(protection);
        return ContractPreparationSources.databaseBacked(new ContractPreparationSources.QuoteBody() {
            public String decrypt(UUID tenant,UUID opportunity,UUID quote,byte[] encrypted) {
                return protection.decryptQuote(tenant,opportunity,quote,false,encrypted);
            }
            public ContractVersionInput.CommercialTerms commercial(String clear,ContractPreparationSource.Basis expected,Instant validUntil) {
                var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(clear);
                if(!node.path("contract").asText().equals("R2_QUOTE_PACKAGE_V1")
                        ||!node.path("tenantId").asText().equals(expected.tenantId().toString())
                        ||!node.path("opportunityId").asText().equals(expected.opportunityId().toString())
                        ||!node.path("customerConfirmationId").asText().equals(expected.customerConfirmationId().toString())
                        ||!Instant.parse(node.path("validUntil").asText()).equals(validUntil))
                    throw new ContractPreparationSources.Unavailable();
                var lines=new ArrayList<ContractVersionInput.FeeLine>();
                if(!node.path("lines").isArray())throw new ContractPreparationSources.Unavailable();
                for(var line:node.path("lines")) {
                    if(!line.path("discount").isBoolean())throw new ContractPreparationSources.Unavailable();
                    lines.add(new ContractVersionInput.FeeLine(line.path("description").asText(),integer(line.path("amountMinor")),line.path("discount").asBoolean()));
                }
                var fee=node.path("conditionalFee");
                var conditional=fee.isNull()?null:new ContractVersionInput.ConditionalFee(fee.path("basis").asText(),Math.toIntExact(integer(fee.path("rateBasisPoints"))),integer(fee.path("capMinor")));
                var terms=new ContractVersionInput.CommercialTerms(node.path("currency").asText(),node.path("scope").asText(),lines,conditional,node.path("paymentTerms").asText());
                if(terms.totalMinor()!=integer(node.path("totalMinor")))throw new ContractPreparationSources.Unavailable();
                return terms;
            }
        });
    }
    private static long integer(tools.jackson.databind.JsonNode node) {
        if(!node.isIntegralNumber()||!node.canConvertToLong())throw new ContractPreparationSources.Unavailable();
        return node.longValue();
    }
}
