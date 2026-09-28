package io.github.windyzhu3.ontologylaw.api;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Untrusted model output: closed task fields and verbatim, locally verified citations. */
public final class R25AiCandidateContract {
    private R25AiCandidateContract() {}
    static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public enum Task {
        FIELDS(List.of("customerName","contactName","contactPhone","customerGoal")),
        SUMMARY(List.of("progressSummary")), MATERIALS(List.of("CONTRACT_BUSINESS","CORRESPONDENCE"));
        final List<String> fields;
        Task(List<String> fields){this.fields=fields;}
    }
    public record Source(String id,String label,String kind,String text) {
        @Override public String toString(){return "AiSource[protected]";}
    }
    public record Citation(String sourceId,String quote) {
        @Override public String toString(){return "AiCitation[protected]";}
    }
    public record Item(String field,String status,String value,List<Citation> citations) {
        public Item {citations=List.copyOf(citations);}
        @Override public String toString(){return "AiCandidateItem[protected]";}
    }
    public record Result(List<Item> items) {
        public Result {items=List.copyOf(items);}
        @Override public String toString(){return "AiCandidateResult[protected]";}
    }
    public static final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    public static void validateSources(List<Source> sources) {
        if(sources==null||sources.isEmpty())throw new Failure("AI_NO_BASIS");
        if(sources.size()>50)throw new Failure("AI_INPUT_TOO_LARGE");
        var ids=new HashSet<String>();long size=0;
        for(var source:sources) {
            if(source==null||source.id()==null||!source.id().matches("[A-Za-z0-9:_-]{1,100}")||!ids.add(source.id())||!Set.of("TEXT","PROGRESS","MATERIAL","RULE").contains(source.kind()))throw new Failure("AI_INVALID_SOURCE");
            validText(source.label(),200);validText(source.text(),32000);size+=source.text().length()+source.label().length()+source.id().length();
        }
        if(size>32000)throw new Failure("AI_INPUT_TOO_LARGE");
    }
    static void validText(String value,int max) {
        if(value==null||value.isBlank()||value.length()>max)throw new Failure("AI_INVALID_OUTPUT");
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.isHighSurrogate(ch)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new Failure("AI_INVALID_OUTPUT");}
            else if(Character.isLowSurrogate(ch)||ch<32&&ch!='\n'&&ch!='\r'&&ch!='\t'||ch>=127&&ch<=159)throw new Failure("AI_INVALID_OUTPUT");
        }
    }
    public static Result parse(Task task,List<Source> sources,String text) {
        Objects.requireNonNull(task);validateSources(sources);
        if(text==null||text.length()>32000)throw new Failure("AI_INVALID_OUTPUT");
        try {
            var sourceById=new HashMap<String,Source>();sources.forEach(s->sourceById.put(s.id(),s));
            Map<?,?> root=JSON.readValue(text,Map.class);keys(root,"items");
            if(!(root.get("items") instanceof List<?> rows)||rows.isEmpty()||rows.size()>task.fields.size())throw new Failure("AI_INVALID_OUTPUT");
            var fields=new HashSet<String>();var result=new ArrayList<Item>();
            for(var raw:rows) {
                if(!(raw instanceof Map<?,?> item))throw new Failure("AI_INVALID_OUTPUT");keys(item,"field","status","value","citations");
                String field=string(item.get("field")),status=string(item.get("status"));
                if(!task.fields.contains(field)||!fields.add(field)||!Set.of("CANDIDATE","MISSING","CONFLICT").contains(status))throw new Failure("AI_INVALID_OUTPUT");
                String value=null;
                if("CANDIDATE".equals(status)){value=string(item.get("value"));validText(value,field.equals("contactPhone")?50:field.equals("contactName")||field.equals("customerName")?200:2000);}
                else if(item.get("value")!=null)throw new Failure("AI_INVALID_OUTPUT");
                if(!(item.get("citations") instanceof List<?> rawCitations)||rawCitations.isEmpty()||rawCitations.size()>5)throw new Failure("AI_INVALID_OUTPUT");
                var citations=new ArrayList<Citation>();boolean rule=false;var seen=new HashSet<Citation>();
                for(var ref:rawCitations) {
                    if(!(ref instanceof Map<?,?> citation))throw new Failure("AI_INVALID_OUTPUT");keys(citation,"sourceId","quote");
                    String id=string(citation.get("sourceId")),quote=string(citation.get("quote"));validText(quote,500);
                    var source=sourceById.get(id);
                    if(source==null||!source.text().contains(quote)||!seen.add(new Citation(id,quote)))throw new Failure("AI_INVALID_OUTPUT");
                    rule|="RULE".equals(source.kind());citations.add(new Citation(id,quote));
                }
                if(task==Task.MATERIALS&&!rule)throw new Failure("AI_INVALID_OUTPUT");
                result.add(new Item(field,status,value,citations));
            }
            return new Result(result);
        } catch(Failure failure){throw failure;}catch(RuntimeException ignored){throw new Failure("AI_INVALID_OUTPUT");}
    }
    private static String string(Object v){if(!(v instanceof String s))throw new Failure("AI_INVALID_OUTPUT");return s;}
    private static void keys(Map<?,?> m,String... keys){if(m==null||!m.keySet().equals(Set.of(keys)))throw new Failure("AI_INVALID_OUTPUT");}
    static Map<String,Object> schema(Task task,List<Source> sources) {
        var citation=object(Map.of("sourceId",Map.of("type","string","enum",sources.stream().map(Source::id).toList()),"quote",Map.of("type","string","minLength",1,"maxLength",500)));
        var item=object(Map.of("field",Map.of("type","string","enum",task.fields),"status",Map.of("type","string","enum",List.of("CANDIDATE","MISSING","CONFLICT")),"value",Map.of("anyOf",List.of(Map.of("type","string","maxLength",2000),Map.of("type","null"))),"citations",Map.of("type","array","items",citation,"minItems",1,"maxItems",5)));
        return object(Map.of("items",Map.of("type","array","items",item,"minItems",1,"maxItems",task.fields.size())));
    }
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false);}
}
