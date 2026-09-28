package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.evidence.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityMaterials;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import java.nio.file.Path;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Synthetic real-byte evidence fixtures only; no production template is installed. */
class R2ContractVersionPersistenceIT extends R2ContractDraftPersistenceIT {
    @TempDir Path directory;
    UUID material,template,clause;String bodySha,partyDigest;
    Subject authorization,anchor;
    ContractPreparationSource source;
    ContractVersionRepository.Policy policy;
    ContractPreparationRepository.Codec codec(){return new ContractPreparationRepository.Codec(){
        public String encode(Map<String,Object> values){return ContractCanonicalJson.encode(values);}
        @SuppressWarnings("unchecked") public Map<String,Object> decode(String body){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(body,Map.class);}
    };}
    ContractVersionRepository versions(){return ContractVersionRepository.databaseBacked(protectedBodies,R2ContractPreparationSources.create(cipher),codec());}
    void versionFixture()throws Exception {
        initialize();material=material();
        template=UUID.randomUUID();clause=UUID.randomUUID();UUID policyId=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(var entry:Map.of("template_version",template,"clause_version",clause).entrySet())sql(x,"insert into contract."+entry.getKey()+"(tenant_id,"+entry.getKey()+"_id,document_code,version_no,evidence_version_id,body_sha256,approved_by_appointment_id,approved_at,created_at) values(?,?,'SYNTHETIC_IT_ONLY',1,?,?,?,clock_timestamp(),clock_timestamp())",seed.tenant(),entry.getValue(),material,HexFormat.of().parseHex(bodySha),seed.appointment());
            registerTemplateSigners(x,template);
            sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode(repeat('12',32),'hex'),clock_timestamp())",seed.tenant(),policyId,seed.org());
            sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),policyId,seed.appointment());
            return null;
        });}
        authorization=approve(newRequest(null));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var basis=new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,terms.digest());
            var selected=new ContractPreparationSources.DirectDecision(authorization.id());
            source=R2ContractPreparationSources.create(cipher).resolve(x,basis,selected);
            anchor=versions().startAnchor(x,seed.tenant(),seed.appointment(),basis,selected);
            policy=versions().currentPolicy(x,seed.tenant(),seed.org());partyDigest=versions().partySnapshotDigest(x,seed.tenant(),confirmation);return null;
        });}
    }
    UUID material()throws Exception {return material(0);}
    UUID material(int rgb)throws Exception {
        var evidence=R2MaterialsServices.evidence(cipher);UUID bid;
        try(var c=database.apiConnection()){bid=inTransaction(c,Capability.COMMAND,x->evidence.open(x,seed.tenant(),opportunity,opportunity,seed.appointment(),confirmationFact,null,"CONTRACT_BUSINESS","SYNTHETIC_IT_ONLY.png","Synthetic fixture; not an approved production template").selector().id());}
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,rgb);var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",bytes);
        var store=MaterialObjectStore.localPrivate(directory,input->new MalwareScanner.ScanResult(MalwareScanner.Verdict.CLEAN,"IT-SYNTHETIC"));
        var stored=store.store(new ByteArrayInputStream(bytes.toByteArray()));bodySha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var b=evidence.basis(x,seed.tenant(),bid);var check=evidence.beginCheck(x,seed.tenant(),b);evidence.completeCheck(x,seed.tenant(),b,check,stored,"PASSED","CLEAN");return null;});}
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var b=evidence.basis(x,seed.tenant(),bid);var submission=evidence.accept(x,seed.tenant(),b);var body=evidence.protectedBody(x,seed.tenant(),bid);return OpportunityMaterials.databaseBacked().accept(x,seed.tenant(),opportunity.id(),null,bid,submission.source(),submission.submission(),submission.binding(),"CONTRACT_BUSINESS",seed.appointment(),submission.at(),body.ciphertext(),body.digest()).selector().id();});}
    }
    ContractVersionInput input(long revision,UUID previous){return new ContractVersionInput(anchor.id(),revision,previous,source,terms,new ContractVersionInput.Document(material,bodySha,template,List.of(clause)),new ContractVersionInput.Signing(partyDigest,"双方有权代表签字并盖章"),new ContractVersionInput.PaymentGate(false,null));}
    Map<String,Object> preparation(ContractVersionInput value){var canonical=value.canonical();return Map.of("document",canonical.get("document"),"signing",canonical.get("signing"),"paymentGate",canonical.get("paymentGate"));}
    Subject fullDraft(ContractVersionInput value,UUID previous)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->repository().saveDraft(x,seed.tenant(),currentBasis(),new ContractPreparationSources.DirectDecision(authorization.id()),previous,terms,preparation(value)));}
    }
    Subject form(Subject draft,ContractVersionInput value)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->versions().formVersion(x,seed.tenant(),draft.id(),seed.appointment(),value,policy));}
    }
    @Test void direct_version_seals_real_evidence_and_approval_set_before_review()throws Exception {
        versionFixture();var value=input(1,null);var draft=fullDraft(value,null);var version=form(draft,value);
        assertEquals("contract.contract_revision",version.type());assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(value.digest())),version.hash());assertNull(version.revision());
        assertEquals(version.id().toString(),scalar("select current_revision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
        assertEquals("true",scalar("select (pre_contract_review_id is null and pre_contract_scope_hash is null and pre_contract_resolution_digest is null and confirmed_action_draft_id is null)::text from contract.contract_revision where tenant_id=? and contract_revision_id=?",seed.tenant(),version.id()));
        assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from contract.revision_approval_requirement where tenant_id=? and contract_revision_id=?",seed.tenant(),version.id()));
        assertEquals("1",scalar("select count(*) from contract.contract_participation where tenant_id=? and contract_revision_id=? and context_role_code='CLIENT'",seed.tenant(),version.id()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{try(var p=x.prepareStatement("select package_ciphertext from contract.contract_revision where tenant_id=? and contract_revision_id=?")){p.setObject(1,seed.tenant());p.setObject(2,version.id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(ContractCanonicalJson.encode(value.canonical()),protectedBodies.open(seed.tenant(),opportunity.id(),version.id(),ContractProtection.Kind.PACKAGE,r.getBytes(1)));}}return null;});}
        assertThrows(Exception.class,()->mutate("update contract.contract_revision set content_digest=content_digest where tenant_id=? and contract_revision_id=?",seed.tenant(),version.id()));
        assertThrows(Exception.class,()->mutate("insert into contract.revision_clause(tenant_id,revision_clause_id,contract_revision_id,clause_version_id,clause_no,created_at) values(?,?,?,?,2,clock_timestamp())",seed.tenant(),UUID.randomUUID(),version.id(),clause));
    }
    @Test void partial_draft_or_wrong_document_bytes_cannot_form_version()throws Exception {
        versionFixture();var partial=saveDraft(authorization,null);assertThrows(Exception.class,()->form(partial,input(1,null)));
        var wrong=new ContractVersionInput(anchor.id(),1,null,source,terms,new ContractVersionInput.Document(material,"ab".repeat(32),template,List.of(clause)),input(1,null).signing(),input(1,null).paymentGate());
        var complete=fullDraft(wrong,partial.id());assertThrows(Exception.class,()->form(complete,wrong));
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void exact_successor_advances_root_and_stale_predecessor_is_rejected()throws Exception {
        versionFixture();var firstInput=input(1,null);var firstDraft=fullDraft(firstInput,null);var first=form(firstDraft,firstInput);
        var nextInput=input(2,first.id());var nextDraft=fullDraft(nextInput,firstDraft.id());var next=form(nextDraft,nextInput);
        assertEquals(first.id().toString(),scalar("select predecessor_revision_id from contract.contract_revision where tenant_id=? and contract_revision_id=?",seed.tenant(),next.id()));
        assertThrows(Exception.class,()->form(nextDraft,nextInput));
        assertEquals("2",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void outer_audit_failure_rolls_back_version_children_and_anchor_pointer()throws Exception {
        versionFixture();var value=input(1,null);var draft=fullDraft(value,null);
        try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{versions().formVersion(x,seed.tenant(),draft.id(),seed.appointment(),value,policy);throw new IllegalStateException("audit failed");}));}
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
        assertEquals("true",scalar("select (current_revision_id is null)::text from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
    }
    @Test void new_direct_authorization_may_revise_existing_contract_without_rewriting_its_origin()throws Exception {
        versionFixture();var origin=authorization;var firstInput=input(1,null);var firstDraft=fullDraft(firstInput,null);var first=form(firstDraft,firstInput);
        UUID oldRequest=UUID.fromString(scalar("select preparation_request_id from contract.preparation_decision where tenant_id=? and preparation_decision_id=?",seed.tenant(),authorization.id()));
        authorization=approve(newRequest(oldRequest));
        try(var c=database.apiConnection()){source=inTransaction(c,Capability.COMMAND,x->R2ContractPreparationSources.create(cipher).resolve(x,new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,terms.digest()),new ContractPreparationSources.DirectDecision(authorization.id())));}
        var nextInput=input(2,first.id());var second=form(fullDraft(nextInput,firstDraft.id()),nextInput);
        assertEquals(origin.id().toString(),scalar("select direct_preparation_decision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
        assertEquals(authorization.id().toString(),scalar("select source_direct_decision_id from contract.contract_revision where tenant_id=? and contract_revision_id=?",seed.tenant(),second.id()));
    }
    void registerTemplateSigners(Connection c,UUID templateVersion)throws SQLException {}
    UUID registerSyntheticTemplateFirm(Connection c,UUID templateVersion)throws SQLException {
        var parties=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked();
        var firm=parties.create(c,seed.tenant(),"ORGANIZATION","签署验收合成律所");
        var snapshot=parties.snapshot(c,seed.tenant(),firm,seed.appointment());UUID id=UUID.randomUUID();
        sql(c,"insert into contract.template_signing_party(tenant_id,template_signing_party_id,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,created_at) values(?,?,?,?,?,?,?,'FIRM',?,clock_timestamp())",seed.tenant(),id,templateVersion,firm.selector().id(),firm.selector().revision(),snapshot.selector().id(),ContractCanonicalJson.digest(snapshot.selector().id().toString()),seed.appointment());
        return id;
    }
    @Test void first_version_may_use_new_authorization_after_anchor_creation()throws Exception {
        versionFixture();var origin=authorization;
        UUID oldRequest=UUID.fromString(scalar("select preparation_request_id from contract.preparation_decision where tenant_id=? and preparation_decision_id=?",seed.tenant(),authorization.id()));
        authorization=approve(newRequest(oldRequest));
        try(var c=database.apiConnection()){source=inTransaction(c,Capability.COMMAND,x->R2ContractPreparationSources.create(cipher).resolve(x,new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,terms.digest()),new ContractPreparationSources.DirectDecision(authorization.id())));}
        var value=input(1,null);var first=form(fullDraft(value,null),value);
        assertEquals(origin.id().toString(),scalar("select direct_preparation_decision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
        assertEquals(authorization.id().toString(),scalar("select source_direct_decision_id from contract.contract_revision where tenant_id=? and contract_revision_id=?",seed.tenant(),first.id()));
    }
    @Test void client_cannot_choose_a_different_approval_set_or_unapproved_template()throws Exception {
        versionFixture();var value=input(1,null);var draft=fullDraft(value,null);
        var forged=new ContractVersionRepository.Policy(policy.id(),policy.digest(),List.of(new ContractVersionRepository.Requirement("OTHER",seed.appointment())));
        try(var c=database.apiConnection()){assertThrows(ContractPreparationSources.Unavailable.class,()->inTransaction(c,Capability.COMMAND,x->versions().formVersion(x,seed.tenant(),draft.id(),seed.appointment(),value,forged)));}
        template=UUID.randomUUID();var missing=input(1,null);var replaced=fullDraft(missing,draft.id());assertThrows(Exception.class,()->form(replaced,missing));
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void preparation_cannot_execute_through_an_empty_legacy_signature_set()throws Exception {
        versionFixture();var value=input(1,null);var version=form(fullDraft(value,null),value);
        var error=assertThrows(Exception.class,()->mutate("insert into contract.contract_execution(tenant_id,contract_execution_id,contract_id,contract_revision_id,approval_set_digest,signature_set_digest,review_scope_hash,review_resolution_digest,archive_evidence_submission_id,execution_digest,executed_by_appointment_id,executed_at) select tenant_id,uuidv7(),?,?,decode(repeat('11',32),'hex'),decode(repeat('11',32),'hex'),decode(repeat('11',32),'hex'),decode(repeat('11',32),'hex'),evidence_submission_id,decode(repeat('11',32),'hex'),?,clock_timestamp() from opportunity.material_version where tenant_id=? and material_version_id=?",anchor.id(),version.id(),seed.appointment(),seed.tenant(),material));
        assertTrue(error.getMessage().contains("R2 preparation requires exact manual execution verification"),error.getMessage());
        assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
    }
}
