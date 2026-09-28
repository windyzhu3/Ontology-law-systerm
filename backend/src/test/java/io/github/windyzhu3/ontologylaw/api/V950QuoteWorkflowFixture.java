package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;

/** Seeds pre-V960 provenance with the pre-contract-preparation port, never used by current-schema tests. */
final class V950QuoteWorkflowFixture {
    private V950QuoteWorkflowFixture() {}
    static QuoteWorkflowService create(OpportunityProgressProtection protection) {
        var current=new QuoteWorkflowPorts(protection);
        var ports=(QuoteWorkflowService.Ports)Proxy.newProxyInstance(QuoteWorkflowService.Ports.class.getClassLoader(),
            new Class<?>[]{QuoteWorkflowService.Ports.class},(proxy,method,args)->{
                if(method.getName().equals("contractTakenOver"))
                    return !OpportunityContractReader.databaseBacked().forOpportunity((Connection)args[0],(UUID)args[1],(UUID)args[2]).isEmpty();
                try{return method.invoke(current,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        return QuoteWorkflowService.databaseBacked(protection,new QuoteDraftService.Codec(){
            public String encode(Map<String,Object> value){return CanonicalJson.encode(value);}
            @SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}
        },ports);
    }
}
