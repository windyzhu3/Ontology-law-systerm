package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import java.util.*;
import java.nio.file.*;
import javax.crypto.spec.SecretKeySpec;

/** Same imported records through two real-authority entry paths to signature archive.
 * Opt-in local acceptance uses an existing licensed-font synthetic fillable PDF fixture.
 */
class R2SalesChainClosureIT extends R2ImportedLeadSalesHttpIT {
 Map<String,Object> paymentGate(){var gate=new LinkedHashMap<String,Object>();gate.put("receiptRequiredBeforeTransfer",false);gate.put("requiredMinor",null);return gate;}
    Actor conflictReviewer,contractApprover,signatureVerifier;
    String base;
 void setupQuote()throws Exception{setupContact();opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));var contact=execute(prepare(contact("CONNECTED_VALID")));try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("SALES_OPPORTUNITY_OWNER","CUSTOMER_REQUIREMENTS_MANAGE","QUOTE_READ","QUOTE_PREPARE","QUOTE_APPROVE","QUOTE_DELIVER","QUOTE_RESPONSE"))grant(x,code);return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"T07_HTTP");var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());p.put("responsibilityBasis",R2CustomerRequirementsServices.selector(opportunity));p.put("expectedDraft",null);p.put("expectedConfirmation",null);p.put("document",Map.of("participants",List.of(Map.of("role","CLIENT","newParty",Map.of("kind","ORGANIZATION","name","T07 HTTP 客户","distinctIdentityConfirmed",true)),Map.of("role","OPPONENT","newParty",Map.of("kind","ORGANIZATION","name","F09 opponent","distinctIdentityConfirmed",true))),"unknownOpponent",false,"matterName","报价测试","customerGoal","确认销售报价","serviceScope","法律咨询","knownConstraints","","contactName","测试联系人","contactPhone",""));var saved=execute(new CommandEnvelope(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p));assertEquals(CommandOutcome.Status.SUCCEEDED,saved.status());p.remove("document");p.put("expectedDraft",R2CustomerRequirementsServices.selector(saved.resultFact()));assertEquals(CommandOutcome.Status.SUCCEEDED,execute(new CommandEnvelope(CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p)).status());}
    @Override void registerActors(Actor entryApprover) {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("ols.contract.fixtureTemplate")!=null,"Local synthetic PDF fixture explicitly required");
        try {
            conflictReviewer=credentialActor(PrincipalKind.HUMAN,"F09 conflict reviewer","CONTRACT_REVIEW");
            contractApprover=credentialActor(PrincipalKind.HUMAN,"F09 contract approver","CONTRACT_APPROVE");
            signatureVerifier=credentialActor(PrincipalKind.HUMAN,"F09 signature verifier","CONTRACT_SIGNATURE_VERIFY");
            for(var actor:List.of(conflictReviewer,contractApprover,signatureVerifier))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'CONTRACT_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),seed.org());
            var registry=new ArrayList<io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration>();
            for(var actor:List.of(seed.request().actor(),entryApprover,conflictReviewer,contractApprover,signatureVerifier))registry.add(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",actor));
            realHumanResolver=new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(credentialKeys::get),List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Trust(ISSUER,AUDIENCE,(java.security.interfaces.RSAPublicKey)signing.getPublic())),registry);
            materialStore=io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore.localPrivate(materialDirectory,input->new io.github.windyzhu3.ontologylaw.evidence.MalwareScanner.ScanResult(io.github.windyzhu3.ontologylaw.evidence.MalwareScanner.Verdict.CLEAN,"F09-SYNTHETIC"));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    Map<String,Object> context(RoleHttp actor)throws Exception{var response=actor.request("GET",base,null,Map.of());assertEquals(200,response.statusCode(),response.body());return actor.body(response);}
    Map<String,Object> step(RoleHttp actor,String path,Map<String,Object> values,boolean signature)throws Exception{
        var ctx=context(actor);var v=new LinkedHashMap<>(values);if(signature)v.put("expectedSignatureWorkflow",((Map<?,?>)((Map<?,?>)ctx.get("signature")).get("workflow")).get("selector"));
        String key=UUID.randomUUID().toString();var body=contractBody(ctx,v);var result=actor.request("POST",base+"/"+path,body,Map.of("Idempotency-Key",key));assertEquals(200,result.statusCode(),path+": "+result.body());
        assertSameReceipt(actor.body(result),actor.body(actor.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));return actor.body(result);
    }
    @SuppressWarnings("unchecked") @Override void continueContractPreparation(HttpHarness http)throws Exception {
        super.continueContractPreparation(http);base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
        var sales=new RoleHttp(http,SUBJECT);var review=new RoleHttp(http,"F09 conflict reviewer");var approval=new RoleHttp(http,"F09 contract approver");var verifier=new RoleHttp(http,"F09 signature verifier");
        byte[] templateBytes=Files.readAllBytes(java.nio.file.Path.of(System.getProperty("ols.contract.fixtureTemplate")));var templateMaterial=acceptedMaterial(context(sales),templateBytes,"F09-template.pdf");String templateSha=sha(templateBytes);UUID template=UUID.randomUUID(),policy=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into contract.template_version(tenant_id,template_version_id,document_code,version_no,evidence_version_id,body_sha256,approved_by_appointment_id,approved_at,created_at) values(?,?,'F09_SYNTHETIC_ONLY',1,?,?,?,clock_timestamp(),clock_timestamp())",seed.tenant(),template,templateMaterial.id(),HexFormat.of().parseHex(templateSha),seed.appointment());
            var parties=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked();var firm=parties.create(x,seed.tenant(),"ORGANIZATION","F09 synthetic law firm");var snapshot=parties.snapshot(x,seed.tenant(),firm,seed.appointment());
            sql(x,"insert into contract.template_signing_party(tenant_id,template_signing_party_id,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,created_at) values(?,?,?,?,?,?,?,'FIRM',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),template,firm.selector().id(),firm.selector().revision(),snapshot.selector().id(),ContractCanonicalJson.digest(snapshot.selector().id().toString()),seed.appointment());
            sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode(repeat('12',32),'hex'),clock_timestamp())",seed.tenant(),policy,seed.org());
            sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),policy,contractApprover.appointmentId());return null;
        });}
        var ctx=context(sales);var values=new LinkedHashMap<String,Object>();values.put("commercial",((Map<?,?>)((Map<?,?>)ctx.get("contract")).get("document")).get("commercial"));
        var doc=new LinkedHashMap<String,Object>(Map.of("evidenceVersionId",templateMaterial.id().toString(),"bodySha256",templateSha,"templateVersionId",template.toString(),"clauseVersionIds",List.of()));values.put("document",doc);values.put("signing",Map.of("partySnapshotDigest",ctx.get("partySnapshotDigest"),"requirements","双方有权代表签字并盖章"));values.put("paymentGate",paymentGate());
        var generated=sales.request("POST",base+"/generate",contractBody(ctx,values),Map.of());assertEquals(200,generated.statusCode(),generated.body());var candidate=sales.body(generated);byte[] pdf=Base64.getDecoder().decode((String)candidate.get("pdfBase64"));assertEquals(candidate.get("bodySha256"),sha(pdf));
        var bodyMaterial=acceptedMaterial(context(sales),pdf,"F09-generated.pdf");doc.put("evidenceVersionId",bodyMaterial.id().toString());doc.put("bodySha256",sha(pdf));doc.put("generationProof",candidate.get("generationProof"));doc.put("humanConfirmed",true);
        step(sales,"form",values,false);step(sales,"review-requests",Map.of(),false);step(review,"review-decisions",Map.of("decision","CLEAR","reason","Reviewed complete exact parties"),false);step(sales,"approval-requests",Map.of(),false);step(approval,"decisions",Map.of("decision","APPROVED","reason","Approved generated version"),false);
        recoverSignature();
        ctx=context(sales);var signature=(Map<?,?>)ctx.get("signature");var slots=new ArrayList<Map<String,Object>>();for(Object raw:(List<?>)signature.get("parties")){var party=(Map<?,?>)raw;if(party.get("templateSigningPartyId")==null&&!String.valueOf(party.get("label")).contains("T07 HTTP"))continue;var slot=new LinkedHashMap<String,Object>();int n=slots.size()+1;slot.put("slotNumber",n);slot.put("partyId",party.get("id"));if(party.get("participationId")!=null)slot.put("participationId",party.get("participationId"));else slot.put("templateSigningPartyId",party.get("templateSigningPartyId"));slot.put("authoritySlot","SIGNER_"+n);slot.put("required",true);slot.put("signatureRequired",true);slot.put("sealRequired",true);slot.put("clauseBasis","Approved signing clause");slots.add(slot);}assertEquals(2,slots.size());
        step(sales,"signature-arrangements",Map.of("humanConfirmed",true,"slots",slots),true);
        for(int n=1;n<=2;n++){
            step(sales,"signature-submissions",Map.of("slotNumber",n,"materialVersionId",bodyMaterial.id().toString(),"materialSha256",sha(pdf),"authorityMaterialVersionId",bodyMaterial.id().toString(),"authorityMaterialSha256",sha(pdf),"signerName","Synthetic signer "+n,"signedAt",java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()),true);
            step(verifier,"signature-verifications",Map.of("decision","VERIFIED","reason","Synthetic exact evidence manually confirmed","contentCorresponds",true,"arrangementComplete",true,"authorityVerified",true,"materialComplete",true,"signatureVerified",true,"sealVerified",true),true);
        }
        step(verifier,"signature-archive",Map.of("materialVersionId",bodyMaterial.id().toString(),"materialSha256",sha(pdf),"reason","Complete synthetic archive","archiveComplete",true),true);
        assertEquals("1",scalar("select count(*) from contract.signature_handoff where tenant_id=?",seed.tenant()));assertEquals("2",scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
    }
    String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    void recoverSignature()throws Exception{
        var worker=service("CONTRACT_TASK_RECOVER");var service=R2ContractServices.create(contractProtection,opportunityProtection,materialStore);Map<String,Object> p;
        try(var c=database.apiConnection()){p=inTransaction(c,Capability.QUERY,x->{var rows=service.recoveryPage(x,worker,100,null).candidates();assertEquals(1,rows.size());var row=rows.getFirst();var v=new LinkedHashMap<String,Object>();v.put("opportunityId",opportunity.id().toString());v.put("expectedOpportunityRevision",row.opportunity().revision());v.put("responsibilityBasis",Map.of("id",row.basis().id().toString(),"revision",row.basis().revision()));v.put("sourceKind","SIGNATURE_READINESS");v.put("source",Map.of("id",row.source().id().toString(),"revision",0L));v.put("expectedWorkflow",null);return v;});}
        var command=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,p);var actual=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"F09_SIGNATURE",service);
        try(var c=database.apiConnection()){var first=actual.execute(c,command);assertInstanceOf(CommandOutcome.class,first);assertEquals(CommandOutcome.Status.SUCCEEDED,((CommandOutcome)first).status());assertEquals(first,actual.execute(c,command));}
    }
}
