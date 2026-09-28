package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Real workflow, task and immutable-version transaction; authority is an explicit test port. */
class R2ContractWorkflowPersistenceIT extends R2ContractVersionPersistenceIT {

    // F02/F03: downstream workflows must never revive the superseded sales entry.
    @Test void sales_chain_direct_request_excludes_late_initial_discovery()throws Exception {
        initialize();
        var worker=service("OPPORTUNITY_TASK_ACTIVATE");
        var discovery=new R2OpportunityDiscoveryService(new byte[32]);
        try(var c=database.apiConnection()){assertEquals(1,discovery.list(c,worker,R2OpportunityDiscoveryService.Kind.INITIAL,50,null).page().candidates().size());}
        command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        for(int i=0;i<2;i++)try(var c=database.apiConnection()){
            var page=new R2OpportunityDiscoveryService(new byte[32]).list(c,worker,R2OpportunityDiscoveryService.Kind.INITIAL,50,null);
            assertEquals(200,page.status(),page.errorCode());assertTrue(page.page().candidates().isEmpty(),"Contract request already owns this opportunity");
        }
    }
    @Test void sales_chain_stale_activation_after_contract_request_cannot_create_followup()throws Exception {
        initialize();var worker=service("OPPORTUNITY_TASK_ACTIVATE");
        var activation=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(
            io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,
            UUID.randomUUID(),UUID.randomUUID(),worker,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        var before=scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id());
        try(var c=database.apiConnection()){
            var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"SALES_CHAIN_GUARD_IT");
            assertThrows(io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected.class,()->runtime.execute(c,activation));
        }
        assertEquals(before,scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id()));
    }
    @Test void sales_chain_direct_contract_blocks_quote_read_and_stale_write()throws Exception {
        initialize();var quotes=R2QuoteServices.create(cipher);Map<String,Object> quoted;
        try(var c=database.apiConnection()){quoted=inTransaction(c,Capability.QUERY,x->quotes.context(x,actor(),opportunity.id()));}
        var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());
        p.put("responsibilityBasis",quoted.get("responsibilityBasis"));p.put("customerConfirmation",quoted.get("customerConfirmation"));
        p.put("expectedDraft",null);p.put("expectedQuote",null);p.put("expectedWorkflow",null);
        p.put("values",Map.of("currency","CNY","scope","服务范围","lines",List.of(Map.of("description","服务费","amountMinor",10000L,"discount",false)),"paymentTerms","签署后支付","validUntil",Instant.now().plusSeconds(86400).toString()));
        command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        var before=scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='OPEN'",seed.tenant(),opportunity.id());
        try(var c=database.apiConnection()){var q=inTransaction(c,Capability.QUERY,x->quotes.context(x,actor(),opportunity.id()));
            assertEquals(List.of(),q.get("allowedActions"));assertEquals(true,q.get("readonly"));}
        for(var action:List.of("SAVE_QUOTE_DRAFT","FORM_QUOTE","END_QUOTE_NEGOTIATION"))try(var c=database.apiConnection()){
            assertThrows(io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.Blocked.class,
                ()->inTransaction(c,Capability.COMMAND,x->quotes.execute(x,action,actor(),p)));
        }
        assertEquals("0",scalar("select count(*) from opportunity.quote_revision where tenant_id=?",seed.tenant()));
        assertEquals(before,scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='OPEN'",seed.tenant(),opportunity.id()));
    }
    @Test void sales_chain_contract_owned_closure_is_blocked_without_permanent_stale_error()throws Exception {
        initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        for(int i=0;i<2;i++)try(var c=database.apiConnection()){
            var result=new R2OpportunityClosureReadService(new byte[32],protection,cipher,
                io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("SALES_CHAIN_CLOSURE_IT")).read(c,actor(),opportunity.id());
            assertEquals("BLOCKED",result.get("status"));assertFalse(result.containsKey("expectedTask"));
        }
    }

    @Test void sales_chain_trusted_initial_factory_rejects_downstream_responsibility()throws Exception {
        initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        try(var c=database.apiConnection()){assertThrows(io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected.class,()->inTransaction(c,Capability.COMMAND,x->tasks.createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),tasks.now(x))));}
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id()));
    }
    @Test void sales_chain_ledger_uses_direct_contract_responsibility_and_honors_read_denial()throws Exception {
        initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","直接准备合同")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_READ");grant(x,TaskFactory.Type.DECIDE_CONTRACT_PREPARATION.authority);return null;});}
        var reader=new R2OpportunityLedgerReadService(new byte[32],protection,cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("F03_LEDGER_IT"));
        Map<String,Object> row;try(var c=database.apiConnection()){row=reader.read(c,actor(),"detail",opportunity.id(),30,null,null,null);}
        assertEquals(true,row.get("canHandle"));assertEquals("OPEN",row.get("taskState"));
        var selected=((Map<?,?>)row.get("task")).get("id");assertEquals(((Map<?,?>)((Map<?,?>)context().get("workflow")).get("task")).get("id"),selected);
        deny(new Subject("responsibility.task_occurrence",UUID.fromString((String)selected),0L,null),"CONTRACT_READ");
        try(var c=database.apiConnection()){row=reader.read(c,actor(),"detail",opportunity.id(),30,null,null,null);}
        assertEquals(false,row.get("canHandle"));assertFalse(row.containsKey("task"));assertFalse(row.containsKey("nextActionLabel"));
    }
    boolean permitted=true,scopeComplete=true,approversEligible=true;
    final List<UUID> additionalSignatureVerifiers=new ArrayList<>();
    final List<UUID> terminationReviewers=new ArrayList<>();
    final Set<String> unavailableAuthorities=new HashSet<>();
    @org.junit.jupiter.api.BeforeEach void resetWorkflowPorts(){permitted=true;scopeComplete=true;approversEligible=true;unavailableAuthorities.clear();additionalSignatureVerifiers.clear();terminationReviewers.clear();}
    final TaskFactory tasks=TaskFactory.databaseBacked();
    ContractWorkflowService service(){return ContractWorkflowService.databaseBacked(protectedBodies,R2ContractPreparationSources.create(cipher),codec(),new ContractWorkflowService.Ports(){
        public List<Subject> transferFacts(Connection c,UUID tenant,UUID id)throws SQLException{return io.github.windyzhu3.ontologylaw.transfer.OpportunityTransferReader.databaseBacked().reviewFactsForOpportunity(c,tenant,id);}
        public Instant signatureDue(Instant readyAt,ZoneId zone){return new ContractWorkflowPorts(cipher,null).signatureDue(readyAt,zone);}
        ContractWorkflowService.Task neutral(TaskFactory.Task t){return t==null?null:new ContractWorkflowService.Task(t.selector(),t.owner(),t.type().name(),t.subject(),t.state(),t.createdAt(),t.completion());}
        TaskFactory.Task owned(ContractWorkflowService.Task t){return new TaskFactory.Task(t.selector(),t.owner(),TaskFactory.Type.valueOf(t.type()),t.subject(),t.state(),t.createdAt(),t.completion());}
        public Instant now(Connection c)throws SQLException{return tasks.now(c);}
        public ContractWorkflowService.Responsibility responsibility(Connection c,UUID tenant,Subject o){return new ContractWorkflowService.Responsibility(opportunity,seed.appointment(),seed.org());}
        public List<Subject> sourceFacts(Connection c,UUID tenant,UUID oid){return List.of(opportunity,confirmationFact);}
        public boolean permitted(Connection c,Actor actor,UUID org,List<Subject> facts,String authority){return permitted;}
        public List<UUID> eligible(Connection c,UUID tenant,UUID org,List<Subject> facts,String authority){if(!permitted||unavailableAuthorities.contains(authority))return List.of();var candidates=new ArrayList<UUID>();candidates.add(seed.appointment());if(authority.equals("CONTRACT_SIGNATURE_VERIFY"))candidates.addAll(additionalSignatureVerifiers);if(Set.of("CONTRACT_TERMINATION_REVIEW","CONTRACT_READ").contains(authority))candidates.addAll(terminationReviewers);return List.copyOf(candidates);}
        public ContractWorkflowService.ApprovalPolicy approvalPolicy(Connection c,UUID tenant,UUID org,List<Subject> facts)throws SQLException{return approversEligible?ContractWorkflowService.approvalPolicy(c,tenant,org):null;}
        public ContractWorkflowService.Task read(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(tasks.read(c,tenant,id));}
        public ContractWorkflowService.Task currentTask(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(tasks.currentTask(c,tenant,id));}
        public List<ContractWorkflowService.Task> active(Connection c,UUID tenant,Subject o)throws SQLException{return tasks.activeForLead(c,tenant,o).stream().map(this::neutral).toList();}
        public ContractWorkflowService.Task create(Connection c,UUID tenant,String type,UUID owner,Subject o,ZoneId zone,Instant now)throws SQLException{return neutral(tasks.create(c,tenant,TaskFactory.Type.valueOf(type),owner,o,zone,now));}
        public ContractWorkflowService.Task createSignatureTask(Connection c,UUID tenant,String type,UUID owner,Subject o,ZoneId zone,Instant now,Instant due)throws SQLException{return neutral(tasks.createSignatureTask(c,tenant,TaskFactory.Type.valueOf(type),owner,o,zone,now,due));}
        public void complete(Connection c,UUID tenant,ContractWorkflowService.Task t,Subject f,Instant now)throws SQLException{tasks.complete(c,tenant,owned(t),f,now);}
        public ContractWorkflowService.Task waitForReceipt(Connection c,UUID tenant,ContractWorkflowService.Task t,UUID actor,Instant now,Subject version)throws SQLException{return neutral(tasks.waitForContractReceipt(c,tenant,owned(t),actor,now,version));}
        public ContractWorkflowService.Task resumeReceipt(Connection c,UUID tenant,ContractWorkflowService.Task t,Subject version)throws SQLException{return neutral(tasks.resumeContractReceipt(c,tenant,owned(t),version));}
        public void cancelForContract(Connection c,UUID tenant,ContractWorkflowService.Task t,String reason,Instant now)throws SQLException{tasks.cancelForContract(c,tenant,owned(t),reason,now);}
        public Subject negotiationWait(Connection c,UUID tenant,ContractWorkflowService.Task t)throws SQLException{return new ContractWorkflowPorts(cipher,null).negotiationWait(c,tenant,t);}
        public void cancelForNegotiation(Connection c,UUID tenant,ContractWorkflowService.Task t,Subject f,Instant now)throws SQLException{tasks.cancelForContractNegotiation(c,tenant,owned(t),f,now);}
        public ContractWorkflowService.Task createTerminationReview(Connection c,UUID tenant,UUID owner,Subject o,Instant now,Instant due)throws SQLException{return neutral(tasks.createTerminationReview(c,tenant,owner,o,now,due));}
        public ContractWorkflowService.Task resumeAfterNegotiation(Connection c,UUID tenant,ContractWorkflowService.Task prior,Subject disposition,UUID member,Instant now)throws SQLException{return neutral(tasks.resumeAfterContractNegotiation(c,tenant,owned(prior),disposition,member,now));}
        public byte[] document(Connection c,Actor actor,UUID o,UUID v){throw new UnsupportedOperationException("Document test supplies real object-store port separately");}
        public List<Subject> documentFacts(Connection c,UUID tenant,UUID id){return List.of(opportunity,new Subject("opportunity.material_version",id,0L,null));}
        public boolean documentUsable(Connection c,UUID tenant,UUID id,String sha)throws SQLException{return new ContractWorkflowPorts(cipher,null).documentUsable(c,tenant,id,sha);}
        public ContractWorkflowService.DocumentObject documentObject(Connection c,UUID tenant,UUID id)throws SQLException{return new ContractWorkflowPorts(cipher,null).documentObject(c,tenant,id);}
        public ContractWorkflowService.Task createContractTakingOver(Connection c,UUID tenant,String type,UUID owner,Subject o,ContractWorkflowService.Task prior,ZoneId zone,Instant now)throws SQLException{return neutral(tasks.createContractTakingOver(c,tenant,TaskFactory.Type.valueOf(type),owner,o,owned(prior),zone,now));}
        public boolean signingPartyCurrent(Connection c,UUID tenant,UUID party,long revision)throws SQLException{var current=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked().current(c,tenant,party,false);return current!=null&&current.selector().revision()==revision;}
        public String customerName(Connection c,UUID tenant,UUID o){return "合成验收客户";}
        public List<Map<String,Object>> documents(Connection c,Actor actor,UUID o){return material==null?List.of():List.of(Map.of("id",material.toString(),"label","合成合同正文","bodySha256",bodySha));}
        public Map<String,Object> acceptedQuote(Connection c,UUID tenant,UUID o){return null;}
        public Subject blockFinding(Connection c,UUID tenant,UUID o,UUID actor,Subject finding,String reason)throws SQLException{return io.github.windyzhu3.ontologylaw.responsibility.ContractConflictDecisions.databaseBacked().block(c,tenant,o,actor,finding,reason);}
        public boolean reviewScopeComplete(Connection c,UUID tenant,UUID o){return scopeComplete;}
    });}
    Actor actor(){return new Actor(seed.tenant(),seed.principal(),seed.appointment(),null,null);}
    Map<String,Object> context()throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->service().context(x,actor(),opportunity.id()));}}
    @SuppressWarnings("unchecked") Map<String,Object> payload(Map<String,Object> context,Map<String,Object> values){var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());p.put("responsibilityBasis",context.get("responsibilityBasis"));p.put("customerConfirmation",context.get("customerConfirmation"));var contract=(Map<String,Object>)context.get("contract");var draft=(Map<String,Object>)context.get("draft");var workflow=(Map<String,Object>)context.get("workflow");p.put("expectedContract",contract==null?null:contract.get("selector"));p.put("expectedVersion",contract==null?null:contract.get("currentRevision"));p.put("expectedDraft",draft==null?null:draft.get("selector"));p.put("expectedWorkflow",workflow==null?null:workflow.get("selector"));p.put("values",values);return p;}
    Subject command(String action,Map<String,Object> payload)throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{if(action.equals("RECORD_CONTRACT_REVIEW"))io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().exclusive(x,seed.tenant());return service().execute(x,action,actor(),payload);});}}
    @Test void direct_request_decision_start_preserves_preparation_task_until_formation()throws Exception {
        initialize();var request=command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","合成直接准备申请")));
        assertEquals("contract.preparation_request",request.type());assertEquals("DIRECT_REVIEW",((Map<?,?>)context().get("workflow")).get("stage"));
        command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","APPROVED","reason","批准本次准确准备范围")));
        Object prepareTask=((Map<?,?>)context().get("workflow")).get("task");
        command("START_CONTRACT_PREPARATION",payload(context(),Map.of()));
        assertEquals(prepareTask,((Map<?,?>)context().get("workflow")).get("task"));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void formation_advances_to_review_submission_and_stale_retry_creates_nothing()throws Exception {
        versionFixture();var values=new LinkedHashMap<>(preparation(input(1,null)));values.put("commercial",terms.canonical());var p=payload(context(),values);
        var result=command("FORM_CONTRACT",p);assertEquals("contract.contract_revision",result.type());
        assertEquals("SUBMIT_REVIEW",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='SUBMIT_CONTRACT_REVIEW' and state='OPEN'",seed.tenant()));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("FORM_CONTRACT",p));assertEquals("1",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void denied_and_rolled_back_workflows_leave_no_successor_or_version()throws Exception {
        versionFixture();var values=new LinkedHashMap<>(preparation(input(1,null)));values.put("commercial",terms.canonical());var p=payload(context(),values);permitted=false;
        assertThrows(ContractWorkflowService.Blocked.class,()->command("FORM_CONTRACT",p));permitted=true;
        try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{service().execute(x,"FORM_CONTRACT",actor(),p);throw new IllegalStateException("audit failed");}));}
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant()));
    }
    Subject formThroughWorkflow()throws Exception{var values=new LinkedHashMap<>(preparation(input(1,null)));values.put("commercial",terms.canonical());return command("FORM_CONTRACT",payload(context(),values));}
    @Test void review_and_approval_finish_at_unsigned_boundary_without_an_unhandleable_task()throws Exception {
        versionFixture();var version=formThroughWorkflow();
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","已核对本次完整参与主体和系统全部准确身份候选")));
        assertEquals("SUBMIT_APPROVAL",((Map<?,?>)context().get("workflow")).get("stage"));
        command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","批准准确合同正文及收费付款安排")));
        var workflow=(Map<?,?>)context().get("workflow");assertEquals("READY_FOR_SIGNATURE",workflow.get("stage"));assertNull(workflow.get("task"));
        assertEquals(version.id().toString(),scalar("select approved_revision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
        assertEquals("1",scalar("select count(*) from contract.signature_readiness where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code like '%CONTRACT%' and state='OPEN'",seed.tenant()));
    }
    @Test void missing_scope_requires_supplement_and_resubmission_keeps_exact_version()throws Exception {
        versionFixture();var version=formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));scopeComplete=false;
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","不能跳过缺项"))));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","NEED_INFO","reason","请补充审查资料")));
        assertEquals("REVIEW_SUPPLEMENT",((Map<?,?>)context().get("workflow")).get("stage"));scopeComplete=true;
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of("reason","逐项补充说明并请求重新审查")));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","重新核对完整准确主体范围")));
        assertEquals("2",scalar("select count(*) from contract.revision_review_request where tenant_id=?",seed.tenant()));assertEquals(version.id().toString(),scalar("select current_revision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
    }
    @Test void every_frozen_approval_requirement_must_finish_before_ready()throws Exception {
        versionFixture();UUID nextPolicy=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            try(var p=x.prepareStatement("insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',2,'REQUIRE_APPROVAL',decode(repeat('23',32),'hex'),clock_timestamp())")){p.setObject(1,seed.tenant());p.setObject(2,nextPolicy);p.setObject(3,seed.org());p.executeUpdate();}
            for(String requirement:List.of("LEGAL","COMMERCIAL"))try(var p=x.prepareStatement("insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,?,?,clock_timestamp())")){p.setObject(1,seed.tenant());p.setObject(2,UUID.randomUUID());p.setObject(3,nextPolicy);p.setObject(4,requirement);p.setObject(5,seed.appointment());p.executeUpdate();}return null;
        });}
        formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","已核对完整范围")));command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","第一项准确要求已确认")));
        assertEquals("AWAIT_APPROVAL",((Map<?,?>)context().get("workflow")).get("stage"));assertEquals("0",scalar("select count(*) from contract.signature_readiness where tenant_id=?",seed.tenant()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","第二项准确要求已确认")));
        assertEquals("READY_FOR_SIGNATURE",((Map<?,?>)context().get("workflow")).get("stage"));assertEquals("2",scalar("select count(*) from contract.revision_approval_decision where tenant_id=?",seed.tenant()));
    }
}


