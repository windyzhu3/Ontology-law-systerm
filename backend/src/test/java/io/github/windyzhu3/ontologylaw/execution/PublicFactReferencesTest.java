package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicFactReferencesTest {
    private static UUID id(long n) { return new UUID(0,n); }
    @Test void receipt_reference_survives_revision_changes_but_not_actor_or_fact_changes() {
        var actor=new Actor(id(1),id(2),id(3),null,null);
        String reference=PublicFactReferences.reference(actor,"lead.lead",id(4));
        for(long revision:List.of(0L,7L)) {
            var fact=new Subject("lead.lead",id(4),revision,null);
            var receipt=new CommandReceiptReader.Receipt(id(5),"CAPTURE_LEAD","{}",new byte[32],
                new CommandOutcome(id(6),CommandOutcome.Status.SUCCEEDED,fact,null),Instant.EPOCH,fact);
            assertEquals(reference,((Map<?,?>)receipt.projection(actor).get("resultFact")).get("factRef"));
        }
        assertNotEquals(reference,PublicFactReferences.reference(new Actor(id(7),id(2),id(3),null,null),"lead.lead",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(new Actor(id(1),id(7),id(3),null,null),"lead.lead",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(new Actor(id(1),id(2),id(7),null,null),"lead.lead",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(new Actor(id(1),id(2),id(3),id(7),id(8)),"lead.lead",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(new Actor(id(1),id(2),id(3),null,null,PrincipalKind.SERVICE),"lead.lead",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(actor,"responsibility.task_occurrence",id(4)));
        assertNotEquals(reference,PublicFactReferences.reference(actor,"lead.lead",id(7)));
    }
}
