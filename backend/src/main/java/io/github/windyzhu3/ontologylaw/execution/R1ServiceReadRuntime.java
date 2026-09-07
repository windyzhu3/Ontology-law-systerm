package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.time.Instant;

/** Read-only R1 SERVICE transaction. No response escapes before commit acknowledgement. */
public final class R1ServiceReadRuntime {
    private R1ServiceReadRuntime() {}
    public static Instant databaseTime(Connection c)throws SQLException{return SensitiveReadClock.now(c);}
    @FunctionalInterface public interface Work<T> { T read(Connection connection,Instant checkedAt)throws SQLException; }
    public static final class Failure extends RuntimeException {
        private final int status;private final String code;
        public Failure(int status,String code){super(code,null,false,false);this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }
    public static <T>T read(Connection connection,Actor actor,Work<T> work) {
        if(actor==null)throw new Failure(401,"UNAUTHENTICATED");
        if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw new Failure(403,"NOT_AUTHORIZED");
        try {
            return inTransaction(connection,Capability.QUERY,c->{
                R1BusinessFence.databaseBacked().shared(c,actor.tenantId());
                AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());
                return work.read(c,SensitiveReadClock.now(c));
            });
        } catch(Failure safe){throw safe;}
        catch(SQLException|RuntimeException failure){throw new Failure(503,"SERVICE_UNAVAILABLE");}
    }
}
