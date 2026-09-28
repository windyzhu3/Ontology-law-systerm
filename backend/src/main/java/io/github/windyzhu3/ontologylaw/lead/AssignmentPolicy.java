package io.github.windyzhu3.ontologylaw.lead;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type;
import io.github.windyzhu3.ontologylaw.execution.CommandHandler;
import java.sql.*;import java.util.*;

/** Deterministic static-source policy. A role code or caller-supplied organization never selects authority. */
public final class AssignmentPolicy {
    private final AuthorizationIdentityReader identity=AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authority=R1AuthorityReader.databaseBacked();
    private UUID root(Connection c,UUID tenant,String code,String error)throws SQLException {var root=identity.organization(c,tenant,code);if(root==null)throw new CommandHandler.Rejected(error);return root.id();}
    public List<R1AuthorityReader.Candidate> sales(Connection c,UUID tenant,Subject lead,R1SourcePolicyRegistry.SourcePolicy policy)throws SQLException {
        var result=new LinkedHashMap<UUID,R1AuthorityReader.Candidate>();
        for(String code:policy.routingOrganizationRootCodes())for(var candidate:authority.candidates(c,tenant,lead,root(c,tenant,code,"SUPERVISOR_UNRESOLVED"),"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER"))result.putIfAbsent(candidate.appointmentId(),candidate);
        return List.copyOf(result.values());
    }
    public UUID unique(Connection c,UUID tenant,Subject lead,R1SourcePolicyRegistry.SourcePolicy policy,Type type)throws SQLException {
        String error=type==Type.ACK_SOURCE_INTAKE_STOP_REQUEST?"SOURCE_INTAKE_OWNER_UNRESOLVED":"SUPERVISOR_UNRESOLVED";
        String code=type.slot.equals("SOURCE_INTAKE_OWNER")?policy.sourceIntakeRootCode():policy.routingSupervisorRootCode();
        var candidates=authority.candidates(c,tenant,lead,root(c,tenant,code,error),type.slot,type.authority);
        if(candidates.size()!=1)throw new CommandHandler.Rejected(error);return candidates.getFirst().appointmentId();
    }
    /** Keep an established business responsibility only while its current authority remains valid. */
    public UUID sourceRequestOwner(Connection c,UUID tenant,io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Task task,R1SourcePolicyRegistry.SourcePolicy policy)throws SQLException {
        var tasks=io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked();
        UUID incumbent;
        if(task.type()==Type.ACK_SOURCE_INTAKE_STOP_REQUEST)incumbent=tasks.sourceRequestOriginOwner(c,tenant,task);
        else if(task.type()==Type.RESOLVE_SOURCE_REQUEST)incumbent=task.owner();
        else throw new IllegalArgumentException("Source request responsibility required");
        var candidates=authority.candidates(c,tenant,task.subject(),root(c,tenant,policy.routingSupervisorRootCode(),"SUPERVISOR_UNRESOLVED"),Type.RESOLVE_SOURCE_REQUEST.slot,Type.RESOLVE_SOURCE_REQUEST.authority);
        if(incumbent!=null&&candidates.stream().anyMatch(candidate->candidate.appointmentId().equals(incumbent)))return incumbent;
        if(candidates.size()==1)return candidates.getFirst().appointmentId();
        throw new CommandHandler.Rejected("SUPERVISOR_UNRESOLVED");
    }
}
