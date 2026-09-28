package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
/** Read-only management projection; business commands remain on their original endpoints. */
@RestController public class R2ManagementApiDelegate {
 private final ObjectProvider<R1ApiServices> services;
 public R2ManagementApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
 private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
 private Actor actor(UUID behalf){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||behalf!=null||!(auth.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
 @GetMapping("/api/v1/business-management/{view:payments|transfer}")
 public ResponseEntity<Map<String,Object>> list(@PathVariable String view,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,@RequestParam(required=false) String search,@RequestParam(required=false) String state,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){return ResponseEntity.ok().header("Cache-Control","no-store").body(service().managementList(actor(behalf),view,limit,cursor,search,state));}
 @GetMapping("/api/v1/business-management/{view:payments|transfer}/{requestId}")
 public ResponseEntity<Map<String,Object>> detail(@PathVariable String view,@PathVariable UUID requestId,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){return ResponseEntity.ok().header("Cache-Control","no-store").body(service().managementDetail(actor(behalf),view,requestId));}
}
