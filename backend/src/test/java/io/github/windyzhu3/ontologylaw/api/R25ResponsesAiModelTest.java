package io.github.windyzhu3.ontologylaw.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class R25ResponsesAiModelTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final List<R25AiCandidateContract.Source> SOURCES=List.of(new R25AiCandidateContract.Source("p:1","已确认跟进","PROGRESS","客户已提交资料。"));
    private static final String ANSWER="{\"items\":[{\"field\":\"progressSummary\",\"status\":\"CANDIDATE\",\"value\":\"已提交资料。\",\"citations\":[{\"sourceId\":\"p:1\",\"quote\":\"客户已提交资料\"}]}]}";
    private static String response(String answer) { return JSON.writeValueAsString(Map.of("id","resp-local","status","completed","output",List.of(Map.of("type","message","role","assistant","status","completed","content",List.of(Map.of("type","output_text","text",answer)))))); }
    @Test void sendsOnlyBoundedStatelessStructuredInputAndNeverBusinessCredentials() throws Exception {
        var captured=new AtomicReference<String>();var authorization=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{captured.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(e.getRequestHeaders().getFirst("Authorization"));byte[] bytes=response(ANSWER).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});server.start();
        try {
            var model=R25ResponsesAiModel.forTesting(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),"configured-test-model","test-secret",Duration.ofSeconds(3));
            var result=model.generate(R25AiCandidateContract.Task.SUMMARY,SOURCES);
            assertEquals("已提交资料。",result.items().getFirst().value());
            var body=JSON.readTree(captured.get());assertEquals(false,body.path("store").asBoolean());assertEquals("json_schema",body.at("/text/format/type").asText());assertTrue(body.at("/text/format/strict").asBoolean());
            assertEquals("configured-test-model",body.path("model").asText());assertEquals("Bearer test-secret",authorization.get());
            assertFalse(body.has("tools"));assertFalse(body.has("previous_response_id"));assertFalse(body.has("conversation"));assertFalse(captured.get().contains("test-secret"));assertFalse(model.toString().contains("test-secret"));
        } finally {server.stop(0);}
    }
    @Test void unavailableRefusalIncompleteToolAndOversizeResponsesFailClosedWithoutEchoOrRetry() throws Exception {
        var body=new AtomicReference<>("private provider error test-secret");var status=new AtomicInteger(503);var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status.get(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}catch(java.io.IOException ignored){}});server.start();
        try {
            var model=R25ResponsesAiModel.forTesting(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),"configured-test-model","test-secret",Duration.ofSeconds(3));
            var unavailable=assertThrows(R25AiCandidateContract.Failure.class,()->model.generate(R25AiCandidateContract.Task.SUMMARY,SOURCES));assertEquals("AI_UNAVAILABLE",unavailable.code());assertFalse(unavailable.toString().contains("test-secret"));assertEquals(1,calls.get());
            status.set(200);
            for(var invalid:List.of(response(ANSWER).replace("completed","incomplete"),"{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}]}","{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"private\"}]}]}","x".repeat(65537))) {
                body.set(invalid);assertThrows(R25AiCandidateContract.Failure.class,()->model.generate(R25AiCandidateContract.Task.SUMMARY,SOURCES));
            }
            assertEquals(5,calls.get());
        } finally {server.stop(0);}
    }
    @Test void disabledConfigurationDoesNotCallNetworkOrSelectModel() {
        assertEquals("AI_UNAVAILABLE",assertThrows(R25AiCandidateContract.Failure.class,()->R25ResponsesAiModel.disabled().generate(R25AiCandidateContract.Task.SUMMARY,SOURCES)).code());
        assertThrows(IllegalArgumentException.class,()->R25ResponsesAiModel.configured("", "secret"));
        assertThrows(IllegalArgumentException.class,()->R25ResponsesAiModel.configured("model", ""));
    }
    @Test void timeoutHasFixedErrorAndRedirectCannotForwardSecret() throws Exception {
        var requests=new AtomicInteger();var delay=new java.util.concurrent.atomic.AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/responses",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();if(delay.get()){try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}else e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/other");try{e.sendResponseHeaders(302,-1);}catch(java.io.IOException ignored){}finally{e.close();}});
        server.createContext("/other",e->{requests.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});server.start();
        try {
            var model=R25ResponsesAiModel.forTesting(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),"configured-test-model","test-secret",Duration.ofMillis(200));
            assertEquals("AI_UNAVAILABLE",assertThrows(R25AiCandidateContract.Failure.class,()->model.generate(R25AiCandidateContract.Task.SUMMARY,SOURCES)).code());assertEquals(1,requests.get());
            delay.set(true);assertEquals("AI_TIMEOUT",assertThrows(R25AiCandidateContract.Failure.class,()->model.generate(R25AiCandidateContract.Task.SUMMARY,SOURCES)).code());assertEquals(2,requests.get());
        }finally{server.stop(0);((java.util.concurrent.ExecutorService)server.getExecutor()).close();}
    }
}
