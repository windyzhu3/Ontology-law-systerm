package io.github.windyzhu3.ontologylaw.testing;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.*;
import tools.jackson.databind.json.JsonMapper;

/** Isolated protocol fixture: synthetic credentials remain in memory/container import, never logged. */
public final class KeycloakFixture implements AutoCloseable {
    public static final String REALM="task92", CLIENT="task92-spa", AUDIENCE="task92-api";
    public static final String REDIRECT="http://127.0.0.1:19092/callback";
    public final String introspectionSecret=random(), directorySecret=random();
    public final String username="synthetic-user", password=random();
    private final Network network=Network.newNetwork();
    private PostgreSQLContainer postgres;
    private GenericContainer<?> keycloak;
    private final TlsFixture tls;private TlsFixture.Key identityTrust;private javax.net.ssl.SSLContext tlsContext;
    private byte[] databaseCertificate;
    public KeycloakFixture(){tls=null;}
    public KeycloakFixture(TlsFixture tls){this.tls=Objects.requireNonNull(tls);}
    public java.nio.file.Path identityTrustPath(){return identityTrust.path();}
    public javax.net.ssl.SSLContext identityTls(){return tlsContext;}
    public boolean identityDatabaseConnectionsUseTls()throws Exception {
        try(var c=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var p=c.prepareStatement("select count(*)>0 and bool_and(s.ssl) from pg_stat_activity a join pg_stat_ssl s using(pid) where a.usename=? and a.pid<>pg_backend_pid()")) {
            p.setString(1,postgres.getUsername());try(var r=p.executeQuery()){r.next();return r.getBoolean(1);}
        }
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public String issuer(){return (tls==null?"http://127.0.0.1:":"https://localhost:")+keycloak.getMappedPort(tls==null?8080:8443)+"/realms/"+REALM;}
    public KeycloakFixture start() throws Exception {
        try {
            postgres=new PostgreSQLContainer(DockerImageName.parse(PostgresIntegrationTest.lockedPostgresImage()).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("identity_only").withUsername("identity_only").withPassword(random()).withNetwork(network).withNetworkAliases("identity-db");
            if(tls!=null) {
                var databaseKey=tls.key("identity-database","SAN=dns:identity-db");databaseCertificate=pem("CERTIFICATE",databaseKey.store().getCertificate(databaseKey.alias()).getEncoded());
                postgres.withCopyToContainer(ownedFile(databaseCertificate,999),"/tmp/identity-db.crt")
                        .withCopyToContainer(ownedFile(pem("PRIVATE KEY",databaseKey.store().getKey(databaseKey.alias(),tls.password).getEncoded()),999),"/tmp/identity-db.key")
                        .withCommand("postgres","-c","ssl=on","-c","ssl_cert_file=/tmp/identity-db.crt","-c","ssl_key_file=/tmp/identity-db.key");
            }
            postgres.start();
            var lock=JSON.readTree(java.nio.file.Files.readString(PostgresIntegrationTest.repositoryRoot().resolve("deploy/identity/identity-toolchain.lock.json")));
            String image=lock.path("keycloak").path("image").asString()+"@"+lock.path("keycloak").path("platformDigest").asString();
            int port=tls==null?8080:8443;
            keycloak=new GenericContainer<>(DockerImageName.parse(image)).withNetwork(network).withExposedPorts(port)
                    .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"),new ExposedPort(port))))
                    .withEnv("KC_DB","postgres").withEnv("KC_DB_URL","jdbc:postgresql://identity-db:5432/identity_only")
                    .withEnv("KC_DB_USERNAME",postgres.getUsername()).withEnv("KC_DB_PASSWORD",postgres.getPassword())
                    .withEnv("KC_HOSTNAME_STRICT","false")
                    .withCopyToContainer(ownedImport(JSON.writeValueAsBytes(realm())),"/opt/keycloak/data/import/task92-realm.json")
                    .withCommand("start-dev","--import-realm").waitingFor(Wait.forHttp("/realms/"+REALM+"/.well-known/openid-configuration").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(3));
            if(tls!=null) {
                var key=tls.key("identity","SAN=dns:localhost");identityTrust=tls.trust("identity-trust",key);tlsContext=tls.client(null,identityTrust);
                keycloak.withEnv("KC_HOSTNAME","localhost").withEnv("KC_HOSTNAME_STRICT","true").withEnv("KC_HTTP_ENABLED","false")
                        .withEnv("KC_DB_URL","jdbc:postgresql://identity-db:5432/identity_only?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/identity-db-ca.crt")
                        .withCopyToContainer(ownedImport(databaseCertificate),"/opt/keycloak/conf/identity-db-ca.crt")
                        .withEnv("KC_HTTPS_CERTIFICATE_FILE","/opt/keycloak/conf/identity.crt").withEnv("KC_HTTPS_CERTIFICATE_KEY_FILE","/opt/keycloak/conf/identity.key")
                        .withCopyToContainer(ownedImport(pem("CERTIFICATE",key.store().getCertificate(key.alias()).getEncoded())),"/opt/keycloak/conf/identity.crt")
                        .withCopyToContainer(ownedImport(pem("PRIVATE KEY",key.store().getKey(key.alias(),tls.password).getEncoded())),"/opt/keycloak/conf/identity.key")
                        .withCommand("start","--import-realm").waitingFor(Wait.forLogMessage(".*Listening on: https://.*8443.*\\n",1));
            }
            keycloak.start();return this;
        }catch(Exception|Error failure){close();throw failure;}
    }
    private static byte[] pem(String kind,byte[] der){return ("-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(der)+"\n-----END "+kind+"-----\n").getBytes(StandardCharsets.US_ASCII);}
    private HttpClient client(){var builder=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2));if(tlsContext!=null)builder.sslContext(tlsContext);return builder.build();}
    private static Transferable ownedImport(byte[] bytes) {return ownedFile(bytes,1000);}
    private static Transferable ownedFile(byte[] bytes,int uid) {
        return new Transferable() {
            public long getSize(){return bytes.length;}
            public byte[] getBytes(){return bytes.clone();}
            public String getDescription(){return "Restricted synthetic realm import";}
            public void transferTo(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar,String path) {
                try {var entry=new org.apache.commons.compress.archivers.tar.TarArchiveEntry(path);entry.setSize(bytes.length);entry.setMode(0600);entry.setUserId(uid);entry.setGroupId(uid==999?999:0);tar.putArchiveEntry(entry);tar.write(bytes);tar.closeArchiveEntry();}
                catch(java.io.IOException failure){throw new IllegalStateException("Synthetic import transfer unavailable");}
            }
        };
    }
    private Map<String,Object> realm() throws Exception {
        var spa=new LinkedHashMap<String,Object>();spa.put("clientId",CLIENT);spa.put("publicClient",true);spa.put("standardFlowEnabled",true);spa.put("directAccessGrantsEnabled",false);spa.put("implicitFlowEnabled",false);spa.put("redirectUris",List.of(REDIRECT));spa.put("webOrigins",List.of("http://127.0.0.1:19092"));spa.put("attributes",Map.of("pkce.code.challenge.method","S256"));
        spa.put("protocolMappers",List.of(Map.of("name","api-audience","protocol","openid-connect","protocolMapper","oidc-audience-mapper","config",Map.of("included.client.audience",AUDIENCE,"access.token.claim","true","id.token.claim","false"))));
        // Import the actual secret-free realm policy; only addresses, synthetic clients/users and explicit local TLS mode differ.
        var realm=new LinkedHashMap<String,Object>(JSON.readValue(java.nio.file.Files.readString(PostgresIntegrationTest.repositoryRoot().resolve("deploy/identity/realm-template.json")),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        realm.put("realm",REALM);realm.put("sslRequired",tls==null?"none":"all");
        var shortLived=new LinkedHashMap<>(spa);shortLived.put("clientId","task92-expiry-spa");shortLived.put("attributes",Map.of("pkce.code.challenge.method","S256","access.token.lifespan","2"));
        realm.put("clients",List.of(spa,shortLived,confidential(AUDIENCE,introspectionSecret,false),confidential("task92-directory",directorySecret,true)));
        realm.put("users",List.of(Map.of("username",username,"enabled",true,"emailVerified",true,"firstName","Synthetic","lastName","Fixture","email","synthetic@example.invalid","credentials",List.of(Map.of("type","password","value",password,"temporary",false))),Map.of("username","synthetic-disabled","enabled",false),Map.of("username","service-account-task92-directory","enabled",true,"serviceAccountClientId","task92-directory","clientRoles",Map.of("realm-management",List.of("query-users","view-users")))));
        return realm;
    }
    private static Map<String,Object> confidential(String id,String secret,boolean service) {return Map.of("clientId",id,"secret",secret,"publicClient",false,"standardFlowEnabled",false,"directAccessGrantsEnabled",false,"serviceAccountsEnabled",service);}
    public record Login(String accessToken,String refreshToken,String subject,String code,String verifier,String state,String nonce) {
        public String toString(){return "SyntheticLogin[restricted]";}
    }
    public Login login()throws Exception {return login(CLIENT);}
    public Login shortLivedLogin()throws Exception {return login("task92-expiry-spa");}
    public Login wrongPasswordLogin()throws Exception{return login(CLIENT,username,random());}
    public Login disabledAccountLogin()throws Exception{return login(CLIENT,"synthetic-disabled",password);}
    public record AuthorizationRejection(int status,boolean fixedRedirect,boolean codeAbsent,String error) {}
    public AuthorizationRejection invalidAuthorizationRequest(boolean wrongRedirect)throws Exception {
        var parameters=new HashMap<>(Map.of("client_id",CLIENT,"redirect_uri",wrongRedirect?"https://untrusted.example.invalid/callback":REDIRECT,"response_type","code","scope","openid","state",random(),"nonce",random()));
        if(wrongRedirect){parameters.put("code_challenge",random()+random());parameters.put("code_challenge_method","S256");}
        try(var http=client()){
            var response=http.send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/auth?"+form(parameters))).GET().build(),HttpResponse.BodyHandlers.discarding());
            var location=response.headers().firstValue("Location");if(location.isEmpty())return new AuthorizationRejection(response.statusCode(),false,true,null);
            var callback=URI.create(location.get());var fields=query(callback);
            return new AuthorizationRejection(response.statusCode(),location.get().startsWith(REDIRECT+"?"),!fields.containsKey("code"),fields.get("error"));
        }
    }
    private Login login(String clientId)throws Exception {return login(clientId,username,password);}
    private Login login(String clientId,String suppliedUsername,String suppliedPassword)throws Exception {
        String verifier=random()+random(),state=random(),nonce=random();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        try(var client=client()) {
            var authorize=client.send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/auth?"+form(Map.of("client_id",clientId,"redirect_uri",REDIRECT,"response_type","code","scope","openid","state",state,"nonce",nonce,"code_challenge",challenge,"code_challenge_method","S256")))).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(authorize.statusCode()!=200)throw new IllegalStateException("Synthetic authorization page unavailable: "+authorize.statusCode());
            var matcher=java.util.regex.Pattern.compile("action=\"([^\"]+)\"").matcher(authorize.body());
            if(!matcher.find())throw new IllegalStateException("Synthetic login form unavailable");
            URI action=URI.create(matcher.group(1).replace("&amp;","&"));
            if(!action.getAuthority().equals(URI.create(issuer()).getAuthority()))throw new IllegalStateException("Unexpected login origin");
            // This single same-origin hop carries the server-issued cookie values in RFC6265 form.
            // java.net.CookieManager's RFC2965 encoding was rejected as a missing cookie by Keycloak.
            String cookies=authorize.headers().allValues("Set-Cookie").stream().flatMap(value->HttpCookie.parse(value).stream())
                    .map(cookie->cookie.getName()+"="+cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
            if(cookies.isEmpty())throw new IllegalStateException("Synthetic authorization session cookie unavailable");
            var authenticated=client.send(HttpRequest.newBuilder(action).header("Cookie",cookies).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username",suppliedUsername,"password",suppliedPassword)))).build(),HttpResponse.BodyHandlers.ofString());
            if(authenticated.statusCode()!=302) {
                String code=java.util.stream.Stream.of("Cookie not found","Invalid username or password","Login timeout","Invalid code","HTTPS required","expired","Restart login")
                        .filter(value->authenticated.body().toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.joining(","));
                throw new IllegalStateException("Synthetic login failed: "+authenticated.statusCode()+"; classified="+code);
            }
            URI callback=URI.create(authenticated.headers().firstValue("Location").orElseThrow());
            if(!callback.toString().startsWith(REDIRECT+"?"))throw new IllegalStateException("Unexpected callback");
            var params=query(callback);if(!state.equals(params.get("state")))throw new IllegalStateException("State mismatch");
            var result=post(issuer()+"/protocol/openid-connect/token",Map.of("grant_type","authorization_code","client_id",clientId,"redirect_uri",REDIRECT,"code",params.get("code"),"code_verifier",verifier));
            if(result.statusCode()!=200)throw new IllegalStateException("Synthetic code exchange failed: "+result.statusCode());
            var body=JSON.readTree(result.body());String access=body.path("access_token").asString();
            var id=com.nimbusds.jwt.SignedJWT.parse(body.path("id_token").asString());
            if(!id.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(publicKey()))||!nonce.equals(id.getJWTClaimsSet().getStringClaim("nonce")))throw new IllegalStateException("Nonce/signature mismatch");
            return new Login(access,body.path("refresh_token").asString(),id.getJWTClaimsSet().getSubject(),params.get("code"),verifier,state,nonce);
        }
    }
    public RSAPublicKey publicKey()throws Exception {
        try(var client=client()) {
            var response=client.send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/certs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var keys=com.nimbusds.jose.jwk.JWKSet.parse(response.body()).getKeys();
            for(var key:keys)if(key instanceof com.nimbusds.jose.jwk.RSAKey rsa&&com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(rsa.getKeyUse()))return rsa.toRSAPublicKey();
            throw new IllegalStateException("Synthetic signing key unavailable");
        }
    }
    public HttpResponse<String> post(String url,Map<String,String> fields)throws Exception {
        try(var client=client()) {return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(),HttpResponse.BodyHandlers.ofString());}
    }
    public void logout(Login login)throws Exception {if(post(issuer()+"/protocol/openid-connect/logout",Map.of("client_id",CLIENT,"refresh_token",login.refreshToken())).statusCode()!=204)throw new IllegalStateException("Synthetic logout failed");}
    public void unavailable(Runnable check) {
        keycloak.getDockerClient().pauseContainerCmd(keycloak.getContainerId()).exec();
        try{check.run();}finally{keycloak.getDockerClient().unpauseContainerCmd(keycloak.getContainerId()).exec();}
    }
    public static String form(Map<String,String> data){return data.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private static Map<String,String> query(URI uri){var map=new HashMap<String,String>();for(var part:uri.getRawQuery().split("&")){var fields=part.split("=",2);map.put(URLDecoder.decode(fields[0],StandardCharsets.UTF_8),URLDecoder.decode(fields[1],StandardCharsets.UTF_8));}return map;}
    private static String random(){return UUID.randomUUID().toString().replace("-","");}
    public void close(){if(keycloak!=null)keycloak.close();if(postgres!=null)postgres.close();network.close();}
    public String toString(){return "KeycloakFixture[isolated]";}
}
