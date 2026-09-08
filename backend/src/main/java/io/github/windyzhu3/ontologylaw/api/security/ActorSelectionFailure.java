package io.github.windyzhu3.ontologylaw.api.security;

import org.springframework.security.core.AuthenticationException;

/** Closed error metadata; never include a submitted selector value. */
public final class ActorSelectionFailure extends AuthenticationException {
    private final String pointer;
    private ActorSelectionFailure(String code,String pointer){super(code);this.pointer=pointer;}
    public static ActorSelectionFailure denied(){return new ActorSelectionFailure("NOT_AUTHORIZED",null);}
    public static ActorSelectionFailure malformed(String header){
        if(!header.equals("X-Appointment-Id")&&!header.equals("X-On-Behalf-Appointment-Id"))throw new IllegalArgumentException("Invalid selector field");
        return new ActorSelectionFailure("VALIDATION_FAILED","/headers/"+header);
    }
    public String pointer(){return pointer;}
}
