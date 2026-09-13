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
    /** Current fact authorization with its mandatory immutable related scope, retaining the chosen anchor. */
    default Access authorizeResource(Connection c,Actor actor,String authority,Resource anchor,Resource target)throws SQLException {
        return authorize(c,actor,authority,anchor,target);
    }
    default Access resourceAccess(Connection c,Actor actor,String authority,Resource target)throws SQLException {
        Kind kind=Kind.of(target.fact().type());
        if(kind==Kind.ORGANIZATION&&target.values().get("parentOrganizationId")!=null) {
            var parent=find(c,actor.tenantId(),Kind.ORGANIZATION,UUID.fromString((String)target.values().get("parentOrganizationId")));
            try{return authorizeResource(c,actor,authority,parent,target);}catch(IdentityCommands.Failure denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}
        }
        var anchor=kind==Kind.PRINCIPAL?root(c,actor.tenantId()):find(c,actor.tenantId(),Kind.ORGANIZATION,target.organization());
        return authorizeResource(c,actor,authority,anchor,target);
    }
    /** Add required evidence without replacing the original anchor or making a wider authorization choice. */
    static Access combine(Access primary,Access related) {
        var first=primary.authorization();
        String dependencies=HexFormat.of().formatHex(primary.digest())+":"+HexFormat.of().formatHex(related.digest());
        String evidence=first.evidence()+"\n"+related.authorization().evidence();
        try {
            var sha=java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest=sha.digest(dependencies.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var snapshot=new AuthorizationSnapshot(first.request(),first.checkedAt(),true,null,first.authorityFact(),evidence,sha.digest(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)),dependencies);
            return new Access(snapshot,digest,primary.scopes());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    Access listAccess(Connection c,Actor actor,String authority,boolean rootRequired)throws SQLException;
    Page list(Connection c,Actor actor,Kind kind,Access access,boolean candidates,Position after,int limit)throws SQLException;
    static IdentityAdminReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqIdentityRepository();}
}
