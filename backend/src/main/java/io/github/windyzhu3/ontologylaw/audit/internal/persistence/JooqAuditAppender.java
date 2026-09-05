package io.github.windyzhu3.ontologylaw.audit.internal.persistence;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.Base64;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.audit.internal.persistence.jooq.Tables.AUDIT_ENTRY;

public final class JooqAuditAppender implements AuditAppender {
    private final String executionNodeCode;
    public JooqAuditAppender(String executionNodeCode) {
        if(executionNodeCode==null || !executionNodeCode.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("Trusted deployment node code required");
        this.executionNodeCode=executionNodeCode;
    }
    public void append(Connection connection,Entry e)throws SQLException {
        var a=AUDIT_ENTRY;var snapshot=e.authorization();var request=snapshot.request();var actor=request.actor();var subject=request.subject();var fact=snapshot.authorityFact();
        DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .insertInto(a).set(a.TENANT_ID,actor.tenantId()).set(a.AUDIT_ENTRY_ID,e.id()).set(a.ENTRY_TYPE,"EVENT")
                .set(a.AUDIT_SCOPE_CODE,"OBJECT").set(a.TRUSTED_AT,OffsetDateTime.ofInstant(snapshot.checkedAt(),ZoneOffset.UTC))
                .set(a.ACTION_CODE,e.commandType()).set(a.RESULT_CODE,e.result()).set(a.ACTOR_PRINCIPAL_ID,actor.principalId())
                .set(a.ACTOR_APPOINTMENT_ID,actor.appointmentId()).set(a.ON_BEHALF_OF_PRINCIPAL_ID,actor.onBehalfPrincipalId()).set(a.ON_BEHALF_OF_APPOINTMENT_ID,actor.onBehalfAppointmentId())
                .set(a.COMMAND_ID,e.commandId()).set(a.COMMAND_TYPE,e.commandType()).set(a.CORRELATION_ID,e.correlationId())
                .set(a.AUTHORIZATION_SLOT_CODE,request.requirement().slot()).set(a.AUTHORIZATION_PATH_CODE,request.requirement().path().name())
                .set(a.AUTHORIZATION_SCOPE_ORGANIZATION_UNIT_ID,request.scopeOrganizationId()).set(a.AUTHORIZATION_SNAPSHOT_DIGEST,snapshot.digest())
                .set(a.TRACE_ID,e.correlationId()).set(a.SERVICE_ROLE_CODE,"API").set(a.EXECUTION_NODE_CODE,executionNodeCode)
                .set(a.SUMMARY_SCHEMA_CODE,"R1_COMMAND_AUDIT_V1").set(a.SUMMARY_SCHEMA_VERSION,1).set(a.CHANGE_SUMMARY,JSONB.valueOf(e.summary())).set(a.CHANGE_SUMMARY_DIGEST,e.summaryDigest())
                .set(a.SUBJECT_TYPE,subject.type()).set(a.SUBJECT_ID,subject.id()).set(a.SUBJECT_REVISION,subject.revision()).set(a.SUBJECT_HASH,subject.hash()==null?null:Base64.getUrlDecoder().decode(subject.hash()))
                .set(a.AUTHORIZATION_FACT_TYPE,fact==null?null:fact.type()).set(a.AUTHORIZATION_FACT_ID,fact==null?null:fact.id()).set(a.AUTHORIZATION_FACT_REVISION,fact==null?null:fact.revision()).execute();
        // No INSERT RETURNING: the append-only role deliberately has no SELECT privilege.
    }
}
