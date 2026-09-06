package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.util.*;

public final class R1ResourceTags {
    private R1ResourceTags() {}
    public static String task(Actor actor,Subject task,String state) {
        if(!task.type().equals("responsibility.task_occurrence") || task.revision()==null
                || !Set.of("OPEN","WAITING","DONE","CANCELLED").contains(state))throw new IllegalArgumentException("Exact Task required");
        var values=new TreeMap<String,Object>();
        values.put("profile","R1_RESOURCE_TAG_V1");values.put("projectionVersion",1);values.put("kind","task");
        values.put("tenantId",actor.tenantId().toString());values.put("principalId",actor.principalId().toString());
        values.put("appointmentId",actor.appointmentId().toString());values.put("principalKind",actor.principalKind().name());
        values.put("onBehalfPrincipalId",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());
        values.put("onBehalfAppointmentId",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());
        values.put("resourceId",task.id().toString());values.put("resourceType",task.type());values.put("revision",task.revision());values.put("state",state);
        return "\"task."+Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(values)))+"\"";
    }
}
