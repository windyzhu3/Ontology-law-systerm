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
    record Settings(String semanticBaseline,Database database,String node,String apiOrigin,List<Binding> bindings) {
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
    private R1WorkerDeployment(RuntimeDatabase database,R1WorkerTenantBindings registry,InternalApiClient client,String node) {
        this.database=database;this.registry=registry;this.client=client;outbox=R1ProjectionOutboxPort.databaseBacked(database::open);
        projection=new R1ProjectionDispatcher(registry,client,outbox,node,Clock.systemUTC());due=new DueTaskScheduler(registry,client,Clock.systemUTC());
    }
    static R1WorkerDeployment from(Environment environment) {
        InternalApiClient client=null;
        try {
            var settings=Binder.get(environment).bind("ols.worker",Settings.class).orElseThrow(()->new IllegalArgumentException());
            var db=Objects.requireNonNull(settings.database());
            var database=RuntimeDatabase.databaseBacked(RuntimeDatabase.jdbc(new RuntimeDatabase.JdbcLogin(db.url(),db.username(),db.password().toCharArray())),RuntimeDatabase.Role.WORKER,
                    new RuntimeDatabase.Expected(db.schemaVersion(),digest(db.releaseDigest()),digest(db.manifestHash())));
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
            return new R1WorkerDeployment(database,registry,client,settings.node());
        }catch(Exception invalid){if(client!=null)client.close();throw new IllegalStateException("R1_WORKER_CONFIGURATION_UNAVAILABLE");}
    }
    private static KeyStore store(String file,String password)throws Exception {
        var path=Path.of(file);if(!path.isAbsolute()||!Files.isRegularFile(path)||Files.size(path)>1048576||password==null||password.isEmpty())throw new IllegalArgumentException();
        char[] secret=password.toCharArray();try(var input=Files.newInputStream(path)){var store=KeyStore.getInstance("PKCS12");store.load(input,secret);if(store.size()==0)throw new IllegalArgumentException();return store;}finally{Arrays.fill(secret,'\0');}
    }
    private static byte[] digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();return HexFormat.of().parseHex(value);}
    public void close(){projection.close();due.close();client.close();}
    public String toString(){return "R1WorkerDeployment[restricted]";}
}
