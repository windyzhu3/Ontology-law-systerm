package io.github.windyzhu3.ontologylaw.api.security;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.authentication.*;
import tools.jackson.databind.json.JsonMapper;

/** Fixed deployment trust; signature/claims first, uncached introspection second. No token-selected URLs. */
public final class HumanCredentialVerifier {
    public record Trust(String issuer,String audience,String provider,UUID tenantId,String introspectionClientId,String introspectionSecret) {
        public Trust {Objects.requireNonNull(tenantId);for(String value:List.of(issuer,audience,provider,introspectionClientId,introspectionSecret))if(value.isBlank())throw new IllegalArgumentException("Invalid HUMAN trust");if(!provider.matches("[A-Z][A-Z0-9_]{0,63}")||!audience.equals(introspectionClientId))throw new IllegalArgumentException("Invalid HUMAN trust");}
        public String toString(){return "HumanTrust[restricted]";}
    }
    public record Credential(UUID tenantId,String provider,String subject) {
        public String toString(){return "VerifiedHumanCredential[restricted]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final List<Trust> trusts;private final HttpClient client;private final Clock clock;
    public HumanCredentialVerifier(List<Trust> trusts){this(trusts,false,Clock.systemUTC(),null);}
    public HumanCredentialVerifier(List<Trust> trusts,javax.net.ssl.SSLContext tls){this(trusts,false,Clock.systemUTC(),Objects.requireNonNull(tls));}
    /** Explicit isolated loopback protocol fixture only. Production deployment always uses the strict constructor. */
    public static HumanCredentialVerifier isolatedLoopback(List<Trust> trusts){return new HumanCredentialVerifier(trusts,true,Clock.systemUTC(),null);}
    private HumanCredentialVerifier(List<Trust> trusts,boolean loopback,Clock clock,javax.net.ssl.SSLContext tls) {
        if(trusts==null||trusts.isEmpty())throw new IllegalArgumentException("HUMAN trust unavailable");
        var issuers=new HashSet<String>();var providerTenants=new HashSet<String>();
        for(var trust:trusts) {
            URI uri=URI.create(trust.issuer());
            if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().matches("/realms/[A-Za-z0-9_-]+")
                    ||!("https".equals(uri.getScheme())||loopback&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost()))
                    ||!issuers.add(trust.issuer())||!providerTenants.add(trust.tenantId()+":"+trust.provider()))throw new IllegalArgumentException("Ambiguous or insecure HUMAN trust");
        }
        this.trusts=List.copyOf(trusts);this.clock=clock;
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER);if(tls!=null)builder.sslContext(tls);client=builder.build();
    }
    public boolean trustsIssuer(String issuer){return trusts.stream().anyMatch(t->t.issuer().equals(issuer));}
    public boolean healthy() {
        try {for(var trust:trusts){if(JWKSet.parse(get(trust.issuer()+"/protocol/openid-connect/certs")).getKeys().isEmpty())return false;
            var response=send(HttpRequest.newBuilder(URI.create(trust.issuer()+"/protocol/openid-connect/token/introspect")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("client_id",trust.introspectionClientId(),"client_secret",trust.introspectionSecret(),"token","noncredential-readiness-probe")))).timeout(Duration.ofSeconds(2)).build());
            var body=JSON.readTree(response);if(!body.path("active").isBoolean()||body.path("active").asBoolean())return false;}return true;
        }catch(Exception unavailable){return false;}
    }
    public boolean acceptsIssuer(String token) {
        try {String issuer=SignedJWT.parse(token).getJWTClaimsSet().getIssuer();return trusts.stream().anyMatch(t->t.issuer().equals(issuer));}
        catch(Exception invalid){return false;}
    }
    public Credential verify(String token) {
        if(token==null||token.isBlank()||token.length()>16384)throw invalid();
        try {
            var jwt=SignedJWT.parse(token);var claims=jwt.getJWTClaimsSet();
            var trust=trusts.stream().filter(t->t.issuer().equals(claims.getIssuer())).findFirst().orElseThrow(HumanCredentialVerifier::invalid);
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())||jwt.getHeader().getKeyID()==null||jwt.getHeader().getCriticalParams()!=null
                    ||!claims.getAudience().contains(trust.audience())||claims.getSubject()==null||claims.getSubject().isEmpty()||claims.getSubject().length()>2048
                    ||claims.getExpirationTime()==null||!clock.instant().isBefore(claims.getExpirationTime().toInstant())
                    ||claims.getNotBeforeTime()!=null&&clock.instant().isBefore(claims.getNotBeforeTime().toInstant()))throw invalid();
            JWKSet keys;try{keys=JWKSet.parse(get(trust.issuer()+"/protocol/openid-connect/certs"));}catch(java.text.ParseException malformedRemote){throw unavailable();}
            var matching=keys.getKeys().stream().filter(key->jwt.getHeader().getKeyID().equals(key.getKeyID())&&key instanceof RSAKey&&KeyUse.SIGNATURE.equals(key.getKeyUse())).toList();
            if(matching.size()!=1)throw invalid();
            var publicKey=((RSAKey)matching.getFirst()).toRSAPublicKey();
            if(publicKey.getModulus().bitLength()<2048||!jwt.verify(new RSASSAVerifier(publicKey)))throw invalid();
            var response=send(HttpRequest.newBuilder(URI.create(trust.issuer()+"/protocol/openid-connect/token/introspect"))
                    .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("client_id",trust.introspectionClientId(),"client_secret",trust.introspectionSecret(),"token",token)))).timeout(Duration.ofSeconds(2)).build());
            var active=JSON.readTree(response);
            if(!active.isObject()||!active.path("active").isBoolean())throw unavailable();
            if(!active.path("active").asBoolean())throw invalid();
            if(!active.path("sub").isString()||active.path("sub").asString().isBlank()||!active.path("iss").isString()||active.path("iss").asString().isBlank())throw unavailable();
            if(!claims.getSubject().equals(active.path("sub").asString())||!trust.issuer().equals(active.path("iss").asString()))throw invalid();
            return new Credential(trust.tenantId(),trust.provider(),claims.getSubject());
        }catch(AuthenticationServiceException|BadCredentialsException classified){throw classified;}
        catch(java.text.ParseException|JOSEException|IllegalArgumentException invalid){throw invalid();}
        catch(RuntimeException unavailable){throw unavailable();}
    }
    private String get(String uri){return send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build());}
    private String send(HttpRequest request) {
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));
        try {
            // The future completes only after the bounded body, not merely response headers.
            var response=pending.get(2,java.util.concurrent.TimeUnit.SECONDS);
            if(response.statusCode()!=200)throw unavailable();
            return new String(response.body(),StandardCharsets.UTF_8);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException unavailable){throw unavailable();}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static String form(Map<String,String> data){return data.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private static BadCredentialsException invalid(){return new BadCredentialsException("UNAUTHENTICATED");}
    private static AuthenticationServiceException unavailable(){return new AuthenticationServiceException("SERVICE_UNAVAILABLE");}
}
