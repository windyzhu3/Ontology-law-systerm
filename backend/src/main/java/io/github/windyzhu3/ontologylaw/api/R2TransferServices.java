package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Runtime composition uses only named Owner reads. */
public final class R2TransferServices {
 private R2TransferServices(){}
 public static R1AuthorizationFacts.Contracts authorization(Connection c,UUID tenant,CommandAuthorizationBinding.Transfers binding,CommandEnvelope.Type type)throws SQLException{
  var reader=TransferWorkflowReader.databaseBacked();var state=reader.workflow(c,tenant,binding.workflow().id());if(state==null||!state.workflow().equals(binding.workflow())||!state.opportunity().id().equals(binding.opportunity().id())||state.opportunity().revision()<binding.opportunity().revision())return null;
  var fs=new LinkedHashSet<Subject>(R2ContractServices.facts(c,tenant,state.opportunity().id()));fs.addAll(reader.facts(c,tenant,state.request().id()));fs.add(state.request());fs.add(state.opportunity());fs.add(binding.opportunity());
  UUID org=R1CommandPolicy.transferAuthority(type).equals("TRANSFER_SUBMIT")?state.fromOrganization():state.toOrganization();return new R1AuthorizationFacts.Contracts(state.opportunity(),List.copyOf(fs),org,state.owner());
 }
 public static boolean result(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Transfers b,Subject exact)throws SQLException{
  var m=TransferWorkflowReader.databaseBacked().result(c,e.actor().tenantId(),exact);return m!=null&&m.opportunity().equals(b.opportunity().id())&&m.actor().equals(e.actor().appointmentId())&&m.createdInCurrentTransaction()&&(m.workflow().equals(b.workflow().id())||e.type()==CommandEnvelope.Type.CLASSIFY_MATTER&&b.workflow().id().equals(m.previousWorkflow()));
 }
}
