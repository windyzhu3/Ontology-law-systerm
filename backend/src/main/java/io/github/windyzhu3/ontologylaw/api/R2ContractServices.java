package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import java.sql.*;import java.util.*;
public final class R2ContractServices {
 private R2ContractServices(){}
 public static ContractWorkflowService create(ContractProtection contractProtection,OpportunityProgressProtection protection,MaterialObjectStore objects){return create(contractProtection,protection,objects,null);}
 public static ContractWorkflowService create(ContractProtection contractProtection,OpportunityProgressProtection protection,MaterialObjectStore objects,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments){return create(contractProtection,protection,objects,payments,new BusinessResponsibilityRouting(List.of()));}
 public static ContractWorkflowService create(ContractProtection contractProtection,OpportunityProgressProtection protection,MaterialObjectStore objects,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments,BusinessResponsibilityRouting routing){return ContractWorkflowService.databaseBacked(contractProtection,R2ContractPreparationSources.create(protection),new ContractPreparationRepository.Codec(){public String encode(Map<String,Object> value){return CanonicalJson.encode(value);}@SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}},payments==null?new ContractWorkflowPorts(protection,objects,routing):new PaymentEnabledContractPorts(protection,objects,payments,routing));}
 private static final ContractProtection CONTRACT_METADATA_ONLY=new ContractProtection(){public byte[] seal(UUID a,UUID b,UUID c,Kind k,String d){throw new UnsupportedOperationException();}public String open(UUID a,UUID b,UUID c,Kind k,byte[] d){throw new UnsupportedOperationException();}};
 private static final OpportunityProgressProtection METADATA_ONLY=new OpportunityProgressProtection(){public byte[] encrypt(UUID a,UUID b,UUID c,String d){throw new UnsupportedOperationException();}public String decrypt(UUID a,UUID b,UUID c,byte[] d){throw new UnsupportedOperationException();}};
 public static List<Subject> facts(Connection c,UUID tenant,UUID id)throws SQLException{return R2LedgerSourceFacts.contractMetadata(c,tenant,id,()->create(CONTRACT_METADATA_ONLY,METADATA_ONLY,null).protectedFacts(c,tenant,id));}
 public static R1AuthorizationFacts.Contracts recoveryAuthorization(Connection c,UUID tenant,CommandAuthorizationBinding.ContractRecovery b)throws SQLException{var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,b.opportunity().id());if(opening==null)return null;var all=new LinkedHashSet<Subject>(b.sourceKind().startsWith("TRANSFER_")?R2TransferRecoveryService.facts(c,tenant,b.opportunity().id()):facts(c,tenant,b.opportunity().id()));if(!all.contains(b.source())||!all.contains(b.basis())||b.workflow()!=null&&!all.contains(b.workflow()))return null;all.add(b.opportunity());var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());return owner==null?null:new R1AuthorizationFacts.Contracts(opening.selector(),List.copyOf(all),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),owner.appointmentId());}
 public static R1AuthorizationFacts.Contracts authorization(Connection c,UUID tenant,CommandAuthorizationBinding.Contracts b)throws SQLException{
  var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,b.opportunity().id());if(opening==null)return null;
  var all=new LinkedHashSet<Subject>(facts(c,tenant,b.opportunity().id()));all.add(opening.selector());all.add(b.opportunity());
  var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());if(owner==null)return null;all.add(owner.basis());
  for(var exact:new Subject[]{b.confirmation(),b.draft(),b.version(),b.workflow()})if(exact!=null&&!all.contains(exact))return null;
  if(b.contract()!=null){var exact=b.contract();var current=all.stream().filter(f->f.type().equals("contract.contract")&&f.id().equals(exact.id())).findFirst().orElse(null);if(current==null||current.revision()<exact.revision())return null;all.add(exact);}
  if(!all.contains(b.basis()))return null;
  return new R1AuthorizationFacts.Contracts(opening.selector(),List.copyOf(all),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),owner.appointmentId());
 }
}
