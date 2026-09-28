package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;

import io.github.windyzhu3.ontologylaw.transfer.TransferSubmissionService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.*;import java.util.*;

/** Creates only the activation-unique transfer root. Acceptance slots stay empty. */
public final class JdbcTransferSubmissionService implements TransferSubmissionService {
 private final Ports ports;
 public JdbcTransferSubmissionService(Ports ports){this.ports=Objects.requireNonNull(ports);}
 private static Blocked stale(){return new Blocked("STALE_SUBJECT");}
 public Subject prepare(Connection c,Actor actor,Subject opportunity,UUID contract)throws SQLException{
  if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Transfer command transaction required");
  if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");
  if(opportunity==null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null||contract==null)throw stale();
  ports.lockAuthority(c,actor);
  try(var p=c.prepareStatement("select revision,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=? for update")){
   p.setObject(1,actor.tenantId());p.setObject(2,opportunity.id());
   try(var r=p.executeQuery()){if(!r.next()||r.getLong(1)!=opportunity.revision()||r.getObject(2)!=null)throw stale();}
  }
  ports.authorizePreparation(c,actor,opportunity);
  var source=ports.source(c,actor.tenantId(),contract);
  if(source==null||!source.contractId().equals(contract)||!source.opportunityId().equals(opportunity.id()))throw stale();
  var route=ports.route(c,actor,opportunity);if(route==null)throw new Blocked("TRANSFER_ROUTE_REQUIRED");
  try(var p=c.prepareStatement("select count(*) from identity.organization_unit where tenant_id=? and organization_unit_id in (?,?) and state='ACTIVE'")){
   p.setObject(1,actor.tenantId());p.setObject(2,route.fromOrganizationId());p.setObject(3,route.toOrganizationId());
   try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=2)throw new Blocked("TRANSFER_ROUTE_REQUIRED");}
  }
  byte[] digest=HexFormat.of().parseHex(source.activationDigest());
  try(var p=c.prepareStatement("select transfer_request_id from transfer.transfer_request where tenant_id=? and contract_id=? and deal_activation_digest=?")){
   p.setObject(1,actor.tenantId());p.setObject(2,contract);p.setBytes(3,digest);try(var r=p.executeQuery()){if(r.next())throw stale();}
  }
  UUID id;try(var s=c.createStatement();var r=s.executeQuery("select uuidv7()")){r.next();id=r.getObject(1,UUID.class);}
  try(var p=c.prepareStatement("""
   insert into transfer.transfer_request(tenant_id,transfer_request_id,opportunity_id,contract_id,
     contract_execution_id,deal_activated_at,deal_activation_digest,from_organization_unit_id,
     to_organization_unit_id,transfer_purpose_code,proposed_matter_type_code,
     proposed_capability_pack_code,proposed_capability_pack_version,created_by_appointment_id,created_at,changed_at)
   values(?,?,?,?,?,?,?,?,?,'R2_SALES_INTAKE','UNCLASSIFIED','R2_INTAKE',1,?,clock_timestamp(),clock_timestamp())
   """)){
   p.setObject(1,actor.tenantId());p.setObject(2,id);p.setObject(3,opportunity.id());p.setObject(4,contract);
   p.setObject(5,source.executionId());p.setObject(6,OffsetDateTime.ofInstant(source.activatedAt(),ZoneOffset.UTC));
   p.setBytes(7,digest);p.setObject(8,route.fromOrganizationId());p.setObject(9,route.toOrganizationId());p.setObject(10,actor.appointmentId());
   if(p.executeUpdate()!=1)throw stale();
  }
  return new Subject("transfer.transfer_request",id,0L,null);
 }
}
