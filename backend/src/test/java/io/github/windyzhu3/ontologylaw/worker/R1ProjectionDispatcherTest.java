package io.github.windyzhu3.ontologylaw.worker;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
/** Deterministic fail-closed configuration tests; real dispatcher/PG/TLS loops are in R1WorkerTransportIT. */
class R1ProjectionDispatcherTest {
    @Test void only_approved_current_release_is_accepted() {
        var binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CLIENT","a".repeat(64));
        assertDoesNotThrow(()->new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding)));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-08.1",List.of(binding)));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(binding)));
    }
    @Test void unsafe_origin_and_unregistered_credentials_never_construct_a_transport(){
        var binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CLIENT","a".repeat(64));var registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding));
        for(String origin:List.of("http://localhost","https://user:password@localhost","https://localhost?tenant=x","https://localhost/prefix","https://localhost/#fragment"))assertThrows(IllegalArgumentException.class,()->new InternalApiClient(URI.create(origin),registry,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient(URI.create("https://localhost"),registry,Map.of()));
    }
    @Test void registry_snapshot_is_immutable_and_duplicate_aliases_cannot_select_a_different_key(){
        var binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CLIENT","a".repeat(64));var mutable=new ArrayList<>(List.of(binding));var registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",mutable);mutable.clear();assertEquals(List.of(binding),registry.bindings());assertThrows(UnsupportedOperationException.class,()->registry.bindings().clear());
        var second=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"client","b".repeat(64));assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding,second)));
    }
}
