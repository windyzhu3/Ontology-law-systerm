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
    @FunctionalInterface public interface FactReference { String reference(Actor actor,Subject subject); }
    private final FactReference factReferences;
    public CurrentWorkCardQuery(FactReference factReferences){this.factReferences=Objects.requireNonNull(factReferences);}
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
        return project(actor,now,data,next,waiting,next,data==null?null:data.task().selector().id(),Map.of());
    }
    public Projection project(Actor actor,Instant now,CardData data,List<CurrentTaskReader.Task> next,int waiting,List<CurrentTaskReader.Task> myTasks,UUID recommended,Map<UUID,Lead> summarySubjects) {
        return project(actor,now,data,next,waiting,myTasks,recommended,summarySubjects,Map.of());
    }
    public Projection project(Actor actor,Instant now,CardData data,List<CurrentTaskReader.Task> next,int waiting,List<CurrentTaskReader.Task> myTasks,UUID recommended,Map<UUID,Lead> summarySubjects,Map<UUID,Subject> summaryReferences) {
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
            var subject=object("subjectType","LEAD","subjectRef",R1ResourceTags.subject(actor,l.selector()).replace("\"",""),"subjectRevision",l.selector().revision(),"title",leadTitle(l));
            String subtitle=t.type()==TaskFactory.Type.CONTACT_LEAD?contactText(data.contact()):t.type()==TaskFactory.Type.RESOLVE_SOURCE_REQUEST&&l.currentAssignmentId()==null?text("来源请求已确认；本线索尚未分配。"+l.legalNeedSummary(),"来源请求已确认，待明确线索去向",300):l.legalNeedSummary();
            if(l.contactName()!=null) subtitle="联系人："+l.contactName()+(subtitle==null?"":" · "+subtitle);
            if(subtitle!=null&&!subtitle.isEmpty())subject.put("subtitle",text(subtitle,"",300));
            var form=CurrentWorkCardForms.form(data);
            card=object("taskId",t.selector().id().toString(),"taskType",t.type().name(),"taskRevision",t.selector().revision(),
                "subject",subject,"owner",object("displayName",text(data.owner().principal().displayName(),"负责人",200),"organizationLabel",text(data.owner().organization().displayName(),"所属组织",200)),
                "businessPurpose",purpose(t),"primaryCommand",object("code",t.type().command,"label",label(t.type()),"enabled",t.lead().equals(l.selector())&&(draft==null||"DRAFT".equals(draft.state()))),
                "expectedCompletionFact",completionCode(t.type()),"sla",object("code",t.slaCode(),"dueAt",t.slaDueAt().toString(),"status",slaStatus(t,now),"timeHint",hint(t,now)),
                "versionStatus",t.lead().equals(l.selector())?"CURRENT":"REFRESH_RECOMMENDED","commandForm",form,
                "actionDraft",draft==null?null:object("draftId",draft.selector().id().toString(),"draftRevision",draft.selector().revision(),"actionCode",draft.actionCode(),"schemaVersion",1,"values",draft.values(),"digest",draft.digest(),"updatedAt",draft.updatedAt().toString(),"editable","DRAFT".equals(draft.state())),
                "preconditions",object("taskETag",R1ResourceTags.task(actor,t.selector(),t.state()),"subjectETag",R1ResourceTags.subject(actor,l.selector()),"draftETag",draft==null?null:R1ResourceTags.draft(actor,draft.selector(),draft.state())));
        }
        var summaries=new ArrayList<Map<String,Object>>();for(var task:next) {
            sources.add(task.selector());summaries.add(object("taskId",task.selector().id().toString(),"businessPurpose",purpose(task),"priority",task.slaDueAt().isBefore(now)?"URGENT":"NORMAL","timeHint",hint(task,now)));
        }
        var mine=new ArrayList<Map<String,Object>>();for(var task:myTasks) {
            sources.add(task.selector());var summary=object("taskId",task.selector().id().toString(),"businessPurpose",purpose(task),"priority",task.slaDueAt().isBefore(now)?"URGENT":"NORMAL","timeHint",hint(task,now));
            var lead=summarySubjects.get(task.selector().id());if(lead!=null){var reference=summaryReferences.getOrDefault(task.selector().id(),lead.selector());sources.add(lead.selector());sources.add(reference);summary.put("subjectTitle",leadTitle(lead));summary.put("subjectFactRef",factReferences.reference(actor,reference));}
            mine.add(summary);
        }
        var envelope=new CurrentWorkCard(object("todaySummary",data==null?"当前没有可处理的责任事项。":"请先完成当前责任事项。","currentCard",card,"nextSummaries",summaries,"myTasks",mine,"recommendedTaskId",recommended==null?null:recommended.toString(),"waitingCount",waiting,
            "chatComposer",object("mode","ACTION_DRAFT","targetTaskId",data==null?null:data.task().selector().id().toString(),"placeholder","输入内容以准备草稿","enabled",data!=null&&data.task().lead().equals(data.lead().selector()))));
        return new Projection(envelope,List.copyOf(sources));
    }
    public Projection projectOpportunity(Actor actor,Instant now,OpportunityWorkCardQuery.Data data,List<CurrentTaskReader.Task> next,int waiting,List<CurrentTaskReader.Task> myTasks,UUID recommended,Map<UUID,Lead> summarySubjects,Map<UUID,Subject> summaryReferences) {
        var base=project(actor,now,null,next,waiting,myTasks,recommended,summarySubjects,summaryReferences);
        var projected=new OpportunityWorkCardQuery().project(actor,now,data);
        var body=new LinkedHashMap<String,Object>(base.envelope().values());body.put("currentCard",projected.card());body.put("todaySummary","请先完成当前责任事项。");
        body.put("chatComposer",object("mode","ACTION_DRAFT","targetTaskId",data.task().selector().id().toString(),"placeholder","输入内容以准备草稿","enabled",true));
        var sources=new LinkedHashSet<Subject>(base.sources());sources.addAll(projected.sources());
        return new Projection(new CurrentWorkCard(body),List.copyOf(sources));
    }
    static String leadTitle(Lead lead) { return text(lead.customerName(),text(lead.contactName(),text(lead.capturedName(),"待处理线索",200),200),200); }
    private static void addOwner(Set<Subject> sources,Owner owner) {sources.add(owner.appointment().selector());sources.add(owner.principal().selector());sources.add(owner.organization().selector());}
    private static String contactText(EffectiveContact c) {if(c==null)return null;return String.join(" · ",java.util.stream.Stream.of(c.phone(),c.email()).filter(Objects::nonNull).toList());}
    static String text(String value,String fallback,int max) {if(value==null||value.isBlank())return fallback;return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}
    static String slaStatus(CurrentTaskReader.Task t,Instant now) {return t.slaDueAt().isBefore(now)?"OVERDUE":!t.slaDueAt().isAfter(now.plusSeconds(1800))?"DUE_SOON":"ON_TRACK";}
    static String hint(CurrentTaskReader.Task t,Instant now) {return switch(slaStatus(t,now)){case "OVERDUE"->"已逾期，请优先处理";case "DUE_SOON"->"即将到期";default->"在处理时限内";};}
    private static Map<String,Object> purpose(CurrentTaskReader.Task t) {return object("code",t.type().name(),"label",label(t.type()));}
    public static String label(TaskFactory.Type type) {return switch(type){case CLASSIFY_MATTER->"确认案件分类及承接";case ACCEPT_TRANSFER->"审核接收转案";case SUPPLEMENT_TRANSFER->"补正转案材料";case PREPARE_TRANSFER->"准备本次转案资料";case REVIEW_TRANSFER->"办理转案前冲突审查";case CHECK_CONTRACT_RECEIPT->"核对本笔到账";case SUPPLEMENT_CONTRACT_RECEIPT->"补齐收款核对依据";case CHECK_CONTRACT_EXECUTION->"核对约定执行条件";case RESOLVE_SOURCE_REQUEST->"确认线索后续安排";case REVIEW_CONTRACT_TERMINATION->"核对终止签约请求";case ARRANGE_CONTRACT_SIGNATURE->"核对本版签署安排";case COLLECT_CONTRACT_SIGNATURE->"提交签署材料";case VERIFY_CONTRACT_SIGNATURE->"核验签署材料";case ARCHIVE_CONTRACT_SIGNATURE->"确认签署归档";case REQUEST_CONTRACT_PREPARATION->"申请直接准备合同";case DECIDE_CONTRACT_PREPARATION->"核对直接准备授权";case PREPARE_CONTRACT->"准备委托合同";case SUBMIT_CONTRACT_REVIEW->"提交签约前审查";case REVIEW_CONTRACT->"办理签约前冲突审查";case SUBMIT_CONTRACT_APPROVAL->"提交合同审批";case APPROVE_CONTRACT->"审批委托合同";case SUPPLEMENT_CONTRACT_REVIEW->"补充冲突审查资料";case PREPARE_QUOTE->"准备收费方案";case SUBMIT_QUOTE_APPROVAL->"提交报价审批";case APPROVE_QUOTE->"审批收费方案";case DELIVER_QUOTE->"报价交付留证";case RECORD_QUOTE_REPLY->"记录客户报价回复";case RESOLVE_QUOTE_AUTHORITY->"修复报价审批依据";case PROGRESS_OPPORTUNITY->"推进客户委托";case RESOLVE_LEAD_DUPLICATE->"确认线索归属";case COMPLETE_LEAD_INGRESS->"补全联系方式";case ASSIGN_LEAD->"分配联系负责人";case RESOLVE_LEAD_ROUTING_GAP->"记录调配安排";case ACK_SOURCE_INTAKE_STOP_REQUEST->"确认接收来源请求";case CONTACT_LEAD->"记录联系结果";case REVIEW_LEAD_VALIDITY->"复核线索有效性";};}
    private static String completionCode(TaskFactory.Type type) {return switch(type){case COMPLETE_LEAD_INGRESS->"LEAD";case ASSIGN_LEAD->"LEAD_ASSIGNMENT";case CONTACT_LEAD->"LEAD_CONTACT_RESULT";default->"DECISION_RECORD";};}
}
