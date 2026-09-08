package io.github.windyzhu3.ontologylaw.query;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.Owner;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.query.CurrentWorkCard.object;

/** Pure projection of named Owner results. No connection, SQL, authorization mutation, or audit. */
public final class CurrentWorkCardQuery {
    public record CardData(CurrentTaskReader.Task task,Lead lead,Owner owner,ActionDraftService.Draft draft,
            EffectiveContact contact,Duplicate duplicate,Party candidateParty,Assignment assignment,
            CurrentTaskReader.Decision decision,ContactResult triggeringResult,List<Owner> assignmentOptions) {
        public CardData { assignmentOptions=List.copyOf(assignmentOptions); }
    }
    public record Projection(CurrentWorkCard envelope,List<Subject> sources) {
        public Projection { sources=List.copyOf(sources); }
    }
    public static List<CurrentTaskReader.Task> ordered(List<CurrentTaskReader.Task> tasks,Instant now) {
        return tasks.stream().sorted(Comparator.comparing((CurrentTaskReader.Task t)->!t.slaDueAt().isBefore(now))
            .thenComparing(CurrentTaskReader.Task::slaDueAt).thenComparing(CurrentTaskReader.Task::createdAt)
            .thenComparing(t->t.selector().id(),CurrentWorkCardQuery::compareUuid)).toList();
    }
    public static int compareUuid(UUID a,UUID b) {
        int high=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());
        return high==0?Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits()):high;
    }
    public Projection project(Actor actor,Instant now,CardData data,List<CurrentTaskReader.Task> next,int waiting) {
        if(waiting<0||next.size()>2)throw new IllegalArgumentException("Invalid workcard cardinality");
        var sources=new LinkedHashSet<Subject>();Map<String,Object> card=null;
        if(data!=null) {
            var t=data.task();UUID owner=actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();
            if(actor.principalKind()!=PrincipalKind.HUMAN||!owner.equals(t.owner())||!"OPEN".equals(t.state()))throw new IllegalArgumentException("Exact OPEN Owner required");
            var l=data.lead();sources.add(t.selector());sources.add(l.selector());addOwner(sources,data.owner());
            if(data.duplicate()!=null){sources.add(data.duplicate().lead());sources.add(data.candidateParty().selector());}
            if(data.assignment()!=null)sources.add(data.assignment().selector());
            if(data.decision()!=null)sources.add(data.decision().selector());
            if(data.triggeringResult()!=null)sources.add(data.triggeringResult().selector());
            data.assignmentOptions().forEach(o->addOwner(sources,o));
            var draft=data.draft();if(draft!=null)sources.add(draft.selector());
            var subject=object("subjectType","LEAD","subjectRef",R1ResourceTags.subject(actor,l.selector()).replace("\"",""),"subjectRevision",l.selector().revision(),"title",text(l.capturedName(),"待处理线索",200));
            String subtitle=t.type()==TaskFactory.Type.CONTACT_LEAD?contactText(data.contact()):l.legalNeedSummary();
            if(subtitle!=null&&!subtitle.isEmpty())subject.put("subtitle",text(subtitle,"",300));
            var form=CurrentWorkCardForms.form(data);
            card=object("taskId",t.selector().id().toString(),"taskType",t.type().name(),"taskRevision",t.selector().revision(),
                "subject",subject,"owner",object("displayName",text(data.owner().principal().displayName(),"负责人",200),"organizationLabel",text(data.owner().organization().displayName(),"所属组织",200)),
                "businessPurpose",purpose(t),"primaryCommand",object("code",t.type().command,"label",label(t.type()),"enabled",draft==null||"DRAFT".equals(draft.state())),
                "expectedCompletionFact",completionCode(t.type()),"sla",object("code",t.slaCode(),"dueAt",t.slaDueAt().toString(),"status",slaStatus(t,now),"timeHint",hint(t,now)),
                "versionStatus",t.lead().equals(l.selector())?"CURRENT":"REFRESH_RECOMMENDED","commandForm",form,
                "actionDraft",draft==null?null:object("draftId",draft.selector().id().toString(),"draftRevision",draft.selector().revision(),"actionCode",draft.actionCode(),"schemaVersion",1,"values",draft.values(),"digest",draft.digest(),"updatedAt",draft.updatedAt().toString(),"editable","DRAFT".equals(draft.state())),
                "preconditions",object("taskETag",R1ResourceTags.task(actor,t.selector(),t.state()),"subjectETag",R1ResourceTags.subject(actor,l.selector()),"draftETag",draft==null?null:R1ResourceTags.draft(actor,draft.selector(),draft.state())));
        }
        var summaries=new ArrayList<Map<String,Object>>();for(var task:next) {
            sources.add(task.selector());summaries.add(object("taskId",task.selector().id().toString(),"businessPurpose",purpose(task),"priority",task.slaDueAt().isBefore(now)?"URGENT":"NORMAL","timeHint",hint(task,now)));
        }
        var envelope=new CurrentWorkCard(object("todaySummary",data==null?"当前没有可处理的责任事项。":"请先完成当前责任事项。","currentCard",card,"nextSummaries",summaries,"waitingCount",waiting,
            "chatComposer",object("mode","ACTION_DRAFT","targetTaskId",data==null?null:data.task().selector().id().toString(),"placeholder","输入内容以准备草稿","enabled",data!=null)));
        return new Projection(envelope,List.copyOf(sources));
    }
    private static void addOwner(Set<Subject> sources,Owner owner) {sources.add(owner.appointment().selector());sources.add(owner.principal().selector());sources.add(owner.organization().selector());}
    private static String contactText(EffectiveContact c) {if(c==null)return null;return String.join(" · ",java.util.stream.Stream.of(c.phone(),c.email()).filter(Objects::nonNull).toList());}
    static String text(String value,String fallback,int max) {if(value==null||value.isBlank())return fallback;return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}
    private static String slaStatus(CurrentTaskReader.Task t,Instant now) {return t.slaDueAt().isBefore(now)?"OVERDUE":!t.slaDueAt().isAfter(now.plusSeconds(1800))?"DUE_SOON":"ON_TRACK";}
    private static String hint(CurrentTaskReader.Task t,Instant now) {return switch(slaStatus(t,now)){case "OVERDUE"->"已逾期，请优先处理";case "DUE_SOON"->"即将到期";default->"在处理时限内";};}
    private static Map<String,Object> purpose(CurrentTaskReader.Task t) {return object("code",t.type().name(),"label",label(t.type()));}
    static String label(TaskFactory.Type type) {return switch(type){case RESOLVE_LEAD_DUPLICATE->"确认线索归属";case COMPLETE_LEAD_INGRESS->"补全联系方式";case ASSIGN_LEAD->"分配联系负责人";case RESOLVE_LEAD_ROUTING_GAP->"记录调配安排";case ACK_SOURCE_INTAKE_STOP_REQUEST->"确认接收来源请求";case CONTACT_LEAD->"记录联系结果";case REVIEW_LEAD_VALIDITY->"复核线索有效性";};}
    private static String completionCode(TaskFactory.Type type) {return switch(type){case COMPLETE_LEAD_INGRESS->"LEAD";case ASSIGN_LEAD->"LEAD_ASSIGNMENT";case CONTACT_LEAD->"LEAD_CONTACT_RESULT";default->"DECISION_RECORD";};}
}
