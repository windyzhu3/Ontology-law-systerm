package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
/** Own/delegated waiting reads share workcard authorization; never grant team access. */
@RestController public final class PersonalWaitingApiDelegate {
 private final ObjectProvider<R1ApiServices> services;
 public PersonalWaitingApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
 @GetMapping("/api/v1/workbench/waiting") public ResponseEntity<Map<String,Object>> list(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){return read(null,limit,cursor);}
 @GetMapping("/api/v1/workbench/waiting/{taskId}") public ResponseEntity<Map<String,Object>> detail(@PathVariable UUID taskId){return read(taskId,20,null);}
 private ResponseEntity<Map<String,Object>> read(UUID id,int limit,String cursor){var authentication=SecurityContextHolder.getContext().getAuthentication();if(authentication==null||!(authentication.getPrincipal() instanceof Actor actor))throw new R1HttpFailure("UNAUTHENTICATED");var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");var result=service.waiting(actor,id,limit,cursor);if(result.errorCode()!=null)throw new R1HttpFailure(result.errorCode());return ResponseEntity.ok().header("Cache-Control","no-store").header("Vary","Authorization").body(result.body());}
}
