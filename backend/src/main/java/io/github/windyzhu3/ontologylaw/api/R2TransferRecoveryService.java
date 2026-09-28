package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.*;import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.opportunity.*;import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.util.*;
/** SERVICE orchestration composes Owners. Discovery returns metadata only and never creates a duty. */
final class R2TransferRecoveryService {
 private final TransferWorkflowPorts ports;private final TransferWorkflowService workflows;private final OpportunityCommandReader opportunities;
 private static final TransferWorkflowReader READER=TransferWorkflowReader.databaseBacked();
 R2TransferRecoveryService(TransferWorkflowPorts ports,OpportunityProgressProtection protection){this.ports=ports;this.workflows=TransferWorkflowService.databaseBacked(ports);this.opportunities=OpportunityCommandReader.databaseBacked(protection);}
 boolean configured(UUID tenant){return ports.configured(tenant);}
 ContractWorkflowService.RecoveryPage page(Connection c,Actor actor,int limit,UUID after)throws SQLException{
  if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw denied();
  var scanned=ContractTransferSourceReader.databaseBacked().page(c,actor.tenantId(),limit,after);var candidates=new ArrayList<ContractWorkflowService.RecoveryCandidate>();
  for(var source:scanned){var h=opportunities.header(c,actor.tenantId(),source.opportunityId());if(h==null||h.closed())continue;var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),h.selector());if(owner==null)continue;
   try{ports.authorizePreparation(c,actor,h.selector());var state=READER.forContract(c,actor.tenantId(),source.contractId());if(state==null){if(ports.route(c,actor,h.selector())==null)continue;candidates.add(new ContractWorkflowService.RecoveryCandidate(h.selector(),owner.basis(),new Subject("contract.execution_verification",source.verificationId(),0L,null),null,owner.appointmentId()));}else if(workflows.recoveryNeeded(c,actor,state.workflow()))candidates.add(new ContractWorkflowService.RecoveryCandidate(h.selector(),owner.basis(),state.workflow(),state.workflow(),owner.appointmentId()));}
   catch(TransferSubmissionService.Blocked|TransferWorkflowService.Blocked blocked){if(!Set.of("NOT_AUTHORIZED","STALE_SUBJECT").contains(blocked.getMessage()))throw blocked;}
  }
  return new ContractWorkflowService.RecoveryPage(candidates,scanned.isEmpty()?after:scanned.getLast().contractId(),scanned.size()<limit);
 }
 void validateEligibility(Connection c,Actor actor,CommandAuthorizationBinding.ContractRecovery b)throws SQLException{
  if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw denied();
  var h=opportunities.header(c,actor.tenantId(),b.opportunity().id());
  if(h==null||h.closed()||!h.selector().equals(b.opportunity()))throw stale();
  var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),h.selector());
  if(owner==null||!owner.basis().equals(b.basis()))throw stale();
  ports.authorizePreparation(c,actor,h.selector());
  var source=ContractTransferSourceReader.databaseBacked().forOpportunity(c,actor.tenantId(),h.selector().id()).orElseThrow(R2TransferRecoveryService::stale);
  var current=READER.forContract(c,actor.tenantId(),source.contractId());
  if("TRANSFER_HANDOFF".equals(b.sourceKind())){
   if(current!=null||b.workflow()!=null||!new Subject("contract.execution_verification",source.verificationId(),0L,null).equals(b.source())||ports.route(c,actor,h.selector())==null)throw stale();
  }else if(!"TRANSFER_RECOVERY".equals(b.sourceKind())||current==null||!current.workflow().equals(b.source())||!current.workflow().equals(b.workflow())||!workflows.recoveryNeeded(c,actor,current.workflow()))throw stale();
 }
 Subject reconcile(Connection c,Actor actor,CommandAuthorizationBinding.ContractRecovery b)throws SQLException{
  if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw denied();var h=opportunities.header(c,actor.tenantId(),b.opportunity().id());if(h==null||h.closed()||!h.selector().equals(b.opportunity()))throw stale();var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),h.selector());if(owner==null||!owner.basis().equals(b.basis()))throw stale();
  var source=ContractTransferSourceReader.databaseBacked().forOpportunity(c,actor.tenantId(),b.opportunity().id()).orElseThrow(R2TransferRecoveryService::stale);var current=READER.forContract(c,actor.tenantId(),source.contractId());
  if("TRANSFER_HANDOFF".equals(b.sourceKind())){if(current!=null||b.workflow()!=null||!new Subject("contract.execution_verification",source.verificationId(),0L,null).equals(b.source()))throw stale();try{var request=TransferSubmissionService.databaseBacked(ports).prepare(c,actor,b.opportunity(),source.contractId());return workflows.start(c,actor,request);}catch(TransferSubmissionService.Blocked blocked){throw new TransferWorkflowService.Blocked(blocked.code());}}
  if(!"TRANSFER_RECOVERY".equals(b.sourceKind())||current==null||!current.workflow().equals(b.source())||!current.workflow().equals(b.workflow()))throw stale();return workflows.recover(c,actor,current.workflow());
 }
 static List<Subject> facts(Connection c,UUID tenant,UUID opportunity)throws SQLException{var result=new LinkedHashSet<Subject>(R2ContractServices.facts(c,tenant,opportunity));for(var state:READER.forOpportunity(c,tenant,opportunity)){result.add(state.request());result.addAll(READER.facts(c,tenant,state.request().id()));}return List.copyOf(result);}
 static boolean result(Connection c,CommandEnvelope e,CommandAuthorizationBinding.ContractRecovery b,Subject fact,boolean created)throws SQLException{var result=READER.result(c,e.actor().tenantId(),fact);return result!=null&&result.opportunity().equals(b.opportunity().id())&&Objects.equals(result.previousWorkflow(),b.workflow()==null?null:b.workflow().id())&&(!created||result.createdInCurrentTransaction()&&result.actor().equals(e.actor().appointmentId()))&&facts(c,e.actor().tenantId(),b.opportunity().id()).contains(fact);}
 private static TransferWorkflowService.Blocked stale(){return new TransferWorkflowService.Blocked("STALE_SUBJECT");}private static TransferWorkflowService.Blocked denied(){return new TransferWorkflowService.Blocked("NOT_AUTHORIZED");}
}
