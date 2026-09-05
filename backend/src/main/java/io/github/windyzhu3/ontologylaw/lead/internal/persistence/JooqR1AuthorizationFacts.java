package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.R1AuthorizationFacts;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.lead.R1SourcePolicyRegistry;
import io.github.windyzhu3.ontologylaw.responsibility.AuthorizationTaskReader;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.*;

public final class JooqR1AuthorizationFacts implements R1AuthorizationFacts {
    private final R1SourcePolicyRegistry sources;
    private final AuthorizationTaskReader tasks;
    private final AuthorizationIdentityReader identities;
    public JooqR1AuthorizationFacts(R1SourcePolicyRegistry sources,AuthorizationTaskReader tasks,AuthorizationIdentityReader identities) {
        this.sources=Objects.requireNonNull(sources);this.tasks=Objects.requireNonNull(tasks);this.identities=Objects.requireNonNull(identities);
    }
    public Capture capture(Connection c,UUID tenant,String account,String digest) throws SQLException {
        var policy=sources.find(account);
        if(policy==null)return null;
        var organization=identities.organization(c,tenant,policy.sourceIntakeRootCode());
        if(organization==null)return null;
        var l=LEAD_;
        var rows=DSL.using(c,SQLDialect.POSTGRES).select(l.LEAD_ID,l.REVISION).from(l).where(l.TENANT_ID.eq(tenant))
                .and(l.SOURCE_ACCOUNT_CODE.eq(account)).and(l.SOURCE_RECORD_KEY_DIGEST.eq(Base64.getUrlDecoder().decode(digest))).fetch();
        if(rows.size()>1)return null;
        return new Capture(organization,rows.isEmpty()?null:new AuthorizationService.Subject("lead.lead",rows.getFirst().value1(),rows.getFirst().value2(),null));
    }
    public Task task(Connection c,UUID tenant,UUID taskId,Instant now) throws SQLException {
        var task=tasks.read(c,tenant,taskId);
        if(task==null || !"lead.lead".equals(task.lead().type()))return null;
        var l=LEAD_;
        var lead=DSL.using(c,SQLDialect.POSTGRES).select(l.REVISION).from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(task.lead().id())).fetchOne();
        if(lead==null)return null;
        var owner=identities.owner(c,tenant,task.ownerAppointmentId(),now);
        var draft=task.draft();
        return new Task(task.selector(),task.ownerAppointmentId(),task.taskType(),task.primaryCommand(),task.lead(),
                new AuthorizationService.Subject("lead.lead",task.lead().id(),lead.value1(),null),
                draft==null?null:new Draft(draft.selector(),draft.taskId(),draft.actionCode(),draft.schemaCode(),draft.schemaVersion()),
                owner==null?null:new Owner(owner.appointmentId(),owner.principalId(),owner.organizationId(),owner.active(),owner.evidence()));
    }
}
