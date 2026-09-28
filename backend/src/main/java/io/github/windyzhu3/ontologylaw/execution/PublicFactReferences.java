package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;

/** Stable Actor-scoped correlation only; possession confers no read authority. */
public final class PublicFactReferences {
    private PublicFactReferences() {}
    public static String reference(Actor actor,String type,UUID id) {
        var scope=new TreeMap<String,Object>();
        scope.put("profile","R1_PUBLIC_FACT_REF_V1");scope.put("tenant",actor.tenantId().toString());
        scope.put("principal",actor.principalId().toString());scope.put("appointment",actor.appointmentId().toString());
        scope.put("onBehalfPrincipal",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());
        scope.put("onBehalfAppointment",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());
        scope.put("kind",actor.principalKind().name());scope.put("type",type);scope.put("id",id.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(scope)));
    }
}
