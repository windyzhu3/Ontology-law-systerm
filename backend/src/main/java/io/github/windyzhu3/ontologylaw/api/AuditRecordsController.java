package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.AuditRecordsApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.audit.AuditRecordReader.*;
import io.github.windyzhu3.ontologylaw.execution.AuditReadRuntime;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditRecordsController implements AuditRecordsApi {
 public static final class Services {
  private final ActorContextResolver.Connections connections;private final AuditReadRuntime runtime;private final Set<UUID> tenants;
  public Services(ActorContextResolver.Connections connections,AuditAppender audit,AuditRecordProtection protection){this(connections,audit,protection,null);}
  public Services(ActorContextResolver.Connections connections,AuditAppender audit,AuditRecordProtection protection,Set<UUID> tenants){this.connections=connections;runtime=new AuditReadRuntime(audit,protection);this.tenants=tenants==null?null:Set.copyOf(tenants);}
  Map<String,Object> read(Actor actor,Query query,UUID id,Relation relation){if(tenants!=null&&!tenants.contains(actor.tenantId()))throw new R1HttpFailure("NOT_AUTHORIZED");try(var c=connections.open()){return id==null?runtime.read(c,actor,query):relation==null?runtime.detail(c,actor,id):runtime.related(c,actor,id,relation,query);}catch(IdentityCommands.Failure refused){throw new R1HttpFailure(refused.code());}catch(Exception unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
 }
 private final ObjectProvider<Services> services;
 public AuditRecordsController(ObjectProvider<Services> services){this.services=services;}
 private static Actor actor(UUID behalf){Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(behalf!=null||!(principal instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
 private <T> ResponseEntity<T> read(UUID behalf,Query query,UUID id,Relation relation,Class<T> type){var actor=actor(behalf);var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(service.read(actor,query,id,relation),type));}
 private static Query query(OffsetDateTime start,OffsetDateTime end,String scope,String result,String search,Integer limit,String cursor){return new Query(start==null?null:start.toInstant(),end==null?null:end.toInstant(),scope,result,search,limit==null?20:limit,cursor);}
 public ResponseEntity<AuditRecordPageV1> listAuditRecords(UUID own,UUID behalf,OffsetDateTime start,OffsetDateTime end,String scope,String result,String search,Integer limit,String cursor){return read(behalf,query(start,end,scope,result,search,limit,cursor),null,null,AuditRecordPageV1.class);}
 public ResponseEntity<AuditRecordV1> getAuditRecord(UUID id,UUID own,UUID behalf){return read(behalf,new Query(null,null,null,null,null,20,null),id,null,AuditRecordV1.class);}
 public ResponseEntity<AuditRecordPageV1> listRelatedAuditRecords(UUID id,String relation,UUID own,UUID behalf,OffsetDateTime start,OffsetDateTime end,String scope,String result,String search,Integer limit,String cursor){Relation selected;try{selected=Relation.valueOf(relation);}catch(IllegalArgumentException invalid){throw new R1HttpFailure("VALIDATION_FAILED");}return read(behalf,query(start,end,scope,result,search,limit,cursor),id,selected,AuditRecordPageV1.class);}
}
