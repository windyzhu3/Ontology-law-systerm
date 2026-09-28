package io.github.windyzhu3.ontologylaw.audit;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityCustomerReceiptMetadataTest {
    private static final String SAVE="SAVE_OPPORTUNITY_CUSTOMER_DRAFT", CONFIRM="CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS";
    private Map<String,Object> selector(String type){return Map.of("type",type,"id",UUID.randomUUID().toString(),"revision",0L);}
    private Map<String,Object> scope(String command){
        var s=new LinkedHashMap<String,Object>();s.put("profile","R2_"+command+"_SCOPE_V1");
        s.put("tenantId",UUID.randomUUID().toString());s.put("commandType",command);
        s.put("principalId",UUID.randomUUID().toString());s.put("appointmentId",UUID.randomUUID().toString());
        var opportunity=selector("opportunity.opportunity");s.put("opportunity",opportunity);s.put("basis",opportunity);
        s.put("draft",CONFIRM.equals(command)?selector("opportunity.customer_requirement_draft"):null);s.put("confirmation",null);return s;
    }
    private ReceiptRecoveryMetadata parse(String command,Map<String,Object> scope){return new ReceiptRecoveryMetadata(command,Map.of("profile","R1_COMMAND_RECEIPT_RECOVERY_V1","scope",scope,"binding",Map.of("kind","CUSTOMER_REQUIREMENTS")));}

    @Test void initial_save_and_exact_confirmation_preserve_only_restricted_selectors(){
        for(String command:List.of(SAVE,CONFIRM)){
            var scope=scope(command);var metadata=parse(command,scope);
            assertEquals(scope,metadata.customerRequirementsScope());assertNull(metadata.taskId());
            assertEquals("opportunity.opportunity",metadata.lead().type());
            assertEquals(CONFIRM.equals(command),metadata.draft()!=null);
            assertEquals("ReceiptRecoveryMetadata[restricted]",metadata.toString());
        }
    }
    @Test void confirmation_without_draft_and_unrelated_selector_types_are_rejected(){
        var missing=scope(CONFIRM);missing.put("draft",null);assertThrows(IllegalArgumentException.class,()->parse(CONFIRM,missing));
        for(String field:List.of("draft","confirmation","basis","opportunity")){
            var forged=scope(CONFIRM);forged.put(field,selector("lead.lead"));assertThrows(IllegalArgumentException.class,()->parse(CONFIRM,forged));
        }
    }
    @Test void missing_identity_fields_and_sensitive_extra_fields_are_rejected(){
        for(String field:List.of("principalId","appointmentId","draft","confirmation","basis")){
            var partial=scope(SAVE);partial.remove(field);assertThrows(IllegalArgumentException.class,()->parse(SAVE,partial));
        }
        for(String field:List.of("contactPhone","customerGoal","document")){
            var extra=scope(SAVE);extra.put(field,"protected input");assertThrows(IllegalArgumentException.class,()->parse(SAVE,extra));
        }
    }
    @Test void different_command_profile_cannot_be_replayed_as_confirmation(){
        var scope=scope(SAVE);assertThrows(IllegalArgumentException.class,()->parse(CONFIRM,scope));
        var handedOff=scope(CONFIRM);handedOff.put("basis",selector("opportunity.responsibility_handoff"));
        handedOff.put("confirmation",selector("opportunity.customer_requirement_confirmation"));
        assertEquals(handedOff,parse(CONFIRM,handedOff).customerRequirementsScope());
    }
}
