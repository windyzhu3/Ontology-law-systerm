package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.time.*;
import java.util.*;
/** Closed canonical input, shared by HTTP adapters and formal command handlers. */
public record R2OpportunityOwnerExceptionInput(Subject opportunity,Subject exception,Subject basis,Subject task,Subject waitReceipt,String reason,UUID receiver,Instant reviewDueAt) {
 public static R2OpportunityOwnerExceptionInput parse(CommandEnvelope e){try{
  require(e.type().ownerException()&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null&&e.actor().onBehalfAppointmentId()==null);
  var p=(Map<?,?>)e.payload();boolean observe=e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION;
  var keys=new HashSet<String>(Set.of("opportunityId","expectedOpportunityRevision"));if(!observe){keys.addAll(Set.of("exceptionId","expectedExceptionRevision","expectedBasis","expectedTask","expectedWait","reason"));keys.add(e.type()==CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY?"receiverAppointmentId":"reviewDueAt");}require(p.keySet().equals(keys));
  var opportunity=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
  if(observe)return new R2OpportunityOwnerExceptionInput(opportunity,null,null,null,null,null,null,null);
  var exception=new Subject("opportunity.owner_exception",uuid(p.get("exceptionId")),revision(p.get("expectedExceptionRevision")),null);var basis=subject(p.get("expectedBasis"));require(basis!=null&&basis.revision()!=null&&Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(basis.type()));
  var task=subject(p.get("expectedTask"));var wait=subject(p.get("expectedWait"));require(task==null||"responsibility.task_occurrence".equals(task.type())&&task.revision()!=null);require(wait==null||task!=null&&"responsibility.wait_receipt".equals(wait.type())&&wait.hash()!=null);
  var reason=(String)p.get("reason");require(reason.equals(reason.strip())&&reason.codePointCount(0,reason.length())>=1&&reason.codePointCount(0,reason.length())<=2000);
  UUID receiver=null;Instant review=null;if(e.type()==CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY)receiver=uuid(p.get("receiverAppointmentId"));else {review=Instant.parse((String)p.get("reviewDueAt"));require(review.getNano()%1000==0);}
  return new R2OpportunityOwnerExceptionInput(opportunity,exception,basis,task,wait,reason,receiver,review);
 }catch(IllegalArgumentException|ClassCastException|NullPointerException|DateTimeException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
 private static UUID uuid(Object value){var text=(String)value;var id=UUID.fromString(text);require(id.toString().equals(text));return id;}
 private static long revision(Object n){require(n instanceof Integer||n instanceof Long);long v=((Number)n).longValue();require(v>=0&&v<=9007199254740991L);return v;}
 private static Subject subject(Object value){if(value==null)return null;var p=(Map<?,?>)value;boolean hash=p.containsKey("hash");require(p.keySet().equals(Set.of("type","id",hash?"hash":"revision")));return new Subject((String)p.get("type"),uuid(p.get("id")),hash?null:revision(p.get("revision")),hash?(String)p.get("hash"):null);}
 private static void require(boolean ok){if(!ok)throw new CommandHandler.Rejected("VALIDATION_FAILED");}
}
