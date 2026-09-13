package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;

/** Six bounded administration disclosures under shared fences, committed audit before BODY. */
public final class IdentityAdminReadRuntime {
    private final IdentityAdminReader reader=IdentityAdminReader.databaseBacked();
    private final AuditAppender audit;private final IdentityResourceProtection protection;private final IdentityCandidateProtection candidates;
    private final IdentityProviderDirectory directory;private final String provider;
    public IdentityAdminReadRuntime(AuditAppender audit,IdentityResourceProtection protection,IdentityCandidateProtection candidates,String provider,IdentityProviderDirectory directory){this.audit=audit;this.protection=protection;this.candidates=candidates;this.directory=directory;this.provider=provider;}
    public Map<String,Object> read(Connection connection,Actor actor,String operation,String page,String option,Integer limit,String cursor,String search)throws SQLException {
        int size=limit==null?20:limit;if(size<1||size>50)throw new Failure("VALIDATION_FAILED");
        return inTransaction(connection,Capability.QUERY,c->{
            R1BusinessFence.databaseBacked().shared(c,actor.tenantId());AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());
            if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)throw new Failure("NOT_AUTHORIZED");
            Kind kind;String code;boolean rootRequired=false,options=operation.equals("getIdentityAdminOptions");
            if(options) {
                int authority=switch(page==null?"":page){case "PRINCIPALS"->0;case "ORGANIZATIONS"->1;case "APPOINTMENTS"->2;case "AUTHORITY_GRANTS"->3;default->throw new Failure("VALIDATION_FAILED");};code=IdentityCommands.MANAGEMENT.get(authority);
                if(option==null||!switch(page){case "PRINCIPALS"->option.equals("PRINCIPAL");case "ORGANIZATIONS"->option.equals("ORGANIZATION");case "APPOINTMENTS"->Set.of("PRINCIPAL","ORGANIZATION").contains(option);default->Set.of("APPOINTMENT","ORGANIZATION").contains(option);})throw new Failure("VALIDATION_FAILED");
                kind=Kind.valueOf(option);rootRequired=kind==Kind.PRINCIPAL;
            } else {kind=switch(operation){case "listIdentityPrincipals","listIdentityProviderUsers"->Kind.PRINCIPAL;case "listOrganizationUnits"->Kind.ORGANIZATION;case "listAppointments"->Kind.APPOINTMENT;case "listAuthorityGrants"->Kind.AUTHORITY_GRANT;default->throw new Failure("VALIDATION_FAILED");};code=IdentityCommands.MANAGEMENT.get(kind.ordinal());rootRequired=kind==Kind.PRINCIPAL;}
            var access=reader.listAccess(c,actor,code,rootRequired);var sources=new ArrayList<Subject>();Map<String,Object> response;int count;
            if(operation.equals("listIdentityProviderUsers")) {
                if(cursor!=null||search==null||search.isBlank()||search.codePointCount(0,search.length())>200||search.codePoints().anyMatch(Character::isISOControl))throw new Failure("VALIDATION_FAILED");
                var account=directory.candidate(search);var items=new ArrayList<Map<String,Object>>();
                if(account!=null)items.add(Map.of("selector",candidates.issue(actor,provider,directory.issuer(),search,account.subject(),SensitiveReadClock.now(c)),"label",search));
                response=page(items,null);count=items.size();
            } else {
                String binding=CanonicalJson.encode(Map.of("profile","R1_IDENTITY_LIST_V1","actor",IdentityResourceProtection.actor(actor),"authority",code,"scopes",access.scopes().stream().map(UUID::toString).toList(),"authorization",Base64.getUrlEncoder().withoutPadding().encodeToString(access.digest()),"operation",operation,"page",page==null?"":page,"option",option==null?"":option));
                var position=protection.position(cursor,binding);boolean empty=options&&page.equals("PRINCIPALS");
                var result=empty?new Page(List.of(),false):reader.list(c,actor,kind,access,options,position,size);var items=new ArrayList<Map<String,Object>>();
                for(var resource:result.items()) {
                    sources.add(resource.fact());
                    if(options)items.add(choice(resource));else {var values=new LinkedHashMap<>(resource.values());var rowAccess=reader.resourceAccess(c,actor,code,resource);values.put("etag",protection.tag(actor,resource.fact(),rowAccess.digest()));items.add(Collections.unmodifiableMap(values));}
                }
                String next=result.hasMore()?protection.cursor(binding,new Position(result.items().getLast().createdAt(),result.items().getLast().fact().id())):null;
                response=page(items,next);count=items.size();
                if(options)response=Map.of("page",page,"optionKind",option,"roleCodes",page.equals("APPOINTMENTS")?IdentityCommands.ROLES:List.of(),"grantableAuthorityCodes",page.equals("AUTHORITY_GRANTS")?IdentityCommands.GRANTABLE:List.of(),"candidates",response);
            }
            // Final fresh DB-clock authorization includes current expiry after external work and projection.
            var finalAccess=reader.listAccess(c,actor,code,rootRequired);
            if(!java.security.MessageDigest.isEqual(access.digest(),finalAccess.digest()))throw new Failure("NOT_AUTHORIZED");
            for(var source:sources) {
                var current=reader.find(c,actor.tenantId(),Kind.of(source.type()),source.id());
                if(current==null||!current.fact().equals(source))throw new Failure("NOT_AUTHORIZED");
                finalAccess=IdentityAdminReader.combine(finalAccess,reader.resourceAccess(c,actor,code,current));
                if(options&&kind==Kind.APPOINTMENT) {
                    var now=SensitiveReadClock.now(c);var from=java.time.Instant.parse((String)current.values().get("effectiveFrom"));Object until=current.values().get("effectiveUntil");
                    if(now.isBefore(from)||until!=null&&!now.isBefore(java.time.Instant.parse((String)until)))throw new Failure("NOT_AUTHORIZED");
                }
            }
            access=finalAccess;
            setLocalRole(c,Capability.AUDIT);audit.append(c,new AuditAppender.IdentityDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),operation,access.authorization(),count,sources));
            return response;
        });
    }
    private static Map<String,Object> page(List<?> items,String cursor){var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",cursor);return Collections.unmodifiableMap(result);}
    @SuppressWarnings("unchecked") private static Map<String,Object> choice(Resource resource){String label=(String)resource.values().get("displayName");if(label==null)label=((Map<String,Object>)resource.values().get("principal")).get("label")+" · "+((Map<String,Object>)resource.values().get("organization")).get("label")+" · "+resource.values().get("roleCode");if(label.codePointCount(0,label.length())>200)label=label.substring(0,label.offsetByCodePoints(0,200));return Map.of("id",resource.fact().id().toString(),"label",label);}
}
