package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.util.*;

public final class R1ResourceTags {
    private R1ResourceTags() {}
    public static String task(Actor actor,Subject task,String state) {
        if(!task.type().equals("responsibility.task_occurrence") || task.revision()==null
                || !Set.of("OPEN","WAITING","DONE","CANCELLED").contains(state))throw new IllegalArgumentException("Exact Task required");
        return tag(actor,task,"task",state);
    }
    public static String task(Actor actor,Subject task,String state,Subject basis) {
        String legacy=task(actor,task,state);
        if(basis==null||"opportunity.opportunity".equals(basis.type()))return legacy;
        if(!"opportunity.responsibility_handoff".equals(basis.type())||basis.revision()==null||basis.hash()!=null)throw new IllegalArgumentException("Exact responsibility basis required");
        var value=Map.of("profile","R2_OPPORTUNITY_RESPONSIBILITY_TASK_TAG_V1","taskTag",legacy,"basisType",basis.type(),"basisId",basis.id().toString(),"basisRevision",basis.revision());
        return "\"task."+Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(value)))+"\"";
    }
    public static String draft(Actor actor,Subject draft,String state) {
        if(!draft.type().equals("responsibility.action_draft")||draft.revision()==null||!Set.of("DRAFT","CONFIRMED").contains(state))
            throw new IllegalArgumentException("Exact Draft required");
        return tag(actor,draft,"draft",state);
    }
    public static String subject(Actor actor,Subject lead) {
        if(!lead.type().equals("lead.lead")||lead.revision()==null)throw new IllegalArgumentException("Exact Lead required");
        return tag(actor,lead,"subject",null);
    }
    public static String opportunitySubject(Actor actor,Subject opportunity) {
        if(!opportunity.type().equals("opportunity.opportunity")||opportunity.revision()==null)throw new IllegalArgumentException("Exact Opportunity required");
        return tag(actor,opportunity,"subject",null,"R2_OPPORTUNITY_RESOURCE_TAG_V1");
    }
    private static String tag(Actor actor,Subject task,String kind,String state) {return tag(actor,task,kind,state,"R1_RESOURCE_TAG_V1");}
    private static String tag(Actor actor,Subject task,String kind,String state,String profile) {
        var values=new TreeMap<String,Object>();
        values.put("profile",profile);values.put("projectionVersion",1);values.put("kind",kind);
        values.put("tenantId",actor.tenantId().toString());values.put("principalId",actor.principalId().toString());
        values.put("appointmentId",actor.appointmentId().toString());values.put("principalKind",actor.principalKind().name());
        values.put("onBehalfPrincipalId",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());
        values.put("onBehalfAppointmentId",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());
        values.put("resourceId",task.id().toString());values.put("resourceType",task.type());values.put("revision",task.revision());values.put("state",state);
        return "\""+kind+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(values)))+"\"";
    }
}
