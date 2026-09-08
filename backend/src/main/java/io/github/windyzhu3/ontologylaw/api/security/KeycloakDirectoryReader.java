package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.identity.IdentityProviderDirectory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.security.authentication.*;

/** Fixed realm, separate query-users/view-users confidential client, no write API. */
public final class KeycloakDirectoryReader implements IdentityProviderDirectory {
    public record Trust(String issuer,String clientId,String secret) {
        public String toString(){return "DirectoryTrust[restricted]";}
    }
    private final Trust trust;private final HttpClient client;
    private KeycloakDirectoryReader(Trust trust,boolean isolated){this(trust,isolated,null);}
    public KeycloakDirectoryReader(Trust trust,javax.net.ssl.SSLContext tls){this(trust,false,Objects.requireNonNull(tls));}
    private KeycloakDirectoryReader(Trust trust,boolean isolated,javax.net.ssl.SSLContext tls){
        var uri=URI.create(trust.issuer());
        if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().matches("/realms/[A-Za-z0-9_-]+")
                ||!("https".equals(uri.getScheme())||isolated&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost()))||trust.clientId()==null||trust.clientId().isBlank()||trust.secret()==null||trust.secret().isBlank())throw new IllegalArgumentException("Invalid directory trust");
        this.trust=trust;var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER);if(tls!=null)builder.sslContext(tls);client=builder.build();
    }
    public KeycloakDirectoryReader(Trust trust){this(trust,false);}
    public static KeycloakDirectoryReader isolatedLoopback(Trust trust){return new KeycloakDirectoryReader(trust,true);}
    public String issuer(){return trust.issuer();}
    public Account exact(String identifier){
        if(identifier==null||identifier.isBlank()||identifier.length()>200)throw invalid();
        // Keycloak 26.7.3 username/exact includes service accounts and omits their client link.
        // Its quoted search branch excludes service accounts server-side; still require an exact username.
        var result=read("/users?max=2&search="+encoded("\""+identifier+"\""));
        if(!result.isArray())throw unavailable();if(result.size()!=1)throw invalid();var account=result.get(0);
        if(!identifier.equals(account.path("username").asString())||!account.path("enabled").isBoolean()||!account.path("enabled").asBoolean())throw invalid();
        return account(account);
    }
    public Account enabled(String subject){
        if(subject==null||!subject.matches("[A-Za-z0-9_-]{1,2048}"))throw invalid();
        var account=read("/users/"+encoded(subject));if(!subject.equals(account.path("id").asString())||!account.path("enabled").isBoolean()||!account.path("enabled").asBoolean())throw invalid();
        var human=exact(account.path("username").asString());if(!subject.equals(human.subject()))throw invalid();return human;
    }
    private Account account(tools.jackson.databind.JsonNode node){if(!node.path("id").isString())throw unavailable();if(node.hasNonNull("serviceAccountClientId"))throw invalid();return new Account(node.path("id").asString());}
    private tools.jackson.databind.JsonNode read(String suffix) {
        String form="grant_type=client_credentials&client_id="+encoded(trust.clientId())+"&client_secret="+encoded(trust.secret());
        var token=send(HttpRequest.newBuilder(URI.create(trust.issuer()+"/protocol/openid-connect/token")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),false);
        String access=token.path("access_token").asString();if(access==null||access.isBlank()||access.length()>16384)throw unavailable();
        String admin=trust.issuer().replace("/realms/","/admin/realms/");
        return send(HttpRequest.newBuilder(URI.create(admin+suffix)).header("Authorization","Bearer "+access).GET().build(),true);
    }
    private tools.jackson.databind.JsonNode send(HttpRequest request,boolean absentIsInvalid) {
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));
        try {
            var response=pending.get(2,java.util.concurrent.TimeUnit.SECONDS);if(absentIsInvalid&&response.statusCode()==404)throw invalid();if(response.statusCode()!=200)throw unavailable();
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException unavailable){throw unavailable();}
        catch(BadCredentialsException|AuthenticationServiceException classified){throw classified;}
        catch(RuntimeException malformed){throw unavailable();}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static String encoded(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static BadCredentialsException invalid(){return new BadCredentialsException("DIRECTORY_ACCOUNT_INVALID");}
    private static AuthenticationServiceException unavailable(){return new AuthenticationServiceException("SERVICE_UNAVAILABLE");}
}
