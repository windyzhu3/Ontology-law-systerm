package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

/** Exact preparation writes only; this adapter cannot approve, sign, execute or emit tasks. */
public final class JdbcContractVersionRepository implements ContractVersionRepository {
    private final ContractProtection protection;
    private final ContractPreparationSources sources;
    private final ContractPreparationRepository drafts;
    public JdbcContractVersionRepository(ContractProtection protection,ContractPreparationSources sources,ContractPreparationRepository.Codec codec) {
        this.protection=Objects.requireNonNull(protection);this.sources=Objects.requireNonNull(sources);
        this.drafts=ContractPreparationRepository.databaseBacked(protection,sources,Objects.requireNonNull(codec));
    }
    private static void transaction(Connection c)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Contract version requires a READ COMMITTED transaction");
    }
    public Policy currentPolicy(Connection c,UUID tenant,UUID organization)throws SQLException {
        transaction(c);Objects.requireNonNull(tenant);Objects.requireNonNull(organization);
        try(var p=prepare(c,"select organization_unit_id from identity.organization_unit where tenant_id=? and organization_unit_id=? for share",tenant,organization);var r=p.executeQuery()){if(!r.next())throw unavailable();}
        UUID id;String digest;
        try(var p=prepare(c,"select approval_policy_id,policy_digest from contract.approval_policy where tenant_id=? and organization_unit_id=? and policy_code='R2_CONTRACT_APPROVAL_V1' and mode='REQUIRE_APPROVAL' order by policy_version desc limit 1",tenant,organization);var r=p.executeQuery()) {
            if(!r.next())throw unavailable();id=r.getObject(1,UUID.class);digest=HexFormat.of().formatHex(r.getBytes(2));
        }
        var requirements=new ArrayList<Requirement>();
        try(var p=prepare(c,"select requirement_code,appointment_id from contract.approval_policy_member where tenant_id=? and policy_id=? order by requirement_code",tenant,id);var r=p.executeQuery()) {
            while(r.next())requirements.add(new Requirement(r.getString(1),r.getObject(2,UUID.class)));
        }
        return new Policy(id,digest,requirements);
    }
    public String partySnapshotDigest(Connection c,UUID tenant,UUID confirmation)throws SQLException {
        transaction(c);Objects.requireNonNull(tenant);Objects.requireNonNull(confirmation);
        var participants=new ArrayList<Map<String,Object>>();
        for(var row:JdbcContractWorkflowService.rows(c,"""
                select x.party_id,x.party_revision,x.role,x.profile_version_id,p.revision,p.status
                from opportunity.customer_requirement_participant x
                join party.party p on p.tenant_id=x.tenant_id and p.party_id=x.party_id
                where x.tenant_id=? and x.confirmation_id=? order by x.party_id,x.role
                """,tenant,confirmation)) {
            if(((Number)row.get("party_revision")).longValue()!=((Number)row.get("revision")).longValue()||!"ACTIVE".equals(row.get("status")))throw unavailable();
            participants.add(Map.of("partyId",row.get("party_id").toString(),"partyRevision",((Number)row.get("party_revision")).longValue(),"role",row.get("role"),"profileVersionId",row.get("profile_version_id").toString()));
        }
        if(participants.isEmpty()||participants.stream().noneMatch(p->"CLIENT".equals(p.get("role"))))throw unavailable();
        return HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(Map.of("format","R2_CONTRACT_PARTIES_V1","confirmationId",confirmation.toString(),"participants",participants))));
    }
    public Subject startAnchor(Connection c,UUID tenant,UUID actor,ContractPreparationSource.Basis basis,ContractPreparationSources.Selection selection)throws SQLException {
        transaction(c);Objects.requireNonNull(actor);
        if(!Objects.requireNonNull(basis).tenantId().equals(tenant))throw unavailable();
        var source=sources.resolve(c,basis,selection);UUID id=id(c);
        execute(c,"""
                insert into contract.contract(tenant_id,contract_id,opportunity_id,accepted_quote_response_id,
                  direct_preparation_decision_id,preparation_contract_code,created_by_appointment_id,revision,created_at,changed_at)
                values(?,?,?,?,?,'R2_CONTRACT_PREPARATION_V1',?,0,clock_timestamp(),clock_timestamp())
                """,tenant,id,basis.opportunityId(),quote(source),direct(source),actor);
        assertSource(source,sources.resolve(c,basis,selection));
        return new Subject("contract.contract",id,0L,null);
    }
    public Subject formVersion(Connection c,UUID tenant,UUID draftId,UUID actor,ContractVersionInput input,Policy selectedPolicy)throws SQLException {
        transaction(c);Objects.requireNonNull(actor);Objects.requireNonNull(draftId);Objects.requireNonNull(input);Objects.requireNonNull(selectedPolicy);
        if(!input.source().basis().tenantId().equals(tenant))throw unavailable();
        var selection=selection(input.source());
        var source=sources.resolve(c,input.source().basis(),selection);assertSource(input.source(),source);
        var draft=drafts.readDraft(c,tenant,draftId);
        if(draft==null||!draft.basis().opportunity().id().equals(source.basis().opportunityId())||!draft.basis().customerConfirmation().equals(source.basis().customerConfirmationId())||!draft.commercialDigest().equals(input.commercial().digest())||!canonical(draft.commercial()).equals(canonical(input.commercial().canonical())))throw unavailable();
        var full=input.canonical();var preparation=Map.of("document",full.get("document"),"signing",full.get("signing"),"paymentGate",full.get("paymentGate"));
        if(!canonical(draft.preparation()).equals(canonical(preparation)))throw new IllegalArgumentException("Confirm a complete exact contract preparation draft");
        if(!partySnapshotDigest(c,tenant,draft.basis().customerConfirmation()).equals(input.signing().partySnapshotDigest()))throw unavailable();
        UUID organization;
        try(var p=prepare(c,"select organization_unit_id from identity.appointment where tenant_id=? and appointment_id=?",tenant,draft.basis().owner());var r=p.executeQuery()){if(!r.next())throw unavailable();organization=r.getObject(1,UUID.class);}
        Policy policy=currentPolicy(c,tenant,organization);if(!policy.equals(selectedPolicy))throw unavailable();
        long rootRevision;
        try(var p=prepare(c,"""
                select revision,current_revision_id,contract_execution_id,contract_termination_id,opportunity_id,preparation_contract_code
                from contract.contract where tenant_id=? and contract_id=? for update
                """,tenant,input.contractId());var r=p.executeQuery()) {
            if(!r.next()||!Objects.equals(r.getObject(2,UUID.class),input.predecessorId())||r.getObject(3)!=null||r.getObject(4)!=null||!source.basis().opportunityId().equals(r.getObject(5,UUID.class))||!ContractVersionInput.FORMAT.equals(r.getString(6)))throw unavailable();
            rootRevision=r.getLong(1);
        }
        UUID version=id(c);String clear=canonical(full);byte[] encrypted=protection.seal(tenant,source.basis().opportunityId(),version,ContractProtection.Kind.PACKAGE,clear);
        execute(c,"""
                insert into contract.contract_revision(tenant_id,contract_revision_id,contract_id,revision_no,predecessor_revision_id,
                  source_quote_revision_id,source_quote_response_id,body_sha256,package_contract_code,package_contract_version,
                  content_digest,created_by_appointment_id,created_at,preparation_draft_id,source_direct_decision_id,
                  customer_confirmation_id,commercial_digest,body_evidence_version_id,template_version_id,party_snapshot_digest,
                  package_ciphertext,receipt_required_before_transfer,required_amount_minor,approval_requirement_count,clause_count)
                values(?,?,?,?,?,?,?,?,'R2_CONTRACT_PREPARATION_V1',1,?,?,clock_timestamp(),?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,version,input.contractId(),input.revision(),input.predecessorId(),source instanceof ContractPreparationSource.AcceptedQuote accepted?accepted.quoteRevisionId():null,
                quote(source),hex(input.document().bodySha256()),hex(input.digest()),actor,draftId,direct(source),source.basis().customerConfirmationId(),hex(input.commercial().digest()),input.document().evidenceVersionId(),input.document().templateVersionId(),hex(input.signing().partySnapshotDigest()),encrypted,input.paymentGate().receiptRequiredBeforeTransfer(),input.paymentGate().requiredMinor(),policy.requirements().size(),input.document().clauseVersionIds().size());
        int ordinal=0;
        try(var p=prepare(c,"select party_id,party_revision,role,profile_version_id from opportunity.customer_requirement_participant where tenant_id=? and confirmation_id=? order by party_id,role",tenant,source.basis().customerConfirmationId());var participants=p.executeQuery()) {
            while(participants.next())execute(c,"insert into contract.contract_participation(tenant_id,contract_participation_id,contract_revision_id,participation_no,party_id,party_revision,party_snapshot_digest,context_role_code,signature_required,created_at) values(?,?,?,?,?,?,?,?,false,clock_timestamp())",tenant,id(c),version,++ordinal,participants.getObject(1,UUID.class),participants.getLong(2),ContractCanonicalJson.digest(participants.getObject(4,UUID.class).toString()),participants.getString(3));
        }
        ordinal=0;
        for(UUID clause:input.document().clauseVersionIds())execute(c,"insert into contract.revision_clause(tenant_id,revision_clause_id,contract_revision_id,clause_version_id,clause_no,created_at) values(?,?,?,?,?,clock_timestamp())",tenant,id(c),version,clause,++ordinal);
        for(var requirement:policy.requirements())execute(c,"insert into contract.revision_approval_requirement(tenant_id,revision_approval_requirement_id,contract_revision_id,requirement_code,approver_appointment_id,policy_digest,policy_id,created_at) values(?,?,?,?,?,?,?,clock_timestamp())",tenant,id(c),version,requirement.code(),requirement.approver(),hex(policy.digest()),policy.id());
        execute(c,"update contract.contract set current_revision_id=?,approved_revision_id=null,revision=revision+1,changed_at=clock_timestamp() where tenant_id=? and contract_id=? and revision=? and current_revision_id is not distinct from ?",version,tenant,input.contractId(),rootRevision,input.predecessorId());
        // Force deferred package verification before returning an exact result to command composition.
        try(var p=prepare(c,"select contract.fn_assert_r2_version(?,?)",tenant,version)){p.execute();}
        assertSource(source,sources.resolve(c,input.source().basis(),selection));
        if(!policy.equals(currentPolicy(c,tenant,organization)))throw unavailable();
        return new Subject("contract.contract_revision",version,null,Base64.getUrlEncoder().withoutPadding().encodeToString(hex(input.digest())));
    }
    private static ContractPreparationSources.Selection selection(ContractPreparationSource source){return switch(source){case ContractPreparationSource.AcceptedQuote a->new ContractPreparationSources.AcceptedResponse(a.responseId());case ContractPreparationSource.DirectAuthorization d->new ContractPreparationSources.DirectDecision(d.decisionId());};}
    private static UUID quote(ContractPreparationSource s){return s instanceof ContractPreparationSource.AcceptedQuote a?a.responseId():null;}
    private static UUID direct(ContractPreparationSource s){return s instanceof ContractPreparationSource.DirectAuthorization a?a.decisionId():null;}
    private static void assertSource(ContractPreparationSource expected,ContractPreparationSource actual){if(!canonical(expected.canonical()).equals(canonical(actual.canonical())))throw unavailable();}
    private static String canonical(Map<String,Object> value){return ContractCanonicalJson.encode(value);}
    private static byte[] hex(String value){return HexFormat.of().parseHex(value);}
    private static ContractPreparationSources.Unavailable unavailable(){return new ContractPreparationSources.Unavailable();}
    private static UUID id(Connection c)throws SQLException{try(var p=c.prepareStatement("select uuidv7()");var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}}
    private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException{var p=c.prepareStatement(sql);try{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}catch(SQLException e){p.close();throw e;}}
    private static void execute(Connection c,String sql,Object...args)throws SQLException{try(var p=prepare(c,sql,args)){if(p.executeUpdate()!=1)throw unavailable();}}
}
