package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.jooq.Tables.*;

public final class JooqCommandReceiptReader implements CommandReceiptReader {
    public Receipt read(Connection c,UUID tenant,UUID command) throws SQLException {
        var s=COMMAND_EXECUTION_SLOT;var r=COMMAND_RECEIPT;
        var rows=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .select(s.COMMAND_TYPE,s.ENVELOPE_TYPE,s.COMMAND_SCOPE_DIGEST).select(r.fields()).from(s).join(r)
                .on(r.TENANT_ID.eq(s.TENANT_ID).and(r.COMMAND_EXECUTION_SLOT_ID.eq(s.COMMAND_EXECUTION_SLOT_ID)))
                .where(s.TENANT_ID.eq(tenant)).and(s.COMMAND_ID.eq(command)).limit(2).fetch();
        if(rows.isEmpty())return null;if(rows.size()!=1)throw new SQLException("Receipt identity unavailable","23000");var row=rows.getFirst();
        var hashValues=new TreeMap<String,Object>();
        for(var field:r.fields()) {Object value=row.get(field);if(value instanceof UUID id)value=id.toString();else if(value instanceof byte[] bytes)value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            else if(value instanceof OffsetDateTime time)value=time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'",Locale.ROOT));hashValues.put(field.getName(),value);}
        var selector=new Subject("execution.command_receipt",row.get(r.COMMAND_RECEIPT_ID),null,Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(hashValues))));
        var fact=row.get(r.RESULT_FACT_TYPE)==null?null:new Subject(row.get(r.RESULT_FACT_TYPE),row.get(r.RESULT_FACT_ID),row.get(r.RESULT_FACT_REVISION),row.get(r.RESULT_FACT_HASH)==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(row.get(r.RESULT_FACT_HASH)));
        var outcome=new CommandOutcome(row.get(r.COMMAND_RECEIPT_ID),CommandOutcome.Status.valueOf(row.get(r.OUTCOME)),fact,row.get(r.REJECTION_CODE));
        return new Receipt(command,row.get(s.COMMAND_TYPE),row.get(s.ENVELOPE_TYPE),row.get(s.COMMAND_SCOPE_DIGEST),outcome,row.get(r.COMPLETED_AT).toInstant(),selector);
    }
}
