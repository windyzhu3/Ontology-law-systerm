package io.github.windyzhu3.ontologylaw.opportunity;
import java.util.*;
/** Strict bounded protected document. Contact identity is deliberately unrelated to client identity. */
public final class CustomerRequirementDocument {
 private CustomerRequirementDocument(){}
 public static Map<String,Object> validate(Map<String,Object> d,boolean confirm){
  require(d!=null&&Set.of("participants","unknownOpponent","matterName","customerGoal","serviceScope","knownConstraints","contactName","contactPhone","unverifiedOpponentName").containsAll(d.keySet()));
  for(var k:List.of("matterName","customerGoal","serviceScope","knownConstraints","contactName","contactPhone","unverifiedOpponentName")){var v=d.get(k);require(v==null||v instanceof String&&((String)v).codePointCount(0,((String)v).length())<=(k.equals("contactPhone")?80:k.equals("matterName")||k.equals("contactName")||k.equals("unverifiedOpponentName")?200:4000));if(confirm&&Set.of("matterName","customerGoal","serviceScope").contains(k))require(v instanceof String&&!((String)v).isBlank());}
  require(d.get("unknownOpponent")==null||d.get("unknownOpponent") instanceof Boolean);
  Object rows=d.get("participants");require(rows==null||rows instanceof List&&((List<?>)rows).size()<=30);boolean client=false;var seen=new HashSet<String>();
  for(Object row:rows==null?List.of():(List<?>)rows){require(row instanceof Map);var p=(Map<?,?>)row;require(Set.of("role","party","newParty","profileChange").containsAll(p.keySet()));require(p.get("role") instanceof String&&Set.of("CLIENT","OPPONENT","OTHER").contains(p.get("role")));client|="CLIENT".equals(p.get("role"));var selected=p.get("party");var fresh=p.get("newParty");require(selected==null||fresh==null);if(confirm)require(selected!=null||fresh!=null);
   if(selected!=null){require(selected instanceof Map);var s=(Map<?,?>)selected;require(s.keySet().equals(Set.of("id","revision")));UUID.fromString((String)s.get("id"));require(s.get("revision") instanceof Number&&((Number)s.get("revision")).longValue()>=0&&((Number)s.get("revision")).doubleValue()==((Number)s.get("revision")).longValue());require(seen.add(s.get("id")+":"+p.get("role")));}
   if(fresh!=null){require(fresh instanceof Map);var n=(Map<?,?>)fresh;require(Set.of("kind","name","distinctIdentityConfirmed").containsAll(n.keySet()));require(n.get("kind") instanceof String&&Set.of("NATURAL_PERSON","ORGANIZATION").contains(n.get("kind")));if(confirm)name(n.get("name"));else draftName(n.get("name"));require(n.get("distinctIdentityConfirmed")==null||n.get("distinctIdentityConfirmed") instanceof Boolean);if(confirm)require(Boolean.TRUE.equals(n.get("distinctIdentityConfirmed")));}
   if(p.get("profileChange")!=null){require(selected!=null&&p.get("profileChange") instanceof Map);var change=(Map<?,?>)p.get("profileChange");require(Set.of("name","sharedProfileImpactConfirmed").containsAll(change.keySet()));if(confirm)name(change.get("name"));else draftName(change.get("name"));if(confirm)require(Boolean.TRUE.equals(change.get("sharedProfileImpactConfirmed")));}
  }if(confirm)require(client);return d;
 }
 private static void draftName(Object v){require(v==null||v instanceof String&&((String)v).codePointCount(0,((String)v).length())<=200);}
 public static void name(Object v){require(v instanceof String&&!((String)v).isBlank()&&((String)v).equals(((String)v).strip())&&((String)v).codePointCount(0,((String)v).length())<=200);}
 private static void require(boolean v){if(!v)throw new IllegalArgumentException("Invalid customer requirements");}
}
