package io.github.windyzhu3.ontologylaw.query;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.*;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.Lead;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityCommandReader.Header;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.Responsibility;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractWorkCardQueryTest {
    private static Subject subject(String type){return new Subject(type,UUID.randomUUID(),0L,null);}
    @Test void all_contract_cards_route_to_purpose_only_forms_with_exact_completion_and_owner() {
        var now=Instant.parse("2026-09-21T01:00:00Z");
        for(var type:TaskFactory.Type.values())if(type.isContract()) {
            var appointment=subject("identity.appointment");var principal=subject("identity.principal");var organization=subject("identity.organization_unit");
            var actor=new Actor(UUID.randomUUID(),principal.id(),appointment.id(),null,null,PrincipalKind.HUMAN);
            var owner=new Owner(new Appointment(appointment,principal.id(),organization.id(),"SYNTHETIC","ACTIVE",now.minusSeconds(1),null),new Principal(principal,"合成办理人","HUMAN","ACTIVE"),new Organization(organization,"SYNTHETIC","合成组织","ACTIVE"));
            var opportunity=subject("opportunity.opportunity");var sales=type.independentDecisionOwner()?UUID.randomUUID():appointment.id();
            var task=new CurrentTaskReader.Task(subject("responsibility.task_occurrence"),appointment.id(),type,opportunity,"OPEN",now,type.slaCode(),type.slaSeconds(),now.plusSeconds(1000),null,opportunity);
            var lead=new Lead(subject("lead.lead"),"SYNTHETIC","SYNTHETIC",now,"合成客户",null,null,"合成需求",null,null,null,null,null,"RESOLVED",null,null);
            var data=new OpportunityWorkCardQuery.Data(task,new Header(opportunity,sales,false),lead,owner,null,new Responsibility(opportunity,sales));
            var projected=new OpportunityWorkCardQuery().project(actor,now,data);
            assertEquals(type.publicCompletionType(),projected.card().get("expectedCompletionFact"));
            assertEquals(type.name(),projected.card().get("taskType"));
            assertNull(projected.card().get("actionDraft"));
            assertEquals(Map.of("actionCode",type.command,"schemaVersion",1,"values",Map.of(),"fields",List.of()),projected.card().get("commandForm"));
            assertFalse(projected.card().toString().contains("合同正文"));
            var stale=new OpportunityWorkCardQuery.Data(task,new Header(opportunity,sales,false),lead,owner,null,new Responsibility(subject("opportunity.responsibility_handoff"),sales));
            assertThrows(IllegalArgumentException.class,()->new OpportunityWorkCardQuery().project(actor,now,stale));
        }
    }
}
