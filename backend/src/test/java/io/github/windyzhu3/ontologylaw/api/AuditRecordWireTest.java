package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.AuditRecordPageV1;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuditRecordWireTest {
 @Test void terminal_and_empty_pages_omit_absent_cursor_instead_of_serializing_null(){
  var mapper=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();var page=new AuditRecordPageV1(java.util.List.of());
  var terminal=mapper.readTree(mapper.writeValueAsString(page));assertTrue(terminal.path("items").isArray());assertFalse(terminal.has("nextCursor"),"Optional non-null cursor must be absent on a terminal page");
  page.nextCursor("opaque_cursor");assertEquals("opaque_cursor",mapper.readTree(mapper.writeValueAsString(page)).path("nextCursor").asString());
 }
}
