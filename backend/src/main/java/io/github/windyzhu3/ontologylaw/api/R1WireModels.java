package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit closed oneOf selection; generated models are never regenerated or widened here. */
final class R1WireModels {
    private static final JsonMapper MAPPER=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
    private R1WireModels(){}
    static <T> T model(Object value,Class<T> type){return MAPPER.convertValue(value,type);}
    static Object payload(Object value){return omitAbsent(MAPPER.convertValue(value,Map.class));}
    private static Object omitAbsent(Object value){
        if(value instanceof Map<?,?> map){var fields=new TreeMap<String,Object>();map.forEach((k,v)->{if(v!=null)fields.put((String)k,omitAbsent(v));});return fields;}
        if(value instanceof List<?> list)return list.stream().map(R1WireModels::omitAbsent).toList();
        if(value instanceof Integer||value instanceof Short||value instanceof Byte)return ((Number)value).longValue();
        return value;
    }
    static CommandReceipt receipt(Map<String,Object> body) {
        if("REJECTED".equals(body.get("outcome")))return MAPPER.convertValue(body,RejectedCommandReceipt.class);
        var fields=new TreeMap<>(body);Object result=fields.remove("resultFact");
        if(!(result instanceof Map<?,?> fact))throw new IllegalArgumentException("Invalid receipt projection");
        return MAPPER.convertValue(fields,SuccessfulCommandReceipt.class).resultFact(fact(fact));
    }
    static PublicFactRef fact(Map<?,?> body) {
        Class<? extends PublicFactRef> type=switch((String)body.get("factType")) {
            case "LEAD"->LeadFactRef.class;case "ACTION_DRAFT"->ActionDraftFactRef.class;
            case "TASK_OCCURRENCE"->TaskOccurrenceFactRef.class;case "DECISION_RECORD"->DecisionRecordFactRef.class;
            case "LEAD_ASSIGNMENT"->LeadAssignmentFactRef.class;case "LEAD_CONTACT_RESULT"->LeadContactResultFactRef.class;
            case "IDENTITY_PRINCIPAL"->IdentityPrincipalFactRefV1.class;case "ORGANIZATION_UNIT"->OrganizationUnitFactRefV1.class;case "APPOINTMENT"->AppointmentFactRefV1.class;case "AUTHORITY_GRANT"->AuthorityGrantFactRefV1.class;
            default->throw new IllegalArgumentException("Invalid receipt projection");
        };
        return MAPPER.convertValue(body,type);
    }
}
