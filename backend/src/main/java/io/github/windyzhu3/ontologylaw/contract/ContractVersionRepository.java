package io.github.windyzhu3.ontologylaw.contract;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

/** Contract Owner persistence; caller owns authorization, audit, receipt and successor transaction. */
public interface ContractVersionRepository {
    record Requirement(String code,UUID approver) {
        public Requirement { if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Exact approval requirement required");Objects.requireNonNull(approver); }
    }
    record Policy(UUID id,String digest,List<Requirement> requirements) {
        public Policy {
            Objects.requireNonNull(id);ContractInputValidation.digest(digest);
            if(requirements==null||requirements.isEmpty()||requirements.size()>100||requirements.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Explicit approval requirements required");
            requirements=requirements.stream().sorted(Comparator.comparing(Requirement::code)).toList();
            if(requirements.stream().map(Requirement::code).distinct().count()!=requirements.size())throw new IllegalArgumentException("Duplicate approval requirement");
        }
        @Override public String toString(){return "ContractApprovalPolicy[protected]";}
    }
    Policy currentPolicy(Connection c,UUID tenant,UUID organization)throws SQLException;
    String partySnapshotDigest(Connection c,UUID tenant,UUID confirmation)throws SQLException;
    Subject startAnchor(Connection c,UUID tenant,UUID actor,ContractPreparationSource.Basis basis,ContractPreparationSources.Selection source)throws SQLException;
    Subject formVersion(Connection c,UUID tenant,UUID draftId,UUID actor,ContractVersionInput input,Policy policy)throws SQLException;
    static ContractVersionRepository databaseBacked(ContractProtection protection,ContractPreparationSources sources,ContractPreparationRepository.Codec codec) {
        return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractVersionRepository(protection,sources,codec);
    }
}
