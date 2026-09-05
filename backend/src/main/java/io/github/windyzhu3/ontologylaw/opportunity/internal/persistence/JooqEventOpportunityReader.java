package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import java.sql.Connection;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.OPPORTUNITY_;

public final class JooqEventOpportunityReader implements EventOpportunityReader {
    public Opportunity forContact(Connection c,UUID tenant,UUID contactId) {
        var o=OPPORTUNITY_;
        var r=DSL.using(c,SQLDialect.POSTGRES).select(o.OPPORTUNITY_ID,o.REVISION,o.SOURCE_LEAD_ID,o.SOURCE_ASSIGNMENT_ID,o.SOURCE_CONTACT_RESULT_ID,o.OWNER_APPOINTMENT_ID)
                .from(o).where(o.TENANT_ID.eq(tenant)).and(o.SOURCE_CONTACT_RESULT_ID.eq(contactId)).fetchOne();
        return r==null?null:new Opportunity(new Subject("opportunity.opportunity",r.value1(),r.value2(),null),r.value3(),r.value4(),r.value5(),r.value6());
    }
}
