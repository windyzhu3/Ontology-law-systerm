package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOpeningService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.OPPORTUNITY_;

public final class JooqOpportunityRepository implements OpportunityOpeningService {
    public Subject open(Connection c,UUID tenant,Subject lead,Subject assignment,Subject contact,UUID owner,byte[] legalNeed,byte[] digest,Instant now){
        if(!"lead.lead".equals(lead.type())||!"lead.lead_assignment".equals(assignment.type())||!"lead.lead_contact_result".equals(contact.type())
                ||lead.revision()==null||assignment.revision()==null||contact.hash()==null||legalNeed==null||digest==null||digest.length!=32)throw new IllegalArgumentException("Exact Opportunity source required");
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));UUID id=db.select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);var o=OPPORTUNITY_;
        db.insertInto(o).set(o.TENANT_ID,tenant).set(o.OPPORTUNITY_ID,id).set(o.SOURCE_LEAD_ID,lead.id()).set(o.SOURCE_ASSIGNMENT_ID,assignment.id()).set(o.SOURCE_CONTACT_RESULT_ID,contact.id())
            .set(o.OWNER_APPOINTMENT_ID,owner).set(o.LEGAL_NEED_CIPHERTEXT,legalNeed).set(o.LEGAL_NEED_DIGEST,digest).set(o.REVISION,0L).set(o.CREATED_AT,now.atOffset(ZoneOffset.UTC)).execute();
        return new Subject("opportunity.opportunity",id,0L,null);
    }
}
