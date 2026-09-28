package io.github.windyzhu3.ontologylaw.execution;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata;
import java.util.*;
import org.junit.jupiter.api.Test;
class MaterialExecutionContractTest {
 @Test void uploads_use_immutable_basis_and_direct_human_scope(){
  var a=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
  var o=new Subject("opportunity.opportunity",UUID.randomUUID(),2L,null);
  var u=new Subject("evidence.material_upload_basis",UUID.randomUUID(),0L,null);
  var e=new CommandEnvelope(CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL,UUID.randomUUID(),UUID.randomUUID(),a,Map.of());
  assertEquals(CommandEnvelope.Envelope.INTERNAL_ADMIN,e.envelope());
  var scope=CommandScope.materials(e,o,o,null,null,u);
  assertNull(scope.taskId());
  var metadata=new TreeMap<String,Object>();metadata.put("profile","R1_COMMAND_RECEIPT_RECOVERY_V1");metadata.put("scope",scope.fields());metadata.put("binding",Map.of("kind","MATERIALS"));
  var recovery=new ReceiptRecoveryMetadata(e.type().name(),metadata);
  assertNotNull(recovery.materialsScope());
  assertThrows(IllegalArgumentException.class,()->CommandScope.materials(e,o,o,null,null,new Subject("evidence.upload_session",u.id(),0L,null)));
  assertThrows(IllegalArgumentException.class,()->CommandScope.materials(e,o,o,null,null,new Subject("evidence.material_upload_basis",u.id(),1L,null)));
  assertThrows(IllegalArgumentException.class,()->CommandScope.materials(e,o,o,null,null,null));
 }
 @Test void material_authorities_are_explicitly_grantable(){assertTrue(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.GRANTABLE.containsAll(Set.of("MATERIALS_MANAGE","MATERIALS_READ")));}
 @Test void material_events_stay_on_r2_projection(){
  for(var e:List.of(CommandHandler.Event.OpportunityMaterialUploadOpenedV1,CommandHandler.Event.OpportunityMaterialAcceptedV1))assertEquals(Set.of(CommandHandler.QueueOwner.R2_PROJECTION),e.queueOwners());
 }
}
