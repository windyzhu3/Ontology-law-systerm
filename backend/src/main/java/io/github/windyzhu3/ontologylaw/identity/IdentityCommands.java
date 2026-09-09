package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.*;
import java.sql.*;
import java.util.*;

/** Closed HUMAN/DIRECT command registry; no dependency on Execution or business owners. */
public final class IdentityCommands {
    private IdentityCommands(){}
    public static final List<String> ROLES=List.of("INTAKE_OPERATOR","ROUTING_SUPERVISOR","CONTACT_OPERATOR");
    public static final List<String> GRANTABLE=List.of("LEAD_CAPTURE","LEAD_INGRESS_RESOLVE","LEAD_INGRESS_COMPLETE","LEAD_ASSIGN","LEAD_ROUTING_DECIDE","SOURCE_INTAKE_REQUEST_ACK","SALES_CONTACT_OWNER","LEAD_VALIDITY_REVIEW");
    public static final List<String> MANAGEMENT=List.of("IDENTITY_PRINCIPAL_MANAGE","IDENTITY_ORGANIZATION_MANAGE","IDENTITY_APPOINTMENT_MANAGE","IDENTITY_AUTHORITY_MANAGE");
    public record Handler(String command,Kind kind,String action,String authority) {
        public boolean create(){return action.equals("CREATE");}
        public boolean rootRequired(){return kind==Kind.PRINCIPAL||command.equals("CREATE_APPOINTMENT");}
    }
    private static Handler h(String command,Kind kind,String action,int authority){return new Handler(command,kind,action,MANAGEMENT.get(authority));}
    private static final List<Handler> HANDLERS=List.of(
        h("CREATE_IDENTITY_PRINCIPAL",Kind.PRINCIPAL,"CREATE",0),h("RENAME_IDENTITY_PRINCIPAL",Kind.PRINCIPAL,"RENAME",0),
        h("SUSPEND_IDENTITY_PRINCIPAL",Kind.PRINCIPAL,"SUSPENDED",0),h("RESUME_IDENTITY_PRINCIPAL",Kind.PRINCIPAL,"ACTIVE",0),h("DISABLE_IDENTITY_PRINCIPAL",Kind.PRINCIPAL,"DISABLED",0),
        h("CREATE_ORGANIZATION_UNIT",Kind.ORGANIZATION,"CREATE",1),h("RENAME_ORGANIZATION_UNIT",Kind.ORGANIZATION,"RENAME",1),h("CLOSE_ORGANIZATION_UNIT",Kind.ORGANIZATION,"CLOSED",1),
        h("CREATE_APPOINTMENT",Kind.APPOINTMENT,"CREATE",2),h("SUSPEND_APPOINTMENT",Kind.APPOINTMENT,"SUSPENDED",2),h("RESUME_APPOINTMENT",Kind.APPOINTMENT,"ACTIVE",2),h("END_APPOINTMENT",Kind.APPOINTMENT,"ENDED",2),
        h("CREATE_AUTHORITY_GRANT",Kind.AUTHORITY_GRANT,"CREATE",3),h("REVOKE_AUTHORITY_GRANT",Kind.AUTHORITY_GRANT,"REVOKED",3));
    public static List<Handler> handlers(){return HANDLERS;}
    public static Handler handler(String command){return HANDLERS.stream().filter(h->h.command().equals(command)).findFirst().orElseThrow(()->new Failure("VALIDATION_FAILED"));}
    public static boolean registered(String command){return HANDLERS.stream().anyMatch(h->h.command().equals(command));}
    public static Map<String,Object> validate(Handler h,Object payload) {
        if(!(payload instanceof Map<?,?> raw))throw new Failure("VALIDATION_FAILED");
        Set<String> fields=h.create()?switch(h.kind()){
            case PRINCIPAL->Set.of("providerUserSelector","displayName");case ORGANIZATION->Set.of("parentOrganizationId","code","displayName");
            case APPOINTMENT->Set.of("principalId","organizationId","roleCode","effectiveFrom","effectiveUntil");case AUTHORITY_GRANT->Set.of("appointmentId","authorityCode","scopeOrganizationId","validFrom","validUntil");
        }:h.action().equals("RENAME")?Set.of("displayName"):Set.of("reasonCode");
        if(!raw.keySet().equals(fields))throw new Failure("VALIDATION_FAILED");var values=new LinkedHashMap<String,Object>();
        try {
            for(String field:fields){Object value=raw.get(field);
                if(value==null){if(!field.endsWith("Until"))throw new IllegalArgumentException();values.put(field,null);continue;}
                if(!(value instanceof String text))throw new IllegalArgumentException();
                if(field.endsWith("Id")){if(!UUID.fromString(text).toString().equals(text.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();text=UUID.fromString(text).toString();}
                else if(field.endsWith("From")||field.endsWith("Until")){java.time.Instant.parse(text);}
                else if(field.equals("displayName")){if(text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();text=text.strip();if(text.isEmpty()||text.codePointCount(0,text.length())>200)throw new IllegalArgumentException();}
                else if(field.equals("providerUserSelector")){if(!text.matches("[A-Za-z0-9_-]{1,2048}"))throw new IllegalArgumentException();}
                else if(field.equals("code")){if(!text.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException();}
                else if(field.equals("roleCode")){if(!ROLES.contains(text))throw new IllegalArgumentException();}
                else if(field.equals("authorityCode")){if(!GRANTABLE.contains(text))throw new IllegalArgumentException();}
                else if(field.equals("reasonCode")&&!Set.of("ADMINISTRATIVE_ACTION","SECURITY_RESPONSE").contains(text))throw new IllegalArgumentException();
                values.put(field,text);
            }
            for(String prefix:List.of("effective","valid"))if(values.containsKey(prefix+"From")&&values.get(prefix+"Until")!=null&&!java.time.Instant.parse((String)values.get(prefix+"Until")).isAfter(java.time.Instant.parse((String)values.get(prefix+"From"))))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new Failure("VALIDATION_FAILED");}
        return Collections.unmodifiableMap(values);
    }
    public static final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code){super(code,null,false,false);this.code=code;}
        public String code(){return code;}
    }
    public record ProviderBinding(String provider,byte[] subjectHmac) {
        public ProviderBinding{subjectHmac=subjectHmac.clone();}
        public byte[] subjectHmac(){return subjectHmac.clone();}
        public String toString(){return "IdentityProviderBinding[restricted]";}
    }
    public record Context(Resource target,Resource anchor,Access access,Map<String,Object> scopeTarget) {
        public Context{scopeTarget=Collections.unmodifiableMap(new LinkedHashMap<>(scopeTarget));}
    }
    public record Mutation(Subject fact,boolean changed) {}
    public interface Port extends IdentityAdminReader {
        void lockRows(Connection c,UUID tenant)throws SQLException;
        Context resolve(Connection c,Actor actor,Handler handler,UUID id,Map<String,Object> body,ProviderBinding provider)throws SQLException;
        Mutation mutate(Connection c,Actor actor,Handler handler,Context context,Map<String,Object> body,ProviderBinding provider,boolean openResponsibilities)throws SQLException;
        boolean recoveryMatches(Connection c,UUID tenant,Handler handler,Map<String,Object> attempted,Resource target)throws SQLException;
    }
    public static Port databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqIdentityRepository();}
}
