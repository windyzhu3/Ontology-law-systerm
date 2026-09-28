package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class R2FollowupAttemptApiDelegate {
    private final ObjectProvider<R1ApiServices> services;public R2FollowupAttemptApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var s=services.getIfAvailable();if(s==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return s;}
    private Actor actor(UUID behalf){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||behalf!=null||!(a.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    @GetMapping("/api/v1/opportunities/{id}/followup-attempts")
    public ResponseEntity<Map<String,Object>> context(@PathVariable UUID id,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false)UUID behalf){return ResponseEntity.ok().header("Cache-Control","no-store").body(service().followupAttempts(actor(behalf),id));}
    @PostMapping("/api/v1/opportunities/{id}/followup-attempts")
    public ResponseEntity<Map<String,Object>> ordinary(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false)UUID behalf,@RequestBody Map<String,Object> body){return command(id,key,actor(behalf),body,CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT);}
    @PostMapping("/api/v1/opportunities/{id}/quotes/followup-attempts")
    public ResponseEntity<Map<String,Object>> quote(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false)UUID behalf,@RequestBody Map<String,Object> body){return command(id,key,actor(behalf),body,CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT);}
    private ResponseEntity<Map<String,Object>> command(UUID id,UUID key,Actor actor,Map<String,Object> body,CommandEnvelope.Type type){
        if(body.containsKey("opportunityId"))throw new R1HttpFailure("VALIDATION_FAILED");var payload=new TreeMap<String,Object>(body);payload.put("opportunityId",id.toString());
        var e=new CommandEnvelope(type,key,UUID.randomUUID(),actor,payload);try{R2FollowupAttemptInput.parse(e);}catch(CommandHandler.Rejected invalid){throw new R1HttpFailure(invalid.code());}
        var response=service().opportunityCommand(e);if(response.errorCode()!=null)throw new R1HttpFailure(response.errorCode(),response.receiptRef(),null,null);
        return ResponseEntity.status(response.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+key+"/receipt").body(response.body());
    }
}
