package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService.*;
import io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection;
import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.sql.*;import java.time.*;import java.util.*;import java.util.function.Function;
/** Trusted composition for finance; no caller-supplied account or deduplication namespace. */
public final class PaymentWorkflowPorts implements PaymentWorkflowService.Ports {
 private final ContractWorkflowPorts contracts;private final ContractProtection protection;private final TaskFactory tasks=TaskFactory.databaseBacked();private final Function<UUID,Account> accounts;
 public PaymentWorkflowPorts(ContractProtection protection,OpportunityProgressProtection materials,MaterialObjectStore objects,Function<UUID,Account> accounts){this(protection,materials,objects,accounts,new BusinessResponsibilityRouting(List.of()));}
 public PaymentWorkflowPorts(ContractProtection protection,OpportunityProgressProtection materials,MaterialObjectStore objects,Function<UUID,Account> accounts,BusinessResponsibilityRouting routing){this.protection=Objects.requireNonNull(protection);this.contracts=new ContractWorkflowPorts(materials,objects,routing);this.accounts=Objects.requireNonNull(accounts);}
 public void lockAuthority(Connection c,Actor actor)throws SQLException{AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());}
 public Instant now(Connection c)throws SQLException{return tasks.now(c);}
 public Instant due(Instant start){return contracts.signatureDue(start,ZoneId.of("Asia/Shanghai"));}
 private List<Subject> facts(Connection c,UUID tenant,Subject opportunity,List<Subject> selected)throws SQLException{var all=new LinkedHashSet<>(R2ContractServices.facts(c,tenant,opportunity.id()));all.add(opportunity);all.addAll(selected);return List.copyOf(all);}
 public void authorize(Connection c,Actor actor,Subject opportunity,String authority,List<Subject> selected)throws SQLException{
  var owner=contracts.responsibility(c,actor.tenantId(),opportunity);if(owner==null)throw new Blocked("NOT_AUTHORIZED");var fs=facts(c,actor.tenantId(),opportunity,selected);
  boolean recovery=authority.equals("PAYMENT_TASK_RECOVER");if(recovery&&actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");
  if(!Set.of("PAYMENT_READ","PAYMENT_TASK_RECOVER","PAYMENT_CONFIRM","PAYMENT_SUBMIT").contains(authority)||!contracts.permitted(c,actor,owner.organization(),fs,recovery?"CONTRACT_TASK_RECOVER":authority.equals("PAYMENT_READ")?"CONTRACT_READ":authority)||!recovery&&!contracts.permitted(c,actor,owner.organization(),fs,"CONTRACT_READ"))throw new Blocked("NOT_AUTHORIZED");
 }
 public UUID owner(Connection c,UUID tenant,Subject opportunity,String stage)throws SQLException{return owner(c,tenant,opportunity,stage,null);}
 public UUID owner(Connection c,UUID tenant,Subject opportunity,String stage,UUID incumbent)throws SQLException{
  var sales=contracts.responsibility(c,tenant,opportunity);if(sales==null)return null;var fs=facts(c,tenant,opportunity,List.of());String authority=stage.equals("SUPPLEMENT_RECEIPT")?"PAYMENT_SUBMIT":"PAYMENT_CONFIRM";
  var candidates=new LinkedHashSet<>(contracts.eligible(c,tenant,sales.organization(),fs,authority));candidates.retainAll(contracts.eligible(c,tenant,sales.organization(),fs,"CONTRACT_READ"));
  if(stage.equals("SUPPLEMENT_RECEIPT"))return candidates.contains(sales.owner())?sales.owner():null;
  if(incumbent!=null&&candidates.contains(incumbent))return incumbent;
  if(contracts.routingEnabled(tenant))return contracts.routingTarget(tenant,sales.organization(),stage).filter(candidates::contains).orElse(null);
  return candidates.size()==1?candidates.iterator().next():null;
 }
 private static Task neutral(TaskFactory.Task t){return t==null?null:new Task(t.selector(),t.owner(),t.state());}
 public Task task(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(tasks.read(c,tenant,id));}
 public Task create(Connection c,UUID tenant,Subject opportunity,UUID owner,String stage,Instant now,Instant due)throws SQLException{var type=switch(stage){case "CHECK_RECEIPT"->TaskFactory.Type.CHECK_CONTRACT_RECEIPT;case "SUPPLEMENT_RECEIPT"->TaskFactory.Type.SUPPLEMENT_CONTRACT_RECEIPT;default->throw new IllegalArgumentException("Finance stage required");};return neutral(tasks.createSignatureTask(c,tenant,type,owner,opportunity,ZoneId.of("Asia/Shanghai"),now,due));}
 public void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException{var exact=tasks.read(c,tenant,task.selector().id());if(exact==null||!exact.selector().equals(task.selector()))throw new Blocked("STALE_TASK");tasks.complete(c,tenant,exact,fact,now);}
 public void cancel(Connection c,UUID tenant,Task task,Instant now)throws SQLException{var exact=tasks.read(c,tenant,task.selector().id());if(exact==null||!exact.selector().equals(task.selector()))throw new Blocked("STALE_TASK");tasks.cancelForContract(c,tenant,exact,"CONTRACT_AUTHORITY_MISSING",now);}
 public void material(Connection c,Actor actor,Subject opportunity,UUID material,String sha,String authority)throws SQLException{authorize(c,actor,opportunity,authority,contracts.documentFacts(c,actor.tenantId(),material));if(!contracts.documentUsable(c,actor.tenantId(),material,sha))throw new Blocked("STALE_EVIDENCE");}
 public Account account(UUID tenant){var account=accounts.apply(tenant);if(account==null)throw new Blocked("PAYMENT_ACCOUNT_NOT_CONFIGURED");return account;}
 public String encode(Map<String,Object> value){return io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(value);}
 @SuppressWarnings("unchecked") public Map<String,Object> open(UUID tenant,UUID opportunity,UUID fact,byte[] body){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(protection.open(tenant,opportunity,fact,ContractProtection.Kind.PAYMENT,body),Map.class);}
 public byte[] seal(UUID tenant,UUID opportunity,UUID fact,String clear){return protection.seal(tenant,opportunity,fact,ContractProtection.Kind.PAYMENT,clear);}
}
