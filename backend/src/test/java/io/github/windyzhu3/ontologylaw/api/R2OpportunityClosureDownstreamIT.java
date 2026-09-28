package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** A real complete quote fixture retains all source, package, current-pointer and task guards. */
class R2OpportunityClosureDownstreamIT extends R1HttpFixture {
    private Subject opportunity,quote;
    private UUID quoteTask;
    private void setupQuotedOpportunity()throws Exception {
        setupContact();var completed=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");grant(x,"OPPORTUNITY_CLOSE");
            // PREPARE_QUOTE / CREATE_QUOTE_REVISION is the documented next responsibility.
            // This test constructs its complete persisted result; it does not register an API command.
            quoteTask=UUID.randomUUID();var draft=UUID.randomUUID();var party=UUID.randomUUID();var participation=UUID.randomUUID();var quoteId=UUID.randomUUID();var scope=UUID.randomUUID();
            var participationHash=CanonicalJson.digest("T04 quoted client participation");var contentHash=CanonicalJson.digest("T04 complete quote package");var draftHash=CanonicalJson.digest("{}");
            sql(x,"insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,'PREPARE_QUOTE','CREATE_QUOTE_REVISION','opportunity.quote_revision','R1_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),quoteTask,seed.appointment(),opportunity.id(),opportunity.revision());
            sql(x,"insert into responsibility.action_draft (tenant_id,action_draft_id,task_occurrence_id,action_code,payload_schema_code,payload_schema_version,candidate_payload,candidate_payload_digest,state,created_by_appointment_id,created_at,last_edited_at) values (?,?,?,'CREATE_QUOTE_REVISION','CreateQuoteRevisionV1',1,'{}'::jsonb,?,'DRAFT',?,clock_timestamp(),clock_timestamp())",seed.tenant(),draft,quoteTask,draftHash,seed.appointment());
            sql(x,"update responsibility.action_draft set state='CONFIRMED',confirmed_by_appointment_id=?,confirmed_at=clock_timestamp(),confirmed_payload_digest=candidate_payload_digest,revision=revision+1 where tenant_id=? and action_draft_id=?",seed.appointment(),seed.tenant(),draft);
            sql(x,"insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','Quoted client','ACTIVE')",seed.tenant(),party);
            sql(x,"insert into opportunity.opportunity_participation (tenant_id,opportunity_participation_id,opportunity_id,participation_set_revision,participation_no,participation_set_size,participation_set_digest,party_id,party_revision,party_snapshot_digest,context_role_code,created_at) values (?,?,?,1,1,1,?,?,0,?,'CLIENT',clock_timestamp())",seed.tenant(),participation,opportunity.id(),participationHash,party,CanonicalJson.digest("Quoted client"));
            sql(x,"insert into opportunity.quote_revision (tenant_id,quote_revision_id,opportunity_id,quote_revision_no,confirmed_action_draft_id,participation_set_revision,participation_set_digest,package_contract_code,package_contract_version,currency_code,total_minor,content_digest,created_by_appointment_id,created_at) values (?,?,?,1,?,1,?,'FIXED_FEE',1,'CNY',10000,?,?,clock_timestamp())",seed.tenant(),quoteId,opportunity.id(),draft,participationHash,contentHash,seed.appointment());
            sql(x,"insert into opportunity.quote_service_scope (tenant_id,quote_service_scope_id,quote_revision_id,scope_no,service_code,scope_summary,included,scope_hash,created_at) values (?,?,?,1,'LEGAL_ADVICE','Quoted advice scope',true,?,clock_timestamp())",seed.tenant(),scope,quoteId,CanonicalJson.digest("Quoted advice scope"));
            sql(x,"insert into opportunity.quote_line (tenant_id,quote_line_id,quote_revision_id,quote_service_scope_id,line_no,line_type_code,line_summary,amount_minor,currency_code,created_at) values (?,?,?,?,1,'FIXED_FEE','Advice fee',10000,'CNY',clock_timestamp())",seed.tenant(),UUID.randomUUID(),quoteId,scope);
            sql(x,"insert into opportunity.quote_payment_term (tenant_id,quote_payment_term_id,quote_revision_id,term_no,due_basis_code,due_offset_days,amount_minor,currency_code,created_at) values (?,?,?,1,'SIGNATURE',0,10000,'CNY',clock_timestamp())",seed.tenant(),UUID.randomUUID(),quoteId);
            sql(x,"update opportunity.opportunity set current_quote_revision_id=?,revision=revision+1 where tenant_id=? and opportunity_id=?",quoteId,seed.tenant(),opportunity.id());
            sql(x,"update responsibility.task_occurrence set state='DONE',completed_at=clock_timestamp(),completion_fact_type='opportunity.quote_revision',completion_fact_id=?,completion_fact_hash=?,revision=revision+1 where tenant_id=? and task_occurrence_id=?",quoteId,contentHash,seed.tenant(),quoteTask);
            opportunity=EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id()).selector();quote=new Subject("opportunity.quote_revision",quoteId,null,Base64.getUrlEncoder().withoutPadding().encodeToString(contentHash));return null;
        });}
    }
    private String contextPath(){return "/api/v1/opportunities/"+opportunity.id()+"/closure";}
    private Map<String,Object> closeBody(){var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",opportunity.revision());body.put("expectedResponsibility",Map.of("type",opportunity.type(),"id",opportunity.id().toString(),"revision",opportunity.revision()));body.put("expectedTask",null);body.put("expectedWait",null);body.put("reasonCode","CLIENT_DECLINED");body.put("summary","This entry must not end a quoted negotiation");return body;}
    @Test void committed_quote_blocks_context_and_direct_close_without_cancelling_downstream()throws Exception {
        setupQuotedOpportunity();
        try(var http=new HttpHarness()){
            var context=http.request("GET",contextPath(),null,Map.of());assertEquals(200,context.statusCode(),context.body());assertEquals("BLOCKED",http.body(context).get("status"));assertEquals(Set.of("opportunity","status"),http.body(context).keySet());assertEquals("no-store",context.headers().firstValue("Cache-Control").orElseThrow());
            var key=UUID.randomUUID();var result=http.request("POST","/api/v1/opportunities/"+opportunity.id()+"/commands/close",closeBody(),Map.of("Idempotency-Key",key.toString()));assertEquals(409,result.statusCode(),result.body());assertEquals("OPPORTUNITY_HAS_DOWNSTREAM_FACTS",http.body(result).get("code"));assertEquals("NO",http.body(result).get("retryPolicy"));assertFalse(result.body().contains("currentETag"));
            var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertEquals("REJECTED",http.body(receipt).get("outcome"));assertEquals("OPPORTUNITY_HAS_DOWNSTREAM_FACTS",http.body(receipt).get("rejectionCode"));
        }
        assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));assertEquals("1",scalar("select count(*) from opportunity.quote_revision where tenant_id=? and quote_revision_id=?",seed.tenant(),quote.id()));assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),quoteTask));assertEquals("0",scalar("select count(*) from opportunity.opportunity where tenant_id=? and opportunity_id=? and closed_at is not null",seed.tenant(),opportunity.id()));
    }
    @Test void exact_quote_read_deny_hides_downstream_existence_and_all_close_selectors()throws Exception {
        setupQuotedOpportunity();deny(quote,"SALES_OPPORTUNITY_OWNER");
        try(var http=new HttpHarness()){
            var context=http.request("GET",contextPath(),null,Map.of());assertEquals(200,context.statusCode(),context.body());assertEquals("READ_ONLY",http.body(context).get("status"));assertEquals(Set.of("opportunity","status"),http.body(context).keySet());assertFalse(context.body().contains(quote.id().toString()));assertFalse(context.body().contains("BLOCKED"));assertFalse(context.body().contains("Quoted client"));
        }
    }
}
