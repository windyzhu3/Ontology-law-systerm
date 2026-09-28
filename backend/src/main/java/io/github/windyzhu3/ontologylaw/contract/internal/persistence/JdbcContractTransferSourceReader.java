package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.ContractTransferSourceReader;
import java.sql.*;
import java.util.*;

public final class JdbcContractTransferSourceReader implements ContractTransferSourceReader {
 private static final String SQL="""
  select r.opportunity_id,r.contract_id,r.current_revision_id,e.contract_execution_id,
         v.execution_verification_id,a.signature_archive_id,r.deal_activated_at,
         encode(r.activation_source_hash,'hex') activation_digest
  from contract.contract r
  join contract.contract_execution e on e.tenant_id=r.tenant_id
    and e.contract_execution_id=r.contract_execution_id and e.contract_id=r.contract_id
    and e.contract_revision_id=r.current_revision_id and e.contract_revision_id=r.approved_revision_id
  join contract.execution_verification v on v.tenant_id=e.tenant_id
    and v.execution_verification_id=e.execution_verification_id
    and v.contract_revision_id=e.contract_revision_id and v.opportunity_id=r.opportunity_id
  join contract.signature_handoff h on h.tenant_id=v.tenant_id
    and h.signature_handoff_id=v.handoff_id and h.opportunity_id=r.opportunity_id
  join contract.signature_archive a on a.tenant_id=h.tenant_id
    and a.signature_archive_id=h.archive_id and a.arrangement_id=h.arrangement_id
    and a.opportunity_id=r.opportunity_id
  where r.tenant_id=? and r.contract_termination_id is null and r.deal_activated_at is not null
    and r.activation_source_type='contract.contract_execution'
    and r.activation_source_id=e.contract_execution_id and r.activation_source_hash=e.execution_digest
    and exists(select 1 from contract.execution_workflow w where w.tenant_id=r.tenant_id
      and w.opportunity_id=r.opportunity_id and w.execution_id=e.contract_execution_id
      and w.verification_id=v.execution_verification_id and w.stage_code='READY_TRANSFER'
      and not exists(select 1 from contract.execution_workflow n where n.tenant_id=w.tenant_id
        and n.previous_workflow_id=w.execution_workflow_id))
  """;
 @Override public Optional<Source> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException{
  try(var p=c.prepareStatement(SQL+" and r.opportunity_id=?")){p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){if(!r.next())return Optional.empty();var result=read(r,tenant);if(r.next())throw new SQLException("Ambiguous transfer source","21000");return Optional.of(result);}}
 }
 @Override public Optional<Source> find(Connection c,UUID tenant,UUID contract)throws SQLException {
  Objects.requireNonNull(tenant);Objects.requireNonNull(contract);
  try(var p=c.prepareStatement(SQL+" and r.contract_id=?")){
   p.setObject(1,tenant);p.setObject(2,contract);
   try(var rows=p.executeQuery()){
    if(!rows.next())return Optional.empty();var result=read(rows,tenant);
    if(rows.next())throw new SQLException("Ambiguous exact transfer source");return Optional.of(result);
   }
  }
 }
 @Override public List<Source> page(Connection c,UUID tenant,int limit,UUID after)throws SQLException {
  Objects.requireNonNull(tenant);if(limit<1||limit>100)throw new IllegalArgumentException("Bounded transfer source page required");
  try(var p=c.prepareStatement(SQL+(after==null?"":" and r.contract_id>?")+" order by r.contract_id limit ?")){
   p.setObject(1,tenant);int i=2;if(after!=null)p.setObject(i++,after);p.setInt(i,limit);
   try(var rows=p.executeQuery()){var result=new ArrayList<Source>();while(rows.next())result.add(read(rows,tenant));return List.copyOf(result);}
  }
 }
 private Source read(ResultSet r,UUID tenant)throws SQLException {
  return new Source(tenant,r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
    r.getObject(4,UUID.class),r.getObject(5,UUID.class),r.getObject(6,UUID.class),r.getTimestamp(7).toInstant(),r.getString(8));
 }
}
