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
    private final CurrentWorkCardDisclosureService cards;
    private final R1ProjectionReadinessService readiness;
    private final DueR1TaskDiscoveryService discovery;
    private final R1ProjectionConsumer consumer;
    public R1ApiServices(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node,byte[] cursorKey) {
        this.database=java.util.Objects.requireNonNull(database);this.receipts=new CommandReceiptRecoveryService(sources,protection,services,node);
        this.commands=new R1CommandService(database,sources,protection,services,node);
        this.cards=new CurrentWorkCardDisclosureService(protection,sources,node);
        this.readiness=new R1ProjectionReadinessService(sources);this.discovery=new DueR1TaskDiscoveryService(cursorKey);this.consumer=new R1ProjectionConsumer(sources);
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
    CurrentWorkCardDisclosureService.Response card(Actor actor,UUID correlation,String ifNoneMatch){
        try(var c=database.open()){return cards.read(c,actor,correlation,ifNoneMatch);}
        catch(java.sql.SQLException unavailable){return new CurrentWorkCardDisclosureService.Response(503,null,null,"private, no-cache","Authorization","SERVICE_UNAVAILABLE");}
    }
    R1CommandService.Response command(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope envelope){return commands.execute(envelope);}
    java.util.Map<String,Object> precondition(Actor actor,io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type operation,UUID task,String kind){return commands.precondition(actor,operation,task,kind);}
    public CommandReceiptRecoveryService.Response receipt(Actor actor,UUID command,UUID correlation){
        try(var c=database.open()){return receipts.read(c,actor,command,correlation);}
        catch(java.sql.SQLException unavailable){return new CommandReceiptRecoveryService.Response(503,null,"no-store","SERVICE_UNAVAILABLE");}
    }
}
