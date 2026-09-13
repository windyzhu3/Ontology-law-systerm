package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqCommandStore;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;

/** R1_IDENTITY_COMMAND_V1. No business Handler engine, events, projections or inverse business locks. */
public final class IdentityCommandRuntime {
    @FunctionalInterface public interface Dependencies {boolean open(Connection c,UUID tenant,UUID appointment)throws SQLException;}
    public record Result(CommandReceiptReader.Receipt receipt,String etag,String location,boolean replay,boolean conflict,String currentETag) {}
    public static final class Precondition extends RuntimeException {
        private final String etag;
        public Precondition(String etag){super("IDENTITY_PRECONDITION_REQUIRED",null,false,false);this.etag=etag;}
        public String etag(){return etag;}
    }
    private final IdentityCommands.Port facts=IdentityCommands.databaseBacked();
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final AuditAppender audit;private final IdentityResourceProtection resources;private final IdentityCandidateProtection candidates;
    private final ExternalSubjectProtection subjects;private final IdentityProviderDirectory directory;private final String provider;
    private final Dependencies dependencies;
    public IdentityCommandRuntime(AuditAppender audit,IdentityResourceProtection resources,IdentityCandidateProtection candidates,ExternalSubjectProtection subjects,String provider,IdentityProviderDirectory directory,Dependencies dependencies){this.audit=audit;this.resources=resources;this.candidates=candidates;this.subjects=subjects;this.provider=provider;this.directory=directory;this.dependencies=Objects.requireNonNull(dependencies);}
    public Result execute(Connection connection,CommandEnvelope envelope)throws SQLException {
        var handler=IdentityCommands.handler(envelope.type().name());var body=IdentityCommands.validate(handler,envelope.payload());
        if(envelope.actor().onBehalfAppointmentId()!=null)throw new Failure("NOT_AUTHORIZED");
        var precondition=envelope.identityPrecondition();UUID id=precondition==null?null:precondition.id();String match=precondition==null?null:precondition.ifMatch();
        if(!handler.create()&&id==null||handler.create()&&(id!=null||match!=null)||match!=null&&!match.matches("\"identity\\.[A-Za-z0-9_-]{43}\""))throw new Failure("VALIDATION_FAILED");
        var digested=new LinkedHashMap<String,Object>();digested.put("body",body);digested.put("ifMatch",match);byte[] payload=CanonicalJson.digest(CanonicalJson.encode(digested));
        return inTransaction(connection,Capability.QUERY,c->{
            R1BusinessFence.databaseBacked().exclusive(c,envelope.actor().tenantId());var store=new JooqCommandStore(c);store.lockCommand(envelope);
            authorization.lockForMutation(c,envelope.actor().tenantId());setLocalRole(c,Capability.COMMAND);facts.lockRows(c,envelope.actor().tenantId());setLocalRole(c,Capability.QUERY);
            if(!ActorIdentityReader.databaseBacked().active(c,envelope.actor()))throw new Failure("NOT_AUTHORIZED");
            var stored=CommandReceiptReader.databaseBacked().read(c,envelope.actor().tenantId(),envelope.commandId());
            if(stored!=null) {
                var original=CommandReceiptAuthorizationReader.databaseBacked().read(c,envelope.actor(),envelope.commandId());
                if(original==null)throw new Failure("COMMAND_PAYLOAD_CONFLICT");
                if(!original.recovery().identity())throw new Failure("COMMAND_PAYLOAD_CONFLICT");
                validateOriginal(stored,original);var current=authorizeOriginal(c,envelope.actor(),original);
                @SuppressWarnings("unchecked") var target=(Map<String,Object>)original.recovery().identityScope().get("target");
                var scope=CommandScope.identity(envelope,target);
                // Scope includes immutable Actor/type; update path remains bound even though it is outside the body.
                boolean wrongTarget=!handler.create()&&!Objects.equals(target.get("id"),id.toString());
                var replay=store.existingOrValidateNew(envelope,scope,payload,ignored->null);
                if(wrongTarget||replay instanceof CommandResult.Conflict)return new Result(stored,null,null,false,true,null);
                String stale="STALE_IDENTITY".equals(stored.outcome().rejectionCode())?resources.tag(envelope.actor(),facts.find(c,envelope.actor().tenantId(),handler.kind(),original.recovery().identityTarget().id()).fact(),current.digest()):null;
                return result(c,envelope,stored,current,true,stale);
            }
            IdentityCandidateProtection.Verified candidate=null;ProviderBinding binding=null;
            if(handler.kind()==Kind.PRINCIPAL&&handler.create()) {
                // Access precedes candidate decryption/remote directory work.
                facts.authorize(c,envelope.actor(),handler.authority(),facts.root(c,envelope.actor().tenantId()),null);
                candidate=candidates.verify((String)body.get("providerUserSelector"),envelope.actor(),provider,directory.issuer());
                binding=new ProviderBinding(provider,subjects.digest(envelope.actor().tenantId(),candidate.subject()));
            }
            var context=facts.resolve(c,envelope.actor(),handler,id,body,binding);
            if(!handler.create()&&match==null)throw new Precondition(resources.tag(envelope.actor(),context.target().fact(),context.access().digest()));
            if(candidate!=null){candidate.requireFresh(SensitiveReadClock.now(c));if(!candidate.subject().equals(directory.candidateEnabled(candidate.subject()).subject()))throw new Failure("VALIDATION_FAILED");candidate.requireFresh(SensitiveReadClock.now(c));}
            var scope=CommandScope.identity(envelope,context.scopeTarget());
            // The receipt reader's inner join cannot establish that a permanent Slot is absent.
            // Under the tenant command UUID lock, any orphan/ambiguous Slot must fail closed.
            if(store.existingOrValidateNew(envelope,scope,payload,ignored->null)!=null)throw new SQLException("Inconsistent command receipt visibility","23000");
            setLocalRole(c,Capability.COMMAND);UUID slot=store.occupy(envelope,scope,payload);Savepoint domain=c.setSavepoint();
            Mutation mutation=null;String rejected=null,currentTag=null;
            try {
                setLocalRole(c,Capability.QUERY);context=facts.resolve(c,envelope.actor(),handler,id,body,binding);
                if(!handler.create()) {currentTag=resources.tag(envelope.actor(),context.target().fact(),context.access().digest());if(!MessageDigest.isEqual(match.getBytes(java.nio.charset.StandardCharsets.UTF_8),currentTag.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new Failure("STALE_IDENTITY");}
                boolean open=handler.command().equals("END_APPOINTMENT")&&dependencies.open(c,envelope.actor().tenantId(),id);
                setLocalRole(c,Capability.COMMAND);mutation=facts.mutate(c,envelope.actor(),handler,context,body,binding,open);
                if(!mutation.changed())c.rollback(domain);
            }catch(Failure rejection){c.rollback(domain);rejected=rejection.code();}
            setLocalRole(c,Capability.COMMAND);var outcome=store.receipt(envelope,slot,mutation==null?CommandOutcome.Status.REJECTED:mutation.changed()?CommandOutcome.Status.SUCCEEDED:CommandOutcome.Status.NO_CHANGE,mutation==null?null:mutation.fact(),rejected);
            var result=new LinkedHashMap<String,Object>();result.put("outcome",outcome.status().name());result.put("resultFact",selector(outcome.resultFact()));result.put("rejectionCode",rejected);
            var recovery=new LinkedHashMap<String,Object>();recovery.put("profile","R1_IDENTITY_RECEIPT_RECOVERY_V1");recovery.put("scope",scope.fields());recovery.put("target",selector(mutation==null?context.target()==null?null:context.target().fact():mutation.fact()));recovery.put("authorizationAnchor",selector(context.anchor().fact()));
            String summary=CanonicalJson.encode(Map.of("result",result,"authorizationEvidence",context.access().authorization().evidence(),"receiptRecovery",recovery));
            setLocalRole(c,Capability.AUDIT);audit.append(c,new AuditAppender.IdentityEntry(UUID.randomUUID(),envelope.commandId(),handler.command(),envelope.correlationId(),outcome.status().name(),context.access().authorization(),summary));
            setLocalRole(c,Capability.QUERY);var receipt=CommandReceiptReader.databaseBacked().read(c,envelope.actor().tenantId(),envelope.commandId());
            return result(c,envelope,receipt,context.access(),false,"STALE_IDENTITY".equals(rejected)?currentTag:null);
        });
    }
    public String precondition(Connection c,Actor actor,String command,UUID id)throws SQLException {
        return inTransaction(c,Capability.QUERY,x->{R1BusinessFence.databaseBacked().shared(x,actor.tenantId());authorization.lockForEvaluation(x,actor.tenantId());var h=IdentityCommands.handler(command);var context=facts.resolve(x,actor,h,id,Map.of(),null);return resources.tag(actor,context.target().fact(),context.access().digest());});
    }
    private Result result(Connection c,CommandEnvelope e,CommandReceiptReader.Receipt receipt,Access access,boolean replay,String stale)throws SQLException {
        Subject fact=receipt.outcome().resultFact();
        if(fact!=null) {
            var current=facts.find(c,e.actor().tenantId(),Kind.of(fact.type()),fact.id());
            try{access=facts.resourceAccess(c,e.actor(),IdentityCommands.handler(e.type().name()).authority(),current);}catch(Failure denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}
        }
        String tag=fact==null?null:resources.tag(e.actor(),fact,access.digest());
        String location=fact==null?null:"/api/v1/commands/"+e.commandId()+"/receipt";
        return new Result(receipt,tag,location,replay,false,stale);
    }
    public static Map<String,Object> selector(Subject fact){return fact==null?null:Map.of("type",fact.type(),"id",fact.id().toString(),"revision",fact.revision());}
    public static void validateOriginal(CommandReceiptReader.Receipt receipt,CommandReceiptAuthorizationReader.Original original) {
        if(!receipt.commandType().equals(original.commandType())||!receipt.outcome().status().name().equals(original.outcome())||!"INTERNAL_ADMIN".equals(receipt.envelope())||!MessageDigest.isEqual(receipt.scopeDigest(),original.recovery().scopeDigest())||!Objects.equals(receipt.outcome().resultFact(),original.resultFact())||!Objects.equals(receipt.outcome().rejectionCode(),original.rejectionCode()))throw new CommandReceiptAuthorizationReader.InvalidMetadata();
    }
    public static Access authorizeOriginal(Connection c,Actor actor,CommandReceiptAuthorizationReader.Original original)throws SQLException {
        var reader=IdentityCommands.databaseBacked();var recovery=original.recovery();var handler=IdentityCommands.handler(original.commandType());
        if(actor.onBehalfAppointmentId()!=null)throw new Failure("NOT_AUTHORIZED");
        var anchor=reader.find(c,actor.tenantId(),Kind.ORGANIZATION,recovery.identityAnchor().id());if(anchor==null||anchor.fact().revision()<recovery.identityAnchor().revision())throw new Failure("NOT_AUTHORIZED");
        var target=recovery.identityTarget()==null?null:reader.find(c,actor.tenantId(),handler.kind(),recovery.identityTarget().id());
        if(recovery.identityTarget()!=null&&(target==null||target.fact().revision()<recovery.identityTarget().revision()))throw new Failure("NOT_AUTHORIZED");
        @SuppressWarnings("unchecked") var attempted=(Map<String,Object>)recovery.identityScope().get("target");
        if(!reader.recoveryMatches(c,actor.tenantId(),handler,attempted,target))throw new Failure("NOT_AUTHORIZED");
        if(handler.rootRequired()&&!reader.root(c,actor.tenantId()).fact().id().equals(anchor.fact().id()))throw new Failure("NOT_AUTHORIZED");
        if(target!=null&&!handler.rootRequired()) {
            UUID parent=handler.kind()==Kind.ORGANIZATION&&target.values().get("parentOrganizationId")!=null?UUID.fromString((String)target.values().get("parentOrganizationId")):null;
            boolean bound=handler.kind()==Kind.ORGANIZATION?(anchor.fact().id().equals(parent)||!handler.create()&&anchor.fact().id().equals(target.fact().id())):anchor.fact().id().equals(target.organization());
            if(!bound)throw new Failure("NOT_AUTHORIZED");
        }
        if(handler.create()&&handler.kind()==Kind.ORGANIZATION&&!anchor.fact().id().toString().equals(attempted.get("parentId")))throw new Failure("NOT_AUTHORIZED");
        return reader.authorizeCommand(c,actor,handler,anchor,target,attempted);
    }
}
