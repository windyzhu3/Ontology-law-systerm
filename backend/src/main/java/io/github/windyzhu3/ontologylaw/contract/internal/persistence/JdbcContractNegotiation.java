package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import static io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowService.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

/** A sales-handling barrier, deliberately separate from legal termination and execution facts. */
final class JdbcContractNegotiation {
    static final Set<String> COMMANDS=Set.of("END_CONTRACT_NEGOTIATION","REQUEST_CONTRACT_TERMINATION_REVIEW","RECORD_CONTRACT_TERMINATION_REVIEW");
    static final Set<String> SALES_TASKS=Set.of("REQUEST_CONTRACT_PREPARATION","DECIDE_CONTRACT_PREPARATION","PREPARE_CONTRACT","SUBMIT_CONTRACT_REVIEW","REVIEW_CONTRACT","SUBMIT_CONTRACT_APPROVAL","APPROVE_CONTRACT","SUPPLEMENT_CONTRACT_REVIEW","ARRANGE_CONTRACT_SIGNATURE","COLLECT_CONTRACT_SIGNATURE","VERIFY_CONTRACT_SIGNATURE","ARCHIVE_CONTRACT_SIGNATURE");
    private final Ports ports;
    private final ContractProtection protection;
    private final ContractPreparationRepository.Codec codec;
    JdbcContractNegotiation(Ports ports,ContractProtection protection,ContractPreparationRepository.Codec codec){this.ports=ports;this.protection=protection;this.codec=codec;}
    Map<String,Object> latest(Connection c,UUID tenant,UUID oid)throws SQLException{return row(c,"select d.* from contract.negotiation_disposition d where d.tenant_id=? and d.opportunity_id=? and not exists(select 1 from contract.negotiation_disposition n where n.tenant_id=d.tenant_id and n.previous_disposition_id=d.negotiation_disposition_id)",tenant,oid);}
    Map<String,Object> assignment(Connection c,UUID tenant,UUID request)throws SQLException{return row(c,"select a.* from contract.termination_review_assignment a where a.tenant_id=? and a.request_id=? and not exists(select 1 from contract.termination_review_assignment n where n.tenant_id=a.tenant_id and n.previous_assignment_id=a.termination_review_assignment_id)",tenant,request);}
    private UUID id(Connection c)throws SQLException{return uuid(row(c,"select uuidv7() id").get("id"));}
    boolean blocked(Map<String,Object> d){return d!=null&&!"CONTINUE".equals(d.get("kind"));}
    boolean stopped(Map<String,Object> d){return d!=null&&Set.of("STOP_UNSIGNED","STOP_REVIEWED").contains(d.get("kind"));}
    void requireContinued(Connection c,UUID tenant,UUID oid)throws SQLException{if(blocked(latest(c,tenant,oid)))throw new Blocked("CONTRACT_HANDLING_PAUSED");}
    void expectCurrent(Map<String,Object> values,Map<String,Object> current){expect(values.get("expectedTermination"),current==null?null:fact("contract.negotiation_disposition",uuid(current.get("negotiation_disposition_id"))));}
    boolean signedEvidence(Connection c,UUID tenant,UUID oid)throws SQLException{return Boolean.TRUE.equals(row(c,"select contract.fn_negotiation_has_evidence(?,?) present",tenant,oid).get("present"));}
    Map<String,Object> signature(Connection c,UUID tenant,UUID oid,Map<String,Object> version)throws SQLException{return version==null?null:row(c,"select w.* from contract.signature_workflow w where w.tenant_id=? and w.opportunity_id=? and w.contract_revision_id=? and not exists(select 1 from contract.signature_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.signature_workflow_id)",tenant,oid,version.get("contract_revision_id"));}
    List<String> actions(Connection c,Actor actor,Subject o,Responsibility owner,Map<String,Object> workflow,List<Subject> facts)throws SQLException{
        if(owner==null||workflow==null||!ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_READ"))return List.of();var d=latest(c,actor.tenantId(),o.id());
        if(stopped(d))return List.of();
        if(d!=null&&"REQUEST_REVIEW".equals(d.get("kind"))){
            var a=assignment(c,actor.tenantId(),uuid(d.get("negotiation_disposition_id")));var task=a==null||a.get("task_id")==null?null:ports.read(c,actor.tenantId(),uuid(a.get("task_id")));
            return task!=null&&task.state().equals("OPEN")&&task.owner().equals(actor.appointmentId())&&!d.get("recorded_by").equals(actor.appointmentId())&&ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_TERMINATION_REVIEW")?List.of("RECORD_CONTRACT_TERMINATION_REVIEW"):List.of();
        }
        if(!owner.owner().equals(actor.appointmentId())||!ports.permitted(c,actor,owner.organization(),facts,"OPPORTUNITY_CLOSE"))return List.of();
        return List.of(signedEvidence(c,actor.tenantId(),o.id())?"REQUEST_CONTRACT_TERMINATION_REVIEW":"END_CONTRACT_NEGOTIATION");
    }
    private Map<String,Object> clear(UUID tenant,UUID oid,Map<String,Object> d){String body=protection.open(tenant,oid,uuid(d.get("negotiation_disposition_id")),ContractProtection.Kind.NEGOTIATION,(byte[])d.get("body_ciphertext"));if(!MessageDigest.isEqual(ContractCanonicalJson.digest(body),(byte[])d.get("body_digest")))throw new IllegalStateException("Negotiation body integrity failure");return codec.decode(body);}
    Map<String,Object> summary(Connection c,UUID tenant,UUID oid)throws SQLException{
        var d=latest(c,tenant,oid);if(d==null)return null;var result=new LinkedHashMap<String,Object>();
        result.put("selector",selector("contract.negotiation_disposition",d.get("negotiation_disposition_id")));result.put("state",stopped(d)?"STOPPED":"REQUEST_REVIEW".equals(d.get("kind"))?"REVIEW_REQUIRED":"CONTINUED");result.put("occurredAt",time(d.get("created_at")).toString());
        var request="REQUEST_REVIEW".equals(d.get("kind"))?d.get("negotiation_disposition_id"):d.get("request_disposition_id");var a=request==null?null:assignment(c,tenant,uuid(request));
        var task=!"REQUEST_REVIEW".equals(d.get("kind"))||a==null||a.get("task_id")==null?null:ports.read(c,tenant,uuid(a.get("task_id")));result.put("task",task==null?null:selector(task.selector()));result.put("ownerAppointmentId",task==null?null:task.owner().toString());result.put("dueAt",a==null?null:time(a.get("due_at")).toString());result.put("ownerLabel",task==null?"等待安排有权主管":"有权主管");return result;
    }
    Map<String,Object> context(Connection c,UUID tenant,UUID oid)throws SQLException{
        var result=summary(c,tenant,oid);if(result==null)return null;var d=latest(c,tenant,oid);result.put("summary",clear(tenant,oid,d).get("summary"));var independent=independentFacts(c,tenant,oid);result.put("independentBasis",independentBasis(independent));result.put("independentState",Map.of("paymentRecorded",independent.stream().anyMatch(f->f.type().equals("contract.payment_confirmation")),"executionRecorded",independent.stream().anyMatch(f->f.type().equals("contract.contract_execution")),"transferRecorded",independent.stream().anyMatch(f->f.type().equals("transfer.transfer_request"))));if("REQUEST_REVIEW".equals(d.get("kind"))){var o=new Subject("opportunity.opportunity",oid,number(d.get("opportunity_revision")),null);result.put("resumeAvailable",canResume(c,tenant,ports.responsibility(c,tenant,o),d,facts(c,tenant,oid)));}
        result.put("history",rows(c,"select * from contract.negotiation_disposition where tenant_id=? and opportunity_id=? order by created_at,negotiation_disposition_id",tenant,oid).stream().map(r->Map.of("selector",selector("contract.negotiation_disposition",r.get("negotiation_disposition_id")),"kind",r.get("kind"),"summary",clear(tenant,oid,r).get("summary"),"occurredAt",time(r.get("created_at")).toString())).toList());return result;
    }
    List<Subject> independentFacts(Connection c,UUID tenant,UUID oid)throws SQLException{
        var result=new ArrayList<Subject>(ports.transferFacts(c,tenant,oid));
        for(String table:List.of("contract_execution","payment_confirmation")){String digest=table.equals("contract_execution")?"execution_digest":"attribution_digest";
            for(var r:rows(c,"select f."+table+"_id id,f."+digest+" digest from contract."+table+" f join contract.contract k on k.tenant_id=f.tenant_id and k.contract_id=f.contract_id where k.tenant_id=? and k.opportunity_id=?",tenant,oid))result.add(new Subject("contract."+table,uuid(r.get("id")),null,Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])r.get("digest"))));
        }return result.stream().sorted(Comparator.comparing(Subject::type).thenComparing(f->f.id().toString())).toList();
    }
    String independentBasis(List<Subject> facts){return HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(facts.stream().map(f->{var value=new LinkedHashMap<String,Object>(selector(f));value.put("type",f.type());return value;}).toList())));}
    List<Subject> facts(Connection c,UUID tenant,UUID oid)throws SQLException{
        var out=new LinkedHashSet<Subject>(independentFacts(c,tenant,oid));
        for(var d:rows(c,"select negotiation_disposition_id from contract.negotiation_disposition where tenant_id=? and opportunity_id=?",tenant,oid))out.add(fact("contract.negotiation_disposition",uuid(d.get("negotiation_disposition_id"))));
        for(var a:rows(c,"select a.* from contract.termination_review_assignment a join contract.negotiation_disposition d on d.tenant_id=a.tenant_id and d.negotiation_disposition_id=a.request_id where d.tenant_id=? and d.opportunity_id=?",tenant,oid)){
            out.add(fact("contract.termination_review_assignment",uuid(a.get("termination_review_assignment_id"))));if(a.get("task_id")!=null){var task=ports.read(c,tenant,uuid(a.get("task_id")));if(task!=null)out.add(task.selector());}
        }
        for(var m:rows(c,"select m.* from contract.negotiation_cancelled_task m join contract.negotiation_disposition d on d.tenant_id=m.tenant_id and d.negotiation_disposition_id=m.disposition_id where d.tenant_id=? and d.opportunity_id=?",tenant,oid)){
            out.add(fact("contract.negotiation_cancelled_task",uuid(m.get("negotiation_cancelled_task_id"))));out.add(new Subject("responsibility.task_occurrence",uuid(m.get("task_id")),number(m.get("task_revision")),null));var task=ports.read(c,tenant,uuid(m.get("task_id")));if(task!=null)out.add(task.selector());var current=ports.currentTask(c,tenant,uuid(m.get("task_id")));if(current!=null)out.add(current.selector());if(m.get("wait_id")!=null)out.add(new Subject("responsibility.wait_receipt",uuid(m.get("wait_id")),null,Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])m.get("wait_hash"))));
        }
        return List.copyOf(out);
    }
    Subject execute(Connection c,String action,Actor actor,Subject opportunity,Responsibility owner,Map<String,Object> root,Map<String,Object> version,Map<String,Object> workflow,Map<String,Object> values,List<Subject> facts)throws SQLException{
        UUID tenant=actor.tenantId(),oid=opportunity.id();var current=latest(c,tenant,oid);expectCurrent(values,current);
        if(!actions(c,actor,opportunity,owner,workflow,facts).contains(action))throw new Blocked("NOT_AUTHORIZED");
        var acceptedKeys=new HashSet<>(Set.of("summary","humanConfirmed","expectedTermination","expectedSignatureWorkflow"));if(action.equals("RECORD_CONTRACT_TERMINATION_REVIEW"))acceptedKeys.addAll(Set.of("decision","expectedIndependentBasis"));
        if(!acceptedKeys.equals(values.keySet())||!Boolean.TRUE.equals(values.get("humanConfirmed")))throw new Blocked("VALIDATION_FAILED");String summary=new ContractPreparationRepository.Comment((String)values.get("summary")).value();
        var independent=independentFacts(c,tenant,oid);if(action.equals("RECORD_CONTRACT_TERMINATION_REVIEW")&&!independentBasis(independent).equals(values.get("expectedIndependentBasis")))throw stale();var sw=signature(c,tenant,oid,version);expect(values.get("expectedSignatureWorkflow"),sw==null?null:fact("contract.signature_workflow",uuid(sw.get("signature_workflow_id"))));
        String kind=switch(action){case "END_CONTRACT_NEGOTIATION"->"STOP_UNSIGNED";case "REQUEST_CONTRACT_TERMINATION_REVIEW"->"REQUEST_REVIEW";case "RECORD_CONTRACT_TERMINATION_REVIEW"->{String decision=(String)values.get("decision");if(!Set.of("STOP","CONTINUE").contains(decision))throw new Blocked("VALIDATION_FAILED");yield decision.equals("STOP")?"STOP_REVIEWED":"CONTINUE";}default->throw new IllegalArgumentException();};
        var active=ports.active(c,tenant,opportunity).stream().filter(t->SALES_TASKS.contains(t.type())).toList();for(var task:active)if(!ports.permitted(c,actor,owner.organization(),List.of(task.selector()),action.equals("RECORD_CONTRACT_TERMINATION_REVIEW")?"CONTRACT_TERMINATION_REVIEW":"OPPORTUNITY_CLOSE"))throw new Blocked("NOT_AUTHORIZED");
        if(kind.equals("CONTINUE")&&!canResume(c,tenant,owner,current,facts))throw new Blocked("CONTRACT_RESPONSIBILITY_REQUIRED");
        Instant now=ports.now(c);UUID id=id(c);String body=ContractCanonicalJson.encode(Map.of("summary",summary,"humanConfirmed",true,"independentBasis",independentBasis(independent)));
        write(c,"insert into contract.negotiation_disposition(tenant_id,negotiation_disposition_id,opportunity_id,opportunity_revision,previous_disposition_id,request_disposition_id,kind,contract_id,contract_revision,contract_version_id,preparation_workflow_id,signature_workflow_id,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",tenant,id,oid,opportunity.revision(),current==null?null:current.get("negotiation_disposition_id"),action.equals("RECORD_CONTRACT_TERMINATION_REVIEW")?current.get("negotiation_disposition_id"):null,kind,root==null?null:root.get("contract_id"),root==null?null:root.get("revision"),version==null?null:version.get("contract_revision_id"),workflow.get("preparation_workflow_id"),sw==null?null:sw.get("signature_workflow_id"),actor.appointmentId(),protection.seal(tenant,oid,id,ContractProtection.Kind.NEGOTIATION,body),ContractCanonicalJson.digest(body),now);
        var result=fact("contract.negotiation_disposition",id);
        if(Set.of("STOP_UNSIGNED","REQUEST_REVIEW").contains(kind)){
            for(var task:active){var wait=task.state().equals("WAITING")?ports.negotiationWait(c,tenant,task):null;if(task.state().equals("WAITING")&&wait==null)throw stale();
                write(c,"insert into contract.negotiation_cancelled_task(tenant_id,negotiation_cancelled_task_id,disposition_id,task_id,task_revision,prior_state,wait_id,wait_hash,created_at) values(?,?,?,?,?,?,?,?,?)",tenant,id(c),id,task.selector().id(),task.selector().revision(),task.state(),wait==null?null:wait.id(),wait==null?null:Base64.getUrlDecoder().decode(wait.hash()),now);ports.cancelForNegotiation(c,tenant,task,result,now);
            }
            if(kind.equals("REQUEST_REVIEW"))assign(c,actor,opportunity,owner,id,null,facts,now,ports.signatureDue(now,ZoneId.of("Asia/Shanghai")));
        }else{
            var a=assignment(c,tenant,uuid(current.get("negotiation_disposition_id")));var review=ports.read(c,tenant,uuid(a.get("task_id")));ports.complete(c,tenant,review,result,now);
            if(kind.equals("CONTINUE"))for(var m:rows(c,"select * from contract.negotiation_cancelled_task where tenant_id=? and disposition_id=? order by task_id",tenant,current.get("negotiation_disposition_id"))){
                var prior=ports.read(c,tenant,uuid(m.get("task_id")));if(!"OPEN".equals(m.get("prior_state")))throw new Blocked("CONTRACT_RESPONSIBILITY_REQUIRED");ports.resumeAfterNegotiation(c,tenant,prior,result,uuid(m.get("negotiation_cancelled_task_id")),now);
            }
        }
        return result;
    }
    private boolean canResume(Connection c,UUID tenant,Responsibility owner,Map<String,Object> request,List<Subject> facts)throws SQLException{
        if(owner==null)return false;
        for(var member:rows(c,"select * from contract.negotiation_cancelled_task where tenant_id=? and disposition_id=?",tenant,request.get("negotiation_disposition_id"))){
            var task=ports.read(c,tenant,uuid(member.get("task_id")));if(task==null||!"OPEN".equals(member.get("prior_state"))||!"CANCELLED".equals(task.state()))return false;
            String authority=switch(task.type()){case "DECIDE_CONTRACT_PREPARATION"->"CONTRACT_PREPARATION_DECIDE";case "REVIEW_CONTRACT"->"CONTRACT_REVIEW";case "APPROVE_CONTRACT"->"CONTRACT_APPROVE";case "VERIFY_CONTRACT_SIGNATURE","ARCHIVE_CONTRACT_SIGNATURE"->"CONTRACT_SIGNATURE_VERIFY";default->"CONTRACT_PREPARE";};
            if(!ports.eligible(c,tenant,owner.organization(),facts,authority).contains(task.owner())||!ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_READ").contains(task.owner()))return false;
        }return true;
    }
    private List<UUID> reviewers(Connection c,UUID tenant,Responsibility owner,Map<String,Object> request,List<Subject> facts)throws SQLException{
        var readers=new HashSet<>(ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_READ"));
        return ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_TERMINATION_REVIEW").stream().filter(v->!v.equals(request.get("recorded_by"))&&readers.contains(v)).sorted(Comparator.comparing(UUID::toString)).toList();
    }
    RecoveryCandidate recovery(Connection c,Actor actor,Subject o,Responsibility owner,List<Subject> facts)throws SQLException{
        var d=latest(c,actor.tenantId(),o.id());if(d==null||!"REQUEST_REVIEW".equals(d.get("kind")))return null;
        var a=assignment(c,actor.tenantId(),uuid(d.get("negotiation_disposition_id")));if(a==null)throw stale();
        var eligible=reviewers(c,actor.tenantId(),owner,d,facts);boolean missing=a.get("owner_appointment_id")==null;
        if(missing?eligible.isEmpty():eligible.contains(uuid(a.get("owner_appointment_id"))))return null;
        return new RecoveryCandidate(o,owner.basis(),fact("contract.negotiation_disposition",uuid(d.get("negotiation_disposition_id"))),fact("contract.termination_review_assignment",uuid(a.get("termination_review_assignment_id"))),owner.owner());
    }
    Subject reconcile(Connection c,Actor actor,Subject o,Responsibility owner,Map<String,Object> payload,List<Subject> facts)throws SQLException{
        var candidate=recovery(c,actor,o,owner,facts);if(candidate==null)throw stale();expect(payload.get("source"),candidate.source());expect(payload.get("expectedWorkflow"),candidate.workflow());
        var a=assignment(c,actor.tenantId(),candidate.source().id());Instant now=ports.now(c);if(a.get("task_id")!=null){var old=ports.read(c,actor.tenantId(),uuid(a.get("task_id")));if(old==null||!"OPEN".equals(old.state()))throw stale();ports.cancelForContract(c,actor.tenantId(),old,"CONTRACT_AUTHORITY_MISSING",now);}
        return assign(c,actor,o,owner,candidate.source().id(),a,facts,now,time(a.get("due_at")));
    }
    private Subject assign(Connection c,Actor actor,Subject opportunity,Responsibility owner,UUID request,Map<String,Object> previous,List<Subject> facts,Instant now,Instant due)throws SQLException{
        var d=row(c,"select recorded_by from contract.negotiation_disposition where tenant_id=? and negotiation_disposition_id=?",actor.tenantId(),request);
        var candidates=reviewers(c,actor.tenantId(),owner,d,facts);UUID nextOwner=candidates.isEmpty()?null:candidates.getFirst();
        var next=nextOwner==null?null:ports.createTerminationReview(c,actor.tenantId(),nextOwner,opportunity,now,due);UUID id=id(c);
        write(c,"insert into contract.termination_review_assignment(tenant_id,termination_review_assignment_id,request_id,previous_assignment_id,owner_appointment_id,task_id,due_at,recorded_by,created_at) values(?,?,?,?,?,?,?,?,?)",actor.tenantId(),id,request,previous==null?null:previous.get("termination_review_assignment_id"),nextOwner,next==null?null:next.selector().id(),due,actor.appointmentId(),now);return fact("contract.termination_review_assignment",id);
    }
}
