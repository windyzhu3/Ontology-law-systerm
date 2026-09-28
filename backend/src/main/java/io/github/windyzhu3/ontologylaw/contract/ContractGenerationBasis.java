package io.github.windyzhu3.ontologylaw.contract;
import java.util.*;
/** A newly accepted material id is not known when rendering; all business inputs are. */
public final class ContractGenerationBasis {
    private ContractGenerationBasis(){}
    @SuppressWarnings("unchecked") public static String digest(Map<String,Object> payload){
        var copy=new TreeMap<String,Object>(payload);var values=new TreeMap<String,Object>((Map<String,Object>)copy.get("values"));var document=(Map<String,Object>)values.get("document");
        values.put("document",Map.of("templateVersionId",document.get("templateVersionId"),"clauseVersionIds",document.get("clauseVersionIds")));copy.put("values",values);
        return HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(copy)));
    }
}
