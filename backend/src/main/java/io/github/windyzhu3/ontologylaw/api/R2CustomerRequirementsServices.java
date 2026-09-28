package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.identity.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;import io.github.windyzhu3.ontologylaw.opportunity.*;import io.github.windyzhu3.ontologylaw.party.*;
import java.sql.*;import java.util.*;
/** Trusted composition of Party and Opportunity owners, with explicit per-fact checks. */
public final class R2CustomerRequirementsServices {
 private R2CustomerRequirementsServices(){}
 public static OpportunityCustomerRequirementsService create(OpportunityProgressProtection cipher){return OpportunityCustomerRequirementsService.databaseBacked(cipher,new OpportunityCustomerRequirementsService.Codec(){public String encode(Map<String,Object> value){return CanonicalJson.encode(value);}@SuppressWarnings("unchecked") public Map<String,Object> decode(String body){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(body,Map.class);}});}
 public static Subject partySubject(CustomerPartyProfiles.Profile p){return new Subject("party.party",p.selector().id(),p.selector().revision(),null);}
 public static OpportunityCustomerRequirementsService.Snapshot snapshot(CustomerPartyProfiles.Profile p){return new OpportunityCustomerRequirementsService.Snapshot(partySubject(p),p.kind(),p.name());}
 public static Map<String,Object> party(OpportunityCustomerRequirementsService.Snapshot p){return Map.of("selector",selector(p.selector()),"kind",p.kind(),"name",p.name());}

 public static List<Subject> sourceFacts(Connection c,UUID tenant,Subject opportunity,Subject exact)throws SQLException{return R2LedgerSourceFacts.customer(c,tenant,opportunity,exact,()->loadSourceFacts(c,tenant,opportunity,exact));}
 private static List<Subject> loadSourceFacts(Connection c,UUID tenant,Subject opportunity,Subject exact)throws SQLException{var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());if(opening==null)return null;var out=new LinkedHashSet<Subject>(R2OpportunityClosureServices.protectedFacts(c,tenant,opening.selector()));out.add(opportunity);out.add(opening.selector());if(exact!=null){var m=OpportunityCustomerRequirementsService.metadata(c,tenant,exact);if(m==null||!m.opportunity().id().equals(opportunity.id()))return null;out.add(m.selector());out.add(m.opportunity());out.add(m.responsibility());if(m.draft()!=null)out.add(m.draft());if(m.previous()!=null)out.add(m.previous());out.addAll(create(nullProtection()).participants(c,tenant,exact));}return List.copyOf(out);}
 private static OpportunityProgressProtection nullProtection(){return new OpportunityProgressProtection(){public byte[] encrypt(UUID a,UUID b,UUID c,String d){throw new UnsupportedOperationException();}public String decrypt(UUID a,UUID b,UUID c,byte[] d){throw new UnsupportedOperationException();}};}
 public static void requireAuthority(Connection c,Actor actor,UUID org,List<Subject> facts,String code)throws SQLException{if(!OpportunityOwnerExceptionAuthorityReader.databaseBacked().permitted(c,actor,org,facts,code))throw new CommandHandler.Rejected("NOT_AUTHORIZED");}
 public static Map<String,Object> selector(Subject s){return s==null?null:Map.of("id",s.id().toString(),"revision",s.revision());}
 public static Map<String,Object> party(CustomerPartyProfiles.Profile p){return Map.of("selector",selector(partySubject(p)),"kind",p.kind(),"name",p.name());}
 public static Subject selected(Map<?,?> p){var s=(Map<?,?>)p.get("party");return s==null?null:new Subject("party.party",UUID.fromString((String)s.get("id")),((Number)s.get("revision")).longValue(),null);}
}
