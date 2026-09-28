package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController public final class R25AiApiDelegate {
    private final ObjectProvider<R1ApiServices> services;
    public R25AiApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
    private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
    private Actor actor(UUID behalf){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||behalf!=null||!(auth.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    private R25AiCandidateContract.Task task(String value){try{return R25AiCandidateContract.Task.valueOf(value);}catch(RuntimeException invalid){throw R1HttpFailure.validation("/task","INVALID_FORMAT");}}
    @PostMapping("/api/v1/opportunities/{opportunityId}/ai-candidates/{task}")
    public ResponseEntity<R25AiCandidateService.Candidate> generate(@PathVariable UUID opportunityId,@PathVariable String task,@RequestBody Map<String,Object> body,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){
        if(body==null||!body.isEmpty())throw R1HttpFailure.validation("/body","NOT_ALLOWED");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service().aiCandidates(actor(behalf),opportunityId,task(task)));
    }
    @PostMapping("/api/v1/opportunities/{opportunityId}/ai-candidates/{task}/recheck")
    public ResponseEntity<Void> recheck(@PathVariable UUID opportunityId,@PathVariable String task,@RequestBody Map<String,Object> body,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){
        if(body==null||!body.keySet().equals(Set.of("sourceToken"))||!(body.get("sourceToken") instanceof String token)||token.isBlank()||token.length()>512)throw R1HttpFailure.validation("/body","INVALID_FORMAT");
        service().recheckAiSources(actor(behalf),opportunityId,task(task),token);
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
