package io.github.windyzhu3.ontologylaw.opportunity;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class CustomerRequirementDocumentTest {
 @Test void incomplete_draft_is_allowed_but_confirmation_requires_client_and_required_text(){var d=new HashMap<String,Object>();d.put("participants",List.of());d.put("unknownOpponent",true);assertDoesNotThrow(()->CustomerRequirementDocument.validate(d,false));assertThrows(IllegalArgumentException.class,()->CustomerRequirementDocument.validate(d,true));}
 @Test void contacts_are_not_automatically_participants(){var d=new HashMap<String,Object>();d.put("contactName","联系人");d.put("contactPhone","13800000000");d.put("participants",List.of());d.put("unknownOpponent",false);assertThrows(IllegalArgumentException.class,()->CustomerRequirementDocument.validate(d,true));}
 @Test void new_party_requires_explicit_distinct_identity(){var d=new HashMap<String,Object>();d.put("participants",List.of(Map.of("role","CLIENT","newParty",Map.of("kind","NATURAL_PERSON","name","同名客户","distinctIdentityConfirmed",false))));d.put("unknownOpponent",false);d.put("matterName","事项");d.put("customerGoal","目标");d.put("serviceScope","范围");d.put("knownConstraints","无");assertDoesNotThrow(()->CustomerRequirementDocument.validate(d,false));assertThrows(IllegalArgumentException.class,()->CustomerRequirementDocument.validate(d,true));}
 @Test void incomplete_new_party_name_can_be_saved_but_never_confirmed(){var d=new HashMap<String,Object>();d.put("participants",List.of(Map.of("role","CLIENT","newParty",Map.of("kind","NATURAL_PERSON","name","","distinctIdentityConfirmed",false))));assertDoesNotThrow(()->CustomerRequirementDocument.validate(d,false));assertThrows(IllegalArgumentException.class,()->CustomerRequirementDocument.validate(d,true));}

 @Test void unverified_opponent_label_remains_protected_text_without_a_placeholder_party(){var d=new HashMap<String,Object>();d.put("participants",List.of());d.put("unknownOpponent",true);d.put("unverifiedOpponentName","待核实名称");assertEquals("待核实名称",CustomerRequirementDocument.validate(d,false).get("unverifiedOpponentName"));assertEquals(List.of(),d.get("participants"));d.put("unverifiedOpponentName","x".repeat(201));assertThrows(IllegalArgumentException.class,()->CustomerRequirementDocument.validate(d,false));}

}
