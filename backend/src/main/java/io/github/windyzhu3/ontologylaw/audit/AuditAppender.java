package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

/** Append-only owner port. Writes on the caller's active AUDIT capability connection. */
public interface AuditAppender {
    record Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
            AuthorizationSnapshot authorization,String summary,byte[] summaryDigest) {
        public Entry {summaryDigest=summaryDigest.clone();}
        @Override public byte[] summaryDigest(){return summaryDigest.clone();}
    }
    void append(Connection connection,Entry entry) throws SQLException;
    enum ResponseMode { BODY, CACHE_REVALIDATED }
    record ReadDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,Subject authorizationAnchor,
            AuthorizationSnapshot authorization,ResponseMode responseMode) {
        public ReadDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(disclosedSource);
            Objects.requireNonNull(authorizationAnchor);Objects.requireNonNull(authorization);Objects.requireNonNull(responseMode);
            if(!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject()))throw new IllegalArgumentException("Authorized anchor required");
            Set<String> anchors=switch(disclosedSource.type()) {
                case "lead.lead","party.party","lead.lead_assignment","responsibility.task_occurrence","responsibility.decision_record","evidence.evidence_submission","evidence.evidence_binding" -> Set.of(disclosedSource.type());
                case "responsibility.action_draft" -> Set.of("responsibility.task_occurrence");
                case "lead.lead_contact_result" -> Set.of("lead.lead");
                case "identity.appointment","identity.principal","identity.organization_unit" -> Set.of("responsibility.task_occurrence","lead.lead");
                default -> throw new IllegalArgumentException("Unregistered disclosed source");
            };
            if(!anchors.contains(authorizationAnchor.type()))throw new IllegalArgumentException("Unregistered source binding");
            if(anchors.contains(disclosedSource.type())&&!disclosedSource.equals(authorizationAnchor))throw new IllegalArgumentException("Exact direct source required");
        }
        public String summary() {
            return "{\"profile\":\"R1_CURRENT_WORKCARD_DISCLOSURE_V1\",\"version\":1,\"responseMode\":\""+responseMode
                +"\",\"fieldGroups\":[\"CURRENT_WORKCARD\"],\"disclosedSource\":"+selector(disclosedSource)+",\"authorizationAnchor\":"+selector(authorizationAnchor)+"}";
        }
        private static String selector(Subject s) {
            return "{\"type\":\""+s.type()+"\",\"id\":\""+s.id()+"\",\"revision\":"+s.revision()+",\"hash\":"+(s.hash()==null?"null":"\""+s.hash()+"\"")+"}";
        }
        public byte[] summaryDigest() {
            try{return java.security.MessageDigest.getInstance("SHA-256").digest(summary().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    default void append(Connection connection,ReadDisclosureEntry entry)throws SQLException {
        throw new SQLException("Read disclosure append not supported","0A000");
    }
    static AuditAppender databaseBacked(String executionNodeCode){return new io.github.windyzhu3.ontologylaw.audit.internal.persistence.JooqAuditAppender(executionNodeCode);}
}
