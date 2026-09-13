package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.lead.*;
import java.sql.Connection;
import java.util.*;

/** API receipt orchestration; the execution runtime owns the acknowledged commit boundary. */
public final class CommandReceiptRecoveryService {
    public record Response(int status,Map<String,Object> body,String cacheControl,String errorCode) {}
    private final io.github.windyzhu3.ontologylaw.execution.CommandReceiptReadRuntime runtime;
    public CommandReceiptRecoveryService(R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node) {
        Objects.requireNonNull(protection);
        runtime=new io.github.windyzhu3.ontologylaw.execution.CommandReceiptReadRuntime(R1AuthorizationReaders.databaseBacked(sources,services),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked(node));
    }
    public Response read(Connection c,Actor actor,UUID command,UUID correlation) {
        try {return new Response(200,runtime.read(c,actor,command,correlation).body(),"no-store",null);}
        catch(io.github.windyzhu3.ontologylaw.execution.CommandReceiptReadRuntime.Failure safe){return new Response(safe.status(),null,"no-store",safe.code());}
    }
}
