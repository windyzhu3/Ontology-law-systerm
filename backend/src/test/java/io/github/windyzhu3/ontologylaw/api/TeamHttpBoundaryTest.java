package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TeamHttpBoundaryTest {
 @Test void four_named_views_are_read_only_and_reject_unregistered_query_parameters()throws Exception {
  var beans=new DefaultListableBeanFactory();var boundary=new R1RequestBoundary(beans.getBeanProvider(R1ApiServices.class),beans.getBeanProvider(IdentityAdminController.Services.class));
  var check=R1RequestBoundary.class.getDeclaredMethod("check",jakarta.servlet.http.HttpServletRequest.class);check.setAccessible(true);
  for(String view:List.of("tasks","waiting","exceptions","history")){
   String path="/api/v1/team-management/"+view;
   var operation=R1HttpOperations.find("GET",path);assertNotNull(operation);assertNull(operation.command());
   var list=new MockHttpServletRequest("GET",path);list.addParameter("limit","20");list.addParameter("search","合成客户");assertDoesNotThrow(()->check.invoke(boundary,list));
   for(String key:List.of("tenantId","appointmentId","rawSql")){var bad=new MockHttpServletRequest("GET",path);bad.addParameter(key,"x");assertInstanceOf(R1HttpFailure.class,assertThrows(InvocationTargetException.class,()->check.invoke(boundary,bad)).getCause());}
   var duplicate=new MockHttpServletRequest("GET",path);duplicate.addParameter("limit","20","30");assertThrows(InvocationTargetException.class,()->check.invoke(boundary,duplicate));
   var detail=new MockHttpServletRequest("GET",path+"/"+UUID.randomUUID());detail.addParameter("cursor","invalid");assertThrows(InvocationTargetException.class,()->check.invoke(boundary,detail));
   assertNull(R1HttpOperations.find("POST",path));
  }
 }
}
