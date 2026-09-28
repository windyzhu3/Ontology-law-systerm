package io.github.windyzhu3.ontologylaw.api;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** V990 database boundary probes; synthetic bytes never represent a legal signature. */
class R2ManualSignatureSchemaIT extends R2ContractWorkflowPersistenceIT {
    UUID ready,version;
    @Override void registerTemplateSigners(Connection c,UUID templateVersion) throws SQLException {registerSyntheticTemplateFirm(c,templateVersion);}
    void signingFixture() throws Exception {
        versionFixture(); version=formThroughWorkflow().id();
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","已核对准确主体和范围")));
        command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","批准本版正文")));
        ready=UUID.fromString(scalar("select signature_readiness_id from contract.signature_readiness where tenant_id=? and contract_revision_id=?",seed.tenant(),version));
    }
    void insertArrangement(Connection c,UUID id,UUID previous,int count) throws SQLException {
        sql(c,"insert into contract.signature_arrangement(tenant_id,signature_arrangement_id,opportunity_id,readiness_id,contract_revision_id,previous_arrangement_id,registered_by_appointment_id,slot_count,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,decode(repeat('00',29),'hex'),decode(repeat('ca',32),'hex'),clock_timestamp())",seed.tenant(),id,opportunity.id(),ready,version,previous,seed.appointment(),count);
    }
    void insertPlan(Connection c,UUID id,UUID arrangement) throws SQLException {
        sql(c,"insert into contract.signature_plan(tenant_id,signature_plan_id,contract_revision_id,slot_no,authority_slot_code,contract_participation_id,signer_party_id,signature_method_code,seal_required,required,plan_digest,created_at,arrangement_id,signature_required) select tenant_id,?,contract_revision_id,1,'CLIENT_MANUAL',contract_participation_id,party_id,'MANUAL',true,true,decode(repeat('ca',32),'hex'),clock_timestamp(),?,true from contract.contract_participation where tenant_id=? and contract_revision_id=? and context_role_code='CLIENT'",id,arrangement,seed.tenant(),version);
    }
    void insertFirmPlan(Connection c,UUID id,UUID arrangement) throws SQLException {
        sql(c,"insert into contract.signature_plan(tenant_id,signature_plan_id,contract_revision_id,slot_no,authority_slot_code,signer_party_id,signature_method_code,seal_required,required,plan_digest,created_at,arrangement_id,signature_required,template_signing_party_id) select tenant_id,?,?,2,'FIRM_MANUAL',party_id,'MANUAL',true,true,decode(repeat('ca',32),'hex'),clock_timestamp(),?,true,template_signing_party_id from contract.template_signing_party where tenant_id=? and template_version_id=?",id,version,arrangement,seed.tenant(),template);
    }
    UUID arrangement(UUID previous) throws Exception {
        var id=UUID.randomUUID();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{insertArrangement(x,id,previous,2);insertPlan(x,UUID.randomUUID(),id);insertFirmPlan(x,UUID.randomUUID(),id);return null;});}return id;
    }
    @Test void manual_arrangements_reuse_plan_without_rewriting_frozen_participation() throws Exception {
        signingFixture();var first=arrangement(null);var second=arrangement(first);
        assertEquals("4",scalar("select count(*) from contract.signature_plan where tenant_id=? and contract_revision_id=?",seed.tenant(),version));
        assertEquals("0",scalar("select count(*) from contract.contract_participation where tenant_id=? and contract_revision_id=? and signature_required",seed.tenant(),version));
        assertThrows(Exception.class,()->arrangement(first));
        assertThrows(Exception.class,()->arrangement(null));
        assertThrows(Exception.class,()->mutate("update contract.signature_arrangement set slot_count=2 where tenant_id=? and signature_arrangement_id=?",seed.tenant(),second));
        assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
    }
    @Test void manual_collection_must_be_complete_and_sealed_in_its_transaction() throws Exception {
        signingFixture();
        try(var c=database.apiConnection()) {
            var failure=assertThrows(Exception.class,()->inTransaction(c,Capability.COMMAND,x->{insertArrangement(x,UUID.randomUUID(),null,1);return null;}));
            assertTrue(failure.getMessage().contains("manual signature arrangement set incomplete"));
        }
        assertEquals("0",scalar("select count(*) from contract.signature_arrangement where tenant_id=?",seed.tenant()));
        try(var c=database.apiConnection()) {
            var failure=assertThrows(Exception.class,()->inTransaction(c,Capability.COMMAND,x->{var onlyClient=UUID.randomUUID();insertArrangement(x,onlyClient,null,1);insertPlan(x,UUID.randomUUID(),onlyClient);return null;}));
            assertTrue(failure.getMessage().contains("manual signature arrangement set incomplete"));
        }
        var a=arrangement(null);
        try(var c=database.apiConnection()) {
            var failure=assertThrows(Exception.class,()->inTransaction(c,Capability.COMMAND,x->{insertPlan(x,UUID.randomUUID(),a);return null;}));
            assertTrue(failure.getMessage().contains("manual signature collection sealed"));
        }
    }
    @Test void manual_template_binding_cannot_be_retrofitted_after_approval() throws Exception {
        signingFixture();
        var failure=assertThrows(Exception.class,()->mutate("insert into contract.template_signing_party(tenant_id,template_signing_party_id,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,created_at) select tenant_id,?,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,clock_timestamp() from contract.template_signing_party where tenant_id=? and template_version_id=?",UUID.randomUUID(),seed.tenant(),template));
        assertTrue(failure.getMessage().contains("manual template signer binding sealed"));
    }
    @Test void archive_cannot_promote_empty_or_partial_signing_to_execution_handoff() throws Exception {
        signingFixture();var a=arrangement(null);
        var failure=assertThrows(Exception.class,()->mutate("insert into contract.signature_archive(tenant_id,signature_archive_id,opportunity_id,arrangement_id,material_version_id,archived_by_appointment_id,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,decode(repeat('00',29),'hex'),decode(repeat('af',32),'hex'),clock_timestamp())",seed.tenant(),UUID.randomUUID(),opportunity.id(),a,material,seed.appointment()));
        assertTrue(failure.getMessage().contains("manual signature archive incomplete"));
        assertEquals("0",scalar("select count(*) from contract.signature_archive where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.signature_handoff where tenant_id=?",seed.tenant()));
    }
}
