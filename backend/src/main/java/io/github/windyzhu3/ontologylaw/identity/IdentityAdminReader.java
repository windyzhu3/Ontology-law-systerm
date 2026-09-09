package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Identity-owned administration facts. Caller holds the named business/identity fences. */
public interface IdentityAdminReader {
    enum Kind {
        PRINCIPAL("identity.principal"), ORGANIZATION("identity.organization_unit"),
        APPOINTMENT("identity.appointment"), AUTHORITY_GRANT("identity.authority_grant");
        public final String factType;
        Kind(String factType){this.factType=factType;}
        public static Kind of(String type){return Arrays.stream(values()).filter(k->k.factType.equals(type)).findFirst().orElseThrow();}
    }
    record Resource(Subject fact,UUID organization,Instant createdAt,Map<String,Object> values) {
        public Resource {values=Collections.unmodifiableMap(new LinkedHashMap<>(values));}
    }
    record Position(Instant createdAt,UUID id) {}
    record Page(List<Resource> items,boolean hasMore) {public Page{items=List.copyOf(items);}}
    /** Permission set is opaque server material. It is never returned in an administration DTO. */
    record Access(AuthorizationSnapshot authorization,byte[] digest,List<UUID> scopes) {
        public Access{digest=digest.clone();scopes=List.copyOf(scopes);}
        public byte[] digest(){return digest.clone();}
    }
    Resource root(Connection c,UUID tenant)throws SQLException;
    Resource find(Connection c,UUID tenant,Kind kind,UUID id)throws SQLException;
    Access authorize(Connection c,Actor actor,String authority,Resource anchor,Resource target)throws SQLException;
    default Access resourceAccess(Connection c,Actor actor,String authority,Resource target)throws SQLException {
        Kind kind=Kind.of(target.fact().type());
        if(kind==Kind.ORGANIZATION&&target.values().get("parentOrganizationId")!=null) {
            var parent=find(c,actor.tenantId(),Kind.ORGANIZATION,UUID.fromString((String)target.values().get("parentOrganizationId")));
            try{return authorize(c,actor,authority,parent,target);}catch(IdentityCommands.Failure denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}
        }
        var anchor=kind==Kind.PRINCIPAL?root(c,actor.tenantId()):find(c,actor.tenantId(),Kind.ORGANIZATION,target.organization());
        return authorize(c,actor,authority,anchor,target);
    }
    Access listAccess(Connection c,Actor actor,String authority,boolean rootRequired)throws SQLException;
    Page list(Connection c,Actor actor,Kind kind,Access access,boolean candidates,Position after,int limit)throws SQLException;
    static IdentityAdminReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqIdentityRepository();}
}
