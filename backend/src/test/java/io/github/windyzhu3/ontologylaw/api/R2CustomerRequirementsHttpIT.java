package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2CustomerRequirementsHttpIT extends R1HttpFixture {
    @SuppressWarnings("unchecked")
    @Test void exactDraftCanBeConfirmedAndReadAgainThroughPublicWireWithoutCompletingOriginalTask()throws Exception {
        setupContact();opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        var contact=execute(prepare(contact("CONNECTED_VALID")));Subject opportunity;
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");grant(x,"CUSTOMER_REQUIREMENTS_MANAGE");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}
        String base="/api/v1/opportunities/"+opportunity.id()+"/customer-requirements";
        try(var http=new HttpHarness()) {
            var initial=http.request("GET",base,null,Map.of());assertEquals(200,initial.statusCode(),initial.body());var context=http.body(initial);assertEquals(false,context.get("draftConsumed"));assertEquals(true,context.get("editable"));
            assertFalse(context.containsKey("draft"));assertFalse(context.containsKey("confirmation"));
            assertFalse(context.containsKey("currentOwnerLabel") && context.get("currentOwnerLabel")==null);
            var source=(Map<String,Object>)context.get("source");
            for(String field:List.of("party","name","legalNeed","contactName","contactPhone"))assertFalse(source.containsKey(field) && source.get(field)==null,field+" must be omitted when absent");
            var p=new LinkedHashMap<String,Object>();p.put("role","CLIENT");p.put("party",null);p.put("profileChange",null);p.put("newParty",Map.of("kind","ORGANIZATION","name","T05 HTTP 合成客户","distinctIdentityConfirmed",true));
            var document=Map.<String,Object>of("participants",List.of(p),"unknownOpponent",true,"matterName","服务需求核对","customerGoal","核对服务目标","serviceScope","咨询服务","knownConstraints","","contactName","合成联系人","contactPhone","");
            var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",opportunity.revision());body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("expectedDraft",null);body.put("expectedConfirmation",null);body.put("document",document);
            var rejectedHeader=http.request("POST",base+"/draft",body,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match","\"task.fake\""));assertEquals(400,rejectedHeader.statusCode(),rejectedHeader.body());
            var saved=http.request("POST",base+"/draft",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,saved.statusCode(),saved.body());assertEquals("OPPORTUNITY_CUSTOMER_DRAFT",((Map<?,?>)http.body(saved).get("resultFact")).get("factType"));
            var reread=http.request("GET",base,null,Map.of());assertEquals(200,reread.statusCode(),reread.body());var afterSave=http.body(reread);var draft=(Map<String,Object>)afterSave.get("draft");assertEquals(((Map<?,?>)http.body(saved).get("resultFact")).get("factRef"),draft.get("factRef"));body.put("expectedDraft",draft.get("selector"));body.remove("document");String key=UUID.randomUUID().toString();
            var confirmed=http.request("POST",base+"/confirm",body,Map.of("Idempotency-Key",key));assertEquals(200,confirmed.statusCode(),confirmed.body());assertEquals("OPPORTUNITY_CUSTOMER_CONFIRMATION",((Map<?,?>)http.body(confirmed).get("resultFact")).get("factType"));
            var replay=http.request("POST",base+"/confirm",body,Map.of("Idempotency-Key",key));assertEquals(200,replay.statusCode(),replay.body());assertEquals(http.body(confirmed),http.body(replay));
            var finalRead=http.request("GET",base,null,Map.of());assertEquals(200,finalRead.statusCode(),finalRead.body());var finalContext=http.body(finalRead);assertEquals(true,finalContext.get("draftConsumed"));var version=(Map<String,Object>)finalContext.get("confirmation");assertEquals(((Map<?,?>)http.body(confirmed).get("resultFact")).get("factRef"),version.get("factRef"));var canonical=(Map<String,Object>)version.get("document");var row=(Map<String,Object>)((List<?>)canonical.get("participants")).getFirst();assertNotNull(row.get("party"));assertTrue(row.containsKey("newParty"));assertNull(row.get("newParty"));assertTrue(row.containsKey("profileChange"));assertNull(row.get("profileChange"));assertEquals(1,((List<?>)version.get("partySnapshots")).size());assertEquals("no-store",finalRead.headers().firstValue("Cache-Control").orElse(""));
            var search=http.request("GET",base+"/parties?q=T05",null,Map.of());assertEquals(200,search.statusCode(),search.body());assertEquals(1,((List<?>)http.body(search).get("items")).size());
            var incomplete=new LinkedHashMap<String,Object>(canonical);incomplete.put("participants",List.of());body.put("expectedConfirmation",version.get("selector"));body.put("document",incomplete);
            var incompleteSaved=http.request("POST",base+"/draft",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,incompleteSaved.statusCode(),incompleteSaved.body());
            var incompleteContext=http.body(http.request("GET",base,null,Map.of()));body.put("expectedDraft",((Map<?,?>)incompleteContext.get("draft")).get("selector"));body.remove("document");String rejectedKey=UUID.randomUUID().toString();
            var invalid=http.request("POST",base+"/confirm",body,Map.of("Idempotency-Key",rejectedKey));assertEquals(400,invalid.statusCode(),invalid.body());assertEquals("VALIDATION_FAILED",http.body(invalid).get("code"));
            var rejectedReceipt=http.request("GET","/api/v1/commands/"+rejectedKey+"/receipt",null,Map.of());assertEquals(200,rejectedReceipt.statusCode(),rejectedReceipt.body());assertEquals("REJECTED",http.body(rejectedReceipt).get("outcome"));assertEquals("VALIDATION_FAILED",http.body(rejectedReceipt).get("rejectionCode"));

        }
        assertEquals("1",scalar("select count(*) from party.party where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
    }
}
