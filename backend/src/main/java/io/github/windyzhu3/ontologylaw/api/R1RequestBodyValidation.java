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
        if(parameter.getMethod().getName().equals("uploadOpportunityMaterialContent")){
            final long limit=io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore.MAX_BYTES;
            if(input.getHeaders().getContentLength()>limit)throw R1HttpFailure.validation("/","OUT_OF_RANGE");
            final InputStream bounded=new FilterInputStream(input.getBody()){
                private long count;
                private void consumed(int n)throws io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException{if(n>0&&(count+=n)>limit)throw new io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException(io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException.Reason.TOO_LARGE);}
                @Override public int read()throws IOException{int value=super.read();if(value>=0)consumed(1);return value;}
                @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,(int)Math.min(len,limit-count+1));consumed(n);return n;}
            };
            return new HttpInputMessage(){public HttpHeaders getHeaders(){return input.getHeaders();}public InputStream getBody(){return bounded;}};
        }
        byte[] bytes=input.getBody().readNBytes(1_048_577);if(bytes.length>1_048_576)throw R1HttpFailure.validation("/","OUT_OF_RANGE");
        JsonNode tree;try{tree=PARSER.readTree(bytes);}catch(RuntimeException malformed){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        if(tree==null||!tree.isObject())throw R1HttpFailure.validation("/","INVALID_FORMAT");
        boolean identity=parameter.getContainingClass()==IdentityAdminController.class;
        if(parameter.getContainingClass()==R2FollowupAttemptApiDelegate.class){
            var required=java.util.Set.of("expectedOpportunityRevision","responsibilityBasis","task","waitReceipt","expectedWorkflow","values");
            if(tree.size()!=required.size())throw R1HttpFailure.validation("/","CONDITION_FAILED");for(String field:required)if(!tree.has(field))throw R1HttpFailure.validation("/"+field,"CONDITION_FAILED");
            for(var property:tree.properties())if(!(java.util.Set.of("waitReceipt","expectedWorkflow").contains(property.getKey())&&property.getValue().isNull()))validate(property.getValue(),"/"+property.getKey(),0);
        }else if(parameter.getContainingClass()==R2TransferApiDelegate.class){
            var required=java.util.Set.of("expectedOpportunityRevision","expectedWorkflow","values");if(tree.size()!=required.size())throw R1HttpFailure.validation("/","CONDITION_FAILED");for(String field:required)if(!tree.has(field))throw R1HttpFailure.validation("/"+field,"CONDITION_FAILED");validateTransfer(tree,"",0);
        }else if(parameter.getContainingClass()==R2ContractApiDelegate.class){
            var required=java.util.Set.of("expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedContract","expectedDraft","expectedVersion","expectedWorkflow","values");
            if(tree.size()!=required.size())throw R1HttpFailure.validation("/","CONDITION_FAILED");for(String field:required)if(!tree.has(field))throw R1HttpFailure.validation("/"+field,"CONDITION_FAILED");
            validateContract(tree,"",0);
        }else if(parameter.getMethod().getName().equals("repairSupersededOpportunityTask")){
            var originalDraft=tree.get("draft");if(originalDraft==null)throw R1HttpFailure.validation("/draft","CONDITION_FAILED");
            var withoutDraft=tree.deepCopy();((tools.jackson.databind.node.ObjectNode)withoutDraft).remove("draft");validate(withoutDraft,"",0);if(!originalDraft.isNull())validate(originalDraft,"/draft",0);
        }else if(parameter.getMethod().getName().equals("reconcileContractPreparation")){
            validateContract(tree,"",0);
        }else if(parameter.getContainingClass()==R2QuoteApiDelegate.class){
            var required=java.util.Set.of("expectedOpportunityRevision","responsibilityBasis","customerConfirmation","expectedDraft","expectedQuote","expectedWorkflow","values");
            if(tree.size()!=required.size())throw R1HttpFailure.validation("/","CONDITION_FAILED");for(String field:required)if(!tree.has(field))throw R1HttpFailure.validation("/"+field,"CONDITION_FAILED");
            validateQuote(tree,"",0);
        }else if(identity){var fields=new java.util.HashMap<String,Object>();for(var property:tree.properties())fields.put(property.getKey(),property.getValue().isNull()?null:property.getValue().isString()?property.getValue().asString():property.getValue());
            String method=parameter.getMethod().getName();String command=method.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(java.util.Locale.ROOT);
            try{tree=PARSER.valueToTree(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.validate(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.handler(command),fields));}catch(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure invalid){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        }else if(java.util.Set.of("transferOpportunityResponsibility","recordOpportunityOwnerCoordination","observeOpportunityOwnerException").contains(parameter.getMethod().getName())){
            String method=parameter.getMethod().getName();var command=switch(method){case "transferOpportunityResponsibility"->io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY;case "recordOpportunityOwnerCoordination"->io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION;default->io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION;};
            Object principal=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if(!(principal instanceof io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor actor))throw new R1HttpFailure("NOT_AUTHORIZED");
            boolean serviceCommand=command==io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION;
            if(actor.onBehalfAppointmentId()!=null||serviceCommand!=(actor.principalKind()==io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.SERVICE))throw new R1HttpFailure("NOT_AUTHORIZED");
            try{R2OpportunityOwnerExceptionInput.parse(new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(command,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),actor,PARSER.convertValue(tree,java.util.Map.class)));}
            catch(io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected|IllegalArgumentException invalid){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        }else if(java.util.Set.of("openOpportunityMaterialUpload","acceptOpportunityMaterial").contains(parameter.getMethod().getName())){
            var actor=(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor)org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if(tree.has("opportunityId"))throw R1HttpFailure.validation("/opportunityId","NOT_ALLOWED");
            var payload=new java.util.TreeMap<String,Object>(PARSER.convertValue(tree,java.util.Map.class));payload.put("opportunityId","00000000-0000-0000-0000-000000000001");
            var command=parameter.getMethod().getName().equals("openOpportunityMaterialUpload")?io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD:io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL;
            try{R2MaterialsInput.parse(new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(command,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),actor,payload));}catch(io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected|IllegalArgumentException invalid){throw R1HttpFailure.validation("/","INVALID_FORMAT");}
        }else if(java.util.Set.of("saveOpportunityCustomerDraft","confirmOpportunityCustomerRequirements").contains(parameter.getMethod().getName())){
            R2CustomerRequirementsJson.validate(tree,parameter.getMethod().getName().equals("saveOpportunityCustomerDraft"));
        }else if(parameter.getMethod().getName().equals("closeOpportunity")){
            var fields=java.util.Set.of("expectedOpportunityRevision","expectedResponsibility","expectedTask","expectedWait","reasonCode","summary");
            if(tree.size()!=fields.size())throw R1HttpFailure.validation("/","CONDITION_FAILED");for(String field:fields)if(!tree.has(field))throw R1HttpFailure.validation("/","CONDITION_FAILED");
            for(var property:tree.properties())if(!(java.util.Set.of("expectedTask","expectedWait").contains(property.getKey())&&property.getValue().isNull()))validate(property.getValue(),"/"+property.getKey(),0);
        }else validate(tree,"",0);
        normalizeSafeText(tree,parameter.getMethod().getName());byte[] normalized=PARSER.writeValueAsBytes(tree);
        return new HttpInputMessage(){public HttpHeaders getHeaders(){return input.getHeaders();}public InputStream getBody(){return new ByteArrayInputStream(normalized);}};
    }
    private static void normalizeSafeText(JsonNode tree,String method){
        java.util.Set<String> fields;String prefix="";
        if(method.equals("captureLead"))fields=java.util.Set.of("capturedName","legalNeedSummary");
        else if(java.util.Set.of("saveActionDraft","saveSourceRequestDraft").contains(method)){var action=tree.get("actionCode");fields=action!=null&&action.isString()?safeFields(action.asString()):java.util.Set.of();tree=tree.get("values");prefix="/values";}
        else fields=safeFields(switch(method){case "completeLeadIngress"->"COMPLETE_LEAD_INGRESS";case "recordContactResult"->"RECORD_CONTACT_RESULT";case "resolveDuplicateLead"->"RESOLVE_DUPLICATE_LEAD";case "recordSourceRequestContinuation"->"RECORD_SOURCE_REQUEST_CONTINUATION";case "recordRoutingDisposition"->"RECORD_ROUTING_DISPOSITION";case "acknowledgeSourceIntakeStopRequest"->"ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST";case "reviewLeadValidity"->"REVIEW_LEAD_VALIDITY";default->"";});
        if(!(tree instanceof tools.jackson.databind.node.ObjectNode object))return;
        for(String field:fields){var node=object.get(field);if(node==null||!node.isString())continue;String value=node.asString();
            if(value.codePoints().anyMatch(cp->cp<32||cp>=127&&cp<=159))throw R1HttpFailure.validation(prefix+"/"+field,"INVALID_FORMAT");
            try{object.put(field,io.github.windyzhu3.ontologylaw.lead.LeadCanonicalization.text(value));}catch(IllegalArgumentException invalid){throw R1HttpFailure.validation(prefix+"/"+field,"INVALID_FORMAT");}
        }
    }
    private static java.util.Set<String> safeFields(String action){return switch(action){
        case "COMPLETE_LEAD_INGRESS"->java.util.Set.of("sourceSummary");case "RECORD_CONTACT_RESULT"->java.util.Set.of("resultSummary","legalNeed");
        case "RECORD_SOURCE_REQUEST_CONTINUATION","RESOLVE_DUPLICATE_LEAD","RECORD_ROUTING_DISPOSITION","ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","REVIEW_LEAD_VALIDITY"->java.util.Set.of("rationaleSummary");default->java.util.Set.of();};}
    private static void validateTransfer(JsonNode tree,String pointer,int depth){
        if(depth>32)throw R1HttpFailure.validation(pointer.isEmpty()?"/":pointer,"OUT_OF_RANGE");if(tree.isNull()){if(!pointer.equals("/values/requirement"))throw R1HttpFailure.validation(pointer,"NOT_ALLOWED");return;}if(tree.isFloatingPointNumber())throw R1HttpFailure.validation(pointer,"INVALID_FORMAT");if(tree.isIntegralNumber()&&(!tree.canConvertToLong()||tree.longValue()<0||tree.longValue()>9007199254740991L))throw R1HttpFailure.validation(pointer,"OUT_OF_RANGE");if(tree.isObject())for(var f:tree.properties())validateTransfer(f.getValue(),pointer+"/"+f.getKey().replace("~","~0").replace("/","~1"),depth+1);if(tree.isArray())for(int i=0;i<tree.size();i++)validateTransfer(tree.get(i),pointer+"/"+i,depth+1);
    }
    private static void validateContract(JsonNode tree,String pointer,int depth){
        if(depth>64)throw R1HttpFailure.validation(pointer.isEmpty()?"/":pointer,"OUT_OF_RANGE");
        if(tree.isNull()){if(!java.util.Set.of("/customerConfirmation","/expectedContract","/expectedDraft","/expectedVersion","/expectedWorkflow","/values/commercial/conditionalFee","/values/paymentGate/requiredMinor","/values/expectedTermination","/values/expectedSignatureWorkflow").contains(pointer))throw R1HttpFailure.validation(pointer,"NOT_ALLOWED");return;}
        if(tree.isFloatingPointNumber())throw R1HttpFailure.validation(pointer,"INVALID_FORMAT");
        if(tree.isIntegralNumber()&&(!tree.canConvertToLong()||tree.longValue() < -9007199254740991L||tree.longValue()>9007199254740991L))throw R1HttpFailure.validation(pointer,"OUT_OF_RANGE");
        if(tree.isObject())for(var field:tree.properties())validateContract(field.getValue(),pointer+"/"+field.getKey().replace("~","~0").replace("/","~1"),depth+1);
        if(tree.isArray())for(int i=0;i<tree.size();i++)validateContract(tree.get(i),pointer+"/"+i,depth+1);
    }
    private static void validateQuote(JsonNode tree,String pointer,int depth){
        if(depth>32)throw R1HttpFailure.validation(pointer,"OUT_OF_RANGE");
        if(tree.isNull()){if(!java.util.Set.of("/customerConfirmation","/expectedDraft","/expectedQuote","/expectedWorkflow","/values/conditionalFee","/values/nextCheckAt").contains(pointer))throw R1HttpFailure.validation(pointer,"NOT_ALLOWED");return;}
        if(tree.isFloatingPointNumber())throw R1HttpFailure.validation(pointer,"INVALID_FORMAT");
        if(tree.isIntegralNumber()&&(!tree.canConvertToLong()||tree.longValue() < -9007199254740991L||tree.longValue()>9007199254740991L))throw R1HttpFailure.validation(pointer,"OUT_OF_RANGE");
        if(tree.isObject())for(var f:tree.properties())validateQuote(f.getValue(),pointer+"/"+f.getKey(),depth+1);
        if(tree.isArray())for(int i=0;i<tree.size();i++)validateQuote(tree.get(i),pointer+"/"+i,depth+1);
    }
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
