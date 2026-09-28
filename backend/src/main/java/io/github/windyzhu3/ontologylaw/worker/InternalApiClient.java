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
    public enum OpportunityKind {INITIAL,DUE,OWNER_EXCEPTION,CONTRACT_PREPARATION}
    public sealed interface OpportunityCandidate permits InitialOpportunityCandidate,DueOpportunityCandidate,OwnerExceptionCandidate,ContractPreparationCandidate {
        OpportunityKind kind();UUID idempotencyKey();UUID opportunityId();long expectedOpportunityRevision();
    }
    public record InitialOpportunityCandidate(UUID idempotencyKey,UUID opportunityId,long expectedOpportunityRevision) implements OpportunityCandidate {
        public InitialOpportunityCandidate {opportunityIdentity(idempotencyKey,opportunityId,expectedOpportunityRevision);}
        public OpportunityKind kind(){return OpportunityKind.INITIAL;}
    }
    public record OwnerExceptionCandidate(UUID idempotencyKey,UUID opportunityId,long expectedOpportunityRevision) implements OpportunityCandidate {
        public OwnerExceptionCandidate {opportunityIdentity(idempotencyKey,opportunityId,expectedOpportunityRevision);}
        public OpportunityKind kind(){return OpportunityKind.OWNER_EXCEPTION;}
    }
    public record ContractPreparationCandidate(UUID idempotencyKey,UUID opportunityId,long expectedOpportunityRevision,UUID basisId,long basisRevision,UUID sourceId,UUID workflowId,String sourceKind) implements OpportunityCandidate {
        public ContractPreparationCandidate(UUID key,UUID opportunity,long revision,UUID basis,long basisRevision,UUID source,UUID workflow){this(key,opportunity,revision,basis,basisRevision,source,workflow,"ACCEPTED_QUOTE");}
        public ContractPreparationCandidate{if(!Set.of("ACCEPTED_QUOTE","AUTHORITY_RETURN","SIGNATURE_READINESS","SIGNATURE_AUTHORITY_RETURN","TERMINATION_REVIEW","EXECUTION_HANDOFF","PAYMENT_HANDOFF","PAYMENT_RECOVERY","TRANSFER_HANDOFF","TRANSFER_RECOVERY").contains(sourceKind)||Set.of("AUTHORITY_RETURN","SIGNATURE_AUTHORITY_RETURN","TRANSFER_RECOVERY").contains(sourceKind)&&!Objects.equals(sourceId,workflowId)||sourceKind.equals("TERMINATION_REVIEW")&&workflowId==null||sourceKind.equals("TRANSFER_HANDOFF")&&workflowId!=null)throw new IllegalArgumentException("Exact contract recovery mode required");opportunityIdentity(idempotencyKey,opportunityId,expectedOpportunityRevision);Objects.requireNonNull(basisId);Objects.requireNonNull(sourceId);safeRevision(basisRevision);}
        public OpportunityKind kind(){return OpportunityKind.CONTRACT_PREPARATION;}
    }
    public record DueOpportunityCandidate(UUID idempotencyKey,UUID opportunityId,long expectedOpportunityRevision,UUID taskId,long expectedTaskRevision,UUID waitReceiptId,String waitReceiptHash,UUID progressId,String progressHash,String dueCutoff) implements OpportunityCandidate {
        public DueOpportunityCandidate {
            opportunityIdentity(idempotencyKey,opportunityId,expectedOpportunityRevision);Objects.requireNonNull(taskId);Objects.requireNonNull(waitReceiptId);Objects.requireNonNull(progressId);
            safeRevision(expectedTaskRevision);digest(waitReceiptHash);digest(progressHash);microsecondTime(dueCutoff);
        }
        public OpportunityKind kind(){return OpportunityKind.DUE;}
    }
    public record OpportunityPage(int status,List<OpportunityCandidate> candidates,String nextCursor,int diagnostics) {
        public OpportunityPage(int status,List<OpportunityCandidate> candidates,String nextCursor){this(status,candidates,nextCursor,0);}
        public OpportunityPage{candidates=List.copyOf(candidates);if(diagnostics<0||diagnostics>100)throw new IllegalArgumentException("Invalid diagnostics count");}
    }
    private static final Set<String> CONTRACT_FIELDS=Set.of("kind","idempotencyKey","opportunityId","expectedOpportunityRevision","responsibilityBasis","sourceKind","source","expectedWorkflow");
    private static final Set<String> INITIAL_FIELDS=Set.of("kind","idempotencyKey","opportunityId","expectedOpportunityRevision");
    private static final Set<String> DUE_FIELDS=Set.of("kind","idempotencyKey","opportunityId","expectedOpportunityRevision","taskId","expectedTaskRevision","waitReceiptId","waitReceiptHash","progressId","progressHash","dueCutoff");
    private static final tools.jackson.databind.json.JsonMapper OPPORTUNITY_JSON=tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public OpportunityPage opportunityCandidates(R1WorkerTenantBindings.Binding binding,OpportunityKind kind,String cursor){
        if(kind==null||cursor!=null&&(cursor.isEmpty()||cursor.length()>2048))return new OpportunityPage(400,List.of(),null);
        // Contract recovery checks signing, payment and transfer authority per source.
        // Keep each page below the shared 10-second request budget; retain its cursor.
        String route=kind==OpportunityKind.CONTRACT_PREPARATION?"/internal/v1/contract-preparation/candidates?limit=5":kind==OpportunityKind.OWNER_EXCEPTION?"/internal/v1/opportunity-owner-exceptions/candidates?limit=50":"/internal/v1/opportunity-tasks/candidates?kind="+kind+"&limit=50";
        var response=send(binding,route+(cursor==null?"":"&cursor="+java.net.URLEncoder.encode(cursor,StandardCharsets.UTF_8)),null,null,false);
        return parseOpportunityCandidates(kind,response);
    }
    static OpportunityPage parseOpportunityCandidates(OpportunityKind kind,Result response) {
        if(response.status()!=200)return new OpportunityPage(response.status(),List.of(),null);
        try{
            var page=OPPORTUNITY_JSON.readValue(response.body(),Map.class);
            if(page==null||!page.containsKey("candidates")||!(kind==OpportunityKind.OWNER_EXCEPTION?Set.of("candidates","nextCursor","diagnostics"):Set.of("candidates","nextCursor")).containsAll(page.keySet())||!(page.get("candidates") instanceof List<?> rows)||rows.size()>50)throw new IllegalArgumentException();
            long diagnostics=page.containsKey("diagnostics")?safeRevision(page.get("diagnostics")):0;if(diagnostics>100)throw new IllegalArgumentException();
            var candidates=new ArrayList<OpportunityCandidate>();var keys=new HashSet<UUID>();
            for(var row:rows){
                if(!(row instanceof Map<?,?> values)||!(kind==OpportunityKind.CONTRACT_PREPARATION?(values.keySet().equals(CONTRACT_FIELDS)||values.keySet().equals(Set.of("kind","idempotencyKey","opportunityId","expectedOpportunityRevision","responsibilityBasis","sourceKind","source"))):values.keySet().equals(kind==OpportunityKind.DUE?DUE_FIELDS:INITIAL_FIELDS))||!kind.name().equals(values.get("kind")))throw new IllegalArgumentException();
                var key=uuid(values.get("idempotencyKey"));var opportunity=uuid(values.get("opportunityId"));long revision=safeRevision(values.get("expectedOpportunityRevision"));
                if(!keys.add(key))throw new IllegalArgumentException();
                if(kind==OpportunityKind.CONTRACT_PREPARATION){var basis=exactRevision(values.get("responsibilityBasis"),false);var source=exactRevision(values.get("source"),true);var workflow=values.get("expectedWorkflow")==null?null:exactRevision(values.get("expectedWorkflow"),true);candidates.add(new ContractPreparationCandidate(key,opportunity,revision,uuid(basis.get("id")),safeRevision(basis.get("revision")),uuid(source.get("id")),workflow==null?null:uuid(workflow.get("id")),(String)values.get("sourceKind")));continue;}
                candidates.add(kind==OpportunityKind.OWNER_EXCEPTION?new OwnerExceptionCandidate(key,opportunity,revision):kind==OpportunityKind.INITIAL?new InitialOpportunityCandidate(key,opportunity,revision):new DueOpportunityCandidate(key,opportunity,revision,
                    uuid(values.get("taskId")),safeRevision(values.get("expectedTaskRevision")),uuid(values.get("waitReceiptId")),(String)values.get("waitReceiptHash"),uuid(values.get("progressId")),(String)values.get("progressHash"),(String)values.get("dueCutoff")));
            }
            String next=(String)page.get("nextCursor");if(page.containsKey("nextCursor")&&((next==null&&kind!=OpportunityKind.CONTRACT_PREPARATION)||next!=null&&(next.isEmpty()||next.length()>2048)))throw new IllegalArgumentException();
            return new OpportunityPage(200,candidates,next,(int)diagnostics);
        }catch(RuntimeException invalid){return new OpportunityPage(503,List.of(),null);}
    }
    public Result maintainOpportunity(R1WorkerTenantBindings.Binding binding,OpportunityCandidate candidate){
        if(candidate==null)return new Result(400,"");
        var body=new TreeMap<String,Object>();body.put("opportunityId",candidate.opportunityId().toString());body.put("expectedOpportunityRevision",candidate.expectedOpportunityRevision());
        if(candidate instanceof DueOpportunityCandidate due){body.put("taskId",due.taskId().toString());body.put("expectedTaskRevision",due.expectedTaskRevision());body.put("waitReceiptId",due.waitReceiptId().toString());body.put("waitReceiptHash",due.waitReceiptHash());body.put("progressId",due.progressId().toString());body.put("progressHash",due.progressHash());body.put("dueCutoff",due.dueCutoff());}
        if(candidate instanceof ContractPreparationCandidate contract){body.put("sourceKind",contract.sourceKind());body.put("responsibilityBasis",Map.of("id",contract.basisId().toString(),"revision",contract.basisRevision()));body.put("source",Map.of("id",contract.sourceId().toString(),"revision",0L));body.put("expectedWorkflow",contract.workflowId()==null?null:Map.of("id",contract.workflowId().toString(),"revision",0L));}
        String route=candidate.kind()==OpportunityKind.CONTRACT_PREPARATION?"/internal/v1/opportunity-tasks/commands/reconcile-contract-preparation":candidate.kind()==OpportunityKind.OWNER_EXCEPTION?"/internal/v1/opportunity-owner-exceptions/commands/observe":"/internal/v1/opportunity-tasks/commands/"+(candidate.kind()==OpportunityKind.INITIAL?"activate-initial":"reopen-due");
        var response=send(binding,route,CanonicalJson.encode(body),candidate.idempotencyKey(),false);
        return validateOpportunityReceipt(candidate,response);
    }
    static Result validateOpportunityReceipt(OpportunityCandidate candidate,Result response) {
        if(response.status()!=200)return response;
        try{
            var receipt=OPPORTUNITY_JSON.readValue(response.body(),Map.class);
            if(receipt==null||!receipt.keySet().equals(Set.of("commandId","receiptId","outcome","completedAt","resultFact"))||!candidate.idempotencyKey().equals(uuid(receipt.get("commandId")))||!Set.of("SUCCEEDED","NO_CHANGE").contains(receipt.get("outcome")))throw new IllegalArgumentException();
            uuid(receipt.get("receiptId"));microsecondTime((String)receipt.get("completedAt"));
            if(!(receipt.get("resultFact") instanceof Map<?,?> fact)||!(fact.get("factRef") instanceof String ref)||ref.codePointCount(0,ref.length())<16||ref.codePointCount(0,ref.length())>512)throw new IllegalArgumentException();
            if(candidate.kind()==OpportunityKind.OWNER_EXCEPTION&&"OPPORTUNITY_OWNER_VALIDATION".equals(fact.get("factType"))) {
                if(!fact.keySet().equals(Set.of("factType","factRef","digest"))||!"NO_CHANGE".equals(receipt.get("outcome")))throw new IllegalArgumentException();
                digest((String)fact.get("digest"));
            } else {
                String expected=candidate instanceof ContractPreparationCandidate contract
                    ?(contract.sourceKind().startsWith("TRANSFER_")?"TRANSFER_WORKFLOW":contract.sourceKind().startsWith("PAYMENT_")?"CONTRACT_PAYMENT_WORKFLOW":contract.sourceKind().equals("EXECUTION_HANDOFF")?"CONTRACT_EXECUTION_WORKFLOW":contract.sourceKind().equals("TERMINATION_REVIEW")?"CONTRACT_TERMINATION_REVIEW_ASSIGNMENT":contract.sourceKind().startsWith("SIGNATURE_")?"CONTRACT_SIGNATURE_WORKFLOW":"CONTRACT_PREPARATION_WORKFLOW")
                    :candidate.kind()==OpportunityKind.OWNER_EXCEPTION?"OPPORTUNITY_OWNER_EXCEPTION":"TASK_OCCURRENCE";
                if(!fact.keySet().equals(Set.of("factType","factRef","revision"))||!expected.equals(fact.get("factType")))throw new IllegalArgumentException();
                safeRevision(fact.get("revision"));
            }
            return response;
        }catch(RuntimeException invalid){return new Result(503,"");}
    }
    private static Map<?,?> exactRevision(Object raw,boolean zero){if(!(raw instanceof Map<?,?> value)||!value.keySet().equals(Set.of("id","revision")))throw new IllegalArgumentException();uuid(value.get("id"));long revision=safeRevision(value.get("revision"));if(zero&&revision!=0)throw new IllegalArgumentException();return value;}
    private static void opportunityIdentity(UUID key,UUID opportunity,long revision){
        if(key==null||key.version()!=5||key.variant()!=2||opportunity==null)throw new IllegalArgumentException();safeRevision(revision);
    }
    private static long safeRevision(Object value){
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>9007199254740991L)throw new IllegalArgumentException();return ((Number)value).longValue();
    }
    private static void digest(String value){
        if(value==null||!value.matches("[A-Za-z0-9_-]{43}")||!Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)).equals(value))throw new IllegalArgumentException();
    }
    private static void microsecondTime(String value){
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw new IllegalArgumentException();java.time.OffsetDateTime.parse(value);
    }
    public enum RecoveryType {CONTACT_TASK,ROUTING_REVIEW_TASK,SOURCE_REQUEST_REVIEW_TASK}
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
        return send(binding,"/internal/v1/tasks/commands/"+(candidate.recoveryType()==RecoveryType.SOURCE_REQUEST_REVIEW_TASK?"reopen-due-source-request-tasks":candidate.recoveryType()==RecoveryType.CONTACT_TASK?"reopen-due-contact-tasks":"reopen-due-routing-review-tasks"),CanonicalJson.encode(Map.of("taskId",candidate.taskId().toString(),"expectedTaskRevision",candidate.expectedTaskRevision(),"waitReceiptId",candidate.waitReceiptId().toString(),"waitReceiptHash",candidate.waitReceiptHash(),"dueCutoff",candidate.dueCutoff())),candidate.idempotencyKey(),false);
    }
    public record Credentials(KeyStore keys,char[] password,KeyStore trust) {
        public Credentials{password=password.clone();}
        public char[] password(){return password.clone();}
        public String toString(){return "R1_MTLS_CREDENTIALS";}
    }
    public record Result(int status,String body) {public String toString(){return "R1_HTTP_STATUS_"+status;}}
    // All schedulers in this worker share the same client. Leave half of the
    // API's default eight connections available for interactive reads.
    private final Semaphore requestSlots=new Semaphore(4,true);
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
        boolean acquired=false;
        try{
            acquired=requestSlots.tryAcquire(10,TimeUnit.SECONDS);
            if(!acquired)return new Result(readiness?503:408,"");
            // Recheck the deployment gate after waiting, immediately before dispatch.
            return sendWithinBudget(binding,path,body,key,readiness);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Result(503,"");}
        finally{if(acquired)requestSlots.release();}
    }
    private Result sendWithinBudget(R1WorkerTenantBindings.Binding binding,String path,String body,UUID key,boolean readiness){
        var client=clients.get(binding);if(client==null)return new Result(503,"");
        try{if(!deploymentGate.getAsBoolean())return new Result(503,"");}catch(RuntimeException unavailable){return new Result(503,"");}
        var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(key!=null)request.header("Idempotency-Key",key.toString());
        long began=System.nanoTime();var future=client.sendAsync(request.build(),ignored->new BoundedBody());
        try{var response=future.get(10,TimeUnit.SECONDS);if(System.nanoTime()-began>=TimeUnit.SECONDS.toNanos(10))return new Result(readiness?503:408,"");
            if(readiness&&response.statusCode()==204){if(response.body().length!=0||response.headers().firstValue("ETag").isPresent()||!response.headers().allValues("Cache-Control").stream().flatMap(v->Arrays.stream(v.split(","))).anyMatch(v->v.strip().equalsIgnoreCase("no-store")))return new Result(503,"");}
            int status=response.statusCode();
            if(status==200&&(path.startsWith("/internal/v1/opportunity-tasks/")||path.startsWith("/internal/v1/opportunity-owner-exceptions/")||path.startsWith("/internal/v1/contract-preparation/"))){
                if(body!=null&&(response.headers().firstValue("ETag").isPresent()||response.headers().firstValue("Location").isPresent()))return new Result(503,"");
                try{return new Result(status,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString());}
                catch(java.nio.charset.CharacterCodingException invalid){return new Result(503,"");}
            }
            return new Result(status,status==200&&path.startsWith("/internal/v1/tasks/due?")?new String(response.body(),StandardCharsets.UTF_8):"");
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
