package io.github.windyzhu3.ontologylaw.lead;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.time.*;import java.time.format.DateTimeFormatterBuilder;import java.util.*;

/** Closed schemas shared by saved candidates and their seven primary commands. */
final class LeadInputs {
    private LeadInputs(){}
    static Map<String,Object> object(Object p){if(!(p instanceof Map<?,?> m))throw new CommandHandler.Rejected("VALIDATION_FAILED");var result=new TreeMap<String,Object>();m.forEach((k,v)->result.put((String)k,v));return result;}
    static String string(Map<String,Object> p,String key){Object v=p.get(key);if(!(v instanceof String s))throw new IllegalArgumentException("Required string");return s;}
    static UUID uuid(Map<String,Object> p,String key){String s=string(p,key);if(!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("UUID required");return UUID.fromString(s);}
    static long revision(Map<String,Object> p,String key){Object v=p.get(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<0||((Number)v).longValue()>9007199254740991L)throw new IllegalArgumentException("Revision required");return ((Number)v).longValue();}
    static String hash(Map<String,Object> p,String key){String s=string(p,key);if(!s.matches("[A-Za-z0-9_-]{43}")||!Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(s)).equals(s))throw new IllegalArgumentException("Digest required");return s;}
    static void fields(Map<String,Object> p,Set<String> required,Set<String> optional){if(!p.keySet().containsAll(required)||p.keySet().stream().anyMatch(k->!required.contains(k)&&!optional.contains(k)))throw new IllegalArgumentException("Unexpected fields");}
    static String text(Map<String,Object> p,String key,int max){String s=LeadCanonicalization.text(string(p,key));int n=s.codePointCount(0,s.length());if(n<1||n>max)throw new IllegalArgumentException("Text length");return s;}
    static void code(Map<String,Object> p,String key){if(!string(p,key).matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Code required");}
    static void contacts(Map<String,Object> p){if(p.containsKey("phone")){String s=LeadCanonicalization.phone(string(p,"phone"));if(s==null)p.remove("phone");else p.put("phone",s);}if(p.containsKey("email")){String s=LeadCanonicalization.email(string(p,"email"));if(s==null)p.remove("email");else {if(s.codePointCount(0,s.length())>320)throw new IllegalArgumentException("Email length");p.put("email",s);}}}
    static Map<String,Object> capture(Object value){
        var p=object(value);fields(p,Set.of("sourceChannelCode","sourceAccountCode","sourceRecordKey","capturedAt","serviceCategoryCode","jurisdictionCode","urgencyCode","legalNeedSummary"),Set.of("capturedName","phone","email","cityCode"));
        for(String key:List.of("sourceChannelCode","serviceCategoryCode","jurisdictionCode","urgencyCode"))code(p,key);if(p.containsKey("cityCode"))code(p,"cityCode");
        for(String key:List.of("sourceAccountCode","sourceRecordKey")){String s=string(p,key);int n=s.codePointCount(0,s.length());if(n<1||n>(key.equals("sourceAccountCode")?128:256))throw new IllegalArgumentException("Source length");}
        Instant captured=OffsetDateTime.parse(string(p,"capturedAt")).toInstant();if(captured.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond time required");p.put("capturedAt",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(captured));
        if(p.containsKey("capturedName"))p.put("capturedName",text(p,"capturedName",200));p.put("legalNeedSummary",text(p,"legalNeedSummary",2000));contacts(p);return p;
    }
    static Map<String,Object> candidate(CommandEnvelope.Type type,Object value){
        var p=object(value);
        switch(type){
            case COMPLETE_LEAD_INGRESS -> {fields(p,Set.of("sourceCode","sourceSummary"),Set.of("phone","email"));contacts(p);if(!p.containsKey("phone")&&!p.containsKey("email"))throw new IllegalArgumentException("Contact required");if(!Set.of("OWNER_CONFIRMED","CUSTOMER_PROVIDED").contains(string(p,"sourceCode")))throw new IllegalArgumentException("Source required");p.put("sourceSummary",text(p,"sourceSummary",500));}
            case ASSIGN_LEAD -> {fields(p,Set.of("ownerAppointmentId"),Set.of());p.put("ownerAppointmentId",uuid(p,"ownerAppointmentId").toString());}
            case RECORD_ROUTING_DISPOSITION -> {fields(p,Set.of("decisionCode","rationaleSummary"),Set.of());if(!Set.of("SCHEDULE_ROUTING_REVIEW","RETRY_ASSIGNMENT_NOW","REQUEST_SOURCE_INTAKE_STOP").contains(string(p,"decisionCode")))throw new IllegalArgumentException("Decision required");p.put("rationaleSummary",text(p,"rationaleSummary",500));}
            case RESOLVE_DUPLICATE_LEAD -> {fields(p,Set.of("decisionCode","candidateLeadId","candidateLeadRevision","partyId","partyRevision","rationaleSummary"),Set.of());if(!Set.of("LINK_EXISTING_PARTY","KEEP_SEPARATE").contains(string(p,"decisionCode")))throw new IllegalArgumentException("Decision required");for(String key:List.of("candidateLeadId","partyId"))p.put(key,uuid(p,key).toString());for(String key:List.of("candidateLeadRevision","partyRevision"))p.put(key,revision(p,key));p.put("rationaleSummary",text(p,"rationaleSummary",500));}
            case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> {fields(p,Set.of("causalDecisionId","causalDecisionHash","rationaleSummary"),Set.of());p.put("causalDecisionId",uuid(p,"causalDecisionId").toString());hash(p,"causalDecisionHash");p.put("rationaleSummary",text(p,"rationaleSummary",500));}
            case RECORD_CONTACT_RESULT -> {
                fields(p,Set.of("leadAssignmentId","leadAssignmentRevision","contactChannelCode","resultCode"),Set.of("resultSummary","legalNeed","evidenceSubmissionId"));
                p.put("leadAssignmentId",uuid(p,"leadAssignmentId").toString());
                p.put("leadAssignmentRevision",revision(p,"leadAssignmentRevision"));
                if(!Set.of("PHONE","EMAIL").contains(string(p,"contactChannelCode")))throw new IllegalArgumentException("Contact channel required");
                if(!Set.of("CONNECTED_VALID","NOT_CONNECTED","SUSPECT_INVALID").contains(string(p,"resultCode")))throw new IllegalArgumentException("Contact result required");
                if("CONNECTED_VALID".equals(p.get("resultCode")))p.put("legalNeed",text(p,"legalNeed",2000));
                else if(p.containsKey("legalNeed"))throw new IllegalArgumentException("Legal need forbidden for this result");
                if(p.containsKey("resultSummary"))p.put("resultSummary",text(p,"resultSummary",500));
                if(p.containsKey("evidenceSubmissionId"))p.put("evidenceSubmissionId",uuid(p,"evidenceSubmissionId").toString());
            }
            case REVIEW_LEAD_VALIDITY -> {
                fields(p,Set.of("triggeringContactResultId","triggeringContactResultHash","decisionCode","rationaleSummary"),Set.of());
                p.put("triggeringContactResultId",uuid(p,"triggeringContactResultId").toString());
                hash(p,"triggeringContactResultHash");
                if(!Set.of("CONFIRM_INVALID","CLOSE_UNREACHED","REOPEN_CONTACT").contains(string(p,"decisionCode")))throw new IllegalArgumentException("Review decision required");
                p.put("rationaleSummary",text(p,"rationaleSummary",500));
            }
            default -> throw new IllegalArgumentException("Primary Task candidate required");
        }return Collections.unmodifiableMap(p);
    }
}
