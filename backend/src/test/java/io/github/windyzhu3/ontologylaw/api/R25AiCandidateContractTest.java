package io.github.windyzhu3.ontologylaw.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class R25AiCandidateContractTest {
    private static final List<R25AiCandidateContract.Source> SOURCES = List.of(
        new R25AiCandidateContract.Source("lead:1", "线索原文", "TEXT", "联系人林悦，电话未提供。需要追收合同款。"));
    private static String candidate(String field, String value, String ref, String quote) {
        return "{\"items\":[{\"field\":\""+field+"\",\"status\":\"CANDIDATE\",\"value\":\""+value+"\",\"citations\":[{\"sourceId\":\""+ref+"\",\"quote\":\""+quote+"\"}]}]}";
    }
    @Test void acceptsOnlyExactCitationsAndClosedTaskFields() {
        var result=R25AiCandidateContract.parse(R25AiCandidateContract.Task.FIELDS,SOURCES,candidate("contactName","林悦","lead:1","联系人林悦"));
        assertEquals("林悦",result.items().getFirst().value());
        assertEquals("lead:1",result.items().getFirst().citations().getFirst().sourceId());
        assertFalse(result.toString().contains("林悦"));
        assertFalse(SOURCES.toString().contains("林悦"));
    }
    @Test void refusesInventedReferencesQuotesCommandsAndDuplicateKeys() {
        for(var json:List.of(candidate("contactName","林悦","other","联系人林悦"),
            candidate("contactName","林悦","lead:1","联系人张三"),
            candidate("approveContract","true","lead:1","联系人林悦"),
            candidate("progressSummary","摘要","lead:1","联系人林悦"),
            candidate("contactName","林悦","lead:1","联系人林悦").replace("\"value\":","\"value\":\"先值\",\"value\":"),
            candidate("contactName","林悦","lead:1","联系人林悦").replace("\"items\":","\"command\":\"CONFIRM\",\"items\":"))) {
            assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.parse(R25AiCandidateContract.Task.FIELDS,SOURCES,json));
        }
    }
    @Test void missingOrConflictingValuesCannotBecomeFormValues() {
        var valid=candidate("contactPhone","not supplied","lead:1","电话未提供").replace("\"CANDIDATE\"","\"MISSING\"").replace("\"not supplied\"","null");
        assertNull(R25AiCandidateContract.parse(R25AiCandidateContract.Task.FIELDS,SOURCES,valid).items().getFirst().value());
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.parse(R25AiCandidateContract.Task.FIELDS,SOURCES,valid.replace("null","\"13800000000\"")));
    }
    @Test void materialSuggestionsMustCiteActualPreparationRule() {
        var sources=new ArrayList<>(SOURCES);
        sources.add(new R25AiCandidateContract.Source("rule:1","销售准备参考","RULE","相关往来记录：仅供准备参考，不改变准入。"));
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.parse(R25AiCandidateContract.Task.MATERIALS,sources,candidate("CORRESPONDENCE","请核对","lead:1","联系人林悦")));
        assertEquals(1,R25AiCandidateContract.parse(R25AiCandidateContract.Task.MATERIALS,sources,candidate("CORRESPONDENCE","请核对","rule:1","相关往来记录")).items().size());
    }
    @Test void oversizeDuplicateSourcesAndEmptyBasisNeverReachModel() {
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.validateSources(List.of()));
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.validateSources(List.of(SOURCES.getFirst(),SOURCES.getFirst())));
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.validateSources(List.of(new R25AiCandidateContract.Source("a","资料","TEXT","x".repeat(32001)))));
        var duplicate=candidate("contactName","林悦","lead:1","联系人林悦");
        var item=duplicate.substring(10,duplicate.length()-2);
        assertThrows(R25AiCandidateContract.Failure.class,()->R25AiCandidateContract.parse(R25AiCandidateContract.Task.FIELDS,SOURCES,"{\"items\":["+item+","+item+"]}"));
    }
}
