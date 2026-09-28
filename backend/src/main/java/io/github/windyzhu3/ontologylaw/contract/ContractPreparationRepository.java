package io.github.windyzhu3.ontologylaw.contract;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Owner persistence only. Caller supplies authorization, audit, receipt and responsibility transaction. */
public interface ContractPreparationRepository {
    interface Codec { String encode(Map<String,Object> body); Map<String,Object> decode(String body); }
    record Basis(Subject opportunity,Subject responsibility,UUID owner,UUID customerConfirmation) {
        public Basis {
            Objects.requireNonNull(opportunity);Objects.requireNonNull(responsibility);Objects.requireNonNull(owner);Objects.requireNonNull(customerConfirmation);
            if(!opportunity.type().equals("opportunity.opportunity")||opportunity.revision()==null||responsibility.revision()==null
                    ||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(responsibility.type()))throw new IllegalArgumentException("Exact preparation responsibility required");
        }
        @Override public String toString(){return "ContractPreparationRepository.Basis[protected]";}
    }
    record Comment(String value) {
        public Comment {value=ContractInputValidation.text(value,4000);}
        @Override public String toString(){return "ContractComment[protected]";}
    }
    record Draft(Subject selector,Basis basis,UUID previous,String commercialDigest,Map<String,Object> commercial,Map<String,Object> preparation,Instant createdAt) {
        @SuppressWarnings("unchecked") public Draft {
            commercial=(Map<String,Object>)ContractCanonicalJson.freeze(commercial);
            preparation=(Map<String,Object>)ContractCanonicalJson.freeze(preparation);
        }
        @Override public String toString(){return "ContractPreparationDraft[protected]";}
    }
    Subject request(Connection c,UUID tenant,Basis basis,UUID previous,ContractVersionInput.CommercialTerms commercial,String reason)throws SQLException;
    Subject decide(Connection c,UUID tenant,UUID request,UUID actor,boolean approved,Instant until,String reason)throws SQLException;
    Subject saveDraft(Connection c,UUID tenant,Basis basis,ContractPreparationSources.Selection source,UUID previous,ContractVersionInput.CommercialTerms commercial,Map<String,Object> preparation)throws SQLException;
    Draft readDraft(Connection c,UUID tenant,UUID id)throws SQLException;
    static ContractPreparationRepository databaseBacked(ContractProtection protection,ContractPreparationSources sources,Codec codec) {
        return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractPreparationRepository(protection,sources,codec);
    }
}
