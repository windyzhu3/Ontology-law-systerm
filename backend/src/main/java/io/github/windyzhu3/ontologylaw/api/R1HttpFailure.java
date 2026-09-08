package io.github.windyzhu3.ontologylaw.api;

/** Safe code only; causes and protected values never enter the HTTP failure carrier. */
final class R1HttpFailure extends RuntimeException {
    private final String code;
    private final java.util.Map<String,Object> receiptRef;
    private final java.util.Map<String,Object> currentETag;
    private final java.util.List<java.util.Map<String,Object>> fields;
    R1HttpFailure(String code){this(code,null,null,null);}
    R1HttpFailure(String code,java.util.Map<String,Object> receiptRef){this(code,receiptRef,null,null);}
    R1HttpFailure(String code,java.util.Map<String,Object> receiptRef,java.util.Map<String,Object> currentETag,java.util.List<java.util.Map<String,Object>> fields){super(code,null,false,false);this.code=code;this.receiptRef=receiptRef;this.currentETag=currentETag;this.fields=fields;}
    String code(){return code;}
    java.util.Map<String,Object> receiptRef(){return receiptRef;}
    java.util.Map<String,Object> currentETag(){return currentETag;}
    java.util.List<java.util.Map<String,Object>> fields(){return fields;}
    static R1HttpFailure validation(String pointer,String code){return new R1HttpFailure("VALIDATION_FAILED",null,null,java.util.List.of(java.util.Map.of("pointer",pointer.length()<=512?pointer:"/","code",code,"detail","请求字段未通过校验")));}
}
