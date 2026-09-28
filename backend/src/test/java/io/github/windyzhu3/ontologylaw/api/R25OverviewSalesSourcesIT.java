package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R25OverviewSalesSourcesIT extends R2TransferIntakeIT {
 @Test void first_archive_and_accepted_matter_survive_return_resubmission_and_classification_without_double_count()throws Exception {
  intake_return_correct_resubmit_review_then_accept_keeps_the_original_snapshot_chain();
  var from=Instant.EPOCH;var until=Instant.now().plusSeconds(600);UUID matter;Instant accepted;
  var recipient=reviewerActor();
  try(var c=database.apiConnection()){
   var rows=inTransaction(c,Capability.QUERY,x->{
    var signed=ContractOverviewReader.databaseBacked().scan(x,seed.tenant(),from,until,null,100);assertEquals(1,signed.size());var archive=signed.getFirst();assertTrue(archive.facts().stream().anyMatch(f->f.type().equals("contract.signature_archive")));assertTrue(archive.facts().stream().anyMatch(f->f.type().equals("contract.contract_revision")&&f.hash()!=null));
    assertTrue(ContractOverviewReader.databaseBacked().scan(x,UUID.randomUUID(),from,until,null,100).isEmpty());assertTrue(ContractOverviewReader.databaseBacked().scan(x,seed.tenant(),from,archive.occurredAt(),null,100).isEmpty());assertEquals(1,ContractOverviewReader.databaseBacked().scan(x,seed.tenant(),archive.occurredAt(),until,null,100).size());
    var cases=TransferOverviewReader.databaseBacked().scan(x,seed.tenant(),from,until,null,100);assertEquals(1,cases.size());var acceptedCase=cases.getFirst();assertTrue(acceptedCase.facts().stream().anyMatch(f->f.type().equals("transfer.intake")));assertTrue(TransferOverviewReader.databaseBacked().scan(x,seed.tenant(),from,until,acceptedCase.id(),100).isEmpty());assertTrue(TransferOverviewReader.databaseBacked().scan(x,UUID.randomUUID(),from,until,null,100).isEmpty());return cases;
   });matter=rows.getFirst().id();accepted=rows.getFirst().occurredAt();
   var workflow=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);
   inTransaction(c,Capability.COMMAND,x->{workflows().classify(x,recipient,workflow,new TransferClassificationInput("GENERAL",reviewer,"概览合成分类核对"));return null;});
   inTransaction(c,Capability.QUERY,x->{var cases=TransferOverviewReader.databaseBacked().scan(x,seed.tenant(),from,until,null,100);assertEquals(1,cases.size());assertEquals(matter,cases.getFirst().id());assertEquals(accepted,cases.getFirst().occurredAt());assertEquals(1,ContractOverviewReader.databaseBacked().scan(x,seed.tenant(),from,until,null,100).size());return null;});
   inTransaction(c,Capability.COMMAND,x->{for(var code:List.of("LEAD_MANAGEMENT_READ","OPPORTUNITY_LEDGER_READ","CONTRACT_READ","TRANSFER_LEDGER_READ","TEAM_TASK_READ"))grant(x,code);return null;});
   var service=new R25BusinessOverviewReadService(new byte[32],protection,policies,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("R25_OVERVIEW_CHAIN"));
   String month=java.time.YearMonth.from(accepted.atZone(ZoneId.of("Asia/Shanghai"))).toString();
   var summary=service.summary(c,seed.request().actor(),month);
   for(var raw:(List<?>)summary.get("metrics")){
    var metric=(Map<?,?>)raw;assertEquals("AVAILABLE",metric.get("status"));String key=(String)metric.get("key");
    var detail=service.details(c,seed.request().actor(),key,month,100,null);assertNull(detail.get("nextCursor"));assertEquals(metric.get("count"),(long)((List<?>)detail.get("items")).size(),key);
    if(Set.of("signedContracts","acceptedMatters").contains(key))assertEquals(1L,metric.get("count"),key);
   }
  }
 }
}
