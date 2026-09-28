package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
public record R2MaterialsInput(Subject opportunity,Subject basis,Subject confirmation,Subject previous,Subject upload,String purpose,String fileName,String note) {
 public static R2MaterialsInput parse(CommandEnvelope e){try{
  require(e.type().materials()&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null&&e.actor().onBehalfAppointmentId()==null);
  var p=(Map<?,?>)e.payload();var keys=new HashSet<>(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","expectedConfirmation","expectedPreviousVersion"));boolean open=e.type()==CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD;
  keys.addAll(open?Set.of("purpose","fileName","note"):Set.of("uploadSession"));require(p.keySet().equals(keys));
  var o=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
  var b=selector(p.get("responsibilityBasis"),"opportunity.responsibility_handoff",false);require(b!=null);if(b.id().equals(o.id()))b=new Subject(o.type(),b.id(),b.revision(),null);
  var confirmation=selector(p.get("expectedConfirmation"),"opportunity.customer_requirement_confirmation",true);var previous=selector(p.get("expectedPreviousVersion"),"opportunity.material_version",true);
  String purpose=null,name=null,note=null;Subject upload=null;if(open){purpose=(String)p.get("purpose");require(Set.of("CONTRACT_BUSINESS","CORRESPONDENCE","OTHER").contains(purpose));name=text(p.get("fileName"),255,false);note=text(p.get("note"),500,true);require(!name.contains("/")&&!name.contains("\\"));}else{upload=selector(p.get("uploadSession"),"evidence.material_upload_basis",true);require(upload!=null);}
  return new R2MaterialsInput(o,b,confirmation,previous,upload,purpose,name,note);
 }catch(IllegalArgumentException|ClassCastException|NullPointerException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
 private static String text(Object v,int max,boolean nullable){if(v==null&&nullable)return null;var s=(String)v;require(s!=null&&(!s.isBlank()||nullable)&&s.length()<=max&&s.codePoints().noneMatch(c->(c<32&&!(nullable&&(c==9||c==10||c==13)))||c>=127&&c<=159));return s;}
 private static Subject selector(Object value,String type,boolean immutable){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id","revision")));long r=revision(p.get("revision"));require(!immutable||r==0);return new Subject(type,uuid(p.get("id")),r,null);}
 private static UUID uuid(Object v){String s=(String)v;var id=UUID.fromString(s);require(id.toString().equals(s));return id;}
 private static long revision(Object v){require(v instanceof Integer||v instanceof Long);long r=((Number)v).longValue();require(r>=0&&r<=9007199254740991L);return r;}
 private static void require(boolean v){if(!v)throw new IllegalArgumentException();}
 @Override public String toString(){return "R2MaterialsInput[protected]";}
}

