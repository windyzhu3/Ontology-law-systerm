package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Source verification inside the future command transaction, not an HTTP authorization test. */
class R2ContractDirectSourceIT extends R2ContractPreparationPersistenceIT {
    ContractPreparationSource.Basis basis() {
        return new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,"ab".repeat(32));
    }
    ContractPreparationSource resolve(UUID decision,ContractPreparationSource.Basis expected)throws Exception {
        try(var c=database.apiConnection()) {
            return inTransaction(c,Capability.COMMAND,x->R2ContractPreparationSources.create(cipher)
                    .resolve(x,expected,new ContractPreparationSources.DirectDecision(decision)));
        }
    }
    @Test void resolves_only_current_approved_exact_basis_without_creating_contract()throws Exception {
        initialize();var req=request(null);var dec=decision(req,"APPROVED");
        var result=assertInstanceOf(ContractPreparationSource.DirectAuthorization.class,resolve(dec,basis()));
        assertEquals(req,result.requestId());assertEquals(dec,result.decisionId());assertEquals(basis(),result.basis());
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(UUID.randomUUID(),basis()));
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),confirmation,"cd".repeat(32))));
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,new ContractPreparationSource.Basis(seed.tenant(),opportunity.id(),UUID.randomUUID(),"ab".repeat(32))));
        assertEquals("0",scalar("select count(*) from contract.contract where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
    }
    @Test void successor_request_and_returned_decision_cannot_reuse_old_approval()throws Exception {
        initialize();var req=request(null);var old=decision(req,"APPROVED");
        var next=request(req);var returned=decision(next,"RETURNED");
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(old,basis()));
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(returned,basis()));
    }
    @Test void different_tenant_and_changed_customer_cannot_reuse_approval()throws Exception {
        initialize();var dec=decision(request(null),"APPROVED");
        var draft=execute(command(false,draftFact,confirmationFact,canonical(confirmationFact)));
        var changed=execute(command(true,draft.resultFact(),confirmationFact,null));
        assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,changed.status());
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,basis()));
        initialize();assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,basis()));
    }
    @Test void requires_live_read_committed_transaction()throws Exception {
        initialize();var dec=decision(request(null),"APPROVED");var sources=R2ContractPreparationSources.create(cipher);
        try(var c=database.apiConnection()) {
            assertThrows(IllegalStateException.class,()->sources.resolve(c,basis(),new ContractPreparationSources.DirectDecision(dec)));
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            assertThrows(IllegalStateException.class,()->sources.resolve(c,basis(),new ContractPreparationSources.DirectDecision(dec)));
            c.rollback();
        }
    }
    @Test void changed_party_requires_fresh_customer_confirmation()throws Exception {
        initialize();var dec=decision(request(null),"APPROVED");
        var party=client(canonical(confirmationFact));
        mutate("update party.party set canonical_name='主体资料更新',revision=revision+1 where tenant_id=? and party_id=?",seed.tenant(),party.id());
        assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,basis()));
    }
    @Test void lock_wait_cannot_extend_authorization_validity()throws Exception {
        initialize();var req=request(null);var dec=UUID.randomUUID();
        mutate("insert into contract.preparation_decision(tenant_id,preparation_decision_id,revision,preparation_request_id,decision_code,decided_by_appointment_id,body_ciphertext,body_digest,effective_until,created_at) values(?,?,0,?,'APPROVED',?,decode(repeat('00',29),'hex'),decode(repeat('ab',32),'hex'),clock_timestamp()+interval '1 second',clock_timestamp())",seed.tenant(),dec,req,seed.appointment());
        var expected=basis();
        try(var blocker=database.apiConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(var s=blocker.createStatement()) { s.execute("SET LOCAL ROLE law_app_command"); }
            try(var p=blocker.prepareStatement("select opportunity_id from opportunity.opportunity where tenant_id=? and opportunity_id=? for update")) {
                p.setObject(1,seed.tenant());p.setObject(2,opportunity.id());try(var rows=p.executeQuery()){assertTrue(rows.next());}
            }
            var started=new java.util.concurrent.CountDownLatch(1);
            var waiting=pool.submit(()->{started.countDown();return assertThrows(ContractPreparationSources.Unavailable.class,()->resolve(dec,expected));});
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try(var p=blocker.prepareStatement("select pg_sleep(greatest(0,extract(epoch from effective_until-clock_timestamp()))+0.02) from contract.preparation_decision where tenant_id=? and preparation_decision_id=?")) {
                p.setObject(1,seed.tenant());p.setObject(2,dec);p.execute();
            }
            blocker.commit();assertNotNull(waiting.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
