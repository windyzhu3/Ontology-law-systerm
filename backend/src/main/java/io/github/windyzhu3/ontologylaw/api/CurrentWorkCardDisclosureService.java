package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.SensitiveReadRuntime;
import io.github.windyzhu3.ontologylaw.lead.*;
import java.sql.Connection;
import java.util.*;

public final class CurrentWorkCardDisclosureService {
    public record Response(int status,Map<String,Object> body,String etag,String cacheControl,String vary,String errorCode) {}
    private final SensitiveReadRuntime runtime;
    private final CurrentWorkCardSources sources;
    public CurrentWorkCardDisclosureService(LeadProtection protection,R1SourcePolicyRegistry policies,String executionNode) {
        var authorization=AuthorizationService.databaseBacked();
        runtime=new SensitiveReadRuntime(authorization,AuditAppender.databaseBacked(executionNode));
        sources=new CurrentWorkCardSources(authorization,Objects.requireNonNull(protection),Objects.requireNonNull(policies));
    }
    public Response read(Connection c,Actor actor,UUID correlation,String ifNoneMatch) {
        try {
            var committed=runtime.read(c,actor,correlation,ifNoneMatch,(connection,now)->sources.read(connection,actor,now));
            return new Response(committed.status(),committed.body(),committed.etag(),"private, no-cache","Authorization",null);
        } catch(SensitiveReadRuntime.Failure failure) {
            return new Response(failure.status(),null,null,"private, no-cache","Authorization",failure.code());
        }
    }
}
