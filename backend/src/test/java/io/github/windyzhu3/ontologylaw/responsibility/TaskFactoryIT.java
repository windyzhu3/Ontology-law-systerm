package io.github.windyzhu3.ontologylaw.responsibility;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;

class TaskFactoryIT extends PostgresIntegrationTest {
    @Test void creates_frozen_responsibility_and_completes_with_exact_decision() throws Exception {
        var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();
        Instant now=Instant.parse("2026-09-04T09:00:00.123456Z");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var task=repo.create(x,s.tenant(),TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),now);
            assertNotNull(task);assertEquals("OPEN",task.state());assertEquals(0L,task.selector().revision());
            try(var p=x.prepareStatement("select original_sla_seconds,original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?")) {
                p.setObject(1,s.tenant());p.setObject(2,task.selector().id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(14400,r.getLong(1));assertEquals(Instant.parse("2026-09-07T04:00:00.123456Z"),r.getObject(2,OffsetDateTime.class).toInstant());}
            }
            var digest=Map.<String,Object>of("tenantId",s.tenant().toString(),"subject",Map.of("type",task.lead().type(),"id",task.lead().id().toString(),"revision",task.lead().revision()),"authoritySlot",task.type().slot,"decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary","Synthetic review");
            Subject fact=repo.decision(x,s.tenant(),task,s.appointment(),"LEAD_ROUTING_DISPOSITION","SCHEDULE_ROUTING_REVIEW","Synthetic review",digest,now);
            repo.complete(x,s.tenant(),task,fact,now);
            var done=repo.read(x,s.tenant(),task.selector().id());assertEquals("DONE",done.state());assertEquals(1L,done.selector().revision());assertEquals(fact,done.completion());return null;
        });}
    }
    @Test void decision_owner_rejects_unregistered_contract_and_forged_digest_coverage() throws Exception {
        var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();Instant now=Instant.parse("2026-09-04T09:00:00Z");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var task=repo.create(x,s.tenant(),TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),now);
            assertThrows(IllegalArgumentException.class,()->repo.decision(x,s.tenant(),task,s.appointment(),"FORGED_CONTRACT","REQUEST_SOURCE_INTAKE_STOP","Synthetic",Map.of(),now));
            assertThrows(IllegalArgumentException.class,()->repo.decision(x,s.tenant(),task,s.appointment(),"LEAD_ROUTING_DISPOSITION","REQUEST_SOURCE_INTAKE_STOP","Synthetic",Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP"),now));
            return null;
        });}
    }
    @Test void waiting_appends_one_immutable_receipt_after_open_to_waiting_cas() throws Exception {
        var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();Instant now=Instant.parse("2026-09-04T09:00:00Z");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var task=repo.create(x,s.tenant(),TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),now);
            assertNotNull(task);var fact=repo.waitUntil(x,s.tenant(),task,s.appointment(),Instant.parse("2026-09-07T01:00:00Z"),now);
            assertNotNull(fact);assertEquals("responsibility.wait_receipt",fact.type());assertNotNull(fact.hash());
            var expected=new TreeMap<String,Object>();expected.put("tenantId",s.tenant().toString());expected.put("wait_receipt_id",fact.id().toString());expected.put("task_occurrence_id",task.selector().id().toString());expected.put("task_revision",1L);expected.put("wait_sequence",1);expected.put("wait_reason_code","ROUTING_REVIEW_WINDOW");expected.put("wait_contract_code","R1_ROUTING_REVIEW_WAIT_V1");expected.put("wait_contract_version",1);expected.put("entered_waiting_at","2026-09-04T09:00:00.000000Z");expected.put("resume_due_at","2026-09-07T01:00:00.000000Z");expected.put("recorded_by_appointment_id",s.appointment().toString());for(String key:List.of("awaited_fact_type","awaited_fact_id","awaited_fact_revision","awaited_fact_hash"))expected.put(key,null);
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(expected))),fact.hash());
            var waiting=repo.read(x,s.tenant(),task.selector().id());assertEquals("WAITING",waiting.state());assertEquals(1L,waiting.selector().revision());
            assertThrows(RuntimeException.class,()->repo.waitUntil(x,s.tenant(),task,s.appointment(),Instant.parse("2026-09-07T01:00:00Z"),now));return null;
        });}
    }
    @Test void causal_stop_requires_complete_exact_chain_and_bounded_timestamp_uuid_maximum()throws Exception {
        for(String fault:List.of("SUBJECT","TYPE","VERSION","HASH","FUTURE","VALID")){
            var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();Instant now=Instant.parse("2026-09-04T09:00:00.123456Z");
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                Subject expected=null;
                for(int sequence=1;sequence<=2;sequence++){
                    boolean altered=sequence==2;UUID id=UUID.fromString("ffffffff-ffff-7fff-bfff-"+String.format(Locale.ROOT,"%012d",sequence));
                    var type=altered&&fault.equals("TYPE")?TaskFactory.Type.RESOLVE_LEAD_DUPLICATE:TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP;
                    var source=repo.create(x,s.tenant(),type,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),now.minusSeconds(1));
                    byte[] hash=new byte[32];hash[31]=(byte)sequence;var fact=new Subject("responsibility.decision_record",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
                    AuthorizationServiceIT.sql(x,"insert into responsibility.decision_record (tenant_id,decision_record_id,task_occurrence_id,decision_version,decided_by_appointment_id,authority_slot_code,decision_contract_code,decision_contract_version,decision_code,content_digest,rationale_summary,decided_at,decision_subject_type,decision_subject_id,decision_subject_revision) values (?,?,?,1,?,'ROUTING_SUPERVISOR','LEAD_ROUTING_DISPOSITION',?,'REQUEST_SOURCE_INTAKE_STOP',?,'Synthetic imported history',?,'lead.lead',?,0)",s.tenant(),id,source.selector().id(),s.appointment(),altered&&fault.equals("VERSION")?2:1,hash,(altered&&fault.equals("FUTURE")?now.plusNanos(1000):now).atOffset(ZoneOffset.UTC),altered&&fault.equals("SUBJECT")?UUID.randomUUID():s.subject());
                    repo.complete(x,s.tenant(),source,altered&&fault.equals("HASH")?new Subject(fact.type(),id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])):fact,now);
                    if(sequence==1||fault.equals("VALID"))expected=fact;
                }
                var ack=repo.create(x,s.tenant(),TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST,s.appointment(),s.request().subject(),ZoneId.of("Asia/Shanghai"),now);
                assertEquals(expected,repo.causalStop(x,s.tenant(),ack),fault);return null;
            });}
        }
    }
}
