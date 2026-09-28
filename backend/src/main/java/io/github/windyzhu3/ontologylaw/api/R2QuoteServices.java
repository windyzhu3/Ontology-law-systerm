package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;import java.util.*;
public final class R2QuoteServices {
 private R2QuoteServices(){}
 public static QuoteWorkflowService create(OpportunityProgressProtection protection){return QuoteWorkflowService.databaseBacked(protection,new QuoteDraftService.Codec(){public String encode(Map<String,Object> value){return CanonicalJson.encode(value);}@SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}},new QuoteWorkflowPorts(protection));}
 private static final OpportunityProgressProtection METADATA_ONLY=new OpportunityProgressProtection(){public byte[] encrypt(UUID a,UUID b,UUID c,String d){throw new UnsupportedOperationException();}public String decrypt(UUID a,UUID b,UUID c,byte[] d){throw new UnsupportedOperationException();}};
 public static List<Subject> facts(Connection c,UUID tenant,UUID id)throws SQLException{return R2LedgerSourceFacts.quote(c,tenant,id,()->create(METADATA_ONLY).protectedFacts(c,tenant,id));}
 public static R1AuthorizationFacts.Quotes authorization(Connection c,UUID tenant,CommandAuthorizationBinding.Quotes b)throws SQLException{
  var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,b.opportunity().id());if(opening==null)return null;
  var all=new LinkedHashSet<Subject>(facts(c,tenant,b.opportunity().id()));all.add(opening.selector());all.add(b.opportunity());
  var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());if(owner==null)return null;all.add(owner.basis());
  for(var exact:new Subject[]{b.confirmation(),b.draft(),b.quote(),b.workflow()})if(exact!=null&&!all.contains(exact))return null;
  if(!all.contains(b.basis()))return null;
  return new R1AuthorizationFacts.Quotes(opening.selector(),List.copyOf(all),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),owner.appointmentId());
 }
}
