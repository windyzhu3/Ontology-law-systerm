package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** Metadata projection only. Business handling still reauthorizes through the existing work-card entry. */
final class R2ContractTaskProjection {
    private static final OpportunityLedgerAuthorityReader authority=OpportunityLedgerAuthorityReader.databaseBacked();
    private R2ContractTaskProjection() {}
    static void apply(Connection c,Actor actor,Subject opportunity,UUID organization,boolean detail,
            Map<String,Object> result,List<AuditAppender.ContractDisclosureEntry> disclosures)throws SQLException {
        var takeover=OpportunityContractReader.databaseBacked().takeoverFacts(c,actor.tenantId(),opportunity.id());
        var active=TaskFactory.databaseBacked().activeForLead(c,actor.tenantId(),opportunity).stream()
            .filter(t->t.type().isContract()).toList();
        if(takeover.isEmpty()&&active.isEmpty())return;
        result.remove("task");result.remove("dueAt");result.remove("nextActionLabel");
        result.put("taskState","NONE");if(detail)result.put("canHandle",false);
        var facts=new LinkedHashSet<Subject>(takeover);facts.add(opportunity);var negotiation=OpportunityContractReader.databaseBacked().negotiation(c,actor.tenantId(),opportunity.id());if(negotiation!=null){facts.add(negotiation.selector());if(negotiation.assignment()!=null)facts.add(negotiation.assignment());}
        var task=active.stream().sorted(Comparator
            .comparing((TaskFactory.Task t)->!t.owner().equals(actor.appointmentId()))
            .thenComparing(TaskFactory.Task::createdAt).thenComparing(t->t.selector().id())).findFirst().orElse(null);
        CurrentTaskReader.Task current=null;
        if(task!=null){
            current=CurrentTaskReader.databaseBacked().read(c,actor.tenantId(),task.selector().id());
            if(current==null||!current.selector().equals(task.selector())||!current.subject().equals(opportunity))
                throw new io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure(409,"STALE_SUBJECT");
            facts.add(current.selector());facts.add(current.responsibilityBasis());
        }
        var owner=current==null?null:WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),current.owner());
        var ownerFacts=owner==null?List.<Subject>of():List.of(owner.appointment().selector(),owner.principal().selector(),owner.organization().selector());
        if(!authority.permitted(c,actor,organization,List.copyOf(facts),"CONTRACT_READ"))return;
        for(var fact:facts)add(c,actor,organization,fact,disclosures);
        result.put("nextActionLabel","查看合同办理结果");
        if(negotiation!=null&&!negotiation.kind().equals("CONTINUE")){result.put("nextActionLabel",negotiation.kind().equals("REQUEST_REVIEW")?(current==null?"等待安排有权主管核对终止签约":"核对终止签约请求"):"本次销售办理已停止");if(negotiation.dueAt()!=null&&negotiation.kind().equals("REQUEST_REVIEW"))result.put("dueAt",negotiation.dueAt().toString());}
        if(current==null)return;
        result.put("taskState",current.state());result.put("nextActionLabel",label(current.type()));
        result.put("dueAt",current.slaDueAt().toString());result.put("ownerLabel","负责人信息受限");
        if(owner!=null&&authority.permitted(c,actor,organization,ownerFacts,"CONTRACT_READ")){
            result.put("ownerLabel",owner.principal().displayName());for(var fact:ownerFacts)add(c,actor,organization,fact,disclosures);
        }
        if(detail&&"OPEN".equals(current.state())&&current.owner().equals(actor.appointmentId())){
            // Full command facts only for the selected detail, not for every row on a list.
            var commandFacts=R2ContractServices.facts(c,actor.tenantId(),opportunity.id());
            if(authority.permitted(c,actor,organization,commandFacts,"CONTRACT_READ")
                    &&authority.permitted(c,actor,organization,commandFacts,current.type().authority)){
                for(var fact:commandFacts)add(c,actor,organization,fact,disclosures);
                result.put("canHandle",true);result.put("task",Map.of("id",current.selector().id().toString(),
                    "revision",current.selector().revision(),"etag",R1ResourceTags.task(actor,current.selector(),current.state(),current.responsibilityBasis())));
            }
        }
    }
    private static void add(Connection c,Actor actor,UUID org,Subject fact,List<AuditAppender.ContractDisclosureEntry> out)throws SQLException {
        var e=authority.evidence(c,actor,org,fact,"CONTRACT_READ");
        if(e==null||!e.allowed())throw new io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");
        out.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,e,"HISTORY"));
    }
    private static String label(TaskFactory.Type type){return switch(type){
        case REVIEW_CONTRACT_TERMINATION->"核对终止签约请求";
        case REQUEST_CONTRACT_PREPARATION->"申请直接准备合同";
        case DECIDE_CONTRACT_PREPARATION->"审核直接合同准备申请";
        case PREPARE_CONTRACT->"准备合同正文";
        case SUBMIT_CONTRACT_REVIEW->"提交签约前审查";
        case REVIEW_CONTRACT->"办理签约前审查";
        case SUPPLEMENT_CONTRACT_REVIEW->"补充签约前审查资料";
        case SUBMIT_CONTRACT_APPROVAL->"提交合同审批";
        case APPROVE_CONTRACT->"审批本版合同";
        case ARRANGE_CONTRACT_SIGNATURE->"核对本版签署安排";
        case COLLECT_CONTRACT_SIGNATURE->"提交本版签署材料";
        case VERIFY_CONTRACT_SIGNATURE->"核验本版签署材料";
        case CHECK_CONTRACT_RECEIPT->"核对本笔收款";
        case SUPPLEMENT_CONTRACT_RECEIPT->"补充收款凭证";
        case CHECK_CONTRACT_EXECUTION->"核对约定执行条件";
        case ARCHIVE_CONTRACT_SIGNATURE->"核对完整签署归档";
        default->throw new IllegalArgumentException("Contract responsibility required");
    };}
}

