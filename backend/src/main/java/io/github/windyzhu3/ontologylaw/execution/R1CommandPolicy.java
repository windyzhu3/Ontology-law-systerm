package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;

/** Closed command-specific authorization. Business eligibility/CAS is deliberately not replay authorization. */
public final class R1CommandPolicy {
    private record DraftPolicy(CommandEnvelope.Type command,String slot,String code,String schema) {}
    private static final Map<String,DraftPolicy> DRAFTS=Map.of(
            "RESOLVE_LEAD_DUPLICATE",new DraftPolicy(CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,"SOURCE_INTAKE_OWNER","LEAD_INGRESS_RESOLVE","ResolveDuplicateLeadV1"),
            "COMPLETE_LEAD_INGRESS",new DraftPolicy(CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,"SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE","CompleteLeadIngressV1"),
            "ASSIGN_LEAD",new DraftPolicy(CommandEnvelope.Type.ASSIGN_LEAD,"ROUTING_SUPERVISOR","LEAD_ASSIGN","AssignLeadV1"),
            "RESOLVE_LEAD_ROUTING_GAP",new DraftPolicy(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordRoutingDispositionV1"),
            "ACK_SOURCE_INTAKE_STOP_REQUEST",new DraftPolicy(CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,"SOURCE_INTAKE_OWNER","SOURCE_INTAKE_REQUEST_ACK","AcknowledgeSourceIntakeStopRequestV1"),
            "CONTACT_LEAD",new DraftPolicy(CommandEnvelope.Type.RECORD_CONTACT_RESULT,"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER","RecordContactResultV1"),
            "REVIEW_LEAD_VALIDITY",new DraftPolicy(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"ROUTING_SUPERVISOR","LEAD_VALIDITY_REVIEW","ReviewLeadValidityV1"));
    private final AuthorizationService authorization;
    private final R1AuthorizationFacts facts;
    public R1CommandPolicy(AuthorizationService authorization,R1AuthorizationFacts facts) {
        this.authorization=Objects.requireNonNull(authorization);this.facts=facts;
    }
    public AuthorizationSnapshot authorize(Connection c,CommandEnvelope e,CommandHandler.Context context,boolean finalCheck) throws SQLException {
        var request=context.authorization();
        boolean dedicated=dedicated(e.type());
        // The shared lock precedes the clock and ALL current Owner/organization reads, not only Grant evaluation.
        if(finalCheck && dedicated)authorization.lockForEvaluation(c,e.actor().tenantId());
        var first=authorization.evaluate(c,request,finalCheck);
        var checks=new ArrayList<AuthorizationSnapshot>();checks.add(first);
        String failure=null;String ownerEvidence="";
        if(!context.scope().tenantId().equals(e.actor().tenantId()) || context.scope().type()!=e.type() || !request.actor().equals(e.actor()))failure="NOT_AUTHORIZED";
        else if(!dedicated) {
            String expected=primaryPolicy(e.type());
            if(expected==null || request.requirement().path()==Path.SYSTEM || !expected.equals(request.requirement().slot()+":"+request.requirement().authorityCode()))failure="NOT_AUTHORIZED";
        } else if(facts==null || context.binding()==null)failure="NOT_AUTHORIZED";
        else {
            try {
                if(e.type()==CommandEnvelope.Type.CAPTURE_LEAD) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Capture b))return merge(first,checks,"NOT_AUTHORIZED","missing capture binding",false);
                    var current=facts.capture(c,e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest());
                    boolean matches=current!=null && current.organization().equals(b.organization()) && request.subject().equals(b.organization())
                            && request.scopeOrganizationId().equals(current.organization().id())
                            && context.scope().canonical().equals(CommandScope.capture(e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest()).canonical())
                            && e.payload() instanceof Map<?,?> payload && b.sourceAccountCode().equals(payload.get("sourceAccountCode"))
                            && human(request,"SOURCE_INTAKE_OWNER","LEAD_CAPTURE");
                    ownerEvidence="binding="+b+";scope="+context.scope().canonical()+";facts="+current;
                    if(!matches)failure="NOT_AUTHORIZED";
                    if(current!=null && current.existingLead()!=null)add(c,checks,request,current.existingLead());
                } else {
                    UUID taskId;Subject boundLead;long taskRevision;
                    if(e.type()==CommandEnvelope.Type.SAVE_ACTION_DRAFT && context.binding() instanceof CommandAuthorizationBinding.Draft b) {
                        taskId=b.taskId();boundLead=b.lead();taskRevision=b.taskRevision();
                    } else if(e.type().recovery() && context.binding() instanceof CommandAuthorizationBinding.Recovery b) {
                        taskId=b.taskId();boundLead=b.lead();taskRevision=b.taskRevision();
                        if(!context.scope().canonical().equals(CommandScope.reopen(e.actor().tenantId(),e.type(),taskId,b.waitReceiptId(),b.waitReceiptHash()).canonical()))failure="NOT_AUTHORIZED";
                        if(!(e.payload() instanceof Map<?,?> payload) || !taskId.toString().equals(payload.get("taskId"))
                                || !b.waitReceiptId().toString().equals(payload.get("waitReceiptId")) || !b.waitReceiptHash().equals(payload.get("waitReceiptHash")))failure="NOT_AUTHORIZED";
                        // Type/profile/latest WaitReceipt/revision/dueCutoff are NEW-only eligibility in recoveryEligibility.
                    } else return merge(first,checks,"NOT_AUTHORIZED","wrong Task binding",e.type().recovery());
                    var task=facts.task(c,e.actor().tenantId(),taskId,first.checkedAt());
                    ownerEvidence="binding="+context.binding()+";scope="+context.scope().canonical()+";facts="+task;
                    if(task==null || !task.selector().id().equals(taskId) || task.owner()==null || !task.owner().active() || !task.lead().equals(boundLead)
                            || !request.scopeOrganizationId().equals(task.owner().organizationId())
                            || !request.subject().equals(new Subject("responsibility.task_occurrence",taskId,taskRevision,null)))failure="NOT_AUTHORIZED";
                    else {
                        if(e.type()==CommandEnvelope.Type.SAVE_ACTION_DRAFT) {
                            var b=(CommandAuthorizationBinding.Draft)context.binding();var policy=DRAFTS.get(task.taskType());
                            UUID represented=request.requirement().path()==Path.DELEGATED?e.actor().onBehalfAppointmentId():e.actor().appointmentId();
                            if(policy==null || !policy.command().name().equals(task.primaryCommand()) || policy.command()!=b.actionCode()
                                    || !task.ownerAppointmentId().equals(represented) || !human(request,policy.slot(),policy.code())
                                    || b.schemaVersion()!=1 || !context.scope().canonical().equals(CommandScope.draft(e.actor().tenantId(),taskId,b.actionCode()).canonical())
                                    || !(e.payload() instanceof Map<?,?> payload) || !b.actionCode().name().equals(payload.get("actionCode"))
                                    || !(payload.get("schemaVersion") instanceof Number version) || version.intValue()!=1 || version.doubleValue()!=1.0)failure="NOT_AUTHORIZED";
                            if((b.draftId()==null)!=(b.draftRevision()==null))failure="NOT_AUTHORIZED";
                            if(task.draft()!=null) {
                                var d=task.draft();
                                if(policy==null || !d.taskId().equals(taskId) || !d.actionCode().equals(task.primaryCommand()) || !d.schemaCode().equals(policy.schema()) || d.schemaVersion()!=1
                                        || b.draftId()!=null && (!b.draftId().equals(d.selector().id()) || b.draftRevision()==null || b.draftRevision()<0 || b.draftRevision()>d.selector().revision()))failure="NOT_AUTHORIZED";
                            } else if(b.draftId()!=null || b.draftRevision()!=null)failure="NOT_AUTHORIZED";
                        } else {
                            String code=e.type()==CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER";
                            if(request.requirement().path()!=Path.SYSTEM || !"SYSTEM_RECOVERY".equals(request.requirement().slot()) || !code.equals(request.requirement().authorityCode())
                                    || e.actor().onBehalfAppointmentId()!=null)failure="NOT_AUTHORIZED";
                        }
                        // Retain the original attempt's selectors and inspect current selectors too. Own CAS is not stale authorization.
                        add(c,checks,request,task.selector());add(c,checks,request,task.lead());add(c,checks,request,task.currentLead());
                    }
                }
            } catch(IllegalArgumentException | NullPointerException invalidBinding) { failure="NOT_AUTHORIZED"; }
        }
        return merge(first,checks,failure,ownerEvidence,e.type().recovery());
    }
    private void add(Connection c,List<AuthorizationSnapshot> checks,Request original,Subject subject) throws SQLException {
        if(checks.stream().noneMatch(s->s.request().subject().equals(subject)))
            checks.add(authorization.evaluate(c,new Request(original.actor(),subject,original.scopeOrganizationId(),original.requirement()),false));
    }
    private static boolean human(Request r,String slot,String code) {
        return (r.requirement().path()==Path.DIRECT || r.requirement().path()==Path.DELEGATED)
                && slot.equals(r.requirement().slot()) && code.equals(r.requirement().authorityCode());
    }
    private static AuthorizationSnapshot merge(AuthorizationSnapshot original,List<AuthorizationSnapshot> checks,String failure,String facts,boolean recovery) {
        for(var check:checks)if(!check.allowed()) {failure=check.rejectionCode();break;}
        if(recovery && "APPOINTMENT_INACTIVE".equals(failure))failure="NOT_AUTHORIZED";
        String evidence="R1_COMMAND_POLICY_V1\n"+facts+"\n"+String.join("\n",checks.stream().map(AuthorizationSnapshot::evidence).toList())+"\n"+(failure==null?"ALLOW":failure);
        return new AuthorizationSnapshot(original.request(),checks.getLast().checkedAt(),failure==null,failure,original.authorityFact(),evidence,CanonicalJson.digest(evidence));
    }
    static boolean dedicated(CommandEnvelope.Type type) {return type==CommandEnvelope.Type.CAPTURE_LEAD || type==CommandEnvelope.Type.SAVE_ACTION_DRAFT || type.recovery();}
    static AuthorizationSnapshot retainDenial(AuthorizationSnapshot denied,AuthorizationSnapshot current) {
        String evidence=denied.evidence()+"\nR1_AFTER_ROLLBACK_FINAL_CHECK\n"+current.evidence();
        return new AuthorizationSnapshot(denied.request(),current.checkedAt(),false,denied.rejectionCode(),denied.authorityFact(),evidence,CanonicalJson.digest(evidence));
    }
    static String primaryPolicy(CommandEnvelope.Type type) {
        return DRAFTS.values().stream().filter(p->p.command()==type).map(p->p.slot()+":"+p.code()).findFirst().orElse(null);
    }
}
