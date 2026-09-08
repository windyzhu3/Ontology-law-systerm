package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

/** ADR0013 single-transaction receipt disclosure; nothing leaves before commit acknowledgement. */
public final class CommandReceiptReadRuntime {
    public record Committed(Map<String,Object> body) {}
    public static final class Failure extends RuntimeException {
        private final int status;private final String code;
        public Failure(int status,String code){super(code,null,false,false);this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final ActorIdentityReader identities=ActorIdentityReader.databaseBacked();
    private final CommandReceiptAuthorizationReader audits=CommandReceiptAuthorizationReader.databaseBacked();
    private final CommandReceiptReader receipts=CommandReceiptReader.databaseBacked();
    private final R1ReceiptAuthorizationPolicy policy;
    private final AuditAppender appender;
    public CommandReceiptReadRuntime(R1AuthorizationFacts facts,AuditAppender appender){policy=new R1ReceiptAuthorizationPolicy(facts);this.appender=Objects.requireNonNull(appender);}
    public Committed read(Connection connection,Actor actor,UUID command,UUID correlation) {
        if(actor==null)throw new Failure(401,"UNAUTHENTICATED");
        Objects.requireNonNull(command);Objects.requireNonNull(correlation);boolean[] committing={false};
        try {
            return inTransaction(connection,Capability.QUERY,c->{
                R1BusinessFence.databaseBacked().shared(c,actor.tenantId());authorization.lockForEvaluation(c,actor.tenantId());
                if(!identities.active(c,actor))throw new Failure(403,"NOT_AUTHORIZED");
                var original=audits.read(c,actor,command);if(original==null)throw new Failure(404,"NOT_FOUND");
                var receipt=receipts.read(c,actor.tenantId(),command);
                if(receipt==null||!receipt.commandType().equals(original.commandType())||!receipt.outcome().status().name().equals(original.outcome())
                        ||!MessageDigest.isEqual(receipt.scopeDigest(),original.recovery().scopeDigest()))throw new CommandReceiptAuthorizationReader.InvalidMetadata();
                String envelope="CAPTURE_LEAD".equals(receipt.commandType())?(actor.principalKind()==PrincipalKind.SERVICE?"SERVICE_ACTOR":"INTERNAL_ADMIN"):"INTERNAL_TASK";
                if(!envelope.equals(receipt.envelope()))throw new CommandReceiptAuthorizationReader.InvalidMetadata();
                policy.authorize(c,actor,original,SensitiveReadClock.now(c));
                if(!identities.active(c,actor))throw new Failure(403,"NOT_AUTHORIZED");
                var current=policy.authorize(c,actor,original,SensitiveReadClock.now(c));
                var body=receipt.projection(actor);
                try {
                    setLocalRole(c,Capability.AUDIT);
                    appender.append(c,new AuditAppender.ReceiptDisclosureEntry(UUID.randomUUID(),correlation,command,receipt.outcome().receiptId(),receipt.selector(),current.request().subject(),current));
                } catch(SQLException|RuntimeException failed){throw new Failure(503,"SERVICE_UNAVAILABLE");}
                committing[0]=true;return new Committed(body);
            });
        } catch(Failure safe){throw safe;}
        catch(CommandReceiptAuthorizationReader.InvalidMetadata invalid){throw new Failure(503,"SERVICE_UNAVAILABLE");}
        catch(SQLException|RuntimeException failed){
            if(committing[0])throw new Failure(503,"SERVICE_UNAVAILABLE");
            for(Throwable cause=failed;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql) {
                String state=sql.getSQLState();if(state!=null&&(state.startsWith("08")||Set.of("55P03","57014","40P01","40001","23000").contains(state)))throw new Failure(503,"SERVICE_UNAVAILABLE");
            }
            throw new Failure(500,"INTERNAL_ERROR");
        }
    }
}
