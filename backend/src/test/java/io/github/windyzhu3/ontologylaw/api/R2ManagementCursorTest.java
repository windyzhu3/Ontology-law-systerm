package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class R2ManagementCursorTest {
 @Test void authenticated_cursor_rejects_other_identity_view_filters_tampering_and_expiry(){
  var cursor=new R2ManagementCursor(new byte[32]);var id=UUID.randomUUID();var now=Instant.parse("2026-09-28T00:00:00Z");
  var token=cursor.encode(id,"exact-actor-view-query-limit",now);
  assertEquals(id,cursor.decode(token,"exact-actor-view-query-limit",now.plusSeconds(599)));
  assertThrows(RuntimeException.class,()->cursor.decode(token,"another-actor-view-query-limit",now));
  assertThrows(RuntimeException.class,()->cursor.decode(token,"exact-actor-view-query-limit",now.plusSeconds(600)));
  var bytes=Base64.getUrlDecoder().decode(token);bytes[bytes.length-1]^=1;
  assertThrows(RuntimeException.class,()->cursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),"exact-actor-view-query-limit",now));
  assertThrows(RuntimeException.class,()->cursor.decode("x".repeat(513),"exact-actor-view-query-limit",now));
 }
}
