package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Only Opportunity facts are written here; task mutation, identity and audit remain in their named Owners. */
public final class JooqOpportunityOwnerExceptionService implements OpportunityOwnerExceptionService {
    private static final String EXCEPTION="opportunity.owner_exception";
    private static final String DISPOSITION="opportunity.owner_exception_disposition";
    private static final String HANDOFF="opportunity.responsibility_handoff";
    private final ObservationProbe probe;
    private final ReceiverGuard receivers;
    private final TaskHandoffPort tasks;
    private final OpportunityResponsibilityReader responsibility=OpportunityResponsibilityReader.databaseBacked();
    public JooqOpportunityOwnerExceptionService(ObservationProbe probe,ReceiverGuard receivers,TaskHandoffPort tasks) {
        this.probe=Objects.requireNonNull(probe); this.receivers=Objects.requireNonNull(receivers); this.tasks=Objects.requireNonNull(tasks);
    }
    private record Root(UUID owner,boolean closed) {}
    public Optional<Snapshot> observe(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        var root=lock(c,tenant,opportunity); var old=active(c,tenant,opportunity.id()); var now=now(c);
        if(root.closed()) {
            if(old==null) return Optional.empty();
            return Optional.of(append(c,tenant,old,old.opportunity(),old.responsibility(),old.task(),old.waitReceipt(),old.reasons(),State.NO_LONGER_APPLICABLE,now,old.lastDispositionId(),old.reviewDueAt(),"OPPORTUNITY_CLOSED",opportunity));
        }
        var current=responsibility.current(c,tenant,opportunity);
        var observed=Objects.requireNonNull(probe.inspect(c,tenant,opportunity,current,now));
        validateObservation(observed);
        if(observed.reasons().isEmpty()) {
            if(old==null) return Optional.empty();
            if(observed.validationEvidence()==null||!"audit.audit_entry".equals(observed.validationEvidence().type())||observed.validationEvidence().hash()==null)throw new IllegalArgumentException("Durable validation audit required");
            return Optional.of(append(c,tenant,old,opportunity,current,observed.task(),observed.waitReceipt(),old.reasons(),State.RESOLVED,now,old.lastDispositionId(),old.reviewDueAt(),"OWNER_VALIDATED",observed.validationEvidence()));
        }
        // Missing routing alone must never interrupt an otherwise normal responsibility.
        if(old==null && observed.reasons().equals(Set.of(Reason.SUPERVISOR_UNRESOLVED))) return Optional.empty();
        var state=old!=null && old.state()==State.COORDINATING && old.reviewDueAt().isAfter(now) ? State.COORDINATING : State.ACTIVE;
        if(old==null) {
            var created=new Snapshot(new Subject(EXCEPTION,UUID.randomUUID(),0L,null),opportunity,root.owner(),current,
                    observed.task(),observed.waitReceipt(),observed.reasons(),state,now,now,null,null,null,null);
            insert(c,tenant,created); return Optional.of(created);
        }
        return Optional.of(append(c,tenant,old,opportunity,current,observed.task(),observed.waitReceipt(),observed.reasons(),state,now,old.lastDispositionId(),old.reviewDueAt(),null,null));
    }
    public Snapshot coordinate(Connection c,UUID tenant,Decision decision,Instant reviewDueAt)throws SQLException {
        var old=checkedDecision(c,tenant,decision); var now=now(c);
        var current=Objects.requireNonNull(probe.inspect(c,tenant,decision.opportunity(),old.responsibility(),now));
        validateObservation(current);
        if(!Objects.equals(old.task(),current.task()) || !Objects.equals(old.waitReceipt(),current.waitReceipt()))throw stale("Responsibility changed");
        if(reviewDueAt==null || !reviewDueAt.isAfter(now)) throw stale("Review time must be in the future");
        var id=UUID.randomUUID();
        disposition(c,tenant,id,old,decision,"COORDINATION",now,reviewDueAt,null,null);
        return append(c,tenant,old,old.opportunity(),old.responsibility(),old.task(),old.waitReceipt(),old.reasons(),State.COORDINATING,old.lastObservedAt(),id,reviewDueAt,null,null);
    }
    public TransferResult transfer(Connection c,UUID tenant,Decision decision,UUID receiver,ZoneId zone)throws SQLException {
        Objects.requireNonNull(receiver); Objects.requireNonNull(zone);
        var old=checkedDecision(c,tenant,decision); var now=now(c);
        var inspection=Objects.requireNonNull(probe.inspect(c,tenant,decision.opportunity(),old.responsibility(),now));
        validateObservation(inspection);
        if(inspection.reasons().contains(Reason.SOURCE_INCONSISTENT) || old.reasons().contains(Reason.SOURCE_INCONSISTENT))
            throw stale("Opening source requires repair before transfer");
        if(!Objects.equals(old.task(),inspection.task()) || !Objects.equals(old.waitReceipt(),inspection.waitReceipt())) throw stale("Responsibility changed");
        receivers.verify(c,tenant,decision.opportunity(),receiver,old.task(),old.waitReceipt());
        if(receiver.equals(old.responsibility().appointmentId()))
            return new TransferResult(old,null,null,null,false);
        try(var p=c.prepareStatement("select count(*) from opportunity.responsibility_handoff where tenant_id=? and opportunity_id=?")) {
            bind(p,tenant,decision.opportunity().id());try(var r=p.executeQuery()){r.next();if(r.getInt(1)>=64)throw stale("Responsibility chain requires controlled repair");}
        }
        var handoff=new Subject(HANDOFF,UUID.randomUUID(),0L,null);
        var disposition=new Subject(DISPOSITION,UUID.randomUUID(),0L,null);
        UUID newTaskId=UUID.randomUUID();
        var result=Objects.requireNonNull(tasks.handoff(c,tenant,handoff,decision.opportunity(),old.responsibility(),old.task(),old.waitReceipt(),receiver,newTaskId,decision.actor(),zone));
        typed(result.newTask(),"responsibility.task_occurrence");
        if(!newTaskId.equals(result.newTask().id()) || !Objects.equals(result.originalWait(),old.waitReceipt()) || result.originalDueAt()==null)
            throw stale("Task handoff returned inconsistent facts");
        if(result.newWait()!=null) hashedWait(result.newWait());
        if((old.waitReceipt()==null)!=(result.newWait()==null)) throw stale("Task handoff did not preserve waiting responsibility");
        disposition(c,tenant,disposition.id(),old,decision,"TRANSFER",now,null,receiver,handoff.id());
        execute(c,"insert into opportunity.responsibility_handoff (tenant_id,responsibility_handoff_id,revision,opportunity_id,opportunity_revision,prior_basis_type,prior_basis_id,prior_basis_revision,from_appointment_id,to_appointment_id,actor_appointment_id,owner_exception_disposition_id,old_task_occurrence_id,old_task_revision,new_task_occurrence_id,new_task_revision,original_due_at,original_wait_receipt_id,original_wait_hash,handed_off_at) values (?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenant,handoff.id(),decision.opportunity().id(),decision.opportunity().revision(),old.responsibility().basis().type(),old.responsibility().basis().id(),old.responsibility().basis().revision(),old.responsibility().appointmentId(),receiver,decision.actor(),disposition.id(),id(old.task()),revision(old.task()),result.newTask().id(),result.newTask().revision(),result.originalDueAt(),id(old.waitReceipt()),digest(old.waitReceipt()),now);
        var updated=append(c,tenant,old,old.opportunity(),new OpportunityResponsibilityReader.Responsibility(handoff,receiver),result.newTask(),result.newWait(),old.reasons(),State.RESOLVED,now,disposition.id(),null,"TRANSFER",handoff);
        // The target can expire or lose an object capability while the task lock is being acquired.
        receivers.verify(c,tenant,decision.opportunity(),receiver,result.newTask(),result.newWait());
        return new TransferResult(updated,disposition,handoff,result.newTask(),true);
    }
    private Snapshot checkedDecision(Connection c,UUID tenant,Decision d)throws SQLException {
        Objects.requireNonNull(d); Objects.requireNonNull(d.actor()); exact(d.expectedBasis()); typed(d.exception(),EXCEPTION);
        String reason=d.reason();
        if(reason==null || !reason.equals(reason.strip()) || reason.codePointCount(0,reason.length())<1 || reason.codePointCount(0,reason.length())>2000)
            throw new IllegalArgumentException("Canonical reason of 1 to 2000 characters required");
        var root=lock(c,tenant,d.opportunity()); if(root.closed()) throw stale("Opportunity closed");
        var old=active(c,tenant,d.opportunity().id());
        if(old==null || !old.selector().equals(d.exception()) || !old.opportunity().equals(d.opportunity())) throw stale("Exception revision changed");
        var current=responsibility.current(c,tenant,d.opportunity());
        if(!current.equals(old.responsibility()) || !current.basis().equals(d.expectedBasis()) || !Objects.equals(old.task(),d.expectedTask()) || !Objects.equals(old.waitReceipt(),d.expectedWait())) throw stale("Responsibility changed");
        return old;
    }
    public Disposition disposition(Connection c,UUID tenant,UUID id)throws SQLException {
        try(var p=c.prepareStatement("select * from opportunity.owner_exception_disposition where tenant_id=? and owner_exception_disposition_id=?")){bind(p,tenant,id);try(var r=p.executeQuery()){if(!r.next())return null;return new Disposition(new Subject(DISPOSITION,id,r.getLong("revision"),null),new Subject(EXCEPTION,r.getObject("owner_exception_id",UUID.class),r.getLong("owner_exception_revision"),null),r.getString("kind"),r.getObject("actor_appointment_id",UUID.class),r.getString("reason"),instant(r,"review_due_at"),r.getObject("receiver_appointment_id",UUID.class),r.getObject("responsibility_handoff_id")==null?null:new Subject(HANDOFF,r.getObject("responsibility_handoff_id",UUID.class),0L,null));}}
    }
    public Snapshot current(Connection c,UUID tenant,UUID exceptionId)throws SQLException {
        try(var p=c.prepareStatement("select * from opportunity.owner_exception where tenant_id=? and owner_exception_id=? and is_current")){bind(p,tenant,exceptionId);try(var r=p.executeQuery()){return r.next()?snapshot(r):null;}}
    }
    public Snapshot read(Connection c,UUID tenant,Subject exception)throws SQLException {
        typed(exception,EXCEPTION);
        try(var p=c.prepareStatement("select * from opportunity.owner_exception where tenant_id=? and owner_exception_id=? and revision=?")) {
            bind(p,tenant,exception.id(),exception.revision()); try(var r=p.executeQuery()){return r.next()?snapshot(r):null;}
        }
    }
    public Snapshot active(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        try(var p=c.prepareStatement("select * from opportunity.owner_exception where tenant_id=? and opportunity_id=? and is_current and state in ('ACTIVE','COORDINATING')")) {
            bind(p,tenant,opportunity); try(var r=p.executeQuery()){if(!r.next())return null;var result=snapshot(r);if(r.next())throw stale("Multiple active cycles");return result;}
        }
    }
    private Root lock(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        typed(opportunity,"opportunity.opportunity"); Objects.requireNonNull(tenant);
        if(c.getAutoCommit() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED) throw new SQLException("Caller READ COMMITTED transaction required","25001");
        try(var p=c.prepareStatement("select revision,owner_appointment_id,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=? for update")) {
            bind(p,tenant,opportunity.id()); try(var r=p.executeQuery()) {
                if(!r.next() || r.getLong(1)!=opportunity.revision())throw stale("Opportunity missing or stale");
                return new Root(r.getObject(2,UUID.class),r.getObject(3)!=null);
            }
        }
    }
    private Snapshot append(Connection c,UUID tenant,Snapshot old,Subject opportunity,OpportunityResponsibilityReader.Responsibility current,Subject task,Subject waitReceipt,Set<Reason> reasons,State state,Instant now,UUID disposition,Instant review,String resolutionKind,Subject resolution)throws SQLException {
        if(old.selector().revision()>=9007199254740991L)throw stale("Exception revision exhausted");
        if(execute(c,"update opportunity.owner_exception set is_current=false where tenant_id=? and owner_exception_id=? and revision=? and is_current",tenant,old.selector().id(),old.selector().revision())!=1)throw stale("Exception revision changed");
        var next=new Snapshot(new Subject(EXCEPTION,old.selector().id(),old.selector().revision()+1,null),opportunity,old.frozenOwner(),current,task,waitReceipt,reasons,state,old.firstObservedAt(),now,disposition,review,resolutionKind,resolution);
        insert(c,tenant,next); return next;
    }
    private void insert(Connection c,UUID tenant,Snapshot s)throws SQLException {
        try(var p=c.prepareStatement("insert into opportunity.owner_exception (tenant_id,owner_exception_id,revision,is_current,opportunity_id,opportunity_revision,responsibility_slot,frozen_owner_appointment_id,current_owner_appointment_id,basis_type,basis_id,basis_revision,task_occurrence_id,task_revision,wait_receipt_id,wait_hash,reason_codes,state,first_observed_at,last_observed_at,last_disposition_id,review_due_at,resolution_kind,resolution_type,resolution_id,resolution_revision,resolution_hash) values (?,?,?,true,?,?,'OPPORTUNITY_OWNER',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            var array=c.createArrayOf("varchar",s.reasons().stream().sorted().map(Enum::name).toArray(String[]::new));
            try { bind(p,tenant,s.selector().id(),s.selector().revision(),s.opportunity().id(),s.opportunity().revision(),s.frozenOwner(),s.responsibility().appointmentId(),s.responsibility().basis().type(),s.responsibility().basis().id(),s.responsibility().basis().revision(),id(s.task()),revision(s.task()),id(s.waitReceipt()),digest(s.waitReceipt()),array,s.state().name(),s.firstObservedAt(),s.lastObservedAt(),s.lastDispositionId(),s.reviewDueAt(),s.resolutionKind(),s.resolution()==null?null:s.resolution().type(),id(s.resolution()),revision(s.resolution()),s.resolution()==null||s.resolution().hash()==null?null:digest(s.resolution())); p.executeUpdate(); }
            finally {array.free();}
        }
    }
    private void disposition(Connection c,UUID tenant,UUID id,Snapshot old,Decision d,String kind,Instant now,Instant review,UUID receiver,UUID handoff)throws SQLException {
        execute(c,"insert into opportunity.owner_exception_disposition (tenant_id,owner_exception_disposition_id,revision,owner_exception_id,owner_exception_revision,kind,actor_appointment_id,reason,decided_at,review_due_at,receiver_appointment_id,responsibility_handoff_id) values (?,?,0,?,?,?,?,?,?,?,?,?)",tenant,id,old.selector().id(),old.selector().revision(),kind,d.actor(),d.reason(),now,review,receiver,handoff);
    }
    static Snapshot snapshot(ResultSet r)throws SQLException {
        var reasons=EnumSet.noneOf(Reason.class); for(var code:(String[])r.getArray("reason_codes").getArray())reasons.add(Reason.valueOf(code));
        var basis=new Subject(r.getString("basis_type"),r.getObject("basis_id",UUID.class),r.getLong("basis_revision"),null);
        var resolution=r.getString("resolution_type")==null?null:new Subject(r.getString("resolution_type"),r.getObject("resolution_id",UUID.class),r.getObject("resolution_revision",Long.class),r.getBytes("resolution_hash")==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes("resolution_hash")));
        return new Snapshot(new Subject(EXCEPTION,r.getObject("owner_exception_id",UUID.class),r.getLong("revision"),null),new Subject("opportunity.opportunity",r.getObject("opportunity_id",UUID.class),r.getLong("opportunity_revision"),null),r.getObject("frozen_owner_appointment_id",UUID.class),new OpportunityResponsibilityReader.Responsibility(basis,r.getObject("current_owner_appointment_id",UUID.class)),selector(r,"task_occurrence_id","task_revision","responsibility.task_occurrence"),waitSelector(r),reasons,State.valueOf(r.getString("state")),instant(r,"first_observed_at"),instant(r,"last_observed_at"),r.getObject("last_disposition_id",UUID.class),instant(r,"review_due_at"),r.getString("resolution_kind"),resolution);
    }
    private static void hashedWait(Subject s){if(s==null || !"responsibility.wait_receipt".equals(s.type()) || s.hash()==null || digest(s).length!=32)throw new IllegalArgumentException("Exact wait digest required");}
    private static byte[] digest(Subject s){return s==null?null:Base64.getUrlDecoder().decode(s.hash());}
    private static Subject waitSelector(ResultSet r)throws SQLException {UUID id=r.getObject("wait_receipt_id",UUID.class);return id==null?null:new Subject("responsibility.wait_receipt",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes("wait_hash")));}
    private static Subject selector(ResultSet r,String id,String revision,String type)throws SQLException {UUID value=r.getObject(id,UUID.class);return value==null?null:new Subject(type,value,r.getLong(revision),null);}
    private static Instant instant(ResultSet r,String column)throws SQLException {var t=r.getObject(column,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private static Instant now(Connection c)throws SQLException {try(var p=c.prepareStatement("select clock_timestamp()");var r=p.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static void validateObservation(Observation o){if(o.task()!=null)typed(o.task(),"responsibility.task_occurrence");if(o.waitReceipt()!=null){hashedWait(o.waitReceipt());if(o.task()==null)throw new IllegalArgumentException("Wait requires task");}}
    private static void exact(Subject s){if(s==null || s.revision()==null)throw new IllegalArgumentException("Revisioned fact required");}
    private static void typed(Subject s,String type){exact(s);if(!type.equals(s.type()))throw new IllegalArgumentException("Unexpected fact type");}
    private static UUID id(Subject s){return s==null?null:s.id();}
    private static Long revision(Subject s){return s==null?null:s.revision();}
    private static SQLException stale(String message){return new SQLException(message,"40001");}
    private static int execute(Connection c,String sql,Object... args)throws SQLException {try(var p=c.prepareStatement(sql)){bind(p,args);return p.executeUpdate();}}
    private static void bind(PreparedStatement p,Object... args)throws SQLException {for(int i=0;i<args.length;i++){Object value=args[i];p.setObject(i+1,value instanceof Instant at?OffsetDateTime.ofInstant(at,ZoneOffset.UTC):value);}}
}
