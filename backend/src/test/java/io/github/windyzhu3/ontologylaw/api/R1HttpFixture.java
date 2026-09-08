package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

abstract class R1HttpFixture extends ContactFlowFixture {
    static final String ISSUER="https://r1-http.example.test",AUDIENCE="r1-http-fixture",SUBJECT="exact HTTP fixture subject";
    final Map<UUID,byte[]> credentialKeys=new HashMap<>();
    final KeyPair signing=signing();
    final JsonMapper mapper=JsonMapper.builder().build();
    java.util.function.UnaryOperator<java.sql.Connection> disclosureConnection=java.util.function.UnaryOperator.identity();
    java.util.function.UnaryOperator<java.sql.Connection> credentialConnection=java.util.function.UnaryOperator.identity();
    @org.junit.jupiter.api.BeforeEach void resetDisclosureFault(){disclosureConnection=java.util.function.UnaryOperator.identity();credentialConnection=java.util.function.UnaryOperator.identity();}
    static KeyPair signing(){try{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    protected AuthorizationServiceIT.Seed seedFor(TaskFactory.Type type)throws Exception{return AuthorizationServiceIT.seed(database,"HUMAN",type.authority,this::credentialHmac);}
    byte[] credentialHmac(UUID tenant){try{byte[] key=new byte[32];new SecureRandom().nextBytes(key);credentialKeys.put(tenant,key);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(SUBJECT.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new AssertionError(e);}}
    protected String bearer()throws Exception {return bearer(SUBJECT);}
    String bearer(String subject)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject).issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("tenantId",UUID.randomUUID().toString()).claim("authority","FORGED_ADMIN").build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);jwt.sign(new RSASSASigner(signing.getPrivate()));return jwt.serialize();
    }
    final class HttpHarness implements AutoCloseable {
        final ConfigurableApplicationContext context;final HttpClient client;final URI origin;final String bearer;
        io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings.Binding workerBinding;
        io.github.windyzhu3.ontologylaw.worker.InternalApiClient.Credentials workerCredentials;
        final java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
        HttpHarness()throws Exception {this(null,null);}
        HttpHarness(Actor serviceActor,io.github.windyzhu3.ontologylaw.testing.TlsFixture tls)throws Exception {
            this(serviceActor,tls,null,SUBJECT,List.of());
        }
        HttpHarness(Actor publicActor,String subject,List<R1ServiceSourceBinding.Entry> entries)throws Exception {this(null,null,publicActor,subject,entries);}
        HttpHarness(Actor serviceActor,io.github.windyzhu3.ontologylaw.testing.TlsFixture tls,Actor publicActor,String subject,List<R1ServiceSourceBinding.Entry> entries)throws Exception {
            this.bearer=bearer(subject);var authenticated=publicActor==null?seed.request().actor():publicActor;
            byte[] release=HexFormat.of().parseHex("11".repeat(32)),manifest=HexFormat.of().parseHex("22".repeat(32));
            try(var c=database.migratorConnection()){sql(c,"update platform_meta.deployment_state set operating_mode='ACTIVE',active_release_digest=?,active_manifest_hash=?,revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY' and (operating_mode,active_release_digest,active_manifest_hash) is distinct from ('ACTIVE',?,?)",release,manifest,release,manifest);}
            var runtimeDatabase=RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.API,new RuntimeDatabase.Expected("52-plus-2-v1.2",release,manifest));
            var certificateBindings=new ArrayList<ActorContextResolver.CertificateRegistration>();var properties=new ArrayList<>(List.of("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF"));
            if(tls!=null){var server=tls.key("server");var worker=tls.key("worker");var serverTrust=tls.trust("server-trust",worker);var clientTrust=tls.trust("client-trust",server);certificateBindings.add(new ActorContextResolver.CertificateRegistration(tls.sha256(worker),"FIXTURE",serviceActor));client=HttpClient.newBuilder().sslContext(tls.client(worker,clientTrust)).build();
                workerBinding=new io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings.Binding(serviceActor.tenantId(),serviceActor.principalId(),serviceActor.appointmentId(),worker.alias(),tls.sha256(worker));
                workerCredentials=new io.github.windyzhu3.ontologylaw.worker.InternalApiClient.Credentials(worker.store(),tls.password,clientTrust.store());
                properties.addAll(List.of("server.ssl.key-store="+server.path(),"server.ssl.key-store-password="+new String(tls.password),"server.ssl.key-store-type=PKCS12","server.ssl.trust-store="+serverTrust.path(),"server.ssl.trust-store-password="+new String(tls.password),"server.ssl.trust-store-type=PKCS12","server.ssl.client-auth=want"));
            }else client=HttpClient.newHttpClient();
            var resolver=new ActorContextResolver(()->credentialConnection.apply(runtimeDatabase.open()),new ExternalSubjectProtection(credentialKeys::get),List.of(new ActorContextResolver.Trust(ISSUER,AUDIENCE,(RSAPublicKey)signing.getPublic())),List.of(new ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",authenticated)),certificateBindings);
            var sourceBindings=entries.isEmpty()?null:new R1AssemblyValidationRuntime().validate(runtimeDatabase,c->R1ServiceSourceBinding.validate(c,entries,policies));
            var disclosureDatabase=new RuntimeDatabase(){public java.sql.Connection open()throws java.sql.SQLException{return disclosureConnection.apply(runtimeDatabase.open());}public boolean healthy(){return runtimeDatabase.healthy();}};
            var services=new R1ApiServices(disclosureDatabase,policies,protection,sourceBindings,"HTTP_IT",new byte[32]);
            context=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var beans=(GenericApplicationContext)c;beans.registerBean(ActorContextResolver.class,()->resolver);beans.registerBean(R1ApiServices.class,()->services);
                beans.registerBean("httpRequestCounterFixture",org.springframework.boot.web.servlet.FilterRegistrationBean.class,()->{var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>((request,response,chain)->{received.incrementAndGet();chain.doFilter(request,response);});registration.setOrder(Integer.MIN_VALUE);return registration;});})
                    .properties(properties.toArray(String[]::new)).run();
            origin=URI.create((tls==null?"http":"https")+"://localhost:"+context.getEnvironment().getRequiredProperty("local.server.port",Integer.class));
        }
        HttpResponse<String> request(String method,String path,Object body,Map<String,String> headers)throws Exception {
            var builder=HttpRequest.newBuilder(origin.resolve(path)).header("Authorization","Bearer "+bearer).header("Accept","application/json");headers.forEach(builder::header);
            if(body==null)builder.method(method,HttpRequest.BodyPublishers.noBody());else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body instanceof String text?text:mapper.writeValueAsString(body)));
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        Map<String,Object> body(HttpResponse<String> response){return mapper.readValue(response.body(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});}
        io.github.windyzhu3.ontologylaw.worker.InternalApiClient workerClient(){return workerClient(()->true);}
        io.github.windyzhu3.ontologylaw.worker.InternalApiClient workerClient(java.util.function.BooleanSupplier gate){return new io.github.windyzhu3.ontologylaw.worker.InternalApiClient(origin,new io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings("MVP-2026-09-08.1",List.of(workerBinding)),Map.of(workerBinding.credentialAlias(),workerCredentials),gate);}
        public void close(){client.close();context.close();}
    }
    Actor credentialActor(AuthorizationService.PrincipalKind kind,String subject,String authority)throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();byte[] digest=new ExternalSubjectProtection(credentialKeys::get).digest(seed.tenant(),subject);
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,'FIXTURE',?,'HTTP actor fixture','ACTIVE',clock_timestamp())",seed.tenant(),principal,kind.name(),digest);
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'HTTP_FIXTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);
        return new Actor(seed.tenant(),principal,appointment,null,null,kind);
    }
}
