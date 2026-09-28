package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2OpportunityReceiptHttpIT extends R1HttpFixture {
    @Test void existing_http_receipt_route_recovers_progress_with_no_store_audit_and_current_authority()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");
            current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;
        });}
        var cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        runtime=R2OpportunityCommandRuntime.create(policies,protection,cipher,"R2_RECEIPT_HTTP_IT",ZoneId.of("Asia/Shanghai"));
        var values=Map.<String,Object>of("progressTypeCode","PHONE_CONNECTED","progressSummary","Private client follow-up","occurredAt",businessAt.toString(),"nextCheckAt",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        var save=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(current.selector().id(),null,"*"));execute(save);
        CommandEnvelope command;
        try(var c=database.apiConnection()){command=inTransaction(c,Capability.QUERY,x->{var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id());var body=new TreeMap<String,Object>(values);body.put("draftId",draft.selector().id().toString());body.put("expectedDraftRevision",draft.selector().revision());body.put("draftDigest",draft.digest());return new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));});}
        var result=execute(command);
        try(var http=new HttpHarness()){
            String path="/api/v1/commands/"+command.commandId()+"/receipt";var response=http.request("GET",path,null,Map.of());
            assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            var fact=(Map<?,?>)http.body(response).get("resultFact");assertEquals("OPPORTUNITY_PROGRESS",fact.get("factType"));assertEquals(result.resultFact().hash(),fact.get("digest"));
            assertEquals(Set.of("factType","factRef","digest"),fact.keySet());assertFalse(response.body().contains(result.resultFact().id().toString()));assertFalse(response.body().contains("Private client follow-up"));
            assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_COMMAND_RECEIPT'",seed.tenant()));
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant());
            var denied=http.request("GET",path,null,Map.of());assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("factRef"));
        }
    }
}
