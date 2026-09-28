package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;
class R25AiHttpIT extends R1HttpFixture {
    private UUID opportunity;
    private void prepareSources()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector().id();});}
    }
    @Test void disabledAiStillEnforcesOriginalSourceAuthorityAndStrictRequest()throws Exception {
        prepareSources();String path="/api/v1/opportunities/"+opportunity+"/ai-candidates/FIELDS";
        try(var http=new HttpHarness()) {
            var response=http.request("POST",path,Map.of(),Map.of());assertEquals(503,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(null));
            assertEquals(400,http.request("POST",path,Map.of("sourceText","injected text"),Map.of()).statusCode());
            assertEquals(400,http.request("POST",path+"?tenantId="+seed.tenant(),Map.of(),Map.of()).statusCode());
            assertEquals(400,http.request("POST",path.replace("FIELDS","EXECUTE"),Map.of(),Map.of()).statusCode());
            assertEquals(400,http.request("POST",path+"/recheck",Map.of("sourceToken","forged","model","forged"),Map.of()).statusCode());
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant());
            assertEquals(403,http.request("POST",path,Map.of(),Map.of()).statusCode());
        }
    }
    @Test void candidateAndRecheckUseSeparateClosedConnectionsAndNeverWriteBusinessFact()throws Exception {
        prepareSources();var before=counts();var calls=new AtomicInteger();var openConnections=new AtomicInteger();
        disclosureConnection=connection->{openConnections.incrementAndGet();var closed=new java.util.concurrent.atomic.AtomicBoolean();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}finally{if(method.getName().equals("close")&&closed.compareAndSet(false,true))openConnections.decrementAndGet();}});};
        aiModel=(task,sources)->{assertEquals(0,openConnections.get(),"No pooled connection or transaction across network wait");calls.incrementAndGet();var source=sources.getFirst();return new Result(List.of(new Item("customerGoal","CANDIDATE","人工核对候选",List.of(new Citation(source.id(),source.text().substring(0,Math.min(30,source.text().length())))))));};
        String path="/api/v1/opportunities/"+opportunity+"/ai-candidates/FIELDS";
        try(var http=new HttpHarness()) {
            var response=http.request("POST",path,Map.of(),Map.of());assertEquals(200,response.statusCode(),response.body());var token=http.body(response).get("sourceToken");assertNotNull(token);
            assertEquals(204,http.request("POST",path+"/recheck",Map.of("sourceToken",token),Map.of()).statusCode());
            assertEquals(412,http.request("POST",path+"/recheck",Map.of("sourceToken","forged"),Map.of()).statusCode());assertEquals(1,calls.get());
        }
        var after=counts();for(int i=0;i<before.size();i++)if(i!=8)assertEquals(before.get(i),after.get(i));
    }
}
