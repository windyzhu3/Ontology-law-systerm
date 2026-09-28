package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.OpportunityMaterialsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.http.*;
import org.springframework.core.io.*;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.web.bind.annotation.RestController;
@RestController
public class R2MaterialsApiDelegate implements OpportunityMaterialsApi {
 private final ObjectProvider<R1ApiServices> services;
 public R2MaterialsApiDelegate(ObjectProvider<R1ApiServices> services){this.services=services;}
 private R1ApiServices service(){var s=services.getIfAvailable();if(s==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return s;}
 private Actor actor(UUID behalf){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||behalf!=null||!(a.getPrincipal() instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
 public ResponseEntity<OpportunityMaterialsContextV1> getOpportunityMaterials(UUID id,UUID own,UUID behalf){return json(service().materials(actor(behalf),id),OpportunityMaterialsContextV1.class);}
 public ResponseEntity<OpportunityMaterialUploadV1> getOpportunityMaterialUpload(UUID id,UUID upload,UUID own,UUID behalf){return json(service().materialUpload(actor(behalf),id,upload),OpportunityMaterialUploadV1.class);}
 public ResponseEntity<OpportunityMaterialUploadV1> uploadOpportunityMaterialContent(UUID id,UUID upload,Resource body,UUID own,UUID behalf){var actor=actor(behalf);try(var bytes=body.getInputStream()){return json(service().uploadMaterial(actor,id,upload,bytes),OpportunityMaterialUploadV1.class);}catch(IOException failure){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
 public ResponseEntity<Resource> downloadOpportunityMaterialContent(UUID id,UUID version,UUID own,UUID behalf,String disposition){var content=service().materialContent(actor(behalf),id,version,disposition);String mode="inline".equals(disposition)&&content.previewAllowed()?"inline":"attachment";return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").header("Content-Disposition",ContentDisposition.builder(mode).filename(content.fileName(),StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(content.mediaType())).contentLength(content.bytes().length).body(new ByteArrayResource(content.bytes()));}
 public ResponseEntity<OpportunityMaterialCommandReceiptV1> openOpportunityMaterialUpload(UUID id,UUID key,OpenOpportunityMaterialUploadV1 body,UUID own,UUID behalf){return command(id,key,body,actor(behalf),CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD);}
 public ResponseEntity<OpportunityMaterialCommandReceiptV1> acceptOpportunityMaterial(UUID id,UUID key,AcceptOpportunityMaterialV1 body,UUID own,UUID behalf){return command(id,key,body,actor(behalf),CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL);}
 @SuppressWarnings("unchecked") private ResponseEntity<OpportunityMaterialCommandReceiptV1> command(UUID id,UUID key,Object body,Actor actor,CommandEnvelope.Type type){var p=new TreeMap<String,Object>((Map<String,Object>)R1WireModels.payload(body));p.put("opportunityId",id.toString());p.putIfAbsent("expectedConfirmation",null);p.putIfAbsent("expectedPreviousVersion",null);if(type==CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD)p.putIfAbsent("note",null);var r=service().opportunityCommand(new CommandEnvelope(type,key,UUID.randomUUID(),actor,p));if(r.errorCode()!=null)throw new R1HttpFailure(r.errorCode(),r.receiptRef(),null,null);return ResponseEntity.status(r.status()).header("Cache-Control","no-store").header("Location","/api/v1/commands/"+key+"/receipt").body(R1WireModels.model(r.body(),OpportunityMaterialCommandReceiptV1.class));}
 private static <T> ResponseEntity<T> json(Map<String,Object> data,Class<T> type){return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(data,type));}
}
