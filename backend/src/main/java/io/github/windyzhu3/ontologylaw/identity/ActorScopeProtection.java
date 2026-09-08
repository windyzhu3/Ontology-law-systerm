package io.github.windyzhu3.ontologylaw.identity;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;

/** Persisted deployment purpose key; neither token nor authorization evidence participates in identity. */
public final class ActorScopeProtection {
    private final ExternalSubjectProtection.Keys keys;
    public ActorScopeProtection(ExternalSubjectProtection.Keys keys){this.keys=Objects.requireNonNull(keys);}
    public String key(Actor actor) {
        Objects.requireNonNull(actor);byte[] supplied=keys.forTenant(actor.tenantId());
        if(supplied==null||supplied.length!=32)throw new IllegalStateException("Actor scope protection unavailable");
        byte[] secret=supplied.clone();
        String tuple="{\"appointmentId\":"+value(actor.appointmentId())+",\"onBehalfAppointmentId\":"+value(actor.onBehalfAppointmentId())+",\"onBehalfPrincipalId\":"+value(actor.onBehalfPrincipalId())+",\"principalId\":"+value(actor.principalId())+",\"tenantId\":"+value(actor.tenantId())+"}";
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return "ask1."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("R1_ACTOR_SCOPE_V1\n"+tuple).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Actor scope protection unavailable");}
        finally{Arrays.fill(secret,(byte)0);}
    }
    private static String value(UUID id){return id==null?"null":"\""+id+"\"";}
}
