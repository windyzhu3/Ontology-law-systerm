package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import java.sql.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqCredentialIdentityReader implements CredentialIdentityReader {
    public boolean matches(Connection c,Actor actor,String provider,byte[] hmac)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Identity requires transaction","25001");
        if(actor==null||provider==null||hmac==null||hmac.length!=32)return false;
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));var p=PRINCIPAL;var a=APPOINTMENT;
        return db.select(p.PRINCIPAL_ID).from(p).join(a).on(a.TENANT_ID.eq(p.TENANT_ID).and(a.PRINCIPAL_ID.eq(p.PRINCIPAL_ID)))
                .where(p.TENANT_ID.eq(actor.tenantId())).and(p.PRINCIPAL_ID.eq(actor.principalId())).and(a.APPOINTMENT_ID.eq(actor.appointmentId()))
                .and(p.PRINCIPAL_KIND.eq(actor.principalKind().name())).and(p.IDENTITY_PROVIDER_CODE.eq(provider)).and(p.EXTERNAL_SUBJECT_HMAC.eq(hmac)).limit(2).fetch().size()==1;
    }
}
