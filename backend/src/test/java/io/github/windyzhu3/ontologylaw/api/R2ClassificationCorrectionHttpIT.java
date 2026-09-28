package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class R2ClassificationCorrectionHttpIT extends R2TransferHttpIT {
 @Override void continueContractPreparation(HttpHarness http)throws Exception{
  super.continueContractPreparation(http);var intake=new RoleHttp(http,"F11 intake");var sales=new RoleHttp(http,SUBJECT);var previous=currentTransfer();String path="/api/v1/opportunities/"+opportunity.id()+"/transfers/classification-context";
  var denied=sales.request("GET",path,null,Map.of());assertEquals(403,denied.statusCode(),denied.body());
  var response=intake.request("GET",path,null,Map.of());assertEquals(200,response.statusCode(),response.body());var context=intake.body(response);assertEquals("GENERAL",context.get("category"));assertEquals(previous.workflow().id().toString(),((Map<?,?>)context.get("expectedWorkflow")).get("id"));
  transferStep(intake,"classifications",Map.of("matterId",previous.matterId().toString(),"category","ENFORCEMENT","recipient",intakeActor.appointmentId().toString(),"explanation","Correction after checking the accepted scope"));
  var fresh=intake.request("GET",path,null,Map.of());assertEquals(200,fresh.statusCode(),fresh.body());assertEquals("ENFORCEMENT",intake.body(fresh).get("category"));assertEquals(previous.matterId(),currentTransfer().matterId());assertEquals("2",scalar("select count(*) from transfer.classification where tenant_id=?",seed.tenant()));
 }
}
