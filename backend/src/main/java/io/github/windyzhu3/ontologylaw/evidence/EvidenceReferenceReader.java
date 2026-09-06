package io.github.windyzhu3.ontologylaw.evidence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;

/** QUERY-only two-table read; callers own connection, capability and business/identity fences. */
public interface EvidenceReferenceReader {
    record Reference(Subject submission,Subject binding,Subject target,boolean active){}
    record Qualified(Reference reference,List<AuthorizationSnapshot> authorization){public Qualified{authorization=List.copyOf(authorization);}}
    Reference read(Connection c,UUID tenant,UUID submissionId)throws SQLException;
    /** Retains one complete selected contact authority path for all four exact subjects. */
    default Qualified qualify(Connection c,Request path,Subject task,Subject lead,UUID submissionId)throws SQLException{
        var requirement=path.requirement();
        if(path.actor().principalKind()!=PrincipalKind.HUMAN||requirement.path()!=Path.DIRECT&&requirement.path()!=Path.DELEGATED
            ||!requirement.slot().equals("ASSIGNMENT_OWNER")||!requirement.authorityCode().equals("SALES_CONTACT_OWNER")
            ||!task.type().equals("responsibility.task_occurrence")||!lead.type().equals("lead.lead")||lead.revision()==null)return null;
        var auth=AuthorizationService.databaseBacked();var snapshots=new ArrayList<AuthorizationSnapshot>();
        for(var subject:List.of(task,lead)){
            var snapshot=auth.evaluate(c,new Request(path.actor(),subject,path.scopeOrganizationId(),requirement),false);
            if(!snapshot.allowed())return null;snapshots.add(snapshot);
        }
        var reference=read(c,path.actor().tenantId(),submissionId);
        if(reference==null||!reference.active()||!reference.target().equals(lead))return null;
        for(var subject:List.of(reference.submission(),reference.binding())){
            var snapshot=auth.evaluate(c,new Request(path.actor(),subject,path.scopeOrganizationId(),requirement),false);
            if(!snapshot.allowed())return null;snapshots.add(snapshot);
        }
        return new Qualified(reference,snapshots);
    }
    static EvidenceReferenceReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.evidence.internal.persistence.JooqEvidenceReferenceReader();}
}
