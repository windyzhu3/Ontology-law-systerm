package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.lead.R1ServiceSourceBinding;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
class R2TransferAdapterIT extends R2TransferCommandIT {
 @Test void transfer_adapter_dispatches_the_actual_command_and_receipt_projection()throws Exception{
  var workflow=beginTransfer();transferSalesGrants();var command=transferCommand(workflow);var payload=new LinkedHashMap<>((Map<String,Object>)command.payload());payload.remove("opportunityId");
  var db=new RuntimeDatabase(){public java.sql.Connection open()throws java.sql.SQLException{return database.apiConnection();}public boolean healthy(){return true;}};
  R1ServiceSourceBinding bindings;try(var c=database.apiConnection()){bindings=inTransaction(c,Capability.QUERY,x->R1ServiceSourceBinding.validate(x,List.of(),policies));}
  var services=new R1ApiServices(db,policies,protection,bindings,"F11_ADAPTER",new byte[32],List.of(),cipher,null,protectedBodies);
  var delegate=new R2TransferApiDelegate(new StaticListableBeanFactory(Map.of("services",services)).getBeanProvider(R1ApiServices.class));
  var resolver=new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver(db::open,new io.github.windyzhu3.ontologylaw.identity.ExternalSubjectProtection(t->new byte[32]),List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Trust("https://transfer.example.test","transfer",(java.security.interfaces.RSAPublicKey)R1HttpFixture.signing().getPublic())),List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration("https://transfer.example.test","transfer","FIXTURE",actor())));
  var app=new org.springframework.boot.builder.SpringApplicationBuilder(io.github.windyzhu3.ontologylaw.OntologyLawApplication.class).initializers(c->{var beans=(org.springframework.context.support.GenericApplicationContext)c;beans.registerBean(R1ApiServices.class,()->services);beans.registerBean(io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.class,()->resolver);}).properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();
  var mvc=MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext)app).build();
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor(),null,List.of()));
  try{var result=mvc.perform(post("/api/v1/opportunities/"+opportunity.id()+"/transfers/submissions").header("Idempotency-Key",command.commandId()).contentType("application/json").content(io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(payload))).andReturn();if(result.getResolvedException()!=null)throw new AssertionError("Actual MVC transfer failure",result.getResolvedException());assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());assertTrue(result.getResponse().getContentAsString().contains("TRANSFER_SUBMISSION"));var replay=services.contractCommand(command);assertEquals(200,replay.status());var recovered=services.receipt(actor(),command.commandId(),UUID.randomUUID());assertEquals(200,recovered.status(),recovered.errorCode());assertNotNull(R1WireModels.receipt(recovered.body()));}finally{SecurityContextHolder.clearContext();app.close();}
 }
}
