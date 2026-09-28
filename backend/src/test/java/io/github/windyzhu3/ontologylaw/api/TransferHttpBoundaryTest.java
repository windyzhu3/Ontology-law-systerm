package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TransferHttpBoundaryTest {
 @Test void correction_context_is_a_named_read_operation(){var op=R1HttpOperations.find("GET","/api/v1/opportunities/"+UUID.randomUUID()+"/transfers/classification-context");assertNotNull(op);assertNull(op.command());assertTrue(op.errors().contains("NOT_AUTHORIZED"));}
 @Test void exact_transfer_paths_register_only_named_human_commands(){for(var pair:Map.of("submissions","SUBMIT_TRANSFER","supplements","RESUBMIT_TRANSFER","conflict-reviews","RECORD_TRANSFER_CONFLICT_REVIEW","intake-decisions","RECORD_TRANSFER_INTAKE","classifications","CLASSIFY_MATTER").entrySet()){var op=R1HttpOperations.find("POST","/api/v1/opportunities/"+UUID.randomUUID()+"/transfers/"+pair.getKey());assertNotNull(op);assertEquals(pair.getValue(),op.command().name());assertTrue(op.errors().contains("STALE_REVIEW"));}assertNull(R1HttpOperations.find("POST","/api/v1/opportunities/"+UUID.randomUUID()+"/transfers/auto-accept"));}
 @Test void original_receipt_endpoint_knows_named_transfer_facts(){for(String type:List.of("TRANSFER_SUBMISSION","TRANSFER_CONFLICT_REVIEW","TRANSFER_INTAKE","MATTER_CLASSIFICATION"))assertDoesNotThrow(()->R1WireModels.fact(Map.of("factType",type,"factRef","opaque-proof","revision",0L)));}
 @Test void transfer_commands_use_their_exact_body_versions_not_legacy_task_headers()throws Exception{
  var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();var boundary=new R1RequestBoundary(factory.getBeanProvider(R1ApiServices.class),factory.getBeanProvider(IdentityAdminController.Services.class));
  var method=R1RequestBoundary.class.getDeclaredMethod("check",jakarta.servlet.http.HttpServletRequest.class);method.setAccessible(true);
  for(String action:List.of("submissions","supplements","conflict-reviews","intake-decisions","classifications")){var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/opportunities/"+UUID.randomUUID()+"/transfers/"+action);request.addHeader("Idempotency-Key",UUID.randomUUID().toString());assertDoesNotThrow(()->method.invoke(boundary,request),action);}
 }
 @Test void transfer_read_paths_reject_unreviewed_queries_and_caller_tenant_headers()throws Exception{
  var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();var boundary=new R1RequestBoundary(factory.getBeanProvider(R1ApiServices.class),factory.getBeanProvider(IdentityAdminController.Services.class));var method=R1RequestBoundary.class.getDeclaredMethod("check",jakarta.servlet.http.HttpServletRequest.class);method.setAccessible(true);
  for(String suffix:List.of("context","materials/"+UUID.randomUUID()+"/content")){
   var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/transfer-tasks/"+UUID.randomUUID()+"/"+suffix);request.addHeader("X-Tenant-Id",UUID.randomUUID().toString());var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(boundary,request));assertInstanceOf(R1HttpFailure.class,failure.getCause());
  }
 }
}
