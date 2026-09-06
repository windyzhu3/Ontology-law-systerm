package io.github.windyzhu3.ontologylaw.responsibility;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class R1ResourceTagsTest {
    @Test void task_tags_bind_actor_resource_state_and_revision_without_exposing_identifiers() {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        var task=new Subject("responsibility.task_occurrence",UUID.randomUUID(),0L,null);
        String tag=R1ResourceTags.task(actor,task,"OPEN");
        assertTrue(tag.matches("\"task\\.[A-Za-z0-9_-]{43}\""));
        assertEquals(tag,R1ResourceTags.task(actor,task,"OPEN"));
        assertNotEquals(tag,R1ResourceTags.task(actor,task,"WAITING"));
        assertNotEquals(tag,R1ResourceTags.task(actor,new Subject(task.type(),task.id(),1L,null),"OPEN"));
        assertNotEquals(tag,R1ResourceTags.task(new Actor(actor.tenantId(),UUID.randomUUID(),actor.appointmentId(),null,null),task,"OPEN"));
        assertNotEquals(tag,R1ResourceTags.task(new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),UUID.randomUUID(),UUID.randomUUID()),task,"OPEN"));
        assertFalse(tag.contains(task.id().toString()));
    }
}
