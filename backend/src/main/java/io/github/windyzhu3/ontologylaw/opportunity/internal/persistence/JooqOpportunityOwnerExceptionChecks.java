package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class JooqOpportunityOwnerExceptionChecks implements OpportunityOwnerExceptionChecks {
    private final OpportunityTaskActivationService.OpeningSourceReader sources;
    private final TaskStateReader tasks;
    private final ValidationEvidenceReader evidence;
    private final OpportunityOwnerExceptionAuthorityReader authority=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
    private final OpportunityResponsibilityReader responsibility=OpportunityResponsibilityReader.databaseBacked();
    private record Facts(UUID organization,List<Subject> protectedFacts,TaskState task,boolean sourceValid) {}
    public JooqOpportunityOwnerExceptionChecks(OpportunityTaskActivationService.OpeningSourceReader sources,TaskStateReader tasks,ValidationEvidenceReader evidence) {
        this.sources=Objects.requireNonNull(sources);this.tasks=Objects.requireNonNull(tasks);this.evidence=Objects.requireNonNull(evidence);
    }
    public Observation inspect(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility current,Instant now)throws SQLException {
        // A caller-provided observation time can never move checks into the future.
        Instant databaseNow=now(c);if(now.isAfter(databaseNow))throw new SQLException("Future observation forbidden","22000");
        if(!responsibility.current(c,tenant,opportunity).equals(current))throw new SQLException("Responsibility changed","40001");
        var facts=facts(c,tenant,opportunity,current,false);var reasons=EnumSet.noneOf(Reason.class);
        if(!facts.sourceValid())reasons.add(Reason.SOURCE_INCONSISTENT);
        var owner=authority.receiver(c,tenant,current.appointmentId(),facts.organization(),facts.protectedFacts(),databaseNow,facts.task().authorityCode());
        if(!owner.active())reasons.add(Reason.OWNER_INACTIVE);
        if(owner.denied())reasons.add(Reason.OWNER_DENIED);
        if(owner.active() && !owner.authorized() && !owner.denied())reasons.add(Reason.OWNER_AUTHORITY_MISSING);
        if(authority.supervisors(c,tenant,facts.organization(),facts.protectedFacts()).size()!=1)reasons.add(Reason.SUPERVISOR_UNRESOLVED);
        Subject validated=reasons.isEmpty()?evidence.existing(c,tenant,opportunity,current,owner.evidence(),databaseNow):null;
        return new Observation(reasons,facts.task().task(),facts.task().waitReceipt(),validated);
    }
    public void verify(Connection c,UUID tenant,Subject opportunity,UUID receiver,Subject expectedTask,Subject expectedWait)throws SQLException {
        var current=responsibility.current(c,tenant,opportunity);var facts=facts(c,tenant,opportunity,current,false);
        if(!facts.sourceValid() || !Objects.equals(expectedTask,facts.task().task()) || !Objects.equals(expectedWait,facts.task().waitReceipt()))throw new SQLException("Receiver source or responsibility changed","40001");
        var result=authority.receiver(c,tenant,receiver,facts.organization(),facts.protectedFacts(),now(c),facts.task().authorityCode());
        if(!result.active() || !result.authorized() || result.denied())throw new SQLException("Receiver is not currently authorized","42501");
    }
    public boolean canDiscover(Connection c,Actor service,Subject opportunity,Set<UUID> organizations)throws SQLException {
        if(service.principalKind()!=PrincipalKind.SERVICE || service.onBehalfAppointmentId()!=null)return false;
        UUID organization=organization(c,service.tenantId(),opportunity);
        if(organization==null || !organizations.contains(organization))return false;
        OpportunityResponsibilityReader.Responsibility current=null;
        try(var p=c.prepareStatement("select e.basis_type,e.basis_id,e.basis_revision,e.current_owner_appointment_id from opportunity.owner_exception e join opportunity.opportunity o on o.tenant_id=e.tenant_id and o.opportunity_id=e.opportunity_id where e.tenant_id=? and e.opportunity_id=? and e.is_current and e.state in ('ACTIVE','COORDINATING') and o.closed_at is not null")) {
            p.setObject(1,service.tenantId());p.setObject(2,opportunity.id());try(var r=p.executeQuery()){if(r.next())current=new OpportunityResponsibilityReader.Responsibility(new Subject(r.getString(1),r.getObject(2,UUID.class),r.getLong(3),null),r.getObject(4,UUID.class));}
        }
        if(current==null)current=responsibility.current(c,service.tenantId(),opportunity);
        var facts=facts(c,service.tenantId(),opportunity,current,true);
        return authority.permitted(c,service,organization,facts.protectedFacts(),"OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
    }
    private UUID organization(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        try(var p=c.prepareStatement("select owner_appointment_id from opportunity.opportunity where tenant_id=? and opportunity_id=? and revision=?")) {
            p.setObject(1,tenant);p.setObject(2,opportunity.id());p.setObject(3,opportunity.revision());try(var r=p.executeQuery()){return r.next()?authority.historicalOrganization(c,tenant,r.getObject(1,UUID.class)):null;}
        }
    }
    private Facts facts(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility current,boolean allowClosed)throws SQLException {
        UUID lead,assignmentId,contactId,frozen;
        try(var p=c.prepareStatement("select source_lead_id,source_assignment_id,source_contact_result_id,owner_appointment_id,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=? and revision=?")) {
            p.setObject(1,tenant);p.setObject(2,opportunity.id());p.setObject(3,opportunity.revision());try(var r=p.executeQuery()){
                if(!r.next() || !allowClosed && r.getObject(5)!=null)throw new SQLException("Opportunity missing, stale or closed","40001");
                lead=r.getObject(1,UUID.class);assignmentId=r.getObject(2,UUID.class);contactId=r.getObject(3,UUID.class);frozen=r.getObject(4,UUID.class);
            }
        }
        UUID organization=authority.historicalOrganization(c,tenant,frozen);
        if(organization==null)throw new SQLException("Historical organization cannot be proven","42501");
        var origin=sources.read(c,tenant,assignmentId,contactId);var protectedFacts=new LinkedHashSet<Subject>();protectedFacts.add(opportunity);protectedFacts.add(current.basis());
        boolean valid=origin!=null && origin.contact()!=null && origin.assignment()!=null && origin.task()!=null;
        if(valid) {
            var contact=origin.contact();var assignment=origin.assignment();var task=origin.task();
            valid=exact(contact.selector(),"lead.lead_contact_result",contactId,false) && exact(assignment.selector(),"lead.lead_assignment",assignmentId,true)
                    && exact(task.selector(),"responsibility.task_occurrence",contact.taskId(),true) && exact(task.subject(),"lead.lead",lead,true)
                    && lead.equals(contact.leadId()) && assignmentId.equals(contact.assignmentId()) && lead.equals(assignment.leadId())
                    && frozen.equals(assignment.owner()) && frozen.equals(task.owner()) && "CONNECTED_VALID".equals(contact.resultCode())
                    && "CONTACT_LEAD".equals(task.purpose()) && "RECORD_CONTACT_RESULT".equals(task.command()) && "DONE".equals(task.state()) && contact.selector().equals(task.completion());
        }
        // A missing source never removes protection from the other exact facts that remain known.
        if(origin!=null) {
            if(origin.contact()!=null && origin.contact().selector()!=null)protectedFacts.add(origin.contact().selector());
            if(origin.assignment()!=null && origin.assignment().selector()!=null)protectedFacts.add(origin.assignment().selector());
            if(origin.task()!=null){if(origin.task().selector()!=null)protectedFacts.add(origin.task().selector());if(origin.task().subject()!=null)protectedFacts.add(origin.task().subject());}
        }
        var task=Objects.requireNonNull(tasks.current(c,tenant,opportunity,current));
        valid&=task.lineageValid();
        if(task.task()!=null) {
            valid&=exact(task.task(),"responsibility.task_occurrence",task.task().id(),true) && current.appointmentId().equals(task.owner()) && ("OPEN".equals(task.state()) || "WAITING".equals(task.state()));
            valid&="WAITING".equals(task.state()) ? task.waitReceipt()!=null && exact(task.waitReceipt(),"responsibility.wait_receipt",task.waitReceipt().id(),false) : task.waitReceipt()==null;
            protectedFacts.add(task.task());
        } else valid&=task.owner()==null && task.state()==null && task.waitReceipt()==null;
        if(task.waitReceipt()!=null)protectedFacts.add(task.waitReceipt());protectedFacts.addAll(task.protectedSources());
        if(protectedFacts.size()>256)throw new SQLException("Protected source bound exceeded","54000");
        return new Facts(organization,List.copyOf(protectedFacts),task,valid);
    }
    private static boolean exact(Subject fact,String type,UUID id,boolean revision){return fact!=null && type.equals(fact.type()) && Objects.equals(id,fact.id()) && (revision?fact.revision()!=null:fact.hash()!=null);}
    private static Instant now(Connection c)throws SQLException {try(var p=c.prepareStatement("select clock_timestamp()");var r=p.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
}
