package io.github.windyzhu3.ontologylaw.api;

import java.sql.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC fault boundaries; no security, authorization, transaction or audit substitute. */
class R1DisclosureCommitHttpIT extends R1HttpFixture {
    @ParameterizedTest @CsvSource({"RECEIPT,APPEND,0","RECEIPT,COMMIT,0","RECEIPT,ACK_LOSS,1","CARD,APPEND,0","CARD,COMMIT,0","CARD,ACK_LOSS,7","CACHED,APPEND,0","CACHED,COMMIT,0","CACHED,ACK_LOSS,7"})
    void failed_disclosure_never_exposes_success_or_reference_before_ack(String target,String fault,long audits)throws Exception {
        setupContact();var command=prepare(contact("NOT_CONNECTED"));
        if(target.equals("RECEIPT"))execute(command);
        try(var http=new HttpHarness()) {
            Map<String,String> headers=Map.of();
            if(target.equals("CACHED")){var first=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,first.statusCode());headers=Map.of("If-None-Match",first.headers().firstValue("ETag").orElseThrow());}
            disclosureConnection=c->fault(c,fault,null,null);
            var before=counts();var response=http.request("GET",target.equals("RECEIPT")?"/api/v1/commands/"+command.commandId()+"/receipt":"/api/v1/workcards/current",null,headers);
            assertEquals(503,response.statusCode(),response.body());assertEquals("SERVICE_UNAVAILABLE",http.body(response).get("code"));
            assertTrue(response.headers().firstValue("ETag").isEmpty());assertTrue(response.headers().firstValue("Location").isEmpty());assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
            assertFalse(response.body().contains("receiptRef"));assertFalse(response.body().contains("receiptId"));assertFalse(response.body().contains("resultFact"));assertFalse(response.body().contains(seed.tenant().toString()));
            delta(before,0,0,0,0,0,0,0,0,audits,0,0);
        }
    }
    @ParameterizedTest @CsvSource({"RECEIPT,200,1","CARD,200,7","CACHED,304,7"})
    void real_http_response_is_not_published_while_commit_ack_is_blocked(String target,int status,long audits)throws Exception {
        setupContact();var command=prepare(contact("NOT_CONNECTED"));if(target.equals("RECEIPT"))execute(command);
        try(var http=new HttpHarness();var pool=Executors.newSingleThreadExecutor()) {
            Map<String,String> headers=Map.of();if(target.equals("CACHED")){var first=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,first.statusCode());headers=Map.of("If-None-Match",first.headers().firstValue("ETag").orElseThrow());}
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);disclosureConnection=c->fault(c,"PAUSE_ACK",entered,release);var before=counts();var requestHeaders=headers;
            var request=java.net.http.HttpRequest.newBuilder(http.origin.resolve(target.equals("RECEIPT")?"/api/v1/commands/"+command.commandId()+"/receipt":"/api/v1/workcards/current")).header("Authorization","Bearer "+http.bearer).header("Accept","application/json");requestHeaders.forEach(request::header);
            // ofInputStream completes on response headers; it does not wait for the response body.
            var future=pool.submit(()->http.client.send(request.GET().build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream()));
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(250,TimeUnit.MILLISECONDS));delta(before,0,0,0,0,0,0,0,0,audits,0,0);}finally{release.countDown();}
            var response=future.get(10,TimeUnit.SECONDS);try(var body=response.body()){String text=new String(body.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(status,response.statusCode(),text);if(status==304)assertEquals("",text);else assertFalse(text.isBlank());}
        }
    }
    private static Connection fault(Connection real,String fault,CountDownLatch entered,CountDownLatch release) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(fault.equals("APPEND")&&m.getName().equals("prepareStatement")&&a[0] instanceof String sql&&sql.startsWith("insert into \"audit\"."))throw new SQLException("Synthetic append failure","08006");
            if(fault.equals("COMMIT")&&m.getName().equals("commit"))throw new SQLException("Synthetic pre-commit failure","08006");
            try{var result=m.invoke(real,a);if(m.getName().equals("commit")){if(fault.equals("ACK_LOSS"))throw new SQLException("Synthetic acknowledgement loss","08006");if(fault.equals("PAUSE_ACK")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("Synthetic acknowledgement deadline","08006");}}return result;}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
}
