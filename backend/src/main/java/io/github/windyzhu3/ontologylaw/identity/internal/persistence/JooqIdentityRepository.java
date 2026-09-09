package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.*;
import java.sql.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.jooq.Record;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** All online Identity SQL stays in its Owner. No business locks or business tables. */
public final class JooqIdentityRepository implements IdentityCommands.Port {
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static String table(Kind kind){return kind.factType;}
    private static String idColumn(Kind kind){return switch(kind){case PRINCIPAL->"principal_id";case ORGANIZATION->"organization_unit_id";case APPOINTMENT->"appointment_id";case AUTHORITY_GRANT->"authority_grant_id";};}
    private static void require(boolean ok,String code){if(!ok)throw new Failure(code);}
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private Record row(Connection c,UUID tenant,Kind kind,UUID id){return db(c).fetchOne("select * from "+table(kind)+" where tenant_id=? and "+idColumn(kind)+"=?",tenant,id);}
    public Resource root(Connection c,UUID tenant)throws SQLException {
        var rows=db(c).fetch("select * from identity.organization_unit where tenant_id=? and parent_organization_unit_id is null limit 2",tenant);
        require(rows.size()==1,"SERVICE_UNAVAILABLE");return resource(c,tenant,Kind.ORGANIZATION,rows.getFirst());
    }
    public Resource find(Connection c,UUID tenant,Kind kind,UUID id)throws SQLException {
        var row=row(c,tenant,kind,id);return row==null?null:resource(c,tenant,kind,row);
    }
    private Resource resource(Connection c,UUID tenant,Kind kind,Record row)throws SQLException {
        UUID id=row.get(idColumn(kind),UUID.class),org=null;
        var values=new LinkedHashMap<String,Object>();values.put("id",id.toString());values.put("state",row.get("state",String.class));
        switch(kind) {
            case PRINCIPAL -> {if(!"HUMAN".equals(row.get("principal_kind")))return null;values.put("displayName",row.get("display_name",String.class));}
            case ORGANIZATION -> {org=id;UUID parent=row.get("parent_organization_unit_id",UUID.class);values.put("parentOrganizationId",parent==null?null:parent.toString());values.put("code",row.get("unit_code",String.class));values.put("displayName",row.get("display_name",String.class));}
            case APPOINTMENT -> {
                var principal=find(c,tenant,Kind.PRINCIPAL,row.get("principal_id",UUID.class));if(principal==null)return null;
                org=row.get("organization_unit_id",UUID.class);var organization=find(c,tenant,Kind.ORGANIZATION,org);
                require(organization!=null,"SERVICE_UNAVAILABLE");values.put("principal",choice(principal));values.put("organization",choice(organization));
                values.put("roleCode",row.get("role_code",String.class));values.put("effectiveFrom",instant(row,"effective_from"));values.put("effectiveUntil",instant(row,"effective_until"));
            }
            case AUTHORITY_GRANT -> {
                var app=find(c,tenant,Kind.APPOINTMENT,row.get("grantee_appointment_id",UUID.class));if(app==null)return null;
                org=app.organization();values.put("appointment",choice(app));values.put("authorityCode",row.get("authority_code",String.class));
                values.put("scopeOrganization",choice(find(c,tenant,Kind.ORGANIZATION,row.get("scope_organization_unit_id",UUID.class))));
                values.put("validFrom",instant(row,"valid_from"));values.put("validUntil",instant(row,"valid_until"));
            }
        }
        return new Resource(new Subject(kind.factType,id,row.get("revision",Long.class),null),org,row.get("created_at",OffsetDateTime.class).toInstant(),values);
    }
    private static String instant(Record r,String field){var value=r.get(field,OffsetDateTime.class);return value==null?null:value.toInstant().toString();}
    @SuppressWarnings("unchecked") public static Map<String,Object> choice(Resource r) {
        require(r!=null,"NOT_FOUND");Object name=r.values().get("displayName");
        if(name==null)name=((Map<String,Object>)r.values().get("principal")).get("label")+" · "+((Map<String,Object>)r.values().get("organization")).get("label")+" · "+r.values().get("roleCode");
        String label=name.toString();if(label.codePointCount(0,label.length())>200)label=label.substring(0,label.offsetByCodePoints(0,200));
        return Map.of("id",r.fact().id().toString(),"label",label);
    }
    public Access authorize(Connection c,Actor actor,String authority,Resource anchor,Resource target)throws SQLException {
        require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null,"NOT_AUTHORIZED");
        require(anchor!=null,"NOT_FOUND");
        var grants=db(c).fetch("select * from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code=? order by authority_grant_id",actor.tenantId(),actor.appointmentId(),authority);
        var evidence=new StringBuilder();AuthorizationSnapshot selected=null;
        for(var g:grants) {
            var request=new Request(actor,anchor.fact(),anchor.fact().id(),new Requirement(authority,"IDENTITY_ADMIN",Path.DIRECT,g.get("authority_grant_id",UUID.class)));
            var snapshot=authorization.evaluate(c,request,false);evidence.append(snapshot.stableDependencies()).append('\n');
            if(snapshot.allowed()&&target!=null&&!target.fact().equals(anchor.fact())) {
                var targetCheck=authorization.evaluate(c,new Request(actor,target.fact(),anchor.fact().id(),request.requirement()),false);
                evidence.append(targetCheck.stableDependencies()).append('\n');if(!targetCheck.allowed())continue;
            }
            if(snapshot.allowed()&&selected==null)selected=snapshot;
        }
        require(selected!=null,"NOT_AUTHORIZED");byte[] setDigest=digest(evidence.toString());
        String retained=selected.evidence()+"\nR1_IDENTITY_AUTHORIZATION_SET_V1:"+HexFormat.of().formatHex(setDigest);
        selected=new AuthorizationSnapshot(selected.request(),selected.checkedAt(),true,null,selected.authorityFact(),retained,digest(retained),selected.stableDependencies());
        return new Access(selected,setDigest,List.of(anchor.fact().id()));
    }
    public Access listAccess(Connection c,Actor actor,String code,boolean rootRequired)throws SQLException {
        if(rootRequired)return authorize(c,actor,code,root(c,actor.tenantId()),null);
        require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null,"NOT_AUTHORIZED");
        var roots=db(c).fetch("select distinct scope_organization_unit_id from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code=? order by scope_organization_unit_id",actor.tenantId(),actor.appointmentId(),code);
        var scopes=new ArrayList<UUID>();Access first=null;var evidence=new StringBuilder();
        for(var row:roots){var org=find(c,actor.tenantId(),Kind.ORGANIZATION,row.get(0,UUID.class));try{var access=authorize(c,actor,code,org,null);scopes.add(org.fact().id());evidence.append(HexFormat.of().formatHex(access.digest()));if(first==null)first=access;}catch(Failure denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}}
        require(first!=null,"NOT_AUTHORIZED");return new Access(first.authorization(),digest(evidence.toString()),scopes);
    }
    public Page list(Connection c,Actor actor,Kind kind,Access access,boolean candidates,Position after,int limit)throws SQLException {
        require(limit>=1&&limit<=50,"VALIDATION_FAILED");String alias="r",column=idColumn(kind);
        String joins=switch(kind){case PRINCIPAL,ORGANIZATION->"";case APPOINTMENT->" join identity.principal p on p.tenant_id=r.tenant_id and p.principal_id=r.principal_id";case AUTHORITY_GRANT->" join identity.appointment a on a.tenant_id=r.tenant_id and a.appointment_id=r.grantee_appointment_id join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id";};
        String human=switch(kind){case PRINCIPAL->" and r.principal_kind='HUMAN'";case ORGANIZATION->"";default->" and p.principal_kind='HUMAN'";};
        String organization=switch(kind){case PRINCIPAL->null;case ORGANIZATION->"r.organization_unit_id";case APPOINTMENT->"r.organization_unit_id";case AUTHORITY_GRANT->"a.organization_unit_id";};
        var args=new ArrayList<Object>();args.add(actor.tenantId());args.add(access.scopes().toArray(UUID[]::new));args.add(actor.tenantId());args.add(actor.tenantId());
        String sql="with recursive visible(id,path) as (select organization_unit_id,array[organization_unit_id] from identity.organization_unit where tenant_id=? and organization_unit_id=any(?::uuid[]) union all select o.organization_unit_id,v.path||o.organization_unit_id from identity.organization_unit o join visible v on o.parent_organization_unit_id=v.id where o.tenant_id=? and not o.organization_unit_id=any(v.path) and cardinality(v.path)<256) select r.* from "+table(kind)+" r"+joins+" where r.tenant_id=?"+human;
        if(organization!=null)sql+=" and "+organization+" in (select id from visible)";
        if(kind==Kind.APPOINTMENT)sql+=" and r.role_code in ('INTAKE_OPERATOR','ROUTING_SUPERVISOR','CONTACT_OPERATOR','IDENTITY_ADMIN')";
        if(kind==Kind.AUTHORITY_GRANT)sql+=" and r.authority_code in ('"+String.join("','",IdentityCommands.GRANTABLE)+"','"+String.join("','",IdentityCommands.MANAGEMENT)+"')";
        if(candidates){sql+=" and r.state='ACTIVE'";if(kind==Kind.APPOINTMENT)sql+=" and p.state='ACTIVE' and r.effective_from<=clock_timestamp() and (r.effective_until is null or r.effective_until>clock_timestamp())";}
        if(after!=null){sql+=" and (r.created_at,r."+column+")>(?::timestamptz,?::uuid)";args.add(after.createdAt().toString());args.add(after.id());}
        // Filter authorization before counting a page. Bounded SQL chunks never disclose a denied row.
        var result=new ArrayList<Resource>();Position position=after;
        while(result.size()<=limit) {
            String query=sql;var parameters=new ArrayList<>(args);
            if(position!=null&&position!=after){query+=" and (r.created_at,r."+column+")>(?::timestamptz,?::uuid)";parameters.add(position.createdAt().toString());parameters.add(position.id());}
            query+=" order by r.created_at,r."+column+" limit ?";parameters.add(limit+1);
            var rows=db(c).fetch(query,parameters.toArray());if(rows.isEmpty())break;
            for(var row:rows){var resource=resource(c,actor.tenantId(),kind,row);position=new Position(row.get("created_at",OffsetDateTime.class).toInstant(),row.get(column,UUID.class));if(resource==null)continue;
                try{resourceAccess(c,actor,access.authorization().request().requirement().authorityCode(),resource);result.add(resource);}catch(Failure denied){if(!"NOT_AUTHORIZED".equals(denied.code()))throw denied;}
                if(result.size()>limit)break;
            }
            if(rows.size()<limit+1)break;
        }
        boolean more=result.size()>limit;if(more)result.removeLast();return new Page(result,more);
    }
    public void lockRows(Connection c,UUID tenant)throws SQLException {
        db(c).fetch("select tenant_id from identity.tenant where tenant_id=? order by tenant_id for update",tenant);
        for(var kind:Kind.values())db(c).fetch("select "+idColumn(kind)+" from "+table(kind)+" where tenant_id=? order by "+idColumn(kind)+" for update",tenant);
    }
    public boolean recoveryMatches(Connection c,UUID tenant,Handler h,Map<String,Object> attempted,Resource target)throws SQLException {
        if(target==null)return h.create();
        if(!h.create())return target.fact().id().toString().equals(attempted.get("id"));
        var stored=row(c,tenant,h.kind(),target.fact().id());
        return switch(h.kind()) {
            case PRINCIPAL -> Objects.equals(stored.get("identity_provider_code"),attempted.get("providerCode"))&&MessageDigest.isEqual(stored.get("external_subject_hmac",byte[].class),Base64.getUrlDecoder().decode((String)attempted.get("subjectHmac")));
            case ORGANIZATION -> Objects.equals(stored.get("parent_organization_unit_id",UUID.class),uuid(attempted,"parentId"))&&Objects.equals(stored.get("unit_code"),attempted.get("code"));
            case APPOINTMENT -> Objects.equals(stored.get("principal_id",UUID.class),uuid(attempted,"principalId"))&&Objects.equals(stored.get("organization_unit_id",UUID.class),uuid(attempted,"organizationId"))&&Objects.equals(stored.get("role_code"),attempted.get("roleCode"))&&sameTerm(c,stored,attempted,"effective");
            case AUTHORITY_GRANT -> Objects.equals(stored.get("grantee_appointment_id",UUID.class),uuid(attempted,"appointmentId"))&&Objects.equals(stored.get("scope_organization_unit_id",UUID.class),uuid(attempted,"scopeOrganizationId"))&&Objects.equals(stored.get("authority_code"),attempted.get("authorityCode"))&&sameTerm(c,stored,attempted,"valid");
        };
    }
    private static boolean sameTerm(Connection c,Record row,Map<String,Object> attempted,String prefix) {
        for(String suffix:List.of("From","Until")) {
            OffsetDateTime actual=row.get(prefix+"_"+suffix.toLowerCase(Locale.ROOT),OffsetDateTime.class);Object value=attempted.get(prefix+suffix);
            if(!db(c).fetchOne("select ?::timestamptz is not distinct from ?::timestamptz",actual,value).get(0,Boolean.class))return false;
        }
        return true;
    }
    public Context resolve(Connection c,Actor actor,Handler h,UUID id,Map<String,Object> body,ProviderBinding provider)throws SQLException {
        Resource target=h.create()?null:find(c,actor.tenantId(),h.kind(),id);if(!h.create())require(target!=null,"NOT_FOUND");
        Resource anchor;var scope=new LinkedHashMap<String,Object>();
        if(h.rootRequired())anchor=root(c,actor.tenantId());
        else if(h.create()&&h.kind()==Kind.ORGANIZATION)anchor=find(c,actor.tenantId(),Kind.ORGANIZATION,uuid(body,"parentOrganizationId"));
        else if(h.create()){var app=find(c,actor.tenantId(),Kind.APPOINTMENT,uuid(body,"appointmentId"));require(app!=null,"NOT_FOUND");anchor=find(c,actor.tenantId(),Kind.ORGANIZATION,app.organization());}
        else anchor=find(c,actor.tenantId(),Kind.ORGANIZATION,resourceAccess(c,actor,h.authority(),target).authorization().request().subject().id());
        require(anchor!=null,"NOT_FOUND");var access=authorize(c,actor,h.authority(),anchor,target);
        if(h.create())switch(h.kind()) {
            case PRINCIPAL -> {require(provider!=null,"VALIDATION_FAILED");scope.put("kind","CREATE_PRINCIPAL");scope.put("providerCode",provider.provider());scope.put("subjectHmac",Base64.getUrlEncoder().withoutPadding().encodeToString(provider.subjectHmac()));}
            case ORGANIZATION -> {scope.put("kind","CREATE_ORGANIZATION");scope.put("parentId",body.get("parentOrganizationId"));scope.put("code",body.get("code"));}
            case APPOINTMENT -> {scope.put("kind","CREATE_APPOINTMENT");for(String field:List.of("principalId","organizationId","roleCode","effectiveFrom","effectiveUntil"))scope.put(field,body.get(field));
                var principal=find(c,actor.tenantId(),Kind.PRINCIPAL,uuid(body,"principalId"));var org=find(c,actor.tenantId(),Kind.ORGANIZATION,uuid(body,"organizationId"));require(principal!=null&&org!=null,"NOT_FOUND");authorize(c,actor,h.authority(),anchor,principal);authorize(c,actor,h.authority(),org,null);}
            case AUTHORITY_GRANT -> {scope.put("kind","CREATE_AUTHORITY_GRANT");for(String field:List.of("appointmentId","authorityCode","scopeOrganizationId","validFrom","validUntil"))scope.put(field,body.get(field));
                var org=find(c,actor.tenantId(),Kind.ORGANIZATION,uuid(body,"scopeOrganizationId"));require(org!=null,"NOT_FOUND");authorize(c,actor,h.authority(),org,null);}
        } else {scope.put("kind",h.kind().factType);scope.put("id",id.toString());}
        return new Context(target,anchor,access,scope);
    }
    public Mutation mutate(Connection c,Actor actor,Handler h,Context context,Map<String,Object> body,ProviderBinding provider,boolean openResponsibilities)throws SQLException {
        if(h.create())return create(c,actor,h,context,body,provider);
        Resource target=context.target();String state=(String)target.values().get("state");
        if(h.action().equals("RENAME")) {
            require(!Set.of("DISABLED","CLOSED").contains(state),"IDENTITY_STATE_CONFLICT");
            if(body.get("displayName").equals(target.values().get("displayName")))return new Mutation(target.fact(),false);
            changed(c,actor,h,target,"display_name=?",body.get("displayName"));
        } else {
            require(!state.equals(h.action())&&!Set.of("DISABLED","CLOSED","ENDED","REVOKED").contains(state),"IDENTITY_STATE_CONFLICT");
            if(h.action().equals("ACTIVE"))require(state.equals("SUSPENDED"),"IDENTITY_STATE_CONFLICT");
            if(h.action().equals("SUSPENDED"))require(state.equals("ACTIVE"),"IDENTITY_STATE_CONFLICT");
            if(h.action().equals("DISABLED")) {
                require(!target.fact().id().equals(actor.principalId()),"IDENTITY_SELF_LOCKOUT");
                require(!db(c).fetchExists(DSL.selectOne().from("identity.appointment").where("tenant_id=? and principal_id=? and state<>'ENDED'",actor.tenantId(),target.fact().id())),"IDENTITY_ORGANIZATION_DEPENDENCY");
            }
            if(h.action().equals("ENDED")){require(!target.fact().id().equals(actor.appointmentId()),"IDENTITY_SELF_LOCKOUT");require(!openResponsibilities,"IDENTITY_RESPONSIBILITY_DEPENDENCY");}
            if(h.action().equals("CLOSED")) {
                require(!db(c).fetchExists(DSL.selectOne().from("identity.organization_unit").where("tenant_id=? and parent_organization_unit_id=? and state='ACTIVE'",actor.tenantId(),target.fact().id())),"IDENTITY_ORGANIZATION_DEPENDENCY");
                require(!db(c).fetchExists(DSL.selectOne().from("identity.appointment").where("tenant_id=? and organization_unit_id=? and state='ACTIVE' and effective_from<=clock_timestamp() and (effective_until is null or effective_until>clock_timestamp())",actor.tenantId(),target.fact().id())),"IDENTITY_ORGANIZATION_DEPENDENCY");
            }
            if(!h.action().equals("ACTIVE"))require(!removesLastAdmin(c,actor.tenantId(),h,target),"IDENTITY_LAST_ADMIN");
            String extra=switch(h.action()){case "DISABLED"->",disabled_at=clock_timestamp()";case "CLOSED"->",closed_at=clock_timestamp()";case "ENDED"->",ended_at=clock_timestamp()";case "REVOKED"->",revoked_at=clock_timestamp(),revocation_reason_code=?";default->"";};
            if(h.action().equals("REVOKED"))changed(c,actor,h,target,"state=?"+extra,h.action(),body.get("reasonCode"));else changed(c,actor,h,target,"state=?"+extra,h.action());
        }
        return new Mutation(new Subject(h.kind().factType,target.fact().id(),target.fact().revision()+1,null),true);
    }
    private void changed(Connection c,Actor actor,Handler h,Resource target,String fields,Object... values)throws SQLException {
        var args=new ArrayList<>(Arrays.asList(values));args.add(actor.tenantId());args.add(target.fact().id());args.add(target.fact().revision());
        int count=db(c).execute("update "+table(h.kind())+" set "+fields+",revision=revision+1 where tenant_id=? and "+idColumn(h.kind())+"=? and revision=?",args.toArray());
        require(count==1,"STALE_IDENTITY");
    }
    private Mutation create(Connection c,Actor actor,Handler h,Context ctx,Map<String,Object> b,ProviderBinding provider)throws SQLException {
        UUID tenant=actor.tenantId(),id=db(c).fetchOne("select uuidv7()").get(0,UUID.class);String prefix="insert into "+table(h.kind())+" (tenant_id,"+idColumn(h.kind());
        switch(h.kind()) {
            case PRINCIPAL -> {
                require(!db(c).fetchExists(DSL.selectOne().from("identity.principal").where("tenant_id=? and identity_provider_code=? and external_subject_hmac=?",tenant,provider.provider(),provider.subjectHmac())),"IDENTITY_BINDING_CONFLICT");
                db(c).execute(prefix+",principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN',?,?,?,'ACTIVE',clock_timestamp())",tenant,id,provider.provider(),provider.subjectHmac(),b.get("displayName"));
            }
            case ORGANIZATION -> {
                require("ACTIVE".equals(ctx.anchor().values().get("state")),"IDENTITY_STATE_CONFLICT");
                require(!db(c).fetchExists(DSL.selectOne().from("identity.organization_unit").where("tenant_id=? and unit_code=?",tenant,b.get("code"))),"IDENTITY_BINDING_CONFLICT");
                db(c).execute(prefix+",parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,?,?,'ACTIVE',clock_timestamp())",tenant,id,uuid(b,"parentOrganizationId"),b.get("code"),b.get("displayName"));
            }
            case APPOINTMENT -> {
                require("ACTIVE".equals(find(c,tenant,Kind.PRINCIPAL,uuid(b,"principalId")).values().get("state"))&&"ACTIVE".equals(find(c,tenant,Kind.ORGANIZATION,uuid(b,"organizationId")).values().get("state")),"IDENTITY_STATE_CONFLICT");
                db(c).execute(prefix+",principal_id,organization_unit_id,role_code,effective_from,effective_until,state,created_at) values (?,?,?,?,?,?::timestamptz,?::timestamptz,'ACTIVE',clock_timestamp())",tenant,id,uuid(b,"principalId"),uuid(b,"organizationId"),b.get("roleCode"),b.get("effectiveFrom"),b.get("effectiveUntil"));
            }
            case AUTHORITY_GRANT -> {
                Record app=row(c,tenant,Kind.APPOINTMENT,uuid(b,"appointmentId"));require(!actor.principalId().equals(app.get("principal_id",UUID.class)),"IDENTITY_SELF_LOCKOUT");
                require("ACTIVE".equals(app.get("state"))&&"ACTIVE".equals(find(c,tenant,Kind.PRINCIPAL,app.get("principal_id",UUID.class)).values().get("state")),"IDENTITY_STATE_CONFLICT");
                Instant start=Instant.parse((String)b.get("validFrom")),end=b.get("validUntil")==null?null:Instant.parse((String)b.get("validUntil"));OffsetDateTime appEnd=app.get("effective_until",OffsetDateTime.class);
                require(!start.isBefore(app.get("effective_from",OffsetDateTime.class).toInstant())&&(appEnd==null||end!=null&&!end.isAfter(appEnd.toInstant())),"IDENTITY_STATE_CONFLICT");
                db(c).execute(prefix+",grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,'ACTIVE',clock_timestamp())",tenant,id,uuid(b,"appointmentId"),actor.appointmentId(),uuid(b,"scopeOrganizationId"),b.get("authorityCode"),b.get("validFrom"),b.get("validUntil"));
            }
        }
        return new Mutation(new Subject(h.kind().factType,id,0L,null),true);
    }
    private boolean removesLastAdmin(Connection c,UUID tenant,Handler h,Resource target)throws SQLException {
        var candidates=db(c).fetch("select a.appointment_id,a.principal_id,a.organization_unit_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id where a.tenant_id=? and a.role_code='IDENTITY_ADMIN' and a.state='ACTIVE' and p.state='ACTIVE' and p.principal_kind='HUMAN' and a.effective_from<=clock_timestamp() and (a.effective_until is null or a.effective_until>clock_timestamp()) order by a.appointment_id",tenant);
        boolean removed=false;var root=root(c,tenant);
        for(var candidate:candidates) {
            Actor founder=new Actor(tenant,candidate.get("principal_id",UUID.class),candidate.get("appointment_id",UUID.class),null,null);
            boolean complete=true,affected=h.kind()==Kind.PRINCIPAL&&target.fact().id().equals(founder.principalId())||h.kind()==Kind.APPOINTMENT&&target.fact().id().equals(founder.appointmentId())||h.kind()==Kind.ORGANIZATION&&under(c,tenant,candidate.get("organization_unit_id",UUID.class),target.fact().id());
            for(String code:IdentityCommands.MANAGEMENT) {
                try{authorize(c,founder,code,root,null);}catch(Failure denied){if(!denied.code().equals("NOT_AUTHORIZED"))throw denied;complete=false;break;}
                if(h.kind()==Kind.AUTHORITY_GRANT) {
                    var matching=db(c).fetch("select authority_grant_id from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and scope_organization_unit_id=? and authority_code=? and state='ACTIVE' and valid_from<=clock_timestamp() and (valid_until is null or valid_until>clock_timestamp())",tenant,founder.appointmentId(),root.fact().id(),code);
                    if(matching.size()==1&&matching.getFirst().get(0,UUID.class).equals(target.fact().id()))affected=true;
                }
            }
            if(complete){if(!affected)return false;removed=true;}
        }
        return removed;
    }
    private boolean under(Connection c,UUID tenant,UUID node,UUID ancestor){var seen=new HashSet<UUID>();while(node!=null&&seen.add(node)&&seen.size()<=256){if(node.equals(ancestor))return true;var row=row(c,tenant,Kind.ORGANIZATION,node);if(row==null)return false;node=row.get("parent_organization_unit_id",UUID.class);}return false;}
    private static UUID uuid(Map<String,Object> values,String name){return UUID.fromString((String)values.get(name));}
}
