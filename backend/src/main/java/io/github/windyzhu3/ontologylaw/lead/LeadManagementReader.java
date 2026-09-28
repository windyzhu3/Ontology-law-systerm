package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Lead-owned raw sources. The composition caller authorizes exact metadata before opening text. */
public interface LeadManagementReader {
 record Metadata(Subject selector,String sourceAccount,String sourceChannel,Instant capturedAt,UUID assignment,UUID party){}
 record Summary(String customerName,String contactName,String capturedName,String phone,String email){}
 List<Metadata> scan(Connection c,UUID tenant,UUID after,int limit)throws SQLException;
 Metadata metadata(Connection c,UUID tenant,UUID id)throws SQLException;
 Summary summary(Connection c,UUID tenant,Subject exact)throws SQLException;
 Subject latestContact(Connection c,UUID tenant,UUID lead)throws SQLException;
 static LeadManagementReader databaseBacked(LeadProtection protection){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JdbcLeadManagementReader(protection);}
}
