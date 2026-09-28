package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

class CustomerRequirementsBoundaryTest {
    private final String base="/api/v1/opportunities/"+UUID.randomUUID()+"/customer-requirements";
    private void check(MockHttpServletRequest request)throws Throwable {
        var beans=new DefaultListableBeanFactory();
        var boundary=new R1RequestBoundary(beans.getBeanProvider(R1ApiServices.class),beans.getBeanProvider(IdentityAdminController.Services.class));
        var method=R1RequestBoundary.class.getDeclaredMethod("check",jakarta.servlet.http.HttpServletRequest.class);method.setAccessible(true);
        try {method.invoke(boundary,request);}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void independentDraftUsesBodySelectorsAndNeverDemandsTaskETag()throws Throwable {
        var request=new MockHttpServletRequest("POST",base+"/draft");request.addHeader("Idempotency-Key",UUID.randomUUID().toString());
        assertEquals(Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,R1HttpOperations.find("POST",request.getRequestURI()).command());
        check(request);
        request.addHeader("If-Match","\"task.not-applicable\"");assertThrows(R1HttpFailure.class,()->check(request));
    }
    @Test void partySearchAllowsOnlyOneQueryAndNoGetBody()throws Throwable {
        var request=new MockHttpServletRequest("GET",base+"/parties");request.addParameter("q","客户");check(request);
        request.addParameter("tenantId",UUID.randomUUID().toString());assertThrows(R1HttpFailure.class,()->check(request));
        var duplicate=new MockHttpServletRequest("GET",base+"/parties");duplicate.addParameter("q","甲","乙");assertThrows(R1HttpFailure.class,()->check(duplicate));
        var body=new MockHttpServletRequest("GET",base);body.setContent("{}".getBytes());assertThrows(R1HttpFailure.class,()->check(body));
    }
    @Test void confirmationRequiresIdempotencyButHasNoOrdinaryTaskFailureVocabulary() {
        var request=new MockHttpServletRequest("POST",base+"/confirm");assertThrows(R1HttpFailure.class,()->check(request));
        var op=R1HttpOperations.find("POST",request.getRequestURI());assertEquals(Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,op.command());assertTrue(op.errors().contains("STALE_DRAFT"));assertFalse(op.errors().contains("STALE_TASK"));assertFalse(op.errors().contains("TASK_PRECONDITION_REQUIRED"));
    }
}
