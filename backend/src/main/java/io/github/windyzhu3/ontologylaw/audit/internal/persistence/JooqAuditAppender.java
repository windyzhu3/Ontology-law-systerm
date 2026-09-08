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
        if(e.schemaVersion()==2) {
            var summary=io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.object(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(e.summary()));
            io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.fields(summary,"result","authorizationEvidence","receiptRecovery");
            if(!e.result().equals(summary.get("result"))||!(summary.get("authorizationEvidence") instanceof String)
                    ||!java.security.MessageDigest.isEqual(e.summaryDigest(),io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(summary))))throw io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.invalid();
            var recovery=new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata(e.commandType(),summary.get("receiptRecovery"));
            if(!recovery.tenantId().equals(e.authorization().request().actor().tenantId()))throw io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.invalid();
        }
        write(connection,e.id(),e.commandId(),e.commandType(),e.correlationId(),e.commandType(),e.result(),e.authorization(),e.authorization().request().subject(),"R1_COMMAND_AUDIT_V"+e.schemaVersion(),e.schemaVersion(),e.summary(),e.summaryDigest());
    }
    public void append(Connection connection,ReadDisclosureEntry e)throws SQLException {
        write(connection,e.id(),null,null,e.correlationId(),"READ_CURRENT_WORKCARD","SUCCEEDED",e.authorization(),e.disclosedSource(),"R1_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",1,e.summary(),e.summaryDigest());
    }
    public void append(Connection connection,ReceiptDisclosureEntry e)throws SQLException {
        write(connection,e.id(),null,null,e.correlationId(),"READ_COMMAND_RECEIPT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R1_COMMAND_RECEIPT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.summaryDigest());
    }
    private void write(Connection connection,java.util.UUID id,java.util.UUID commandId,String commandType,java.util.UUID correlation,
            String action,String result,io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot snapshot,
            io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject subject,String schema,int schemaVersion,String summary,byte[] summaryDigest) {
        var a=AUDIT_ENTRY;var request=snapshot.request();var actor=request.actor();var fact=snapshot.authorityFact();
        DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .insertInto(a).set(a.TENANT_ID,actor.tenantId()).set(a.AUDIT_ENTRY_ID,id).set(a.ENTRY_TYPE,"EVENT")
                .set(a.AUDIT_SCOPE_CODE,"OBJECT").set(a.TRUSTED_AT,OffsetDateTime.ofInstant(snapshot.checkedAt(),ZoneOffset.UTC))
                .set(a.ACTION_CODE,action).set(a.RESULT_CODE,result).set(a.ACTOR_PRINCIPAL_ID,actor.principalId())
                .set(a.ACTOR_APPOINTMENT_ID,actor.appointmentId()).set(a.ON_BEHALF_OF_PRINCIPAL_ID,actor.onBehalfPrincipalId()).set(a.ON_BEHALF_OF_APPOINTMENT_ID,actor.onBehalfAppointmentId())
                .set(a.COMMAND_ID,commandId).set(a.COMMAND_TYPE,commandType).set(a.CORRELATION_ID,correlation)
                .set(a.AUTHORIZATION_SLOT_CODE,request.requirement().slot()).set(a.AUTHORIZATION_PATH_CODE,request.requirement().path().name())
                .set(a.AUTHORIZATION_SCOPE_ORGANIZATION_UNIT_ID,request.scopeOrganizationId()).set(a.AUTHORIZATION_SNAPSHOT_DIGEST,snapshot.digest())
                .set(a.TRACE_ID,correlation).set(a.SERVICE_ROLE_CODE,"API").set(a.EXECUTION_NODE_CODE,executionNodeCode)
                .set(a.SUMMARY_SCHEMA_CODE,schema).set(a.SUMMARY_SCHEMA_VERSION,schemaVersion).set(a.CHANGE_SUMMARY,JSONB.valueOf(summary)).set(a.CHANGE_SUMMARY_DIGEST,summaryDigest)
                .set(a.SUBJECT_TYPE,subject.type()).set(a.SUBJECT_ID,subject.id()).set(a.SUBJECT_REVISION,subject.revision()).set(a.SUBJECT_HASH,subject.hash()==null?null:Base64.getUrlDecoder().decode(subject.hash()))
                .set(a.AUTHORIZATION_FACT_TYPE,fact==null?null:fact.type()).set(a.AUTHORIZATION_FACT_ID,fact==null?null:fact.id()).set(a.AUTHORIZATION_FACT_REVISION,fact==null?null:fact.revision()).execute();
        // No INSERT RETURNING: the append-only role deliberately has no SELECT privilege.
    }
}
