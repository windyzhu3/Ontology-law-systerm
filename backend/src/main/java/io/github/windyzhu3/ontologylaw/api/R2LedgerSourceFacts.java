package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.util.*;
/** Source graphs reused only inside the contract ledger's business and identity read locks. */
final class R2LedgerSourceFacts {
 @FunctionalInterface interface Loader {List<Subject> read()throws SQLException;}
 @FunctionalInterface interface SourceLoader<T> {T read()throws SQLException;}
 private record CustomerSource(Subject opportunity,Subject confirmation){}
 private record CurrentTask(UUID original){}
 private record ExactTask(UUID id){}
 private record MaterialBasis(UUID id){}
 private record EvidenceReference(UUID id){}
 private record Scope(Connection connection,UUID tenant,Map<Subject,List<Subject>> closures,Map<UUID,List<Subject>> quotes,Map<CustomerSource,List<Subject>> customers,Map<UUID,List<Subject>> contracts,Map<UUID,List<Subject>> documents,Map<Subject,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.Responsibility> responsibilities,Map<Subject,UUID> organizations,Map<Object,Object> metadata){}
 private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
 static ReadScope open(Connection c,UUID tenant)throws SQLException{
  if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED||CURRENT.get()!=null)throw new SQLException("Fenced ledger scope required","25001");
  CURRENT.set(new Scope(c,tenant,new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>()));return CURRENT::remove;
 }
 @SuppressWarnings("unchecked") private static <T>T metadata(Connection c,UUID tenant,Object key,SourceLoader<T> loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(c.getAutoCommit())throw new SQLException("Fenced ledger transaction ended","25001");
  if(scope.metadata().containsKey(key))return (T)scope.metadata().get(key);
  var value=loader.read();if(scope.metadata().size()<4096)scope.metadata().put(key,value);return value;
 }
 static io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Task currentTask(Connection c,UUID tenant,UUID original)throws SQLException{
  return metadata(c,tenant,new CurrentTask(original),()->io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().currentTask(c,tenant,original));
 }
 static io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Task exactTask(Connection c,UUID tenant,UUID id)throws SQLException{
  return metadata(c,tenant,new ExactTask(id),()->io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().read(c,tenant,id));
 }
 static io.github.windyzhu3.ontologylaw.evidence.MaterialEvidence.Basis materialBasis(Connection c,UUID tenant,UUID id)throws SQLException{
  return metadata(c,tenant,new MaterialBasis(id),()->R2MaterialsServices.metadata().basis(c,tenant,id));
 }
 static io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader.Reference evidenceReference(Connection c,UUID tenant,UUID id)throws SQLException{
  return metadata(c,tenant,new EvidenceReference(id),()->io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader.databaseBacked().read(c,tenant,id));
 }
 static io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.Responsibility responsibility(Connection c,UUID tenant,Subject opportunity,SourceLoader<io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.Responsibility> loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.responsibilities().containsKey(opportunity))scope.responsibilities().put(opportunity,loader.read());
  return scope.responsibilities().get(opportunity);
 }
 static UUID organization(Connection c,UUID tenant,Subject opportunity,SourceLoader<UUID> loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.organizations().containsKey(opportunity))scope.organizations().put(opportunity,loader.read());
  return scope.organizations().get(opportunity);
 }
 static List<Subject> closure(Connection c,UUID tenant,Subject opportunity,Loader loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.closures().containsKey(opportunity)){var facts=loader.read();scope.closures().put(opportunity,facts==null?null:List.copyOf(facts));}
  return scope.closures().get(opportunity);
 }
 static List<Subject> quote(Connection c,UUID tenant,UUID opportunity,Loader loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.quotes().containsKey(opportunity)){var facts=loader.read();scope.quotes().put(opportunity,facts==null?null:List.copyOf(facts));}
  return scope.quotes().get(opportunity);
 }
 static List<Subject> contractMetadata(Connection c,UUID tenant,UUID opportunity,Loader loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.contracts().containsKey(opportunity)){var facts=loader.read();scope.contracts().put(opportunity,facts==null?null:List.copyOf(facts));}
  return scope.contracts().get(opportunity);
 }
 static List<Subject> document(Connection c,UUID tenant,UUID version,Loader loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  if(!scope.documents().containsKey(version)){var facts=loader.read();scope.documents().put(version,facts==null?null:List.copyOf(facts));}
  return scope.documents().get(version);
 }
 static List<Subject> customer(Connection c,UUID tenant,Subject opportunity,Subject confirmation,Loader loader)throws SQLException{
  var scope=CURRENT.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
  var key=new CustomerSource(opportunity,confirmation);
  if(!scope.customers().containsKey(key)){var facts=loader.read();scope.customers().put(key,facts==null?null:List.copyOf(facts));}
  return scope.customers().get(key);
 }
}
