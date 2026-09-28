package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import java.sql.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Exercises the production command/auth/audit composition, not permissive Owner ports. */
class R2ContractCommandIT extends R2CustomerRequirementsIT {
    ContractWorkflowService contracts;
    void initializeContract()throws Exception {
        setup(true,true);
        var draft=save(doc());var confirmation=execute(command(true,draft.resultFact(),null,null));
        assertEquals(CommandOutcome.Status.SUCCEEDED,confirmation.status(),confirmation.rejectionCode());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(String code:List.of("CONTRACT_READ","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE"))grant(x,code);
            return null;
        });}
        contracts=R2ContractServices.create(ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES")),cipher,null);
        runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"T08_COMMAND_IT",contracts);
    }
    Map<String,Object> contractContext()throws Exception {
        try(var c=database.apiConnection()){return new R2ContractReadService(contracts,AuditAppender.databaseBacked("T08_DISCLOSURE_IT")).read(c,seed.request().actor(),opportunity.id());}
    }
    CommandEnvelope request()throws Exception {
        var context=contractContext();var body=new LinkedHashMap<String,Object>();
        body.put("opportunityId",opportunity.id().toString());body.put("expectedOpportunityRevision",opportunity.revision());
        body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("customerConfirmation",context.get("customerConfirmation"));
        for(String field:List.of("expectedContract","expectedDraft","expectedVersion","expectedWorkflow"))body.put(field,null);
        var commercial=new LinkedHashMap<String,Object>();commercial.put("currency","CNY");commercial.put("scope","保密合成范围");commercial.put("lines",List.of(Map.of("description","合成费用","amountMinor",12000L,"discount",false)));commercial.put("conditionalFee",null);commercial.put("paymentTerms","合成付款说明");
        body.put("values",Map.of("commercial",commercial,"reason","保密合成申请原因"));
        return new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body);
    }
    CommandEnvelope nextContract(CommandEnvelope.Type type,Map<String,Object> values)throws Exception {
        var x=contractContext();var body=new LinkedHashMap<String,Object>();body.put("opportunityId",opportunity.id().toString());body.put("expectedOpportunityRevision",opportunity.revision());body.put("responsibilityBasis",x.get("responsibilityBasis"));body.put("customerConfirmation",x.get("customerConfirmation"));
        for(var pair:Map.of("expectedContract","contract","expectedDraft","draft","expectedWorkflow","workflow").entrySet()){var value=(Map<?,?>)x.get(pair.getValue());body.put(pair.getKey(),value==null?null:value.get("selector"));}
        var contract=(Map<?,?>)x.get("contract");body.put("expectedVersion",contract==null?null:contract.get("currentRevision"));body.put("values",values);return new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body);
    }
    @Test void contract_ledger_applies_server_filters_and_bounded_exclusive_position()throws Exception {
        initializeContract();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,Map.of("decision","APPROVED","reason","合成授权"))).status());
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.START_CONTRACT_PREPARATION,Map.of())).status());
        var reader=new R2ContractReadService(contracts,AuditAppender.databaseBacked("R25_LEDGER_IT"),new byte[32]);
        var actor=seed.request().actor();
        try(var c=database.apiConnection()) {
            var all=reader.ledger(c,actor,1,null,null,null);
            var row=(Map<?,?>)((List<?>)all.get("items")).getFirst();
            assertEquals(opportunity.id().toString(),row.get("opportunityId"));
            assertEquals(1,((List<?>)reader.ledger(c,actor,1,null,(String)row.get("customerLabel"),(String)row.get("stateLabel")).get("items")).size());
            assertEquals(List.of(),reader.ledger(c,actor,1,null,"not-the-customer",null).get("items"));
            assertEquals(List.of(),reader.ledger(c,actor,1,null,null,"待审批").get("items"));
            for(int limit:new int[]{0,101})assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,limit,null,null,null));
            assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,20,"forged",null,null));
            assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,20,null,"x".repeat(201),null));
            inTransaction(c,Capability.QUERY,x->{
                assertEquals(List.of(opportunity.id()),contracts.ledgerOpportunities(x,seed.tenant(),1,null));
                assertEquals(List.of(),contracts.ledgerOpportunities(x,seed.tenant(),1,opportunity.id()));
                assertEquals(List.of(),contracts.ledgerOpportunities(x,UUID.randomUUID(),1,null));
                assertThrows(IllegalArgumentException.class,()->contracts.ledgerOpportunities(x,seed.tenant(),101,null));
                return null;
            });
        }
    }
    @Test void contract_ledger_continues_past_denied_rows_and_binds_cursor_to_query_and_identity()throws Exception {
        initializeContract();var ids=new ArrayList<UUID>();
        for(int i=0;i<3;i++) {
            if(i>0)addContractOpportunity();
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,Map.of("decision","APPROVED","reason","合成授权"))).status());
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.START_CONTRACT_PREPARATION,Map.of())).status());
            ids.add(opportunity.id());
        }
        ids.sort(Comparator.comparing(UUID::toString));
        deny(new AuthorizationService.Subject("opportunity.opportunity",ids.getFirst(),0L,null),"CONTRACT_READ");
        var actor=seed.request().actor();byte[] key=new byte[32];
        var reader=new R2ContractReadService(contracts,AuditAppender.databaseBacked("R25_PAGES_IT"),key);
        try(var c=database.apiConnection()) {
            var first=reader.ledger(c,actor,1,null,null,null);
            assertEquals(ids.get(1).toString(),((Map<?,?>)((List<?>)first.get("items")).getFirst()).get("opportunityId"));
            var token=(String)first.get("nextCursor");assertNotNull(token);
            var second=reader.ledger(c,actor,1,token,null,null);
            assertEquals(ids.get(2).toString(),((Map<?,?>)((List<?>)second.get("items")).getFirst()).get("opportunityId"));
            assertNull(second.get("nextCursor"));
            assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,2,token,null,null));
            assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,1,token,"changed",null));
            assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,1,token,null,"待审批"));
            // Tokens for another identity must fail even with a valid authenticated payload.
            for(String field:List.of("tenant","principal","appointment")) {
                var scope=new TreeMap<String,Object>(Map.of("purpose","R25_CONTRACT_LEDGER_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"limit",1,"search","","state",""));
                scope.put(field,UUID.randomUUID().toString());
                var foreign=new R2ManagementCursor(key).encode(ids.getFirst(),CanonicalJson.encode(scope),java.time.Instant.now());
                var failure=assertThrows(R1ServiceReadRuntime.Failure.class,()->reader.ledger(c,actor,1,foreign,null,null));
                assertEquals("VALIDATION_FAILED",failure.getMessage());
            }
        }
    }
    @Test void ledger_batches_exact_candidate_reads_without_changing_authorized_rows()throws Exception {
        initializeContract();var ids=new ArrayList<UUID>();
        for(int i=0;i<3;i++){
            if(i>0)addContractOpportunity();
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,Map.of("decision","APPROVED","reason","合成授权"))).status());
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.START_CONTRACT_PREPARATION,Map.of())).status());ids.add(opportunity.id());
        }
        var reader=new R2ContractReadService(contracts,AuditAppender.databaseBacked("R25_BATCH_READ_IT"));
        try(var c=database.apiConnection()){
            var probe=new ReadConnectionProbe(c);var rows=(List<?>)reader.ledger(probe.connection(),seed.request().actor(),20,null,null,null).get("items");
            assertEquals(new HashSet<>(ids.stream().map(UUID::toString).toList()),rows.stream().map(v->((Map<?,?>)v).get("opportunityId")).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2,probe.statements.stream().filter(q->q.startsWith("select * from contract.contract where tenant_id=? and opportunity_id=?")).count(),"The two exact Contract root query shapes each use one bounded group round trip");
            assertEquals(1,probe.statements.stream().filter(q->q.startsWith("select 'contract.preparation_request' fact_type")).count(),"Repeated exact tenant/opportunity pairs keep their bound values in one grouped source read");
            assertEquals(1,probe.statements.stream().filter(q->q.startsWith("select party_id,party_revision,profile_version_id from opportunity.customer_requirement_participant")).count(),"Confirmation IDs discovered in a bounded Owner page are grouped without cross-tenant source reuse");
            assertTrue(rows.stream().allMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("canHandle"))));
        }
        deny(new AuthorizationService.Subject("opportunity.opportunity",ids.getFirst(),0L,null),"CONTRACT_READ");
        try(var c=database.apiConnection()){
            var rows=(List<?>)reader.ledger(c,seed.request().actor(),20,null,null,null).get("items");assertEquals(2,rows.size());assertTrue(rows.stream().noneMatch(v->ids.getFirst().toString().equals(((Map<?,?>)v).get("opportunityId"))));
        }
    }
    private void addContractOpportunity()throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var leads=io.github.windyzhu3.ontologylaw.lead.LeadIngressService.databaseBacked(protection);
            var lead=leads.capture(x,seed.tenant(),input(true),CanonicalJson.digest(UUID.randomUUID().toString()),businessAt);
            var assignment=leads.assign(x,seed.tenant(),lead,seed.appointment(),"MANUAL_SELECTION",businessAt);
            lead=leads.update(x,seed.tenant(),lead,null,null,null,seed.appointment(),assignment.selector().id(),businessAt);
            secondaryFact=assignment.selector();current=io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().create(x,seed.tenant(),io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD,seed.appointment(),lead.selector(),java.time.ZoneId.of("Asia/Shanghai"),businessAt);
            return null;
        });}
        var handlers=new ArrayList<CommandHandler>(new io.github.windyzhu3.ontologylaw.lead.LeadCommands(policies,protection).handlers());
        handlers.removeIf(h->h.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT||h.type()==CommandEnvelope.Type.REVIEW_LEAD_VALIDITY);
        handlers.addAll(new io.github.windyzhu3.ontologylaw.lead.ContactCommands(policies,protection).handlers());
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),AuditAppender.databaseBacked("R25_CONTACT_IT"),io.github.windyzhu3.ontologylaw.lead.R1AuthorizationReaders.databaseBacked(policies),io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked());
        var result=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector());}
        runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"R25_COMMAND_IT",contracts);
        var draft=save(doc());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command(true,draft.resultFact(),null,null)).status());
    }
    @Test void ledger_uses_list_projection_and_preserves_authorized_owner_deadline_and_deny()throws Exception {
        initializeContract();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(nextContract(CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,Map.of("decision","APPROVED","reason","合成授权"))).status());
        var started=execute(nextContract(CommandEnvelope.Type.START_CONTRACT_PREPARATION,Map.of()));assertEquals(CommandOutcome.Status.SUCCEEDED,started.status(),started.rejectionCode());
        var detail=contractContext();var workflow=(Map<?,?>)detail.get("workflow");
        var factReads=new java.util.concurrent.atomic.AtomicInteger();
        var thin=(ContractWorkflowService)java.lang.reflect.Proxy.newProxyInstance(ContractWorkflowService.class.getClassLoader(),new Class<?>[]{ContractWorkflowService.class},(proxy,method,args)->{
            if(method.getName().equals("protectedFacts"))factReads.incrementAndGet();
            if(method.getName().equals("context"))throw new AssertionError("Ledger must not load full contract detail");
            try{return method.invoke(contracts,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        var reader=new R2ContractReadService(thin,AuditAppender.databaseBacked("T08_LEDGER_IT"));
        try(var c=database.apiConnection()){var probe=new ReadConnectionProbe(c);var page=reader.ledger(probe.connection(),seed.request().actor());long locks=probe.statements.stream().filter(q->q.contains("pg_advisory_xact_lock_shared")).count();System.out.printf("LEDGER_SQL kind=contract count=%d locks=%d sqlMs=%.1f%n",probe.statements.size(),locks,probe.sqlNanos.sum()/1e6);assertEquals(2,locks,"One business fence and one identity lock per locked read");assertEquals(1,factReads.get(),"Business sources are built once under the tenant fence");assertEquals(2,probe.statements.stream().filter(q->q.equals("select * from opportunity.opportunity where tenant_id=? and opportunity_id=?")).count(),"Contract and Quote each fetch their opportunity row once within this fenced read");var items=(List<?>)page.get("items");assertEquals(1,items.size());var row=(Map<?,?>)items.getFirst();assertEquals(workflow.get("ownerLabel"),row.get("ownerLabel"));assertEquals(workflow.get("dueAt"),row.get("dueAt"));assertEquals(true,row.get("canHandle"));}
        try(var rc=database.apiConnection();var wc=database.apiConnection();var observer=database.adminConnection();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var probe=new ReadConnectionProbe(rc);probe.pauseCommit=true;int pid;try(var q=wc.createStatement();var rs=q.executeQuery("select pg_backend_pid()")){rs.next();pid=rs.getInt(1);}
            var reading=executor.submit(()->reader.ledger(probe.connection(),seed.request().actor()));
            try {
                assertTrue(probe.commitReached.await(10,java.util.concurrent.TimeUnit.SECONDS));
                var writing=executor.submit(()->inTransaction(wc,Capability.COMMAND,c->{R1BusinessFence.databaseBacked().exclusive(c,seed.tenant());AuthorizationService.databaseBacked().lockForMutation(c,seed.tenant());try(var q=c.prepareStatement("update identity.principal set display_name='Updated contract Owner',revision=revision+1 where tenant_id=? and principal_id=?")){q.setObject(1,seed.tenant());q.setObject(2,seed.request().actor().principalId());q.executeUpdate();}return null;}));
                boolean blocked=false;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while(!blocked&&System.nanoTime()<until){try(var q=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")){q.setInt(1,pid);try(var rs=q.executeQuery()){rs.next();blocked=rs.getBoolean(1);}}if(!blocked)Thread.sleep(5);}
                assertTrue(blocked);assertFalse(writing.isDone());probe.commitContinue.countDown();reading.get(10,java.util.concurrent.TimeUnit.SECONDS);writing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            }finally{probe.release();}
        }
        try(var c=database.apiConnection()){var row=(Map<?,?>)((List<?>)reader.ledger(c,seed.request().actor()).get("items")).getFirst();assertEquals("Updated contract Owner",row.get("ownerLabel"));}
        var auditFailure=new AuditAppender(){public void append(Connection c,Entry e)throws SQLException{throw new SQLException("injected");}public void append(Connection c,ContractDisclosureEntry e)throws SQLException{throw new SQLException("injected");}};
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->new R2ContractReadService(contracts,auditFailure).ledger(c,seed.request().actor()));}
        mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'CONTRACT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'contract.contract',?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),started.resultFact().id(),started.resultFact().revision());
        try(var c=database.apiConnection()){assertTrue(((List<?>)reader.ledger(c,seed.request().actor()).get("items")).isEmpty());}
    }
    @Test void timing_instrumentation_preserves_the_contract_detail_identity_scope()throws Exception {
        initializeContract();String previous=System.getProperty("ols.read.metrics");System.setProperty("ols.read.metrics","true");
        try(var c=database.apiConnection()) {
            var probe=new ReadConnectionProbe(c);var result=new R2ContractReadService(contracts,AuditAppender.databaseBacked("T08_METRICS_IT")).read(probe.connection(),seed.request().actor(),opportunity.id());
            assertNotNull(result.get("opportunity"));
            assertEquals(2,probe.statements.stream().filter(q->q.contains("pg_advisory_xact_lock_shared")).count(),"Detail callbacks must use the instrumented transaction connection, retaining identity fact reuse");
        }finally {if(previous==null)System.clearProperty("ols.read.metrics");else System.setProperty("ols.read.metrics",previous);}
    }
    @Test void real_contract_task_current_card_disclosure_returns_audited_body()throws Exception {
        initializeContract();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        try(var c=database.apiConnection()){
            var response=new CurrentWorkCardDisclosureService(protection,policies,"T08_CARD_IT",cipher).read(c,seed.request().actor(),UUID.randomUUID(),null);
            assertEquals(200,response.status(),response.errorCode());
            assertNotNull(response.body().get("currentCard"));
        }
        assertTrue(Long.parseLong(scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CURRENT_WORKCARD'",seed.tenant()))>0);
    }
    @Test void direct_request_has_authorized_audit_and_same_key_receipt_replay_without_duplicate_fact_or_task()throws Exception {
        initializeContract();var envelope=request();var deadline=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id());var result=execute(envelope);
        assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());assertEquals("contract.preparation_request",result.resultFact().type());
        assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and command_id=? and command_type='REQUEST_CONTRACT_PREPARATION' and result_code='SUCCEEDED'",seed.tenant(),envelope.commandId()));
        var before=counts();assertEquals(result,execute(envelope));assertEquals(before,counts());
        assertEquals(deadline,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DECIDE_CONTRACT_PREPARATION' and state='OPEN'",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DECIDE_CONTRACT_PREPARATION' and state='OPEN'",seed.tenant(),opportunity.id()));
        try(var c=database.apiConnection()){assertEquals(200,new CommandReceiptRecoveryService(policies,protection,null,"T08_RECEIPT_IT").read(c,seed.request().actor(),envelope.commandId(),UUID.randomUUID()).status());}
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_PREPARE'",seed.tenant());
        try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"T08_REVOKED_RECEIPT_IT").read(c,seed.request().actor(),envelope.commandId(),UUID.randomUUID()).status());}
        assertEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and change_summary::text like '%保密合成申请原因%'",seed.tenant()));
    }
    @Test void authority_loss_rejects_new_command_without_writing_contract_fact()throws Exception {
        initializeContract();var envelope=request();
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_PREPARE'",seed.tenant());
        try(var c=database.apiConnection()){var rejected=assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,envelope));assertEquals("NOT_AUTHORIZED",rejected.code());}
        assertEquals("0",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from execution.command_receipt r join execution.command_execution_slot s on s.tenant_id=r.tenant_id and s.command_execution_slot_id=r.command_execution_slot_id where r.tenant_id=? and s.command_id=?",seed.tenant(),envelope.commandId()));
    }
    @Test void disclosure_audits_exact_sources_and_audit_failure_or_revocation_returns_no_body()throws Exception {
        initializeContract();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        long before=Long.parseLong(scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CONTRACT'",seed.tenant()));
        var disclosed=contractContext();assertNotNull(disclosed.get("preparation"));
        long after=Long.parseLong(scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CONTRACT'",seed.tenant()));assertTrue(after>before);
        var fail=new AuditAppender(){public void append(Connection c,Entry entry)throws SQLException{throw new SQLException("injected");}public void append(Connection c,ContractDisclosureEntry entry)throws SQLException{throw new SQLException("injected disclosure audit");}};
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->new R2ContractReadService(contracts,fail).read(c,seed.request().actor(),opportunity.id()));}
        assertEquals(Long.toString(after),scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CONTRACT'",seed.tenant()));
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_READ'",seed.tenant());
        assertThrows(R1ServiceReadRuntime.Failure.class,this::contractContext);
    }
    UUID independentPreparationDecider()throws Exception {
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_PREPARATION_DECIDE'",seed.tenant());
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'合成独立授权人','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'LEGAL_REVIEW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'CONTRACT_PREPARATION_DECIDE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());
        return principal;
    }
    @Test void workflow_owner_label_and_original_deadline_are_authorized_and_audited()throws Exception {
        initializeContract();var principal=independentPreparationDecider();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        var workflow=(Map<?,?>)contractContext().get("workflow");assertEquals("合成独立授权人",workflow.get("ownerLabel"));assertNotNull(workflow.get("dueAt"));
        var task=(Map<?,?>)workflow.get("task");
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var exact=io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(x,seed.tenant(),UUID.fromString((String)task.get("id")));assertEquals(exact.slaDueAt().toString(),workflow.get("dueAt"));return null;});}
        assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CONTRACT' and subject_type='identity.principal' and subject_id=?",seed.tenant(),principal));
    }
    @Test void denied_owner_identity_is_hidden_without_denying_contract_or_deadline()throws Exception {
        initializeContract();var principal=independentPreparationDecider();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(request()).status());
        mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'CONTRACT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'identity.principal',?,0)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),principal);
        var context=contractContext();assertNotNull(context.get("preparation"));var workflow=(Map<?,?>)context.get("workflow");assertEquals("负责人信息受限",workflow.get("ownerLabel"));assertNotNull(workflow.get("dueAt"));
        assertEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_CONTRACT' and subject_type='identity.principal' and subject_id=?",seed.tenant(),principal));
    }
}
