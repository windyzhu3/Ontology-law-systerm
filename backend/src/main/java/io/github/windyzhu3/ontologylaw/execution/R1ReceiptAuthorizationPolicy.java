package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.CommandReceiptAuthorizationReader.Original;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Terminal receipt visibility rechecks authorization, never the attempted command's eligibility. */
final class R1ReceiptAuthorizationPolicy {
    private static Subject materialSelector(Object value,String type){return value==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(value,type,false);}
    private final R1AuthorizationFacts facts;
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    R1ReceiptAuthorizationPolicy(R1AuthorizationFacts facts){this.facts=Objects.requireNonNull(facts);}
    AuthorizationSnapshot authorize(Connection c,Actor actor,Original original,Instant now)throws SQLException {
        if(original.recovery().identity()){try{return IdentityCommandRuntime.authorizeOriginal(c,actor,original).authorization();}catch(IdentityCommands.Failure denied){throw new CommandReceiptReadRuntime.Failure(403,"NOT_AUTHORIZED");}}
        var recovery=original.recovery();var checks=new ArrayList<AuthorizationSnapshot>();Request request;
        if(recovery.transfersScope()!=null){
            var scope=recovery.transfersScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var b=new CommandAuthorizationBinding.Transfers(recovery.lead(),materialSelector(scope.get("workflow"),"transfer.workflow"));var type=CommandEnvelope.Type.valueOf(original.commandType());var current=facts.transfers(c,actor.tenantId(),b,type);require(current!=null&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(b.opportunity()),403);
            request=authorities.select(c,actor,b.opportunity(),current.organization(),"OPPORTUNITY_OWNER",R1CommandPolicy.transferAuthority(type));require(request!=null&&request.requirement().path()==Path.DIRECT,403);var read=authorities.select(c,actor,b.opportunity(),current.organization(),"OPPORTUNITY_OWNER","CONTRACT_READ");require(read!=null&&read.requirement().path()==Path.DIRECT,403);
            for(var fact:current.protectedFacts())add(c,checks,request,fact,403);var readChecks=new ArrayList<AuthorizationSnapshot>();for(var fact:current.protectedFacts())add(c,readChecks,read,fact,403);checks.addAll(readChecks);
            var receipt=facts.commandReceipt(c,actor.tenantId(),original.commandId());require(receipt!=null,404);if(receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();require(R1CommandPolicy.transferResultType(type).equals(result.type())&&current.protectedFacts().contains(result),403);add(c,checks,request,result,403);}
        } else if(recovery.contractsScope()!=null){
            var scope=recovery.contractsScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var binding=new CommandAuthorizationBinding.Contracts(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),materialSelector(scope.get("confirmation"),"opportunity.customer_requirement_confirmation"),materialSelector(scope.get("contract"),"contract.contract"),materialSelector(scope.get("draft"),"contract.preparation_draft"),scope.get("version")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("version"),"contract.contract_revision",true),materialSelector(scope.get("workflow"),"contract.preparation_workflow"));
            var current=facts.contracts(c,actor.tenantId(),binding);require(current!=null&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            var type=CommandEnvelope.Type.valueOf(original.commandType());request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER",R1CommandPolicy.contractAuthority(type));require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            if(R1CommandPolicy.contractNegotiation(type)){
                var read=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER","CONTRACT_READ");require(read!=null&&read.requirement().path()==Path.DIRECT,403);
                var readChecks=new ArrayList<AuthorizationSnapshot>();add(c,readChecks,read,binding.opportunity(),403);for(var fact:current.protectedFacts())add(c,readChecks,read,fact,403);
                // Keep both authority proofs; the common add helper deduplicates by subject.
                for(var proof:readChecks)require(proof.allowed(),403);
                add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.opportunity(),403);for(var fact:current.protectedFacts())add(c,checks,request,fact,403);checks.addAll(readChecks);
            }else {add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.opportunity(),403);for(var fact:current.protectedFacts())add(c,checks,request,fact,403);}
            var receipt=facts.commandReceipt(c,actor.tenantId(),original.commandId());require(receipt!=null,404);if(receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();require(R1CommandPolicy.contractResultType(type).equals(result.type())&&current.protectedFacts().contains(result),403);add(c,checks,request,result,403);}
        } else if(recovery.attemptScope()!=null){
            var scope=recovery.attemptScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var b=new CommandAuthorizationBinding.FollowupAttempt(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),materialSelector(scope.get("task"),"responsibility.task_occurrence"),scope.get("wait")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("wait"),"responsibility.wait_receipt",true),materialSelector(scope.get("workflow"),"opportunity.quote_workflow"));
            var receipt=facts.commandReceipt(c,actor.tenantId(),original.commandId());require(receipt!=null,404);var current=facts.followupAttempt(c,actor.tenantId(),b,receipt.outcome().resultFact());
            require(current!=null&&actor.appointmentId().equals(current.owner())&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(b.opportunity()),403);
            request=authorities.select(c,actor,b.opportunity(),current.organization(),"OPPORTUNITY_OWNER",R1CommandPolicy.attemptAuthority(CommandEnvelope.Type.valueOf(original.commandType())));require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            for(var fact:current.protectedFacts())add(c,checks,request,fact,403);
        } else if(recovery.quotesScope()!=null){
            var scope=recovery.quotesScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var binding=new CommandAuthorizationBinding.Quotes(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),materialSelector(scope.get("confirmation"),"opportunity.customer_requirement_confirmation"),materialSelector(scope.get("draft"),"opportunity.quote_draft"),scope.get("quote")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("quote"),"opportunity.quote_revision",true),materialSelector(scope.get("workflow"),"opportunity.quote_workflow"));
            var current=facts.quotes(c,actor.tenantId(),binding);require(current!=null&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            var type=CommandEnvelope.Type.valueOf(original.commandType());request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER",R1CommandPolicy.quoteAuthority(type));require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.opportunity(),403);for(var fact:current.protectedFacts())add(c,checks,request,fact,403);
            var receipt=facts.commandReceipt(c,actor.tenantId(),original.commandId());require(receipt!=null,404);if(receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();require(R1CommandPolicy.quoteResultType(type).equals(result.type())&&current.protectedFacts().contains(result),403);add(c,checks,request,result,403);}
        } else if(recovery.materialsScope()!=null){
            var scope=recovery.materialsScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var binding=new CommandAuthorizationBinding.Materials(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),materialSelector(scope.get("confirmation"),"opportunity.customer_requirement_confirmation"),materialSelector(scope.get("previous"),"opportunity.material_version"),materialSelector(scope.get("upload"),"evidence.material_upload_basis"));
            var current=facts.materials(c,actor.tenantId(),binding,true);require(current!=null&&actor.appointmentId().equals(current.owner())&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER","MATERIALS_MANAGE");require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.opportunity(),403);for(var fact:current.protectedFacts())add(c,checks,request,fact,403);
            var receipt=CommandReceiptReader.databaseBacked().read(c,actor.tenantId(),original.commandId());require(receipt!=null,404);
            if(receipt.outcome().resultFact()!=null){var result=receipt.outcome().resultFact();var m=facts.materialFact(c,actor.tenantId(),result);require(R1CommandPolicy.materialMatches(CommandEnvelope.Type.valueOf(original.commandType()),binding,m,actor.appointmentId()),403);var resolved=facts.materials(c,actor.tenantId(),new CommandAuthorizationBinding.Materials(binding.opportunity(),binding.basis(),binding.confirmation(),binding.previous(),m.upload()),true);require(resolved!=null&&actor.appointmentId().equals(resolved.owner())&&current.organization().equals(resolved.organization()),403);for(var fact:resolved.protectedFacts())add(c,checks,request,fact,403);add(c,checks,request,result,403);}
        } else if(recovery.customerRequirementsScope()!=null){
            var scope=recovery.customerRequirementsScope();
            require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null
                    &&actor.principalId().toString().equals(scope.get("principalId"))
                    &&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");
            var binding=new CommandAuthorizationBinding.CustomerRequirements(recovery.lead(),
                    io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),
                    recovery.draft(),scope.get("confirmation")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("confirmation"),"opportunity.customer_requirement_confirmation",false));
            var current=facts.customerRequirements(c,actor.tenantId(),binding,true);
            require(current!=null&&actor.appointmentId().equals(current.owner())
                    &&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER","CUSTOMER_REQUIREMENTS_MANAGE");
            require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.opportunity(),403);
            for(var fact:current.protectedFacts())add(c,checks,request,fact,403);
            var receipt=CommandReceiptReader.databaseBacked().read(c,actor.tenantId(),original.commandId());require(receipt!=null,404);
            if(receipt.outcome().resultFact()!=null){
                var result=receipt.outcome().resultFact();
                boolean confirmation="CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS".equals(original.commandType());
                require((confirmation?"opportunity.customer_requirement_confirmation":"opportunity.customer_requirement_draft").equals(result.type()),403);
                var metadata=facts.customerRequirementVersion(c,actor.tenantId(),result);
                require(metadata!=null&&metadata.selector().equals(result)&&metadata.opportunity().equals(binding.opportunity())
                        &&metadata.responsibility().equals(binding.basis())&&actor.appointmentId().equals(metadata.owner())
                        &&Objects.equals(metadata.previous(),confirmation?binding.confirmation():binding.draft())
                        &&(!confirmation||Objects.equals(metadata.draft(),binding.draft())),403);
                var resultBinding=new CommandAuthorizationBinding.CustomerRequirements(binding.opportunity(),binding.basis(),confirmation?binding.draft():result,confirmation?result:binding.confirmation());
                var resultFacts=facts.customerRequirements(c,actor.tenantId(),resultBinding,true);
                require(resultFacts!=null&&resultFacts.organization().equals(current.organization())&&resultFacts.owner().equals(current.owner()),403);
                add(c,checks,request,result,403);
                for(var fact:resultFacts.protectedFacts())add(c,checks,request,fact,403);
            }
        } else if(recovery.opportunityClosureScope()!=null){
            var scope=recovery.opportunityClosureScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var binding=new CommandAuthorizationBinding.OpportunityClosure(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),scope.get("task")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("task"),"responsibility.task_occurrence",false),scope.get("wait")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("wait"),"responsibility.wait_receipt",true));
            var receipt=CommandReceiptReader.databaseBacked().read(c,actor.tenantId(),original.commandId());require(receipt!=null,404);var current=facts.opportunityClosure(c,actor.tenantId(),binding,receipt.outcome().resultFact());require(current!=null&&(receipt.outcome().resultFact()==null||actor.appointmentId().equals(current.closureActor()))&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER","OPPORTUNITY_CLOSE");require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            add(c,checks,request,binding.opportunity(),403);add(c,checks,request,current.currentOpportunity(),403);
            for(var fact:current.protectedFacts())add(c,checks,request,fact,403);
            if(receipt.outcome().resultFact()!=null)add(c,checks,request,receipt.outcome().resultFact(),403);
        } else if(recovery.ownerExceptionScope()!=null){
            var scope=recovery.ownerExceptionScope();require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null&&actor.principalId().toString().equals(scope.get("principalId"))&&actor.appointmentId().toString().equals(scope.get("appointmentId")),403);
            var basis=(Map<?,?>)scope.get("basis");var binding=new CommandAuthorizationBinding.OwnerException(recovery.lead(),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("exception"),"opportunity.owner_exception",false),io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(basis,(String)basis.get("type"),false),scope.get("task")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("task"),"responsibility.task_occurrence",false),scope.get("wait")==null?null:io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(scope.get("wait"),"responsibility.wait_receipt",true));
            var receipt=CommandReceiptReader.databaseBacked().read(c,actor.tenantId(),original.commandId());require(receipt!=null,404);var current=facts.ownerException(c,actor.tenantId(),binding,receipt.outcome().resultFact());require(current!=null&&current.organization().equals(original.scopeOrganization())&&original.subject().equals(binding.opportunity()),403);
            request=authorities.select(c,actor,binding.opportunity(),current.organization(),"OPPORTUNITY_OWNER","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            for(var fact:current.protectedFacts())for(String code:List.of("OPPORTUNITY_OWNER_EXCEPTION_RESOLVE","OPPORTUNITY_OWNER_EXCEPTION_READ")){var r=authorities.select(c,actor,fact,current.organization(),"OPPORTUNITY_OWNER",code);require(r!=null&&r.requirement().path()==Path.DIRECT,403);var checked=authorization.evaluate(c,r,false);require(checked.allowed(),403);checks.add(checked);}
        } else if("RECORD_OPPORTUNITY_PROGRESS".equals(recovery.actionCode())){
            require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null,403);
            var historical=facts.opportunityReceiptTask(c,actor.tenantId(),recovery.taskId(),now);require(historical!=null,404);var task=historical.task();require(task.owner()!=null&&task.owner().active()&&task.ownerAppointmentId().equals(actor.appointmentId())&&task.owner().principalId().equals(actor.principalId())&&task.lead().equals(recovery.lead()),403);
            var receipt=CommandReceiptReader.databaseBacked().read(c,actor.tenantId(),original.commandId());require(receipt!=null,404);var org=facts.opportunityOrganization(c,actor.tenantId(),task.lead().id());require(org!=null&&org.equals(original.scopeOrganization()),403);
            boolean draft="SAVE_ACTION_DRAFT".equals(original.commandType());require(draft?original.subject().id().equals(task.selector().id()):original.subject().equals(task.lead()),403);
            request=authorities.select(c,actor,original.subject(),org,"OPPORTUNITY_OWNER","SALES_OPPORTUNITY_OWNER");require(request!=null&&request.requirement().path()==Path.DIRECT,403);
            add(c,checks,request,original.subject(),403);for(var fact:historical.protectedFacts())add(c,checks,request,fact,403);if(receipt.outcome().resultFact()!=null)add(c,checks,request,receipt.outcome().resultFact(),403);if(recovery.draft()!=null)add(c,checks,request,recovery.draft(),403);
        } else if("CAPTURE_LEAD".equals(original.commandType())) {
            var source=facts.capture(c,actor.tenantId(),recovery.sourceAccountCode(),recovery.sourceRecordKeyDigest());
            require(source!=null&&source.organization().equals(original.subject())&&source.organization().id().equals(original.scopeOrganization()),403);
            require(actor.principalKind()!=PrincipalKind.SERVICE||facts.serviceSourceAllowed(c,actor,recovery.sourceAccountCode()),403);
            request=authorities.select(c,actor,source.organization(),source.organization().id(),"SOURCE_INTAKE_OWNER","LEAD_CAPTURE");require(request!=null,403);
            add(c,checks,request,source.organization(),403);if(source.existingLead()!=null)add(c,checks,request,source.existingLead(),403);
        } else {
            require(actor.principalKind()==PrincipalKind.HUMAN,403);
            var task=facts.task(c,actor.tenantId(),recovery.taskId(),now);require(task!=null,404);
            require(task.owner()!=null&&task.owner().active(),403);
            UUID represented=actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();
            UUID principal=actor.onBehalfPrincipalId()==null?actor.principalId():actor.onBehalfPrincipalId();
            require(task.ownerAppointmentId().equals(represented)&&task.owner().principalId().equals(principal),403);
            var type=CommandEnvelope.Type.valueOf(recovery.actionCode());String policy=R1CommandPolicy.primaryPolicy(type);
            require(policy!=null&&task.primaryCommand().equals(recovery.actionCode())&&R1CommandPolicy.matchesTask(type,task.taskType())&&task.lead().equals(recovery.lead()),403);
            boolean draftCommand="SAVE_ACTION_DRAFT".equals(original.commandType());
            Subject anchor=draftCommand?task.selector():task.lead();
            if(draftCommand)require(original.subject().equals(new Subject("responsibility.task_occurrence",recovery.taskId(),recovery.taskRevision(),null))&&recovery.taskRevision()<=task.selector().revision(),403);
            else require(original.subject().equals(recovery.lead()),403);
            var parts=policy.split(":");request=authorities.select(c,actor,anchor,task.owner().organizationId(),parts[0],parts[1]);require(request!=null,403);
            add(c,checks,request,anchor,403);add(c,checks,request,task.selector(),403);add(c,checks,request,task.lead(),403);add(c,checks,request,task.currentLead(),403);
            if(task.draft()!=null) {
                var draft=task.draft();require(draft.taskId().equals(recovery.taskId())&&draft.actionCode().equals(task.primaryCommand())
                        &&draft.schemaCode().equals(R1CommandPolicy.primarySchema(type))&&draft.schemaVersion()==1,403);
                if(recovery.draft()!=null)require(recovery.draft().id().equals(draft.selector().id())&&recovery.draft().revision()<=draft.selector().revision(),403);
            } else require(recovery.draft()==null,403);
            if(recovery.submission()!=null) {
                var reference=facts.evidence(c,actor.tenantId(),recovery.submission().id());
                require(reference!=null&&reference.active()&&reference.submission().equals(recovery.submission())&&reference.binding().equals(recovery.evidenceBinding())&&reference.target().equals(task.lead()),404);
                add(c,checks,request,reference.submission(),404);add(c,checks,request,reference.binding(),404);
            }
        }
        var primary=checks.getFirst();String evidence="R1_RECEIPT_CURRENT_AUTHORIZATION_V1\n"+String.join("\n",checks.stream().sorted(Comparator.comparing(s->s.request().subject().toString())).map(AuthorizationSnapshot::evidence).toList());
        return new AuthorizationSnapshot(primary.request(),checks.getLast().checkedAt(),true,null,primary.authorityFact(),evidence,CanonicalJson.digest(evidence));
    }
    private void add(Connection c,List<AuthorizationSnapshot> checks,Request request,Subject subject,int status)throws SQLException {
        require(subject!=null,status);if(checks.stream().anyMatch(s->s.request().subject().equals(subject)))return;
        var snapshot=authorization.evaluate(c,new Request(request.actor(),subject,request.scopeOrganizationId(),request.requirement()),false);require(snapshot.allowed(),status);checks.add(snapshot);
    }
    private static void require(boolean condition,int status){if(!condition)throw new CommandReceiptReadRuntime.Failure(status,status==404?"NOT_FOUND":"NOT_AUTHORIZED");}
}
