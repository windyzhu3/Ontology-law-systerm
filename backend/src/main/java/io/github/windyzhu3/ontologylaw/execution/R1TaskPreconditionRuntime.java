package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;

/** Only named HUMAN Task error tags, not a general subject reader or a command/disclosure operation. */
public final class R1TaskPreconditionRuntime {
    public enum Kind { TASK,DRAFT,SUBJECT }
    @FunctionalInterface public interface TagProjection {String tag(Connection c,Actor actor,UUID taskId,Kind kind)throws SQLException;}
    private final R1AuthorizationFacts facts;
    private final R1CommandPolicy policy;
    private final TagProjection projection;
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    public R1TaskPreconditionRuntime(R1AuthorizationFacts facts,TagProjection projection){this.facts=Objects.requireNonNull(facts);this.projection=Objects.requireNonNull(projection);this.policy=new R1CommandPolicy(authorization,facts);}
    public String read(Connection c,Actor actor,CommandEnvelope.Type operation,UUID taskId,Kind kind)throws SQLException {
        return inTransaction(c,Capability.QUERY,tx->{R1BusinessFence.databaseBacked().shared(tx,actor.tenantId());authorization.lockForEvaluation(tx,actor.tenantId());return current(tx,actor,operation,taskId,kind);});
    }
    /** Caller already holds the Runtime transaction and business/identity fences; no extra transaction or Audit. */
    public String current(Connection c,Actor actor,CommandEnvelope.Type operation,UUID taskId,Kind kind)throws SQLException {
        if(!authorized(c,actor,operation,taskId))return null;
        String tag=projection.tag(c,actor,taskId,kind);
        if(!authorized(c,actor,operation,taskId))return null;
        if(tag!=null&&!tag.matches("\""+kind.name().toLowerCase(Locale.ROOT)+"\\.[A-Za-z0-9_-]{43}\""))throw new SQLException("Invalid resource tag projection","XX000");
        return tag;
    }
    private boolean authorized(Connection c,Actor actor,CommandEnvelope.Type operation,UUID taskId)throws SQLException {
        if(actor.principalKind()!=PrincipalKind.HUMAN||!ActorIdentityReader.databaseBacked().active(c,actor))return false;
        var task=facts.task(c,actor.tenantId(),taskId,R1ServiceReadRuntime.databaseTime(c));
        if(task==null||task.owner()==null||!task.owner().active())return false;
        var primary=CommandEnvelope.Type.valueOf(task.primaryCommand());String key=R1CommandPolicy.primaryPolicy(primary);
        if(key==null||!R1CommandPolicy.matchesTask(primary,task.taskType())||operation!=CommandEnvelope.Type.SAVE_ACTION_DRAFT&&operation!=primary)return false;
        var parts=key.split(":");var request=R1AuthorityReader.databaseBacked().select(c,actor,task.selector(),task.owner().organizationId(),parts[0],parts[1]);if(request==null)return false;
        // Reuse the full current Task/Draft policy; these in-memory carriers never execute a handler or occupy a slot.
        var draft=task.draft();var envelope=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,taskId,taskId,actor,Map.of("actionCode",primary.name(),"schemaVersion",1));
        var binding=new CommandAuthorizationBinding.Draft(taskId,task.lead(),task.selector().revision(),draft==null?null:draft.selector().id(),draft==null?null:draft.selector().revision(),primary,1);
        var context=new CommandHandler.Context(CommandScope.draft(actor.tenantId(),taskId,primary),request,binding);
        // Draft is an Owner-bound fact, not a BUSINESS_SUBJECT_TYPES authorization object.
        return policy.authorize(c,envelope,context,true).allowed();
    }
}
