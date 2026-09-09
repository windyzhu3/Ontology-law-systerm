package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type;
import java.util.*;

/** Static operation/error binding from the single frozen OpenAPI contract. */
final class R1HttpOperations {
    record Operation(Type command,Set<String> errors){}
    private static Set<String> codes(String... groups){var result=new HashSet<String>();for(var group:groups)result.addAll(List.of(group.split(" ")));return Set.copyOf(result);}
    private static final String COMMON="UNAUTHENTICATED NOT_AUTHORIZED RATE_LIMITED INTERNAL_ERROR SERVICE_UNAVAILABLE";
    private static final String WRITE="VALIDATION_FAILED IDEMPOTENCY_KEY_REQUIRED IDEMPOTENCY_KEY_INVALID COMMAND_PAYLOAD_CONFLICT";
    private static final String TASK="APPOINTMENT_INACTIVE NOT_FOUND TASK_NOT_OPEN TASK_ALREADY_COMPLETED DRAFT_DIGEST_MISMATCH STALE_TASK STALE_DRAFT STALE_SUBJECT TASK_PRECONDITION_REQUIRED";
    private static final Set<String> READ=codes(COMMON,"NOT_FOUND VALIDATION_FAILED");
    private R1HttpOperations(){}
    static Operation find(String method,String path){
        if(path.startsWith("/api/v1/admin/identity/"))return identity(method,path);
        if(method.equals("GET")&&path.equals("/api/v1/session/context"))return new Operation(null,codes(COMMON,"VALIDATION_FAILED"));
        if(method.equals("GET")&&(path.equals("/api/v1/workcards/current")||path.matches("/api/v1/commands/[^/]+/receipt")))return new Operation(null,READ);
        if(method.equals("GET")&&(path.equals("/internal/v1/projections/r1/readiness")||path.equals("/internal/v1/tasks/due")))return new Operation(null,codes(COMMON,"VALIDATION_FAILED"));
        if(method.equals("POST")&&path.equals("/internal/v1/projections/r1/consume"))return new Operation(null,codes(COMMON,"VALIDATION_FAILED NOT_FOUND STALE_OUTBOX_CLAIM PROJECTION_EVENT_INVALID"));
        if(method.equals("POST")&&path.equals("/api/v1/leads"))return new Operation(Type.CAPTURE_LEAD,codes(COMMON,WRITE,"APPOINTMENT_INACTIVE SUPERVISOR_UNRESOLVED"));
        if(method.equals("PUT")&&path.matches("/api/v1/tasks/[^/]+/draft"))return new Operation(Type.SAVE_ACTION_DRAFT,codes(COMMON,WRITE,"APPOINTMENT_INACTIVE NOT_FOUND TASK_NOT_OPEN TASK_ALREADY_COMPLETED DRAFT_DIGEST_MISMATCH STALE_TASK STALE_DRAFT DRAFT_PRECONDITION_REQUIRED"));
        if(!method.equals("POST"))return null;
        if(path.equals("/internal/v1/tasks/commands/reopen-due-contact-tasks"))return new Operation(Type.REOPEN_DUE_CONTACT_TASKS,codes(COMMON,WRITE));
        if(path.equals("/internal/v1/tasks/commands/reopen-due-routing-review-tasks"))return new Operation(Type.REOPEN_DUE_ROUTING_REVIEW_TASKS,codes(COMMON,WRITE));
        if(!path.matches("/api/v1/tasks/[^/]+/commands/[^/]+"))return null;
        Type type=switch(path.substring(path.lastIndexOf('/')+1)){
            case "resolve-duplicate-lead"->Type.RESOLVE_DUPLICATE_LEAD;case "complete-lead-ingress"->Type.COMPLETE_LEAD_INGRESS;case "assign-lead"->Type.ASSIGN_LEAD;case "record-routing-disposition"->Type.RECORD_ROUTING_DISPOSITION;case "acknowledge-source-intake-stop-request"->Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST;case "record-contact-result"->Type.RECORD_CONTACT_RESULT;case "review-lead-validity"->Type.REVIEW_LEAD_VALIDITY;default->null;};
        if(type==null)return null;var errors=new HashSet<>(codes(COMMON,WRITE,TASK));
        if(Set.of(Type.RESOLVE_DUPLICATE_LEAD,Type.COMPLETE_LEAD_INGRESS,Type.RECORD_CONTACT_RESULT).contains(type))errors.add("SUPERVISOR_UNRESOLVED");
        if(type==Type.COMPLETE_LEAD_INGRESS)errors.add("INGRESS_COMPLETION_ALREADY_RECORDED");
        if(type==Type.RECORD_ROUTING_DISPOSITION)errors.add("SOURCE_INTAKE_OWNER_UNRESOLVED");
        return new Operation(type,Set.copyOf(errors));
    }
    private static Operation identity(String method,String path) {
        String resource=path.substring("/api/v1/admin/identity/".length());
        if(method.equals("GET")&&Set.of("provider-users","options","principals","organizations","appointments","authority-grants").contains(resource))return new Operation(null,codes(COMMON,"VALIDATION_FAILED APPOINTMENT_INACTIVE NOT_FOUND"));
        String[] pieces=resource.split("/");String noun=switch(pieces[0]){case "principals"->"IDENTITY_PRINCIPAL";case "organizations"->"ORGANIZATION_UNIT";case "appointments"->"APPOINTMENT";case "authority-grants"->"AUTHORITY_GRANT";default->null;};if(noun==null)return null;
        String command=null;
        if(pieces.length==1&&method.equals("POST"))command="CREATE_"+noun;
        else if(pieces.length==3){String action=switch(pieces[2]){case "display-name"->method.equals("PATCH")?"RENAME":null;case "suspend","resume","disable","close","end","revoke"->method.equals("POST")?pieces[2].toUpperCase(Locale.ROOT):null;default->null;};if(action!=null)command=action+"_"+noun;}
        if(command==null||!io.github.windyzhu3.ontologylaw.identity.IdentityCommands.registered(command))return null;
        return new Operation(Type.valueOf(command),codes(COMMON,WRITE,"APPOINTMENT_INACTIVE NOT_FOUND IDENTITY_BINDING_CONFLICT IDENTITY_STATE_CONFLICT IDENTITY_SELF_LOCKOUT IDENTITY_LAST_ADMIN IDENTITY_ORGANIZATION_DEPENDENCY IDENTITY_RESPONSIBILITY_DEPENDENCY STALE_IDENTITY IDENTITY_PRECONDITION_REQUIRED"));
    }
}
