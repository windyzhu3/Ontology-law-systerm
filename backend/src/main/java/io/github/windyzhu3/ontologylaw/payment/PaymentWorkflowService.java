package io.github.windyzhu3.ontologylaw.payment;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.*;import java.util.*;
/** Finance Owner. All operations require the command runtime's fenced, audited transaction. */
public interface PaymentWorkflowService {
 record Recovery(Subject source,Subject workflow) {}
 record Task(Subject selector,UUID owner,String state) {}
 record Account(String code,String label){public Account{if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,63}")||label==null||label.isBlank())throw new IllegalArgumentException("Trusted account required");}}
 class Blocked extends RuntimeException{private final String code;public Blocked(String code){super(code);this.code=code;}public String code(){return code;}}
 interface Ports {
  void lockAuthority(Connection c,Actor actor)throws SQLException;
  Instant now(Connection c)throws SQLException;
  Instant due(Instant start);
  void authorize(Connection c,Actor actor,Subject opportunity,String authority,List<Subject> facts)throws SQLException;
  UUID owner(Connection c,UUID tenant,Subject opportunity,String stage)throws SQLException;
  default UUID owner(Connection c,UUID tenant,Subject opportunity,String stage,UUID incumbent)throws SQLException{return owner(c,tenant,opportunity,stage);}
  Task task(Connection c,UUID tenant,UUID id)throws SQLException;
  Task create(Connection c,UUID tenant,Subject opportunity,UUID owner,String stage,Instant now,Instant due)throws SQLException;
  void complete(Connection c,UUID tenant,Task task,Subject result,Instant now)throws SQLException;
  void cancel(Connection c,UUID tenant,Task task,Instant now)throws SQLException;
  void material(Connection c,Actor actor,Subject opportunity,UUID material,String sha,String authority)throws SQLException;
  Account account(UUID tenant);
  String encode(Map<String,Object> value);
  Map<String,Object> open(UUID tenant,UUID opportunity,UUID fact,byte[] body);
  byte[] seal(UUID tenant,UUID opportunity,UUID fact,String clear);
 }
 Recovery recovery(Connection c,Actor actor,Subject opportunity)throws SQLException;
 Subject reconcile(Connection c,Actor actor,Subject opportunity,Recovery recovery)throws SQLException;
 List<Map<String,Object>> context(Connection c,Actor actor,Subject opportunity)throws SQLException;
 Subject request(Connection c,Actor actor,Subject completedWorkflow,UUID material,String sha,String explanation)throws SQLException;
 Subject start(Connection c,Actor actor,Subject opportunity,UUID handoff)throws SQLException;
 Subject recover(Connection c,Actor actor,Subject workflow)throws SQLException;
 Subject confirm(Connection c,Actor actor,Subject workflow,PaymentReceiptInput receipt)throws SQLException;
 Subject returnForCorrection(Connection c,Actor actor,Subject workflow,String reason)throws SQLException;
 Subject supplement(Connection c,Actor actor,Subject workflow,UUID material,String sha,String reason)throws SQLException;
 static PaymentWorkflowService databaseBacked(PaymentTransactionProtection protection,Ports ports){return new io.github.windyzhu3.ontologylaw.payment.internal.persistence.JdbcPaymentWorkflowService(protection,ports);}
}
