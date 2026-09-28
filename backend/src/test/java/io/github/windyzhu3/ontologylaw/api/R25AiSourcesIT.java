package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.lead.LeadProtection;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;

class R25AiSourcesIT extends R2MaterialsIT {
    private boolean namedInput;
    @Override protected Map<String,Object> input(boolean contacts){var value=super.input(contacts);if(namedInput){value.remove("capturedName");value.put("customerName","合成公司海宁实业");value.put("contactName","林悦");}return value;}
    @Test void modernCaptureCustomerNameIsASeparateSourceFromHistoricalCapturedName()throws Exception {
        namedInput=true;try{setup(true,false);}finally{namedInput=false;}
        try(var c=database.apiConnection()){var source=sources().read(c,seed.request().actor(),opportunity.id(),Task.FIELDS).sources().getFirst();assertTrue(source.text().contains("客户名称：合成公司海宁实业"));assertTrue(source.text().contains("联系人：林悦"));}
    }
    private R25AiSourceReadService sources(){return new R25AiSourceReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("R25_AI_SOURCE_IT"));}
    @Test void exactLeadAndOnlyConfirmedProgressAreReadWithoutBusinessWrites() throws Exception {
        setup(true,true);var before=counts();
        try(var c=database.apiConnection()) {
            var fields=sources().read(c,seed.request().actor(),opportunity.id(),Task.FIELDS);
            assertEquals(1,fields.sources().size());assertEquals("TEXT",fields.sources().getFirst().kind());assertTrue(c.getAutoCommit());
            var summary=sources().read(c,seed.request().actor(),opportunity.id(),Task.SUMMARY);
            assertEquals(1,summary.sources().size());assertEquals("PROGRESS",summary.sources().getFirst().kind());assertTrue(summary.sources().getFirst().text().contains("下周补材料"));assertFalse(fields.fingerprint().equals(summary.fingerprint()));
            assertEquals(summary.fingerprint(),sources().read(c,seed.request().actor(),opportunity.id(),Task.SUMMARY).fingerprint());
        }
        var after=counts();for(int i=0;i<before.size();i++)if(i!=8)assertEquals(before.get(i),after.get(i));assertTrue(after.get(8)>before.get(8));
    }
    @Test void deniedLatestMaterialDoesNotResurrectHistoricalVersionAsCurrent() throws Exception {
        setup(true,false);var upload1=execute(command(null,null)).resultFact();upload(upload1);var first=execute(command(null,upload1)).resultFact();
        var upload2=execute(command(first,null)).resultFact();upload(upload2);var second=execute(command(first,upload2)).resultFact();
        try(var c=database.apiConnection()) {
            var source=sources().read(c,seed.request().actor(),opportunity.id(),Task.MATERIALS);
            assertEquals(1,source.sources().stream().filter(s->s.kind().equals("MATERIAL")).count());
            for(var code:List.of("MATERIALS_MANAGE","MATERIALS_READ"))deny(second,code);
            var filtered=sources().read(c,seed.request().actor(),opportunity.id(),Task.MATERIALS);
            assertEquals(0,filtered.sources().stream().filter(s->s.kind().equals("MATERIAL")).count());
            assertNotEquals(source.fingerprint(),filtered.fingerprint());
            assertTrue(filtered.sources().stream().filter(s->s.kind().equals("RULE")).allMatch(s->s.text().contains("准备参考")));
        }
    }
    @Test void deniedSourceAndFailedAuditNeverReleaseInput() throws Exception {
        setup(true,true);
        var broken=new AuditAppender(){public void append(java.sql.Connection c,Entry e)throws java.sql.SQLException{throw new java.sql.SQLException("audit unavailable");}};
        try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->new R25AiSourceReadService(new byte[32],protection,cipher,broken).read(c,seed.request().actor(),opportunity.id(),Task.FIELDS));}
        Subject lead;
        try(var c=database.apiConnection()){lead=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->{var opening=io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id());return io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.databaseBacked(protection).selector(x,seed.tenant(),opening.leadId());});}
        deny(lead,"SALES_OPPORTUNITY_OWNER");
        var bomb=new LeadProtection(){public byte[] encrypt(UUID t,Field f,String v){throw new AssertionError();}public byte[] hmac(UUID t,Purpose p,String a,String v){throw new AssertionError();}public String decrypt(UUID t,Field f,byte[] v){throw new AssertionError("Denied source decrypted");}};
        try(var c=database.apiConnection()){assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->new R25AiSourceReadService(new byte[32],bomb,cipher,AuditAppender.databaseBacked("R25_AI_DENY")).read(c,seed.request().actor(),opportunity.id(),Task.FIELDS)).status());}
    }
}
