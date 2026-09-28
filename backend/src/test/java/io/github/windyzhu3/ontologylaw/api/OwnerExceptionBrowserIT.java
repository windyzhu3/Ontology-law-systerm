package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real database + authorized HTTP + rendered React; isolated fixture credentials remain in memory. */
@EnabledIfSystemProperty(named="t01.browser.node",matches=".+")
class OwnerExceptionBrowserIT extends R1HttpFixture {
    @Test void supervisor_confirms_real_transfer_in_browser_and_exact_business_facts_commit()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        Subject opportunity;
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{
            grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
            return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();
        });}
        var receiver=credentialActor(PrincipalKind.HUMAN,"browser-receiver","SALES_OPPORTUNITY_OWNER");
        var observer=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"T01_BROWSER_IT");
        var observation=new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),observer,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        CommandOutcome observed;
        try(var c=database.apiConnection()){observed=assertInstanceOf(CommandOutcome.class,runtime.execute(c,observation));assertEquals(CommandOutcome.Status.SUCCEEDED,observed.status());}
        var root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(root.getFileName().toString().equals("backend"))root=root.getParent();
        var directory=root.resolve("output/t01-live-browser");Files.createDirectories(directory);
        try(var http=new HttpHarness()){
            var actor=new LinkedHashMap<String,Object>();actor.put("identityEpoch",1);actor.put("actorScopeKey","ask1."+"A".repeat(43));actor.put("selectedAppointmentId",seed.appointment().toString());actor.put("selectedOnBehalfAppointmentId",null);actor.put("displayName","主管 · 浏览器验收");
            var input=Map.of("origin",http.origin.toString(),"bearer",http.bearer,"actor",actor,"outputDir",directory.toString(),"receiverAppointmentId",receiver.appointmentId().toString(),"exceptionId",observed.resultFact().id().toString());
            var process=new ProcessBuilder(System.getProperty("t01.browser.node"),root.resolve("e2e/tests/t01-owner-exception-browser.mjs").toString()).directory(root.toFile()).redirectOutput(directory.resolve("browser.log").toFile()).redirectError(directory.resolve("browser-error.log").toFile()).start();
            try{
                try(var stdin=process.getOutputStream()){stdin.write(mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8));}
                assertTrue(process.waitFor(90,TimeUnit.SECONDS),"Browser acceptance timed out; see isolated output logs");assertEquals(0,process.exitValue(),"Browser acceptance failed; see output/t01-live-browser logs");
            }finally{if(process.isAlive())process.destroyForcibly();}
        }
        assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current and state='RESOLVED'",seed.tenant()));
        assertEquals("1",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=? and opportunity_id=? and to_appointment_id=?",seed.tenant(),opportunity.id(),receiver.appointmentId()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and owner_appointment_id=? and state='OPEN'",seed.tenant(),opportunity.id(),receiver.appointmentId()));
        assertEquals(seed.appointment().toString(),scalar("select owner_appointment_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
}
