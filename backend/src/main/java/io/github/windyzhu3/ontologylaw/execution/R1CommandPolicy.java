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
            "RESOLVE_SOURCE_REQUEST",new DraftPolicy(CommandEnvelope.Type.RECORD_SOURCE_REQUEST_CONTINUATION,"ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordSourceRequestContinuationV1"),
            "RESOLVE_LEAD_ROUTING_GAP",new DraftPolicy(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordRoutingDispositionV1"),
            "ACK_SOURCE_INTAKE_STOP_REQUEST",new DraftPolicy(CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,"SOURCE_INTAKE_OWNER","SOURCE_INTAKE_REQUEST_ACK","AcknowledgeSourceIntakeStopRequestV1"),
            "CONTACT_LEAD",new DraftPolicy(CommandEnvelope.Type.RECORD_CONTACT_RESULT,"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER","RecordContactResultV1"),
            "REVIEW_LEAD_VALIDITY",new DraftPolicy(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"ROUTING_SUPERVISOR","LEAD_VALIDITY_REVIEW","ReviewLeadValidityV1"),
            "PROGRESS_OPPORTUNITY",new DraftPolicy(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,"OPPORTUNITY_OWNER","SALES_OPPORTUNITY_OWNER","RecordOpportunityProgressV1"));
    private final AuthorizationService authorization;
    private final R1AuthorizationFacts facts;
    public R1CommandPolicy(AuthorizationService authorization,R1AuthorizationFacts facts) {
        this.authorization=Objects.requireNonNull(authorization);this.facts=facts;
    }
    public AuthorizationSnapshot authorize(Connection c,CommandEnvelope e,CommandHandler.Context context,boolean finalCheck) throws SQLException {
        var request=context.authorization();
        boolean dedicated=dedicated(e.type());
        // The shared lock precedes the clock and ALL current Owner/organization reads, not only Grant evaluation.
        if(finalCheck)authorization.lockForEvaluation(c,e.actor().tenantId());
        var first=authorization.evaluate(c,request,finalCheck);
        var checks=new ArrayList<AuthorizationSnapshot>();checks.add(first);
        String failure=null;String ownerEvidence="";
        if(!context.scope().tenantId().equals(e.actor().tenantId()) || context.scope().type()!=e.type() || !request.actor().equals(e.actor()))failure="NOT_AUTHORIZED";
        else if(!dedicated) {
            String expected=primaryPolicy(e.type());
            if(expected==null || (request.requirement().path()!=Path.DIRECT&&request.requirement().path()!=Path.DELEGATED) || !expected.equals(request.requirement().slot()+":"+request.requirement().authorityCode()) || facts==null || context.scope().taskId()==null)failure="NOT_AUTHORIZED";
            else {
                var task=facts.task(c,e.actor().tenantId(),context.scope().taskId(),first.checkedAt());
                if(e.type()==CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS){var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&e.type().name().equals(receipt.commandType())){var historical=facts.opportunityReceiptTask(c,e.actor().tenantId(),context.scope().taskId(),first.checkedAt());if(historical!=null){task=historical.task();for(var source:historical.protectedFacts())add(c,checks,request,source);if(receipt.outcome().resultFact()!=null)add(c,checks,request,receipt.outcome().resultFact());}}}
                UUID represented=request.requirement().path()==Path.DELEGATED?e.actor().onBehalfAppointmentId():e.actor().appointmentId();
                ownerEvidence="primaryTask="+task;
                if(task==null||task.owner()==null||!task.owner().active()||!task.ownerAppointmentId().equals(represented)
                        ||!task.lead().equals(request.subject())||!request.scopeOrganizationId().equals(taskOrganization(c,e.actor().tenantId(),task))
                        ||!e.type().name().equals(task.primaryCommand())||!DRAFTS.containsKey(task.taskType())||DRAFTS.get(task.taskType()).command()!=e.type())failure="NOT_AUTHORIZED";
                else {add(c,checks,request,task.selector());add(c,checks,request,task.lead());add(c,checks,request,task.currentLead());
                    if(e.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT&&context.binding() instanceof CommandAuthorizationBinding.Contact b){
                        for(var subject:List.of(b.submission(),b.binding())){
                            var check=authorization.evaluate(c,new Request(request.actor(),subject,request.scopeOrganizationId(),request.requirement()),false);
                            checks.add(check.allowed()?check:new AuthorizationSnapshot(check.request(),check.checkedAt(),false,"NOT_FOUND",check.authorityFact(),check.evidence(),check.digest()));
                        }
                    }
                }
            }
        } else if(facts==null || context.binding()==null)failure="NOT_AUTHORIZED";
        else {
            try {
                if(e.type()==CommandEnvelope.Type.REPAIR_SUPERSEDED_OPPORTUNITY_TASK){
                    if(!(context.binding() instanceof CommandAuthorizationBinding.OpportunityRepair b))return merge(first,checks,"NOT_AUTHORIZED","missing opportunity repair binding",true);
                    var current=facts.opportunity(c,e.actor().tenantId(),b.opportunity().id(),first.checkedAt());var task=facts.task(c,e.actor().tenantId(),b.task().id(),first.checkedAt());
                    if(current==null||task==null||!task.lead().id().equals(b.opportunity().id())||!"PROGRESS_OPPORTUNITY".equals(task.taskType())
                        ||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(facts.opportunityOrganization(c,e.actor().tenantId(),b.opportunity().id()))
                        ||!context.scope().canonical().equals(CommandScope.opportunityRepair(e.actor().tenantId(),b).canonical())||!b.payload().equals(e.payload())
                        ||e.actor().principalKind()!=PrincipalKind.SERVICE||e.actor().onBehalfAppointmentId()!=null||e.actor().onBehalfPrincipalId()!=null||request.requirement().path()!=Path.SYSTEM
                        ||!"R2_OPPORTUNITY_SYSTEM".equals(request.requirement().slot())||!"OPPORTUNITY_TASK_RECOVER".equals(request.requirement().authorityCode()))failure="NOT_AUTHORIZED";
                    else {
                        var subjects=new ArrayList<Subject>(List.of(b.opportunity(),b.task(),b.basis(),current.selector(),task.selector()));subjects.addAll(b.takeover());if(current.source()!=null)subjects.add(current.source());if(b.draft()!=null)subjects.add(b.draft());
                        var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();if(!"responsibility.task_occurrence".equals(result.type())||!b.task().id().equals(result.id()))failure="NOT_AUTHORIZED";else subjects.add(result);}
                        for(var subject:subjects)add(c,checks,request,subject);
                    }
                    ownerEvidence="opportunityRepair="+b;
                } else if(e.type()==CommandEnvelope.Type.RESTORE_SOURCE_REQUEST_TASK){
                    if(!(context.binding() instanceof CommandAuthorizationBinding.SourceRepair b))return merge(first,checks,"NOT_AUTHORIZED","missing source repair binding",true);
                    var current=facts.task(c,e.actor().tenantId(),b.task().id(),first.checkedAt());
                    if(current==null||current.owner()==null||!current.ownerAppointmentId().equals(b.owner())||!"ACK_SOURCE_INTAKE_STOP_REQUEST".equals(current.taskType())
                        ||!"ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST".equals(current.primaryCommand())||!current.lead().id().equals(b.lead().id())
                        ||!request.subject().equals(b.lead())||!request.scopeOrganizationId().equals(current.owner().organizationId())
                        ||!context.scope().canonical().equals(CommandScope.sourceRepair(e.actor().tenantId(),b).canonical())||!b.payload().equals(e.payload())
                        ||e.actor().principalKind()!=PrincipalKind.SERVICE||e.actor().onBehalfAppointmentId()!=null||e.actor().onBehalfPrincipalId()!=null||request.requirement().path()!=Path.SYSTEM
                        ||!"SYSTEM_RECOVERY".equals(request.requirement().slot())||!"ROUTING_REVIEW_TASK_RECOVER".equals(request.requirement().authorityCode()))failure="NOT_AUTHORIZED";
                    else {
                        for(var subject:List.of(b.task(),b.lead(),b.decision(),current.selector(),current.currentLead()))add(c,checks,request,subject);
                        var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());
                        if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();
                            var next="responsibility.task_occurrence".equals(result.type())?facts.task(c,e.actor().tenantId(),result.id(),first.checkedAt()):null;
                            if(next==null||!"RESOLVE_SOURCE_REQUEST".equals(next.taskType())||!next.lead().id().equals(b.lead().id()))failure="NOT_AUTHORIZED";
                            else {add(c,checks,request,result);add(c,checks,request,next.selector());}
                        }
                    }
                    ownerEvidence="sourceRepair="+b;
                } else if(e.type()==CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION){
                    if(!(context.binding() instanceof CommandAuthorizationBinding.ContractRecovery b))return merge(first,checks,"NOT_AUTHORIZED","missing contract recovery binding",true);
                    var current=facts.contractRecovery(c,e.actor().tenantId(),b);if(current==null||current.organization()==null||e.actor().principalKind()!=PrincipalKind.SERVICE||e.actor().onBehalfAppointmentId()!=null||request.requirement().path()!=Path.SYSTEM||!"SYSTEM_RECOVERY".equals(request.requirement().slot())||!"CONTRACT_TASK_RECOVER".equals(request.requirement().authorityCode())||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!context.scope().canonical().equals(CommandScope.contractRecovery(e.actor().tenantId(),b).canonical())||!contractRecoveryPayload(e,b))failure="NOT_AUTHORIZED";
                    else{for(var subject:current.protectedFacts())add(c,checks,request,subject);var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();if(!b.resultType().equals(result.type())||!current.protectedFacts().contains(result))failure="NOT_AUTHORIZED";else add(c,checks,request,result);}}ownerEvidence="contractRecovery="+current;
                } else if(e.type().transfers()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Transfers b))return merge(first,checks,"NOT_AUTHORIZED","missing transfer binding",false);
                    var current=facts.transfers(c,e.actor().tenantId(),b,e.type());
                    if(current==null||current.organization()==null||!transferPayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())||!transferAuthority(e.type()).equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.transfers(e,b).canonical()))failure="NOT_AUTHORIZED";
                    else {
                        for(var subject:current.protectedFacts())add(c,checks,request,subject);
                        var read=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.opportunity(),current.organization(),"OPPORTUNITY_OWNER","CONTRACT_READ");if(read==null||read.requirement().path()!=Path.DIRECT)failure="NOT_AUTHORIZED";else for(var subject:current.protectedFacts())checks.add(authorization.evaluate(c,new Request(e.actor(),subject,current.organization(),read.requirement()),false));
                        var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();if(!transferResultType(e.type()).equals(result.type())||!current.protectedFacts().contains(result))failure="NOT_AUTHORIZED";else add(c,checks,request,result);}
                    }
                    ownerEvidence="transfers="+current;
                } else if(e.type().contracts()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Contracts b))return merge(first,checks,"NOT_AUTHORIZED","missing contract binding",false);
                    var current=facts.contracts(c,e.actor().tenantId(),b);
                    if(current==null||current.organization()==null||!contractPayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
                      ||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())
                      ||!contractAuthority(e.type()).equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.contracts(e,b).canonical()))failure="NOT_AUTHORIZED";
                    else {for(var subject:current.protectedFacts())add(c,checks,request,subject);var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();if(!contractResultType(e.type()).equals(result.type())||!current.protectedFacts().contains(result))failure="NOT_AUTHORIZED";else add(c,checks,request,result);}}
                    if(failure==null&&contractNegotiation(e.type())){
                        var read=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.opportunity(),current.organization(),"OPPORTUNITY_OWNER","CONTRACT_READ");
                        if(read==null||read.requirement().path()!=Path.DIRECT)failure="NOT_AUTHORIZED";
                        else for(var subject:current.protectedFacts())checks.add(authorization.evaluate(c,new Request(e.actor(),subject,current.organization(),read.requirement()),false));
                    }
                    ownerEvidence="contracts="+current;
                } else if(e.type().quotes()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Quotes b))return merge(first,checks,"NOT_AUTHORIZED","missing quote binding",false);
                    var current=facts.quotes(c,e.actor().tenantId(),b);
                    if(current==null||current.organization()==null||!quotePayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null
                      ||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())
                      ||!quoteAuthority(e.type()).equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.quotes(e,b).canonical()))failure="NOT_AUTHORIZED";
                    else {for(var subject:current.protectedFacts())add(c,checks,request,subject);var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();if(!quoteResultType(e.type()).equals(result.type())||!current.protectedFacts().contains(result))failure="NOT_AUTHORIZED";else add(c,checks,request,result);}}
                    ownerEvidence="quotes="+current;
                } else if(e.type().materials()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Materials b))return merge(first,checks,"NOT_AUTHORIZED","missing material binding",false);
                    var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());var current=facts.materials(c,e.actor().tenantId(),b,receipt!=null);
                    if(current==null||current.organization()==null||!Objects.equals(current.owner(),e.actor().appointmentId())||!materialsPayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())||!"MATERIALS_MANAGE".equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.materials(e,b.opportunity(),b.basis(),b.confirmation(),b.previous(),b.upload()).canonical()))failure="NOT_AUTHORIZED";
                    else {
                        for(var subject:current.protectedFacts())add(c,checks,request,subject);
                        if(receipt!=null&&receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();var m=facts.materialFact(c,e.actor().tenantId(),result);if(!materialMatches(e.type(),b,m,e.actor().appointmentId()))failure="NOT_AUTHORIZED";else {var resolved=facts.materials(c,e.actor().tenantId(),new CommandAuthorizationBinding.Materials(b.opportunity(),b.basis(),b.confirmation(),b.previous(),m.upload()),true);if(resolved==null||!Objects.equals(resolved.owner(),e.actor().appointmentId())||!current.organization().equals(resolved.organization()))failure="NOT_AUTHORIZED";else for(var subject:resolved.protectedFacts())add(c,checks,request,subject);}add(c,checks,request,result);}
                    }
                    ownerEvidence="materials="+current;
                } else if(e.type().customerRequirements()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.CustomerRequirements b))return merge(first,checks,"NOT_AUTHORIZED","missing customer requirements binding",false);
                    var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());
                    var current=facts.customerRequirements(c,e.actor().tenantId(),b,receipt!=null);
                    if(current==null||current.organization()==null||!Objects.equals(current.owner(),e.actor().appointmentId())||!customerRequirementsPayload(e,b)
                        ||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())
                        ||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())
                        ||!"CUSTOMER_REQUIREMENTS_MANAGE".equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT
                        ||!context.scope().canonical().equals(CommandScope.customerRequirements(e,b.opportunity(),b.basis(),b.draft(),b.confirmation()).canonical()))failure="NOT_AUTHORIZED";
                    else {
                        for(var subject:current.protectedFacts())add(c,checks,request,subject);
                        if(receipt!=null&&receipt.outcome().resultFact()!=null){
                            var result=receipt.outcome().resultFact();
                            var resultBinding=e.type()==CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT?new CommandAuthorizationBinding.CustomerRequirements(b.opportunity(),b.basis(),result,b.confirmation()):new CommandAuthorizationBinding.CustomerRequirements(b.opportunity(),b.basis(),b.draft(),result);
                            var resultFacts=facts.customerRequirements(c,e.actor().tenantId(),resultBinding,true);
                            if(resultFacts==null||!Objects.equals(resultFacts.owner(),e.actor().appointmentId())||!current.organization().equals(resultFacts.organization()))failure="NOT_AUTHORIZED";
                            else for(var subject:resultFacts.protectedFacts())add(c,checks,request,subject);
                            add(c,checks,request,result);
                        }
                    }
                    ownerEvidence="customerRequirements="+current;
                } else if(e.type().followupAttempts()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.FollowupAttempt b))return merge(first,checks,"NOT_AUTHORIZED","missing attempt binding",false);
                    var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());var current=facts.followupAttempt(c,e.actor().tenantId(),b,receipt==null?null:receipt.outcome().resultFact());
                    if(current==null||current.organization()==null||!current.owner().equals(e.actor().appointmentId())||!attemptPayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())||!attemptAuthority(e.type()).equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.followupAttempt(e,b).canonical()))failure="NOT_AUTHORIZED";
                    else for(var subject:current.protectedFacts())add(c,checks,request,subject);
                    ownerEvidence="followupAttempt="+current;
                } else if(e.type()==CommandEnvelope.Type.CLOSE_OPPORTUNITY) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.OpportunityClosure b))return merge(first,checks,"NOT_AUTHORIZED","missing closure binding",false);
                    var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());var current=facts.opportunityClosure(c,e.actor().tenantId(),b,receipt==null?null:receipt.outcome().resultFact());
                    if(current==null||current.organization()==null||!closurePayload(e,b)||e.actor().principalKind()!=PrincipalKind.HUMAN||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())||!"OPPORTUNITY_CLOSE".equals(request.requirement().authorityCode())||request.requirement().path()!=Path.DIRECT||!context.scope().canonical().equals(CommandScope.opportunityClosure(e,b.opportunity(),b.basis(),b.task(),b.waitReceipt()).canonical()))failure="NOT_AUTHORIZED";
                    else for(var subject:current.protectedFacts())add(c,checks,request,subject);
                    ownerEvidence="opportunityClosure="+current;
                } else if(e.type().ownerException()) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.OwnerException b))return merge(first,checks,"NOT_AUTHORIZED","missing owner exception binding",false);
                    var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());
                    var current=facts.ownerException(c,e.actor().tenantId(),b,receipt==null?null:receipt.outcome().resultFact());
                    boolean observe=e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION;
                    String code=observe?"OPPORTUNITY_OWNER_EXCEPTION_DISCOVER":"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE";
                    if(current==null||current.organization()==null||!ownerExceptionPayload(e,b)||e.actor().onBehalfAppointmentId()!=null||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(current.organization())
                        ||!"OPPORTUNITY_OWNER".equals(request.requirement().slot())||!code.equals(request.requirement().authorityCode())
                        ||request.requirement().path()!=(observe?Path.SYSTEM:Path.DIRECT)||e.actor().principalKind()!=(observe?PrincipalKind.SERVICE:PrincipalKind.HUMAN)
                        ||!context.scope().canonical().equals(CommandScope.ownerException(e,b.opportunity(),b.exception(),b.basis(),b.task(),b.waitReceipt()).canonical()))failure="NOT_AUTHORIZED";
                    else for(var subject:current.protectedFacts()){
                        add(c,checks,request,subject);
                        if(!observe){var read=R1AuthorityReader.databaseBacked().select(c,e.actor(),subject,current.organization(),"OPPORTUNITY_OWNER","OPPORTUNITY_OWNER_EXCEPTION_READ");if(read==null||read.requirement().path()!=Path.DIRECT)failure="NOT_AUTHORIZED";else checks.add(authorization.evaluate(c,read,false));}
                    }
                    ownerEvidence="ownerException="+current;
                } else if(e.type()==CommandEnvelope.Type.CAPTURE_LEAD) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.Capture b))return merge(first,checks,"NOT_AUTHORIZED","missing capture binding",false);
                    var current=facts.capture(c,e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest());
                    boolean matches=current!=null && current.organization().equals(b.organization()) && request.subject().equals(b.organization())
                            && request.scopeOrganizationId().equals(current.organization().id())
                            && context.scope().canonical().equals(CommandScope.capture(e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest()).canonical())
                            && e.payload() instanceof Map<?,?> payload && b.sourceAccountCode().equals(payload.get("sourceAccountCode"))
                            && (e.actor().principalKind()==PrincipalKind.HUMAN?human(request,"SOURCE_INTAKE_OWNER","LEAD_CAPTURE"):
                                request.requirement().path()==Path.SYSTEM && e.actor().onBehalfPrincipalId()==null
                                && "SOURCE_INTAKE_OWNER".equals(request.requirement().slot()) && "LEAD_CAPTURE".equals(request.requirement().authorityCode())
                                && facts.serviceSourceAllowed(c,e.actor(),b.sourceAccountCode()));
                    ownerEvidence="binding="+b+";scope="+context.scope().canonical()+";facts="+current;
                    if(!matches)failure="NOT_AUTHORIZED";
                    if(current!=null && current.existingLead()!=null)add(c,checks,request,current.existingLead());
                } else if(e.type()==CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK) {
                    if(!(context.binding() instanceof CommandAuthorizationBinding.OpportunityActivation b))return merge(first,checks,"NOT_AUTHORIZED","missing activation binding",true);
                    var current=facts.opportunity(c,e.actor().tenantId(),b.opportunity().id(),first.checkedAt());
                    ownerEvidence="binding="+b+";facts="+current;
                    if(current==null||current.owner()==null||!current.owner().active()||current.source()==null
                            ||!request.subject().equals(b.opportunity())||!request.scopeOrganizationId().equals(facts.opportunityOrganization(c,e.actor().tenantId(),b.opportunity().id()))
                            ||!context.scope().canonical().equals(CommandScope.opportunityActivation(e.actor().tenantId(),b.opportunity()).canonical())
                            ||!(e.payload() instanceof Map<?,?> payload)||!payload.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision"))
                            ||!sameUuid(payload.get("opportunityId"),b.opportunity().id())||!sameRevision(payload.get("expectedOpportunityRevision"),b.opportunity().revision())
                            ||e.actor().principalKind()!=PrincipalKind.SERVICE||e.actor().onBehalfAppointmentId()!=null||request.requirement().path()!=Path.SYSTEM
                            ||!"R2_OPPORTUNITY_SYSTEM".equals(request.requirement().slot())||!"OPPORTUNITY_TASK_ACTIVATE".equals(request.requirement().authorityCode()))failure="NOT_AUTHORIZED";
                    else {
                        var owner=current.owner();var registration=AuthorizationIdentityReader.databaseBacked().registration(c,e.actor().tenantId(),owner.appointmentId());
                        var ownerActor=new Actor(e.actor().tenantId(),owner.principalId(),owner.appointmentId(),null,null,PrincipalKind.HUMAN);
                        var path=R1AuthorityReader.databaseBacked().select(c,ownerActor,current.selector(),request.scopeOrganizationId(),"OPPORTUNITY_OWNER","SALES_OPPORTUNITY_OWNER");
                        if(registration==null||registration.principalKind()!=PrincipalKind.HUMAN||path==null)failure="NOT_AUTHORIZED";
                        var subjects=new ArrayList<Subject>(List.of(b.opportunity(),current.selector(),current.source()));if(current.initialTask()!=null)subjects.add(current.initialTask());
                        // Replay may disclose an older revision than the current initial Task (including NO_CHANGE).
                        var persisted=CommandReceiptReader.databaseBacked().read(c,e.actor().tenantId(),e.commandId());
                        if(persisted!=null&&e.type().name().equals(persisted.commandType())&&persisted.outcome().resultFact()!=null)subjects.add(persisted.outcome().resultFact());
                        for(var subject:subjects){add(c,checks,request,subject);if(path!=null)checks.add(authorization.evaluate(c,new Request(ownerActor,subject,request.scopeOrganizationId(),path.requirement()),false));}
                    }
                } else {
                    UUID taskId;Subject boundLead;long taskRevision;
                    if(e.type()==CommandEnvelope.Type.SAVE_ACTION_DRAFT && context.binding() instanceof CommandAuthorizationBinding.Draft b) {
                        taskId=b.taskId();boundLead=b.lead();taskRevision=b.taskRevision();
                    } else if(e.type().recovery() && context.binding() instanceof CommandAuthorizationBinding.Recovery b) {
                        taskId=b.taskId();boundLead=b.lead();taskRevision=b.taskRevision();
                        var expectedScope=e.type()==CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS
                                ?CommandScope.opportunityRecovery(e.actor().tenantId(),taskId,b.lead(),new Subject("responsibility.wait_receipt",b.waitReceiptId(),null,b.waitReceiptHash()),recoveryProgress(c,e,first.checkedAt()))
                                :CommandScope.reopen(e.actor().tenantId(),e.type(),taskId,b.waitReceiptId(),b.waitReceiptHash());
                        if(!context.scope().canonical().equals(expectedScope.canonical()))failure="NOT_AUTHORIZED";
                        if(e.type()==CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS){
                            var payload=(Map<?,?>)e.payload();
                            if(!sameUuid(payload.get("opportunityId"),b.lead().id())
                                    ||!sameRevision(payload.get("expectedOpportunityRevision"),b.lead().revision())
                                    ||!sameRevision(payload.get("expectedTaskRevision"),b.taskRevision()))failure="NOT_AUTHORIZED";
                        }
                        if(!(e.payload() instanceof Map<?,?> payload) || !sameUuid(payload.get("taskId"),taskId)
                                || !sameUuid(payload.get("waitReceiptId"),b.waitReceiptId()) || !b.waitReceiptHash().equals(payload.get("waitReceiptHash")))failure="NOT_AUTHORIZED";
                        // Type/profile/latest WaitReceipt/revision/dueCutoff are NEW-only eligibility in recoveryEligibility.
                    } else return merge(first,checks,"NOT_AUTHORIZED","wrong Task binding",e.type().recovery());
                    var task=facts.task(c,e.actor().tenantId(),taskId,first.checkedAt());
                    if(e.type()==CommandEnvelope.Type.SAVE_ACTION_DRAFT&&context.binding() instanceof CommandAuthorizationBinding.Draft draft&&draft.actionCode()==CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS){var receipt=facts.commandReceipt(c,e.actor().tenantId(),e.commandId());if(receipt!=null&&e.type().name().equals(receipt.commandType())){var historical=facts.opportunityReceiptTask(c,e.actor().tenantId(),taskId,first.checkedAt());if(historical!=null){task=historical.task();for(var source:historical.protectedFacts())add(c,checks,request,source);if(receipt.outcome().resultFact()!=null)add(c,checks,request,receipt.outcome().resultFact());}}}
                    ownerEvidence="binding="+context.binding()+";scope="+context.scope().canonical()+";facts="+task;
                    if(task==null || !task.selector().id().equals(taskId) || task.owner()==null || !task.owner().active() || !task.lead().equals(boundLead)
                            || !request.scopeOrganizationId().equals(taskOrganization(c,e.actor().tenantId(),task))
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
                            boolean opportunity=e.type()==CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS;
                            String code=opportunity?"OPPORTUNITY_TASK_RECOVER":e.type()==CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER";
                            if(request.requirement().path()!=Path.SYSTEM || !(opportunity?"R2_OPPORTUNITY_SYSTEM":"SYSTEM_RECOVERY").equals(request.requirement().slot()) || !code.equals(request.requirement().authorityCode())
                                    || e.actor().onBehalfAppointmentId()!=null)failure="NOT_AUTHORIZED";
                            if(opportunity){
                                var b=(CommandAuthorizationBinding.Recovery)context.binding();
                                var source=facts.opportunitySource(c,e.actor().tenantId(),task.lead().id());
                                var registration=AuthorizationIdentityReader.databaseBacked().registration(c,e.actor().tenantId(),task.ownerAppointmentId());
                                if(source==null||!("PROGRESS_OPPORTUNITY".equals(task.taskType())&&"RECORD_OPPORTUNITY_PROGRESS".equals(task.primaryCommand())||"RECORD_QUOTE_REPLY".equals(task.taskType())&&"RECORD_QUOTE_RESPONSE".equals(task.primaryCommand()))
                                        ||registration==null||registration.principalKind()!=PrincipalKind.HUMAN)failure="NOT_AUTHORIZED";
                                else {
                                    var subjects=List.of(task.selector(),task.lead(),task.currentLead(),source,recoveryProgress(c,e,first.checkedAt()),new Subject("responsibility.wait_receipt",b.waitReceiptId(),null,b.waitReceiptHash()));
                                    var ownerActor=new Actor(e.actor().tenantId(),task.owner().principalId(),task.ownerAppointmentId(),null,null,PrincipalKind.HUMAN);
                                    var ownerPath=R1AuthorityReader.databaseBacked().select(c,ownerActor,task.currentLead(),request.scopeOrganizationId(),"OPPORTUNITY_OWNER","RECORD_QUOTE_REPLY".equals(task.taskType())?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER");
                                    if(ownerPath==null)failure="NOT_AUTHORIZED";
                                    for(var subject:subjects){add(c,checks,request,subject);if(ownerPath!=null)checks.add(authorization.evaluate(c,new Request(ownerActor,subject,request.scopeOrganizationId(),ownerPath.requirement()),false));}
                                }
                            }
                        }
                        // Retain the original attempt's selectors and inspect current selectors too. Own CAS is not stale authorization.
                        add(c,checks,request,task.selector());add(c,checks,request,task.lead());add(c,checks,request,task.currentLead());
                    }
                }
            } catch(IllegalArgumentException | NullPointerException invalidBinding) { failure="NOT_AUTHORIZED"; }
        }
        return merge(first,checks,failure,ownerEvidence,e.type().internalMaintenance());
    }
    private UUID taskOrganization(Connection c,UUID tenant,R1AuthorizationFacts.Task task)throws SQLException {
        return "opportunity.opportunity".equals(task.lead().type())
                ?facts.opportunityOrganization(c,tenant,task.lead().id()):task.owner().organizationId();
    }
    private void add(Connection c,List<AuthorizationSnapshot> checks,Request original,Subject subject) throws SQLException {
        if(checks.stream().noneMatch(s->s.request().subject().equals(subject)))
            checks.add(authorization.evaluate(c,new Request(original.actor(),subject,original.scopeOrganizationId(),original.requirement()),false));
    }
    private static boolean human(Request r,String slot,String code) {
        return (r.requirement().path()==Path.DIRECT || r.requirement().path()==Path.DELEGATED)
                && slot.equals(r.requirement().slot()) && code.equals(r.requirement().authorityCode());
    }
    private static boolean sameUuid(Object value,UUID expected){return value instanceof String text&&expected.toString().equalsIgnoreCase(text);}
    private static boolean sameRevision(Object value,long expected){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==expected;}
    private static boolean ownerExceptionPayload(CommandEnvelope e,CommandAuthorizationBinding.OwnerException b){
        if(!(e.payload() instanceof Map<?,?> p)||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        if(e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION)return p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision"))&&b.exception()==null&&b.basis()==null&&b.task()==null&&b.waitReceipt()==null;
        String choice=e.type()==CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY?"receiverAppointmentId":"reviewDueAt";
        return p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","exceptionId","expectedExceptionRevision","expectedBasis","expectedTask","expectedWait","reason",choice))&&b.exception()!=null&&b.basis()!=null
            &&sameUuid(p.get("exceptionId"),b.exception().id())&&sameRevision(p.get("expectedExceptionRevision"),b.exception().revision())
            &&CanonicalJson.encode(p.get("expectedBasis")).equals(CanonicalJson.encode(CommandScope.selector(b.basis())))&&CanonicalJson.encode(p.get("expectedTask")).equals(CanonicalJson.encode(CommandScope.selector(b.task())))&&CanonicalJson.encode(p.get("expectedWait")).equals(CanonicalJson.encode(CommandScope.selector(b.waitReceipt())));
    }
    private static boolean closurePayload(CommandEnvelope e,CommandAuthorizationBinding.OpportunityClosure b){
        if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","expectedResponsibility","expectedTask","expectedWait","reasonCode","summary"))||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        return b.basis()!=null&&CanonicalJson.encode(p.get("expectedResponsibility")).equals(CanonicalJson.encode(CommandScope.selector(b.basis())))&&CanonicalJson.encode(p.get("expectedTask")).equals(CanonicalJson.encode(CommandScope.selector(b.task())))&&CanonicalJson.encode(p.get("expectedWait")).equals(CanonicalJson.encode(CommandScope.selector(b.waitReceipt())));
    }
    private Subject recoveryProgress(Connection c,CommandEnvelope e,java.time.Instant at)throws SQLException {
        var payload=(Map<?,?>)e.payload();var task=facts.task(c,e.actor().tenantId(),UUID.fromString((String)payload.get("taskId")),at);
        var source=facts.opportunityRecoverySource(c,e.actor().tenantId(),UUID.fromString((String)payload.get("taskId")));
        String type=source!=null?source.type():task!=null&&"RECORD_QUOTE_REPLY".equals(task.taskType())?"opportunity.quote_response":"opportunity.opportunity_progress";
        return new Subject(type,UUID.fromString((String)payload.get("progressId")),null,(String)payload.get("progressHash"));
    }
    private static AuthorizationSnapshot merge(AuthorizationSnapshot original,List<AuthorizationSnapshot> checks,String failure,String facts,boolean recovery) {
        for(var check:checks)if(!check.allowed()) {failure=check.rejectionCode();break;}
        if(recovery && "APPOINTMENT_INACTIVE".equals(failure))failure="NOT_AUTHORIZED";
        String evidence="R1_COMMAND_POLICY_V1\n"+facts+"\n"+String.join("\n",checks.stream().map(AuthorizationSnapshot::evidence).toList())+"\n"+(failure==null?"ALLOW":failure);
        return new AuthorizationSnapshot(original.request(),checks.getLast().checkedAt(),failure==null,failure,original.authorityFact(),evidence,CanonicalJson.digest(evidence));
    }
    static boolean materialMatches(CommandEnvelope.Type type,CommandAuthorizationBinding.Materials b,R1AuthorizationFacts.MaterialFact m,UUID owner){
        return m!=null&&m.opportunity().equals(b.opportunity())&&m.basis().equals(b.basis())&&m.owner().equals(owner)&&Objects.equals(m.confirmation(),b.confirmation())&&Objects.equals(m.previous(),b.previous())&&(type==CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD?"evidence.material_upload_basis".equals(m.selector().type())&&m.selector().equals(m.upload()):"opportunity.material_version".equals(m.selector().type())&&Objects.equals(m.upload(),b.upload()));
    }
    private static boolean materialsPayload(CommandEnvelope e,CommandAuthorizationBinding.Materials b){
        if(!(e.payload() instanceof Map<?,?> p)||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        return customerSelector(p.get("responsibilityBasis"),b.basis())&&p.containsKey("expectedConfirmation")&&customerSelector(p.get("expectedConfirmation"),b.confirmation())&&p.containsKey("expectedPreviousVersion")&&customerSelector(p.get("expectedPreviousVersion"),b.previous())&&customerSelector(p.get("uploadSession"),b.upload());
    }
    private static boolean customerRequirementsPayload(CommandEnvelope e,CommandAuthorizationBinding.CustomerRequirements b){
        if(!(e.payload() instanceof Map<?,?> p)||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        return customerSelector(p.get("responsibilityBasis"),b.basis())&&p.containsKey("expectedDraft")&&customerSelector(p.get("expectedDraft"),b.draft())&&p.containsKey("expectedConfirmation")&&customerSelector(p.get("expectedConfirmation"),b.confirmation());
    }
    private static boolean customerSelector(Object value,Subject subject){
        if(subject==null)return value==null;
        return value instanceof Map<?,?> m&&m.keySet().equals(Set.of("id","revision"))&&sameUuid(m.get("id"),subject.id())&&sameRevision(m.get("revision"),subject.revision());
    }
    private static boolean contractRecoveryPayload(CommandEnvelope e,CommandAuthorizationBinding.ContractRecovery b){if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","sourceKind","source","expectedWorkflow")))return false;return b.sourceKind().equals(p.get("sourceKind"))&&sameUuid(p.get("opportunityId"),b.opportunity().id())&&sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision())&&customerSelector(p.get("responsibilityBasis"),b.basis())&&customerSelector(p.get("source"),b.source())&&customerSelector(p.get("expectedWorkflow"),b.workflow());}
    static boolean contractNegotiation(CommandEnvelope.Type type){return Set.of(CommandEnvelope.Type.END_CONTRACT_NEGOTIATION,CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW).contains(type);}
    private static boolean transferPayload(CommandEnvelope e,CommandAuthorizationBinding.Transfers b){return e.payload() instanceof Map<?,?> p&&p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","expectedWorkflow","values"))&&sameUuid(p.get("opportunityId"),b.opportunity().id())&&sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision())&&customerSelector(p.get("expectedWorkflow"),b.workflow());}
    public static String transferAuthority(CommandEnvelope.Type type){return switch(type){case SUBMIT_TRANSFER,RESUBMIT_TRANSFER->"TRANSFER_SUBMIT";case RECORD_TRANSFER_CONFLICT_REVIEW->"TRANSFER_REVIEW";case RECORD_TRANSFER_INTAKE->"TRANSFER_ACCEPT";case CLASSIFY_MATTER->"MATTER_CLASSIFY";default->throw new IllegalArgumentException("Not a transfer command");};}
    public static String transferResultType(CommandEnvelope.Type type){return switch(type){case SUBMIT_TRANSFER,RESUBMIT_TRANSFER->"transfer.submission";case RECORD_TRANSFER_CONFLICT_REVIEW->"transfer.review";case RECORD_TRANSFER_INTAKE->"transfer.intake";case CLASSIFY_MATTER->"transfer.classification";default->throw new IllegalArgumentException("Not a transfer command");};}
    public static String contractAuthority(CommandEnvelope.Type type){return switch(type){case REQUEST_CONTRACT_RECEIPT_REVIEW,SUPPLEMENT_CONTRACT_RECEIPT->"PAYMENT_SUBMIT";case RECORD_CONTRACT_RECEIPT_REVIEW->"PAYMENT_CONFIRM";case VERIFY_CONTRACT_EXECUTION_CONDITIONS->"CONTRACT_EXECUTION_VERIFY";case END_CONTRACT_NEGOTIATION,REQUEST_CONTRACT_TERMINATION_REVIEW->"OPPORTUNITY_CLOSE";case RECORD_CONTRACT_TERMINATION_REVIEW->"CONTRACT_TERMINATION_REVIEW";case RETURN_CONTRACT_SIGNATURE_FOR_REVISION->"CONTRACT_SIGNATURE_VERIFY";case SAVE_CONTRACT_SIGNATURE_DRAFT->"CONTRACT_PREPARE";case CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT->"CONTRACT_PREPARE";case SUBMIT_CONTRACT_SIGNATURE->"CONTRACT_PREPARE";case RECORD_CONTRACT_SIGNATURE_VERIFICATION->"CONTRACT_SIGNATURE_VERIFY";case ARCHIVE_CONTRACT_SIGNATURE->"CONTRACT_SIGNATURE_VERIFY";case RETURN_CONTRACT_FOR_REVISION->"CONTRACT_PREPARE";case REQUEST_CONTRACT_PREPARATION->"CONTRACT_PREPARE";case RECORD_CONTRACT_PREPARATION_DECISION->"CONTRACT_PREPARATION_DECIDE";case START_CONTRACT_PREPARATION->"CONTRACT_PREPARE";case SAVE_CONTRACT_DRAFT->"CONTRACT_PREPARE";case FORM_CONTRACT->"CONTRACT_PREPARE";case REQUEST_CONTRACT_REVIEW->"CONTRACT_PREPARE";case RECORD_CONTRACT_REVIEW->"CONTRACT_REVIEW";case REQUEST_CONTRACT_APPROVAL->"CONTRACT_PREPARE";case RECORD_CONTRACT_DECISION->"CONTRACT_APPROVE";default->throw new IllegalArgumentException("Not a contract command");};}
    public static String contractResultType(CommandEnvelope.Type type){return switch(type){case REQUEST_CONTRACT_RECEIPT_REVIEW->"contract.payment_request";case RECORD_CONTRACT_RECEIPT_REVIEW,SUPPLEMENT_CONTRACT_RECEIPT->"contract.payment_review";case VERIFY_CONTRACT_EXECUTION_CONDITIONS->"contract.execution_verification";case END_CONTRACT_NEGOTIATION,REQUEST_CONTRACT_TERMINATION_REVIEW,RECORD_CONTRACT_TERMINATION_REVIEW->"contract.negotiation_disposition";case RETURN_CONTRACT_SIGNATURE_FOR_REVISION->"contract.signature_revision_return";case SAVE_CONTRACT_SIGNATURE_DRAFT->"contract.signature_draft";case CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT->"contract.signature_arrangement";case SUBMIT_CONTRACT_SIGNATURE->"contract.signature_submission";case RECORD_CONTRACT_SIGNATURE_VERIFICATION->"contract.signature_verification";case ARCHIVE_CONTRACT_SIGNATURE->"contract.signature_archive";case RETURN_CONTRACT_FOR_REVISION->"contract.signature_revision_return";case REQUEST_CONTRACT_PREPARATION->"contract.preparation_request";case RECORD_CONTRACT_PREPARATION_DECISION->"contract.preparation_decision";case START_CONTRACT_PREPARATION->"contract.contract";case SAVE_CONTRACT_DRAFT->"contract.preparation_draft";case FORM_CONTRACT->"contract.contract_revision";case REQUEST_CONTRACT_REVIEW->"contract.revision_review_request";case RECORD_CONTRACT_REVIEW->"contract.revision_review_decision";case REQUEST_CONTRACT_APPROVAL->"contract.revision_approval_request";case RECORD_CONTRACT_DECISION->"contract.revision_approval_decision";default->throw new IllegalArgumentException("Not a contract command");};}
    private static boolean sameContractHash(String expected,Object raw){if(!(raw instanceof String value))return false;try{return expected.equals(value.matches("[0-9a-f]{64}")?Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(value)):value);}catch(IllegalArgumentException invalid){return false;}}
    private static boolean contractPayload(CommandEnvelope e,CommandAuthorizationBinding.Contracts b){
        if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedContract","expectedDraft","expectedVersion","expectedWorkflow","values"))||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        boolean version=b.version()==null?p.get("expectedVersion")==null:p.get("expectedVersion") instanceof Map<?,?> q&&q.keySet().equals(Set.of("id","hash"))&&sameUuid(q.get("id"),b.version().id())&&sameContractHash(b.version().hash(),q.get("hash"));
        return version&&customerSelector(p.get("responsibilityBasis"),b.basis())&&customerSelector(p.get("customerConfirmation"),b.confirmation())&&customerSelector(p.get("expectedContract"),b.contract())&&customerSelector(p.get("expectedDraft"),b.draft())&&customerSelector(p.get("expectedWorkflow"),b.workflow());
    }
    public static String attemptAuthority(CommandEnvelope.Type type){return switch(type){case RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT->"SALES_OPPORTUNITY_OWNER";case RECORD_QUOTE_FOLLOWUP_ATTEMPT->"QUOTE_RESPONSE";default->throw new IllegalArgumentException("Not an attempt command");};}
    private static boolean attemptPayload(CommandEnvelope e,CommandAuthorizationBinding.FollowupAttempt b){
        if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","task","waitReceipt","expectedWorkflow","values"))||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        boolean wait=b.waitReceipt()==null?p.get("waitReceipt")==null:p.get("waitReceipt") instanceof Map<?,?> w&&w.keySet().equals(Set.of("id","hash"))&&sameUuid(w.get("id"),b.waitReceipt().id())&&b.waitReceipt().hash().equals(w.get("hash"));
        return wait&&p.get("responsibilityBasis") instanceof Map<?,?> r&&r.keySet().equals(Set.of("type","id","revision"))&&b.basis().type().equals(r.get("type"))&&sameUuid(r.get("id"),b.basis().id())&&sameRevision(r.get("revision"),b.basis().revision())&&customerSelector(p.get("task"),b.task())&&customerSelector(p.get("expectedWorkflow"),b.workflow());
    }
    public static String quoteAuthority(CommandEnvelope.Type type){return switch(type){case END_QUOTE_NEGOTIATION->"OPPORTUNITY_CLOSE";case START_QUOTE_PREPARATION,SAVE_QUOTE_DRAFT,FORM_QUOTE,REQUEST_QUOTE_APPROVAL->"QUOTE_PREPARE";case RECORD_QUOTE_DECISION->"QUOTE_APPROVE";case RECORD_QUOTE_DELIVERY->"QUOTE_DELIVER";case RECORD_QUOTE_RESPONSE->"QUOTE_RESPONSE";default->throw new IllegalArgumentException("Not a quote command");};}
    public static String quoteResultType(CommandEnvelope.Type type){return switch(type){case END_QUOTE_NEGOTIATION->"opportunity.quote_termination";case START_QUOTE_PREPARATION->"opportunity.quote_preparation_intent";case SAVE_QUOTE_DRAFT->"opportunity.quote_draft";case FORM_QUOTE->"opportunity.quote_revision";case REQUEST_QUOTE_APPROVAL->"opportunity.quote_approval_request";case RECORD_QUOTE_DECISION->"opportunity.quote_approval_decision";case RECORD_QUOTE_DELIVERY->"opportunity.quote_issue";case RECORD_QUOTE_RESPONSE->"opportunity.quote_response";default->throw new IllegalArgumentException("Not a quote command");};}
    private static boolean quotePayload(CommandEnvelope e,CommandAuthorizationBinding.Quotes b){
        if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedDraft","expectedQuote","expectedWorkflow","values"))||!sameUuid(p.get("opportunityId"),b.opportunity().id())||!sameRevision(p.get("expectedOpportunityRevision"),b.opportunity().revision()))return false;
        boolean quote=b.quote()==null?p.get("expectedQuote")==null:p.get("expectedQuote") instanceof Map<?,?> q&&q.keySet().equals(Set.of("id","hash"))&&sameUuid(q.get("id"),b.quote().id())&&b.quote().hash().equals(q.get("hash"));
        return quote&&customerSelector(p.get("responsibilityBasis"),b.basis())&&customerSelector(p.get("customerConfirmation"),b.confirmation())&&customerSelector(p.get("expectedDraft"),b.draft())&&customerSelector(p.get("expectedWorkflow"),b.workflow());
    }
    static boolean dedicated(CommandEnvelope.Type type) {return type.transfers() || type.followupAttempts() || type.contracts() || type.quotes() || type.materials() || type.customerRequirements() || type==CommandEnvelope.Type.CLOSE_OPPORTUNITY || type.ownerException() || type==CommandEnvelope.Type.CAPTURE_LEAD || type==CommandEnvelope.Type.SAVE_ACTION_DRAFT || type.internalMaintenance();}
    static AuthorizationSnapshot retainDenial(AuthorizationSnapshot denied,AuthorizationSnapshot current) {
        String evidence=denied.evidence()+"\nR1_AFTER_ROLLBACK_FINAL_CHECK\n"+current.evidence();
        return new AuthorizationSnapshot(denied.request(),current.checkedAt(),false,denied.rejectionCode(),denied.authorityFact(),evidence,CanonicalJson.digest(evidence));
    }
    static String primaryPolicy(CommandEnvelope.Type type) {
        return DRAFTS.values().stream().filter(p->p.command()==type).map(p->p.slot()+":"+p.code()).findFirst().orElse(null);
    }
    static boolean matchesTask(CommandEnvelope.Type type,String taskType){var policy=DRAFTS.get(taskType);return policy!=null&&policy.command()==type;}
    static String primarySchema(CommandEnvelope.Type type){return DRAFTS.values().stream().filter(p->p.command()==type).map(DraftPolicy::schema).findFirst().orElse(null);}
}
