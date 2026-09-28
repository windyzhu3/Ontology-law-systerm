package io.github.windyzhu3.ontologylaw.identity.internal.persistence;
import java.sql.SQLException;import java.time.Instant;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class StableAuthorizationBatchTest {
 static Instant t(long seconds){return Instant.EPOCH.plusSeconds(seconds);}
 static StableAuthorizationBatch.Clock clock(long...seconds){var queue=new ArrayDeque<Instant>();for(long s:seconds)queue.add(t(s));return queue::remove;}
 @Test void stable_batch_checks_end_time_and_returns_the_whole_result()throws Exception {
  assertEquals(List.of("a","b"),StableAuthorizationBatch.evaluate(clock(1,2),at->new StableAuthorizationBatch.Result<>(List.of("a","b"),t(3))));
 }
 @Test void equality_at_expiry_discards_the_entire_old_decision()throws Exception {
  var calls=new AtomicInteger();assertEquals("expired",StableAuthorizationBatch.evaluate(clock(1,2,3),at->{calls.incrementAndGet();return new StableAuthorizationBatch.Result<>(at.isBefore(t(2))?"allowed":"expired",at.isBefore(t(2))?t(2):null);}));assertEquals(2,calls.get());
 }
 @Test void future_denial_and_denial_expiry_each_trigger_a_new_complete_evaluation()throws Exception {
  var seen=new ArrayList<Instant>();assertEquals("allowed-again",StableAuthorizationBatch.evaluate(clock(1,2,3,4),at->{seen.add(at);return new StableAuthorizationBatch.Result<>(at.isBefore(t(2))?"allowed":at.isBefore(t(3))?"denied":"allowed-again",at.isBefore(t(2))?t(2):at.isBefore(t(3))?t(3):null);}));assertEquals(List.of(t(1),t(2),t(3)),seen);
 }
 @Test void backward_clock_rechecks_even_without_a_future_boundary()throws Exception {
  var seen=new ArrayList<Instant>();assertEquals(t(1),StableAuthorizationBatch.evaluate(clock(2,1,1),at->{seen.add(at);return new StableAuthorizationBatch.Result<>(at,null);}));assertEquals(List.of(t(2),t(1)),seen);
 }
 @Test void three_unstable_rounds_fail_closed_as_transient_failure() {
  var e=assertThrows(SQLException.class,()->StableAuthorizationBatch.evaluate(clock(1,2,3,4),at->new StableAuthorizationBatch.Result<>("never-return",at.plusSeconds(1))));assertEquals("40001",e.getSQLState());
 }
 @Test void evaluator_failure_does_not_return_any_partial_result() {
  assertThrows(SQLException.class,()->StableAuthorizationBatch.evaluate(clock(1),at->{throw new SQLException("read failed");}));
 }
}
