package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
/** Exact selectors at the HTTP/command trust boundary; business values remain owner validated. */
public record R2ContractInput(CommandAuthorizationBinding.Contracts binding,Map<String,Object> values) {
 @SuppressWarnings("unchecked") public static R2ContractInput parse(CommandEnvelope e){try{
  require(e.type().contracts()&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null&&e.actor().onBehalfAppointmentId()==null);
  var p=(Map<?,?>)e.payload();require(p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedContract","expectedDraft","expectedVersion","expectedWorkflow","values")));
  var o=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
  var raw=(Map<?,?>)p.get("responsibilityBasis");require(raw.keySet().equals(Set.of("id","revision")));var id=uuid(raw.get("id"));
  var basis=new Subject(id.equals(o.id())?"opportunity.opportunity":"opportunity.responsibility_handoff",id,revision(raw.get("revision")),null);
  var binding=new CommandAuthorizationBinding.Contracts(o,basis,selector(p.get("customerConfirmation"),"opportunity.customer_requirement_confirmation"),anchor(p.get("expectedContract")),selector(p.get("expectedDraft"),"contract.preparation_draft"),versionSelector(p.get("expectedVersion")),selector(p.get("expectedWorkflow"),"contract.preparation_workflow"));
  var values=(Map<String,Object>)p.get("values");require(values!=null&&CanonicalJson.encode(values).length()<=130000);CommandScope.contracts(e,binding);return new R2ContractInput(binding,values);
 }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
 private static Subject anchor(Object value){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","revision")));return new Subject("contract.contract",uuid(p.get("id")),revision(p.get("revision")),null);}
 private static Subject versionSelector(Object value){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","hash")));String hash=(String)p.get("hash");if(hash.matches("[0-9a-f]{64}"))hash=Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(hash));return new Subject("contract.contract_revision",uuid(p.get("id")),null,hash);}
 private static Subject selector(Object value,String type){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","revision")));long r=revision(p.get("revision"));require(r==0);return new Subject(type,uuid(p.get("id")),r,null);}
 private static UUID uuid(Object v){var s=(String)v;var id=UUID.fromString(s);require(id.toString().equals(s));return id;}
 private static long revision(Object v){require(v instanceof Integer||v instanceof Long);long r=((Number)v).longValue();require(r>=0&&r<=9007199254740991L);return r;}
 private static void require(boolean v){if(!v)throw new IllegalArgumentException();}
 @SuppressWarnings("unchecked") public static Map<String,Object> ownerPayload(CommandEnvelope e){var parsed=parse(e);var payload=new TreeMap<String,Object>((Map<String,Object>)e.payload());if(parsed.binding().version()!=null){var version=parsed.binding().version();payload.put("expectedVersion",Map.of("id",version.id().toString(),"hash",HexFormat.of().formatHex(Base64.getUrlDecoder().decode(version.hash()))));}return Collections.unmodifiableMap(payload);}
 @Override public String toString(){return "R2ContractInput[protected]";}
}
