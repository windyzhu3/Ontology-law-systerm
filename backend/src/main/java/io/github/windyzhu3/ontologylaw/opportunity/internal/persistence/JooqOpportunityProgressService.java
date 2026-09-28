package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.*;

/** Opportunity owns its fact SQL; responsibility mutations go through its public Owner port. */
public final class JooqOpportunityProgressService implements OpportunityProgressService {
    private final OpportunityProgressProtection protection;
    private final Function<Map<String,Object>,String> canonical;
    private final Responsibility responsibility;
    public JooqOpportunityProgressService(OpportunityProgressProtection protection,Function<Map<String,Object>,String> canonical,Responsibility responsibility){this.protection=Objects.requireNonNull(protection);this.canonical=Objects.requireNonNull(canonical);this.responsibility=Objects.requireNonNull(responsibility);}
    public Result record(Connection c,Actor actor,Subject expected,Subject expectedTask,OpportunityProgressInput input,ZoneId zone,Instant recordedAt)throws SQLException{
        Objects.requireNonNull(input);Objects.requireNonNull(zone);input.validateAt(recordedAt);
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Opportunity progress requires READ COMMITTED transaction","25001");
        if(!"opportunity.opportunity".equals(expected.type())||expected.revision()==null||!"responsibility.task_occurrence".equals(expectedTask.type())||expectedTask.revision()==null)
            throw new IllegalArgumentException("Exact Opportunity and Task required");
        if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfPrincipalId()!=null)throw new Blocked("FORBIDDEN");
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));
        var o=OPPORTUNITY_;
        var opening=db.select(o.REVISION,o.OWNER_APPOINTMENT_ID,o.CLOSED_AT,o.CREATED_AT).from(o)
            .where(o.TENANT_ID.eq(actor.tenantId())).and(o.OPPORTUNITY_ID.eq(expected.id())).forUpdate().fetchOne();
        if(opening==null||!expected.revision().equals(opening.get(o.REVISION))||opening.get(o.CLOSED_AT)!=null)
            throw new Blocked("STALE_SUBJECT");
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),expected);
        if(!actor.appointmentId().equals(effective.appointmentId()))throw new Blocked("FORBIDDEN");
        if(input.occurredAt().isBefore(opening.get(o.CREATED_AT).toInstant()))throw new IllegalArgumentException("Progress cannot precede Opportunity opening");
        var auth=AuthorizationService.databaseBacked();auth.lockForEvaluation(c,actor.tenantId());
        Instant checkedAt=db.select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class).toInstant();
        var identities=AuthorizationIdentityReader.databaseBacked();var owner=identities.owner(c,actor.tenantId(),actor.appointmentId(),checkedAt);
        var registration=identities.registration(c,actor.tenantId(),actor.appointmentId());
        if(owner==null||!owner.active()||!actor.principalId().equals(owner.principalId())||registration==null||registration.principalKind()!=PrincipalKind.HUMAN)
            throw new Blocked("FORBIDDEN");
        UUID organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,actor.tenantId(),opening.get(o.OWNER_APPOINTMENT_ID));
        if(organization==null)throw new Blocked("FORBIDDEN");
        var request=R1AuthorityReader.databaseBacked().select(c,actor,expected,organization,"OPPORTUNITY_OWNER","SALES_OPPORTUNITY_OWNER");
        if(request==null||!auth.evaluate(c,request,true).allowed())throw new Blocked("FORBIDDEN");
        var basisRequest=new Request(actor,effective.basis(),organization,request.requirement());
        if(!auth.evaluate(c,basisRequest,true).allowed())throw new Blocked("FORBIDDEN");
        var current=responsibility.lockAndRead(c,actor.tenantId(),expectedTask.id());
        if(current==null||!expectedTask.equals(current.selector())||!"PROGRESS_OPPORTUNITY".equals(current.purpose())||!"RECORD_OPPORTUNITY_PROGRESS".equals(current.command())||!current.subject().equals(expected)
            ||!"OPEN".equals(current.state())||!actor.appointmentId().equals(current.owner()))throw new Blocked("STALE_TASK");
        var p=OPPORTUNITY_PROGRESS;
        long previous=db.select(DSL.coalesce(DSL.max(p.PROGRESS_NO),0L)).from(p).where(p.TENANT_ID.eq(actor.tenantId())).and(p.OPPORTUNITY_ID.eq(expected.id())).fetchOne(0,Long.class);
        long next=Math.addExact(previous,1L);
        UUID id=db.select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);
        String body=canonical.apply(input.bodyValues(actor.tenantId(),expected.id(),expected.revision(),id,current.selector().id(),current.selector().revision(),actor.appointmentId(),recordedAt));
        byte[] digest;
        try{digest=MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        db.insertInto(p).set(p.TENANT_ID,actor.tenantId()).set(p.OPPORTUNITY_PROGRESS_ID,id).set(p.OPPORTUNITY_ID,expected.id())
            .set(p.PROGRESS_NO,next).set(p.PROGRESS_TYPE_CODE,input.type()).set(p.PROGRESS_CONTRACT_CODE,OpportunityProgressInput.CONTRACT)
            .set(p.PROGRESS_CONTRACT_VERSION,1).set(p.PROGRESS_DIGEST,digest)
            .set(p.PROGRESS_BODY_CIPHERTEXT,protection.encrypt(actor.tenantId(),expected.id(),id,body))
            .set(p.OCCURRED_AT,input.occurredAt().atOffset(ZoneOffset.UTC)).set(p.CREATED_AT,recordedAt.atOffset(ZoneOffset.UTC)).execute();
        var fact=new Subject("opportunity.opportunity_progress",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        var successor=responsibility.completeAndSchedule(c,actor.tenantId(),current,fact,zone,recordedAt,input.nextCheckAt());
        if(successor==null||!"responsibility.task_occurrence".equals(successor.type())||successor.revision()==null||successor.id().equals(current.selector().id()))
            throw new IllegalStateException("Responsibility did not return a distinct follow-up");
        if(!auth.evaluate(c,request,true).allowed())throw new Blocked("FORBIDDEN");
        if(!auth.evaluate(c,basisRequest,true).allowed())throw new Blocked("FORBIDDEN");
        return new Result(fact,successor);
    }
}
