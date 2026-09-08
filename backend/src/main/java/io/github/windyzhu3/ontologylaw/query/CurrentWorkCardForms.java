package io.github.windyzhu3.ontologylaw.query;

import java.util.*;
import static io.github.windyzhu3.ontologylaw.query.CurrentWorkCard.object;

/** Frozen seven form registrations. Fixed selectors come exclusively from the bound Owner graph. */
final class CurrentWorkCardForms {
    private CurrentWorkCardForms() {}
    static Map<String,Object> form(CurrentWorkCardQuery.CardData data) {
        var values=new LinkedHashMap<String,Object>();var fields=new ArrayList<Map<String,Object>>();
        switch(data.task().type()) {
            case RESOLVE_LEAD_DUPLICATE -> {
                values.put("candidateLeadId",data.duplicate().lead().id().toString());values.put("candidateLeadRevision",data.duplicate().lead().revision());
                values.put("partyId",data.candidateParty().selector().id().toString());values.put("partyRevision",data.candidateParty().selector().revision());
                select(values,fields,"decisionCode","归属决定",options("LINK_EXISTING_PARTY","关联："+CurrentWorkCardQuery.text(data.candidateParty().canonicalName(),"已有当事人",190),"KEEP_SEPARATE","保留独立线索"));
                text(values,fields,"rationaleSummary","决定理由","TEXTAREA",true);
            }
            case COMPLETE_LEAD_INGRESS -> {
                text(values,fields,"phone","电话（电话或邮箱至少一项）","TEL",false);text(values,fields,"email","邮箱（电话或邮箱至少一项）","EMAIL",false);
                select(values,fields,"sourceCode","信息来源",options("OWNER_CONFIRMED","负责人确认","CUSTOMER_PROVIDED","客户提供"));text(values,fields,"sourceSummary","来源说明","TEXTAREA",true);
            }
            case ASSIGN_LEAD -> {
                var choices=new ArrayList<Map<String,Object>>();for(var owner:data.assignmentOptions())choices.add(option(owner.appointment().selector().id().toString(),CurrentWorkCardQuery.text(owner.principal().displayName()+" · "+owner.organization().displayName(),"负责人",200)));
                select(values,fields,"ownerAppointmentId","联系负责人",choices);
            }
            case RESOLVE_LEAD_ROUTING_GAP -> {
                select(values,fields,"decisionCode","调配决定",options("SCHEDULE_ROUTING_REVIEW","安排下次复核","RETRY_ASSIGNMENT_NOW","再次尝试分配","REQUEST_SOURCE_INTAKE_STOP","请求确认来源处理"));text(values,fields,"rationaleSummary","决定理由","TEXTAREA",true);
            }
            case ACK_SOURCE_INTAKE_STOP_REQUEST -> {
                values.put("causalDecisionId",data.decision().selector().id().toString());values.put("causalDecisionHash",data.decision().selector().hash());text(values,fields,"rationaleSummary","接收确认说明","TEXTAREA",true);
            }
            case CONTACT_LEAD -> {
                values.put("leadAssignmentId",data.assignment().selector().id().toString());values.put("leadAssignmentRevision",data.assignment().selector().revision());
                var channels=new ArrayList<Map<String,Object>>();if(data.contact().phone()!=null)channels.add(option("PHONE","电话"));if(data.contact().email()!=null)channels.add(option("EMAIL","邮箱"));
                select(values,fields,"contactChannelCode","联系渠道",channels);select(values,fields,"resultCode","联系结果",options("CONNECTED_VALID","联系成功且需求有效","NOT_CONNECTED","未能联系","SUSPECT_INVALID","疑似无效"));
                text(values,fields,"resultSummary","结果说明","TEXTAREA",false);text(values,fields,"legalNeed","有效联系时填写法律需求","TEXTAREA",data.draft()!=null&&"CONNECTED_VALID".equals(data.draft().values().get("resultCode")));
                text(values,fields,"evidenceSubmissionId","证据提交引用","TEXT",false);
            }
            case REVIEW_LEAD_VALIDITY -> {
                values.put("triggeringContactResultId",data.triggeringResult().selector().id().toString());values.put("triggeringContactResultHash",data.triggeringResult().selector().hash());
                select(values,fields,"decisionCode","复核决定",options("CONFIRM_INVALID","确认无效","CLOSE_UNREACHED","结束未联系线索","REOPEN_CONTACT","重新安排联系"));text(values,fields,"rationaleSummary","复核理由","TEXTAREA",true);
            }
        }
        if(data.draft()!=null) {
            // Fixed selectors must remain the current real facts; stale Draft values never override them.
            var editable=new HashSet<String>();for(var f:fields)editable.add((String)f.get("name"));
            for(var e:data.draft().values().entrySet())if(editable.contains(e.getKey()))values.put(e.getKey(),e.getValue());
            if("NOT_CONNECTED".equals(values.get("resultCode"))||"SUSPECT_INVALID".equals(values.get("resultCode"))) {
                values.remove("legalNeed");fields.removeIf(f->"legalNeed".equals(f.get("name")));
            }
            if("CONFIRMED".equals(data.draft().state()))fields.forEach(f->f.put("readOnly",true));
        }
        return object("actionCode",data.task().type().command,"schemaVersion",1,"values",values,"fields",fields);
    }
    private static void text(Map<String,Object> values,List<Map<String,Object>> fields,String name,String label,String control,boolean required) {
        values.put(name,"");fields.add(object("name",name,"label",label,"control",control,"required",required,"readOnly",false,"options",List.of()));
    }
    private static void select(Map<String,Object> values,List<Map<String,Object>> fields,String name,String label,List<Map<String,Object>> options) {
        if(options.isEmpty())throw new IllegalArgumentException("Missing authorized form choices");values.put(name,"");fields.add(object("name",name,"label",label,"control","SELECT","required",true,"readOnly",false,"options",options));
    }
    private static Map<String,Object> option(String value,String label) {return object("value",value,"label",label,"disabled",false);}
    private static List<Map<String,Object>> options(String... values) {var result=new ArrayList<Map<String,Object>>();for(int i=0;i<values.length;i+=2)result.add(option(values[i],values[i+1]));return result;}
}
