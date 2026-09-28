package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

public final class JdbcContractPreparationRepository implements ContractPreparationRepository {
    private final ContractProtection protection;
    private final ContractPreparationSources sources;
    private final Codec codec;
    public JdbcContractPreparationRepository(ContractProtection protection,ContractPreparationSources sources,Codec codec) {
        this.protection=Objects.requireNonNull(protection);this.sources=Objects.requireNonNull(sources);this.codec=Objects.requireNonNull(codec);
    }
    private static void transaction(Connection c)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Preparation requires a READ COMMITTED transaction");
    }
    public Subject request(Connection c,UUID tenant,Basis basis,UUID previous,ContractVersionInput.CommercialTerms commercial,String reason)throws SQLException {
        transaction(c);Objects.requireNonNull(commercial);var comment=new Comment(reason);
        UUID id=id(c);String clear=body(Map.of("commercial",commercial.canonical(),"reason",comment.value()));
        execute(c,"""
                insert into contract.preparation_request(tenant_id,preparation_request_id,revision,
                opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,
                owner_appointment_id,customer_confirmation_id,commercial_digest,previous_request_id,body_ciphertext,body_digest,created_at)
                values(?,?,0,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())
                """,tenant,id,basis.opportunity().id(),basis.opportunity().revision(),basis.responsibility().type(),basis.responsibility().id(),basis.responsibility().revision(),basis.owner(),basis.customerConfirmation(),HexFormat.of().parseHex(commercial.digest()),previous,
                protection.seal(tenant,basis.opportunity().id(),id,ContractProtection.Kind.REQUEST,clear),ContractCanonicalJson.digest(clear));
        return exact("contract.preparation_request",id);
    }
    public Subject decide(Connection c,UUID tenant,UUID request,UUID actor,boolean approved,Instant until,String reason)throws SQLException {
        transaction(c);Objects.requireNonNull(actor);var comment=new Comment(reason);
        if(!approved&&until!=null||until!=null&&until.getNano()%1000!=0)throw new IllegalArgumentException("Exact authorization interval required");
        UUID opportunity;
        try(var p=prepare(c,"select opportunity_id from contract.preparation_request where tenant_id=? and preparation_request_id=?",tenant,request);var r=p.executeQuery()) {
            if(!r.next())throw new ContractPreparationSources.Unavailable();opportunity=r.getObject(1,UUID.class);
        }
        UUID id=id(c);String clear=body(Map.of("reason",comment.value()));
        execute(c,"""
                insert into contract.preparation_decision(tenant_id,preparation_decision_id,revision,preparation_request_id,
                decision_code,decided_by_appointment_id,body_ciphertext,body_digest,effective_until,created_at)
                values(?,?,0,?,?,?,?,?,?,clock_timestamp())
                """,tenant,id,request,approved?"APPROVED":"RETURNED",actor,
                protection.seal(tenant,opportunity,id,ContractProtection.Kind.DECISION,clear),ContractCanonicalJson.digest(clear),until);
        return exact("contract.preparation_decision",id);
    }
    public Subject saveDraft(Connection c,UUID tenant,Basis basis,ContractPreparationSources.Selection selection,UUID previous,ContractVersionInput.CommercialTerms commercial,Map<String,Object> preparation)throws SQLException {
        transaction(c);Objects.requireNonNull(commercial);Objects.requireNonNull(preparation);
        var expected=new ContractPreparationSource.Basis(tenant,basis.opportunity().id(),basis.customerConfirmation(),commercial.digest());
        var source=sources.resolve(c,expected,selection);
        UUID quote=null,direct=null;
        switch(source) {
            case ContractPreparationSource.AcceptedQuote accepted -> quote=accepted.responseId();
            case ContractPreparationSource.DirectAuthorization authorized -> direct=authorized.decisionId();
        }
        UUID id=id(c);String clear=body(Map.of("commercial",commercial.canonical(),"preparation",preparation,"source",source.canonical()));
        execute(c,"""
                insert into contract.preparation_draft(tenant_id,preparation_draft_id,revision,
                opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,
                owner_appointment_id,customer_confirmation_id,commercial_digest,previous_draft_id,source_quote_response_id,
                source_direct_decision_id,body_ciphertext,body_digest,created_at)
                values(?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())
                """,tenant,id,basis.opportunity().id(),basis.opportunity().revision(),basis.responsibility().type(),basis.responsibility().id(),basis.responsibility().revision(),basis.owner(),basis.customerConfirmation(),HexFormat.of().parseHex(commercial.digest()),previous,quote,direct,
                protection.seal(tenant,basis.opportunity().id(),id,ContractProtection.Kind.DRAFT,clear),ContractCanonicalJson.digest(clear));
        // Saving may involve lock waits in triggers; never extend an authorization using an old clock.
        sources.resolve(c,expected,selection);
        return exact("contract.preparation_draft",id);
    }
    @SuppressWarnings("unchecked") public Draft readDraft(Connection c,UUID tenant,UUID id)throws SQLException {
        try(var p=prepare(c,"select * from contract.preparation_draft where tenant_id=? and preparation_draft_id=?",tenant,id);var r=p.executeQuery()) {
            if(!r.next())return null;
            UUID opportunity=r.getObject("opportunity_id",UUID.class);
            String clear=protection.open(tenant,opportunity,id,ContractProtection.Kind.DRAFT,r.getBytes("body_ciphertext"));
            if(!MessageDigest.isEqual(ContractCanonicalJson.digest(clear),r.getBytes("body_digest")))throw new IllegalStateException("Contract draft integrity failure");
            var doc=codec.decode(clear);
            var basis=new Basis(new Subject("opportunity.opportunity",opportunity,r.getLong("opportunity_revision"),null),new Subject(r.getString("responsibility_type"),r.getObject("responsibility_id",UUID.class),r.getLong("responsibility_revision"),null),r.getObject("owner_appointment_id",UUID.class),r.getObject("customer_confirmation_id",UUID.class));
            return new Draft(exact("contract.preparation_draft",id),basis,r.getObject("previous_draft_id",UUID.class),HexFormat.of().formatHex(r.getBytes("commercial_digest")),(Map<String,Object>)doc.get("commercial"),(Map<String,Object>)doc.get("preparation"),r.getTimestamp("created_at").toInstant());
        }
    }
    private String body(Map<String,Object> values) {
        String canonical=ContractCanonicalJson.encode(values);
        String encoded=codec.encode(values);
        if(!canonical.equals(encoded))throw new IllegalArgumentException("Canonical contract body required");
        return canonical;
    }
    private static Subject exact(String type,UUID id){return new Subject(type,id,0L,null);}
    private static UUID id(Connection c)throws SQLException {try(var p=c.prepareStatement("select uuidv7()");var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}}
    private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException {
        var p=c.prepareStatement(sql);try{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i] instanceof Instant t?t.atOffset(ZoneOffset.UTC):args[i]);return p;}catch(SQLException e){p.close();throw e;}
    }
    private static void execute(Connection c,String sql,Object...args)throws SQLException{try(var p=prepare(c,sql,args)){if(p.executeUpdate()!=1)throw new IllegalStateException("Contract preparation write did not persist");}}
}
