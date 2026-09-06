package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.execution.SensitiveReadRuntime;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class CurrentWorkCardDisclosureIT extends WorkcardTestFixture {
    // Catches exception-wrapper-dependent retry semantics at the real JDBC read boundary.
    @ParameterizedTest
    @CsvSource({"42P01,false,500", "42P01,true,500", "22012,false,500", "22012,true,500",
        "25001,false,500", "25001,true,500", "08003,false,503", "08003,true,503",
        "55P03,false,503", "55P03,true,503"})
    void read_sql_failure_classification_is_independent_of_checked_or_wrapped_form(String state,boolean wrapped,int expectedStatus)throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        var runtime=new SensitiveReadRuntime(AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("SQL_CLASSIFICATION_IT"));
        var observedState=new AtomicReference<String>();
        try(var c=database.apiConnection();var blocker=database.adminConnection()) {
            try(var statement=blocker.createStatement()){statement.execute("select pg_advisory_lock(814735192)");}
            var failure=assertThrows(SensitiveReadRuntime.Failure.class,()->runtime.read(c,seed.request().actor(),UUID.randomUUID(),null,(connection,now)->{
                try {
                    if(state.equals("25001"))return inTransaction(connection,Capability.QUERY,ignored->{throw new AssertionError("Nested read must be rejected before callback");});
                    if(state.equals("08003"))connection.close();
                    try(var statement=connection.createStatement()) {
                        if(state.equals("55P03"))statement.execute("set local lock_timeout='10ms'");
                        statement.execute(state.equals("42P01")?"select * from pg_catalog.task5_missing_relation":state.equals("55P03")?"select pg_advisory_xact_lock(814735192)":"select 1/0");
                    }
                    throw new AssertionError("Real JDBC read must fail");
                } catch(SQLException sql) {
                    observedState.set(sql.getSQLState());
                    if(wrapped)throw new IllegalStateException("Synthetic read wrapper",sql);
                    throw sql;
                }
            }));
            assertEquals(state,observedState.get());
            assertEquals(expectedStatus,failure.status());
            assertEquals(expectedStatus==500?"INTERNAL_ERROR":"SERVICE_UNAVAILABLE",failure.code());
            assertNull(failure.getCause());assertEquals(0,auditCount());
        }
    }
    @Test void nested_transaction_initialization_is_safe_500_and_does_not_enter_disclosure()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        try(var c=database.apiConnection()) {
            c.setAutoCommit(false);
            var response=new CurrentWorkCardDisclosureService(protection,policies,"NESTED_READ_IT").read(c,seed.request().actor(),UUID.randomUUID(),null);
            assertEquals(500,response.status());assertEquals("INTERNAL_ERROR",response.errorCode());
            assertNull(response.body());assertNull(response.etag());assertEquals(0,auditCount());
            assertFalse(c.getAutoCommit());c.rollback();
        }
    }
    @Test void repeat_body_and_revalidation_commit_a_fresh_exact_audit_set_with_stable_etag()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        var service=new CurrentWorkCardDisclosureService(protection,policies,"DISCLOSURE_IT");
        try(var c=database.apiConnection()) {
            var first=service.read(c,seed.request().actor(),UUID.randomUUID(),null);assertNotNull(first);assertEquals(200,first.status());assertEquals(5,auditCount());
            var second=service.read(c,seed.request().actor(),UUID.randomUUID(),null);assertEquals(first.etag(),second.etag());assertEquals(10,auditCount());
            var cached=service.read(c,seed.request().actor(),UUID.randomUUID(),first.etag());assertEquals(304,cached.status());assertNull(cached.body());assertEquals(first.etag(),cached.etag());assertEquals(15,auditCount());
            for(String invalid:List.of("*","W/"+first.etag(),first.etag()+", "+first.etag(),"\"wb."+"A".repeat(43)+"\"")) {
                var response=service.read(c,seed.request().actor(),UUID.randomUUID(),invalid);assertEquals(200,response.status());assertNotNull(response.body());
            }
            assertEquals(35,auditCount());
        }
    }
    @Test void nth_real_audit_insert_failure_rolls_back_all_rows_and_suppresses_success()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        try(var admin=database.adminConnection();var sql=admin.createStatement()) {
            sql.execute("create function public.workcard_fail_nth() returns trigger language plpgsql as 'begin if NEW.subject_type = ''identity.principal'' then raise exception ''Synthetic audit failure''; end if; return NEW; end'");
            sql.execute("create trigger workcard_fail_nth before insert on audit.audit_entry for each row execute function public.workcard_fail_nth()");
            try(var c=database.apiConnection()) {
                var response=new CurrentWorkCardDisclosureService(protection,policies,"DISCLOSURE_IT").read(c,seed.request().actor(),UUID.randomUUID(),null);
                assertNotNull(response);assertEquals(503,response.status());assertNull(response.body());assertNull(response.etag());assertEquals(0,auditCount());
            } finally {sql.execute("drop trigger workcard_fail_nth on audit.audit_entry");sql.execute("drop function public.workcard_fail_nth()");}
        }
    }
    @Test void real_http_emits_no_success_byte_or_etag_until_every_audit_and_commit_acknowledgement()throws Exception {
        for(boolean cached:List.of(false,true)) {
            setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
            String tag=cached?readCard(null).etag():null;long before=auditCount();
            try(var connection=database.apiConnection()) {
                var probe=new ReadConnectionProbe(connection);probe.pauseAuditAt=3;probe.pauseCommit=true;
                var observed=new AtomicLong(-1);var failure=new AtomicReference<Throwable>();
                var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
                server.createContext("/api/v1/workcards/current",exchange->{
                    try {
                        var response=new CurrentWorkCardDisclosureService(protection,policies,"HTTP_TEST_BRIDGE").read(probe.connection(),seed.request().actor(),UUID.randomUUID(),exchange.getRequestHeaders().getFirst("If-None-Match"));
                        observed.set(auditCount());if(response.etag()!=null)exchange.getResponseHeaders().set("ETag",response.etag());
                        exchange.getResponseHeaders().set("Cache-Control",response.cacheControl());exchange.getResponseHeaders().set("Vary",response.vary());
                        byte[] bytes=response.body()==null?new byte[0]:CanonicalJson.encode(response.body()).getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(response.status(),bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();
                    } catch(Throwable t){failure.set(t);exchange.close();}
                });server.start();
                try(var socket=new Socket(InetAddress.getLoopbackAddress(),server.getAddress().getPort())) {
                    socket.setSoTimeout(150);socket.getOutputStream().write(("GET /api/v1/workcards/current HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"+(tag==null?"":"If-None-Match: "+tag+"\r\n")+"\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertTrue(probe.auditReached.await(10,TimeUnit.SECONDS));assertEquals(before,auditCount());
                    assertThrows(SocketTimeoutException.class,()->socket.getInputStream().read());
                    probe.auditContinue.countDown();assertTrue(probe.commitReached.await(10,TimeUnit.SECONDS));assertEquals(5,probe.inserts.get());assertEquals(before,auditCount());
                    assertThrows(SocketTimeoutException.class,()->socket.getInputStream().read());
                    probe.commitContinue.countDown();socket.setSoTimeout(10000);String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                    assertNull(failure.get());assertTrue(response.startsWith(cached?"HTTP/1.1 304":"HTTP/1.1 200"));assertTrue(response.toLowerCase(Locale.ROOT).contains("etag: \"wb."));
                    assertEquals(before+5,observed.get());assertEquals(before+5,auditCount());assertEquals(List.of("law_app_query","law_audit_append"),probe.roles);
                    if(cached)assertFalse(response.contains("currentCard"));else assertTrue(response.contains("张测试"));
                } finally {probe.release();server.stop(0);}
            }
        }
    }
    @Test void commit_succeeded_but_ack_lost_returns_safe_503_and_retry_adds_a_new_audit_set()throws Exception {
        for(String state:List.of("08006","XX000","42P01")) {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        try(var c=database.apiConnection()) {
            var probe=new ReadConnectionProbe(c);probe.loseCommitAck=true;probe.commitAckSqlState=state;
            var response=new CurrentWorkCardDisclosureService(protection,policies,"ACK_TEST").read(probe.connection(),seed.request().actor(),UUID.randomUUID(),null);
            assertEquals(503,response.status());assertEquals("SERVICE_UNAVAILABLE",response.errorCode());assertNull(response.body());assertNull(response.etag());assertEquals(5,auditCount());
        }
        assertEquals(200,readCard(null).status());assertEquals(10,auditCount());
        }
    }
    @Test void audit_subjects_and_bound_authorization_summaries_are_exact_and_read_has_no_command_side_effects()throws Exception {
        setupCard(TaskFactory.Type.ASSIGN_LEAD);UUID correlation=UUID.randomUUID();
        try(var c=database.apiConnection()) {
            var response=new CurrentWorkCardDisclosureService(protection,policies,"AUDIT_SHAPE_IT").read(c,seed.request().actor(),correlation,null);assertEquals(200,response.status());
        }
        try(var c=database.adminConnection();var p=c.prepareStatement("select subject_type,subject_id,subject_revision,subject_hash,command_id,command_type,entry_type,action_code,result_code,summary_schema_code,summary_schema_version,service_role_code,correlation_id,trace_id,change_summary::text,authorization_fact_type,authorization_fact_id,authorization_fact_revision,authorization_path_code from audit.audit_entry where tenant_id=?")) {
            p.setObject(1,seed.tenant());var actual=new HashMap<String,Set<UUID>>();
            try(var r=p.executeQuery()){while(r.next()) {
                actual.computeIfAbsent(r.getString(1),k->new HashSet<>()).add(r.getObject(2,UUID.class));assertEquals(0L,r.getLong(3));assertNull(r.getBytes(4));assertNull(r.getObject(5));assertNull(r.getObject(6));
                assertEquals("EVENT",r.getString(7));assertEquals("READ_CURRENT_WORKCARD",r.getString(8));assertEquals("SUCCEEDED",r.getString(9));assertEquals("R1_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",r.getString(10));assertEquals(1,r.getInt(11));assertEquals("API",r.getString(12));assertEquals(correlation,r.getObject(13,UUID.class));assertEquals(correlation,r.getObject(14,UUID.class));
                String summary=r.getString(15);assertTrue(summary.contains("authorizationAnchor"));assertTrue(summary.contains("disclosedSource"));assertFalse(summary.contains("张测试"));assertFalse(summary.contains("李销售"));assertFalse(summary.contains("+12025550123"));
                assertEquals("identity.authority_grant",r.getString(16));assertEquals(seed.grant(),r.getObject(17,UUID.class));assertEquals(0,r.getLong(18));assertEquals("DIRECT",r.getString(19));
            }}
            assertEquals(Map.of("responsibility.task_occurrence",Set.of(current.selector().id()),"lead.lead",Set.of(current.lead().id()),"identity.appointment",Set.of(seed.appointment(),secondaryAppointment),"identity.principal",Set.of(seed.principal(),secondaryPrincipal),"identity.organization_unit",Set.of(seed.org(),secondaryOrganization)),actual);
            for(String table:List.of("execution.command_execution_slot","execution.command_receipt","execution.domain_event","execution.domain_event_outbox"))try(var q=c.prepareStatement("select count(*) from "+table+" where tenant_id=?")){q.setObject(1,seed.tenant());try(var r=q.executeQuery()){r.next();assertEquals(0,r.getLong(1));}}
        }
    }
}
