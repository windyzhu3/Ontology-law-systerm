package io.github.windyzhu3.ontologylaw.lead.internal.persistence;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.sql.*;import java.time.*;import java.time.format.DateTimeFormatterBuilder;import java.util.*;import java.nio.ByteBuffer;
import org.jooq.*;import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.*;
import static io.github.windyzhu3.ontologylaw.lead.LeadProtection.Field.*;
import static io.github.windyzhu3.ontologylaw.lead.LeadProtection.Purpose.*;

public final class JooqLeadRepository implements LeadIngressService {
    private final LeadProtection protection;private final R1PartyReader parties;
    public JooqLeadRepository(LeadProtection protection,R1PartyReader parties){this.protection=Objects.requireNonNull(protection);this.parties=Objects.requireNonNull(parties);}
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static UUID id(Connection c){return db(c).select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);}
    public Instant now(Connection c){return db(c).select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static OffsetDateTime time(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private static String timestamp(Instant i){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(i);}
    private static String base64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private Lead lead(org.jooq.Record r){if(r==null)return null;var l=LEAD_;return new Lead(new Subject("lead.lead",r.get(l.LEAD_ID),r.get(l.REVISION),null),r.get(l.SOURCE_ACCOUNT_CODE),r.get(l.CAPTURED_AT).toInstant(),r.get(l.CREATED_AT).toInstant(),
        r.get(l.PARSED_PARTY_ID),r.get(l.PARTY_RESOLUTION_CODE),r.get(l.DISPOSITION_CODE),r.get(l.CURRENT_ASSIGNMENT_ID),r.get(l.CAPTURED_PHONE_HMAC),r.get(l.INGRESS_COMPLETION_PHONE_HMAC),r.get(l.CAPTURED_EMAIL_HMAC),r.get(l.INGRESS_COMPLETION_EMAIL_HMAC),
        r.get(l.INGRESS_COMPLETION_PHONE_CIPHERTEXT)==null&&r.get(l.INGRESS_COMPLETION_PHONE_HMAC)==null&&r.get(l.INGRESS_COMPLETION_EMAIL_CIPHERTEXT)==null&&r.get(l.INGRESS_COMPLETION_EMAIL_HMAC)==null&&r.get(l.INGRESS_COMPLETION_SOURCE_CODE)==null&&r.get(l.INGRESS_COMPLETION_SOURCE_SUMMARY_CIPHERTEXT)==null&&r.get(l.INGRESS_COMPLETED_BY_APPOINTMENT_ID)==null&&r.get(l.INGRESS_COMPLETED_AT)==null&&r.get(l.INGRESS_COMPLETION_DIGEST)==null);}
    public Lead read(Connection c,UUID tenant,UUID id){var l=LEAD_;return lead(db(c).selectFrom(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne());}
    public Header header(Connection c,UUID tenant,UUID id){var l=LEAD_;var r=db(c).select(l.REVISION,l.SOURCE_ACCOUNT_CODE,l.CURRENT_ASSIGNMENT_ID).from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne();return r==null?null:new Header(new Subject("lead.lead",id,r.value1(),null),r.value2(),r.value3());}
    public Lead natural(Connection c,UUID tenant,String account,byte[] key){var l=LEAD_;return lead(db(c).selectFrom(l).where(l.TENANT_ID.eq(tenant)).and(l.SOURCE_ACCOUNT_CODE.eq(account)).and(l.SOURCE_RECORD_KEY_DIGEST.eq(key)).fetchOne());}
    public void lock(Connection c,UUID tenant,UUID id){var l=LEAD_;db(c).select(l.LEAD_ID).from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).forUpdate().fetch();}
    public void lockNatural(Connection c,UUID tenant,String account,byte[] key){long lock=ByteBuffer.wrap(CanonicalJson.digest(CanonicalJson.encode(Map.of("profile","R1_LEAD_SOURCE_LOCK_V1","tenantId",tenant.toString(),"account",account,"key",base64(key))))).getLong();db(c).select(DSL.field("pg_advisory_xact_lock({0})",Object.class,DSL.val(lock))).fetch();}
    private byte[] encrypt(UUID tenant,LeadProtection.Field field,Map<String,Object> values,String name){return protection.encrypt(tenant,field,(String)values.get(name));}
    private byte[] hmac(UUID tenant,LeadProtection.Purpose purpose,Map<String,Object> values,String name){String v=(String)values.get(name);return v==null?null:protection.hmac(tenant,purpose,null,v);}
    public Lead capture(Connection c,UUID tenant,Map<String,Object> p,byte[] sourceKey,Instant now){
        var l=LEAD_;UUID id=id(c);var digest=new TreeMap<String,Object>();
        for(String key:List.of("sourceChannelCode","sourceAccountCode","capturedName","phone","email","cityCode","serviceCategoryCode","jurisdictionCode","urgencyCode","legalNeedSummary"))digest.put(key,p.get(key));
        Instant captured=Instant.parse((String)p.get("capturedAt"));digest.put("capturedAt",timestamp(captured));digest.put("sourceRecordKeyDigest",base64(sourceKey));
        db(c).insertInto(l).set(l.TENANT_ID,tenant).set(l.LEAD_ID,id).set(l.SOURCE_CHANNEL_CODE,(String)p.get("sourceChannelCode")).set(l.SOURCE_ACCOUNT_CODE,(String)p.get("sourceAccountCode")).set(l.SOURCE_RECORD_KEY_DIGEST,sourceKey).set(l.CAPTURED_AT,time(captured))
          .set(l.CAPTURED_NAME_CIPHERTEXT,encrypt(tenant,CAPTURED_NAME,p,"capturedName")).set(l.CAPTURED_PHONE_CIPHERTEXT,encrypt(tenant,CAPTURED_PHONE,p,"phone")).set(l.CAPTURED_PHONE_HMAC,hmac(tenant,LEAD_PHONE_EXACT,p,"phone"))
          .set(l.CAPTURED_EMAIL_CIPHERTEXT,encrypt(tenant,CAPTURED_EMAIL,p,"email")).set(l.CAPTURED_EMAIL_HMAC,hmac(tenant,LEAD_EMAIL_EXACT,p,"email")).set(l.CITY_CODE,(String)p.get("cityCode"))
          .set(l.SERVICE_CATEGORY_CODE,(String)p.get("serviceCategoryCode")).set(l.JURISDICTION_CODE,(String)p.get("jurisdictionCode")).set(l.URGENCY_CODE,(String)p.get("urgencyCode"))
          .set(l.LEGAL_NEED_SUMMARY_CIPHERTEXT,encrypt(tenant,LEGAL_NEED_SUMMARY,p,"legalNeedSummary")).set(l.CAPTURED_CONTENT_DIGEST,CanonicalJson.digest(CanonicalJson.encode(digest)))
          .set(l.PARTY_RESOLUTION_CODE,"UNRESOLVED").set(l.DISPOSITION_CODE,"CAPTURED").set(l.REVISION,0L).set(l.CREATED_AT,time(now)).execute();return read(c,tenant,id);
    }
    public Duplicate duplicate(Connection c,UUID tenant,Lead current,Instant cutoff)throws SQLException {
        var result=JooqDuplicateCandidateReader.select(c,tenant,current.selector().id(),current.disposition(),cutoff,parties);
        return result==null?null:new Duplicate(result.lead(),result.party());
    }
    public Assignment assignment(Connection c,UUID tenant,UUID id){var a=LEAD_ASSIGNMENT;var r=db(c).selectFrom(a).where(a.TENANT_ID.eq(tenant)).and(a.LEAD_ASSIGNMENT_ID.eq(id)).fetchOne();return r==null?null:new Assignment(new Subject("lead.lead_assignment",id,r.get(a.REVISION),null),r.get(a.LEAD_ID),r.get(a.OWNER_APPOINTMENT_ID),r.get(a.ASSIGNMENT_STATUS_CODE),r.get(a.CREATED_AT).toInstant());}
    public boolean hasOpenAssignment(Connection c,UUID tenant,UUID lead){var a=LEAD_ASSIGNMENT;return db(c).fetchExists(DSL.selectOne().from(a).where(a.TENANT_ID.eq(tenant)).and(a.LEAD_ID.eq(lead)).and(a.ASSIGNMENT_STATUS_CODE.eq("OPEN")));}
    public Assignment assign(Connection c,UUID tenant,Lead lead,UUID owner,String reason,Instant now){
        if(!Set.of("MANUAL_SELECTION","SOURCE_POLICY_AUTOMATIC","ROUTING_RETRY").contains(reason))throw new IllegalArgumentException("Invalid assignment reason");
        if(lead.assignment()!=null||hasOpenAssignment(c,tenant,lead.selector().id()))throw new CommandHandler.Rejected("STALE_SUBJECT");
        var a=LEAD_ASSIGNMENT;UUID id=id(c);db(c).insertInto(a).set(a.TENANT_ID,tenant).set(a.LEAD_ASSIGNMENT_ID,id).set(a.LEAD_ID,lead.selector().id()).set(a.ASSIGNMENT_NO,1L).set(a.OWNER_APPOINTMENT_ID,owner).set(a.ASSIGNMENT_REASON_CODE,reason)
            .set(a.ASSIGNED_AT,time(now)).set(a.ASSIGNMENT_STATUS_CODE,"OPEN").set(a.REVISION,0L).set(a.CREATED_AT,time(now)).execute();return assignment(c,tenant,id);
    }
    public Lead update(Connection c,UUID tenant,Lead lead,String disposition,UUID party,Map<String,Object> ingress,UUID actor,UUID assignment,Instant now)throws SQLException{
        var l=LEAD_;var update=db(c).update(l).set(l.REVISION,CommandHandler.nextRevision(lead.selector().revision()));
        if(disposition!=null){if(!Set.of("LINK_EXISTING_PARTY","KEEP_SEPARATE").contains(disposition)||!"CAPTURED".equals(lead.disposition()))throw new CommandHandler.Rejected("STALE_SUBJECT");update.set(l.DISPOSITION_CODE,disposition);if("LINK_EXISTING_PARTY".equals(disposition)){if(party==null)throw new IllegalArgumentException("Exact Party required");update.set(l.PARSED_PARTY_ID,party).set(l.PARTY_RESOLUTION_CODE,"RESOLVED");}else if(party!=null)throw new IllegalArgumentException("KEEP cannot change Party");}
        if(ingress!=null){if(!lead.missingContact()||!lead.ingressEmpty())throw new CommandHandler.Rejected("INGRESS_COMPLETION_ALREADY_RECORDED");
            var digest=new TreeMap<String,Object>();for(String key:List.of("phone","email","sourceCode","sourceSummary"))digest.put(key,ingress.get(key));digest.put("completedByAppointmentId",actor.toString());digest.put("completedAt",timestamp(now));
            update.set(l.INGRESS_COMPLETION_PHONE_CIPHERTEXT,encrypt(tenant,INGRESS_PHONE,ingress,"phone")).set(l.INGRESS_COMPLETION_PHONE_HMAC,hmac(tenant,LEAD_PHONE_EXACT,ingress,"phone"))
                .set(l.INGRESS_COMPLETION_EMAIL_CIPHERTEXT,encrypt(tenant,INGRESS_EMAIL,ingress,"email")).set(l.INGRESS_COMPLETION_EMAIL_HMAC,hmac(tenant,LEAD_EMAIL_EXACT,ingress,"email"))
                .set(l.INGRESS_COMPLETION_SOURCE_CODE,(String)ingress.get("sourceCode")).set(l.INGRESS_COMPLETION_SOURCE_SUMMARY_CIPHERTEXT,encrypt(tenant,INGRESS_SOURCE_SUMMARY,ingress,"sourceSummary"))
                .set(l.INGRESS_COMPLETED_BY_APPOINTMENT_ID,actor).set(l.INGRESS_COMPLETED_AT,time(now)).set(l.INGRESS_COMPLETION_DIGEST,CanonicalJson.digest(CanonicalJson.encode(digest)));}
        if(assignment!=null){var a=assignment(c,tenant,assignment);if(lead.assignment()!=null||a==null||!a.lead().equals(lead.selector().id())||!"OPEN".equals(a.state())||a.selector().revision()!=0||!a.createdAt().equals(now))throw new CommandHandler.Rejected("STALE_SUBJECT");update.set(l.CURRENT_ASSIGNMENT_ID,assignment);}
        int changed=update.where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(lead.selector().id())).and(l.REVISION.eq(lead.selector().revision())).execute();if(changed!=1)throw new CommandHandler.Rejected("STALE_SUBJECT");return read(c,tenant,lead.selector().id());
    }
}
