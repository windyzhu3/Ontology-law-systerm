package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;

/** Server-resolved scope; caller digests are never accepted. */
public final class CommandScope {
    private final UUID tenantId;
    private final CommandEnvelope.Type type;
    private final String canonical;
    private final UUID taskId;
    private final Map<String,Object> fields;
    @SuppressWarnings("unchecked")
    private CommandScope(UUID tenantId,CommandEnvelope.Type type,Map<String,?> fields){this.tenantId=Objects.requireNonNull(tenantId);this.type=Objects.requireNonNull(type);this.canonical=CanonicalJson.encode(fields);this.fields=(Map<String,Object>)CanonicalJson.freeze(fields);this.taskId=fields.containsKey("taskId")?UUID.fromString((String)fields.get("taskId")):null;}
    public UUID tenantId(){return tenantId;}
    public CommandEnvelope.Type type(){return type;}
    public String canonical(){return canonical;}
    /** Immutable server-resolved tree; identical bytes remain the scope digest authority. */
    public Map<String,Object> fields(){return fields;}
    public UUID taskId(){return taskId;}
    public byte[] digest(){return CanonicalJson.digest(canonical);}
    public static CommandScope opportunityRepair(UUID tenant,CommandAuthorizationBinding.OpportunityRepair b){
        if(!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null||!"responsibility.task_occurrence".equals(b.task().type())||b.task().revision()==null||b.basis()==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(b.basis().type())||b.basis().revision()==null||b.draft()!=null&&(!"responsibility.action_draft".equals(b.draft().type())||b.draft().revision()==null)||b.takeover().isEmpty()||b.takeover().size()>1000||new HashSet<>(b.takeover()).size()!=b.takeover().size()||b.takeover().stream().anyMatch(s->!Set.of("contract.contract","contract.preparation_workflow","contract.preparation_request").contains(s.type())||s.revision()==null))throw new IllegalArgumentException("Exact opportunity repair selectors required");
        var fields=new TreeMap<String,Object>(b.payload());fields.put("profile","R2_SUPERSEDED_OPPORTUNITY_REPAIR_SCOPE_V1");fields.put("tenantId",tenant.toString());fields.put("taskId",b.task().id().toString());return new CommandScope(tenant,CommandEnvelope.Type.REPAIR_SUPERSEDED_OPPORTUNITY_TASK,fields);
    }
    public static CommandScope sourceRepair(UUID tenant,CommandAuthorizationBinding.SourceRepair b){
        if(!"responsibility.task_occurrence".equals(b.task().type())||b.task().revision()==null||!"lead.lead".equals(b.lead().type())||b.lead().revision()==null||!"responsibility.decision_record".equals(b.decision().type())||b.decision().hash()==null)throw new IllegalArgumentException("Exact historical ACK required");
        var fields=new TreeMap<String,Object>(b.payload());fields.put("profile","R2_SOURCE_REQUEST_REPAIR_SCOPE_V1");fields.put("tenantId",tenant.toString());fields.put("taskId",b.task().id().toString());return new CommandScope(tenant,CommandEnvelope.Type.RESTORE_SOURCE_REQUEST_TASK,fields);
    }
    public static CommandScope contractRecovery(UUID tenant,CommandAuthorizationBinding.ContractRecovery b){
        if(b.opportunity()==null||!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null||b.basis()==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(b.basis().type())||b.basis().revision()==null||b.source()==null||!Set.of("opportunity.contract_preparation_source","contract.preparation_workflow","contract.signature_readiness","contract.signature_workflow","contract.negotiation_disposition","contract.signature_handoff","contract.signature_archive","contract.payment_request","contract.execution_verification","transfer.workflow").contains(b.source().type())||!Long.valueOf(0).equals(b.source().revision())||b.workflow()!=null&&(!b.resultType().equals(b.workflow().type())||!Long.valueOf(0).equals(b.workflow().revision())))throw new IllegalArgumentException("Exact contract recovery basis required");
        if(Set.of("PAYMENT_RECOVERY","TERMINATION_REVIEW").contains(b.sourceKind())&&b.workflow()==null)throw new IllegalArgumentException("Exact supervisor assignment required");
        if(Set.of("AUTHORITY_RETURN","SIGNATURE_AUTHORITY_RETURN","TRANSFER_RECOVERY").contains(b.sourceKind())&&!b.source().equals(b.workflow()))throw new IllegalArgumentException("Exact authority return head required");
        if("TRANSFER_HANDOFF".equals(b.sourceKind())&&b.workflow()!=null)throw new IllegalArgumentException("Initial transfer has no prior workflow");
        var fields=new TreeMap<String,Object>();fields.put("sourceKind",b.sourceKind());fields.put("profile","R2_CONTRACT_PREPARATION_RECOVERY_SCOPE_V1");fields.put("tenantId",tenant.toString());fields.put("opportunity",selector(b.opportunity()));fields.put("basis",selector(b.basis()));fields.put("source",selector(b.source()));fields.put("workflow",b.workflow()==null?null:selector(b.workflow()));return new CommandScope(tenant,CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,fields);
    }
    public static CommandScope transfers(CommandEnvelope e,CommandAuthorizationBinding.Transfers b){
        if(!e.type().transfers()||e.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||b.opportunity()==null||!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null||b.workflow()==null||!"transfer.workflow".equals(b.workflow().type())||!Long.valueOf(0).equals(b.workflow().revision()))throw new IllegalArgumentException("Exact transfer scope required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());fields.put("opportunity",selector(b.opportunity()));fields.put("workflow",selector(b.workflow()));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static CommandScope contracts(CommandEnvelope e,CommandAuthorizationBinding.Contracts b){
        if(!e.type().contracts()||e.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
            ||b.opportunity()==null||!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null
            ||b.basis()==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(b.basis().type())||b.basis().revision()==null
            ||!(b.contract()==null||"contract.contract".equals(b.contract().type())&&b.contract().revision()!=null&&b.contract().hash()==null)
            ||!materialSelector(b.confirmation(),"opportunity.customer_requirement_confirmation")||!materialSelector(b.draft(),"contract.preparation_draft")
            ||!(b.version()==null||"contract.contract_revision".equals(b.version().type())&&b.version().hash()!=null&&b.version().revision()==null)||!materialSelector(b.workflow(),"contract.preparation_workflow"))throw new IllegalArgumentException("Exact contract scope required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(b.opportunity()));fields.put("basis",selector(b.basis()));fields.put("confirmation",selector(b.confirmation()));fields.put("draft",selector(b.draft()));fields.put("contract",selector(b.contract()));fields.put("version",selector(b.version()));fields.put("workflow",selector(b.workflow()));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static CommandScope followupAttempt(CommandEnvelope e,CommandAuthorizationBinding.FollowupAttempt b){
        if(!e.type().followupAttempts()||e.actor().onBehalfAppointmentId()!=null||b.opportunity()==null||!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null||b.task()==null||!"responsibility.task_occurrence".equals(b.task().type())||b.task().revision()==null||b.basis()==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(b.basis().type())||b.basis().revision()==null)throw new IllegalArgumentException("Exact follow-up attempt scope required");
        if(b.waitReceipt()!=null&&(!"responsibility.wait_receipt".equals(b.waitReceipt().type())||b.waitReceipt().hash()==null)||b.workflow()!=null&&(!"opportunity.quote_workflow".equals(b.workflow().type())||!Long.valueOf(0).equals(b.workflow().revision()))||(e.type()==CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT)!=(b.workflow()!=null))throw new IllegalArgumentException("Exact wait and quote workflow required");
        var f=new TreeMap<String,Object>();f.put("profile","R2_"+e.type().name()+"_SCOPE_V1");f.put("tenantId",e.actor().tenantId().toString());f.put("commandType",e.type().name());f.put("principalId",e.actor().principalId().toString());f.put("appointmentId",e.actor().appointmentId().toString());f.put("opportunity",selector(b.opportunity()));f.put("basis",selector(b.basis()));f.put("task",selector(b.task()));f.put("wait",selector(b.waitReceipt()));f.put("workflow",selector(b.workflow()));return new CommandScope(e.actor().tenantId(),e.type(),f);
    }
    public static CommandScope quotes(CommandEnvelope e,CommandAuthorizationBinding.Quotes b){
        if(!e.type().quotes()||e.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
            ||b.opportunity()==null||!"opportunity.opportunity".equals(b.opportunity().type())||b.opportunity().revision()==null
            ||b.basis()==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(b.basis().type())||b.basis().revision()==null
            ||!materialSelector(b.confirmation(),"opportunity.customer_requirement_confirmation")||!materialSelector(b.draft(),"opportunity.quote_draft")
            ||!(b.quote()==null||"opportunity.quote_revision".equals(b.quote().type())&&b.quote().hash()!=null&&b.quote().revision()==null)||!materialSelector(b.workflow(),"opportunity.quote_workflow"))throw new IllegalArgumentException("Exact quote scope required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(b.opportunity()));fields.put("basis",selector(b.basis()));fields.put("confirmation",selector(b.confirmation()));fields.put("draft",selector(b.draft()));fields.put("quote",selector(b.quote()));fields.put("workflow",selector(b.workflow()));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static CommandScope materials(CommandEnvelope e,Subject opportunity,Subject basis,Subject confirmation,Subject previous,Subject upload){
        if(!e.type().materials()||e.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
            ||opportunity==null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null
            ||basis==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.type())||basis.revision()==null
            ||!materialSelector(confirmation,"opportunity.customer_requirement_confirmation")||!materialSelector(previous,"opportunity.material_version")||!materialSelector(upload,"evidence.material_upload_basis")
            ||(e.type()==CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL)!=(upload!=null))throw new IllegalArgumentException("Exact material scope required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(opportunity));fields.put("basis",selector(basis));fields.put("confirmation",selector(confirmation));fields.put("previous",selector(previous));fields.put("upload",selector(upload));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    private static boolean materialSelector(Subject s,String type){return s==null||type.equals(s.type())&&Long.valueOf(0).equals(s.revision())&&s.hash()==null;}
    public static CommandScope customerRequirements(CommandEnvelope e,Subject opportunity,Subject basis,Subject draft,Subject confirmation){
        if(!e.type().customerRequirements()||e.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
            ||opportunity==null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null
            ||basis==null||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.type())||basis.revision()==null
            ||draft!=null&&(!"opportunity.customer_requirement_draft".equals(draft.type())||draft.revision()==null||draft.revision()!=0)
            ||confirmation!=null&&(!"opportunity.customer_requirement_confirmation".equals(confirmation.type())||confirmation.revision()==null||confirmation.revision()!=0)
            ||e.type()==CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS&&draft==null)throw new IllegalArgumentException("Exact customer requirements scope required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(opportunity));fields.put("basis",selector(basis));fields.put("draft",selector(draft));fields.put("confirmation",selector(confirmation));
        return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static CommandScope ownerException(CommandEnvelope e,Subject opportunity,Subject exception,Subject basis,Subject task,Subject waitReceipt){
        if(!e.type().ownerException()||e.actor().onBehalfAppointmentId()!=null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null)throw new IllegalArgumentException("Exact direct owner exception command required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_"+e.type().name()+"_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(opportunity));fields.put("exception",selector(exception));fields.put("basis",selector(basis));fields.put("task",selector(task));fields.put("wait",selector(waitReceipt));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static CommandScope opportunityClosure(CommandEnvelope e,Subject opportunity,Subject basis,Subject task,Subject waitReceipt){
        if(e.type()!=CommandEnvelope.Type.CLOSE_OPPORTUNITY||e.actor().onBehalfAppointmentId()!=null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null)throw new IllegalArgumentException("Exact direct closure command required");
        var fields=new TreeMap<String,Object>();fields.put("profile","R2_CLOSE_OPPORTUNITY_SCOPE_V1");fields.put("tenantId",e.actor().tenantId().toString());fields.put("commandType",e.type().name());fields.put("principalId",e.actor().principalId().toString());fields.put("appointmentId",e.actor().appointmentId().toString());
        fields.put("opportunity",selector(opportunity));fields.put("basis",selector(basis));fields.put("task",selector(task));fields.put("wait",selector(waitReceipt));return new CommandScope(e.actor().tenantId(),e.type(),fields);
    }
    public static Map<String,Object> selector(Subject s){return s==null?null:s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash());}
    public static CommandScope identity(CommandEnvelope e,Map<String,Object> target){
        if(!e.type().identity()||e.actor().onBehalfAppointmentId()!=null)throw new IllegalArgumentException("Not a direct Identity command");
        return new CommandScope(e.actor().tenantId(),e.type(),Map.of("profile","R1_IDENTITY_COMMAND_SCOPE_V1","tenantId",e.actor().tenantId().toString(),"commandType",e.type().name(),"principalId",e.actor().principalId().toString(),"appointmentId",e.actor().appointmentId().toString(),"target",target));
    }
    public static CommandScope capture(UUID tenant,String sourceAccountCode,String sourceRecordKeyDigest){
        if(sourceAccountCode==null || sourceAccountCode.isEmpty() || sourceAccountCode.length()>128)throw new IllegalArgumentException("Invalid source account code");
        new Subject("lead.lead",tenant,null,sourceRecordKeyDigest);
        return new CommandScope(tenant,CommandEnvelope.Type.CAPTURE_LEAD,Map.of("profile","R1_CAPTURE_SCOPE_V1","tenantId",tenant.toString(),"sourceAccountCode",sourceAccountCode,"sourceRecordKeyDigest",sourceRecordKeyDigest));
    }
    public static CommandScope draft(UUID tenant,UUID task,CommandEnvelope.Type primary){
        if(R1CommandPolicy.primaryPolicy(primary)==null)throw new IllegalArgumentException("Draft requires a primary Task command");
        return new CommandScope(tenant,CommandEnvelope.Type.SAVE_ACTION_DRAFT,Map.of("profile","R1_DRAFT_SCOPE_V1","tenantId",tenant.toString(),"taskId",task.toString(),"actionCode",primary.name()));
    }
    public static CommandScope opportunity(UUID tenant,UUID task,Subject opportunity){
        if(!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null)throw new IllegalArgumentException("Exact Opportunity required");
        return new CommandScope(tenant,CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,Map.of("profile","R2_OPPORTUNITY_COMMAND_SCOPE_V1","tenantId",tenant.toString(),"commandType","RECORD_OPPORTUNITY_PROGRESS","taskId",task.toString(),"subject",Map.of("type",opportunity.type(),"id",opportunity.id().toString(),"revision",opportunity.revision())));
    }
    public static CommandScope reopen(UUID tenant,CommandEnvelope.Type type,UUID task,UUID wait,String hash){
        if(type!=CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS&&type!=CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS&&type!=CommandEnvelope.Type.REOPEN_DUE_SOURCE_REQUEST_TASKS)throw new IllegalArgumentException("Not an R1 recovery command");
        new Subject("responsibility.wait_receipt",wait,null,hash);
        return new CommandScope(tenant,type,Map.of("profile",type==CommandEnvelope.Type.REOPEN_DUE_SOURCE_REQUEST_TASKS?"R2_SOURCE_REQUEST_REOPEN_SCOPE_V1":"R1_REOPEN_SCOPE_V1","tenantId",tenant.toString(),"commandType",type.name(),"taskId",task.toString(),"waitReceiptId",wait.toString(),"waitReceiptHash",hash));
    }
    public static CommandScope opportunityActivation(UUID tenant,Subject opportunity){
        if(!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null)throw new IllegalArgumentException("Exact Opportunity required");
        return new CommandScope(tenant,CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,Map.of("profile","R2_OPPORTUNITY_ACTIVATION_SCOPE_V1","tenantId",tenant.toString(),"commandType","ACTIVATE_INITIAL_OPPORTUNITY_TASK","opportunityId",opportunity.id().toString(),"opportunityRevision",opportunity.revision()));
    }
    public static CommandScope opportunityRecovery(UUID tenant,UUID task,Subject opportunity,Subject wait,Subject progress){
        if(!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null
                ||!"responsibility.wait_receipt".equals(wait.type())||wait.hash()==null
                ||!Set.of("opportunity.opportunity_progress","opportunity.quote_response","opportunity.followup_attempt").contains(progress.type())||progress.hash()==null)throw new IllegalArgumentException("Exact R2 recovery sources required");
        return new CommandScope(tenant,CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,Map.of("profile","R2_OPPORTUNITY_RECOVERY_SCOPE_V1","tenantId",tenant.toString(),"commandType","REOPEN_DUE_OPPORTUNITY_TASKS","taskId",task.toString(),
                "opportunity",Map.of("id",opportunity.id().toString(),"revision",opportunity.revision()),"wait",Map.of("id",wait.id().toString(),"hash",wait.hash()),"progress",
                !progress.type().equals("opportunity.opportunity_progress")?Map.of("type",progress.type(),"id",progress.id().toString(),"hash",progress.hash()):Map.of("id",progress.id().toString(),"hash",progress.hash())));
    }
    public static CommandScope task(UUID tenant,CommandEnvelope.Type type,UUID task,Subject lead,Map<String,Object> bindings){
        Objects.requireNonNull(task);
        if(!"lead.lead".equals(lead.type()) || lead.revision()==null)throw new IllegalArgumentException("Task requires exact Lead revision");
        Set<String> names=switch(type) {
            case RESOLVE_DUPLICATE_LEAD -> Set.of("candidateLeadId","candidateLeadRevision","partyId","partyRevision");
            case COMPLETE_LEAD_INGRESS,RECORD_ROUTING_DISPOSITION,RECORD_SOURCE_REQUEST_CONTINUATION -> Set.of();
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
