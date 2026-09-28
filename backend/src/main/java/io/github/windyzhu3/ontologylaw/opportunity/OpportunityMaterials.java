package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.*;import java.util.*;
/** Append-only business versions; the evidence chain is supplied as exact owner facts. */
public interface OpportunityMaterials {
 String VERSION="opportunity.material_version";
 record Version(Subject selector,UUID opportunity,UUID item,Subject previous,UUID upload,UUID source,UUID submission,UUID binding,String purpose,UUID receivedBy,Instant receivedAt){}
 List<Version> history(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 /** Current heads selected before per-source disclosure authorization. */
 List<Version> currentVersions(Connection c,UUID tenant,UUID opportunity,int limit)throws SQLException;
 Version version(Connection c,UUID tenant,UUID id)throws SQLException;
 boolean current(Connection c,UUID tenant,UUID id)throws SQLException;
 Version accept(Connection c,UUID tenant,UUID opportunity,UUID previous,UUID upload,UUID source,UUID submission,UUID binding,String purpose,UUID actor,Instant at,byte[] ciphertext,byte[] digest)throws SQLException;
 static OpportunityMaterials databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcOpportunityMaterials();}
}
