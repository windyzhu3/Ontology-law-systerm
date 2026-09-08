package io.github.windyzhu3.ontologylaw.worker;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Flow;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.execution.R1ProjectionOutboxPort.Claim;
public final class InternalApiClient implements AutoCloseable {
    public enum RecoveryType {CONTACT_TASK,ROUTING_REVIEW_TASK}
    public record Candidate(RecoveryType recoveryType,UUID taskId,long expectedTaskRevision,UUID waitReceiptId,String waitReceiptHash,String dueCutoff,UUID idempotencyKey) {}
    public record DuePage(int status,List<Candidate> candidates,String nextCursor) {public DuePage{candidates=List.copyOf(candidates);}}
    public DuePage due(R1WorkerTenantBindings.Binding binding,RecoveryType type,String cursor){
        if(type==null||cursor!=null&&cursor.length()>2048)return new DuePage(400,List.of(),null);
        var response=send(binding,"/internal/v1/tasks/due?recoveryType="+type+"&limit=50"+(cursor==null?"":"&cursor="+java.net.URLEncoder.encode(cursor,StandardCharsets.UTF_8)),null,null,false);
        if(response.status()!=200)return new DuePage(response.status(),List.of(),null);
        try{
            var mapper=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();var page=mapper.readValue(response.body(),Map.class);
            if(!Set.of(Set.of("candidates"),Set.of("candidates","nextCursor")).contains(page.keySet())||!(page.get("candidates") instanceof List<?> rows)||rows.size()>50)throw new IllegalArgumentException();
            var candidates=new ArrayList<Candidate>();for(var row:rows){if(!(row instanceof Map<?,?> values)||!values.keySet().equals(Set.of("recoveryType","taskId","expectedTaskRevision","waitReceiptId","waitReceiptHash","dueCutoff","idempotencyKey"))||!type.name().equals(values.get("recoveryType")))throw new IllegalArgumentException();
                var revision=values.get("expectedTaskRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<0||((Number)revision).longValue()>9007199254740991L)throw new IllegalArgumentException();
                var hash=(String)values.get("waitReceiptHash");if(!hash.matches("[A-Za-z0-9_-]{43}")||!Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(hash)).equals(hash))throw new IllegalArgumentException();
                String due=(String)values.get("dueCutoff");java.time.OffsetDateTime.parse(due);var key=uuid(values.get("idempotencyKey"));if(key.version()!=5)throw new IllegalArgumentException();
                candidates.add(new Candidate(type,uuid(values.get("taskId")),((Number)revision).longValue(),uuid(values.get("waitReceiptId")),hash,due,key));
            }
            String next=(String)page.get("nextCursor");if(page.containsKey("nextCursor")&&(next==null||next.isEmpty()||next.length()>2048))throw new IllegalArgumentException();return new DuePage(200,candidates,next);
        }catch(RuntimeException failure){return new DuePage(503,List.of(),null);}
    }
    private static UUID uuid(Object value){var id=UUID.fromString((String)value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    public Result recover(R1WorkerTenantBindings.Binding binding,Candidate candidate){
        return send(binding,"/internal/v1/tasks/commands/"+(candidate.recoveryType()==RecoveryType.CONTACT_TASK?"reopen-due-contact-tasks":"reopen-due-routing-review-tasks"),CanonicalJson.encode(Map.of("taskId",candidate.taskId().toString(),"expectedTaskRevision",candidate.expectedTaskRevision(),"waitReceiptId",candidate.waitReceiptId().toString(),"waitReceiptHash",candidate.waitReceiptHash(),"dueCutoff",candidate.dueCutoff())),candidate.idempotencyKey(),false);
    }
    public record Credentials(KeyStore keys,char[] password,KeyStore trust) {
        public Credentials{password=password.clone();}
        public char[] password(){return password.clone();}
        public String toString(){return "R1_MTLS_CREDENTIALS";}
    }
    public record Result(int status,String body) {public String toString(){return "R1_HTTP_STATUS_"+status;}}
    private final URI origin;
    private final Map<R1WorkerTenantBindings.Binding,HttpClient> clients;
    private final java.util.function.BooleanSupplier deploymentGate;
    public InternalApiClient(URI origin,R1WorkerTenantBindings registry,Map<String,Credentials> credentials) {
        this(origin,registry,credentials,()->true);
    }
    public InternalApiClient(URI origin,R1WorkerTenantBindings registry,Map<String,Credentials> credentials,java.util.function.BooleanSupplier deploymentGate) {
        this.deploymentGate=Objects.requireNonNull(deploymentGate);
        if(origin==null||!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/")))throw new IllegalArgumentException("R1_HTTPS_ORIGIN_REQUIRED");
        this.origin=origin;var clients=new HashMap<R1WorkerTenantBindings.Binding,HttpClient>();
        try{if(credentials.size()!=registry.bindings().size())throw new GeneralSecurityException();for(var binding:registry.bindings()){
            var credential=credentials.get(binding.credentialAlias());if(credential==null)throw new GeneralSecurityException();var keys=credential.keys();var cert=(X509Certificate)keys.getCertificate(binding.credentialAlias());
            if(cert==null||!keys.isKeyEntry(binding.credentialAlias())||!binding.certificateSha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))))throw new GeneralSecurityException();cert.checkValidity();
            // Snapshot one exact identity into an isolated keystore; TLS cannot select a different alias.
            var selected=KeyStore.getInstance("PKCS12");selected.load(null,null);selected.setKeyEntry(binding.credentialAlias(),keys.getKey(binding.credentialAlias(),credential.password()),credential.password(),keys.getCertificateChain(binding.credentialAlias()));
            var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(selected,credential.password());var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(credential.trust());var ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),null);
            clients.put(binding,HttpClient.newBuilder().sslContext(ssl).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build());
        }}catch(Exception invalid){clients.values().forEach(HttpClient::shutdownNow);throw new IllegalArgumentException("R1_MTLS_BINDING_INVALID");}this.clients=Map.copyOf(clients);
    }
    public Result readiness(R1WorkerTenantBindings.Binding binding){return send(binding,"/internal/v1/projections/r1/readiness",null,null,true);}
    public Result consume(R1WorkerTenantBindings.Binding binding,Claim claim){
        if(!binding.tenantId().equals(claim.tenantId()))return new Result(400,"");
        return send(binding,"/internal/v1/projections/r1/consume",CanonicalJson.encode(Map.of("domainEventOutboxId",claim.outboxId().toString(),"domainEventId",claim.eventId().toString(),"expectedOutboxRevision",claim.revision(),"leaseOwner",claim.leaseOwner(),"fencingToken",claim.fencingToken())),null,false);
    }
    private Result send(R1WorkerTenantBindings.Binding binding,String path,String body,UUID key,boolean readiness){
        var client=clients.get(binding);if(client==null)return new Result(503,"");
        try{if(!deploymentGate.getAsBoolean())return new Result(503,"");}catch(RuntimeException unavailable){return new Result(503,"");}
        var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(key!=null)request.header("Idempotency-Key",key.toString());
        long began=System.nanoTime();var future=client.sendAsync(request.build(),ignored->new BoundedBody());
        try{var response=future.get(10,TimeUnit.SECONDS);if(System.nanoTime()-began>=TimeUnit.SECONDS.toNanos(10))return new Result(readiness?503:408,"");
            if(readiness&&response.statusCode()==204){if(response.body().length!=0||response.headers().firstValue("ETag").isPresent()||!response.headers().allValues("Cache-Control").stream().flatMap(v->Arrays.stream(v.split(","))).anyMatch(v->v.strip().equalsIgnoreCase("no-store")))return new Result(503,"");}
            int status=response.statusCode();return new Result(status,status==200&&path.startsWith("/internal/v1/tasks/due?")?new String(response.body(),StandardCharsets.UTF_8):"");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();future.cancel(true);return new Result(503,"");}
        catch(TimeoutException timeout){future.cancel(true);return new Result(readiness?503:408,"");}
        catch(ExecutionException failure){future.cancel(true);return new Result(503,"");}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers){if(bytes.size()+buffer.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalStateException("R1_HTTP_BODY_LIMIT"));return;}byte[] next=new byte[buffer.remaining()];buffer.get(next);bytes.writeBytes(next);}subscription.request(1);}
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    public void close(){clients.values().forEach(HttpClient::shutdownNow);}
}
