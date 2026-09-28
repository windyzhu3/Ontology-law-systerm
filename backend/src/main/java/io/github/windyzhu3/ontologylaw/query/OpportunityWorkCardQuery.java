package io.github.windyzhu3.ontologylaw.query;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.Owner;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.Lead;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityCommandReader.Header;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.Instant;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.query.CurrentWorkCard.object;

/** Pure projection of separately authorized Opportunity and source Lead facts. */
public final class OpportunityWorkCardQuery {
    public record Data(CurrentTaskReader.Task task,Header opportunity,Lead lead,Owner owner,ActionDraftService.Draft draft,io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.Responsibility responsibility) {
        public Data(CurrentTaskReader.Task task,Header opportunity,Lead lead,Owner owner,ActionDraftService.Draft draft){this(task,opportunity,lead,owner,draft,new io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.Responsibility(opportunity.selector(),opportunity.owner()));}
    }
    public record Projection(Map<String,Object> card,List<Subject> sources) {
        public Projection {card=new CurrentWorkCard(card).values();sources=List.copyOf(sources);}
    }
    public Projection project(Actor actor,Instant now,Data data) {
        var task=data.task();var opportunity=data.opportunity();var lead=data.lead();var draft=data.draft();
        if((task.type().isContract()||task.type().isTransfer())&&draft!=null)throw new IllegalArgumentException("Contract drafts are loaded through the exact contract context");
        if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null
            ||!task.type().subjectType().equals("opportunity.opportunity")||!"OPEN".equals(task.state())
            ||!task.owner().equals(actor.appointmentId())||(!task.type().independentDecisionOwner()&&!data.responsibility().appointmentId().equals(task.owner()))||!data.responsibility().basis().equals(task.responsibilityBasis())
            ||opportunity.closed()||!opportunity.selector().equals(task.subject()))throw new IllegalArgumentException("Exact actionable Opportunity required");
        var sources=new LinkedHashSet<Subject>();sources.add(task.selector());sources.add(opportunity.selector());sources.add(lead.selector());sources.add(data.responsibility().basis());
        sources.add(data.owner().appointment().selector());sources.add(data.owner().principal().selector());sources.add(data.owner().organization().selector());
        if(draft!=null)sources.add(draft.selector());
        var subject=object("subjectType","OPPORTUNITY","subjectRef",R1ResourceTags.opportunitySubject(actor,opportunity.selector()).replace("\"",""),
            "subjectRevision",opportunity.selector().revision(),"title",CurrentWorkCardQuery.leadTitle(lead));
        String subtitle=lead.legalNeedSummary();
        if(lead.contactName()!=null)subtitle="联系人："+lead.contactName()+(subtitle==null?"":" · "+subtitle);
        if(subtitle!=null&&!subtitle.isBlank())subject.put("subtitle",CurrentWorkCardQuery.text(subtitle,"",300));
        boolean editable=draft==null||"DRAFT".equals(draft.state());
        var card=object("taskId",task.selector().id().toString(),"taskType",task.type().name(),"taskRevision",task.selector().revision(),
            "subject",subject,"owner",object("displayName",CurrentWorkCardQuery.text(data.owner().principal().displayName(),"负责人",200),"organizationLabel",CurrentWorkCardQuery.text(data.owner().organization().displayName(),"所属组织",200)),
            "businessPurpose",object("code",task.type().name(),"label",CurrentWorkCardQuery.label(task.type())),
            "primaryCommand",object("code",task.type().command,"label",task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY?"确认本次进展":task.type().isContract()?"办理合同事项":task.type().isTransfer()?"办理转案事项":"办理报价事项","enabled",editable),"expectedCompletionFact",task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY?"OPPORTUNITY_PROGRESS":task.type().publicCompletionType(),
            "sla",object("code",task.slaCode(),"dueAt",task.slaDueAt().toString(),"status",CurrentWorkCardQuery.slaStatus(task,now),"timeHint",CurrentWorkCardQuery.hint(task,now)),
            "versionStatus","CURRENT",
            "commandForm",object("actionCode",task.type().command,"schemaVersion",1,"values",draft==null?Map.of():draft.values(),"fields",List.of()),
            "actionDraft",draft==null?null:object("draftId",draft.selector().id().toString(),"draftRevision",draft.selector().revision(),"actionCode",draft.actionCode(),"schemaVersion",1,"values",draft.values(),"digest",draft.digest(),"updatedAt",draft.updatedAt().toString(),"editable",editable),
            "preconditions",object("taskETag",R1ResourceTags.task(actor,task.selector(),task.state(),task.responsibilityBasis()),"subjectETag",R1ResourceTags.opportunitySubject(actor,opportunity.selector()),"draftETag",draft==null?null:R1ResourceTags.draft(actor,draft.selector(),draft.state())));
        return new Projection(card,List.copyOf(sources));
    }
}

