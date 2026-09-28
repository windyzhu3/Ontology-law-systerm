package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;

public record R2OpportunityClosureInput(Subject opportunity,Subject responsibility,Subject task,Subject waitReceipt,String reasonCode,String summary) {
    public static R2OpportunityClosureInput parse(CommandEnvelope e){try{
        require(e.type()==CommandEnvelope.Type.CLOSE_OPPORTUNITY&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null&&e.actor().onBehalfAppointmentId()==null);
        var p=(Map<?,?>)e.payload();require(p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","expectedResponsibility","expectedTask","expectedWait","reasonCode","summary")));
        var opportunity=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
        var basis=subject(p.get("expectedResponsibility"));var task=subject(p.get("expectedTask"));var wait=subject(p.get("expectedWait"));
        require(basis!=null&&basis.revision()!=null&&Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.type()));
        require(task==null||"responsibility.task_occurrence".equals(task.type())&&task.revision()!=null);require(wait==null||task!=null&&"responsibility.wait_receipt".equals(wait.type())&&wait.hash()!=null);
        String reason=(String)p.get("reasonCode"),summary=(String)p.get("summary");require(Set.of("CLIENT_DECLINED","NEED_CANCELLED","OTHER").contains(reason)&&summary!=null&&summary.equals(summary.strip())&&!summary.isBlank()&&summary.codePointCount(0,summary.length())<=1000);
        return new R2OpportunityClosureInput(opportunity,basis,task,wait,reason,summary);
    }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    private static UUID uuid(Object value){String text=(String)value;var id=UUID.fromString(text);require(id.toString().equals(text));return id;}
    private static long revision(Object n){require(n instanceof Integer||n instanceof Long);long v=((Number)n).longValue();require(v>=0&&v<=9007199254740991L);return v;}
    private static Subject subject(Object value){if(value==null)return null;var p=(Map<?,?>)value;boolean hash=p.containsKey("hash");require(p.keySet().equals(Set.of("type","id",hash?"hash":"revision")));return new Subject((String)p.get("type"),uuid(p.get("id")),hash?null:revision(p.get("revision")),hash?(String)p.get("hash"):null);}
    private static void require(boolean valid){if(!valid)throw new CommandHandler.Rejected("VALIDATION_FAILED");}
    @Override public String toString(){return "R2OpportunityClosureInput[protected]";}
}
