package io.github.windyzhu3.ontologylaw.party.internal.persistence;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import java.sql.*;import java.util.UUID;import org.jooq.SQLDialect;import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.party.internal.persistence.jooq.Tables.*;
public final class JooqR1PartyReader implements R1PartyReader {
    public ExactParty active(Connection c,UUID tenant,UUID id){var p=PARTY_;var r=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
        .select(p.REVISION).from(p).where(p.TENANT_ID.eq(tenant)).and(p.PARTY_ID.eq(id)).and(p.STATUS.eq("ACTIVE")).and(p.MERGED_INTO_PARTY_ID.isNull()).fetchOne();
        return r==null?null:new ExactParty(id,r.value1());
    }
}
