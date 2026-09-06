package io.github.windyzhu3.ontologylaw.lead;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.Instant;import java.util.*;

/** Lead Owner fact port. Protected values and generated persistence types stay in this Owner. */
public interface LeadIngressService {
    record Lead(Subject selector,String source,Instant capturedAt,Instant createdAt,UUID party,String resolution,String disposition,UUID assignment,
            byte[] phone,byte[] ingressPhone,byte[] email,byte[] ingressEmail,boolean ingressEmpty) {
        public boolean missingContact(){return phone==null&&email==null&&ingressPhone==null&&ingressEmail==null;}
    }
    record Duplicate(Subject lead,Subject party) {}
    record Header(Subject selector,String source,UUID assignment) {}
    record Assignment(Subject selector,UUID lead,UUID owner,String state,Instant createdAt) {}
    Instant now(Connection c)throws SQLException;
    Lead read(Connection c,UUID tenant,UUID id)throws SQLException;
    Header header(Connection c,UUID tenant,UUID id)throws SQLException;
    Lead natural(Connection c,UUID tenant,String account,byte[] key)throws SQLException;
    void lock(Connection c,UUID tenant,UUID id)throws SQLException;
    void lockNatural(Connection c,UUID tenant,String account,byte[] key)throws SQLException;
    Lead capture(Connection c,UUID tenant,Map<String,Object> normalized,byte[] sourceKey,Instant now)throws SQLException;
    Duplicate duplicate(Connection c,UUID tenant,Lead lead,Instant cutoff)throws SQLException;
    Assignment assignment(Connection c,UUID tenant,UUID id)throws SQLException;
    boolean hasOpenAssignment(Connection c,UUID tenant,UUID lead)throws SQLException;
    Assignment assign(Connection c,UUID tenant,Lead lead,UUID owner,String reason,Instant now)throws SQLException;
    Lead update(Connection c,UUID tenant,Lead lead,String disposition,UUID linkedParty,Map<String,Object> ingress,UUID actor,UUID newAssignment,Instant now)throws SQLException;
    static LeadIngressService databaseBacked(LeadProtection protection){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqLeadRepository(protection,io.github.windyzhu3.ontologylaw.party.R1PartyReader.databaseBacked());}
}
