package io.github.windyzhu3.ontologylaw.audit.internal.persistence;

import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;

public final class JooqCommandReceiptAuthorizationReader implements CommandReceiptAuthorizationReader {
    private static <T> Field<T> field(String name,Class<T> type){return DSL.field(DSL.name(name),type);}
    public Original read(Connection c,Actor actor,UUID command) throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Recovery requires transaction","25001");
        var type=field("command_type",String.class);var action=field("action_code",String.class);var outcome=field("result_code",String.class);
        var schema=field("summary_schema_code",String.class);var version=field("summary_schema_version",Integer.class);
        var summary=field("change_summary",JSONB.class);var digest=field("change_summary_digest",byte[].class);
        var subjectType=field("subject_type",String.class);var subjectId=field("subject_id",UUID.class);var revision=field("subject_revision",Long.class);var hash=field("subject_hash",byte[].class);
        var org=field("authorization_scope_organization_unit_id",UUID.class);
        var rows=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .select(type,action,outcome,schema,version,summary,digest,subjectType,subjectId,revision,hash,org,field("authorization_path_code",String.class),field("authorization_slot_code",String.class),field("authorization_fact_type",String.class),field("authorization_fact_id",UUID.class),field("authorization_fact_revision",Long.class),field("authorization_snapshot_digest",byte[].class),field("service_role_code",String.class),field("trace_id",UUID.class),field("correlation_id",UUID.class),field("causation_id",UUID.class))
                .from(DSL.table(DSL.name("audit","audit_entry_classified_v")))
                .where(field("tenant_id",UUID.class).eq(actor.tenantId())).and(field("command_id",UUID.class).eq(command))
                .and(field("actor_principal_id",UUID.class).eq(actor.principalId())).and(field("actor_appointment_id",UUID.class).eq(actor.appointmentId()))
                .and(field("on_behalf_of_principal_id",UUID.class).isNotDistinctFrom(actor.onBehalfPrincipalId()))
                .and(field("on_behalf_of_appointment_id",UUID.class).isNotDistinctFrom(actor.onBehalfAppointmentId()))
                .and(field("entry_type",String.class).eq("EVENT")).and(type.isNotNull()).limit(2).fetch();
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw new InvalidMetadata();var row=rows.getFirst();
        if(Set.of("REOPEN_DUE_CONTACT_TASKS","REOPEN_DUE_ROUTING_REVIEW_TASKS","BOOTSTRAP_IDENTITY_ADMIN").contains(row.get(type)))return null;
        try {
            if(io.github.windyzhu3.ontologylaw.identity.IdentityCommands.registered(row.get(type))) {
                if(actor.onBehalfAppointmentId()!=null||!row.get(type).equals(row.get(action))||!"R1_IDENTITY_COMMAND_AUDIT_V1".equals(row.get(schema))||!Integer.valueOf(1).equals(row.get(version)))throw new InvalidMetadata();
                var values=ReceiptAuditJson.object(ReceiptAuditJson.parse(row.get(summary).data()));ReceiptAuditJson.fields(values,"result","authorizationEvidence","receiptRecovery");
                var result=ReceiptAuditJson.object(values.get("result"));ReceiptAuditJson.fields(result,"outcome","resultFact","rejectionCode");
                if(!row.get(outcome).equals(result.get("outcome"))||!(values.get("authorizationEvidence") instanceof String evidence)||!MessageDigest.isEqual(row.get(digest),ReceiptAuditJson.digest(ReceiptAuditJson.encode(values)))
                        ||!MessageDigest.isEqual(row.get(field("authorization_snapshot_digest",byte[].class)),ReceiptAuditJson.digest(evidence))||!"DIRECT".equals(row.get("authorization_path_code"))||!"IDENTITY_ADMIN".equals(row.get("authorization_slot_code"))
                        ||!"identity.authority_grant".equals(row.get("authorization_fact_type"))||row.get("authorization_fact_id")==null||row.get("authorization_fact_revision")==null||!"API".equals(row.get("service_role_code"))
                        ||!Objects.equals(row.get("trace_id"),row.get("correlation_id"))||row.get("causation_id")!=null)throw new InvalidMetadata();
                var recovery=new ReceiptRecoveryMetadata(row.get(type),values.get("receiptRecovery"));
                if(!recovery.tenantId().equals(actor.tenantId())||!actor.principalId().toString().equals(recovery.identityScope().get("principalId"))||!actor.appointmentId().toString().equals(recovery.identityScope().get("appointmentId")))throw new InvalidMetadata();
                var subject=new Subject(row.get(subjectType),row.get(subjectId),row.get(revision),null);
                if(row.get(hash)!=null||!subject.equals(recovery.identityAnchor())||!subject.id().equals(row.get(org)))throw new InvalidMetadata();
                Subject fact=result.get("resultFact")==null?null:ReceiptRecoveryMetadata.selector(result.get("resultFact"),io.github.windyzhu3.ontologylaw.identity.IdentityCommands.handler(row.get(type)).kind().factType,false);
                String rejection=(String)result.get("rejectionCode");boolean rejected="REJECTED".equals(row.get(outcome));
                if(rejected?(fact!=null||rejection==null):(fact==null||rejection!=null||!fact.equals(recovery.identityTarget())))throw new InvalidMetadata();
                return new Original(command,row.get(type),row.get(outcome),subject,row.get(org),recovery,fact,rejection);
            }
            if(!row.get(type).equals(row.get(action))||!"R1_COMMAND_AUDIT_V2".equals(row.get(schema))||!Integer.valueOf(2).equals(row.get(version)))throw new InvalidMetadata();
            var values=ReceiptAuditJson.object(ReceiptAuditJson.parse(row.get(summary).data()));ReceiptAuditJson.fields(values,"result","authorizationEvidence","receiptRecovery");
            if(!row.get(outcome).equals(values.get("result"))||!(values.get("authorizationEvidence") instanceof String)
                    ||!MessageDigest.isEqual(row.get(digest),ReceiptAuditJson.digest(ReceiptAuditJson.encode(values))))throw new InvalidMetadata();
            var recovery=new ReceiptRecoveryMetadata(row.get(type),values.get("receiptRecovery"));
            if(!recovery.tenantId().equals(actor.tenantId()))throw new InvalidMetadata();
            var subject=new Subject(row.get(subjectType),row.get(subjectId),row.get(revision),row.get(hash)==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(row.get(hash)));
            return new Original(command,row.get(type),row.get(outcome),subject,row.get(org),recovery);
        } catch(IllegalArgumentException|NullPointerException invalid){throw new InvalidMetadata();}
    }
}
