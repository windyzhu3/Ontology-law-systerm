package io.github.windyzhu3.ontologylaw.identity.internal.persistence;
import java.sql.SQLException;import java.time.Instant;
/** A locked fact set may be reused; its time-dependent decision may not. */
final class StableAuthorizationBatch {
 record Result<T>(T value,Instant nextBoundary) {}
 @FunctionalInterface interface Clock {Instant now()throws SQLException;}
 @FunctionalInterface interface Evaluation<T> {Result<T> at(Instant time)throws SQLException;}
 static <T>T evaluate(Clock clock,Evaluation<T> evaluation)throws SQLException {Instant at=clock.now();
  for(int attempt=0;attempt<3;attempt++){
   Result<T> result=evaluation.at(at);Instant end=clock.now();
   if(!end.isBefore(at)&&(result.nextBoundary()==null||end.isBefore(result.nextBoundary())))return result.value();
   at=end;
  }
  throw new SQLException("Authorization time boundary did not stabilize","40001");}
}
