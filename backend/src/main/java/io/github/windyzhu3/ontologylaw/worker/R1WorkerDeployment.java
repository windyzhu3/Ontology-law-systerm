package io.github.windyzhu3.ontologylaw.worker;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.net.URI;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Worker-only deployment composition: technical WORKER capability and certificate-bound HTTP. */
final class R1WorkerDeployment implements AutoCloseable {
    static int databasePoolSize(int bindings,boolean opportunities,boolean exceptions){
        // Each active scan keeps its checkpoint connection while HTTP performs a
        // second deployment check. Reserve capacity for both and the R1 loops.
        int scans=(opportunities?Math.min(8,bindings*3):0)+(exceptions?Math.min(8,bindings):0);
        return Math.min(32,2+2*scans);
    }
    record Settings(String semanticBaseline,Database database,String node,String apiOrigin,List<Binding> bindings,boolean ownerExceptionObservationEnabled,boolean opportunityTaskSchedulingEnabled) {
        Settings {
            if(opportunityTaskSchedulingEnabled&&(database==null||!("52-plus-2-r2-v3".equals(database.schemaVersion())||"52-plus-2-r2-v4".equals(database.schemaVersion())||"52-plus-2-r2-v5".equals(database.schemaVersion())||"52-plus-2-r2-v6".equals(database.schemaVersion())||"52-plus-2-r2-v7".equals(database.schemaVersion())||"52-plus-2-r2-v8".equals(database.schemaVersion())||"52-plus-2-r2-v9".equals(database.schemaVersion())||"52-plus-2-r2-v10".equals(database.schemaVersion())||"52-plus-2-r2-v11".equals(database.schemaVersion())||"52-plus-2-r2-v12".equals(database.schemaVersion())||"52-plus-2-r2-v13".equals(database.schemaVersion())||"52-plus-2-r2-v14".equals(database.schemaVersion())||"52-plus-2-r2-v15".equals(database.schemaVersion())||"52-plus-2-r2-v16".equals(database.schemaVersion())||"52-plus-2-r2-v17".equals(database.schemaVersion())||"52-plus-2-r2-v18".equals(database.schemaVersion())||"52-plus-2-r2-v19".equals(database.schemaVersion())||"52-plus-2-r2-v20".equals(database.schemaVersion()))))throw new IllegalArgumentException("Opportunity task scheduling requires a registered checkpoint schema");
            if(ownerExceptionObservationEnabled&&(database==null||!("52-plus-2-r2-v4".equals(database.schemaVersion())||"52-plus-2-r2-v5".equals(database.schemaVersion())||"52-plus-2-r2-v6".equals(database.schemaVersion())||"52-plus-2-r2-v7".equals(database.schemaVersion())||"52-plus-2-r2-v8".equals(database.schemaVersion())||"52-plus-2-r2-v9".equals(database.schemaVersion())||"52-plus-2-r2-v10".equals(database.schemaVersion())||"52-plus-2-r2-v11".equals(database.schemaVersion())||"52-plus-2-r2-v12".equals(database.schemaVersion())||"52-plus-2-r2-v13".equals(database.schemaVersion())||"52-plus-2-r2-v14".equals(database.schemaVersion())||"52-plus-2-r2-v15".equals(database.schemaVersion())||"52-plus-2-r2-v16".equals(database.schemaVersion())||"52-plus-2-r2-v17".equals(database.schemaVersion())||"52-plus-2-r2-v18".equals(database.schemaVersion())||"52-plus-2-r2-v19".equals(database.schemaVersion())||"52-plus-2-r2-v20".equals(database.schemaVersion()))))throw new IllegalArgumentException("Owner exception observation requires its registered schema");}
        public String toString(){return "R1WorkerSettings[restricted]";}
    }
    record Database(String url,String username,String password,String schemaVersion,String releaseDigest,String manifestHash) {
        public String toString(){return "R1WorkerDatabase[restricted]";}
    }
    record Binding(UUID tenantId,UUID principalId,UUID appointmentId,String credentialAlias,String certificateSha256,
                   String keyStorePath,String keyStorePassword,String trustStorePath,String trustStorePassword) {
        public String toString(){return "R1WorkerBinding[restricted]";}
    }
    final RuntimeDatabase database;
    final R1WorkerTenantBindings registry;
    final InternalApiClient client;
    final R1ProjectionOutboxPort outbox;
    final R1ProjectionDispatcher projection;
    final DueTaskScheduler due;
    final R2OpportunityTaskScheduler ownerExceptionObservation;
    final R2OpportunityTaskScheduler opportunityTaskScheduling;
    private R1WorkerDeployment(RuntimeDatabase database,R1WorkerTenantBindings registry,InternalApiClient client,String node,boolean ownerExceptionObservationEnabled,boolean opportunityTaskSchedulingEnabled) {
        this.database=database;this.registry=registry;this.client=client;outbox=R1ProjectionOutboxPort.databaseBacked(database::open);
        projection=new R1ProjectionDispatcher(registry,client,outbox,node,Clock.systemUTC());due=new DueTaskScheduler(registry,client,Clock.systemUTC());
        opportunityTaskScheduling=opportunityTaskSchedulingEnabled?new R2OpportunityTaskScheduler(registry,client,Clock.systemUTC(),R2OpportunityCheckpointPort.databaseBacked(database::open),Set.of(InternalApiClient.OpportunityKind.INITIAL,InternalApiClient.OpportunityKind.DUE,InternalApiClient.OpportunityKind.CONTRACT_PREPARATION)):null;
        ownerExceptionObservation=ownerExceptionObservationEnabled?new R2OpportunityTaskScheduler(registry,client,Clock.systemUTC(),R2OpportunityCheckpointPort.databaseBacked(database::open),Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION)):null;
    }
    static R1WorkerDeployment from(Environment environment) {
        InternalApiClient client=null;RuntimeDatabase opened=null;
        try {
            var settings=Binder.get(environment).bind("ols.worker",Settings.class).orElseThrow(()->new IllegalArgumentException());
            var db=Objects.requireNonNull(settings.database());
            var expected=new RuntimeDatabase.Expected(db.schemaVersion(),digest(db.releaseDigest()),digest(db.manifestHash()));
            var database=RuntimeDatabase.databaseBacked(RuntimeDatabase.jdbc(new RuntimeDatabase.JdbcLogin(db.url(),db.username(),db.password().toCharArray()),databasePoolSize(settings.bindings()==null?0:settings.bindings().size(),settings.opportunityTaskSchedulingEnabled(),settings.ownerExceptionObservationEnabled())),RuntimeDatabase.Role.WORKER,
                    expected);
            opened=database;
            if(!database.healthy()||settings.bindings()==null)throw new IllegalArgumentException();
            var bindings=new ArrayList<R1WorkerTenantBindings.Binding>();var credentials=new HashMap<String,InternalApiClient.Credentials>();
            for(var binding:settings.bindings()) {
                bindings.add(new R1WorkerTenantBindings.Binding(binding.tenantId(),binding.principalId(),binding.appointmentId(),binding.credentialAlias(),binding.certificateSha256()));
                char[] password=Objects.requireNonNull(binding.keyStorePassword()).toCharArray();
                try{credentials.put(binding.credentialAlias(),new InternalApiClient.Credentials(store(binding.keyStorePath(),binding.keyStorePassword()),password,store(binding.trustStorePath(),binding.trustStorePassword())));}
                finally{Arrays.fill(password,'\0');}
            }
            var registry=new R1WorkerTenantBindings(settings.semanticBaseline(),bindings);
            client=new InternalApiClient(URI.create(settings.apiOrigin()),registry,credentials,database::healthy);
            return new R1WorkerDeployment(database,registry,client,settings.node(),settings.ownerExceptionObservationEnabled(),settings.opportunityTaskSchedulingEnabled());
        }catch(Exception invalid){if(client!=null)client.close();if(opened!=null)opened.close();throw new IllegalStateException("R1_WORKER_CONFIGURATION_UNAVAILABLE");}
    }
    private static KeyStore store(String file,String password)throws Exception {
        var path=Path.of(file);if(!path.isAbsolute()||!Files.isRegularFile(path)||Files.size(path)>1048576||password==null||password.isEmpty())throw new IllegalArgumentException();
        char[] secret=password.toCharArray();try(var input=Files.newInputStream(path)){var store=KeyStore.getInstance("PKCS12");store.load(input,secret);if(store.size()==0)throw new IllegalArgumentException();return store;}finally{Arrays.fill(secret,'\0');}
    }
    private static byte[] digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();return HexFormat.of().parseHex(value);}
    public void close(){if(opportunityTaskScheduling!=null)opportunityTaskScheduling.close();if(ownerExceptionObservation!=null)ownerExceptionObservation.close();projection.close();due.close();client.close();database.close();}
    public String toString(){return "R1WorkerDeployment[restricted]";}
}
