package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier;
import io.github.windyzhu3.ontologylaw.api.security.IdentityDeploymentFiles;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import io.github.windyzhu3.ontologylaw.lead.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Immutable deployment-only assembly; configuration is never accepted from an HTTP request. */
final class R1ApiDeployment {
    record Settings(String semanticBaseline,Database database,String node,String cursorKey,List<Trust> trusts,List<Registration> registrations,
                    List<Certificate> certificates,Map<UUID,TenantKeys> tenantKeys,Map<String,R1SourcePolicyRegistry.SourcePolicy> sources,
                    List<HumanTrust> humanTrusts,String identityTrustStorePath,String identityTrustStorePasswordPath) {
        public String toString(){return "R1ApiSettings[restricted]";}
    }
    record Database(String url,String username,String password,String schemaVersion,String releaseDigest,String manifestHash) {
        public String toString(){return "R1ApiDatabase[restricted]";}
    }
    record Trust(String issuer,String audience,String verificationKeyPath) {}
    record HumanTrust(String issuer,String audience,String identityProviderCode,UUID tenantId,String introspectionClientId,String introspectionSecretPath) {}
    record Registration(String issuer,String audience,String identityProviderCode,UUID tenantId,UUID principalId,UUID appointmentId,
                        PrincipalKind principalKind,UUID onBehalfPrincipalId,UUID onBehalfAppointmentId,Set<String> sourceAccountCodes) {
        Actor actor(){return new Actor(tenantId,principalId,appointmentId,onBehalfPrincipalId,onBehalfAppointmentId,principalKind);}
    }
    record Certificate(String sha256,String identityProviderCode,UUID tenantId,UUID principalId,UUID appointmentId) {
        Actor actor(){return new Actor(tenantId,principalId,appointmentId,null,null,PrincipalKind.SERVICE);}
    }
    record TenantKeys(String encryption,String phoneHmac,String emailHmac,String sourceHmac,String credentialSubjectHmac,String actorScopeHmac) {
        public String toString(){return "R1ApiTenantKeys[restricted]";}
    }
    final RuntimeDatabase database;
    final ActorContextResolver actors;
    final R1ApiServices services;
    final SessionContextController.Services session;final HumanCredentialVerifier humans;
    private R1ApiDeployment(RuntimeDatabase database,ActorContextResolver actors,R1ApiServices services,SessionContextController.Services session,HumanCredentialVerifier humans){this.database=database;this.actors=actors;this.services=services;this.session=session;this.humans=humans;}
    static R1ApiDeployment from(Environment environment) {
        try {
            var settings=Binder.get(environment).bind("ols.api",Settings.class).orElseThrow(()->new IllegalArgumentException());
            if(!"MVP-2026-09-08.3".equals(settings.semanticBaseline())||settings.node()==null||!settings.node().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw new IllegalArgumentException();
            if(!"want".equals(environment.getProperty("server.ssl.client-auth"))||environment.getProperty("server.ssl.key-store")==null||environment.getProperty("server.ssl.trust-store")==null||"false".equals(environment.getProperty("server.ssl.enabled")))throw new IllegalArgumentException();
            var db=Objects.requireNonNull(settings.database());
            var database=RuntimeDatabase.databaseBacked(RuntimeDatabase.jdbc(new RuntimeDatabase.JdbcLogin(db.url(),db.username(),db.password().toCharArray())),RuntimeDatabase.Role.API,
                    new RuntimeDatabase.Expected(db.schemaVersion(),digest(db.releaseDigest()),digest(db.manifestHash())));
            if(!database.healthy())throw new IllegalArgumentException();
            if(settings.trusts()==null||settings.trusts().isEmpty()||settings.registrations()==null||settings.registrations().isEmpty()||settings.certificates()==null||settings.certificates().isEmpty()||settings.sources()==null||settings.sources().isEmpty())throw new IllegalArgumentException();
            if(settings.humanTrusts()==null||settings.humanTrusts().isEmpty()||settings.registrations().stream().anyMatch(r->r.principalKind()!=PrincipalKind.SERVICE||r.onBehalfPrincipalId()!=null||r.onBehalfAppointmentId()!=null))throw new IllegalArgumentException();
            var configuredHumans=new ArrayList<HumanCredentialVerifier.Trust>();for(var trust:settings.humanTrusts())configuredHumans.add(new HumanCredentialVerifier.Trust(trust.issuer(),trust.audience(),trust.identityProviderCode(),trust.tenantId(),trust.introspectionClientId(),IdentityDeploymentFiles.secret(trust.introspectionSecretPath())));
            var humans=new HumanCredentialVerifier(configuredHumans,IdentityDeploymentFiles.tls(settings.identityTrustStorePath(),settings.identityTrustStorePasswordPath()));if(!humans.healthy())throw new IllegalArgumentException();
            var tenants=new HashSet<UUID>();settings.registrations().forEach(r->tenants.add(r.tenantId()));settings.certificates().forEach(r->tenants.add(r.tenantId()));settings.humanTrusts().forEach(r->tenants.add(r.tenantId()));
            if(settings.tenantKeys()==null||!settings.tenantKeys().keySet().equals(tenants))throw new IllegalArgumentException();
            var encryption=new HashMap<UUID,javax.crypto.SecretKey>();var hmac=new HashMap<UUID,Map<LeadProtection.Purpose,javax.crypto.SecretKey>>();var subjects=new HashMap<UUID,byte[]>();var scopes=new HashMap<UUID,byte[]>();
            var distinctKeys=new HashSet<String>();
            for(var entry:settings.tenantKeys().entrySet()) {
                var keys=entry.getValue();var encoded=List.of(keys.encryption(),keys.phoneHmac(),keys.emailHmac(),keys.sourceHmac(),keys.credentialSubjectHmac(),keys.actorScopeHmac());
                for(String key:encoded)if(!distinctKeys.add(Base64.getEncoder().encodeToString(key(key))))throw new IllegalArgumentException();
                encryption.put(entry.getKey(),new SecretKeySpec(key(keys.encryption()),"AES"));subjects.put(entry.getKey(),key(keys.credentialSubjectHmac()));
                scopes.put(entry.getKey(),key(keys.actorScopeHmac()));
                hmac.put(entry.getKey(),Map.of(LeadProtection.Purpose.LEAD_PHONE_EXACT,new SecretKeySpec(key(keys.phoneHmac()),"HmacSHA256"),LeadProtection.Purpose.LEAD_EMAIL_EXACT,new SecretKeySpec(key(keys.emailHmac()),"HmacSHA256"),LeadProtection.Purpose.SOURCE_RECORD_KEY,new SecretKeySpec(key(keys.sourceHmac()),"HmacSHA256")));
            }
            var protection=LeadProtection.aesGcm(new LeadProtection.Keys(){public javax.crypto.SecretKey encryption(UUID tenant){return Objects.requireNonNull(encryption.get(tenant));}public javax.crypto.SecretKey hmac(UUID tenant,LeadProtection.Purpose purpose){return Objects.requireNonNull(hmac.get(tenant)).get(purpose);}});
            var sources=new R1SourcePolicyRegistry(settings.sources());var serviceEntries=new ArrayList<R1ServiceSourceBinding.Entry>();var registrations=new ArrayList<ActorContextResolver.Registration>();
            for(var registration:settings.registrations()) {
                registrations.add(new ActorContextResolver.Registration(registration.issuer(),registration.audience(),registration.identityProviderCode(),registration.actor()));
                if(registration.principalKind()==PrincipalKind.SERVICE)serviceEntries.add(new R1ServiceSourceBinding.Entry(registration.issuer(),registration.audience(),registration.identityProviderCode(),registration.tenantId(),registration.principalId(),registration.appointmentId(),registration.sourceAccountCodes()));
                else if(registration.sourceAccountCodes()!=null&&!registration.sourceAccountCodes().isEmpty())throw new IllegalArgumentException();
            }
            var bindings=new R1AssemblyValidationRuntime().validate(database,c->{
                for(var trust:settings.humanTrusts())if(!HumanIdentityReader.databaseBacked().tenantActive(c,trust.tenantId()))throw new IllegalArgumentException();
                for(var registration:settings.registrations())identity(c,registration.actor(),registration.identityProviderCode());
                for(var certificate:settings.certificates())identity(c,certificate.actor(),certificate.identityProviderCode());
                return R1ServiceSourceBinding.validate(c,serviceEntries,sources);
            });
            var trusts=new ArrayList<ActorContextResolver.Trust>();for(var trust:settings.trusts())trusts.add(new ActorContextResolver.Trust(trust.issuer(),trust.audience(),publicKey(trust.verificationKeyPath())));
            var certificates=settings.certificates().stream().map(c->new ActorContextResolver.CertificateRegistration(c.sha256(),c.identityProviderCode(),c.actor())).toList();
            var actors=new ActorContextResolver(database::open,new ExternalSubjectProtection(subjects::get),humans,trusts,registrations,certificates);
            byte[] cursor=key(settings.cursorKey());if(!distinctKeys.add(Base64.getEncoder().encodeToString(cursor)))throw new IllegalArgumentException();
            return new R1ApiDeployment(database,actors,new R1ApiServices(database,sources,protection,bindings,settings.node(),cursor),new SessionContextController.Services(database::open,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(settings.node()),new ActorScopeProtection(scopes::get)),humans);
        }catch(Exception invalid){throw new IllegalStateException("R1_API_CONFIGURATION_UNAVAILABLE");}
    }
    private static RSAPublicKey publicKey(String file)throws Exception {
        var path=Path.of(file);if(!path.isAbsolute()||!Files.isRegularFile(path)||Files.size(path)>16384)throw new IllegalArgumentException();
        String pem=Files.readString(path);if(!pem.startsWith("-----BEGIN PUBLIC KEY-----")||!pem.stripTrailing().endsWith("-----END PUBLIC KEY-----"))throw new IllegalArgumentException();
        String encoded=pem.substring("-----BEGIN PUBLIC KEY-----".length(),pem.indexOf("-----END PUBLIC KEY-----")).replaceAll("\\s","");
        return (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    private static void identity(java.sql.Connection connection,Actor actor,String provider)throws java.sql.SQLException {
        var reader=AuthorizationIdentityReader.databaseBacked();var actual=reader.registration(connection,actor.tenantId(),actor.appointmentId());
        if(actual==null||!actual.principalId().equals(actor.principalId())||actual.principalKind()!=actor.principalKind()||!actual.identityProviderCode().equals(provider))throw new IllegalArgumentException();
        if(actor.onBehalfAppointmentId()!=null){var represented=reader.registration(connection,actor.tenantId(),actor.onBehalfAppointmentId());if(represented==null||!represented.principalId().equals(actor.onBehalfPrincipalId())||represented.principalKind()!=PrincipalKind.HUMAN)throw new IllegalArgumentException();}
    }
    private static byte[] key(String encoded){var bytes=Base64.getDecoder().decode(encoded);if(bytes.length!=32||!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new IllegalArgumentException();boolean nonzero=false;for(byte b:bytes)nonzero|=b!=0;if(!nonzero)throw new IllegalArgumentException();return bytes;}
    private static byte[] digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();return HexFormat.of().parseHex(value);}
    public String toString(){return "R1ApiDeployment[restricted]";}
}
