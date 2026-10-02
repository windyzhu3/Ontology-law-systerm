package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.api.security.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuditRecordsHttpIT extends PostgresIntegrationTest {
 @Test void own_audit_only_session_reads_without_identity_admin_and_closed_http_rejects_invalid_filters()throws Exception{
  byte[] key=new byte[32];Arrays.fill(key,(byte)17);var protection=new ExternalSubjectProtection(t->key);
  try(var idp=new KeycloakFixture().start()){
   var login=idp.login();var seed=AuthorizationServiceIT.seed(database,"HUMAN","AUDIT_READ",t->protection.digest(t,login.subject()));
   var human=new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"FIXTURE",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret);
   var resolver=new ActorContextResolver(database::apiConnection,protection,HumanCredentialVerifier.isolatedLoopback(List.of(human)));
   var audit=AuditAppender.databaseBacked("AUDIT_HTTP_IT");UUID id=AuditRecordReaderIT.append(database,seed,UUID.randomUUID());
   try(var app=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(ActorContextResolver.class,()->resolver);registry.registerBean(SessionContextController.Services.class,()->new SessionContextController.Services(database::apiConnection,audit,new ActorScopeProtection(t->key)));registry.registerBean(AuditRecordsController.Services.class,()->new AuditRecordsController.Services(database::apiConnection,audit,new AuditRecordProtection(key)));}).properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();var client=HttpClient.newHttpClient()){
    String origin="http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port");
    var session=get(client,origin,"/api/v1/session/context",login.accessToken());assertEquals(200,session.statusCode());var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(session.body());assertTrue(body.path("canReadAuditRecords").asBoolean());assertFalse(body.path("canEnterIdentityAdmin").asBoolean());
    var page=get(client,origin,"/api/v1/admin/audit-records",login.accessToken());assertEquals(200,page.statusCode());assertEquals("no-store",page.headers().firstValue("Cache-Control").orElseThrow());assertFalse(page.body().contains("HMAC_TOKEN"));
    assertEquals(200,get(client,origin,"/api/v1/admin/audit-records/"+id,login.accessToken()).statusCode());
    assertEquals(400,get(client,origin,"/api/v1/admin/audit-records?scope=INVALID",login.accessToken()).statusCode());
    assertEquals(400,get(client,origin,"/api/v1/admin/audit-records/"+id+"/related?relation=INVALID",login.accessToken()).statusCode());
    assertEquals(403,get(client,origin,"/api/v1/admin/audit-records/"+UUID.randomUUID(),login.accessToken()).statusCode());
   }
  }
 }
 private HttpResponse<String> get(HttpClient client,String origin,String path,String token)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(origin+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());}
}
