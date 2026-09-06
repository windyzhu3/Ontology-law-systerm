package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ContactTimingIT extends ContactFlowFixture {
    @Test void accelerated_business_clock_cannot_keep_real_time_expired_authority_alive_at_final_check()throws Exception{
        setupContact();var command=prepare(contact("CONNECTED_VALID"));
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day',clock_timestamp()+interval '2 seconds','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
        var real=AuthorizationService.databaseBacked();var paused=new AtomicBoolean();
        AuthorizationService delayed=new AuthorizationService(){
            public AuthorizationSnapshot evaluate(Connection c,Request r,boolean last)throws SQLException{
                if(last&&paused.compareAndSet(false,true)){try{Thread.sleep(2200);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new SQLException(ex);}}
                return real.evaluate(c,r,last);
            }
            public void lockForMutation(Connection c,UUID t)throws SQLException{real.lockForMutation(c,t);}public void lockForEvaluation(Connection c,UUID t)throws SQLException{real.lockForEvaluation(c,t);}
        };
        runtime=new CommandRuntime(new ContactCommands(policies,protection,()->businessAt).handlers(),delayed,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("REAL_TIME_EXPIRY_IT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        var before=counts();var outcome=execute(command);assertTrue(paused.get());assertEquals(CommandOutcome.Status.REJECTED,outcome.status());assertEquals("NOT_AUTHORIZED",outcome.rejectionCode());delta(before,0,0,0,0,0,0,1,1,1,0,0);
    }
}
