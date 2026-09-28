package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
class R2OpportunityOwnerExceptionWireTest {
    private final JsonMapper json=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private Map<String,Object> transfer(){var id=UUID.randomUUID().toString();var value=new LinkedHashMap<String,Object>();value.put("opportunityId",id);value.put("expectedOpportunityRevision",0L);value.put("exceptionId",UUID.randomUUID().toString());value.put("expectedExceptionRevision",0L);value.put("expectedBasis",Map.of("type","opportunity.opportunity","id",id,"revision",0L));value.put("expectedTask",null);value.put("expectedWait",null);value.put("reason","Arrange a valid receiver");value.put("receiverAppointmentId",UUID.randomUUID().toString());return value;}
    private byte[] validate(Map<String,Object> body)throws Exception {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.HUMAN);SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));
        var method=R2OpportunityOwnerExceptionApiDelegate.class.getMethod("transferOpportunityResponsibility",UUID.class,TransferOpportunityResponsibilityV1.class,UUID.class,UUID.class);
        byte[] bytes=json.writeValueAsBytes(body);var input=new HttpInputMessage(){public HttpHeaders getHeaders(){return new HttpHeaders();}public InputStream getBody(){return new ByteArrayInputStream(bytes);}};
        return new R1RequestBodyValidation().beforeBodyRead(input,new MethodParameter(method,1),TransferOpportunityResponsibilityV1.class,null).getBody().readAllBytes();
    }
    @Test void exact_null_task_preconditions_survive_raw_validation_and_generated_binding()throws Exception {
        byte[] normalized=validate(transfer());var body=json.readValue(normalized,TransferOpportunityResponsibilityV1.class);assertNull(body.getExpectedTask());assertNull(body.getExpectedWait());assertInstanceOf(OwnerExceptionRevisionSelectorV1.class,body.getExpectedBasis());assertTrue(new String(normalized,StandardCharsets.UTF_8).contains("\"expectedTask\":null"));
    }
    @Test void missing_null_precondition_extra_field_and_unsafe_revision_are_rejected_before_dto_conversion()throws Exception {
        var missing=transfer();missing.remove("expectedTask");assertThrows(R1HttpFailure.class,()->validate(missing));var extra=transfer();extra.put("tenantId",UUID.randomUUID().toString());assertThrows(R1HttpFailure.class,()->validate(extra));var bad=transfer();bad.put("expectedExceptionRevision",9007199254740992L);assertThrows(R1HttpFailure.class,()->validate(bad));
    }
    @Test void management_result_fact_is_a_closed_generated_union_and_recoverable_public_fact(){
        var fact=Map.of("factType","OPPORTUNITY_OWNER_EXCEPTION","factRef","opaque-test-reference-123456","revision",0L);
        var receipt=R1WireModels.model(Map.of("commandId",UUID.randomUUID().toString(),"receiptId",UUID.randomUUID().toString(),"outcome","SUCCEEDED","completedAt","2026-09-15T00:00:00Z","resultFact",fact),OwnerExceptionCommandReceiptV1.class);
        assertInstanceOf(OwnerExceptionFactRefV1.class,receipt.getResultFact());assertInstanceOf(OwnerExceptionFactRefV1.class,R1WireModels.fact(fact));
    }
}
