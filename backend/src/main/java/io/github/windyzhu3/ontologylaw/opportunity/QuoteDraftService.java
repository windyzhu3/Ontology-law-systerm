package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Opportunity-owned immutable saves; callers supply an authorized fenced command transaction. */
public interface QuoteDraftService {
 String TYPE="opportunity.quote_draft";
 interface Codec {String encode(Map<String,Object> value);Map<String,Object> decode(String body);}
 record Input(Subject opportunity,Subject responsibility,UUID owner,Subject confirmation,Subject previous,Map<String,Object> document){
  @Override public String toString(){return "QuoteDraftInput[protected]";}
 }
 record Version(Subject selector,Subject opportunity,Subject responsibility,UUID owner,Subject confirmation,Subject previous,Map<String,Object> document,Instant createdAt){
  @Override public String toString(){return "QuoteDraftVersion[protected]";}
 }
 Version save(Connection c,UUID tenant,Input input)throws SQLException;
 Version read(Connection c,UUID tenant,UUID draft)throws SQLException;
 Version latest(Connection c,UUID tenant,Subject opportunity,Subject responsibility,UUID owner)throws SQLException;
 static QuoteDraftService databaseBacked(OpportunityProgressProtection protection,Codec codec){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteDraftService(protection,codec);}
}
