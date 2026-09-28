package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Named quote exit; Contract takeover is checked by the composition before entering this owner. */
final class JdbcQuoteTermination {
    private JdbcQuoteTermination(){}
    static Subject end(Connection c,Actor actor,Subject opportunity,Subject basis,UUID workflow,UUID quote,Map<String,Object> values,Instant now,OpportunityProgressProtection protection,QuoteWorkflowService.Ports tasks)throws SQLException {
        if(!values.keySet().equals(Set.of("reasonCode","summary"))||!(values.get("reasonCode") instanceof String reason)||!Set.of("CLIENT_DECLINED","NEED_CANCELLED","OTHER").contains(reason)||!(values.get("summary") instanceof String text)||!text.equals(text.strip())||text.isBlank()||text.codePointCount(0,text.length())>1000||text.codePoints().anyMatch(ch->(Character.isISOControl(ch)&&ch!='\n'&&ch!='\t')||ch>=0xD800&&ch<=0xDFFF))throw new QuoteWorkflowService.Blocked("VALIDATION_FAILED");
        if(opportunity.revision()>=9007199254740991L)throw new QuoteWorkflowService.Blocked("STALE_SUBJECT");
        UUID tenant=actor.tenantId(),closure=id(c),termination=id(c);var fact=new Subject("opportunity.quote_termination",termination,0L,null);
        var active=tasks.activeForLead(c,tenant,opportunity);var waits=new HashMap<UUID,Subject>();
        for(var task:active){if(!Set.of("PROGRESS_OPPORTUNITY","PREPARE_QUOTE","SUBMIT_QUOTE_APPROVAL","APPROVE_QUOTE","DELIVER_QUOTE","RECORD_QUOTE_REPLY","RESOLVE_QUOTE_AUTHORITY","PREPARE_CONTRACT").contains(task.type())||!task.subject().id().equals(opportunity.id()))throw new QuoteWorkflowService.Blocked("STALE_TASK");waits.put(task.selector().id(),tasks.waitReceipt(c,tenant,task));}
        sql(c,"insert into opportunity.closure(tenant_id,closure_id,revision,opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,closed_by_appointment_id,reason_code,closure_summary_ciphertext,summary_digest,closed_at) values(?,?,0,?,?,?,?,?,?,?,?,?,?)",tenant,closure,opportunity.id(),opportunity.revision(),basis.type(),basis.id(),basis.revision(),actor.appointmentId(),reason,protection.encryptClosure(tenant,opportunity.id(),closure,text),QuoteCanonicalJson.digest(text),now);
        sql(c,"insert into opportunity.quote_termination(tenant_id,quote_termination_id,revision,opportunity_id,opportunity_revision,quote_workflow_id,quote_revision_id,closure_id,recorded_by,created_at) values(?,?,0,?,?,?,?,?,?,?)",tenant,termination,opportunity.id(),opportunity.revision(),workflow,quote,closure,actor.appointmentId(),now);
        for(var task:active){var wait=waits.get(task.selector().id());sql(c,"insert into opportunity.quote_termination_task(tenant_id,quote_termination_task_id,revision,quote_termination_id,task_id,task_revision,prior_state,wait_id,wait_hash,created_at) values(?,?,0,?,?,?,?,?,?,?)",tenant,id(c),termination,task.selector().id(),task.selector().revision(),task.state(),wait==null?null:wait.id(),wait==null?null:Base64.getUrlDecoder().decode(wait.hash()),now);tasks.cancelForTermination(c,tenant,task,fact,now);}
        if(sql(c,"update opportunity.opportunity set close_outcome_code=?,closed_at=?,revision=revision+1 where tenant_id=? and opportunity_id=? and revision=? and closed_at is null",reason,now,tenant,opportunity.id(),opportunity.revision())!=1)throw new QuoteWorkflowService.Blocked("STALE_SUBJECT");
        return fact;
    }
    private static UUID id(Connection c)throws SQLException{try(var p=c.prepareStatement("select uuidv7()")){try(var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}}}
    private static int sql(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i] instanceof Instant at?at.atOffset(ZoneOffset.UTC):values[i]);return p.executeUpdate();}}
}
