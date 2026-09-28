package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.*;import java.util.*;
import io.github.windyzhu3.ontologylaw.responsibility.R1BusinessTime;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.jooq.DSLContext;import org.jooq.SQLDialect;import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;
public final class JooqTaskRepository implements TaskFactory {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static UUID id(Connection c){return db(c).select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);}
    private static OffsetDateTime time(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private static String base64(byte[] b){return b==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public HandoffResult handoffOpportunityTask(Connection c,UUID tenant,Subject handoff,Subject opportunity,Subject expectedBasis,Subject expectedTask,Subject expectedWait,UUID newOwner,UUID newTaskId,UUID actor,ZoneId zone)throws SQLException {
        return JooqOpportunityTaskHandoff.handoff(c,tenant,handoff,opportunity,expectedBasis,expectedTask,expectedWait,newOwner,newTaskId,actor,zone);
    }
    public Task read(Connection c,UUID tenant,UUID id){
        var t=TASK_OCCURRENCE;var r=db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).fetchOne();
        if(r==null)return null;
        return new Task(new Subject("responsibility.task_occurrence",id,r.get(t.REVISION),null),r.get(t.OWNER_APPOINTMENT_ID),Type.valueOf(r.get(t.BUSINESS_PURPOSE_CODE)),
            new Subject(r.get(t.SUBJECT_TYPE),r.get(t.SUBJECT_ID),r.get(t.SUBJECT_REVISION),base64(r.get(t.SUBJECT_HASH))),r.get(t.STATE),r.get(t.CREATED_AT).toInstant(),
            r.get(t.COMPLETION_FACT_TYPE)==null?null:new Subject(r.get(t.COMPLETION_FACT_TYPE),r.get(t.COMPLETION_FACT_ID),r.get(t.COMPLETION_FACT_REVISION),base64(r.get(t.COMPLETION_FACT_HASH))));
    }
    public Task currentTask(Connection c,UUID tenant,UUID originalId)throws SQLException {
        UUID id=Objects.requireNonNull(originalId);var visited=new HashSet<UUID>();
        for(int depth=0;depth<64;depth++){
            if(!visited.add(id))throw new CommandHandler.Rejected("STALE_TASK");
            var task=read(c,tenant,id);if(task==null)return null;
            var successors=db(c).fetch("select * from responsibility.task_occurrence where tenant_id=? and handoff_predecessor_task_occurrence_id=?",tenant,id);
            if(successors.isEmpty()){
                if(!"CANCELLED".equals(task.state()))return task;
                var cancelled=db(c).fetchOne("select cancellation_reason_code from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,id);
                if(!"R2_CONTRACT_NEGOTIATION_V1".equals(cancelled.get(0)))return task;
                var resumed=db(c).fetch("select next_task_id from responsibility.contract_task_resumption where tenant_id=? and prior_task_id=?",tenant,id);
                if(resumed.isEmpty())return task;
                if(resumed.size()!=1||!task.type().isContract())throw new CommandHandler.Rejected("STALE_TASK");
                id=resumed.getFirst().get("next_task_id",UUID.class);continue;
            }
            if(successors.size()!=1||!"CANCELLED".equals(task.state())||!Set.of(Type.PREPARE_QUOTE,Type.SUBMIT_QUOTE_APPROVAL,Type.DELIVER_QUOTE,Type.RECORD_QUOTE_REPLY,Type.REQUEST_CONTRACT_PREPARATION,Type.PREPARE_CONTRACT,Type.SUBMIT_CONTRACT_REVIEW,Type.SUBMIT_CONTRACT_APPROVAL,Type.SUPPLEMENT_CONTRACT_REVIEW,Type.ARRANGE_CONTRACT_SIGNATURE,Type.COLLECT_CONTRACT_SIGNATURE).contains(task.type()))throw new CommandHandler.Rejected("STALE_TASK");
            var prior=db(c).fetchOne("select * from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,id);var next=successors.getFirst();
            if(!"R2_OPPORTUNITY_HANDOFF_V1".equals(prior.get("cancellation_reason_code",String.class))
                ||!"opportunity.responsibility_handoff".equals(prior.get("cancellation_fact_type",String.class))
                ||!Objects.equals(prior.get("cancellation_fact_type"),next.get("responsibility_basis_type"))
                ||!Objects.equals(prior.get("cancellation_fact_id"),next.get("responsibility_basis_id"))
                ||!Objects.equals(prior.get("cancellation_fact_revision"),next.get("responsibility_basis_revision")))throw new CommandHandler.Rejected("STALE_TASK");
            for(String field:List.of("subject_type","subject_id","subject_revision","subject_hash","business_purpose_code","primary_command_code","expected_completion_fact_type","original_sla_code","original_sla_seconds","original_sla_due_at"))
                if(!Objects.deepEquals(prior.get(field),next.get(field)))throw new CommandHandler.Rejected("STALE_TASK");
            id=next.get("task_occurrence_id",UUID.class);
        }
        throw new CommandHandler.Rejected("STALE_TASK");
    }
    public void lock(Connection c,UUID tenant,UUID id){var t=TASK_OCCURRENCE;db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).forUpdate().fetch();}
    public Instant now(Connection c){return db(c).select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class).toInstant();}
    public Task reopen(Connection c,UUID tenant,Task task)throws SQLException{
        if(task.type()==Type.PROGRESS_OPPORTUNITY||task.type()==Type.CHECK_CONTRACT_EXECUTION)throw new IllegalArgumentException("Use the guarded business recovery entrypoint");
        return reopenTask(c,tenant,task);
    }
    private Task reopenTask(Connection c,UUID tenant,Task task)throws SQLException{
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        int changed=db(c).update(t).set(t.STATE,"OPEN").set(t.REVISION,revision)
            .where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.eq("WAITING")).execute();
        if(changed!=1)throw new CommandHandler.Rejected("VALIDATION_FAILED");
        return read(c,tenant,task.selector().id());
    }
    public Task reopenFollowupAttempt(Connection c,UUID tenant,Subject expected,Subject opportunity,UUID owner,Subject expectedWait,Subject attempt,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Attempt recovery requires READ COMMITTED transaction","25001");
        lock(c,tenant,expected.id());var task=read(c,tenant,expected.id());var wait=new JooqEventResponsibilityReader().latestWait(c,tenant,expected.id());
        if(task==null||!task.selector().equals(expected)||!Set.of(Type.PROGRESS_OPPORTUNITY,Type.RECORD_QUOTE_REPLY).contains(task.type())||!"WAITING".equals(task.state())||!task.owner().equals(owner)||!task.subject().equals(opportunity)||wait==null||!wait.selector().equals(expectedWait)||wait.taskRevision()!=expected.revision()||wait.version()!=1||!Set.of("R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile())||!wait.resumeDue().equals(due)||due.isAfter(now(c))||!"opportunity.followup_attempt".equals(attempt.type()))throw new CommandHandler.Rejected("STALE_TASK");
        var row=JooqOpportunityTaskHandoff.row(c,tenant,expected.id());
        if(!attempt.equals(JooqOpportunityTaskHandoff.verifyWait(c,tenant,task,wait,row.get("original_sla_due_at",OffsetDateTime.class).toInstant())))throw new CommandHandler.Rejected("STALE_TASK");
        return reopenTask(c,tenant,task);
    }
    public Task reopenOpportunityFollowup(Connection c,UUID tenant,Subject expected,Subject opportunity,UUID owner,Subject expectedWait,Subject progress,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Opportunity recovery requires READ COMMITTED transaction","25001");
        if(expected==null||!"responsibility.task_occurrence".equals(expected.type())||expected.revision()==null)
            throw new IllegalArgumentException("Exact task required");
        lock(c,tenant,expected.id());var task=read(c,tenant,expected.id());
        var wait=new JooqEventResponsibilityReader().latestWait(c,tenant,expected.id());
        if(task==null||!task.selector().equals(expected)||task.type()!=Type.PROGRESS_OPPORTUNITY||!"WAITING".equals(task.state())
                ||!task.owner().equals(owner)||!task.subject().equals(opportunity)||wait==null||!wait.selector().equals(expectedWait)
                ||!Set.of("R2_OPPORTUNITY_FOLLOWUP_V1","R2_OPPORTUNITY_HANDOFF_WAIT_V1").contains(wait.profile())||wait.version()!=1||wait.taskRevision()!=expected.revision()
                ||!wait.resumeDue().equals(due)||due.isAfter(now(c)))throw new CommandHandler.Rejected("STALE_TASK");
        var t=TASK_OCCURRENCE;
        var row=db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(expected.id())).fetchOne();
        if("R2_OPPORTUNITY_HANDOFF_WAIT_V1".equals(wait.profile())) {
            Subject origin=JooqOpportunityTaskHandoff.verifyWait(c,tenant,task,wait,row.get(t.ORIGINAL_SLA_DUE_AT).toInstant());
            if(!origin.equals(progress))throw new CommandHandler.Rejected("STALE_TASK");
            return reopenTask(c,tenant,task);
        }
        UUID predecessor=row.get(t.PREDECESSOR_TASK_OCCURRENCE_ID);
        var prior=predecessor==null?null:read(c,tenant,predecessor);
        if(prior==null||prior.type()!=Type.PROGRESS_OPPORTUNITY||!"DONE".equals(prior.state())||!opportunity.equals(prior.subject())
                ||!owner.equals(prior.owner())||!progress.equals(prior.completion())
                ||!Type.PROGRESS_OPPORTUNITY.command.equals(row.get(t.PRIMARY_COMMAND_CODE))
                ||!Type.PROGRESS_OPPORTUNITY.completionType.equals(row.get(t.EXPECTED_COMPLETION_FACT_TYPE)))
            throw new CommandHandler.Rejected("STALE_TASK");
        return reopenTask(c,tenant,task);
    }
    public List<Task> activeForLead(Connection c,UUID tenant,Subject lead){var t=TASK_OCCURRENCE;return db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq(lead.type())).and(t.SUBJECT_ID.eq(lead.id())).and(t.SUBJECT_REVISION.eq(lead.revision())).and(t.STATE.in("OPEN","WAITING")).fetch(t.TASK_OCCURRENCE_ID).stream().map(id->read(c,tenant,id)).toList();}
    public Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now){
        if(type==Type.PROGRESS_OPPORTUNITY)throw new IllegalArgumentException("Use the guarded initial Opportunity responsibility entrypoint");
        return create(c,tenant,type,owner,lead,zone,now,now);
    }
    public Task createContractTakingOver(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,Task prior,ZoneId zone,Instant now)throws SQLException {
        if(!type.isContract()||prior==null||!opportunity.equals(prior.subject())||!"opportunity.opportunity".equals(opportunity.type()))throw new IllegalArgumentException("Exact prior Opportunity responsibility required");
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Contract takeover requires READ COMMITTED transaction","25001");
        lock(c,tenant,prior.selector().id());var current=read(c,tenant,prior.selector().id());
        if(current==null||!Set.of("DONE","CANCELLED").contains(current.state())||!current.subject().equals(opportunity)||!current.owner().equals(prior.owner())||current.selector().revision()!=prior.selector().revision()+1)throw new CommandHandler.Rejected("STALE_TASK");
        var t=TASK_OCCURRENCE;var row=db(c).select(t.ORIGINAL_SLA_CODE,t.ORIGINAL_SLA_SECONDS,t.ORIGINAL_SLA_DUE_AT).from(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(prior.selector().id())).fetchOne();
        if(row==null||!Set.of("R1_BUSINESS_4H_V1","R2_BUSINESS_4H_V1").contains(row.value1())||row.value2()!=14400)throw new IllegalArgumentException("Registered four-hour business deadline required");
        return create(c,tenant,type,owner,opportunity,zone,now,now,null,new OriginalSla(row.value1(),row.value2(),row.value3()));
    }
    private record OriginalSla(String code,long seconds,OffsetDateTime due){}
    public Task createSignatureTask(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant dueAt)throws SQLException {
        if(!Set.of(Type.CHECK_CONTRACT_RECEIPT,Type.SUPPLEMENT_CONTRACT_RECEIPT,Type.CHECK_CONTRACT_EXECUTION,Type.ARRANGE_CONTRACT_SIGNATURE,Type.COLLECT_CONTRACT_SIGNATURE,Type.VERIFY_CONTRACT_SIGNATURE,Type.ARCHIVE_CONTRACT_SIGNATURE).contains(type)
            ||dueAt==null||dueAt.getNano()%1000!=0||!"opportunity.opportunity".equals(opportunity.type()))throw new IllegalArgumentException("Exact signing responsibility deadline required");
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Signature task requires READ COMMITTED transaction","25001");
        return create(c,tenant,type,owner,opportunity,zone,now,now,null,new OriginalSla(type.slaCode(),type.slaSeconds(),time(dueAt)));
    }
    public Task createTransferTask(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant dueAt)throws SQLException {
        if(!type.isTransfer()||dueAt==null||dueAt.getNano()%1000!=0||!"opportunity.opportunity".equals(opportunity.type()))throw new IllegalArgumentException("Exact transfer responsibility deadline required");
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Transfer task transaction required","25001");
        return create(c,tenant,type,owner,opportunity,zone,now,now,null,new OriginalSla(type.slaCode(),type.slaSeconds(),time(dueAt)));
    }
    public void cancelTransferTask(Connection c,UUID tenant,Task task,Instant now)throws SQLException {
        if(!task.type().isTransfer())throw new IllegalArgumentException("Exact transfer task required");
        var t=TASK_OCCURRENCE;int n=db(c).update(t).set(t.STATE,"CANCELLED").set(t.REVISION,CommandHandler.nextRevision(task.selector().revision())).set(t.CANCELLED_AT,time(now)).set(t.CANCELLATION_REASON_CODE,"TRANSFER_AUTHORITY_MISSING").where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.in("OPEN","WAITING")).execute();
        if(n!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public Task createInitialOpportunity(Connection c,UUID tenant,UUID owner,Subject opportunity,ZoneId zone,Instant now)throws SQLException {
        Objects.requireNonNull(tenant);Objects.requireNonNull(owner);Objects.requireNonNull(zone);Objects.requireNonNull(now);
        if(!Type.PROGRESS_OPPORTUNITY.subjectType().equals(opportunity.type())||opportunity.revision()==null)
            throw new IllegalArgumentException("Exact Opportunity required");
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Initial Opportunity responsibility requires READ COMMITTED transaction","25001");
        // Every supported initial creation path takes this lock, including direct trusted Owner calls.
        // The persisted all-state Task is the durable identity; transaction end releases the lock.
        long key=java.nio.ByteBuffer.wrap(CanonicalJson.digest("R2_INITIAL_OPPORTUNITY_TASK_V1:"+tenant+":"+opportunity.id())).getLong();
        db(c).select(org.jooq.impl.DSL.field("pg_advisory_xact_lock({0})",Object.class,org.jooq.impl.DSL.val(key))).fetch();
        var t=TASK_OCCURRENCE;
        var rows=db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq("opportunity.opportunity"))
            .and(t.SUBJECT_ID.eq(opportunity.id())).and(t.BUSINESS_PURPOSE_CODE.eq(Type.PROGRESS_OPPORTUNITY.name())).and(t.PREDECESSOR_TASK_OCCURRENCE_ID.isNull()).and(DSL.field("handoff_predecessor_task_occurrence_id",UUID.class).isNull()).limit(2).fetch();
        if(rows.size()>1)throw new IllegalStateException("Duplicate initial Opportunity responsibilities require repair");
        if(!rows.isEmpty()) {
            var row=rows.getFirst();var type=Type.PROGRESS_OPPORTUNITY;
            if(!owner.equals(row.get(t.OWNER_APPOINTMENT_ID))||!type.command.equals(row.get(t.PRIMARY_COMMAND_CODE))
                ||!type.completionType.equals(row.get(t.EXPECTED_COMPLETION_FACT_TYPE))||!type.slaCode().equals(row.get(t.ORIGINAL_SLA_CODE))
                ||type.slaSeconds()!=row.get(t.ORIGINAL_SLA_SECONDS)||row.get(t.SUBJECT_REVISION)==null||row.get(t.SUBJECT_HASH)!=null)
                throw new IllegalStateException("Initial Opportunity responsibility does not match its frozen contract");
            return read(c,tenant,row.get(t.TASK_OCCURRENCE_ID));
        }
        // The initial identity must not be backfilled after a later sales responsibility existed.
        // Keep historical tasks and independent approvers intact; only refuse new obsolete work.
        if(db(c).fetchExists(DSL.selectOne().from(t).where(t.TENANT_ID.eq(tenant))
            .and(t.SUBJECT_TYPE.eq("opportunity.opportunity")).and(t.SUBJECT_ID.eq(opportunity.id()))
            .and(t.BUSINESS_PURPOSE_CODE.ne(Type.PROGRESS_OPPORTUNITY.name()))))
            throw new CommandHandler.Rejected("STALE_TASK");
        return create(c,tenant,Type.PROGRESS_OPPORTUNITY,owner,opportunity,zone,now,now);
    }
    public Task createContactRetry(Connection c,UUID tenant,UUID owner,Subject lead,ZoneId zone,Instant now,Instant resume){
        if(!resume.isAfter(now))throw new IllegalArgumentException("Future retry required");
        return create(c,tenant,Type.CONTACT_LEAD,owner,lead,zone,now,resume);
    }
    private Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now,Instant slaOrigin){
        return create(c,tenant,type,owner,lead,zone,now,slaOrigin,null);
    }
    public Task arrangeFollowupAttempt(Connection c,UUID tenant,Task expected,Subject fact,ZoneId zone,Instant now,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Attempt requires transaction","25001");
        if(!Set.of(Type.PROGRESS_OPPORTUNITY,Type.RECORD_QUOTE_REPLY).contains(expected.type())||!"opportunity.followup_attempt".equals(fact.type())||fact.hash()==null||!due.isAfter(now))throw new IllegalArgumentException("Exact attempt arrangement required");
        lock(c,tenant,expected.selector().id());var current=read(c,tenant,expected.selector().id());
        if(current==null||!current.equals(expected)||!Set.of("OPEN","WAITING").contains(current.state()))throw new CommandHandler.Rejected("STALE_TASK");
        int changed=db(c).execute("update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=cast(? as timestamptz),cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1',cancellation_fact_type=?,cancellation_fact_id=?,cancellation_fact_hash=? where tenant_id=? and task_occurrence_id=? and revision=? and state in ('OPEN','WAITING')",time(now),fact.type(),fact.id(),Base64.getUrlDecoder().decode(fact.hash()),tenant,current.selector().id(),current.selector().revision());
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
        var next=create(c,tenant,current.type(),current.owner(),current.subject(),zone,now,due,current.selector().id());
        waitUntil(c,tenant,next,current.owner(),due,now,fact);return read(c,tenant,next.selector().id());
    }
    public Task createOpportunityFollowup(Connection c,UUID tenant,Task predecessor,Subject progress,ZoneId zone,Instant now,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Opportunity continuation requires READ COMMITTED transaction","25001");
        Objects.requireNonNull(due);Objects.requireNonNull(now);
        if(predecessor.type()!=Type.PROGRESS_OPPORTUNITY||!"opportunity.opportunity_progress".equals(progress.type())||progress.hash()==null)
            throw new IllegalArgumentException("Exact Opportunity progress and future follow-up required");
        lock(c,tenant,predecessor.selector().id());
        var current=read(c,tenant,predecessor.selector().id());
        if(current==null||!current.selector().equals(predecessor.selector())||!"DONE".equals(current.state())||!progress.equals(current.completion())
            ||!current.subject().equals(predecessor.subject())||!current.owner().equals(predecessor.owner()))
            throw new CommandHandler.Rejected("STALE_TASK");
        var t=TASK_OCCURRENCE;
        UUID existing=db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant))
            .and(t.PREDECESSOR_TASK_OCCURRENCE_ID.eq(current.selector().id())).fetchOne(t.TASK_OCCURRENCE_ID);
        if(existing!=null){
            var result=read(c,tenant,existing);var wait=new JooqEventResponsibilityReader().latestWait(c,tenant,existing);
            if(result.type()!=Type.PROGRESS_OPPORTUNITY||!result.subject().equals(current.subject())||!result.owner().equals(current.owner())
                ||wait==null||!"R2_OPPORTUNITY_FOLLOWUP_V1".equals(wait.profile())||!due.equals(wait.resumeDue()))
                throw new IllegalStateException("Opportunity successor differs from its frozen contract");
            return result;
        }
        if(!due.isAfter(now))throw new IllegalArgumentException("Future Opportunity follow-up required");
        var next=create(c,tenant,Type.PROGRESS_OPPORTUNITY,current.owner(),current.subject(),zone,now,due,current.selector().id());
        waitUntil(c,tenant,next,current.owner(),due,now);
        return read(c,tenant,next.selector().id());
    }
    private Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now,Instant slaOrigin,UUID predecessor){return create(c,tenant,type,owner,lead,zone,now,slaOrigin,predecessor,null);}
    private Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now,Instant slaOrigin,UUID predecessor,OriginalSla inherited){
        if(!type.subjectType().equals(lead.type())||lead.revision()==null)throw new IllegalArgumentException("Exact registered task Subject required");
        var t=TASK_OCCURRENCE;UUID id=id(c);
        var inheritedBasis=predecessor==null?null:JooqOpportunityTaskHandoff.basis(JooqOpportunityTaskHandoff.row(c,tenant,predecessor),lead);
        if(type!=Type.PROGRESS_OPPORTUNITY&&"opportunity.opportunity".equals(type.subjectType())){
            var h=db(c).fetchOne("select h.responsibility_handoff_id,h.revision from opportunity.responsibility_handoff h where h.tenant_id=? and h.opportunity_id=? and not exists(select 1 from opportunity.responsibility_handoff n where n.tenant_id=h.tenant_id and n.prior_basis_id=h.responsibility_handoff_id and n.prior_basis_type='opportunity.responsibility_handoff')",tenant,lead.id());
            inheritedBasis=h==null?lead:new Subject("opportunity.responsibility_handoff",h.get("responsibility_handoff_id",UUID.class),h.get("revision",Long.class),null);
        }
        db(c).insertInto(t).set(t.TENANT_ID,tenant).set(t.TASK_OCCURRENCE_ID,id).set(t.OWNER_APPOINTMENT_ID,owner)
            .set(t.BUSINESS_PURPOSE_CODE,type.name()).set(t.PRIMARY_COMMAND_CODE,type.command).set(t.EXPECTED_COMPLETION_FACT_TYPE,type.completionType)
            .set(t.ORIGINAL_SLA_CODE,inherited==null?type.slaCode():inherited.code()).set(t.ORIGINAL_SLA_SECONDS,inherited==null?type.slaSeconds():inherited.seconds()).set(t.ORIGINAL_SLA_DUE_AT,inherited==null?time(R1BusinessTime.due(slaOrigin,type.slaSeconds(),zone)):inherited.due())
            .set(t.STATE,"OPEN").set(t.CREATED_AT,time(now)).set(t.REVISION,0L).set(t.SUBJECT_TYPE,lead.type()).set(t.SUBJECT_ID,lead.id()).set(t.SUBJECT_REVISION,lead.revision())
            .set(t.PREDECESSOR_TASK_OCCURRENCE_ID,predecessor)
            .set(DSL.field("responsibility_basis_type",String.class),inheritedBasis==null?null:inheritedBasis.type())
            .set(DSL.field("responsibility_basis_id",UUID.class),inheritedBasis==null?null:inheritedBasis.id())
            .set(DSL.field("responsibility_basis_revision",Long.class),inheritedBasis==null?null:inheritedBasis.revision())
            .set(DSL.field("responsibility_basis_hash",byte[].class),inheritedBasis==null||inheritedBasis.hash()==null?null:Base64.getUrlDecoder().decode(inheritedBasis.hash())).execute();
        return read(c,tenant,id);
    }
    public void cancelForContract(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException{
        boolean takeover="CONTRACT_WORKFLOW_TAKEOVER".equals(reason);
        boolean registered=Set.of("CONTRACT_WORKFLOW_TAKEOVER","CONTRACT_AUTHORITY_MISSING","CONTRACT_RETURNED","CONTRACT_SUPERSEDED").contains(reason);
        if(!registered||!"opportunity.opportunity".equals(task.subject().type())||(!task.type().isContract()&&!takeover))throw new IllegalArgumentException("Registered contract cancellation required");
        var t=TASK_OCCURRENCE;int n=db(c).update(t).set(t.STATE,"CANCELLED").set(t.REVISION,CommandHandler.nextRevision(task.selector().revision())).set(t.CANCELLED_AT,time(now)).set(t.CANCELLATION_REASON_CODE,reason).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.in("OPEN","WAITING")).execute();
        if(n!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public void cancelForQuote(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException{
        if(task.type().isContract()||!Set.of("QUOTE_WORKFLOW_TAKEOVER","QUOTE_AUTHORITY_MISSING","QUOTE_RETURNED").contains(reason)||!"opportunity.opportunity".equals(task.subject().type()))throw new IllegalArgumentException("Quote takeover reason required");
        var t=TASK_OCCURRENCE;int n=db(c).update(t).set(t.STATE,"CANCELLED").set(t.REVISION,CommandHandler.nextRevision(task.selector().revision())).set(t.CANCELLED_AT,time(now)).set(t.CANCELLATION_REASON_CODE,reason).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.in("OPEN","WAITING")).execute();
        if(n!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public void cancelForQuoteTermination(Connection c,UUID tenant,Task task,Subject termination,Instant now)throws SQLException{
        if(!"opportunity.quote_termination".equals(termination.type())||!Long.valueOf(0).equals(termination.revision())||termination.hash()!=null||!"opportunity.opportunity".equals(task.subject().type())||!Set.of("PROGRESS_OPPORTUNITY","PREPARE_QUOTE","SUBMIT_QUOTE_APPROVAL","APPROVE_QUOTE","DELIVER_QUOTE","RECORD_QUOTE_REPLY","RESOLVE_QUOTE_AUTHORITY","PREPARE_CONTRACT").contains(task.type().name()))throw new IllegalArgumentException("Exact quote termination required");
        long revision=CommandHandler.nextRevision(task.selector().revision());
        try(var p=c.prepareStatement("update responsibility.task_occurrence set state='CANCELLED',revision=?,cancelled_at=?,cancellation_reason_code='R2_QUOTE_TERMINATION_V1',cancellation_fact_type='opportunity.quote_termination',cancellation_fact_id=?,cancellation_fact_revision=0 where tenant_id=? and task_occurrence_id=? and revision=? and state in ('OPEN','WAITING')")){
            p.setLong(1,revision);p.setObject(2,now.atOffset(ZoneOffset.UTC));p.setObject(3,termination.id());p.setObject(4,tenant);p.setObject(5,task.selector().id());p.setLong(6,task.selector().revision());if(p.executeUpdate()!=1)throw new CommandHandler.Rejected("STALE_TASK");
        }
    }
    public void cancelForContractNegotiation(Connection c,UUID tenant,Task task,Subject disposition,Instant now)throws SQLException{
        if(!task.type().isContract()||task.type()==Type.REVIEW_CONTRACT_TERMINATION||!"contract.negotiation_disposition".equals(disposition.type())||!Long.valueOf(0).equals(disposition.revision())||disposition.hash()!=null)throw new IllegalArgumentException("Exact contract disposition required");
        int changed=db(c).execute("update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=cast(? as timestamptz),cancellation_reason_code='R2_CONTRACT_NEGOTIATION_V1',cancellation_fact_type='contract.negotiation_disposition',cancellation_fact_id=?,cancellation_fact_revision=0 where tenant_id=? and task_occurrence_id=? and revision=? and state in ('OPEN','WAITING')",time(now),disposition.id(),tenant,task.selector().id(),task.selector().revision());
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public Task createTerminationReview(Connection c,UUID tenant,UUID owner,Subject opportunity,Instant now,Instant due)throws SQLException{
        var type=Type.REVIEW_CONTRACT_TERMINATION;
        return create(c,tenant,type,owner,opportunity,ZoneId.of("Asia/Shanghai"),now,now,null,new OriginalSla(type.slaCode(),type.slaSeconds(),time(due)));
    }
    public Task resumeAfterContractNegotiation(Connection c,UUID tenant,Task prior,Subject disposition,UUID cancelledMember,Instant now)throws SQLException{
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Contract resumption requires transaction","25001");
        if(!prior.type().isContract()||prior.type()==Type.REVIEW_CONTRACT_TERMINATION||!"CANCELLED".equals(prior.state())||!"contract.negotiation_disposition".equals(disposition.type())||!Long.valueOf(0).equals(disposition.revision())||disposition.hash()!=null)throw new IllegalArgumentException("Exact contract resumption required");
        lock(c,tenant,prior.selector().id());if(!prior.equals(read(c,tenant,prior.selector().id())))throw new CommandHandler.Rejected("STALE_TASK");
        var r=db(c).fetchOne("select original_sla_code,original_sla_seconds,original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,prior.selector().id());
        var next=create(c,tenant,prior.type(),prior.owner(),prior.subject(),ZoneId.of("Asia/Shanghai"),now,now,null,new OriginalSla(r.get(0,String.class),r.get(1,Long.class),r.get(2,OffsetDateTime.class)));
        db(c).execute("insert into responsibility.contract_task_resumption(tenant_id,contract_task_resumption_id,disposition_id,cancelled_task_id,prior_task_id,next_task_id,created_at) values(?,?,?,?,?,?,cast(? as timestamptz))",tenant,id(c),disposition.id(),cancelledMember,prior.selector().id(),next.selector().id(),time(now));return next;
    }
    public void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException{
        if(!task.type().completionType.equals(fact.type()))throw new IllegalArgumentException("Wrong completion fact");
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        int changed=db(c).update(t).set(t.STATE,"DONE").set(t.REVISION,revision).set(t.COMPLETED_AT,time(now)).set(t.COMPLETION_FACT_TYPE,fact.type())
            .set(t.COMPLETION_FACT_ID,fact.id()).set(t.COMPLETION_FACT_REVISION,fact.revision()).set(t.COMPLETION_FACT_HASH,fact.hash()==null?null:Base64.getUrlDecoder().decode(fact.hash()))
            .where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.in(task.type()==Type.RECORD_QUOTE_REPLY?List.of("OPEN","WAITING"):List.of("OPEN"))).execute();
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public Subject decision(Connection c,UUID tenant,Task task,UUID actor,String contract,String decision,String rationale,Map<String,Object> digestValues,Instant now)throws SQLException{
        validateDecision(tenant,task,contract,decision,rationale,digestValues);
        var d=DECISION_RECORD;UUID id=id(c);byte[] digest=CanonicalJson.digest(CanonicalJson.encode(digestValues));
        db(c).insertInto(d).set(d.TENANT_ID,tenant).set(d.DECISION_RECORD_ID,id).set(d.TASK_OCCURRENCE_ID,task.selector().id()).set(d.DECISION_VERSION,1)
            .set(d.DECIDED_BY_APPOINTMENT_ID,actor).set(d.AUTHORITY_SLOT_CODE,task.type().slot).set(d.DECISION_CONTRACT_CODE,contract).set(d.DECISION_CONTRACT_VERSION,1)
            .set(d.DECISION_CODE,decision).set(d.CONTENT_DIGEST,digest).set(d.RATIONALE_SUMMARY,rationale).set(d.DECIDED_AT,time(now))
            .set(d.DECISION_SUBJECT_TYPE,task.lead().type()).set(d.DECISION_SUBJECT_ID,task.lead().id()).set(d.DECISION_SUBJECT_REVISION,task.lead().revision()).execute();
        return new Subject("responsibility.decision_record",id,null,base64(digest));
    }
    /** The Owner accepts only the closed Task3 contracts, including their exact digest coverage. */
    private static void validateDecision(UUID tenant,Task task,String contract,String code,String rationale,Map<String,Object> values)throws SQLException {
        String expectedContract=switch(task.type()) {
            case RESOLVE_LEAD_DUPLICATE -> "LEAD_DUPLICATE_RESOLUTION";
            case RESOLVE_SOURCE_REQUEST -> "SOURCE_REQUEST_CONTINUATION";
            case RESOLVE_LEAD_ROUTING_GAP -> "LEAD_ROUTING_DISPOSITION";
            case ACK_SOURCE_INTAKE_STOP_REQUEST -> "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED";
            case REVIEW_LEAD_VALIDITY -> "LEAD_VALIDITY_REVIEW";
            default -> throw new IllegalArgumentException("Task has no Task3 Decision contract");
        };
        Set<String> codes=switch(task.type()) {
            case RESOLVE_LEAD_DUPLICATE -> Set.of("LINK_EXISTING_PARTY","KEEP_SEPARATE");
            case RESOLVE_SOURCE_REQUEST -> Set.of("ASSIGN_SELECTED","SCHEDULE_REVIEW","END_LEAD");
            case RESOLVE_LEAD_ROUTING_GAP -> Set.of("SCHEDULE_ROUTING_REVIEW","RETRY_ASSIGNMENT_NOW","REQUEST_SOURCE_INTAKE_STOP");
            case REVIEW_LEAD_VALIDITY -> Set.of("CONFIRM_INVALID","CLOSE_UNREACHED","REOPEN_CONTACT");
            default -> Set.of(expectedContract);
        };
        if(!expectedContract.equals(contract)||!codes.contains(code)||rationale==null||rationale.isBlank())throw new IllegalArgumentException("Unregistered Decision");
        var expected=new TreeMap<String,Object>();
        expected.put("tenantId",tenant.toString());expected.put("subject",Map.of("type",task.lead().type(),"id",task.lead().id().toString(),"revision",task.lead().revision()));
        expected.put("authoritySlot",task.type().slot);expected.put("decisionCode",code);expected.put("rationaleSummary",rationale);
        if(task.type()==Type.RESOLVE_LEAD_DUPLICATE) {
            for(String key:List.of("candidateLeadId","partyId")) {
                Object value=values.get(key);if(!(value instanceof String id)||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Exact candidate required");expected.put(key,value);
            }
            for(String key:List.of("candidateLeadRevision","partyRevision")) {
                Object value=values.get(key);if(!(value instanceof Long revision)||revision<0||revision>9007199254740991L)throw new IllegalArgumentException("Exact candidate revision required");expected.put(key,value);
            }
            expected.put("newRevision",CommandHandler.nextRevision(task.lead().revision()));
            var changes=new TreeMap<String,Object>();changes.put("disposition_code",code);
            if(code.equals("LINK_EXISTING_PARTY")){changes.put("parsed_party_id",values.get("partyId"));changes.put("party_resolution_code","RESOLVED");}
            expected.put("newValues",changes);
        }
        if(task.type()==Type.ACK_SOURCE_INTAKE_STOP_REQUEST||task.type()==Type.RESOLVE_SOURCE_REQUEST) {
            var causal=new Subject("responsibility.decision_record",UUID.fromString((String)values.get("causalDecisionId")),null,(String)values.get("causalDecisionHash"));
            expected.put("causalDecisionId",causal.id().toString());expected.put("causalDecisionHash",causal.hash());
        }
        if(task.type()==Type.RESOLVE_SOURCE_REQUEST){
            if(code.equals("ASSIGN_SELECTED")){var selected=UUID.fromString((String)values.get("ownerAppointmentId"));expected.put("ownerAppointmentId",selected.toString());}
            if(code.equals("SCHEDULE_REVIEW")){var due=java.time.OffsetDateTime.parse((String)values.get("reviewAt"));if(due.getNano()%1000!=0)throw new IllegalArgumentException("Exact review time required");expected.put("reviewAt",values.get("reviewAt"));}
        }
        if(task.type()==Type.REVIEW_LEAD_VALIDITY){
            var causal=new Subject("lead.lead_contact_result",UUID.fromString((String)values.get("triggeringContactResultId")),null,(String)values.get("triggeringContactResultHash"));
            expected.put("triggeringContactResultId",causal.id().toString());expected.put("triggeringContactResultHash",causal.hash());
        }
        if(!expected.equals(values))throw new IllegalArgumentException("Wrong Decision digest coverage");
    }
    public Task waitForContractReceipt(Connection c,UUID tenant,Task task,UUID actor,Instant now,Subject version)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Contract receipt wait requires command transaction","25001");
        if(task.type()!=Type.CHECK_CONTRACT_EXECUTION||version==null||!"contract.contract_revision".equals(version.type())||version.hash()==null)throw new IllegalArgumentException("Exact contract version required");
        lock(c,tenant,task.selector().id());if(!task.equals(read(c,tenant,task.selector().id()))||!"OPEN".equals(task.state()))throw new CommandHandler.Rejected("STALE_TASK");
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        if(db(c).update(t).set(t.STATE,"WAITING").set(t.REVISION,revision).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.eq("OPEN")).execute()!=1)throw new CommandHandler.Rejected("STALE_TASK");
        var w=WAIT_RECEIPT;int sequence=db(c).select(DSL.coalesce(DSL.max(w.WAIT_SEQUENCE),0)).from(w).where(w.TENANT_ID.eq(tenant)).and(w.TASK_OCCURRENCE_ID.eq(task.selector().id())).fetchOne(0,Integer.class)+1;
        db(c).insertInto(w).set(w.TENANT_ID,tenant).set(w.WAIT_RECEIPT_ID,id(c)).set(w.TASK_OCCURRENCE_ID,task.selector().id()).set(w.TASK_REVISION,revision).set(w.WAIT_SEQUENCE,sequence)
            .set(w.WAIT_REASON_CODE,"CONTRACT_REQUIRED_RECEIPT").set(w.WAIT_CONTRACT_CODE,"R2_CONTRACT_RECEIPT_WAIT_V1").set(w.WAIT_CONTRACT_VERSION,1)
            .set(w.AWAITED_FACT_TYPE,version.type()).set(w.AWAITED_FACT_ID,version.id()).set(w.AWAITED_FACT_HASH,Base64.getUrlDecoder().decode(version.hash()))
            .set(w.ENTERED_WAITING_AT,time(now)).set(w.RECORDED_BY_APPOINTMENT_ID,actor).execute();
        return read(c,tenant,task.selector().id());
    }
    public Task resumeContractReceipt(Connection c,UUID tenant,Task task,Subject version)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Contract receipt resumption requires command transaction","25001");
        if(task.type()!=Type.CHECK_CONTRACT_EXECUTION||version==null||!"contract.contract_revision".equals(version.type())||version.hash()==null)throw new IllegalArgumentException("Exact contract version required");
        lock(c,tenant,task.selector().id());if(!task.equals(read(c,tenant,task.selector().id()))||!"WAITING".equals(task.state()))throw new CommandHandler.Rejected("STALE_TASK");
        var w=WAIT_RECEIPT;
        if(!db(c).fetchExists(db(c).selectOne().from(w).where(w.TENANT_ID.eq(tenant)).and(w.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(w.TASK_REVISION.eq(task.selector().revision()))
            .and(w.WAIT_CONTRACT_CODE.eq("R2_CONTRACT_RECEIPT_WAIT_V1")).and(w.WAIT_CONTRACT_VERSION.eq(1)).and(w.RESUME_DUE_AT.isNull())
            .and(w.AWAITED_FACT_TYPE.eq(version.type())).and(w.AWAITED_FACT_ID.eq(version.id())).and(w.AWAITED_FACT_HASH.eq(Base64.getUrlDecoder().decode(version.hash())))))throw new CommandHandler.Rejected("STALE_TASK");
        return reopenTask(c,tenant,task);
    }
    public Subject waitForQuoteReply(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject response)throws SQLException{if(task.type()!=Type.RECORD_QUOTE_REPLY||response==null||!"opportunity.quote_response".equals(response.type())||response.hash()==null)throw new IllegalArgumentException("Exact quote response required");return waitUntil(c,tenant,task,actor,due,now,response);}
    public Subject waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now)throws SQLException{return waitUntil(c,tenant,task,actor,due,now,null);}
    private Subject waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject response)throws SQLException{
        if(task.type()!=Type.RESOLVE_SOURCE_REQUEST&&task.type()!=Type.RESOLVE_LEAD_ROUTING_GAP&&task.type()!=Type.CONTACT_LEAD&&task.type()!=Type.PROGRESS_OPPORTUNITY&&task.type()!=Type.RECORD_QUOTE_REPLY||!due.isAfter(now))throw new IllegalArgumentException("Registered timed wait required");
        if(task.type()==Type.PROGRESS_OPPORTUNITY){
            var table=TASK_OCCURRENCE;
            UUID predecessor=db(c).select(table.PREDECESSOR_TASK_OCCURRENCE_ID).from(table).where(table.TENANT_ID.eq(tenant)).and(table.TASK_OCCURRENCE_ID.eq(task.selector().id())).fetchOne(table.PREDECESSOR_TASK_OCCURRENCE_ID);
            if(predecessor==null)throw new IllegalArgumentException("Only a causal Opportunity successor can enter a follow-up wait");
        }
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        int changed=db(c).update(t).set(t.STATE,"WAITING").set(t.REVISION,revision).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id()))
            .and(t.STATE.eq("OPEN")).and(t.REVISION.eq(task.selector().revision())).execute();
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
        boolean attempt=response!=null&&"opportunity.followup_attempt".equals(response.type());
        var w=WAIT_RECEIPT;
        int sequence=db(c).select(DSL.coalesce(DSL.max(w.WAIT_SEQUENCE),0)).from(w).where(w.TENANT_ID.eq(tenant)).and(w.TASK_OCCURRENCE_ID.eq(task.selector().id())).fetchOne(0,Integer.class)+1;
        db(c).insertInto(w).set(w.TENANT_ID,tenant).set(w.WAIT_RECEIPT_ID,id(c)).set(w.TASK_OCCURRENCE_ID,task.selector().id()).set(w.TASK_REVISION,revision).set(w.WAIT_SEQUENCE,sequence)
            .set(w.WAIT_REASON_CODE,task.type()==Type.RESOLVE_SOURCE_REQUEST?"SOURCE_REQUEST_REVIEW":attempt?"SALES_FOLLOWUP_ATTEMPT":task.type()==Type.RECORD_QUOTE_REPLY?"QUOTE_FOLLOWUP":task.type()==Type.PROGRESS_OPPORTUNITY?"OPPORTUNITY_FOLLOWUP":task.type()==Type.CONTACT_LEAD?"CONTACT_RETRY":"ROUTING_REVIEW_WINDOW").set(w.WAIT_CONTRACT_CODE,task.type()==Type.RESOLVE_SOURCE_REQUEST?"R2_SOURCE_REQUEST_REVIEW_WAIT_V1":attempt?"R2_SALES_ATTEMPT_WAIT_V1":task.type()==Type.RECORD_QUOTE_REPLY?"R2_QUOTE_FOLLOWUP_V1":task.type()==Type.PROGRESS_OPPORTUNITY?"R2_OPPORTUNITY_FOLLOWUP_V1":task.type()==Type.CONTACT_LEAD?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1").set(w.WAIT_CONTRACT_VERSION,1)
            .set(w.AWAITED_FACT_TYPE,response==null?null:response.type()).set(w.AWAITED_FACT_ID,response==null?null:response.id()).set(w.AWAITED_FACT_HASH,response==null?null:java.util.Base64.getUrlDecoder().decode(response.hash()))
            .set(w.ENTERED_WAITING_AT,time(now)).set(w.RESUME_DUE_AT,time(due)).set(w.RECORDED_BY_APPOINTMENT_ID,actor).execute();
        return new JooqEventResponsibilityReader().latestWait(c,tenant,task.selector().id()).selector();
    }
    public Task createSourceRequestContinuation(Connection c,UUID tenant,UUID owner,Task prior,Subject decision,ZoneId zone,Instant now)throws SQLException{
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Source continuation requires READ COMMITTED transaction","25001");
        if(!Set.of(Type.ACK_SOURCE_INTAKE_STOP_REQUEST,Type.RESOLVE_SOURCE_REQUEST).contains(prior.type())||!"responsibility.decision_record".equals(decision.type())||decision.hash()==null)throw new IllegalArgumentException("Exact source decision required");
        lock(c,tenant,prior.selector().id());var current=read(c,tenant,prior.selector().id());
        if(current==null||!"DONE".equals(current.state())||current.selector().revision()!=prior.selector().revision()+1||!decision.equals(current.completion())||!activeForLead(c,tenant,current.lead()).isEmpty())throw new CommandHandler.Rejected("STALE_TASK");
        OriginalSla sla=null;
        if(prior.type()==Type.RESOLVE_SOURCE_REQUEST){
            var row=db(c).fetchOne("select original_sla_code,original_sla_seconds,original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,prior.selector().id());
            sla=new OriginalSla(row.get(0,String.class),row.get(1,Long.class),row.get(2,OffsetDateTime.class));
        }
        return create(c,tenant,Type.RESOLVE_SOURCE_REQUEST,owner,current.lead(),zone,now,now,null,sla);
    }
    public Task restoreHistoricalSourceRequest(Connection c,UUID tenant,UUID owner,Task completedAck,ZoneId zone,Instant now)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Historical source repair requires READ COMMITTED transaction","25001");
        Objects.requireNonNull(owner);Objects.requireNonNull(zone);Objects.requireNonNull(now);
        if(completedAck==null||completedAck.type()!=Type.ACK_SOURCE_INTAKE_STOP_REQUEST||!"DONE".equals(completedAck.state())
            ||completedAck.completion()==null||!"responsibility.decision_record".equals(completedAck.completion().type())||completedAck.completion().hash()==null)
            throw new IllegalArgumentException("Exact completed ACK required");
        lock(c,tenant,completedAck.selector().id());
        if(!completedAck.equals(read(c,tenant,completedAck.selector().id())))throw new CommandHandler.Rejected("STALE_TASK");
        var row=db(c).fetchOne("select completed_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",tenant,completedAck.selector().id());
        Instant completed=row.get(0,OffsetDateTime.class).toInstant();
        var d=DECISION_RECORD;
        boolean exact=db(c).fetchExists(db(c).selectOne().from(d).where(d.TENANT_ID.eq(tenant))
            .and(d.DECISION_RECORD_ID.eq(completedAck.completion().id())).and(d.TASK_OCCURRENCE_ID.eq(completedAck.selector().id()))
            .and(d.CONTENT_DIGEST.eq(Base64.getUrlDecoder().decode(completedAck.completion().hash())))
            .and(d.DECISION_CODE.eq("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED")).and(d.DECISION_CONTRACT_CODE.eq("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED"))
            .and(d.DECISION_CONTRACT_VERSION.eq(1)).and(d.DECISION_VERSION.eq(1)).and(d.AUTHORITY_SLOT_CODE.eq(Type.ACK_SOURCE_INTAKE_STOP_REQUEST.slot))
            .and(d.DECISION_SUBJECT_TYPE.eq(completedAck.subject().type())).and(d.DECISION_SUBJECT_ID.eq(completedAck.subject().id()))
            .and(d.DECISION_SUBJECT_REVISION.eq(completedAck.subject().revision())).and(d.DECIDED_AT.eq(time(completed))));
        if(!exact||completed.isAfter(now)||causalStop(c,tenant,completedAck)==null)throw new CommandHandler.Rejected("STALE_TASK");
        var t=TASK_OCCURRENCE;
        boolean advanced=db(c).fetchExists(db(c).selectOne().from(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq(completedAck.subject().type()))
            .and(t.SUBJECT_ID.eq(completedAck.subject().id())).and(t.TASK_OCCURRENCE_ID.ne(completedAck.selector().id()))
            .and(t.STATE.in("OPEN","WAITING").or(t.BUSINESS_PURPOSE_CODE.eq(Type.RESOLVE_SOURCE_REQUEST.name())).or(t.CREATED_AT.ge(time(completed)))));
        if(advanced)throw new CommandHandler.Rejected("STALE_TASK");
        var type=Type.RESOLVE_SOURCE_REQUEST;
        return create(c,tenant,type,owner,completedAck.subject(),zone,now,now,null,
            new OriginalSla(type.slaCode(),type.slaSeconds(),time(R1BusinessTime.due(completed,type.slaSeconds(),zone))));
    }
    public Subject waitForSourceRequestReview(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject decision)throws SQLException{
        if(task.type()!=Type.RESOLVE_SOURCE_REQUEST||!"responsibility.decision_record".equals(decision.type())||decision.hash()==null)throw new IllegalArgumentException("Source review decision required");
        return waitUntil(c,tenant,task,actor,due,now,decision);
    }
    public Subject causalSourceRequest(Connection c,UUID tenant,Task task){
        if(task.type()!=Type.RESOLVE_SOURCE_REQUEST)throw new IllegalArgumentException("Source continuation task required");
        var d=DECISION_RECORD;var t=TASK_OCCURRENCE;
        var rows=db(c).select(d.DECISION_RECORD_ID,d.CONTENT_DIGEST).from(d).join(t).on(t.TENANT_ID.eq(d.TENANT_ID)).and(t.TASK_OCCURRENCE_ID.eq(d.TASK_OCCURRENCE_ID))
            .where(d.TENANT_ID.eq(tenant)).and(d.DECISION_CODE.eq("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED")).and(d.DECISION_CONTRACT_CODE.eq("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED"))
            .and(d.DECISION_CONTRACT_VERSION.eq(1)).and(d.DECISION_VERSION.eq(1)).and(d.AUTHORITY_SLOT_CODE.eq(Type.ACK_SOURCE_INTAKE_STOP_REQUEST.slot))
            .and(d.DECISION_SUBJECT_TYPE.eq(task.lead().type())).and(d.DECISION_SUBJECT_ID.eq(task.lead().id())).and(d.DECISION_SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.BUSINESS_PURPOSE_CODE.eq(Type.ACK_SOURCE_INTAKE_STOP_REQUEST.name())).and(t.PRIMARY_COMMAND_CODE.eq(Type.ACK_SOURCE_INTAKE_STOP_REQUEST.command))
            .and(t.EXPECTED_COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_REVISION.isNull())
            .and(d.DECIDED_AT.le(time(task.createdAt()))).and(t.STATE.eq("DONE")).and(t.SUBJECT_TYPE.eq(task.lead().type())).and(t.SUBJECT_ID.eq(task.lead().id())).and(t.SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_ID.eq(d.DECISION_RECORD_ID)).and(t.COMPLETION_FACT_HASH.eq(d.CONTENT_DIGEST))
            .orderBy(d.DECIDED_AT.desc(),d.DECISION_RECORD_ID.desc()).limit(1).fetchOne();
        return rows==null?null:new Subject("responsibility.decision_record",rows.value1(),null,base64(rows.value2()));
    }

    public UUID sourceRequestOriginOwner(Connection c,UUID tenant,Task acknowledgement){
        var source=causalStop(c,tenant,acknowledgement);
        if(source==null)return null;
        var d=DECISION_RECORD;var t=TASK_OCCURRENCE;
        return db(c).select(t.OWNER_APPOINTMENT_ID).from(d).join(t)
            .on(t.TENANT_ID.eq(d.TENANT_ID)).and(t.TASK_OCCURRENCE_ID.eq(d.TASK_OCCURRENCE_ID))
            .where(d.TENANT_ID.eq(tenant)).and(d.DECISION_RECORD_ID.eq(source.id()))
            .and(d.CONTENT_DIGEST.eq(Base64.getUrlDecoder().decode(source.hash())))
            .fetchOne(t.OWNER_APPOINTMENT_ID);
    }
    public Subject causalStop(Connection c,UUID tenant,Task task){
        if(task.type()!=Type.ACK_SOURCE_INTAKE_STOP_REQUEST)throw new IllegalArgumentException("ACK Task required");
        var d=DECISION_RECORD;var t=TASK_OCCURRENCE;
        var rows=db(c).select(d.DECISION_RECORD_ID,d.CONTENT_DIGEST).from(d).join(t).on(t.TENANT_ID.eq(d.TENANT_ID)).and(t.TASK_OCCURRENCE_ID.eq(d.TASK_OCCURRENCE_ID))
            .where(d.TENANT_ID.eq(tenant)).and(d.DECISION_CODE.eq("REQUEST_SOURCE_INTAKE_STOP")).and(d.DECISION_CONTRACT_CODE.eq("LEAD_ROUTING_DISPOSITION"))
            .and(d.DECISION_CONTRACT_VERSION.eq(1)).and(d.DECISION_VERSION.eq(1)).and(d.AUTHORITY_SLOT_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.slot))
            .and(d.DECISION_SUBJECT_TYPE.eq(task.lead().type())).and(d.DECISION_SUBJECT_ID.eq(task.lead().id())).and(d.DECISION_SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.BUSINESS_PURPOSE_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.name())).and(t.PRIMARY_COMMAND_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.command))
            .and(t.EXPECTED_COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_REVISION.isNull())
            .and(d.DECIDED_AT.le(time(task.createdAt()))).and(t.STATE.eq("DONE")).and(t.SUBJECT_TYPE.eq(task.lead().type())).and(t.SUBJECT_ID.eq(task.lead().id())).and(t.SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_ID.eq(d.DECISION_RECORD_ID)).and(t.COMPLETION_FACT_HASH.eq(d.CONTENT_DIGEST))
            .orderBy(d.DECIDED_AT.desc(),d.DECISION_RECORD_ID.desc()).limit(1).fetchOne();
        return rows==null?null:new Subject("responsibility.decision_record",rows.value1(),null,base64(rows.value2()));
    }
}
