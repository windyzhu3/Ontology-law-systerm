package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.lead.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class R1ReceiptMetadataHttpIT extends R1HttpFixture {
    @ParameterizedTest @ValueSource(strings={"DRAFT_MISSING_OR_REPLACED","DRAFT_FUTURE_REVISION"})
    void original_draft_reference_cannot_be_guessed_from_the_current_unique_task_draft(String defect)throws Exception {
        setupContact();prepare(contact("NOT_CONNECTED"));io.github.windyzhu3.ontologylaw.responsibility.ActionDraftService.Draft draft;
        try(var c=database.apiConnection()){draft=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->io.github.windyzhu3.ontologylaw.responsibility.ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id()));}
        var handlers=new ArrayList<CommandHandler>(new LeadCommands(policies,protection).handlers());handlers.addAll(new ActionDraftCommands(protection).handlers());runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),(c,e)->historicalAudit(c,e,defect),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        var payload=Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",contact("CONNECTED_VALID"));String tag=io.github.windyzhu3.ontologylaw.responsibility.R1ResourceTags.draft(seed.request().actor(),draft.selector(),draft.state());var command=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload,null,new CommandEnvelope.DraftPrecondition(current.selector().id(),tag,null));var outcome=execute(command);
        try(var http=new HttpHarness()){var before=counts();var denied=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());var replay=http.request("PUT","/api/v1/tasks/"+current.selector().id()+"/draft",payload,Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",tag));assertEquals(200,replay.statusCode(),replay.body());assertEquals(outcome.receiptId().toString(),((Map<?,?>)http.body(replay).get("receipt")).get("receiptId"));assertEquals(before,counts());}
    }
    @ParameterizedTest @ValueSource(strings={"LEGACY","UNKNOWN_SCHEMA","UNKNOWN_VERSION","MISSING_RECOVERY","UNKNOWN_FIELD","BAD_DIGEST","WRONG_ACTION","WRONG_OUTCOME","WRONG_SCOPE","DUPLICATE"})
    void historical_bad_metadata_is_unavailable_and_original_request_replay_never_repairs_it(String defect)throws Exception {
        setupContact();runtime=new CommandRuntime(new LeadCommands(policies,protection).handlers(),AuthorizationService.databaseBacked(),(c,e)->historicalAudit(c,e,defect),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        var command=prepare(contact("CONNECTED_VALID"));var original=execute(command);
        assertEquals("DUPLICATE".equals(defect)?"2":"1",scalar("select count(*)::text from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
        try(var http=new HttpHarness()) {
            var before=counts();var hidden=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(503,hidden.statusCode(),defect+":"+hidden.body());assertEquals("SERVICE_UNAVAILABLE",http.body(hidden).get("code"));assertTrue(hidden.headers().firstValue("ETag").isEmpty());assertFalse(hidden.body().contains("receiptRef"));assertFalse(hidden.body().contains(original.receiptId().toString()));assertEquals(before,counts());
            var replay=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",command.taskPrecondition().ifMatch()));assertEquals(200,replay.statusCode(),replay.body());assertEquals(original.receiptId().toString(),http.body(replay).get("receiptId"));assertEquals(before,counts());
        }
    }
    /** Deliberately imports historical invalid metadata under the real append capability; never edits immutable rows. */
    private void historicalAudit(Connection c,AuditAppender.Entry entry,String defect)throws SQLException {
        try(var statement=c.createStatement();var row=statement.executeQuery("select current_user")){assertTrue(row.next());assertEquals("law_audit_append",row.getString(1));}
        var snapshot=entry.authorization();var request=snapshot.request();var actor=request.actor();var subject=request.subject();var fact=snapshot.authorityFact();
        var summary=new TreeMap<String,Object>(mapper.readValue(entry.summary(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        if(defect.equals("LEGACY")||defect.equals("MISSING_RECOVERY"))summary.remove("receiptRecovery");
        if(defect.equals("UNKNOWN_FIELD"))summary.put("unregistered",true);
        if(defect.equals("WRONG_SCOPE")){var recovery=new TreeMap<String,Object>((Map<String,Object>)summary.get("receiptRecovery"));var scope=new TreeMap<String,Object>((Map<String,Object>)recovery.get("scope"));scope.put("taskId",UUID.randomUUID().toString());recovery.put("scope",scope);summary.put("receiptRecovery",recovery);}
        if(defect.startsWith("DRAFT_")){var recovery=new TreeMap<String,Object>((Map<String,Object>)summary.get("receiptRecovery"));var binding=new TreeMap<String,Object>((Map<String,Object>)recovery.get("binding"));var draft=new TreeMap<String,Object>((Map<String,Object>)binding.get("draft"));if(defect.equals("DRAFT_FUTURE_REVISION"))draft.put("revision",100L);else draft.put("id",UUID.randomUUID().toString());binding.put("draft",draft);recovery.put("binding",binding);summary.put("receiptRecovery",recovery);}
        String json=CanonicalJson.encode(summary);var columns=new LinkedHashMap<String,Object>();
        columns.put("tenant_id",actor.tenantId());columns.put("audit_entry_id",entry.id());columns.put("entry_type","EVENT");columns.put("audit_scope_code","OBJECT");columns.put("trusted_at",OffsetDateTime.ofInstant(snapshot.checkedAt(),ZoneOffset.UTC));
        columns.put("action_code",defect.equals("WRONG_ACTION")?"SAVE_ACTION_DRAFT":entry.commandType());columns.put("result_code",defect.equals("WRONG_OUTCOME")?"NO_CHANGE":entry.result());columns.put("actor_principal_id",actor.principalId());columns.put("actor_appointment_id",actor.appointmentId());columns.put("on_behalf_of_principal_id",actor.onBehalfPrincipalId());columns.put("on_behalf_of_appointment_id",actor.onBehalfAppointmentId());
        columns.put("command_id",entry.commandId());columns.put("command_type",entry.commandType());columns.put("correlation_id",entry.correlationId());columns.put("authorization_slot_code",request.requirement().slot());columns.put("authorization_path_code",request.requirement().path().name());columns.put("authorization_scope_organization_unit_id",request.scopeOrganizationId());columns.put("authorization_snapshot_digest",snapshot.digest());columns.put("trace_id",entry.correlationId());columns.put("service_role_code","API");columns.put("execution_node_code","HISTORICAL_BAD_METADATA_FIXTURE");
        columns.put("summary_schema_code",defect.equals("LEGACY")?"R1_COMMAND_AUDIT_V1":defect.equals("UNKNOWN_SCHEMA")?"UNRECOGNIZED_HISTORICAL_SCHEMA":"R1_COMMAND_AUDIT_V2");columns.put("summary_schema_version",defect.equals("LEGACY")?1:defect.equals("UNKNOWN_VERSION")?3:2);columns.put("change_summary",json);columns.put("change_summary_digest",defect.equals("BAD_DIGEST")?CanonicalJson.digest("wrong"):CanonicalJson.digest(json));
        columns.put("subject_type",subject.type());columns.put("subject_id",subject.id());columns.put("subject_revision",subject.revision());columns.put("subject_hash",subject.hash()==null?null:Base64.getUrlDecoder().decode(subject.hash()));columns.put("authorization_fact_type",fact.type());columns.put("authorization_fact_id",fact.id());columns.put("authorization_fact_revision",fact.revision());
        String placeholders=String.join(",",columns.keySet().stream().map(name->name.equals("change_summary")?"?::jsonb":"?").toList());
        for(int copy=0;copy<(defect.equals("DUPLICATE")?2:1);copy++){if(copy==1)columns.put("audit_entry_id",UUID.randomUUID());try(var insert=c.prepareStatement("insert into audit.audit_entry ("+String.join(",",columns.keySet())+") values ("+placeholders+")")){int index=1;for(Object value:columns.values())insert.setObject(index++,value);assertEquals(1,insert.executeUpdate());}}
    }
}
