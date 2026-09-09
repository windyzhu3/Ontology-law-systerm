package io.github.windyzhu3.ontologylaw.api;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validate original bytes before generated optional-null fields erase absence/type/duplicate distinctions. */
@ControllerAdvice(basePackageClasses=R1ApiDelegate.class)
public class R1RequestBodyValidation extends RequestBodyAdviceAdapter {
    private static final JsonMapper PARSER=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){return true;}
    public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter)throws IOException {
        byte[] bytes=input.getBody().readNBytes(1_048_577);if(bytes.length>1_048_576)throw R1HttpFailure.validation("/","OUT_OF_RANGE");
        JsonNode tree;try{tree=PARSER.readTree(bytes);}catch(RuntimeException malformed){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        if(tree==null||!tree.isObject())throw R1HttpFailure.validation("/","INVALID_FORMAT");
        boolean identity=parameter.getContainingClass()==IdentityAdminController.class;
        if(identity){var fields=new java.util.HashMap<String,Object>();for(var property:tree.properties())fields.put(property.getKey(),property.getValue().isNull()?null:property.getValue().isString()?property.getValue().asString():property.getValue());
            String method=parameter.getMethod().getName();String command=method.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(java.util.Locale.ROOT);
            try{tree=PARSER.valueToTree(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.validate(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.handler(command),fields));}catch(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure invalid){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        }else validate(tree,"",0);
        normalizeSafeText(tree,parameter.getMethod().getName());byte[] normalized=PARSER.writeValueAsBytes(tree);
        return new HttpInputMessage(){public HttpHeaders getHeaders(){return input.getHeaders();}public InputStream getBody(){return new ByteArrayInputStream(normalized);}};
    }
    private static void normalizeSafeText(JsonNode tree,String method){
        java.util.Set<String> fields;String prefix="";
        if(method.equals("captureLead"))fields=java.util.Set.of("capturedName","legalNeedSummary");
        else if(method.equals("saveActionDraft")){var action=tree.get("actionCode");fields=action!=null&&action.isString()?safeFields(action.asString()):java.util.Set.of();tree=tree.get("values");prefix="/values";}
        else fields=safeFields(switch(method){case "completeLeadIngress"->"COMPLETE_LEAD_INGRESS";case "recordContactResult"->"RECORD_CONTACT_RESULT";case "resolveDuplicateLead"->"RESOLVE_DUPLICATE_LEAD";case "recordRoutingDisposition"->"RECORD_ROUTING_DISPOSITION";case "acknowledgeSourceIntakeStopRequest"->"ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST";case "reviewLeadValidity"->"REVIEW_LEAD_VALIDITY";default->"";});
        if(!(tree instanceof tools.jackson.databind.node.ObjectNode object))return;
        for(String field:fields){var node=object.get(field);if(node==null||!node.isString())continue;String value=node.asString();
            if(value.codePoints().anyMatch(cp->cp<32||cp>=127&&cp<=159))throw R1HttpFailure.validation(prefix+"/"+field,"INVALID_FORMAT");
            try{object.put(field,io.github.windyzhu3.ontologylaw.lead.LeadCanonicalization.text(value));}catch(IllegalArgumentException invalid){throw R1HttpFailure.validation(prefix+"/"+field,"INVALID_FORMAT");}
        }
    }
    private static java.util.Set<String> safeFields(String action){return switch(action){
        case "COMPLETE_LEAD_INGRESS"->java.util.Set.of("sourceSummary");case "RECORD_CONTACT_RESULT"->java.util.Set.of("resultSummary","legalNeed");
        case "RESOLVE_DUPLICATE_LEAD","RECORD_ROUTING_DISPOSITION","ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","REVIEW_LEAD_VALIDITY"->java.util.Set.of("rationaleSummary");default->java.util.Set.of();};}
    private static void validate(JsonNode tree,String pointer,int depth){
        String at=pointer.isEmpty()?"/":pointer;
        if(depth>64)throw R1HttpFailure.validation(at,"OUT_OF_RANGE");
        if(tree.isNull())throw R1HttpFailure.validation(at,"NOT_ALLOWED");
        if(tree.isFloatingPointNumber())throw R1HttpFailure.validation(at,"INVALID_FORMAT");
        if(tree.isIntegralNumber()&&(!tree.canConvertToLong()||tree.longValue() < -9007199254740991L||tree.longValue()>9007199254740991L))throw R1HttpFailure.validation(at,"OUT_OF_RANGE");
        if(tree.isObject())for(var property:tree.properties())validate(property.getValue(),pointer+"/"+property.getKey().replace("~","~0").replace("/","~1"),depth+1);
        if(tree.isArray())for(int i=0;i<tree.size();i++)validate(tree.get(i),pointer+"/"+i,depth+1);
    }
}
