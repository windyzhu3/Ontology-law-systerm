package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class R1ReceiptEvidenceHttpIT extends R1HttpFixture {
    @ParameterizedTest @ValueSource(strings={"TASK_DENY","LEAD_DENY","SUBMISSION_DENY","BINDING_DENY","REVOKED","REBOUND"})
    void immutable_receipt_requires_current_exact_task_lead_submission_and_binding(String defect)throws Exception {
        setupContact();var reference=evidence(current.lead());var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",reference.submission().id().toString());var command=prepare(values);
        try(var http=new HttpHarness()) {
            var written=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",command.taskPrecondition().ifMatch()));assertEquals(200,written.statusCode(),written.body());
            String path="/api/v1/commands/"+command.commandId()+"/receipt";var read=http.request("GET",path,null,Map.of());assertEquals(200,read.statusCode(),read.body());assertEquals(http.body(written),http.body(read));
            if(defect.equals("REVOKED")||defect.equals("REBOUND")){mutate("update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=?,revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id=?",seed.appointment(),CanonicalJson.digest("revoke"),seed.tenant(),reference.binding().id());
                if(defect.equals("REBOUND")){var rejected=assertThrows(java.sql.SQLException.class,()->mutate("insert into evidence.evidence_binding (tenant_id,evidence_binding_id,evidence_submission_id,purpose_code,bound_by_appointment_id,bound_at,target_type,target_id,target_revision) values (?,?,?,'CONTACT',?,clock_timestamp(),?,?,?)",seed.tenant(),UUID.randomUUID(),reference.submission().id(),seed.appointment(),current.lead().type(),current.lead().id(),current.lead().revision()));assertEquals("23505",rejected.getSQLState(),"Frozen unique submission binding cannot be replaced");}}
            else {var denied=switch(defect){case "TASK_DENY"->new Subject(current.selector().type(),current.selector().id(),1L,null);case "LEAD_DENY"->current.lead();case "SUBMISSION_DENY"->reference.submission();default->reference.binding();};
                mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values (?,?,?,?,'SALES_CONTACT_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),denied.type(),denied.id(),denied.revision(),denied.hash()==null?null:Base64.getUrlDecoder().decode(denied.hash()));}
            var before=counts();var denied=http.request("GET",path,null,Map.of());assertEquals(defect.equals("TASK_DENY")||defect.equals("LEAD_DENY")?403:404,denied.statusCode(),denied.body());assertEquals(before,counts());assertFalse(denied.body().contains("receiptRef"));assertFalse(denied.body().contains(reference.submission().id().toString()));assertFalse(denied.body().contains(reference.binding().id().toString()));
        }
    }
    /** Frozen physical import chain, no intake endpoint or schema relaxation. */
    private EvidenceReferenceReader.Reference evidence(Subject target)throws Exception {
        UUID submission=UUID.randomUUID(),binding=UUID.randomUUID(),session=UUID.randomUUID(),object=UUID.randomUUID();String key=UUID.randomUUID().toString();byte[] digest=CanonicalJson.digest("fixture bytes");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into evidence.upload_session (tenant_id,upload_session_id,object_store_code,object_key,purpose_code,intake_contract_code,intake_contract_version,intake_contract_digest,upload_capability_hash,status,created_by_appointment_id,created_at,expires_at,target_type,target_id,target_revision,target_hash) values (?,?,'FIXTURE',?,'CONTACT','FIXTURE',1,?,?,'OPEN',?,clock_timestamp(),clock_timestamp()+interval '1 hour',?,?,?,?)",seed.tenant(),session,key,digest,digest,seed.appointment(),target.type(),target.id(),target.revision(),target.hash()==null?null:Base64.getUrlDecoder().decode(target.hash()));
            sql(x,"update evidence.upload_session set status='OBJECT_RECEIVED',received_at=clock_timestamp(),revision=1 where tenant_id=? and upload_session_id=?",seed.tenant(),session);
            sql(x,"insert into evidence.received_source_object (tenant_id,received_source_object_id,upload_session_id,object_store_code,object_key,object_version,size_bytes,server_sha256,detected_media_type,scan_result,scan_engine_code,scan_contract_version,scanned_at,received_at) values (?,?,?,'FIXTURE',?,'immutable-fixture-version',13,?,'text/plain','PASSED','FIXTURE',1,clock_timestamp(),clock_timestamp())",seed.tenant(),object,session,key,digest);
            sql(x,"insert into evidence.evidence_submission (tenant_id,evidence_submission_id,received_source_object_id,submission_contract_code,submission_contract_version,submitted_by_appointment_id,submitted_at) values (?,?,?,'FIXTURE',1,?,clock_timestamp())",seed.tenant(),submission,object,seed.appointment());
            sql(x,"insert into evidence.evidence_binding (tenant_id,evidence_binding_id,evidence_submission_id,purpose_code,bound_by_appointment_id,bound_at,target_type,target_id,target_revision,target_hash) values (?,?,?,'CONTACT',?,clock_timestamp(),?,?,?,?)",seed.tenant(),binding,submission,seed.appointment(),target.type(),target.id(),target.revision(),target.hash()==null?null:Base64.getUrlDecoder().decode(target.hash()));
            sql(x,"update evidence.upload_session set status='FINALIZED',finalized_at=clock_timestamp(),revision=2 where tenant_id=? and upload_session_id=?",seed.tenant(),session);return null;
        });return inTransaction(c,Capability.QUERY,x->EvidenceReferenceReader.databaseBacked().read(x,seed.tenant(),submission));}
    }
}
