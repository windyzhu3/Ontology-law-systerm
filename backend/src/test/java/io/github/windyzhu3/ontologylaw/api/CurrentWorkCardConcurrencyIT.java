package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CurrentWorkCardConcurrencyIT extends WorkcardTestFixture {
    private static int pid(Connection c)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid()")){r.next();return r.getInt(1);}}
    private static void blocked(Connection c,int pid)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){try(var p=c.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")){p.setInt(1,pid);try(var r=p.executeQuery()){r.next();if(r.getBoolean(1))return;}}Thread.sleep(5);}
        fail("Expected a blocked real advisory lock");
    }
    @Test void final_identity_contention_regenerates_authority_labels_and_organization_or_rejects()throws Exception {
        for(String change:List.of("REVOKE","EXPIRE","DENY","LABEL","REPARENT")) {
            setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
            if(change.equals("EXPIRE")) {
                mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
                UUID expiring=UUID.randomUUID();mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day',clock_timestamp()+interval '5 seconds','ACTIVE',clock_timestamp())",seed.tenant(),expiring,seed.appointment(),seed.appointment(),seed.org());
                seed=new AuthorizationServiceIT.Seed(seed.tenant(),seed.principal(),seed.appointment(),seed.org(),expiring,seed.subject());
            }
            String oldTag=readCard(null).etag();long before=auditCount();
            try(var reader=database.apiConnection();var writer=database.apiConnection();var observer=database.adminConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                int readerPid=pid(reader);var holder=new java.util.concurrent.atomic.AtomicReference<Future<CurrentWorkCardDisclosureService.Response>>();
                inTransaction(writer,Capability.COMMAND,c->{
                    AuthorizationService.databaseBacked().lockForMutation(c,seed.tenant());
                    holder.set(executor.submit(()->new CurrentWorkCardDisclosureService(protection,policies,"CONTENDED_IDENTITY_IT").read(reader,seed.request().actor(),UUID.randomUUID(),oldTag)));
                    try{blocked(observer,readerPid);}catch(Exception e){throw new SQLException("Read did not wait for identity",e);}
                    switch(change) {
                        case "REVOKE" -> sql(c,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
                        case "EXPIRE" -> {boolean expired=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!expired&&System.nanoTime()<deadline){try(var p=c.prepareStatement("select clock_timestamp()>=valid_until from identity.authority_grant where tenant_id=? and authority_grant_id=?")){p.setObject(1,seed.tenant());p.setObject(2,seed.grant());try(var r=p.executeQuery()){r.next();expired=r.getBoolean(1);}}}assertTrue(expired);}
                        case "DENY" -> sql(c,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),current.selector().id());
                        case "LABEL" -> sql(c,"update identity.principal set display_name='最新负责人',revision=revision+1 where tenant_id=? and principal_id=?",seed.tenant(),seed.principal());
                        case "REPARENT" -> {UUID parent=UUID.randomUUID();sql(c,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'NEW_ROOT','新上级','ACTIVE',clock_timestamp())",seed.tenant(),parent);sql(c,"update identity.organization_unit set parent_organization_unit_id=?,revision=revision+1 where tenant_id=? and organization_unit_id=?",parent,seed.tenant(),seed.org());}
                    }
                    return null;
                });
                var response=holder.get().get(10,TimeUnit.SECONDS);
                if(Set.of("REVOKE","EXPIRE").contains(change)){assertEquals(403,response.status());assertNull(response.etag());assertNull(response.body());assertEquals(before,auditCount());}
                else {assertEquals(200,response.status());assertNotEquals(oldTag,response.etag());if(change.equals("DENY")){assertNull(response.body().get("currentCard"));assertEquals(before,auditCount());}
                    else {assertEquals(before+5,auditCount());if(change.equals("LABEL"))assertEquals("最新负责人",((Map<?,?>)((Map<?,?>)response.body().get("currentCard")).get("owner")).get("displayName"));
                        try(var p=observer.prepareStatement("select subject_revision from audit.audit_entry where tenant_id=? and subject_type=? order by trusted_at desc limit 1")){p.setObject(1,seed.tenant());p.setString(2,change.equals("LABEL")?"identity.principal":"identity.organization_unit");try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getLong(1));}}
                    }
                }
            }
        }
    }
    @Test void tenant_fence_keeps_missing_draft_update_confirm_completion_and_candidate_versions_with_their_audit()throws Exception {
        for(String change:List.of("FIRST_SAVE","UPDATE","CONFIRM","COMPLETE","LEAD_CHANGE","CANDIDATE_CHANGE")) {
            setupCard(change.equals("CANDIDATE_CHANGE")?TaskFactory.Type.RESOLVE_LEAD_DUPLICATE:TaskFactory.Type.COMPLETE_LEAD_INGRESS);
            var values=Map.<String,Object>of("phone","+12025550124","sourceCode","OWNER_CONFIRMED","sourceSummary","原始草稿");
            if(Set.of("UPDATE","CONFIRM","COMPLETE").contains(change))saveDraft(values,false);
            try(var reader=database.apiConnection();var writer=database.apiConnection();var observer=database.adminConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var probe=new ReadConnectionProbe(reader);probe.pauseCommit=true;int writerPid=pid(writer);
                var read=executor.submit(()->new CurrentWorkCardDisclosureService(protection,policies,"BUSINESS_FENCE_IT").read(probe.connection(),seed.request().actor(),UUID.randomUUID(),null));
                assertTrue(probe.commitReached.await(10,TimeUnit.SECONDS));assertEquals(0,auditCount());
                var write=executor.submit(()->inTransaction(writer,Capability.COMMAND,c->{
                    R1BusinessFence.databaseBacked().exclusive(c,seed.tenant());var now=LeadIngressService.databaseBacked(protection).now(c);var drafts=ActionDraftService.databaseBacked();var draft=drafts.read(c,seed.tenant(),current.selector().id());
                    switch(change) {
                        case "FIRST_SAVE","UPDATE" -> drafts.save(c,seed.tenant(),current,draft,Map.of("phone","+12025550125","sourceCode","OWNER_CONFIRMED","sourceSummary","新草稿"),seed.appointment(),now);
                        case "CONFIRM","COMPLETE" -> {drafts.confirm(c,seed.tenant(),current,new ActionDraftService.Confirmation(draft.selector().id(),draft.selector().revision(),draft.digest()),draft.values(),seed.appointment(),now);
                            if(change.equals("COMPLETE")){var leads=LeadIngressService.databaseBacked(protection);var updated=leads.update(c,seed.tenant(),leads.read(c,seed.tenant(),current.lead().id()),null,null,values,seed.appointment(),null,now);TaskFactory.databaseBacked().complete(c,seed.tenant(),current,updated.selector(),now);}}
                        case "LEAD_CHANGE" -> {var leads=LeadIngressService.databaseBacked(protection);leads.update(c,seed.tenant(),leads.read(c,seed.tenant(),current.lead().id()),null,null,values,seed.appointment(),null,now);}
                        case "CANDIDATE_CHANGE" -> sql(c,"update party.party set canonical_name='新候选名称',revision=revision+1 where tenant_id=? and party_id=?",seed.tenant(),secondaryParty);
                    }return null;
                }));
                try {
                    blocked(observer,writerPid);assertFalse(write.isDone());probe.commitContinue.countDown();var old=read.get(10,TimeUnit.SECONDS);assertEquals(200,old.status());
                    var oldCard=(Map<?,?>)old.body().get("currentCard");assertEquals(0L,((Map<?,?>)oldCard.get("subject")).get("subjectRevision"));
                    if(change.equals("FIRST_SAVE"))assertNull(oldCard.get("actionDraft"));
                    int oldCount=change.equals("CANDIDATE_CHANGE")?7:Set.of("UPDATE","CONFIRM","COMPLETE").contains(change)?6:5;assertEquals(oldCount,auditCount());
                    write.get(10,TimeUnit.SECONDS);var fresh=readCard(old.etag());assertEquals(200,fresh.status());assertNotEquals(old.etag(),fresh.etag());
                    if(change.equals("COMPLETE")){assertNull(fresh.body().get("currentCard"));assertEquals(oldCount,auditCount());}
                    else {var card=(Map<?,?>)fresh.body().get("currentCard");assertNotNull(card);
                        if(Set.of("FIRST_SAVE","UPDATE").contains(change))assertEquals("新草稿",((Map<?,?>)((Map<?,?>)card.get("actionDraft")).get("values")).get("sourceSummary"));
                        if(change.equals("CONFIRM"))assertEquals(false,((Map<?,?>)card.get("actionDraft")).get("editable"));
                        if(change.equals("LEAD_CHANGE"))assertEquals(1L,((Map<?,?>)card.get("subject")).get("subjectRevision"));
                        if(change.equals("CANDIDATE_CHANGE"))assertEquals(1L,((Map<?,?>)((Map<?,?>)card.get("commandForm")).get("values")).get("partyRevision"));
                        assertEquals(oldCount+(change.equals("CANDIDATE_CHANGE")?7:change.equals("LEAD_CHANGE")?5:6),auditCount());
                    }
                } finally {probe.release();}
            }
        }
    }
    @Test void tenant_lock_timeout_returns_503_without_body_etag_or_audit()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        try(var writer=database.apiConnection();var reader=database.apiConnection()) {
            try(var s=reader.createStatement()){s.execute("set lock_timeout='100ms'");}
            inTransaction(writer,Capability.COMMAND,c->{R1BusinessFence.databaseBacked().exclusive(c,seed.tenant());
                var response=new CurrentWorkCardDisclosureService(protection,policies,"LOCK_TIMEOUT_IT").read(reader,seed.request().actor(),UUID.randomUUID(),null);
                assertEquals(503,response.status());assertNull(response.body());assertNull(response.etag());return null;});
            assertEquals(0,auditCount());
        }
    }
}
