package io.github.windyzhu3.ontologylaw.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;

/** Safe response construction shared by MVC and the authentication filter boundary. */
@RestControllerAdvice(basePackageClasses=R1ApiDelegate.class)
public class ProblemDetailsAdvice {
    private record ProblemCode(int status,String retry,String text,String kind) {}
    private static final Map<String,ProblemCode> CODES=Map.ofEntries(
            entry("PAYMENT_ALREADY_RECORDED",409,"NEW_KEY_AFTER_REFRESH","本笔银行流水已记录，请核对已有到账结果",null),
            entry("PAYMENT_ACCOUNT_NOT_CONFIGURED",409,"NEW_KEY_AFTER_ADMIN_FIX","尚未配置收款账户，请联系管理员核对",null),
            entry("SIGNATURE_INCOMPLETE",409,"NEW_KEY_AFTER_REFRESH","必需签署事项尚未全部核验完成",null),
            entry("CONTRACT_TEMPLATE_SIGNING_PARTY_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","本版模板未绑定律所签署主体，请返回合同修订",null),
            entry("COMMERCIAL_AUTHORIZATION_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","商业条件变化，请重新申请准确授权",null),
            entry("CONTRACT_APPROVAL_POLICY_CHANGED",409,"NEW_KEY_AFTER_REFRESH","审批配置已变化，请刷新并重新形成版本",null),
            entry("CONTRACT_APPROVAL_POLICY_REQUIRED",409,"NEW_KEY_AFTER_ADMIN_FIX","尚未配置有效合同审批成员",null),
            entry("CONTRACT_REVIEW_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","本版尚未通过签约前审查",null),
            entry("CONTRACT_SOURCE_AMBIGUOUS",409,"NEW_KEY_AFTER_REFRESH","合同准备来源存在歧义，需要核实",null),
            entry("CONTRACT_VERSION_BASIS_CHANGED",409,"NEW_KEY_AFTER_REFRESH","本版合同依据已变化，请形成新版本",null),
            entry("CONTRACT_PREPARATION_SOURCE_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","请先取得准确合同准备依据",null),
            entry("CONTRACT_HANDLING_PAUSED",409,"NEW_KEY_AFTER_REFRESH","合同销售办理已暂停或停止，请刷新查看当前处置",null),
            entry("CONTRACT_RESPONSIBILITY_REQUIRED",409,"NEW_KEY_AFTER_ADMIN_FIX","无法唯一确定有权办理人，请配置任职与授权",null),
            entry("CONTRACT_REVIEW_SCOPE_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","本版缺少完整审查参与方",null),
            entry("CONTRACT_REVIEW_NOT_CLEAR",409,"NEW_KEY_AFTER_REFRESH","存在冲突候选或范围不完整，不能记录无冲突",null),
            entry("CONTRACT_REVIEW_SCOPE_COMPLETE",409,"NEW_KEY_AFTER_REFRESH","当前范围完整，请选择与实际结果一致的决定",null),
            entry("CONTRACT_REVIEW_FINDING_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","没有准确冲突候选，不能记录冲突阻断",null),
            entry("CONTRACT_REVIEW_WAIVER_UNAVAILABLE",409,"NEW_KEY_AFTER_REFRESH","当前合同流程不支持冲突豁免",null),
            entry("CONTRACT_CONFLICT_DECISION_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","冲突阻断需要准确人工决定",null),
            entry("CUSTOMER_CONFIRMATION_REQUIRED",409,"NEW_KEY_AFTER_REFRESH","请先完成客户及需求确认",null),
            entry("RECIPIENT_UNAVAILABLE",409,"NEW_KEY_AFTER_REFRESH","承接任职已变化，请重新选择有权承接人",null),
            entry("STALE_CUSTOMER_BASIS",412,"NEW_KEY_AFTER_REFRESH","客户主体依据已变化，请重新核对",null),
            entry("STALE_REVIEW",412,"NEW_KEY_AFTER_REFRESH","审查依据已变化，请退回补正后重新审查",null),
            entry("STALE_TRANSFER_PARTY",412,"NEW_KEY_AFTER_REFRESH","转案主体资料已变化，请重新核对",null),
            entry("STALE_TRANSFER_REVIEW_BASIS",412,"NEW_KEY_AFTER_REFRESH","转案审查依据已变化，请刷新后重新核对",null),
            entry("TRANSFER_REVIEW_OUTCOME_UNAVAILABLE",409,"NEW_KEY_AFTER_REFRESH","当前审查依据不支持所选结论，请核对缺项或冲突",null),
            entry("STALE_EVIDENCE",412,"NEW_KEY_AFTER_REFRESH","材料版本已变化，请刷新后重新选择",null),
            entry("VALIDATION_FAILED",400,"SAME_KEY_AFTER_FIX","请求字段未通过校验",null),
            entry("IDEMPOTENCY_KEY_REQUIRED",400,"SAME_KEY_AFTER_FIX","写操作缺少幂等键",null),
            entry("IDEMPOTENCY_KEY_INVALID",400,"SAME_KEY_AFTER_FIX","幂等键格式或长度无效",null),
            entry("UNAUTHENTICATED",401,"SAME_KEY_AFTER_REAUTH","需要有效认证",null),
            entry("NOT_AUTHORIZED",403,"NO","当前身份无权执行此操作",null),
            entry("APPOINTMENT_INACTIVE",403,"NO","当前任职不可用于此操作",null),
            entry("NOT_FOUND",404,"NO","资源不存在或不可见",null),
            entry("COMMAND_PAYLOAD_CONFLICT",409,"NO","幂等键已绑定其他请求",null),
            entry("IDENTITY_BINDING_CONFLICT",409,"NO","身份绑定已存在",null),
            entry("IDENTITY_STATE_CONFLICT",409,"NEW_KEY_AFTER_REFRESH","身份事实状态不允许此操作",null),
            entry("IDENTITY_SELF_LOCKOUT",409,"NO","不能通过此操作锁定自己的管理资格",null),
            entry("IDENTITY_LAST_ADMIN",409,"NO","不能移除最后可用管理员",null),
            entry("IDENTITY_ORGANIZATION_DEPENDENCY",409,"NEW_KEY_AFTER_ADMIN_FIX","存在尚未处理的身份依赖",null),
            entry("IDENTITY_RESPONSIBILITY_DEPENDENCY",409,"NEW_KEY_AFTER_ADMIN_FIX","该任职仍承担开放或等待责任",null),
            entry("STALE_IDENTITY",412,"NEW_KEY_AFTER_REFRESH","身份事实版本已变化",null),
            entry("IDENTITY_PRECONDITION_REQUIRED",428,"SAME_KEY_AFTER_FIX","缺少身份事实前置条件",null),
            entry("STALE_OUTBOX_CLAIM",409,"NO","投影领取 revision、owner、token 或 lease 已失效",null),
            entry("TASK_NOT_OPEN",409,"NO","Task 当前不可执行","TASK"),
            entry("TASK_ALREADY_COMPLETED",409,"NO","Task 已完成","TASK"),
            entry("DRAFT_DIGEST_MISMATCH",409,"NEW_KEY_AFTER_REFRESH","提交内容与草稿摘要不一致","DRAFT"),
            entry("INGRESS_COMPLETION_ALREADY_RECORDED",409,"NO","Ingress completion 已存在","SUBJECT"),
            entry("STALE_TASK",412,"NEW_KEY_AFTER_REFRESH","Task 版本已变化","TASK"),
            entry("STALE_DRAFT",412,"NEW_KEY_AFTER_REFRESH","Draft 版本已变化","DRAFT"),
            entry("STALE_SUBJECT",412,"NEW_KEY_AFTER_REFRESH","业务对象版本已变化","SUBJECT"),
            entry("STALE_PROGRESS",412,"NEW_KEY_AFTER_REFRESH","商机进展引用已变化",null),
            entry("OPPORTUNITY_OPENING_SOURCE_INVALID",422,"NEW_KEY_AFTER_ADMIN_FIX","商机承接来源需要核实",null),
            entry("OPPORTUNITY_NOT_FOUND",404,"NO","商机不存在或不可见",null),
            entry("STALE_OPPORTUNITY",412,"NEW_KEY_AFTER_REFRESH","商机版本已变化",null),
            entry("OPPORTUNITY_HAS_DOWNSTREAM_FACTS",409,"NO","商机已有后续业务，不能在此结束",null),
            entry("OPPORTUNITY_CLOSED",409,"NO","商机已关闭",null),
            entry("SUPERVISOR_UNRESOLVED",422,"NEW_KEY_AFTER_ADMIN_FIX","无法唯一解析准确主管",null),
            entry("SOURCE_INTAKE_OWNER_UNRESOLVED",422,"NEW_KEY_AFTER_ADMIN_FIX","无法唯一解析准确来源接入负责人",null),
            entry("PROJECTION_EVENT_INVALID",422,"NO","Event/Outbox/source selector 不符合冻结投影合同",null),
            entry("DRAFT_PRECONDITION_REQUIRED",428,"SAME_KEY_AFTER_FIX","缺少 Draft 创建或更新前置条件","DRAFT"),
            entry("TASK_PRECONDITION_REQUIRED",428,"SAME_KEY_AFTER_FIX","缺少 Task 命令前置条件","TASK"),
            entry("RATE_LIMITED",429,"SAME_KEY_AFTER_BACKOFF","请求过于频繁，请稍后重试",null),
            entry("INTERNAL_ERROR",500,"SAME_KEY_AFTER_BACKOFF","服务暂时无法完成请求",null),
            entry("SERVICE_UNAVAILABLE",503,"SAME_KEY_AFTER_BACKOFF","服务暂时不可用",null));
    private static Map.Entry<String,ProblemCode> entry(String code,int status,String retry,String text,String kind){return Map.entry(code,new ProblemCode(status,retry,text,kind));}
    static String tagKind(String code){var entry=CODES.get(code);return entry==null?null:entry.kind();}
    @ExceptionHandler(R1HttpFailure.class)
    ResponseEntity<Map<String,Object>> failure(R1HttpFailure failure,HttpServletRequest request){return response(failure,request);}
    @ExceptionHandler(IdentityHttpFailure.class)
    ResponseEntity<Map<String,Object>> identityFailure(IdentityHttpFailure failure,HttpServletRequest request){
        var response=response(new R1HttpFailure(failure.code,failure.receipt),request);var body=new TreeMap<>(Objects.requireNonNull(response.getBody()));
        if(failure.code.equals(body.get("code"))&&Set.of("STALE_IDENTITY","IDENTITY_PRECONDITION_REQUIRED").contains(failure.code)&&failure.etag!=null)body.put("currentETag",failure.etag);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(body);
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,jakarta.validation.ConstraintViolationException.class,org.springframework.web.method.annotation.HandlerMethodValidationException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<Map<String,Object>> invalid(Exception failure,HttpServletRequest request){
        String pointer="/";
        if(failure instanceof org.springframework.web.bind.MethodArgumentNotValidException bad&&!bad.getBindingResult().getFieldErrors().isEmpty())pointer="/"+bad.getBindingResult().getFieldErrors().getFirst().getField().replace('.', '/');
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof tools.jackson.databind.exc.UnrecognizedPropertyException unknown)pointer="/"+unknown.getPropertyName().replace("~","~0").replace("/","~1");
        var op=R1HttpOperations.find(request.getMethod(),request.getRequestURI());
        if(op!=null&&!op.errors().contains("VALIDATION_FAILED"))return response(new R1HttpFailure("NOT_FOUND"),request);
        return response(R1HttpFailure.validation(pointer.length()<=512?pointer:"/","INVALID_FORMAT"),request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unexpected(Exception ignored,HttpServletRequest request){return response(new R1HttpFailure("INTERNAL_ERROR"),request);}
    private ResponseEntity<Map<String,Object>> response(R1HttpFailure failure,HttpServletRequest request) {
        var op=R1HttpOperations.find(request.getMethod(),request.getRequestURI());String requested=failure.code();
        String code=CODES.containsKey(requested)&&op!=null&&op.errors().contains(requested)?requested:"INTERNAL_ERROR";var policy=CODES.get(code);
        var result=ResponseEntity.status(policy.status()).contentType(MediaType.valueOf("application/problem+json")).header("Cache-Control","no-store");
        if(policy.status()==401&&request.getRequestURI().startsWith("/api/v1/"))result.header("WWW-Authenticate","Bearer");
        var body=new TreeMap<String,Object>();body.putAll(Map.of("type","urn:ontology-law:problem:"+code,"title",policy.text(),"status",policy.status(),"code",code,"detail",policy.text(),"instance","/problems/"+UUID.randomUUID(),"retryPolicy",policy.retry()));
        if(code.equals("VALIDATION_FAILED"))body.put("fieldErrors",failure.fields()==null?R1HttpFailure.validation("/","CONDITION_FAILED").fields():failure.fields());
        if(code.equals(requested)&&policy.kind()!=null&&failure.currentETag()!=null&&policy.kind().equals(failure.currentETag().get("resourceKind")))body.put("currentETag",failure.currentETag());
        if(code.equals(requested)&&policy.status()<500&&failure.receiptRef()!=null)body.put("receiptRef",failure.receiptRef());
        return result.body(body);
    }

    public static void unauthenticated(HttpServletResponse response, boolean publicBearer) throws IOException {
        authenticationProblem(response,publicBearer,"UNAUTHENTICATED");
    }
    public static void authenticationFailure(HttpServletResponse response,boolean publicBearer,org.springframework.security.core.AuthenticationException failure)throws IOException {
        if(failure instanceof io.github.windyzhu3.ontologylaw.api.security.ActorSelectionFailure selection){authenticationProblem(response,publicBearer,selection.getMessage(),selection.pointer());return;}
        authenticationProblem(response,publicBearer,failure instanceof org.springframework.security.authentication.AuthenticationServiceException?"SERVICE_UNAVAILABLE":"UNAUTHENTICATED");
    }
    private static void authenticationProblem(HttpServletResponse response,boolean publicBearer,String code)throws IOException {
        authenticationProblem(response,publicBearer,code,null);
    }
    private static void authenticationProblem(HttpServletResponse response,boolean publicBearer,String code,String pointer)throws IOException {
        var policy=CODES.get(code);response.setStatus(policy.status());
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (publicBearer&&policy.status()==401) response.setHeader("WWW-Authenticate", "Bearer");
        var body = new TreeMap<String,Object>(Map.of("type", "urn:ontology-law:problem:"+code,
                "title",policy.text(),"status",policy.status(),"code",code,
                "detail",policy.text(),"instance","/problems/"+UUID.randomUUID(),"retryPolicy",policy.retry()));
        if(code.equals("VALIDATION_FAILED"))body.put("fieldErrors",R1HttpFailure.validation(pointer,"INVALID_FORMAT").fields());
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(body));
    }
}
