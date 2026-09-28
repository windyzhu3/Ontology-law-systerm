package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.QuoteFollowupReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

public final class JdbcQuoteFollowupReader implements QuoteFollowupReader {
    public Source read(Connection c,UUID tenant,UUID task)throws SQLException {
        try(var p=c.prepareStatement("""
            select r.quote_response_id,r.response_content_digest,w.opportunity_id,w.owner_appointment_id,w.prior_task_id,
                   d.responsibility_type,d.responsibility_id,d.responsibility_revision,w.next_check_at
            from opportunity.quote_workflow w
            join opportunity.quote_revision q on q.tenant_id=w.tenant_id and q.quote_revision_id=w.quote_revision_id
            join opportunity.opportunity o on o.tenant_id=q.tenant_id and o.opportunity_id=q.opportunity_id
            join opportunity.quote_package_basis b on b.tenant_id=q.tenant_id and b.quote_revision_id=q.quote_revision_id
            join opportunity.quote_draft d on d.tenant_id=b.tenant_id and d.quote_draft_id=b.quote_draft_id
            join opportunity.quote_issue i on i.tenant_id=q.tenant_id and i.quote_revision_id=q.quote_revision_id
            join opportunity.quote_response r on r.tenant_id=i.tenant_id and r.quote_issue_id=i.quote_issue_id
            join opportunity.quote_response_basis rb on rb.tenant_id=r.tenant_id and rb.quote_response_id=r.quote_response_id
            where w.tenant_id=? and w.task_id=? and w.stage in ('FOLLOW_UP','CLARIFY_REPLY')
              and o.closed_at is null and o.current_quote_revision_id=q.quote_revision_id
              and i.issue_status_code='ACTIVE'
              and ((w.stage='FOLLOW_UP' and r.response_code='NOT_ACCEPTED') or (w.stage='CLARIFY_REPLY' and r.response_code='AMBIGUOUS'))
              and rb.next_check_at=w.next_check_at
              and not exists(select 1 from opportunity.customer_requirement_confirmation changed where changed.tenant_id=b.tenant_id and changed.previous_confirmation_id=b.customer_confirmation_id)
              and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)
              and not exists(select 1 from opportunity.quote_response n where n.tenant_id=r.tenant_id and n.quote_issue_id=r.quote_issue_id and n.response_no>r.response_no)
            """)) {
            p.setObject(1,tenant);p.setObject(2,task);
            try(var r=p.executeQuery()) {
                if(!r.next())return null;
                var result=new Source(new Subject("opportunity.quote_response",r.getObject(1,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(2))),
                    r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getObject(5,UUID.class),
                    new Subject(r.getString(6),r.getObject(7,UUID.class),r.getLong(8),null),r.getObject(9,java.time.OffsetDateTime.class).toInstant());
                if(r.next())throw new SQLException("Ambiguous quote followup source","23000");
                return result;
            }
        }
    }
}
