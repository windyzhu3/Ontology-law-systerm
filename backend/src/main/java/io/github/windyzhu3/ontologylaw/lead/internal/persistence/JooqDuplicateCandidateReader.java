package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.Duplicate;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.LEAD_;

/** Shared command/query candidate algorithm. Protected matching material never escapes this Owner. */
final class JooqDuplicateCandidateReader {
    private static boolean matches(byte[] a,byte[] b,byte[] x,byte[] y) {
        return a!=null&&(Arrays.equals(a,x)||Arrays.equals(a,y))||b!=null&&(Arrays.equals(b,x)||Arrays.equals(b,y));
    }
    static Duplicate select(Connection c,UUID tenant,UUID id,String disposition,Instant cutoff,R1PartyReader parties)throws SQLException {
        if(!"CAPTURED".equals(disposition))return null;
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));var l=LEAD_;
        var current=db.select(l.CAPTURED_PHONE_HMAC,l.INGRESS_COMPLETION_PHONE_HMAC,l.CAPTURED_EMAIL_HMAC,l.INGRESS_COMPLETION_EMAIL_HMAC)
            .from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne();
        if(current==null)return null;
        Duplicate best=null;int bestRank=3;Instant bestTime=null;
        var rows=db.select(l.LEAD_ID,l.REVISION,l.PARSED_PARTY_ID,l.CAPTURED_AT,l.CAPTURED_PHONE_HMAC,l.INGRESS_COMPLETION_PHONE_HMAC,l.CAPTURED_EMAIL_HMAC,l.INGRESS_COMPLETION_EMAIL_HMAC)
            .from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.ne(id)).and(l.CREATED_AT.le(cutoff.atOffset(ZoneOffset.UTC)))
            .and(l.PARTY_RESOLUTION_CODE.eq("RESOLVED")).fetch();
        for(var row:rows) {
            boolean phone=matches(current.value1(),current.value2(),row.get(l.CAPTURED_PHONE_HMAC),row.get(l.INGRESS_COMPLETION_PHONE_HMAC));
            boolean email=matches(current.value3(),current.value4(),row.get(l.CAPTURED_EMAIL_HMAC),row.get(l.INGRESS_COMPLETION_EMAIL_HMAC));
            if(!phone&&!email)continue;
            var party=parties.active(c,tenant,row.get(l.PARSED_PARTY_ID));if(party==null)continue;
            int rank=phone&&email?0:phone?1:2;Instant captured=row.get(l.CAPTURED_AT).toInstant();UUID candidate=row.get(l.LEAD_ID);
            if(best==null||rank<bestRank||rank==bestRank&&(captured.isBefore(bestTime)||captured.equals(bestTime)&&compare(candidate,best.lead().id())<0)) {
                best=new Duplicate(new Subject("lead.lead",candidate,row.get(l.REVISION),null),new Subject("party.party",party.id(),party.revision(),null));
                bestRank=rank;bestTime=captured;
            }
        }
        return best;
    }
    private static int compare(UUID a,UUID b) {
        int high=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());
        return high==0?Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits()):high;
    }
}
