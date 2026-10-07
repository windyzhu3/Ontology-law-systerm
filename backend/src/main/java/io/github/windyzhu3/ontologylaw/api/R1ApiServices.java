package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.UUID;

/** Role-local application service assembly, using public Owner factories only. */
public final class R1ApiServices {
    private final RuntimeDatabase database;
    private final CommandReceiptRecoveryService receipts;
    private final R1CommandService commands;
    private final boolean opportunityEnabled;
    private final CurrentWorkCardDisclosureService cards;
    private final R1ProjectionReadinessService readiness;
    private final DueR1TaskDiscoveryService discovery;
    private final R2OpportunityDiscoveryService opportunityDiscovery;
    private final OwnerExceptionObservationDiscovery ownerExceptionDiscovery;
    private final R2OpportunityOwnerExceptionReadService ownerExceptionReads;
    private final R2OpportunityLedgerReadService opportunityLedgerReads;
    private final R2OpportunityClosureReadService opportunityClosureReads;
    private final R2CustomerRequirementsReadService customerRequirementReads;
    private final R2MaterialsReadService materialReads;
    private final R2QuoteReadService quoteReads;
    private final R2FollowupAttemptReadService attemptReads;
    private final R2ContractReadService contractReads;
    private final R2TransferReadService transferReads;
    private final R2ManagementLedgerReadService managementReads;
    private final R2TeamManagementReadService teamReads;
    private final R25BusinessOverviewReadService businessOverview;
    private final R25AiCandidateService aiCandidates;
    private final R25LeadManagementReadService leadManagement;
    private final R2ContractGenerationService contractGeneration;
    private final ContractPreparationDiscovery contractDiscovery;
    private final R1ProjectionConsumer consumer;
    private final LeadIntakeSources intakeSources;
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey) {
        this(database,sources,protection,services,node,cursorKey,java.util.List.of());
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,null);
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,io.github.windyzhu3.ontologylaw.api.internal.storage.MaterialObjectStoreFactory.configured());
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,materialStore,null);
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,materialStore,contractProtection,null);
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments) {this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,materialStore,contractProtection,payments,tenant->null);}
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments,java.util.function.Function<UUID,UUID> transferDestinations) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,materialStore,contractProtection,payments,transferDestinations,R25ResponsesAiModel.disabled());
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments,java.util.function.Function<UUID,UUID> transferDestinations,R25AiModel aiModel) {
        this(database,sources,protection,services,node,cursorKey,intakeMetadata,opportunityProtection,materialStore,contractProtection,payments,transferDestinations,aiModel,new R1HumanSourceBinding(java.util.List.of(),sources));
    }
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey,java.util.List<LeadIntakeSources.Source> intakeMetadata,io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection opportunityProtection,io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore materialStore,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection,io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService payments,java.util.function.Function<UUID,UUID> transferDestinations,R25AiModel aiModel,R1HumanSourceBinding humanSources) {
        this.opportunityEnabled=opportunityProtection!=null;
        var aiSources=opportunityProtection==null?null:new R25AiSourceReadService(cursorKey,protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.aiCandidates=aiSources==null?null:new R25AiCandidateService(cursorKey,(actor,id,task)->{try(var connection=database.open()){return aiSources.read(connection,actor,id,task);}},aiModel,java.time.Clock.systemUTC());
        this.contractGeneration=new R2ContractGenerationService(materialStore,contractProtection);
        this.intakeSources=new LeadIntakeSources(sources,intakeMetadata,humanSources);
        this.database=java.util.Objects.requireNonNull(database);this.receipts=new CommandReceiptRecoveryService(sources,protection,services,node);
        var contracts=contractProtection==null||opportunityProtection==null?null:R2ContractServices.create(contractProtection,opportunityProtection,materialStore,payments);

        this.businessOverview=new R25BusinessOverviewReadService(cursorKey,protection,sources,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.leadManagement=new R25LeadManagementReadService(cursorKey,protection,sources,intakeMetadata,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.teamReads=new R2TeamManagementReadService(cursorKey,protection,opportunityProtection,contractProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.managementReads=new R2ManagementLedgerReadService(cursorKey,protection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.contractReads=contracts==null?null:new R2ContractReadService(contracts,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node),cursorKey);
        var transferPorts=contracts==null?null:new TransferWorkflowPorts(contractProtection,opportunityProtection,materialStore,transferDestinations);
        var transfers=transferPorts==null?null:io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService.databaseBacked(transferPorts);
        this.transferReads=transferPorts==null?null:new R2TransferReadService(contractProtection,opportunityProtection,contracts,new ContractWorkflowPorts(opportunityProtection,materialStore),transferPorts,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        var transferRecovery=transferPorts==null?null:new R2TransferRecoveryService(transferPorts,opportunityProtection);
        this.contractDiscovery=contracts==null?null:new ContractPreparationDiscovery(contracts,cursorKey,transferRecovery);
        this.commands=new R1CommandService(database,sources,protection,services,node,opportunityProtection,contracts,transfers,transferRecovery,humanSources);
        this.cards=new CurrentWorkCardDisclosureService(protection,sources,node,opportunityProtection);
        this.readiness=new R1ProjectionReadinessService(sources);this.discovery=new DueR1TaskDiscoveryService(cursorKey,sources,protection);this.consumer=new R1ProjectionConsumer(sources);
        this.opportunityDiscovery=new R2OpportunityDiscoveryService(cursorKey);
        this.ownerExceptionDiscovery=new OwnerExceptionObservationDiscovery(cursorKey);
        this.opportunityLedgerReads=opportunityProtection==null?null:new R2OpportunityLedgerReadService(cursorKey,protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.opportunityClosureReads=opportunityProtection==null?null:new R2OpportunityClosureReadService(cursorKey,protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.customerRequirementReads=opportunityProtection==null?null:new R2CustomerRequirementsReadService(cursorKey,protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.attemptReads=opportunityProtection==null?null:new R2FollowupAttemptReadService(opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.quoteReads=opportunityProtection==null?null:new R2QuoteReadService(opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
        this.materialReads=opportunityProtection==null?null:new R2MaterialsReadService(cursorKey,protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node),materialStore);
        this.ownerExceptionReads=new R2OpportunityOwnerExceptionReadService(cursorKey,protection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
    }
    java.util.Map<String,Object> intakeSources(Actor actor) {
        if(actor.principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN || actor.onBehalfAppointmentId()!=null)
            throw new R1HttpFailure("NOT_AUTHORIZED");
        try(var c=database.open()) {
            return java.util.Map.of("sources",new io.github.windyzhu3.ontologylaw.execution.LeadIntakeReadRuntime().read(c,actor,connection -> intakeSources.read(connection,actor)));
        } catch(java.sql.SQLException | RuntimeException unavailable) { throw new R1HttpFailure("SERVICE_UNAVAILABLE"); }
    }
    R1ProjectionReadinessService.Response readiness(Actor actor){
        try(var c=database.open()){return readiness.check(c,actor);}catch(java.sql.SQLException unavailable){return new R1ProjectionReadinessService.Response(503,"SERVICE_UNAVAILABLE","no-store");}
    }
    DueR1TaskDiscoveryService.Response due(Actor actor,io.github.windyzhu3.ontologylaw.api.adapter.generated.model.RecoveryTypeV1 type,Integer limit,String cursor){
        try(var c=database.open()){return discovery.list(c,actor,type,limit,cursor);}catch(java.sql.SQLException unavailable){return new DueR1TaskDiscoveryService.Response(503,null,"SERVICE_UNAVAILABLE");}
    }
    R1ProjectionConsumer.Response consume(Actor actor,io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ConsumeR1ProjectionV1 request){
        try(var c=database.open()){return consumer.consume(c,actor,request);}catch(java.sql.SQLException unavailable){return new R1ProjectionConsumer.Response(503,"SERVICE_UNAVAILABLE");}
    }
    CurrentWorkCardDisclosureService.Response waiting(Actor actor,UUID id,int limit,String cursor){
        try(var c=database.open()){return cards.readWaiting(c,actor,UUID.randomUUID(),id,limit,cursor);}
        catch(java.sql.SQLException unavailable){return new CurrentWorkCardDisclosureService.Response(503,null,null,"no-store","Authorization","SERVICE_UNAVAILABLE");}
    }
    CurrentWorkCardDisclosureService.Response card(Actor actor,UUID correlation,String ifNoneMatch,UUID selectedTaskId){
        try(var c=database.open()){return cards.read(c,actor,correlation,ifNoneMatch,selectedTaskId);}
        catch(java.sql.SQLException unavailable){return new CurrentWorkCardDisclosureService.Response(503,null,null,"private, no-cache","Authorization","SERVICE_UNAVAILABLE");}
    }
    R1CommandService.Response opportunityCommand(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope envelope){if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return commands.execute(envelope);}
    R2OpportunityDiscoveryService.Response opportunityCandidates(Actor actor,R2OpportunityDiscoveryService.Kind kind,int limit,String cursor){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return opportunityDiscovery.list(c,actor,kind,limit,cursor);}
        catch(java.sql.SQLException unavailable){return new R2OpportunityDiscoveryService.Response(503,null,"SERVICE_UNAVAILABLE");}
    }
    OwnerExceptionObservationDiscovery.Response ownerExceptionCandidates(Actor actor,int limit,String cursor){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return ownerExceptionDiscovery.list(c,actor,limit,cursor);}
        catch(java.sql.SQLException unavailable){return new OwnerExceptionObservationDiscovery.Response(503,null,"SERVICE_UNAVAILABLE");}
    }
    java.util.Map<String,Object> opportunities(Actor actor,String operation,UUID id,int limit,String cursor,String search,String state){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return opportunityLedgerReads.read(c,actor,operation,id,limit,cursor,search,state);}
        catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure denied){throw new R1HttpFailure(denied.code());}
        catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    java.util.Map<String,Object> contractCandidates(Actor actor,int limit,String cursor){return contractCall(c->contractDiscovery.list(c,actor,limit,cursor));}
    R1CommandService.Response contractCommand(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope envelope){if(contractReads==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return commands.execute(envelope);}
    java.util.Map<String,Object> generateContract(Actor actor,UUID id,java.util.Map<String,Object> payload){
        try{return contractGeneration.generate(actor,id,()->contractCall(c->contractReads.generation(c,actor,id,payload)));}catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new R1HttpFailure("VALIDATION_FAILED");}
    }
    byte[] contractDocument(Actor actor,UUID id,UUID contract,UUID version){return contractCall(c->contractReads.document(c,actor,id,contract,version));}
    byte[] transferDocument(Actor actor,UUID task,UUID version){return contractCall(c->transferReads.document(c,actor,task,version));}
    java.util.Map<String,Object> classificationCorrection(Actor actor,UUID opportunity){return contractCall(c->transferReads.classificationCorrection(c,actor,opportunity));}
    java.util.Map<String,Object> transferTask(Actor actor,UUID task){return contractCall(c->transferReads.task(c,actor,task));}
    java.util.Map<String,Object> contractTask(Actor actor,UUID task){return contractCall(c->contractReads.task(c,actor,task));}
    java.util.Map<String,Object> contracts(Actor actor,UUID id){return contractCall(c->contractReads.read(c,actor,id));}
    java.util.Map<String,Object> contractLedger(Actor actor,int limit,String cursor,String search,String state){return contractCall(c->contractReads.ledger(c,actor,limit,cursor,search,state));}
    java.util.Map<String,Object> managementList(Actor actor,String view,int limit,String cursor,String search,String state){return managementCall(c->managementReads.list(c,actor,view,limit,cursor,search,state));}
    java.util.Map<String,Object> managementDetail(Actor actor,String view,UUID id){return managementCall(c->managementReads.detail(c,actor,view,id));}
    java.util.Map<String,Object> teamList(Actor actor,String view,int limit,String cursor,String search,String state){return managementCall(c->teamReads.list(c,actor,view,limit,cursor,search,state));}
    java.util.Map<String,Object> teamDetail(Actor actor,String view,UUID id){return managementCall(c->teamReads.detail(c,actor,view,id));}
    java.util.Map<String,Object> leadManagementList(Actor actor,int limit,String cursor,String search,String source,String owner,String state){return managementCall(c->leadManagement.list(c,actor,limit,cursor,search,source,owner,state));}
    java.util.Map<String,Object> leadManagementDetail(Actor actor,UUID id){return managementCall(c->leadManagement.detail(c,actor,id));}
    java.util.Map<String,Object> leadManagementSources(Actor actor){return managementCall(c->leadManagement.sources(c,actor));}
    R25AiCandidateService.Candidate aiCandidates(Actor actor,UUID opportunity,R25AiCandidateContract.Task task){return aiCall(()->aiCandidates.generate(actor,opportunity,task));}
    void recheckAiSources(Actor actor,UUID opportunity,R25AiCandidateContract.Task task,String token){aiCall(()->{aiCandidates.recheck(actor,opportunity,task,token);return null;});}
    @FunctionalInterface private interface AiCall<T>{T run()throws java.sql.SQLException;}
    private <T>T aiCall(AiCall<T> call){
        if(aiCandidates==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try{return call.run();}catch(R25AiCandidateContract.Failure failure){throw new R1HttpFailure(switch(failure.code()){case "AI_SOURCE_CHANGED"->"STALE_SUBJECT";case "AI_NO_BASIS","AI_INPUT_TOO_LARGE"->"VALIDATION_FAILED";default->"SERVICE_UNAVAILABLE";});}
        catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure failure){throw new R1HttpFailure(failure.code());}
        catch(java.sql.SQLException failure){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    java.util.Map<String,Object> businessOverview(Actor actor,String month){return managementCall(c->businessOverview.summary(c,actor,month));}
    java.util.Map<String,Object> businessOverviewDetails(Actor actor,String metric,String month,int limit,String cursor){return managementCall(c->businessOverview.details(c,actor,metric,month,limit,cursor));}
    private <T>T managementCall(MaterialCall<T> call){try(var c=database.open()){return call.run(c);}catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure failure){throw new R1HttpFailure(failure.code());}catch(java.sql.SQLException failure){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
    private <T>T contractCall(MaterialCall<T> call){if(contractReads==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");try(var c=database.open()){return call.run(c);}catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure failure){throw new R1HttpFailure(failure.code());}catch(io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService.Blocked failure){throw new R1HttpFailure(failure.code());}catch(io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.Blocked failure){throw new R1HttpFailure(failure.code());}catch(java.sql.SQLException failure){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
    byte[] quoteDocument(Actor actor,UUID id,UUID quote){return materialCall(c->quoteReads.document(c,actor,id,quote));}
    java.util.Map<String,Object> quoteTask(Actor actor,UUID task){return materialCall(c->quoteReads.task(c,actor,task));}
    java.util.Map<String,Object> followupAttempts(Actor actor,UUID id){return materialCall(c->attemptReads.read(c,actor,id));}
    java.util.Map<String,Object> quotes(Actor actor,UUID id){return materialCall(c->quoteReads.read(c,actor,id));}
    java.util.Map<String,Object> materials(Actor actor,UUID id){return materialCall(c->materialReads.read(c,actor,id));}
    java.util.Map<String,Object> materialUpload(Actor actor,UUID id,UUID upload){return materialCall(c->materialReads.status(c,actor,id,upload));}
    java.util.Map<String,Object> uploadMaterial(Actor actor,UUID id,UUID upload,java.io.InputStream bytes){return materialCall(c->materialReads.upload(c,actor,id,upload,bytes));}
    R2MaterialsReadService.Content materialContent(Actor actor,UUID id,UUID version,String disposition){return materialCall(c->materialReads.content(c,actor,id,version,disposition));}
    @FunctionalInterface private interface MaterialCall<T>{T run(java.sql.Connection c)throws java.sql.SQLException;}
    private <T>T materialCall(MaterialCall<T> call){if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");try(var c=database.open()){return call.run(c);}catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure failure){throw new R1HttpFailure(failure.code());}catch(io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.Blocked failure){throw new R1HttpFailure(failure.code());}catch(java.sql.SQLException failure){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
    java.util.Map<String,Object> customerRequirements(Actor actor,UUID id,String query,boolean search){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return customerRequirementReads.read(c,actor,id,query,search);}
        catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure denied){throw new R1HttpFailure(denied.code());}
        catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    java.util.Map<String,Object> opportunityClosure(Actor actor,UUID id){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return opportunityClosureReads.read(c,actor,id);}
        catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure denied){throw new R1HttpFailure(denied.code());}
        catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    java.util.Map<String,Object> ownerExceptions(Actor actor,String operation,UUID id,Long revision,int limit,String cursor){
        if(!opportunityEnabled)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        try(var c=database.open()){return ownerExceptionReads.read(c,actor,operation,id,revision,limit,cursor);}
        catch(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure denied){throw new R1HttpFailure(denied.code());}
        catch(java.sql.SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    R1CommandService.Response command(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope envelope){return commands.execute(envelope);}
    java.util.Map<String,Object> precondition(Actor actor,io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type operation,UUID task,String kind){return commands.precondition(actor,operation,task,kind);}
    public CommandReceiptRecoveryService.Response receipt(Actor actor,UUID command,UUID correlation){
        try(var c=database.open()){return receipts.read(c,actor,command,correlation);}
        catch(java.sql.SQLException unavailable){return new CommandReceiptRecoveryService.Response(503,null,"no-store","SERVICE_UNAVAILABLE");}
    }
}
