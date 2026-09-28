package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
/** Exact selectors at the HTTP/command trust boundary; business values remain owner validated. */
public record R2QuoteInput(CommandAuthorizationBinding.Quotes binding,Map<String,Object> values) {
 @SuppressWarnings("unchecked") public static R2QuoteInput parse(CommandEnvelope e){try{
  require(e.type().quotes()&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null&&e.actor().onBehalfAppointmentId()==null);
  var p=(Map<?,?>)e.payload();require(p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedDraft","expectedQuote","expectedWorkflow","values")));
  var o=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
  var raw=(Map<?,?>)p.get("responsibilityBasis");require(raw.keySet().equals(Set.of("id","revision")));var id=uuid(raw.get("id"));
  var basis=new Subject(id.equals(o.id())?"opportunity.opportunity":"opportunity.responsibility_handoff",id,revision(raw.get("revision")),null);
  var binding=new CommandAuthorizationBinding.Quotes(o,basis,selector(p.get("customerConfirmation"),"opportunity.customer_requirement_confirmation"),selector(p.get("expectedDraft"),"opportunity.quote_draft"),quoteSelector(p.get("expectedQuote")),selector(p.get("expectedWorkflow"),"opportunity.quote_workflow"));
  var values=(Map<String,Object>)p.get("values");require(values!=null&&CanonicalJson.encode(values).length()<=130000);CommandScope.quotes(e,binding);return new R2QuoteInput(binding,values);
 }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
 private static Subject quoteSelector(Object value){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","hash")));return new Subject("opportunity.quote_revision",uuid(p.get("id")),null,(String)p.get("hash"));}
 private static Subject selector(Object value,String type){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","revision")));long r=revision(p.get("revision"));require(r==0);return new Subject(type,uuid(p.get("id")),r,null);}
 private static UUID uuid(Object v){var s=(String)v;var id=UUID.fromString(s);require(id.toString().equals(s));return id;}
 private static long revision(Object v){require(v instanceof Integer||v instanceof Long);long r=((Number)v).longValue();require(r>=0&&r<=9007199254740991L);return r;}
 private static void require(boolean v){if(!v)throw new IllegalArgumentException();}
 @Override public String toString(){return "R2QuoteInput[protected]";}
}
