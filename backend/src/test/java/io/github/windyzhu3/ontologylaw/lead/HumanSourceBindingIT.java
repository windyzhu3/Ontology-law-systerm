package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;
import org.junit.jupiter.api.Test;

class HumanSourceBindingIT extends LeadBusinessFixture {
    private R1HumanSourceBinding binding(String account) {
        return new R1HumanSourceBinding(List.of(new R1HumanSourceBinding.Entry(seed.tenant(),seed.request().actor().principalId(),account)),sources);
    }
    private void configured(R1HumanSourceBinding bindings) {
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection(),bindings).handlers(),AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("HUMAN_SOURCE_IT"),R1AuthorizationReaders.databaseBacked(sources),R1EventReaders.databaseBacked());
    }
    private void setupSources() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);
        var policy=sources.find("FIXTURE");sources=new R1SourcePolicyRegistry(Map.of("FIXTURE",policy,"OTHER",policy));
        configured(binding("FIXTURE"));
    }
    private List<LeadIntakeSources.Source> read(R1HumanSourceBinding bindings,Actor actor) throws Exception {
        var catalog=new LeadIntakeSources(sources,List.of(
            new LeadIntakeSources.Source("FIXTURE","本人来源","MANUAL","CONSULTATION","CN","NORMAL"),
            new LeadIntakeSources.Source("OTHER","其他来源","MANUAL","CONSULTATION","CN","NORMAL")),bindings);
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->catalog.read(x,actor));}
    }
    @Test void catalog_discloses_only_own_source_and_unbound_human_is_closed() throws Exception {
        setupSources();var current=seed.request().actor();
        assertEquals(List.of("FIXTURE"),read(binding("FIXTURE"),current).stream().map(LeadIntakeSources.Source::sourceAccountCode).toList());
        var other=actor("HUMAN","LEAD_CAPTURE");assertTrue(read(binding("FIXTURE"),other).isEmpty());
        var before=counts();var forged=new TreeMap<>(captureValues("forged",true));forged.put("sourceAccountCode","OTHER");
        var result=run(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),current,forged));
        assertEquals("NOT_AUTHORIZED",result.rejectionCode());assertEquals(before.get(0),counts().get(0));assertEquals(before.get(4),counts().get(4));
        var absent=run(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),other,captureValues("unbound",true)));
        assertEquals("NOT_AUTHORIZED",absent.rejectionCode());assertEquals(before.get(0),counts().get(0));
    }
    @Test void binding_follows_principal_across_appointments_without_combining_grants() throws Exception {
        setupSources();var current=seed.request().actor();var second=UUID.randomUUID();
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),second,current.principalId(),seed.org());
        var alternate=new Actor(seed.tenant(),current.principalId(),second,null,null);
        assertTrue(binding("FIXTURE").permits(alternate,"FIXTURE"));assertTrue(read(binding("FIXTURE"),alternate).isEmpty());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),second,seed.appointment(),seed.org());
        assertEquals(1,read(binding("FIXTURE"),alternate).size());
    }
    @Test void current_revocation_and_suspension_still_block_bound_source() throws Exception {
        setupSources();var bindings=binding("FIXTURE");assertEquals(1,read(bindings,seed.request().actor()).size());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='LEAD_CAPTURE'",seed.tenant());
        assertTrue(read(bindings,seed.request().actor()).isEmpty());rejectedBefore(capture("revoked",true),"NOT_AUTHORIZED");
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());
        assertTrue(read(bindings,seed.request().actor()).isEmpty());
    }
    @Test void original_receipt_replay_survives_binding_change_without_rewriting_source() throws Exception {
        setupSources();var command=capture("original",true);var original=run(command);assertEquals(CommandOutcome.Status.SUCCEEDED,original.status());
        var before=counts();configured(binding("OTHER"));assertEquals(original,run(command));assertEquals(before,counts());
        assertEquals("FIXTURE",scalar("select source_account_code from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.resultFact().id()));
        assertEquals("NOT_AUTHORIZED",run(capture("new-wrong-source",true)).rejectionCode());
    }
    @Test void legacy_tenant_and_service_semantics_remain_and_configuration_is_unambiguous() throws Exception {
        setupSources();var current=seed.request().actor();var bound=binding("FIXTURE");
        assertTrue(bound.enabled(seed.tenant()));assertFalse(bound.permits(current,"OTHER"));
        assertTrue(bound.permits(new Actor(seed.tenant(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE),"OTHER"));
        assertTrue(bound.permits(new Actor(UUID.randomUUID(),current.principalId(),current.appointmentId(),null,null),"OTHER"));
        assertFalse(bound.permits(new Actor(seed.tenant(),UUID.randomUUID(),current.appointmentId(),null,null),"FIXTURE"));
        assertEquals(2,read(new R1HumanSourceBinding(List.of(),sources),current).size());
        var entry=new R1HumanSourceBinding.Entry(seed.tenant(),current.principalId(),"FIXTURE");
        assertThrows(IllegalArgumentException.class,()->new R1HumanSourceBinding(List.of(entry,entry),sources));
        assertThrows(IllegalArgumentException.class,()->new R1HumanSourceBinding(List.of(entry,new R1HumanSourceBinding.Entry(seed.tenant(),current.principalId(),"OTHER")),sources));
        assertThrows(IllegalArgumentException.class,()->new R1HumanSourceBinding(List.of(new R1HumanSourceBinding.Entry(seed.tenant(),current.principalId(),"UNKNOWN")),sources));
    }
    @Test void human_binding_does_not_replace_the_registered_service_protocol() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var service=actor("SERVICE","LEAD_CAPTURE");
        var command=new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),service,captureValues("bound-service",false));
        configured(binding("FIXTURE"));rejectedBefore(command,"NOT_AUTHORIZED");
        R1ServiceSourceBinding registered;
        try(var c=database.apiConnection()){registered=inTransaction(c,Capability.QUERY,x->R1ServiceSourceBinding.validate(x,List.of(new R1ServiceSourceBinding.Entry("https://synthetic.example","law-api","FIXTURE",seed.tenant(),service.principalId(),service.appointmentId(),Set.of("FIXTURE"))),sources));}
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection(),binding("FIXTURE")).handlers(),AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("HUMAN_SOURCE_IT"),R1AuthorizationReaders.databaseBacked(sources,registered),R1EventReaders.databaseBacked());
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(command).status());assertEquals(seed.appointment(),task("COMPLETE_LEAD_INGRESS").owner());
    }
}
