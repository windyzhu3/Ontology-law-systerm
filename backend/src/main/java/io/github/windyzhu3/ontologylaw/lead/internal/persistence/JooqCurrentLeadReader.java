package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.party.R1PartyReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.*;
import static io.github.windyzhu3.ontologylaw.lead.LeadProtection.Field.*;

public final class JooqCurrentLeadReader implements CurrentLeadReader {
    private final LeadProtection protection;
    private final R1PartyReader parties;
    public JooqCurrentLeadReader(LeadProtection protection,R1PartyReader parties) {
        this.protection=Objects.requireNonNull(protection);this.parties=Objects.requireNonNull(parties);
    }
    private static DSLContext db(Connection c) { return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)); }
    public Lead read(Connection c,UUID tenant,UUID id) {
        var l=LEAD_;
        // Explicit original columns are essential: SELECT * includes V850 columns forbidden to QUERY.
        var r=db(c).select(l.REVISION,l.SOURCE_CHANNEL_CODE,l.SOURCE_ACCOUNT_CODE,l.CAPTURED_AT,l.CAPTURED_NAME_CIPHERTEXT,
                l.CAPTURED_PHONE_CIPHERTEXT,l.CAPTURED_EMAIL_CIPHERTEXT,l.LEGAL_NEED_SUMMARY_CIPHERTEXT,l.CITY_CODE,
                l.SERVICE_CATEGORY_CODE,l.JURISDICTION_CODE,l.URGENCY_CODE,l.PARSED_PARTY_ID,l.PARTY_RESOLUTION_CODE,l.DISPOSITION_CODE,l.CURRENT_ASSIGNMENT_ID)
                .from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne();
        if(r==null)return null;
        return new Lead(new Subject("lead.lead",id,r.get(l.REVISION),null),r.get(l.SOURCE_CHANNEL_CODE),r.get(l.SOURCE_ACCOUNT_CODE),r.get(l.CAPTURED_AT).toInstant(),
                protection.decrypt(tenant,CAPTURED_NAME,r.get(l.CAPTURED_NAME_CIPHERTEXT)),protection.decrypt(tenant,CAPTURED_PHONE,r.get(l.CAPTURED_PHONE_CIPHERTEXT)),
                protection.decrypt(tenant,CAPTURED_EMAIL,r.get(l.CAPTURED_EMAIL_CIPHERTEXT)),protection.decrypt(tenant,LEGAL_NEED_SUMMARY,r.get(l.LEGAL_NEED_SUMMARY_CIPHERTEXT)),
                r.get(l.CITY_CODE),r.get(l.SERVICE_CATEGORY_CODE),r.get(l.JURISDICTION_CODE),r.get(l.URGENCY_CODE),r.get(l.PARSED_PARTY_ID),
                r.get(l.PARTY_RESOLUTION_CODE),r.get(l.DISPOSITION_CODE),r.get(l.CURRENT_ASSIGNMENT_ID));
    }
    public Party namedParty(Connection c,UUID tenant,UUID id)throws SQLException {
        var p=parties.named(c,tenant,id);
        return p==null?null:new Party(new Subject("party.party",p.id(),p.revision(),null),p.canonicalName(),p.status());
    }
    public Assignment assignment(Connection c,UUID tenant,UUID id) {
        var a=LEAD_ASSIGNMENT;
        var r=db(c).select(a.REVISION,a.LEAD_ID,a.OWNER_APPOINTMENT_ID,a.ASSIGNMENT_STATUS_CODE,a.ASSIGNED_AT)
                .from(a).where(a.TENANT_ID.eq(tenant)).and(a.LEAD_ASSIGNMENT_ID.eq(id)).fetchOne();
        return r==null?null:new Assignment(new Subject("lead.lead_assignment",id,r.get(a.REVISION),null),r.get(a.LEAD_ID),r.get(a.OWNER_APPOINTMENT_ID),
                r.get(a.ASSIGNMENT_STATUS_CODE),r.get(a.ASSIGNED_AT).toInstant());
    }
    public ContactResult contactResult(Connection c,UUID tenant,UUID id) {
        var f=LEAD_CONTACT_RESULT;var r=db(c).selectFrom(f).where(f.TENANT_ID.eq(tenant)).and(f.LEAD_CONTACT_RESULT_ID.eq(id)).fetchOne();
        return r==null?null:new ContactResult(JooqR1EventFacts.contactSelector(tenant,id,r),r.get(f.LEAD_ID),r.get(f.LEAD_ASSIGNMENT_ID),r.get(f.CONTACT_TASK_ID),
                r.get(f.CONTACT_NO),r.get(f.CONTACT_CHANNEL_CODE),r.get(f.RESULT_CODE),r.get(f.RESULT_SUMMARY),r.get(f.EVIDENCE_SUBMISSION_ID),r.get(f.RESULTED_AT).toInstant());
    }
}
