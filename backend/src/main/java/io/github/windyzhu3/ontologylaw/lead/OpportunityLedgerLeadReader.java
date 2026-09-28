package io.github.windyzhu3.ontologylaw.lead;
import java.sql.*;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
/** Minimal label source: metadata must be authorized before materializing the encrypted label. */
public interface OpportunityLedgerLeadReader {
 record Metadata(Subject selector,UUID partyId){}
 Metadata metadata(Connection c,UUID tenant,UUID id)throws SQLException;
 static Metadata metadataOnly(Connection c,UUID tenant,UUID id)throws SQLException{return io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqOpportunityLedgerLeadReader.readMetadata(c,tenant,id);}
 String label(Connection c,UUID tenant,Subject exact)throws SQLException;
 static OpportunityLedgerLeadReader databaseBacked(LeadProtection p){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqOpportunityLedgerLeadReader(p);}
}
