package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.ContractExecutionConditions;
import io.github.windyzhu3.ontologylaw.contract.ContractExecutionSourceReader;
import java.sql.*;
import java.util.*;

public final class JdbcContractExecutionSourceReader implements ContractExecutionSourceReader {
    private static final String SOURCE_SQL="""
            select root.contract_id, v.contract_revision_id, a.signature_archive_id, h.signature_handoff_id, h.opportunity_id, h.created_at
            from contract.signature_handoff h
            join contract.signature_archive a on a.tenant_id=h.tenant_id
                and a.signature_archive_id=h.archive_id and a.arrangement_id=h.arrangement_id
                and a.opportunity_id=h.opportunity_id
            join contract.signature_readiness r on r.tenant_id=h.tenant_id
                and r.signature_readiness_id=h.readiness_id
            join contract.contract_revision v on v.tenant_id=r.tenant_id
                and v.contract_revision_id=r.contract_revision_id
            join contract.contract root on root.tenant_id=v.tenant_id and root.contract_id=v.contract_id
                and root.opportunity_id=h.opportunity_id and root.current_revision_id=v.contract_revision_id
                and root.approved_revision_id=v.contract_revision_id
            where h.tenant_id=?
                and h.state_code='AWAITING_EXECUTION_CONDITIONS'
                and exists(select 1 from contract.signature_workflow w
                    where w.tenant_id=h.tenant_id and w.readiness_id=h.readiness_id
                        and w.arrangement_id=h.arrangement_id and w.stage_code='SIGNATURE_COMPLETE'
                        and not exists(select 1 from contract.signature_workflow n
                            where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.signature_workflow_id))

        """;
    @Override public Optional<ContractExecutionConditions.Basis> find(Connection c,UUID tenant,UUID handoff)throws SQLException {
        Objects.requireNonNull(tenant);Objects.requireNonNull(handoff);
        try(var p=c.prepareStatement(SOURCE_SQL+" and h.signature_handoff_id=?")) {
            p.setObject(1,tenant);p.setObject(2,handoff);
            try(var r=p.executeQuery()){
                if(!r.next())return Optional.empty();var result=source(r,tenant).basis();
                if(r.next())throw new SQLException("Ambiguous exact signature handoff");return Optional.of(result);
            }
        }
    }
    @Override public List<Source> page(Connection c,UUID tenant,int limit,UUID after)throws SQLException {
        Objects.requireNonNull(tenant);if(limit<1||limit>100)throw new IllegalArgumentException("Bounded execution source page required");
        try(var p=c.prepareStatement(SOURCE_SQL+(after==null?"":" and h.signature_handoff_id>?")+" order by h.signature_handoff_id limit ?")) {
            p.setObject(1,tenant);int index=2;if(after!=null)p.setObject(index++,after);p.setInt(index,limit);
            try(var r=p.executeQuery()){var sources=new ArrayList<Source>();while(r.next())sources.add(source(r,tenant));return List.copyOf(sources);}
        }
    }
    private Source source(ResultSet r,UUID tenant)throws SQLException {
        var basis=new ContractExecutionConditions.Basis(tenant,r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class));
        return new Source(basis,r.getObject(5,UUID.class),r.getTimestamp(6).toInstant());
    }
}
