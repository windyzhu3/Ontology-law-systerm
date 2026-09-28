package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
/** Actual ledger -> formal HTTP close -> terminal ledger, with isolated fixture identity. */
@EnabledIfSystemProperty(named="t04.browser.node",matches=".+")
class OpportunityClosureBrowserIT extends R1HttpFixture {
 @Test void sales_explicitly_ends_opportunity_without_fake_progress()throws Exception { runBrowser(false); }
 @Test void lost_close_response_recovers_original_receipt_without_another_write()throws Exception { runBrowser(true); }
 private void runBrowser(boolean loseResponse)throws Exception {
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
  opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  Subject opportunity;
  try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");grant(x,"OPPORTUNITY_CLOSE");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}
  var actor=service("OPPORTUNITY_TASK_ACTIVATE");
  var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"T04_BROWSER_IT");
  var activate=new CommandEnvelope(CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
  CommandOutcome activated;try(var c=database.apiConnection()){activated=assertInstanceOf(CommandOutcome.class,runtime.execute(c,activate));assertEquals(CommandOutcome.Status.SUCCEEDED,activated.status());}
  var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();if(root.getFileName().toString().equals("backend"))root=root.getParent();
  var directory=root.resolve(loseResponse?"output/t04-live-browser-recovery":"output/t04-live-browser");Files.createDirectories(directory);
  try(var http=new HttpHarness()){
   var identity=new LinkedHashMap<String,Object>();identity.put("identityEpoch",1);identity.put("actorScopeKey","ask1."+"A".repeat(43));identity.put("selectedAppointmentId",seed.appointment().toString());identity.put("selectedOnBehalfAppointmentId",null);identity.put("displayName","销售 · 浏览器验收");
   var input=Map.of("origin",http.origin.toString(),"bearer",http.bearer,"actor",identity,"outputDir",directory.toString(),"opportunityId",opportunity.id().toString(),"taskId",activated.resultFact().id().toString(),"loseCloseResponse",loseResponse);
   var process=new ProcessBuilder(System.getProperty("t04.browser.node"),root.resolve("e2e/tests/t04-opportunity-closure-browser.mjs").toString()).directory(root.toFile()).redirectOutput(directory.resolve("browser.log").toFile()).redirectError(directory.resolve("browser-error.log").toFile()).start();
   try{try(var stdin=process.getOutputStream()){stdin.write(mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Browser acceptance timeout");assertEquals(0,process.exitValue(),"See output/t04-live-browser");}finally{if(process.isAlive())process.destroyForcibly();}
  }
  assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
  assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),activated.resultFact().id()));
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
  assertEquals("1",scalar("select count(*) from opportunity.closure where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
  assertEquals(seed.appointment().toString(),scalar("select owner_appointment_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
 }
}
