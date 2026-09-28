package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.audit.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;import io.github.windyzhu3.ontologylaw.lead.*;
import java.sql.*;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferCommandIT extends R2TransferAuthorityIT {
 CommandRuntime transferRuntime(AuditAppender audit){var owner=TransferWorkflowService.databaseBacked(realPorts());return new CommandRuntime(Arrays.stream(CommandEnvelope.Type.values()).filter(CommandEnvelope.Type::transfers).map(type->(CommandHandler)new R2TransferCommand(type,cipher,owner)).toList(),AuthorizationService.databaseBacked(),audit,R2OpportunityCommandRuntime.authorization(R1AuthorizationReaders.databaseBacked(policies)),R2OpportunityCommandRuntime.events(R1EventReaders.databaseBacked(),cipher));}
 CommandEnvelope transferCommand(Subject workflow){var proof=Map.of("versionId",material.toString(),"sha256",bodySha);return new CommandEnvelope(CommandEnvelope.Type.SUBMIT_TRANSFER,UUID.randomUUID(),UUID.randomUUID(),actor(),Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"expectedWorkflow",Map.of("id",workflow.id().toString(),"revision",0),"values",Map.of("clientIdentity",proof,"signatureArchive",proof,"explanation","转案资料完整","consistencyChecked",true,"corrections",List.of())));}
 void transferSalesGrants()throws Exception{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}}
 @Test void transfer_runtime_commits_exact_event_receipt_and_replays_without_second_submission()throws Exception{
  var workflow=beginTransfer();transferSalesGrants();var runtime=transferRuntime(AuditAppender.databaseBacked("F11_RUNTIME"));var command=transferCommand(workflow);CommandResult first;
  try(var c=database.apiConnection()){first=runtime.execute(c,command);}assertInstanceOf(CommandOutcome.class,first);assertEquals(CommandOutcome.Status.SUCCEEDED,((CommandOutcome)first).status());
  try(var c=database.apiConnection()){assertEquals(first,runtime.execute(c,command));}
  assertEquals("1",scalar("select count(*) from transfer.submission where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='TransferSubmittedV1'",seed.tenant()));
 }
 @Test void transfer_runtime_audit_failure_rolls_back_submission_draft_and_successor()throws Exception{
  var workflow=beginTransfer();transferSalesGrants();var runtime=transferRuntime(new AuditAppender(){public void append(Connection c,Entry e)throws SQLException{throw new SQLException("injected audit failure");}});var command=transferCommand(workflow);
  try(var c=database.apiConnection()){assertThrows(SQLException.class,()->runtime.execute(c,command));}
  assertEquals("0",scalar("select count(*) from transfer.submission where tenant_id=?",seed.tenant()));assertEquals("PREPARE",scalar("select stage_code from transfer.workflow where tenant_id=?",seed.tenant()));
 }
}
