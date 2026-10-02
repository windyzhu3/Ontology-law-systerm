package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.IdentityChoiceV1;
class IdentityChoiceJsonTest {
 final JsonMapper mapper=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
 @Test void original_session_and_nonrole_choices_keep_exact_id_label_shape(){
  var choice=R1WireModels.model(Map.of("id",UUID.randomUUID().toString(),"label","销售一部 · 销售经办"),IdentityChoiceV1.class);
  var tree=mapper.valueToTree(choice);assertTrue(tree.has("id"));assertTrue(tree.has("label"));assertFalse(tree.has("code"));assertEquals(2,tree.size());
 }
 @Test void role_candidates_keep_the_immutable_code(){
  var choice=R1WireModels.model(Map.of("id",UUID.randomUUID().toString(),"label","可配置顾问","code","CUSTOM_ADVISOR"),IdentityChoiceV1.class);
  assertEquals("CUSTOM_ADVISOR",mapper.valueToTree(choice).get("code").asString());
 }
}
