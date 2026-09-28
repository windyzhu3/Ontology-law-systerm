package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;

import java.sql.*;import java.time.Instant;import java.util.*;
public interface OpportunityCustomerRequirementsService {
 String DRAFT="opportunity.customer_requirement_draft", CONFIRMATION="opportunity.customer_requirement_confirmation";
 record Metadata(Subject selector,Subject opportunity,Subject responsibility,UUID owner,Subject draft,Subject previous,Instant createdAt){}
 record Snapshot(Subject selector,String kind,String name){}
 interface Codec{String encode(Map<String,Object> value);Map<String,Object> decode(String body);}
 record Version(Metadata metadata,Map<String,Object> document,List<Snapshot> partySnapshots,Subject sourceLead){@Override public String toString(){return "CustomerRequirementVersion[protected]";}}
 record Input(Subject opportunity,Subject responsibility,UUID owner,Subject expectedDraft,Subject expectedConfirmation,Map<String,Object> document){@Override public String toString(){return "CustomerRequirementInput[protected]";}}
 record Participant(Subject profileVersion,Snapshot party,String role){}
 class Blocked extends RuntimeException{private final String code;public Blocked(String code){super(code);this.code=code;}public String code(){return code;}}
 Metadata latestDraft(Connection c,UUID tenant,UUID opportunity,Subject basis,UUID owner)throws SQLException;
 List<Metadata> history(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 Version read(Connection c,UUID tenant,Subject exact)throws SQLException;
 Metadata save(Connection c,UUID tenant,Input input,Subject sourceLead,List<Snapshot> snapshots)throws SQLException;
 Metadata confirm(Connection c,UUID tenant,Input input,Map<String,Object> canonical,List<Participant> participants,Subject sourceLead)throws SQLException;
 List<Subject> participants(Connection c,UUID tenant,Subject confirmation)throws SQLException;
 static Metadata metadata(Connection c,UUID tenant,Subject exact)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcOpportunityCustomerRequirements.metadata(c,tenant,exact);}
 static OpportunityCustomerRequirementsService databaseBacked(OpportunityProgressProtection cipher,Codec codec){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcOpportunityCustomerRequirements(cipher,codec);}
}
