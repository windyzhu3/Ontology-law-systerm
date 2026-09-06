package io.github.windyzhu3.ontologylaw.responsibility;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.UUID;
import java.util.List;
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
    @Test void draft_and_subject_tags_bind_actor_represented_identity_resource_and_exact_revision() {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        for(String kind:List.of("draft","subject")) {
            var source=new Subject(kind.equals("draft")?"responsibility.action_draft":"lead.lead",UUID.randomUUID(),0L,null);
            String token=token(kind,actor,source);
            assertTrue(token.matches("\""+kind+"\\.[A-Za-z0-9_-]{43}\""));assertFalse(token.contains(source.id().toString()));
            assertNotEquals(token,token(kind,actor,new Subject(source.type(),source.id(),1L,null)));
            assertNotEquals(token,token(kind,actor,new Subject(source.type(),UUID.randomUUID(),0L,null)));
            for(Actor different:List.of(new Actor(UUID.randomUUID(),actor.principalId(),actor.appointmentId(),null,null),
                    new Actor(actor.tenantId(),UUID.randomUUID(),actor.appointmentId(),null,null),
                    new Actor(actor.tenantId(),actor.principalId(),UUID.randomUUID(),null,null),
                    new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),UUID.randomUUID(),UUID.randomUUID())))
                assertNotEquals(token,token(kind,different,source));
            if(kind.equals("draft"))assertNotEquals(token,R1ResourceTags.draft(actor,source,"CONFIRMED"));
        }
    }
    private String token(String kind,Actor actor,Subject source) {
        return kind.equals("draft")?R1ResourceTags.draft(actor,source,"DRAFT"):R1ResourceTags.subject(actor,source);
    }
}
