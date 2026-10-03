package io.github.windyzhu3.ontologylaw.api;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;
import static org.junit.jupiter.api.Assertions.*;
/** Protocol-level synthetic samples; never evidence of a real external provider call. */
class HaihuaAiSyntheticSamplesTest {
 static Stream<Arguments> samples(){return Arrays.stream(Task.values()).flatMap(t->Stream.of("NORMAL","MISSING","CONFLICT","REVOKED","FAILURE").map(s->Arguments.of(t,s)));}
 @ParameterizedTest(name="{0}-{1}") @MethodSource("samples")
 void candidateBoundaries(Task task,String sample)throws Exception{
  var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);var opportunity=UUID.randomUUID();
  String text=switch(task){case FIELDS->"合成客户林悦，联系电话未提供，与另份资料的林越姓名存在矛盾。";case SUMMARY->"已核对合成服务范围，下次联系尚未发生；另一记录未确认该范围。";case MATERIALS->"需要核对合同及业务资料，往来记录尚未提供；当前材料目录不代表全部材料。";};
  var source=new Source("sample:1","合成样本 "+sample,task==Task.MATERIALS?"RULE":"TEXT",text);
  var snapshot=new R25AiSourceReadService.Snapshot("a".repeat(64),List.of(source));var reads=new AtomicInteger();
  String status=Set.of("MISSING","CONFLICT").contains(sample)?sample:"CANDIDATE";
  var result=new Result(List.of(new Item(task.fields.getFirst(),status,"CANDIDATE".equals(status)?"仅供人工核对的合成候选":null,List.of(new Citation(source.id(),text)))));
  var service=new R25AiCandidateService(new byte[32],(a,o,t)->{if(sample.equals("REVOKED")&&reads.incrementAndGet()>1)throw new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");return snapshot;},(t,s)->{if(sample.equals("FAILURE"))throw new Failure("AI_TIMEOUT");return result;},Clock.systemUTC());
  if(sample.equals("REVOKED")){assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->service.generate(actor,opportunity,task)).status());return;}
  if(sample.equals("FAILURE")){assertEquals("AI_TIMEOUT",assertThrows(Failure.class,()->service.generate(actor,opportunity,task)).code());return;}
  var candidate=service.generate(actor,opportunity,task);assertEquals(status,candidate.items().getFirst().status());assertEquals(result.items().getFirst().value(),candidate.items().getFirst().value());
  service.recheck(actor,opportunity,task,candidate.sourceToken());assertFalse(candidate.toString().contains(text));
 }
}
