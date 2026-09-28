package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.*;
import java.sql.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

/** No independent connection, clock cache, plaintext logging or transaction commit. */
public final class JdbcContractPreparationSources implements ContractPreparationSources {
    private final QuoteBody body;
    public JdbcContractPreparationSources(QuoteBody body) { this.body=Objects.requireNonNull(body); }

    public ContractPreparationSource resolve(Connection c,ContractPreparationSource.Basis expected,Selection selection)throws SQLException {
        Objects.requireNonNull(c);Objects.requireNonNull(expected);Objects.requireNonNull(selection);
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException("Contract source requires a READ COMMITTED command transaction");
        // Same lock order as the sales writers: opportunity first, then its issue.
        try(var p=prepare(c,"select closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=? for update",expected.tenantId(),expected.opportunityId());var r=p.executeQuery()) {
            if(!r.next()||r.getObject(1)!=null)throw new Unavailable();
        }
        try(var p=prepare(c,"""
                select 1 from opportunity.customer_requirement_confirmation c
                 where c.tenant_id=? and c.opportunity_id=? and c.customer_requirement_confirmation_id=?
                   and not exists(select 1 from opportunity.customer_requirement_confirmation n
                                  where n.tenant_id=c.tenant_id and n.previous_confirmation_id=c.customer_requirement_confirmation_id)
                """,expected.tenantId(),expected.opportunityId(),expected.customerConfirmationId());var r=p.executeQuery()) {
            if(!r.next())throw new Unavailable();
        }
        // A confirmation is immutable, but the referenced Party anchors can change or merge.
        // Hold shared locks until version persistence finishes, so a concurrent edit cannot race us.
        try(var p=prepare(c,"""
                select p.revision,f.party_revision,p.status
                  from opportunity.customer_requirement_participant f
                  join party.party p on p.tenant_id=f.tenant_id and p.party_id=f.party_id
                 where f.tenant_id=? and f.confirmation_id=?
                 order by p.party_id for share of p
                """,expected.tenantId(),expected.customerConfirmationId());var r=p.executeQuery()) {
            boolean present=false;
            while(r.next()) {
                present=true;
                if(r.getLong(1)!=r.getLong(2)||!"ACTIVE".equals(r.getString(3)))throw new Unavailable();
            }
            if(!present)throw new Unavailable();
        }
        ContractPreparationSource source=switch(selection) {
            case DirectDecision d -> direct(c,expected,d.decisionId());
            case AcceptedResponse a -> accepted(c,expected,a.responseId());
        };
        // Read the database clock after lock waits, decryption and all source checks.
        try(var p=c.prepareStatement("select clock_timestamp()");var r=p.executeQuery()) {
            r.next();try { source.validateAt(expected,r.getTimestamp(1).toInstant()); }
            catch(IllegalArgumentException changed) { throw new Unavailable(); }
        }
        return source;
    }

    private ContractPreparationSource direct(Connection c,ContractPreparationSource.Basis expected,UUID decision)throws SQLException {
        try(var p=prepare(c,"""
                select d.preparation_request_id,d.effective_from,d.effective_until
                  from contract.preparation_decision d
                  join contract.preparation_request r on r.tenant_id=d.tenant_id and r.preparation_request_id=d.preparation_request_id
                  join opportunity.opportunity o on o.tenant_id=r.tenant_id and o.opportunity_id=r.opportunity_id
                 where d.tenant_id=? and d.preparation_decision_id=? and d.decision_code='APPROVED'
                   and r.opportunity_id=? and r.customer_confirmation_id=? and r.commercial_digest=?
                   and r.opportunity_revision=o.revision
                   and not exists(select 1 from contract.preparation_request n where n.tenant_id=r.tenant_id and n.previous_request_id=r.preparation_request_id)
                   and ((r.responsibility_type='opportunity.opportunity' and r.responsibility_id=o.opportunity_id
                         and r.responsibility_revision=o.revision and r.owner_appointment_id=o.owner_appointment_id
                         and not exists(select 1 from opportunity.responsibility_handoff h where h.tenant_id=r.tenant_id and h.opportunity_id=r.opportunity_id))
                     or (r.responsibility_type='opportunity.responsibility_handoff' and exists(
                         select 1 from opportunity.responsibility_handoff h
                          where h.tenant_id=r.tenant_id and h.opportunity_id=r.opportunity_id
                            and h.responsibility_handoff_id=r.responsibility_id and h.revision=r.responsibility_revision
                            and h.to_appointment_id=r.owner_appointment_id
                            and not exists(select 1 from opportunity.responsibility_handoff n where n.tenant_id=h.tenant_id
                                           and n.prior_basis_type='opportunity.responsibility_handoff' and n.prior_basis_id=h.responsibility_handoff_id))))
                """,expected.tenantId(),decision,expected.opportunityId(),expected.customerConfirmationId(),HexFormat.of().parseHex(expected.commercialDigest()));var r=p.executeQuery()) {
            if(!r.next())throw new Unavailable();
            return new ContractPreparationSource.DirectAuthorization(expected,r.getObject(1,UUID.class),decision,instant(r,2),instant(r,3));
        }
    }

    private ContractPreparationSource accepted(Connection c,ContractPreparationSource.Basis expected,UUID response)throws SQLException {
        try(var p=prepare(c,"""
                select i.quote_issue_id from opportunity.quote_issue i
                  join opportunity.quote_response r on r.tenant_id=i.tenant_id and r.quote_issue_id=i.quote_issue_id
                  join opportunity.quote_revision q on q.tenant_id=i.tenant_id and q.quote_revision_id=i.quote_revision_id
                 where r.tenant_id=? and r.quote_response_id=? and q.opportunity_id=? for update of i
                """,expected.tenantId(),response,expected.opportunityId());var r=p.executeQuery()) {
            if(!r.next())throw new Unavailable();
        }
        try(var p=prepare(c,"""
                select q.quote_revision_id,i.quote_issue_id,b.material_version_id,i.issued_at,r.received_at,
                       q.valid_until,r.created_at,p.body_ciphertext,p.body_digest,q.content_digest,
                       m.evidence_binding_id,dm.evidence_binding_id
                  from opportunity.quote_response r
                  join opportunity.quote_issue i on i.tenant_id=r.tenant_id and i.quote_issue_id=r.quote_issue_id
                  join opportunity.quote_revision q on q.tenant_id=i.tenant_id and q.quote_revision_id=i.quote_revision_id
                  join opportunity.quote_package_basis p on p.tenant_id=q.tenant_id and p.quote_revision_id=q.quote_revision_id
                  join opportunity.quote_response_basis b on b.tenant_id=r.tenant_id and b.quote_response_id=r.quote_response_id
                  join opportunity.material_version m on m.tenant_id=b.tenant_id and m.material_version_id=b.material_version_id
                  join opportunity.quote_manual_delivery d on d.tenant_id=i.tenant_id and d.quote_manual_delivery_id=i.delivery_fact_id
                  join opportunity.material_version dm on dm.tenant_id=d.tenant_id and dm.material_version_id=d.material_version_id
                  join opportunity.contract_preparation_source s on s.tenant_id=r.tenant_id and s.quote_response_id=r.quote_response_id
                 where r.tenant_id=? and r.quote_response_id=? and r.response_code='ACCEPTED'
                   and q.opportunity_id=? and p.customer_confirmation_id=?
                   and q.package_contract_code='R2_QUOTE_PACKAGE_V1' and q.package_contract_version=1
                   and i.issue_status_code='ACTIVE' and s.source_kind='ACCEPTED_QUOTE' and s.opportunity_id=q.opportunity_id
                   and m.opportunity_id=q.opportunity_id and m.evidence_submission_id=r.evidence_submission_id
                   and i.delivery_fact_type='opportunity.quote_manual_delivery' and i.delivery_fact_revision=0
                   and d.quote_revision_id=q.quote_revision_id and d.occurred_at=i.issued_at and dm.opportunity_id=q.opportunity_id
                   and not exists(select 1 from opportunity.quote_issue n where n.tenant_id=i.tenant_id and n.replaces_quote_issue_id=i.quote_issue_id)
                   and not exists(select 1 from opportunity.quote_response n where n.tenant_id=r.tenant_id and n.quote_issue_id=r.quote_issue_id and n.response_no>r.response_no)
                   and not exists(select 1 from opportunity.quote_revision n where n.tenant_id=q.tenant_id and n.predecessor_quote_revision_id=q.quote_revision_id)
                """,expected.tenantId(),response,expected.opportunityId(),expected.customerConfirmationId());var r=p.executeQuery()) {
            if(!r.next())throw new Unavailable();
            // Shared, stable-order locks prevent either proof being revoked between validation and write.
            for(UUID binding:new TreeSet<>(List.of(r.getObject(11,UUID.class),r.getObject(12,UUID.class)))) {
                try(var lock=prepare(c,"select revoked_at from evidence.evidence_binding where tenant_id=? and evidence_binding_id=? for share",expected.tenantId(),binding);var proof=lock.executeQuery()) {
                    if(!proof.next()||proof.getObject(1)!=null)throw new Unavailable();
                }
            }
            UUID quote=r.getObject(1,UUID.class);Instant validUntil=instant(r,6);
            String clear=body.decrypt(expected.tenantId(),expected.opportunityId(),quote,r.getBytes(8));
            byte[] digest=ContractCanonicalJson.digest(clear);
            if(!MessageDigest.isEqual(digest,r.getBytes(9))||!MessageDigest.isEqual(digest,r.getBytes(10)))
                throw new IllegalStateException("Contract quote source integrity failure");
            var terms=body.commercial(clear,expected,validUntil);
            if(!terms.digest().equals(expected.commercialDigest()))throw new Unavailable();
            try {
                return new ContractPreparationSource.AcceptedQuote(expected,quote,r.getObject(2,UUID.class),response,
                        r.getObject(3,UUID.class),instant(r,4),instant(r,5),validUntil,instant(r,7));
            } catch(IllegalArgumentException invalid) { throw new Unavailable(); }
        }
    }

    private static Instant instant(ResultSet r,int column)throws SQLException {
        Timestamp value=r.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private static PreparedStatement prepare(Connection c,String sql,Object... args)throws SQLException {
        var p=c.prepareStatement(sql);
        try { for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p; }
        catch(SQLException failure) { p.close();throw failure; }
    }
}
