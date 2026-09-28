package io.github.windyzhu3.ontologylaw.api;

import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Real database source constraints; no claim that direct approval commands are activated. */
class R2ContractPreparationPersistenceIT extends R2CustomerRequirementsIT {
    UUID confirmation;
    io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject confirmationFact,draftFact;
    void initialize()throws Exception{setup(true,false);var d=save(doc());var result=execute(command(true,d.resultFact(),null,null));assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,result.status());confirmation=result.resultFact().id();confirmationFact=result.resultFact();draftFact=d.resultFact();}
    UUID request(UUID previous)throws Exception{UUID id=UUID.randomUUID();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{insertRequest(x,id,previous);return null;});}return id;}
    void insertRequest(Connection c,UUID id,UUID previous)throws SQLException{
        sql(c,"insert into contract.preparation_request(tenant_id,preparation_request_id,revision,opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,owner_appointment_id,customer_confirmation_id,commercial_digest,previous_request_id,body_ciphertext,body_digest,created_at) select tenant_id,?,0,opportunity_id,revision,'opportunity.opportunity',opportunity_id,revision,owner_appointment_id,?,decode(repeat('ab',32),'hex'),?,decode(repeat('00',29),'hex'),decode(repeat('cd',32),'hex'),clock_timestamp() from opportunity.opportunity where tenant_id=? and opportunity_id=?",id,confirmation,previous,seed.tenant(),opportunity.id());
    }
    UUID decision(UUID request,String code)throws Exception{var id=UUID.randomUUID();mutate("insert into contract.preparation_decision(tenant_id,preparation_decision_id,revision,preparation_request_id,decision_code,decided_by_appointment_id,body_ciphertext,body_digest,created_at) values(?,?,0,?,?,?,decode(repeat('00',29),'hex'),decode(repeat('ab',32),'hex'),'2000-01-01')",seed.tenant(),id,request,code,seed.appointment());return id;}
    @Test void source_decision_is_immutable_unique_and_does_not_fabricate_quote_or_contract()throws Exception{
        initialize();var r=request(null);var d=decision(r,"APPROVED");
        assertEquals("true",scalar("select (effective_from=created_at and created_at>'2026-01-01')::text from contract.preparation_decision where tenant_id=? and preparation_decision_id=?",seed.tenant(),d));
        assertThrows(Exception.class,()->decision(r,"RETURNED"));
        assertThrows(Exception.class,()->mutate("update contract.preparation_decision set decision_code='RETURNED' where tenant_id=? and preparation_decision_id=?",seed.tenant(),d));
        assertThrows(Exception.class,()->mutate("delete from contract.preparation_request where tenant_id=? and preparation_request_id=?",seed.tenant(),r));
        assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.contract where tenant_id=?",seed.tenant()));
    }
    @Test void request_chain_cannot_fork_and_old_pending_request_cannot_be_decided()throws Exception{
        initialize();var first=request(null);var next=request(first);
        assertThrows(Exception.class,()->request(first));
        assertThrows(Exception.class,()->request(null));
        assertThrows(Exception.class,()->decision(first,"APPROVED"));
        var d=decision(next,"RETURNED");
        assertEquals("true",scalar("select (effective_from is null and effective_until is null)::text from contract.preparation_decision where tenant_id=? and preparation_decision_id=?",seed.tenant(),d));
    }
    @Test void stale_customer_and_foreign_decision_source_are_rejected()throws Exception{
        initialize();var good=confirmation;confirmation=UUID.randomUUID();assertThrows(Exception.class,()->request(null));confirmation=good;
        var r=request(null);var current=canonical(confirmationFact);
        current.put("customerGoal","已确认的新服务需求");
        var draft=execute(command(false,draftFact,confirmationFact,current));
        assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,draft.status());
        var changed=execute(command(true,draft.resultFact(),confirmationFact,null));
        assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,changed.status());
        assertThrows(Exception.class,()->decision(r,"APPROVED"));
        assertThrows(Exception.class,()->decision(UUID.randomUUID(),"APPROVED"));
    }
    @Test void request_rollback_and_concurrent_first_requests_leave_one_source()throws Exception{
        initialize();try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{insertRequest(x,UUID.randomUUID(),null);throw new IllegalStateException("after insert failure");}));}
        assertEquals("0",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(()->{try{request(null);return true;}catch(Exception ex){return false;}},()->{try{request(null);return true;}catch(Exception ex){return false;}}));
            int successes=0;for(var result:results)if(result.get())successes++;assertEquals(1,successes);
        }
        assertEquals("1",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
    }
    @Test void different_tenant_cannot_decide_an_existing_request()throws Exception{
        initialize();var first=request(null);var oldTenant=seed.tenant();
        initialize();assertNotEquals(oldTenant,seed.tenant());
        assertThrows(Exception.class,()->decision(first,"APPROVED"));
        assertEquals("0",scalar("select count(*) from contract.preparation_decision where tenant_id=?",seed.tenant()));
    }
    @Test void root_unique_index_protects_even_a_stale_repeatable_read_snapshot()throws Exception{
        initialize();
        try(var first=database.apiConnection();var second=database.apiConnection()){
            for(var c:List.of(first,second)){c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try(var st=c.createStatement()){st.execute("SET LOCAL ROLE law_app_command");try(var rows=st.executeQuery("select count(*) from contract.preparation_request")){assertTrue(rows.next());}}}
            insertRequest(first,UUID.randomUUID(),null);first.commit();
            var error=assertThrows(SQLException.class,()->insertRequest(second,UUID.randomUUID(),null));
            assertEquals("23505",error.getSQLState());second.rollback();
        }
        assertEquals("1",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
    }
}
