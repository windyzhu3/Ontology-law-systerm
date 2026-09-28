package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;

/** Responsibility-owned state and inheritance writes; caller holds the Opportunity root lock. */
final class JooqOpportunityTaskHandoff {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static OffsetDateTime at(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private static byte[] hash(Subject s){return s.hash()==null?null:Base64.getUrlDecoder().decode(s.hash());}
    private static void stale(){throw new CommandHandler.Rejected("STALE_TASK");}
    static org.jooq.Record row(Connection c,UUID tenant,UUID task){return db(c).fetchOne("select * from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,task);}
    static Subject basis(org.jooq.Record r,Subject opportunity){
        String type=r.get("responsibility_basis_type",String.class);
        return type==null?opportunity:new Subject(type,r.get("responsibility_basis_id",UUID.class),r.get("responsibility_basis_revision",Long.class),r.get("responsibility_basis_hash",byte[].class)==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(r.get("responsibility_basis_hash",byte[].class)));
    }
    static TaskFactory.HandoffResult handoff(Connection c,UUID tenant,Subject handoff,Subject opportunity,Subject expectedBasis,Subject expectedTask,Subject expectedWait,UUID owner,UUID nextId,UUID actor,ZoneId zone)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Opportunity handoff requires READ COMMITTED transaction","25001");
        Objects.requireNonNull(expectedBasis);Objects.requireNonNull(owner);Objects.requireNonNull(nextId);Objects.requireNonNull(actor);Objects.requireNonNull(zone);
        if(!"opportunity.responsibility_handoff".equals(handoff.type())||!Long.valueOf(0).equals(handoff.revision())||handoff.hash()!=null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null)throw new IllegalArgumentException("Exact registered handoff and Opportunity required");
        long key=java.nio.ByteBuffer.wrap(CanonicalJson.digest("R2_INITIAL_OPPORTUNITY_TASK_V1:"+tenant+":"+opportunity.id())).getLong();
        db(c).fetch("select pg_advisory_xact_lock(?)",key);
        var repo=new JooqTaskRepository();var clock=repo.now(c);var type=TaskFactory.Type.PROGRESS_OPPORTUNITY;
        Instant deadline;long seconds;String sla;String state="OPEN";Subject progress=null;R1EventFacts.Wait oldWait=null;
        if(expectedTask==null){
            if(expectedWait!=null||db(c).fetchExists(DSL.selectOne().from("responsibility.task_occurrence").where("tenant_id=? and subject_type='opportunity.opportunity' and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",tenant,opportunity.id())))stale();
            seconds=type.slaSeconds();sla=type.slaCode();deadline=R1BusinessTime.due(clock,seconds,zone);
        }else{
            if(!"responsibility.task_occurrence".equals(expectedTask.type())||expectedTask.revision()==null)throw new IllegalArgumentException("Exact task required");
            repo.lock(c,tenant,expectedTask.id());var task=repo.read(c,tenant,expectedTask.id());var r=row(c,tenant,expectedTask.id());
            if(task==null||!task.selector().equals(expectedTask)||!Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.PREPARE_QUOTE,TaskFactory.Type.SUBMIT_QUOTE_APPROVAL,TaskFactory.Type.DELIVER_QUOTE,TaskFactory.Type.RECORD_QUOTE_REPLY,TaskFactory.Type.REQUEST_CONTRACT_PREPARATION,TaskFactory.Type.PREPARE_CONTRACT,TaskFactory.Type.SUBMIT_CONTRACT_REVIEW,TaskFactory.Type.SUBMIT_CONTRACT_APPROVAL,TaskFactory.Type.SUPPLEMENT_CONTRACT_REVIEW,TaskFactory.Type.ARRANGE_CONTRACT_SIGNATURE,TaskFactory.Type.COLLECT_CONTRACT_SIGNATURE).contains(task.type())||!task.subject().equals(opportunity)||!Set.of("OPEN","WAITING").contains(task.state())||!basis(r,opportunity).equals(expectedBasis)||owner.equals(task.owner()))stale();
            type=task.type();
            deadline=r.get("original_sla_due_at",OffsetDateTime.class).toInstant();seconds=r.get("original_sla_seconds",Long.class);sla=r.get("original_sla_code",String.class);state=task.state();
            if("WAITING".equals(state)){
                oldWait=new JooqEventResponsibilityReader().latestWait(c,tenant,expectedTask.id());
                if(oldWait==null||!oldWait.selector().equals(expectedWait)||oldWait.taskRevision()!=expectedTask.revision())stale();
                progress=verifyWait(c,tenant,task,oldWait,deadline);
            }else if(expectedWait!=null)stale();
            int changed=db(c).execute("update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=cast(? as timestamptz),cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1',cancellation_fact_type=?,cancellation_fact_id=?,cancellation_fact_revision=0 where tenant_id=? and task_occurrence_id=? and revision=? and state=?",at(clock),handoff.type(),handoff.id(),tenant,expectedTask.id(),expectedTask.revision(),state);
            if(changed!=1)stale();
        }
        db(c).execute("insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,revision,subject_type,subject_id,subject_revision,responsibility_basis_type,responsibility_basis_id,responsibility_basis_revision,handoff_predecessor_task_occurrence_id) values (?,?,?,?,?,?,?,?,cast(? as timestamptz),?,cast(? as timestamptz),0,?,?,?, ?,?,0,?)",tenant,nextId,owner,type.name(),type.command,type.completionType,sla,seconds,at(deadline),state,at(clock),opportunity.type(),opportunity.id(),opportunity.revision(),handoff.type(),handoff.id(),expectedTask==null?null:expectedTask.id());
        Subject newWait=null;
        if(oldWait!=null){
            boolean attempt="opportunity.followup_attempt".equals(progress.type());
            boolean quote=type==TaskFactory.Type.RECORD_QUOTE_REPLY||attempt;
            db(c).execute("insert into responsibility.wait_receipt (tenant_id,wait_receipt_id,task_occurrence_id,task_revision,wait_sequence,wait_reason_code,wait_contract_code,wait_contract_version,entered_waiting_at,resume_due_at,recorded_by_appointment_id,handoff_fact_id,handoff_fact_revision,inherited_wait_receipt_id,inherited_wait_hash,origin_progress_id,origin_progress_hash,original_sla_due_at,awaited_fact_type,awaited_fact_id,awaited_fact_hash) values (?,uuidv7(),?,0,1,'OPPORTUNITY_HANDOFF',?,1,cast(? as timestamptz),cast(? as timestamptz),?, ?,0,?,?,?,?,cast(? as timestamptz),?,?,?)",tenant,nextId,attempt?"R2_ATTEMPT_HANDOFF_WAIT_V1":quote?"R2_QUOTE_HANDOFF_WAIT_V1":"R2_OPPORTUNITY_HANDOFF_WAIT_V1",at(clock),at(oldWait.resumeDue()),actor,handoff.id(),oldWait.selector().id(),hash(oldWait.selector()),quote?null:progress.id(),quote?null:hash(progress),at(deadline),quote?progress.type():null,quote?progress.id():null,quote?hash(progress):null);
            newWait=new JooqEventResponsibilityReader().latestWait(c,tenant,nextId).selector();
        }
        return new TaskFactory.HandoffResult(repo.read(c,tenant,nextId),deadline,oldWait==null?null:oldWait.selector(),newWait);
    }
    /** Follow immutable wait links, never reinterpret a cancelled card as a completed predecessor. */
    static Subject verifyWait(Connection c,UUID tenant,TaskFactory.Task task,R1EventFacts.Wait receipt,Instant deadline)throws SQLException {
        var repo=new JooqTaskRepository();var seen=new HashSet<UUID>();var originalDue=receipt.resumeDue();Subject expectedProgress=null;
        for(int depth=0;depth<=64;depth++){
            if(!seen.add(receipt.selector().id())||receipt.version()!=1||!receipt.resumeDue().equals(originalDue))stale();
            var r=row(c,tenant,task.selector().id());
            if(!deadline.equals(r.get("original_sla_due_at",OffsetDateTime.class).toInstant()))stale();
            if("R2_SALES_ATTEMPT_WAIT_V1".equals(receipt.profile())){
                var w=db(c).fetchOne("select * from responsibility.wait_receipt where tenant_id=? and wait_receipt_id=?",tenant,receipt.selector().id());
                UUID predecessor=r.get("predecessor_task_occurrence_id",UUID.class);var prior=predecessor==null?null:repo.read(c,tenant,predecessor);var p=predecessor==null?null:row(c,tenant,predecessor);
                if(w==null||prior==null||!Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.RECORD_QUOTE_REPLY).contains(task.type())||prior.type()!=task.type()||!"CANCELLED".equals(prior.state())||prior.completion()!=null||!prior.subject().equals(task.subject())||!prior.owner().equals(task.owner())||!"R2_FOLLOWUP_ATTEMPT_V1".equals(p.get("cancellation_reason_code",String.class))||!"opportunity.followup_attempt".equals(w.get("awaited_fact_type",String.class))||!w.get("awaited_fact_type").equals(p.get("cancellation_fact_type"))||!w.get("awaited_fact_id").equals(p.get("cancellation_fact_id"))||!Arrays.equals(w.get("awaited_fact_hash",byte[].class),p.get("cancellation_fact_hash",byte[].class))||w.get("awaited_fact_revision")!=null||p.get("cancellation_fact_revision")!=null)stale();
                Subject attempt=new Subject("opportunity.followup_attempt",w.get("awaited_fact_id",UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(w.get("awaited_fact_hash",byte[].class)));
                if(expectedProgress!=null&&!expectedProgress.equals(attempt))stale();return attempt;
            }
            if("R2_QUOTE_FOLLOWUP_V1".equals(receipt.profile())){
                var w=db(c).fetchOne("select * from responsibility.wait_receipt where tenant_id=? and wait_receipt_id=?",tenant,receipt.selector().id());
                if(task.type()!=TaskFactory.Type.RECORD_QUOTE_REPLY||w==null||!"opportunity.quote_response".equals(w.get("awaited_fact_type",String.class))||w.get("awaited_fact_revision")!=null||w.get("awaited_fact_id")==null||w.get("awaited_fact_hash")==null)stale();
                Subject response=new Subject("opportunity.quote_response",w.get("awaited_fact_id",UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(w.get("awaited_fact_hash",byte[].class)));
                if(expectedProgress!=null&&!expectedProgress.equals(response))stale();return response;
            }
            if("R2_OPPORTUNITY_FOLLOWUP_V1".equals(receipt.profile())){
                UUID predecessor=r.get("predecessor_task_occurrence_id",UUID.class);var prior=predecessor==null?null:repo.read(c,tenant,predecessor);
                if(prior==null||!"DONE".equals(prior.state())||prior.type()!=TaskFactory.Type.PROGRESS_OPPORTUNITY||!prior.subject().equals(task.subject())||!prior.owner().equals(task.owner())||prior.completion()==null||!"opportunity.opportunity_progress".equals(prior.completion().type())||expectedProgress!=null&&!expectedProgress.equals(prior.completion()))stale();
                return prior.completion();
            }
            if(depth==64||!Set.of("R2_OPPORTUNITY_HANDOFF_WAIT_V1","R2_QUOTE_HANDOFF_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(receipt.profile()))stale();
            var w=db(c).fetchOne("select * from responsibility.wait_receipt where tenant_id=? and wait_receipt_id=?",tenant,receipt.selector().id());
            UUID predecessor=r.get("handoff_predecessor_task_occurrence_id",UUID.class);var prior=predecessor==null?null:repo.read(c,tenant,predecessor);
            if(w==null||prior==null||!"CANCELLED".equals(prior.state())||prior.type()!=task.type()||!prior.subject().equals(task.subject())||!deadline.equals(w.get("original_sla_due_at",OffsetDateTime.class).toInstant()))stale();
            var priorRow=row(c,tenant,predecessor);var handoff=basis(r,task.subject());
            if(!handoff.id().equals(w.get("handoff_fact_id",UUID.class))||!handoff.id().equals(priorRow.get("cancellation_fact_id",UUID.class))||!handoff.type().equals(priorRow.get("cancellation_fact_type",String.class))||!Long.valueOf(0).equals(priorRow.get("cancellation_fact_revision",Long.class)))stale();
            var inherited=new JooqEventResponsibilityReader().latestWait(c,tenant,predecessor);
            if(inherited==null||!inherited.selector().id().equals(w.get("inherited_wait_receipt_id",UUID.class))||!Arrays.equals(hash(inherited.selector()),w.get("inherited_wait_hash",byte[].class))||inherited.taskRevision()+1!=prior.selector().revision())stale();
            boolean attempt=receipt.profile().equals("R2_ATTEMPT_HANDOFF_WAIT_V1"),quote=task.type()==TaskFactory.Type.RECORD_QUOTE_REPLY;
            if(!attempt&&quote!=receipt.profile().equals("R2_QUOTE_HANDOFF_WAIT_V1"))stale();
            if(attempt&&!"opportunity.followup_attempt".equals(w.get("awaited_fact_type",String.class)))stale();
            Subject progress=new Subject(attempt?"opportunity.followup_attempt":quote?"opportunity.quote_response":"opportunity.opportunity_progress",w.get(quote||attempt?"awaited_fact_id":"origin_progress_id",UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(w.get(quote||attempt?"awaited_fact_hash":"origin_progress_hash",byte[].class)));
            if(expectedProgress!=null&&!expectedProgress.equals(progress))stale();expectedProgress=progress;task=prior;receipt=inherited;
        }
        throw new CommandHandler.Rejected("STALE_TASK");
    }
}
