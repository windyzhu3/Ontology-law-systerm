package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.responsibility.IdentityDependencyReader;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic founder fixture; test target facts are built through the production Identity runtime. */
abstract class IdentityAdminFixture extends PostgresIntegrationTest {
    AuthorizationServiceIT.Seed seed;Actor actor;
    final byte[] subjectKey=key(),candidateKey=key(),tagKey=key(),cursorKey=key();
    final IdentityCandidateProtection candidates=new IdentityCandidateProtection("online-fixture",Map.of("online-fixture",candidateKey));
    final IdentityResourceProtection protection=new IdentityResourceProtection(tagKey,cursorKey);
    final AuditAppender audit=AuditAppender.databaseBacked("TASK93_IDENTITY_IT");
    final IdentityProviderDirectory directory=new IdentityProviderDirectory(){public String issuer(){return "https://synthetic.example.invalid/realms/identity";}public Account exact(String name){return new Account(name);}public Account enabled(String subject){return new Account(subject);}};
    IdentityCommandRuntime runtime;
    @BeforeEach void administration()throws Exception {
        UUID tenant=UUID.randomUUID();String tenantCode="T"+tenant.toString().replace("-","");var binding=new BootstrapCandidateProtection.Binding("Synthetic authorized operator",tenantCode,"FIXTURE",directory.issuer());
        var offline=new BootstrapCandidateProtection("offline-fixture",Map.of("offline-fixture",key()));String selector=offline.issue(binding,"founder",directory,Instant.now());
        var manifest=new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),tenantCode,"Synthetic tenant","ROOT","Root","FIXTURE",directory.issuer(),selector,"Founder",Instant.now().minusSeconds(120),binding.operatorAssertion());
        try(var c=database.apiConnection()){new IdentityBootstrapRuntime(tenant,binding,offline,new ExternalSubjectProtection(t->subjectKey),directory,audit).run(c,manifest,false);}
        UUID principal=UUID.fromString(scalar("select principal_id from identity.principal where tenant_id=?",tenant)),appointment=UUID.fromString(scalar("select appointment_id from identity.appointment where tenant_id=?",tenant)),root=UUID.fromString(scalar("select organization_unit_id from identity.organization_unit where tenant_id=?",tenant)),grant=UUID.fromString(scalar("select authority_grant_id from identity.authority_grant where tenant_id=? and authority_code='IDENTITY_PRINCIPAL_MANAGE'",tenant));
        seed=new AuthorizationServiceIT.Seed(tenant,principal,appointment,root,grant,UUID.randomUUID());actor=new Actor(tenant,principal,appointment,null,null);
        runtime=runtime(audit);
    }
    IdentityCommandRuntime runtime(AuditAppender append){return new IdentityCommandRuntime(append,protection,candidates,new ExternalSubjectProtection(t->subjectKey),"FIXTURE",directory,IdentityDependencyReader.databaseBacked()::open);}
    IdentityCommandRuntime.Result execute(String command,UUID id,String tag,Map<String,Object> body)throws Exception{return execute(UUID.randomUUID(),command,id,tag,body);}
    IdentityCommandRuntime.Result execute(UUID key,String command,UUID id,String tag,Map<String,Object> body)throws Exception{try(var c=database.apiConnection()){return runtime.execute(c,envelope(key,command,id,tag,body));}}
    CommandEnvelope envelope(UUID key,String command,UUID id,String tag,Map<String,Object> body){return new CommandEnvelope(CommandEnvelope.Type.valueOf(command),key,UUID.randomUUID(),actor,body,null,null,new CommandEnvelope.IdentityPrecondition(id,tag));}
    UUID principal()throws Exception {String selector=candidates.issue(actor,"FIXTURE",directory.issuer(),"synthetic",UUID.randomUUID().toString(),Instant.now());return success(execute("CREATE_IDENTITY_PRINCIPAL",null,null,Map.of("providerUserSelector",selector,"displayName","Synthetic user"))).id();}
    UUID organization()throws Exception{return success(execute("CREATE_ORGANIZATION_UNIT",null,null,Map.of("parentOrganizationId",seed.org().toString(),"code","O"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT),"displayName","Synthetic organization"))).id();}
    UUID appointment(UUID principal,UUID organization)throws Exception{return success(execute("CREATE_APPOINTMENT",null,null,body("principalId",principal.toString(),"organizationId",organization.toString(),"roleCode","CONTACT_OPERATOR","effectiveFrom",Instant.now().minusSeconds(60).toString(),"effectiveUntil",null))).id();}
    UUID grant(UUID appointment,UUID organization)throws Exception{return success(execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",appointment.toString(),"scopeOrganizationId",organization.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom",Instant.now().minusSeconds(10).toString(),"validUntil",null))).id();}
    String tag(String command,UUID id)throws Exception {try(var c=database.apiConnection()){return runtime.precondition(c,actor,command,id);}}
    IdentityCommandRuntime.Result change(String command,UUID id)throws Exception{return execute(command,id,tag(command,id),Map.of("reasonCode","ADMINISTRATIVE_ACTION"));}
    Subject success(IdentityCommandRuntime.Result r){assertEquals(CommandOutcome.Status.SUCCEEDED,r.receipt().outcome().status(),r.receipt().outcome().rejectionCode());return r.receipt().outcome().resultFact();}
    void rejected(String code,IdentityCommandRuntime.Result r){assertEquals(CommandOutcome.Status.REJECTED,r.receipt().outcome().status());assertEquals(code,r.receipt().outcome().rejectionCode());assertNull(r.receipt().outcome().resultFact());}
    void mutate(String sql,Object... args)throws Exception {try(var c=database.adminConnection()){AuthorizationServiceIT.sql(c,sql,args);}}
    String scalar(String sql,Object... args)throws Exception{try(var c=database.migratorConnection();var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){r.next();return r.getString(1);}}}
    List<Long> counts()throws Exception{var counts=new ArrayList<Long>();for(String table:List.of("identity.principal","identity.organization_unit","identity.appointment","identity.authority_grant","execution.command_execution_slot","execution.command_receipt","audit.audit_entry","responsibility.task_occurrence","responsibility.action_draft","execution.domain_event","execution.domain_event_outbox"))counts.add(Long.valueOf(scalar("select count(*) from "+table+" where tenant_id=?",seed.tenant())));return counts;}
    void delta(List<Long> before,int fact)throws Exception {var expected=new ArrayList<>(before);if(fact>=0)expected.set(fact,expected.get(fact)+1);for(int index:List.of(4,5,6))expected.set(index,expected.get(index)+1);assertEquals(expected,counts());}
    static Map<String,Object> body(Object... pairs){var values=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;}
    static byte[] key(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
}
