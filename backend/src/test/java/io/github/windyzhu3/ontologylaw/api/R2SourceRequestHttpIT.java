package io.github.windyzhu3.ontologylaw.api;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
class R2SourceRequestHttpIT extends R1HttpFixture {
 @Test void acknowledged_request_has_current_card_saved_draft_terminal_receipt_and_no_stranded_task()throws Exception {
  setupSource();
  String path="/api/v1/tasks/"+current.selector().id();
  try(var c=database.apiConnection()){
   var direct=new CurrentWorkCardDisclosureService(protection,policies,"SOURCE_HTTP_IT").read(c,seed.request().actor(),UUID.randomUUID(),null,current.selector().id());assertEquals(200,direct.status(),direct.errorCode());
   assertDoesNotThrow(()->R1WireModels.model(direct.body(),io.github.windyzhu3.ontologylaw.api.adapter.generated.model.CurrentWorkCardEnvelope.class));
  }
  try(var http=new HttpHarness()){
   var read=http.request("GET","/api/v1/workcards/current?taskId="+current.selector().id(),null,Map.of());assertEquals(200,read.statusCode(),read.body());
   var card=(Map<?,?>)http.body(read).get("currentCard");assertNotNull(card,read.body());assertEquals("RESOLVE_SOURCE_REQUEST",card.get("taskType"));
   var form=(Map<?,?>)card.get("commandForm");assertEquals("RECORD_SOURCE_REQUEST_CONTINUATION",form.get("actionCode"));assertTrue(read.body().contains("确认线索后续安排"));
   var values=Map.of("decisionCode","END_LEAD","rationaleSummary","确认本线索结束");
   var saved=http.request("PUT",path+"/source-request-draft",Map.of("actionCode","RECORD_SOURCE_REQUEST_CONTINUATION","schemaVersion",1,"values",values),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*"));assertEquals(201,saved.statusCode(),saved.body());
   var draft=(Map<?,?>)http.body(saved).get("draft");var body=new TreeMap<String,Object>(values);body.put("draftId",draft.get("draftId"));body.put("expectedDraftRevision",draft.get("draftRevision"));body.put("draftDigest",draft.get("digest"));
   var key=UUID.randomUUID();var headers=Map.of("Idempotency-Key",key.toString(),"If-Match",(String)((Map<?,?>)http.body(saved).get("preconditions")).get("taskETag"));
   var done=http.request("POST",path+"/commands/record-source-request-continuation",body,headers);assertEquals(200,done.statusCode(),done.body());assertEquals("SUCCEEDED",http.body(done).get("outcome"));
   var replay=http.request("POST",path+"/commands/record-source-request-continuation",body,headers);assertEquals(200,replay.statusCode(),replay.body());assertEquals(http.body(done),http.body(replay));
   var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertEquals(http.body(done),http.body(receipt));
   assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),current.lead().id()));
  }
 }
 private void setupSource()throws Exception {
  setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
  mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SOURCE_INTAKE_REQUEST_ACK',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
  var request=execute(prepare(Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","合成来源请求")));selectTask(TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST);
  execute(prepare(Map.of("causalDecisionId",request.resultFact().id().toString(),"causalDecisionHash",request.resultFact().hash(),"rationaleSummary","已收到，请主管明确去向")));selectTask(TaskFactory.Type.RESOLVE_SOURCE_REQUEST);
 }
 @Test void worker_discovers_only_due_authorized_source_reviews_and_replays_exact_recovery()throws Exception {
  setupSource();
  credentialActor(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN,"another-qualified-supervisor","LEAD_ROUTING_DECIDE");
  var due=java.time.Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  execute(prepare(Map.of("decisionCode","SCHEDULE_REVIEW","reviewAt",due.toString(),"rationaleSummary","约时复查")));selectTask(TaskFactory.Type.RESOLVE_SOURCE_REQUEST);
  var worker=service("ROUTING_REVIEW_TASK_RECOVER");
  var tls=new io.github.windyzhu3.ontologylaw.testing.TlsFixture(java.nio.file.Files.createTempDirectory("source-review-tls"));
  try(var http=new HttpHarness(worker,tls);var client=http.workerClient()) {
   var type=io.github.windyzhu3.ontologylaw.worker.InternalApiClient.RecoveryType.SOURCE_REQUEST_REVIEW_TASK;
   awaitDatabaseTime(due);
   var page=client.due(http.workerBinding,type,null);assertEquals(200,page.status());assertEquals(1,page.candidates().size());var candidate=page.candidates().getFirst();assertEquals(current.selector().id(),candidate.taskId());
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
   assertTrue(client.due(http.workerBinding,type,null).candidates().isEmpty());
   mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_ROUTING_DECIDE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
   var response=client.recover(http.workerBinding,candidate);assertEquals(200,response.status(),response.body());var before=counts();assertEquals(response,client.recover(http.workerBinding,candidate));assertEquals(before,counts());
   assertTrue(client.due(http.workerBinding,type,null).candidates().isEmpty());assertEquals("OPEN",selectTask(TaskFactory.Type.RESOLVE_SOURCE_REQUEST).state());
  }
 }
}
