package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

/** Append-only owner port. Writes on the caller's active AUDIT capability connection. */
public interface AuditAppender {
    record BootstrapEntry(UUID id,UUID commandId,UUID correlationId,String manifestDigest,String operatorAssertion,
            io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.Facts facts) {
        public BootstrapEntry{Objects.requireNonNull(id);Objects.requireNonNull(commandId);Objects.requireNonNull(correlationId);Objects.requireNonNull(facts);if(manifestDigest==null||!manifestDigest.matches("[0-9a-f]{64}")||operatorAssertion==null||operatorAssertion.isBlank())throw new IllegalArgumentException("Invalid bootstrap audit");}
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_IDENTITY_BOOTSTRAP_V1","version",1,"manifestDigest",manifestDigest,"operatorAssertion",operatorAssertion,"founderPrincipalId",facts.principal().toString(),"rootOrganizationId",facts.root().toString(),"appointmentId",facts.appointment().toString(),"grantIds",facts.grants().stream().map(UUID::toString).toList()));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,BootstrapEntry entry)throws SQLException{throw new SQLException("Bootstrap audit unsupported","0A000");}
    default BootstrapEntry bootstrapOriginal(Connection c,UUID tenant,UUID command)throws SQLException{throw new SQLException("Bootstrap audit unsupported","0A000");}
    record SelfDisclosureEntry(UUID id,UUID correlationId,UUID tenantId,Subject principal,UUID ownAppointment,
            java.time.Instant checkedAt,List<Subject> sources) {
        public SelfDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(tenantId);Objects.requireNonNull(checkedAt);
            sources=List.copyOf(sources);
            if(!"identity.principal".equals(principal.type())||sources.isEmpty()||sources.size()>101||!sources.contains(principal)
                    ||sources.stream().anyMatch(s->s.revision()==null||!s.equals(principal)&&!"identity.appointment".equals(s.type()))
                    ||new HashSet<>(sources).size()!=sources.size())throw new IllegalArgumentException("Invalid self disclosure");
        }
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_IDENTITY_SELF_DISCLOSURE_V1","version",1,"operationId","getSessionContext","responseMode","BODY","resultCount",sources.size(),"disclosedSources",sources.stream().map(s->Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision())).toList()));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,SelfDisclosureEntry entry)throws SQLException{throw new SQLException("Self disclosure unsupported","0A000");}
    record Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
            AuthorizationSnapshot authorization,String summary,byte[] summaryDigest,int schemaVersion) {
        public Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
                AuthorizationSnapshot authorization,String summary,byte[] summaryDigest) {
            this(id,commandId,commandType,correlationId,result,authorization,summary,summaryDigest,1);
        }
        public Entry {summaryDigest=summaryDigest.clone();if(schemaVersion!=1&&schemaVersion!=2)throw new IllegalArgumentException("Unsupported command Audit schema");}
        @Override public byte[] summaryDigest(){return summaryDigest.clone();}
    }
    void append(Connection connection,Entry entry) throws SQLException;
    record ReceiptDisclosureEntry(UUID id,UUID correlationId,UUID commandId,UUID receiptId,
            Subject disclosedSource,Subject authorizationAnchor,AuthorizationSnapshot authorization) {
        public ReceiptDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(commandId);Objects.requireNonNull(receiptId);
            if(!"execution.command_receipt".equals(disclosedSource.type())||disclosedSource.hash()==null||!receiptId.equals(disclosedSource.id())
                    ||!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject()))throw new IllegalArgumentException("Invalid receipt disclosure");
        }
        public String summary() {
            return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_COMMAND_RECEIPT_DISCLOSURE_V1","version",1,"responseMode","BODY",
                    "commandId",commandId.toString(),"receiptId",receiptId.toString(),"disclosedSource",selector(disclosedSource),"authorizationAnchor",selector(authorizationAnchor)));
        }
        private static Map<String,Object> selector(Subject s) {return s.revision()==null?Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash()):Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision());}
        public byte[] summaryDigest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection connection,ReceiptDisclosureEntry entry)throws SQLException {throw new SQLException("Receipt disclosure unsupported","0A000");}
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
