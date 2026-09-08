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
    private static final Set<String> READ=codes(COMMON,"NOT_FOUND");
    private R1HttpOperations(){}
    static Operation find(String method,String path){
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
}
