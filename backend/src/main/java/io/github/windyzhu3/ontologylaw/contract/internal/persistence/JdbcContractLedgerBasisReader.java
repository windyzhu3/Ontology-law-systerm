package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.ContractLedgerBasisReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
public final class JdbcContractLedgerBasisReader implements ContractLedgerBasisReader {
 public Basis revision(Connection c,UUID tenant,UUID revision)throws SQLException{return read(c,tenant,revision,false);}
 public Basis executed(Connection c,UUID tenant,UUID contract)throws SQLException{return read(c,tenant,contract,true);}
 private Basis read(Connection c,UUID tenant,UUID id,boolean execution)throws SQLException{
  String sql="select v.contract_revision_id,v.revision_no,v.content_digest"+(execution?",e.contract_execution_id,e.execution_digest":"")+" from contract.contract_revision v"+(execution?" join contract.contract_execution e on e.tenant_id=v.tenant_id and e.contract_revision_id=v.contract_revision_id":"")+" where v.tenant_id=? and "+(execution?"e.contract_id":"v.contract_revision_id")+"=?";
  try(var p=c.prepareStatement(sql)){p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){if(!r.next())return null;var facts=new ArrayList<Subject>();facts.add(new Subject("contract.contract_revision",r.getObject(1,UUID.class),null,hash(r.getBytes(3))));if(execution)facts.add(new Subject("contract.contract_execution",r.getObject(4,UUID.class),null,hash(r.getBytes(5))));var value=new Basis(r.getInt(2),List.copyOf(facts));if(r.next())throw new SQLException("Ambiguous management contract basis","21000");return value;}}
 }
 private static String hash(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
}
