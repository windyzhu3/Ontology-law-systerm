package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.*;
public final class JooqOpportunityCommandReader implements OpportunityCommandReader {
    private final OpportunityProgressProtection protection;
    public JooqOpportunityCommandReader(OpportunityProgressProtection protection){this.protection=Objects.requireNonNull(protection);}
    private static org.jooq.DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private record HeaderSource(UUID id){}
    public Header header(Connection c,UUID tenant,UUID id)throws SQLException{return OpportunityReadRows.fact(c,tenant,new HeaderSource(id),()->headerUncached(c,tenant,id));}
    private Header headerUncached(Connection c,UUID tenant,UUID id){var o=OPPORTUNITY_;var r=db(c).select(o.REVISION,o.OWNER_APPOINTMENT_ID,o.CLOSED_AT).from(o).where(o.TENANT_ID.eq(tenant)).and(o.OPPORTUNITY_ID.eq(id)).fetchOne();return r==null?null:new Header(new Subject("opportunity.opportunity",id,r.get(o.REVISION),null),r.get(o.OWNER_APPOINTMENT_ID),r.get(o.CLOSED_AT)!=null);}
    public void lock(Connection c,UUID tenant,UUID id){var o=OPPORTUNITY_;db(c).select(o.OPPORTUNITY_ID).from(o).where(o.TENANT_ID.eq(tenant)).and(o.OPPORTUNITY_ID.eq(id)).forUpdate().fetch();}
    public Progress progress(Connection c,UUID tenant,UUID id)throws SQLException{
        var p=OPPORTUNITY_PROGRESS;var r=db(c).selectFrom(p).where(p.TENANT_ID.eq(tenant)).and(p.OPPORTUNITY_PROGRESS_ID.eq(id)).fetchOne();
        if(r==null||!OpportunityProgressInput.CONTRACT.equals(r.get(p.PROGRESS_CONTRACT_CODE))||r.get(p.PROGRESS_CONTRACT_VERSION)!=1)return null;
        String body;
        try{body=protection.decrypt(tenant,r.get(p.OPPORTUNITY_ID),id,r.get(p.PROGRESS_BODY_CIPHERTEXT));
            if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)),r.get(p.PROGRESS_DIGEST)))throw new IllegalArgumentException();
        }catch(IllegalArgumentException|NoSuchAlgorithmException invalid){throw new SQLException("Protected progress verification failed","22000");}
        return new Progress(new Subject("opportunity.opportunity_progress",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.get(p.PROGRESS_DIGEST))),r.get(p.OPPORTUNITY_ID),body);
    }
}
