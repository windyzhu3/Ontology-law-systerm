package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Header/query syntax before DTO conversion, authorization/locks only for the named error ETag boundary. */
@Configuration(proxyBeanMethods=false)
public class R1RequestBoundary implements WebMvcConfigurer {
    private static final String UUID_TEXT="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final ObjectProvider<R1ApiServices> services;
    private final ObjectProvider<IdentityAdminController.Services> identities;
    public R1RequestBoundary(ObjectProvider<R1ApiServices> services,ObjectProvider<IdentityAdminController.Services> identities){this.services=services;this.identities=identities;}
    public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception {check(request);return true;}
    });}
    private void check(HttpServletRequest request)throws Exception {
        String path=request.getRequestURI();var op=R1HttpOperations.find(request.getMethod(),path);if(op==null)return;
        if(path.startsWith("/api/v1/workbench/waiting")||path.startsWith("/api/v1/business-overview")||path.startsWith("/api/v1/lead-management/")||path.startsWith("/api/v1/team-management/")||path.startsWith("/api/v1/business-management/")||path.equals("/api/v1/opportunities")||path.startsWith("/api/v1/opportunities/")||path.startsWith("/api/v1/quote-tasks/")||path.startsWith("/api/v1/contract-tasks/")||path.startsWith("/api/v1/transfer-tasks/")||path.equals("/api/v1/contracts")){
            Set<String> allowed=path.equals("/api/v1/workbench/waiting")?Set.of("limit","cursor"):path.startsWith("/api/v1/workbench/waiting/")?Set.of():path.equals("/api/v1/business-overview")?Set.of("month"):path.startsWith("/api/v1/business-overview/")?Set.of("month","limit","cursor"):path.equals("/api/v1/lead-management/leads")?Set.of("limit","cursor","search","source","owner","state"):(path.matches("/api/v1/team-management/(?:tasks|waiting|exceptions|history)")||path.matches("/api/v1/business-management/(?:payments|transfer)")||path.equals("/api/v1/opportunities")||path.equals("/api/v1/contracts"))?Set.of("limit","cursor","search","state"):path.endsWith("/customer-requirements/parties")?Set.of("q"):path.matches("/api/v1/opportunities/[^/]+/materials/versions/[^/]+/content")?Set.of("disposition"):Set.of();
            for(var parameter:request.getParameterMap().entrySet())if(!allowed.contains(parameter.getKey())||parameter.getValue().length!=1)throw R1HttpFailure.validation("/query","NOT_ALLOWED");
            if(request.getMethod().equals("GET")&&(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null))throw R1HttpFailure.validation("/body","NOT_ALLOWED");
            for(String header:Collections.list(request.getHeaderNames())){String name=header.toLowerCase(Locale.ROOT);if(name.contains("tenant")||Set.of("x-principal-id","if-match","if-none-match").contains(name))throw R1HttpFailure.validation("/headers","NOT_ALLOWED");}
        }
        if(path.startsWith("/api/v1/opportunity-owner-exceptions")||path.startsWith("/internal/v1/opportunity-owner-exceptions/")){
            boolean internal=path.startsWith("/internal/");boolean get=request.getMethod().equals("GET");
            Set<String> allowed=!get?Set.of():path.endsWith("/candidates")&&!internal?Set.of("expectedRevision","limit","cursor"):path.matches("/api/v1/opportunity-owner-exceptions/[0-9a-fA-F-]{36}")?Set.of():Set.of("limit","cursor");
            for(var parameter:request.getParameterMap().entrySet())if(!allowed.contains(parameter.getKey())||parameter.getValue().length!=1)throw R1HttpFailure.validation("/query","NOT_ALLOWED");
            if(get&&(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null))throw R1HttpFailure.validation("/body","NOT_ALLOWED");
            for(String header:Collections.list(request.getHeaderNames())){String name=header.toLowerCase(Locale.ROOT);if(name.contains("tenant")||Set.of("x-principal-id","if-match","if-none-match").contains(name)||internal&&Set.of("x-appointment-id","x-on-behalf-appointment-id").contains(name))throw R1HttpFailure.validation("/headers","NOT_ALLOWED");}
        }
        if(path.startsWith("/internal/v1/opportunity-tasks/")){
            var allowed=request.getMethod().equals("GET")?Set.of("kind","limit","cursor"):Set.<String>of();
            for(var parameter:request.getParameterMap().entrySet())if(!allowed.contains(parameter.getKey())||parameter.getValue().length!=1)throw R1HttpFailure.validation("/query","NOT_ALLOWED");
            if(request.getMethod().equals("GET")&&(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null))throw R1HttpFailure.validation("/body","NOT_ALLOWED");
            for(String header:Collections.list(request.getHeaderNames())){
                var name=header.toLowerCase(Locale.ROOT);
                if(name.contains("tenant")||Set.of("x-appointment-id","x-on-behalf-appointment-id","x-principal-id","if-match","if-none-match").contains(name))throw R1HttpFailure.validation("/headers","NOT_ALLOWED");
            }
        }
        if(path.startsWith("/api/v1/admin/identity/")) {
            Set<String> allowed=request.getMethod().equals("GET")?path.endsWith("/provider-users")?Set.of("search","limit","cursor"):path.endsWith("/options")?Set.of("page","optionKind","limit","cursor"):Set.of("limit","cursor"):Set.of();
            for(var parameter:request.getParameterMap().entrySet())if(!allowed.contains(parameter.getKey())||parameter.getValue().length!=1)throw R1HttpFailure.validation("/query","NOT_ALLOWED");
            if(request.getMethod().equals("GET")&&(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null))throw R1HttpFailure.validation("/body","NOT_ALLOWED");
            for(String name:Collections.list(request.getHeaderNames()))if(name.toLowerCase(Locale.ROOT).contains("tenant"))throw R1HttpFailure.validation("/headers","NOT_ALLOWED");
        }
        if(path.equals("/internal/v1/projections/r1/readiness")||path.equals("/api/v1/session/context")){
            if(request.getQueryString()!=null&&!request.getQueryString().isEmpty())throw R1HttpFailure.validation("/query","NOT_ALLOWED");
            if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null&&request.getInputStream().read()!=-1)throw R1HttpFailure.validation("/body","NOT_ALLOWED");
        }
        if(path.equals("/internal/v1/tasks/due"))for(var parameter:request.getParameterMap().entrySet())if(!Set.of("recoveryType","limit","cursor").contains(parameter.getKey())||parameter.getValue().length!=1)throw R1HttpFailure.validation("/query","NOT_ALLOWED");
        if(op.command()==null)return;
        var key=Collections.list(request.getHeaders("Idempotency-Key"));if(key.isEmpty())throw new R1HttpFailure("IDEMPOTENCY_KEY_REQUIRED");
        if(key.size()!=1||!key.getFirst().matches(UUID_TEXT))throw new R1HttpFailure("IDEMPOTENCY_KEY_INVALID");
        if(op.command().identity()) {
            var matches=Collections.list(request.getHeaders("If-Match"));if(matches.size()>1||request.getHeader("If-None-Match")!=null)throw R1HttpFailure.validation("/headers","NOT_ALLOWED");
            var command=io.github.windyzhu3.ontologylaw.identity.IdentityCommands.handler(op.command().name());
            if(command.create()){if(!matches.isEmpty())throw R1HttpFailure.validation("/headers/If-Match","NOT_ALLOWED");return;}
            String raw=path.split("/")[6];if(!raw.matches(UUID_TEXT))throw R1HttpFailure.validation("/path/id","INVALID_FORMAT");
            if(matches.isEmpty()){var service=identities.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");var actor=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();throw new IdentityHttpFailure("IDENTITY_PRECONDITION_REQUIRED",service.precondition(actor,command.command(),UUID.fromString(raw)),null);}
            if(!matches.getFirst().matches("\"identity\\.[A-Za-z0-9_-]{43}\""))throw R1HttpFailure.validation("/headers/If-Match","INVALID_FORMAT");return;
        }
        if(op.command().transfers()||op.command().followupAttempts()||op.command().contracts()||op.command().quotes()||op.command().materials()||op.command().customerRequirements()||op.command()==Type.CLOSE_OPPORTUNITY||op.command()==Type.CAPTURE_LEAD||op.command().internalMaintenance()||op.command().ownerException())return;
        var actor=(Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(actor.principalKind()!=PrincipalKind.HUMAN)throw new R1HttpFailure("NOT_AUTHORIZED");
        String rawTask=path.split("/")[4];if(!rawTask.matches(UUID_TEXT))throw R1HttpFailure.validation("/path/taskId","INVALID_FORMAT");UUID task=UUID.fromString(rawTask);
        var matches=Collections.list(request.getHeaders("If-Match"));var nones=Collections.list(request.getHeaders("If-None-Match"));
        if(matches.size()>1||nones.size()>1)throw R1HttpFailure.validation("/headers","NOT_ALLOWED");
        boolean draft=op.command()==Type.SAVE_ACTION_DRAFT;
        if(matches.isEmpty()&&(!draft||nones.isEmpty())){
            String kind=draft?"DRAFT":"TASK";var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
            throw new R1HttpFailure(kind+"_PRECONDITION_REQUIRED",null,service.precondition(actor,op.command(),task,kind),null);
        }
        if(!nones.isEmpty()&&(!draft||!matches.isEmpty()||!nones.getFirst().equals("*")))throw R1HttpFailure.validation("/headers/If-None-Match","INVALID_FORMAT");
        if(!matches.isEmpty()&&!matches.getFirst().matches("\""+(draft?"draft":"task")+"\\.[A-Za-z0-9_-]{43}\""))throw R1HttpFailure.validation("/headers/If-Match","INVALID_FORMAT");
    }
}
