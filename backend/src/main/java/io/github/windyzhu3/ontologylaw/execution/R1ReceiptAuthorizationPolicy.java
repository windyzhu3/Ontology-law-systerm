package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.CommandReceiptAuthorizationReader.Original;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Terminal receipt visibility rechecks authorization, never the attempted command's eligibility. */
final class R1ReceiptAuthorizationPolicy {
    private final R1AuthorizationFacts facts;
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    R1ReceiptAuthorizationPolicy(R1AuthorizationFacts facts){this.facts=Objects.requireNonNull(facts);}
    AuthorizationSnapshot authorize(Connection c,Actor actor,Original original,Instant now)throws SQLException {
        if(original.recovery().identity()){try{return IdentityCommandRuntime.authorizeOriginal(c,actor,original).authorization();}catch(IdentityCommands.Failure denied){throw new CommandReceiptReadRuntime.Failure(403,"NOT_AUTHORIZED");}}
        var recovery=original.recovery();var checks=new ArrayList<AuthorizationSnapshot>();Request request;
        if("CAPTURE_LEAD".equals(original.commandType())) {
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
