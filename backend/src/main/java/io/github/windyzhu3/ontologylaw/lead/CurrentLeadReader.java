package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Source facts on the caller's QUERY transaction. Sensitive values must pass disclosure audit before any response.
 * capturedPhone/capturedEmail remain original fields; effectiveContact uses only the four V860-approved ingress columns.
 */
public interface CurrentLeadReader {
    record Lead(Subject selector,String sourceChannel,String sourceAccount,Instant capturedAt,String capturedName,
            String capturedPhone,String capturedEmail,String legalNeedSummary,String cityCode,String serviceCategoryCode,
            String jurisdictionCode,String urgencyCode,UUID partyId,String resolution,String disposition,UUID currentAssignmentId) {}
    record Party(Subject selector,String canonicalName,String status) {}
    record Assignment(Subject selector,UUID leadId,UUID owner,String state,Instant assignedAt) {}
    record ContactResult(Subject selector,UUID leadId,UUID assignmentId,UUID taskId,long contactNo,String channel,
            String resultCode,String summary,UUID evidenceSubmissionId,Instant resultedAt) {}
    record EffectiveContact(Subject selector,String phone,String email) {}
    record Duplicate(Subject lead,Subject party) {}
    EffectiveContact effectiveContact(Connection c,UUID tenant,UUID leadId)throws SQLException;
    Duplicate duplicate(Connection c,UUID tenant,UUID leadId,Instant cutoff)throws SQLException;
    Lead read(Connection c,UUID tenant,UUID leadId)throws SQLException;
    /** Exact selector only: authorize the current revision before reading any protected field. */
    Subject selector(Connection c,UUID tenant,UUID leadId)throws SQLException;
    Party namedParty(Connection c,UUID tenant,UUID partyId)throws SQLException;
    Assignment assignment(Connection c,UUID tenant,UUID assignmentId)throws SQLException;
    ContactResult contactResult(Connection c,UUID tenant,UUID resultId)throws SQLException;
    static java.util.Map<String,Object> validatedDraftValues(String actionCode,java.util.Map<String,Object> values) {
        return LeadInputs.candidate(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.valueOf(actionCode),values);
    }
    static CurrentLeadReader databaseBacked(LeadProtection protection) {
        return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqCurrentLeadReader(protection,io.github.windyzhu3.ontologylaw.party.R1PartyReader.databaseBacked());
    }
}
