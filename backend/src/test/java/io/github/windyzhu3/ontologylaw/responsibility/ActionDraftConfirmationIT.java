package io.github.windyzhu3.ontologylaw.responsibility;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;

class ActionDraftConfirmationIT extends PostgresIntegrationTest {
    @Test void confirms_only_exact_draft_candidate_and_rolls_back_with_its_transaction() throws Exception {
        var s=AuthorizationServiceIT.seed(database);var drafts=ActionDraftService.databaseBacked();var tasks=TaskFactory.databaseBacked();
        UUID id=UUID.randomUUID();var values=Map.<String,Object>of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary","Synthetic review");
        String json=CanonicalJson.encode(values);byte[] digest=CanonicalJson.digest(json);String hash=Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        TaskFactory.Task task;
        try(var c=database.apiConnection()){task=inTransaction(c,Capability.COMMAND,x->{
            var t=tasks.create(x,s.tenant(),TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),Instant.parse("2026-09-04T09:00:00Z"));
            sql(x,"insert into responsibility.action_draft (tenant_id,action_draft_id,task_occurrence_id,action_code,payload_schema_code,payload_schema_version,candidate_payload,candidate_payload_digest,state,created_by_appointment_id,created_at,last_edited_at) values (?,?,?,'RECORD_ROUTING_DISPOSITION','RecordRoutingDispositionV1',1,?::jsonb,?,'DRAFT',?,clock_timestamp(),clock_timestamp())",s.tenant(),id,t.selector().id(),json,digest,s.appointment());return t;
        });}
        var confirmation=new ActionDraftService.Confirmation(id,0,hash);
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{assertTrue(drafts.exists(x,s.tenant(),task.selector().id(),id));assertFalse(drafts.exists(x,UUID.randomUUID(),task.selector().id(),id));return null;});
            assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
                drafts.confirm(x,s.tenant(),task,confirmation,values,s.appointment(),Instant.now());throw new java.sql.SQLException("Synthetic rollback","45000");
            }));
            inTransaction(c,Capability.COMMAND,x->{
                assertEquals("STALE_DRAFT",assertThrows(CommandHandler.Rejected.class,()->drafts.validate(x,s.tenant(),task,new ActionDraftService.Confirmation(id,1,hash),values)).code());
                assertEquals("DRAFT_DIGEST_MISMATCH",assertThrows(CommandHandler.Rejected.class,()->drafts.validate(x,s.tenant(),task,confirmation,Map.of("decisionCode","RETRY_ASSIGNMENT_NOW","rationaleSummary","Synthetic review"))).code());
                drafts.confirm(x,s.tenant(),task,confirmation,values,s.appointment(),Instant.now());
                try(var p=x.prepareStatement("select state,revision,confirmed_payload_digest=candidate_payload_digest from responsibility.action_draft where tenant_id=? and action_draft_id=?")) {
                    p.setObject(1,s.tenant());p.setObject(2,id);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("CONFIRMED",r.getString(1));assertEquals(1L,r.getLong(2));assertTrue(r.getBoolean(3));}
                }
                return null;
            });
        }
    }
}
