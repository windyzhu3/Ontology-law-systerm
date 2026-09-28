package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class R1EventPolicyTest {
    @Test void descriptors_and_success_branches_match_the_canonical_contract_without_a_test_registry()throws Exception {
        var root=PostgresIntegrationTest.repositoryRoot();
        String contract=Files.readString(root.resolve("docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md"));
        var descriptors=rows(contract,"Event descriptor registry");
        // Approved sales-chain repair additions retain the original lead projection owner.
        var repairEvents=Set.of(CommandHandler.Event.SalesFollowupAttemptRecordedV1,CommandHandler.Event.SourceRequestContinuationRecordedV1,CommandHandler.Event.SourceRequestTaskRestoredV1,CommandHandler.Event.SourceRequestReviewReopenedV1);
        assertEquals(descriptors.size()+repairEvents.size(),Arrays.stream(CommandHandler.Event.values()).filter(e->e.queueOwners().contains(CommandHandler.QueueOwner.R1_PROJECTION)).count());
        for(var event:repairEvents){assertEquals(1,event.schemaVersion());assertEquals(Set.of(CommandHandler.QueueOwner.R1_PROJECTION),event.queueOwners());assertFalse(descriptors.stream().anyMatch(row->row.get(0).equals(event.name())));}
        for(var row:descriptors) {
            var event=CommandHandler.Event.valueOf(row.get(0));
            assertEquals(Integer.parseInt(row.get(1)),event.schemaVersion());assertEquals(row.get(2),event.sourceFactType());assertEquals(row.get(3),event.sourceSelector());
            assertEquals(Set.of(row.get(4)),event.queueOwners().stream().map(Enum::name).collect(Collectors.toSet()));
        }
        var branches=rows(contract,"Success branch event registry");assertEquals(branches.size(),R1EventPolicy.branches().size());
        for(var row:branches) {
            var branch=R1EventPolicy.branches().stream().filter(b->b.id().equals(row.get(0))).findFirst().orElseThrow();
            assertEquals(row.get(1),branch.command().name());assertEquals(row.get(2),branch.outcome());
            assertEquals(Set.of(row.get(3).split(",")),branch.events().stream().map(Enum::name).collect(Collectors.toSet()));
            assertEquals(Integer.parseInt(row.get(4)),branch.events().size());assertEquals(Integer.parseInt(row.get(5)),branch.events().stream().mapToInt(e->e.queueOwners().size()).sum());
        }
        var schema=JsonMapper.builder().build().readTree(Files.readString(root.resolve("contracts/events/r1-domain-notification-v1.schema.json")));
        assertEquals("object",schema.path("type").asString());assertTrue(schema.path("properties").isObject());assertEquals(0,schema.path("properties").size());
        assertTrue(schema.path("additionalProperties").isBoolean());assertFalse(schema.path("additionalProperties").asBoolean());
    }
    private static List<List<String>> rows(String contract,String section) {
        String part=contract.split("## "+section+"\\R",2)[1].split("\\R## ",2)[0];
        return part.lines().filter(line->line.startsWith("| ")).skip(1).map(line->Arrays.stream(line.substring(1,line.length()-1).split("\\|",-1)).map(String::trim).toList()).toList();
    }
}
