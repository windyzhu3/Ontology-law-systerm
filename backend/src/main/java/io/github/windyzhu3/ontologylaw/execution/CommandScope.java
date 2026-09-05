package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;

/** Server-resolved scope; caller digests are never accepted. */
public final class CommandScope {
    private final UUID tenantId;
    private final CommandEnvelope.Type type;
    private final String canonical;
    private final UUID taskId;
    private CommandScope(UUID tenantId,CommandEnvelope.Type type,Map<String,?> fields){this.tenantId=Objects.requireNonNull(tenantId);this.type=Objects.requireNonNull(type);this.canonical=CanonicalJson.encode(fields);this.taskId=fields.containsKey("taskId")?UUID.fromString((String)fields.get("taskId")):null;}
    public UUID tenantId(){return tenantId;}
    public CommandEnvelope.Type type(){return type;}
    public String canonical(){return canonical;}
    public UUID taskId(){return taskId;}
    public byte[] digest(){return CanonicalJson.digest(canonical);}
    public static CommandScope capture(UUID tenant,String sourceAccountCode,String sourceRecordKeyDigest){
        if(sourceAccountCode==null || sourceAccountCode.isEmpty() || sourceAccountCode.length()>128)throw new IllegalArgumentException("Invalid source account code");
        new Subject("lead.lead",tenant,null,sourceRecordKeyDigest);
        return new CommandScope(tenant,CommandEnvelope.Type.CAPTURE_LEAD,Map.of("profile","R1_CAPTURE_SCOPE_V1","tenantId",tenant.toString(),"sourceAccountCode",sourceAccountCode,"sourceRecordKeyDigest",sourceRecordKeyDigest));
    }
    public static CommandScope draft(UUID tenant,UUID task,CommandEnvelope.Type primary){
        if(primary.envelope()!=CommandEnvelope.Envelope.INTERNAL_TASK || primary==CommandEnvelope.Type.SAVE_ACTION_DRAFT)throw new IllegalArgumentException("Draft requires a primary Task command");
        return new CommandScope(tenant,CommandEnvelope.Type.SAVE_ACTION_DRAFT,Map.of("profile","R1_DRAFT_SCOPE_V1","tenantId",tenant.toString(),"taskId",task.toString(),"actionCode",primary.name()));
    }
    public static CommandScope reopen(UUID tenant,CommandEnvelope.Type type,UUID task,UUID wait,String hash){
        if(!type.recovery())throw new IllegalArgumentException("Not an internal recovery command");
        new Subject("responsibility.wait_receipt",wait,null,hash);
        return new CommandScope(tenant,type,Map.of("profile","R1_REOPEN_SCOPE_V1","tenantId",tenant.toString(),"commandType",type.name(),"taskId",task.toString(),"waitReceiptId",wait.toString(),"waitReceiptHash",hash));
    }
    public static CommandScope task(UUID tenant,CommandEnvelope.Type type,UUID task,Subject lead,Map<String,Object> bindings){
        Objects.requireNonNull(task);
        if(!"lead.lead".equals(lead.type()) || lead.revision()==null)throw new IllegalArgumentException("Task requires exact Lead revision");
        Set<String> names=switch(type) {
            case RESOLVE_DUPLICATE_LEAD -> Set.of("candidateLeadId","candidateLeadRevision","partyId","partyRevision");
            case COMPLETE_LEAD_INGRESS,RECORD_ROUTING_DISPOSITION -> Set.of();
            case ASSIGN_LEAD -> Set.of("selectedOwnerAppointmentId");
            case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> Set.of("causalDecisionId","causalDecisionHash");
            case RECORD_CONTACT_RESULT -> Set.of("leadAssignmentId","leadAssignmentRevision");
            case REVIEW_LEAD_VALIDITY -> Set.of("triggeringContactResultId","triggeringContactResultHash");
            default -> throw new IllegalArgumentException("Not a primary Task command");
        };
        if(!bindings.keySet().equals(names))throw new IllegalArgumentException("Incorrect scope bindings");
        List<Object> selectors=new ArrayList<>();
        for(String name:new TreeSet<>(names)) {
            Object value=bindings.get(name);
            if(name.endsWith("Id")){if(!(value instanceof UUID))throw new IllegalArgumentException("UUID binding required");value=value.toString();}
            else if(name.endsWith("Revision")){if(!(value instanceof Long || value instanceof Integer) || ((Number)value).longValue()<0)throw new IllegalArgumentException("Revision binding required");}
            else if(name.endsWith("Hash")){if(!(value instanceof String hash))throw new IllegalArgumentException("Hash binding required");new Subject("lead.lead",lead.id(),null,hash);}
            selectors.add(Map.of("name",name,"value",value));
        }
        return new CommandScope(tenant,type,Map.of("profile","R1_COMMAND_SCOPE_V1","tenantId",tenant.toString(),"commandType",type.name(),"taskId",task.toString(),"lead",Map.of("type",lead.type(),"id",lead.id().toString(),"revision",lead.revision()),"bindings",selectors));
    }
}
