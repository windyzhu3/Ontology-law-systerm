package io.github.windyzhu3.ontologylaw.audit.internal.persistence;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.Base64;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.audit.internal.persistence.jooq.Tables.AUDIT_ENTRY;

public final class JooqAuditAppender implements AuditAppender {
    public void appendWorkcards(Connection c,java.util.List<ReadDisclosureEntry> entries)throws SQLException {
        batch(c,entries,e->insert(c,e.id(),null,null,e.correlationId(),"READ_CURRENT_WORKCARD","SUCCEEDED",e.authorization(),e.disclosedSource(),e.schemaCode(),1,e.summary(),e.summaryDigest()));
    }
    public void appendManagement(Connection c,java.util.List<ManagementDisclosureEntry> entries)throws SQLException {
        batch(c,entries,e->insert(c,e.id(),null,null,e.correlationId(),"READ_BUSINESS_MANAGEMENT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_MANAGEMENT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest()));
    }
    public void appendContracts(Connection c,java.util.List<ContractDisclosureEntry> entries)throws SQLException {
        batch(c,entries,e->insert(c,e.id(),null,null,e.correlationId(),"READ_CONTRACT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_CONTRACT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest()));
    }
    public void appendOpportunityLedger(Connection c,java.util.List<OpportunityLedgerDisclosureEntry> entries,java.util.List<QuoteDisclosureEntry> quotes)throws SQLException {
        batch(c,entries,e->insert(c,e.id(),null,null,e.correlationId(),"READ_OPPORTUNITY_LEDGER","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_OPPORTUNITY_LEDGER_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest()));
        batch(c,quotes,e->insert(c,e.id(),null,null,e.correlationId(),"READ_QUOTE","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_QUOTE_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest()));
    }
    private static <T>void batch(Connection c,java.util.List<T> entries,java.util.function.Function<T,Query> statement)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Audit requires transaction","25001");
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));
        for(int offset=0;offset<entries.size();offset+=128) {
            int end=Math.min(entries.size(),offset+128);var first=statement.apply(entries.get(offset));var batch=db.batch(first);
            for(int i=offset;i<end;i++)batch.bind((i==offset?first:statement.apply(entries.get(i))).getBindValues().toArray());
            int[] counts=batch.execute();
            if(counts.length!=end-offset)throw new SQLException("Incomplete disclosure audit batch","58000");
            for(int count:counts)if(count!=1&&count!=java.sql.Statement.SUCCESS_NO_INFO)throw new SQLException("Disclosure audit append failed","58000");
        }
        // Caller retains the AUDIT transaction and awaits commit; no asynchronous queue,
        // deduplication, INSERT RETURNING, or permission to return partially audited data.
    }
    public void append(Connection c,ContractDisclosureEntry e)throws SQLException{write(c,e.id(),null,null,e.correlationId(),"READ_CONTRACT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_CONTRACT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,FollowupAttemptDisclosureEntry e)throws SQLException{write(c,e.id(),null,null,e.correlationId(),"READ_FOLLOWUP_ATTEMPT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_FOLLOWUP_ATTEMPT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,QuoteDisclosureEntry e)throws SQLException{write(c,e.id(),null,null,e.correlationId(),"READ_QUOTE","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_QUOTE_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,MaterialDisclosureEntry e)throws SQLException{write(c,e.id(),null,null,e.correlationId(),"READ_OPPORTUNITY_MATERIAL","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_MATERIAL_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,CustomerRequirementsDisclosureEntry e)throws SQLException{write(c,e.id(),null,null,e.correlationId(),"READ_CUSTOMER_REQUIREMENTS","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_CUSTOMER_REQUIREMENTS_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}

    public void append(Connection c,OpportunityClosureDisclosureEntry e)throws SQLException {write(c,e.id(),null,null,e.correlationId(),"READ_OPPORTUNITY_CLOSURE","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_OPPORTUNITY_CLOSURE_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,OpportunityLedgerDisclosureEntry e)throws SQLException {write(c,e.id(),null,null,e.correlationId(),"READ_OPPORTUNITY_LEDGER","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_OPPORTUNITY_LEDGER_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}

    public void append(Connection c,OwnerExceptionDisclosureEntry e)throws SQLException {write(c,e.id(),null,null,e.correlationId(),"READ_OPPORTUNITY_OWNER_EXCEPTION","SUCCEEDED",e.authorization(),e.disclosedSource(),"R2_OWNER_EXCEPTION_DISCLOSURE_AUDIT_V1",1,e.summary(),e.digest());}
    public void append(Connection c,OwnerValidationEntry e)throws SQLException {write(c,e.id(),null,null,e.correlationId(),"OBSERVE_OPPORTUNITY_OWNER_VALIDATION","SUCCEEDED",e.authorization(),e.opportunity(),"R2_OPPORTUNITY_OWNER_VALIDATION_V1",1,e.summary(),e.digest());}
    public void append(Connection c,IdentityEntry e)throws SQLException {
        var values=io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.object(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(e.summary()));
        io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.fields(values,"result","authorizationEvidence","receiptRecovery");
        var result=io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.object(values.get("result"));
        io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.fields(result,"outcome","resultFact","rejectionCode");
        var metadata=new io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata(e.commandType(),values.get("receiptRecovery"));
        if(!e.outcome().equals(result.get("outcome"))||!metadata.tenantId().equals(e.authorization().request().actor().tenantId()))throw io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.invalid();
        write(c,e.id(),e.commandId(),e.commandType(),e.correlationId(),e.commandType(),e.outcome(),e.authorization(),e.authorization().request().subject(),"R1_IDENTITY_COMMAND_AUDIT_V1",1,e.summary(),e.digest());
    }
    public void append(Connection c,IdentityDisclosureEntry e)throws SQLException {
        String action=e.operationId().replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(java.util.Locale.ROOT);
        write(c,e.id(),null,null,e.correlationId(),action,"SUCCEEDED",e.authorization(),e.authorization().request().subject(),"R1_IDENTITY_DISCLOSURE_V1",1,e.summary(),e.digest());
    }
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
    public void append(Connection c,SelfDisclosureEntry e)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Audit requires transaction","25001");
        var a=AUDIT_ENTRY;
        DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).insertInto(a)
                .set(a.TENANT_ID,e.tenantId()).set(a.AUDIT_ENTRY_ID,e.id()).set(a.ENTRY_TYPE,"EVENT").set(a.AUDIT_SCOPE_CODE,"OBJECT")
                .set(a.TRUSTED_AT,OffsetDateTime.ofInstant(e.checkedAt(),ZoneOffset.UTC)).set(a.ACTION_CODE,"GET_SESSION_CONTEXT").set(a.RESULT_CODE,"SUCCEEDED")
                .set(a.ACTOR_PRINCIPAL_ID,e.principal().id()).set(a.ACTOR_APPOINTMENT_ID,e.ownAppointment())
                .set(a.AUTHORIZATION_SLOT_CODE,"SELF_IDENTITY").set(a.AUTHORIZATION_PATH_CODE,"DIRECT").set(a.AUTHORIZATION_SNAPSHOT_DIGEST,e.digest())
                .set(a.CORRELATION_ID,e.correlationId()).set(a.TRACE_ID,e.correlationId()).set(a.SERVICE_ROLE_CODE,"API").set(a.EXECUTION_NODE_CODE,executionNodeCode)
                .set(a.SUBJECT_TYPE,e.principal().type()).set(a.SUBJECT_ID,e.principal().id()).set(a.SUBJECT_REVISION,e.principal().revision())
                .set(a.SUMMARY_SCHEMA_CODE,"R1_IDENTITY_SELF_DISCLOSURE_V1").set(a.SUMMARY_SCHEMA_VERSION,1)
                .set(a.CHANGE_SUMMARY,JSONB.valueOf(e.summary())).set(a.CHANGE_SUMMARY_DIGEST,e.digest()).execute();
    }
    public void append(Connection c,BootstrapEntry e)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Audit requires transaction","25001");
        var a=AUDIT_ENTRY;var f=e.facts();
        DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).insertInto(a)
                .set(a.TENANT_ID,f.tenant()).set(a.AUDIT_ENTRY_ID,e.id()).set(a.ENTRY_TYPE,"EVENT").set(a.AUDIT_SCOPE_CODE,"OBJECT")
                .set(a.TRUSTED_AT,OffsetDateTime.ofInstant(f.createdAt(),ZoneOffset.UTC)).set(a.ACTION_CODE,"BOOTSTRAP_IDENTITY_ADMIN").set(a.RESULT_CODE,"SUCCEEDED")
                .set(a.ACTOR_PRINCIPAL_ID,f.principal()).set(a.AUTHORIZATION_SLOT_CODE,"IDENTITY_BOOTSTRAP").set(a.AUTHORIZATION_PATH_CODE,"SYSTEM")
                .set(a.AUTHORIZATION_SCOPE_ORGANIZATION_UNIT_ID,f.root()).set(a.AUTHORIZATION_SNAPSHOT_DIGEST,e.digest())
                .set(a.COMMAND_ID,e.commandId()).set(a.COMMAND_TYPE,"BOOTSTRAP_IDENTITY_ADMIN").set(a.CORRELATION_ID,e.correlationId()).set(a.TRACE_ID,e.correlationId())
                .set(a.SERVICE_ROLE_CODE,"API").set(a.EXECUTION_NODE_CODE,executionNodeCode)
                .set(a.SUBJECT_TYPE,"identity.tenant").set(a.SUBJECT_ID,f.tenant()).set(a.SUBJECT_REVISION,0L)
                .set(a.SUMMARY_SCHEMA_CODE,"R1_IDENTITY_BOOTSTRAP_V1").set(a.SUMMARY_SCHEMA_VERSION,1)
                .set(a.CHANGE_SUMMARY,JSONB.valueOf(e.summary())).set(a.CHANGE_SUMMARY_DIGEST,e.digest()).execute();
    }
    public BootstrapEntry bootstrapOriginal(Connection c,java.util.UUID tenant,java.util.UUID command)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Audit requires transaction","25001");
        var rows=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).fetch("select * from audit.audit_entry_classified_v where tenant_id=? and command_id=? and command_type is not null limit 2",tenant,command);
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalidBootstrap();var row=rows.getFirst();
        try {
            var values=io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.object(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(row.get("change_summary",JSONB.class).data()));
            io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.fields(values,"profile","version","manifestDigest","operatorAssertion","founderPrincipalId","rootOrganizationId","appointmentId","grantIds");
            if(!"R1_IDENTITY_BOOTSTRAP_V1".equals(values.get("profile"))||!Long.valueOf(1).equals(((Number)values.get("version")).longValue()))throw invalidBootstrap();
            var ids=((java.util.List<?>)values.get("grantIds")).stream().map(value->java.util.UUID.fromString((String)value)).toList();
            var facts=new io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.Facts(tenant,java.util.UUID.fromString((String)values.get("rootOrganizationId")),java.util.UUID.fromString((String)values.get("founderPrincipalId")),java.util.UUID.fromString((String)values.get("appointmentId")),ids,row.get("trusted_at",OffsetDateTime.class).toInstant());
            var entry=new BootstrapEntry(row.get("audit_entry_id",java.util.UUID.class),command,row.get("correlation_id",java.util.UUID.class),(String)values.get("manifestDigest"),(String)values.get("operatorAssertion"),facts);
            if(!java.security.MessageDigest.isEqual(entry.digest(),row.get("change_summary_digest",byte[].class))||!java.security.MessageDigest.isEqual(entry.digest(),row.get("authorization_snapshot_digest",byte[].class))
                    ||!"EVENT".equals(row.get("entry_type"))||!"OBJECT".equals(row.get("audit_scope_code"))||!"SUCCEEDED".equals(row.get("result_code"))||!"BOOTSTRAP_IDENTITY_ADMIN".equals(row.get("command_type"))||!"BOOTSTRAP_IDENTITY_ADMIN".equals(row.get("action_code"))
                    ||!"R1_IDENTITY_BOOTSTRAP_V1".equals(row.get("summary_schema_code"))||!Integer.valueOf(1).equals(row.get("summary_schema_version",Integer.class))||!"IDENTITY_BOOTSTRAP".equals(row.get("authorization_slot_code"))||!"SYSTEM".equals(row.get("authorization_path_code"))
                    ||!facts.principal().equals(row.get("actor_principal_id"))||row.get("actor_appointment_id")!=null||row.get("on_behalf_of_principal_id")!=null||row.get("on_behalf_of_appointment_id")!=null||row.get("authorization_fact_type")!=null||row.get("authorization_fact_id")!=null||row.get("authorization_fact_revision")!=null
                    ||!facts.root().equals(row.get("authorization_scope_organization_unit_id"))||!"identity.tenant".equals(row.get("subject_type"))||!tenant.equals(row.get("subject_id"))||!Long.valueOf(0).equals(row.get("subject_revision",Long.class))||row.get("subject_hash")!=null
                    ||!"API".equals(row.get("service_role_code"))||!entry.correlationId().equals(row.get("trace_id"))||row.get("causation_id")!=null
                    ||row.get("correction_target_type")!=null||row.get("correction_target_id")!=null||row.get("correction_target_revision")!=null||row.get("correction_target_hash")!=null||row.get("authorization_fact_hash")!=null)throw invalidBootstrap();
            return entry;
        }catch(RuntimeException corrupt){throw invalidBootstrap();}
    }
    private static SQLException invalidBootstrap(){return new SQLException("BOOTSTRAP_AUDIT_UNAVAILABLE","23000");}
    public void append(Connection connection,ReadDisclosureEntry e)throws SQLException {
        write(connection,e.id(),null,null,e.correlationId(),"READ_CURRENT_WORKCARD","SUCCEEDED",e.authorization(),e.disclosedSource(),e.schemaCode(),1,e.summary(),e.summaryDigest());
    }
    public void append(Connection connection,ReceiptDisclosureEntry e)throws SQLException {
        write(connection,e.id(),null,null,e.correlationId(),"READ_COMMAND_RECEIPT","SUCCEEDED",e.authorization(),e.disclosedSource(),"R1_COMMAND_RECEIPT_DISCLOSURE_AUDIT_V1",1,e.summary(),e.summaryDigest());
    }
    private void write(Connection connection,java.util.UUID id,java.util.UUID commandId,String commandType,java.util.UUID correlation,
            String action,String result,io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot snapshot,
            io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject subject,String schema,int schemaVersion,String summary,byte[] summaryDigest) {
        insert(connection,id,commandId,commandType,correlation,action,result,snapshot,subject,schema,schemaVersion,summary,summaryDigest).execute();
    }
    private Query insert(Connection connection,java.util.UUID id,java.util.UUID commandId,String commandType,java.util.UUID correlation,
            String action,String result,io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot snapshot,
            io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject subject,String schema,int schemaVersion,String summary,byte[] summaryDigest) {
        var a=AUDIT_ENTRY;var request=snapshot.request();var actor=request.actor();var fact=snapshot.authorityFact();
        return DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
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
                .set(a.AUTHORIZATION_FACT_TYPE,fact==null?null:fact.type()).set(a.AUTHORIZATION_FACT_ID,fact==null?null:fact.id()).set(a.AUTHORIZATION_FACT_REVISION,fact==null?null:fact.revision());
        // No INSERT RETURNING: the append-only role deliberately has no SELECT privilege.
    }
}
