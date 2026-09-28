package io.github.windyzhu3.ontologylaw.identity;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
/** Named direct-human ledger authority; never interprets exception grants as ledger grants. */
public final class OpportunityLedgerAuthorityReader {
 public static final String READ="OPPORTUNITY_LEDGER_READ", OWN="SALES_OPPORTUNITY_OWNER";
 private final OpportunityOwnerExceptionAuthorityReader current=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
 public static OpportunityLedgerAuthorityReader databaseBacked(){return new OpportunityLedgerAuthorityReader();}
 public boolean hasAuthority(Connection c,Actor a,Instant now)throws SQLException{return a!=null&&(current.hasAuthority(c,a,READ,now)||current.hasAuthority(c,a,OWN,now));}
 public String code(Connection c,Actor a,UUID owner,UUID organization,List<Subject> facts,Instant now)throws SQLException {
  if(current.hasAuthority(c,a,READ,now)&&permitted(c,a,organization,facts,READ))return READ;
  return a.appointmentId().equals(owner)&&current.hasAuthority(c,a,OWN,now)&&permitted(c,a,organization,facts,OWN)?OWN:null;
 }
 public UUID historicalOrganization(Connection c,UUID tenant,UUID owner)throws SQLException{return current.historicalOrganization(c,tenant,owner);}
 public boolean permitted(Connection c,Actor a,UUID organization,List<Subject> facts,String code)throws SQLException{if(code==null||facts.isEmpty())return false;
  return R1AuthorityReader.databaseBacked().authorizeAll(c,a,facts,organization,"OPPORTUNITY_OWNER",code).size()==facts.size();}
 public AuthorizationSnapshot evidence(Connection c,Actor a,UUID organization,Subject fact,String code)throws SQLException {
  return R1AuthorityReader.databaseBacked().authorize(c,a,fact,organization,"OPPORTUNITY_OWNER",code);
 }
}
