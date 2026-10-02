package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.*;

/** Typed restricted recovery selectors. Never serialize or log this internal metadata. */
public final class ReceiptRecoveryMetadata {
    private static final Set<String> PRIMARY=Set.of("RECORD_SOURCE_REQUEST_CONTINUATION","RESOLVE_DUPLICATE_LEAD","COMPLETE_LEAD_INGRESS","ASSIGN_LEAD","RECORD_ROUTING_DISPOSITION","ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","RECORD_CONTACT_RESULT","REVIEW_LEAD_VALIDITY");
    private final String commandType,account,sourceKey,action;
    private final UUID tenant,task;
    private final Subject lead,draft,submission,evidenceBinding;
    private final Long taskRevision;
    private final byte[] scopeDigest;
    private Map<String,Object> ownerExceptionScope;
    private Map<String,Object> opportunityClosureScope;
    private Map<String,Object> customerRequirementsScope;
    private Map<String,Object> materialsScope;
    private Map<String,Object> transfersScope;
    public Map<String,Object> transfersScope(){return transfersScope;}
    private Map<String,Object> contractsScope;
    public Map<String,Object> contractsScope(){return contractsScope;}
    private Map<String,Object> attemptScope;
    public Map<String,Object> attemptScope(){return attemptScope;}
    private Map<String,Object> quotesScope;
    public Map<String,Object> quotesScope(){return quotesScope;}
    private Map<String,Object> identityScope;
    private Subject identityTarget,identityAnchor;

    public ReceiptRecoveryMetadata(String commandType, Object recovery) {
        if(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.registered(commandType)) {
            var root=object(recovery);fields(root,"profile","scope","target","authorizationAnchor");equal(root.get("profile"),"R1_IDENTITY_RECEIPT_RECOVERY_V1");
            if(encode(root).getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();
            var scope=object(root.get("scope"));fields(scope,"profile","tenantId","commandType","principalId","appointmentId","target");equal(scope.get("profile"),"R1_IDENTITY_COMMAND_SCOPE_V1");equal(scope.get("commandType"),commandType);
            tenant=uuid(scope.get("tenantId"));uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            var handler=io.github.windyzhu3.ontologylaw.identity.IdentityCommands.handler(commandType);var target=object(scope.get("target"));
            if(handler.create())switch(handler.kind()) {
                case ROLE->{fields(target,"kind","code");equal(target.get("kind"),"CREATE_ROLE");if(!string(target.get("code")).matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();}
                case PRINCIPAL->{fields(target,"kind","providerCode","subjectHmac");equal(target.get("kind"),"CREATE_PRINCIPAL");if(!string(target.get("providerCode")).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw invalid();hash(target.get("subjectHmac"));}
                case ORGANIZATION->{fields(target,"kind","parentId","code");equal(target.get("kind"),"CREATE_ORGANIZATION");uuid(target.get("parentId"));if(!string(target.get("code")).matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();}
                case APPOINTMENT->{fields(target,"kind","principalId","organizationId","roleCode","effectiveFrom","effectiveUntil");equal(target.get("kind"),"CREATE_APPOINTMENT");uuid(target.get("principalId"));uuid(target.get("organizationId"));if(!string(target.get("roleCode")).matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();term(target,"effective");}
                case AUTHORITY_GRANT->{fields(target,"kind","appointmentId","authorityCode","scopeOrganizationId","validFrom","validUntil");equal(target.get("kind"),"CREATE_AUTHORITY_GRANT");uuid(target.get("appointmentId"));uuid(target.get("scopeOrganizationId"));if(!io.github.windyzhu3.ontologylaw.identity.IdentityCommands.GRANTABLE.contains(target.get("authorityCode")))throw invalid();term(target,"valid");}
            } else {fields(target,"kind","id");equal(target.get("kind"),handler.kind().factType);uuid(target.get("id"));}
            identityScope=scope;identityTarget=root.get("target")==null?null:selector(root.get("target"),handler.kind().factType,false);
            if(identityTarget==null&&!handler.create())throw invalid();
            if(!handler.create()&&!identityTarget.id().equals(uuid(target.get("id"))))throw invalid();
            identityAnchor=selector(root.get("authorizationAnchor"),"identity.organization_unit",false);
            this.commandType=commandType;scopeDigest=digest(encode(scope));task=null;account=null;sourceKey=null;action=null;lead=null;draft=null;submission=null;evidenceBinding=null;taskRevision=null;return;
        }
        var root=object(recovery);fields(root,"profile","scope","binding");equal(root.get("profile"),"R1_COMMAND_RECEIPT_RECOVERY_V1");
        if(encode(root).getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();
        var scope=object(root.get("scope"));var binding=object(root.get("binding"));
        this.commandType=commandType;tenant=uuid(scope.get("tenantId"));scopeDigest=digest(encode(scope));
        UUID parsedTask=null;String parsedAccount=null,parsedKey=null,parsedAction=null;
        Subject parsedLead=null,parsedDraft=null,parsedSubmission=null,parsedBinding=null;Long parsedRevision=null;
        if(Set.of("SUBMIT_TRANSFER","RESUBMIT_TRANSFER","RECORD_TRANSFER_CONFLICT_REVIEW","RECORD_TRANSFER_INTAKE","CLASSIFY_MATTER").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","workflow");equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);var workflow=selector(scope.get("workflow"),"transfer.workflow",false);if(!Long.valueOf(0).equals(workflow.revision()))throw invalid();fields(binding,"kind");equal(binding.get("kind"),"TRANSFERS");transfersScope=scope;
        } else if(Set.of("REQUEST_CONTRACT_RECEIPT_REVIEW","RECORD_CONTRACT_RECEIPT_REVIEW","SUPPLEMENT_CONTRACT_RECEIPT","VERIFY_CONTRACT_EXECUTION_CONDITIONS","END_CONTRACT_NEGOTIATION","REQUEST_CONTRACT_TERMINATION_REVIEW","RECORD_CONTRACT_TERMINATION_REVIEW","RETURN_CONTRACT_SIGNATURE_FOR_REVISION","SAVE_CONTRACT_SIGNATURE_DRAFT","CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT","SUBMIT_CONTRACT_SIGNATURE","RECORD_CONTRACT_SIGNATURE_VERIFICATION","ARCHIVE_CONTRACT_SIGNATURE","RETURN_CONTRACT_FOR_REVISION","REQUEST_CONTRACT_PREPARATION","RECORD_CONTRACT_PREPARATION_DECISION","START_CONTRACT_PREPARATION","SAVE_CONTRACT_DRAFT","FORM_CONTRACT","REQUEST_CONTRACT_REVIEW","RECORD_CONTRACT_REVIEW","REQUEST_CONTRACT_APPROVAL","RECORD_CONTRACT_DECISION").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","confirmation","draft","contract","version","workflow");
            equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            for(var entry:Map.of("confirmation","opportunity.customer_requirement_confirmation","draft","contract.preparation_draft","workflow","contract.preparation_workflow").entrySet())if(scope.get(entry.getKey())!=null){var exact=selector(scope.get(entry.getKey()),entry.getValue(),false);if(!Long.valueOf(0).equals(exact.revision()))throw invalid();}
            if(scope.get("contract")!=null)selector(scope.get("contract"),"contract.contract",false);
            if(scope.get("version")!=null)selector(scope.get("version"),"contract.contract_revision",true);
            fields(binding,"kind");equal(binding.get("kind"),"CONTRACTS");contractsScope=scope;
        } else if(Set.of("RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT","RECORD_QUOTE_FOLLOWUP_ATTEMPT").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","task","wait","workflow");equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            selector(scope.get("task"),"responsibility.task_occurrence",false);if(scope.get("wait")!=null)selector(scope.get("wait"),"responsibility.wait_receipt",true);
            if("RECORD_QUOTE_FOLLOWUP_ATTEMPT".equals(commandType)!=(scope.get("workflow")!=null))throw invalid();if(scope.get("workflow")!=null&&!Long.valueOf(0).equals(selector(scope.get("workflow"),"opportunity.quote_workflow",false).revision()))throw invalid();
            fields(binding,"kind");equal(binding.get("kind"),"FOLLOWUP_ATTEMPT");attemptScope=scope;
        } else if(Set.of("END_QUOTE_NEGOTIATION","START_QUOTE_PREPARATION","SAVE_QUOTE_DRAFT","FORM_QUOTE","REQUEST_QUOTE_APPROVAL","RECORD_QUOTE_DECISION","RECORD_QUOTE_DELIVERY","RECORD_QUOTE_RESPONSE").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","confirmation","draft","quote","workflow");
            equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            for(var entry:Map.of("confirmation","opportunity.customer_requirement_confirmation","draft","opportunity.quote_draft","workflow","opportunity.quote_workflow").entrySet())if(scope.get(entry.getKey())!=null){var exact=selector(scope.get(entry.getKey()),entry.getValue(),false);if(!Long.valueOf(0).equals(exact.revision()))throw invalid();}
            if(scope.get("quote")!=null)selector(scope.get("quote"),"opportunity.quote_revision",true);
            fields(binding,"kind");equal(binding.get("kind"),"QUOTES");quotesScope=scope;
        } else if(Set.of("OPEN_OPPORTUNITY_MATERIAL_UPLOAD","ACCEPT_OPPORTUNITY_MATERIAL").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","confirmation","previous","upload");
            equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            for(var entry:Map.of("confirmation","opportunity.customer_requirement_confirmation","previous","opportunity.material_version","upload","evidence.material_upload_basis").entrySet())if(scope.get(entry.getKey())!=null){var exact=selector(scope.get(entry.getKey()),entry.getValue(),false);if(!Long.valueOf(0).equals(exact.revision()))throw invalid();}
            if("ACCEPT_OPPORTUNITY_MATERIAL".equals(commandType)!=(scope.get("upload")!=null))throw invalid();
            fields(binding,"kind");equal(binding.get("kind"),"MATERIALS");materialsScope=scope;
        } else if(Set.of("SAVE_OPPORTUNITY_CUSTOMER_DRAFT","CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","draft","confirmation");
            equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);
            uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            if(scope.get("draft")!=null)parsedDraft=selector(scope.get("draft"),"opportunity.customer_requirement_draft",false);
            if("CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS".equals(commandType)&&parsedDraft==null)throw invalid();
            if(scope.get("confirmation")!=null)selector(scope.get("confirmation"),"opportunity.customer_requirement_confirmation",false);
            fields(binding,"kind");equal(binding.get("kind"),"CUSTOMER_REQUIREMENTS");customerRequirementsScope=scope;
        } else if(Set.of("TRANSFER_OPPORTUNITY_RESPONSIBILITY","RECORD_OPPORTUNITY_OWNER_COORDINATION").contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","exception","basis","task","wait");equal(scope.get("profile"),"R2_"+commandType+"_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);selector(scope.get("exception"),"opportunity.owner_exception",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            if(scope.get("task")!=null)selector(scope.get("task"),"responsibility.task_occurrence",false);if(scope.get("wait")!=null){if(scope.get("task")==null)throw invalid();selector(scope.get("wait"),"responsibility.wait_receipt",true);}
            fields(binding,"kind");equal(binding.get("kind"),"OWNER_EXCEPTION");ownerExceptionScope=scope;
        } else if("CLOSE_OPPORTUNITY".equals(commandType)) {
            fields(scope,"profile","tenantId","commandType","principalId","appointmentId","opportunity","basis","task","wait");equal(scope.get("profile"),"R2_CLOSE_OPPORTUNITY_SCOPE_V1");equal(scope.get("commandType"),commandType);uuid(scope.get("principalId"));uuid(scope.get("appointmentId"));
            parsedLead=selector(scope.get("opportunity"),"opportunity.opportunity",false);
            var basis=object(scope.get("basis"));if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.get("type")))throw invalid();selector(basis,(String)basis.get("type"),false);
            if(scope.get("task")!=null)selector(scope.get("task"),"responsibility.task_occurrence",false);if(scope.get("wait")!=null){if(scope.get("task")==null)throw invalid();selector(scope.get("wait"),"responsibility.wait_receipt",true);}
            fields(binding,"kind");equal(binding.get("kind"),"OPPORTUNITY_CLOSURE");opportunityClosureScope=scope;
        } else if("CAPTURE_LEAD".equals(commandType)) {
            fields(scope,"profile","tenantId","sourceAccountCode","sourceRecordKeyDigest");equal(scope.get("profile"),"R1_CAPTURE_SCOPE_V1");
            fields(binding,"kind");equal(binding.get("kind"),"CAPTURE");
            parsedAccount=string(scope.get("sourceAccountCode"));if(parsedAccount.isEmpty()||parsedAccount.length()>128)throw invalid();
            parsedKey=hash(scope.get("sourceRecordKeyDigest"));
        } else if("SAVE_ACTION_DRAFT".equals(commandType)) {
            fields(scope,"profile","tenantId","taskId","actionCode");equal(scope.get("profile"),"R1_DRAFT_SCOPE_V1");
            parsedTask=uuid(scope.get("taskId"));parsedAction=string(scope.get("actionCode"));if(!PRIMARY.contains(parsedAction)&&!"RECORD_OPPORTUNITY_PROGRESS".equals(parsedAction))throw invalid();
            fields(binding,"kind","lead","taskRevision","draft");equal(binding.get("kind"),"DRAFT");
            parsedLead=selector(binding.get("lead"),"RECORD_OPPORTUNITY_PROGRESS".equals(parsedAction)?"opportunity.opportunity":"lead.lead",false);parsedRevision=revision(binding.get("taskRevision"));
            if(binding.get("draft")!=null)parsedDraft=selector(binding.get("draft"),"responsibility.action_draft",false);
        } else if("RECORD_OPPORTUNITY_PROGRESS".equals(commandType)) {
            fields(scope,"profile","tenantId","commandType","taskId","subject");equal(scope.get("profile"),"R2_OPPORTUNITY_COMMAND_SCOPE_V1");equal(scope.get("commandType"),commandType);
            parsedTask=uuid(scope.get("taskId"));parsedLead=selector(scope.get("subject"),"opportunity.opportunity",false);parsedAction=commandType;
            fields(binding,"kind","evidence");equal(binding.get("kind"),"TASK");if(binding.get("evidence")!=null)throw invalid();
        } else if(PRIMARY.contains(commandType)) {
            fields(scope,"profile","tenantId","commandType","taskId","lead","bindings");equal(scope.get("profile"),"R1_COMMAND_SCOPE_V1");equal(scope.get("commandType"),commandType);
            parsedTask=uuid(scope.get("taskId"));parsedLead=selector(scope.get("lead"),"lead.lead",false);parsedAction=commandType;
            validateAttempted(commandType,scope.get("bindings"));fields(binding,"kind","evidence");equal(binding.get("kind"),"TASK");
            if(binding.get("evidence")!=null) {
                if(!"RECORD_CONTACT_RESULT".equals(commandType))throw invalid();
                var pair=object(binding.get("evidence"));fields(pair,"submission","binding");
                parsedSubmission=selector(pair.get("submission"),"evidence.evidence_submission",true);
                parsedBinding=selector(pair.get("binding"),"evidence.evidence_binding",false);
            }
        } else throw invalid();
        task=parsedTask;account=parsedAccount;sourceKey=parsedKey;action=parsedAction;lead=parsedLead;draft=parsedDraft;taskRevision=parsedRevision;submission=parsedSubmission;evidenceBinding=parsedBinding;
    }
    private static void validateAttempted(String type,Object value) {
        var expected=new TreeSet<>(switch(type) {
            case "RESOLVE_DUPLICATE_LEAD" -> Set.of("candidateLeadId","candidateLeadRevision","partyId","partyRevision");
            case "ASSIGN_LEAD" -> Set.of("selectedOwnerAppointmentId");
            case "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST" -> Set.of("causalDecisionId","causalDecisionHash");
            case "RECORD_CONTACT_RESULT" -> Set.of("leadAssignmentId","leadAssignmentRevision");
            case "REVIEW_LEAD_VALIDITY" -> Set.of("triggeringContactResultId","triggeringContactResultHash");
            default -> Set.<String>of();
        });
        if(!(value instanceof List<?> items)||items.size()!=expected.size())throw invalid();
        int index=0;for(String name:expected) {var item=object(items.get(index++));fields(item,"name","value");equal(item.get("name"),name);
            if(name.endsWith("Id"))uuid(item.get("value"));else if(name.endsWith("Revision"))revision(item.get("value"));else hash(item.get("value"));}
    }
    public static Subject selector(Object value,String type,boolean hashed) {
        var fields=object(value);ReceiptAuditJson.fields(fields,"type","id",hashed?"hash":"revision");equal(fields.get("type"),type);
        return new Subject(type,uuid(fields.get("id")),hashed?null:revision(fields.get("revision")),hashed?hash(fields.get("hash")):null);
    }
    private static void equal(Object value,String expected){if(!expected.equals(value))throw invalid();}
    private static String string(Object value){if(!(value instanceof String text))throw invalid();return text;}
    private static UUID uuid(Object value){String text=string(value);try{var id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}catch(IllegalArgumentException bad){throw invalid();}}
    private static Long revision(Object value){if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw invalid();long n=((Number)value).longValue();if(n<0||n>9007199254740991L)throw invalid();return n;}
    private static String hash(Object value){String text=string(value);try{byte[] bytes=Base64.getUrlDecoder().decode(text);if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text))throw invalid();return text;}catch(IllegalArgumentException bad){throw invalid();}}
    public String commandType(){return commandType;}public UUID tenantId(){return tenant;}public UUID taskId(){return task;}
    private static void term(Map<String,Object> fields,String prefix){var start=java.time.Instant.parse(string(fields.get(prefix+"From")));if(fields.get(prefix+"Until")!=null&&!java.time.Instant.parse(string(fields.get(prefix+"Until"))).isAfter(start))throw invalid();}
    public Map<String,Object> opportunityClosureScope(){return opportunityClosureScope;}
    public Map<String,Object> materialsScope(){return materialsScope;}
    public Map<String,Object> customerRequirementsScope(){return customerRequirementsScope;}
    public Map<String,Object> ownerExceptionScope(){return ownerExceptionScope;}
    public boolean identity(){return identityScope!=null;}
    public Map<String,Object> identityScope(){return identityScope;}
    public Subject identityTarget(){return identityTarget;}
    public Subject identityAnchor(){return identityAnchor;}
    public String sourceAccountCode(){return account;}public String sourceRecordKeyDigest(){return sourceKey;}public String actionCode(){return action;}
    public Subject lead(){return lead;}public Subject draft(){return draft;}public Long taskRevision(){return taskRevision;}
    public Subject submission(){return submission;}public Subject evidenceBinding(){return evidenceBinding;}public byte[] scopeDigest(){return scopeDigest.clone();}
    @Override public String toString(){return "ReceiptRecoveryMetadata[restricted]";}
}
