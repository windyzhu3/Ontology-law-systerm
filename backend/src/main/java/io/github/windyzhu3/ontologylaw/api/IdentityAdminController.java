package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.api.IdentityAdminApi;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.responsibility.IdentityDependencyReader;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Generated closed HTTP surface; trusted authentication and owner ports supply all authority. */
@RestController
public class IdentityAdminController implements IdentityAdminApi {
    public static final class Services {
        private final ActorContextResolver.Connections connections;
        private final IdentityCommandRuntime commands;private final IdentityAdminReadRuntime reads;
        private final Map<UUID,Services> tenants;
        public Services(Map<UUID,Services> tenants){this.tenants=Map.copyOf(tenants);connections=null;commands=null;reads=null;}
        public Services(ActorContextResolver.Connections connections,AuditAppender audit,IdentityResourceProtection resources,IdentityCandidateProtection candidates,ExternalSubjectProtection subjects,String provider,IdentityProviderDirectory directory){this.connections=connections;tenants=null;commands=new IdentityCommandRuntime(audit,resources,candidates,subjects,provider,directory,IdentityDependencyReader.databaseBacked()::open);reads=new IdentityAdminReadRuntime(audit,resources,candidates,provider,directory);}
        private Services tenant(Actor actor){var service=tenants.get(actor.tenantId());if(service==null)throw new R1HttpFailure("NOT_AUTHORIZED");return service;}
        IdentityCommandRuntime.Result write(Actor actor,String command,UUID key,UUID id,String tag,Object payload) {
            if(tenants!=null)return tenant(actor).write(actor,command,key,id,tag,payload);
            try(var c=connections.open()){return commands.execute(c,new CommandEnvelope(CommandEnvelope.Type.valueOf(command),key,UUID.randomUUID(),actor,R1WireModels.model(payload,Map.class),null,null,new CommandEnvelope.IdentityPrecondition(id,tag)));}
            catch(IdentityCommands.Failure failed){throw new R1HttpFailure(failed.code());}
            catch(IdentityCommandRuntime.Precondition missing){throw new IdentityHttpFailure("IDENTITY_PRECONDITION_REQUIRED",missing.etag(),null);}
            catch(Exception unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
        }
        Map<String,Object> read(Actor actor,String operation,String page,String option,Integer limit,String cursor,String search){if(tenants!=null)return tenant(actor).read(actor,operation,page,option,limit,cursor,search);try(var c=connections.open()){return reads.read(c,actor,operation,page,option,limit,cursor,search);}catch(IdentityCommands.Failure refused){throw new R1HttpFailure(refused.code());}catch(Exception unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
        String precondition(Actor actor,String command,UUID id){if(tenants!=null)return tenant(actor).precondition(actor,command,id);try(var c=connections.open()){return commands.precondition(c,actor,command,id);}catch(IdentityCommands.Failure refused){throw new R1HttpFailure(refused.code());}catch(Exception unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}}
    }
    private final ObjectProvider<Services> services;
    public IdentityAdminController(ObjectProvider<Services> services){this.services=services;}
    private Services service(){var service=services.getIfAvailable();if(service==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");return service;}
    private static Actor actor(UUID behalf){Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(behalf!=null||!(principal instanceof Actor actor)||actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new R1HttpFailure("NOT_AUTHORIZED");return actor;}
    private <T> ResponseEntity<T> write(String command,UUID key,UUID id,String tag,Object payload,UUID behalf,Class<T> type){
        var result=service().write(actor(behalf),command,key,id,tag,payload);var receipt=result.receipt();
        var reference=Map.<String,Object>of("commandId",receipt.commandId().toString(),"receiptId",receipt.outcome().receiptId().toString());
        if(result.conflict())throw new IdentityHttpFailure("COMMAND_PAYLOAD_CONFLICT",null,reference);
        if(receipt.outcome().status()==CommandOutcome.Status.REJECTED)throw new IdentityHttpFailure(receipt.outcome().rejectionCode(),result.currentETag(),null);
        return ResponseEntity.status(IdentityCommands.handler(command).create()?201:200).header("Cache-Control","no-store").header("ETag",result.etag()).header("Location",result.location()).body(R1WireModels.model(receipt.projection(actor(behalf)),type));
    }
    private <T> ResponseEntity<T> read(String operation,String page,String option,Integer limit,String cursor,String search,UUID behalf,Class<T> type){return ResponseEntity.ok().header("Cache-Control","no-store").body(R1WireModels.model(service().read(actor(behalf),operation,page,option,limit,cursor,search),type));}
    public ResponseEntity<IdentityPrincipalCommandReceiptV1> createIdentityPrincipal(UUID key,CreateIdentityPrincipalV1 body,UUID own,UUID behalf){return write("CREATE_IDENTITY_PRINCIPAL",key,null,null,body,behalf,IdentityPrincipalCommandReceiptV1.class);}
    public ResponseEntity<OrganizationUnitCommandReceiptV1> createOrganizationUnit(UUID key,CreateOrganizationUnitV1 body,UUID own,UUID behalf){return write("CREATE_ORGANIZATION_UNIT",key,null,null,body,behalf,OrganizationUnitCommandReceiptV1.class);}
    public ResponseEntity<AppointmentCommandReceiptV1> createAppointment(UUID key,CreateAppointmentV1 body,UUID own,UUID behalf){return write("CREATE_APPOINTMENT",key,null,null,body,behalf,AppointmentCommandReceiptV1.class);}
    public ResponseEntity<AuthorityGrantCommandReceiptV1> createAuthorityGrant(UUID key,CreateAuthorityGrantV1 body,UUID own,UUID behalf){return write("CREATE_AUTHORITY_GRANT",key,null,null,body,behalf,AuthorityGrantCommandReceiptV1.class);}
    public ResponseEntity<IdentityPrincipalCommandReceiptV1> renameIdentityPrincipal(UUID key,UUID id,String tag,RenameIdentityPrincipalV1 body,UUID own,UUID behalf){return write("RENAME_IDENTITY_PRINCIPAL",key,id,tag,body,behalf,IdentityPrincipalCommandReceiptV1.class);}
    public ResponseEntity<IdentityPrincipalCommandReceiptV1> suspendIdentityPrincipal(UUID key,UUID id,String tag,SuspendIdentityPrincipalV1 body,UUID own,UUID behalf){return write("SUSPEND_IDENTITY_PRINCIPAL",key,id,tag,body,behalf,IdentityPrincipalCommandReceiptV1.class);}
    public ResponseEntity<IdentityPrincipalCommandReceiptV1> resumeIdentityPrincipal(UUID key,UUID id,String tag,ResumeIdentityPrincipalV1 body,UUID own,UUID behalf){return write("RESUME_IDENTITY_PRINCIPAL",key,id,tag,body,behalf,IdentityPrincipalCommandReceiptV1.class);}
    public ResponseEntity<IdentityPrincipalCommandReceiptV1> disableIdentityPrincipal(UUID key,UUID id,String tag,DisableIdentityPrincipalV1 body,UUID own,UUID behalf){return write("DISABLE_IDENTITY_PRINCIPAL",key,id,tag,body,behalf,IdentityPrincipalCommandReceiptV1.class);}
    public ResponseEntity<OrganizationUnitCommandReceiptV1> renameOrganizationUnit(UUID key,UUID id,String tag,RenameOrganizationUnitV1 body,UUID own,UUID behalf){return write("RENAME_ORGANIZATION_UNIT",key,id,tag,body,behalf,OrganizationUnitCommandReceiptV1.class);}
    public ResponseEntity<OrganizationUnitCommandReceiptV1> closeOrganizationUnit(UUID key,UUID id,String tag,CloseOrganizationUnitV1 body,UUID own,UUID behalf){return write("CLOSE_ORGANIZATION_UNIT",key,id,tag,body,behalf,OrganizationUnitCommandReceiptV1.class);}
    public ResponseEntity<AppointmentCommandReceiptV1> suspendAppointment(UUID key,UUID id,String tag,SuspendAppointmentV1 body,UUID own,UUID behalf){return write("SUSPEND_APPOINTMENT",key,id,tag,body,behalf,AppointmentCommandReceiptV1.class);}
    public ResponseEntity<AppointmentCommandReceiptV1> resumeAppointment(UUID key,UUID id,String tag,ResumeAppointmentV1 body,UUID own,UUID behalf){return write("RESUME_APPOINTMENT",key,id,tag,body,behalf,AppointmentCommandReceiptV1.class);}
    public ResponseEntity<AppointmentCommandReceiptV1> endAppointment(UUID key,UUID id,String tag,EndAppointmentV1 body,UUID own,UUID behalf){return write("END_APPOINTMENT",key,id,tag,body,behalf,AppointmentCommandReceiptV1.class);}
    public ResponseEntity<AuthorityGrantCommandReceiptV1> revokeAuthorityGrant(UUID key,UUID id,String tag,RevokeAuthorityGrantV1 body,UUID own,UUID behalf){return write("REVOKE_AUTHORITY_GRANT",key,id,tag,body,behalf,AuthorityGrantCommandReceiptV1.class);}
    public ResponseEntity<IdentityPrincipalPageV1> listIdentityPrincipals(UUID own,UUID behalf,Integer limit,String cursor){return read("listIdentityPrincipals",null,null,limit,cursor,null,behalf,IdentityPrincipalPageV1.class);}
    public ResponseEntity<OrganizationUnitPageV1> listOrganizationUnits(UUID own,UUID behalf,Integer limit,String cursor){return read("listOrganizationUnits",null,null,limit,cursor,null,behalf,OrganizationUnitPageV1.class);}
    public ResponseEntity<AppointmentPageV1> listAppointments(UUID own,UUID behalf,Integer limit,String cursor){return read("listAppointments",null,null,limit,cursor,null,behalf,AppointmentPageV1.class);}
    public ResponseEntity<AuthorityGrantPageV1> listAuthorityGrants(UUID own,UUID behalf,Integer limit,String cursor){return read("listAuthorityGrants",null,null,limit,cursor,null,behalf,AuthorityGrantPageV1.class);}
    public ResponseEntity<ProviderUserPageV1> listIdentityProviderUsers(String search,UUID own,UUID behalf,Integer limit,String cursor){return read("listIdentityProviderUsers",null,null,limit,cursor,search,behalf,ProviderUserPageV1.class);}
    public ResponseEntity<IdentityAdminOptionsV1> getIdentityAdminOptions(IdentityAdminPageV1 page,IdentityOptionKindV1 option,UUID own,UUID behalf,Integer limit,String cursor){return read("getIdentityAdminOptions",page.getValue(),option.getValue(),limit,cursor,null,behalf,IdentityAdminOptionsV1.class);}
}
