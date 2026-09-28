package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Opt-in internal runtime composition. HTTP/work-card exposure is a separate registered contract. */
public final class R2OpportunityCommandRuntime {
    private R2OpportunityCommandRuntime(){}
    /** Pure trusted adapter: preserve the discovery command key; execution remains explicit. */
    public static CommandEnvelope activation(Actor actor,R2OpportunityDiscoveryService.Candidate candidate,UUID correlation){
        if(candidate==null||candidate.kind()!=R2OpportunityDiscoveryService.Kind.INITIAL||candidate.task()!=null||candidate.waitReceipt()!=null||candidate.progress()!=null||candidate.due()!=null)throw new IllegalArgumentException("Exact initial candidate required");
        CommandScope.opportunityActivation(actor.tenantId(),candidate.opportunity());
        return new CommandEnvelope(CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,candidate.commandId(),correlation,actor,Map.of("opportunityId",candidate.opportunity().id().toString(),"expectedOpportunityRevision",candidate.opportunity().revision()));
    }
    public static CommandEnvelope recovery(Actor actor,R2OpportunityDiscoveryService.Candidate candidate,UUID correlation){
        if(candidate==null||candidate.kind()!=R2OpportunityDiscoveryService.Kind.DUE||candidate.task()==null
                ||candidate.waitReceipt()==null||candidate.progress()==null||candidate.due()==null)throw new IllegalArgumentException("Exact due candidate required");
        CommandScope.opportunityRecovery(actor.tenantId(),candidate.task().id(),candidate.opportunity(),candidate.waitReceipt(),candidate.progress());
        return new CommandEnvelope(CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,candidate.commandId(),correlation,actor,Map.of(
                "opportunityId",candidate.opportunity().id().toString(),"expectedOpportunityRevision",candidate.opportunity().revision(),
                "taskId",candidate.task().id().toString(),"expectedTaskRevision",candidate.task().revision(),
                "waitReceiptId",candidate.waitReceipt().id().toString(),"waitReceiptHash",candidate.waitReceipt().hash(),
                "progressId",candidate.progress().id().toString(),"progressHash",candidate.progress().hash(),"dueCutoff",candidate.due().toString()));
    }
    public static CommandRuntime create(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,String node,ZoneId zone){
        return create(sources,leads,protection,null,node,zone);
    }
    public static CommandRuntime create(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,ZoneId zone){
        return assemble(sources,leads,protection,services,node,new R2OpportunityCommands(protection,zone));
    }
    public static CommandRuntime fromSourcePolicy(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node){
        return fromSourcePolicy(sources,leads,protection,services,node,null);
    }
    public static CommandRuntime fromSourcePolicy(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts){
        return fromSourcePolicy(sources,leads,protection,services,node,contracts,null);
    }
    public static CommandRuntime fromSourcePolicy(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts,io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService transfers){return fromSourcePolicy(sources,leads,protection,services,node,contracts,transfers,null);}
    public static CommandRuntime fromSourcePolicy(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts,io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService transfers,R2TransferRecoveryService transferRecovery){
        var reader=LeadIngressService.databaseBacked(leads);var opportunities=EventOpportunityReader.databaseBacked();
        return assemble(sources,leads,protection,services,node,new R2OpportunityCommands(protection,(c,actor,subject)->{
            var opportunity=opportunities.byId(c,actor.tenantId(),subject.id());
            var lead=opportunity==null?null:reader.header(c,actor.tenantId(),opportunity.leadId());
            var policy=lead==null?null:sources.find(lead.source());
            if(policy==null)throw new SQLException("Opportunity source policy unavailable","22000");
            return ZoneId.of(policy.businessTimezone());
        }),contracts,transfers,transferRecovery);
    }
    private static CommandRuntime assemble(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,R2OpportunityCommands r2){
        return assemble(sources,leads,protection,services,node,r2,null);
    }
    private static CommandRuntime assemble(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,R2OpportunityCommands r2,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts){
        return assemble(sources,leads,protection,services,node,r2,contracts,null);
    }
    private static CommandRuntime assemble(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,R2OpportunityCommands r2,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts,io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService transfers){return assemble(sources,leads,protection,services,node,r2,contracts,transfers,null);}
    private static CommandRuntime assemble(R1SourcePolicyRegistry sources,LeadProtection leads,OpportunityProgressProtection protection,R1ServiceSourceBinding services,String node,R2OpportunityCommands r2,io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService contracts,io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService transfers,R2TransferRecoveryService transferRecovery){
        var handlers=new ArrayList<>(new LeadCommands(sources,leads).handlers());
        handlers.add(r2.draft(new ActionDraftCommands(leads).handlers().getFirst()));handlers.add(r2.primary());
        handlers.add(new R2SalesChainRepairCommand(protection));
        handlers.add(new R2OpportunityRecoveryCommand(protection));
        handlers.add(new R2OpportunityActivationCommand(sources,leads,protection));
        handlers.addAll(R2OpportunityOwnerExceptionCommand.handlers(sources,leads,protection));
        handlers.add(new R2OpportunityClosureCommand(protection));
        handlers.add(new R2CustomerRequirementsCommand(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,protection));
        handlers.add(new R2CustomerRequirementsCommand(CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,protection));
        handlers.add(new R2MaterialsCommand(CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD,protection));
        handlers.add(new R2MaterialsCommand(CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL,protection));
        for(var type:CommandEnvelope.Type.values())if(type.followupAttempts())handlers.add(new R2FollowupAttemptCommand(type,protection,r2.businessZone()));
        for(var type:CommandEnvelope.Type.values())if(type.quotes())handlers.add(new R2QuoteCommand(type,protection));
        if(transfers!=null)for(var type:CommandEnvelope.Type.values())if(type.transfers())handlers.add(new R2TransferCommand(type,protection,transfers));
        if(contracts!=null)handlers.add(new R2ContractRecoveryCommand(protection,contracts,transferRecovery));
        if(contracts!=null)for(var type:CommandEnvelope.Type.values())if(type.contracts())handlers.add(new R2ContractCommand(type,protection,contracts));
        return new CommandRuntime(handlers,AuthorizationService.databaseBacked(),AuditAppender.databaseBacked(node),authorization(R1AuthorizationReaders.databaseBacked(sources,services)),events(R1EventReaders.databaseBacked(),protection));
    }
    public static R1AuthorizationFacts authorization(R1AuthorizationFacts original){
        Objects.requireNonNull(original);var opportunities=EventOpportunityReader.databaseBacked();var tasks=AuthorizationTaskReader.databaseBacked();var identity=AuthorizationIdentityReader.databaseBacked();
        return new R1AuthorizationFacts(){
            public Subject opportunityRecoverySource(Connection c,UUID tenant,UUID task)throws SQLException{return FollowupAttemptRecovery.source(c,tenant,task);}
            public Contracts transfers(Connection c,UUID tenant,CommandAuthorizationBinding.Transfers b,CommandEnvelope.Type type)throws SQLException{return R2TransferServices.authorization(c,tenant,b,type);}
            public Contracts contractRecovery(Connection c,UUID tenant,CommandAuthorizationBinding.ContractRecovery b)throws SQLException{return R2ContractServices.recoveryAuthorization(c,tenant,b);}
            public Contracts contracts(Connection c,UUID tenant,CommandAuthorizationBinding.Contracts b)throws SQLException{return R2ContractServices.authorization(c,tenant,b);}
            public Quotes quotes(Connection c,UUID tenant,CommandAuthorizationBinding.Quotes b)throws SQLException{return R2QuoteServices.authorization(c,tenant,b);}
            public MaterialFact materialFact(Connection c,UUID tenant,Subject exact)throws SQLException{return R2MaterialsServices.fact(c,tenant,exact);}
            public Materials materials(Connection c,UUID tenant,CommandAuthorizationBinding.Materials b,boolean receipt)throws SQLException{
                var opening=opportunities.byId(c,tenant,b.opportunity().id());if(opening==null)return null;var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
                var initial=R2MaterialsServices.facts(c,tenant,b.opportunity(),null);if(initial==null)return null;var values=new LinkedHashSet<Subject>(initial);values.add(b.basis());values.add(owner.basis());
                if(b.confirmation()!=null){var cf=R2CustomerRequirementsServices.sourceFacts(c,tenant,b.opportunity(),b.confirmation());if(cf==null)return null;values.addAll(cf);}
                for(var exact:new Subject[]{b.previous(),b.upload()})if(exact!=null){var fs=R2MaterialsServices.facts(c,tenant,b.opportunity(),exact);if(fs==null)return null;values.addAll(fs);}
                if(b.upload()!=null)for(var v:OpportunityMaterials.databaseBacked().history(c,tenant,b.opportunity().id()))if(v.upload().equals(b.upload().id())){var fs=R2MaterialsServices.facts(c,tenant,b.opportunity(),v.selector());if(fs==null)return null;values.addAll(fs);}
                return new Materials(opening.selector(),List.copyOf(values),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),owner.appointmentId());
            }
            public R1AuthorizationFacts.CustomerVersion customerRequirementVersion(Connection c,UUID tenant,Subject exact)throws SQLException{var m=OpportunityCustomerRequirementsService.metadata(c,tenant,exact);return m==null?null:new R1AuthorizationFacts.CustomerVersion(m.selector(),m.opportunity(),m.responsibility(),m.owner(),m.draft(),m.previous(),m.createdAt());}
            public CustomerRequirements customerRequirements(Connection c,UUID tenant,CommandAuthorizationBinding.CustomerRequirements b,boolean receipt)throws SQLException{
                var opening=opportunities.byId(c,tenant,b.opportunity().id());if(opening==null)return null;
                var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
                var values=new LinkedHashSet<Subject>(R2CustomerRequirementsServices.sourceFacts(c,tenant,b.opportunity(),null));values.add(b.basis());if(b.draft()!=null){var fs=R2CustomerRequirementsServices.sourceFacts(c,tenant,b.opportunity(),b.draft());if(fs==null)return null;values.addAll(fs);}if(b.confirmation()!=null){var fs=R2CustomerRequirementsServices.sourceFacts(c,tenant,b.opportunity(),b.confirmation());if(fs==null)return null;values.addAll(fs);}
                return new CustomerRequirements(opening.selector(),List.copyOf(values),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),owner.appointmentId());
            }
            public FollowupAttempt followupAttempt(Connection c,UUID tenant,CommandAuthorizationBinding.FollowupAttempt b,Subject receipt)throws SQLException{return R2FollowupAttemptServices.authorization(c,tenant,b,receipt);}
            public OpportunityClosure opportunityClosure(Connection c,UUID tenant,CommandAuthorizationBinding.OpportunityClosure binding,Subject receipt)throws SQLException {
                var opening=opportunities.byId(c,tenant,binding.opportunity().id());if(opening==null)return null;
                var protectedSources=R2OpportunityClosureServices.protectedFacts(c,tenant,opening.selector());if(protectedSources==null)return null;
                var values=new LinkedHashSet<Subject>(protectedSources);values.add(binding.opportunity());values.add(binding.basis());
                for(var exact:new Subject[]{binding.task(),binding.waitReceipt(),receipt})if(exact!=null)values.add(exact);
                if(receipt!=null){var terminal=OpportunityClosureService.metadata(c,tenant,receipt);if(terminal==null||opening.selector().revision()!=CommandHandler.nextRevision(binding.opportunity().revision())||!terminal.opportunity().equals(binding.opportunity())||!terminal.responsibility().equals(binding.basis())||!Objects.equals(terminal.task(),binding.task())||!Objects.equals(terminal.waitReceipt(),binding.waitReceipt()))return null;}
                var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());values.add(effective.basis());
                var closed=OpportunityClosureService.currentMetadata(c,tenant,opening.selector().id());if(closed!=null){values.add(closed.selector());values.add(closed.opportunity());values.add(closed.responsibility());if(closed.task()!=null)values.add(closed.task());if(closed.waitReceipt()!=null)values.add(closed.waitReceipt());}
                // Preserve submitted historical selectors and independently authorize the current task revision.
                if(binding.task()!=null){var task=CurrentTaskReader.databaseBacked().read(c,tenant,binding.task().id());if(task==null||!task.subject().id().equals(opening.selector().id()))return null;values.add(task.selector());values.add(task.subject());if(task.responsibilityBasis()!=null)values.add(task.responsibilityBasis());var authorizedTask=tasks.read(c,tenant,binding.task().id());if(authorizedTask!=null&&authorizedTask.draft()!=null)values.add(authorizedTask.draft().selector());}
                var taskState=R2OpportunityOwnerExceptionAssembly.taskState(c,tenant,opening.selector(),effective);if(taskState.task()!=null)values.add(taskState.task());if(taskState.waitReceipt()!=null)values.add(taskState.waitReceipt());values.addAll(taskState.protectedSources());
                return new OpportunityClosure(opening.selector(),List.copyOf(values),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),closed==null?null:closed.actor());
            }
            public CommandReceiptReader.Receipt commandReceipt(Connection c,UUID tenant,UUID command)throws SQLException{return CommandReceiptReader.databaseBacked().read(c,tenant,command);}
            public OpportunityReceiptTask opportunityReceiptTask(Connection c,UUID tenant,UUID id,Instant at)throws SQLException {
                var stored=tasks.read(c,tenant,id);if(stored==null||!"opportunity.opportunity".equals(stored.lead().type())||!"PROGRESS_OPPORTUNITY".equals(stored.taskType())||!"RECORD_OPPORTUNITY_PROGRESS".equals(stored.primaryCommand()))return null;
                var opening=opportunities.byId(c,tenant,stored.lead().id());if(opening==null)return null;var owner=identity.owner(c,tenant,stored.ownerAppointmentId(),at);var draft=stored.draft();
                var task=new Task(stored.selector(),stored.ownerAppointmentId(),stored.taskType(),stored.primaryCommand(),stored.lead(),opening.selector(),draft==null?null:new Draft(draft.selector(),draft.taskId(),draft.actionCode(),draft.schemaCode(),draft.schemaVersion()),owner==null?null:new Owner(owner.appointmentId(),owner.principalId(),owner.organizationId(),owner.active(),owner.evidence()));
                var protectedFacts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,tenant,opening.selector()));protectedFacts.add(stored.selector());protectedFacts.add(stored.lead());var detail=CurrentTaskReader.databaseBacked().read(c,tenant,id);if(detail==null||detail.responsibilityBasis()==null)return null;protectedFacts.add(detail.responsibilityBasis());if(draft!=null)protectedFacts.add(draft.selector());
                return new OpportunityReceiptTask(task,List.copyOf(protectedFacts));
            }

            public OwnerException ownerException(Connection c,UUID tenant,CommandAuthorizationBinding.OwnerException binding,Subject receipt)throws SQLException {
                var opening=opportunities.byId(c,tenant,binding.opportunity().id());if(opening==null)return null;
                var protectedFacts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,tenant,opening.selector()));protectedFacts.add(binding.opportunity());
                for(var exact:new Subject[]{binding.exception(),binding.basis(),binding.task(),binding.waitReceipt(),receipt})if(exact!=null)protectedFacts.add(exact);
                var service=R2OpportunityOwnerExceptionAssembly.service();
                for(var exact:new Subject[]{binding.exception(),receipt})if(exact!=null&&"opportunity.owner_exception".equals(exact.type())){
                    var history=service.read(c,tenant,exact);if(history==null||!history.opportunity().id().equals(opening.selector().id()))return null;
                    var current=service.current(c,tenant,exact.id());if(current==null)return null;
                    for(var snapshot:List.of(history,current)){protectedFacts.add(snapshot.selector());protectedFacts.add(snapshot.opportunity());protectedFacts.add(snapshot.responsibility().basis());if(snapshot.task()!=null)protectedFacts.add(snapshot.task());if(snapshot.waitReceipt()!=null)protectedFacts.add(snapshot.waitReceipt());if(snapshot.resolution()!=null)protectedFacts.add(snapshot.resolution());if(snapshot.lastDispositionId()!=null)protectedFacts.add(new Subject("opportunity.owner_exception_disposition",snapshot.lastDispositionId(),0L,null));}
                }
                return new OwnerException(opening.selector(),List.copyOf(protectedFacts),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()));
            }

            public Opportunity opportunity(Connection c,UUID tenant,UUID id,Instant at)throws SQLException{
                var opening=opportunities.byId(c,tenant,id);if(opening==null)return null;
                var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
                var source=R1EventReaders.databaseBacked().lead(c,tenant,opening.leadId());var owner=identity.owner(c,tenant,effective.appointmentId(),at);
                var initial=OpportunityMaintenanceTasks.databaseBacked().initial(c,tenant,id);
                return new Opportunity(opening.selector(),source,owner==null?null:new Owner(owner.appointmentId(),owner.principalId(),owner.organizationId(),owner.active(),owner.evidence()),initial==null?null:initial.selector());
            }
            public Subject opportunitySource(Connection c,UUID tenant,UUID id)throws SQLException{var opening=opportunities.byId(c,tenant,id);return opening==null?null:R1EventReaders.databaseBacked().lead(c,tenant,opening.leadId());}
            public UUID opportunityOrganization(Connection c,UUID tenant,UUID id)throws SQLException {
                var opening=opportunities.byId(c,tenant,id);
                return opening==null?null:OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,opening.owner());
            }
            public Capture capture(Connection c,UUID tenant,String account,String digest)throws SQLException{return original.capture(c,tenant,account,digest);}
            public Evidence evidence(Connection c,UUID tenant,UUID submission)throws SQLException{return original.evidence(c,tenant,submission);}
            public boolean serviceSourceAllowed(Connection c,Actor actor,String account)throws SQLException{return original.serviceSourceAllowed(c,actor,account);}
            public Task task(Connection c,UUID tenant,UUID id,Instant at)throws SQLException{
                var task=tasks.read(c,tenant,id);if(task==null||!"opportunity.opportunity".equals(task.lead().type()))return original.task(c,tenant,id,at);
                boolean quote="RECORD_QUOTE_REPLY".equals(task.taskType())&&"RECORD_QUOTE_RESPONSE".equals(task.primaryCommand());
                if(!quote&&(!"PROGRESS_OPPORTUNITY".equals(task.taskType())||!"RECORD_OPPORTUNITY_PROGRESS".equals(task.primaryCommand())))return null;
                var opportunity=opportunities.byId(c,tenant,task.lead().id());if(opportunity==null)return null;
                var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity.selector());
                var current=CurrentTaskReader.databaseBacked().read(c,tenant,id);
                if(!effective.appointmentId().equals(task.ownerAppointmentId())||current==null||!quote&&!effective.basis().equals(current.responsibilityBasis()))return null;
                var owner=identity.owner(c,tenant,task.ownerAppointmentId(),at);var draft=task.draft();
                return new Task(task.selector(),task.ownerAppointmentId(),task.taskType(),task.primaryCommand(),task.lead(),opportunity.selector(),draft==null?null:new Draft(draft.selector(),draft.taskId(),draft.actionCode(),draft.schemaCode(),draft.schemaVersion()),owner==null?null:new Owner(owner.appointmentId(),owner.principalId(),owner.organizationId(),owner.active(),owner.evidence()));
            }
        };
    }
    public static R1EventFacts events(R1EventFacts original,OpportunityProgressProtection protection){
        var opportunities=OpportunityCommandReader.databaseBacked(protection);
        return new R1EventFacts(){
            public boolean opportunityRepairResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.OpportunityRepair b,Subject exact)throws SQLException{return R2SalesChainRepairCommand.result(c,e.actor().tenantId(),b,exact);}
            public boolean contractRecoveryResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.ContractRecovery b,Subject exact,boolean created)throws SQLException{if(b.sourceKind().startsWith("TRANSFER_"))return R2TransferRecoveryService.result(c,e,b,exact,created);var m=io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.metadata(c,e.actor().tenantId(),exact);return m!=null&&m.opportunityId().equals(b.opportunity().id())&&(!created||m.actorAppointmentId().equals(e.actor().appointmentId())&&m.createdInCurrentTransaction())&&R2ContractServices.facts(c,e.actor().tenantId(),b.opportunity().id()).contains(exact);}
            public boolean transferResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Transfers b,Subject exact)throws SQLException{return R2TransferServices.result(c,e,b,exact);}
            public boolean contractResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Contracts b,Subject exact)throws SQLException{var m=io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.metadata(c,e.actor().tenantId(),exact);return m!=null&&m.opportunityId().equals(b.opportunity().id())&&m.actorAppointmentId().equals(e.actor().appointmentId())&&m.createdInCurrentTransaction()&&R2ContractServices.facts(c,e.actor().tenantId(),b.opportunity().id()).contains(exact);}
            public boolean followupAttemptResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.FollowupAttempt b,Subject exact)throws SQLException{var m=FollowupAttemptService.readMetadata(c,e.actor().tenantId(),exact);return m!=null&&m.createdInTransaction()&&m.actor().equals(e.actor().appointmentId())&&m.basis().equals(R2FollowupAttemptInput.basis(b))&&m.context().equals(e.type()==CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT?"QUOTE":"OPPORTUNITY");}
            public boolean quoteResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Quotes b,Subject exact)throws SQLException{var m=QuoteWorkflowService.metadata(c,e.actor().tenantId(),exact);return m!=null&&m.opportunityId().equals(b.opportunity().id())&&m.actorAppointmentId().equals(e.actor().appointmentId())&&m.createdInCurrentTransaction()&&R2QuoteServices.facts(c,e.actor().tenantId(),b.opportunity().id()).contains(exact);}
            public R1AuthorizationFacts.MaterialFact materialFact(Connection c,UUID tenant,Subject exact)throws SQLException{return R2MaterialsServices.fact(c,tenant,exact);}
            public R1AuthorizationFacts.CustomerVersion customerRequirements(Connection c,UUID tenant,Subject exact)throws SQLException{var m=OpportunityCustomerRequirementsService.metadata(c,tenant,exact);return m==null?null:new R1AuthorizationFacts.CustomerVersion(m.selector(),m.opportunity(),m.responsibility(),m.owner(),m.draft(),m.previous(),m.createdAt());}
            public Closure opportunityClosure(Connection c,UUID tenant,Subject exact)throws SQLException{var s=OpportunityClosureService.metadata(c,tenant,exact);return s==null?null:new Closure(s.selector(),s.opportunity(),s.responsibility(),s.task(),s.waitReceipt(),s.actor(),s.reasonCode(),s.closedAt());}
            public Subject opportunityTaskCancellation(Connection c,UUID tenant,UUID task)throws SQLException{return CurrentTaskReader.databaseBacked().cancellation(c,tenant,task);}
            public OwnerException activeOwnerException(Connection c,UUID tenant,UUID opportunity)throws SQLException {var s=R2OpportunityOwnerExceptionAssembly.service().active(c,tenant,opportunity);return s==null?null:ownerException(c,tenant,s.selector());}
            public OwnerException ownerException(Connection c,UUID tenant,Subject exact)throws SQLException {
                var service=R2OpportunityOwnerExceptionAssembly.service();var s=service.read(c,tenant,exact);if(s==null)return null;var d=s.lastDispositionId()==null?null:service.disposition(c,tenant,s.lastDispositionId());
                return new OwnerException(s.selector(),s.opportunity(),s.responsibility().basis(),s.responsibility().appointmentId(),s.task(),s.waitReceipt(),s.state().name(),s.resolution(),d==null?null:d.selector(),d==null?null:d.kind(),d==null?null:d.exception(),d==null?null:d.actor(),d==null?null:d.reason(),d==null?null:d.reviewDueAt(),d==null?null:d.receiver());
            }

            public InitialResponsibility initialOpportunityTask(Connection c,UUID tenant,UUID id)throws SQLException{
                var initial=OpportunityMaintenanceTasks.databaseBacked().initial(c,tenant,id);
                return new InitialResponsibility(initial==null?null:original.task(c,tenant,initial.selector().id()),initial==null?null:initial.createdAt(),TaskFactory.databaseBacked().now(c));
            }
            public Progress progress(Connection c,UUID tenant,UUID id)throws SQLException{
                var progress=opportunities.progress(c,tenant,id);if(progress==null)return null;
                try{
                    var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(progress.canonicalBody());
                    if(!body.isObject()||!OpportunityProgressInput.CONTRACT.equals(body.path("profile").asString())||!tenant.toString().equals(body.path("tenantId").asString())||!id.toString().equals(body.path("progressId").asString())||!progress.opportunity().toString().equals(body.path("opportunityId").asString()))throw new IllegalArgumentException();
                    String task=body.path("taskId").asString();UUID taskId=UUID.fromString(task);if(!taskId.toString().equals(task))throw new IllegalArgumentException();
                    return new Progress(progress.selector(),progress.opportunity(),taskId);
                }catch(RuntimeException invalid){throw new SQLException("Protected progress source invalid","22000");}
            }
            public LeadAnchor leadAnchor(Connection c,UUID tenant,UUID id)throws SQLException{return original.leadAnchor(c,tenant,id);}
            public ContactAnchor contactAnchor(Connection c,UUID tenant,UUID id)throws SQLException{return original.contactAnchor(c,tenant,id);}
            public Opportunity opportunity(Connection c,UUID tenant,UUID id)throws SQLException{return original.opportunity(c,tenant,id);}
            public Subject lead(Connection c,UUID tenant,UUID id)throws SQLException{return original.lead(c,tenant,id);}
            public Task task(Connection c,UUID tenant,UUID id)throws SQLException{return original.task(c,tenant,id);}
            public Contact contact(Connection c,UUID tenant,UUID id)throws SQLException{return original.contact(c,tenant,id);}
            public Subject reviewTrigger(Connection c,UUID tenant,UUID id)throws SQLException{return original.reviewTrigger(c,tenant,id);}
            public boolean contactExistsForTask(Connection c,UUID tenant,UUID id)throws SQLException{return original.contactExistsForTask(c,tenant,id);}
            public Assignment assignment(Connection c,UUID tenant,UUID id)throws SQLException{return original.assignment(c,tenant,id);}
            public Opportunity opportunityForContact(Connection c,UUID tenant,UUID id)throws SQLException{return original.opportunityForContact(c,tenant,id);}
            public Decision decision(Connection c,UUID tenant,UUID id)throws SQLException{return original.decision(c,tenant,id);}
            public Wait latestWait(Connection c,UUID tenant,UUID id)throws SQLException{return original.latestWait(c,tenant,id);}
            public Set<UUID> retainedAssignmentOwners(Connection c,UUID tenant)throws SQLException{return original.retainedAssignmentOwners(c,tenant);}
            public Subject capturedLead(Connection c,UUID tenant,String account,String digest)throws SQLException{return original.capturedLead(c,tenant,account,digest);}
        };
    }
}


