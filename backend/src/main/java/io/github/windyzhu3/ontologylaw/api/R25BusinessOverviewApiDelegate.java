package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController public final class R25BusinessOverviewApiDelegate {
 private final ObjectProvider<R1ApiServices> services;
 public R25BusinessOverviewApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
 private R1ApiServices service(){var value=services.getIfAvailable();if(value==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return value;}
 private Actor actor(UUID behalf){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||behalf!=null||!(auth.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
 @GetMapping("/api/v1/business-overview")
 public ResponseEntity<Map<String,Object>> summary(@RequestParam(required=false) String month,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){return ResponseEntity.ok().header("Cache-Control","no-store").body(service().businessOverview(actor(behalf),month));}
 @GetMapping("/api/v1/business-overview/{metric}")
 public ResponseEntity<Map<String,Object>> details(@PathVariable String metric,@RequestParam(required=false) String month,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,@RequestHeader(value="X-On-Behalf-Appointment-Id",required=false) UUID behalf){return ResponseEntity.ok().header("Cache-Control","no-store").body(service().businessOverviewDetails(actor(behalf),metric,month,limit,cursor));}
}
