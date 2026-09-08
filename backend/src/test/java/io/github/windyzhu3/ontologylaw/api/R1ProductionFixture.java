package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import java.nio.file.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Only test fixtures create secrets or activate synthetic deployment expectations. */
public abstract class R1ProductionFixture extends R1HttpFixture {
    protected io.github.windyzhu3.ontologylaw.testing.KeycloakFixture identityProvider;
    private TlsFixture identityTls;
    private String actualBearer;
    @org.junit.jupiter.api.BeforeAll void realIdentityProvider(@org.junit.jupiter.api.io.TempDir Path identityDirectory)throws Exception{identityTls=new TlsFixture(identityDirectory);identityProvider=new io.github.windyzhu3.ontologylaw.testing.KeycloakFixture(identityTls).start();}
    @org.junit.jupiter.api.AfterAll void stopRealIdentityProvider(){if(identityProvider!=null)identityProvider.close();}
    @Override protected String bearer()throws Exception{return actualBearer==null?super.bearer():actualBearer;}
    @Override protected io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.Seed seedFor(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type type)throws Exception {
        var login=identityProvider.login();actualBearer=login.accessToken();
        return io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.seed(database,"HUMAN",type.authority,tenant->{byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);credentialKeys.put(tenant,key);return new io.github.windyzhu3.ontologylaw.identity.ExternalSubjectProtection(credentialKeys::get).digest(tenant,login.subject());});
    }
    protected record Deployment(Map<String,Object> api,Map<String,Object> worker,TlsFixture tls,TlsFixture.Key clientKey,TlsFixture.Key clientTrust,Actor service) {}
    protected Deployment deployment(Path directory)throws Exception {
        byte[] release=HexFormat.of().parseHex("11".repeat(32)),manifest=HexFormat.of().parseHex("22".repeat(32));
        try(var c=database.migratorConnection()){sql(c,"update platform_meta.deployment_state set operating_mode='ACTIVE',active_release_digest=?,active_manifest_hash=?,revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY' and (operating_mode,active_release_digest,active_manifest_hash) is distinct from ('ACTIVE',?,?)",release,manifest,release,manifest);}
        var actor=service("R1_PROJECTION_CONSUME");
        for(String authority:List.of("CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER"))mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),seed.org(),authority);
        var tls=new TlsFixture(directory);var server=tls.key("server");var client=tls.key("worker");var serverTrust=tls.trust("server-trust",client);var clientTrust=tls.trust("client-trust",server);
        var pem=directory.resolve("issuer-public.pem");Files.writeString(pem,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(signing.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
        var api=new TreeMap<String,Object>();api.putAll(Map.of("ols.runtime-role","api","server.port","0","spring.main.banner-mode","off","logging.level.root","OFF","server.ssl.client-auth","want"));
        api.putAll(Map.of("server.ssl.key-store",server.path().toString(),"server.ssl.key-store-password",new String(tls.password),"server.ssl.key-store-type","PKCS12","server.ssl.trust-store",serverTrust.path().toString(),"server.ssl.trust-store-password",new String(tls.password),"server.ssl.trust-store-type","PKCS12"));
        database(api,"ols.api.database",true);api.put("ols.api.semantic-baseline","MVP-2026-09-08.3");api.put("ols.api.node","PRODUCTION_API_IT");api.put("ols.api.cursor-key",encoded(0x65));
        api.putAll(Map.of("ols.api.trusts[0].issuer",ISSUER,"ols.api.trusts[0].audience",AUDIENCE,"ols.api.trusts[0].verification-key-path",pem.toString()));
        String registration="ols.api.registrations[0].";api.putAll(Map.of(registration+"issuer",ISSUER,registration+"audience",AUDIENCE,registration+"identity-provider-code","FIXTURE",registration+"tenant-id",seed.tenant().toString(),registration+"principal-id",actor.principalId().toString(),registration+"appointment-id",actor.appointmentId().toString(),registration+"principal-kind","SERVICE",registration+"source-account-codes[0]","FIXTURE"));
        var login=identityProvider.login();actualBearer=login.accessToken();
        var secret=directory.resolve("introspection.secret");Files.writeString(secret,identityProvider.introspectionSecret);var trustPassword=directory.resolve("identity-trust.secret");Files.writeString(trustPassword,new String(identityTls.password));
        String human="ols.api.human-trusts[0].";api.putAll(Map.of(human+"issuer",identityProvider.issuer(),human+"audience",io.github.windyzhu3.ontologylaw.testing.KeycloakFixture.AUDIENCE,human+"identity-provider-code","FIXTURE",human+"tenant-id",seed.tenant().toString(),human+"introspection-client-id",io.github.windyzhu3.ontologylaw.testing.KeycloakFixture.AUDIENCE,human+"introspection-secret-path",secret.toString()));
        api.put("ols.api.identity-trust-store-path",identityProvider.identityTrustPath().toString());api.put("ols.api.identity-trust-store-password-path",trustPassword.toString());
        String certificate="ols.api.certificates[0].";api.putAll(Map.of(certificate+"sha256",tls.sha256(client),certificate+"identity-provider-code","FIXTURE",certificate+"tenant-id",actor.tenantId().toString(),certificate+"principal-id",actor.principalId().toString(),certificate+"appointment-id",actor.appointmentId().toString()));
        String keys="ols.api.tenant-keys["+seed.tenant()+"].";api.put(keys+"encryption",encoded(0x51));api.put(keys+"actor-scope-hmac",encoded(0x67));
        byte[] business=new byte[32];Arrays.fill(business,(byte)0x51);int purpose=0;for(String field:List.of("phone-hmac","email-hmac","source-hmac")){business[0]=(byte)purpose++;api.put(keys+field,Base64.getEncoder().encodeToString(business));}api.put(keys+"credential-subject-hmac",Base64.getEncoder().encodeToString(credentialKeys.get(seed.tenant())));
        String source="ols.api.sources[FIXTURE].";api.putAll(Map.of(source+"assignment-mode","MANUAL",source+"routing-organization-root-codes[0]","ROOT",source+"routing-supervisor-root-code","ROOT",source+"source-intake-root-code","ROOT",source+"business-timezone","Asia/Shanghai"));
        var worker=new TreeMap<String,Object>();worker.putAll(Map.of("ols.runtime-role","worker","spring.main.banner-mode","off","logging.level.root","OFF","ols.worker.semantic-baseline","MVP-2026-09-08.3","ols.worker.node","PRODUCTION_WORKER_IT"));database(worker,"ols.worker.database",false);
        String binding="ols.worker.bindings[0].";worker.putAll(Map.of(binding+"tenant-id",actor.tenantId().toString(),binding+"principal-id",actor.principalId().toString(),binding+"appointment-id",actor.appointmentId().toString(),binding+"credential-alias",client.alias(),binding+"certificate-sha256",tls.sha256(client),binding+"key-store-path",client.path().toString(),binding+"key-store-password",new String(tls.password),binding+"trust-store-path",clientTrust.path().toString(),binding+"trust-store-password",new String(tls.password)));
        return new Deployment(api,worker,tls,client,clientTrust,actor);
    }
    private void database(Map<String,Object> properties,String prefix,boolean api){properties.putAll(Map.of(prefix+".url",database.jdbcUrl(),prefix+".username",api?"law_api_login":"law_worker_login",prefix+".password",api?database.apiPassword():database.workerPassword(),prefix+".schema-version","52-plus-2-v1.2",prefix+".release-digest","11".repeat(32),prefix+".manifest-hash","22".repeat(32)));}
    static String encoded(int fill){byte[] value=new byte[32];Arrays.fill(value,(byte)fill);return Base64.getEncoder().encodeToString(value);}
}
